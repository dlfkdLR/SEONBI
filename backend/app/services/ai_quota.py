"""AI 호출 상한과 사용량 기록 (개발기획서 12.2, 역할 문서 5.4).

- 사용자가 직접 부르는 호출(18 recommend_options, 23 recommend_criteria, 28 extract_conditions)만 클라이언트 ID당
  하루 `daily_limit`회(기본 50)로 센다. 세 가지를 합쳐서 센다. 서버가 자동으로 부르는 phrase_question·
  summarize_options·explain_result는 세지 않는다(대신 결과를 캐시한다).
- 세는 방법: `UsageStore.count_today`가 돌려주는 ai_usage_logs 행 수. 기록은 `usage_recorder`가 AI 호출마다(제공자 시도
  하나당 한 줄, 실패 포함) 남기므로 "횟수"는 제공자 호출 시도 수다 (명세에 시도/요청 구분 없음; 가장 단순한 읽기).
- 초과 처리: 18·28은 `FlowError(429, AI_QUOTA_EXCEEDED)`, 23은 오류 대신 `check`가 False를 돌려줘 호출자가 기본 기준
  목록(`calls.template_criteria`)으로 대체한다.
- `FlowErrorCode`에 AI_QUOTA_EXCEEDED가 아직 없어 `cast`로 넘긴다(flow_errors.py 수정 필요, 보고서 참고).
"""

from __future__ import annotations

import asyncio
import logging

from app.ai.client import CALL_TYPES, LLMUsage

from .flow_errors import FlowError
from .ports import UsageRecord, UsageStore

USER_CALL_TYPES: tuple[str, ...] = ("recommend_options", "recommend_criteria", "extract_conditions")
DEFAULT_DAILY_LIMIT = 50  # AI_DAILY_QUOTA_PER_CLIENT 기본값

logger = logging.getLogger(__name__)


class QuotaGuard:
    def __init__(self, usage_store: UsageStore, daily_limit: int = DEFAULT_DAILY_LIMIT) -> None:
        self._usage = usage_store
        self.daily_limit = daily_limit

    async def check(self, client_id: str, call_type: str) -> bool:
        """호출해도 되면 True.

        - 사용자가 부르는 호출이 아니면 세지 않고 True.
        - 상한 미만이면 True.
        - 상한 이상이면: recommend_criteria는 False(호출자가 기본 기준 목록으로 대체), 나머지는 429 AI_QUOTA_EXCEEDED.
        """
        if call_type not in CALL_TYPES:
            raise ValueError(f"알 수 없는 호출 종류입니다: {call_type!r}")
        if call_type not in USER_CALL_TYPES:
            return True
        if await self._usage.count_today(client_id, USER_CALL_TYPES) < self.daily_limit:
            return True
        if call_type == "recommend_criteria":
            return False
        raise FlowError(
            429,
            "AI_QUOTA_EXCEEDED",
            "오늘 사용할 수 있는 AI 호출 횟수를 모두 사용했어요. 내일 다시 시도해 주세요.",
            {"limit": self.daily_limit},
        )


class UsageRecorder:
    """`calls.py` 함수·`LLMRouter.client_for`의 `on_usage`로 쓰는 콜백 (`UsageCallback = Callable[[LLMUsage], None]`).

    콜백은 동기 함수이고 저장은 비동기라서, 실행 중인 이벤트 루프가 있으면 저장 작업을 예약하고 없으면 보관했다가
    `flush()`에서 쓴다. 호출이 끝난 뒤 `await recorder.flush()`를 부르면 모든 기록이 저장된 것을 보장한다
    (다음 `QuotaGuard.check` 전에 부를 것). 저장 실패는 요청을 깨뜨리지 않고 로그만 남긴다.
    기록에는 사용량 숫자와 호출 종류·제공자·모델만 담는다 (프롬프트·사용자 원문 없음, 8.1).
    """

    def __init__(self, usage_store: UsageStore, client_id: str, decision_id: str | None) -> None:
        self._usage = usage_store
        self._client_id = client_id
        self._decision_id = decision_id
        self._pending: list[UsageRecord] = []
        self._tasks: set[asyncio.Task[None]] = set()

    def __call__(self, usage: LLMUsage) -> None:
        record = UsageRecord(
            client_id=self._client_id,
            decision_id=self._decision_id,
            call_type=usage.call_type,
            provider=usage.provider,
            model=usage.model,
            input_tokens=usage.input_tokens,
            output_tokens=usage.output_tokens,
            latency_ms=usage.latency_ms,
            success=usage.success,
        )
        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            self._pending.append(record)
            return
        task = loop.create_task(self._write(record))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)

    async def _write(self, record: UsageRecord) -> None:
        try:
            await self._usage.add(record)
        except Exception:
            logger.warning("AI 사용량 기록 실패", exc_info=True)

    async def flush(self) -> None:
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)
        pending, self._pending = self._pending, []
        for record in pending:
            await self._write(record)


def usage_recorder(usage_store: UsageStore, client_id: str, decision_id: str | None) -> UsageRecorder:
    """`on_usage=usage_recorder(stores.usage, client_id, decision_id)`로 넘긴다."""
    return UsageRecorder(usage_store, client_id, decision_id)

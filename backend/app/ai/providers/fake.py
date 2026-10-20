"""결정적 Fake 어댑터 (개발기획서 6.4: 테스트·로컬 기본, 키 불필요).

스키마 타입마다 고정된 유효 응답을 돌려준다. 테스트는 `script`로 응답·오류·잘못된 JSON을 순서대로 주입할 수 있다.
canned 응답은 프롬프트의 `<user_input>` 블록을 읽지 않는다 → 사용자 원문이 출력을 바꾸지 못한다.
"""

from __future__ import annotations

import re
from collections import deque
from collections.abc import Sequence
from dataclasses import asdict
from typing import Any

from pydantic import BaseModel, ValidationError

from ..client import Generated, LLMUsage, T
from ..defaults import defaults_for
from ..errors import SchemaViolationError
from ..schemas import (
    ExplainResultOutput,
    ExtractConditionsOutput,
    ExtractedConstraint,
    OptionProsCons,
    PhraseQuestionOutput,
    RecommendCriteriaOutput,
    RecommendCriterion,
    RecommendedOption,
    RecommendOptionsOutput,
    SummarizeOptionsOutput,
)

_USER_BLOCK = re.compile(r"<user_input>.*?</user_input>", re.DOTALL)
_CATEGORY = re.compile(r"카테고리: *(\w+)")
_SERVER_DATA = re.compile(r"<server_data>(.*?)</server_data>", re.DOTALL)
_OPTION_ID = re.compile(r'"optionId"\s*:\s*"([^"]+)"')

# 스크립트 한 칸: 모델 인스턴스 | dict | 원본 JSON 문자열 | 예외
ScriptItem = BaseModel | dict[str, Any] | str | Exception


def _canned(schema: type[T], prompt: str) -> BaseModel:
    visible = _USER_BLOCK.sub("", prompt)  # 사용자 원문 블록은 읽지 않는다
    if schema is ExtractConditionsOutput:
        return ExtractConditionsOutput(
            relevant=True,
            items=[
                ExtractedConstraint(type="constraint", kind="required_tag", label="Windows 지원", confidence="high")
            ],
        )
    if schema is RecommendCriteriaOutput:
        match = _CATEGORY.search(visible)
        items = defaults_for(match.group(1) if match else "etc")
        return RecommendCriteriaOutput(
            criteria=[
                RecommendCriterion.model_validate({**asdict(d), "levels": list(d.levels) if d.levels else None})
                for d in items
            ]
        )
    if schema is RecommendOptionsOutput:
        return RecommendOptionsOutput(
            options=[RecommendedOption(name=f"추천 후보 {c}", reason="목적에 맞는 후보") for c in "ABC"]
        )
    if schema is PhraseQuestionOutput:
        return PhraseQuestionOutput(
            title="두 가지 중 어느 쪽이 더 마음에 드세요?",
            option_a="첫 번째 선택지의 조건입니다",
            option_b="두 번째 선택지의 조건입니다",
            left_label="왼쪽 기준",
            right_label="오른쪽 기준",
        )
    if schema is SummarizeOptionsOutput:
        data = _SERVER_DATA.search(prompt)
        ids = list(dict.fromkeys(_OPTION_ID.findall(data.group(1) if data else "")))
        return SummarizeOptionsOutput(
            options=[
                OptionProsCons(
                    option_id=i, pros=["입력한 값 기준으로 비교해 보세요"], cons=["표의 값을 함께 확인해 보세요"]
                )
                for i in ids
            ]
        )
    if schema is ExplainResultOutput:
        return ExplainResultOutput(
            summary="입력한 기준과 답변을 바탕으로 계산한 결과입니다.",
            reasons=["중요하게 답한 기준에서 앞선 선택지가 상위에 올랐습니다."],
            caveats=["답변이 바뀌면 결과도 달라질 수 있습니다."],
        )
    raise SchemaViolationError(f"Fake 어댑터가 모르는 스키마: {schema.__name__}")


class FakeAdapter:
    """`script`가 남아 있으면 그것을 먼저 소비하고, 없으면 canned 응답을 돌려준다."""

    provider = "fake"

    def __init__(self, model: str = "default", *, script: Sequence[ScriptItem] | None = None) -> None:
        self.model = model
        self._script: deque[ScriptItem] = deque(script or ())
        self.calls: list[tuple[type[BaseModel], str, int]] = []  # 테스트 검사용: (스키마, 프롬프트, max_tokens)

    def push(self, *items: ScriptItem) -> None:
        self._script.extend(items)

    async def generate(self, schema: type[T], prompt: str, *, max_tokens: int) -> Generated[T]:
        self.calls.append((schema, prompt, max_tokens))
        item: ScriptItem | None = self._script.popleft() if self._script else None
        if isinstance(item, Exception):
            raise item
        parsed = self._to_model(schema, item, prompt)
        usage = LLMUsage(self.provider, self.model, 0, 0, 0, True, "")
        return Generated(parsed, usage)

    async def complete_structured(self, schema: type[T], prompt: str, *, max_tokens: int) -> T:
        return (await self.generate(schema, prompt, max_tokens=max_tokens)).parsed

    @staticmethod
    def _to_model(schema: type[T], item: ScriptItem | None, prompt: str) -> T:
        try:
            if item is None:
                return schema.model_validate(_canned(schema, prompt).model_dump())
            if isinstance(item, str):
                return schema.model_validate_json(item)
            if isinstance(item, BaseModel):
                if not isinstance(item, schema):
                    raise SchemaViolationError("스크립트 응답이 요청한 스키마와 다릅니다")
                return item
            return schema.model_validate(item)
        except ValidationError as exc:
            raise SchemaViolationError("스키마 검증 실패") from exc

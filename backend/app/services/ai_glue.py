"""AI 호출 함수를 질문 흐름에 꽂는 연결부 (개발기획서 6.2, 7.3).

질문 흐름(`question_flow`)은 문장 다듬기를 `Phraser` 함수로 받는다. 이 함수를 `LLMRouter`로 만든다.
AI가 실패하거나 서버 값과 다른 숫자를 쓰면 None이 되어 템플릿 문장이 그대로 쓰인다.
"""

from __future__ import annotations

from typing import Any

from app.ai.calls import LLMSource, PhrasedQuestion, phrase_question
from app.ai.client import UsageCallback

from .question_flow import Phraser


def make_phraser(source: LLMSource, *, on_usage: UsageCallback | None = None) -> Phraser:
    async def phraser(spec: dict[str, Any]) -> PhrasedQuestion | None:
        return await phrase_question(source, spec, on_usage=on_usage)

    return phraser

"""질문 흐름에 AI 문장 다듬기를 꽂는 연결부 (개발기획서 6.2)."""

import asyncio

from fakes import laptop_snapshot, make_stores

from app.ai.providers.fake import FakeAdapter
from app.ai.router import LLMRouter, LLMSettings
from app.services.ai_glue import make_phraser
from app.services.question_flow import start_questions


def _router() -> LLMRouter:
    return LLMRouter(LLMSettings.from_env({}))


def test_phraser_keeps_server_numbers_and_flow_works():
    snapshot = laptop_snapshot()
    stores = make_stores(snapshot)
    out = asyncio.run(start_questions(snapshot, stores, phraser=make_phraser(_router())))
    assert out["status"] == "QUESTIONING" and out["question"]["title"]
    assert FakeAdapter is not None


def test_failing_ai_falls_back_to_template():
    snapshot = laptop_snapshot()
    stores = make_stores(snapshot)

    async def broken(spec):
        raise RuntimeError("AI 장애")

    out = asyncio.run(start_questions(snapshot, stores, phraser=broken))
    assert out["question"]["title"]  # 템플릿 문장으로 계속 진행된다

"""AI 호출 상한(12.2)과 사용량 기록."""

import asyncio
from dataclasses import asdict
from typing import Any

import pytest
from fakes import CLIENT_ID, FakeUsageStore

from app.ai.calls import explain_result
from app.ai.client import CALL_TYPES, LLMUsage
from app.ai.router import LLMRouter, LLMSettings
from app.services.ai_quota import DEFAULT_DAILY_LIMIT, USER_CALL_TYPES, QuotaGuard, usage_recorder
from app.services.flow_errors import FlowError
from app.services.ports import UsageRecord

run = asyncio.run
OTHER = "00000000-0000-7000-8000-000000000002"


def record(call_type: str, client_id: str = CLIENT_ID, *, success: bool = True) -> UsageRecord:
    return UsageRecord(client_id, None, call_type, "fake", "m", 1, 1, 1, success)


def fill(store: FakeUsageStore, n: int, call_type: str = "extract_conditions", client_id: str = CLIENT_ID) -> None:
    for _ in range(n):
        run(store.add(record(call_type, client_id)))


def test_constants() -> None:
    assert USER_CALL_TYPES == ("recommend_options", "recommend_criteria", "extract_conditions")
    assert DEFAULT_DAILY_LIMIT == 50 and QuotaGuard(FakeUsageStore()).daily_limit == 50


@pytest.mark.parametrize("call_type", USER_CALL_TYPES)
def test_allowed_below_limit(call_type: str) -> None:
    store = FakeUsageStore()
    fill(store, 49)
    assert run(QuotaGuard(store).check(CLIENT_ID, call_type)) is True


@pytest.mark.parametrize("call_type", ["recommend_options", "extract_conditions"])
def test_18_and_28_raise_429_at_limit(call_type: str) -> None:
    store = FakeUsageStore()
    fill(store, 50)
    with pytest.raises(FlowError) as exc:
        run(QuotaGuard(store).check(CLIENT_ID, call_type))
    assert (exc.value.http_status, exc.value.code) == (429, "AI_QUOTA_EXCEEDED")
    assert exc.value.to_body()["error"]["details"] == {"limit": 50}


def test_23_returns_fallback_signal_at_limit() -> None:
    store = FakeUsageStore()
    fill(store, 50, "recommend_options")  # 세 가지를 합쳐서 센다
    assert run(QuotaGuard(store).check(CLIENT_ID, "recommend_criteria")) is False


def test_counts_only_user_triggered_calls() -> None:
    store = FakeUsageStore()
    for t in ("phrase_question", "summarize_options", "explain_result"):
        fill(store, 30, t)
    guard = QuotaGuard(store)
    assert run(guard.check(CLIENT_ID, "extract_conditions")) is True  # 자동 호출 90건은 세지 않는다
    fill(store, 50)
    for t in ("phrase_question", "summarize_options", "explain_result"):
        assert run(guard.check(CLIENT_ID, t)) is True  # 자동 호출은 상한에 막히지 않는다


def test_limit_is_per_client_and_configurable() -> None:
    store = FakeUsageStore()
    fill(store, 3)
    assert run(QuotaGuard(store, daily_limit=3).check(OTHER, "extract_conditions")) is True
    with pytest.raises(FlowError):
        run(QuotaGuard(store, daily_limit=3).check(CLIENT_ID, "recommend_options"))


def test_unknown_call_type_rejected() -> None:
    with pytest.raises(ValueError):
        run(QuotaGuard(FakeUsageStore()).check(CLIENT_ID, "nope"))
    assert set(USER_CALL_TYPES) <= set(CALL_TYPES)


def test_recorder_converts_usage_without_prompt_text() -> None:
    async def go() -> list[UsageRecord]:
        store = FakeUsageStore()
        rec = usage_recorder(store, CLIENT_ID, "d1")
        rec(LLMUsage("gemini", "flash", 120, 30, 456, True, "extract_conditions"))
        rec(LLMUsage("gemini", "flash", 0, 0, 12, False, "extract_conditions"))
        await rec.flush()
        return store.records

    records = run(go())
    assert records[0] == UsageRecord(CLIENT_ID, "d1", "extract_conditions", "gemini", "flash", 120, 30, 456, True)
    assert records[1].success is False and len(records) == 2
    assert set(asdict(records[0])) == {
        "client_id",
        "decision_id",
        "call_type",
        "provider",
        "model",
        "input_tokens",
        "output_tokens",
        "latency_ms",
        "success",
    }


def test_recorder_works_as_on_usage_of_ai_calls_and_feeds_the_guard() -> None:
    secret = "내 비밀 사용자 입력 문장"

    async def go() -> tuple[FakeUsageStore, Any]:
        store = FakeUsageStore()
        rec = usage_recorder(store, CLIENT_ID, None)
        router = LLMRouter(LLMSettings())
        out = await explain_result(router, {"note": secret, "recommendation": {"optionId": "o1"}}, on_usage=rec)
        await rec.flush()
        return store, out

    store, out = run(go())
    assert out is not None and len(store.records) == 1
    assert store.records[0].call_type == "explain_result" and store.records[0].provider == "fake"
    assert secret not in repr(store.records)
    # 자동 호출(explain_result)로 쌓인 기록은 사용자 상한에 세지 않는다
    assert run(store.count_today(CLIENT_ID, USER_CALL_TYPES)) == 0


def test_recorder_without_running_loop_buffers_until_flush() -> None:
    store = FakeUsageStore()
    rec = usage_recorder(store, CLIENT_ID, "d1")
    rec(LLMUsage("fake", "m", 1, 2, 3, True, "recommend_options"))  # 이벤트 루프 밖
    assert store.records == []
    run(rec.flush())
    assert [r.call_type for r in store.records] == ["recommend_options"]
    run(rec.flush())
    assert len(store.records) == 1  # 두 번 쓰지 않는다


def test_recorder_store_failure_does_not_raise() -> None:
    class Broken(FakeUsageStore):
        async def add(self, record: UsageRecord) -> None:
            raise RuntimeError("db down")

    async def go() -> None:
        rec = usage_recorder(Broken(), CLIENT_ID, None)
        rec(LLMUsage("fake", "m", 1, 2, 3, True, "recommend_options"))
        await rec.flush()

    run(go())

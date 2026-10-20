"""분석 API 39~46번 HTTP 테스트: 질문·답변 라우터로 가상 사용자를 끝까지 돌린 뒤 각 엔드포인트를 검사한다."""

import sys
from collections.abc import Callable, Iterator
from pathlib import Path
from typing import Annotated, Any

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "services"))

import numpy as np  # noqa: E402
import pytest  # noqa: E402
from fakes import CLIENT_ID, FakeStores, laptop_snapshot, make_stores  # noqa: E402
from fastapi import Depends, FastAPI  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from pydantic import BaseModel  # noqa: E402

from app.ai.providers.fake import FakeAdapter  # noqa: E402
from app.ai.schemas import ExplainResultOutput, OptionProsCons, SummarizeOptionsOutput  # noqa: E402
from app.engine.likelihood import response_logprobs, response_options  # noqa: E402
from app.routers import a_deps, analysis, answers, questions  # noqa: E402
from app.schemas.analysis import (  # noqa: E402
    AnalysisResponse,
    ChangeConditionsResponse,
    RankingsResponse,
    ScoresResponse,
    SensitivityResponse,
    SimulationResponse,
    StabilityResponse,
    WeightsResponse,
)
from app.services.engine_bridge import spec_from_dict  # noqa: E402
from app.services.finalize import finalize_decision  # noqa: E402
from app.services.flow_errors import not_found  # noqa: E402
from app.services.ports import DecisionSnapshot  # noqa: E402
from app.services.state_rules import Status  # noqa: E402

BASE = "/api/v1/decisions/d-laptop"
HEADERS = {"X-Client-Id": CLIENT_ID}

GETS: dict[str, tuple[str, type[BaseModel]]] = {
    "39": ("analysis", AnalysisResponse),
    "40": ("weights", WeightsResponse),
    "41": ("rankings", RankingsResponse),
    "42": ("scores", ScoresResponse),
    "43": ("sensitivity", SensitivityResponse),
    "44": ("stability", StabilityResponse),
    "45": ("change-conditions", ChangeConditionsResponse),
}


def build_app(stores: FakeStores, llm: Any | None = None) -> FastAPI:
    app = FastAPI()
    a_deps.register_a_error_handlers(app)
    for r in (questions.router, answers.router, analysis.router):
        app.include_router(r, prefix="/api/v1")

    async def snapshot(decision_id: str, client_id: Annotated[str, Depends(a_deps.get_client_id)]) -> DecisionSnapshot:
        snap = stores.snapshot()
        if decision_id != snap.id or client_id != snap.client_id:
            raise not_found("결정을 찾을 수 없습니다.")
        return snap

    async def get_stores() -> FakeStores:
        return stores

    async def get_llm() -> Any | None:
        return llm

    app.dependency_overrides[a_deps.get_snapshot] = snapshot
    app.dependency_overrides[a_deps.get_stores] = get_stores
    app.dependency_overrides[a_deps.get_llm] = get_llm
    return app


@pytest.fixture
def make() -> Iterator[Callable[..., tuple[TestClient, FakeStores]]]:
    clients: list[TestClient] = []

    def factory(status: Status = Status.DRAFT, llm: Any | None = None) -> tuple[TestClient, FakeStores]:
        stores = make_stores(laptop_snapshot(status))
        client = TestClient(build_app(stores, llm))
        clients.append(client)
        return client, stores

    yield factory
    for c in clients:
        c.close()


def virtual_answer(stores: FakeStores, rng: np.random.Generator, qobj: dict[str, Any]) -> dict[str, Any]:
    row = next(q for q in stores.questions.questions if q.id == qobj["id"])
    spec = spec_from_dict(row.spec, [c.id for c in stores.snapshot().criteria])
    probs = np.exp(response_logprobs(spec, np.array([[0.2, 0.3, 0.5]])))[0]
    r = response_options(spec)[int(rng.choice(len(probs), p=probs / probs.sum()))]
    return {"value": r} if row.type == "slider_pair" else {"choice": r}


def play(client: TestClient, stores: FakeStores, max_answers: int | None = None) -> int:
    """질문 시작 후 가상 사용자가 답한다. max_answers가 없으면 분석 가능 상태(ANALYZED)까지."""
    rng = np.random.default_rng(1)
    r = client.post(f"{BASE}/questions/start", headers=HEADERS)
    assert r.status_code == 200, r.text
    nxt: dict[str, Any] = {"done": False, "question": r.json()["question"]}
    count = 0
    while not nxt["done"] and (max_answers is None or count < max_answers):
        r = client.post(
            f"{BASE}/answers",
            headers=HEADERS,
            json={"questionId": nxt["question"]["id"], "response": virtual_answer(stores, rng, nxt["question"])},
        )
        assert r.status_code == 201, r.text
        nxt = r.json()["next"]
        count += 1
    return count


def analyzed(make: Callable[..., tuple[TestClient, FakeStores]], llm: Any | None = None):
    client, stores = make(llm=llm)
    play(client, stores)
    assert stores.snapshot().status is Status.ANALYZED
    return client, stores


def error_body(r: Any, status: int, code: str) -> dict[str, Any]:
    assert r.status_code == status, r.text
    body = dict(r.json())
    assert set(body) == {"error"} and body["error"]["code"] == code
    assert isinstance(body["error"]["message"], str) and body["error"]["message"]
    return dict(body["error"])


def weights_body(stores: FakeStores, values: list[float]) -> dict[str, Any]:
    ids = [c.id for c in stores.snapshot().criteria]
    return {"weights": [{"criterionId": c, "weight": v} for c, v in zip(ids, values, strict=True)]}


def ai_stub(snapshot: DecisionSnapshot) -> FakeAdapter:
    ad = FakeAdapter()
    ad.push(
        SummarizeOptionsOutput(
            options=[OptionProsCons(option_id=o.id, pros=["AI 장점"], cons=["AI 단점"]) for o in snapshot.options]
        ),
        ExplainResultOutput(summary="입력한 값으로 계산한 결과입니다.", reasons=["근거"], caveats=[]),
    )
    return ad


# ------------------------------------------------------------------ 해피 패스
@pytest.mark.parametrize("api", list(GETS))
def test_get_endpoints_analyzed_validate(make, api):
    client, _ = analyzed(make)
    path, model = GETS[api]
    r = client.get(f"{BASE}/{path}", headers=HEADERS)
    assert r.status_code == 200, r.text
    model.model_validate(r.json())
    if api == "39":
        assert r.json()["provisional"] is False
        assert r.json()["explanation"]["source"] == "template"


def test_numbers_consistent_between_39_and_parts(make):
    client, _ = analyzed(make)
    full = client.get(f"{BASE}/analysis", headers=HEADERS).json()
    assert client.get(f"{BASE}/rankings", headers=HEADERS).json()["items"] == full["rankings"]
    assert client.get(f"{BASE}/weights", headers=HEADERS).json()["items"] == full["weights"]


def test_simulate_valid_is_200_and_does_not_change_state(make):
    client, stores = analyzed(make)
    version = stores.snapshot().version
    r = client.post(f"{BASE}/simulations", headers=HEADERS, json=weights_body(stores, [1, 1, 8]))
    assert r.status_code == 200, r.text
    SimulationResponse.model_validate(r.json())
    assert set(r.json()) == {"normalizedWeights", "rankings", "changedFromCurrent"}
    assert abs(sum(w["weight"] for w in r.json()["normalizedWeights"]) - 1) < 1e-3
    assert stores.snapshot().version == version


# ------------------------------------------------------------------ 상태 표
@pytest.mark.parametrize("api", [*GETS, "46"])
def test_draft_is_422_not_ready(make, api):
    client, stores = make()
    if api == "46":
        r = client.post(f"{BASE}/simulations", headers=HEADERS, json=weights_body(stores, [1, 1, 1]))
    else:
        r = client.get(f"{BASE}/{GETS[api][0]}", headers=HEADERS)
    error_body(r, 422, "NOT_READY")


@pytest.mark.parametrize("api", [*GETS, "46"])
def test_questioning_without_answers_is_422(make, api):
    client, stores = make()
    assert client.post(f"{BASE}/questions/start", headers=HEADERS).status_code == 200
    assert stores.snapshot().status is Status.QUESTIONING
    if api == "46":
        r = client.post(f"{BASE}/simulations", headers=HEADERS, json=weights_body(stores, [1, 1, 1]))
    else:
        r = client.get(f"{BASE}/{GETS[api][0]}", headers=HEADERS)
    error_body(r, 422, "NOT_READY")


def test_questioning_with_answers_is_provisional(make):
    client, stores = make()
    assert play(client, stores, max_answers=2) == 2
    assert stores.snapshot().status is Status.QUESTIONING
    r = client.get(f"{BASE}/analysis", headers=HEADERS)
    assert r.status_code == 200, r.text
    assert AnalysisResponse.model_validate(r.json()).provisional is True
    for api, (path, model) in GETS.items():
        r = client.get(f"{BASE}/{path}", headers=HEADERS)
        assert r.status_code == 200, (api, r.text)
        model.model_validate(r.json())
    r = client.post(f"{BASE}/simulations", headers=HEADERS, json=weights_body(stores, [1, 1, 1]))
    assert r.status_code == 200, r.text


def test_finalized_still_readable_and_returns_snapshot(make):
    import asyncio

    client, stores = analyzed(make)
    before = client.get(f"{BASE}/analysis", headers=HEADERS).json()
    option_id = before["recommendation"]["optionId"]
    asyncio.run(finalize_decision(stores.snapshot(), stores, option_id, None))
    assert stores.snapshot().status is Status.FINALIZED
    r = client.get(f"{BASE}/analysis", headers=HEADERS)
    assert r.status_code == 200, r.text
    after = AnalysisResponse.model_validate(r.json())
    assert after.recommendation.option_id == option_id and after.provisional is False
    for api, (path, model) in GETS.items():
        r = client.get(f"{BASE}/{path}", headers=HEADERS)
        assert r.status_code == 200, (api, r.text)
        model.model_validate(r.json())


# ------------------------------------------------------------------ 43번
def test_sensitivity_with_and_without_criterion_id(make):
    client, stores = analyzed(make)
    ids = [c.id for c in stores.snapshot().criteria]
    all_items = client.get(f"{BASE}/sensitivity", headers=HEADERS).json()["items"]
    assert [i["criterionId"] for i in all_items] == ids
    r = client.get(f"{BASE}/sensitivity", params={"criterionId": ids[1]}, headers=HEADERS)
    assert r.status_code == 200, r.text
    one = SensitivityResponse.model_validate(r.json())
    assert [i.criterion_id for i in one.items] == [ids[1]]
    assert r.json()["items"][0] == all_items[1]


def test_sensitivity_unknown_criterion_is_400(make):
    client, _ = analyzed(make)
    r = client.get(f"{BASE}/sensitivity", params={"criterionId": "nope"}, headers=HEADERS)
    error_body(r, 400, "VALIDATION_ERROR")


# ------------------------------------------------------------------ 46번 검증
@pytest.mark.parametrize(
    "mutate",
    [
        pytest.param(lambda b: b["weights"].pop(), id="missing-criterion"),
        pytest.param(lambda b: b["weights"][0].update(weight=-1), id="negative"),
        pytest.param(lambda b: [w.update(weight=0) for w in b["weights"]], id="all-zero"),
        pytest.param(lambda b: b["weights"][0].update(criterionId="nope"), id="unknown-id"),
        pytest.param(lambda b: b.update(extra=1), id="extra-field"),
        pytest.param(lambda b: b["weights"][0].update(weight="abc"), id="non-number"),
        pytest.param(lambda b: b.pop("weights"), id="no-weights"),
    ],
)
def test_simulate_invalid_bodies_are_400(make, mutate):
    client, stores = analyzed(make)
    body = weights_body(stores, [1, 2, 3])
    mutate(body)
    error_body(client.post(f"{BASE}/simulations", headers=HEADERS, json=body), 400, "VALIDATION_ERROR")


# ------------------------------------------------------------------ 헤더·존재 여부
@pytest.mark.parametrize("headers", [{}, {"X-Client-Id": "not-a-uuid"}, {"X-Client-Id": " "}])
def test_missing_or_invalid_client_id(make, headers):
    client, _ = analyzed(make)
    for path in ("analysis", "weights", "rankings", "scores", "sensitivity", "stability", "change-conditions"):
        error_body(client.get(f"{BASE}/{path}", headers=headers), 400, "CLIENT_ID_REQUIRED")
    r = client.post(f"{BASE}/simulations", headers=headers, json={"weights": []})
    error_body(r, 400, "CLIENT_ID_REQUIRED")


def test_other_client_or_unknown_decision_is_404(make):
    client, _ = analyzed(make)
    other = {"X-Client-Id": "00000000-0000-4000-8000-000000000000"}
    error_body(client.get(f"{BASE}/analysis", headers=other), 404, "NOT_FOUND")
    error_body(client.get("/api/v1/decisions/zzz/analysis", headers=HEADERS), 404, "NOT_FOUND")


# ------------------------------------------------------------------ OpenAPI
def test_openapi_has_eight_operations(make):
    client, _ = make()
    spec = client.app.openapi()
    expected = {
        ("get", "/api/v1/decisions/{decision_id}/analysis"): "getAnalysis",
        ("get", "/api/v1/decisions/{decision_id}/weights"): "getWeights",
        ("get", "/api/v1/decisions/{decision_id}/rankings"): "getRankings",
        ("get", "/api/v1/decisions/{decision_id}/scores"): "getScores",
        ("get", "/api/v1/decisions/{decision_id}/sensitivity"): "getSensitivity",
        ("get", "/api/v1/decisions/{decision_id}/stability"): "getStability",
        ("get", "/api/v1/decisions/{decision_id}/change-conditions"): "getChangeConditions",
        ("post", "/api/v1/decisions/{decision_id}/simulations"): "simulate",
    }
    for (method, path), op_id in expected.items():
        assert spec["paths"][path][method]["operationId"] == op_id
    sens = spec["paths"]["/api/v1/decisions/{decision_id}/sensitivity"]["get"]
    assert [p["name"] for p in sens["parameters"] if p["in"] == "query"] == ["criterionId"]
    assert "200" in spec["paths"]["/api/v1/decisions/{decision_id}/simulations"]["post"]["responses"]


# ------------------------------------------------------------------ AI
def test_analysis_calls_ai_once_per_version(make):
    probe, probe_stores = make()
    llm = ai_stub(probe_stores.snapshot())
    client, _ = analyzed(make, llm=llm)
    first = client.get(f"{BASE}/analysis", headers=HEADERS)
    assert first.status_code == 200, first.text
    calls = len(llm.calls)
    assert calls == 2 and first.json()["explanation"]["source"] == "ai"
    second = client.get(f"{BASE}/analysis", headers=HEADERS)
    assert second.json() == first.json() and len(llm.calls) == calls
    for path in ("weights", "rankings", "stability", "change-conditions"):
        assert client.get(f"{BASE}/{path}", headers=HEADERS).status_code == 200
    assert len(llm.calls) == calls

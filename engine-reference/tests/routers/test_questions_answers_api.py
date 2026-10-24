"""질문·답변 API 31~38번 HTTP 테스트: 작은 FastAPI 앱에 라우터를 `/api/v1`로 붙이고 메모리 저장소로 돌린다."""

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
from pydantic import TypeAdapter  # noqa: E402

from app.engine.likelihood import response_logprobs, response_options  # noqa: E402
from app.routers import a_deps, answers, questions  # noqa: E402
from app.schemas.answers import EditAnswerResponse, SubmitAnswerResponse  # noqa: E402
from app.schemas.questions import (  # noqa: E402
    AnswerListResponse,
    NextQuestionResponse,
    ProgressResponse,
    StartQuestionsResponse,
    StatusResponse,
    StopResponse,
)
from app.services.engine_bridge import spec_from_dict  # noqa: E402
from app.services.flow_errors import FlowError, not_found  # noqa: E402
from app.services.ports import DecisionSnapshot  # noqa: E402
from app.services.state_rules import Status  # noqa: E402

BASE = "/api/v1/decisions/d-laptop"
HEADERS = {"X-Client-Id": CLIENT_ID}
NEXT = TypeAdapter(NextQuestionResponse)


def build_app(stores: FakeStores) -> FastAPI:
    app = FastAPI()
    a_deps.register_a_error_handlers(app)
    app.include_router(questions.router, prefix="/api/v1")
    app.include_router(answers.router, prefix="/api/v1")

    async def snapshot(decision_id: str, client_id: Annotated[str, Depends(a_deps.get_client_id)]) -> DecisionSnapshot:
        snap = stores.snapshot()
        if decision_id != snap.id or client_id != snap.client_id:
            raise not_found("결정을 찾을 수 없습니다.")
        return snap

    async def get_stores() -> FakeStores:
        return stores

    app.dependency_overrides[a_deps.get_snapshot] = snapshot
    app.dependency_overrides[a_deps.get_stores] = get_stores
    return app


@pytest.fixture
def make() -> Iterator[Callable[..., tuple[TestClient, FakeStores]]]:
    clients: list[TestClient] = []

    def factory(status: Status = Status.DRAFT) -> tuple[TestClient, FakeStores]:
        stores = make_stores(laptop_snapshot(status))
        client = TestClient(build_app(stores))
        clients.append(client)
        return client, stores

    yield factory
    for c in clients:
        c.close()


def virtual_answer(stores: FakeStores, rng: np.random.Generator, qobj: dict[str, Any]) -> dict[str, Any]:
    """우도 모델대로 답하는 가상 사용자 (정답 가중치 [0.2, 0.3, 0.5])."""
    row = next(q for q in stores.questions.questions if q.id == qobj["id"])
    spec = spec_from_dict(row.spec, [c.id for c in stores.snapshot().criteria])
    probs = np.exp(response_logprobs(spec, np.array([[0.2, 0.3, 0.5]])))[0]
    r = response_options(spec)[int(rng.choice(len(probs), p=probs / probs.sum()))]
    return {"value": r} if row.type == "slider_pair" else {"choice": r}


def start(client: TestClient) -> dict[str, Any]:
    r = client.post(f"{BASE}/questions/start", headers=HEADERS)
    assert r.status_code == 200, r.text
    StartQuestionsResponse.model_validate(r.json())
    return dict(r.json())


def error_body(r: Any, status: int, code: str) -> dict[str, Any]:
    assert r.status_code == status, r.text
    body = dict(r.json())
    assert set(body) == {"error"} and body["error"]["code"] == code
    assert isinstance(body["error"]["message"], str) and body["error"]["message"]
    return dict(body["error"])


# ------------------------------------------------------------------ 해피 패스
def test_full_flow_to_analyzed(make):
    client, stores = make()
    rng = np.random.default_rng(1)
    started = start(client)
    assert started["status"] == "QUESTIONING" and started["round"] == "initial"
    assert started["progress"]["answered"] == 0 and started["question"]["seq"] == 1

    nxt: dict[str, Any] = {"done": False, "question": started["question"]}
    count = 0
    for _ in range(40):
        if nxt["done"]:
            break
        r = client.post(
            f"{BASE}/answers",
            headers=HEADERS,
            json={"questionId": nxt["question"]["id"], "response": virtual_answer(stores, rng, nxt["question"])},
        )
        assert r.status_code == 201, r.text
        SubmitAnswerResponse.model_validate(r.json())
        assert set(r.json()) == {"answer", "estimate", "next"}
        nxt = r.json()["next"]
        count += 1
    assert nxt["done"] is True and nxt["reason"] in ("CONFIDENT", "LOW_INFO_GAIN", "MAX_QUESTIONS")
    assert stores.snapshot().status is Status.ANALYZED

    # 종료 뒤 32번은 항상 done, 33·34는 읽기
    r = client.get(f"{BASE}/questions/next", headers=HEADERS)
    assert r.status_code == 200 and r.json()["done"] is True
    NEXT.validate_python(r.json())
    r = client.get(f"{BASE}/answers", headers=HEADERS)
    assert len(r.json()["items"]) == count


def test_start_is_idempotent_and_next_repeats(make):
    client, _ = make()
    first = start(client)
    again = start(client)
    assert again["question"] == first["question"] and again["round"] == "initial"
    r1 = client.get(f"{BASE}/questions/next", headers=HEADERS)
    r2 = client.get(f"{BASE}/questions/next", headers=HEADERS)
    assert r1.status_code == 200 and r1.json() == r2.json()
    NEXT.validate_python(r1.json())
    assert r1.json() == {"done": False, "question": first["question"]}


def test_status_progress_payloads(make):
    client, _ = make()
    started = start(client)
    r = client.get(f"{BASE}/questions/status", headers=HEADERS)
    assert r.status_code == 200
    StatusResponse.model_validate(r.json())
    body = r.json()
    assert set(body) == {"shouldStop", "reason", "metrics", "remainingUncertainty"}
    assert set(body["metrics"]) == {"answered", "topOption", "topProbability", "decisionEntropy", "bestNextInfoGain"}
    assert body["metrics"]["answered"] == 0 and body["shouldStop"] is False and body["reason"] is None

    r = client.get(f"{BASE}/questions/progress", headers=HEADERS)
    assert r.status_code == 200
    ProgressResponse.model_validate(r.json())
    prog = r.json()
    assert set(prog) == {"answered", "expectedRange", "confidence"}
    assert prog["expectedRange"] == started["progress"]["expectedRange"] and prog["answered"] == 0


def test_stop_and_list_answers_with_origin_and_edit(make):
    client, stores = make()
    rng = np.random.default_rng(3)
    q = start(client)["question"]
    sub = client.post(
        f"{BASE}/answers",
        headers=HEADERS,
        json={"questionId": q["id"], "response": virtual_answer(stores, rng, q)},
    )
    assert sub.status_code == 201
    answer_id = sub.json()["answer"]["id"]

    r = client.get(f"{BASE}/answers", headers=HEADERS)
    assert r.status_code == 200
    AnswerListResponse.model_validate(r.json())
    item = r.json()["items"][0]
    assert set(item) == {"id", "questionId", "question", "response", "isValid", "createdAt"}
    assert item["question"]["origin"] == "engine" and item["isValid"] is True and item["questionId"] == q["id"]

    # 38: 슬라이더 질문의 답을 바꾼다 (첫 질문은 slider_pair)
    assert q["type"] == "slider_pair"
    r = client.patch(f"{BASE}/answers/{answer_id}", headers=HEADERS, json={"response": {"value": 4}})
    assert r.status_code == 200, r.text
    EditAnswerResponse.model_validate(r.json())
    assert set(r.json()) == {"estimate"}
    assert client.get(f"{BASE}/answers", headers=HEADERS).json()["items"][0]["response"] == {"value": 4}
    assert stores.snapshot().status is Status.QUESTIONING

    # 35
    r = client.post(f"{BASE}/questions/stop", headers=HEADERS)
    assert r.status_code == 200
    StopResponse.model_validate(r.json())
    assert r.json()["reason"] == "USER_STOPPED" and isinstance(r.json()["lowConfidence"], bool)
    assert stores.snapshot().status is Status.ANALYZED

    # ANALYZED에서도 36은 되고, 31은 추가 라운드
    assert client.get(f"{BASE}/answers", headers=HEADERS).status_code == 200
    r = client.post(f"{BASE}/questions/start", headers=HEADERS)
    assert r.status_code == 200 and r.json()["round"] == "extra"
    StartQuestionsResponse.model_validate(r.json())


# ------------------------------------------------------------------ 오류
def test_client_id_missing_or_invalid(make):
    client, _ = make()
    for headers in ({}, {"X-Client-Id": "not-a-uuid"}, {"X-Client-Id": ""}):
        r = client.post(f"{BASE}/questions/start", headers=headers)
        err = error_body(r, 400, "CLIENT_ID_REQUIRED")
        assert "details" not in err
    # 읽기 요청도 똑같이
    error_body(client.get(f"{BASE}/answers"), 400, "CLIENT_ID_REQUIRED")


def test_other_clients_decision_is_404(make):
    client, _ = make()
    other = {"X-Client-Id": "00000000-0000-7000-8000-0000000000ff"}
    error_body(client.get(f"{BASE}/questions/next", headers=other), 404, "NOT_FOUND")

    # get_snapshot이 FlowError 404를 던지는 B의 구현을 흉내 낸다
    async def deny(decision_id: str, client_id: Annotated[str, Depends(a_deps.get_client_id)]) -> DecisionSnapshot:
        raise FlowError(404, "NOT_FOUND", "결정을 찾을 수 없습니다.")

    client.app.dependency_overrides[a_deps.get_snapshot] = deny  # type: ignore[attr-defined]
    error_body(client.get(f"{BASE}/answers", headers=HEADERS), 404, "NOT_FOUND")


def test_draft_calls_follow_state_table(make):
    """4.2 표: DRAFT에서 32·33·34는 422 NOT_READY, 35·37은 409 INVALID_STATE."""
    client, _ = make()
    for path in ("questions/next", "questions/status", "questions/progress"):
        error_body(client.get(f"{BASE}/{path}", headers=HEADERS), 422, "NOT_READY")
    error_body(client.post(f"{BASE}/questions/stop", headers=HEADERS), 409, "INVALID_STATE")
    r = client.post(f"{BASE}/answers", headers=HEADERS, json={"questionId": "q1", "response": {"skip": True}})
    error_body(r, 409, "INVALID_STATE")


def test_start_with_missing_is_not_ready_with_details(make):
    client, stores = make()

    async def missing() -> list[str]:
        return ["VALUES_INCOMPLETE"]

    client.app.dependency_overrides[a_deps.get_readiness_missing] = missing  # type: ignore[attr-defined]
    err = error_body(client.post(f"{BASE}/questions/start", headers=HEADERS), 422, "NOT_READY")
    assert err["details"] == {"missing": ["VALUES_INCOMPLETE"]}
    assert stores.snapshot().status is Status.DRAFT


def test_duplicate_answer_is_409(make):
    client, _ = make()
    q = start(client)["question"]
    body = {"questionId": q["id"], "response": {"value": 1}}
    assert client.post(f"{BASE}/answers", headers=HEADERS, json=body).status_code == 201
    err = error_body(client.post(f"{BASE}/answers", headers=HEADERS, json=body), 409, "QUESTION_ALREADY_ANSWERED")
    assert err["details"] == {"questionId": q["id"]}


def test_bad_bodies_are_400_validation_error(make):
    client, _ = make()
    q = start(client)["question"]
    bad_bodies: list[Any] = [
        {"questionId": q["id"], "response": {"choice": "A", "value": 1}},  # 둘 이상
        {"questionId": q["id"], "response": {"choice": "A", "skip": True}},
        {"questionId": q["id"], "response": {}},  # 아무것도 없음
        {"questionId": q["id"], "response": {"value": 6}},  # 범위 밖
        {"questionId": q["id"], "response": {"choice": "C"}},
        {"response": {"skip": True}},  # questionId 없음
    ]
    for body in bad_bodies:
        err = error_body(client.post(f"{BASE}/answers", headers=HEADERS, json=body), 400, "VALIDATION_ERROR")
        assert err["details"]["fields"] and all({"field", "message"} <= set(f) for f in err["details"]["fields"])
    error_body(
        client.patch(f"{BASE}/answers/whatever", headers=HEADERS, json={"response": {"value": 6}}),
        400,
        "VALIDATION_ERROR",
    )
    # 질문 유형과 응답 형식 불일치 (slider 질문에 choice)
    err = error_body(
        client.post(f"{BASE}/answers", headers=HEADERS, json={"questionId": q["id"], "response": {"choice": "A"}}),
        400,
        "VALIDATION_ERROR",
    )
    assert err["message"]


def test_unknown_question_and_answer_are_404(make):
    client, _ = make()
    start(client)
    r = client.post(f"{BASE}/answers", headers=HEADERS, json={"questionId": "nope", "response": {"skip": True}})
    error_body(r, 404, "NOT_FOUND")
    r = client.patch(f"{BASE}/answers/nope", headers=HEADERS, json={"response": {"skip": True}})
    error_body(r, 404, "NOT_FOUND")


def test_finalized_is_409(make):
    client, _ = make(Status.FINALIZED)
    err = error_body(client.post(f"{BASE}/questions/start", headers=HEADERS), 409, "DECISION_FINALIZED")
    assert err["message"]
    error_body(client.post(f"{BASE}/questions/stop", headers=HEADERS), 409, "DECISION_FINALIZED")
    r = client.post(f"{BASE}/answers", headers=HEADERS, json={"questionId": "q1", "response": {"skip": True}})
    error_body(r, 409, "DECISION_FINALIZED")
    r = client.patch(f"{BASE}/answers/a1", headers=HEADERS, json={"response": {"skip": True}})
    error_body(r, 409, "DECISION_FINALIZED")
    # 32는 FINALIZED에서도 항상 done, 36은 읽기
    r = client.get(f"{BASE}/questions/next", headers=HEADERS)
    assert r.status_code == 200 and r.json()["done"] is True
    assert client.get(f"{BASE}/answers", headers=HEADERS).json() == {"items": []}


# ------------------------------------------------------------------ OpenAPI
def test_openapi_has_eight_operations(make):
    client, _ = make()
    schema = client.app.openapi()  # type: ignore[attr-defined]
    p = "/api/v1/decisions/{decision_id}"
    expected = {
        (f"{p}/questions/start", "post"): ("startQuestions", "31", "200"),
        (f"{p}/questions/next", "get"): ("getNextQuestion", "32", "200"),
        (f"{p}/questions/status", "get"): ("getQuestionStatus", "33", "200"),
        (f"{p}/questions/progress", "get"): ("getQuestionProgress", "34", "200"),
        (f"{p}/questions/stop", "post"): ("stopQuestions", "35", "200"),
        (f"{p}/answers", "get"): ("listAnswers", "36", "200"),
        (f"{p}/answers", "post"): ("submitAnswer", "37", "201"),
        (f"{p}/answers/{{answer_id}}", "patch"): ("editAnswer", "38", "200"),
    }
    for (path, method), (op_id, number, code) in expected.items():
        op = schema["paths"][path][method]
        assert op["operationId"] == op_id
        assert op["summary"].startswith(f"{number}. ")
        assert code in op["responses"]
    assert sum(len(v) for v in schema["paths"].values()) == 8

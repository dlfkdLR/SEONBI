"""질문 흐름 서비스 31~38번: 메모리 가짜 저장소로 처음부터 끝까지 돌린다 (가상 사용자는 우도 모델대로 답한다)."""

import asyncio
from dataclasses import replace
from typing import Any

import numpy as np
import pytest
from fakes import FakeStores, career_snapshot, laptop_snapshot, make_stores

from app.ai.calls import PhrasedQuestion
from app.engine.likelihood import ScenarioSpec, response_logprobs, response_options
from app.engine.stopping import extra_round_limit, max_questions
from app.services import question_flow as qf
from app.services.engine_bridge import spec_from_dict
from app.services.flow_errors import FlowError, from_violation, validation_error
from app.services.ports import CriterionRow, ValueRow
from app.services.state_rules import Status, Violation

run = asyncio.run

D, Q, A, F = Status.DRAFT, Status.QUESTIONING, Status.ANALYZED, Status.FINALIZED


# ------------------------------------------------------------------ 도우미
class User:
    """정답 가중치를 가진 가상 사용자. 우도 모델에서 응답을 뽑는다(시드 고정)."""

    def __init__(self, w: list[float], seed: int = 0, *, always_skip: bool = False) -> None:
        self.w = np.array(w)
        self.rng = np.random.default_rng(seed)
        self.always_skip = always_skip

    def respond(self, stores: FakeStores, qobj: dict[str, Any]) -> dict[str, Any]:
        if self.always_skip:
            return {"skip": True}
        row = next(q for q in stores.questions.questions if q.id == qobj["id"])
        spec = spec_from_dict(row.spec, [c.id for c in stores.snapshot().criteria])
        probs = np.exp(response_logprobs(spec, self.w[None, :]))[0]
        r = response_options(spec)[int(self.rng.choice(len(probs), p=probs / probs.sum()))]
        return {"value": r} if row.type == "slider_pair" else {"choice": r}


async def play(stores: FakeStores, user: User, nxt: dict[str, Any], *, phraser: Any = None) -> dict[str, Any]:
    """done이 될 때까지 답한다. 마지막 `next`(done=true)를 돌려준다."""
    for _ in range(40):
        if nxt["done"]:
            return nxt
        out = await qf.submit_answer(
            stores.snapshot(), stores, nxt["question"]["id"], user.respond(stores, nxt["question"]), phraser=phraser
        )
        nxt = out["next"]
    raise AssertionError("질문이 끝나지 않는다")


async def start_and_play(stores: FakeStores, user: User) -> dict[str, Any]:
    started = await qf.start_questions(stores.snapshot(), stores)
    return await play(stores, user, {"done": False, "question": started["question"]})


def engine_questions(stores: FakeStores) -> list[Any]:
    return [q for q in stores.questions.questions if q.origin == "engine"]


def fixed(stores: FakeStores, qobj: dict[str, Any]) -> dict[str, Any]:
    return {"value": -2} if qobj["type"] == "slider_pair" else {"choice": "A"}


async def answer_n(stores: FakeStores, n: int) -> dict[str, Any]:
    """n개 답하고 마지막 next를 돌려준다. 질문이 먼저 끝나면 실패."""
    started = await qf.start_questions(stores.snapshot(), stores)
    nxt: dict[str, Any] = {"done": False, "question": started["question"]}
    for _ in range(n):
        assert not nxt["done"]
        nxt = (
            await qf.submit_answer(stores.snapshot(), stores, nxt["question"]["id"], fixed(stores, nxt["question"]))
        )["next"]
    return nxt


def status_of(stores: FakeStores) -> Status:
    return stores.snapshot().status


LAPTOP_W = [0.2, 0.3, 0.5]
CAREER_W = [0.1, 0.2, 0.3, 0.2, 0.2]


# ------------------------------------------------------------------ 31 → 37… → done
def test_laptop_happy_path():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        started = await qf.start_questions(stores.snapshot(), stores)
        assert set(started) == {"status", "round", "question", "progress"}
        assert (started["status"], started["round"]) == ("QUESTIONING", "initial")
        assert started["progress"] == {"answered": 0, "expectedRange": [3, max_questions(3)]}
        q1 = started["question"]
        assert list(q1)[:2] == ["id", "seq"] and q1["seq"] == 1 and q1["type"] == "slider_pair"
        assert q1["criteria"] == ["price", "weight"] and q1["payload"]["min"] == -5
        assert status_of(stores) is Q and stores.snapshot().question_limit is None  # 첫 라운드는 NULL

        out = await qf.submit_answer(stores.snapshot(), stores, q1["id"], {"value": -2})
        assert set(out) == {"answer", "estimate", "next"}
        assert set(out["answer"]) == {"id", "questionId", "response", "isValid", "createdAt"}
        assert out["answer"]["response"] == {"value": -2} and out["answer"]["isValid"] is True
        assert set(out["estimate"]) == {"weights", "topProbability"}
        assert [w["criterionId"] for w in out["estimate"]["weights"]] == ["price", "weight", "perf"]
        assert not out["next"]["done"] and out["next"]["question"]["seq"] == 2

        last = await play(stores, User(LAPTOP_W, 1), out["next"])
        assert last["done"] and last["reason"] in ("CONFIDENT", "LOW_INFO_GAIN", "MAX_QUESTIONS")
        assert status_of(stores) is A
        qs = engine_questions(stores)
        assert [q.seq for q in qs] == list(range(1, len(qs) + 1))
        assert [q.type for q in qs[:2]] == ["slider_pair", "slider_pair"]  # min(K−1, 3) = 2개의 사슬
        assert [(q.spec["a"], q.spec["b"]) for q in qs[:2]] == [("price", "weight"), ("weight", "perf")]
        if len(qs) >= 4:
            assert qs[3].type == "ab_scenario"  # 4번째는 상황형 할당 차례
        answers = await stores.questions.list_answers("d-laptop")
        assert len(answers) == len(qs) <= max_questions(3)
        assert {a.preference_kind for a in answers} <= {"stated", "revealed"}
        assert all(
            a.preference_kind == ("revealed" if q.type == "ab_scenario" else "stated")
            for q, a in zip(qs, answers, strict=True)
        )
        assert all(q.info_gain is not None for q in qs) and qs[0].phrasing_source == "template"
        assert stores.state.version == 1 + len(answers)  # 답변마다 버전 +1
        # 종료 뒤 32번은 항상 done
        assert (await qf.next_question(stores.snapshot(), stores))["done"] is True

    run(go())


def test_career_happy_path():
    async def go() -> None:
        stores = make_stores(career_snapshot())
        started = await qf.start_questions(stores.snapshot(), stores)
        assert started["progress"]["expectedRange"] == [3, max_questions(5)]
        # 단계형 범위 표기는 양 끝 선택지 이름
        assert started["question"]["payload"]["left"]["range"] == "교육학과~컴퓨터공학과"
        last = await play(stores, User(CAREER_W, 4), {"done": False, "question": started["question"]})
        assert last["done"] and status_of(stores) is A
        qs = engine_questions(stores)
        assert [q.type for q in qs[:3]] == ["slider_pair"] * 3  # min(K−1, 3) = 3
        for q in qs:
            if q.type == "ab_scenario":
                assert "interest" not in (q.spec["a"], q.spec["b"])  # 순위 입력 기준은 상황형에 쓰지 않는다
        assert len(qs) <= max_questions(5)

    run(go())


# ------------------------------------------------------------------ 31 멱등 · 척도 고정
def test_start_is_idempotent_and_freezes_scales_once():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        first = await qf.start_questions(stores.snapshot(), stores)
        again = await qf.start_questions(stores.snapshot(), stores)
        assert again["question"] == first["question"] and again["round"] == "initial"
        assert len(stores.questions.questions) == 1
        assert stores.state.calls.count("freeze_scales") == 1  # 척도는 한 번만 저장
        assert stores.state.calls.count("set_status:QUESTIONING") == 1
        assert stores.state.scales == {"price": (150.0, 189.0), "weight": (1.19, 1.45)}  # 숫자형만
        snap = stores.snapshot()
        assert [c.scale_frozen for c in snap.criteria] == [True, True, False]
        # 값이 나중에 바뀌어도 고정된 척도는 그대로
        stores.state.base = replace(
            stores.state.base,
            values=tuple(
                replace(v, value_num=500.0) if (v.option_id, v.criterion_id) == ("galaxy", "price") else v
                for v in stores.state.base.values
            ),
        )
        await qf.next_question(stores.snapshot(), stores)
        assert stores.state.scales["price"] == (150.0, 189.0)
        assert stores.state.calls.count("freeze_scales") == 1

    run(go())


def test_next_returns_the_unanswered_question_until_answered():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        started = await qf.start_questions(stores.snapshot(), stores)
        n1 = await qf.next_question(stores.snapshot(), stores)
        n2 = await qf.next_question(stores.snapshot(), stores)
        assert n1 == n2 == {"done": False, "question": started["question"]}
        assert len(stores.questions.questions) == 1
        await qf.submit_answer(stores.snapshot(), stores, started["question"]["id"], {"skip": True})
        n3 = await qf.next_question(stores.snapshot(), stores)
        assert n3["question"]["seq"] == 2 and n3 == await qf.next_question(stores.snapshot(), stores)

    run(go())


def test_start_without_candidates_changes_nothing():
    async def go() -> None:
        snap = laptop_snapshot()
        snap = replace(snap, values=tuple(replace(v, value_num=1.0) for v in snap.values))  # 모든 값이 같다
        stores = make_stores(snap)
        with pytest.raises(FlowError) as exc:
            await qf.start_questions(stores.snapshot(), stores)
        assert (exc.value.http_status, exc.value.code) == (422, "NOT_READY")
        assert exc.value.details is not None and exc.value.details["missing"] == ["NO_QUESTION_CANDIDATES"]
        assert status_of(stores) is D and stores.questions.questions == [] and stores.state.calls == []

    run(go())


# ------------------------------------------------------------------ 추가 라운드
def test_extra_round_from_analyzed():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        # 한 기준에 크게 몰린 사용자 → 첫 라운드는 일찍 끝난다
        await start_and_play(stores, User([0.05, 0.05, 0.9], 3))
        assert status_of(stores) is A
        answered = len(engine_questions(stores))
        n_questions = len(stores.questions.questions)

        again = await qf.start_questions(stores.snapshot(), stores)
        limit = extra_round_limit(answered)
        assert (again["status"], again["round"]) == ("QUESTIONING", "extra")
        assert again["progress"] == {"answered": answered, "expectedRange": [answered + 1, limit]}
        assert stores.snapshot().question_limit == limit and status_of(stores) is Q
        assert again["question"]["seq"] == n_questions + 1  # 기존 답변·질문 유지, 다음 seq
        assert stores.state.calls.count("freeze_scales") == 1  # 척도는 다시 고정하지 않는다

        # 멱등: QUESTIONING에서 다시 부르면 같은 질문, round는 extra
        same = await qf.start_questions(stores.snapshot(), stores)
        assert same["question"] == again["question"] and same["round"] == "extra"

        last = await play(stores, User([0.05, 0.05, 0.9], 3), {"done": False, "question": again["question"]})
        assert last["reason"] in ("LOW_INFO_GAIN", "MAX_QUESTIONS")  # CONFIDENT로는 끝나지 않는다
        now = len(engine_questions(stores))
        assert answered + 1 <= now <= limit  # 최소 1문항, 상한 이내
        assert status_of(stores) is A

    run(go())


def test_extra_round_limit_is_independent_of_virtual_answers():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        await qf.add_stated_preference(stores.snapshot(), stores, "price", "perf", -3)
        await start_and_play(stores, User(LAPTOP_W, 5))
        counted = len(engine_questions(stores))
        again = await qf.start_questions(stores.snapshot(), stores)
        assert stores.snapshot().question_limit == counted + 3  # 가상 답변은 세지 않는다
        assert again["progress"]["answered"] == counted
        assert again["progress"]["expectedRange"] == [counted + 1, counted + 3]

    run(go())


# ------------------------------------------------------------------ 상태 표 (4.2)
def snap_in(status: Status, **kw: Any) -> Any:
    return laptop_snapshot(status, **kw)


FIN, INV, NR = (409, "DECISION_FINALIZED"), (409, "INVALID_STATE"), (422, "NOT_READY")


def expect_error(coro: Any, expected: tuple[int, str]) -> FlowError:
    with pytest.raises(FlowError) as exc:
        run(coro)
    assert (exc.value.http_status, exc.value.code) == expected
    return exc.value


def test_state_table_errors_for_question_apis():
    for status, expected in [(D, NR), (Q, None), (A, None), (F, None)]:
        for name in ("next_question", "question_status", "question_progress"):
            stores = make_stores(snap_in(status))
            coro = getattr(qf, name)(stores.snapshot(), stores)
            if expected:
                expect_error(coro, expected)
            else:  # 32·33·34는 QUESTIONING·ANALYZED·FINALIZED에서 된다 (질문이 없어도 오류 아님은 아래에서 따로)
                coro.close()

    # 31
    stores = make_stores(snap_in(F))
    expect_error(qf.start_questions(stores.snapshot(), stores), FIN)
    # 35·37
    for status, expected in [(D, INV), (A, INV), (F, FIN)]:
        stores = make_stores(snap_in(status))
        expect_error(qf.stop_questions(stores.snapshot(), stores), expected)
        expect_error(qf.submit_answer(stores.snapshot(), stores, "q", {"skip": True}), expected)
        assert stores.state.calls == []
    # 38
    for status, expected in [(D, INV), (F, FIN)]:
        stores = make_stores(snap_in(status))
        expect_error(qf.edit_answer(stores.snapshot(), stores, "a", {"skip": True}), expected)
    # 29 가상 선호 / 19·22 기준 추가·삭제
    stores = make_stores(snap_in(F))
    expect_error(qf.add_stated_preference(stores.snapshot(), stores, "price", "weight", 1), FIN)
    expect_error(qf.on_criterion_added(stores.snapshot(), stores, "perf"), FIN)
    expect_error(qf.on_criterion_deleted(stores.snapshot(), stores, "perf"), FIN)


def test_read_apis_work_after_analysis_and_finalized():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        await start_and_play(stores, User(LAPTOP_W, 2))
        for status in (A, F):
            snap = replace(stores.snapshot(), status=status)
            nxt = await qf.next_question(snap, stores)
            assert nxt["done"] is True and nxt["reason"] in (
                "CONFIDENT",
                "LOW_INFO_GAIN",
                "MAX_QUESTIONS",
                "USER_STOPPED",
            )
            st = await qf.question_status(snap, stores)
            assert st["shouldStop"] is True and st["reason"] == nxt["reason"]
            await qf.question_progress(snap, stores)
            assert (await qf.list_answers(snap, stores))["items"]
        assert status_of(stores) is A

    run(go())


def test_start_requires_readiness_in_draft_and_analyzed():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        with pytest.raises(FlowError) as exc:
            await qf.start_questions(stores.snapshot(), stores, missing=["OPTIONS_LT_2"])
        assert (exc.value.http_status, exc.value.code) == (422, "NOT_READY")
        assert exc.value.details is not None and exc.value.details["missing"] == ["OPTIONS_LT_2"]
        assert status_of(stores) is D and stores.questions.questions == [] and stores.state.calls == []
        await qf.start_questions(stores.snapshot(), stores)
        # 멱등(QUESTIONING)에서는 readiness를 보지 않는다
        assert (await qf.start_questions(stores.snapshot(), stores, missing=["X"]))["status"] == "QUESTIONING"
        stores.state.status = A
        with pytest.raises(FlowError):
            await qf.start_questions(stores.snapshot(), stores, missing=["OPTIONS_LT_2"])

    run(go())


# ------------------------------------------------------------------ 37 오류
def test_duplicate_unknown_and_invalid_answers():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        started = await qf.start_questions(stores.snapshot(), stores)
        q1 = started["question"]["id"]
        await qf.submit_answer(stores.snapshot(), stores, q1, {"value": 1})
        with pytest.raises(FlowError) as exc:
            await qf.submit_answer(stores.snapshot(), stores, q1, {"value": 2})
        assert (exc.value.http_status, exc.value.code) == (409, "QUESTION_ALREADY_ANSWERED")
        with pytest.raises(FlowError) as exc:
            await qf.submit_answer(stores.snapshot(), stores, "nope", {"value": 2})
        assert (exc.value.http_status, exc.value.code) == (404, "NOT_FOUND")
        assert len(stores.questions.answers) == 1

        # 슬라이더 질문(2번)에 잘못된 형식
        q2 = (await qf.next_question(stores.snapshot(), stores))["question"]
        for bad in [
            {"choice": "A"},
            {"value": 6},
            {"value": "1"},
            {"value": 1.5},
            {},
            {"value": True},
            "A",
            {"skip": False},
        ]:
            with pytest.raises(FlowError) as exc:
                await qf.submit_answer(stores.snapshot(), stores, q2["id"], bad)
            assert (exc.value.http_status, exc.value.code) == (400, "VALIDATION_ERROR")
        assert len(stores.questions.answers) == 1 and stores.state.version == 2  # 실패한 요청은 아무것도 바꾸지 않는다

        # A/B 질문까지 가서 확인
        user = User(LAPTOP_W, 8)
        nxt = {"done": False, "question": q2}
        while nxt["question"]["type"] == "slider_pair":
            nxt = (
                await qf.submit_answer(
                    stores.snapshot(), stores, nxt["question"]["id"], user.respond(stores, nxt["question"])
                )
            )["next"]
            assert not nxt["done"]
        ab = nxt["question"]
        for bad in [{"value": 1}, {"choice": "C"}, {"choice": "a"}, {}]:
            with pytest.raises(FlowError) as exc:
                await qf.submit_answer(stores.snapshot(), stores, ab["id"], bad)
            assert exc.value.code == "VALIDATION_ERROR"
        await qf.submit_answer(stores.snapshot(), stores, ab["id"], {"skip": True})
        stored = next(a for a in stores.questions.answers if a.question_id == ab["id"])
        assert stored.response == {"skip": True}

    run(go())


# ------------------------------------------------------------------ 38 수정
def test_edit_answer_in_analyzed_keeps_status_and_reestimates():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        await start_and_play(stores, User(LAPTOP_W, 9))
        assert status_of(stores) is A
        first = (await stores.questions.list_answers("d-laptop"))[0]
        version = stores.state.version
        before = await qf.question_progress(stores.snapshot(), stores)
        out = await qf.edit_answer(stores.snapshot(), stores, first.id, {"value": 5})
        assert set(out) == {"estimate"} and set(out["estimate"]) == {"weights", "topProbability"}
        assert status_of(stores) is A  # 상태 유지
        assert stores.state.version == version + 1
        edited = (await stores.questions.list_answers("d-laptop"))[0]
        assert edited.response == {"value": 5} and edited.preference_kind == "stated"
        after = await qf.question_progress(stores.snapshot(), stores)
        assert after["confidence"] == out["estimate"]["topProbability"]
        assert before["answered"] == after["answered"]
        # 형식 오류 400 / 없는 답변 404
        with pytest.raises(FlowError) as exc:
            await qf.edit_answer(stores.snapshot(), stores, first.id, {"choice": "A"})
        assert exc.value.code == "VALIDATION_ERROR"
        with pytest.raises(FlowError) as exc:
            await qf.edit_answer(stores.snapshot(), stores, "nope", {"value": 1})
        assert exc.value.code == "NOT_FOUND"

    run(go())


def test_edit_answer_in_questioning_changes_the_estimate():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        started = await qf.start_questions(stores.snapshot(), stores)
        out = await qf.submit_answer(stores.snapshot(), stores, started["question"]["id"], {"value": -5})
        edit = await qf.edit_answer(stores.snapshot(), stores, out["answer"]["id"], {"value": 5})
        w_before = {w["criterionId"]: w["mean"] for w in out["estimate"]["weights"]}
        w_after = {w["criterionId"]: w["mean"] for w in edit["estimate"]["weights"]}
        assert w_before["price"] < w_after["price"]  # −5(무게 쪽) → +5(가격 쪽)
        assert status_of(stores) is Q

    run(go())


# ------------------------------------------------------------------ 35 · 33 · 34 · 36
def test_stop_questions():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        await answer_n(stores, 2)
        confidence = (await qf.question_progress(stores.snapshot(), stores))["confidence"]
        out = await qf.stop_questions(stores.snapshot(), stores)
        assert set(out) == {"reason", "lowConfidence"} and out["reason"] == "USER_STOPPED"
        assert isinstance(out["lowConfidence"], bool) and status_of(stores) is A
        assert out["lowConfidence"] == (confidence < 0.55)  # 5.10 배너 조건 P_best < 0.55
        # ANALYZED에서 다시 부르면 409
        with pytest.raises(FlowError) as exc:
            await qf.stop_questions(stores.snapshot(), stores)
        assert (exc.value.http_status, exc.value.code) == INV
        # 답변이 없어도 종료할 수 있고, 모르는 상태라 확신이 낮다
        s2 = make_stores(laptop_snapshot())
        await qf.start_questions(s2.snapshot(), s2)
        assert (await qf.stop_questions(s2.snapshot(), s2))["lowConfidence"] is True

    run(go())


def test_status_and_progress_payloads():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        await answer_n(stores, 3)
        calls = list(stores.state.calls)
        st = await qf.question_status(stores.snapshot(), stores)
        assert set(st) == {"shouldStop", "reason", "metrics", "remainingUncertainty"}
        assert set(st["metrics"]) == {"answered", "topOption", "topProbability", "decisionEntropy", "bestNextInfoGain"}
        assert st["metrics"]["answered"] == 3 and st["metrics"]["topOption"] in ("galaxy", "gram", "yoga")
        assert 0 <= st["metrics"]["topProbability"] <= 1 and st["metrics"]["bestNextInfoGain"] >= 0
        assert isinstance(st["shouldStop"], bool) and (st["reason"] is None) == (not st["shouldStop"])
        ru = st["remainingUncertainty"]
        assert len(ru) <= 3 and all(set(r) == {"criterionId", "interval"} for r in ru)
        widths = [r["interval"][1] - r["interval"][0] for r in ru]
        assert widths == sorted(widths, reverse=True) and all(w >= 0.2 - 0.011 for w in widths)  # 반올림 오차
        pr = await qf.question_progress(stores.snapshot(), stores)
        assert set(pr) == {"answered", "expectedRange", "confidence"}
        assert pr["answered"] == 3 and pr["expectedRange"] == [3, 6]
        assert pr["confidence"] == st["metrics"]["topProbability"]
        assert stores.state.calls == calls and status_of(stores) is Q  # 조회만 한다

    run(go())


def test_list_answers_shape_and_origin():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        created = await qf.add_stated_preference(stores.snapshot(), stores, "price", "perf", 3)
        started = await qf.start_questions(stores.snapshot(), stores)
        out = await qf.submit_answer(stores.snapshot(), stores, started["question"]["id"], {"value": 1})
        items = (await qf.list_answers(stores.snapshot(), stores))["items"]
        assert [i["question"]["origin"] for i in items] == ["free_text", "engine"]
        assert set(items[0]) == {"id", "questionId", "question", "response", "isValid", "createdAt"}
        assert items[0]["id"] == created["answerId"] and items[0]["questionId"] == created["questionId"]
        assert items[1]["id"] == out["answer"]["id"] and items[1]["question"]["seq"] == 2
        assert {"id", "seq", "type", "criteria", "title", "payload", "allowFreeText", "allowSkip"} <= set(
            items[1]["question"]
        )

    run(go())


# ------------------------------------------------------------------ 가상 선호 (29번)
def test_virtual_preference_is_not_counted_or_asked():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        created = await qf.add_stated_preference(stores.snapshot(), stores, "price", "weight", 3)  # DRAFT에서도 된다
        assert status_of(stores) is D and stores.state.version == 2
        vq, va = stores.questions.questions[0], stores.questions.answers[0]
        assert (vq.id, vq.origin, vq.type, vq.seq, vq.phrasing_source) == (
            created["questionId"],
            "free_text",
            "slider_pair",
            1,
            "template",
        )
        assert (
            vq.spec == {"kind": "slider_pair", "a": "price", "b": "weight"}
            and vq.display["payload"]["left"]["range"] == "150~189만 원"
        )
        assert (va.id, va.preference_kind, va.response, va.is_valid) == (
            created["answerId"],
            "stated",
            {"value": 3},
            True,
        )

        started = await qf.start_questions(stores.snapshot(), stores)
        assert started["progress"] == {"answered": 0, "expectedRange": [3, 6]}  # 가상 답변은 세지 않는다
        assert started["question"]["seq"] == 2 and started["question"]["id"] != vq.id  # 가상 질문은 반환하지 않는다
        assert stores.snapshot().question_limit is None
        status = await qf.question_status(stores.snapshot(), stores)
        assert status["metrics"]["answered"] == 0

        # 건너뛰기만 하는 사용자: 엔진 질문 6개(=MAX_QUESTIONS)까지 묻는다 (가상 답변이 있어도 5개에서 끝나지 않는다)
        last = await play(stores, User(LAPTOP_W, 0, always_skip=True), {"done": False, "question": started["question"]})
        assert last == {"done": True, "reason": "MAX_QUESTIONS"}
        assert len(engine_questions(stores)) == 6 and len(stores.questions.questions) == 7
        assert (await qf.question_progress(stores.snapshot(), stores))["answered"] == 6

    run(go())


def test_virtual_preference_validation():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        for args, code in [
            (("price", "price", 1), "VALIDATION_ERROR"),
            (("price", "x", 1), "NOT_FOUND"),
            (("price", "weight", 6), "VALIDATION_ERROR"),
            (("price", "weight", True), "VALIDATION_ERROR"),
        ]:
            with pytest.raises(FlowError) as exc:
                await qf.add_stated_preference(stores.snapshot(), stores, *args)
            assert exc.value.code == code
        assert stores.questions.questions == []
        # QUESTIONING·ANALYZED에서도 된다. seq는 다음 번호
        await qf.start_questions(stores.snapshot(), stores)
        created = await qf.add_stated_preference(stores.snapshot(), stores, "weight", "perf", -4)
        assert next(q for q in stores.questions.questions if q.id == created["questionId"]).seq == 2
        assert status_of(stores) is Q

    run(go())


# ------------------------------------------------------------------ 기준 추가 · 삭제
BATTERY = CriterionRow("battery", "배터리", "numeric", "higher_better", 0, unit="시간")


def battery_values() -> list[ValueRow]:
    return [
        ValueRow("galaxy", "battery", value_num=14.0),
        ValueRow("gram", "battery", value_num=8.0),
        ValueRow("yoga", "battery", value_num=11.0),
    ]


def test_criterion_added_after_analysis_starts_extra_round_and_asks_it_first():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        await start_and_play(stores, User(LAPTOP_W, 12))
        answered = len(engine_questions(stores))
        stores.state.add_criterion(BATTERY, battery_values())
        changed = await qf.on_criterion_added(stores.snapshot(), stores, "battery")
        assert changed == "QUESTIONING" and status_of(stores) is Q
        assert stores.snapshot().question_limit == extra_round_limit(answered)
        nxt = await qf.next_question(stores.snapshot(), stores)
        assert nxt["done"] is False and "battery" in nxt["question"]["criteria"]  # 새 기준이 든 쌍을 먼저 묻는다
        assert stores.state.scales["battery"] == (8.0, 14.0)  # 값이 채워진 뒤 한 번 고정
        assert stores.state.calls.count("freeze_scales") == 2
        await qf.next_question(stores.snapshot(), stores)
        assert stores.state.calls.count("freeze_scales") == 2
        # 이 라운드는 최소 1문항 + 상한 이내
        last = await play(stores, User([*LAPTOP_W, 0.5], 13), nxt)
        assert last["reason"] in ("LOW_INFO_GAIN", "MAX_QUESTIONS")
        assert answered + 1 <= len(engine_questions(stores)) <= answered + 3

    run(go())


def test_criterion_added_while_questioning_raises_limit_only_when_needed():
    async def go() -> None:
        # 라운드 도중(추가 라운드 상한 = 답변 수 + 3)에 답변을 늘려 가며 기준을 추가한다
        stores = make_stores(laptop_snapshot())
        await start_and_play(stores, User(LAPTOP_W, 21))
        base = len(engine_questions(stores))
        started = await qf.start_questions(stores.snapshot(), stores)
        limit = stores.snapshot().question_limit
        assert limit == base + 3
        # 상한 여유가 있을 때: 답변 수 + 3 ≤ 상한이면 바꾸지 않는다 (답변 0개 추가)
        stores.state.add_criterion(BATTERY, battery_values())
        assert await qf.on_criterion_added(stores.snapshot(), stores, "battery") is None
        assert stores.snapshot().question_limit == limit
        # 답변 1개를 더하면 답변 수 + 3 > 상한 → 올린다 (기준 하나 더 추가)
        nxt = {"done": False, "question": started["question"]}
        out = await qf.submit_answer(stores.snapshot(), stores, nxt["question"]["id"], fixed(stores, nxt["question"]))
        assert not out["next"]["done"]
        stores.state.add_criterion(
            replace(BATTERY, id="speed", name="속도"), [replace(v, criterion_id="speed") for v in battery_values()]
        )
        assert await qf.on_criterion_added(stores.snapshot(), stores, "speed") is None
        assert stores.snapshot().question_limit == base + 1 + 3 and status_of(stores) is Q
        # 열려 있던 질문을 답하면 새 기준(배터리·속도)이 든 쌍을 먼저 묻는다
        out = await qf.submit_answer(
            stores.snapshot(), stores, out["next"]["question"]["id"], fixed(stores, out["next"]["question"])
        )
        assert not out["next"]["done"] and {"battery", "speed"} & set(out["next"]["question"]["criteria"])

    run(go())


def test_criterion_added_in_first_round_keeps_null_limit_when_there_is_room():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        await answer_n(stores, 2)
        stores.state.add_criterion(BATTERY, battery_values())
        assert await qf.on_criterion_added(stores.snapshot(), stores, "battery") is None
        assert stores.snapshot().question_limit is None  # 답변 2 + 3 ≤ max_questions(4) = 8
        nxt = await qf.next_question(stores.snapshot(), stores)
        assert "battery" in nxt["question"]["criteria"] or nxt["question"]["seq"] == 3  # 이미 열려 있는 질문이 먼저
        # DRAFT에서는 아무것도 하지 않는다
        d = make_stores(laptop_snapshot())
        assert await qf.on_criterion_added(d.snapshot(), d, "perf") is None and d.state.calls == []

    run(go())


def test_criterion_deleted_invalidates_answers_and_estimate_still_works():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        await answer_n(stores, 3)  # 질문: price-weight, weight-perf, 세 번째
        before = await qf.question_progress(stores.snapshot(), stores)
        version = stores.state.version
        involved = sum(1 for q in stores.questions.questions if "weight" in (q.spec["a"], q.spec["b"]))
        count = await qf.on_criterion_deleted(stores.snapshot(), stores, "weight")
        assert count == involved >= 2
        assert stores.state.version == version + 1
        stores.state.remove_criterion("weight")
        valid = [a for a in stores.questions.answers if a.is_valid]
        assert len(valid) == 3 - count
        items = (await qf.list_answers(stores.snapshot(), stores))["items"]
        assert len(items) == 3 and sum(not i["isValid"] for i in items) == count  # 무효 답변도 보인다
        # 추정은 K=2로 다시 계산된다
        prog = await qf.question_progress(stores.snapshot(), stores)
        assert prog["answered"] == before["answered"] - count and prog["expectedRange"] == [3, 6]
        st = await qf.question_status(stores.snapshot(), stores)
        assert {r["criterionId"] for r in st["remainingUncertainty"]} <= {"price", "perf"}
        # 새 질문은 남은 두 기준으로만 만들고, 지워진 기준을 가리키는 미답변 질문은 건너뛴다
        nxt = await qf.next_question(stores.snapshot(), stores)
        if not nxt["done"]:
            assert set(nxt["question"]["criteria"]) == {"price", "perf"}
        # 무효가 된 답변은 수정할 수 없다
        dead = next(a for a in stores.questions.answers if not a.is_valid)
        with pytest.raises(FlowError) as exc:
            await qf.edit_answer(stores.snapshot(), stores, dead.id, {"value": 1})
        assert (exc.value.http_status, exc.value.code) == INV

    run(go())


# ------------------------------------------------------------------ 결정성
def test_determinism():
    async def session() -> list[tuple[Any, ...]]:
        stores = make_stores(laptop_snapshot())
        started = await qf.start_questions(stores.snapshot(), stores)
        nxt: dict[str, Any] = {"done": False, "question": started["question"]}
        while not nxt["done"]:
            nxt = (
                await qf.submit_answer(stores.snapshot(), stores, nxt["question"]["id"], fixed(stores, nxt["question"]))
            )["next"]
        return [(q.seq, q.type, q.spec, q.display, q.info_gain) for q in stores.questions.questions]

    first, second = run(session()), run(session())
    assert first == second and len(first) >= 3

    async def same_store() -> None:
        stores = make_stores(laptop_snapshot())
        await answer_n(stores, 2)
        a = await qf.next_question(stores.snapshot(), stores)
        b = await qf.next_question(stores.snapshot(), stores)
        assert a == b

    run(same_store())


def test_new_question_depends_only_on_stores_not_on_call_order():
    """같은 질문·답변 목록이면 어느 저장소에서든 같은 다음 질문 (시드·답변만으로 결정)."""

    async def go() -> None:
        s1 = make_stores(laptop_snapshot())
        await answer_n(s1, 3)
        s2 = make_stores(laptop_snapshot())
        s2.questions.questions = list(s1.questions.questions)
        s2.questions.answers = list(s1.questions.answers)
        s2.state.status = Q
        n1 = await qf.next_question(s1.snapshot(), s1)
        n2 = await qf.next_question(s2.snapshot(), s2)
        strip = lambda n: {k: v for k, v in n["question"].items() if k != "id"}  # noqa: E731
        assert strip(n1) == strip(n2)

    run(go())


# ------------------------------------------------------------------ AI 문장
def phrased(**kw: Any) -> PhrasedQuestion:
    base: dict[str, Any] = {
        "source": "ai",
        "title": "AI 제목",
        "option_a_description": "AI 설명 A",
        "option_b_description": "AI 설명 B",
        "left_label": "왼쪽",
        "right_label": "오른쪽",
    }
    return PhrasedQuestion(**{**base, **kw})


def test_phraser_success_overwrites_only_allowed_fields():
    async def go() -> None:
        seen: list[dict[str, Any]] = []

        async def phraser(q: dict[str, Any]) -> PhrasedQuestion | None:
            seen.append(q)
            q["title"] = "mutated"  # 호출자가 받은 건 복사본이다
            return phrased()

        stores = make_stores(laptop_snapshot())
        started = await qf.start_questions(stores.snapshot(), stores, phraser=phraser)
        q = started["question"]
        assert (
            q["title"] == "AI 제목"
            and q["payload"]["left"]["label"] == "왼쪽"
            and q["payload"]["right"]["label"] == "오른쪽"
        )
        assert (
            q["payload"]["left"]["range"] == "150~189만 원" and q["payload"]["left"]["criterionId"] == "price"
        )  # 숫자는 서버 값
        assert "description" not in q["payload"] and seen[0]["id"] == q["id"] and seen[0]["seq"] == 1
        row = stores.questions.questions[0]
        assert row.phrasing_source == "ai" and row.display["title"] == "AI 제목"
        # 상황형까지 간다
        nxt = {"done": False, "question": q}
        scenario = None
        while not nxt["done"]:
            if nxt["question"]["type"] == "ab_scenario":
                scenario = nxt["question"]
                break
            nxt = (
                await qf.submit_answer(
                    stores.snapshot(), stores, nxt["question"]["id"], fixed(stores, nxt["question"]), phraser=phraser
                )
            )["next"]
        assert scenario is not None
        assert scenario["title"] == "AI 제목" and scenario["payload"]["optionA"]["description"] == "AI 설명 A"
        assert scenario["payload"]["optionB"]["description"] == "AI 설명 B"
        assert scenario["payload"]["optionA"]["deltas"][0]["display"].endswith(("원", "kg", "단계"))  # deltas는 서버 값
        assert "left" not in scenario["payload"]

    run(go())


def test_phraser_none_or_exception_falls_back_to_template():
    async def go() -> None:
        async def none_phraser(q: dict[str, Any]) -> PhrasedQuestion | None:
            return None

        async def boom(q: dict[str, Any]) -> PhrasedQuestion | None:
            raise RuntimeError("LLM down")

        template = make_stores(laptop_snapshot())
        base = (await qf.start_questions(template.snapshot(), template))["question"]
        for phraser in (none_phraser, boom):
            stores = make_stores(laptop_snapshot())
            q = (await qf.start_questions(stores.snapshot(), stores, phraser=phraser))["question"]
            assert {k: v for k, v in q.items() if k != "id"} == {k: v for k, v in base.items() if k != "id"}
            assert stores.questions.questions[0].phrasing_source == "template"
            nxt = await qf.submit_answer(stores.snapshot(), stores, q["id"], {"value": 1}, phraser=phraser)
            assert nxt["next"]["question"]["title"].endswith("어느 쪽이 더 중요한가요?")

        # 부분 결과: None 필드는 덮어쓰지 않는다
        async def only_title(q: dict[str, Any]) -> PhrasedQuestion | None:
            return PhrasedQuestion("ai", "제목만")

        stores = make_stores(laptop_snapshot())
        q = (await qf.start_questions(stores.snapshot(), stores, phraser=only_title))["question"]
        assert q["title"] == "제목만" and q["payload"]["left"]["label"] == "가격"

    run(go())


# ------------------------------------------------------------------ 기타
def test_new_id_is_uuid_v7():
    import uuid

    ids = {qf.new_id() for _ in range(50)}
    assert len(ids) == 50
    for i in ids:
        u = uuid.UUID(i)
        assert u.version == 7 and u.variant == uuid.RFC_4122


def test_flow_error_helpers():
    err = from_violation(Violation(409, "INVALID_STATE"), {"decisionId": "d"})
    assert (err.http_status, err.code, err.details) == (409, "INVALID_STATE", {"decisionId": "d"})
    assert err.to_body() == {"error": {"code": "INVALID_STATE", "message": err.message, "details": {"decisionId": "d"}}}
    assert "details" not in validation_error("x").to_body()["error"]


def test_scenario_questions_store_ids_not_indices():
    async def go() -> None:
        stores = make_stores(laptop_snapshot())
        await start_and_play(stores, User(LAPTOP_W, 31))
        scenarios = [q for q in stores.questions.questions if q.type == "ab_scenario"]
        assert scenarios
        for q in scenarios:
            assert q.spec["kind"] == "ab_scenario" and set(q.spec["delta"]) == {"price", "weight", "perf"}
            assert q.spec["a"] in ("price", "weight", "perf") and {"rawA", "rawB"} <= set(q.spec)
            spec = spec_from_dict(q.spec, ["price", "weight", "perf"])
            assert isinstance(spec, ScenarioSpec)

    run(go())

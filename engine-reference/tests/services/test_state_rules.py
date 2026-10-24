"""4.2 상태 머신 규칙: "API별 상태 제약" 표의 모든 칸과 전이를 명세와 대조한다."""

import pytest

from app.services.state_rules import (
    Action,
    InvalidTransition,
    Status,
    Violation,
    after_criterion_added,
    after_finalize,
    after_question_start,
    after_stop_condition,
    check,
    is_provisional,
    round_kind,
)

D, Q, A, F = Status.DRAFT, Status.QUESTIONING, Status.ANALYZED, Status.FINALIZED
FIN = Violation(409, "DECISION_FINALIZED")
INV = Violation(409, "INVALID_STATE")
NR = Violation(422, "NOT_READY")

# 명세 4.2 표를 그대로 옮긴 기대값 (열 순서: DRAFT, QUESTIONING, ANALYZED, FINALIZED). None = 가능.
# ANALYSIS_READ·SIMULATE의 QUESTIONING 칸은 답변 1개 이상일 때의 값이고 0개는 아래에서 따로 본다.
TABLE: dict[Action, tuple[Violation | None, ...]] = {
    Action.CONFIG_EDIT: (None, None, None, FIN),
    Action.FREE_INPUT: (None, None, None, FIN),
    Action.QUESTION_START: (None, None, None, FIN),
    Action.QUESTION_READ: (NR, None, None, None),
    Action.ANSWER_SUBMIT_OR_STOP: (INV, None, INV, FIN),
    Action.ANSWER_EDIT: (INV, None, None, FIN),
    Action.ANALYSIS_READ: (NR, None, None, None),
    Action.SIMULATE: (NR, None, None, None),
    Action.FINALIZE: (INV, INV, None, FIN),
    Action.EXPORT: (NR, NR, None, None),
    Action.DELETE_OR_DUPLICATE: (None, None, None, None),
}
STATUSES = [D, Q, A, F]
CELLS = [(a, s, TABLE[a][i]) for a in TABLE for i, s in enumerate(STATUSES)]


def test_table_covers_every_action_but_read() -> None:
    assert set(TABLE) == set(Action) - {Action.READ}
    assert len(CELLS) == 11 * 4


@pytest.mark.parametrize(("action", "status", "expected"), CELLS)
def test_every_table_cell(action: Action, status: Status, expected: Violation | None) -> None:
    assert check(action, status, answered=1) == expected


@pytest.mark.parametrize("status", STATUSES)
def test_pure_reads_always_allowed(status: Status) -> None:
    assert check(Action.READ, status) is None


@pytest.mark.parametrize("action", [Action.ANALYSIS_READ, Action.SIMULATE])
def test_questioning_without_answers_is_not_ready(action: Action) -> None:
    assert check(action, Q, answered=0) == NR
    assert check(action, Q, answered=1) is None
    assert check(action, Q) == NR  # 기본값 0


@pytest.mark.parametrize("action", [a for a in Action if a not in (Action.ANALYSIS_READ, Action.SIMULATE)])
def test_answers_only_matter_for_analysis_and_simulation(action: Action) -> None:
    for status in STATUSES:
        assert check(action, status, answered=0) == check(action, status, answered=5)


@pytest.mark.parametrize("action", [Action.ANALYSIS_READ, Action.SIMULATE])
@pytest.mark.parametrize(("status", "expected"), [(D, NR), (A, None), (F, None)])
def test_answers_do_not_change_other_statuses(action: Action, status: Status, expected: Violation | None) -> None:
    assert check(action, status, answered=0) == expected


def test_finalized_is_dead_end_for_every_mutation() -> None:
    mutating = [
        Action.CONFIG_EDIT,
        Action.FREE_INPUT,
        Action.QUESTION_START,
        Action.ANSWER_SUBMIT_OR_STOP,
        Action.ANSWER_EDIT,
        Action.FINALIZE,
    ]
    for action in mutating:
        assert check(action, F) == FIN


def test_provisional() -> None:
    assert is_provisional(Q, 1)
    assert is_provisional(Q, 7)
    assert not is_provisional(Q, 0)
    assert not is_provisional(Q)
    for s in (D, A, F):
        assert not is_provisional(s, 3)


@pytest.mark.parametrize(("before", "after"), [(D, Q), (Q, Q), (A, Q)])
def test_question_start(before: Status, after: Status) -> None:
    assert after_question_start(before) is after


@pytest.mark.parametrize(("before", "after"), [(Q, A), (A, A)])
def test_stop_condition(before: Status, after: Status) -> None:
    assert after_stop_condition(before) is after


@pytest.mark.parametrize("status", [D, F])
def test_stop_condition_invalid(status: Status) -> None:
    with pytest.raises(InvalidTransition):
        after_stop_condition(status)


@pytest.mark.parametrize(("before", "after"), [(D, D), (Q, Q), (A, Q)])
def test_criterion_added(before: Status, after: Status) -> None:
    assert after_criterion_added(before) is after


def test_finalize_only_from_analyzed() -> None:
    assert after_finalize(A) is F
    for s in (D, Q, F):
        with pytest.raises(InvalidTransition):
            after_finalize(s)


def test_finalized_rejects_other_transitions() -> None:
    for fn in (after_question_start, after_criterion_added, round_kind):
        with pytest.raises(InvalidTransition):
            fn(F)


def test_round_kind() -> None:
    assert round_kind(D) == "initial"
    assert round_kind(Q) == "initial"
    assert round_kind(A) == "extra"
    assert round_kind(Q, extra_round_active=True) == "extra"
    assert round_kind(D, extra_round_active=True) == "initial"


@pytest.mark.parametrize("status", STATUSES)
def test_never_back_to_draft(status: Status) -> None:
    """어떤 전이 함수도 DRAFT가 아닌 상태에서 DRAFT를 돌려주지 않는다."""
    results: list[Status] = []
    for fn in (after_question_start, after_stop_condition, after_criterion_added, after_finalize):
        try:
            results.append(fn(status))
        except InvalidTransition:
            continue
    if status is not D:
        assert D not in results
    assert after_question_start(D) is Q  # DRAFT에서도 시작 이후에는 DRAFT가 아님


def test_full_walk() -> None:
    s = D
    s = after_question_start(s)
    s = after_criterion_added(s)
    assert s is Q
    s = after_stop_condition(s)
    s = after_criterion_added(s)  # 추가 라운드
    assert s is Q
    s = after_stop_condition(s)
    assert after_finalize(s) is F

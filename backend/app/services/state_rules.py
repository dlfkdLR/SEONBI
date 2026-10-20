"""결정 상태 머신 규칙 (개발기획서 4.2). 순수 함수만 두고 I/O는 하지 않는다.

- `check`: "API별 상태 제약" 표의 칸을 그대로 옮긴 검사. 위반이면 `Violation`, 허용이면 None.
- `after_*`: 전이 결과 상태. 허용되지 않는 전이는 `InvalidTransition`을 낸다(먼저 `check`를 했다면 일어나지 않는다).
- `QUESTIONING → DRAFT` 전이는 없다. 한번 질문을 시작한 결정은 DRAFT로 돌아가지 않는다.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from typing import Literal


class Status(StrEnum):
    DRAFT = "DRAFT"
    QUESTIONING = "QUESTIONING"
    ANALYZED = "ANALYZED"
    FINALIZED = "FINALIZED"


class Action(StrEnum):
    """4.2 "API별 상태 제약" 표의 행 (괄호는 API 번호)."""

    CONFIG_EDIT = "CONFIG_EDIT"  # 12, 14~17, 19~22, 25, 27
    FREE_INPUT = "FREE_INPUT"  # 28~30
    QUESTION_START = "QUESTION_START"  # 31
    QUESTION_READ = "QUESTION_READ"  # 32·33·34
    ANSWER_SUBMIT_OR_STOP = "ANSWER_SUBMIT_OR_STOP"  # 37·35
    ANSWER_EDIT = "ANSWER_EDIT"  # 38
    ANALYSIS_READ = "ANALYSIS_READ"  # 39~45
    SIMULATE = "SIMULATE"  # 46
    FINALIZE = "FINALIZE"  # 47
    EXPORT = "EXPORT"  # 48
    DELETE_OR_DUPLICATE = "DELETE_OR_DUPLICATE"  # 13·49
    READ = "READ"  # 10·11·15·20·24·26·36 — 표에 없는 조회, 모든 상태에서 가능


ErrorCode = Literal["DECISION_FINALIZED", "INVALID_STATE", "NOT_READY"]
RoundKind = Literal["initial", "extra"]

MIN_ANSWERS_FOR_ANALYSIS = 1  # QUESTIONING 중 분석 조회에 필요한 답변 수 (4.2)


@dataclass(frozen=True)
class Violation:
    http_status: int
    code: ErrorCode


class InvalidTransition(ValueError):
    """허용되지 않는 상태 전이."""


_FINALIZED = Violation(409, "DECISION_FINALIZED")
_INVALID_STATE = Violation(409, "INVALID_STATE")
_NOT_READY = Violation(422, "NOT_READY")

_S = Status

# 표의 칸을 그대로 옮긴 것. 값이 없으면 가능. ANALYSIS_READ·SIMULATE의 QUESTIONING 칸은 답변 수로 따로 판단한다.
_TABLE: dict[Action, dict[Status, Violation]] = {
    Action.CONFIG_EDIT: {_S.FINALIZED: _FINALIZED},
    Action.FREE_INPUT: {_S.FINALIZED: _FINALIZED},
    Action.QUESTION_START: {_S.FINALIZED: _FINALIZED},  # DRAFT는 readiness가 필요하지만 그 검사는 B 담당
    Action.QUESTION_READ: {_S.DRAFT: _NOT_READY},
    Action.ANSWER_SUBMIT_OR_STOP: {_S.DRAFT: _INVALID_STATE, _S.ANALYZED: _INVALID_STATE, _S.FINALIZED: _FINALIZED},
    Action.ANSWER_EDIT: {_S.DRAFT: _INVALID_STATE, _S.FINALIZED: _FINALIZED},
    Action.ANALYSIS_READ: {_S.DRAFT: _NOT_READY},
    Action.SIMULATE: {_S.DRAFT: _NOT_READY},
    Action.FINALIZE: {_S.DRAFT: _INVALID_STATE, _S.QUESTIONING: _INVALID_STATE, _S.FINALIZED: _FINALIZED},
    Action.EXPORT: {_S.DRAFT: _NOT_READY, _S.QUESTIONING: _NOT_READY},
    Action.DELETE_OR_DUPLICATE: {},
    Action.READ: {},
}

_NEEDS_ANSWERS = frozenset({Action.ANALYSIS_READ, Action.SIMULATE})


def check(action: Action, status: Status, *, answered: int = 0) -> Violation | None:
    """작업이 이 상태에서 허용되면 None, 아니면 위반(HTTP 상태·오류 코드).

    answered는 QUESTIONING 중 분석 조회·시뮬레이션(ANALYSIS_READ·SIMULATE)에서만 쓴다: 0개면 422 NOT_READY.
    세는 기준은 호출하는 쪽이 정한다(유효 답변 수; 자유 입력의 가상 답변도 추정에 쓰이므로 포함하는 것을 가정).
    """
    if action in _NEEDS_ANSWERS and status is Status.QUESTIONING and answered < MIN_ANSWERS_FOR_ANALYSIS:
        return _NOT_READY
    return _TABLE[action].get(status)


def is_provisional(status: Status, answered: int = 0) -> bool:
    """분석 응답의 `provisional`. QUESTIONING에서 답변이 1개 이상일 때만 잠정 결과다."""
    return status is Status.QUESTIONING and answered >= MIN_ANSWERS_FOR_ANALYSIS


def after_question_start(status: Status) -> Status:
    """31번. DRAFT → QUESTIONING, QUESTIONING 유지(멱등), ANALYZED → QUESTIONING(추가 질문 라운드)."""
    if status is Status.FINALIZED:
        raise InvalidTransition("FINALIZED에서는 질문을 시작할 수 없다")
    return Status.QUESTIONING


def after_stop_condition(status: Status) -> Status:
    """37·32·35번의 종료. QUESTIONING → ANALYZED. 이미 ANALYZED면 그대로(32번이 done=true만 돌려주는 경우)."""
    if status in (Status.QUESTIONING, Status.ANALYZED):
        return Status.ANALYZED
    raise InvalidTransition(f"{status}에서는 질문을 끝낼 수 없다")


def after_criterion_added(status: Status) -> Status:
    """19·29번의 기준 추가. ANALYZED → QUESTIONING(추가 라운드), DRAFT·QUESTIONING은 그대로."""
    if status is Status.FINALIZED:
        raise InvalidTransition("FINALIZED에서는 기준을 추가할 수 없다")
    return Status.QUESTIONING if status is Status.ANALYZED else status


def after_finalize(status: Status) -> Status:
    """47번. ANALYZED → FINALIZED만 된다."""
    if status is Status.ANALYZED:
        return Status.FINALIZED
    raise InvalidTransition(f"{status}에서는 확정할 수 없다")


def round_kind(status: Status, *, extra_round_active: bool = False) -> RoundKind:
    """31번 응답의 `round`. ANALYZED에서 호출하면 추가 라운드(extra), DRAFT에서는 첫 라운드(initial).

    QUESTIONING에서 31번을 다시 부르면(멱등) 상태만으로는 첫 라운드인지 추가 라운드인지 알 수 없다.
    명세에 이를 가르는 필드가 없어 호출하는 쪽이 `extra_round_active`로 알려 준다(기본 initial).
    """
    if status is Status.FINALIZED:
        raise InvalidTransition("FINALIZED에서는 질문 라운드가 없다")
    if status is Status.ANALYZED or (status is Status.QUESTIONING and extra_round_active):
        return "extra"
    return "initial"

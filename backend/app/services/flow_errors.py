"""질문 흐름 서비스의 오류 (개발기획서 7.1 공통 오류 형식).

서비스는 FastAPI를 모른다. 라우터 계층이 `FlowError`를 7.1의 오류 JSON
(`{"error": {"code", "message", "details"}}`)과 HTTP 상태로 바꾼다.
"""

from __future__ import annotations

from typing import Any, Literal

from .state_rules import Violation

FlowErrorCode = Literal[
    "VALIDATION_ERROR",
    "NOT_FOUND",
    "INVALID_STATE",
    "DECISION_FINALIZED",
    "QUESTION_ALREADY_ANSWERED",
    "NOT_READY",
]

_VIOLATION_MESSAGES: dict[str, str] = {
    "DECISION_FINALIZED": "확정된 결정은 수정할 수 없습니다.",
    "INVALID_STATE": "현재 결정 상태에서는 할 수 없는 작업입니다.",
    "NOT_READY": "아직 이 작업을 할 준비가 되지 않았습니다.",
}


class FlowError(Exception):
    def __init__(self, http_status: int, code: FlowErrorCode, message: str, details: dict[str, Any] | None = None):
        super().__init__(f"{http_status} {code}: {message}")
        self.http_status = http_status
        self.code: FlowErrorCode = code
        self.message = message
        self.details = details

    def to_body(self) -> dict[str, Any]:
        """7.1 오류 JSON 본문. details가 없으면 키를 뺀다."""
        err: dict[str, Any] = {"code": self.code, "message": self.message}
        if self.details is not None:
            err["details"] = self.details
        return {"error": err}


def from_violation(violation: Violation, details: dict[str, Any] | None = None) -> FlowError:
    """state_rules.Violation → FlowError (한 곳에서만 바꾼다)."""
    return FlowError(violation.http_status, violation.code, _VIOLATION_MESSAGES[violation.code], details)


def raise_if_violation(violation: Violation | None, details: dict[str, Any] | None = None) -> None:
    if violation is not None:
        raise from_violation(violation, details)


def validation_error(message: str, details: dict[str, Any] | None = None) -> FlowError:
    return FlowError(400, "VALIDATION_ERROR", message, details)


def not_found(message: str, details: dict[str, Any] | None = None) -> FlowError:
    return FlowError(404, "NOT_FOUND", message, details)

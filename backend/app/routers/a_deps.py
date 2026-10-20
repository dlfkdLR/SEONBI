"""A 라우터(31~46번)가 쓰는 FastAPI 의존성 자리표시자와 오류 처리기 (개발기획서 7.1, 12.2).

이 모듈은 백엔드 B가 실제 구현(DB 세션, `get_decision`, 준비 상태 계산 등)을 붙이기 전까지의 **자리표시자**다.
B는 아래 둘 중 하나로 바꿀 수 있다.

1. `app.dependency_overrides[a_deps.get_snapshot] = real_get_snapshot` 처럼 같은 함수 객체를 덮어쓴다(권장).
   덮어쓰는 함수의 시그니처는 이 모듈의 것과 같은 매개변수 이름(`decision_id`, `client_id` 등)을 쓴다.
2. 이 모듈의 함수 본문을 B의 구현으로 직접 교체한다. 라우터는 이 모듈의 이름만 import 하므로 그대로 동작한다.

오류 처리기(`flow_error_handler`, `register_a_error_handlers`)도 B가 `app/core`의 공통 처리기로 바꿔도 된다.
단 본문 형식은 7.1의 `{"error": {"code", "message", "details"}}`를 지켜야 한다.
"""

from __future__ import annotations

import uuid
from collections.abc import Sequence
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.services.flow_errors import FlowError
from app.services.ports import DecisionSnapshot, Stores
from app.services.question_flow import Phraser


def _client_id_error(message: str) -> FlowError:
    # flow_errors.FlowErrorCode(A 소유)에 CLIENT_ID_REQUIRED가 없어 cast로 넘긴다. 런타임에는 문제없다.
    # 코드 목록에 추가되면 cast를 지운다.
    return FlowError(400, "CLIENT_ID_REQUIRED", message)


async def get_client_id(x_client_id: Annotated[str | None, Header(alias="X-Client-Id")] = None) -> str:
    """`X-Client-Id` 헤더(UUID) 검사. 없거나 형식이 틀리면 400 CLIENT_ID_REQUIRED (7.1, 3.1)."""
    if x_client_id is None or not x_client_id.strip():
        raise _client_id_error("X-Client-Id 헤더가 필요합니다.")
    try:
        return str(uuid.UUID(x_client_id.strip()))
    except ValueError:
        raise _client_id_error("X-Client-Id 헤더 형식이 올바르지 않습니다.") from None


async def get_snapshot(decision_id: str, client_id: Annotated[str, Depends(get_client_id)]) -> DecisionSnapshot:
    """결정 스냅샷 조회 (12.2의 `get_decision`). B가 구현한다.

    구현 규칙: `WHERE id = :decision_id AND client_id = :client_id AND deleted_at IS NULL`,
    없으면 `FlowError(404, "NOT_FOUND", ...)` (다른 클라이언트의 결정도 404, 7.1).
    """
    raise NotImplementedError("B가 구현")


async def get_stores(request: Request) -> Stores:
    """요청 하나에서 쓰는 저장소 묶음(`ports.Stores`). B가 DB 세션으로 구현한다."""
    raise NotImplementedError("B가 구현")


async def get_phraser() -> Phraser | None:
    """질문 문장 다듬기(AI) 함수. 기본은 None(템플릿 문장만 사용). B가 6.2의 phrase_question을 묶어 줄 수 있다."""
    return None


async def get_llm() -> Any | None:
    """LLM 클라이언트(분석 설명 등 39번대에서 사용). 기본은 None(템플릿 fallback)."""
    return None


async def get_readiness_missing(snapshot: Annotated[DecisionSnapshot, Depends(get_snapshot)]) -> Sequence[str]:
    """31번 `NOT_READY`의 `missing` 목록. B가 readiness 계산으로 바꾼다. 기본은 빈 목록(준비됨으로 본다)."""
    return []


# --------------------------------------------------------------------------------------------------
# 오류 처리기 (7.1 공통 오류 형식)
# --------------------------------------------------------------------------------------------------


async def flow_error_handler(request: Request, exc: Exception) -> JSONResponse:
    """`FlowError` → HTTP 상태 + `{"error": {"code", "message", "details"}}`."""
    assert isinstance(exc, FlowError)
    return JSONResponse(status_code=exc.http_status, content=exc.to_body())


async def validation_error_handler(request: Request, exc: Exception) -> JSONResponse:
    """FastAPI 요청 검증 실패 → 400 VALIDATION_ERROR (details.fields에 필드별 오류, 7.1).

    FastAPI 기본값은 422이지만 명세는 형식·범위 오류를 400으로 정한다.
    """
    assert isinstance(exc, RequestValidationError)
    fields = [
        {
            "field": ".".join(str(p) for p in err["loc"]),
            "message": err["msg"],
            "type": err["type"],
        }
        for err in exc.errors()
    ]
    err = FlowError(400, "VALIDATION_ERROR", "요청 형식이 올바르지 않습니다.", {"fields": fields})
    return JSONResponse(status_code=400, content=err.to_body())


def register_a_error_handlers(app: FastAPI) -> None:
    """앱에 `FlowError`와 `RequestValidationError` 처리기를 등록한다 (B가 공통 처리기로 대체 가능)."""
    app.add_exception_handler(FlowError, flow_error_handler)
    app.add_exception_handler(RequestValidationError, validation_error_handler)

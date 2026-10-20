"""질문 API 31~35번 (개발기획서 7.2·7.3 "질문·답변").

`router`는 Base URL(`/api/v1`)을 **붙이지 않는다**. B가 `app.include_router(router, prefix="/api/v1")`로 붙인다.
상태·검증 로직은 모두 `services.question_flow`에 있고, 여기서는 의존성 주입과 응답 모델 연결만 한다.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Annotated, Any

from fastapi import APIRouter, Depends

from app.routers.a_deps import get_phraser, get_readiness_missing, get_snapshot, get_stores
from app.schemas.questions import (
    NextQuestionResponse,
    ProgressResponse,
    StartQuestionsResponse,
    StatusResponse,
    StopResponse,
)
from app.services import question_flow as qf
from app.services.ports import DecisionSnapshot, Stores
from app.services.question_flow import Phraser

router = APIRouter(
    prefix="/decisions/{decision_id}",
    tags=["질문·답변"],
    responses={
        400: {"description": "CLIENT_ID_REQUIRED 등 형식 오류 (7.1)"},
        404: {"description": "NOT_FOUND: 결정 없음 또는 다른 클라이언트의 결정"},
        409: {"description": "DECISION_FINALIZED, INVALID_STATE"},
    },
)

Snapshot = Annotated[DecisionSnapshot, Depends(get_snapshot)]
StoresDep = Annotated[Stores, Depends(get_stores)]
PhraserDep = Annotated[Phraser | None, Depends(get_phraser)]
MissingDep = Annotated[Sequence[str], Depends(get_readiness_missing)]


@router.post(
    "/questions/start",
    operation_id="startQuestions",
    summary="31. 질문 시작",
    response_model=StartQuestionsResponse,
    response_model_by_alias=True,
    status_code=200,  # 명세: 200 (이미 QUESTIONING이면 현재 질문을 그대로 반환하는 멱등 호출이라 201이 아니다)
)
async def start_questions(
    snapshot: Snapshot, stores: StoresDep, phraser: PhraserDep, missing: MissingDep
) -> dict[str, Any]:
    """준비되지 않았으면 `422 NOT_READY`(missing 포함), FINALIZED면 `409 DECISION_FINALIZED`."""
    return await qf.start_questions(snapshot, stores, missing=missing, phraser=phraser)


@router.get(
    "/questions/next",
    operation_id="getNextQuestion",
    summary="32. 다음 질문 조회",
    response_model=NextQuestionResponse,
    response_model_by_alias=True,
)
async def get_next_question(snapshot: Snapshot, stores: StoresDep, phraser: PhraserDep) -> dict[str, Any]:
    """미답변 질문이 있으면 그대로 반환. 종료 조건을 처음 확인하면 ANALYZED로 바꾼다."""
    return await qf.next_question(snapshot, stores, phraser=phraser)


@router.get(
    "/questions/status",
    operation_id="getQuestionStatus",
    summary="33. 질문 종료 판단",
    response_model=StatusResponse,
    response_model_by_alias=True,
)
async def get_question_status(snapshot: Snapshot, stores: StoresDep) -> dict[str, Any]:
    return await qf.question_status(snapshot, stores)


@router.get(
    "/questions/progress",
    operation_id="getQuestionProgress",
    summary="34. 질문 진행률 조회",
    response_model=ProgressResponse,
    response_model_by_alias=True,
)
async def get_question_progress(snapshot: Snapshot, stores: StoresDep) -> dict[str, Any]:
    return await qf.question_progress(snapshot, stores)


@router.post(
    "/questions/stop",
    operation_id="stopQuestions",
    summary="35. 질문 조기 종료",
    response_model=StopResponse,
    response_model_by_alias=True,
    status_code=200,
)
async def stop_questions(snapshot: Snapshot, stores: StoresDep) -> dict[str, Any]:
    return await qf.stop_questions(snapshot, stores)

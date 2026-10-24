"""답변 API 36~38번 (개발기획서 7.2·7.3 "질문·답변").

`router`는 Base URL(`/api/v1`)을 붙이지 않는다. B가 `include_router(router, prefix="/api/v1")`로 붙인다.
"""

from __future__ import annotations

from typing import Annotated, Any

from fastapi import APIRouter, Depends

from app.routers.a_deps import get_phraser, get_snapshot, get_stores
from app.schemas.answers import (
    EditAnswerRequest,
    EditAnswerResponse,
    SubmitAnswerRequest,
    SubmitAnswerResponse,
)
from app.schemas.questions import AnswerListResponse
from app.services import question_flow as qf
from app.services.ports import DecisionSnapshot, Stores
from app.services.question_flow import Phraser

router = APIRouter(
    prefix="/decisions/{decision_id}",
    tags=["질문·답변"],
    responses={
        400: {"description": "VALIDATION_ERROR, CLIENT_ID_REQUIRED (7.1)"},
        404: {"description": "NOT_FOUND: 결정·질문·답변 없음 또는 다른 클라이언트의 결정"},
        409: {"description": "DECISION_FINALIZED, INVALID_STATE, QUESTION_ALREADY_ANSWERED"},
    },
)

Snapshot = Annotated[DecisionSnapshot, Depends(get_snapshot)]
StoresDep = Annotated[Stores, Depends(get_stores)]
PhraserDep = Annotated[Phraser | None, Depends(get_phraser)]


@router.get(
    "/answers",
    operation_id="listAnswers",
    summary="36. 답변 목록 조회",
    response_model=AnswerListResponse,
    response_model_by_alias=True,
)
async def list_answers(snapshot: Snapshot, stores: StoresDep) -> dict[str, Any]:
    return await qf.list_answers(snapshot, stores)


@router.post(
    "/answers",
    operation_id="submitAnswer",
    summary="37. 질문 답변 제출",
    response_model=SubmitAnswerResponse,
    response_model_by_alias=True,
    status_code=201,
)
async def submit_answer(
    body: SubmitAnswerRequest, snapshot: Snapshot, stores: StoresDep, phraser: PhraserDep
) -> dict[str, Any]:
    # model_dump는 AnswerValue의 직렬화기 덕에 값이 있는 키 하나만 낸다 ({"choice": "B"} 등).
    return await qf.submit_answer(snapshot, stores, body.question_id, body.response.model_dump(), phraser=phraser)


@router.patch(
    "/answers/{answer_id}",
    operation_id="editAnswer",
    summary="38. 질문 답변 수정",
    response_model=EditAnswerResponse,
    response_model_by_alias=True,
)
async def edit_answer(answer_id: str, body: EditAnswerRequest, snapshot: Snapshot, stores: StoresDep) -> dict[str, Any]:
    return await qf.edit_answer(snapshot, stores, answer_id, body.response.model_dump())

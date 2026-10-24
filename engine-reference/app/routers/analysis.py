"""분석 API 39~46번 (개발기획서 7.2·7.3 "분석").

`router`는 Base URL(`/api/v1`)을 붙이지 않는다. B가 `include_router(router, prefix="/api/v1")`로 붙인다.
상태 검사·캐시·AI 대체는 모두 `analysis_flow`가 한다. 이 모듈은 HTTP 연결만 맡는다.
"""

from __future__ import annotations

from typing import Annotated, Any

from fastapi import APIRouter, Depends, Query

from app.routers.a_deps import get_llm, get_snapshot, get_stores
from app.schemas.analysis import (
    AnalysisResponse,
    ChangeConditionsResponse,
    RankingsResponse,
    ScoresResponse,
    SensitivityResponse,
    SimulationRequest,
    SimulationResponse,
    StabilityResponse,
    WeightsResponse,
)
from app.services import analysis_flow as af
from app.services.ports import DecisionSnapshot, Stores

router = APIRouter(
    prefix="/decisions/{decision_id}",
    tags=["분석"],
    responses={
        400: {"description": "VALIDATION_ERROR, CLIENT_ID_REQUIRED (7.1)"},
        404: {"description": "NOT_FOUND: 결정 없음 또는 다른 클라이언트의 결정"},
        422: {"description": "NOT_READY: 분석할 만큼 입력되지 않음 (4.2)"},
    },
)

Snapshot = Annotated[DecisionSnapshot, Depends(get_snapshot)]
StoresDep = Annotated[Stores, Depends(get_stores)]
# 39번만 AI 문장(optionSummaries·explanation)을 만든다. None이면 템플릿 문장.
# 가정: LLM 호출 사용량 기록(on_usage)은 B가 get_llm이 돌려주는 객체 쪽에서 처리한다(명세에 라우터 인자 없음).
LlmDep = Annotated[Any, Depends(get_llm)]


@router.get(
    "/analysis",
    operation_id="getAnalysis",
    summary="39. 분석 결과 조회(종합)",
    response_model=AnalysisResponse,
    response_model_by_alias=True,
)
async def get_analysis(snapshot: Snapshot, stores: StoresDep, llm: LlmDep) -> dict[str, Any]:
    return await af.get_analysis(snapshot, stores, llm=llm)


@router.get(
    "/weights",
    operation_id="getWeights",
    summary="40. 가중치 조회",
    response_model=WeightsResponse,
    response_model_by_alias=True,
)
async def get_weights(snapshot: Snapshot, stores: StoresDep) -> dict[str, Any]:
    return await af.get_weights(snapshot, stores)


@router.get(
    "/rankings",
    operation_id="getRankings",
    summary="41. 순위 조회",
    response_model=RankingsResponse,
    response_model_by_alias=True,
)
async def get_rankings(snapshot: Snapshot, stores: StoresDep) -> dict[str, Any]:
    return await af.get_rankings(snapshot, stores)


@router.get(
    "/scores",
    operation_id="getScores",
    summary="42. 점수 상세 조회",
    response_model=ScoresResponse,
    response_model_by_alias=True,
)
async def get_scores(snapshot: Snapshot, stores: StoresDep) -> dict[str, Any]:
    return await af.get_scores(snapshot, stores)


@router.get(
    "/sensitivity",
    operation_id="getSensitivity",
    summary="43. 민감도 분석 조회",
    response_model=SensitivityResponse,
    response_model_by_alias=True,
)
async def get_sensitivity(
    snapshot: Snapshot, stores: StoresDep, criterion_id: Annotated[str | None, Query(alias="criterionId")] = None
) -> dict[str, Any]:
    return await af.get_sensitivity(snapshot, stores, criterion_id)


@router.get(
    "/stability",
    operation_id="getStability",
    summary="44. 추천 안정성 조회",
    response_model=StabilityResponse,
    response_model_by_alias=True,
)
async def get_stability(snapshot: Snapshot, stores: StoresDep) -> dict[str, Any]:
    return await af.get_stability(snapshot, stores)


@router.get(
    "/change-conditions",
    operation_id="getChangeConditions",
    summary="45. 결과가 바뀌는 조건 조회",
    response_model=ChangeConditionsResponse,
    response_model_by_alias=True,
)
async def get_change_conditions(snapshot: Snapshot, stores: StoresDep) -> dict[str, Any]:
    return await af.get_change_conditions(snapshot, stores)


@router.post(
    "/simulations",
    operation_id="simulate",
    summary="46. 가중치 시뮬레이션",
    response_model=SimulationResponse,
    response_model_by_alias=True,
)
async def simulate(body: SimulationRequest, snapshot: Snapshot, stores: StoresDep) -> dict[str, Any]:
    # 서비스는 camelCase dict를 받는다. 저장하지 않으므로 200 (명세 7.2).
    return await af.simulate(snapshot, stores, body.model_dump(by_alias=True)["weights"])

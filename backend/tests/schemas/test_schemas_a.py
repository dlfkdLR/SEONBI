"""개발자 A 스키마(31~46번): 7.3 예시 왕복, 잘못된 입력 거부, 서비스 함수 출력 검증, OpenAPI 스키마 생성."""

from __future__ import annotations

import importlib.util
from pathlib import Path
from typing import Any

import pytest
from pydantic import BaseModel, TypeAdapter, ValidationError

from app.engine.decision_analysis import change_conditions, value_conditions
from app.engine.likelihood import CriteriaABSpec, ScenarioSpec, SliderSpec
from app.engine.normalize import Scale
from app.engine.posterior import Answer, fit, prior_particles, summary
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
from app.schemas.answers import (
    EditAnswerRequest,
    EditAnswerResponse,
    Estimate,
    SubmitAnswerRequest,
    SubmitAnswerResponse,
)
from app.schemas.questions import (
    AnswerListResponse,
    NextQuestionResponse,
    ProgressResponse,
    QuestionObject,
    StartQuestionsResponse,
    StatusResponse,
    StopResponse,
)
from app.services import analysis_helpers as ah
from app.services.question_text import CriterionMeta, build_question

_spec = importlib.util.spec_from_file_location("example_data", Path(__file__).parents[1] / "engine" / "example_data.py")
assert _spec is not None and _spec.loader is not None
example_data = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(example_data)

TS = "2026-10-20T01:02:03Z"


def roundtrip(model: type[BaseModel] | Any, data: Any) -> None:
    """검증 → 별칭 JSON 덤프가 입력과 같은지 확인."""
    adapter = TypeAdapter(model)
    obj = adapter.validate_python(data)
    assert adapter.dump_python(obj, mode="json", by_alias=True, exclude_none=False) == data


SLIDER_Q = {
    "id": "q3",
    "seq": 1,
    "type": "slider_pair",
    "criteria": ["c1", "c2"],
    "title": "가격 차이(150~189만 원)와 무게 차이(1.19~1.45kg) 중 어느 쪽이 더 중요한가요?",
    "payload": {
        "left": {"criterionId": "c1", "label": "가격", "range": "150~189만 원"},
        "right": {"criterionId": "c2", "label": "무게", "range": "1.19~1.45kg"},
        "min": -5,
        "max": 5,
    },
    "allowFreeText": True,
    "allowSkip": True,
}
AB_Q = {
    "id": "q5",
    "seq": 2,
    "type": "ab_criteria",
    "criteria": ["c2", "c3"],
    "title": "무엇이 더 중요한가요?",
    "payload": {"optionA": {"criterionId": "c2", "label": "무게"}, "optionB": {"criterionId": "c3", "label": "성능"}},
    "allowFreeText": True,
    "allowSkip": True,
}
SCEN_Q = {
    "id": "q7",
    "seq": 4,
    "type": "ab_scenario",
    "criteria": ["c1", "c4"],
    "title": "다음 두 노트북 중 어느 쪽을 고르시겠어요?",
    "payload": {
        "optionA": {
            "label": "A",
            "description": "20만 원 더 저렴하지만 300g 더 무거움",
            "deltas": [{"criterionId": "c1", "display": "-20만 원"}, {"criterionId": "c4", "display": "+0.3kg"}],
        },
        "optionB": {
            "label": "B",
            "description": "20만 원 더 비싸지만 300g 더 가벼움",
            "deltas": [{"criterionId": "c1", "display": "+20만 원"}, {"criterionId": "c4", "display": "-0.3kg"}],
        },
    },
    "allowFreeText": True,
    "allowSkip": True,
}
ESTIMATE = {
    "weights": [{"criterionId": "c1", "mean": 0.31, "interval": [0.22, 0.4]}],
    "topProbability": 0.78,
}
WEIGHT_ITEM = {
    "criterionId": "c1",
    "name": "가격",
    "mean": 0.25,
    "interval": [0.2, 0.31],
    "statedRank": 1,
    "revealedRank": 3,
}
RANK_ITEMS = [
    {"rank": 1, "optionId": "o2", "name": "갤럭시북5 프로", "score": 0.68, "topProbability": 0.78},
    {"rank": 2, "optionId": "o3", "name": "LG 그램 16", "score": 0.61, "topProbability": 0.22},
]
STABILITY = {
    "optionId": "o2",
    "score": 0.78,
    "level": "MEDIUM",
    "localPerturbationKeepRate": 0.86,
    "competitors": [{"optionId": "o3", "probability": 0.22}],
    "text": "가격과 무게 중 무엇을 더 중시하는지에 따라 1위가 바뀔 수 있어요.",
}
CHANGE_ITEMS = [
    {
        "type": "weight",
        "criterionId": "c1",
        "currentWeight": 0.25,
        "threshold": 0.31,
        "direction": "increase",
        "newTop": "o3",
        "text": "가격 중요도가 25% → 31% 이상이 되면 LG 그램 16이 1위가 됩니다.",
    },
    {
        "type": "weight",
        "criterionId": "c3",
        "threshold": None,
        "text": "배터리 중요도가 어떻게 바뀌어도 1위가 유지됩니다.",
    },
    {
        "type": "value",
        "optionId": "o3",
        "criterionId": "c1",
        "currentValue": 210,
        "threshold": 198,
        "text": "LG 그램 16의 가격이 12만 원 이상 내려가면 1위가 됩니다.",
    },
]


# ------------------------------------------------------------------ 질문 객체 · 31~36
@pytest.mark.parametrize("q", [SLIDER_Q, AB_Q, SCEN_Q], ids=["slider", "ab_criteria", "ab_scenario"])
def test_question_object_roundtrip(q: dict[str, Any]) -> None:
    roundtrip(QuestionObject, q)


def test_question_object_payload_must_match_type() -> None:
    with pytest.raises(ValidationError):
        QuestionObject.model_validate({**SLIDER_Q, "payload": AB_Q["payload"]})
    with pytest.raises(ValidationError):
        QuestionObject.model_validate({**AB_Q, "payload": SCEN_Q["payload"]})
    with pytest.raises(ValidationError):
        QuestionObject.model_validate({**SLIDER_Q, "type": "bogus"})
    # numeric / free_text: 형식이 명세에 없어 자유 객체
    roundtrip(QuestionObject, {**SLIDER_Q, "type": "free_text", "payload": {"hint": "x"}})
    with pytest.raises(ValidationError):
        QuestionObject.model_validate({**SLIDER_Q, "type": "numeric", "payload": ["x"]})


def test_question_object_accepts_python_names() -> None:
    q = QuestionObject.model_validate(SLIDER_Q)
    again = QuestionObject(**{**q.model_dump(), "id": "q9"})
    assert again.id == "q9" and again.allow_free_text is True


def test_start_response() -> None:
    data = {
        "status": "QUESTIONING",
        "round": "initial",
        "question": SLIDER_Q,
        "progress": {"answered": 0, "expectedRange": [3, 8]},
    }
    roundtrip(StartQuestionsResponse, data)
    roundtrip(StartQuestionsResponse, {**data, "round": "extra"})
    with pytest.raises(ValidationError):
        StartQuestionsResponse.model_validate({**data, "round": "later"})
    with pytest.raises(ValidationError):
        StartQuestionsResponse.model_validate({**data, "progress": {"answered": 0, "expectedRange": [3]}})


def test_next_question_union() -> None:
    roundtrip(NextQuestionResponse, {"done": False, "question": SLIDER_Q})
    roundtrip(NextQuestionResponse, {"done": True, "reason": "CONFIDENT"})
    ta = TypeAdapter(NextQuestionResponse)
    with pytest.raises(ValidationError):
        ta.validate_python({"done": True, "question": SLIDER_Q})
    with pytest.raises(ValidationError):
        ta.validate_python({"done": False, "reason": "CONFIDENT"})
    with pytest.raises(ValidationError):
        ta.validate_python({"done": True, "reason": "NOPE"})


def test_status_progress_stop() -> None:
    status = {
        "shouldStop": False,
        "reason": None,
        "metrics": {
            "answered": 4,
            "topOption": "o2",
            "topProbability": 0.71,
            "decisionEntropy": 0.89,
            "bestNextInfoGain": 0.21,
        },
        "remainingUncertainty": [{"criterionId": "c2", "interval": [0.25, 0.55]}],
    }
    roundtrip(StatusResponse, status)
    roundtrip(StatusResponse, {**status, "shouldStop": True, "reason": "LOW_INFO_GAIN"})
    roundtrip(ProgressResponse, {"answered": 4, "expectedRange": [3, 8], "confidence": 0.71})
    roundtrip(StopResponse, {"reason": "USER_STOPPED", "lowConfidence": True})


def test_answer_list() -> None:
    data = {
        "items": [
            {
                "id": "a1",
                "questionId": "q3",
                "question": {**SLIDER_Q, "origin": "engine"},
                "response": {"value": -2},
                "isValid": True,
                "createdAt": TS,
            },
            {
                "id": "a2",
                "questionId": "q7",
                "question": {**SCEN_Q, "origin": "free_text"},
                "response": {"choice": "B"},
                "isValid": False,
                "createdAt": TS,
            },
            {
                "id": "a3",
                "questionId": "q5",
                "question": {**AB_Q, "origin": "engine"},
                "response": {"skip": True},
                "isValid": True,
                "createdAt": TS,
            },
        ]
    }
    roundtrip(AnswerListResponse, data)
    bad = {"items": [{**data["items"][0], "question": SLIDER_Q}]}  # origin 없음
    with pytest.raises(ValidationError):
        AnswerListResponse.model_validate(bad)


# ------------------------------------------------------------------ 37 · 38
@pytest.mark.parametrize(
    "response",
    [{"choice": "B"}, {"choice": "A"}, {"value": -2}, {"value": 0}, {"value": 5}, {"value": -5}, {"skip": True}],
)
def test_submit_request_valid(response: dict[str, Any]) -> None:
    data = {"questionId": "q7", "response": response}
    roundtrip(SubmitAnswerRequest, data)
    roundtrip(EditAnswerRequest, {"response": response})


@pytest.mark.parametrize(
    "response",
    [
        {},
        {"choice": "A", "value": 1},
        {"choice": "A", "skip": True},
        {"value": 1, "skip": True},
        {"choice": "A", "value": 1, "skip": True},
        {"value": 6},
        {"value": -6},
        {"value": 1.5},
        {"value": True},
        {"choice": "C"},
        {"choice": None},
        {"skip": False},
        {"skip": None},
        {"other": 1},
        {"choice": "A", "other": 1},
    ],
)
def test_submit_request_invalid(response: dict[str, Any]) -> None:
    with pytest.raises(ValidationError):
        SubmitAnswerRequest.model_validate({"questionId": "q7", "response": response})
    with pytest.raises(ValidationError):
        EditAnswerRequest.model_validate({"response": response})


def test_submit_request_missing_or_extra_fields() -> None:
    with pytest.raises(ValidationError):
        SubmitAnswerRequest.model_validate({"response": {"skip": True}})
    with pytest.raises(ValidationError):
        SubmitAnswerRequest.model_validate({"questionId": "q1"})
    with pytest.raises(ValidationError):
        SubmitAnswerRequest.model_validate({"questionId": "q1", "response": {"skip": True}, "x": 1})
    assert SubmitAnswerRequest(question_id="q1", response={"skip": True}).question_id == "q1"  # type: ignore[arg-type]


def test_submit_and_edit_response() -> None:
    answer = {"id": "a7", "questionId": "q7", "response": {"choice": "B"}, "isValid": True, "createdAt": TS}
    roundtrip(
        SubmitAnswerResponse, {"answer": answer, "estimate": ESTIMATE, "next": {"done": False, "question": SCEN_Q}}
    )
    roundtrip(
        SubmitAnswerResponse,
        {"answer": answer, "estimate": ESTIMATE, "next": {"done": True, "reason": "MAX_QUESTIONS"}},
    )
    roundtrip(EditAnswerResponse, {"estimate": ESTIMATE})
    with pytest.raises(ValidationError):
        SubmitAnswerResponse.model_validate({"answer": answer, "estimate": ESTIMATE})


# ------------------------------------------------------------------ 39~46
def test_weights_rankings_scores_sensitivity_stability() -> None:
    roundtrip(
        WeightsResponse,
        {"items": [WEIGHT_ITEM, {**WEIGHT_ITEM, "statedRank": None, "revealedRank": None}], "intervalLevel": 0.8},
    )
    roundtrip(RankingsResponse, {"items": RANK_ITEMS})
    roundtrip(
        ScoresResponse,
        {
            "criteria": ["c1", "c2", "c3", "c4"],
            "items": [
                {
                    "optionId": "o2",
                    "cells": [{"criterionId": "c1", "rawValue": 189, "normalized": 0.51, "contribution": 0.13}],
                }
            ],
        },
    )
    roundtrip(
        SensitivityResponse,
        {
            "items": [
                {
                    "criterionId": "c1",
                    "currentWeight": 0.25,
                    "points": [
                        {"weight": 0.0, "scores": {"o2": 0.72, "o3": 0.58}},
                        {"weight": 0.05, "scores": {"o2": 0.7, "o3": 0.6}},
                    ],
                    "crossovers": [{"weight": 0.31, "from": "o2", "to": "o3"}],
                }
            ]
        },
    )
    roundtrip(StabilityResponse, STABILITY)
    with pytest.raises(ValidationError):
        StabilityResponse.model_validate({**STABILITY, "level": "높음"})


def test_crossover_python_name() -> None:
    from app.schemas.analysis import Crossover

    c = Crossover(weight=0.3, from_="o2", to="o3")
    assert c.model_dump(by_alias=True) == {"weight": 0.3, "from": "o2", "to": "o3"}


def test_change_conditions() -> None:
    data = {"currentTop": "o2", "items": CHANGE_ITEMS}
    roundtrip(ChangeConditionsResponse, data)
    parsed = ChangeConditionsResponse.model_validate(data)
    assert [type(i).__name__ for i in parsed.items] == [
        "WeightChangeCondition",
        "WeightNoThresholdCondition",
        "ValueChangeCondition",
    ]
    # 임계값이 있는 가중치 항목에서 키가 빠지면 거부
    broken = {k: v for k, v in CHANGE_ITEMS[0].items() if k != "newTop"}
    with pytest.raises(ValidationError):
        ChangeConditionsResponse.model_validate({"currentTop": "o2", "items": [broken]})


ANALYSIS = {
    "provisional": False,
    "recommendation": {"optionId": "o2", "name": "갤럭시북5 프로", "topProbability": 0.78},
    "rankings": RANK_ITEMS,
    "weights": [WEIGHT_ITEM],
    "stability": STABILITY,
    "changeConditions": CHANGE_ITEMS,
    "hiddenPreferences": [
        {
            "criterionA": "c1",
            "criterionB": "c4",
            "stated": "A_MORE_IMPORTANT",
            "revealed": "B_MORE_IMPORTANT",
            "strength": 0.82,
            "text": "가격이 더 중요하다고 답했지만, 실제 선택에서는 무게를 위해 가격을 양보하는 경향이 보였어요.",
        }
    ],
    "excludedOptions": [{"optionId": "o1", "reasons": ["Windows 지원 미충족"]}],
    "optionSummaries": [{"optionId": "o2", "pros": ["가벼움"], "cons": ["비쌈"]}],
    "explanation": {"summary": "요약", "reasons": ["이유"], "caveats": ["주의"], "source": "ai"},
    "lowConfidence": False,
    "computedAt": TS,
}


def test_analysis_response() -> None:
    roundtrip(AnalysisResponse, ANALYSIS)
    roundtrip(
        AnalysisResponse,
        {**ANALYSIS, "provisional": True, "explanation": {**ANALYSIS["explanation"], "source": "template"}},
    )  # type: ignore[dict-item]
    with pytest.raises(ValidationError):
        AnalysisResponse.model_validate({k: v for k, v in ANALYSIS.items() if k != "stability"})
    with pytest.raises(ValidationError):
        AnalysisResponse.model_validate({**ANALYSIS, "explanation": {**ANALYSIS["explanation"], "source": "llm"}})  # type: ignore[dict-item]


def test_simulation() -> None:
    req = {"weights": [{"criterionId": "c1", "weight": 0.4}, {"criterionId": "c2", "weight": 0.3}]}
    roundtrip(SimulationRequest, req)
    roundtrip(
        SimulationResponse,
        {
            "normalizedWeights": [{"criterionId": "c1", "weight": 0.57}, {"criterionId": "c2", "weight": 0.43}],
            "rankings": [{"rank": 1, "optionId": "o3", "score": 0.64}],
            "changedFromCurrent": True,
        },
    )
    with pytest.raises(ValidationError):
        SimulationRequest.model_validate({"weights": [{"criterionId": "c1"}]})


# ------------------------------------------------------------------ 실제 서비스 출력 검증
PRICE = CriterionMeta("c1", "가격", "numeric", "lower_better", "만 원")
WEIGHT = CriterionMeta("c2", "무게", "numeric", "lower_better", "kg")
PERF = CriterionMeta("c3", "성능", "rating", levels=["매우 낮음", "낮음", "보통", "높음", "매우 높음"])
TOUCH = CriterionMeta("c4", "터치스크린", "boolean", "true_better")
LAPTOP = [PRICE, WEIGHT, PERF, TOUCH]
SCALES = {"c1": Scale(150, 189), "c2": Scale(1.19, 1.45)}


@pytest.mark.parametrize(
    ("spec", "qtype"),
    [
        (SliderSpec(0, 1), "slider_pair"),
        (CriteriaABSpec(1, 2), "ab_criteria"),
        (ScenarioSpec(0, 1, (0.0, 0.0, 0.0, 0.0), 20.0, 0.1), "ab_scenario"),
        (ScenarioSpec(2, 3, (0.0, 0.0, 0.0, 0.0), 1.0, 1.0), "ab_scenario"),
    ],
)
def test_build_question_validates(spec: Any, qtype: str) -> None:
    q = build_question(spec, LAPTOP, SCALES, "purchase")
    full = {"id": "q1", "seq": 1, **q}
    obj = QuestionObject.model_validate(full)
    assert obj.type == qtype
    assert obj.model_dump(mode="json", by_alias=True) == full


@pytest.fixture(scope="module")
def ex() -> dict[str, Any]:
    scales, V = example_data.build_example()
    q1, q2 = Answer(SliderSpec(0, 2), -2), Answer(SliderSpec(2, 1), 1)
    answers = [q1, q2, Answer(ScenarioSpec(0, 1, (0.5, -0.5, 0.0), 10, 0.1), "B")]
    post = fit(42, 3, answers)
    return {"V": V, "scales": scales, "answers": answers, "s": summary(post, V), "W0": prior_particles(42, 3)}


IDS = ["price", "weight", "perf"]
NAMES = ["가격", "무게", "성능"]
OIDS = ["galaxy", "gram", "yoga"]
ONAMES = ["갤럭시북", "LG 그램 16", "요가"]


def test_analysis_helpers_outputs_validate(ex: dict[str, Any]) -> None:
    s, V = ex["s"], ex["V"]
    est = ah.estimate_payload(s, V, IDS)
    assert Estimate.model_validate(est).model_dump(by_alias=True) == est

    weights = ah.weights_items(s, IDS, NAMES, ex["answers"], ex["W0"])
    body = {"items": weights, "intervalLevel": ah.INTERVAL_LEVEL}
    assert WeightsResponse.model_validate(body).model_dump(by_alias=True) == body

    rankings = ah.rankings_items(s.mean, V, s.p_best, OIDS, ONAMES)
    assert RankingsResponse.model_validate({"items": rankings}).model_dump(by_alias=True) == {"items": rankings}

    cc = change_conditions(s.mean, V)
    raw = [[189, 1.23, 4], [165, 1.19, 3], [150, 1.45, 4]]
    import numpy as np

    vc = value_conditions(s.mean, V, example_data.CRIT, ex["scales"], np.array(raw, dtype=float))
    changes = ah.change_conditions_payload(
        cc,
        1,
        IDS,
        NAMES,
        OIDS,
        ONAMES,
        value_conds=vc,
        criterion_units=["만 원", "kg", ""],
        criterion_levels=[[], [], ["a", "b", "c", "d", "e"]],
    )
    parsed = ChangeConditionsResponse.model_validate(changes)
    assert parsed.model_dump(by_alias=True) == changes

    # 임계값 없는 형태도 실제 출력으로
    from app.engine.decision_analysis import ChangeCondition

    nothr = ah.change_conditions_payload([ChangeCondition(0, 0.25, None, None, None)], 0, IDS, NAMES, OIDS, ONAMES)
    assert ChangeConditionsResponse.model_validate(nothr).model_dump(by_alias=True) == nothr


def test_stability_and_hidden_payloads_validate() -> None:
    import numpy as np

    from app.engine.decision_analysis import ChangeCondition, Stability
    from app.engine.hidden_preference import Mismatch

    st = Stability(top=0, p_best=0.6, grade="medium", perturb_keep=0.86)
    cc = [ChangeCondition(0, 0.25, 0.31, "increase", 1)]
    out = ah.stability_payload(st, np.array([0.6, 0.3, 0.1]), cc, ["o1", "o2", "o3"], ["A", "B", "C"], ["가격"])
    assert StabilityResponse.model_validate(out).model_dump(by_alias=True) == out
    high = ah.stability_payload(
        Stability(0, 0.9, "high", 0.99), np.array([0.9, 0.1]), cc, ["o1", "o2"], ["A", "B"], ["가격"]
    )
    assert StabilityResponse.model_validate(high).level == "HIGH"

    hp = ah.hidden_preferences_payload([Mismatch(0, 1, 0.9, 0.1, 2)], IDS, NAMES)
    body = {**ANALYSIS, "hiddenPreferences": hp}
    assert AnalysisResponse.model_validate(body).model_dump(mode="json", by_alias=True)["hiddenPreferences"] == hp


# ------------------------------------------------------------------ OpenAPI
@pytest.mark.parametrize(
    "model",
    [
        QuestionObject,
        StartQuestionsResponse,
        StatusResponse,
        ProgressResponse,
        StopResponse,
        AnswerListResponse,
        SubmitAnswerRequest,
        SubmitAnswerResponse,
        EditAnswerRequest,
        EditAnswerResponse,
        AnalysisResponse,
        WeightsResponse,
        RankingsResponse,
        ScoresResponse,
        SensitivityResponse,
        StabilityResponse,
        ChangeConditionsResponse,
        SimulationRequest,
        SimulationResponse,
    ],
)
def test_json_schema_generation(model: type[BaseModel]) -> None:
    schema = model.model_json_schema(by_alias=True)
    assert schema["type"] == "object"
    assert all(k == k.replace("_", "") or k in {"$defs"} for k in schema.get("properties", {}))  # camelCase 키
    # 입력/출력 모드 모두 생성 가능
    model.model_json_schema(mode="serialization")


def test_next_question_json_schema() -> None:
    schema = TypeAdapter(NextQuestionResponse).json_schema()
    assert "oneOf" in schema and schema["discriminator"]["propertyName"] == "done"

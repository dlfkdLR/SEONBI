"""출력 스키마 규칙(6.2, 7.3 19·28번)과 Fake 어댑터 응답 (6.2 기본 기준 목록)."""

import asyncio
from typing import Any

import pytest
from pydantic import BaseModel, ValidationError

from app.ai.defaults import DEFAULT_CRITERIA, PRESETS
from app.ai.errors import ProviderError, SchemaViolationError
from app.ai.providers.fake import FakeAdapter
from app.ai.schemas import (
    ExplainResultOutput,
    ExtractConditionsOutput,
    PhraseQuestionOutput,
    RecommendCriteriaOutput,
    RecommendCriterion,
    RecommendOptionsOutput,
    SummarizeOptionsOutput,
)

ALL_SCHEMAS: list[type[BaseModel]] = [
    ExtractConditionsOutput,
    RecommendCriteriaOutput,
    RecommendOptionsOutput,
    PhraseQuestionOutput,
    SummarizeOptionsOutput,
    ExplainResultOutput,
]


def _crit(**kw: Any) -> dict[str, Any]:
    base: dict[str, Any] = {"name": "적성", "type": "rating", "reason": "r"}
    return {**base, **kw}


def test_levels_rules() -> None:
    RecommendCriterion.model_validate(_crit(levels=["a", "b", "c"]))
    RecommendCriterion.model_validate(_crit(levels=[str(i) for i in range(7)]))
    for bad in (["a", "b"], [str(i) for i in range(8)], ["a", "b", "a"], ["a", "b", "x" * 13], ["a", "b", ""]):
        with pytest.raises(ValidationError):
            RecommendCriterion.model_validate(_crit(levels=bad))
    RecommendCriterion.model_validate(_crit(levels=["a", "b", "x" * 12]))


def test_input_mode_only_for_rating() -> None:
    RecommendCriterion.model_validate(_crit(inputMode="rank"))
    for t in ("numeric", "boolean"):
        with pytest.raises(ValidationError):
            RecommendCriterion.model_validate(_crit(type=t, inputMode="score"))


def test_camel_case_alias_and_snake_names() -> None:
    a = RecommendCriterion.model_validate(_crit(inputMode="rank"))
    b = RecommendCriterion(name="흥미", type="rating", input_mode="rank", reason="r")
    assert a.input_mode == b.input_mode == "rank"
    assert b.model_dump(by_alias=True)["inputMode"] == "rank"


def test_recommend_criteria_count_3_to_6() -> None:
    item = _crit()
    for n in (2, 7):
        with pytest.raises(ValidationError):
            RecommendCriteriaOutput.model_validate({"criteria": [item] * n})
    RecommendCriteriaOutput.model_validate({"criteria": [item] * 3})
    RecommendCriteriaOutput.model_validate({"criteria": [item] * 6})


def test_extracted_item_types() -> None:
    out = ExtractConditionsOutput.model_validate(
        {
            "relevant": True,
            "ambiguity": "필수인가요?",
            "items": [
                {
                    "type": "criterion",
                    "name": "무게",
                    "criterionType": "numeric",
                    "direction": "lower_better",
                    "unit": "kg",
                    "confidence": "high",
                },
                {
                    "type": "constraint",
                    "kind": "threshold",
                    "criterionId": "c1",
                    "operator": "<=",
                    "value": 200,
                    "confidence": "medium",
                },
                {"type": "constraint", "kind": "required_tag", "label": "Windows 지원", "confidence": "low"},
                {"type": "option", "name": "LG 그램", "confidence": "high"},
                {"type": "preference", "criterionA": "c1", "criterionB": "c2", "value": -3, "confidence": "high"},
            ],
        }
    )
    assert [i.type for i in out.items] == ["criterion", "constraint", "constraint", "option", "preference"]


@pytest.mark.parametrize(
    "item",
    [
        {"type": "preference", "criterionA": "c1", "criterionB": "c2", "value": 6, "confidence": "high"},
        {"type": "preference", "criterionA": "c1", "criterionB": "c2", "value": 3},  # confidence 없음
        {"type": "constraint", "kind": "threshold", "label": "x", "confidence": "high"},
        {"type": "constraint", "kind": "required_tag", "confidence": "high"},
        {"type": "criterion", "name": "x", "criterionType": "numeric", "inputMode": "rank", "confidence": "high"},
        {"type": "other", "confidence": "high"},
    ],
)
def test_extracted_item_rejections(item: dict[str, Any]) -> None:
    with pytest.raises(ValidationError):
        ExtractConditionsOutput.model_validate({"relevant": True, "items": [item]})


@pytest.mark.parametrize("schema", ALL_SCHEMAS)
def test_fake_output_validates_against_every_schema(schema: type[BaseModel]) -> None:
    prompt = '카테고리: purchase\n<server_data>{"options": [{"optionId": "o1"}, {"optionId": "o2"}]}</server_data>'
    out = asyncio.run(FakeAdapter().complete_structured(schema, prompt, max_tokens=10))
    assert isinstance(out, schema)
    schema.model_validate(out.model_dump(by_alias=True))  # 별칭 JSON으로도 재검증된다


def test_fake_recommend_criteria_matches_defaults_for_all_categories() -> None:
    assert set(DEFAULT_CRITERIA) == {"purchase", "career", "study", "activity", "living", "etc"}
    for category, defaults in DEFAULT_CRITERIA.items():
        out = asyncio.run(
            FakeAdapter().complete_structured(RecommendCriteriaOutput, f"카테고리: {category}", max_tokens=10)
        )
        assert [c.name for c in out.criteria] == [d.name for d in defaults]
        assert 3 <= len(out.criteria) <= 6


def test_default_criteria_content_follows_spec() -> None:
    career = {d.name: d for d in DEFAULT_CRITERIA["career"]}
    assert career["흥미"].input_mode == "rank" and career["흥미"].levels is None
    assert career["적성"].levels == PRESETS["적합도"]
    assert career["통학 시간"].unit == "분" and career["통학 시간"].direction == "lower_better"
    assert {d.name: d.levels for d in DEFAULT_CRITERIA["activity"]}["흥미"] == PRESETS["끌림"]
    purchase = {d.name: d for d in DEFAULT_CRITERIA["purchase"]}
    assert purchase["가격"].unit == "만 원" and purchase["성능"].levels == PRESETS["수준"]
    assert all(d.direction is None for ds in DEFAULT_CRITERIA.values() for d in ds if d.type == "rating")


def test_fake_script_items() -> None:
    fake = FakeAdapter(
        script=[
            ExplainResultOutput(summary="s", reasons=[], caveats=[]),
            {"summary": "d"},
            '{"summary": "j"}',
            "not json",
            ProviderError("boom"),
            PhraseQuestionOutput(title="t"),  # 다른 스키마
        ]
    )

    def call() -> ExplainResultOutput:
        return asyncio.run(fake.complete_structured(ExplainResultOutput, "p", max_tokens=1))

    assert [call().summary for _ in range(3)] == ["s", "d", "j"]
    with pytest.raises(SchemaViolationError):
        call()
    with pytest.raises(ProviderError):
        call()
    with pytest.raises(SchemaViolationError):
        call()
    assert call().summary  # 스크립트가 비면 canned 응답

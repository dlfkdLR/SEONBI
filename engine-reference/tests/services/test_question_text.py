"""question_text 테스트: 5.12 노트북 예시, 5.2 학과 예시, 형식·오류."""

from __future__ import annotations

import json

import pytest

from app.engine.likelihood import CriteriaABSpec, ScenarioSpec, SliderSpec
from app.engine.normalize import Scale
from app.services.question_text import (
    CriterionMeta,
    build_question,
    format_number,
    format_range,
    format_unit,
    noun_for_category,
)

QUESTION_KEYS = {"type", "criteria", "title", "payload", "allowFreeText", "allowSkip"}

PRICE = CriterionMeta("c1", "가격", "numeric", "lower_better", "만 원")
WEIGHT = CriterionMeta("c2", "무게", "numeric", "lower_better", "kg")
PERF = CriterionMeta("c3", "성능", "rating", levels=["매우 낮음", "낮음", "보통", "높음", "매우 높음"])
TOUCH = CriterionMeta("c4", "터치스크린", "boolean", "true_better")
LAPTOP = [PRICE, WEIGHT, PERF, TOUCH]
SCALES = {"c1": Scale(150, 189), "c2": Scale(1.19, 1.45)}


def _check_shape(q: dict[str, object]) -> None:
    assert set(q) == QUESTION_KEYS
    assert q["allowFreeText"] is True and q["allowSkip"] is True
    assert json.loads(json.dumps(q)) == q


def test_slider_laptop() -> None:
    q = build_question(SliderSpec(0, 1), LAPTOP, SCALES, "purchase")
    _check_shape(q)
    assert q["type"] == "slider_pair"
    assert q["criteria"] == ["c1", "c2"]
    assert q["title"] == "가격 차이(150~189만 원)와 무게 차이(1.19~1.45kg) 중 어느 쪽이 더 중요한가요?"
    assert q["payload"] == {
        "left": {"criterionId": "c1", "label": "가격", "range": "150~189만 원"},
        "right": {"criterionId": "c2", "label": "무게", "range": "1.19~1.45kg"},
        "min": -5,
        "max": 5,
    }


def test_ab_criteria_laptop() -> None:
    q = build_question(CriteriaABSpec(1, 2), LAPTOP, SCALES, "purchase")
    _check_shape(q)
    assert q["type"] == "ab_criteria"
    assert q["criteria"] == ["c2", "c3"]
    assert q["title"] == "하나만 고른다면, 무게 차이(1.19~1.45kg)와 성능 차이(매우 낮음~매우 높음) 중 무엇이 더 중요한가요?"
    assert q["payload"] == {
        "optionA": {"criterionId": "c2", "label": "무게"},
        "optionB": {"criterionId": "c3", "label": "성능"},
    }


def test_scenario_laptop() -> None:
    # 5.5: Δa=0.5 → 가격 20만 원 저렴 / 무게 0.1kg 무거움
    spec = ScenarioSpec(0, 1, (0.0, 0.0, 0.0, 0.0), 20.0, 0.1)
    q = build_question(spec, LAPTOP, SCALES, "purchase")
    _check_shape(q)
    assert q["type"] == "ab_scenario"
    assert q["title"] == "다음 두 제품 중 어느 쪽을 고르시겠어요? (가격 ↔ 무게)"
    a, b = q["payload"]["optionA"], q["payload"]["optionB"]
    assert a == {
        "label": "A",
        "description": "가격 20만 원 더 좋음, 무게 0.1kg 더 나쁨",
        "deltas": [{"criterionId": "c1", "display": "-20만 원"}, {"criterionId": "c2", "display": "+0.1kg"}],
    }
    assert b == {
        "label": "B",
        "description": "가격 20만 원 더 나쁨, 무게 0.1kg 더 좋음",
        "deltas": [{"criterionId": "c1", "display": "+20만 원"}, {"criterionId": "c2", "display": "-0.1kg"}],
    }


def test_scenario_higher_better_and_rating_and_boolean() -> None:
    salary = CriterionMeta("s", "연봉", "numeric", "higher_better", "만 원")
    crit = [salary, PERF, TOUCH]
    scales = {"s": Scale(2000, 5000)}
    q = build_question(ScenarioSpec(0, 1, (0.0,) * 3, 300.0, 1.0), crit, scales, "career")
    assert q["title"] == "다음 두 학과 중 어느 쪽을 고르시겠어요? (연봉 ↔ 성능)"
    a = q["payload"]["optionA"]
    assert a["description"] == "연봉 300만 원 더 좋음, 성능이(가) 1단계 더 낮음"
    assert [d["display"] for d in a["deltas"]] == ["+300만 원", "-1단계"]
    assert [d["display"] for d in q["payload"]["optionB"]["deltas"]] == ["-300만 원", "+1단계"]

    q = build_question(ScenarioSpec(1, 0, (0.0,) * 3, 2.0, 100.0), crit, scales, "etc")
    assert q["title"] == "다음 두 선택지 중 어느 쪽을 고르시겠어요? (성능 ↔ 연봉)"
    assert [d["display"] for d in q["payload"]["optionA"]["deltas"]] == ["+2단계", "-100만 원"]

    q = build_question(ScenarioSpec(2, 0, (0.0,) * 3, 1.0, 100.0), crit, scales, "etc")
    q2 = build_question(ScenarioSpec(0, 2, (0.0,) * 3, 100.0, 1.0), crit, scales, "etc")
    a = q2["payload"]["optionA"]
    assert a["description"] == "연봉 100만 원 더 좋음, 터치스크린 없음"
    assert [d["display"] for d in a["deltas"]] == ["+100만 원", "없음"]
    assert [d["display"] for d in q2["payload"]["optionB"]["deltas"]] == ["-100만 원", "있음"]
    assert q["payload"]["optionA"]["description"] == "터치스크린 있음, 연봉 100만 원 더 나쁨"


def test_career_ranges_from_option_names_and_levels() -> None:
    interest = CriterionMeta(
        "i", "흥미", "rating", levels=["별로", "끌림"], input_mode="rank", low_name="경영학과", high_name="컴퓨터공학과"
    )
    aptitude = CriterionMeta(
        "a", "적성", "rating", levels=["안 맞음", "그럭저럭", "잘 맞음", "매우 잘 맞음"], input_mode="rank"
    )
    crit = [interest, aptitude]
    q = build_question(SliderSpec(0, 1), crit, {}, "career")
    assert q["payload"]["left"]["range"] == "경영학과~컴퓨터공학과"  # 선택지 이름 우선
    assert q["payload"]["right"]["range"] == "안 맞음~매우 잘 맞음"  # 라벨로 대체
    assert (
        q["title"] == "흥미 차이(경영학과~컴퓨터공학과)와 적성 차이(안 맞음~매우 잘 맞음) 중 어느 쪽이 더 중요한가요?"
    )
    with pytest.raises(ValueError, match="순위"):
        build_question(ScenarioSpec(0, 1, (0.0, 0.0), 1.0, 1.0), crit, {}, "career")


def test_boolean_range_and_ranges_override() -> None:
    q = build_question(SliderSpec(3, 2), LAPTOP, SCALES, "purchase", ranges={"c3": "A~B"})
    assert q["payload"]["left"]["range"] == "없음~있음"
    assert q["payload"]["right"]["range"] == "A~B"
    # 범위 문자열을 직접 주면 척도가 없어도 된다
    q = build_question(SliderSpec(0, 1), LAPTOP, {}, "purchase", ranges={"c1": "x", "c2": "y"})
    assert q["payload"]["left"]["range"] == "x"


def test_noun_for_category() -> None:
    assert noun_for_category("purchase") == "제품"
    assert noun_for_category("career") == "학과"
    assert noun_for_category("activity") == "활동"
    for c in ("study", "living", "etc", "other", ""):
        assert noun_for_category(c) == "선택지"


def test_format_number() -> None:
    assert format_number(20.0) == "20"
    assert format_number(0.1) == "0.1"
    assert format_number(0.1 + 0.2) == "0.3"
    assert format_number(0.10000000000000002) == "0.1"
    assert format_number(1.19) == "1.19"
    assert format_number(-0.0) == "0"
    assert format_number(1e-7) == "0.0000001"
    assert format_number(12345678901) == "12345678901"
    with pytest.raises(ValueError):
        format_number(float("nan"))
    with pytest.raises(ValueError):
        format_number(float("inf"))


def test_format_range_and_unit() -> None:
    assert format_range(150, 189, "만 원") == "150~189만 원"
    assert format_range(1.19, 1.45, "kg") == "1.19~1.45kg"
    assert format_range(0, 10) == "0~10"
    assert format_unit(None) == ""
    assert format_unit("분") == "분"
    assert format_unit("%") == "%"


def test_errors() -> None:
    with pytest.raises(ValueError, match="척도"):
        build_question(SliderSpec(0, 1), LAPTOP, {"c2": Scale(1, 2)}, "purchase")
    with pytest.raises(ValueError, match="척도"):
        build_question(ScenarioSpec(0, 1, (0.0,) * 4, 1.0, 1.0), LAPTOP, {}, "purchase")
    with pytest.raises(TypeError):
        build_question("slider", LAPTOP, SCALES, "purchase")  # type: ignore[arg-type]
    with pytest.raises(ValueError, match="인덱스"):
        build_question(SliderSpec(0, 9), LAPTOP, SCALES, "purchase")
    with pytest.raises(ValueError, match="서로 다른"):
        build_question(CriteriaABSpec(1, 1), LAPTOP, SCALES, "purchase")
    bare = CriterionMeta("r", "분위기", "rating")
    with pytest.raises(ValueError, match="범위 이름"):
        build_question(SliderSpec(0, 1), [bare, PERF], {}, "etc")

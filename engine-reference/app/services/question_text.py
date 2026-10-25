"""템플릿(AI 없는) 질문 문장 생성 (개발기획서 5.1, 5.5 문장 템플릿, 7.3 질문 객체).

엔진이 만든 질문 스펙(`SliderSpec` / `CriteriaABSpec` / `ScenarioSpec`)을 7.3의 "질문 객체" 내용으로 바꾼다.
`id`와 `seq`는 서버가 저장하며 붙이므로 여기서는 만들지 않는다. LLM `phrase_question`(6.2)은 이 결과의
`title`, `payload.*.description`, `payload.left/right.label`만 바꾸고 숫자·`deltas`·`range`는 그대로 쓴다.
DB·FastAPI에 의존하지 않는 순수 함수 모듈이다.

명세의 모호한 부분 (가정):
- 단위는 숫자 바로 뒤에 그대로 붙인다("20만 원", "0.1kg"). 띄어쓰기는 단위 문자열 안에 둔다(7.3·5.1 예시).
- 단계형 범위 표기는 "낮은 쪽~높은 쪽"을 `~`로 잇는다(5.1은 띄어 쓴 예시). 양 끝 선택지 이름이 없으면
  첫/마지막 단계 라벨로 대신하고, 라벨도 없으면 ValueError.
- 예/아니요 범위는 "없음~있음", 예/아니요 기준은 `deltas[].display`가 "있음"/"없음".
- `deltas[].display`의 부호는 A의 값에서 B의 값을 뺀 방향이 아니라 "그 선택지의 값이 상대보다 어느 쪽으로
  달라졌나"를 뜻한다. 방향이 lower_better인 숫자형에서 "더 좋음"은 음수다.
- 상황형 두 조각은 5.5 조각 그대로 ", "로 잇는다.
- 슬라이더·A/B의 "차이"라는 말 뒤 조사는 항상 "와"다("차이"가 모음으로 끝남).
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from typing import Any, Literal

from app.engine.likelihood import SLIDER_MAX, CriteriaABSpec, QuestionSpec, ScenarioSpec, SliderSpec
from app.engine.normalize import CriterionType, Direction, InputMode, Scale

ALLOW_FREE_TEXT = True  # "이유 추가" 버튼 노출 (7.3 예시: 엔진 질문은 모두 true)
ALLOW_SKIP = True  # "잘 모르겠어요"

SLIDER_MIN = -SLIDER_MAX

_NOUNS = {"purchase": "제품", "career": "학과", "activity": "활동"}
_DEFAULT_NOUN = "선택지"


@dataclass(frozen=True)
class CriterionMeta:
    """질문 문장에 필요한 기준 정보. DB의 criteria 행에서 그대로 만들 수 있다."""

    id: str
    name: str
    type: CriterionType
    direction: Direction = "higher_better"
    unit: str | None = None
    levels: list[str] | None = None  # 단계 라벨 (나쁜 것 → 좋은 것)
    input_mode: InputMode = "score"
    low_name: str | None = None  # 단계형 범위 표기용: 값이 가장 낮은 선택지 이름
    high_name: str | None = None  # 값이 가장 높은 선택지 이름


def noun_for_category(category: str) -> str:
    """카테고리별 선택지 명사 (5.5 문장 템플릿). 기타는 "선택지"."""
    return _NOUNS.get(category, _DEFAULT_NOUN)


def format_number(x: float) -> str:
    """부동소수점 꼬리를 없앤 숫자 문자열. 정수는 ".0" 없이 쓴다."""
    if x != x or x in (float("inf"), float("-inf")):
        raise ValueError(f"유한한 수가 아니다: {x!r}")
    if x == int(x) and abs(x) < 1e15:
        return str(int(x))
    r = float(f"{x:.10g}")
    text = format(Decimal(repr(r)), "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text


def format_unit(unit: str | None) -> str:
    """숫자 바로 뒤에 붙는 단위 (없으면 빈 문자열)."""
    return unit or ""


def format_range(low: float, high: float, unit: str | None = None) -> str:
    """숫자형 범위. 예: format_range(150, 189, "만 원") → "150~189만 원"."""
    return f"{format_number(low)}~{format_number(high)}{format_unit(unit)}"


def format_value(x: float, unit: str | None = None) -> str:
    """단위가 붙은 숫자. 예: "20만 원", "0.1kg"."""
    return f"{format_number(x)}{format_unit(unit)}"


def build_question(
    spec: QuestionSpec,
    criteria: Sequence[CriterionMeta],
    scales: Mapping[str, Scale],
    category: str,
    *,
    ranges: Mapping[str, str] | None = None,
) -> dict[str, Any]:
    """질문 스펙 → 7.3 질문 객체(`id`, `seq` 제외). 기준은 `criteria`의 인덱스로 가리킨다.

    ranges: 기준 ID → 미리 만든 범위 문자열. 있으면 척도·단계 라벨 대신 그대로 쓴다.
    """
    ranges = ranges or {}
    if isinstance(spec, SliderSpec):
        ca, cb = _pair(spec, criteria)
        ra, rb = _range_of(ca, scales, ranges), _range_of(cb, scales, ranges)
        return _wrap(
            "slider_pair",
            [ca.id, cb.id],
            _range_title(ca, ra, cb, rb),
            # s > 0이면 왼쪽(left, 기준 a)이 더 중요 (5.3)
            {
                "left": {"criterionId": ca.id, "label": ca.name, "range": ra},
                "right": {"criterionId": cb.id, "label": cb.name, "range": rb},
                "min": SLIDER_MIN,
                "max": SLIDER_MAX,
            },
        )
    if isinstance(spec, CriteriaABSpec):
        ca, cb = _pair(spec, criteria)
        ra, rb = _range_of(ca, scales, ranges), _range_of(cb, scales, ranges)
        return _wrap(
            "ab_criteria",
            [ca.id, cb.id],
            _choose_title(ca, ra, cb, rb),
            {
                "optionA": {"criterionId": ca.id, "label": ca.name},
                "optionB": {"criterionId": cb.id, "label": cb.name},
            },
        )
    if isinstance(spec, ScenarioSpec):
        return _scenario(spec, criteria, scales, category)
    raise TypeError(f"알 수 없는 질문 스펙: {type(spec).__name__}")


def _wrap(qtype: str, criterion_ids: list[str], title: str, payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": qtype,
        "criteria": criterion_ids,
        "title": title,
        "payload": payload,
        "allowFreeText": ALLOW_FREE_TEXT,
        "allowSkip": ALLOW_SKIP,
    }


def _pair(
    spec: SliderSpec | CriteriaABSpec | ScenarioSpec, criteria: Sequence[CriterionMeta]
) -> tuple[CriterionMeta, CriterionMeta]:
    for idx in (spec.a, spec.b):
        if not 0 <= idx < len(criteria):
            raise ValueError(f"기준 인덱스가 범위를 벗어났다: {idx} (기준 {len(criteria)}개)")
    if spec.a == spec.b:
        raise ValueError("서로 다른 두 기준이어야 한다")
    return criteria[spec.a], criteria[spec.b]


def _is_rank(c: CriterionMeta) -> bool:
    return c.type == "rating" and c.input_mode == "rank"


def _range_title(ca: CriterionMeta, ra: str, cb: CriterionMeta, rb: str) -> str:
    # 5.1: "가격 차이(150~189만 원)와 무게 차이(1.19~1.45kg) 중 어느 쪽이 더 중요한가요?"
    return f"{ca.name} 차이({ra})와 {cb.name} 차이({rb}) 중 어느 쪽이 더 중요한가요?"


def _choose_title(ca: CriterionMeta, ra: str, cb: CriterionMeta, rb: str) -> str:
    # 슬라이더와 같은 쌍을 다시 묻더라도 문장이 똑같지 않도록 "하나만 고른다면"으로 시작한다
    return f"하나만 고른다면, {ca.name} 차이({ra})와 {cb.name} 차이({rb}) 중 무엇이 더 중요한가요?"


def _scale_of(c: CriterionMeta, scales: Mapping[str, Scale]) -> Scale:
    scale = scales.get(c.id)
    if scale is None:
        raise ValueError(f"숫자형 기준 {c.id}의 척도가 없다")
    return scale


def _range_of(c: CriterionMeta, scales: Mapping[str, Scale], ranges: Mapping[str, str]) -> str:
    """기준 하나의 범위 표기 (5.1)."""
    if c.id in ranges:
        return ranges[c.id]
    if c.type == "numeric":
        scale = _scale_of(c, scales)
        return format_range(scale.min, scale.max, c.unit)
    if c.type == "boolean":
        return "없음~있음"
    low = c.low_name or (c.levels[0] if c.levels else None)
    high = c.high_name or (c.levels[-1] if c.levels else None)
    if not low or not high:
        raise ValueError(f"단계형 기준 {c.id}의 범위 이름(선택지 이름 또는 단계 라벨)이 없다")
    return f"{low}~{high}"


def _scenario(
    spec: ScenarioSpec, criteria: Sequence[CriterionMeta], scales: Mapping[str, Scale], category: str
) -> dict[str, Any]:
    ca, cb = _pair(spec, criteria)
    for c in (ca, cb):
        if _is_rank(c):
            raise ValueError(f"순위 입력 기준 {c.id}는 상황형에 쓰지 않는다 (5.5)")
        if c.type == "numeric":
            _scale_of(c, scales)
    # A는 a가 raw_a만큼 좋고 b가 raw_b만큼 나쁘다. B는 반대.
    a_better_a, a_worse_b = (ca, spec.raw_a, True), (cb, spec.raw_b, False)
    b_worse_a, b_better_b = (ca, spec.raw_a, False), (cb, spec.raw_b, True)
    option_a = _option("A", [a_better_a, a_worse_b])
    option_b = _option("B", [b_worse_a, b_better_b])
    return _wrap(
        "ab_scenario",
        [ca.id, cb.id],
        f"다음 두 {noun_for_category(category)} 중 어느 쪽을 고르시겠어요? ({ca.name} ↔ {cb.name})",
        {"optionA": option_a, "optionB": option_b},
    )


def _option(label: Literal["A", "B"], parts: list[tuple[CriterionMeta, float, bool]]) -> dict[str, Any]:
    return {
        "label": label,
        "description": ", ".join(_fragment(c, mag, better) for c, mag, better in parts),
        "deltas": [{"criterionId": c.id, "display": _delta_display(c, mag, better)} for c, mag, better in parts],
    }


def _fragment(c: CriterionMeta, magnitude: float, better: bool) -> str:
    """5.5 문장 조각. better=True면 그 선택지가 상대보다 이 기준에서 좋다."""
    if c.type == "numeric":
        return f"{c.name} {format_value(magnitude, c.unit)} 더 {'좋음' if better else '나쁨'}"
    if c.type == "rating":
        return f"{c.name}이(가) {format_number(magnitude)}단계 더 {'높음' if better else '낮음'}"
    return f"{c.name} {'있음' if better else '없음'}"


def _delta_display(c: CriterionMeta, magnitude: float, better: bool) -> str:
    """상대 선택지 대비 값이 어떻게 달라졌는지 (부호 포함, 원래 단위)."""
    if c.type == "boolean":
        return "있음" if better else "없음"
    positive = better == (c.direction != "lower_better") if c.type == "numeric" else better
    sign = "+" if positive else "-"
    if c.type == "rating":
        return f"{sign}{format_number(magnitude)}단계"
    return f"{sign}{format_value(magnitude, c.unit)}"

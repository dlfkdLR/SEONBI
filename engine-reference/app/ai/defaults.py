"""카테고리별 기본 기준 목록 (개발기획서 6.2 표, 3.4 프리셋).

LLM 장애·쿼터 초과 시 fallback이자 Fake LLM의 응답 데이터다. 필드는 `RecommendCriterion`
(recommend_criteria 출력 스키마)과 이름·의미가 같아서 그대로 변환된다.
"""

from __future__ import annotations

from dataclasses import dataclass

# 3.4 단계 라벨 프리셋 (나쁜 것 → 좋은 것)
PRESETS: dict[str, tuple[str, ...]] = {
    "수준": ("매우 낮음", "낮음", "보통", "높음", "매우 높음"),
    "만족도": ("매우 불만", "불만", "보통", "만족", "매우 만족"),
    "적합도": ("안 맞음", "그럭저럭", "잘 맞음", "매우 잘 맞음"),
    "끌림": ("별로", "그럭저럭", "끌림", "매우 끌림"),
}

# 6.2 표에서 프리셋을 적지 않은 "단계"는 3.4의 기본 프리셋(수준)이다.
DEFAULT_PRESET = "수준"


@dataclass(frozen=True)
class DefaultCriterion:
    """recommend_criteria 출력 항목과 같은 모양 (direction은 rating이면 None — 19번)."""

    name: str
    type: str  # numeric | rating | boolean
    direction: str | None
    unit: str | None
    levels: tuple[str, ...] | None
    input_mode: str | None
    reason: str


def _num(name: str, unit: str | None, reason: str, direction: str = "lower_better") -> DefaultCriterion:
    return DefaultCriterion(name, "numeric", direction, unit, None, None, reason)


def _rating(name: str, reason: str, preset: str = DEFAULT_PRESET) -> DefaultCriterion:
    return DefaultCriterion(name, "rating", None, None, PRESETS[preset], "score", reason)


def _rank(name: str, reason: str) -> DefaultCriterion:
    """순위 입력 단계형: levels 생략(23번 career 예시)."""
    return DefaultCriterion(name, "rating", None, None, None, "rank", reason)


DEFAULT_CRITERIA: dict[str, tuple[DefaultCriterion, ...]] = {
    "purchase": (
        _num("가격", "만 원", "구매 결정의 기본 기준"),
        _rating("성능", "쓰임새에 맞는 성능인지 비교"),
        _rating("디자인", "오래 쓰면서 만족할 겉모습"),
        _num("무게/크기", None, "들고 다니거나 놓을 공간에 영향"),
        _rating("내구성·A/S", "오래 안심하고 쓸 수 있는지"),
    ),
    "career": (
        _rank("흥미", "점수보다 순서로 답하기 쉬운 기준"),
        _rating("적성", "잘 맞는지 가늠하는 핵심 기준", "적합도"),
        _rating("취업·진로 전망", "졸업 후 진로에 미치는 영향"),
        _num("통학 시간", "분", "매일 쓰는 시간 부담"),
        _num("학비", "만 원", "비용 부담"),
    ),
    "study": (
        _rating("효과 기대", "공부 효과를 얼마나 기대하는지"),
        _num("비용", "만 원", "들어가는 돈"),
        _num("시간 부담", "시간/주", "매주 써야 하는 시간"),
        _rating("난이도 적합", "내 수준에 맞는 정도", "적합도"),
        _num("이동 거리", "분", "오가는 데 드는 시간"),
    ),
    "activity": (
        _rating("흥미", "얼마나 끌리는지", "끌림"),
        _rating("배울 수 있는 것", "얻을 수 있는 경험과 지식"),
        _num("시간 부담", "시간/주", "매주 써야 하는 시간"),
        _rating("함께하는 사람", "같이 하는 사람들과의 만족", "만족도"),
        _rating("포트폴리오 도움", "진학·취업 준비에 도움이 되는 정도"),
    ),
    # 6.2 표에서 "비용·수입"만 방향 표시가 없다 → 가장 단순하게 다른 비용 기준처럼 lower_better로 둔다.
    "living": (
        _num("비용·수입", "만 원", "돈과 관련된 조건"),
        _num("거리", "분", "생활 동선에 미치는 영향"),
        _rating("분위기·환경", "지내기 편한 환경인지", "만족도"),
        _rating("시간 유연성", "시간을 자유롭게 쓸 수 있는지"),
        _rating("안전", "안심하고 지낼 수 있는지"),
    ),
    "etc": (
        _rating("만족도 기대", "결과에 대한 기대 만족", "만족도"),
        _num("비용", None, "들어가는 돈"),
        _num("시간", None, "들어가는 시간"),
        _rating("위험 부담", "잘못됐을 때의 부담"),
    ),
}

DEFAULT_CATEGORY = "etc"


def defaults_for(category: str) -> tuple[DefaultCriterion, ...]:
    """카테고리별 기본 기준. 모르는 카테고리는 `etc`(가장 일반적인 목록)로 본다."""
    return DEFAULT_CRITERIA.get(category, DEFAULT_CRITERIA[DEFAULT_CATEGORY])

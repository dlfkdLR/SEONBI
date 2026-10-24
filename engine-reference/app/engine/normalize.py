"""척도 고정, 값 정규화, 필수 조건 필터 (개발기획서 5.1, 3.4, 3.5)."""

from __future__ import annotations

from collections.abc import Collection, Mapping, Sequence
from dataclasses import dataclass, field
from typing import Literal

import numpy as np
from numpy.typing import NDArray

CriterionType = Literal["numeric", "rating", "boolean"]
Direction = Literal["higher_better", "lower_better", "true_better"]
InputMode = Literal["score", "rank"]

DEFAULT_LEVELS = 5  # 단계형 기본 단계 수 (수준 프리셋)

# (option_id, criterion_id) → 원래 값. numeric=수, rating(score)=1~L, rating(rank)=그룹 번호(1=가장 좋음), boolean=bool
RawValues = Mapping[tuple[str, str], float | int | bool]


@dataclass(frozen=True)
class Criterion:
    id: str
    type: CriterionType
    direction: Direction = "higher_better"
    levels: int | None = None  # rating 단계 수 L (라벨 개수)
    input_mode: InputMode = "score"
    scale_min: float | None = None  # 사용자가 직접 지정한 척도 (없으면 선택지 값으로 결정)
    scale_max: float | None = None


@dataclass(frozen=True)
class Scale:
    min: float
    max: float

    @property
    def span(self) -> float:
        return self.max - self.min


@dataclass(frozen=True)
class Condition:
    id: str
    kind: Literal["threshold", "required_tag"]
    criterion_id: str | None = None
    operator: Literal["<=", ">=", "=="] | None = None
    value: float | None = None
    satisfied_by: frozenset[str] = field(default_factory=frozenset)  # required_tag: 충족하는 선택지 ID


@dataclass(frozen=True)
class NormalizedMatrix:
    option_ids: tuple[str, ...]  # 제외되지 않은 선택지만, 입력 순서 유지
    criterion_ids: tuple[str, ...]
    values: NDArray[np.float64]  # (선택지 수, 기준 수), 0~1, 클수록 좋음


def excluded_options(
    option_ids: Sequence[str], values: RawValues, conditions: Collection[Condition]
) -> dict[str, list[str]]:
    """필수 조건을 만족하지 못하는 선택지 → 미충족 조건 ID 목록. 삭제하지 않고 제외만 한다."""
    result: dict[str, list[str]] = {}
    for oid in option_ids:
        failed = [c.id for c in conditions if not _satisfies(oid, values, c)]
        if failed:
            result[oid] = failed
    return result


def freeze_scales(criteria: Sequence[Criterion], values: RawValues, excluded: Collection[str] = ()) -> dict[str, Scale]:
    """질문 시작 시 숫자형 기준의 척도를 정한다.

    사용자가 지정한 척도가 있으면 그대로, 없으면 제외되지 않은 선택지 값의 최솟값·최댓값.
    단계형·예/아니요는 척도가 단계 수로 정해지므로 대상이 아니다.
    """
    scales: dict[str, Scale] = {}
    for c in criteria:
        if c.type != "numeric":
            continue
        xs = [float(v) for (oid, cid), v in values.items() if cid == c.id and oid not in excluded]
        lo = c.scale_min if c.scale_min is not None else (min(xs) if xs else 0.0)
        hi = c.scale_max if c.scale_max is not None else (max(xs) if xs else 0.0)
        scales[c.id] = Scale(min(lo, hi), max(lo, hi))
    return scales


def normalize(
    values: RawValues,
    criteria: Sequence[Criterion],
    scales: Mapping[str, Scale],
    *,
    option_ids: Sequence[str],
    conditions: Collection[Condition] = (),
) -> tuple[NormalizedMatrix, dict[str, list[str]]]:
    """선택지 × 기준 값을 0~1(클수록 좋음)로 바꾸고, 필수 조건 미충족 선택지를 뺀다.

    모든 선택지 값이 같은 기준은 전부 0.5가 되어 순위에 영향을 주지 않는다.
    """
    excluded = excluded_options(option_ids, values, conditions)
    kept = tuple(oid for oid in option_ids if oid not in excluded)
    V = np.full((len(kept), len(criteria)), 0.5)
    for k, c in enumerate(criteria):
        raw = [values.get((oid, c.id)) for oid in kept]
        if any(x is None for x in raw):
            missing = [oid for oid, x in zip(kept, raw, strict=True) if x is None]
            raise ValueError(f"기준 {c.id}에 값이 없는 선택지: {missing}")
        V[:, k] = _normalize_column(c, [x for x in raw if x is not None], scales.get(c.id))
    return NormalizedMatrix(kept, tuple(c.id for c in criteria), V), excluded


def _normalize_column(c: Criterion, raw: list[float | int | bool], scale: Scale | None) -> NDArray[np.float64]:
    x = np.array([float(v) for v in raw])
    if c.type == "boolean":
        return (x > 0).astype(float)
    if c.type == "rating" and c.input_mode == "rank":
        # 남은 선택지 기준으로 그룹 번호를 1..G로 다시 매긴 뒤 v = (G − g) / (G − 1)
        groups = np.unique(x)
        g = np.searchsorted(groups, x) + 1
        G = len(groups)
        return np.full(len(x), 0.5) if G == 1 else (G - g) / (G - 1)
    if c.type == "rating":
        L = c.levels or DEFAULT_LEVELS
        return np.clip((x - 1) / (L - 1), 0.0, 1.0)
    if scale is None:
        raise ValueError(f"숫자형 기준 {c.id}의 척도가 고정되지 않았다")
    if scale.span == 0:
        return np.full(len(x), 0.5)
    v = (x - scale.min) / scale.span
    if c.direction == "lower_better":
        v = 1 - v
    return np.clip(v, 0.0, 1.0)


def _satisfies(option_id: str, values: RawValues, cond: Condition) -> bool:
    if cond.kind == "required_tag":
        return option_id in cond.satisfied_by
    if cond.criterion_id is None or cond.operator is None or cond.value is None:
        return True
    x = values.get((option_id, cond.criterion_id))
    if x is None:
        return True  # 값이 없으면 판단 보류 (값 입력 완료 전)
    x = float(x)
    if cond.operator == "<=":
        return x <= cond.value
    if cond.operator == ">=":
        return x >= cond.value
    return x == cond.value

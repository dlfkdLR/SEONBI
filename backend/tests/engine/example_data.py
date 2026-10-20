"""엔진 테스트 공통: 개발기획서 5.12 노트북 예시 데이터."""

import numpy as np
from numpy.typing import NDArray

from app.engine.normalize import Criterion, Scale, freeze_scales, normalize

CRIT = [
    Criterion("price", "numeric", "lower_better"),
    Criterion("weight", "numeric", "lower_better"),
    Criterion("perf", "rating", levels=5),
]
OPTS = ["galaxy", "gram", "yoga"]
VALS = {
    ("galaxy", "price"): 189,
    ("gram", "price"): 165,
    ("yoga", "price"): 150,
    ("galaxy", "weight"): 1.23,
    ("gram", "weight"): 1.19,
    ("yoga", "weight"): 1.45,
    ("galaxy", "perf"): 4,
    ("gram", "perf"): 3,
    ("yoga", "perf"): 4,
}


def build_example() -> tuple[dict[str, Scale], NDArray[np.float64]]:
    scales = freeze_scales(CRIT, VALS)
    matrix, _ = normalize(VALS, CRIT, scales, option_ids=OPTS)
    return scales, matrix.values

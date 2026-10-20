"""엔진 시뮬레이션 (개발기획서 14장): EIG 질문 선택 vs 랜덤 질문 선택.

가상 사용자(정답 가중치 ~ Dirichlet(1))가 엔진 자신의 우도 모델대로 답한다(--noise ε이면 확률 ε로 무작위 응답).
두 전략을 같은 사용자·같은 종료 규칙으로 돌려 MAE, 1위 적중률, 평균 질문 수를 비교한다.

    python notebooks/simulation.py --scenario both --users 200 --seed 42
"""

from __future__ import annotations

import argparse
import math
import sys
from pathlib import Path
from typing import Any

import numpy as np
from numpy.typing import NDArray

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.engine.decision_analysis import ranking  # noqa: E402
from app.engine.likelihood import (  # noqa: E402
    CriteriaABSpec,
    QuestionSpec,
    SliderSpec,
    response_logprobs,
    response_options,
)
from app.engine.normalize import Criterion, Scale, freeze_scales, normalize  # noqa: E402
from app.engine.posterior import N_PARTICLES, Answer, fit  # noqa: E402
from app.engine.question_selector import active_criteria, best_info_gain, build_scenarios, next_question  # noqa: E402
from app.engine.stopping import max_questions, should_stop  # noqa: E402

STRATEGIES = ("eig", "random")
FIXED_COUNTS = (3, 5, 7)
QUICK_USERS = 30
DEFAULT_USERS = 200
DEFAULT_SEED = 42
SCENARIO_IDS = {"purchase": 0, "career": 1}

Problem = tuple[list[Criterion], dict[str, Scale], NDArray[np.float64]]


def purchase_problem() -> Problem:
    """구매형: 노트북 4개, 기준 5개 (숫자형 4 + 단계형 1)."""
    crit = [
        Criterion("price", "numeric", "lower_better"),
        Criterion("weight", "numeric", "lower_better"),
        Criterion("battery", "numeric", "higher_better"),
        Criterion("storage", "numeric", "higher_better"),
        Criterion("perf", "rating", levels=5),
    ]
    options = ["galaxy", "gram", "yoga", "macbook"]
    rows = {
        "galaxy": (189, 1.23, 12, 512, 4),
        "gram": (165, 1.19, 15, 256, 3),
        "yoga": (150, 1.45, 10, 512, 4),
        "macbook": (240, 0.98, 18, 1024, 5),
    }
    vals = {(o, c.id): rows[o][k] for o in options for k, c in enumerate(crit)}
    scales = freeze_scales(crit, vals)
    matrix, _ = normalize(vals, crit, scales, option_ids=options)
    return crit, scales, matrix.values


def career_problem() -> Problem:
    """진로형: 학과 4개, 기준 5개 (순위 1 + 단계형 2 + 숫자형 2). tests/engine/test_simulation.py의 _career()와 같다."""
    crit = [
        Criterion("interest", "rating", input_mode="rank"),
        Criterion("fit", "rating", levels=4),
        Criterion("outlook", "rating", levels=5),
        Criterion("commute", "numeric", "lower_better"),
        Criterion("tuition", "numeric", "lower_better"),
    ]
    options = ["cs", "biz", "design", "edu"]
    vals = {
        ("cs", "interest"): 1, ("biz", "interest"): 3, ("design", "interest"): 2, ("edu", "interest"): 4,
        ("cs", "fit"): 4, ("biz", "fit"): 2, ("design", "fit"): 3, ("edu", "fit"): 2,
        ("cs", "outlook"): 5, ("biz", "outlook"): 3, ("design", "outlook"): 3, ("edu", "outlook"): 2,
        ("cs", "commute"): 70, ("biz", "commute"): 30, ("design", "commute"): 50, ("edu", "commute"): 20,
        ("cs", "tuition"): 800, ("biz", "tuition"): 600, ("design", "tuition"): 900, ("edu", "tuition"): 500,
    }  # fmt: skip
    scales = freeze_scales(crit, vals)
    matrix, _ = normalize(vals, crit, scales, option_ids=options)
    return crit, scales, matrix.values


PROBLEMS = {"purchase": purchase_problem, "career": career_problem}


def covered(answers: list[Answer]) -> set[int]:
    return {k for a in answers if a.response is not None for k in (a.spec.a, a.spec.b)}


def random_question(
    mean: NDArray[np.float64],
    V: NDArray[np.float64],
    crit: list[Criterion],
    scales: dict[str, Scale],
    rng: np.random.Generator,
) -> QuestionSpec | None:
    """무작위 기준 쌍 + 무작위 유형(slider_pair / ab_criteria / ab_scenario). 규칙·EIG는 쓰지 않는다."""
    active = active_criteria(V)
    if len(active) < 2:
        return None
    a, b = (int(x) for x in rng.choice(active, size=2, replace=False))
    scenarios = build_scenarios(mean, a, b, crit, scales)
    kinds = ["slider", "ab"] + (["scenario"] if scenarios else [])
    kind = kinds[int(rng.integers(len(kinds)))]
    if kind == "slider":
        return SliderSpec(a, b)
    if kind == "ab":
        return CriteriaABSpec(a, b)
    return scenarios[int(rng.integers(len(scenarios)))]


def sample_response(q: QuestionSpec, true_w: NDArray[np.float64], rng: np.random.Generator, noise: float) -> Any:
    options = response_options(q)
    if noise > 0 and rng.random() < noise:
        return options[int(rng.integers(len(options)))]
    probs = np.exp(response_logprobs(q, true_w[None, :]))[0]
    return options[int(rng.choice(len(probs), p=probs / probs.sum()))]


def run_user(
    strategy: str,
    true_w: NDArray[np.float64],
    problem: Problem,
    fit_seed: int,
    rng: np.random.Generator,
    noise: float,
) -> dict[str, Any]:
    """한 사용자의 질문 궤적. 답변 n개 시점의 사후 평균을 모두 기록해 두고 종료 시점·고정 개수 시점을 고른다."""
    crit, scales, V = problem
    K = len(true_w)
    n_max = max(max(FIXED_COUNTS), max_questions(K))
    answers: list[Answer] = []
    history: list[QuestionSpec] = []
    means: dict[int, NDArray[np.float64]] = {}
    stop_n: int | None = None
    reason: str | None = None
    while True:
        n = len(answers)
        post = fit(fit_seed, K, answers)
        means[n] = post.mean
        if stop_n is None and n > 0:
            stop, why = should_stop(post, V, answers, best_info_gain(post, V, crit, scales=scales))
            if stop:
                stop_n, reason = n, why
        if (stop_n is not None and n >= max(FIXED_COUNTS)) or n >= n_max:
            break
        if strategy == "eig":
            q = next_question(post, V, crit, history, scales=scales, covered=covered(answers))
        else:
            q = random_question(post.mean, V, crit, scales, rng)
        if q is None:
            stop_n = stop_n if stop_n is not None else n
            break
        history.append(q)
        answers.append(Answer(q, sample_response(q, true_w, rng, noise)))
    last = max(means)
    stop_n = stop_n if stop_n is not None else last
    top_true = ranking(true_w, V)[0]

    def score(n: int) -> tuple[float, float]:
        m = means[min(n, last)]
        return float(np.mean(np.abs(m - true_w))), float(ranking(m, V)[0] == top_true)

    out: dict[str, Any] = {"stop_n": stop_n, "reason": reason or "NONE"}
    out["mae"], out["hit"] = score(stop_n)
    for k in FIXED_COUNTS:
        out[f"mae@{k}"], out[f"hit@{k}"] = score(k)
    return out


def _stat(x: list[float]) -> dict[str, float]:
    a = np.asarray(x, dtype=float)
    se = float(a.std(ddof=1) / math.sqrt(len(a))) if len(a) > 1 else 0.0
    return {"mean": float(a.mean()), "se": se}


def run_experiment(scenario: str, users: int, seed: int, noise: float = 0.0) -> dict[str, Any]:
    """한 시나리오를 두 전략으로 돌려 요약 dict를 돌려준다. 같은 seed면 항상 같은 결과."""
    problem = PROBLEMS[scenario]()
    K = problem[2].shape[1]
    sid = SCENARIO_IDS[scenario]
    rows: dict[str, list[dict[str, Any]]] = {s: [] for s in STRATEGIES}
    for i in range(users):
        true_w = np.random.default_rng([seed, sid, i, 0]).dirichlet(np.ones(K))
        fit_seed = (seed * 1_000_003 + sid * 10_007 + i) % (2**31)
        for j, s in enumerate(STRATEGIES):
            rng = np.random.default_rng([seed, sid, i, 1 + j])
            rows[s].append(run_user(s, true_w, problem, fit_seed, rng, noise))

    def col(s: str, key: str) -> list[float]:
        return [float(r[key]) for r in rows[s]]

    summary: dict[str, Any] = {
        "scenario": scenario, "users": users, "seed": seed, "noise": noise, "particles": N_PARTICLES,
        "criteria": K, "options": problem[2].shape[0],
        "adaptive": {}, "fixed": {k: {} for k in FIXED_COUNTS}, "paired": {},
    }  # fmt: skip
    for s in STRATEGIES:
        reasons: dict[str, int] = {}
        for r in rows[s]:
            reasons[r["reason"]] = reasons.get(r["reason"], 0) + 1
        summary["adaptive"][s] = {
            "mae": _stat(col(s, "mae")), "hit": _stat(col(s, "hit")), "questions": _stat(col(s, "stop_n")),
            "reasons": reasons,
        }  # fmt: skip
        for k in FIXED_COUNTS:
            summary["fixed"][k][s] = {"mae": _stat(col(s, f"mae@{k}")), "hit": _stat(col(s, f"hit@{k}"))}
    for key in ("mae", *(f"mae@{k}" for k in FIXED_COUNTS)):
        diff = [r - e for r, e in zip(col("random", key), col("eig", key), strict=True)]
        summary["paired"][key] = _stat(diff)  # 랜덤 − EIG, 양수면 EIG가 더 정확
    return summary


def format_markdown(res: dict[str, Any]) -> str:
    title = (
        f"### {res['scenario']} (기준 {res['criteria']}개, 선택지 {res['options']}개, "
        f"사용자 {res['users']}명, ε={res['noise']})"
    )
    f = lambda d, p=3: f"{d['mean']:.{p}f} ± {d['se']:.{p}f}"  # noqa: E731
    lines = [title, "", "종료 규칙(5.10)을 그대로 적용한 결과 (평균 ± 표준오차)", "",
             "| 전략 | MAE | 1위 적중률 | 평균 질문 수 |", "|---|---|---|---|"]  # fmt: skip
    for s in STRATEGIES:
        a = res["adaptive"][s]
        lines.append(f"| {s} | {f(a['mae'])} | {f(a['hit'])} | {f(a['questions'], 2)} |")
    lines += ["", "같은 질문 수에서의 정확도", "", "| 질문 수 | 전략 | MAE | 1위 적중률 |", "|---|---|---|---|"]
    for k in FIXED_COUNTS:
        for s in STRATEGIES:
            c = res["fixed"][k][s]
            lines.append(f"| {k} | {s} | {f(c['mae'])} | {f(c['hit'])} |")
    p = res["paired"]
    lines += ["", "MAE 차이 (랜덤 − EIG, 양수면 EIG가 더 정확): "
              + ", ".join(f"{k}={f(v, 4)}" for k, v in p.items())]  # fmt: skip
    return "\n".join(lines)


def run_all(scenario: str, users: int, seed: int, noise: float = 0.0) -> list[dict[str, Any]]:
    names = list(PROBLEMS) if scenario == "both" else [scenario]
    return [run_experiment(n, users, seed, noise) for n in names]


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--users", type=int, default=DEFAULT_USERS)
    ap.add_argument("--seed", type=int, default=DEFAULT_SEED)
    ap.add_argument("--scenario", choices=["purchase", "career", "both"], default="both")
    ap.add_argument("--noise", type=float, default=0.0, help="무작위 응답 확률 ε")
    ap.add_argument("--quick", action="store_true", help=f"{QUICK_USERS}명으로 빠르게")
    args = ap.parse_args(argv)
    users = QUICK_USERS if args.quick else args.users
    for res in run_all(args.scenario, users, args.seed, args.noise):
        print(format_markdown(res), end="\n\n", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

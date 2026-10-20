"""엔진 교차검증 (개발기획서 14장): 입자 사후분포(app.engine.posterior.fit) vs PyMC NUTS.

PyMC는 테스트 의존성이 아니다. 별도 venv에 설치해 오프라인으로만 돌린다.

    PYTHONPATH=backend <pymc venv>/bin/python backend/notebooks/pymc_check.py [--tol 0.03]

모델은 likelihood.py를 그대로 옮긴 것이다: Dirichlet(1) 사전분포 × 답변별 우도(pm.Potential).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np
import pymc as pm
import pytensor.tensor as pt
from numpy.typing import NDArray

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.engine.likelihood import (  # noqa: E402
    BT_LAMBDA,
    SCENARIO_BETA,
    SLIDER_C,
    SLIDER_MAX,
    SLIDER_SIGMA,
    CriteriaABSpec,
    ScenarioSpec,
    SliderSpec,
)
from app.engine.posterior import INTERVAL, N_PARTICLES, Answer, fit  # noqa: E402

QUANTILES = INTERVAL  # (0.10, 0.90)
ENGINE_SEEDS = (1, 2, 3, 4, 5)


def slider(a: int, b: int, s: int) -> Answer:
    return Answer(SliderSpec(a, b), s)


def ab(a: int, b: int, r: str) -> Answer:
    return Answer(CriteriaABSpec(a, b), r)  # type: ignore[arg-type]


def scen(a: int, b: int, K: int, da: float, db: float, r: str) -> Answer:
    delta = [0.0] * K
    delta[a], delta[b] = da, -db
    return Answer(ScenarioSpec(a, b, tuple(delta), da, db), r)  # type: ignore[arg-type]


CASES: dict[str, tuple[int, list[Answer]]] = {
    "K3 슬라이더 3개": (3, [slider(0, 1, 2), slider(1, 2, -1), slider(0, 2, 3)]),
    "K3 기준 A/B 4개": (3, [ab(0, 1, "A"), ab(1, 2, "A"), ab(0, 2, "B"), ab(0, 1, "A")]),
    "K3 상황형 혼합": (
        3,
        [slider(0, 1, 1), scen(0, 1, 3, 0.5, 0.4, "A"), ab(1, 2, "B"), scen(1, 2, 3, 0.3, 0.6, "B"), slider(0, 2, -2)],
    ),
    "K5 혼합 7개": (
        5,
        [
            slider(0, 1, 2),
            slider(1, 2, -1),
            slider(2, 3, 1),
            scen(0, 3, 5, 0.5, 0.5, "A"),
            ab(3, 4, "B"),
            scen(1, 4, 5, 0.7, 0.3, "A"),
            slider(0, 4, 3),
        ],
    ),  # fmt: skip
    "K5 강한 답변 10개": (
        5,
        [
            slider(0, 1, 4),
            slider(0, 2, 5),
            slider(0, 3, 4),
            slider(0, 4, 5),
            slider(1, 2, 2),
            ab(0, 1, "A"),
            ab(0, 2, "A"),
            scen(0, 1, 5, 0.5, 0.6, "A"),
            scen(0, 3, 5, 0.7, 0.5, "A"),
            slider(3, 4, -1),
        ],
    ),  # fmt: skip
}


def pymc_model(K: int, answers: list[Answer]) -> pm.Model:
    with pm.Model() as model:
        w = pm.Dirichlet("w", a=np.ones(K))
        s_vals = np.arange(-SLIDER_MAX, SLIDER_MAX + 1)
        terms = []
        for ans in answers:
            spec, r = ans.spec, ans.response
            if isinstance(spec, SliderSpec):
                lr = pt.log(w[spec.a]) - pt.log(w[spec.b])
                logits = -((lr - SLIDER_C * s_vals) ** 2) / (2 * SLIDER_SIGMA**2)
                ll = logits[int(r) + SLIDER_MAX] - pt.logsumexp(logits)  # type: ignore[arg-type]
            elif isinstance(spec, CriteriaABSpec):
                la, lb = BT_LAMBDA * pt.log(w[spec.a]), BT_LAMBDA * pt.log(w[spec.b])
                norm = pt.logsumexp(pt.stack([la, lb]))
                ll = (la if r == "A" else lb) - norm
            else:
                z = SCENARIO_BETA * pt.dot(w, np.asarray(spec.delta))
                ll = -pt.softplus(-z) if r == "A" else -pt.softplus(z)
            terms.append(ll)
        pm.Potential("answers", pt.sum(pt.stack(terms)))
    return model


def engine_stats(K: int, answers: list[Answer], seed: int) -> tuple[NDArray[np.float64], NDArray[np.float64]]:
    post = fit(seed, K, answers, n=N_PARTICLES)
    q = np.empty((K, 2))
    for k in range(K):
        order = np.argsort(post.W[:, k])
        cum = np.cumsum(post.p[order])
        idx = np.minimum(np.searchsorted(cum, QUANTILES), len(cum) - 1)
        q[k] = post.W[:, k][order][idx]
    return post.mean, q


def nuts_stats(K: int, answers: list[Answer], seed: int) -> tuple[NDArray[np.float64], NDArray[np.float64], float, int]:
    with pymc_model(K, answers):
        idata = pm.sample(
            draws=4000, tune=1500, chains=4, target_accept=0.95, random_seed=seed, progressbar=False,
            compute_convergence_checks=False,
        )  # fmt: skip
    draws = idata.posterior["w"].values.reshape(-1, K)
    rhat = float(pm.stats.rhat(idata)["w"].max())
    ndiv = int(idata.sample_stats["diverging"].sum())
    mean = draws.mean(axis=0)
    q = np.quantile(draws, QUANTILES, axis=0).T
    return mean, q, rhat, ndiv


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--tol", type=float, default=0.03)
    ap.add_argument("--seed", type=int, default=2026, help="NUTS 시드")
    args = ap.parse_args(argv)
    print(
        f"입자 N={N_PARTICLES}, 엔진 시드 {ENGINE_SEEDS} / NUTS 4체인x4000 draws, 시드 {args.seed}, "
        f"허용 오차 ±{args.tol}\n"
    )
    print(
        "| 사례 | NUTS R-hat / 발산 | 평균 최대 편차 (시드별 최악) | 10% 분위 최대 편차 | 90% 분위 최대 편차 | 판정 |"
    )
    print("|---|---|---|---|---|---|")
    detail: list[str] = []
    ok_all = True
    for name, (K, answers) in CASES.items():
        nm, nq, rhat, ndiv = nuts_stats(K, answers, args.seed)
        dm, dlo, dhi = [], [], []
        first: tuple[NDArray[np.float64], NDArray[np.float64]] | None = None
        for s in ENGINE_SEEDS:
            em, eq = engine_stats(K, answers, s)
            first = first or (em, eq)
            dm.append(float(np.abs(em - nm).max()))
            dlo.append(float(np.abs(eq[:, 0] - nq[:, 0]).max()))
            dhi.append(float(np.abs(eq[:, 1] - nq[:, 1]).max()))
        ok = max(dm + dlo + dhi) <= args.tol
        ok_all &= ok
        print(
            f"| {name} | {rhat:.3f} / {ndiv} | {max(dm):.4f} (시드별 {min(dm):.4f}~{max(dm):.4f}) "
            f"| {max(dlo):.4f} | {max(dhi):.4f} | {'통과' if ok else '초과'} |",
            flush=True,
        )
        assert first is not None
        for k in range(K):
            detail.append(
                f"| {name} | w{k} | {nm[k]:.4f} | {first[0][k]:.4f} | {nq[k, 0]:.4f}~{nq[k, 1]:.4f} "
                f"| {first[1][k, 0]:.4f}~{first[1][k, 1]:.4f} |"
            )
    print("\n기준별 값 (엔진은 시드 1)\n")
    print("| 사례 | 기준 | NUTS 평균 | 입자 평균 | NUTS 10~90% | 입자 10~90% |")
    print("|---|---|---|---|---|---|")
    print("\n".join(detail))
    return 0 if ok_all else 1


if __name__ == "__main__":
    raise SystemExit(main())

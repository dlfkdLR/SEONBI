"""파이썬 참조 구현에서 골든 파일을 만든다 (개발기획서 5.11, 14).

자바 엔진(com.seonbi.engine)은 같은 입력으로 이 파일의 값과 대조한다.
결정적인 부분(정규화·우도·EIG·전환 조건 등)은 1e-9로, 난수가 들어가는 부분(사후분포·섭동 유지율)은 ±0.03으로 비교한다.

실행: engine-reference/에서  uv run python golden/generate.py ../backend/src/test/resources/golden
"""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path
from typing import Any

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.engine import decision_analysis as da  # noqa: E402
from app.engine import hidden_preference as hp  # noqa: E402
from app.engine import posterior as po  # noqa: E402
from app.engine import question_selector as qs  # noqa: E402
from app.engine import stopping as st  # noqa: E402
from app.engine.likelihood import (  # noqa: E402
    CriteriaABSpec,
    ScenarioSpec,
    SliderSpec,
    log_likelihood,
    response_logprobs,
)
from app.engine.normalize import Condition, Criterion, Scale, freeze_scales, normalize  # noqa: E402


def spec_json(s: Any) -> dict[str, Any]:
    if isinstance(s, SliderSpec):
        return {"type": "slider", "a": s.a, "b": s.b}
    if isinstance(s, CriteriaABSpec):
        return {"type": "ab", "a": s.a, "b": s.b}
    return {"type": "scenario", "a": s.a, "b": s.b, "delta": list(s.delta), "rawA": s.raw_a, "rawB": s.raw_b}


def criterion_json(c: Criterion) -> dict[str, Any]:
    return {
        "id": c.id, "type": c.type, "direction": c.direction, "levels": c.levels, "inputMode": c.input_mode,
        "scaleMin": c.scale_min, "scaleMax": c.scale_max,
    }


def scales_json(scales: dict[str, Scale]) -> dict[str, list[float]]:
    return {k: [v.min, v.max] for k, v in scales.items()}


def mat(a: Any) -> list[list[float]]:
    return [[float(x) for x in row] for row in np.asarray(a)]


# ---------- 1. 정규화 ----------
def normalize_cases() -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []

    def add(name: str, crit: list[Criterion], opts: list[str], vals: dict[tuple[str, str], float | bool],
            conds: list[Condition] | None = None, scales: dict[str, Scale] | None = None) -> None:
        conds = conds or []
        excl0 = {k for k in __import__("app.engine.normalize", fromlist=["excluded_options"]).excluded_options(opts, vals, conds)}
        sc = scales if scales is not None else freeze_scales(crit, vals, excl0)
        m, excluded = normalize(vals, crit, sc, option_ids=opts, conditions=conds)
        cases.append({
            "name": name,
            "criteria": [criterion_json(c) for c in crit],
            "options": opts,
            "values": [[o, c, float(v)] for (o, c), v in vals.items()],
            "conditions": [
                {"id": c.id, "kind": c.kind, "criterionId": c.criterion_id, "operator": c.operator,
                 "value": c.value, "satisfiedBy": sorted(c.satisfied_by)} for c in conds
            ],
            "scales": scales_json(sc),
            "keptOptions": list(m.option_ids),
            "matrix": mat(m.values),
            "excluded": excluded,
        })

    crit = [Criterion("price", "numeric", "lower_better"), Criterion("weight", "numeric", "lower_better"),
            Criterion("perf", "rating", levels=5)]
    opts = ["galaxy", "gram", "yoga"]
    vals = {("galaxy", "price"): 189, ("gram", "price"): 165, ("yoga", "price"): 150,
            ("galaxy", "weight"): 1.23, ("gram", "weight"): 1.19, ("yoga", "weight"): 1.45,
            ("galaxy", "perf"): 4, ("gram", "perf"): 3, ("yoga", "perf"): 4}
    add("example_5_12", crit, opts, vals)

    crit2 = [Criterion("salary", "numeric", "higher_better", scale_min=0, scale_max=1000),
             Criterion("remote", "boolean", "true_better"),
             Criterion("fit", "rating", levels=3, input_mode="rank"),
             Criterion("same", "numeric", "higher_better")]
    opts2 = ["a", "b", "c", "d"]
    vals2 = {("a", "salary"): 300, ("b", "salary"): 700, ("c", "salary"): 1200, ("d", "salary"): 500,
             ("a", "remote"): True, ("b", "remote"): False, ("c", "remote"): True, ("d", "remote"): False,
             ("a", "fit"): 1, ("b", "fit"): 3, ("c", "fit"): 3, ("d", "fit"): 2,
             ("a", "same"): 7, ("b", "same"): 7, ("c", "same"): 7, ("d", "same"): 7}
    add("rank_boolean_userscale_equal", crit2, opts2, vals2)

    conds = [Condition("c1", "threshold", "salary", ">=", 400),
             Condition("c2", "required_tag", satisfied_by=frozenset({"b", "c", "d"}))]
    add("conditions_exclude", crit2, opts2, vals2, conds)

    crit3 = [Criterion("price", "numeric", "lower_better"), Criterion("grade", "rating", levels=None)]
    vals3 = {("x", "price"): 10, ("y", "price"): 20, ("x", "grade"): 5, ("y", "grade"): 1}
    add("rating_default_levels", crit3, ["x", "y"], vals3)
    return cases


# ---------- 2. 우도 ----------
def likelihood_cases() -> dict[str, Any]:
    rng = np.random.default_rng(7)
    W = rng.dirichlet(np.ones(4), size=8)
    specs = [SliderSpec(0, 2), CriteriaABSpec(1, 3),
             ScenarioSpec(0, 1, (0.3, -0.5, 0.0, 0.0), 12.0, 0.5)]
    out = []
    for s in specs:
        resp: list[Any] = [None] + (list(range(-5, 6)) if isinstance(s, SliderSpec) else ["A", "B"])
        out.append({
            "spec": spec_json(s),
            "responseLogprobs": mat(response_logprobs(s, W)),
            "responses": [{"response": r, "logLik": [float(x) for x in log_likelihood(s, r, W)]} for r in resp],
        })
    return {"W": mat(W), "cases": out}


# ---------- 3. 질문 선택 ----------
def selector_cases() -> dict[str, Any]:
    from tests.engine.example_data import CRIT, build_example  # type: ignore[import-not-found]

    scales, V = build_example()
    answers = [po.Answer(SliderSpec(0, 2), -2), po.Answer(SliderSpec(2, 1), 1)]
    post = po.fit(42, 3, answers, n=300)  # 입자는 파이썬이 만든 것을 그대로 저장한다
    cands = qs.score_candidates(post, V, CRIT, [], scales=scales)
    mean = post.mean

    histories: list[list[Any]] = [[], [SliderSpec(0, 2)], [SliderSpec(0, 2), SliderSpec(2, 1)],
                                  [SliderSpec(0, 2), SliderSpec(2, 1), CriteriaABSpec(0, 1)],
                                  [SliderSpec(0, 2), SliderSpec(2, 1), CriteriaABSpec(0, 1), CriteriaABSpec(1, 2)]]
    nexts = []
    for h in histories:
        n = qs.next_question(post, V, CRIT, h, scales=scales)
        nexts.append({"history": [spec_json(x) for x in h], "next": spec_json(n) if n else None})
    nexts.append({"history": [spec_json(x) for x in histories[2]], "covered": [0],
                  "next": spec_json(qs.next_question(post, V, CRIT, histories[2], scales=scales, covered=[0]))})

    steps = [0.26, 39, 1, 5, 1000, 0.07, 12.5, 200]
    return {
        "W": mat(post.W), "p": [float(x) for x in post.p], "V": mat(V),
        "criteria": [criterion_json(c) for c in CRIT], "scales": scales_json(scales),
        "candidates": [{"spec": spec_json(c.spec), "eig": float(c.eig)} for c in cands],
        "bestInfoGain": float(qs.best_info_gain(post, V, CRIT, scales=scales)),
        "scenarios": [spec_json(s) for s in qs.build_scenarios(mean, 0, 1, CRIT, scales)],
        "mean": [float(x) for x in mean],
        "next": nexts,
        "niceStep": [{"span": s, "step": qs.nice_step(s)} for s in steps],
        "activeCriteria": qs.active_criteria(V),
    }


# ---------- 4. 분석 ----------
def analysis_cases() -> list[dict[str, Any]]:
    from tests.engine.example_data import CRIT, build_example  # type: ignore[import-not-found]

    scales, V = build_example()
    out = []
    for name, seed, answers in [
        ("two_sliders", 42, [po.Answer(SliderSpec(0, 2), -2), po.Answer(SliderSpec(2, 1), 1)]),
        ("with_scenario", 42, [po.Answer(SliderSpec(0, 2), -2), po.Answer(SliderSpec(2, 1), 1),
                               po.Answer(ScenarioSpec(0, 1, (0.5, -0.5, 0.0), 20.0, 0.1), "B")]),
    ]:
        post = po.fit(seed, 3, answers, n=300)
        mean = post.mean
        raw = np.array([[189, 1.23, 4], [165, 1.19, 3], [150, 1.45, 4]], dtype=float)
        s = da.stability(post, V, seed=1)
        out.append({
            "name": name,
            "W": mat(post.W), "p": [float(x) for x in post.p], "V": mat(V), "mean": [float(x) for x in mean],
            "criteria": [criterion_json(c) for c in CRIT], "scales": scales_json(scales), "raw": mat(raw),
            "scores": [float(x) for x in da.scores(mean, V)],
            "ranking": da.ranking(mean, V),
            "stability": {"top": s.top, "pBest": s.p_best, "grade": s.grade, "perturbKeep": s.perturb_keep},
            "changeConditions": [
                {"criterion": c.criterion, "current": c.current, "threshold": c.threshold,
                 "direction": c.direction, "newTop": c.new_top} for c in da.change_conditions(mean, V)
            ],
            "sensitivity": [{"k": k, "t": [float(x) for x in da.sensitivity(mean, V, k).t],
                             "scores": mat(da.sensitivity(mean, V, k).scores)} for k in range(3)],
            "valueConditions": [
                {"option": c.option, "criterion": c.criterion, "current": c.current, "threshold": c.threshold,
                 "direction": c.direction, "neededV": c.needed_v}
                for c in da.value_conditions(mean, V, CRIT, scales, raw)
            ],
        })
    return out


# ---------- 5. 종료 판단 ----------
def stopping_cases() -> dict[str, Any]:
    from tests.engine.example_data import build_example  # type: ignore[import-not-found]

    _, V = build_example()
    rng = np.random.default_rng(3)
    W = rng.dirichlet(np.ones(3), size=200)
    p_flat = np.full(200, 1 / 200)
    # 한쪽 선택지에 몰린 사후분포
    p_conf = np.exp(-60 * np.abs(W[:, 0] - 0.05)) + 1e-9
    p_conf = p_conf / p_conf.sum()

    def hist(n_engine: int, n_free: int = 0) -> list[po.Answer]:
        return [po.Answer(SliderSpec(0, 1), 1)] * n_engine + [po.Answer(SliderSpec(0, 2), 1, "free_text")] * n_free

    cases = []
    for label, p in (("flat", p_flat), ("conf", p_conf)):
        post = po.Posterior(W, p)
        top = float(np.max(post.p @ po.best_shares(W, V)))
        for n_engine, n_free, eig, kw in [
            (0, 0, 0.5, {}), (2, 0, 0.5, {}), (3, 0, 0.5, {}), (3, 0, 0.01, {}), (6, 0, 0.5, {}),
            (5, 3, 0.5, {}), (4, 0, 0.5, {"user_stopped": True}), (6, 0, 0.5, {"question_limit": 9}),
            (9, 0, 0.5, {"question_limit": 9}), (4, 0, 0.5, {"question_limit": 7, "round_start": 4}),
            (5, 0, 0.5, {"question_limit": 7, "round_start": 4}), (5, 0, 0.01, {"question_limit": 7, "round_start": 4}),
            (7, 0, 0.5, {"question_limit": 7, "round_start": 4}),
        ]:
            go, reason = st.should_stop(post, V, hist(n_engine, n_free), eig, **kw)
            cases.append({"label": label, "nEngine": n_engine, "nFree": n_free, "bestEig": eig,
                          "userStopped": kw.get("user_stopped", False), "questionLimit": kw.get("question_limit"),
                          "roundStart": kw.get("round_start"), "stop": go, "reason": reason, "top": top})
    return {
        "W": mat(W), "pFlat": [float(x) for x in p_flat], "pConf": [float(x) for x in p_conf], "V": mat(V),
        "cases": cases,
        "maxQuestions": [{"k": k, "limit": st.max_questions(k)} for k in range(2, 9)],
        "extraRoundLimit": [{"answered": a, "limit": st.extra_round_limit(a)} for a in (0, 3, 7)],
        "raisedLimit": [{"current": c, "answered": a, "limit": st.raised_limit(c, a)} for c, a in ((6, 2), (6, 5), (10, 4))],
    }


# ---------- 6. 숨은 선호 ----------
def hidden_cases() -> dict[str, Any]:
    rng = np.random.default_rng(11)
    W = rng.dirichlet(np.ones(3), size=500)
    answers = [po.Answer(SliderSpec(0, 1), 4), po.Answer(SliderSpec(0, 1), 3),
               po.Answer(ScenarioSpec(0, 1, (0.5, -0.5, 0.0), 20.0, 0.1), "B"),
               po.Answer(ScenarioSpec(0, 1, (0.4, -0.5, 0.0), 15.0, 0.1), "B"),
               po.Answer(ScenarioSpec(1, 2, (0.0, 0.3, -0.3), 1.0, 1.0), "A")]
    found = hp.detect(W, answers)
    return {
        "W": mat(W),
        "answers": [{"spec": spec_json(a.spec), "response": a.response} for a in answers],
        "mismatches": [{"statedOver": m.stated_over, "revealedOver": m.revealed_over, "statedP": m.stated_p,
                        "revealedP": m.revealed_p, "nScenarios": m.n_scenarios} for m in found],
    }


# ---------- 7. 사후분포 통계 (난수가 다르므로 ±0.03 대조) ----------
def posterior_stats() -> list[dict[str, Any]]:
    from tests.engine.example_data import build_example  # type: ignore[import-not-found]

    _, V = build_example()
    scen = ScenarioSpec(0, 1, (0.5, -0.5, 0.0), 20.0, 0.1)
    runs = [
        ("two_sliders", [po.Answer(SliderSpec(0, 2), -2), po.Answer(SliderSpec(2, 1), 1)]),
        ("with_scenario", [po.Answer(SliderSpec(0, 2), -2), po.Answer(SliderSpec(2, 1), 1), po.Answer(scen, "B")]),
        ("strong_evidence", [po.Answer(SliderSpec(0, 1), 5)] * 6 + [po.Answer(CriteriaABSpec(0, 2), "A")] * 4),
    ]
    out = []
    for name, answers in runs:
        post = po.fit(42, 3, answers)
        s = po.summary(post, V)
        out.append({"name": name, "seed": 42,
                    "answers": [{"spec": spec_json(a.spec), "response": a.response} for a in answers],
                    "mean": [float(x) for x in s.mean], "intervals": mat(s.intervals),
                    "pBest": [float(x) for x in s.p_best], "entropy": s.entropy, "ess": post.ess})
    return out


def main() -> None:
    out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "golden-out")
    out_dir.mkdir(parents=True, exist_ok=True)
    files = {
        "normalize": normalize_cases(), "likelihood": likelihood_cases(), "selector": selector_cases(),
        "analysis": analysis_cases(), "stopping": stopping_cases(), "hidden": hidden_cases(),
        "posterior": posterior_stats(),
    }
    for name, data in files.items():
        (out_dir / f"{name}.json").write_text(json.dumps(data, ensure_ascii=False, allow_nan=False), encoding="utf-8")
        print(f"wrote {name}.json")
    assert math.isfinite(1.0)


if __name__ == "__main__":
    main()

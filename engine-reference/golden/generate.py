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


# ---------- 8. 상태 머신 규칙 (4.2) ----------
def state_rules_cases() -> dict[str, Any]:
    from app.services import state_rules as sr

    checks = []
    for action in sr.Action:
        for status in sr.Status:
            for answered in (0, 1, 3):
                v = sr.check(action, status, answered=answered)
                checks.append({"action": action.value, "status": status.value, "answered": answered,
                               "violation": None if v is None else {"httpStatus": v.http_status, "code": v.code}})
    transitions = []
    for name in ("after_question_start", "after_stop_condition", "after_criterion_added", "after_finalize"):
        for status in sr.Status:
            try:
                result: str | None = getattr(sr, name)(status).value
            except sr.InvalidTransition:
                result = None
            transitions.append({"fn": name, "status": status.value, "result": result})
    rounds = []
    for status in sr.Status:
        for extra in (False, True):
            try:
                r: str | None = sr.round_kind(status, extra_round_active=extra)
            except sr.InvalidTransition:
                r = None
            rounds.append({"status": status.value, "extra": extra, "result": r})
    provisional = [{"status": st.value, "answered": a, "result": sr.is_provisional(st, a)}
                   for st in sr.Status for a in (0, 1, 5)]
    return {"checks": checks, "transitions": transitions, "rounds": rounds, "provisional": provisional}


# ---------- 9. 질문 문장 템플릿 (5.1, 5.5) ----------
def question_text_cases() -> dict[str, Any]:
    from app.services import question_text as qt

    def meta(**kw: Any) -> qt.CriterionMeta:
        return qt.CriterionMeta(**kw)

    price = meta(id="c1", name="가격", type="numeric", direction="lower_better", unit="만 원")
    weight = meta(id="c2", name="무게", type="numeric", direction="lower_better", unit="kg")
    perf = meta(id="c3", name="성능", type="rating", levels=["낮음", "보통", "높음", "매우 높음", "최고"])
    camera = meta(id="c4", name="카메라", type="boolean", direction="true_better")
    battery = meta(id="c5", name="배터리", type="numeric", direction="higher_better", unit="시간")
    interest = meta(id="c6", name="흥미", type="rating", input_mode="rank", low_name="학과 A", high_name="학과 B")
    fit = meta(id="c7", name="적성", type="rating", levels=["안 맞음", "그럭저럭", "잘 맞음", "매우 잘 맞음"],
               low_name="학과 C")
    metas = [price, weight, perf, camera, battery, interest]
    scales = {"c1": Scale(150, 189), "c2": Scale(1.19, 1.45), "c5": Scale(8, 20.5)}

    def crit_json(m: qt.CriterionMeta) -> dict[str, Any]:
        return {"id": m.id, "name": m.name, "type": m.type, "direction": m.direction, "unit": m.unit,
                "levels": m.levels, "inputMode": m.input_mode, "lowName": m.low_name, "highName": m.high_name}

    cases: list[dict[str, Any]] = []

    def add(name: str, spec: Any, category: str = "purchase", ranges: dict[str, str] | None = None,
            ms: list[qt.CriterionMeta] | None = None) -> None:
        ms = ms or metas
        try:
            out: Any = qt.build_question(spec, ms, scales, category, ranges=ranges)
        except ValueError as e:
            out = None
            err = str(e)
        else:
            err = None
        cases.append({"name": name, "spec": spec_json(spec), "category": category, "ranges": ranges,
                      "criteria": [crit_json(m) for m in ms], "result": out, "error": err})

    add("slider_numeric", SliderSpec(0, 1))
    add("slider_rating_boolean", SliderSpec(2, 3))
    add("slider_rank_names", SliderSpec(5, 0))
    add("slider_ranges_override", SliderSpec(0, 4), ranges={"c1": "150~189만 원 (세일 포함)"})
    add("ab_criteria", CriteriaABSpec(0, 2))
    add("scenario_numeric", ScenarioSpec(0, 1, (0.5, -0.5, 0, 0, 0, 0), 20.0, 0.1))
    add("scenario_higher_better", ScenarioSpec(4, 0, (0, 0, 0, 0, 0.3, 0), 2.5, 10.0), category="career")
    add("scenario_rating", ScenarioSpec(2, 4, (0, 0, 0.5, 0, -0.2, 0), 2.0, 1.5), category="activity")
    add("scenario_boolean", ScenarioSpec(3, 1, (0, -0.4, 0, 1.0, 0, 0), 1.0, 0.07), category="living")
    add("scenario_rank_rejected", ScenarioSpec(5, 0, (0, 0, 0, 0, 0, 0), 1.0, 1.0))
    add("slider_bad_index", SliderSpec(0, 9))
    add("slider_same_index", SliderSpec(1, 1))
    add("rating_without_names", SliderSpec(2, 6), ms=metas + [meta(id="c8", name="x", type="rating")])
    numbers = [0, 1, 20, 0.1, 0.30000000000000004, 1.19, 12.5, 150, 1e-5, 1234567.89, -3, 0.05, 2.675]
    return {"cases": cases, "formatNumber": [{"x": x, "text": qt.format_number(x)} for x in numbers],
            "nouns": {c: qt.noun_for_category(c) for c in ("purchase", "career", "activity", "study", "living", "etc", "zzz")},
            "fit": crit_json(fit)}


# ---------- 10. 응답 조각 조립 (7.3 31·37·39~45번) ----------
def analysis_helper_cases() -> dict[str, Any]:
    from app.services import analysis_helpers as ah
    from tests.engine.example_data import CRIT, build_example  # type: ignore[import-not-found]

    scales, V = build_example()
    raw = np.array([[189, 1.23, 4], [165, 1.19, 3], [150, 1.45, 4]], dtype=float)
    cids, cnames = ["c1", "c2", "c3"], ["가격", "무게", "성능"]
    oids, onames = ["o1", "o2", "o3"], ["갤럭시북", "그램", "요가"]
    units, levels = ["만 원", "kg", ""], [[], [], ["낮음", "보통", "높음", "매우 높음", "최고"]]
    rng = np.random.default_rng(5)
    w_prior = rng.dirichlet(np.ones(3), size=300)
    scen = ScenarioSpec(0, 1, (0.5, -0.5, 0.0), 20.0, 0.1)
    runs = [
        ("two_sliders", [po.Answer(SliderSpec(0, 2), -2), po.Answer(SliderSpec(2, 1), 1)]),
        ("mixed", [po.Answer(SliderSpec(0, 2), -2), po.Answer(SliderSpec(2, 1), 1), po.Answer(scen, "B"),
                   po.Answer(SliderSpec(1, 0), None)]),
    ]
    out = []
    for name, answers in runs:
        post = po.fit(42, 3, answers, n=300)
        s = po.summary(post, V)
        st = da.stability(post, V, seed=1)
        cc = da.change_conditions(s.mean, V)
        vc = da.value_conditions(s.mean, V, CRIT, scales, raw)
        top = da.ranking(s.mean, V)[0]
        out.append({
            "name": name, "W": mat(post.W), "p": [float(x) for x in post.p], "V": mat(V), "wPrior": mat(w_prior),
            "answers": [{"spec": spec_json(a.spec), "response": a.response} for a in answers],
            "raw": mat(raw), "criteria": [criterion_json(c) for c in CRIT], "scales": scales_json(scales),
            "ids": {"criterionIds": cids, "criterionNames": cnames, "optionIds": oids, "optionNames": onames,
                    "units": units, "levels": levels},
            "estimate": ah.estimate_payload(s, V, cids),
            "weightsItems": ah.weights_items(s, cids, cnames, answers, w_prior),
            "rankingsItems": ah.rankings_items(s.mean, V, s.p_best, oids, onames),
            "stability": ah.stability_payload(st, s.p_best, cc, oids, onames, cnames),
            "changeConditions": ah.change_conditions_payload(cc, top, cids, cnames, oids, onames, vc, units, levels),
            "topProbability": ah.top_probability(s, V),
        })
    hid = hidden_cases()
    mism = hp.detect(np.array(hid["W"]), [po.Answer(  # 같은 입력으로 다시 계산
        __import__("app.engine.likelihood", fromlist=["x"]).SliderSpec(a["spec"]["a"], a["spec"]["b"]), a["response"])
        if a["spec"]["type"] == "slider" else po.Answer(
        ScenarioSpec(a["spec"]["a"], a["spec"]["b"], tuple(a["spec"]["delta"]), a["spec"]["rawA"], a["spec"]["rawB"]),
        a["response"]) for a in hid["answers"]])
    particles = ["가격", "무게", "갤럭시북", "서울", "A", "L", "R", "7", "가격(만 원)", "그램", "N3", ""]
    return {
        "cases": out,
        "hidden": {"mismatches": [{"statedOver": m.stated_over, "revealedOver": m.revealed_over,
                                   "statedP": m.stated_p, "revealedP": m.revealed_p, "nScenarios": m.n_scenarios}
                                  for m in mism],
                   "payload": ah.hidden_preferences_payload(mism, cids, cnames)},
        "expectedRange": [{"kind": k, "K": K, "answered": a, "limit": lim, "result": ah.expected_range(k, K, a, lim)}
                          for k, K, a, lim in (("initial", 3, 0, 6), ("initial", 8, 2, 12), ("extra", 3, 6, 9))],
        "particles": [{"word": w, "iGa": ah.i_ga(w), "eulReul": ah.eul_reul(w), "euRo": ah.eu_ro(w)} for w in particles],
        "lowConfidence": [{"p": x, "result": ah.low_confidence(x)} for x in (0.2, 0.549, 0.55, 0.9)],
    }


def main() -> None:
    out_dir = Path(sys.argv[1] if len(sys.argv) > 1 else "golden-out")
    out_dir.mkdir(parents=True, exist_ok=True)
    files = {
        "normalize": normalize_cases(), "likelihood": likelihood_cases(), "selector": selector_cases(),
        "analysis": analysis_cases(), "stopping": stopping_cases(), "hidden": hidden_cases(),
        "posterior": posterior_stats(), "staterules": state_rules_cases(), "questiontext": question_text_cases(), "analysishelpers": analysis_helper_cases(),
    }
    for name, data in files.items():
        (out_dir / f"{name}.json").write_text(json.dumps(data, ensure_ascii=False, allow_nan=False), encoding="utf-8")
        print(f"wrote {name}.json")
    assert math.isfinite(1.0)


if __name__ == "__main__":
    main()

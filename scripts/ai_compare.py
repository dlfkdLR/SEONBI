#!/usr/bin/env python3
"""범용 AI 비교 실험 (개발기획서 1.5, 14장 · 개선사항 4).

두 가지를 잰다.
  1) 일관성+정확도: 같은 가상 사용자의 같은 슬라이더 답변 6개를 GPT·Claude에 새 대화로 5번씩 보여 주고 가중치를 추정하게 한다.
     선비 엔진은 같은 답변에서 입자 시드만 바꿔 5번 추정한다. 정답 가중치가 있어서 오차(MAE)와 1위 적중도 잰다.
  2) 전환점 정확도: 같은 값 표와 가중치를 주고 "몇 %가 되면 1위가 바뀌나?"를 묻는다. 정답은 닫힌 해(검증은 이진 탐색으로도 한다).

사용법 (저장소 루트에서):
  cd backend && ./gradlew aiCompareFixture -q > /tmp/fixture.json      # 선비 쪽 입력·결과
  python3 scripts/ai_compare.py run  --fixture /tmp/fixture.json --out docs/실험/범용AI-비교-원본
  python3 scripts/ai_compare.py report --fixture /tmp/fixture.json --out docs/실험/범용AI-비교-원본 > docs/실험/범용AI-비교-결과.md

모델 호출은 로컬에 로그인된 CLI를 쓴다(claude -p, codex exec). 합성 데이터만 보내며 사용자 정보는 보내지 않는다.
도구·코드 실행은 쓰지 않게 막거나(claude --tools "") 쓰지 말라고 지시한다. 그래서 "코드를 실행하게 하면 그 코드가 곧 선비 엔진"(1.5) 경우는 재지 않는다.
"""
import argparse, json, os, random, re, statistics, subprocess, sys, tempfile, concurrent.futures as cf

MODELS = {
    "claude": {"label": "Claude (claude-sonnet-5-5)", "cmd": "claude"},
    "gpt": {"label": "GPT (gpt-5.6-sol, 추론 medium)", "cmd": "codex"},
}
OPTION_NAMES = ["갤럭시북", "그램", "요가", "맥북"]
OPTION_KEYS = ["galaxy", "gram", "yoga", "macbook"]
CRITERIA = ["가격(낮을수록 좋음)", "무게(낮을수록 좋음)", "배터리(길수록 좋음)", "저장공간(클수록 좋음)", "성능(높을수록 좋음)"]
REPS_1, REPS_2 = 5, 3


# ── 프롬프트 ────────────────────────────────────────────────

def prompt_weights(user, matrix):
    lines = []
    for a in user["answers"]:
        v = a["value"]
        lines.append(f"- [{CRITERIA[a['a']].split('(')[0]}] vs [{CRITERIA[a['b']].split('(')[0]}] : {v:+d}")
    rows = "\n".join(f"- {OPTION_NAMES[i]}: " + ", ".join(f"{CRITERIA[k].split('(')[0]} {matrix[i][k]:.2f}" for k in range(5)) for i in range(4))
    return f"""노트북 4개를 비교하려는 사용자의 선호를 추정해 주세요. 계산 도구나 코드 실행은 쓰지 말고 직접 추정하세요.

평가 기준 5개: {', '.join(c.split('(')[0] for c in CRITERIA)}

사용자는 기준 두 개를 보고 "어느 쪽이 얼마나 더 중요한가"를 -5~+5로 답했습니다. +이면 앞 기준이 더 중요하고, -이면 뒤 기준이 더 중요하며, 0이면 같습니다.
슬라이더 한 칸은 중요도 비율로 약 e^0.3(약 1.35배) 차이를 뜻하고, 답에는 약간의 흔들림이 있습니다(로그 비율 표준편차 약 0.6).
사용자의 답변:
{chr(10).join(lines)}

선택지의 정규화 점수(0~1, 높을수록 좋음, 이미 방향이 반영됨):
{rows}

요청: 5개 기준의 중요도 가중치(합계 100%)를 추정하고, 가중합 점수(가중치×점수의 합)가 가장 높은 선택지를 고르세요.
설명 없이 아래 JSON 한 줄만 출력하세요.
{{"weights_percent": [가격, 무게, 배터리, 저장공간, 성능], "top_option": "갤럭시북|그램|요가|맥북 중 하나"}}"""


def prompt_flip(p):
    rows = "\n".join(f"- {n}: " + ", ".join(f"{c} {p['v'][i][k]:.2f}" for k, c in enumerate(p['criteria'])) for i, n in enumerate(p['options']))
    w = ", ".join(f"{c} {p['w'][k]*100:.1f}%" for k, c in enumerate(p['criteria']))
    top = p['options'][p['top']]
    target = p['criteria'][p['k']]
    return f"""의사결정 점수 계산 문제입니다. 계산 도구나 코드 실행은 쓰지 말고 직접 계산하세요.

선택지별 점수표(0~1, 높을수록 좋음):
{rows}

현재 가중치: {w} (합계 100%)
점수 = Σ(가중치 × 기준 점수). 현재 1위는 {top}입니다.

질문: "{target}"의 가중치만 바꾸고 나머지 기준들은 원래 비율을 그대로 유지하며 합계를 100%로 맞춥니다.
"{target}"의 가중치를 현재({p['w'][p['k']]*100:.1f}%)에서 올리거나 내릴 때, 1위가 처음으로 바뀌는 가장 가까운 지점은 몇 %입니까? (올리는 쪽과 내리는 쪽 중 현재 가중치에 더 가까운 지점. 거리가 같으면 올리는 쪽.)
어떻게 바꿔도 1위가 바뀌지 않으면 null입니다.
설명 없이 아래 JSON 한 줄만 출력하세요.
{{"threshold_percent": 숫자 또는 null, "new_top": "새 1위 선택지 이름 또는 null"}}"""


# ── 정답(전환점 닫힌 해) ────────────────────────────────────

def scores(w, v):
    return [sum(w[k] * row[k] for k in range(len(w))) for row in v]

def top_of(w, v):
    s = scores(w, v)
    return max(range(len(s)), key=lambda i: (s[i], -i))

def with_weight(w, k, t):
    rest = 1 - w[k]
    return [t if j == k else w[j] * (1 - t) / rest for j in range(len(w))]

def flip_truth(w, v, k):
    """닫힌 해: 두 선택지 점수가 같아지는 t를 모아 현재에서 가까운 순으로 1위가 바뀌는 첫 지점."""
    top = top_of(w, v)
    u = scores(w, v)
    r = [(u[i] - w[k] * v[i][k]) / (1 - w[k]) for i in range(len(v))]
    cross = set()
    for i in range(len(v)):
        for j in range(i + 1, len(v)):
            den = (v[i][k] - r[i]) - (v[j][k] - r[j])
            if abs(den) < 1e-12: continue
            t = (r[j] - r[i]) / den
            if 0 < t < 1 and abs(t - w[k]) > 1e-12: cross.add(t)
    best = [None, None]  # [올리는 쪽, 내리는 쪽]
    for side, up in enumerate((True, False)):
        ahead = sorted((t for t in cross if (t > w[k] if up else t < w[k])), reverse=not up)
        for m, here in enumerate(ahead):
            nxt = ahead[m + 1] if m + 1 < len(ahead) else (1.0 if up else 0.0)
            leader = top_of(with_weight(w, k, (here + nxt) / 2), v)
            if leader != top:
                best[side] = (here, leader); break
    if best[0] is None and best[1] is None: return None
    if best[0] is None: side = 1
    elif best[1] is None: side = 0
    else: side = 1 if abs(best[1][0] - w[k]) < abs(best[0][0] - w[k]) else 0
    return best[side]

def flip_truth_bisect(w, v, k, steps=200000):
    """검증용: 0~1을 촘촘히 훑어 현재에서 가까운 1위 변경 지점을 찾는다(닫힌 해와 독립)."""
    top = top_of(w, v)
    for d in range(1, steps):
        for sign in (+1, -1):  # 거리가 같으면 올리는 쪽
            t = w[k] + sign * d / steps
            if 0 < t < 1 and top_of(with_weight(w, k, t), v) != top:
                return t
    return None

def make_flip_problems(n=4, seed=11):
    rng = random.Random(seed)
    out = []
    while len(out) < n:
        v = [[round(rng.random(), 2) for _ in range(3)] for _ in range(3)]
        raw = [rng.random() + 0.3 for _ in range(3)]
        w = [round(x / sum(raw), 3) for x in raw]
        w[2] = round(1 - w[0] - w[1], 3)
        k = len(out) % 3  # 기준 세 개를 돌아가며 묻는다
        truth = flip_truth(w, v, k)
        if truth is None or abs(truth[0] - w[k]) < 0.03: continue
        chk = flip_truth_bisect(w, v, k, 20000)
        assert chk is not None and abs(chk - truth[0]) < 2e-4, (chk, truth)
        out.append({"id": f"f{len(out)+1}", "v": v, "w": w, "k": k, "top": top_of(w, v),
                    "options": ["A안", "B안", "C안"], "criteria": ["가격", "성능", "무게"],
                    "truth_t": truth[0], "truth_top": truth[1]})
    return out


# ── 모델 호출 ───────────────────────────────────────────────

def call(model, prompt, workdir):
    if model == "claude":
        cmd = ["claude", "-p", "--tools", "", "--no-session-persistence", "--disable-slash-commands", "--setting-sources", "project", "--output-format", "json"]
        r = subprocess.run(cmd, input=prompt, capture_output=True, text=True, cwd=workdir, timeout=600)
        data = json.loads(r.stdout)
        return data.get("result", ""), {"model": list(data.get("modelUsage", {}).keys()), "cost": data.get("total_cost_usd")}
    out = os.path.join(workdir, "last.txt")
    if os.path.exists(out): os.remove(out)
    cmd = ["codex", "exec", "-m", "gpt-5.6-sol", "-c", "model_reasoning_effort=medium", "-C", workdir, "--skip-git-repo-check", "--ephemeral", "-s", "read-only", "-o", out, "-"]
    r = subprocess.run(cmd, input=prompt, capture_output=True, text=True, cwd=workdir, timeout=900)
    return (open(out).read() if os.path.exists(out) else ""), {"model": ["gpt-5.6-sol"], "stderr_tail": r.stderr[-200:]}

def parse_json(text):
    m = re.findall(r"\{.*?\}", text, re.S)
    for chunk in reversed(m):
        try: return json.loads(chunk)
        except Exception: continue
    return None

def one_job(job, outdir):
    path = os.path.join(outdir, job["file"])
    if os.path.exists(path): return
    work = tempfile.mkdtemp(prefix="aicmp-")  # 빈 작업 폴더에서 돌려 저장소 내용을 보지 못하게 한다
    text, meta = "", {}
    for attempt in range(3):
        try:
            text, meta = call(job["model"], job["prompt"], work)
            if parse_json(text) is not None: break
        except Exception as e:
            meta = {"error": str(e)[:200]}
    json.dump({"job": {k: v for k, v in job.items() if k != "prompt"}, "prompt": job["prompt"], "raw": text, "parsed": parse_json(text), "meta": meta},
              open(path, "w"), ensure_ascii=False, indent=1)

def cmd_run(a):
    fx = json.load(open(a.fixture)); os.makedirs(a.out, exist_ok=True)
    jobs = []
    for m in MODELS:
        for ui, u in enumerate(fx["users"]):
            for r in range(REPS_1):
                jobs.append({"exp": 1, "model": m, "user": ui, "rep": r, "file": f"e1-{m}-u{ui}-r{r}.json", "prompt": prompt_weights(u, fx["normalizedMatrix"])})
        for p in make_flip_problems():
            for r in range(REPS_2):
                jobs.append({"exp": 2, "model": m, "problem": p["id"], "rep": r, "file": f"e2-{m}-{p['id']}-r{r}.json", "prompt": prompt_flip(p)})
    with cf.ThreadPoolExecutor(max_workers=a.workers) as ex:
        list(ex.map(lambda j: one_job(j, a.out), jobs))
    print(f"{len(jobs)}개 호출 완료", file=sys.stderr)


# ── 보고 ────────────────────────────────────────────────────

def load(out, name):
    p = os.path.join(out, name)
    return json.load(open(p)) if os.path.exists(p) else None

def sd(xs): return statistics.pstdev(xs) if len(xs) > 1 else 0.0

def report(a):
    fx = json.load(open(a.fixture)); v = fx["normalizedMatrix"]
    L = []
    L.append("# 범용 AI 비교 실험 결과 (개발기획서 1.5·14장, 개선사항 4)\n")
    L.append("`scripts/ai_compare.py`로 측정한 값이다. 원본 응답은 `docs/실험/범용AI-비교-원본/`에 있다. 모두 합성 데이터이며 사용자 정보는 쓰지 않았다.\n")
    L.append("## 실험 조건\n")
    L.append(f"- 모델: {MODELS['claude']['label']}, {MODELS['gpt']['label']}. 도구·코드 실행 없이 답하게 했다(Claude는 도구 목록을 비웠고, GPT는 읽기 전용 샌드박스에서 코드를 쓰지 말라고 지시).")
    L.append("- 실험 1은 같은 질문을 **새 대화로 5회**, 실험 2는 **3회** 반복했다. 선비 엔진은 입자 난수 시드만 바꿔 5회.")
    L.append("- 모델은 매번 새 세션(이전 대화 없음)이다.\n")

    # 실험 1
    L.append("## 실험 1. 일관성과 정확도 — 같은 답변에서 가중치 추정\n")
    L.append("가상 사용자 4명이 같은 슬라이더 질문 6개에 답했다(정답 가중치를 알고 있다). 각 시스템이 5개 기준 가중치(%)를 추정했다.\n")
    L.append("| 사용자 | 시스템 | 응답 수 | 가중치 표준편차(%p, 기준 평균) | 최대 범위(%p) | 정답 대비 MAE(%p) | 1위 적중(정답 1위 기준) | 5회 1위 일치 |")
    L.append("|---|---|---|---|---|---|---|---|")
    agg = {}
    for ui, u in enumerate(fx["users"]):
        truth = [x * 100 for x in u["trueWeights"]]
        systems = {"선비 엔진": [([x * 100 for x in r["weights"]], r["top"]) for r in u["seonbi"]]}
        for m in MODELS:
            runs = []
            for r in range(REPS_1):
                d = load(a.out, f"e1-{m}-u{ui}-r{r}.json")
                pj = d and d["parsed"]
                if pj and isinstance(pj.get("weights_percent"), list) and len(pj["weights_percent"]) == 5:
                    w = [float(x) for x in pj["weights_percent"]]; s = sum(w) or 1
                    w = [x * 100 / s for x in w]
                    runs.append((w, None))
            systems[MODELS[m]["label"].split(" ")[0]] = runs
        for name, runs in systems.items():
            if not runs: continue
            ws = [r[0] for r in runs]
            sds = [sd([w[k] for w in ws]) for k in range(5)]
            rng = [max(w[k] for w in ws) - min(w[k] for w in ws) for k in range(5)]
            mae = statistics.mean(sum(abs(w[k] - truth[k]) for k in range(5)) / 5 for w in ws)
            tops = [top_of([x / 100 for x in w], v) for w in ws]
            hit = sum(t == u["trueTop"] for t in tops) / len(tops)
            same = max(tops.count(t) for t in set(tops)) / len(tops)
            L.append(f"| {ui+1} | {name} | {len(ws)} | {statistics.mean(sds):.2f} | {max(rng):.1f} | {mae:.2f} | {hit*100:.0f}% | {same*100:.0f}% |")
            g = agg.setdefault(name, {"sd": [], "mae": [], "hit": [], "same": [], "n": 0})
            g["sd"].append(statistics.mean(sds)); g["mae"].append(mae); g["hit"].append(hit); g["same"].append(same); g["n"] += len(ws)
    L.append("\n**4명 평균**\n")
    L.append("| 시스템 | 응답 수 | 가중치 표준편차(%p) | 정답 대비 MAE(%p) | 1위 적중 | 5회 1위 일치 |")
    L.append("|---|---|---|---|---|---|")
    for name, g in agg.items():
        L.append(f"| {name} | {g['n']} | {statistics.mean(g['sd']):.2f} | {statistics.mean(g['mae']):.2f} | {statistics.mean(g['hit'])*100:.0f}% | {statistics.mean(g['same'])*100:.0f}% |")
    L.append("\n- 1위 적중과 5회 1위 일치는 **모델이 말한 추천이 아니라, 추정한 가중치로 같은 점수표에서 계산한 1위** 기준이다(모델이 점수 계산에서 틀리는 경우를 따로 보지 않으려고). 모델이 직접 말한 추천은 원본 파일에 있다.")
    stated = {}
    for m in MODELS:
        ok = tot = 0
        for ui, u in enumerate(fx["users"]):
            for r in range(REPS_1):
                d = load(a.out, f"e1-{m}-u{ui}-r{r}.json"); pj = d and d["parsed"]
                if pj and isinstance(pj.get("weights_percent"), list) and len(pj["weights_percent"]) == 5:
                    w = [float(x) / (sum(map(float, pj["weights_percent"])) or 1) for x in pj["weights_percent"]]
                    tot += 1; ok += (OPTION_NAMES[top_of(w, v)] == str(pj.get("top_option")).strip())
        stated[m] = (ok, tot)
    for m, (ok, tot) in stated.items():
        L.append(f"- {MODELS[m]['label']}가 자기 가중치로 직접 말한 추천 1위와 그 가중치로 계산한 1위가 일치한 비율: {ok}/{tot}")
    L.append("")

    # 실험 2
    L.append("## 실험 2. 전환점 정확도 — \"몇 %가 되면 1위가 바뀌나?\"\n")
    L.append("선택지 3개·기준 3개의 점수표와 가중치를 주고, 한 기준의 가중치를 움직일 때 1위가 처음 바뀌는 지점(%)을 물었다. 정답은 닫힌 해이며 0.02%p 이내로 촘촘한 탐색과 일치함을 확인했다. 허용 오차 1%p 안이면 정답으로 센다.\n")
    probs = make_flip_problems()
    L.append("| 문제 | 정답 전환점(%) | 새 1위 | 시스템 | 응답(%) | 오차(%p) | 새 1위 일치 |")
    L.append("|---|---|---|---|---|---|---|")
    stats = {}
    for p in probs:
        tt = p["truth_t"] * 100; tn = p["options"][p["truth_top"]]
        L.append(f"| {p['id']} ({p['criteria'][p['k']]} {p['w'][p['k']]*100:.1f}%) | {tt:.2f} | {tn} | 선비(닫힌 해) | {tt:.2f} | 0.00 | 일치 |")
        for m in MODELS:
            for r in range(REPS_2):
                d = load(a.out, f"e2-{m}-{p['id']}-r{r}.json"); pj = d and d["parsed"]
                name = MODELS[m]["label"].split(" ")[0]
                s = stats.setdefault(name, {"err": [], "ok": 0, "n": 0, "top": 0})
                s["n"] += 1
                try:
                    pt = pj.get("threshold_percent"); pn = pj.get("new_top")
                    if pt is None: raise ValueError("null")
                    e = abs(float(pt) - tt); topok = str(pn).strip() == tn
                    s["err"].append(e); s["ok"] += e <= 1.0; s["top"] += topok
                    L.append(f"| | | | {name} #{r+1} | {float(pt):.2f} | {e:.2f} | {'일치' if topok else '불일치'} |")
                except Exception:
                    L.append(f"| | | | {name} #{r+1} | {'없음' if not pj else pj.get('threshold_percent')} | - | - |")
    L.append("\n**요약**\n")
    L.append("| 시스템 | 응답 수 | 평균 오차(%p) | 최대 오차(%p) | 1%p 이내 정답 | 새 1위 일치 |")
    L.append("|---|---|---|---|---|---|")
    L.append(f"| 선비(닫힌 해) | {len(probs)} | 0.00 | 0.00 | 100% | 100% |")
    for name, s in stats.items():
        ee = s["err"]
        L.append(f"| {name} | {s['n']} | {statistics.mean(ee):.2f} | {max(ee):.2f} | {s['ok']}/{s['n']} | {s['top']}/{s['n']} |" if ee else f"| {name} | {s['n']} | - | - | 0/{s['n']} | 0/{s['n']} |")
    # 응답 간 일관성
    L.append("\n**같은 문제를 3번 물었을 때의 응답 흩어짐(전환점 %의 표준편차, 문제 평균)**\n")
    L.append("| 시스템 | 표준편차(%p) |\n|---|---|")
    L.append("| 선비(닫힌 해) | 0.00 |")
    for m in MODELS:
        sds = []
        for p in probs:
            xs = []
            for r in range(REPS_2):
                d = load(a.out, f"e2-{m}-{p['id']}-r{r}.json"); pj = d and d["parsed"]
                try: xs.append(float(pj["threshold_percent"]))
                except Exception: pass
            if len(xs) > 1: sds.append(sd(xs))
        L.append(f"| {MODELS[m]['label'].split(' ')[0]} | {statistics.mean(sds):.2f} |" if sds else f"| {m} | - |")
    print("\n".join(L))

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); sub = ap.add_subparsers(dest="cmd", required=True)
    for n in ("run", "report"):
        s = sub.add_parser(n); s.add_argument("--fixture", required=True); s.add_argument("--out", required=True)
        if n == "run": s.add_argument("--workers", type=int, default=6)
    a = ap.parse_args()
    cmd_run(a) if a.cmd == "run" else report(a)

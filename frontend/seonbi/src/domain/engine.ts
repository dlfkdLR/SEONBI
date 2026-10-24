import type { Analysis, Answer, Condition, Criterion, Decision, Exclusion, Question, Ranking } from './types';
import { maxQuestions } from './types';

const clamp = (x: number, min = 0, max = 1) => Math.max(min, Math.min(max, x));
const sigmoid = (x: number) => 1 / (1 + Math.exp(-x));
const sum = (xs: number[]) => xs.reduce((a, b) => a + b, 0);
const entropy = (p: number[]) => -sum(p.filter(x => x > 0).map(x => x * Math.log(x)));
export function random(seed: number) {
  let a = seed >>> 0;
  return () => { a += 0x6D2B79F5; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}

export function exclusionReasons(d: Decision): Exclusion[] {
  return d.options.flatMap(o => {
    const reasons = d.conditions.flatMap(c => {
      if (c.kind === 'required_tag') return c.satisfiedBy.includes(o.id) ? [] : [`${c.label} 미충족`];
      const v = d.values[o.id]?.[c.criterionId];
      const criterion = d.criteria.find(k => k.id === c.criterionId);
      if (!criterion) return [];
      const pass = typeof v === 'number' && (c.operator === '<=' ? v <= c.value : c.operator === '>=' ? v >= c.value : v === c.value);
      return pass ? [] : [`${criterion.name} ${c.operator} ${c.value}${criterion.unit} 조건 미충족`];
    });
    return reasons.length ? [{ optionId: o.id, reasons }] : [];
  });
}

export function validationErrors(d: Decision, step = 4): string[] {
  const errors: string[] = [];
  if (!d.title.trim()) errors.push('결정 제목을 입력해주세요.');
  if (d.options.length < 2) errors.push('선택지는 2개 이상 필요해요.');
  if (d.options.length > 8) errors.push('선택지는 8개까지 등록할 수 있어요.');
  if (d.options.some(o => !o.name.trim())) errors.push('모든 선택지의 이름을 입력해주세요.');
  if (new Set(d.options.map(o => o.name.trim())).size !== d.options.length) errors.push('선택지 이름은 서로 달라야 해요.');
  if (step >= 2) {
    if (d.criteria.length < 2 || d.criteria.length > 7) errors.push('평가 기준은 2~7개가 필요해요.');
    if (d.criteria.some(c => !c.name.trim())) errors.push('평가 기준 이름을 입력해주세요.');
    if (new Set(d.criteria.map(c => c.name.trim())).size !== d.criteria.length) errors.push('기준 이름은 서로 달라야 해요.');
  }
  if (step >= 3) for (const o of d.options) for (const c of d.criteria) {
    const v = d.values[o.id]?.[c.id];
    const valid = c.type === 'boolean' ? typeof v === 'boolean' : typeof v === 'number' && Number.isFinite(v)
      && (c.type !== 'rating' || (Number.isInteger(v) && v >= 1 && v <= (c.inputMode === 'rank' ? d.options.length : c.levels.length)));
    if (!valid) errors.push(`${o.name}의 ${c.name} 값을 입력해주세요.`);
  }
  if (step >= 4 && d.options.length - exclusionReasons(d).length < 2) errors.push('조건을 충족하는 선택지가 2개 이상 필요해요. 필수 조건을 조정해주세요.');
  return errors;
}

export function freezeScales(d: Decision): Decision {
  const excluded = new Set(exclusionReasons(d).map(x => x.optionId));
  return { ...d, criteria: d.criteria.map(c => {
    if (c.type !== 'numeric' || c.scaleMin !== undefined) return c;
    const xs = d.options.filter(o => !excluded.has(o.id)).map(o => Number(d.values[o.id]?.[c.id]));
    return { ...c, scaleMin: Math.min(...xs), scaleMax: Math.max(...xs) };
  }) };
}

export function normalized(d: Decision, optionId: string, c: Criterion): number {
  const raw = d.values[optionId]?.[c.id];
  if (raw === null || raw === undefined) return .5;
  if (c.type === 'boolean') return raw ? 1 : 0;
  if (c.type === 'rating' && c.inputMode === 'rank') {
    const groups = [...new Set(d.options.map(o => Number(d.values[o.id]?.[c.id])))].sort((a, b) => a - b);
    return groups.length === 1 ? .5 : (groups.length - 1 - groups.indexOf(Number(raw))) / (groups.length - 1);
  }
  let v: number;
  if (c.type === 'rating') v = (Number(raw) - 1) / (c.levels.length - 1);
  else {
    const xs = d.options.map(o => Number(d.values[o.id]?.[c.id]));
    const lo = c.scaleMin ?? Math.min(...xs), hi = c.scaleMax ?? Math.max(...xs);
    if (lo === hi) return .5;
    v = clamp((Number(raw) - lo) / (hi - lo));
  }
  return c.direction === 'lower_better' ? 1 - v : v;
}

function responseProb(q: Question, w: number[], d: Decision): number[] {
  const ai = d.criteria.findIndex(c => c.id === q.criteria[0]), bi = d.criteria.findIndex(c => c.id === q.criteria[1]);
  const a = w[ai] ?? 0, b = w[bi] ?? 0;
  if (q.type === 'slider_pair') {
    const ratio = Math.log((a + 1e-9) / (b + 1e-9));
    const logs = Array.from({ length: 11 }, (_, i) => -((ratio - .3 * (i - 5)) ** 2) / (2 * .6 ** 2));
    const max = Math.max(...logs), ps = logs.map(x => Math.exp(x - max)), z = sum(ps);
    return ps.map(x => x / z);
  }
  const p = q.type === 'ab_scenario' ? sigmoid(8 * (a * (q.delta?.[0] ?? .5) + b * (q.delta?.[1] ?? -.5))) : a / Math.max(a + b, 1e-9);
  return [p, 1 - p];
}

export function posterior(d: Decision, answers = d.answers, count = 4000) {
  const rng = random(d.seed), k = d.criteria.length;
  const particles: number[][] = [], logs: number[] = [];
  const valid = answers.filter(a => a.isValid && !a.response.skip && a.question.criteria.every(id => d.criteria.some(c => c.id === id)));
  for (let n = 0; n < count; n++) {
    const xs = Array.from({ length: k }, () => -Math.log(Math.max(rng(), 1e-12))), total = sum(xs);
    const w = xs.map(x => x / total);
    let log = 0;
    for (const a of valid) {
      const index = a.question.type === 'slider_pair' ? (a.response.value ?? 0) + 5 : a.response.choice === 'A' ? 0 : 1;
      log += Math.log(Math.max(responseProb(a.question, w, d)[index] ?? 0, 1e-300));
    }
    particles.push(w); logs.push(log);
  }
  const max = Math.max(...logs), ps = logs.map(l => Math.exp(l - max)), z = sum(ps);
  const probabilities = ps.map(p => p / z), means = Array(k).fill(0) as number[];
  particles.forEach((w, i) => w.forEach((v, j) => { means[j] += probabilities[i] * v; }));
  return { particles, probabilities, means, ess: 1 / sum(probabilities.map(p => p * p)) };
}

function matrix(d: Decision) {
  const exclusions = exclusionReasons(d), ids = new Set(exclusions.map(x => x.optionId));
  const options = d.options.filter(o => !ids.has(o.id));
  return { options, rows: options.map(o => d.criteria.map(c => normalized(d, o.id, c))), exclusions };
}
const dot = (a: number[], b: number[]) => sum(a.map((v, i) => v * (b[i] ?? 0)));
function winners(scores: number[]) {
  const top = Math.max(...scores);
  return scores.map((s, i) => Math.abs(s - top) < 1e-9 ? i : -1).filter(i => i >= 0);
}

export function ranking(d: Decision, weights: number[]): Ranking[] {
  const { options, rows } = matrix(d), total = sum(weights);
  const w = total > 0 ? weights.map(x => x / total) : weights.map(() => 1 / weights.length);
  return options.map((o, i) => ({ optionId: o.id, name: o.name, score: dot(rows[i], w), probability: 0, rank: 0 }))
    .sort((a, b) => b.score - a.score).map((x, i, xs) => ({ ...x, rank: i && Math.abs(x.score - xs[i - 1].score) < 1e-9 ? xs.slice(0, i).findIndex(p => Math.abs(p.score - x.score) < 1e-9) + 1 : i + 1 }));
}

export function redistribute(weights: number[], index: number, value: number): number[] {
  const t = clamp(value), rest = sum(weights.filter((_, i) => i !== index));
  return weights.map((v, i) => i === index ? t : (1 - t) * (rest > 1e-12 ? v / rest : 1 / (weights.length - 1)));
}

export function changeConditions(d: Decision, weights: number[]) {
  const { rows, options } = matrix(d), top = ranking(d, weights)[0];
  if (!top || options.length < 2) return [];
  const topIndex = options.findIndex(o => o.id === top.optionId), changes: Analysis['changes'] = [];
  for (let k = 0; k < weights.length; k++) {
    const atZero = redistribute(weights, k, 0), rest = rows.map(r => dot(r, atZero));
    const candidates: { t: number; newTop: string }[] = [];
    for (let j = 0; j < rows.length; j++) {
      if (j === topIndex) continue;
      const denom = (rows[topIndex][k] - rows[j][k]) - (rest[topIndex] - rest[j]);
      if (Math.abs(denom) < 1e-10) continue;
      const t = (rest[j] - rest[topIndex]) / denom;
      if (t < 0 || t > 1 || Math.abs(t - weights[k]) < 1e-8) continue;
      const score = rest[topIndex] * (1 - t) + rows[topIndex][k] * t;
      if (rows.some((r, i) => rest[i] * (1 - t) + r[k] * t > score + 1e-7)) continue;
      const beyond = clamp(t + (t > weights[k] ? 1e-6 : -1e-6));
      const next = ranking(d, redistribute(weights, k, beyond))[0];
      if (next?.optionId !== top.optionId) candidates.push({ t, newTop: next.optionId });
    }
    candidates.sort((a, b) => Math.abs(a.t - weights[k]) - Math.abs(b.t - weights[k]));
    if (candidates[0]) changes.push({ criterionId: d.criteria[k].id, threshold: candidates[0].t, newTop: candidates[0].newTop, direction: candidates[0].t > weights[k] ? 'increase' : 'decrease' });
  }
  return changes.sort((a, b) => Math.abs(a.threshold - weights[d.criteria.findIndex(c => c.id === a.criterionId)]) - Math.abs(b.threshold - weights[d.criteria.findIndex(c => c.id === b.criterionId)]));
}

export function analyze(d: Decision): Analysis {
  const p = posterior(d), { options, rows, exclusions } = matrix(d);
  const best = Array(options.length).fill(0) as number[];
  p.particles.forEach((w, n) => { const win = winners(rows.map(r => dot(r, w))); for (const i of win) best[i] += p.probabilities[n] / win.length; });
  const weights = d.criteria.map((c, k) => {
    const sorted = p.particles.map((w, n) => ({ v: w[k], p: p.probabilities[n] })).sort((a, b) => a.v - b.v);
    const quantile = (q: number) => { let t = 0; for (const x of sorted) { t += x.p; if (t >= q) return x.v; } return 1; };
    return { criterionId: c.id, mean: p.means[k], interval: [quantile(.1), quantile(.9)] as [number, number] };
  });
  const ranks = ranking(d, p.means).map(r => ({ ...r, probability: best[options.findIndex(o => o.id === r.optionId)] ?? 0 }));
  const rng = random(d.seed + 89), top = ranks[0]?.optionId;
  let kept = 0;
  for (let i = 0; i < 200; i++) {
    const perturb = p.means.map(w => Math.max(.0001, w + (rng() - .5) * .1));
    if (ranking(d, perturb)[0]?.optionId === top) kept++;
  }
  const probability = ranks[0]?.probability ?? 0;
  return { weights, rankings: ranks, excluded: exclusions, stability: probability >= .85 && kept / 200 >= .9 ? '높음' : probability >= .65 ? '보통' : '낮음', keepRate: kept / 200, changes: changeConditions(d, p.means), entropy: entropy(best), ess: p.ess };
}

const pairKey = (q: Question) => JSON.stringify([...q.criteria].sort());

export function hasAskedQuestion(question: Question, answers: Answer[]): boolean {
  // A/B and the slider both ask for the same stated preference. Switching the
  // presentation is not a new question. The one concrete trade-off currently
  // available per pair is a distinct follow-up, but it must not repeat either.
  const scenario = question.type === 'ab_scenario';
  return answers.some(a => a.isValid && pairKey(a.question) === pairKey(question)
    && (a.question.type === 'ab_scenario') === scenario);
}

export function nextQuestion(d: Decision): Question | undefined {
  const p = posterior(d, d.answers, 700), { rows } = matrix(d);
  const varying = d.criteria.map((c, i) => ({ c, i })).filter(({ i }) => new Set(rows.map(r => r[i])).size > 1);
  if (varying.length < 2) return undefined;
  const seen = new Set(d.answers.filter(a => a.isValid && !a.response.skip).flatMap(a => a.question.criteria));
  const available: Question[] = [];
  const n = d.answers.length;
  const preferredType: Question['type'] = ['ab_criteria', 'slider_pair', 'ab_scenario'][n % 3] as Question['type'];
  for (let i = 0; i < varying.length; i++) for (let j = i + 1; j < varying.length; j++) {
    const a = varying[i].c, b = varying[j].c;
    for (const type of ['ab_criteria', 'slider_pair', 'ab_scenario'] as const) {
    if (type === 'ab_scenario' && (a.inputMode === 'rank' || b.inputMode === 'rank' || a.type === 'boolean' || b.type === 'boolean')) continue;
    const q: Question = { id: `q-${n}-${a.id}-${b.id}-${type}`, type, criteria: [a.id, b.id] };
    // Skipped questions also count as presented; invalidated answers do not.
    if (hasAskedQuestion(q, d.answers)) continue;
    if (type === 'ab_scenario') {
      const profile = scenarioValues(d, a, b);
      q.scenario = profile;
      const normRaw = (c: Criterion, x: number) => {
        const v = c.type === 'rating' ? (x - 1) / (c.levels.length - 1) : (x - (c.scaleMin ?? 0)) / ((c.scaleMax ?? 1) - (c.scaleMin ?? 0) || 1);
        return c.direction === 'lower_better' ? 1 - v : v;
      };
      q.delta = [normRaw(a, profile.a[0]) - normRaw(a, profile.b[0]), normRaw(b, profile.a[1]) - normRaw(b, profile.b[1])];
    }
    available.push(q);
    }
  }
  if (!available.length) return undefined;
  let candidates = available.filter(q => q.type === preferredType);
  if (!candidates.length) candidates = available;
  const previous = d.answers.filter(a => a.isValid).at(-1)?.question;
  const otherPairs = previous ? candidates.filter(q => pairKey(q) !== pairKey(previous)) : candidates;
  if (otherPairs.length) candidates = otherPairs;
  const particleWins = p.particles.map(w => winners(rows.map(r => dot(r, w))));
  let selected: Question | undefined, bestGain = -Infinity;
  for (const q of candidates) {
    const distributions = p.particles.map(w => responseProb(q, w, d));
    let expectedEntropy = 0;
    for (let r = 0; r < distributions[0].length; r++) {
      const masses = Array(rows.length).fill(0) as number[];
      for (let z = 0; z < distributions.length; z++) for (const win of particleWins[z]) masses[win] += p.probabilities[z] * distributions[z][r] / particleWins[z].length;
      const total = sum(masses);
      if (total > 0) expectedEntropy += total * entropy(masses.map(x => x / total));
    }
    // Prefer criteria not yet covered, then maximize expected reduction in decision entropy.
    const score = -expectedEntropy + q.criteria.filter(id => !seen.has(id)).length * 10;
    if (score > bestGain) { bestGain = score; selected = q; }
  }
  return selected;
}

export function scenarioValues(d: Decision, a: Criterion, b: Criterion) {
  const raw = (c: Criterion, benefit: number) => {
    const v = c.direction === 'lower_better' ? 1 - benefit : benefit;
    if (c.type === 'rating') return Math.round(1 + v * (c.levels.length - 1));
    const xs = d.options.map(o => Number(d.values[o.id]?.[c.id]));
    const min = c.scaleMin ?? Math.min(...xs), max = c.scaleMax ?? Math.max(...xs), span = max - min;
    const precision = span >= 100 ? 0 : span >= 1 ? 1 : 2;
    return Number((min + v * span).toFixed(precision));
  };
  return { a: [raw(a, .75), raw(b, .25)] as [number, number], b: [raw(a, .25), raw(b, .75)] as [number, number] };
}

export function shouldStop(d: Decision, analysis: Analysis) {
  if (d.answers.length >= (d.questionLimit ?? maxQuestions(d.criteria.length))) return true;
  // Extra rounds retain prior answers and always ask at least one more question.
  if (d.questionLimit !== undefined) return false;
  const covered = new Set(d.answers.filter(a => a.isValid && !a.response.skip).flatMap(a => a.question.criteria));
  return d.answers.length >= 3 && covered.size === d.criteria.length && (analysis.rankings[0]?.probability ?? 0) >= .85;
}

export function criterionRange(d: Decision, c: Criterion): string {
  if (c.type === 'boolean') return '아니요 ~ 예';
  if (c.type === 'rating') return c.inputMode === 'rank' ? '선택지의 상대 순위' : `${c.levels[0]} ~ ${c.levels.at(-1)}`;
  const xs = d.options.map(o => Number(d.values[o.id]?.[c.id]));
  return `${c.scaleMin ?? Math.min(...xs)}~${c.scaleMax ?? Math.max(...xs)}${c.unit}`;
}

export function formatValue(value: number | boolean | null | undefined, c: Criterion): string {
  if (value == null) return '—';
  if (c.type === 'boolean') return value ? '예' : '아니요';
  if (c.type === 'rating') return c.inputMode === 'rank' ? `${value}위` : (c.levels[Number(value) - 1] ?? '—');
  return `${value}${c.unit ? ' ' + c.unit : ''}`;
}

export function hiddenPreferences(d: Decision): string[] {
  const stated = d.answers.filter(a => a.question.type !== 'ab_scenario' && a.isValid && !a.response.skip), revealed = d.answers.filter(a => a.question.type === 'ab_scenario' && a.isValid && !a.response.skip);
  if (!stated.length || !revealed.length) return [];
  const s = posterior(d, stated, 1000), r = posterior(d, revealed, 1000), findings: string[] = [];
  for (let a = 0; a < d.criteria.length; a++) for (let b = a + 1; b < d.criteria.length; b++) {
    const prob = (p: typeof s) => sum(p.particles.map((w, n) => w[a] > w[b] ? p.probabilities[n] : 0));
    const sp = prob(s), rp = prob(r);
    if (sp > .75 && rp < .25) findings.push(`${d.criteria[a].name}을 더 중요하게 답했지만, 상황형 선택에서는 ${d.criteria[b].name}을 더 중시하는 경향이 있어요.`);
    if (sp < .25 && rp > .75) findings.push(`${d.criteria[b].name}을 더 중요하게 답했지만, 상황형 선택에서는 ${d.criteria[a].name}을 더 중시하는 경향이 있어요.`);
  }
  return findings;
}

export function conditionLabel(c: Condition, d: Decision) {
  return c.kind === 'required_tag' ? c.label : `${d.criteria.find(k => k.id === c.criterionId)?.name ?? '기준'} ${c.operator} ${c.value}${d.criteria.find(k => k.id === c.criterionId)?.unit ?? ''}`;
}

export function newAnswer(question: Question, response: Answer['response']): Answer {
  return { id: crypto.randomUUID(), question, response, isValid: true };
}

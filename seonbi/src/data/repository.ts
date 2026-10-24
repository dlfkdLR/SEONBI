import { api, ApiError } from '../api/http';
import type { DecisionDto, DecisionList, ValuesRequest, CriterionDto } from '../api/contracts';
import type { Decision, Criterion, Condition } from '../domain/types';
import { defaultLevels } from '../domain/types';

export function fromDto(d: DecisionDto): Decision {
  if (!d || typeof d.id !== 'string' || !Array.isArray(d.options) || !Array.isArray(d.criteria) || !Array.isArray(d.values))
    throw new ApiError(0, 'INVALID_RESPONSE', '서버의 결정 응답 형식을 확인해주세요.');
  const values: Decision['values'] = Object.fromEntries(d.options.map(o => [o.id, {}]));
  for (const v of d.values) (values[v.optionId] ??= {})[v.criterionId] = v.value;
  for (const ranking of d.rankings ?? []) ranking.order.forEach((group, i) => group.forEach(id => { (values[id] ??= {})[ranking.criterionId] = i + 1; }));
  return { id: d.id, title: d.title, description: d.description ?? '', category: d.category, status: d.status,
    options: d.options, criteria: d.criteria.map(c => ({ ...c, unit: c.unit ?? '', levels: c.levels ?? [...defaultLevels], inputMode: c.inputMode ?? 'score' })),
    values, conditions: (d.conditions ?? []) as Condition[], readiness: d.readiness,
    excludedOptions: d.options.filter(o => o.excluded).map(o => ({ optionId: o.id, reasons: o.excludedReasons })),
    createdAt: d.createdAt, updatedAt: d.updatedAt };
}
/** Keep unsaved fields after a partially successful multi-request save, while
 * adopting IDs already issued by the server so retry never adds them twice. */
export function rebaseDraft(draft: Decision, server: Decision): Decision {
  const next = structuredClone(draft), ids = new Map<string,string>();
  for (const o of draft.options) {
    const saved = server.options.find(x => x.id === o.id) ?? server.options.find(x => x.name === o.name.trim());
    if (saved) ids.set(o.id, saved.id);
  }
  for (const c of draft.criteria) {
    const saved = server.criteria.find(x => x.id === c.id) ?? server.criteria.find(x => x.name === c.name.trim());
    if (saved) ids.set(c.id, saved.id);
  }
  const id = (value: string) => ids.get(value) ?? value;
  next.id = server.id; next.status = server.status;
  next.options = next.options.map(o => ({...o,id:id(o.id)}));
  next.criteria = next.criteria.map(c => ({...c,id:id(c.id)}));
  next.values = Object.fromEntries(Object.entries(next.values).map(([o,row]) => [id(o),Object.fromEntries(Object.entries(row).map(([c,value]) => [id(c),value]))]));
  next.conditions = next.conditions.map(c => c.kind === 'required_tag' ? {...c,satisfiedBy:c.satisfiedBy.map(id)} : {...c,criterionId:id(c.criterionId)});
  next.readiness = server.readiness; next.excludedOptions = server.excludedOptions;
  return next;
}
export function criterionBody(c: Criterion) {
  return { name: c.name, type: c.type, ...(c.source === 'ai_recommended' ? { source: c.source } : {}), direction: c.type === 'boolean' ? 'true_better' : c.type === 'rating' ? 'higher_better' : c.direction,
    ...(c.type === 'numeric' ? { unit: c.unit, ...(c.scaleMin == null ? {} : { scaleMin: c.scaleMin }), ...(c.scaleMax == null ? {} : { scaleMax: c.scaleMax }) } : {}),
    ...(c.type === 'rating' ? { inputMode: c.inputMode, levels: c.levels } : {}) };
}
export function valuesBody(d: Decision, ids: Map<string, string> = new Map()): ValuesRequest {
  const id = (value: string) => ids.get(value) ?? value;
  const body: ValuesRequest = { items: [], rankings: [] };
  for (const c of d.criteria) {
    if (c.type === 'rating' && c.inputMode === 'rank') {
      const ranks = d.options.map(o => ({ id: o.id, rank: d.values[o.id]?.[c.id] }));
      if (ranks.every(r => typeof r.rank === 'number' && Number.isInteger(r.rank) && r.rank >= 1)) {
        const groups = new Map<number, string[]>();
        ranks.forEach(r => { const n = Number(r.rank); groups.set(n, [...groups.get(n) ?? [], id(r.id)]); });
        body.rankings.push({ criterionId: id(c.id), order: [...groups.entries()].sort((a,b) => a[0]-b[0]).map(([, group]) => group) });
      }
    } else for (const o of d.options) {
      const value = d.values[o.id]?.[c.id];
      if (typeof value === 'boolean' || typeof value === 'number' && Number.isFinite(value)) body.items.push({ optionId: id(o.id), criterionId: id(c.id), value });
    }
  }
  return body;
}
const equal = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
export const repository = {
  async list(cursor?: string): Promise<DecisionList> {
    const result = await api.request('listDecisions', { query: { cursor, limit: 20 } }) as DecisionList;
    if (!result || !Array.isArray(result.items)) throw new ApiError(0, 'INVALID_RESPONSE', '서버의 결정 목록 응답 형식을 확인해주세요.');
    return result;
  },
  async get(id: string): Promise<Decision> { return fromDto(await api.request('getDecision', { params: { decisionId: id } }) as DecisionDto); },
  async remove(id: string) { await api.request('deleteDecision', { params: { decisionId: id } }); },
  async duplicate(id: string): Promise<Decision> { return fromDto(await api.request('duplicateDecision', { params: { decisionId: id } }) as DecisionDto); },
  /** Forms stage edits in memory. Only successful server responses are persisted.
   * Multiple CRUD calls are not a server transaction; failures are surfaced and
   * callers refetch before retrying, so generated resources aren't duplicated.
   */
  async save(previous: Decision | undefined, draft: Decision): Promise<Decision> {
    const ids = new Map<string, string>();
    let decisionId = previous?.id;
    if (!decisionId) {
      const created = await api.request('createDecision', { body: { title: draft.title.trim(), description: draft.description, category: draft.category, options: draft.options.map(o => ({ name: o.name.trim() })) } }) as DecisionDto;
      decisionId = created.id;
      if (!decisionId || !Array.isArray(created.options)) throw new ApiError(0, 'INVALID_RESPONSE', '결정 생성 응답을 확인해주세요.');
      draft.options.forEach((o, i) => ids.set(o.id, created.options[i].id));
      // Creation is a single write. Do not repeat POST if a later detail GET fails.
      return fromDto({ ...created, criteria: [], values: [], rankings: [], conditions: [], readiness: { canStartQuestions: false, missing: ['CRITERIA_LT_2'] } });
    }
    const params = { decisionId };
    const base = previous!;
    const metadata = (d: Decision) => ({ title: d.title.trim(), description: d.description, category: d.category });
    if (!equal(metadata(base), metadata(draft))) await api.request('updateDecision', { params, body: metadata(draft) });
    for (const o of base.options) if (!draft.options.some(n => (ids.get(n.id) ?? n.id) === o.id)) await api.request('deleteOption', { params: { ...params, optionId: o.id } });
    for (const o of draft.options) {
      const old = base.options.find(v => v.id === (ids.get(o.id) ?? o.id));
      if (!old) {
        const saved = await api.request('createOption', { params, body: { name: o.name.trim() } }) as { id: string };
        ids.set(o.id, saved.id);
      } else if (old.name !== o.name.trim()) await api.request('updateOption', { params: { ...params, optionId: old.id }, body: { name: o.name.trim() } });
    }
    for (const c of base.criteria) if (!draft.criteria.some(n => n.id === c.id)) await api.request('deleteCriterion', { params: { ...params, criterionId: c.id } });
    for (const c of draft.criteria) {
      const old = base.criteria.find(v => v.id === c.id);
      if (!old) {
        const saved = await api.request('createCriterion', { params, body: criterionBody(c) }) as CriterionDto;
        ids.set(c.id, saved.id);
      } else if (!equal(criterionBody(old), criterionBody(c))) await api.request('updateCriterion', { params: { ...params, criterionId: c.id }, body: criterionBody(c) });
    }
    const values = valuesBody(draft, ids);
    if (!equal(values, valuesBody(base)) && (values.items.length || values.rankings.length)) await api.request('saveValues', { params, body: values as never });
    const conditions = (d: Decision, map = new Map<string, string>()) => d.conditions.map(c => c.kind === 'required_tag'
      ? { kind: c.kind, label: c.label, satisfiedBy: c.satisfiedBy.map(id => map.get(id) ?? id) }
      : { kind: c.kind, criterionId: map.get(c.criterionId) ?? c.criterionId, operator: c.operator, value: c.value });
    if (!equal(conditions(base), conditions(draft, ids))) await api.request('setConditions', { params, body: { items: conditions(draft, ids) } });
    return this.get(decisionId);
  },
};

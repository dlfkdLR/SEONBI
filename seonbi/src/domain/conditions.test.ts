import { describe, expect, it } from 'vitest';
import { previewConditions } from './conditions';
import type { Decision } from './types';
const data: Pick<Decision, 'options' | 'criteria' | 'values' | 'conditions'> = {
  options: [{ id: 'a', name: 'A' }, { id: 'b', name: 'B' }, { id: 'c', name: 'C' }],
  criteria: [{ id: 'weight', name: '무게', type: 'numeric', direction: 'lower_better', unit: 'kg', inputMode: 'score', levels: [] }],
  values: { a: { weight: 1.3 }, b: { weight: 1.4 }, c: { weight: null } },
  conditions: [{ id: 'max', kind: 'threshold', criterionId: 'weight', operator: '<=', value: 1.3 }],
};
describe('조건 충족 미리보기', () => {
  it('경계값은 포함하고 초과값은 제외한다', () => {
    const rows = previewConditions(data); expect(rows[0].eligible).toBe(true); expect(rows[1].eligible).toBe(false); expect(rows[1].reasons).toEqual(['무게 조건']);
  });
  it('비어 있는 값을 0으로 간주하지 않고 정보 부족을 표시한다', () => {
    expect(previewConditions(data)[2]).toMatchObject({ eligible: false, unknown: ['무게'], reasons: [] });
  });
  it('조건을 끄면 정보를 입력하지 않은 후보도 제외하지 않는다', () => {
    expect(previewConditions({ ...data, conditions: [] }).every(o => o.eligible)).toBe(true);
  });
  it('최소값과 정확한 값 조건을 적용한다', () => {
    const min = { ...data, conditions: [{ id: 'min', kind: 'threshold' as const, criterionId: 'weight', operator: '>=' as const, value: 1.4 }] };
    expect(previewConditions(min).filter(o => o.eligible).map(o => o.id)).toEqual(['b']);
    expect(previewConditions({ ...min, conditions: [{ ...min.conditions[0], operator: '==' }] }).filter(o => o.eligible).map(o => o.id)).toEqual(['b']);
  });
  it('여러 조건을 모두 만족해야 비교에 남는다', () => {
    const rows = previewConditions({ ...data, conditions: [...data.conditions, { id: 'tag', kind: 'required_tag', label: 'Windows', satisfiedBy: ['b'] }] });
    expect(rows.every(o => !o.eligible)).toBe(true); expect(rows[0].reasons).toEqual(['Windows']);
  });
});

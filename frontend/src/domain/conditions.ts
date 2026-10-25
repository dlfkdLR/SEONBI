import type { Decision } from './types';
/** Input-only preview. Ranking and recommendation always come from the API. */
export function previewConditions(d: Pick<Decision, 'options' | 'criteria' | 'values' | 'conditions'>) {
  return d.options.map(option => {
    const reasons: string[] = [], unknown: string[] = [];
    for (const condition of d.conditions) {
      if (condition.kind === 'required_tag') {
        if (!condition.satisfiedBy.includes(option.id)) reasons.push(condition.label);
      } else {
        const value = d.values[option.id]?.[condition.criterionId];
        const name = d.criteria.find(c => c.id === condition.criterionId)?.name ?? '항목';
        if (typeof value !== 'number' || !Number.isFinite(value)) unknown.push(name);
        else if (!(condition.operator === '<=' ? value <= condition.value : condition.operator === '>=' ? value >= condition.value : value === condition.value)) reasons.push(`${name} 조건`);
      }
    }
    return { ...option, reasons, unknown, eligible: reasons.length === 0 && unknown.length === 0 };
  });
}

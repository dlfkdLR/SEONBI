import type { QuestionDto } from '../api/contracts';
import { withParticle } from '../lib/korean';
import type { Criterion } from './types';

export type QuestionCriterion = Pick<Criterion, 'id' | 'name' | 'unit' | 'type'>;

/** Explain the server's signed changes without changing their values or meaning. */
export function scenarioDescription(option: QuestionDto['payload']['optionA'], criteria: QuestionCriterion[]): string | undefined {
  if (!option?.deltas?.length) return option?.description;
  const sentences: string[] = [];
  for (const delta of option.deltas) {
    const criterion = criteria.find(c => c.id === delta.criterionId);
    const match = delta.display.trim().match(/^([+\-−])(\d[\d,]*(?:\.\d+)?)\s*(.*)$/);
    if (!criterion || !match || criterion.type === 'boolean') return option.description;
    const [, sign, amount, displayUnit] = match;
    const unit = displayUnit.trim() || (criterion.type === 'numeric' ? criterion.unit.trim() : '단계');
    // Only short unit text is a unit; never turn a prose description into one.
    const quantity = unit && unit.length <= 12 ? `${amount}${unit}` : `${amount}만큼`;
    sentences.push(`${withParticle(criterion.name, '은', '는')} ${quantity} ${sign === '+' ? '늘어요' : '줄어요'}.`);
  }
  return sentences.join(' ');
}

/** Keep question IDs, answer directions and values intact; simplify display only. */
export function presentQuestion(q: QuestionDto): QuestionDto {
  if (!q.payload || (q.type !== 'slider_pair' && q.type !== 'ab_criteria')) return q;
  const first = q.type === 'slider_pair' ? q.payload.left : q.payload.optionA;
  const second = q.type === 'slider_pair' ? q.payload.right : q.payload.optionB;
  const name = (label: string) => {
    const base = label.split(/[（(]/, 1)[0].trim() || label.trim();
    return base === '만족도 기대' ? '만족도' : base;
  };
  if (!first?.label || !second?.label) return q;
  const a = name(first.label), b = name(second.label);
  const payload = { ...q.payload };
  if (q.type === 'slider_pair') {
    payload.left = { ...q.payload.left!, label: a, range: undefined };
    payload.right = { ...q.payload.right!, label: b, range: undefined };
  } else {
    payload.optionA = { ...q.payload.optionA!, label: a, description: undefined, deltas: undefined };
    payload.optionB = { ...q.payload.optionB!, label: b, description: undefined, deltas: undefined };
  }
  return { ...q, title: `${withParticle(a, '과', '와')} ${b} 중 어떤 요소를 더 중요시하나요?`, payload };
}

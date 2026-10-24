import type { Decision, Criterion } from './types';
const clamp = (v: number) => Math.max(0, Math.min(1, v));
const sum = (xs: number[]) => xs.reduce((a,b) => a+b,0);
export function redistribute(weights: number[], index: number, value: number): number[] {
  const t = clamp(value), rest = sum(weights.filter((_, i) => i !== index));
  return weights.map((v, i) => i === index ? t : (1 - t) * (rest > 1e-12 ? v / rest : 1 / (weights.length - 1)));
}

export function formatValue(value: number | boolean | null | undefined, c: Criterion): string {
  if (value == null) return '—';
  if (c.type === 'boolean') return value ? '예' : '아니요';
  if (c.type === 'rating') return c.inputMode === 'rank' ? `${value}위` : (c.levels[Number(value) - 1] ?? '—');
  return `${value}${c.unit ? ' ' + c.unit : ''}`;
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
  if (step >= 3) for (const o of d.options) {
    // 칸마다 한 문장씩 늘어놓지 않고, 선택지별로 비어 있는 기준 이름만 묶어서 알려준다
    const empty = d.criteria.filter(c => {
      const v = d.values[o.id]?.[c.id];
      const valid = c.type === 'boolean' ? typeof v === 'boolean' : typeof v === 'number' && Number.isFinite(v)
        && (c.type !== 'rating' || (Number.isInteger(v) && v >= 1 && v <= (c.inputMode === 'rank' ? d.options.length : c.levels.length)));
      return !valid;
    });
    if (empty.length) errors.push(`${o.name || '이름 없는 선택지'}: ${empty.map(c => c.name).join(', ')} 값을 입력해주세요.`);
  }
  return errors;
}

import type { Criterion, Decision } from './types';

const phone = /스마트폰|휴대폰|핸드폰|아이폰|iphone|갤럭시\s*(?:s\d|a\d|z\s*(?:폴드|플립))|galaxy\s*(?:s\d|a\d|z\s*(?:fold|flip))/i;
const batteryTimeNames = new Set(['시간', '배터리', '배터리 시간', '배터리 사용 시간', '배터리 지속 시간']);

export function isPhoneDecision(d: Pick<Decision, 'title' | 'options'>): boolean {
  return phone.test([d.title, ...d.options.map(o => o.name)].join(' '));
}

/** A phone comparison uses capacity, never a renamed/reinterpreted hour value. */
export function phoneCriterion(c: Criterion, d: Pick<Decision, 'title' | 'options'>): Criterion {
  if (!isPhoneDecision(d) || !batteryTimeNames.has(c.name.trim()) || c.type !== 'numeric') return c;
  const { scaleMin: _min, scaleMax: _max, ...rest } = c;
  return { ...rest, name: '배터리 용량', unit: 'mAh', direction: 'higher_better' };
}

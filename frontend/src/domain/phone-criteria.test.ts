import { expect, it } from 'vitest';
import { phoneCriterion } from './phone-criteria';
import type { Criterion } from './types';
const time: Criterion = { id: 'time', name: '시간', type: 'numeric', unit: '시간', direction: 'lower_better', inputMode: 'score', levels: [], scaleMin: 1, scaleMax: 24 };
it('휴대폰 비교의 시간을 배터리 용량 mAh·높을수록 좋음으로 바꾸고 시간 척도는 버린다', () => {
  const next = phoneCriterion(time, { title: '새 폰 선택', options: [{ id: 'a', name: '아이폰 18프로' }, { id: 'b', name: '갤럭시 S26울트라' }] });
  expect(next).toMatchObject({ id: 'time', name: '배터리 용량', unit: 'mAh', direction: 'higher_better' });
  expect(next.scaleMin).toBeUndefined(); expect(next.scaleMax).toBeUndefined();
  expect(time.name).toBe('시간');
});
it('통학·노트북의 시간 항목은 바꾸지 않는다', () => {
  for (const title of ['통학 경로 선택', '노트북 고르기']) expect(phoneCriterion(time, { title, options: [{ id: 'a', name: '갤럭시북5 프로' }] })).toBe(time);
});
it('이미 입력한 배터리 용량과 다른 항목은 그대로 둔다', () => {
  const capacity = { ...time, name: '배터리 용량', unit: 'mAh' };
  expect(phoneCriterion(capacity, { title: '휴대폰', options: [] })).toBe(capacity);
  const other = { ...time, name: '충전 시간' };
  expect(phoneCriterion(other, { title: '휴대폰', options: [] })).toBe(other);
});

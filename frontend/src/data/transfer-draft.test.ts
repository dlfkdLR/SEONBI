import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { moveNewDecisionDraft } from './new-decision-draft';

const key = (owner: string) => `seonbi.newDecisionDraft.v1.${owner}`;
beforeEach(() => {
  const data = new Map<string, string>();
  vi.stubGlobal('localStorage', { getItem: (k: string) => data.get(k) ?? null, setItem: (k: string, v: string) => data.set(k, v), removeItem: (k: string) => data.delete(k) });
});
afterEach(() => vi.unstubAllGlobals());

it('기록 이동 뒤에는 작성 중 임시 저장을 새 ID 쪽으로 옮긴다', () => {
  localStorage.setItem(key('old'), '{"a":1}');
  moveNewDecisionDraft('old', 'new');
  expect(localStorage.getItem(key('new'))).toBe('{"a":1}');
  expect(localStorage.getItem(key('old'))).toBeNull();
});
it('새 ID에 이미 임시 저장이 있으면 덮어쓰지 않는다', () => {
  localStorage.setItem(key('old'), 'OLD');
  localStorage.setItem(key('new'), 'NEW');
  moveNewDecisionDraft('old', 'new');
  expect(localStorage.getItem(key('new'))).toBe('NEW');
});
it('같은 ID로 옮기면 아무 일도 하지 않는다', () => {
  localStorage.setItem(key('same'), 'X');
  moveNewDecisionDraft('same', 'same');
  expect(localStorage.getItem(key('same'))).toBe('X');
});

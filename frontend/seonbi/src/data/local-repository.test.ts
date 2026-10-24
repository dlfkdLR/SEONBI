import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { blankDecision, examples } from './examples';
import { repository } from './local-repository';
import type { Decision } from '../domain/types';

const key = 'seonbi.decisions.v1';
function stored(): Decision[] { return JSON.parse(localStorage.getItem(key) ?? '[]'); }
beforeEach(() => {
  const data = new Map<string, string>();
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => { data.set(key, value); },
  });
});
afterEach(() => vi.unstubAllGlobals());

describe('내 결정 수에 따른 예시 표시', () => {
  it('처음에는 예시 하나만 만들고 다시 열어도 중복되지 않는다', () => {
    const first = repository.list('owner');
    expect(first).toHaveLength(1);
    expect(first[0].example).toBe(true);
    expect(first[0].title).toBe('새 노트북 고르기');
    expect(repository.list('owner')).toEqual(first);
  });

  it('새 결정을 만들면 예시를 없애고 마지막 내 결정을 삭제하면 복원한다', () => {
    repository.list('owner');
    const a = repository.save('owner', { ...blankDecision('owner'), title: '내 고민' });
    const b = repository.save('owner', blankDecision('owner'));
    expect(stored().some(d => d.example)).toBe(false);
    repository.remove('owner', a.id);
    expect(repository.list('owner')).toEqual([b]);
    repository.remove('owner', b.id);
    expect(stored()).toHaveLength(1);
    expect(stored()[0].example).toBe(true);
    const restored = repository.list('owner');
    expect(repository.list('owner')).toEqual(restored);
    repository.save('owner', a);
    expect(repository.list('owner')).toEqual([expect.objectContaining({ id: a.id, title: '내 고민' })]);
  });

  it('기존 예시 세 개를 하나로 정리하고 기존 내 기록을 보존한다', () => {
    const sample = examples('owner')[0];
    localStorage.setItem(key, JSON.stringify([
      { ...sample, id: 'activity', title: '여름방학에 할 활동' }, sample,
      { ...sample, id: 'career', title: '다음 학기 전공 선택' },
    ]));
    localStorage.setItem('seonbi.initialized', '1');
    expect(repository.list('owner')).toEqual([sample]);
    const own = { ...blankDecision('owner'), title: '기존 내 결정' };
    localStorage.setItem(key, JSON.stringify([sample, own]));
    expect(repository.list('owner')).toEqual([own]);
    expect(stored()).toEqual([own]);
  });

  it('다른 소유자의 기록은 예시 정리나 복원으로 변경하지 않는다', () => {
    const other = { ...blankDecision('other'), title: '다른 기록' };
    localStorage.setItem(key, JSON.stringify([other]));
    repository.list('owner');
    const own = repository.save('owner', blankDecision('owner'));
    repository.remove('owner', own.id);
    expect(stored().filter(d => d.ownerId === 'other')).toEqual([other]);
    expect(repository.list('owner')).toEqual([expect.objectContaining({ example: true })]);
  });

  it('저장이 실패하면 내 결정과 기존 예시를 함께 보존한다', () => {
    const own = repository.save('owner', blankDecision('owner'));
    const before = stored();
    vi.spyOn(localStorage, 'setItem').mockImplementation(() => { throw new Error('quota'); });
    expect(() => repository.remove('owner', own.id)).toThrow('저장하지 못했어요');
    expect(stored()).toEqual(before);
  });
});

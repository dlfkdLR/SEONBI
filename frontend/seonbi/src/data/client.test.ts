import { describe, expect, it, vi } from 'vitest';
import { createClientIdProvider } from './client';

describe('브라우저 클라이언트 ID', () => {
  it('저장된 ID를 다시 사용한다', () => {
    const storage = { getItem: vi.fn(() => 'existing-id'), setItem: vi.fn() } as unknown as Storage;
    const getId = createClientIdProvider(() => storage, () => 'new-id');
    expect(getId()).toBe('existing-id');
    expect(getId()).toBe('existing-id');
    expect(storage.setItem).not.toHaveBeenCalled();
  });

  it('스토리지 읽기·쓰기가 차단되어도 메모리 ID로 요청을 계속할 수 있다', () => {
    const storage = {
      getItem: () => { throw new Error('blocked'); },
      setItem: () => { throw new Error('blocked'); },
    } as unknown as Storage;
    const makeId = vi.fn(() => 'memory-id');
    const getId = createClientIdProvider(() => storage, makeId);
    expect(getId()).toBe('memory-id');
    expect(getId()).toBe('memory-id');
    expect(makeId).toHaveBeenCalledTimes(1);
  });

  it('스토리지 객체 접근 자체가 차단되어도 동작한다', () => {
    const getId = createClientIdProvider(() => { throw new Error('blocked'); }, () => 'fallback-id');
    expect(getId()).toBe('fallback-id');
  });
});

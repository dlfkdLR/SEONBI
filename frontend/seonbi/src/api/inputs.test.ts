import { describe, expect, it, vi } from 'vitest';
import { createInputApi } from './inputs';

describe('자유 입력 API', () => {
  it('추가 설명이 500자를 넘으면 기존 입력을 거절하기 전에 중단한다', async () => {
    const request = vi.fn(async (..._args: unknown[]) => ({}));
    const client = { request } as never;
    const inputApi = createInputApi(client);
    await expect(inputApi.retryInputWithClarification('d1', 'i1', { context: 'reason', text: 'a'.repeat(480) }, 'b'.repeat(13))).rejects.toThrow(RangeError);
    expect(request).not.toHaveBeenCalled();
  });

  it('500자 이내면 기존 추출을 거절하고 합친 문장으로 다시 추출한다', async () => {
    const request = vi.fn(async (..._args: unknown[]) => ({}));
    const inputApi = createInputApi({ request } as never);
    await inputApi.retryInputWithClarification('d1', 'i1', { context: 'reason', text: '선호 조건' }, '예산은 100만원');
    expect(request.mock.calls.map(([name]) => name)).toEqual(['rejectInput', 'extractInput']);
    expect(request.mock.calls[1][1]).toMatchObject({ body: { text: '선호 조건\n추가 설명: 예산은 100만원' } });
  });
});

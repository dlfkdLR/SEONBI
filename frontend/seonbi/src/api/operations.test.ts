import { describe, expect, it, vi } from 'vitest';
import { createDecisionApi } from './operations';

describe('화면 API 작업 함수', () => {
  it('화면에서 쓰는 결정·구성·질문·답변·분석 작업을 공통 클라이언트에 전달한다', async () => {
    const request = vi.fn(async (..._args: unknown[]) => ({}));
    const exportDecision = vi.fn(async () => ({ blob: new Blob(), filename: 'decision.md' }));
    const operations = createDecisionApi({ request, exportDecision } as never);
    await operations.create({ title: '선택' });
    await operations.getOptions('d1');
    await operations.setConditions('d1', { items: [] });
    await operations.submitAnswer('d1', { questionId: 'q1', response: { choice: 'A' } });
    await operations.analysis('d1');
    await operations.export('d1');
    expect(request.mock.calls.map(([name]) => name)).toEqual([
      'createDecision', 'listOptions', 'setConditions', 'submitAnswer', 'analysis',
    ]);
    expect(request.mock.calls[2][1]).toMatchObject({ params: { decisionId: 'd1' }, body: { items: [] } });
    expect(exportDecision).toHaveBeenCalledWith({ params: { decisionId: 'd1' }, query: { format: 'md' } });
  });
});

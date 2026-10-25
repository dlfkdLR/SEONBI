import { expect, test } from '@playwright/test';
import type { QuestionDto } from '../src/api/contracts';
import { decisionFixture, mockApi } from './test-api';

test('질문과 답변 수정창의 항목 이름·좌우 방향을 맞추고 답변 부호는 유지한다', async ({ page }, info) => {
  const d = decisionFixture(), server = await mockApi(page, [d]);
  const q: QuestionDto = { id: 'q0', seq: 1, type: 'slider_pair', title: '원본 질문', criteria: ['c1', 'c2'], allowSkip: true, allowFreeText: false,
    payload: { left: { criterionId: 'c1', label: '비용(220~270)', range: '220~270' }, right: { criterionId: 'c2', label: '만족도 기대(아이폰(낮음)~갤럭시(높음))' }, min: -5, max: 5 } };
  await page.route('**/questions/next', route => route.fulfill({ json: { done: false, question: q } }));
  await page.route('**/answers', async route => {
    if (route.request().method() !== 'GET') return route.fallback();
    return route.fulfill({ json: { items: [{ id: 'a1', questionId: q.id, question: q, response: { value: 0 }, isValid: true, createdAt: d.createdAt }] } });
  });
  let saved: unknown;
  await page.route('**/answers/a1', async route => {
    saved = route.request().postDataJSON();
    return route.fulfill({ status: 204 });
  });
  await page.goto(`/decisions/${d.id}/questions`);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('비용과 만족도 중 어떤 요소를 더 중요시하나요?');
  await expect(page.locator('.preference-slider strong')).toHaveText('만족도 기대 ↔ 비용');
  await expect(page.locator('.range-labels > span')).toHaveText(['만족도', '비용']);
  await page.getByRole('slider').fill('-5');
  await page.getByRole('button', { name: '이 선택으로 계속', exact: true }).click();
  await expect.poll(() => server.calls.find(c => c.method === 'POST' && c.path.endsWith('/answers'))?.body).toEqual({ questionId: 'q0', response: { value: -5 } });
  await page.getByRole('button', { name: '이전 답변 보기' }).click();
  await page.getByRole('button', { name: '수정', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.locator('.preference-slider strong')).toHaveText('만족도 기대 ↔ 비용');
  await expect(dialog.locator('.range-labels > span')).toHaveText(['만족도', '비용']);
  await dialog.getByRole('slider').fill('5');
  await page.screenshot({ path: info.outputPath('slider-directions.png'), fullPage: true, animations: 'disabled' });
  await dialog.getByRole('button', { name: '수정한 답변 저장' }).click();
  await expect(dialog).toHaveCount(0);
  expect(saved).toEqual({ response: { value: 5 } });
});

test('가상 선택지의 증감값에 실제 기준 이름을 붙이고 선택 답변은 유지한다', async ({ page }, info) => {
  const d = decisionFixture();
  d.criteria[0] = { id: 'c1', name: '비용', type: 'numeric', direction: 'lower_better', unit: '만 원' };
  d.criteria[1] = { id: 'c2', name: '배터리 용량', type: 'numeric', direction: 'higher_better', unit: 'mAh' };
  const server = await mockApi(page, [d]);
  const q: QuestionDto = { id: 'q0', seq: 4, type: 'ab_scenario', title: '다음 두 선택지 중 어느 쪽을 고르시겠어요?', criteria: ['c1', 'c2'], allowSkip: true, allowFreeText: false,
    payload: { optionA: { label: 'A', description: '비용은 줄고 배터리 용량은 줄어요.', deltas: [{ criterionId: 'c1', display: '-25' }, { criterionId: 'c2', display: '-160mAh' }] }, optionB: { label: 'B', description: '비용은 늘고 배터리 용량은 늘어요.', deltas: [{ criterionId: 'c1', display: '+25' }, { criterionId: 'c2', display: '+160mAh' }] } } };
  await page.route('**/questions/next', route => route.fulfill({ json: { done: false, question: q } }));
  await page.route('**/answers', route => route.request().method() === 'GET'
    ? route.fulfill({ json: { items: [{ id: 'a1', questionId: q.id, question: q, response: { choice: 'A' }, isValid: true, createdAt: d.createdAt }] } }) : route.fallback());
  await page.goto(`/decisions/${d.id}/questions`);
  const cards = page.locator('.choice-card');
  await expect(cards.nth(0)).toContainText('비용은 25만 원 줄어요. 배터리 용량은 160mAh 줄어요.');
  await expect(cards.nth(1)).toContainText('비용은 25만 원 늘어요. 배터리 용량은 160mAh 늘어요.');
  await expect(cards.nth(0)).toContainText('비용: -25');
  await expect(cards.nth(0)).toContainText('배터리 용량: -160mAh');
  await expect(cards.nth(1)).toContainText('비용: +25');
  await expect(cards.nth(1)).toContainText('배터리 용량: +160mAh');
  await page.screenshot({ path: info.outputPath('scenario-labels.png'), fullPage: true, animations: 'disabled' });
  await cards.nth(1).click();
  await page.getByRole('button', { name: '이 선택으로 계속' }).click();
  await expect.poll(() => server.calls.find(c => c.method === 'POST' && c.path.endsWith('/answers'))?.body).toEqual({ questionId: 'q0', response: { choice: 'B' } });
  await page.getByRole('button', { name: '이전 답변 보기' }).click();
  await page.getByRole('button', { name: '수정', exact: true }).click();
  await expect(page.getByRole('dialog').locator('.choice-card').nth(0)).toContainText('비용은 25만 원 줄어요.');
  await expect(page.getByRole('dialog').locator('.choice-card').nth(0)).toContainText('비용: -25');
  await expect(page.getByRole('dialog').locator('.choice-card').nth(1)).toContainText('배터리 용량: +160mAh');
});

import { expect, test } from '@playwright/test';
import { mockApi } from './test-api';

async function fillDraft(page: import('@playwright/test').Page) {
  await page.goto('/decisions/new/setup/1');
  await page.getByLabel('고민 제목', { exact: true }).fill('새 휴대폰 고르기');
  await page.getByLabel('어디에 사용할 건가요? · 선택').fill('사진과 동영상 촬영');
  await page.getByRole('button', { name: '후보 적기', exact: true }).click();
  await page.getByLabel('후보 1', { exact: true }).fill('아이폰 18프로');
  await page.getByLabel('후보 2', { exact: true }).fill('갤럭시 S26울트라');
}

test('새로고침과 화면 재진입 후 제목·설명·후보를 유지하고 생성 요청에 보낸다', async ({ page }) => {
  const server = await mockApi(page);
  await fillDraft(page);
  await page.reload();
  await expect(page.getByLabel('후보 1', { exact: true })).toHaveValue('아이폰 18프로');
  await expect(page.getByLabel('후보 2', { exact: true })).toHaveValue('갤럭시 S26울트라');
  await page.getByRole('button', { name: '‹ 고민 제목으로' }).click();
  await expect(page.getByLabel('고민 제목', { exact: true })).toHaveValue('새 휴대폰 고르기');
  await expect(page.getByLabel('어디에 사용할 건가요? · 선택')).toHaveValue('사진과 동영상 촬영');
  await page.goto('/');
  await page.getByRole('button', { name: '내 결정 시작하기', exact: true }).click();
  await expect(page.getByLabel('고민 제목', { exact: true })).toHaveValue('새 휴대폰 고르기');
  expect(server.calls.some(c => c.method === 'POST')).toBe(false);
  await page.getByRole('button', { name: '후보 적기', exact: true }).click();
  await page.getByRole('button', { name: '비교할 항목 고르기', exact: true }).click();
  await expect(page).toHaveURL(/server-decision-1\/setup\/3/);
  const request = server.calls.find(c => c.method === 'POST' && c.path.endsWith('/decisions'));
  expect(request?.body).toMatchObject({ title: '새 휴대폰 고르기', description: '사진과 동영상 촬영', options: [{ name: '아이폰 18프로' }, { name: '갤럭시 S26울트라' }] });
  await page.goto('/');
  await page.getByRole('button', { name: '내 결정 시작하기', exact: true }).click();
  await expect(page.getByLabel('고민 제목', { exact: true })).toHaveValue('');
  await page.getByLabel('고민 제목', { exact: true }).fill('다음 고민');
  await page.getByRole('button', { name: '후보 적기', exact: true }).click();
  await expect(page.getByLabel('후보 1', { exact: true })).toHaveValue('');
});

test('생성 실패 후에도 새로고침하면 제목과 후보가 유지돼 재시도한다', async ({ page }) => {
  const server = await mockApi(page);
  const fail = async (route: import('@playwright/test').Route) => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: { code: 'UNAVAILABLE', message: '잠시 후 다시 시도해주세요.' } }) });
  await page.route('**/api/v1/decisions', fail);
  await fillDraft(page);
  await page.getByRole('button', { name: '비교할 항목 고르기', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('저장하지 못했어요.');
  await page.reload();
  await expect(page.getByLabel('후보 1', { exact: true })).toHaveValue('아이폰 18프로');
  await page.unroute('**/api/v1/decisions', fail);
  await page.getByRole('button', { name: '비교할 항목 고르기', exact: true }).click();
  await expect(page).toHaveURL(/server-decision-1\/setup\/3/);
  expect(server.records.get('server-decision-1')?.title).toBe('새 휴대폰 고르기');
});

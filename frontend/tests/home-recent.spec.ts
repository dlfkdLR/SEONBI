import { expect, test } from '@playwright/test';
import { decisionFixture, mockApi } from './test-api';

test('홈에서 최근 고민 3개를 최신순으로 보여주고 상태에 맞게 이어간다', async ({ page }, info) => {
  const rows = (['FINALIZED', 'ANALYZED', 'QUESTIONING', 'DRAFT'] as const).map((status, i) => ({
    ...decisionFixture(), id: `recent-${i}`, title: ['오래된 고민', '주말 여행지 정하기', '노트북 비교하기', '새 휴대폰 고르기'][i],
    status, updatedAt: `2026-10-${23 + i}T00:00:00Z`,
  }));
  const server = await mockApi(page, rows);
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto('/');
  const recent = page.getByRole('region', { name: '최근 고민 이어보기' });
  await expect(recent.locator('h3')).toHaveText(['새 휴대폰 고르기', '노트북 비교하기', '주말 여행지 정하기']);
  await expect(recent.locator('.home-status')).toHaveText(['작성 중', '질문 중', '분석 완료']);
  await expect(recent.getByRole('link', { name: '새 휴대폰 고르기 · 이어서 작성하기' })).toHaveAttribute('href', '/decisions/recent-3/setup/1');
  await expect(recent.getByRole('link', { name: '노트북 비교하기 · 질문 이어가기' })).toHaveAttribute('href', '/decisions/recent-2/questions');
  await expect(recent.getByRole('link', { name: '주말 여행지 정하기 · 결과 살펴보기' })).toHaveAttribute('href', '/decisions/recent-1/result');
  const cards = recent.locator('.home-recent-card');
  const first = (await cards.first().boundingBox())!, last = (await cards.last().boundingBox())!;
  expect(Math.abs(first.y - last.y)).toBeLessThan(2);
  expect(last.x).toBeGreaterThan(first.x + first.width);
  expect(Math.abs(first.x - (await page.locator('.home-intro').boundingBox())!.x)).toBeLessThan(2);
  await page.screenshot({ path: info.outputPath('home-recent.png'), fullPage: true, animations: 'disabled' });
  await page.setViewportSize({ width: 1060, height: 920 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(1060);
  await recent.getByRole('link', { name: '새 휴대폰 고르기 · 이어서 작성하기' }).click();
  await expect(page.getByLabel('고민 제목', { exact: true })).toHaveValue('새 휴대폰 고르기');
  expect(server.calls.some(c => c.method !== 'GET')).toBe(false);
});

test('홈의 임시 저장 카드는 서버에 만들지 않은 내용을 이어 쓴다', async ({ page }) => {
  const server = await mockApi(page, [decisionFixture()]);
  await page.goto('/decisions/new/setup/1');
  await page.getByLabel('고민 제목', { exact: true }).fill('이사할 동네 고르기');
  await page.getByRole('button', { name: '저장하고 나가기' }).click();
  await page.getByRole('link', { name: '선비 홈' }).click();
  const recent = page.getByRole('region', { name: '최근 고민 이어보기' });
  await expect(recent.locator('h3').first()).toHaveText('이사할 동네 고르기');
  await expect(recent.locator('.home-status').first()).toHaveText('임시 저장');
  await expect(page.locator('.home-preview')).toHaveCount(0);
  await recent.getByRole('link', { name: '이사할 동네 고르기 · 이어서 작성하기' }).click();
  await expect(page.getByLabel('고민 제목', { exact: true })).toHaveValue('이사할 동네 고르기');
  expect(server.calls.some(c => c.method !== 'GET')).toBe(false);
});

import { expect, test } from '@playwright/test';
import { decisionFixture, mockApi } from './test-api';

test('제목만 임시 저장하고 새 탭에서 이어 쓴 뒤 서버에 생성하면 중복 카드를 없앤다', async ({ page, context }, info) => {
  const server = await mockApi(page);
  await page.goto('/decisions/new/setup/1');
  await page.getByLabel('고민 제목', { exact: true }).fill('이사할 동네 고르기');
  await page.getByRole('button', { name: '저장하고 나가기', exact: true }).click();
  await expect(page).toHaveURL(/\/decisions$/);
  await expect(page.locator('.local-draft-card')).toContainText('이사할 동네 고르기');
  expect(server.calls.some(c => c.method === 'POST')).toBe(false);
  const second = await context.newPage();
  const secondServer = await mockApi(second);
  await second.goto('/decisions');
  await second.locator('.local-draft-card').getByRole('link', { name: '이어서 작성하기' }).click();
  await expect(second.getByLabel('고민 제목', { exact: true })).toHaveValue('이사할 동네 고르기');
  await second.getByRole('button', { name: '후보 적기', exact: true }).click();
  await second.getByLabel('후보 1', { exact: true }).fill('성수동');
  await second.getByLabel('후보 2', { exact: true }).fill('합정동');
  await second.getByRole('button', { name: '저장하고 나가기' }).click();
  await second.locator('.local-draft-card').getByRole('link', { name: '이어서 작성하기' }).click();
  await expect(second).toHaveURL(/new\/setup\/2$/);
  await expect(second.getByLabel('후보 1', { exact: true })).toHaveValue('성수동');
  await second.getByRole('button', { name: '비교할 항목 고르기', exact: true }).click();
  await expect(second).toHaveURL(/server-decision-1\/setup\/3$/);
  expect(secondServer.records.get('server-decision-1')?.title).toBe('이사할 동네 고르기');
  await second.goto('/decisions');
  await expect(second.locator('.local-draft-card')).toHaveCount(0);
  await expect(second.locator('.record-card')).toHaveCount(1);
  await second.screenshot({ path: info.outputPath('saved-decision.png'), fullPage: true, animations: 'disabled' });
  await second.close();
});

test('서버 결정의 중간 저장은 서버에 반영하고 실패하면 작성 화면에 남는다', async ({ page }) => {
  const d = decisionFixture(), server = await mockApi(page, [d]);
  await page.goto(`/decisions/${d.id}/setup/1`);
  await page.getByLabel('고민 제목', { exact: true }).fill('수정한 고민');
  const fail = async (route: import('@playwright/test').Route) => route.fulfill({ status: 503, json: { error: { code: 'UNAVAILABLE', message: '잠시 후 다시 시도해주세요.' } } });
  await page.route(`**/api/v1/decisions/${d.id}`, route => route.request().method() === 'PATCH' ? fail(route) : route.fallback());
  await page.getByRole('button', { name: '저장하고 나가기' }).click();
  await expect(page.getByRole('alert')).toContainText('저장하지 못했어요.');
  await expect(page).toHaveURL(/setup\/1$/);
  await expect(page.getByLabel('고민 제목', { exact: true })).toHaveValue('수정한 고민');
  await page.unroute(`**/api/v1/decisions/${d.id}`);
  await page.getByRole('button', { name: '저장하고 나가기' }).click();
  await expect(page).toHaveURL(/\/decisions$/);
  expect(server.records.get(d.id)?.title).toBe('수정한 고민');
  await page.reload();
  await page.getByRole('link', { name: '이어서 작성하기' }).click();
  await expect(page.getByLabel('고민 제목', { exact: true })).toHaveValue('수정한 고민');
});

test('결정은 데스크톱에서 3열·2열로 배치하고 제목과 카드의 왼쪽 정렬을 맞춘다', async ({ page }, info) => {
  const records = Array.from({ length: 6 }, (_, i) => ({ ...decisionFixture(), id: `decision-${i}`, title: ['새 노트북 고르기', '이사할 동네 정하기', '다음 학기 수업 고르기', '출퇴근 방법 비교', '주말 여행지 고르기', '새 휴대폰 비교'][i] }));
  await mockApi(page, records);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto('/decisions');
  await expect(page.locator('.record-card')).toHaveCount(6);
  const cards = page.locator('.record-card');
  const first = (await cards.nth(0).boundingBox())!, third = (await cards.nth(2).boundingBox())!, fourth = (await cards.nth(3).boundingBox())!;
  expect(Math.abs(first.y - third.y)).toBeLessThan(2);
  expect(third.x).toBeGreaterThan(first.x + first.width);
  expect(fourth.y).toBeGreaterThan(first.y + first.height);
  expect(Math.abs((await page.locator('h1').boundingBox())!.x - first.x)).toBeLessThan(2);
  await page.screenshot({ path: info.outputPath('decisions-wide.png'), fullPage: true, animations: 'disabled' });
  await page.setViewportSize({ width: 1060, height: 920 });
  const narrowFirst = (await cards.nth(0).boundingBox())!, narrowThird = (await cards.nth(2).boundingBox())!;
  expect(narrowThird.y).toBeGreaterThan(narrowFirst.y + narrowFirst.height);
  expect(Math.abs(narrowThird.x - narrowFirst.x)).toBeLessThan(2);
  await page.screenshot({ path: info.outputPath('decisions-compact.png'), fullPage: true, animations: 'disabled' });
});

test('서버 연결이 없어도 브라우저 임시 저장을 이어 쓰고 삭제할 수 있다', async ({ page }) => {
  await mockApi(page);
  await page.goto('/decisions/new/setup/1');
  await page.getByLabel('고민 제목', { exact: true }).fill('나중에 이어 쓸 고민');
  await page.getByRole('button', { name: '저장하고 나가기' }).click();
  await page.route('**/api/v1/decisions?*', route => route.abort('connectionrefused'));
  await page.reload();
  await expect(page.locator('.local-draft-card')).toContainText('나중에 이어 쓸 고민');
  await page.locator('.local-draft-card').getByRole('link').click();
  await expect(page.getByLabel('고민 제목', { exact: true })).toHaveValue('나중에 이어 쓸 고민');
  await page.getByRole('button', { name: '저장하고 나가기' }).click();
  await page.getByRole('button', { name: '나중에 이어 쓸 고민 임시 저장 삭제' }).click();
  await page.goto('/decisions/new/setup/1');
  await expect(page.getByLabel('고민 제목', { exact: true })).toHaveValue('');
});

test('브라우저 저장 공간이 막혀 있으면 저장 성공이나 화면 이동으로 처리하지 않는다', async ({ page }) => {
  await page.addInitScript(() => {
    const original = Storage.prototype.setItem;
    Storage.prototype.setItem = function (key, value) {
      if (this === localStorage && key.startsWith('seonbi.newDecisionDraft.')) throw new DOMException('Storage blocked', 'QuotaExceededError');
      return original.call(this, key, value);
    };
  });
  const server = await mockApi(page);
  await page.goto('/decisions/new/setup/1');
  await page.getByLabel('고민 제목', { exact: true }).fill('저장 실패 확인');
  await page.getByRole('button', { name: '저장하고 나가기' }).click();
  await expect(page.getByRole('alert')).toContainText('브라우저에 저장하지 못했어요.');
  await expect(page).toHaveURL(/new\/setup\/1$/);
  await expect(page.getByLabel('고민 제목', { exact: true })).toHaveValue('저장 실패 확인');
  expect(server.calls.some(c => c.method === 'POST')).toBe(false);
});

import { expect, test } from '@playwright/test';
import { fillFromCriteria, mockApi } from './test-api';

async function fillDraft(page: import('@playwright/test').Page) {
  await page.goto('/decisions/new/setup/1');
  await page.getByLabel('고민 제목', { exact: true }).fill('새 휴대폰 고르기');
  await page.getByLabel('어디에 사용할 건가요? · 선택').fill('사진과 동영상 촬영');
  await page.getByRole('button', { name: '후보 적기', exact: true }).click();
  await page.getByLabel('후보 1', { exact: true }).fill('개발 동아리');
  await page.getByLabel('후보 2', { exact: true }).fill('사진 동아리');
}

test('1~4단계에서 새로고침·이탈해도 입력은 복구되고 서버에는 아무것도 만들지 않는다', async ({ page }) => {
  const server = await mockApi(page);
  await fillDraft(page);
  await page.reload();
  await expect(page.getByLabel('후보 1', { exact: true })).toHaveValue('개발 동아리');
  await expect(page.getByLabel('후보 2', { exact: true })).toHaveValue('사진 동아리');
  await page.getByRole('button', { name: '‹ 고민 제목으로' }).click();
  await expect(page.getByLabel('고민 제목', { exact: true })).toHaveValue('새 휴대폰 고르기');
  await expect(page.getByLabel('어디에 사용할 건가요? · 선택')).toHaveValue('사진과 동영상 촬영');
  await page.getByRole('button', { name: '후보 적기', exact: true }).click();
  await page.getByRole('button', { name: '비교할 항목 고르기', exact: true }).click();
  // 3단계: 비교 항목 선택도 새로고침 후 이어진다
  await page.getByRole('button', { name: '흥미 비교 항목' }).click();
  await page.getByRole('button', { name: '성장 비교 항목' }).click();
  await page.reload();
  await expect(page).toHaveURL(/decisions\/new\/setup\/3/);
  await expect(page.getByRole('button', { name: '흥미 비교 항목' })).toHaveAttribute('aria-pressed', 'true');
  // 4단계: 입력한 평가값도 이탈 후 복구된다
  await page.getByRole('button', { name: '후보 정보 입력하기', exact: true }).click();
  await page.getByLabel('개발 동아리 흥미', { exact: true }).selectOption('3');
  await page.goto('/');
  await page.getByRole('button', { name: '내 결정 시작하기', exact: true }).click();
  await expect(page.getByLabel('고민 제목', { exact: true })).toHaveValue('새 휴대폰 고르기');
  await page.goto('/decisions/new/setup/4');
  await expect(page.getByLabel('개발 동아리 흥미', { exact: true })).toHaveValue('3');
  expect(server.calls.some(c => c.method !== 'GET' && !c.path.endsWith('/criteria/recommendations'))).toBe(false);
  expect(server.records.size).toBe(0);
});

test('저장에 실패하면 입력이 남아 같은 요청으로 다시 시도하고 결정은 하나만 생긴다', async ({ page }) => {
  const server = await mockApi(page);
  await fillDraft(page);
  await page.getByRole('button', { name: '비교할 항목 고르기', exact: true }).click();
  await fillFromCriteria(page);
  const fail = async (route: import('@playwright/test').Route) => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ error: { code: 'UNAVAILABLE', message: '잠시 후 다시 시도해주세요.' } }) });
  await page.route('**/api/v1/decisions/setup', fail);
  await page.getByRole('button', { name: '조건 없이 시작할게요' }).click();
  await expect(page.getByRole('alert')).toContainText('저장하지 못했어요.');
  await expect(page).toHaveURL(/decisions\/new\/setup\/5/);
  expect(server.records.size).toBe(0);
  await page.reload();
  await page.getByRole('button', { name: '‹ 후보 정보로' }).click();
  await expect(page.getByLabel('개발 동아리 흥미', { exact: true })).toHaveValue('3');
  await page.getByRole('button', { name: '다음 항목', exact: true }).click();
  await expect(page.getByLabel('개발 동아리 성장', { exact: true })).toHaveValue('1');
  await page.getByRole('button', { name: '입력 마치기', exact: true }).click();
  await page.unroute('**/api/v1/decisions/setup', fail);
  await page.getByRole('button', { name: '조건 없이 시작할게요' }).click();
  await expect(page).toHaveURL(/server-decision-1\/questions/);
  expect(server.records.size).toBe(1);
  expect(server.records.get('server-decision-1')?.title).toBe('새 휴대폰 고르기');
  // 저장에 성공했으니 임시 입력은 지워져 다음 새 결정은 빈 화면에서 시작한다
  await page.goto('/');
  await page.getByRole('button', { name: '내 결정 시작하기', exact: true }).click();
  await expect(page.getByLabel('고민 제목', { exact: true })).toHaveValue('');
});

test('응답을 받지 못해 같은 내용을 다시 보내도 같은 멱등 키라 결정은 하나만 생긴다', async ({ page }) => {
  const server = await mockApi(page, [], { dropFirstSetupResponse: true }); // 서버는 저장했지만 첫 응답이 끊긴다
  await fillDraft(page);
  await page.getByRole('button', { name: '비교할 항목 고르기', exact: true }).click();
  await fillFromCriteria(page);
  await page.getByRole('button', { name: '조건 없이 시작할게요' }).click();
  await expect(page.getByRole('alert')).toContainText('저장하지 못했어요.');
  expect(server.records.size).toBe(1);
  await page.getByRole('button', { name: '조건 없이 시작할게요' }).click();
  await expect(page).toHaveURL(/server-decision-1\/questions/);
  expect(server.records.size).toBe(1);
  const keys = server.calls.filter(c => c.path.endsWith('/decisions/setup')).map(c => c.body.setupKey);
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBe(keys[1]);
});

test('질문 시작 응답을 기다리는 동안에도 버튼이 잠겨 있어 결정이 하나만 생긴다', async ({ page }) => {
  const server = await mockApi(page);
  await fillDraft(page);
  await page.getByRole('button', { name: '비교할 항목 고르기', exact: true }).click();
  await fillFromCriteria(page);
  // 질문 시작 응답을 붙잡아 둔다. 결정 저장은 끝났지만 아직 화면은 /decisions/new 인 구간이다
  let release!: () => void, held!: () => void;
  const gate = new Promise<void>(resolve => { release = resolve; }), reached = new Promise<void>(resolve => { held = resolve; });
  await page.route('**/api/v1/decisions/*/questions/start', async route => { held(); await gate; await route.fallback(); });
  await page.getByRole('button', { name: '조건 없이 시작할게요' }).click();
  await reached; // 결정 저장이 끝나고 질문 시작 요청이 서버로 나간 시점
  expect(server.records.size).toBe(1);
  await expect(page).toHaveURL(/decisions\/new\/setup\/5/);
  await expect(page.getByRole('button', { name: '조건 없이 시작할게요' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '저장 중…' })).toBeDisabled();
  release();
  await expect(page).toHaveURL(/server-decision-1\/questions/);
  expect(server.records.size).toBe(1);
  expect(server.calls.filter(c => c.path.endsWith('/decisions/setup'))).toHaveLength(1);
});

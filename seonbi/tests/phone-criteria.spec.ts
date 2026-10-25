import { expect, test } from '@playwright/test';
import { decisionFixture, mockApi } from './test-api';

test('휴대폰 비교는 시간 대신 배터리 용량을 저장하고 mAh를 새로 입력받는다', async ({ page }, info) => {
  const d = decisionFixture(); d.title = '휴대폰 고르기';
  d.options[0].name = '아이폰 18프로'; d.options[1].name = '갤럭시 S26울트라';
  d.criteria[0] = { id: 'c1', name: '시간', type: 'numeric', direction: 'lower_better', unit: '시간', scaleMin: 1, scaleMax: 24 };
  d.values = [{ optionId: 'o1', criterionId: 'c1', value: 14 }, { optionId: 'o2', criterionId: 'c1', value: 16 }];
  const server = await mockApi(page, [d]);
  await page.route('**/criteria/c1', async route => {
    expect(route.request().method()).toBe('DELETE');
    const record = server.records.get(d.id)!;
    record.criteria = record.criteria.filter(c => c.id !== 'c1');
    record.values = record.values.filter(v => v.criterionId !== 'c1');
    await route.fulfill({ status: 204 });
  });
  await page.route('**/criteria', async route => {
    const body = route.request().postDataJSON();
    expect(body).toMatchObject({ name: '배터리 용량', type: 'numeric', unit: 'mAh', direction: 'higher_better' });
    expect(body.scaleMax).toBeUndefined();
    const criterion = { ...body, id: 'capacity' };
    server.records.get(d.id)!.criteria.push(criterion);
    await route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify(criterion) });
  });
  await page.goto(`/decisions/${d.id}/setup/3`);
  await expect(page.getByRole('button', { name: '배터리 용량 비교 항목' })).toHaveAttribute('aria-pressed', 'true');
  await expect(page.getByText('배터리 용량을 mAh 단위로 비교해요.', { exact: true })).toBeVisible();
  await expect(page.getByText('용량이 클수록 좋아요.', { exact: true })).toBeVisible();
  await page.screenshot({ path: info.outputPath('battery-capacity.png'), fullPage: true, animations: 'disabled' });
  await page.getByRole('button', { name: '후보 정보 입력하기' }).click();
  await page.getByLabel('아이폰 18프로 성장', { exact: true }).selectOption('3');
  await page.getByLabel('갤럭시 S26울트라 성장', { exact: true }).selectOption('3');
  await page.getByRole('button', { name: '다음 항목', exact: true }).click();
  await expect(page.getByLabel('아이폰 18프로 배터리 용량', { exact: true })).toHaveValue('');
  await page.getByLabel('아이폰 18프로 배터리 용량', { exact: true }).fill('5000');
  await page.getByLabel('갤럭시 S26울트라 배터리 용량', { exact: true }).fill('6000');
});


test('휴대폰의 추천 항목도 배터리 용량으로 표시하고 저장한다', async ({ page }) => {
  const d = decisionFixture(); d.title = '휴대폰 고르기'; d.criteria = []; d.values = [];
  const server = await mockApi(page, [d]);
  await page.route('**/criteria/recommendations', route => route.fulfill({ contentType: 'application/json', body: JSON.stringify({ items: [{ name: '시간', type: 'numeric', unit: '시간', direction: 'lower_better' }, { name: '성능', type: 'rating', direction: 'higher_better', levels: ['낮음', '보통', '높음'] }] }) }));
  await page.goto(`/decisions/${d.id}/setup/3`);
  await expect(page.getByRole('button', { name: '시간 비교 항목', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '배터리 용량 비교 항목' }).click();
  await page.getByRole('button', { name: '성능 비교 항목' }).click();
  await page.getByRole('button', { name: '후보 정보 입력하기' }).click();
  await expect(page.getByText('mAh', { exact: true })).toHaveCount(2);
  const created = server.calls.find(c => c.method === 'POST' && c.path.endsWith('/criteria') && c.body.name === '배터리 용량');
  expect(created?.body).toMatchObject({ unit: 'mAh', direction: 'higher_better' });
});

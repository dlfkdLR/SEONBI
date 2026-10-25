import { expect, test } from '@playwright/test';
import { decisionFixture, mockApi } from './test-api';

test('처음 방문한 사용자가 단계별 입력부터 서버 분석·확정까지 진행한다', async ({ page }, info) => {
  const server = await mockApi(page);
  await page.goto('/'); await expect(page.locator('.example-label')).toHaveCount(1);
  await page.screenshot({ path: info.outputPath('01-home.png'), fullPage: true, animations: 'disabled' });
  await page.getByRole('button', { name: '내 결정 시작하기', exact: true }).click();
  await page.getByLabel('고민 제목').fill('내 동아리 결정');
  await page.getByLabel('어디에 사용할 건가요? · 선택').fill('활동 비교');
  await page.getByRole('button', { name: '후보 적기', exact: true }).click();
  expect(server.calls.some(c => c.method === 'POST')).toBe(false);
  await page.getByLabel('후보 1', { exact: true }).fill('개발 동아리'); await page.getByLabel('후보 2', { exact: true }).fill('사진 동아리');
  await page.getByRole('button', { name: '비교할 항목 고르기', exact: true }).click();
  await expect(page).toHaveURL(/server-decision-1\/setup\/3/);
  await page.getByRole('button', { name: '흥미 비교 항목' }).click(); await page.getByRole('button', { name: '성장 비교 항목' }).click();
  await expect(page.getByRole('button', { name: '흥미 비교 항목' })).toHaveAttribute('aria-pressed', 'true');
  await page.screenshot({ path: info.outputPath('02-criteria.png'), fullPage: true, animations: 'disabled' });
  await page.getByRole('button', { name: '후보 정보 입력하기', exact: true }).click();
  await expect(page.getByLabel('개발 동아리 성장', { exact: true })).toHaveCount(0);
  await page.getByLabel('개발 동아리 흥미', { exact: true }).selectOption('3'); await page.getByLabel('사진 동아리 흥미', { exact: true }).selectOption('1');
  await page.getByRole('button', { name: '다음 항목', exact: true }).click();
  await page.getByLabel('개발 동아리 성장', { exact: true }).selectOption('1'); await page.getByLabel('사진 동아리 성장', { exact: true }).selectOption('3');
  await page.screenshot({ path: info.outputPath('03-values.png'), fullPage: true, animations: 'disabled' });
  await page.getByRole('button', { name: '입력 마치기', exact: true }).click(); await expect(page).toHaveURL(/setup\/5/);
  await page.getByRole('button', { name: '+ 꼭 필요한 조건 추가', exact: true }).click(); await page.getByLabel('조건 이름').fill('교내 활동'); await page.getByRole('button', { name: '조건 추가', exact: true }).click();
  await page.screenshot({ path: info.outputPath('04-conditions.png'), fullPage: true, animations: 'disabled' });
  await page.getByRole('button', { name: '이 조건으로 질문 시작', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('흥미와 성장 중 어떤 요소를 더 중요시하나요?');
  await page.locator('.choice-card').first().click(); await page.getByRole('button', { name: '이 선택으로 계속' }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('흥미와 성장 중 어떤 요소를 더 중요시하나요?');
  await page.getByRole('slider', { name: '상대 중요도' }).fill('2'); await page.getByRole('button', { name: '이 선택으로 계속' }).click();
  await expect(page.getByRole('heading', { name: /지금의 기준에는/ })).toContainText('사진 동아리');
  await expect(page.getByText('서버가 계산한 추천 근거', { exact: true })).toBeVisible();
  await page.screenshot({ path: info.outputPath('05-result.png'), fullPage: true, animations: 'disabled' });
  await page.getByRole('button', { name: '자세한 비교 결과 보기 ›' }).click(); await expect(page.getByRole('dialog')).toContainText('91%'); await page.getByRole('button', { name: '확인했어요' }).click();
  await page.getByRole('button', { name: '기준 바꿔보기' }).click(); await page.getByRole('slider', { name: '흥미 중요도' }).fill('80'); await expect(page.getByText('45.6점')).toBeVisible();
  await page.getByRole('link', { name: '‹ 내 결과로 돌아가기' }).click(); await page.getByRole('button', { name: '이 선택으로 정할게요', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '이 선택으로 확정', exact: true }).click(); await expect(page.locator('.notice')).toContainText('최종 선택을 서버에 기록');
  await page.getByText('다른 작업', { exact: true }).click(); await page.getByRole('button', { name: '파일로 내보내기' }).click();
  const downloaded = page.waitForEvent('download'); await page.getByRole('button', { name: 'Markdown 다운로드' }).click(); expect((await downloaded).suggestedFilename()).toBe('server-result.md'); await page.getByRole('button', { name: '닫기', exact: true }).click();
  await page.getByRole('button', { name: '복제해서 다시 고민하기' }).click(); await expect(page.getByLabel('고민 제목')).toHaveValue('내 동아리 결정 (복사본)');
  expect(server.calls.some(c => c.method === 'POST' && c.path.endsWith('/criteria/recommendations'))).toBe(true);
  expect(server.calls.find(c => c.method === 'PUT' && c.path.endsWith('/values'))?.body.items).toHaveLength(4);
  expect(server.calls.some(c => c.method === 'PUT' && c.path.endsWith('/conditions'))).toBe(true);
});

test('내 결정이 있으면 예시를 숨기고 마지막 삭제 후 예시 하나를 다시 표시한다', async ({ page }) => {
  const server = await mockApi(page, [decisionFixture()]);
  await page.goto('/'); await expect(page.locator('.example-label')).toHaveCount(0);
  await page.getByRole('link', { name: '공유 결정', exact: true }).click();
  await page.getByRole('button', { name: '서버의 결정 삭제' }).click(); await page.getByRole('button', { name: '결정 삭제', exact: true }).click();
  await expect(page.locator('.example-label')).toHaveCount(1); expect(server.records.size).toBe(0);
  await page.goto('/'); await page.reload(); await expect(page.locator('.example-label')).toHaveCount(1);
  await page.getByRole('link', { name: /예시로 살펴보기/ }).click(); await expect(page.getByRole('heading', { name: '어떤 점을 비교할까요?' })).toBeVisible();
  expect(server.calls.some(c => c.method === 'POST')).toBe(false);
});

test('예시의 평가 기준과 필수 조건을 조작해도 내 결정이나 가짜 결과를 만들지 않는다', async ({ page }) => {
  const server = await mockApi(page); await page.goto('/example');
  await page.getByRole('button', { name: '무게 비교 항목' }).click(); await expect(page.getByRole('button', { name: '무게 비교 항목' })).toHaveAttribute('aria-pressed', 'false');
  await page.getByRole('button', { name: '정보 입력 예시 보기' }).click(); await page.getByRole('button', { name: '필수 조건 예시 보기' }).click();
  const status = page.getByRole('status'); await expect(status).not.toContainText('맥북 에어 M4');
  await page.getByRole('button', { name: /Windows가 꼭 필요해요/ }).click(); await expect(status).toContainText('맥북 에어 M4');
  await page.getByLabel('예시 예산').fill('155'); await expect(status).toContainText('LG 그램 16'); await expect(status).not.toContainText('갤럭시북5 프로');
  expect(server.calls.some(c => c.method !== 'GET')).toBe(false);
});

test('501 스텁 확정은 완료 상태로 바꾸지 않는다', async ({ page }) => {
  const d = decisionFixture(); d.status = 'ANALYZED'; await mockApi(page, [d]);
  await page.route('**/final-choice', route => route.fulfill({ status: 501, contentType: 'application/json', body: JSON.stringify({ error: { code: 'NOT_IMPLEMENTED', message: '서비스 연결 전입니다.', details: { stub: true } } }) }));
  await page.goto(`/decisions/${d.id}/result`); await page.getByRole('button', { name: '이 선택으로 정할게요', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '이 선택으로 확정', exact: true }).click(); await expect(page.getByRole('dialog').getByRole('alert')).toContainText('서비스 연결 전입니다.');
  await expect(page.getByRole('dialog')).toBeVisible(); await expect(page.getByText('최종 선택을 서버에 기록했어요.', { exact: false })).toHaveCount(0);
});

test('연결 실패 시 예시를 내 기록으로 대체하지 않고 시작 화면에서 재연결한다', async ({ page }) => {
  await page.route(/\/api\/(?:v1\/|health)/, route => route.abort('connectionrefused')); await page.goto('/');
  await expect(page.getByRole('alert')).toContainText('연결하지 못했어요'); await expect(page.locator('.example-label')).toHaveCount(0); await expect(page.getByRole('button', { name: '다시 연결' })).toBeVisible();
  await page.getByRole('button', { name: '내 결정 시작하기' }).click(); await expect(page.getByLabel('고민 제목')).toBeVisible();
});

test('값 저장 415 오류 시 현재 항목과 편집값을 유지하고 재시도한다', async ({ page }) => {
  const d = decisionFixture(), server = await mockApi(page, [d]);
  const fail = async (route: import('@playwright/test').Route) => route.fulfill({ status: 415, contentType: 'application/json', body: JSON.stringify({ error: { code: 'UNSUPPORTED_MEDIA_TYPE', message: '저장 요청 형식을 확인해주세요.' } }) });
  await page.route('**/values', fail); await page.goto(`/decisions/${d.id}/setup/4`);
  await page.getByLabel('개발 동아리 흥미', { exact: true }).selectOption('2'); await page.getByRole('button', { name: '다음 항목', exact: true }).click(); await page.getByRole('button', { name: '입력 마치기', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('저장 요청 형식을 확인해주세요.'); await expect(page).toHaveURL(/setup\/4/);
  await page.getByRole('button', { name: '‹ 이전 항목', exact: true }).click(); await expect(page.getByLabel('개발 동아리 흥미', { exact: true })).toHaveValue('2');
  await page.unroute('**/values', fail); await page.getByRole('button', { name: '다음 항목', exact: true }).click(); await page.getByRole('button', { name: '입력 마치기', exact: true }).click();
  await expect(page).toHaveURL(/setup\/5/); expect(server.records.get(d.id)?.values.find(v => v.optionId === 'o1' && v.criterionId === 'c1')?.value).toBe(2);
});

test('비어 있는 현재 항목을 건너뛰지 않고, 뒤로 갔다가 와도 작성 내용을 유지한다', async ({ page }) => {
  const d = decisionFixture(); d.values = []; await mockApi(page, [d]); await page.goto(`/decisions/${d.id}/setup/4`);
  await page.getByRole('button', { name: '다음 항목', exact: true }).click(); await expect(page.getByRole('alert')).toContainText('흥미 값을 입력해주세요.');
  await page.getByLabel('개발 동아리 흥미', { exact: true }).selectOption('3'); await page.getByLabel('사진 동아리 흥미', { exact: true }).selectOption('1');
  await page.getByRole('button', { name: '다음 항목', exact: true }).click(); await page.getByRole('button', { name: '‹ 이전 항목', exact: true }).click();
  await expect(page.getByLabel('개발 동아리 흥미', { exact: true })).toHaveValue('3');
});

test('필수 조건을 끄고 건너뛰면 서버에도 빈 조건을 저장한다', async ({ page }) => {
  const d = decisionFixture(); d.conditions = [{ id: 'tag', kind: 'required_tag', label: '교내', satisfiedBy: ['o1'] }];
  const server = await mockApi(page, [d]); await page.goto(`/decisions/${d.id}/setup/5`);
  await expect(page.getByRole('status')).toContainText('개발 동아리'); await expect(page.getByRole('status')).not.toContainText('사진 동아리');
  await page.getByRole('checkbox', { name: '교내 조건 사용' }).uncheck(); await expect(page.getByRole('status')).toContainText('모든 후보');
  await page.getByRole('checkbox', { name: '교내 조건 사용' }).check(); await expect(page.getByRole('status')).not.toContainText('사진 동아리');
  await page.getByRole('button', { name: '조건 없이 시작할게요' }).click(); await expect(page).toHaveURL(/\/questions$/);
  expect(server.calls.find(c => c.method === 'PUT' && c.path.endsWith('/conditions'))?.body).toEqual({ items: [] });
});

test('PC 화면은 피그마 크기에서 가로로 넘치지 않고 예시의 선택 색상을 표시한다', async ({ page }, info) => {
  await page.setViewportSize({ width: 1440, height: 1000 }); await mockApi(page); await page.goto('/');
  await expect(page.locator('.home-preview')).toBeVisible();
  await page.screenshot({ path: info.outputPath('home-1440.png'), fullPage: true, animations: 'disabled' });
  await page.goto('/example'); await expect(page.locator('.criterion-card.selected')).toHaveCount(4);
  await expect(page.locator('.criterion-card.selected').first()).toHaveCSS('background-color', 'rgb(240, 228, 215)');
  await page.screenshot({ path: info.outputPath('criteria-1440.png'), fullPage: true, animations: 'disabled' });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole('button', { name: '정보 입력 예시 보기' }).click(); await page.getByRole('button', { name: '필수 조건 예시 보기' }).click();
  await page.screenshot({ path: info.outputPath('conditions-1440.png'), fullPage: true, animations: 'disabled' });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

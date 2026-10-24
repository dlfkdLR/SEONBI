import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

async function fresh(page: Page) {
  await page.goto('/');
  await page.evaluate(() => { localStorage.clear(); });
  await page.reload();
  await expect(page.getByRole('heading', { name: '내 결정', exact: true })).toBeVisible();
}
async function createBasic(page: Page) {
  await page.getByRole('button', { name: '새 결정 만들기 +' }).click();
  await page.getByLabel('결정 제목').fill('동아리 선택');
  await page.getByLabel('어디에, 어떻게 사용할 예정인가요?').fill('방과 후 활동을 비교해요.');
  await page.getByLabel('선택지 1', { exact: true }).fill('개발 동아리');
  await page.getByLabel('선택지 2', { exact: true }).fill('사진 동아리');
  await page.getByLabel('결정 분류').selectOption('activity');
  await page.getByRole('button', { name: '다음: 평가 기준' }).click();
  await page.getByRole('button', { name: '기본 기준 제안 보기' }).click();
  await page.getByRole('checkbox').nth(0).check();
  await page.getByRole('checkbox').nth(1).check();
  await page.getByRole('button', { name: '선택한 기준 추가' }).click();
  await page.getByRole('button', { name: '다음: 값 비교' }).click();
  await page.getByLabel('개발 동아리 흥미', { exact: true }).selectOption('5');
  await page.getByLabel('개발 동아리 성장 가능성', { exact: true }).selectOption('3');
  await page.getByLabel('사진 동아리 흥미', { exact: true }).selectOption('3');
  await page.getByLabel('사진 동아리 성장 가능성', { exact: true }).selectOption('5');
}

test('로그인 없이 예시 목록과 결과, 시뮬레이션이 열리고 내보낼 수 있다', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message));
  await fresh(page);
  await expect(page.locator('.history-row')).toHaveCount(1);
  await expect(page.getByRole('link', { name: /로그인|계정|회원가입/ })).toHaveCount(0);
  await page.screenshot({ path: 'tmp/qa/history.png', fullPage: true });
  await page.getByRole('link', { name: /새 노트북 고르기/ }).click();
  await expect(page.getByRole('heading', { name: /지금의 기준에는/ })).toBeVisible();
  const resultURL = page.url();
  await page.screenshot({ path: 'tmp/qa/result.png', fullPage: true });
  await page.getByRole('button', { name: '기준 바꿔보기' }).click();
  await expect(page.getByRole('heading', { name: '우선순위가 달라지면, 선택도 달라질까요?' })).toBeVisible();
  await page.getByRole('slider', { name: /^가격 / }).fill('80');
  await expect(page.locator('.weight-control strong').first()).toHaveText('80.0%');
  const percentages = await page.locator('.weight-control strong').allTextContents();
  expect(percentages.reduce((a, x) => a + parseFloat(x), 0)).toBeCloseTo(100, 0);
  await page.screenshot({ path: 'tmp/qa/simulation.png', fullPage: true });
  await page.getByRole('button', { name: '원래 기준으로 되돌리기' }).click();
  await expect(page.locator('.weight-control strong').first()).not.toHaveText('80.0%');
  await page.getByRole('link', { name: '분석 결과로 돌아가기' }).click();
  expect(page.url()).toBe(resultURL);
  await page.getByRole('button', { name: '파일로 내보내기' }).click();
  const downloadPromise = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Markdown 다운로드' }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/\.md$/);
  await download.saveAs('tmp/qa/result.md');
  await page.getByRole('button', { name: '닫기', exact: true }).click();
  expect(errors).toEqual([]);
});

test('내 결정을 만들면 예시가 사라지고 마지막 결정을 삭제하면 예시 하나가 돌아온다', async ({ page }) => {
  await fresh(page);
  await createBasic(page);
  await page.getByRole('link', { name: '내 결정', exact: true }).click();
  await expect(page.locator('.history-row')).toHaveCount(1);
  await expect(page.locator('.history-row')).toContainText('동아리 선택');
  await expect(page.locator('.example-label')).toHaveCount(0);
  await page.reload();
  await expect(page.locator('.example-label')).toHaveCount(0);
  await page.getByRole('link', { name: /동아리 선택/ }).click();
  await page.getByRole('button', { name: '다음: 필수 조건' }).click();
  await page.getByRole('button', { name: '입력 끝내고 질문 시작' }).click();
  for (let i = 0; i < 2; i++) {
    await page.locator('.choice-card').first().click();
    await page.getByRole('button', { name: '이 답변으로 계속', exact: true }).click();
  }
  await expect(page.getByRole('heading', { name: /지금의 기준에는/ })).toBeVisible();
  await page.getByRole('button', { name: '삭제', exact: true }).click();
  await page.getByRole('button', { name: '결정 삭제', exact: true }).click();
  await expect(page.locator('.history-row')).toHaveCount(1);
  await expect(page.locator('.history-row')).toContainText('새 노트북 고르기');
  await expect(page.locator('.example-label')).toHaveCount(1);
  await page.reload();
  await expect(page.locator('.history-row')).toHaveCount(1);
  await expect(page.locator('.example-label')).toHaveCount(1);
});

test('새 결정의 행·열을 추가, 수정, 삭제하면 값과 이름이 유지되고 새로고침에도 남는다', async ({ page }) => {
  await fresh(page); await createBasic(page);
  await page.getByRole('button', { name: '+ 선택지 추가', exact: true }).click();
  await page.getByRole('textbox', { name: '선택지 이름', exact: true }).fill('음악 동아리');
  await page.getByRole('button', { name: '선택지 저장' }).click();
  await expect(page.getByLabel('음악 동아리 흥미', { exact: true })).toBeVisible();
  await expect(page.getByLabel('개발 동아리 흥미', { exact: true })).toHaveValue('5');
  await page.getByRole('button', { name: '+ 평가 기준 추가', exact: true }).click();
  await page.getByLabel('기준 이름').fill('활동비');
  await page.getByLabel('단위', { exact: true }).fill('만원');
  await page.getByLabel('좋은 방향').selectOption('lower_better');
  await page.getByRole('button', { name: '기준 저장' }).click();
  await expect(page.getByRole('columnheader', { name: '활동비 기준 수정' })).toBeVisible();
  await page.getByLabel('개발 동아리 활동비', { exact: true }).fill('2');
  await page.getByLabel('사진 동아리 활동비', { exact: true }).fill('5');
  await page.getByLabel('음악 동아리 활동비', { exact: true }).fill('4');
  await page.getByRole('button', { name: '개발 동아리 이름 수정' }).click();
  await page.getByRole('textbox', { name: '선택지 이름', exact: true }).fill('코딩 동아리');
  await page.getByRole('button', { name: '선택지 저장' }).click();
  await expect(page.getByLabel('코딩 동아리 활동비', { exact: true })).toHaveValue('2');
  await page.reload();
  await expect(page.getByLabel('코딩 동아리 활동비', { exact: true })).toHaveValue('2');
  await page.screenshot({ path: 'tmp/qa/values.png', fullPage: true });
  await page.getByRole('button', { name: '선택지 편집', exact: true }).click();
  await page.getByRole('button', { name: '음악 동아리 삭제' }).click();
  await page.getByRole('button', { name: '03 값 비교' }).click();
  await expect(page.getByLabel('음악 동아리 활동비', { exact: true })).toHaveCount(0);
  await expect(page.getByLabel('코딩 동아리 활동비', { exact: true })).toHaveValue('2');
  await page.getByRole('button', { name: '평가 기준 편집', exact: true }).click();
  await page.getByRole('button', { name: '활동비 삭제', exact: true }).click();
  await page.getByRole('button', { name: '기준 삭제', exact: true }).click();
  await page.getByRole('button', { name: '다음: 값 비교' }).click();
  await expect(page.getByRole('columnheader', { name: '활동비 기준 수정' })).toHaveCount(0);
  await expect(page.getByLabel('코딩 동아리 흥미', { exact: true })).toHaveValue('5');
});

test('입력 검증, 필수 조건, 질문 수정, 확정 잠금 및 복제가 동작한다', async ({ page }) => {
  await fresh(page);
  await page.getByRole('button', { name: '새 결정 만들기 +' }).click();
  await page.getByRole('button', { name: '다음: 평가 기준' }).click();
  await expect(page.getByRole('alert')).toContainText('결정 제목');
  await page.getByRole('link', { name: '내 결정', exact: true }).click();
  await createBasic(page);
  await page.getByRole('button', { name: '다음: 필수 조건' }).click();
  await page.getByRole('button', { name: '+ 필수 조건 추가', exact: true }).click();
  await page.getByLabel('조건 이름').fill('교내 활동');
  await page.getByRole('checkbox', { name: '사진 동아리' }).uncheck();
  await page.getByRole('button', { name: '조건 추가', exact: true }).click();
  await page.getByRole('button', { name: '입력 끝내고 질문 시작' }).click();
  await expect(page.getByRole('alert')).toContainText('2개 이상');
  await page.getByRole('checkbox', { name: '사진 동아리' }).check();
  await page.getByRole('button', { name: '입력 끝내고 질문 시작' }).click();
  await expect(page.locator('.choice-card')).toHaveCount(2);
  await page.screenshot({ path: 'tmp/qa/questions.png', fullPage: true });
  await page.locator('.choice-card').first().click();
  await page.getByRole('button', { name: '이 답변으로 계속', exact: true }).click();
  // With two criteria, follow the stated preference with a concrete situation
  // rather than asking the same importance comparison again as a slider.
  await expect(page.getByRole('heading', { level: 1 })).toContainText('어떤 조합');
  await page.getByRole('button', { name: '이전 답변 수정' }).click();
  await page.locator('.answer-row').first().getByRole('button', { name: '수정' }).click();
  await page.getByRole('dialog').locator('.choice-card').last().click();
  await page.getByRole('button', { name: '수정한 답변 저장' }).click();
  await page.locator('.choice-card').first().click();
  await page.getByRole('button', { name: '이 답변으로 계속', exact: true }).click();
  await expect(page.getByRole('heading', { name: /지금의 기준에는/ })).toBeVisible();
  await page.getByRole('button', { name: '이 선택으로 확정', exact: true }).click();
  await page.getByLabel('최종 선택').selectOption({ label: '사진 동아리' });
  await page.getByLabel('결정 메모 · 선택').fill('성장 가능성을 우선했어요.');
  await page.getByRole('dialog').getByRole('button', { name: '이 선택으로 확정', exact: true }).click();
  await expect(page.locator('.notice')).toContainText('최종 선택 · 사진 동아리');
  await expect(page.getByRole('button', { name: '결정 수정', exact: true })).toHaveCount(0);
  await page.reload();
  await expect(page.locator('.notice')).toContainText('성장 가능성을 우선했어요.');
  await page.locator('.result-values summary').click();
  await page.getByRole('link', { name: '구성 내용 보기' }).click();
  await expect(page.getByLabel('개발 동아리 흥미', { exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '결과로 돌아가기' }).click();
  await page.getByRole('button', { name: '복제해서 다시 고민하기' }).click();
  await expect(page.getByLabel('결정 제목')).toHaveValue('동아리 선택 (복사)');
  await expect(page.getByLabel('선택지 1', { exact: true })).toBeEnabled();
});

import { expect, test } from '@playwright/test';

test('건너뛰어도 동일한 가격·배터리 질문을 반복하지 않고 질문 상한에서 결과를 보여준다', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: '새 결정 만들기 +' }).click();
  await page.getByLabel('결정 제목', { exact: true }).fill('노트북 비교');
  await page.getByLabel('선택지 1', { exact: true }).fill('노트북 A');
  await page.getByLabel('선택지 2', { exact: true }).fill('노트북 B');
  await page.getByLabel('결정 분류').selectOption('purchase');
  await page.getByRole('button', { name: '다음: 평가 기준' }).click();
  await page.getByRole('button', { name: '기본 기준 제안 보기' }).click();
  for (const box of await page.getByRole('dialog').getByRole('checkbox').all()) await box.check();
  await page.getByRole('button', { name: '선택한 기준 추가' }).click();
  await page.getByRole('button', { name: '다음: 값 비교' }).click();
  for (const [name, price, performance, weight, battery] of [['노트북 A', '170', '5', '1.3', '10'], ['노트북 B', '150', '3', '1.1', '16']]) {
    await page.getByLabel(`${name} 가격`, { exact: true }).fill(price);
    await page.getByLabel(`${name} 성능`, { exact: true }).selectOption(performance);
    await page.getByLabel(`${name} 무게`, { exact: true }).fill(weight);
    await page.getByLabel(`${name} 배터리`, { exact: true }).fill(battery);
  }
  await page.getByRole('button', { name: '다음: 필수 조건' }).click();
  await page.getByRole('button', { name: '입력 끝내고 질문 시작' }).click();
  const titles = new Set<string>();
  for (let i = 0; i < 8; i++) {
    await expect(page).toHaveURL(/\/questions$/);
    const title = await page.getByRole('heading', { level: 1 }).innerText();
    expect(titles.has(title), `반복 질문: ${title}`).toBe(false);
    titles.add(title);
    await page.getByRole('button', { name: '잘 모르겠어요', exact: true }).click();
    await page.getByRole('button', { name: '이 답변으로 계속', exact: true }).click();
    if (i < 7) await expect(page.getByRole('heading', { level: 1 })).not.toHaveText(title);
  }
  await expect(page).toHaveURL(/\/result$/);
  expect(titles.size).toBe(8);
});

test('이전에 저장된 중복 대기 질문은 새로고침 시 새로운 질문으로 바꾼다', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('link', { name: /새 노트북 고르기/ }).click();
  const result = page.url(), id = result.split('/decisions/')[1].split('/')[0];
  const oldTitle = await page.evaluate(decisionId => {
    const items = JSON.parse(localStorage.getItem('seonbi.decisions.v1')!);
    const d = items.find((x: { id: string }) => x.id === decisionId);
    const old = d.answers[0].question;
    d.status = 'QUESTIONING';
    d.pendingQuestion = { ...old, id: 'old-repeated-pending', type: 'ab_criteria' };
    localStorage.setItem('seonbi.decisions.v1', JSON.stringify(items));
    const names = old.criteria.map((id: string) => d.criteria.find((c: { id: string }) => c.id === id).name);
    return names;
  }, id);
  await page.goto(result.replace(/\/result$/, '/questions'));
  await expect.poll(() => page.evaluate(decisionId => {
    const d = JSON.parse(localStorage.getItem('seonbi.decisions.v1')!).find((x: { id: string }) => x.id === decisionId);
    return d.pendingQuestion?.id;
  }, id)).not.toBe('old-repeated-pending');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  expect(oldTitle).toHaveLength(2);
});

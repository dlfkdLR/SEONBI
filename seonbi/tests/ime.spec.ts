import { expect, test } from '@playwright/test';
import { mockApi } from './test-api';

for (const label of ['결정 제목', '어디에, 어떻게 사용할 예정인가요?', '선택지 1']) {
  test(`${label}: 실제 브라우저 IME 조합 중 값을 덮어쓰지 않고 완성한 한글을 저장한다`, async ({ page, context }) => {
    await mockApi(page);
    await page.goto('/decisions/new/setup/1');
    const input = page.getByRole('textbox', { name: label, exact: true });
    await input.focus();
    // CDP creates a real Chromium composition session. Track JS value assignments:
    // React must not restore a stale cache value while the native IME owns the text.
    await input.evaluate(element => {
      const descriptor = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!;
      let composing = false;
      const writes: string[] = [];
      Object.defineProperty(window, '__imeWrites', { value: writes, configurable: true });
      element.addEventListener('compositionstart', () => { composing = true; });
      element.addEventListener('compositionend', () => { composing = false; });
      Object.defineProperty(element, 'value', {
        configurable: true,
        get() { return descriptor.get!.call(this); },
        set(value: string) { if (composing) writes.push(value); descriptor.set!.call(this, value); },
      });
    });
    const cdp = await context.newCDPSession(page);
    for (const text of ['ㅎ', '하', '한']) {
      await cdp.send('Input.imeSetComposition', { text, selectionStart: text.length, selectionEnd: text.length });
      await expect(input).toHaveValue(text);
    }
    expect(await page.evaluate(() => (window as unknown as { __imeWrites: string[] }).__imeWrites)).toEqual([]);
    await cdp.send('Input.insertText', { text: '한' });
    for (const text of ['ㄱ', '그', '글']) await cdp.send('Input.imeSetComposition', { text, selectionStart: text.length, selectionEnd: text.length });
    await cdp.send('Input.insertText', { text: '글' });
    await expect(input).toHaveValue('한글');
    await input.press('Tab');
    for (const [name,value] of [['결정 제목','다른 제목'],['선택지 1','개발'],['선택지 2','사진']]) if (name !== label) await page.getByRole('textbox',{name,exact:true}).fill(value);
    await page.getByRole('button',{name:'변경사항 저장',exact:true}).click();
    await expect(page).toHaveURL(/server-decision-1/);
    await page.reload();
    await expect(page.getByRole('textbox', { name: label, exact: true })).toHaveValue('한글');
  });
}

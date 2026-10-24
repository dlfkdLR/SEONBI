/** Attach a Korean particle to a dynamic criterion or option name. */
export function withParticle(text: string, consonant: string, vowel: string): string {
  let last = text.trim().at(-1) ?? '';
  if (/\d/.test(last)) last = '영일이삼사오육칠팔구'[Number(last)];
  const code = last.charCodeAt(0);
  const hasFinal = code >= 0xAC00 && code <= 0xD7A3 ? (code - 0xAC00) % 28 !== 0 : /[lmnr]$/i.test(last);
  return text + (hasFinal ? consonant : vowel);
}

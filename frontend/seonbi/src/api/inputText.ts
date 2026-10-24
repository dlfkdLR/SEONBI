/** The persisted free-text field, including a clarification, is limited to 500 characters. */
export function clarifiedInputText(originalText: string, clarification: string): string {
  const combined = `${originalText.trim()}\n추가 설명: ${clarification.trim()}`;
  if (!originalText.trim() || !clarification.trim()) throw new RangeError('입력과 추가 설명을 작성해주세요.');
  if (combined.length > 500) throw new RangeError('추가 설명을 포함한 입력은 500자 이하여야 합니다.');
  return combined;
}

export function validateInputText(text: string): string {
  const normalized = text.trim();
  if (!normalized) throw new RangeError('내용을 입력해주세요.');
  if (normalized.length > 500) throw new RangeError('입력은 500자 이하여야 합니다.');
  return normalized;
}

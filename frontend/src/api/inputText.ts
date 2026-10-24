/** Build the retried prompt and enforce the persisted free-text input limit. */
export function clarifiedInputText(originalText: string, clarification: string): string {
  const combined = `${originalText.trim()}\n추가 설명: ${clarification.trim()}`;
  if (combined.length > 500) {
    throw new RangeError("추가 설명을 포함한 입력은 500자 이하여야 합니다.");
  }
  return combined;
}

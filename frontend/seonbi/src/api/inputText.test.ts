import { describe, expect, it } from 'vitest';
import { clarifiedInputText, validateInputText } from './inputText';

describe('자유 입력 길이 검증', () => {
  it('500자 입력과 추가 설명 500자를 허용한다', () => {
    expect(validateInputText('가'.repeat(500))).toHaveLength(500);
    expect(clarifiedInputText('가'.repeat(479), '나'.repeat(13))).toHaveLength(500);
  });
  it('500자를 넘는 입력은 거절한다', () => {
    expect(() => validateInputText('가'.repeat(501))).toThrow(RangeError);
    expect(() => clarifiedInputText('가'.repeat(480), '나'.repeat(13))).toThrow(RangeError);
  });
  it('추가 설명이 500자를 넘으면 API 호출 전에 검증할 수 있다', () => {
    expect(() => clarifiedInputText('원문', '설명'.repeat(300))).toThrow(RangeError);
  });
});

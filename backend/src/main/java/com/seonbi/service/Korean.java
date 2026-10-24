package com.seonbi.service;

/** 한국어 조사 붙이기. 마지막 글자의 읽는 소리로 받침을 판단한다. */
public final class Korean {

  // 숫자의 읽는 소리 받침 여부 (영 일 이 삼 사 오 육 칠 팔 구)
  private static final boolean[] DIGIT_BATCHIM = {
    true, true, false, true, false, false, true, true, true, false
  };
  // 알파벳을 읽는 소리 중 받침이 있는 것 (엘 엠 엔 알)
  private static final String LETTER_BATCHIM = "LMNR";
  private static final String RIEUL_LETTERS = "LR"; // 엘, 알 — ㄹ 받침
  private static final String RIEUL_DIGITS = "178"; // 일, 칠, 팔

  private Korean() {}

  /** 받침 있음 / ㄹ 받침 여부. */
  private record Sound(boolean batchim, boolean rieul) {}

  private static Sound lastSound(String word) {
    for (int i = word.length() - 1; i >= 0; i--) {
      char ch = word.charAt(i);
      if (ch >= '가' && ch <= '힣') {
        int jong = (ch - 0xAC00) % 28;
        return new Sound(jong != 0, jong == 8);
      }
      if (ch >= '0' && ch <= '9') {
        return new Sound(DIGIT_BATCHIM[ch - '0'], RIEUL_DIGITS.indexOf(ch) >= 0);
      }
      char up = Character.toUpperCase(ch);
      if (up >= 'A' && up <= 'Z') {
        return new Sound(LETTER_BATCHIM.indexOf(up) >= 0, RIEUL_LETTERS.indexOf(up) >= 0);
      }
      if (Character.isLetterOrDigit(ch)) {
        return new Sound(false, false);
      }
      // 괄호·따옴표·공백 등은 건너뛴다
    }
    return new Sound(false, false);
  }

  /** word 뒤에 이/가를 붙여 돌려준다. 예: 가격 → 가격이, 무게 → 무게가. */
  public static String iGa(String word) {
    return word + (lastSound(word).batchim() ? "이" : "가");
  }

  /** 을/를. 예: 가격 → 가격을, 무게 → 무게를. */
  public static String eulReul(String word) {
    return word + (lastSound(word).batchim() ? "을" : "를");
  }

  /** (으)로. ㄹ 받침은 받침이 없는 것처럼 '로'. 예: 갤럭시북 → 갤럭시북으로, 서울 → 서울로. */
  public static String euRo(String word) {
    Sound s = lastSound(word);
    return word + (s.batchim() && !s.rieul() ? "으로" : "로");
  }

  /** 받침이 있으면 true (따옴표로 감싼 라벨 뒤 조사 선택에 쓴다). */
  static boolean hasBatchim(String word) {
    return lastSound(word).batchim();
  }
}

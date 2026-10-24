package com.seonbi.engine;

/** 답변. "잘 모르겠어요"는 {@link #DONT_KNOW}. */
public sealed interface Response {

  Response DONT_KNOW = new Unknown();

  /** 슬라이더 응답 −5..+5. */
  record Slider(int value) implements Response {}

  /** A/B 질문 응답. */
  record Choice(boolean a) implements Response {
    public static final Choice A = new Choice(true);
    public static final Choice B = new Choice(false);
  }

  /** "잘 모르겠어요". 정보가 없는 답변이다. */
  record Unknown() implements Response {}
}

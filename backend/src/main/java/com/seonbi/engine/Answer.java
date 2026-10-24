package com.seonbi.engine;

/**
 * 유효한 답변 하나 (is_valid=false 답변은 넘기지 않는다). origin이 FREE_TEXT인 답변은 자유 입력(29번)으로 만든
 * 가상 슬라이더 답변이다. 추정에는 쓰지만 질문 수·질문 순서 규칙에는 세지 않는다(4.2, 7.3 29번).
 */
public record Answer(QuestionSpec spec, Response response, Origin origin) {

    public enum Origin { ENGINE, FREE_TEXT }

    public Answer(QuestionSpec spec, Response response) {
        this(spec, response, Origin.ENGINE);
    }
}

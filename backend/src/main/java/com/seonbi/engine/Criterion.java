package com.seonbi.engine;

/** 엔진이 보는 평가 기준. levels는 단계형의 단계 수 L, 척도는 사용자가 지정했을 때만 채운다. */
public record Criterion(
        String id,
        Type type,
        Direction direction,
        Integer levels,
        InputMode inputMode,
        Double scaleMin,
        Double scaleMax) {

    public enum Type { NUMERIC, RATING, BOOLEAN }

    public enum Direction { HIGHER_BETTER, LOWER_BETTER, TRUE_BETTER }

    public enum InputMode { SCORE, RANK }

    public Criterion(String id, Type type, Direction direction, Integer levels) {
        this(id, type, direction, levels, InputMode.SCORE, null, null);
    }

    public boolean isRank() {
        return type == Type.RATING && inputMode == InputMode.RANK;
    }
}

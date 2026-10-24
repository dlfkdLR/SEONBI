package com.seonbi.engine;

import java.util.List;

/** 질문 종료 판단과 질문 수 상한 (개발기획서 5.10, 4.2 추가 질문 라운드). */
public final class Stopping {

    public static final int MIN_QUESTIONS = 3;
    public static final double CONFIDENT_P_BEST = 0.85;
    public static final double LOW_INFO_GAIN_BITS = 0.03;
    public static final int MAX_QUESTIONS_FLOOR = 6;
    public static final int MAX_QUESTIONS_CAP = 12;
    public static final double LOW_CONFIDENCE_P_BEST = 0.55; // 이보다 낮게 끝나면 결과 화면에 배너
    public static final int EXTRA_ROUND_QUESTIONS = 3; // 추가 질문 라운드에서 더 묻는 최대 문항 수 (4.2)

    public enum StopReason { CONFIDENT, LOW_INFO_GAIN, MAX_QUESTIONS, USER_STOPPED }

    /** 종료 여부. reason은 종료일 때만 있다. */
    public record Decision(boolean stop, StopReason reason) {
        static final Decision CONTINUE = new Decision(false, null);
    }

    private Stopping() {}

    /** 첫 라운드의 질문 수 상한. 5.10: max(6, 2K), 상한 12. */
    public static int maxQuestions(int k) {
        return Math.min(MAX_QUESTIONS_CAP, Math.max(MAX_QUESTIONS_FLOOR, 2 * k));
    }

    /** 추가 질문 라운드를 시작할 때의 상한 = 현재 답변 수 + 3 (4.2). decisions.question_limit에 저장한다. */
    public static int extraRoundLimit(int answered) {
        return answered + EXTRA_ROUND_QUESTIONS;
    }

    /** QUESTIONING 중 기준을 추가했을 때의 상한 = max(현재 상한, 현재 답변 수 + 3) (4.2). */
    public static int raisedLimit(int currentLimit, int answered) {
        return Math.max(currentLimit, answered + EXTRA_ROUND_QUESTIONS);
    }

    /** 질문 수로 세는 답변 수. 자유 입력으로 만든 가상 답변(FREE_TEXT)은 뺀다. */
    public static int countedAnswers(List<Answer> history) {
        return (int) history.stream().filter(a -> a.origin() == Answer.Origin.ENGINE).count();
    }

    /**
     * 질문을 끝낼지 판단한다. history는 지금까지의 답변("잘 모르겠어요" 포함, 가상 답변은 세지 않음), bestEig는
     * {@link QuestionSelector#bestInfoGain} 값이다. questionLimit이 null이면 첫 라운드 상한 maxQuestions(K)를 쓴다.
     * roundStart는 추가 질문 라운드를 시작할 때의 답변 수이며, 값이 있으면 라운드 안에서 최소 1문항을 묻기 전에는
     * 끝내지 않고(USER_STOPPED 제외) CONFIDENT로는 끝내지 않는다.
     */
    public static Decision shouldStop(
            Posterior post,
            double[][] v,
            List<Answer> history,
            double bestEig,
            boolean userStopped,
            Integer questionLimit,
            Integer roundStart) {
        if (userStopped) {
            return new Decision(true, StopReason.USER_STOPPED);
        }
        int answered = countedAnswers(history);
        boolean extraRound = roundStart != null;
        if (extraRound && answered <= roundStart) {
            return Decision.CONTINUE;
        }
        // 상한은 저장된 값이 있으면 그걸(추가 라운드), 없으면 기준 수로 계산한 첫 라운드 상한을 쓴다
        int limit = questionLimit != null ? questionLimit : maxQuestions(v[0].length);
        double top = 0;
        for (double q : Posterior.pBest(post.p(), Posterior.bestShares(post.w(), v))) {
            top = Math.max(top, q);
        }
        if (!extraRound && answered >= MIN_QUESTIONS && top >= CONFIDENT_P_BEST) {
            return new Decision(true, StopReason.CONFIDENT);
        }
        if (answered >= MIN_QUESTIONS && bestEig < LOW_INFO_GAIN_BITS) {
            return new Decision(true, StopReason.LOW_INFO_GAIN);
        }
        if (answered >= limit) {
            return new Decision(true, StopReason.MAX_QUESTIONS);
        }
        return Decision.CONTINUE;
    }

    public static boolean isLowConfidence(double topPBest) {
        return topPBest < LOW_CONFIDENCE_P_BEST;
    }
}

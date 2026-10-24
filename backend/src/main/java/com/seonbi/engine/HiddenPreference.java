package com.seonbi.engine;

import com.seonbi.engine.Likelihood.PreferenceKind;
import com.seonbi.engine.QuestionSpec.ScenarioSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 진술 선호와 드러난 선호 비교 (개발기획서 5.6). */
public final class HiddenPreference {

  public static final double STATED_THRESHOLD = 0.75; // 진술 쪽 P(w_a > w_b) ≥ 이 값
  public static final double REVEALED_THRESHOLD = 0.25; // 드러난 쪽 P(w_a > w_b) ≤ 이 값
  public static final int MIN_SCENARIOS_PER_PAIR = 2;

  /** statedOver: 말로는 더 중요하다고 한 기준, revealedOver: 실제 선택에서 더 중시한 기준. */
  public record Mismatch(
      int statedOver, int revealedOver, double statedP, double revealedP, int nScenarios) {}

  private HiddenPreference() {}

  /**
   * w는 사전분포 입자({@link Posterior#priorParticles}). 같은 입자에 답변 묶음만 달리해 두 번 재가중한다. 상황형 답변이 기준 쌍당 2개 이상인
   * 쌍만 판단한다. 순위 입력 기준은 상황형이 없으므로 자연히 빠진다.
   */
  public static List<Mismatch> detect(double[][] w, List<Answer> answers) {
    List<Answer> answered =
        answers.stream().filter(a -> !(a.response() instanceof Response.Unknown)).toList();
    List<Answer> stated =
        answered.stream()
            .filter(a -> Likelihood.preferenceKind(a.spec()) == PreferenceKind.STATED)
            .toList();
    List<Answer> revealed =
        answered.stream()
            .filter(a -> Likelihood.preferenceKind(a.spec()) == PreferenceKind.REVEALED)
            .toList();
    if (stated.isEmpty() || revealed.isEmpty()) {
      return List.of();
    }
    double[] pStated = Posterior.reweight(w, stated);
    double[] pRevealed = Posterior.reweight(w, revealed);

    Map<List<Integer>, Integer> perPair =
        new TreeMap<>(
            (x, y) -> {
              int c = Integer.compare(x.get(0), y.get(0));
              return c != 0 ? c : Integer.compare(x.get(1), y.get(1));
            });
    for (Answer a : revealed) {
      if (a.spec() instanceof ScenarioSpec s) {
        perPair.merge(List.of(Math.min(s.a(), s.b()), Math.max(s.a(), s.b())), 1, Integer::sum);
      }
    }
    List<Mismatch> out = new ArrayList<>();
    for (Map.Entry<List<Integer>, Integer> e : perPair.entrySet()) {
      int i = e.getKey().get(0);
      int j = e.getKey().get(1);
      int n = e.getValue();
      if (n < MIN_SCENARIOS_PER_PAIR) {
        continue;
      }
      double s = 0;
      double r = 0;
      for (int x = 0; x < w.length; x++) {
        if (w[x][i] > w[x][j]) {
          s += pStated[x];
          r += pRevealed[x];
        }
      }
      if (s >= STATED_THRESHOLD && r <= REVEALED_THRESHOLD) {
        out.add(new Mismatch(i, j, s, r, n));
      } else if (1 - s >= STATED_THRESHOLD && 1 - r <= REVEALED_THRESHOLD) {
        out.add(new Mismatch(j, i, 1 - s, 1 - r, n));
      }
    }
    return out;
  }
}

package com.seonbi.engine.sim;

import com.seonbi.engine.Answer;
import com.seonbi.engine.DecisionAnalysis;
import com.seonbi.engine.Posterior;
import com.seonbi.engine.QuestionSelector;
import com.seonbi.engine.QuestionSpec;
import com.seonbi.engine.Stopping;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;

/**
 * 질문 수·종료 기준 보정 실험 (개선사항 3, 개발기획서 5.11 "파일럿 보정").
 *
 * <p>실제 사용자 파일럿 데이터가 아직 없어서, 가상 사용자로 종료 규칙 상수를 훑어 본다. 질문 선택(EIG)은 종료 규칙과 무관하므로 사용자마다 끝까지(질문 12개) 한
 * 번만 돌려 시점별 값(최고 확률·정보 이득·정확도)을 기록한 뒤, 여러 종료 규칙을 그 기록 위에서 다시 적용한다. 같은 사용자·같은 질문열에 규칙만 바꿔 비교하므로
 * 공정하다.
 *
 * <p>튜닝용 시드와 검증용 시드를 따로 두어, 튜닝 시드에서 고른 규칙이 새 사용자에게도 통하는지 확인한다.
 *
 * <p>실행: {@code ./gradlew calibrate -Pargs="--users 150"}
 */
public final class Calibration {

  static final int N_MAX = 12;

  /** 한 사용자의 질문열 기록. 인덱스 n = 답변 n개 시점(0은 사전분포). */
  record Trace(double[] topP, double[] eig, double[] hit, double[] mae) {}

  record Rule(String name, int minQ, double conf, double lowEig, int limit) {}

  record Outcome(double questions, double hit, double mae, double confidentShare, double topP) {}

  static Trace run(
      Simulation.Problem p, double[] trueW, long fitSeed, RandomGenerator rng, double noise) {
    int k = trueW.length;
    List<Answer> answers = new ArrayList<>();
    List<QuestionSpec> history = new ArrayList<>();
    double[] topP = new double[N_MAX + 1], eig = new double[N_MAX + 1];
    double[] hit = new double[N_MAX + 1], mae = new double[N_MAX + 1];
    int topTrue = DecisionAnalysis.ranking(trueW, p.v()).get(0);
    int last = 0;
    for (int n = 0; n <= N_MAX; n++) {
      Posterior post = Posterior.fit(fitSeed, k, answers);
      double[] pb = Posterior.pBest(post.p(), Posterior.bestShares(post.w(), p.v()));
      double top = 0;
      for (double q : pb) top = Math.max(top, q);
      double[] mean = post.mean();
      double m = 0;
      for (int i = 0; i < k; i++) m += Math.abs(mean[i] - trueW[i]);
      topP[n] = top;
      eig[n] = QuestionSelector.bestInfoGain(post, p.v(), p.criteria(), p.scales());
      hit[n] = DecisionAnalysis.ranking(mean, p.v()).get(0) == topTrue ? 1 : 0;
      mae[n] = m / k;
      last = n;
      if (n == N_MAX) break;
      QuestionSpec q =
          QuestionSelector.nextQuestion(
                  post, p.v(), p.criteria(), history, p.scales(), coveredOf(answers))
              .orElse(null);
      if (q == null) break;
      history.add(q);
      answers.add(new Answer(q, Simulation.sampleResponse(q, trueW, rng, noise)));
    }
    // 질문이 더 없으면 마지막 값을 이어 붙인다(그 뒤에는 어떤 규칙이든 멈춘다)
    for (int n = last + 1; n <= N_MAX; n++) {
      topP[n] = topP[last];
      eig[n] = 0;
      hit[n] = hit[last];
      mae[n] = mae[last];
    }
    return new Trace(topP, eig, hit, mae);
  }

  private static java.util.Set<Integer> coveredOf(List<Answer> answers) {
    java.util.Set<Integer> out = new java.util.HashSet<>();
    for (Answer a : answers) {
      if (!(a.response() instanceof com.seonbi.engine.Response.Unknown)) {
        out.add(a.spec().a());
        out.add(a.spec().b());
      }
    }
    return out;
  }

  /** 규칙을 기록에 적용해 멈추는 시점을 찾는다(5.10과 같은 순서: 확신 → 정보 이득 → 상한). */
  static int stopAt(Trace t, Rule r) {
    for (int n = 1; n <= N_MAX; n++) {
      if (n >= r.minQ() && t.topP()[n] >= r.conf()) return n;
      if (n >= r.minQ() && t.eig()[n] < r.lowEig()) return n;
      if (n >= r.limit()) return n;
    }
    return N_MAX;
  }

  static Outcome apply(List<Trace> traces, Rule r) {
    double q = 0, h = 0, m = 0, c = 0, tp = 0;
    for (Trace t : traces) {
      int n = stopAt(t, r);
      q += n;
      h += t.hit()[n];
      m += t.mae()[n];
      tp += t.topP()[n];
      if (t.topP()[n] >= r.conf() && n >= r.minQ()) c++;
    }
    int u = traces.size();
    return new Outcome(q / u, h / u, m / u, c / u, tp / u);
  }

  static List<Trace> traces(String scenario, int users, long seed, double noise) {
    Simulation.Problem p = Simulation.problem(scenario);
    int k = p.v()[0].length;
    int sid = Simulation.SCENARIO_IDS.get(scenario);
    return IntStream.range(0, users)
        .parallel()
        .mapToObj(
            i -> {
              double[] trueW =
                  Posterior.priorParticles(seed * 1_000_003L + sid * 10_007L + i * 31L, k, 1)[0];
              long fitSeed = (seed * 1_000_003L + sid * 10_007L + i) % (1L << 31);
              RandomGenerator rng =
                  new Well19937c(seed * 1_000_003L + sid * 10_007L + i * 31L + 977L);
              return run(p, trueW, fitSeed, rng, noise);
            })
        .toList();
  }

  static List<Rule> grid() {
    List<Rule> out = new ArrayList<>();
    out.add(
        new Rule(
            "현재(5.10)",
            Stopping.MIN_QUESTIONS,
            Stopping.CONFIDENT_P_BEST,
            Stopping.LOW_INFO_GAIN_BITS,
            10));
    for (int minQ : new int[] {2, 3, 4})
      for (double conf : new double[] {0.70, 0.75, 0.80, 0.85, 0.90, 0.95})
        for (double low : new double[] {0.01, 0.03, 0.05, 0.08})
          for (int limit : new int[] {6, 8, 10}) {
            if (minQ == Stopping.MIN_QUESTIONS
                && conf == Stopping.CONFIDENT_P_BEST
                && low == Stopping.LOW_INFO_GAIN_BITS
                && limit == 10) continue;
            out.add(new Rule("후보", minQ, conf, low, limit));
          }
    return out;
  }

  static String row(Rule r, Outcome o) {
    return String.format(
        Locale.ROOT,
        "| %s | %d | %.2f | %.2f | %d | %.2f | %.3f | %.3f | %.0f%% | %.3f |",
        r.name(),
        r.minQ(),
        r.conf(),
        r.lowEig(),
        r.limit(),
        o.questions(),
        o.hit(),
        o.mae(),
        o.confidentShare() * 100,
        o.topP());
  }

  public static void main(String[] args) {
    int users = 150;
    long tuneSeed = 42, validSeed = 1001;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--users" -> users = Integer.parseInt(args[++i]);
        case "--seed" -> tuneSeed = Long.parseLong(args[++i]);
        case "--valid-seed" -> validSeed = Long.parseLong(args[++i]);
        default -> throw new IllegalArgumentException("알 수 없는 옵션: " + args[i]);
      }
    }
    List<Rule> grid = grid();
    for (String scenario : List.of("purchase", "career")) {
      for (double noise : new double[] {0.0, 0.1, 0.2}) {
        List<Trace> tune = traces(scenario, users, tuneSeed, noise);
        List<Trace> valid = traces(scenario, users, validSeed, noise);
        System.out.printf(
            Locale.ROOT,
            "%n## %s · 노이즈 ε=%.1f · 사용자 %d명(튜닝 시드 %d / 검증 시드 %d)%n%n",
            scenario,
            noise,
            users,
            tuneSeed,
            validSeed);
        System.out.println("| 규칙 | 최소 | 확신 | 정보이득 | 상한 | 평균 질문 | 1위 적중 | MAE | 확신 종료 | 종료시 최고확률 |");
        System.out.println("|---|---|---|---|---|---|---|---|---|---|");
        // 고정 질문 수(규칙 없이 n개만 묻기)도 같이 본다
        for (int n : new int[] {2, 3, 4, 5, 6, 8, 10, 12}) {
          Rule fixed = new Rule("고정 " + n + "문항", n, 2.0, -1, n);
          System.out.println(row(fixed, apply(tune, fixed)) + " ← 튜닝");
        }
        for (Rule r : grid) {
          Outcome t = apply(tune, r);
          // 현재 규칙과 파레토 후보만 출력하려고 전부 찍어 두고 분석은 후처리에서 한다
          System.out.println(row(r, t) + " ← 튜닝");
          System.out.println(row(r, apply(valid, r)) + " ← 검증");
        }
        // 확신도 보정: 종료 시점 최고 확률이 실제 적중률과 맞는지(현재 규칙)
        Rule cur = grid.get(0);
        System.out.println("\n확신도 보정(현재 규칙, 튜닝+검증 합침): 종료 시점 최고 확률 구간별 실제 1위 적중률");
        System.out.println("| 구간 | 사용자 수 | 평균 표시 확률 | 실제 적중률 |\n|---|---|---|---|");
        List<Trace> all = new ArrayList<>(tune);
        all.addAll(valid);
        double[][] edges = {{0, .55}, {.55, .7}, {.7, .85}, {.85, .95}, {.95, 1.01}};
        for (double[] e : edges) {
          int c = 0;
          double sp = 0, sh = 0;
          for (Trace t : all) {
            int n = stopAt(t, cur);
            if (t.topP()[n] >= e[0] && t.topP()[n] < e[1]) {
              c++;
              sp += t.topP()[n];
              sh += t.hit()[n];
            }
          }
          System.out.printf(
              Locale.ROOT,
              "| %.2f–%.2f | %d | %.3f | %.3f |%n",
              e[0],
              Math.min(e[1], 1.0),
              c,
              c == 0 ? 0 : sp / c,
              c == 0 ? 0 : sh / c);
        }
      }
    }
  }
}

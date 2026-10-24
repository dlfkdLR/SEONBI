package com.seonbi.engine.sim;

import com.seonbi.engine.Answer;
import com.seonbi.engine.Criterion;
import com.seonbi.engine.DecisionAnalysis;
import com.seonbi.engine.Likelihood;
import com.seonbi.engine.Normalizer;
import com.seonbi.engine.Posterior;
import com.seonbi.engine.QuestionSelector;
import com.seonbi.engine.QuestionSpec;
import com.seonbi.engine.QuestionSpec.CriteriaABSpec;
import com.seonbi.engine.QuestionSpec.ScenarioSpec;
import com.seonbi.engine.QuestionSpec.SliderSpec;
import com.seonbi.engine.Response;
import com.seonbi.engine.Scale;
import com.seonbi.engine.Stopping;
import com.seonbi.engine.ValueKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;

/**
 * 엔진 시뮬레이션 (개발기획서 14장): EIG 질문 선택 vs 랜덤 질문 선택. 파이썬 참조 구현(engine-reference/notebooks/simulation.py)을
 * 자바 엔진으로 다시 돌린다.
 *
 * <p>가상 사용자(정답 가중치 ~ Dirichlet(1))가 엔진 자신의 우도 모델대로 답한다(noise ε이면 확률 ε로 무작위 응답). 두 전략을 같은 사용자·같은 종료
 * 규칙으로 돌려 MAE, 1위 적중률, 평균 질문 수를 비교한다. 난수 생성기가 파이썬(numpy)과 달라서 사용자별 값은 같지 않고, 평균 수준에서 비교한다.
 *
 * <p>실행: {@code ./gradlew simulate -Pargs="--scenario both --users 200 --seed 42"}
 */
public final class Simulation {

  static final int[] FIXED_COUNTS = {3, 5, 7};
  static final int QUICK_USERS = 30;
  static final int DEFAULT_USERS = 200;
  static final long DEFAULT_SEED = 42;
  static final List<String> STRATEGIES = List.of("eig", "random");
  static final Map<String, Integer> SCENARIO_IDS = Map.of("purchase", 0, "career", 1);

  /** 풀 문제: 기준, 고정된 척도, 정규화 값 행렬. */
  record Problem(List<Criterion> criteria, Map<String, Scale> scales, double[][] v) {}

  /** 평균과 표준오차. */
  public record Stat(double mean, double se) {
    static Stat of(List<Double> x) {
      int n = x.size();
      double mean = x.stream().mapToDouble(Double::doubleValue).average().orElse(0);
      double se = 0;
      if (n > 1) {
        double ss = x.stream().mapToDouble(d -> (d - mean) * (d - mean)).sum();
        se = Math.sqrt(ss / (n - 1)) / Math.sqrt(n);
      }
      return new Stat(mean, se);
    }
  }

  /** 한 시나리오의 요약. adaptive/fixed/paired는 파이썬 출력과 같은 구조다. */
  public record Summary(
      String scenario,
      int users,
      long seed,
      double noise,
      int criteria,
      int options,
      Map<String, Map<String, Stat>> adaptive,
      Map<Integer, Map<String, Map<String, Stat>>> fixed,
      Map<String, Stat> paired,
      Map<String, Map<String, Integer>> reasons) {}

  private Simulation() {}

  // ── 문제 ──────────────────────────────────────────────────────

  static Problem purchase() {
    List<Criterion> crit =
        List.of(
            new Criterion("price", Criterion.Type.NUMERIC, Criterion.Direction.LOWER_BETTER, null),
            new Criterion("weight", Criterion.Type.NUMERIC, Criterion.Direction.LOWER_BETTER, null),
            new Criterion(
                "battery", Criterion.Type.NUMERIC, Criterion.Direction.HIGHER_BETTER, null),
            new Criterion(
                "storage", Criterion.Type.NUMERIC, Criterion.Direction.HIGHER_BETTER, null),
            new Criterion("perf", Criterion.Type.RATING, Criterion.Direction.HIGHER_BETTER, 5));
    List<String> options = List.of("galaxy", "gram", "yoga", "macbook");
    double[][] rows = {
      {189, 1.23, 12, 512, 4},
      {165, 1.19, 15, 256, 3},
      {150, 1.45, 10, 512, 4},
      {240, 0.98, 18, 1024, 5}
    };
    return build(crit, options, rows);
  }

  static Problem career() {
    List<Criterion> crit =
        List.of(
            new Criterion(
                "interest",
                Criterion.Type.RATING,
                Criterion.Direction.HIGHER_BETTER,
                null,
                Criterion.InputMode.RANK,
                null,
                null),
            new Criterion("fit", Criterion.Type.RATING, Criterion.Direction.HIGHER_BETTER, 4),
            new Criterion("outlook", Criterion.Type.RATING, Criterion.Direction.HIGHER_BETTER, 5),
            new Criterion(
                "commute", Criterion.Type.NUMERIC, Criterion.Direction.LOWER_BETTER, null),
            new Criterion(
                "tuition", Criterion.Type.NUMERIC, Criterion.Direction.LOWER_BETTER, null));
    List<String> options = List.of("cs", "biz", "design", "edu");
    double[][] rows = {
      {1, 4, 5, 70, 800}, {3, 2, 3, 30, 600}, {2, 3, 3, 50, 900}, {4, 2, 2, 20, 500}
    };
    return build(crit, options, rows);
  }

  private static Problem build(List<Criterion> crit, List<String> options, double[][] rows) {
    Map<ValueKey, Double> vals = new LinkedHashMap<>();
    for (int o = 0; o < options.size(); o++) {
      for (int k = 0; k < crit.size(); k++) {
        vals.put(new ValueKey(options.get(o), crit.get(k).id()), rows[o][k]);
      }
    }
    Map<String, Scale> scales = Normalizer.freezeScales(crit, vals, List.of());
    double[][] v = Normalizer.normalize(vals, crit, scales, options, List.of()).matrix().values();
    return new Problem(crit, scales, v);
  }

  static Problem problem(String scenario) {
    return "career".equals(scenario) ? career() : purchase();
  }

  // ── 가상 사용자 ───────────────────────────────────────────────

  // 파이썬의 default_rng([seed, 시나리오, i, 스트림])처럼 네 값에서 독립된 난수열을 만든다
  private static RandomGenerator rngOf(long seed, int sid, int user, int stream) {
    long mix = seed * 1_000_003L + sid * 10_007L + user * 31L + stream * 1_000_000_007L;
    return new Well19937c(mix);
  }

  /** 무작위 기준 쌍 + 무작위 유형. 규칙·EIG는 쓰지 않는다. */
  static QuestionSpec randomQuestion(
      double[] mean,
      double[][] v,
      List<Criterion> crit,
      Map<String, Scale> scales,
      RandomGenerator rng) {
    List<Integer> active = QuestionSelector.activeCriteria(v);
    if (active.size() < 2) {
      return null;
    }
    int first = rng.nextInt(active.size());
    int second = rng.nextInt(active.size() - 1);
    if (second >= first) {
      second++;
    }
    int a = active.get(first);
    int b = active.get(second);
    List<ScenarioSpec> scenarios = QuestionSelector.buildScenarios(mean, a, b, crit, scales);
    int kinds = scenarios.isEmpty() ? 2 : 3;
    int kind = rng.nextInt(kinds);
    if (kind == 0) {
      return new SliderSpec(a, b);
    }
    if (kind == 1) {
      return new CriteriaABSpec(a, b);
    }
    return scenarios.get(rng.nextInt(scenarios.size()));
  }

  /** 정답 가중치가 응답할 확률대로 응답을 뽑는다. noise이면 확률 noise로 응답 후보 중 무작위. */
  static Response sampleResponse(
      QuestionSpec q, double[] trueW, RandomGenerator rng, double noise) {
    List<Response> options = Likelihood.responseOptions(q);
    if (noise > 0 && rng.nextDouble() < noise) {
      return options.get(rng.nextInt(options.size()));
    }
    double[] logp = Likelihood.responseLogProbs(q, new double[][] {trueW})[0];
    double total = 0;
    double[] probs = new double[logp.length];
    for (int i = 0; i < logp.length; i++) {
      probs[i] = Math.exp(logp[i]);
      total += probs[i];
    }
    double u = rng.nextDouble() * total;
    double acc = 0;
    for (int i = 0; i < probs.length; i++) {
      acc += probs[i];
      if (u < acc) {
        return options.get(i);
      }
    }
    return options.get(options.size() - 1);
  }

  private static Set<Integer> covered(List<Answer> answers) {
    Set<Integer> out = new HashSet<>();
    for (Answer a : answers) {
      if (!(a.response() instanceof Response.Unknown)) {
        out.add(a.spec().a());
        out.add(a.spec().b());
      }
    }
    return out;
  }

  /** 한 사용자의 결과: 종료 시점과 고정 질문 수 시점의 MAE·1위 적중. */
  record UserResult(int stopN, String reason, Map<String, double[]> scores) {}

  /** 한 사용자의 질문 궤적. 답변 n개 시점의 사후 평균을 모두 기록해 두고 종료 시점·고정 개수 시점을 고른다. */
  static UserResult runUser(
      String strategy,
      double[] trueW,
      Problem problem,
      long fitSeed,
      RandomGenerator rng,
      double noise) {
    int k = trueW.length;
    int maxFixed = FIXED_COUNTS[FIXED_COUNTS.length - 1];
    int nMax = Math.max(maxFixed, Stopping.maxQuestions(k));
    List<Answer> answers = new ArrayList<>();
    List<QuestionSpec> history = new ArrayList<>();
    Map<Integer, double[]> means = new LinkedHashMap<>();
    Integer stopN = null;
    String reason = null;
    while (true) {
      int n = answers.size();
      Posterior post = Posterior.fit(fitSeed, k, answers);
      means.put(n, post.mean());
      if (stopN == null && n > 0) {
        Stopping.Decision d =
            Stopping.shouldStop(
                post,
                problem.v(),
                answers,
                QuestionSelector.bestInfoGain(
                    post, problem.v(), problem.criteria(), problem.scales()),
                false,
                null,
                null);
        if (d.stop()) {
          stopN = n;
          reason = d.reason().name();
        }
      }
      if ((stopN != null && n >= maxFixed) || n >= nMax) {
        break;
      }
      QuestionSpec q;
      if ("eig".equals(strategy)) {
        q =
            QuestionSelector.nextQuestion(
                    post,
                    problem.v(),
                    problem.criteria(),
                    history,
                    problem.scales(),
                    covered(answers))
                .orElse(null);
      } else {
        q = randomQuestion(post.mean(), problem.v(), problem.criteria(), problem.scales(), rng);
      }
      if (q == null) {
        stopN = stopN != null ? stopN : n;
        break;
      }
      history.add(q);
      answers.add(new Answer(q, sampleResponse(q, trueW, rng, noise)));
    }
    int last = means.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
    int stop = stopN != null ? stopN : last;
    int topTrue = DecisionAnalysis.ranking(trueW, problem.v()).get(0);

    Map<String, double[]> scores = new LinkedHashMap<>();
    scores.put("stop", score(means.get(Math.min(stop, last)), trueW, problem.v(), topTrue));
    for (int fixed : FIXED_COUNTS) {
      scores.put("@" + fixed, score(means.get(Math.min(fixed, last)), trueW, problem.v(), topTrue));
    }
    return new UserResult(stop, reason == null ? "NONE" : reason, scores);
  }

  private static double[] score(double[] mean, double[] trueW, double[][] v, int topTrue) {
    double mae = 0;
    for (int i = 0; i < mean.length; i++) {
      mae += Math.abs(mean[i] - trueW[i]);
    }
    boolean hit = DecisionAnalysis.ranking(mean, v).get(0) == topTrue;
    return new double[] {mae / mean.length, hit ? 1.0 : 0.0};
  }

  // ── 실험 ──────────────────────────────────────────────────────

  /** 한 시나리오를 두 전략으로 돌려 요약을 돌려준다. 같은 seed면 항상 같은 결과. */
  public static Summary runExperiment(String scenario, int users, long seed, double noise) {
    Problem problem = problem(scenario);
    int k = problem.v()[0].length;
    int sid = SCENARIO_IDS.get(scenario);
    Map<String, List<UserResult>> rows = new LinkedHashMap<>();
    STRATEGIES.forEach(s -> rows.put(s, new ArrayList<>()));
    for (int i = 0; i < users; i++) {
      double[] trueW =
          Posterior.priorParticles(seed * 1_000_003L + sid * 10_007L + i * 31L, k, 1)[0];
      long fitSeed = (seed * 1_000_003L + sid * 10_007L + i) % (1L << 31);
      for (int j = 0; j < STRATEGIES.size(); j++) {
        RandomGenerator rng = rngOf(seed, sid, i, 1 + j);
        rows.get(STRATEGIES.get(j))
            .add(runUser(STRATEGIES.get(j), trueW, problem, fitSeed, rng, noise));
      }
    }
    Map<String, Map<String, Stat>> adaptive = new LinkedHashMap<>();
    Map<String, Map<String, Integer>> reasons = new LinkedHashMap<>();
    Map<Integer, Map<String, Map<String, Stat>>> fixed = new LinkedHashMap<>();
    for (int f : FIXED_COUNTS) {
      fixed.put(f, new LinkedHashMap<>());
    }
    for (String s : STRATEGIES) {
      List<UserResult> r = rows.get(s);
      Map<String, Stat> a = new LinkedHashMap<>();
      a.put("mae", Stat.of(r.stream().map(u -> u.scores().get("stop")[0]).toList()));
      a.put("hit", Stat.of(r.stream().map(u -> u.scores().get("stop")[1]).toList()));
      a.put("questions", Stat.of(r.stream().map(u -> (double) u.stopN()).toList()));
      adaptive.put(s, a);
      Map<String, Integer> reason = new LinkedHashMap<>();
      r.forEach(u -> reason.merge(u.reason(), 1, Integer::sum));
      reasons.put(s, reason);
      for (int f : FIXED_COUNTS) {
        Map<String, Stat> cell = new LinkedHashMap<>();
        cell.put("mae", Stat.of(r.stream().map(u -> u.scores().get("@" + f)[0]).toList()));
        cell.put("hit", Stat.of(r.stream().map(u -> u.scores().get("@" + f)[1]).toList()));
        fixed.get(f).put(s, cell);
      }
    }
    Map<String, Stat> paired = new LinkedHashMap<>();
    List<String> keys = new ArrayList<>(List.of("stop"));
    for (int f : FIXED_COUNTS) {
      keys.add("@" + f);
    }
    for (String key : keys) {
      List<Double> diff = new ArrayList<>();
      for (int i = 0; i < users; i++) {
        // 랜덤 − EIG, 양수면 EIG가 더 정확
        diff.add(
            rows.get("random").get(i).scores().get(key)[0]
                - rows.get("eig").get(i).scores().get(key)[0]);
      }
      paired.put("stop".equals(key) ? "mae" : "mae" + key, Stat.of(diff));
    }
    return new Summary(
        scenario, users, seed, noise, k, problem.v().length, adaptive, fixed, paired, reasons);
  }

  private static String f(Stat s, int digits) {
    return String.format("%." + digits + "f ± %." + digits + "f", s.mean(), s.se());
  }

  public static String formatMarkdown(Summary res) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        String.format(
            "### %s (기준 %d개, 선택지 %d개, 사용자 %d명, ε=%s)%n%n",
            res.scenario(), res.criteria(), res.options(), res.users(), res.noise()));
    sb.append("종료 규칙(5.10)을 그대로 적용한 결과 (평균 ± 표준오차)\n\n");
    sb.append("| 전략 | MAE | 1위 적중률 | 평균 질문 수 |\n|---|---|---|---|\n");
    for (String s : STRATEGIES) {
      Map<String, Stat> a = res.adaptive().get(s);
      sb.append(
          String.format(
              "| %s | %s | %s | %s |%n",
              s, f(a.get("mae"), 3), f(a.get("hit"), 3), f(a.get("questions"), 2)));
    }
    sb.append("\n같은 질문 수에서의 정확도\n\n| 질문 수 | 전략 | MAE | 1위 적중률 |\n|---|---|---|---|\n");
    for (int k : FIXED_COUNTS) {
      for (String s : STRATEGIES) {
        Map<String, Stat> c = res.fixed().get(k).get(s);
        sb.append(
            String.format("| %d | %s | %s | %s |%n", k, s, f(c.get("mae"), 3), f(c.get("hit"), 3)));
      }
    }
    sb.append("\nMAE 차이 (랜덤 − EIG, 양수면 EIG가 더 정확): ");
    List<String> parts = new ArrayList<>();
    res.paired().forEach((key, v) -> parts.add(key + "=" + f(v, 4)));
    sb.append(String.join(", ", parts)).append('\n');
    return sb.toString();
  }

  public static void main(String[] args) {
    int users = DEFAULT_USERS;
    long seed = DEFAULT_SEED;
    String scenario = "both";
    double noise = 0;
    for (int i = 0; i < args.length; i++) {
      switch (args[i]) {
        case "--users" -> users = Integer.parseInt(args[++i]);
        case "--seed" -> seed = Long.parseLong(args[++i]);
        case "--scenario" -> scenario = args[++i];
        case "--noise" -> noise = Double.parseDouble(args[++i]);
        case "--quick" -> users = QUICK_USERS;
        default -> throw new IllegalArgumentException("알 수 없는 옵션: " + args[i]);
      }
    }
    List<String> names =
        "both".equals(scenario) ? List.of("purchase", "career") : List.of(scenario);
    for (String name : names) {
      System.out.println(formatMarkdown(runExperiment(name, users, seed, noise)));
    }
  }
}

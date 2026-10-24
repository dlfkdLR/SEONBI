package com.seonbi.engine;

import com.seonbi.engine.QuestionSpec.CriteriaABSpec;
import com.seonbi.engine.QuestionSpec.ScenarioSpec;
import com.seonbi.engine.QuestionSpec.SliderSpec;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 후보 생성, 상황형 구성·반올림, EIG 계산, 선택 규칙 (개발기획서 5.5). */
public final class QuestionSelector {

    public static final int INITIAL_SLIDERS_MAX = 3; // 초기 슬라이더 사슬 최대 개수
    public static final double[] SCENARIO_DELTAS = {0.3, 0.5, 0.7}; // 상황형 Δa 변형 (정규화 범위 대비)
    public static final double SCENARIO_DELTA_CAP = 0.8; // Δb 상한
    public static final int SCENARIO_QUOTA_FROM = 4; // 이 번호부터 짝수 번째 질문은 상황형
    public static final int SCENARIOS_PER_PAIR = 2; // 숨은 선호 판단에 필요한 쌍당 상황형 수
    public static final int MAX_SAME_TYPE_RUN = 2; // 같은 유형 3연속 금지

    public record Candidate(QuestionSpec spec, double eig) {}

    private QuestionSelector() {}

    /**
     * 다음 질문. 물어볼 기준 쌍이 없으면 비어 있다. history는 지금까지 낸 서버 질문(답변·건너뛰기 포함)을 순서대로 담는다.
     * 자유 입력으로 만든 가상 질문은 넣지 않는다. covered는 유효 답변(가상 답변 포함, "잘 모르겠어요" 제외)에 한 번이라도 나온
     * 기준 번호이며, null이면 history의 질문에 나온 기준으로 대신한다.
     */
    public static Optional<QuestionSpec> nextQuestion(
            Posterior post,
            double[][] v,
            List<Criterion> criteria,
            List<QuestionSpec> history,
            Map<String, Scale> scales,
            Collection<Integer> covered) {
        List<Integer> active = activeCriteria(v);
        if (active.size() < 2) {
            return Optional.empty();
        }
        int n = history.size() + 1; // 지금 내려는 질문의 번호 (1부터)
        // 처음 몇 문항은 EIG를 따지지 않고 기준을 사슬처럼 이어서 슬라이더로 묻는다
        if (n <= Math.min(active.size() - 1, INITIAL_SLIDERS_MAX)) {
            return Optional.of(new SliderSpec(active.get(n - 1), active.get(n))); // 기준을 사슬처럼 연결
        }
        List<Candidate> candidates = scoreCandidates(post, v, criteria, scales);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        // 이미 답변에 나온 기준을 모아 둔다 (아직 안 나온 기준을 먼저 묻기 위해)
        Set<Integer> seen = new HashSet<>();
        if (covered != null) {
            seen.addAll(covered);
        } else {
            for (QuestionSpec q : history) {
                seen.add(q.a());
                seen.add(q.b());
            }
        }
        Set<Integer> fresh = new HashSet<>();
        for (int k : active) {
            if (!seen.contains(k)) {
                fresh.add(k);
            }
        }
        // 규칙 2: 아직 답변에 나온 적 없는 기준이 있으면 그 기준이 들어간 후보 안에서 고른다
        List<Candidate> focused = new ArrayList<>();
        for (Candidate c : candidates) {
            if (fresh.isEmpty() || fresh.contains(c.spec().a()) || fresh.contains(c.spec().b())) {
                focused.add(c);
            }
        }
        if (n >= SCENARIO_QUOTA_FROM && n % 2 == 0) {
            Optional<Candidate> scenario = pickScenario(focused, history); // 규칙 4·5: 후보가 없으면 규칙 2로 넘어간다
            if (scenario.isPresent()) {
                return Optional.of(scenario.get().spec());
            }
        }
        // 같은 쌍·같은 유형이 연달아 나오지 않게 거른다. 다 걸러지면 거르지 않은 후보에서 고른다
        List<Candidate> allowed = focused.stream().filter(c -> allowed(c.spec(), history, active.size())).toList();
        return Optional.of(maxByEig(allowed.isEmpty() ? focused : allowed).spec()); // 동점이면 먼저 생성된 후보
    }

    /** 후보 중 최고 EIG. 종료 판단(LOW_INFO_GAIN)에 쓴다. */
    public static double bestInfoGain(Posterior post, double[][] v, List<Criterion> criteria, Map<String, Scale> scales) {
        double best = 0.0;
        for (Candidate c : scoreCandidates(post, v, criteria, scales)) {
            best = Math.max(best, c.eig());
        }
        return best;
    }

    public static List<Candidate> scoreCandidates(
            Posterior post, double[][] v, List<Criterion> criteria, Map<String, Scale> scales) {
        double[][] shares = Posterior.bestShares(post.w(), v);
        double hNow = Posterior.entropyBits(Posterior.pBest(post.p(), shares));
        double[] mean = post.mean();
        List<Candidate> out = new ArrayList<>();
        List<Integer> active = activeCriteria(v);
        for (int x = 0; x < active.size(); x++) {
            for (int y = x + 1; y < active.size(); y++) {
                int a = active.get(x);
                int b = active.get(y);
                List<QuestionSpec> specs = new ArrayList<>();
                specs.add(new SliderSpec(a, b));
                specs.add(new CriteriaABSpec(a, b));
                specs.addAll(buildScenarios(mean, a, b, criteria, scales));
                for (QuestionSpec s : specs) {
                    out.add(new Candidate(s, eig(post, shares, hNow, s)));
                }
            }
        }
        return out;
    }

    /** 선택지 사이에 값 차이가 있는 기준만. 값이 모두 같으면 순위에 영향이 없어 묻지 않는다. */
    public static List<Integer> activeCriteria(double[][] v) {
        List<Integer> out = new ArrayList<>();
        int k = v.length == 0 ? 0 : v[0].length;
        for (int j = 0; j < k; j++) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (double[] row : v) {
                min = Math.min(min, row[j]);
                max = Math.max(max, row[j]);
            }
            if (max - min > 0) {
                out.add(j);
            }
        }
        return out;
    }

    /** 응답이 반반으로 갈리도록 Δb = Δa · ŵ_a / ŵ_b 로 맞춘 상황형 후보. 순위 입력 기준은 만들지 않는다. */
    public static List<ScenarioSpec> buildScenarios(
            double[] mean, int a, int b, List<Criterion> criteria, Map<String, Scale> scales) {
        Criterion ca = criteria.get(a);
        Criterion cb = criteria.get(b);
        if (ca.isRank() || cb.isRank()) {
            return List.of();
        }
        List<ScenarioSpec> out = new ArrayList<>();
        for (double da0 : SCENARIO_DELTAS) {
            double da = da0;
            double db = da * mean[a] / Math.max(mean[b], 1e-12);
            if (db > SCENARIO_DELTA_CAP) {
                da = da * SCENARIO_DELTA_CAP / db;
                db = SCENARIO_DELTA_CAP;
            }
            double[] ra = roundDelta(ca, scales.get(ca.id()), da);
            double[] rb = roundDelta(cb, scales.get(cb.id()), db);
            double[] delta = new double[criteria.size()];
            delta[a] = ra[0];
            delta[b] = -rb[0];
            ScenarioSpec spec = new ScenarioSpec(a, b, delta, ra[1], rb[1]);
            if (!out.contains(spec)) {
                out.add(spec);
            }
        }
        return out;
    }

    /** span/10 이하에서 가장 큰 1·2·5×10ⁿ. 예: 39 → 2, 0.26 → 0.02. */
    public static double niceStep(double span) {
        double raw = span / 10;
        double e = Math.pow(10.0, Math.floor(Math.log10(raw)));
        for (int m : new int[] {5, 2, 1}) {
            if (m * e <= raw * (1 + 1e-9)) {
                return m * e;
            }
        }
        return e;
    }

    /** 10자리 유효숫자로 반올림해 부동소수점 꼬리를 없앤다 (파이썬 float(f"{x:.10g}")와 같다). */
    static double tidy(double x) {
        if (x == 0 || Double.isNaN(x) || Double.isInfinite(x)) {
            return x;
        }
        return new BigDecimal(x).round(new MathContext(10, RoundingMode.HALF_EVEN)).doubleValue();
    }

    /** 정규화 Δ를 화면에 보여줄 값으로 반올림하고, 반올림한 값에서 정규화 Δ를 다시 계산한다. {정규화 Δ, 화면 값}. */
    private static double[] roundDelta(Criterion c, Scale scale, double d) {
        if (c.type() == Criterion.Type.BOOLEAN) {
            return new double[] {1.0, 1.0};
        }
        if (c.type() == Criterion.Type.RATING) {
            double unit = 1.0 / ((c.levels() != null ? c.levels() : Normalizer.DEFAULT_LEVELS) - 1);
            double steps = Math.max(1.0, Math.rint(d / unit));
            return new double[] {Math.min(steps * unit, 1.0), steps};
        }
        if (scale == null || scale.span() == 0) {
            throw new IllegalArgumentException("숫자형 기준 " + c.id() + "의 척도가 없다");
        }
        double step = niceStep(scale.span());
        double raw = Math.max(step, Math.rint(d * scale.span() / step) * step);
        raw = tidy(raw); // 부동소수점 꼬리 제거 (0.30000000000000004 → 0.3)
        return new double[] {Math.min(raw / scale.span(), 1.0), raw};
    }

    /** EIG = H(현재) − Σ_r P(r) · H(답변 r 이후). */
    private static double eig(Posterior post, double[][] shares, double hNow, QuestionSpec spec) {
        double[][] logL = Likelihood.responseLogProbs(spec, post.w());
        int n = logL.length;
        int r = logL[0].length;
        int options = shares[0].length;
        double[] pr = new double[r];
        double[][] joint = new double[r][options];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < r; j++) {
                double pl = post.p()[i] * Math.exp(logL[i][j]);
                pr[j] += pl;
                for (int o = 0; o < options; o++) {
                    joint[j][o] += pl * shares[i][o];
                }
            }
        }
        double hAfter = 0.0;
        for (int j = 0; j < r; j++) {
            if (pr[j] > 1e-12) {
                double[] cond = new double[options];
                for (int o = 0; o < options; o++) {
                    cond[o] = joint[j][o] / pr[j];
                }
                hAfter += pr[j] * Posterior.entropyBits(cond);
            }
        }
        return hNow - hAfter;
    }

    private static int[] pair(QuestionSpec spec) {
        return new int[] {Math.min(spec.a(), spec.b()), Math.max(spec.a(), spec.b())};
    }

    private static boolean samePair(QuestionSpec x, QuestionSpec y) {
        return java.util.Arrays.equals(pair(x), pair(y));
    }

    private static boolean allowed(QuestionSpec spec, List<QuestionSpec> history, int nActive) {
        if (!history.isEmpty() && nActive > 2 && samePair(history.get(history.size() - 1), spec)) {
            return false; // 같은 기준 쌍 연속 금지 (기준이 2개면 적용하지 않음)
        }
        if (history.size() < MAX_SAME_TYPE_RUN) {
            return true;
        }
        List<QuestionSpec> recent = history.subList(history.size() - MAX_SAME_TYPE_RUN, history.size());
        return !recent.stream().allMatch(q -> q.getClass() == spec.getClass());
    }

    /** 상황형 할당 차례: 상황형이 1개뿐인 쌍을 먼저 채워 쌍당 2개가 되게 하고, 그다음 EIG 순. */
    private static Optional<Candidate> pickScenario(List<Candidate> candidates, List<QuestionSpec> history) {
        List<Candidate> scenarios = candidates.stream().filter(c -> c.spec() instanceof ScenarioSpec).toList();
        if (scenarios.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Integer> counts = new HashMap<>();
        for (QuestionSpec q : history) {
            if (q instanceof ScenarioSpec) {
                counts.merge(pairKey(q), 1, Integer::sum);
            }
        }
        List<Candidate> halfFilled = scenarios.stream()
                .filter(c -> {
                    int cnt = counts.getOrDefault(pairKey(c.spec()), 0);
                    return cnt > 0 && cnt < SCENARIOS_PER_PAIR;
                })
                .toList();
        return Optional.of(maxByEig(halfFilled.isEmpty() ? scenarios : halfFilled));
    }

    private static String pairKey(QuestionSpec spec) {
        int[] p = pair(spec);
        return p[0] + ":" + p[1];
    }

    private static Candidate maxByEig(List<Candidate> pool) {
        Candidate best = pool.get(0);
        for (Candidate c : pool) {
            if (c.eig() > best.eig()) {
                best = c; // 동점이면 먼저 생성된 후보
            }
        }
        return best;
    }
}

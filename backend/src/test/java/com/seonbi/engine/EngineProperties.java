package com.seonbi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.seonbi.engine.QuestionSpec.CriteriaABSpec;
import com.seonbi.engine.QuestionSpec.SliderSpec;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

/** 입력을 바꿔 가며 엔진이 항상 지켜야 하는 성질을 확인한다 (jqwik). */
class EngineProperties {

    // 가중치 두 개와 슬라이더 응답을 받아 우도를 계산하는 보조 함수
    private static double slider(double wa, double wb, int s) {
        double[][] w = {{wa, wb, 1 - wa - wb}};
        return Likelihood.logLikelihood(new SliderSpec(0, 1), new Response.Slider(s), w)[0];
    }

    @Property
    void 슬라이더_응답_확률의_합은_항상_1이다(
            @ForAll @DoubleRange(min = 0.01, max = 0.49) double wa, @ForAll @DoubleRange(min = 0.01, max = 0.49) double wb) {
        double sum = 0;
        for (int s = -5; s <= 5; s++) {
            sum += Math.exp(slider(wa, wb, s));
        }
        assertThat(sum).isCloseTo(1.0, offset(1e-9));
    }

    @Property
    void a가_더_중요한_가중치일수록_a쪽_응답이_더_그럴듯하다(@ForAll @IntRange(min = 1, max = 5) int s) {
        // a가 b보다 훨씬 큰 입자에서는 양수 응답이, 반대면 음수 응답이 더 그럴듯해야 한다
        assertThat(slider(0.6, 0.1, s)).isGreaterThan(slider(0.1, 0.6, s));
    }

    @Property
    void 기준_AB_질문은_두_응답의_확률_합이_1이다(
            @ForAll @DoubleRange(min = 0.01, max = 0.49) double wa, @ForAll @DoubleRange(min = 0.01, max = 0.49) double wb) {
        double[][] w = {{wa, wb, 1 - wa - wb}};
        CriteriaABSpec spec = new CriteriaABSpec(0, 1);
        double pa = Math.exp(Likelihood.logLikelihood(spec, Response.Choice.A, w)[0]);
        double pb = Math.exp(Likelihood.logLikelihood(spec, Response.Choice.B, w)[0]);
        assertThat(pa + pb).isCloseTo(1.0, offset(1e-9));
    }

    @Property(tries = 20)
    void 가중치_후보는_언제나_합이_1이고_모두_양수다(@ForAll @IntRange(min = 2, max = 8) int k, @ForAll long seed) {
        double[][] w = Posterior.priorParticles(seed, k, 50);
        for (double[] row : w) {
            double sum = 0;
            for (double x : row) {
                assertThat(x).isGreaterThan(0);
                sum += x;
            }
            assertThat(sum).isCloseTo(1.0, offset(1e-9));
        }
    }

    @Property
    void 정규화_값은_항상_0과_1_사이다(
            @ForAll @DoubleRange(min = -1000, max = 1000) double a,
            @ForAll @DoubleRange(min = -1000, max = 1000) double b,
            @ForAll @DoubleRange(min = -1000, max = 1000) double c) {
        Criterion price = new Criterion("price", Criterion.Type.NUMERIC, Criterion.Direction.LOWER_BETTER, null);
        List<String> options = List.of("x", "y", "z");
        var values = java.util.Map.of(
                new ValueKey("x", "price"), a, new ValueKey("y", "price"), b, new ValueKey("z", "price"), c);
        var scales = Normalizer.freezeScales(List.of(price), values, List.of());
        double[][] m = Normalizer.normalize(values, List.of(price), scales, options, List.of()).matrix().values();
        for (double[] row : m) {
            assertThat(row[0]).isBetween(0.0, 1.0);
        }
    }

    @Property
    void 가중치가_정확히_전환점이면_순위가_바뀐다(@ForAll @IntRange(min = 0, max = 2) int k) {
        // 전환점은 닫힌 해라서, 그 값보다 조금 더 가면 새 1위가 실제로 1위여야 한다
        double[][] v = {{1.0, 0.2, 0.5}, {0.4, 0.9, 0.5}, {0.6, 0.5, 0.8}};
        double[] mean = {0.4, 0.35, 0.25};
        DecisionAnalysis.ChangeCondition cc = DecisionAnalysis.changeConditions(mean, v).get(k);
        if (cc.threshold() == null) {
            return;
        }
        double eps = cc.direction() == DecisionAnalysis.Direction.INCREASE ? 1e-3 : -1e-3;
        double t = cc.threshold() + eps;
        double rest = 1 - mean[k];
        double[] w = new double[3];
        for (int j = 0; j < 3; j++) {
            w[j] = mean[j] * (1 - t) / rest;
        }
        w[k] = t;
        assertThat(DecisionAnalysis.ranking(w, v).get(0)).isEqualTo(cc.newTop());
    }
}

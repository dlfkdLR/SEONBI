package com.seonbi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class PosteriorTest {

    // 난수 생성기가 파이썬과 달라서 입자가 똑같지는 않다. 시드 하나만 보면 파이썬 쪽 값도 표본 잡음이 있으니
    // 시드 5개의 평균을 파이썬 결과와 ±0.03으로 비교한다 (개발기획서 14)
    @Test
    void 요약_통계가_파이썬_결과와_허용_오차_안에서_같다() {
        double[][] v = Golden.mat(Golden.load("selector").get("V"));
        int seeds = 5;
        for (JsonNode c : Golden.load("posterior")) {
            String name = c.get("name").asText();
            List<Answer> answers = Golden.answers(c.get("answers"));
            double[] mean = new double[3];
            double[] pBest = new double[3];
            double[][] intervals = new double[3][2];
            double entropy = 0;
            for (int seed = 1; seed <= seeds; seed++) {
                Posterior.Summary s = Posterior.fit(seed, 3, answers).summary(v);
                for (int k = 0; k < 3; k++) {
                    mean[k] += s.mean()[k] / seeds;
                    pBest[k] += s.pBest()[k] / seeds;
                    intervals[k][0] += s.intervals()[k][0] / seeds;
                    intervals[k][1] += s.intervals()[k][1] / seeds;
                }
                entropy += s.entropy() / seeds;
            }

            assertThat(mean).as(name + " 평균").containsExactly(Golden.vec(c.get("mean")), offset(Golden.RANDOM_TOL));
            assertThat(pBest).as(name + " 1위 확률").containsExactly(Golden.vec(c.get("pBest")), offset(Golden.RANDOM_TOL));
            assertThat(entropy).as(name + " 엔트로피").isCloseTo(c.get("entropy").asDouble(), offset(Golden.RANDOM_TOL * 2));
            double[][] expected = Golden.mat(c.get("intervals"));
            for (int k = 0; k < expected.length; k++) {
                assertThat(intervals[k]).as(name + " 구간 " + k).containsExactly(expected[k], offset(Golden.RANDOM_TOL));
            }
        }
    }

    @Test
    void 같은_시드와_답변이면_결과가_같다() {
        List<Answer> answers = List.of(
                new Answer(new QuestionSpec.SliderSpec(0, 1), new Response.Slider(4)),
                new Answer(new QuestionSpec.SliderSpec(1, 2), new Response.Slider(-3)));
        Posterior a = Posterior.fit(7, 3, answers);
        Posterior b = Posterior.fit(7, 3, answers);
        assertThat(a.mean()).containsExactly(b.mean(), offset(0.0));
    }

    @Test
    void 답변이_없으면_사전분포라_가중치_평균이_균등하다() {
        Posterior post = Posterior.fit(42, 4, List.of());
        for (double m : post.mean()) {
            assertThat(m).isCloseTo(0.25, offset(0.02));
        }
    }

    @Test
    void 입자의_가중치는_합이_1이다() {
        Posterior post = Posterior.fit(1, 5, List.of(new Answer(new QuestionSpec.SliderSpec(0, 4), new Response.Slider(5))));
        for (double[] w : post.w()) {
            double sum = 0;
            for (double x : w) {
                sum += x;
            }
            assertThat(sum).isCloseTo(1.0, offset(1e-9));
        }
        double pSum = 0;
        for (double p : post.p()) {
            pSum += p;
        }
        assertThat(pSum).isCloseTo(1.0, offset(1e-9));
    }
}

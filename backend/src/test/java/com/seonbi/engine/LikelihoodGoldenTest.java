package com.seonbi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

import com.fasterxml.jackson.databind.JsonNode;
import com.seonbi.engine.QuestionSpec.CriteriaABSpec;
import com.seonbi.engine.QuestionSpec.ScenarioSpec;
import com.seonbi.engine.QuestionSpec.SliderSpec;
import org.junit.jupiter.api.Test;

class LikelihoodGoldenTest {

    @Test
    void 골든_파일과_로그_우도가_같다() {
        JsonNode g = Golden.load("likelihood");
        double[][] w = Golden.mat(g.get("W"));
        for (JsonNode c : g.get("cases")) {
            QuestionSpec spec = Golden.spec(c.get("spec"));
            double[][] expected = Golden.mat(c.get("responseLogprobs"));
            double[][] actual = Likelihood.responseLogProbs(spec, w);
            for (int i = 0; i < w.length; i++) {
                for (int j = 0; j < expected[0].length; j++) {
                    assertThat(actual[i][j]).isCloseTo(expected[i][j], offset(Golden.TOL));
                }
            }
            for (JsonNode r : c.get("responses")) {
                double[] exp = Golden.vec(r.get("logLik"));
                double[] act = Likelihood.logLikelihood(spec, Golden.response(r.get("response")), w);
                for (int i = 0; i < exp.length; i++) {
                    assertThat(act[i]).as(spec + " " + r.get("response")).isCloseTo(exp[i], offset(Golden.TOL));
                }
            }
        }
    }

    @Test
    void 슬라이더_응답_확률은_합이_1이다() {
        double[][] w = {{0.5, 0.3, 0.2}, {0.1, 0.8, 0.1}};
        double[][] lp = Likelihood.responseLogProbs(new SliderSpec(0, 1), w);
        for (double[] row : lp) {
            double sum = 0;
            for (double v : row) {
                sum += Math.exp(v);
            }
            assertThat(sum).isCloseTo(1.0, offset(1e-12));
        }
    }

    @Test
    void 범위를_벗어난_응답은_오류다() {
        double[][] w = {{0.5, 0.5}};
        assertThatThrownBy(() -> Likelihood.logLikelihood(new SliderSpec(0, 1), new Response.Slider(6), w))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Likelihood.logLikelihood(new CriteriaABSpec(0, 1), new Response.Slider(1), w))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잘_모르겠어요는_정보가_없다() {
        double[][] w = {{0.5, 0.5}, {0.2, 0.8}};
        ScenarioSpec s = new ScenarioSpec(0, 1, new double[] {0.3, -0.3}, 1, 1);
        assertThat(Likelihood.logLikelihood(s, Response.DONT_KNOW, w)).containsExactly(0.0, 0.0);
    }

    @Test
    void 진술_선호와_드러난_선호를_구분한다() {
        assertThat(Likelihood.preferenceKind(new SliderSpec(0, 1))).isEqualTo(Likelihood.PreferenceKind.STATED);
        assertThat(Likelihood.preferenceKind(new CriteriaABSpec(0, 1))).isEqualTo(Likelihood.PreferenceKind.STATED);
        assertThat(Likelihood.preferenceKind(new ScenarioSpec(0, 1, new double[] {0, 0}, 1, 1)))
                .isEqualTo(Likelihood.PreferenceKind.REVEALED);
    }
}

package com.seonbi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DecisionAnalysisGoldenTest {

    @Test
    void 골든과_분석_결과가_같다() {
        for (JsonNode c : Golden.load("analysis")) {
            String name = c.get("name").asText();
            Posterior post = new Posterior(Golden.mat(c.get("W")), Golden.vec(c.get("p")));
            double[][] v = Golden.mat(c.get("V"));
            double[] mean = Golden.vec(c.get("mean"));
            List<Criterion> criteria = Golden.criteria(c.get("criteria"));
            Map<String, Scale> scales = Golden.scales(c.get("scales"));

            assertThat(post.mean()).as(name + " 평균").containsExactly(mean, offset(Golden.TOL));
            assertThat(DecisionAnalysis.scores(mean, v)).as(name + " 점수")
                    .containsExactly(Golden.vec(c.get("scores")), offset(Golden.TOL));
            assertThat(DecisionAnalysis.ranking(mean, v)).as(name + " 순위")
                    .isEqualTo(toList(c.get("ranking")));

            // 안정성: 1위 확률과 등급은 결정적이고, 섭동 유지율만 난수라서 느슨하게 본다
            DecisionAnalysis.Stability s = DecisionAnalysis.stability(post, v, 1);
            JsonNode es = c.get("stability");
            assertThat(s.top()).isEqualTo(es.get("top").asInt());
            assertThat(s.pBest()).isCloseTo(es.get("pBest").asDouble(), offset(Golden.TOL));
            assertThat(s.grade().name().toLowerCase()).isEqualTo(es.get("grade").asText());
            assertThat(s.perturbKeep()).isCloseTo(es.get("perturbKeep").asDouble(), offset(Golden.RANDOM_TOL));

            checkChangeConditions(name, DecisionAnalysis.changeConditions(mean, v), c.get("changeConditions"));
            checkSensitivity(name, mean, v, c.get("sensitivity"));
            checkValueConditions(name, DecisionAnalysis.valueConditions(mean, v, criteria, scales, Golden.mat(c.get("raw"))),
                    c.get("valueConditions"));
        }
    }

    private static List<Integer> toList(JsonNode n) {
        List<Integer> out = new java.util.ArrayList<>();
        n.forEach(x -> out.add(x.asInt()));
        return out;
    }

    private static void checkChangeConditions(String name, List<DecisionAnalysis.ChangeCondition> actual, JsonNode expected) {
        assertThat(actual).as(name + " 전환 조건 수").hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode e = expected.get(i);
            DecisionAnalysis.ChangeCondition a = actual.get(i);
            assertThat(a.criterion()).isEqualTo(e.get("criterion").asInt());
            assertThat(a.current()).isCloseTo(e.get("current").asDouble(), offset(Golden.TOL));
            if (e.get("threshold").isNull()) {
                // 어떻게 바꿔도 1위가 그대로인 기준
                assertThat(a.threshold()).isNull();
                assertThat(a.direction()).isNull();
                assertThat(a.newTop()).isNull();
            } else {
                assertThat(a.threshold()).as(name + " 전환점 " + i).isCloseTo(e.get("threshold").asDouble(), offset(Golden.TOL));
                assertThat(a.direction().name().toLowerCase()).isEqualTo(e.get("direction").asText());
                assertThat(a.newTop()).isEqualTo(e.get("newTop").asInt());
            }
        }
    }

    private static void checkSensitivity(String name, double[] mean, double[][] v, JsonNode expected) {
        for (JsonNode e : expected) {
            DecisionAnalysis.Curve curve = DecisionAnalysis.sensitivity(mean, v, e.get("k").asInt());
            assertThat(curve.t()).as(name + " t").containsExactly(Golden.vec(e.get("t")), offset(Golden.TOL));
            double[][] exp = Golden.mat(e.get("scores"));
            for (int i = 0; i < exp.length; i++) {
                assertThat(curve.scores()[i]).as(name + " 곡선 " + i).containsExactly(exp[i], offset(Golden.TOL));
            }
        }
    }

    private static void checkValueConditions(String name, List<DecisionAnalysis.ValueCondition> actual, JsonNode expected) {
        assertThat(actual).as(name + " 값 전환 조건 수").hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            JsonNode e = expected.get(i);
            DecisionAnalysis.ValueCondition a = actual.get(i);
            assertThat(a.option()).isEqualTo(e.get("option").asInt());
            assertThat(a.criterion()).isEqualTo(e.get("criterion").asInt());
            assertThat(a.current()).isCloseTo(e.get("current").asDouble(), offset(Golden.TOL));
            assertThat(a.threshold()).isCloseTo(e.get("threshold").asDouble(), offset(Golden.TOL));
            assertThat(a.direction().name().toLowerCase()).isEqualTo(e.get("direction").asText());
            assertThat(a.neededV()).isCloseTo(e.get("neededV").asDouble(), offset(Golden.TOL));
        }
    }
}

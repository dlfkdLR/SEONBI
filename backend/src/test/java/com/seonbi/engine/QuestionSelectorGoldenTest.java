package com.seonbi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class QuestionSelectorGoldenTest {

    static JsonNode g;
    static Posterior post;
    static double[][] v;
    static List<Criterion> criteria;
    static Map<String, Scale> scales;

    @BeforeAll
    static void load() {
        g = Golden.load("selector");
        // 입자는 파이썬이 만든 것을 그대로 쓴다. 그래야 EIG를 1e-9로 맞출 수 있다
        post = new Posterior(Golden.mat(g.get("W")), Golden.vec(g.get("p")));
        v = Golden.mat(g.get("V"));
        criteria = Golden.criteria(g.get("criteria"));
        scales = Golden.scales(g.get("scales"));
    }

    @Test
    void 후보_목록과_EIG가_골든과_같다() {
        List<QuestionSelector.Candidate> actual = QuestionSelector.scoreCandidates(post, v, criteria, scales);
        JsonNode expected = g.get("candidates");
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            // 후보가 만들어지는 순서까지 같아야 동점일 때 같은 질문이 뽑힌다
            assertThat(actual.get(i).spec()).as("후보 " + i).isEqualTo(Golden.spec(expected.get(i).get("spec")));
            assertThat(actual.get(i).eig()).as("EIG " + i).isCloseTo(expected.get(i).get("eig").asDouble(), offset(Golden.TOL));
        }
    }

    @Test
    void 최고_EIG가_골든과_같다() {
        assertThat(QuestionSelector.bestInfoGain(post, v, criteria, scales))
                .isCloseTo(g.get("bestInfoGain").asDouble(), offset(Golden.TOL));
    }

    @Test
    void 상황형_후보가_골든과_같다() {
        List<QuestionSpec.ScenarioSpec> actual =
                QuestionSelector.buildScenarios(Golden.vec(g.get("mean")), 0, 1, criteria, scales);
        JsonNode expected = g.get("scenarios");
        assertThat(actual).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            QuestionSpec.ScenarioSpec e = (QuestionSpec.ScenarioSpec) Golden.spec(expected.get(i));
            assertThat(actual.get(i).rawA()).isCloseTo(e.rawA(), offset(Golden.TOL));
            assertThat(actual.get(i).rawB()).isCloseTo(e.rawB(), offset(Golden.TOL));
            assertThat(actual.get(i).delta()).containsExactly(e.delta());
        }
    }

    @Test
    void 다음_질문이_골든과_같다() {
        for (JsonNode c : g.get("next")) {
            List<QuestionSpec> history = new ArrayList<>();
            c.get("history").forEach(h -> history.add(Golden.spec(h)));
            List<Integer> covered = null;
            if (c.has("covered")) {
                covered = new ArrayList<>();
                for (JsonNode x : c.get("covered")) {
                    covered.add(x.asInt());
                }
            }
            Optional<QuestionSpec> next = QuestionSelector.nextQuestion(post, v, criteria, history, scales, covered);
            if (c.get("next").isNull()) {
                assertThat(next).isEmpty();
            } else {
                assertThat(next).as("history=" + history.size() + " covered=" + covered)
                        .contains(Golden.spec(c.get("next")));
            }
        }
    }

    @Test
    void 반올림_단위가_골든과_같다() {
        for (JsonNode c : g.get("niceStep")) {
            assertThat(QuestionSelector.niceStep(c.get("span").asDouble()))
                    .as("span=" + c.get("span"))
                    .isCloseTo(c.get("step").asDouble(), offset(Golden.TOL));
        }
    }

    @Test
    void 값이_모두_같은_기준은_묻지_않는다() {
        assertThat(g.get("activeCriteria").size()).isEqualTo(QuestionSelector.activeCriteria(v).size());
        double[][] flat = {{0.5, 0.2}, {0.5, 0.9}}; // 첫 기준은 선택지 사이 차이가 없다
        assertThat(QuestionSelector.activeCriteria(flat)).containsExactly(1);
    }

    @Test
    void 물어볼_기준이_하나뿐이면_질문이_없다() {
        double[][] flat = {{0.5, 0.2}, {0.5, 0.9}};
        Posterior p = new Posterior(new double[][] {{0.5, 0.5}}, new double[] {1.0});
        assertThat(QuestionSelector.nextQuestion(p, flat, criteria.subList(0, 2), List.of(), scales, null)).isEmpty();
    }
}

package com.seonbi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.seonbi.engine.QuestionSpec.SliderSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 개발기획서 5.14 예외 상황: 엔진이 이런 입력에서도 오류 없이 말이 되는 결과를 내야 한다. */
class EdgeCaseTest {

  private static final List<Criterion> THREE =
      List.of(
          new Criterion("c0", Criterion.Type.NUMERIC, Criterion.Direction.HIGHER_BETTER, null),
          new Criterion("c1", Criterion.Type.NUMERIC, Criterion.Direction.HIGHER_BETTER, null),
          new Criterion("c2", Criterion.Type.NUMERIC, Criterion.Direction.HIGHER_BETTER, null));

  @Test
  void 선택지가_하나뿐이면_질문도_전환_조건도_없다() {
    double[][] v = {{0.5, 0.5, 0.5}};
    Posterior post = Posterior.fit(1, 3, List.of());

    // 값 차이가 있는 기준이 없으니 물어볼 게 없다
    assertThat(QuestionSelector.activeCriteria(v)).isEmpty();
    assertThat(QuestionSelector.nextQuestion(post, v, THREE, List.of(), Map.of(), null)).isEmpty();

    // 1위 확률은 처음부터 100%
    double[] pBest = Posterior.pBest(post.p(), Posterior.bestShares(post.w(), v));
    assertThat(pBest).containsExactly(1.0);
    assertThat(Posterior.entropyBits(pBest)).isZero();

    // 뒤집을 선택지가 없으니 모든 기준이 "어떻게 바꿔도 1위 유지"
    for (DecisionAnalysis.ChangeCondition c : DecisionAnalysis.changeConditions(post.mean(), v)) {
      assertThat(c.threshold()).isNull();
    }
  }

  @Test
  void 기준이_두_개면_첫_질문은_슬라이더_하나다() {
    double[][] v = {{1.0, 0.0}, {0.0, 1.0}};
    Posterior post = Posterior.fit(1, 2, List.of());
    var next =
        QuestionSelector.nextQuestion(post, v, THREE.subList(0, 2), List.of(), Map.of(), null);
    assertThat(next).contains(new SliderSpec(0, 1));
  }

  @Test
  void 값이_모두_같은_기준은_0점5이고_묻지_않는다() {
    Criterion price = THREE.get(0);
    Map<ValueKey, Double> values =
        Map.of(new ValueKey("a", "c0"), 7.0, new ValueKey("b", "c0"), 7.0);
    Map<String, Scale> scales = Normalizer.freezeScales(List.of(price), values, List.of());
    double[][] v =
        Normalizer.normalize(values, List.of(price), scales, List.of("a", "b"), List.of())
            .matrix()
            .values();
    assertThat(v[0][0]).isEqualTo(0.5);
    assertThat(v[1][0]).isEqualTo(0.5);
    assertThat(QuestionSelector.activeCriteria(v)).isEmpty();
  }

  @Test
  void 전부_잘_모르겠어요면_사전분포가_그대로다() {
    List<Answer> answers = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      answers.add(new Answer(new SliderSpec(0, 1), Response.DONT_KNOW));
    }
    Posterior post = Posterior.fit(3, 3, answers);
    // 정보가 없는 답변이라 비중이 모두 같고, 재샘플링도 일어나지 않는다
    assertThat(post.ess()).isCloseTo(Posterior.N_PARTICLES, offset(1e-6));
    for (double m : post.mean()) {
      assertThat(m).isCloseTo(1.0 / 3, offset(0.02));
    }
  }

  @Test
  void 두_선택지가_똑같으면_1위_확률을_반씩_나눈다() {
    double[][] v = {{0.8, 0.2}, {0.8, 0.2}};
    Posterior post = Posterior.fit(5, 2, List.of());
    double[] pBest = Posterior.pBest(post.p(), Posterior.bestShares(post.w(), v));
    assertThat(pBest[0]).isCloseTo(0.5, offset(1e-9));
    assertThat(pBest[1]).isCloseTo(0.5, offset(1e-9));
    // 점수가 같으면 입력 순서대로 순위를 매긴다
    assertThat(DecisionAnalysis.ranking(post.mean(), v)).containsExactly(0, 1);
  }

  @Test
  void 기준을_하나_추가하면_차원이_늘어도_같은_답변이_반영된다() {
    // 기준 0이 기준 1보다 훨씬 중요하다는 답변. 기준 2는 나중에 추가된 기준이다
    List<Answer> answers =
        List.of(
            new Answer(new SliderSpec(0, 1), new Response.Slider(5)),
            new Answer(new SliderSpec(0, 1), new Response.Slider(4)));
    Posterior before = Posterior.fit(9, 2, answers);
    Posterior after = Posterior.fit(9, 3, answers);

    assertThat(after.w()[0]).hasSize(3);
    assertThat(after.mean()[0]).isGreaterThan(after.mean()[1] * 2);
    assertThat(before.mean()[0]).isGreaterThan(before.mean()[1] * 2);
    // 같은 시드로 다시 하면 똑같다 (입자를 저장하지 않고 매번 다시 계산하는 근거)
    assertThat(Posterior.fit(9, 3, answers).mean()).containsExactly(after.mean(), offset(0.0));
  }

  @Test
  void 질문을_모두_소진하면_종료한다() {
    double[][] v = {{1.0, 0.0}, {0.0, 1.0}};
    Posterior post = Posterior.fit(1, 2, List.of());
    List<Answer> history = new ArrayList<>();
    for (int i = 0; i < Stopping.maxQuestions(2); i++) {
      history.add(new Answer(new SliderSpec(0, 1), Response.DONT_KNOW));
    }
    Stopping.Decision d = Stopping.shouldStop(post, v, history, 0.5, false, null, null);
    assertThat(d.stop()).isTrue();
    assertThat(d.reason()).isEqualTo(Stopping.StopReason.MAX_QUESTIONS);
  }
}

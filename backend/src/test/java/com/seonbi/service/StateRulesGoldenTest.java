package com.seonbi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.seonbi.engine.GoldenFiles;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** 4.2 상태 표와 전이가 파이썬 참조 구현과 칸마다 같은지 확인한다. */
class StateRulesGoldenTest {

  private static final JsonNode G = GoldenFiles.load("staterules");

  @Test
  void API별_상태_제약_표의_모든_칸이_같다() {
    for (JsonNode c : G.get("checks")) {
      StateRules.Action action = StateRules.Action.valueOf(c.get("action").asText());
      DecisionStatus status = DecisionStatus.valueOf(c.get("status").asText());
      Optional<StateRules.Violation> v =
          StateRules.check(action, status, c.get("answered").asInt());
      String where = action + "/" + status + "/" + c.get("answered");
      if (c.get("violation").isNull()) {
        assertThat(v).as(where).isEmpty();
      } else {
        assertThat(v).as(where).isPresent();
        assertThat(v.get().httpStatus())
            .as(where)
            .isEqualTo(c.get("violation").get("httpStatus").asInt());
        assertThat(v.get().code().name())
            .as(where)
            .isEqualTo(c.get("violation").get("code").asText());
      }
    }
  }

  @Test
  void 상태_전이가_같고_허용되지_않는_전이는_예외다() {
    for (JsonNode t : G.get("transitions")) {
      UnaryOperator<DecisionStatus> fn =
          switch (t.get("fn").asText()) {
            case "after_question_start" -> StateRules::afterQuestionStart;
            case "after_stop_condition" -> StateRules::afterStopCondition;
            case "after_criterion_added" -> StateRules::afterCriterionAdded;
            default -> StateRules::afterFinalize;
          };
      DecisionStatus from = DecisionStatus.valueOf(t.get("status").asText());
      String where = t.get("fn").asText() + " from " + from;
      if (t.get("result").isNull()) {
        assertThatThrownBy(() -> fn.apply(from))
            .as(where)
            .isInstanceOf(StateRules.InvalidTransition.class);
      } else {
        assertThat(fn.apply(from).name()).as(where).isEqualTo(t.get("result").asText());
      }
    }
  }

  @Test
  void 질문_라운드_종류가_같다() {
    for (JsonNode r : G.get("rounds")) {
      DecisionStatus status = DecisionStatus.valueOf(r.get("status").asText());
      boolean extra = r.get("extra").asBoolean();
      if (r.get("result").isNull()) {
        assertThatThrownBy(() -> StateRules.roundKind(status, extra))
            .isInstanceOf(StateRules.InvalidTransition.class);
      } else {
        assertThat(StateRules.roundKind(status, extra).json()).isEqualTo(r.get("result").asText());
      }
    }
  }

  @Test
  void 잠정_결과_여부가_같다() {
    for (JsonNode p : G.get("provisional")) {
      assertThat(
              StateRules.isProvisional(
                  DecisionStatus.valueOf(p.get("status").asText()), p.get("answered").asInt()))
          .isEqualTo(p.get("result").asBoolean());
    }
  }

  @Test
  void 질문을_시작한_결정은_DRAFT로_돌아가지_않는다() {
    for (DecisionStatus s : DecisionStatus.values()) {
      if (s == DecisionStatus.DRAFT) {
        continue; // 처음부터 DRAFT인 결정이 DRAFT로 남는 것은 되돌아간 것이 아니다
      }
      for (UnaryOperator<DecisionStatus> fn :
          java.util.List.<UnaryOperator<DecisionStatus>>of(
              StateRules::afterQuestionStart,
              StateRules::afterStopCondition,
              StateRules::afterCriterionAdded)) {
        try {
          assertThat(fn.apply(s)).as("%s", s).isNotEqualTo(DecisionStatus.DRAFT);
        } catch (StateRules.InvalidTransition e) {
          // 허용되지 않는 전이는 건너뛴다
        }
      }
    }
  }
}

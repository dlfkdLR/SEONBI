package com.seonbi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seonbi.ai.Json;
import com.seonbi.engine.Answer;
import com.seonbi.engine.Criterion;
import com.seonbi.engine.DecisionAnalysis;
import com.seonbi.engine.Golden;
import com.seonbi.engine.GoldenFiles;
import com.seonbi.engine.HiddenPreference;
import com.seonbi.engine.Posterior;
import com.seonbi.engine.Scale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 7.3 응답 조각이 파이썬 참조 구현과 글자·숫자까지 같은지 확인한다 (문장 속 조사 포함). */
class AnalysisHelpersGoldenTest {

  private static final JsonNode G = GoldenFiles.load("analysishelpers");

  // 한 번 문자열로 바꿨다 읽어 파일에서 읽은 JSON과 같은 숫자 타입(Int/Double)으로 맞춘다
  private static JsonNode tree(Object value) {
    try {
      return Json.MAPPER.readTree(Json.write(value));
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<String> strings(JsonNode n) {
    List<String> out = new ArrayList<>();
    n.forEach(x -> out.add(x.asText()));
    return out;
  }

  @Test
  void 응답_조각이_골든과_같다() {
    for (JsonNode c : G.get("cases")) {
      String name = c.get("name").asText();
      Posterior post = new Posterior(Golden.mat(c.get("W")), Golden.vec(c.get("p")));
      double[][] v = Golden.mat(c.get("V"));
      double[][] wPrior = Golden.mat(c.get("wPrior"));
      List<Criterion> criteria = Golden.criteria(c.get("criteria"));
      Map<String, Scale> scales = Golden.scales(c.get("scales"));
      List<Answer> answers = Golden.answers(c.get("answers"));
      JsonNode ids = c.get("ids");
      List<String> cids = strings(ids.get("criterionIds"));
      List<String> cnames = strings(ids.get("criterionNames"));
      List<String> oids = strings(ids.get("optionIds"));
      List<String> onames = strings(ids.get("optionNames"));
      List<String> units = strings(ids.get("units"));
      List<List<String>> levels = new ArrayList<>();
      ids.get("levels").forEach(l -> levels.add(strings(l)));

      Posterior.Summary s = post.summary(v);
      assertThat((Object) tree(AnalysisHelpers.estimatePayload(s, v, cids)))
          .as(name + " estimate")
          .isEqualTo(c.get("estimate"));
      assertThat(AnalysisHelpers.topProbability(s, v))
          .isCloseTo(c.get("topProbability").asDouble(), org.assertj.core.data.Offset.offset(1e-9));
      assertThat((Object) tree(AnalysisHelpers.weightsItems(s, cids, cnames, answers, wPrior)))
          .as(name + " weights")
          .isEqualTo(c.get("weightsItems"));
      assertThat((Object) tree(AnalysisHelpers.rankingsItems(s.mean(), v, s.pBest(), oids, onames)))
          .as(name + " rankings")
          .isEqualTo(c.get("rankingsItems"));

      List<DecisionAnalysis.ChangeCondition> cc = DecisionAnalysis.changeConditions(s.mean(), v);
      // 섭동 유지율은 난수라서 파이썬 값과 다를 수 있어, 같은 값을 받도록 골든에서 읽어 넣는다
      DecisionAnalysis.Stability st = DecisionAnalysis.stability(post, v, 1);
      assertThat(
              Json.MAPPER
                  .valueToTree(
                      AnalysisHelpers.stabilityPayload(st, s.pBest(), cc, oids, onames, cnames))
                  .get("text"))
          .as(name + " stability text")
          .isEqualTo(c.get("stability").get("text"));

      int top = DecisionAnalysis.ranking(s.mean(), v).get(0);
      List<DecisionAnalysis.ValueCondition> vc =
          DecisionAnalysis.valueConditions(s.mean(), v, criteria, scales, Golden.mat(c.get("raw")));
      assertThat(
              (Object)
                  tree(
                      AnalysisHelpers.changeConditionsPayload(
                          cc, top, cids, cnames, oids, onames, vc, units, levels)))
          .as(name + " changeConditions")
          .isEqualTo(c.get("changeConditions"));
    }
  }

  @Test
  void 안정성_응답의_결정적_필드가_같다() {
    JsonNode c = G.get("cases").get(0);
    Posterior post = new Posterior(Golden.mat(c.get("W")), Golden.vec(c.get("p")));
    double[][] v = Golden.mat(c.get("V"));
    Posterior.Summary s = post.summary(v);
    JsonNode ids = c.get("ids");
    List<String> cnames = strings(ids.get("criterionNames"));
    Map<String, Object> got =
        AnalysisHelpers.stabilityPayload(
            DecisionAnalysis.stability(post, v, 1),
            s.pBest(),
            DecisionAnalysis.changeConditions(s.mean(), v),
            strings(ids.get("optionIds")),
            strings(ids.get("optionNames")),
            cnames);
    JsonNode expected = c.get("stability");
    JsonNode actual = tree(got);
    for (String key : List.of("optionId", "score", "level", "competitors", "text")) {
      assertThat((Object) actual.get(key)).as(key).isEqualTo(expected.get(key));
    }
    // 섭동 유지율만 난수 차이를 허용한다
    assertThat(actual.get("localPerturbationKeepRate").asDouble())
        .isEqualTo(
            expected.get("localPerturbationKeepRate").asDouble(),
            org.assertj.core.data.Offset.offset(Golden.RANDOM_TOL));
  }

  @Test
  void 숨은_선호_응답이_골든과_같다() {
    JsonNode h = G.get("hidden");
    List<HiddenPreference.Mismatch> mism = new ArrayList<>();
    h.get("mismatches")
        .forEach(
            m ->
                mism.add(
                    new HiddenPreference.Mismatch(
                        m.get("statedOver").asInt(),
                        m.get("revealedOver").asInt(),
                        m.get("statedP").asDouble(),
                        m.get("revealedP").asDouble(),
                        m.get("nScenarios").asInt())));
    JsonNode c = G.get("cases").get(0);
    List<String> cids = strings(c.get("ids").get("criterionIds"));
    List<String> cnames = strings(c.get("ids").get("criterionNames"));
    assertThat((Object) tree(AnalysisHelpers.hiddenPreferencesPayload(mism, cids, cnames)))
        .isEqualTo(h.get("payload"));
  }

  @Test
  void 예상_질문_수_범위가_같다() {
    for (JsonNode e : G.get("expectedRange")) {
      StateRules.RoundKind kind =
          StateRules.RoundKind.valueOf(e.get("kind").asText().toUpperCase());
      List<Integer> got =
          AnalysisHelpers.expectedRange(
              kind, e.get("K").asInt(), e.get("answered").asInt(), e.get("limit").asInt());
      assertThat(got)
          .containsExactly(e.get("result").get(0).asInt(), e.get("result").get(1).asInt());
    }
  }

  @Test
  void 조사가_골든과_같다() {
    for (JsonNode p : G.get("particles")) {
      String w = p.get("word").asText();
      assertThat(Korean.iGa(w)).as("이/가 " + w).isEqualTo(p.get("iGa").asText());
      assertThat(Korean.eulReul(w)).as("을/를 " + w).isEqualTo(p.get("eulReul").asText());
      assertThat(Korean.euRo(w)).as("으로 " + w).isEqualTo(p.get("euRo").asText());
    }
  }

  @Test
  void 확신이_낮은지_판단이_같다() {
    for (JsonNode e : G.get("lowConfidence")) {
      assertThat(AnalysisHelpers.lowConfidence(e.get("p").asDouble()))
          .isEqualTo(e.get("result").asBoolean());
    }
  }
}

package com.seonbi.flow;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seonbi.ai.CallType;
import com.seonbi.ai.LlmCalls;
import com.seonbi.ai.LlmRouter;
import com.seonbi.ai.LlmSettings;
import com.seonbi.ai.ProviderAdapter;
import com.seonbi.ai.provider.FakeAdapter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 외부 AI 호출은 DB 트랜잭션 밖에서 해야 한다 (리뷰 O04). 호출을 기다리는 동안 결정 행 잠금과 DB 연결을 잡고 있지 않다는 것을 실제 제공자(fake가 아닌
 * 이름의 어댑터)가 설정된 상태에서 확인한다.
 */
@Import(AnalysisAiPhaseTest.Config.class)
class AnalysisAiPhaseTest extends FlowTestBase {

  static final List<Boolean> TX_ACTIVE_DURING_CALL = new CopyOnWriteArrayList<>();
  static final List<String> CALLS = new CopyOnWriteArrayList<>();

  /** 이름만 gemini인 가짜 어댑터. 호출될 때 트랜잭션이 열려 있는지 기록한다. */
  static final class TxProbeAdapter implements ProviderAdapter {
    private final FakeAdapter delegate = new FakeAdapter("m");

    @Override
    public String provider() {
      return "gemini";
    }

    @Override
    public String model() {
      return "m";
    }

    @Override
    public <T> Generated<T> generate(Class<T> schema, String prompt, int maxTokens) {
      TX_ACTIVE_DURING_CALL.add(TransactionSynchronizationManager.isActualTransactionActive());
      CALLS.add(schema.getSimpleName());
      Generated<T> g = delegate.generate(schema, prompt, maxTokens);
      return new Generated<>(
          g.parsed(), new com.seonbi.ai.LlmUsage("gemini", "m", 0, 0, 1, true, ""));
    }
  }

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    LlmRouter probeRouter() {
      LlmSettings settings =
          LlmSettings.fromEnv(
              Map.of(
                  "LLM_MODEL_FAST",
                  "gemini:m",
                  "LLM_MODEL_QUALITY",
                  "gemini:m",
                  "GEMINI_API_KEY",
                  "k"));
      return new LlmRouter(settings, Map.of("gemini", (spec, s) -> new TxProbeAdapter()), null);
    }

    @Bean
    @Primary
    LlmCalls probeCalls(LlmRouter probeRouter) {
      return new LlmCalls(probeRouter);
    }
  }

  @Test
  void 분석의_AI_호출은_트랜잭션_밖에서_하고_결과를_캐시한다() throws Exception {
    TX_ACTIVE_DURING_CALL.clear();
    CALLS.clear();
    Setup s = laptops();
    postJ(s.base() + "/questions/start", null, 200);
    postJ(s.base() + "/questions/stop", null, 200);

    // 질문 시작에서 한 문장 다듬기 호출은 빼고 분석 조회만 본다
    TX_ACTIVE_DURING_CALL.clear();
    CALLS.clear();
    JsonNode first = getJ(s.base() + "/analysis", 200);

    assertThat(CALLS).contains("SummarizeOptionsOutput", "ExplainResultOutput");
    assertThat(TX_ACTIVE_DURING_CALL).isNotEmpty().containsOnly(false);
    assertThat(first.path("explanation").path("summary").asText()).isNotBlank();
    assertThat(first.path("explanation").path("caveats").toString()).contains("1위 확률은");

    // 같은 버전이면 캐시를 써서 AI를 다시 부르지 않는다
    int calls = CALLS.size();
    getJ(s.base() + "/analysis", 200);
    assertThat(CALLS).hasSize(calls);
    assertThat(CallType.EXPLAIN_RESULT).isNotNull();
  }

  // 질문 문장 다듬기도 트랜잭션 밖에서 하고, 다듬은 문장은 저장돼 다시 조회해도 같다
  @Test
  void 질문_문장_다듬기는_트랜잭션_밖에서_하고_저장한다() throws Exception {
    TX_ACTIVE_DURING_CALL.clear();
    CALLS.clear();
    Setup s = laptops();

    JsonNode started = postJ(s.base() + "/questions/start", null, 200);

    assertThat(CALLS).contains("PhraseQuestionOutput");
    assertThat(TX_ACTIVE_DURING_CALL).isNotEmpty().containsOnly(false);
    String title = started.path("question").path("title").asText();
    assertThat(title).isEqualTo("두 가지 중 어느 쪽이 더 마음에 드세요?"); // 가짜 AI의 고정 문구가 반영됨
    // 새로고침해도 같은 문장 (저장됨), AI를 다시 부르지 않는다
    int calls = CALLS.size();
    JsonNode again = getJ(s.base() + "/questions/next", 200);
    assertThat(again.path("question").path("title").asText()).isEqualTo(title);
    assertThat(CALLS).hasSize(calls);

    // 답변하면 다음 질문도 같은 방식으로 다듬는다
    CALLS.clear();
    TX_ACTIVE_DURING_CALL.clear();
    JsonNode answered =
        postJ(
            s.base() + "/answers",
            Map.of(
                "questionId",
                started.path("question").path("id").asText(),
                "response",
                Map.of("value", 2)),
            201);
    if (!answered.path("next").path("done").asBoolean()) {
      assertThat(CALLS).contains("PhraseQuestionOutput");
      assertThat(TX_ACTIVE_DURING_CALL).containsOnly(false);
    }
  }
}

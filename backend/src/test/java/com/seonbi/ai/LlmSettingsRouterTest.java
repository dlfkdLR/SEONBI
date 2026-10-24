package com.seonbi.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seonbi.ai.LlmSchemas.PhraseQuestionOutput;
import com.seonbi.ai.LlmSchemas.RecommendCriteriaOutput;
import com.seonbi.ai.provider.FakeAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 여러 제공자·모델을 설정만으로 갈아 끼우는 구조 (개발기획서 6.4). */
class LlmSettingsRouterTest {

  @Test
  void 기본값은_fake다() {
    LlmSettings s = LlmSettings.defaults();
    assertThat(s.primaryFor(CallType.EXTRACT_CONDITIONS))
        .isEqualTo(new ModelSpec("fake", "default"));
    assertThat(s.primaryFor(CallType.EXPLAIN_RESULT)).isEqualTo(new ModelSpec("fake", "default"));
  }

  @Test
  void 환경변수에서_설정을_읽는다() {
    LlmSettings s =
        LlmSettings.fromEnv(
            Map.of(
                "LLM_MODEL_FAST", "gemini:gemini-2.5-flash",
                "LLM_MODEL_QUALITY", "anthropic:claude-sonnet-4-5",
                "LLM_FALLBACK_MODELS", "openai:gpt-4.1-mini, fake:default",
                "LLM_ROUTES", "phrase_question=openai:gpt-4.1-mini",
                "GEMINI_API_KEY", "g-key",
                "OPENAI_API_KEY", "o-key"));
    // 결과 설명만 QUALITY, 나머지는 FAST, LLM_ROUTES는 그 호출만 덮어쓴다
    assertThat(s.primaryFor(CallType.EXTRACT_CONDITIONS).toString())
        .isEqualTo("gemini:gemini-2.5-flash");
    assertThat(s.primaryFor(CallType.EXPLAIN_RESULT).toString())
        .isEqualTo("anthropic:claude-sonnet-4-5");
    assertThat(s.primaryFor(CallType.PHRASE_QUESTION).toString()).isEqualTo("openai:gpt-4.1-mini");
    assertThat(s.candidatesFor(CallType.EXTRACT_CONDITIONS).stream().map(ModelSpec::toString))
        .containsExactly("gemini:gemini-2.5-flash", "openai:gpt-4.1-mini", "fake:default");
    assertThat(s.apiKeys()).containsOnlyKeys("gemini", "openai");
  }

  @Test
  void 모델_지정_형식이_틀리면_설정_오류다() {
    for (String bad : List.of("gemini", ":모델", "gemini:", "unknown:x")) {
      assertThatThrownBy(() -> ModelSpec.parse(bad)).as(bad).isInstanceOf(LlmConfigException.class);
    }
    // 모델명에 ':'가 있어도 첫 ':'에서만 나눈다
    assertThat(ModelSpec.parse("gemini:tuned:v2").model()).isEqualTo("tuned:v2");
    assertThatThrownBy(() -> LlmSettings.fromEnv(Map.of("LLM_ROUTES", "nope=fake:x")))
        .isInstanceOf(LlmConfigException.class);
    assertThatThrownBy(() -> LlmSettings.fromEnv(Map.of("LLM_ROUTES", "explain_result")))
        .isInstanceOf(LlmConfigException.class);
  }

  @Test
  void 설정된_모델의_키가_없으면_시작할_때_알린다() {
    LlmSettings noKey = LlmSettings.fromEnv(Map.of("LLM_MODEL_FAST", "gemini:flash"));
    assertThatThrownBy(noKey::validate)
        .isInstanceOf(LlmConfigException.class)
        .hasMessageContaining("GEMINI_API_KEY");
    // 쓰지 않는 제공자의 키는 필요 없다
    LlmSettings ok =
        LlmSettings.fromEnv(Map.of("LLM_MODEL_FAST", "gemini:flash", "GEMINI_API_KEY", "k"));
    ok.validate();
  }

  @Test
  void 키_값은_로그에_남지_않는다() {
    LlmSettings s = LlmSettings.fromEnv(Map.of("GEMINI_API_KEY", "TOP-SECRET-KEY"));
    assertThat(s.toString()).doesNotContain("TOP-SECRET-KEY");
  }

  @Test
  void 모든_제공자_어댑터가_등록되어_있다() {
    LlmSettings s =
        LlmSettings.fromEnv(
            Map.of(
                "LLM_MODEL_FAST", "gemini:a",
                "LLM_MODEL_QUALITY", "anthropic:b",
                "LLM_FALLBACK_MODELS", "openai:c,fake:d",
                "GEMINI_API_KEY", "k1",
                "ANTHROPIC_API_KEY", "k2",
                "OPENAI_API_KEY", "k3"));
    LlmRouter router = new LlmRouter(s);
    router.validateSettings(); // 어댑터가 모두 만들어지면 통과
    assertThat(router.adapterFor(ModelSpec.parse("openai:c")).provider()).isEqualTo("openai");
    assertThat(router.adapterFor(ModelSpec.parse("anthropic:b")).provider()).isEqualTo("anthropic");
    assertThat(router.adapterFor(ModelSpec.parse("gemini:a")).provider()).isEqualTo("gemini");
  }

  /** 새 제공자는 어댑터 하나와 레지스트리 항목 하나로 붙는다. 서비스 코드·프롬프트·스키마는 바뀌지 않는다. */
  @Test
  void 새_제공자를_레지스트리에만_추가해서_붙일_수_있다() {
    FakeAdapter mine = new FakeAdapter("my-model");
    Map<String, LlmRouter.AdapterFactory> registry =
        new java.util.HashMap<>(LlmRouter.defaultRegistry());
    registry.put("fake", (spec, s) -> mine);
    LlmRouter router = new LlmRouter(LlmSettings.defaults(), registry, null);
    router
        .clientFor(CallType.PHRASE_QUESTION)
        .completeStructured(PhraseQuestionOutput.class, "p", 10);
    assertThat(mine.calls()).hasSize(1);
  }

  @Test
  void 호출_종류마다_다른_제공자를_쓸_수_있다() {
    FakeAdapter fast = new FakeAdapter("fast");
    FakeAdapter quality = new FakeAdapter("quality");
    Map<String, FakeAdapter> byModel = Map.of("fast", fast, "quality", quality);
    LlmRouter router =
        new LlmRouter(
            LlmSettings.fromEnv(
                Map.of("LLM_MODEL_FAST", "fake:fast", "LLM_MODEL_QUALITY", "fake:quality")),
            Map.of("fake", (spec, s) -> byModel.get(spec.model())),
            null);
    router
        .clientFor(CallType.PHRASE_QUESTION)
        .completeStructured(PhraseQuestionOutput.class, "p", 10);
    router
        .clientFor(CallType.EXPLAIN_RESULT)
        .completeStructured(LlmSchemas.ExplainResultOutput.class, "p", 10);
    assertThat(fast.calls()).hasSize(1);
    assertThat(quality.calls()).hasSize(1);
  }

  private static LlmRouter routerWith(
      Map<String, FakeAdapter> adapters, String fast, String fallback, List<LlmUsage> seen) {
    return new LlmRouter(
        LlmSettings.fromEnv(Map.of("LLM_MODEL_FAST", fast, "LLM_FALLBACK_MODELS", fallback)),
        Map.of("fake", (spec, s) -> adapters.get(spec.model())),
        seen::add);
  }

  @Test
  void 제공자_실패_시_대체_모델을_순서대로_시도한다() {
    FakeAdapter a = new FakeAdapter("a").push(new LlmException.ProviderError("down", false));
    FakeAdapter b = new FakeAdapter("b").push(new LlmException.ProviderError("down", false));
    FakeAdapter c = new FakeAdapter("c");
    List<LlmUsage> seen = new ArrayList<>();
    LlmRouter router = routerWith(Map.of("a", a, "b", b, "c", c), "fake:a", "fake:b,fake:c", seen);

    router
        .clientFor(CallType.RECOMMEND_CRITERIA)
        .completeStructured(RecommendCriteriaOutput.class, "카테고리: etc", 900);

    assertThat(a.calls()).hasSize(1);
    assertThat(b.calls()).hasSize(1);
    assertThat(c.calls()).hasSize(1);
    // 시도마다 기록이 남고, 실패 기록은 success=false
    assertThat(seen).extracting(LlmUsage::success).containsExactly(false, false, true);
    assertThat(seen).extracting(LlmUsage::model).containsExactly("a", "b", "c");
    assertThat(seen).allMatch(u -> u.callType().equals("recommend_criteria"));
  }

  @Test
  void 스키마_위반도_대체_모델로_넘어간다() {
    FakeAdapter a = new FakeAdapter("a").push("not json");
    FakeAdapter b = new FakeAdapter("b");
    LlmRouter router = routerWith(Map.of("a", a, "b", b), "fake:a", "fake:b", new ArrayList<>());
    assertThat(
            router
                .clientFor(CallType.PHRASE_QUESTION)
                .completeStructured(PhraseQuestionOutput.class, "p", 10))
        .isNotNull();
  }

  @Test
  void 모든_모델이_실패하면_사용_불가_오류다() {
    FakeAdapter a = new FakeAdapter("a").push(new LlmException.ProviderError("x", false));
    FakeAdapter b = new FakeAdapter("b").push(new LlmException.ProviderError("y", false));
    LlmRouter router = routerWith(Map.of("a", a, "b", b), "fake:a", "fake:b", new ArrayList<>());
    assertThatThrownBy(
            () ->
                router
                    .clientFor(CallType.PHRASE_QUESTION)
                    .completeStructured(PhraseQuestionOutput.class, "p", 10))
        .isInstanceOf(LlmException.AiUnavailable.class);
  }

  @Test
  void 대체_모델이_없으면_한_번만_시도한다() {
    FakeAdapter a = new FakeAdapter("a").push(new LlmException.ProviderError("x", false));
    LlmRouter router =
        new LlmRouter(
            LlmSettings.fromEnv(Map.of("LLM_MODEL_FAST", "fake:a")),
            Map.of("fake", (spec, s) -> a),
            null);
    assertThatThrownBy(
            () ->
                router
                    .clientFor(CallType.PHRASE_QUESTION)
                    .completeStructured(PhraseQuestionOutput.class, "p", 10))
        .isInstanceOf(LlmException.AiUnavailable.class);
    assertThat(a.calls()).hasSize(1);
  }

  @Test
  void 사용량_기록에는_프롬프트와_사용자_글이_없다() {
    List<LlmUsage> seen = new ArrayList<>();
    LlmRouter router =
        new LlmRouter(LlmSettings.defaults(), LlmRouter.defaultRegistry(), seen::add);
    router
        .clientFor(CallType.PHRASE_QUESTION)
        .completeStructured(PhraseQuestionOutput.class, "내-비밀-SECRET", 10);
    assertThat(seen).hasSize(1);
    assertThat(seen.toString()).doesNotContain("SECRET").doesNotContain("비밀");
  }

  @Test
  void 사용량_콜백이_실패해도_호출은_성공한다() {
    LlmRouter router =
        new LlmRouter(
            LlmSettings.defaults(),
            LlmRouter.defaultRegistry(),
            u -> {
              throw new IllegalStateException("DB 오류");
            });
    assertThat(
            router
                .clientFor(CallType.PHRASE_QUESTION)
                .completeStructured(PhraseQuestionOutput.class, "p", 10))
        .isNotNull();
  }

  @Test
  void 호출별_훅은_해당_호출의_사용량만_받는다() {
    List<LlmUsage> mine = new ArrayList<>();
    LlmRouter router = new LlmRouter(LlmSettings.defaults());
    router
        .clientFor(CallType.PHRASE_QUESTION, mine::add)
        .completeStructured(PhraseQuestionOutput.class, "p", 10);
    router
        .clientFor(CallType.PHRASE_QUESTION)
        .completeStructured(PhraseQuestionOutput.class, "p", 10);
    assertThat(mine).hasSize(1);
  }
}

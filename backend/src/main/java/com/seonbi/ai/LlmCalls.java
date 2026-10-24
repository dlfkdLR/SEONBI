package com.seonbi.ai;

import com.seonbi.ai.LlmSchemas.ExplainResultOutput;
import com.seonbi.ai.LlmSchemas.ExtractConditionsOutput;
import com.seonbi.ai.LlmSchemas.ExtractedItem;
import com.seonbi.ai.LlmSchemas.OptionProsCons;
import com.seonbi.ai.LlmSchemas.PhraseQuestionOutput;
import com.seonbi.ai.LlmSchemas.RecommendCriteriaOutput;
import com.seonbi.ai.LlmSchemas.RecommendCriterion;
import com.seonbi.ai.LlmSchemas.RecommendOptionsOutput;
import com.seonbi.ai.LlmSchemas.RecommendedOption;
import com.seonbi.ai.LlmSchemas.SummarizeOptionsOutput;
import com.seonbi.ai.Prompts.DecisionContext;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * LLM 호출 6종과 후처리 규칙 (개발기획서 6.2, 6.3, 7.3). 여기서는 아무것도 저장하지 않는다. 호출 상한(12.2)·ai_usage_logs 쓰기·결과 캐시는
 * 서비스 계층 몫이고, onUsage 훅으로 사용량을 받아 갈 수 있다.
 */
public final class LlmCalls {

  /** 호출 종류에 맞는 LlmClient를 고르는 자리. 라우터 또는 단일 클라이언트(테스트)를 쓴다. */
  @FunctionalInterface
  public interface ClientSource {
    LlmClient clientFor(CallType call, Consumer<LlmUsage> onUsage);
  }

  /** phrase_question에서 서버 값으로 인정하는 질문 객체의 문자열 키 (7.3 질문 객체). */
  private static final Set<String> PHRASE_SERVER_KEYS =
      Set.of("display", "range", "description", "title", "label");

  private final ClientSource source;

  public LlmCalls(ClientSource source) {
    this.source = source;
  }

  public LlmCalls(LlmRouter router) {
    this(router::clientFor);
  }

  public LlmCalls(LlmClient client) {
    this((call, onUsage) -> client);
  }

  private <T> T complete(
      CallType call, Class<T> schema, String prompt, Consumer<LlmUsage> onUsage) {
    return source.clientFor(call, onUsage).completeStructured(schema, prompt, call.maxTokens());
  }

  // ── extract_conditions ──────────────────────────────────────────

  /**
   * irrelevant가 true면 서비스가 422 IRRELEVANT_INPUT으로 응답한다(6.3 고정 문구). ambiguity는 needsClarification으로
   * 옮긴다.
   */
  public record ExtractResult(boolean irrelevant, List<ExtractedItem> items, String ambiguity) {}

  /** fallback 없음: 실패하면 AiUnavailable(서비스가 503으로). text 길이(1~500자)는 호출 전에 검증한다. */
  public ExtractResult extractConditions(
      DecisionContext ctx, String text, String questionContext, Consumer<LlmUsage> onUsage) {
    String prompt = Prompts.extractConditions(ctx, text, questionContext);
    ExtractConditionsOutput out;
    try {
      out = complete(CallType.EXTRACT_CONDITIONS, ExtractConditionsOutput.class, prompt, onUsage);
    } catch (LlmException e) {
      throw new LlmException.AiUnavailable("조건 추출에 실패했습니다", e);
    }
    if (!out.relevant() || out.items().isEmpty()) {
      return new ExtractResult(true, List.of(), null); // LLM의 자유 답변(ambiguity 포함)은 노출하지 않는다
    }
    return new ExtractResult(false, out.items(), out.ambiguity());
  }

  // ── recommend_criteria ──────────────────────────────────────────

  /** source는 7.3 23번의 "source"(ai | template)다. */
  public record CriteriaRecommendation(String source, List<RecommendCriterion> items) {}

  /** 카테고리별 기본 기준 목록(6.2)을 출력 스키마 항목으로 돌려준다. 상한 초과 대체에도 쓴다. */
  public static List<RecommendCriterion> templateCriteria(String category) {
    return DefaultCriteria.forCategory(category);
  }

  /** 실패·스키마 위반이면 오류 대신 기본 기준 목록(source="template"). */
  public CriteriaRecommendation recommendCriteria(DecisionContext ctx, Consumer<LlmUsage> onUsage) {
    String prompt = Prompts.recommendCriteria(ctx);
    try {
      RecommendCriteriaOutput out =
          complete(CallType.RECOMMEND_CRITERIA, RecommendCriteriaOutput.class, prompt, onUsage);
      return new CriteriaRecommendation("ai", out.criteria());
    } catch (LlmException e) {
      return new CriteriaRecommendation("template", templateCriteria(ctx.category()));
    }
  }

  // ── recommend_options (P2) ──────────────────────────────────────

  /** 실패하면 비어 있다(기능 숨김). */
  public Optional<List<RecommendedOption>> recommendOptions(
      DecisionContext ctx, int count, Consumer<LlmUsage> onUsage) {
    String prompt = Prompts.recommendOptions(ctx, count);
    try {
      RecommendOptionsOutput out =
          complete(CallType.RECOMMEND_OPTIONS, RecommendOptionsOutput.class, prompt, onUsage);
      return Optional.of(out.options().stream().limit(count).toList());
    } catch (LlmException e) {
      return Optional.empty();
    }
  }

  // ── phrase_question ─────────────────────────────────────────────

  /** LLM이 다듬은 문장. 호출하는 쪽이 아래 필드를 질문 객체에 덮어쓴다(값이 null이면 덮어쓰지 않는다). */
  public record PhrasedQuestion(
      String source,
      String title, // → title
      String optionADescription, // → payload.optionA.description
      String optionBDescription, // → payload.optionB.description
      String leftLabel, // → payload.left.label
      String rightLabel) {} // → payload.right.label

  /** spec은 7.3 질문 객체(서버 값 포함). 실패하거나 서버 값에 없는 숫자가 있으면 비어 있다 → 호출자가 템플릿을 쓴다. */
  public Optional<PhrasedQuestion> phraseQuestion(
      Map<String, Object> spec, Consumer<LlmUsage> onUsage) {
    String prompt = Prompts.phraseQuestion(spec);
    PhraseQuestionOutput out;
    try {
      out = complete(CallType.PHRASE_QUESTION, PhraseQuestionOutput.class, prompt, onUsage);
    } catch (LlmException e) {
      return Optional.empty();
    }
    List<String> texts =
        java.util.stream.Stream.of(
                out.title(), out.optionA(), out.optionB(), out.leftLabel(), out.rightLabel())
            .filter(t -> t != null && !t.isEmpty())
            .toList();
    if (OutputVerifier.hasForeignNumber(
        texts, OutputVerifier.serverNumbersStrict(spec, PHRASE_SERVER_KEYS))) {
      return Optional.empty();
    }
    return Optional.of(
        new PhrasedQuestion(
            "ai", out.title(), out.optionA(), out.optionB(), out.leftLabel(), out.rightLabel()));
  }

  // ── summarize_options ───────────────────────────────────────────

  /** 실패하면 비어 있다(숫자 비교표만 표시). */
  public Optional<List<OptionProsCons>> summarizeOptions(
      Map<String, Object> matrix, Consumer<LlmUsage> onUsage) {
    String prompt = Prompts.summarizeOptions(matrix);
    try {
      return Optional.of(
          complete(CallType.SUMMARIZE_OPTIONS, SummarizeOptionsOutput.class, prompt, onUsage)
              .options());
    } catch (LlmException e) {
      return Optional.empty();
    }
  }

  // ── explain_result ──────────────────────────────────────────────

  public record Explanation(
      String source, String summary, List<String> reasons, List<String> caveats) {}

  /** 출력의 숫자·단계 라벨이 입력 JSON에 없으면 비어 있다 → 호출자가 템플릿 설명을 쓴다(6.3). */
  public Optional<Explanation> explainResult(
      Map<String, Object> analysis, Consumer<LlmUsage> onUsage) {
    String prompt = Prompts.explainResult(analysis);
    ExplainResultOutput out;
    try {
      out = complete(CallType.EXPLAIN_RESULT, ExplainResultOutput.class, prompt, onUsage);
    } catch (LlmException e) {
      return Optional.empty();
    }
    java.util.ArrayList<String> texts = new java.util.ArrayList<>();
    texts.add(out.summary());
    texts.addAll(out.reasons());
    texts.addAll(out.caveats());
    if (OutputVerifier.hasForeignNumber(texts, OutputVerifier.serverNumbersLoose(analysis))
        || OutputVerifier.hasForeignLevelLabel(texts, analysis)) {
      return Optional.empty();
    }
    return Optional.of(new Explanation("ai", out.summary(), out.reasons(), out.caveats()));
  }
}

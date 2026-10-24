package com.seonbi.service;

import static com.seonbi.service.FlowErrors.details;

import com.fasterxml.jackson.core.type.TypeReference;
import com.seonbi.ai.Json;
import com.seonbi.ai.LlmCalls;
import com.seonbi.ai.LlmCalls.PhrasedQuestion;
import com.seonbi.core.FlowError;
import com.seonbi.domain.Decision;
import com.seonbi.domain.EstimateSnapshot;
import com.seonbi.domain.Question;
import com.seonbi.engine.Posterior;
import com.seonbi.engine.QuestionSelector;
import com.seonbi.engine.QuestionSpec;
import com.seonbi.engine.QuestionSpec.SliderSpec;
import com.seonbi.engine.Response;
import com.seonbi.engine.Scale;
import com.seonbi.engine.Stopping;
import com.seonbi.engine.Stopping.StopReason;
import com.seonbi.service.EngineBridge.DecisionData;
import com.seonbi.service.EngineBridge.EngineInput;
import com.seonbi.service.StateRules.Action;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 질문 흐름 서비스: 7.3의 31~38번 로직 (역할 문서 5.1·5.2, 개발기획서 4.2·4.3·5.5·5.10·5.14). 응답은 7.3 키 그대로의 camelCase
 * 맵이고, 컨트롤러가 응답 record로 바꿔 계약을 한 번 더 검사한다. 오류는 {@link FlowError}로 낸다.
 *
 * <p>결정 사항 (사용자 지시):
 *
 * <ul>
 *   <li>{@code decisions.question_limit}: 첫 라운드는 NULL(max_questions(K)는 서비스가 계산), 추가 라운드는 {@code
 *       extraRoundLimit(답변 수)}를 저장하고 {@code roundStart = question_limit − EXTRA_ROUND_QUESTIONS}. 둘
 *       다 {@code shouldStop}에 그대로 넘긴다.
 *   <li>질문 {@code spec}은 기준 id로 저장한다({@link EngineBridge}).
 * </ul>
 *
 * <p>명세의 모호한 부분 (가정, 파이썬 참조 구현과 같다):
 *
 * <ul>
 *   <li>31번: 준비되지 않았으면(readiness.missing) DRAFT·ANALYZED에서 422 NOT_READY. QUESTIONING(멱등)에서는 보지
 *       않는다. 물어볼 기준 쌍이 하나도 없으면(모든 기준 값이 같음 등) 422 NOT_READY(missing: NO_QUESTION_CANDIDATES). 오류가
 *       나면 이 요청의 쓰기는 모두 되돌려진다.
 *   <li>31번 round: QUESTIONING 재호출은 question_limit이 NULL이 아니면 "extra"다.
 *   <li>32번 ANALYZED·FINALIZED의 reason: 종료 이유를 저장하는 컬럼이 없어, 지금 상태로 shouldStop을 다시 계산한 이유를 쓰고 종료 조건이
 *       안 걸리면(사용자 조기 종료 등) "USER_STOPPED"를 쓴다. 33번의 ANALYZED·FINALIZED shouldStop도 같은 값이다.
 *   <li>엔진이 더 낼 질문이 없을 때(nextQuestion이 비어 있음) 32·37번은 "LOW_INFO_GAIN"으로 끝낸다.
 *   <li>33번 remainingUncertainty: 10~90% 구간 폭이 {@value #REMAINING_MIN_WIDTH} 이상인 기준을 폭 내림차순으로 최대
 *       {@value #REMAINING_MAX_ITEMS}개(명세에 규칙이 없어 상수로 둠).
 *   <li>34번 expectedRange: 첫 라운드 [3, max_questions(K)], 추가 라운드 [답변 수 + 1, question_limit](하한이 상한보다
 *       크면 상한으로 맞춘다).
 *   <li>38번 응답: 37번의 estimate와 같은 값을 {"estimate": ...}로 감싸 돌려준다.
 *   <li>지워진 기준을 가리키는 미답변 질문은 건너뛴다.
 *   <li>기준 추가({@link #onCriterionAdded})의 데이터는 추가 후 상태(새 기준 포함)로 읽는다. 값이 비어 있는 새 기준은 값이 채워질 때까지 질문
 *       후보에서 빠지고, 척도는 값이 채워진 뒤 질문 흐름이 처음 도는 때(31·32·37·38번) 고정한다.
 * </ul>
 */
@Service
@Transactional
public class QuestionFlowService {

  static final double REMAINING_MIN_WIDTH = 0.2;
  static final int REMAINING_MAX_ITEMS = 3;
  static final int EIG_DIGITS = 3; // 33번 bestNextInfoGain 반올림 (종료 기준 0.03 bit과 구별되도록 확률보다 한 자리 더)
  static final StopReason FALLBACK_REASON = StopReason.USER_STOPPED;
  static final StopReason NO_CANDIDATE_REASON = StopReason.LOW_INFO_GAIN;

  private final EntityManager em;
  private final DecisionResolver resolver;
  private final EngineBridge bridge;
  private final ConfigurationService configuration;
  private final AiUsageService usage;
  private final ObjectProvider<LlmCalls> llm;

  public QuestionFlowService(
      EntityManager em,
      DecisionResolver resolver,
      EngineBridge bridge,
      ConfigurationService configuration,
      AiUsageService usage,
      ObjectProvider<LlmCalls> llm) {
    this.em = em;
    this.resolver = resolver;
    this.bridge = bridge;
    this.configuration = configuration;
    this.usage = usage;
    this.llm = llm;
  }

  // ── 공통 ──────────────────────────────────────────────────────

  /** 한 번 읽은 결정의 행·엔진 입력 묶음. 쓰기 뒤에는 {@link #reload}로 다시 만든다. */
  private static final class Ctx {
    final Decision decision;
    DecisionData data;
    EngineInput inp;

    Ctx(Decision decision, DecisionData data, EngineInput inp) {
      this.decision = decision;
      this.data = data;
      this.inp = inp;
    }

    int answered() {
      return AnalysisHelpers.answeredCount(inp.answers());
    }
  }

  private Ctx read(Decision d) {
    em.flush(); // 방금 쓴 행이 조회에 보이게 한다
    DecisionData data = bridge.load(d);
    return new Ctx(d, data, bridge.build(data));
  }

  private Ctx reload(Ctx ctx) {
    Ctx fresh = read(ctx.decision);
    ctx.data = fresh.data;
    ctx.inp = fresh.inp;
    return ctx;
  }

  private Decision readable(UUID id, UUID clientId) {
    return resolver.get(id, clientId);
  }

  /** 쓰기 전에 결정 행을 잠가 같은 결정의 동시 요청이 겹치지 않게 한다. */
  private Decision writable(UUID id, UUID clientId) {
    Decision d = resolver.get(id, clientId);
    em.lock(d, LockModeType.PESSIMISTIC_WRITE);
    return d;
  }

  private static DecisionStatus statusOf(Decision d) {
    return DecisionStatus.valueOf(d.getStatus());
  }

  private static void check(Action action, Decision d) {
    FlowErrors.raiseIf(StateRules.check(action, statusOf(d)), details("decisionId", d.getId()));
  }

  /** 고정할 척도를 기준 행에 저장한다(scale_frozen = true). 쓰기 후 {@link #reload}가 필요하다. */
  private void persistFreeze(DecisionData data, Map<String, Scale> scales) {
    for (com.seonbi.domain.Criterion c : data.criteria()) {
      Scale s = scales.get(c.getId().toString());
      if (s != null) {
        c.setScaleMin(BigDecimal.valueOf(s.min()));
        c.setScaleMax(BigDecimal.valueOf(s.max()));
        c.setScaleFrozen(true);
      }
    }
  }

  private Ctx readFrozen(Decision d, boolean freeze) {
    Ctx ctx = read(d);
    if (freeze && statusOf(d) != DecisionStatus.DRAFT) {
      Map<String, Scale> scales = bridge.pendingScaleFreeze(ctx.data);
      if (!scales.isEmpty()) {
        persistFreeze(ctx.data, scales);
        reload(ctx);
      }
    }
    return ctx;
  }

  private static Posterior fit(EngineInput inp) {
    return Posterior.fit(inp.seed(), inp.k(), inp.answers());
  }

  private static Integer[] limits(Decision d) {
    Integer ql = d.getQuestionLimit();
    return new Integer[] {ql, ql == null ? null : ql - Stopping.EXTRA_ROUND_QUESTIONS};
  }

  private record Stop(boolean stop, StopReason reason, double eig) {}

  private Stop stopDecision(Ctx ctx, Posterior post) {
    EngineInput inp = ctx.inp;
    double eig = QuestionSelector.bestInfoGain(post, inp.v(), inp.criteria(), inp.scales());
    Integer[] lim = limits(ctx.decision);
    Stopping.Decision d =
        Stopping.shouldStop(post, inp.v(), inp.answers(), eig, false, lim[0], lim[1]);
    return new Stop(d.stop(), d.reason(), eig);
  }

  private static Map<String, Object> questionObject(Question q) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", q.getId().toString());
    out.put("seq", q.getSeq());
    out.putAll(q.getDisplay());
    return out;
  }

  private static Map<String, Object> answerObject(com.seonbi.domain.Answer a) {
    return details(
        "id", a.getId().toString(),
        "questionId", a.getQuestion().getId().toString(),
        "response", a.getResponse(),
        "isValid", a.getIsValid(),
        "createdAt", a.getCreatedAt().toString());
  }

  /** 답변 행이 없는 가장 앞선 질문. 지워진 기준을 가리키는 질문은 건너뛴다. */
  private static Optional<Question> unanswered(Ctx ctx) {
    Set<UUID> answeredIds = new java.util.HashSet<>();
    ctx.data.answers().forEach(a -> answeredIds.add(a.getQuestion().getId()));
    return ctx.data.questions().stream()
        .sorted(Comparator.comparing(Question::getSeq))
        .filter(q -> !answeredIds.contains(q.getId()))
        .filter(
            q -> {
              try {
                EngineBridge.specFromMap(q.getSpec(), ctx.inp.criterionIds());
                return true;
              } catch (RuntimeException e) {
                return false;
              }
            })
        .findFirst();
  }

  private static List<Integer> expectedRange(Ctx ctx) {
    Integer ql = ctx.decision.getQuestionLimit();
    int k = ctx.inp.k();
    int answered = ctx.answered();
    if (ql == null) {
      return AnalysisHelpers.expectedRange(
          StateRules.RoundKind.INITIAL, k, answered, Stopping.maxQuestions(k));
    }
    List<Integer> r = AnalysisHelpers.expectedRange(StateRules.RoundKind.EXTRA, k, answered, ql);
    return List.of(Math.min(r.get(0), r.get(1)), r.get(1));
  }

  private StopReason finalReason(Ctx ctx) {
    Stop s = stopDecision(ctx, fit(ctx.inp));
    return s.reason() == null ? FALLBACK_REASON : s.reason();
  }

  // ── 질문 만들기 ───────────────────────────────────────────────

  private static Map<String, Object> deepCopy(Map<String, Object> m) {
    return Json.MAPPER.convertValue(m, new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  private Function<Map<String, Object>, Optional<PhrasedQuestion>> phraser(
      UUID clientId, UUID decisionId) {
    LlmCalls calls = llm.getIfAvailable();
    if (calls == null) {
      return null;
    }
    return spec -> calls.phraseQuestion(spec, usage.recorder(clientId, decisionId));
  }

  private record Phrased(Map<String, Object> display, String source) {}

  /** AI 문장을 덮어쓴다 (6.2: title, optionA/B.description, left/right.label만). 실패·없으면 템플릿. */
  @SuppressWarnings("unchecked")
  private static Phrased phrase(
      Map<String, Object> obj,
      Map<String, Object> display,
      Function<Map<String, Object>, Optional<PhrasedQuestion>> phraser) {
    if (phraser == null) {
      return new Phrased(display, "template");
    }
    Optional<PhrasedQuestion> result;
    try {
      result = phraser.apply(deepCopy(obj));
    } catch (RuntimeException e) { // LLM 계층의 어떤 실패도 질문 흐름을 막지 않는다 (6.3)
      return new Phrased(display, "template");
    }
    if (result.isEmpty()) {
      return new Phrased(display, "template");
    }
    PhrasedQuestion r = result.get();
    Map<String, Object> out = deepCopy(display);
    Map<String, Object> payload = (Map<String, Object>) out.get("payload");
    if (r.title() != null && !r.title().isEmpty()) {
      out.put("title", r.title());
    }
    String[][] options = {{"optionA", r.optionADescription()}, {"optionB", r.optionBDescription()}};
    for (String[] o : options) {
      Object side = payload.get(o[0]);
      if (o[1] != null
          && side instanceof Map<?, ?> m
          && m.get("description") instanceof String template
          && informative(template, o[1])) {
        ((Map<String, Object>) side).put("description", o[1]);
      }
    }
    String[][] labels = {{"left", r.leftLabel()}, {"right", r.rightLabel()}};
    for (String[] l : labels) {
      Object side = payload.get(l[0]);
      if (l[1] != null && !l[1].isEmpty() && side instanceof Map<?, ?>) {
        ((Map<String, Object>) side).put("label", l[1]);
      }
    }
    return new Phrased(out, r.source());
  }

  /**
   * AI가 쓴 선택지 설명이 쓸 만한지 본다. "A안"처럼 내용이 없는 문장이 템플릿(기준 이름과 차이를 적은 문장)을 덮어쓰면 사용자가 무엇을 비교하는지 알 수 없다. 8자
   * 이상이고 템플릿에 나온 단어(기준 이름 등)를 하나라도 포함해야 받아들인다.
   */
  static boolean informative(String template, String candidate) {
    if (candidate == null || candidate.strip().length() < 8) {
      return false;
    }
    for (String word : template.split("[^\\p{L}\\p{N}]+")) {
      if (word.length() >= 2 && !word.matches("\\d+") && candidate.contains(word)) {
        return true;
      }
    }
    return false;
  }

  /** 엔진이 다음 질문을 골라 행으로 만든다(저장은 호출하는 쪽). 낼 질문이 없으면 비어 있다. */
  private Optional<Question> makeQuestion(
      Ctx ctx, Posterior post, Function<Map<String, Object>, Optional<PhrasedQuestion>> phraser) {
    EngineInput inp = ctx.inp;
    Optional<QuestionSpec> next =
        QuestionSelector.nextQuestion(
            post, inp.v(), inp.criteria(), inp.history(), inp.scales(), inp.covered());
    if (next.isEmpty()) {
      return Optional.empty();
    }
    QuestionSpec spec = next.get();
    Float gain =
        QuestionSelector.scoreCandidates(post, inp.v(), inp.criteria(), inp.scales()).stream()
            .filter(c -> c.spec().equals(spec))
            .map(c -> (float) c.eig())
            .findFirst()
            .orElse(null);
    int seq = ctx.data.questions().stream().mapToInt(Question::getSeq).max().orElse(0) + 1;
    Question q = new Question();
    Map<String, Object> display =
        QuestionText.buildQuestion(spec, inp.metas(), inp.scales(), inp.category());
    Map<String, Object> forAi = new LinkedHashMap<>();
    forAi.put("id", q.getId().toString());
    forAi.put("seq", seq);
    forAi.putAll(display);
    Phrased phrased = phrase(forAi, display, phraser);
    q.setDecision(ctx.decision);
    q.setSeq(seq);
    q.setType(EngineBridge.questionTypeOf(spec));
    q.setSpec(EngineBridge.specToMap(spec, inp.criterionIds()));
    q.setDisplay(phrased.display());
    q.setPhrasingSource(phrased.source());
    q.setOrigin("engine");
    q.setInfoGain(gain);
    return Optional.of(q);
  }

  /** QUESTIONING에서 32번·37번 next: 미답변 질문 → 종료 판단 → 새 질문. 종료면 ANALYZED로 바꾼다. */
  private Map<String, Object> nextPayload(
      Ctx ctx, Function<Map<String, Object>, Optional<PhrasedQuestion>> phraser) {
    Optional<Question> pending = unanswered(ctx);
    if (pending.isPresent()) {
      return details("done", false, "question", questionObject(pending.get()));
    }
    Posterior post = fit(ctx.inp);
    Stop stop = stopDecision(ctx, post);
    StopReason reason = stop.reason();
    if (!stop.stop()) {
      Optional<Question> row = makeQuestion(ctx, post, phraser);
      if (row.isPresent()) {
        em.persist(row.get());
        ctx.data.questions().add(row.get());
        return details("done", false, "question", questionObject(row.get()));
      }
      reason = NO_CANDIDATE_REASON;
    }
    ctx.decision.setStatus(DecisionStatus.ANALYZED.name());
    return details("done", true, "reason", reason.name());
  }

  // ── 31 · 32 ───────────────────────────────────────────────────

  /** 31번 POST /questions/start. */
  public Map<String, Object> startQuestions(UUID decisionId, UUID clientId) {
    Decision d = writable(decisionId, clientId);
    DecisionStatus status = statusOf(d);
    check(Action.QUESTION_START, d);
    Function<Map<String, Object>, Optional<PhrasedQuestion>> phraser =
        phraser(clientId, decisionId);

    @SuppressWarnings("unchecked")
    List<String> missing =
        status == DecisionStatus.QUESTIONING
            ? List.of()
            : (List<String>) configuration.computeReadiness(d).get("missing");
    if (!missing.isEmpty()) {
      throw FlowErrors.notReady("질문을 시작할 수 없습니다.", details("missing", missing));
    }

    if (status == DecisionStatus.QUESTIONING) { // 멱등: 현재 질문을 그대로
      Ctx ctx = readFrozen(d, true);
      boolean extra = d.getQuestionLimit() != null;
      Optional<Question> row = unanswered(ctx);
      if (row.isEmpty()) {
        row = makeQuestion(ctx, fit(ctx.inp), phraser);
        if (row.isEmpty()) {
          throw noCandidates(d);
        }
        em.persist(row.get());
        ctx.data.questions().add(row.get());
      }
      return startPayload(ctx, row.get(), extra);
    }

    // DRAFT → 첫 라운드, ANALYZED → 추가 라운드. 질문을 만들 수 없으면 예외로 이 요청의 쓰기를 모두 되돌린다.
    Ctx ctx = read(d);
    Map<String, Scale> scales = bridge.pendingScaleFreeze(ctx.data);
    if (!scales.isEmpty()) {
      persistFreeze(ctx.data, scales);
      reload(ctx);
    }
    boolean extra = status == DecisionStatus.ANALYZED;
    Integer limit = extra ? Stopping.extraRoundLimit(ctx.answered()) : null;
    Optional<Question> row = unanswered(ctx);
    if (row.isEmpty()) {
      row = makeQuestion(ctx, fit(ctx.inp), phraser);
      if (row.isEmpty()) {
        throw noCandidates(d);
      }
      em.persist(row.get());
      ctx.data.questions().add(row.get());
    }
    d.setQuestionLimit(limit);
    d.setStatus(StateRules.afterQuestionStart(status).name());
    return startPayload(ctx, row.get(), extra);
  }

  private static FlowError noCandidates(Decision d) {
    return FlowErrors.notReady(
        "물어볼 수 있는 기준 쌍이 없습니다.",
        details("missing", List.of("NO_QUESTION_CANDIDATES"), "decisionId", d.getId()));
  }

  private static Map<String, Object> startPayload(Ctx ctx, Question row, boolean extra) {
    return details(
        "status",
        "QUESTIONING",
        "round",
        extra ? "extra" : "initial",
        "question",
        questionObject(row),
        "progress",
        details("answered", ctx.answered(), "expectedRange", expectedRange(ctx)));
  }

  /** 32번 GET /questions/next. */
  public Map<String, Object> nextQuestion(UUID decisionId, UUID clientId) {
    Decision d = writable(decisionId, clientId);
    check(Action.QUESTION_READ, d);
    DecisionStatus status = statusOf(d);
    if (status == DecisionStatus.ANALYZED || status == DecisionStatus.FINALIZED) {
      return details("done", true, "reason", finalReason(read(d)).name());
    }
    Ctx ctx = readFrozen(d, true);
    return nextPayload(ctx, phraser(clientId, decisionId));
  }

  // ── 33 · 34 · 35 · 36 ─────────────────────────────────────────

  /** 33번 GET /questions/status. 조회만 한다. */
  @Transactional(readOnly = true)
  public Map<String, Object> questionStatus(UUID decisionId, UUID clientId) {
    Decision d = readable(decisionId, clientId);
    check(Action.QUESTION_READ, d);
    Ctx ctx = read(d);
    EngineInput inp = ctx.inp;
    Posterior post = fit(inp);
    Posterior.Summary s = post.summary(inp.v());
    Stop stop = stopDecision(ctx, post);
    boolean shouldStop = stop.stop();
    StopReason reason = stop.reason();
    DecisionStatus status = statusOf(d);
    if (status == DecisionStatus.ANALYZED || status == DecisionStatus.FINALIZED) {
      shouldStop = true;
      reason = reason == null ? FALLBACK_REASON : reason;
    }
    int top = com.seonbi.engine.DecisionAnalysis.ranking(s.mean(), inp.v()).get(0);
    List<Integer> uncertain = new ArrayList<>();
    for (int k = 0; k < inp.k(); k++) {
      if (s.intervals()[k][1] - s.intervals()[k][0] >= REMAINING_MIN_WIDTH) {
        uncertain.add(k);
      }
    }
    uncertain.sort(
        Comparator.comparingDouble((Integer k) -> -(s.intervals()[k][1] - s.intervals()[k][0])));
    List<Object> remaining = new ArrayList<>();
    for (int k : uncertain.stream().limit(REMAINING_MAX_ITEMS).toList()) {
      remaining.add(
          details(
              "criterionId", inp.criterionIds().get(k),
              "interval",
                  List.of(
                      AnalysisHelpers.p(s.intervals()[k][0]),
                      AnalysisHelpers.p(s.intervals()[k][1]))));
    }
    return details(
        "shouldStop",
        shouldStop,
        "reason",
        reason == null ? null : reason.name(),
        "metrics",
        details(
            "answered", ctx.answered(),
            "topOption", inp.optionIds().get(top),
            "topProbability", AnalysisHelpers.p(AnalysisHelpers.topProbability(s, inp.v())),
            "decisionEntropy", AnalysisHelpers.p(s.entropy()),
            "bestNextInfoGain", round(stop.eig(), EIG_DIGITS)),
        "remainingUncertainty",
        remaining);
  }

  private static double round(double x, int digits) {
    return BigDecimal.valueOf(x).setScale(digits, java.math.RoundingMode.HALF_EVEN).doubleValue();
  }

  /** 34번 GET /questions/progress. */
  @Transactional(readOnly = true)
  public Map<String, Object> questionProgress(UUID decisionId, UUID clientId) {
    Decision d = readable(decisionId, clientId);
    check(Action.QUESTION_READ, d);
    Ctx ctx = read(d);
    Posterior.Summary s = fit(ctx.inp).summary(ctx.inp.v());
    return details(
        "answered", ctx.answered(),
        "expectedRange", expectedRange(ctx),
        "confidence", AnalysisHelpers.p(AnalysisHelpers.topProbability(s, ctx.inp.v())));
  }

  /** 35번 POST /questions/stop. QUESTIONING에서만, ANALYZED로 바꾼다. */
  public Map<String, Object> stopQuestions(UUID decisionId, UUID clientId) {
    Decision d = writable(decisionId, clientId);
    check(Action.ANSWER_SUBMIT_OR_STOP, d);
    Ctx ctx = read(d);
    Posterior.Summary s = fit(ctx.inp).summary(ctx.inp.v());
    boolean low = AnalysisHelpers.lowConfidence(AnalysisHelpers.topProbability(s, ctx.inp.v()));
    d.setStatus(DecisionStatus.ANALYZED.name());
    return details("reason", "USER_STOPPED", "lowConfidence", low);
  }

  /** 36번 GET /answers. 모든 상태에서 된다. 무효 답변도 isValid=false로 포함한다. */
  @Transactional(readOnly = true)
  public Map<String, Object> listAnswers(UUID decisionId, UUID clientId) {
    Decision d = readable(decisionId, clientId);
    DecisionData data = bridge.load(d);
    List<Object> items = new ArrayList<>();
    data.answers().stream()
        .sorted(Comparator.comparing(a -> a.getQuestion().getSeq()))
        .forEach(
            a -> {
              Question q = a.getQuestion();
              Map<String, Object> question = questionObject(q);
              question.put("origin", q.getOrigin());
              Map<String, Object> item = answerObject(a);
              item.put("question", question);
              items.add(item);
            });
    return details("items", items);
  }

  // ── 37 · 38 ───────────────────────────────────────────────────

  /** 37번 POST /answers → {answer, estimate, next}. */
  public Map<String, Object> submitAnswer(
      UUID decisionId, UUID clientId, UUID questionId, Map<String, Object> response) {
    Decision d = writable(decisionId, clientId);
    check(Action.ANSWER_SUBMIT_OR_STOP, d);
    Ctx ctx = readFrozen(d, true);
    Question question =
        ctx.data.questions().stream()
            .filter(q -> q.getId().equals(questionId))
            .findFirst()
            .orElseThrow(
                () ->
                    FlowErrors.notFound(
                        "질문을 찾을 수 없습니다.", details("questionId", questionId.toString())));
    if (ctx.data.answers().stream().anyMatch(a -> a.getQuestion().getId().equals(questionId))) {
      throw FlowErrors.conflict(
          "QUESTION_ALREADY_ANSWERED",
          "이미 답한 질문입니다. 수정은 PATCH를 쓰세요.",
          details("questionId", questionId.toString()));
    }
    QuestionSpec spec = specOf(question, ctx);
    Response parsed = EngineBridge.parseResponse(question.getType(), response);
    com.seonbi.domain.Answer row = new com.seonbi.domain.Answer();
    row.setQuestion(question);
    row.setDecision(d);
    row.setResponse(EngineBridge.responseToMap(question.getType(), parsed));
    row.setPreferenceKind(EngineBridge.preferenceKindOf(spec));
    row.setIsValid(true);
    em.persist(row);
    resolver.bumpVersion(d);
    reload(ctx);
    Posterior.Summary s = fit(ctx.inp).summary(ctx.inp.v());
    Map<String, Object> estimate =
        AnalysisHelpers.estimatePayload(s, ctx.inp.v(), ctx.inp.criterionIds());
    recordSnapshot(d, ctx, s);
    Map<String, Object> next = nextPayload(ctx, phraser(clientId, decisionId));
    return details("answer", answerObject(row), "estimate", estimate, "next", next);
  }

  /** 38번 PATCH /answers/{answerId} → {estimate}. QUESTIONING·ANALYZED에서 되고 상태는 그대로다. */
  public Map<String, Object> editAnswer(
      UUID decisionId, UUID clientId, UUID answerId, Map<String, Object> response) {
    Decision d = writable(decisionId, clientId);
    check(Action.ANSWER_EDIT, d);
    Ctx ctx = readFrozen(d, true);
    com.seonbi.domain.Answer answer =
        ctx.data.answers().stream()
            .filter(a -> a.getId().equals(answerId))
            .findFirst()
            .orElseThrow(
                () ->
                    FlowErrors.notFound(
                        "답변을 찾을 수 없습니다.", details("answerId", answerId.toString())));
    if (!Boolean.TRUE.equals(answer.getIsValid())) {
      throw FlowErrors.conflict(
          "INVALID_STATE", "무효가 된 답변은 수정할 수 없습니다.", details("answerId", answerId.toString()));
    }
    Question question = answer.getQuestion();
    QuestionSpec spec = specOf(question, ctx);
    Response parsed = EngineBridge.parseResponse(question.getType(), response);
    answer.setResponse(EngineBridge.responseToMap(question.getType(), parsed));
    answer.setPreferenceKind(EngineBridge.preferenceKindOf(spec));
    answer.setUpdatedAt(Instant.now());
    resolver.bumpVersion(d);
    reload(ctx);
    Posterior.Summary s = fit(ctx.inp).summary(ctx.inp.v());
    recordSnapshot(d, ctx, s);
    return details(
        "estimate", AnalysisHelpers.estimatePayload(s, ctx.inp.v(), ctx.inp.criterionIds()));
  }

  /** estimate_snapshots(P1)에 이번 추정을 남긴다. */
  private void recordSnapshot(Decision d, Ctx ctx, Posterior.Summary s) {
    EstimateSnapshot row = new EstimateSnapshot();
    row.setDecision(d);
    row.setAfterAnswerCount(Stopping.countedAnswers(ctx.inp.answers()));
    List<Map<String, Object>> weights = new ArrayList<>();
    for (int k = 0; k < ctx.inp.k(); k++) {
      weights.add(details("criterionId", ctx.inp.criterionIds().get(k), "mean", s.mean()[k]));
    }
    row.setWeights(weights);
    row.setTopProbability((float) AnalysisHelpers.topProbability(s, ctx.inp.v()));
    row.setEntropy((float) s.entropy());
    em.persist(row);
  }

  private static QuestionSpec specOf(Question question, Ctx ctx) {
    try {
      return EngineBridge.specFromMap(question.getSpec(), ctx.inp.criterionIds());
    } catch (RuntimeException e) {
      throw FlowErrors.conflict(
          "INVALID_STATE",
          "삭제된 기준이 들어간 질문입니다.",
          details("questionId", question.getId().toString()));
    }
  }

  // ── 자유 입력(29번)·기준 추가(19번)가 부르는 함수 ──────────────

  /**
   * 29번 {@code type: preference}: 가상 slider_pair 질문(origin=free_text)과 답변(stated)을 만든다. DRAFT에서도
   * 된다. 호출하는 쪽(C)의 트랜잭션 안에서 부른다.
   */
  public Map<String, Object> addStatedPreference(
      Decision d, UUID criterionAId, UUID criterionBId, int value) {
    check(Action.FREE_INPUT, d);
    Ctx ctx = read(d);
    List<String> ids = ctx.inp.criterionIds();
    for (UUID cid : List.of(criterionAId, criterionBId)) {
      if (!ids.contains(cid.toString())) {
        throw FlowErrors.notFound("기준을 찾을 수 없습니다.", details("criterionId", cid.toString()));
      }
    }
    if (criterionAId.equals(criterionBId)) {
      throw FlowErrors.validation(
          "서로 다른 두 기준이어야 합니다.", details("criterionB", "서로 다른 두 기준이어야 합니다."));
    }
    Response parsed =
        EngineBridge.parseResponse("slider_pair", Map.of("value", value)); // −5..5 정수 검사
    SliderSpec spec =
        new SliderSpec(ids.indexOf(criterionAId.toString()), ids.indexOf(criterionBId.toString()));
    Map<String, Object> display;
    try {
      display =
          QuestionText.buildQuestion(
              spec,
              bridge.criterionMetas(ctx.data),
              bridge.computeScales(ctx.data),
              d.getCategory());
    } catch (IllegalArgumentException e) {
      throw FlowErrors.notReady(
          "질문 범위를 만들 수 없습니다.",
          details("missing", List.of("CRITERION_RANGE"), "reason", e.getMessage()));
    }
    Question q = new Question();
    q.setDecision(d);
    q.setSeq(ctx.data.questions().stream().mapToInt(Question::getSeq).max().orElse(0) + 1);
    q.setType("slider_pair");
    q.setSpec(EngineBridge.specToMap(spec, ids));
    q.setDisplay(display);
    q.setPhrasingSource("template");
    q.setOrigin("free_text");
    em.persist(q);
    com.seonbi.domain.Answer a = new com.seonbi.domain.Answer();
    a.setQuestion(q);
    a.setDecision(d);
    a.setResponse(EngineBridge.responseToMap("slider_pair", parsed));
    a.setPreferenceKind("stated");
    a.setIsValid(true);
    em.persist(a);
    resolver.bumpVersion(d);
    return details("questionId", q.getId().toString(), "answerId", a.getId().toString());
  }

  /**
   * 19·29번: 기준 추가 후 상태·상한 처리. 상태가 바뀌었으면 "QUESTIONING"(statusChanged), 아니면 null. ANALYZED →
   * QUESTIONING(추가 라운드, 상한 = 답변 수 + 3). QUESTIONING이면 답변 수 + 3이 현재 상한보다 클 때만 상한을 {@code
   * raisedLimit}으로 올린다(그러면 NULL이던 상한도 값이 생긴다). DRAFT는 아무것도 하지 않는다.
   */
  public String onCriterionAdded(Decision d, UUID criterionId) {
    check(Action.CONFIG_EDIT, d);
    DecisionStatus status = statusOf(d);
    if (status == DecisionStatus.DRAFT) {
      return null;
    }
    Ctx ctx = read(d);
    int answered = ctx.answered();
    if (status == DecisionStatus.ANALYZED) {
      d.setQuestionLimit(Stopping.extraRoundLimit(answered));
      d.setStatus(StateRules.afterCriterionAdded(status).name());
      return "QUESTIONING";
    }
    int effective =
        d.getQuestionLimit() != null ? d.getQuestionLimit() : Stopping.maxQuestions(ctx.inp.k());
    if (answered + Stopping.EXTRA_ROUND_QUESTIONS > effective) {
      d.setQuestionLimit(Stopping.raisedLimit(effective, answered));
    }
    return null;
  }
}

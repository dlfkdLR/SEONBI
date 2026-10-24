package com.seonbi.service;

import static com.seonbi.service.FlowErrors.details;

import com.fasterxml.jackson.core.type.TypeReference;
import com.seonbi.ai.CallType;
import com.seonbi.ai.Json;
import com.seonbi.ai.LlmCalls;
import com.seonbi.ai.LlmRouter;
import com.seonbi.ai.LlmSchemas.OptionProsCons;
import com.seonbi.domain.AnalysisResult;
import com.seonbi.domain.Decision;
import com.seonbi.domain.FinalChoice;
import com.seonbi.domain.Option;
import com.seonbi.domain.OptionValue;
import com.seonbi.engine.DecisionAnalysis;
import com.seonbi.engine.DecisionAnalysis.ChangeCondition;
import com.seonbi.engine.DecisionAnalysis.ValueCondition;
import com.seonbi.engine.HiddenPreference;
import com.seonbi.engine.Posterior;
import com.seonbi.service.EngineBridge.DecisionData;
import com.seonbi.service.EngineBridge.EngineInput;
import com.seonbi.service.StateRules.Action;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 서비스 39~46번 (개발기획서 4.2, 5.4~5.9, 6.2, 7.3, 12.2). 일반 camelCase 맵을 돌려주고, 컨트롤러가 응답 record로 바꿔 계약을
 * 한 번 더 검사한다.
 *
 * <ul>
 *   <li>캐시({@code analysis_results}): {@code decision_version == decisions.version}일 때만 유효하다.
 *       {@code payload}는 결정적으로 계산한 39번 본문에서 provisional·optionSummaries·explanation을 뺀 것이고(상태는 버전을
 *       올리지 않으므로 provisional은 읽을 때 붙인다), AI가 만든 문장(optionSummaries·explanation)은 {@code
 *       explanation}에 같은 버전 동안 한 번만 만들어 둔다(12.2). 40·41·44·45번은 같은 본문에서 꺼내므로 숫자가 39번과 항상 같다.
 *   <li>FINALIZED: 확정 시점에 저장한 recommendation_snapshot(39번 전체)을 그대로 돌려준다. 42·43·46번(P1)은 확정 후 입력이 바뀔
 *       수 없으므로 시드+답변으로 다시 계산한다.
 * </ul>
 *
 * <p>명세의 모호한 부분 (가정, 파이썬 참조 구현과 같다):
 *
 * <ul>
 *   <li>분석 조회의 "답변 수"는 유효 답변 전체(잘 모르겠어요·자유 입력 가상 답변 포함)로 센다.
 *   <li>제외되지 않은 선택지가 하나도 없으면 422 NOT_READY(추천할 선택지가 없다; 명세에 없음).
 *   <li>AI 문장은 실제 LLM 제공자가 설정되어 있을 때만 시도하고, 시도했으면(실패해 템플릿이 되어도) 그 버전의 explanation에 저장해 다시 부르지 않는다.
 *       설정이 fake(로컬·테스트 기본)이면 템플릿을 매번 계산만 하고 저장하지 않는다.
 *   <li>42번: 값이 비어 있는 칸은 셀을 만들지 않는다. rawValue는 숫자·단계형은 입력값, 순위 입력은 그룹 번호, 예/아니요는 1/0이다.
 *   <li>43번: crossovers는 기준마다 5.8의 "가장 가까운 전환점 1개"만 담는다. 알 수 없는 criterionId는 400 VALIDATION_ERROR다.
 *   <li>46번: 가중치는 모든 기준을 정확히 한 번씩 담아야 하고(누락·중복·알 수 없는 id는 400), 음수·비유한수·합 0도 400이다.
 *       normalizedWeights는 소수 넷째 자리, 점수는 둘째 자리 반올림. changedFromCurrent는 순위 순서가 달라졌는지만 본다.
 *   <li>템플릿 optionSummaries: 기준별로 값이 가장 좋은(정규화 값 최대) 선택지에 pros, 가장 나쁜 선택지에 cons를 붙인다(모든 선택지가 같으면
 *       건너뜀). 중요도(평균 가중치)가 큰 기준부터 최대 3개씩.
 * </ul>
 */
@Service
@Transactional
public class AnalysisService {

  static final int WEIGHT_DIGITS = 4; // 46번 normalizedWeights, 43번 crossover 가중치
  static final int MAX_BULLETS = 3; // 템플릿 장단점·설명 이유의 최대 개수

  private final EntityManager em;
  private final DecisionResolver resolver;
  private final EngineBridge bridge;
  private final AiUsageService usage;
  private final ObjectProvider<LlmCalls> llm;
  private final ObjectProvider<LlmRouter> router;

  public AnalysisService(
      EntityManager em,
      DecisionResolver resolver,
      EngineBridge bridge,
      AiUsageService usage,
      ObjectProvider<LlmCalls> llm,
      ObjectProvider<LlmRouter> router) {
    this.em = em;
    this.resolver = resolver;
    this.bridge = bridge;
    this.usage = usage;
    this.llm = llm;
    this.router = router;
  }

  // ── 읽기 준비 ─────────────────────────────────────────────────

  private static final class Ctx {
    final Decision decision;
    final DecisionData data;
    final EngineInput inp;
    final int answered;
    private Posterior post;
    private Posterior.Summary summary;

    Ctx(Decision decision, DecisionData data, EngineInput inp) {
      this.decision = decision;
      this.data = data;
      this.inp = inp;
      this.answered = inp.answers().size();
    }

    Posterior post() {
      if (post == null) {
        post = Posterior.fit(inp.seed(), inp.k(), inp.answers());
      }
      return post;
    }

    Posterior.Summary summary() {
      if (summary == null) {
        summary = post().summary(inp.v());
      }
      return summary;
    }
  }

  private Ctx context(Decision d, Action action) {
    em.flush();
    DecisionData data = bridge.load(d);
    EngineInput inp = bridge.build(data);
    int answered = inp.answers().size();
    FlowErrors.raiseIf(
        StateRules.check(action, DecisionStatus.valueOf(d.getStatus()), answered),
        details("decisionId", d.getId()));
    if (inp.optionIds().isEmpty()) {
      throw FlowErrors.notReady(
          "필수 조건을 만족하는 선택지가 없어 결과를 계산할 수 없습니다.", details("decisionId", d.getId()));
    }
    return new Ctx(d, data, inp);
  }

  private Decision writable(UUID id, UUID clientId) {
    Decision d = resolver.get(id, clientId);
    em.lock(d, LockModeType.PESSIMISTIC_WRITE); // 같은 결정의 분석 캐시를 동시에 만들지 않게 한다
    return d;
  }

  /** FINALIZED면 확정 시점의 39번 응답(없으면 비어 있음 → 다시 계산). */
  private Optional<Map<String, Object>> finalizedSnapshot(Decision d) {
    if (!"FINALIZED".equals(d.getStatus())) {
      return Optional.empty();
    }
    FinalChoice row = em.find(FinalChoice.class, d.getId());
    if (row == null
        || row.getRecommendationSnapshot() == null
        || row.getRecommendationSnapshot().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(deepCopy(row.getRecommendationSnapshot()));
  }

  private static Map<String, Object> deepCopy(Map<String, Object> m) {
    return Json.MAPPER.convertValue(m, new TypeReference<LinkedHashMap<String, Object>>() {});
  }

  // ── 값 표기 (장단점·AI 입력용) ────────────────────────────────

  private static Map<String, OptionValue> valueMap(DecisionData data) {
    Map<String, OptionValue> out = new HashMap<>();
    for (OptionValue v : data.values()) {
      out.put(v.getOption().getId() + "/" + v.getCriterion().getId(), v);
    }
    return out;
  }

  private static boolean isRank(com.seonbi.domain.Criterion c) {
    return "rating".equals(c.getType()) && "rank".equals(c.getInputMode());
  }

  private static String fmt(double x) {
    return QuestionText.formatNumber(
        BigDecimal.valueOf(x).setScale(4, RoundingMode.HALF_EVEN).doubleValue());
  }

  private static String valueLabel(com.seonbi.domain.Criterion c, OptionValue v) {
    if (v == null) {
      return null;
    }
    if ("boolean".equals(c.getType())) {
      return v.getValueBool() == null ? null : (v.getValueBool() ? "예" : "아니요");
    }
    if (isRank(c)) {
      return v.getValueRank() == null ? null : v.getValueRank() + "순위";
    }
    if (v.getValueNum() == null) {
      return null;
    }
    if ("rating".equals(c.getType())) {
      int idx = (int) Math.rint(v.getValueNum().doubleValue());
      return c.getLevels() != null && idx >= 1 && idx <= c.getLevels().size()
          ? c.getLevels().get(idx - 1)
          : idx + "단계";
    }
    return fmt(v.getValueNum().doubleValue()) + (c.getUnit() == null ? "" : c.getUnit());
  }

  private static Double rawValue(com.seonbi.domain.Criterion c, OptionValue v) {
    if (v == null) {
      return null;
    }
    if ("boolean".equals(c.getType())) {
      return v.getValueBool() == null ? null : (v.getValueBool() ? 1.0 : 0.0);
    }
    if (isRank(c)) {
      return v.getValueRank() == null ? null : v.getValueRank().doubleValue();
    }
    return v.getValueNum() == null ? null : v.getValueNum().doubleValue();
  }

  // ── 제외 선택지 ───────────────────────────────────────────────

  /** 미충족 조건 → 짧은 사유. 임계값 조건은 '가격 200만 원 초과', 필수 태그는 'Windows 지원 미충족'. */
  private static String conditionReason(
      com.seonbi.domain.Condition cond, Map<UUID, com.seonbi.domain.Criterion> criteria) {
    if ("required_tag".equals(cond.getKind())) {
      return (cond.getLabel() == null ? "필수 조건" : cond.getLabel()) + " 미충족";
    }
    com.seonbi.domain.Criterion c =
        cond.getCriterion() == null ? null : criteria.get(cond.getCriterion().getId());
    String name = c != null ? c.getName() : (cond.getLabel() == null ? "조건" : cond.getLabel());
    if (cond.getValue() == null || cond.getOperator() == null) {
      return name + " 조건 미충족";
    }
    String amount =
        fmt(cond.getValue().doubleValue()) + (c != null && c.getUnit() != null ? c.getUnit() : "");
    return switch (cond.getOperator()) {
      case "<=" -> name + " " + amount + " 초과";
      case ">=" -> name + " " + amount + " 미만";
      default -> name + " " + amount + "이(가) 아님"; // "==" 조건
    };
  }

  private static List<Map<String, Object>> excludedPayload(Ctx ctx) {
    Map<String, com.seonbi.domain.Condition> conds =
        ctx.data.conditions().stream().collect(Collectors.toMap(c -> c.getId().toString(), c -> c));
    Map<UUID, com.seonbi.domain.Criterion> crit =
        ctx.data.criteria().stream()
            .collect(Collectors.toMap(com.seonbi.domain.Criterion::getId, c -> c));
    List<Map<String, Object>> out = new ArrayList<>();
    for (Option o : ctx.data.options()) {
      List<String> failed = ctx.inp.excluded().get(o.getId().toString());
      if (failed != null && !failed.isEmpty()) {
        List<String> reasons = new ArrayList<>();
        for (String f : failed) {
          if (conds.containsKey(f)) {
            reasons.add(conditionReason(conds.get(f), crit));
          }
        }
        out.add(details("optionId", o.getId().toString(), "reasons", reasons));
      }
    }
    return out;
  }

  // ── 템플릿 문장 ───────────────────────────────────────────────

  /** 값 매트릭스로 만든 장단점. 정규화 값이 가장 큰(작은) 선택지에 pros(cons)를 붙인다. */
  private static List<Map<String, Object>> templateSummaries(Ctx ctx, List<Double> mean) {
    EngineInput inp = ctx.inp;
    Map<String, OptionValue> vals = valueMap(ctx.data);
    List<Integer> order = new ArrayList<>();
    for (int k = 0; k < inp.k(); k++) {
      order.add(k);
    }
    order.sort(Comparator.comparingDouble((Integer k) -> -mean.get(k)));
    List<Map<String, Object>> items = new ArrayList<>();
    for (int i = 0; i < inp.optionIds().size(); i++) {
      List<String> pros = new ArrayList<>();
      List<String> cons = new ArrayList<>();
      for (int k : order) {
        double max = Double.NEGATIVE_INFINITY;
        double min = Double.POSITIVE_INFINITY;
        for (double[] row : inp.v()) {
          max = Math.max(max, row[k]);
          min = Math.min(min, row[k]);
        }
        if (max - min < 1e-12) {
          continue;
        }
        com.seonbi.domain.Criterion c = ctx.data.criteria().get(k);
        String label = valueLabel(c, vals.get(inp.optionIds().get(i) + "/" + c.getId()));
        String tail = label == null ? "" : " (" + label + ")";
        if (inp.v()[i][k] >= max - 1e-12 && pros.size() < MAX_BULLETS) {
          pros.add(Korean.iGa(c.getName()) + " 가장 유리해요" + tail);
        }
        if (inp.v()[i][k] <= min + 1e-12 && cons.size() < MAX_BULLETS) {
          cons.add(Korean.iGa(c.getName()) + " 가장 불리해요" + tail);
        }
      }
      items.add(details("optionId", inp.optionIds().get(i), "pros", pros, "cons", cons));
    }
    return items;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> templateExplanation(Ctx ctx, Map<String, Object> base) {
    EngineInput inp = ctx.inp;
    Map<String, Object> rec = (Map<String, Object>) base.get("recommendation");
    int top = inp.optionIds().indexOf((String) rec.get("optionId"));
    String name = (String) rec.get("name");
    double topProbability = ((Number) rec.get("topProbability")).doubleValue();
    String summaryText =
        Korean.euRo(name) + " 추천해요. 1위일 확률은 " + AnalysisHelpers.pct(topProbability) + "%예요.";
    Map<String, Double> means = new HashMap<>();
    for (Map<String, Object> w : (List<Map<String, Object>>) base.get("weights")) {
      means.put((String) w.get("criterionId"), ((Number) w.get("mean")).doubleValue());
    }
    List<double[]> contribs = new ArrayList<>();
    for (int k = 0; k < inp.k(); k++) {
      contribs.add(new double[] {means.get(inp.criterionIds().get(k)) * inp.v()[top][k], k});
    }
    contribs.sort(Comparator.comparingDouble((double[] c) -> -c[0]));
    List<String> reasons = new ArrayList<>();
    for (double[] c : contribs) {
      if (reasons.size() >= MAX_BULLETS) {
        break;
      }
      int k = (int) c[1];
      double mean = means.get(inp.criterionIds().get(k));
      if (inp.v()[top][k] >= 0.5 && mean > 0) {
        reasons.add(
            inp.criterionNames().get(k)
                + " 기준(중요도 "
                + AnalysisHelpers.pct(mean)
                + "%)에서 "
                + Korean.iGa(name)
                + " 유리해서 점수에 크게 기여했어요.");
      }
    }
    if (reasons.isEmpty() && !contribs.isEmpty()) {
      reasons.add(
          "점수에 가장 크게 기여한 기준은 " + inp.criterionNames().get((int) contribs.get(0)[1]) + "이에요.");
    }
    List<String> caveats = new ArrayList<>();
    Map<String, Object> stability = (Map<String, Object>) base.get("stability");
    if (!"HIGH".equals(stability.get("level"))) {
      caveats.add((String) stability.get("text"));
    }
    if (Boolean.TRUE.equals(base.get("lowConfidence"))) {
      caveats.add(
          "1위 확률이 " + AnalysisHelpers.pct(topProbability) + "%로 낮아요. 질문을 더 하면 결과가 더 분명해질 수 있어요.");
    }
    if (!((List<?>) base.get("hiddenPreferences")).isEmpty()) {
      caveats.add(
          "말로 답한 중요도와 실제 선택이 엇갈린 항목이 있어요. " + AnalysisHelpers.HIDDEN_PREFERENCE_NOTICE + ".");
    }
    Map<String, String> namesById =
        ctx.data.options().stream()
            .collect(Collectors.toMap(o -> o.getId().toString(), Option::getName));
    List<String> excluded = new ArrayList<>();
    for (Map<String, Object> e : (List<Map<String, Object>>) base.get("excludedOptions")) {
      String n = namesById.get((String) e.get("optionId"));
      if (n != null) {
        excluded.add(n);
      }
    }
    if (!excluded.isEmpty()) {
      caveats.add("필수 조건을 만족하지 못해 " + String.join(", ", excluded) + "은(는) 비교에서 제외됐어요.");
    }
    return details(
        "summary", summaryText, "reasons", reasons, "caveats", caveats, "source", "template");
  }

  // ── 39번 본문 계산 (결정적) ───────────────────────────────────

  /** 값이 비어 있는 기준(정규화 값이 임의로 0.5인 열)은 값 전환 조건에서 뺀다. */
  private static List<ValueCondition> usableValueConditions(Ctx ctx, Posterior.Summary s) {
    EngineInput inp = ctx.inp;
    return DecisionAnalysis.valueConditions(
            s.mean(), inp.v(), inp.criteria(), inp.scales(), inp.raw())
        .stream()
        .filter(
            vc -> {
              for (double[] row : inp.raw()) {
                if (Double.isNaN(row[vc.criterion()])) {
                  return false;
                }
              }
              return true;
            })
        .toList();
  }

  private static Map<String, Object> computeBase(Ctx ctx) {
    EngineInput inp = ctx.inp;
    Posterior post = ctx.post();
    Posterior.Summary s = ctx.summary();
    double[][] w0 = Posterior.priorParticles(inp.seed(), inp.k());
    DecisionAnalysis.Stability st = DecisionAnalysis.stability(post, inp.v(), inp.seed());
    List<ChangeCondition> conds = DecisionAnalysis.changeConditions(s.mean(), inp.v());
    int top = DecisionAnalysis.ranking(s.mean(), inp.v()).get(0);
    double topP = AnalysisHelpers.topProbability(s, inp.v());
    List<String> units =
        ctx.data.criteria().stream().map(c -> c.getUnit() == null ? "" : c.getUnit()).toList();
    List<List<String>> levels =
        ctx.data.criteria().stream()
            .map(c -> c.getLevels() == null ? List.<String>of() : List.copyOf(c.getLevels()))
            .toList();
    Map<String, Object> changes =
        AnalysisHelpers.changeConditionsPayload(
            conds,
            top,
            inp.criterionIds(),
            inp.criterionNames(),
            inp.optionIds(),
            inp.optionNames(),
            usableValueConditions(ctx, s),
            units,
            levels);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(
        "recommendation",
        details(
            "optionId", inp.optionIds().get(top),
            "name", inp.optionNames().get(top),
            "topProbability", AnalysisHelpers.p(topP)));
    out.put(
        "rankings",
        AnalysisHelpers.rankingsItems(
            s.mean(), inp.v(), s.pBest(), inp.optionIds(), inp.optionNames()));
    out.put(
        "weights",
        AnalysisHelpers.weightsItems(
            s, inp.criterionIds(), inp.criterionNames(), inp.answers(), w0));
    out.put(
        "stability",
        AnalysisHelpers.stabilityPayload(
            st, s.pBest(), conds, inp.optionIds(), inp.optionNames(), inp.criterionNames()));
    out.put("changeConditions", changes.get("items"));
    out.put(
        "hiddenPreferences",
        AnalysisHelpers.hiddenPreferencesPayload(
            HiddenPreference.detect(w0, inp.answers()), inp.criterionIds(), inp.criterionNames()));
    out.put("excludedOptions", excludedPayload(ctx));
    out.put("lowConfidence", AnalysisHelpers.lowConfidence(topP));
    out.put("computedAt", Instant.now().toString());
    return out;
  }

  /** 캐시가 유효하면 그 본문, 아니면 계산해서 캐시에 넣는다. */
  private AnalysisResult base(Ctx ctx) {
    AnalysisResult cached = em.find(AnalysisResult.class, ctx.decision.getId());
    if (cached != null && cached.getDecisionVersion().equals(ctx.decision.getVersion())) {
      return cached;
    }
    Map<String, Object> payload = computeBase(ctx);
    if (cached == null) {
      cached = new AnalysisResult();
      cached.setDecision(ctx.decision);
      em.persist(cached);
    }
    cached.setDecisionVersion(ctx.decision.getVersion());
    cached.setPayload(payload);
    cached.setExplanation(null);
    cached.setComputedAt(Instant.parse((String) payload.get("computedAt")));
    return cached;
  }

  // ── AI 문장 (optionSummaries·explanation) ─────────────────────

  private Map<String, Object> matrixForAi(Ctx ctx) {
    Map<String, OptionValue> vals = valueMap(ctx.data);
    List<Object> options = new ArrayList<>();
    for (int i = 0; i < ctx.inp.optionIds().size(); i++) {
      String oid = ctx.inp.optionIds().get(i);
      List<Object> values = new ArrayList<>();
      for (com.seonbi.domain.Criterion c : ctx.data.criteria()) {
        Map<String, Object> cell = new LinkedHashMap<>();
        cell.put("criterion", c.getName());
        cell.put("value", valueLabel(c, vals.get(oid + "/" + c.getId())));
        values.add(cell);
      }
      options.add(details("optionId", oid, "name", ctx.inp.optionNames().get(i), "values", values));
    }
    return details("options", options);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> templateTexts(Ctx ctx, Map<String, Object> base) {
    List<Double> mean = new ArrayList<>();
    for (Map<String, Object> w : (List<Map<String, Object>>) base.get("weights")) {
      mean.add(((Number) w.get("mean")).doubleValue());
    }
    return details(
        "optionSummaries",
        templateSummaries(ctx, mean),
        "explanation",
        templateExplanation(ctx, base));
  }

  /** 실제 LLM 제공자(fake가 아닌)가 이 호출에 설정되어 있는가. */
  private boolean realLlm(CallType call) {
    LlmRouter r = router.getIfAvailable();
    return llm.getIfAvailable() != null
        && r != null
        && !"fake".equals(r.settings().primaryFor(call).provider());
  }

  /** AI로 만들되 실패·검증 탈락은 각각 템플릿으로 대체한다 (AI는 선택 사항). */
  @SuppressWarnings("unchecked")
  private Map<String, Object> aiTexts(Ctx ctx, Map<String, Object> base) {
    Map<String, Object> texts = templateTexts(ctx, base);
    LlmCalls calls = llm.getIfAvailable();
    var recorder = usage.recorder(ctx.decision.getClientId(), ctx.decision.getId());
    Optional<List<OptionProsCons>> summaries = calls.summarizeOptions(matrixForAi(ctx), recorder);
    if (summaries.isPresent()) {
      Map<String, OptionProsCons> byId =
          summaries.get().stream()
              .collect(Collectors.toMap(OptionProsCons::optionId, i -> i, (a, b) -> a));
      List<Map<String, Object>> merged = new ArrayList<>();
      for (Map<String, Object> s : (List<Map<String, Object>>) texts.get("optionSummaries")) {
        OptionProsCons ai = byId.get((String) s.get("optionId"));
        merged.add(
            ai == null
                ? s
                : details(
                    "optionId",
                    s.get("optionId"),
                    "pros",
                    List.copyOf(ai.pros()),
                    "cons",
                    List.copyOf(ai.cons())));
      }
      texts.put("optionSummaries", merged);
    }
    Map<String, Object> forAi = new LinkedHashMap<>(base);
    forAi.remove("computedAt");
    Optional<LlmCalls.Explanation> ex = calls.explainResult(forAi, recorder);
    if (ex.isPresent()) {
      LlmCalls.Explanation e = ex.get();
      texts.put(
          "explanation",
          details(
              "summary", e.summary(),
              "reasons", List.copyOf(e.reasons()),
              "caveats", List.copyOf(e.caveats()),
              "source", e.source()));
    }
    return texts;
  }

  private Map<String, Object> texts(Ctx ctx, AnalysisResult cached) {
    if (cached.getExplanation() != null) {
      return cached.getExplanation();
    }
    Map<String, Object> base = cached.getPayload();
    if (!(realLlm(CallType.EXPLAIN_RESULT) || realLlm(CallType.SUMMARIZE_OPTIONS))) {
      return templateTexts(ctx, base); // 실제 제공자가 없으면 저장하지 않고 매번 계산만 한다
    }
    Map<String, Object> texts = aiTexts(ctx, base);
    cached.setExplanation(texts);
    return texts;
  }

  // ── 39 ~ 45 ───────────────────────────────────────────────────

  /** 39번. FINALIZED면 확정 시점 결과, 아니면 (캐시된) 계산 결과. */
  public Map<String, Object> getAnalysis(UUID decisionId, UUID clientId) {
    Decision d = writable(decisionId, clientId);
    return analysisOf(d);
  }

  /** 확정 서비스가 같은 트랜잭션 안에서 부르는 39번 본문 계산. */
  Map<String, Object> analysisOf(Decision d) {
    Ctx ctx = context(d, Action.ANALYSIS_READ);
    Optional<Map<String, Object>> stored = finalizedSnapshot(d);
    if (stored.isPresent()) {
      return stored.get();
    }
    AnalysisResult cached = base(ctx);
    Map<String, Object> base = cached.getPayload();
    Map<String, Object> texts = texts(ctx, cached);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(
        "provisional",
        StateRules.isProvisional(DecisionStatus.valueOf(d.getStatus()), ctx.answered));
    for (String key :
        List.of(
            "recommendation",
            "rankings",
            "weights",
            "stability",
            "changeConditions",
            "hiddenPreferences",
            "excludedOptions")) {
      out.put(key, deepCopy(Map.of("v", base.get(key))).get("v"));
    }
    out.put("optionSummaries", deepCopy(Map.of("v", texts.get("optionSummaries"))).get("v"));
    out.put("explanation", deepCopy(Map.of("v", texts.get("explanation"))).get("v"));
    out.put("lowConfidence", base.get("lowConfidence"));
    out.put("computedAt", base.get("computedAt"));
    return out;
  }

  /** 40·41·44·45번이 꺼내 쓰는 39번 본문 조각 (FINALIZED는 저장된 확정 결과). */
  private Map<String, Object> part(UUID decisionId, UUID clientId) {
    Decision d = writable(decisionId, clientId);
    Ctx ctx = context(d, Action.ANALYSIS_READ);
    Optional<Map<String, Object>> stored = finalizedSnapshot(d);
    return stored.isPresent() ? stored.get() : base(ctx).getPayload();
  }

  private static Object copy(Object o) {
    return deepCopy(Map.of("v", o)).get("v");
  }

  /** 40번. */
  public Map<String, Object> getWeights(UUID decisionId, UUID clientId) {
    Map<String, Object> part = part(decisionId, clientId);
    return details(
        "items", copy(part.get("weights")), "intervalLevel", AnalysisHelpers.INTERVAL_LEVEL);
  }

  /** 41번. */
  public Map<String, Object> getRankings(UUID decisionId, UUID clientId) {
    return details("items", copy(part(decisionId, clientId).get("rankings")));
  }

  /** 44번. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> getStability(UUID decisionId, UUID clientId) {
    return (Map<String, Object>) copy(part(decisionId, clientId).get("stability"));
  }

  /** 45번. 가중치 항목 + (P1) 값 항목. */
  @SuppressWarnings("unchecked")
  public Map<String, Object> getChangeConditions(UUID decisionId, UUID clientId) {
    Map<String, Object> part = part(decisionId, clientId);
    return details(
        "currentTop", ((Map<String, Object>) part.get("recommendation")).get("optionId"),
        "items", copy(part.get("changeConditions")));
  }

  // ── 42 · 43 · 46 (P1) ─────────────────────────────────────────

  /** 42번. contribution = 평균 가중치 × 정규화 값. */
  @Transactional(readOnly = true)
  public Map<String, Object> getScores(UUID decisionId, UUID clientId) {
    Decision d = resolver.get(decisionId, clientId);
    Ctx ctx = context(d, Action.ANALYSIS_READ);
    EngineInput inp = ctx.inp;
    Posterior.Summary s = ctx.summary();
    Map<String, OptionValue> vals = valueMap(ctx.data);
    List<Object> items = new ArrayList<>();
    for (int i = 0; i < inp.optionIds().size(); i++) {
      List<Object> cells = new ArrayList<>();
      for (int k = 0; k < ctx.data.criteria().size(); k++) {
        com.seonbi.domain.Criterion c = ctx.data.criteria().get(k);
        Double raw = rawValue(c, vals.get(inp.optionIds().get(i) + "/" + c.getId()));
        if (raw == null) { // 값 입력 전인 칸은 셀을 만들지 않는다
          continue;
        }
        double v = inp.v()[i][k];
        cells.add(
            details(
                "criterionId", c.getId().toString(),
                "rawValue", round(raw, 4),
                "normalized", AnalysisHelpers.p(v),
                "contribution", AnalysisHelpers.p(s.mean()[k] * v)));
      }
      items.add(details("optionId", inp.optionIds().get(i), "cells", cells));
    }
    return details("criteria", inp.criterionIds(), "items", items);
  }

  /** 43번. 기준 하나(생략하면 전체)의 가중치를 0~1로 움직일 때 선택지별 점수와 1위가 바뀌는 지점. */
  @Transactional(readOnly = true)
  public Map<String, Object> getSensitivity(UUID decisionId, UUID clientId, String criterionId) {
    Decision d = resolver.get(decisionId, clientId);
    Ctx ctx = context(d, Action.ANALYSIS_READ);
    EngineInput inp = ctx.inp;
    if (criterionId != null && !inp.criterionIds().contains(criterionId)) {
      throw FlowErrors.validation(
          "알 수 없는 criterionId입니다.", details("criterionId", "알 수 없는 criterionId입니다."));
    }
    Posterior.Summary s = ctx.summary();
    List<ChangeCondition> conds = DecisionAnalysis.changeConditions(s.mean(), inp.v());
    int top = DecisionAnalysis.ranking(s.mean(), inp.v()).get(0);
    List<Object> items = new ArrayList<>();
    for (int k = 0; k < inp.criterionIds().size(); k++) {
      String cid = inp.criterionIds().get(k);
      if (criterionId != null && !cid.equals(criterionId)) {
        continue;
      }
      DecisionAnalysis.Curve curve = DecisionAnalysis.sensitivity(s.mean(), inp.v(), k);
      ChangeCondition c = conds.get(k);
      List<Object> crossovers = new ArrayList<>();
      if (c.threshold() != null && c.newTop() != null) {
        crossovers.add(
            details(
                "weight", round(c.threshold(), WEIGHT_DIGITS),
                "from", inp.optionIds().get(top),
                "to", inp.optionIds().get(c.newTop())));
      }
      List<Object> points = new ArrayList<>();
      for (int j = 0; j < curve.t().length; j++) {
        Map<String, Object> scores = new LinkedHashMap<>();
        for (int i = 0; i < inp.optionIds().size(); i++) {
          scores.put(inp.optionIds().get(i), AnalysisHelpers.p(curve.scores()[j][i]));
        }
        points.add(
            details("weight", round(curve.t()[j], AnalysisHelpers.PROB_DIGITS), "scores", scores));
      }
      items.add(
          details(
              "criterionId", cid,
              "currentWeight", AnalysisHelpers.p(s.mean()[k]),
              "points", points,
              "crossovers", crossovers));
    }
    return details("items", items);
  }

  /** 요청 가중치 → 기준 순서의 정규화된 배열. 형식·범위가 틀리면 400 VALIDATION_ERROR. */
  private static double[] parseWeights(
      List<Map<String, Object>> weights, List<String> criterionIds) {
    Map<String, Double> given = new LinkedHashMap<>();
    if (weights == null) {
      throw FlowErrors.validation("weights는 목록이어야 합니다.", details("weights", "필수 항목입니다."));
    }
    for (Map<String, Object> item : weights) {
      Object cid = item.get("criterionId");
      Object w = item.get("weight");
      if (!(cid instanceof String c) || !criterionIds.contains(c)) {
        throw FlowErrors.validation(
            "알 수 없는 criterionId입니다.", details("weights.criterionId", String.valueOf(cid)));
      }
      if (given.containsKey(c)) {
        throw FlowErrors.validation("criterionId가 중복되었습니다.", details("weights.criterionId", c));
      }
      if (!(w instanceof Number n)
          || Double.isNaN(n.doubleValue())
          || Double.isInfinite(n.doubleValue())
          || n.doubleValue() < 0) {
        throw FlowErrors.validation(
            "weight는 0 이상의 유한한 숫자여야 합니다.", details("weights.weight", "0 이상의 유한한 숫자"));
      }
      given.put(c, n.doubleValue());
    }
    List<String> missing = criterionIds.stream().filter(c -> !given.containsKey(c)).toList();
    if (!missing.isEmpty()) {
      throw FlowErrors.validation("모든 기준의 가중치가 필요합니다.", details("weights", missing));
    }
    double total = given.values().stream().mapToDouble(Double::doubleValue).sum();
    if (total <= 0) {
      throw FlowErrors.validation("가중치의 합이 0보다 커야 합니다.", details("weights", "합이 0보다 커야 합니다."));
    }
    double[] out = new double[criterionIds.size()];
    for (int k = 0; k < out.length; k++) {
      out[k] = given.get(criterionIds.get(k)) / total;
    }
    return out;
  }

  /** 46번. 저장하지 않는 순수 계산. weights는 [{criterionId, weight}, ...]. */
  @Transactional(readOnly = true)
  public Map<String, Object> simulate(
      UUID decisionId, UUID clientId, List<Map<String, Object>> weights) {
    Decision d = resolver.get(decisionId, clientId);
    Ctx ctx = context(d, Action.SIMULATE);
    EngineInput inp = ctx.inp;
    double[] w = parseWeights(weights, inp.criterionIds());
    Posterior.Summary s = ctx.summary();
    List<Integer> order = DecisionAnalysis.ranking(w, inp.v());
    double[] u = DecisionAnalysis.scores(w, inp.v());
    List<Object> normalized = new ArrayList<>();
    for (int k = 0; k < w.length; k++) {
      normalized.add(
          details("criterionId", inp.criterionIds().get(k), "weight", round(w[k], WEIGHT_DIGITS)));
    }
    List<Object> rankings = new ArrayList<>();
    int rank = 1;
    for (int i : order) {
      rankings.add(
          details(
              "rank",
              rank++,
              "optionId",
              inp.optionIds().get(i),
              "score",
              AnalysisHelpers.p(u[i])));
    }
    return details(
        "normalizedWeights", normalized,
        "rankings", rankings,
        "changedFromCurrent", !order.equals(DecisionAnalysis.ranking(s.mean(), inp.v())));
  }

  private static double round(double x, int digits) {
    return new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
  }
}

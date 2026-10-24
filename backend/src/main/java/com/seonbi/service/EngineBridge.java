package com.seonbi.service;

import com.seonbi.core.FlowError;
import com.seonbi.domain.Decision;
import com.seonbi.domain.Option;
import com.seonbi.domain.OptionValue;
import com.seonbi.domain.Question;
import com.seonbi.engine.Answer;
import com.seonbi.engine.Condition;
import com.seonbi.engine.Criterion;
import com.seonbi.engine.Likelihood;
import com.seonbi.engine.Normalizer;
import com.seonbi.engine.QuestionSpec;
import com.seonbi.engine.QuestionSpec.CriteriaABSpec;
import com.seonbi.engine.QuestionSpec.ScenarioSpec;
import com.seonbi.engine.QuestionSpec.SliderSpec;
import com.seonbi.engine.Response;
import com.seonbi.engine.Scale;
import com.seonbi.engine.ValueKey;
import com.seonbi.service.QuestionText.CriterionMeta;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * DB 엔티티 ↔ 엔진 입력 변환 (역할 문서 5.2, 개발기획서 5.1·5.5·8.1).
 *
 * <ul>
 *   <li>엔진은 기준·선택지를 인덱스로 가리키고, 저장소는 id로 가리킨다. 이 클래스가 그 사이를 맡는다.
 *   <li>질문 {@code spec}(jsonb)에는 기준 <b>id</b>를 저장한다(기준이 추가·삭제되면 인덱스가 바뀌기 때문).
 *   <li>답변 {@code response}(jsonb): {@code {"choice":"A"|"B"}}(ab_*), {@code
 *       {"value":-5..5}}(slider_pair), {@code {"skip":true}}.
 *   <li>엔진 클래스는 엔티티를 모른다. 엔티티 → 엔진 record 변환은 여기서만 한다.
 * </ul>
 *
 * <p>명세의 모호한 부분 (가정):
 *
 * <ul>
 *   <li>값이 하나라도 비어 있는 기준은 정규화값을 전부 0.5로 두어 질문 후보에서 빠지게 한다. 질문 도중 기준을 추가하면 값 입력 전까지 이런 상태가 되기
 *       때문이다(명세에 없음).
 *   <li>지워진 기준을 가리키는 질문·답변은 엔진 입력에서 뺀다.
 *   <li>단계형 범위 표기(lowName/highName)는 값이 가장 나쁜/좋은 선택지 이름이다(score는 값이 큰 쪽, rank는 그룹 번호가 작은 쪽이 좋음).
 *   <li>{@code {"skip": false}}와 알 수 없는 키는 형식 오류로 본다.
 * </ul>
 */
@Component
public class EngineBridge {

  public static final int SLIDER_MIN = -Likelihood.SLIDER_MAX;

  private final EntityManager em;

  public EngineBridge(EntityManager em) {
    this.em = em;
  }

  /** 한 결정의 DB 행 묶음. 선택지·기준은 position 순, 질문은 seq 순이다. */
  public record DecisionData(
      Decision decision,
      List<Option> options,
      List<com.seonbi.domain.Criterion> criteria,
      List<OptionValue> values,
      List<com.seonbi.domain.Condition> conditions,
      Map<UUID, Set<UUID>> satisfiedBy,
      List<Question> questions,
      List<com.seonbi.domain.Answer> answers) {}

  /** 엔진에 넘길 입력과, 엔진 인덱스 ↔ API id 변환표. */
  public record EngineInput(
      List<String> criterionIds, // position 순 (엔진 열 순서)
      List<String> criterionNames,
      List<String> optionIds, // 제외되지 않은 선택지만 (엔진 행 순서)
      List<String> optionNames,
      Map<String, List<String>> excluded, // 선택지 id → 미충족 조건 id
      List<Criterion> criteria,
      Map<String, Scale> scales, // 숫자형 기준만
      double[][] v, // (선택지, 기준) 정규화 행렬
      double[][] raw, // (선택지, 기준) 원래 값. 숫자형·단계형(순위는 그룹 번호)·예/아니요(1/0), 비면 NaN
      List<Answer> answers, // 유효 답변, seq 순 (가상 답변 포함, origin 표시)
      List<QuestionSpec> history, // origin=engine 질문의 스펙 (건너뛴 것 포함), seq 순
      Set<Integer> covered, // 유효하고 건너뛰지 않은 답변(가상 포함)에 나온 기준 인덱스
      int k,
      long seed,
      List<CriterionMeta> metas, // 질문 문장 생성용
      String category) {}

  // ── 읽기 ──────────────────────────────────────────────────────

  /** 결정에 속한 행을 모두 읽는다. */
  public DecisionData load(Decision d) {
    List<Option> options =
        em.createQuery(
                "select o from Option o where o.decision.id=:id order by o.position", Option.class)
            .setParameter("id", d.getId())
            .getResultList();
    List<com.seonbi.domain.Criterion> criteria =
        em.createQuery(
                "select c from Criterion c where c.decision.id=:id order by c.position",
                com.seonbi.domain.Criterion.class)
            .setParameter("id", d.getId())
            .getResultList();
    List<OptionValue> values =
        em.createQuery(
                "select v from OptionValue v where v.option.decision.id=:id", OptionValue.class)
            .setParameter("id", d.getId())
            .getResultList();
    List<com.seonbi.domain.Condition> conditions =
        em.createQuery(
                "select c from Condition c where c.decision.id=:id",
                com.seonbi.domain.Condition.class)
            .setParameter("id", d.getId())
            .getResultList();
    Map<UUID, Set<UUID>> satisfied = new HashMap<>();
    em.createQuery(
            "select s.condition.id, s.option.id from ConditionSatisfaction s"
                + " where s.condition.decision.id=:id and s.satisfied=true",
            Object[].class)
        .setParameter("id", d.getId())
        .getResultList()
        .forEach(
            row ->
                satisfied
                    .computeIfAbsent((UUID) row[0], k -> new LinkedHashSet<>())
                    .add((UUID) row[1]));
    List<Question> questions =
        em.createQuery(
                "select q from Question q where q.decision.id=:id order by q.seq", Question.class)
            .setParameter("id", d.getId())
            .getResultList();
    List<com.seonbi.domain.Answer> answers =
        em.createQuery(
                "select a from Answer a where a.decision.id=:id", com.seonbi.domain.Answer.class)
            .setParameter("id", d.getId())
            .getResultList();
    return new DecisionData(
        d, options, criteria, values, conditions, satisfied, questions, answers);
  }

  // ── 엔진 입력 만들기 ──────────────────────────────────────────

  private static Criterion engineCriterion(com.seonbi.domain.Criterion c) {
    Criterion.Direction direction =
        c.getDirection() == null
            ? Criterion.Direction.HIGHER_BETTER
            : Criterion.Direction.valueOf(c.getDirection().toUpperCase());
    return new Criterion(
        c.getId().toString(),
        Criterion.Type.valueOf(c.getType().toUpperCase()),
        direction,
        c.getLevels() == null || c.getLevels().isEmpty() ? null : c.getLevels().size(),
        Criterion.InputMode.valueOf(c.getInputMode().toUpperCase()),
        c.getScaleMin() == null ? null : c.getScaleMin().doubleValue(),
        c.getScaleMax() == null ? null : c.getScaleMax().doubleValue());
  }

  private static boolean isRank(com.seonbi.domain.Criterion c) {
    return "rating".equals(c.getType()) && "rank".equals(c.getInputMode());
  }

  /** (선택지 id, 기준 id) → 원래 값. 예/아니요는 1/0, 순위 입력은 그룹 번호. 값이 비면 빠진다. */
  private static Map<ValueKey, Double> rawValues(DecisionData data) {
    Map<UUID, com.seonbi.domain.Criterion> byId =
        data.criteria().stream()
            .collect(Collectors.toMap(com.seonbi.domain.Criterion::getId, c -> c));
    Set<UUID> optionIds = data.options().stream().map(Option::getId).collect(Collectors.toSet());
    Map<ValueKey, Double> out = new LinkedHashMap<>();
    for (OptionValue v : data.values()) {
      com.seonbi.domain.Criterion c = byId.get(v.getCriterion().getId());
      if (c == null || !optionIds.contains(v.getOption().getId())) {
        continue;
      }
      Double x;
      if ("boolean".equals(c.getType())) {
        x = v.getValueBool() == null ? null : (v.getValueBool() ? 1.0 : 0.0);
      } else if (isRank(c)) {
        x = v.getValueRank() == null ? null : v.getValueRank().doubleValue();
      } else {
        x = v.getValueNum() == null ? null : v.getValueNum().doubleValue();
      }
      if (x != null) {
        out.put(new ValueKey(v.getOption().getId().toString(), c.getId().toString()), x);
      }
    }
    return out;
  }

  private static List<Condition> conditions(DecisionData data) {
    List<Condition> out = new ArrayList<>();
    for (com.seonbi.domain.Condition c : data.conditions()) {
      Set<String> sat =
          data.satisfiedBy().getOrDefault(c.getId(), Set.of()).stream()
              .map(UUID::toString)
              .collect(Collectors.toSet());
      Condition.Operator op =
          c.getOperator() == null
              ? null
              : switch (c.getOperator()) {
                case "<=" -> Condition.Operator.LE;
                case ">=" -> Condition.Operator.GE;
                default -> Condition.Operator.EQ;
              };
      out.add(
          new Condition(
              c.getId().toString(),
              Condition.Kind.valueOf(c.getKind().toUpperCase()),
              c.getCriterion() == null ? null : c.getCriterion().getId().toString(),
              op,
              c.getValue() == null ? null : c.getValue().doubleValue(),
              sat));
    }
    return out;
  }

  private static List<String> optionIds(DecisionData data) {
    return data.options().stream().map(o -> o.getId().toString()).toList();
  }

  /** 모든(제외되지 않은) 선택지의 값이 채워진 기준 id. */
  private static Set<String> completeCriteria(
      DecisionData data, Map<ValueKey, Double> values, List<String> keptIds) {
    Set<String> out = new LinkedHashSet<>();
    for (com.seonbi.domain.Criterion c : data.criteria()) {
      String cid = c.getId().toString();
      if (keptIds.stream().allMatch(oid -> values.containsKey(new ValueKey(oid, cid)))) {
        out.add(cid);
      }
    }
    return out;
  }

  /** 숫자형 기준의 척도. 고정된 기준은 저장된 값, 아니면 제외되지 않은 선택지 값의 min~max(사용자 지정 우선). */
  public Map<String, Scale> computeScales(DecisionData data) {
    Map<ValueKey, Double> values = rawValues(data);
    Set<String> excluded =
        Normalizer.excludedOptions(optionIds(data), values, conditions(data)).keySet();
    Map<String, Scale> scales = new LinkedHashMap<>();
    List<Criterion> live = new ArrayList<>();
    for (com.seonbi.domain.Criterion c : data.criteria()) {
      if (!"numeric".equals(c.getType())) {
        continue;
      }
      if (Boolean.TRUE.equals(c.getScaleFrozen())
          && c.getScaleMin() != null
          && c.getScaleMax() != null) {
        scales.put(
            c.getId().toString(),
            new Scale(c.getScaleMin().doubleValue(), c.getScaleMax().doubleValue()));
      } else {
        live.add(engineCriterion(c));
      }
    }
    scales.putAll(Normalizer.freezeScales(live, values, excluded));
    return scales;
  }

  /** 아직 고정되지 않은 숫자형 기준 중 값이 모두 채워져 지금 고정할 수 있는 것의 척도. */
  public Map<String, Scale> pendingScaleFreeze(DecisionData data) {
    Map<ValueKey, Double> values = rawValues(data);
    Set<String> excluded =
        Normalizer.excludedOptions(optionIds(data), values, conditions(data)).keySet();
    List<String> kept = optionIds(data).stream().filter(o -> !excluded.contains(o)).toList();
    Set<String> complete = completeCriteria(data, values, kept);
    List<Criterion> live = new ArrayList<>();
    for (com.seonbi.domain.Criterion c : data.criteria()) {
      if ("numeric".equals(c.getType())
          && !Boolean.TRUE.equals(c.getScaleFrozen())
          && complete.contains(c.getId().toString())) {
        live.add(engineCriterion(c));
      }
    }
    return Normalizer.freezeScales(live, values, excluded);
  }

  /** 질문 문장용 기준 정보. 단계형은 양 끝 선택지 이름을 범위 표기에 쓴다(5.1). */
  public List<CriterionMeta> criterionMetas(DecisionData data) {
    Map<ValueKey, Double> values = rawValues(data);
    Set<String> excluded =
        Normalizer.excludedOptions(optionIds(data), values, conditions(data)).keySet();
    List<Option> kept =
        data.options().stream().filter(o -> !excluded.contains(o.getId().toString())).toList();
    List<CriterionMeta> metas = new ArrayList<>();
    for (com.seonbi.domain.Criterion c : data.criteria()) {
      String low = null;
      String high = null;
      if ("rating".equals(c.getType())) {
        double sign = isRank(c) ? -1.0 : 1.0; // rank는 그룹 번호가 작을수록 좋음
        double lowScore = Double.POSITIVE_INFINITY;
        double highScore = Double.NEGATIVE_INFINITY;
        for (Option o : kept) {
          Double x = values.get(new ValueKey(o.getId().toString(), c.getId().toString()));
          if (x == null) {
            continue;
          }
          double score = sign * x;
          if (score < lowScore) { // 동점이면 앞선 선택지
            lowScore = score;
            low = o.getName();
          }
          if (score > highScore) {
            highScore = score;
            high = o.getName();
          }
        }
      }
      metas.add(
          new CriterionMeta(
              c.getId().toString(),
              c.getName(),
              com.seonbi.engine.Criterion.Type.valueOf(c.getType().toUpperCase()),
              c.getDirection() == null
                  ? Criterion.Direction.HIGHER_BETTER
                  : Criterion.Direction.valueOf(c.getDirection().toUpperCase()),
              c.getUnit(),
              c.getLevels() == null || c.getLevels().isEmpty() ? null : List.copyOf(c.getLevels()),
              Criterion.InputMode.valueOf(c.getInputMode().toUpperCase()),
              low,
              high));
    }
    return metas;
  }

  /** 엔티티 묶음 → 엔진 입력. 무효 답변과 지워진 기준을 가리키는 질문·답변은 뺀다. */
  public EngineInput build(DecisionData data) {
    List<String> criterionIds = data.criteria().stream().map(c -> c.getId().toString()).toList();
    List<Criterion> criteria = data.criteria().stream().map(EngineBridge::engineCriterion).toList();
    Map<ValueKey, Double> values = rawValues(data);
    List<Condition> conditions = conditions(data);
    Map<String, Scale> scales = computeScales(data);

    Map<String, List<String>> excluded =
        Normalizer.excludedOptions(optionIds(data), values, conditions);
    List<Option> kept =
        data.options().stream().filter(o -> !excluded.containsKey(o.getId().toString())).toList();
    List<String> keptIds = kept.stream().map(o -> o.getId().toString()).toList();
    Set<String> complete = completeCriteria(data, values, keptIds);

    int k = criteria.size();
    double[][] v = new double[kept.size()][k];
    for (double[] row : v) {
      Arrays.fill(row, 0.5);
    }
    if (!complete.isEmpty() && !kept.isEmpty()) {
      List<Criterion> completeCriteria =
          criteria.stream().filter(c -> complete.contains(c.id())).toList();
      double[][] matrix =
          Normalizer.normalize(values, completeCriteria, scales, optionIds(data), conditions)
              .matrix()
              .values();
      int col = 0;
      for (int j = 0; j < k; j++) {
        if (complete.contains(criterionIds.get(j))) {
          for (int i = 0; i < kept.size(); i++) {
            v[i][j] = matrix[i][col];
          }
          col++;
        }
      }
    }
    double[][] raw = new double[kept.size()][k];
    for (double[] row : raw) {
      Arrays.fill(row, Double.NaN);
    }
    for (int j = 0; j < k; j++) {
      for (int i = 0; i < kept.size(); i++) {
        Double x = values.get(new ValueKey(keptIds.get(i), criterionIds.get(j)));
        if (x != null) {
          raw[i][j] = x;
        }
      }
    }

    Map<UUID, Question> byId =
        data.questions().stream().collect(Collectors.toMap(Question::getId, q -> q));
    Map<UUID, QuestionSpec> specs = new HashMap<>();
    for (Question q : data.questions()) {
      try {
        specs.put(q.getId(), specFromMap(q.getSpec(), criterionIds));
      } catch (RuntimeException e) {
        // 지워진 기준을 가리키는 질문은 건너뛴다
      }
    }
    List<QuestionSpec> history =
        data.questions().stream()
            .filter(q -> "engine".equals(q.getOrigin()) && specs.containsKey(q.getId()))
            .sorted(Comparator.comparing(Question::getSeq))
            .map(q -> specs.get(q.getId()))
            .toList();

    List<Answer> engineAnswers = new ArrayList<>();
    Set<Integer> covered = new LinkedHashSet<>();
    List<com.seonbi.domain.Answer> ordered =
        data.answers().stream()
            .filter(
                a ->
                    Boolean.TRUE.equals(a.getIsValid())
                        && byId.containsKey(a.getQuestion().getId()))
            .sorted(Comparator.comparing(a -> byId.get(a.getQuestion().getId()).getSeq()))
            .toList();
    for (com.seonbi.domain.Answer a : ordered) {
      Question q = byId.get(a.getQuestion().getId());
      QuestionSpec spec = specs.get(q.getId());
      if (spec == null) {
        continue;
      }
      Response resp = parseResponse(q.getType(), a.getResponse());
      engineAnswers.add(
          new Answer(
              spec,
              resp,
              "free_text".equals(q.getOrigin()) ? Answer.Origin.FREE_TEXT : Answer.Origin.ENGINE));
      if (!(resp instanceof Response.Unknown)) {
        covered.add(spec.a());
        covered.add(spec.b());
      }
    }
    return new EngineInput(
        criterionIds,
        data.criteria().stream().map(com.seonbi.domain.Criterion::getName).toList(),
        keptIds,
        kept.stream().map(Option::getName).toList(),
        excluded,
        criteria,
        scales,
        v,
        raw,
        engineAnswers,
        history,
        covered,
        k,
        data.decision().getRngSeed(),
        criterionMetas(data),
        data.decision().getCategory());
  }

  // ── 질문 spec ↔ Map ───────────────────────────────────────────

  public static String questionTypeOf(QuestionSpec spec) {
    if (spec instanceof SliderSpec) {
      return "slider_pair";
    }
    if (spec instanceof CriteriaABSpec) {
      return "ab_criteria";
    }
    return "ab_scenario";
  }

  /** 엔진 스펙 → questions.spec (기준은 id). 상황형은 모든 기준의 정규화 Δ를 id로 담는다. */
  public static Map<String, Object> specToMap(QuestionSpec spec, List<String> criterionIds) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("kind", questionTypeOf(spec));
    out.put("a", criterionIds.get(spec.a()));
    out.put("b", criterionIds.get(spec.b()));
    if (spec instanceof ScenarioSpec s) {
      Map<String, Object> delta = new LinkedHashMap<>();
      for (int i = 0; i < criterionIds.size(); i++) {
        delta.put(criterionIds.get(i), s.delta()[i]);
      }
      out.put("delta", delta);
      out.put("rawA", s.rawA());
      out.put("rawB", s.rawB());
    }
    return out;
  }

  /** questions.spec → 엔진 스펙. 삭제된 기준 id면 예외. 상황형 Δ에 없는 기준(나중에 추가된 기준)은 0. */
  public static QuestionSpec specFromMap(Map<String, Object> d, List<String> criterionIds) {
    Object kind = d.get("kind");
    if (!(kind instanceof String type)
        || !Set.of("slider_pair", "ab_criteria", "ab_scenario").contains(type)) {
      throw new IllegalArgumentException("알 수 없는 질문 kind: " + kind);
    }
    int a = criterionIds.indexOf(String.valueOf(d.get("a")));
    int b = criterionIds.indexOf(String.valueOf(d.get("b")));
    if (a < 0 || b < 0) {
      throw new IllegalArgumentException("삭제된 기준이 들어간 질문입니다.");
    }
    return switch (type) {
      case "slider_pair" -> new SliderSpec(a, b);
      case "ab_criteria" -> new CriteriaABSpec(a, b);
      default -> {
        @SuppressWarnings("unchecked")
        Map<String, Object> deltaById = (Map<String, Object>) d.get("delta");
        double[] delta = new double[criterionIds.size()];
        for (int i = 0; i < delta.length; i++) {
          Object x = deltaById.get(criterionIds.get(i));
          delta[i] = x == null ? 0.0 : ((Number) x).doubleValue();
        }
        yield new ScenarioSpec(
            a,
            b,
            delta,
            ((Number) d.get("rawA")).doubleValue(),
            ((Number) d.get("rawB")).doubleValue());
      }
    };
  }

  /** answers.preference_kind: 슬라이더·기준 A/B는 stated, 상황형은 revealed. */
  public static String preferenceKindOf(QuestionSpec spec) {
    return Likelihood.preferenceKind(spec).name().toLowerCase();
  }

  // ── 응답 형식 ─────────────────────────────────────────────────

  private static FlowError invalid(String message, String questionType) {
    return new FlowError(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        message,
        Map.of("response", message, "questionType", String.valueOf(questionType)));
  }

  /**
   * answers.response → 엔진 응답. "잘 모르겠어요"는 {@link Response#DONT_KNOW}. 형식·범위가 틀리면 400
   * VALIDATION_ERROR.
   */
  public static Response parseResponse(String questionType, Object response) {
    if (!(response instanceof Map<?, ?> map)) {
      throw invalid("response는 객체여야 합니다.", questionType);
    }
    Set<Object> keys = new HashSet<>(map.keySet());
    if (keys.equals(Set.of("skip"))) {
      if (!Boolean.TRUE.equals(map.get("skip"))) {
        throw invalid("skip은 true만 보낼 수 있습니다.", questionType);
      }
      return Response.DONT_KNOW;
    }
    if ("slider_pair".equals(questionType)) {
      if (!keys.equals(Set.of("value"))) {
        throw invalid("슬라이더 응답은 {\"value\": -5..5} 형식이어야 합니다.", questionType);
      }
      Object v = map.get("value");
      if (!(v instanceof Integer i) || i < SLIDER_MIN || i > Likelihood.SLIDER_MAX) {
        throw invalid(
            "value는 " + SLIDER_MIN + "~" + Likelihood.SLIDER_MAX + " 정수여야 합니다.", questionType);
      }
      return new Response.Slider(i);
    }
    if ("ab_criteria".equals(questionType) || "ab_scenario".equals(questionType)) {
      if (!keys.equals(Set.of("choice"))) {
        throw invalid("A/B 응답은 {\"choice\": \"A\"|\"B\"} 형식이어야 합니다.", questionType);
      }
      Object c = map.get("choice");
      if ("A".equals(c)) {
        return Response.Choice.A;
      }
      if ("B".equals(c)) {
        return Response.Choice.B;
      }
      throw invalid("choice는 A 또는 B여야 합니다.", questionType);
    }
    throw invalid("이 질문 유형에는 답변할 수 없습니다.", questionType);
  }

  /** 엔진 응답 → answers.response (정규화된 저장 형식). */
  public static Map<String, Object> responseToMap(String questionType, Response parsed) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (parsed instanceof Response.Unknown) {
      out.put("skip", true);
    } else if (parsed instanceof Response.Slider s) {
      out.put("value", s.value());
    } else {
      out.put("choice", ((Response.Choice) parsed).a() ? "A" : "B");
    }
    return out;
  }
}

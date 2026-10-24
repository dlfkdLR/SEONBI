package com.seonbi.service;

import com.seonbi.engine.Criterion;
import com.seonbi.engine.Likelihood;
import com.seonbi.engine.QuestionSpec;
import com.seonbi.engine.QuestionSpec.CriteriaABSpec;
import com.seonbi.engine.QuestionSpec.ScenarioSpec;
import com.seonbi.engine.QuestionSpec.SliderSpec;
import com.seonbi.engine.Scale;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 템플릿(AI 없는) 질문 문장 생성 (개발기획서 5.1, 5.5 문장 템플릿, 7.3 질문 객체). 엔진이 만든 질문 스펙을 7.3의 "질문 객체" 내용으로 바꾼다. id와
 * seq는 서버가 저장하며 붙이므로 여기서는 만들지 않는다. LLM phraseQuestion(6.2)은 이 결과의 title, payload.*.description,
 * payload.left/right.label만 바꾸고 숫자·deltas·range는 그대로 쓴다. DB·Spring에 의존하지 않는 순수 함수 모음이다.
 *
 * <p>명세의 모호한 부분 (가정):
 *
 * <ul>
 *   <li>단위는 숫자 바로 뒤에 그대로 붙인다("20만 원", "0.1kg"). 띄어쓰기는 단위 문자열 안에 둔다(7.3·5.1 예시).
 *   <li>단계형 범위 표기는 "낮은 쪽~높은 쪽"을 ~로 잇는다. 양 끝 선택지 이름이 없으면 첫/마지막 단계 라벨로 대신하고, 라벨도 없으면 오류다.
 *   <li>예/아니요 범위는 "없음~있음", 예/아니요 기준은 deltas[].display가 "있음"/"없음".
 *   <li>deltas[].display의 부호는 "그 선택지의 값이 상대보다 어느 쪽으로 달라졌나"를 뜻한다. lower_better인 숫자형에서 "더 좋음"은 음수다.
 *   <li>상황형 두 조각은 5.5 조각 그대로 ", "로 잇는다.
 * </ul>
 */
public final class QuestionText {

  public static final boolean ALLOW_FREE_TEXT = true; // "이유 추가" 버튼 노출 (7.3 예시: 엔진 질문은 모두 true)
  public static final boolean ALLOW_SKIP = true; // "잘 모르겠어요"
  public static final int SLIDER_MIN = -Likelihood.SLIDER_MAX;

  private static final Map<String, String> NOUNS =
      Map.of("purchase", "제품", "career", "학과", "activity", "활동");
  private static final String DEFAULT_NOUN = "선택지";

  /** 질문 문장에 필요한 기준 정보. DB의 criteria 행에서 그대로 만들 수 있다. */
  public record CriterionMeta(
      String id,
      String name,
      Criterion.Type type,
      Criterion.Direction direction,
      String unit,
      List<String> levels, // 단계 라벨 (나쁜 것 → 좋은 것)
      Criterion.InputMode inputMode,
      String lowName, // 단계형 범위 표기용: 값이 가장 낮은 선택지 이름
      String highName) {

    /** 필수 값만 받는 짧은 생성자. 나머지는 기본값(higher_better, score)이다. */
    public static CriterionMeta of(String id, String name, Criterion.Type type) {
      return new CriterionMeta(
          id,
          name,
          type,
          Criterion.Direction.HIGHER_BETTER,
          null,
          null,
          Criterion.InputMode.SCORE,
          null,
          null);
    }

    boolean isRank() {
      return type == Criterion.Type.RATING && inputMode == Criterion.InputMode.RANK;
    }
  }

  private QuestionText() {}

  /** 카테고리별 선택지 명사 (5.5 문장 템플릿). 기타는 "선택지". */
  public static String nounForCategory(String category) {
    return NOUNS.getOrDefault(category, DEFAULT_NOUN);
  }

  /** 부동소수점 꼬리를 없앤 숫자 문자열. 정수는 ".0" 없이 쓴다. */
  public static String formatNumber(double x) {
    if (Double.isNaN(x) || Double.isInfinite(x)) {
      throw new IllegalArgumentException("유한한 수가 아니다: " + x);
    }
    if (x == Math.rint(x) && Math.abs(x) < 1e15) {
      return Long.toString((long) x);
    }
    // 10자리 유효숫자로 맞춘 뒤 가장 짧은 표기로 쓴다 (파이썬의 f"{x:.10g}" → repr과 같다)
    double r = new BigDecimal(x).round(new MathContext(10, RoundingMode.HALF_EVEN)).doubleValue();
    return BigDecimal.valueOf(r).stripTrailingZeros().toPlainString();
  }

  /** 숫자 바로 뒤에 붙는 단위 (없으면 빈 문자열). */
  public static String formatUnit(String unit) {
    return unit == null ? "" : unit;
  }

  /** 숫자형 범위. 예: formatRange(150, 189, "만 원") → "150~189만 원". */
  public static String formatRange(double low, double high, String unit) {
    return formatNumber(low) + "~" + formatNumber(high) + formatUnit(unit);
  }

  /** 단위가 붙은 숫자. 예: "20만 원", "0.1kg". */
  public static String formatValue(double x, String unit) {
    return formatNumber(x) + formatUnit(unit);
  }

  /**
   * 질문 스펙 → 7.3 질문 객체(id, seq 제외). 기준은 criteria의 인덱스로 가리킨다. ranges는 기준 ID → 미리 만든 범위 문자열이며, 있으면
   * 척도·단계 라벨 대신 그대로 쓴다.
   */
  public static Map<String, Object> buildQuestion(
      QuestionSpec spec,
      List<CriterionMeta> criteria,
      Map<String, Scale> scales,
      String category,
      Map<String, String> ranges) {
    if (spec instanceof SliderSpec) {
      CriterionMeta[] p = pair(spec, criteria);
      String ra = rangeOf(p[0], scales, ranges);
      String rb = rangeOf(p[1], scales, ranges);
      // s > 0이면 왼쪽(left, 기준 a)이 더 중요 (5.3)
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("left", linked("criterionId", p[0].id(), "label", p[0].name(), "range", ra));
      payload.put("right", linked("criterionId", p[1].id(), "label", p[1].name(), "range", rb));
      payload.put("min", SLIDER_MIN);
      payload.put("max", Likelihood.SLIDER_MAX);
      return wrap(
          "slider_pair", List.of(p[0].id(), p[1].id()), rangeTitle(p[0], ra, p[1], rb), payload);
    }
    if (spec instanceof CriteriaABSpec) {
      CriterionMeta[] p = pair(spec, criteria);
      String ra = rangeOf(p[0], scales, ranges);
      String rb = rangeOf(p[1], scales, ranges);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("optionA", linked("criterionId", p[0].id(), "label", p[0].name()));
      payload.put("optionB", linked("criterionId", p[1].id(), "label", p[1].name()));
      return wrap(
          "ab_criteria", List.of(p[0].id(), p[1].id()), rangeTitle(p[0], ra, p[1], rb), payload);
    }
    return scenario((ScenarioSpec) spec, criteria, scales, category);
  }

  public static Map<String, Object> buildQuestion(
      QuestionSpec spec, List<CriterionMeta> criteria, Map<String, Scale> scales, String category) {
    return buildQuestion(spec, criteria, scales, category, Map.of());
  }

  private static Map<String, Object> linked(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static Map<String, Object> wrap(
      String type, List<String> criterionIds, String title, Map<String, Object> payload) {
    return linked(
        "type", type,
        "criteria", criterionIds,
        "title", title,
        "payload", payload,
        "allowFreeText", ALLOW_FREE_TEXT,
        "allowSkip", ALLOW_SKIP);
  }

  private static CriterionMeta[] pair(QuestionSpec spec, List<CriterionMeta> criteria) {
    for (int idx : new int[] {spec.a(), spec.b()}) {
      if (idx < 0 || idx >= criteria.size()) {
        throw new IllegalArgumentException(
            "기준 인덱스가 범위를 벗어났다: " + idx + " (기준 " + criteria.size() + "개)");
      }
    }
    if (spec.a() == spec.b()) {
      throw new IllegalArgumentException("서로 다른 두 기준이어야 한다");
    }
    return new CriterionMeta[] {criteria.get(spec.a()), criteria.get(spec.b())};
  }

  // 5.1: "가격 차이(150~189만 원)와 무게 차이(1.19~1.45kg) 중 어느 쪽이 더 중요한가요?"
  private static String rangeTitle(CriterionMeta ca, String ra, CriterionMeta cb, String rb) {
    return ca.name() + " 차이(" + ra + ")와 " + cb.name() + " 차이(" + rb + ") 중 어느 쪽이 더 중요한가요?";
  }

  private static Scale scaleOf(CriterionMeta c, Map<String, Scale> scales) {
    Scale scale = scales.get(c.id());
    if (scale == null) {
      throw new IllegalArgumentException("숫자형 기준 " + c.id() + "의 척도가 없다");
    }
    return scale;
  }

  /** 기준 하나의 범위 표기 (5.1). */
  private static String rangeOf(
      CriterionMeta c, Map<String, Scale> scales, Map<String, String> ranges) {
    if (ranges.containsKey(c.id())) {
      return ranges.get(c.id());
    }
    if (c.type() == Criterion.Type.NUMERIC) {
      Scale scale = scaleOf(c, scales);
      return formatRange(scale.min(), scale.max(), c.unit());
    }
    if (c.type() == Criterion.Type.BOOLEAN) {
      return "없음~있음";
    }
    boolean hasLevels = c.levels() != null && !c.levels().isEmpty();
    String low =
        c.lowName() != null && !c.lowName().isEmpty()
            ? c.lowName()
            : (hasLevels ? c.levels().get(0) : null);
    String high =
        c.highName() != null && !c.highName().isEmpty()
            ? c.highName()
            : (hasLevels ? c.levels().get(c.levels().size() - 1) : null);
    if (low == null || high == null || low.isEmpty() || high.isEmpty()) {
      throw new IllegalArgumentException("단계형 기준 " + c.id() + "의 범위 이름(선택지 이름 또는 단계 라벨)이 없다");
    }
    return low + "~" + high;
  }

  private static Map<String, Object> scenario(
      ScenarioSpec spec, List<CriterionMeta> criteria, Map<String, Scale> scales, String category) {
    CriterionMeta[] p = pair(spec, criteria);
    for (CriterionMeta c : p) {
      if (c.isRank()) {
        throw new IllegalArgumentException("순위 입력 기준 " + c.id() + "는 상황형에 쓰지 않는다 (5.5)");
      }
      if (c.type() == Criterion.Type.NUMERIC) {
        scaleOf(c, scales);
      }
    }
    // A는 a가 rawA만큼 좋고 b가 rawB만큼 나쁘다. B는 반대
    Map<String, Object> optionA =
        option("A", new Part(p[0], spec.rawA(), true), new Part(p[1], spec.rawB(), false));
    Map<String, Object> optionB =
        option("B", new Part(p[0], spec.rawA(), false), new Part(p[1], spec.rawB(), true));
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("optionA", optionA);
    payload.put("optionB", optionB);
    return wrap(
        "ab_scenario",
        List.of(p[0].id(), p[1].id()),
        "다음 두 " + nounForCategory(category) + " 중 어느 쪽을 고르시겠어요?",
        payload);
  }

  private record Part(CriterionMeta c, double magnitude, boolean better) {}

  private static Map<String, Object> option(String label, Part first, Part second) {
    List<String> fragments = new ArrayList<>();
    List<Object> deltas = new ArrayList<>();
    for (Part part : List.of(first, second)) {
      fragments.add(fragment(part.c(), part.magnitude(), part.better()));
      deltas.add(
          linked(
              "criterionId",
              part.c().id(),
              "display",
              deltaDisplay(part.c(), part.magnitude(), part.better())));
    }
    return linked("label", label, "description", String.join(", ", fragments), "deltas", deltas);
  }

  /** 5.5 문장 조각. better면 그 선택지가 상대보다 이 기준에서 좋다. */
  private static String fragment(CriterionMeta c, double magnitude, boolean better) {
    return switch (c.type()) {
      case NUMERIC ->
          c.name() + " " + formatValue(magnitude, c.unit()) + " 더 " + (better ? "좋음" : "나쁨");
      case RATING ->
          c.name() + "이(가) " + formatNumber(magnitude) + "단계 더 " + (better ? "높음" : "낮음");
      case BOOLEAN -> c.name() + " " + (better ? "있음" : "없음");
    };
  }

  /** 상대 선택지 대비 값이 어떻게 달라졌는지 (부호 포함, 원래 단위). */
  private static String deltaDisplay(CriterionMeta c, double magnitude, boolean better) {
    if (c.type() == Criterion.Type.BOOLEAN) {
      return better ? "있음" : "없음";
    }
    boolean positive =
        c.type() == Criterion.Type.NUMERIC
            ? better == (c.direction() != Criterion.Direction.LOWER_BETTER)
            : better;
    String sign = positive ? "+" : "-";
    if (c.type() == Criterion.Type.RATING) {
      return sign + formatNumber(magnitude) + "단계";
    }
    return sign + formatValue(magnitude, c.unit());
  }
}

package com.seonbi.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 출력의 숫자·단계 라벨 검증 (개발기획서 6.2 phrase_question, 6.3 explain_result). 정규식으로 출력 속 숫자를 뽑아 서버가 준 값에
 * 있는지 본다. 보수적으로 판단한다 — 의심되면 거절하고 템플릿을 쓴다.
 */
public final class OutputVerifier {

  private static final Pattern NUMBER =
      Pattern.compile("\\d+(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?");
  // "c1", "o3" 같은 식별자는 숫자로 세지 않는다
  private static final Pattern ID_LIKE = Pattern.compile("^[A-Za-z]{1,2}\\d+$");

  private static final Set<String> ALL_LEVEL_LABELS = new HashSet<>();

  static {
    DefaultCriteria.PRESETS.values().forEach(ALL_LEVEL_LABELS::addAll);
  }

  private OutputVerifier() {}

  private static BigDecimal dec(String value) {
    BigDecimal d = new BigDecimal(value.replace(",", ""));
    return d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
  }

  private static BigDecimal dec(double value) {
    BigDecimal d = new BigDecimal(Double.toString(value));
    return d.signum() == 0 ? BigDecimal.ZERO : d.stripTrailingZeros();
  }

  /** 텍스트 속 숫자(부호 제외 크기, 천 단위 쉼표 제거). */
  public static Set<BigDecimal> numbersIn(String text) {
    Set<BigDecimal> out = new HashSet<>();
    Matcher m = NUMBER.matcher(text);
    while (m.find()) {
      out.add(dec(m.group()));
    }
    return out;
  }

  /** JSON 구조의 (부모 키, 말단 값)을 모두 모은다. */
  private static void walk(Object obj, String key, List<Map.Entry<String, Object>> out) {
    if (obj instanceof Map<?, ?> map) {
      for (Map.Entry<?, ?> e : map.entrySet()) {
        walk(e.getValue(), String.valueOf(e.getKey()), out);
      }
    } else if (obj instanceof Iterable<?> list) {
      for (Object v : list) {
        walk(v, key, out);
      }
    } else {
      out.add(Map.entry(key, obj == null ? "" : obj));
    }
  }

  private static boolean isIdKey(String key) {
    String low = key.toLowerCase();
    return low.equals("id") || low.equals("ids") || low.endsWith("id") || low.endsWith("ids");
  }

  private static List<String> leafStrings(Object obj, Set<String> keys) {
    List<Map.Entry<String, Object>> leaves = new ArrayList<>();
    walk(obj, "", leaves);
    List<String> out = new ArrayList<>();
    for (Map.Entry<String, Object> e : leaves) {
      if (e.getValue() instanceof String s
          && !isIdKey(e.getKey())
          && !ID_LIKE.matcher(s).matches()
          && (keys == null || keys.contains(e.getKey()))) {
        out.add(s);
      }
    }
    return out;
  }

  /** phrase_question용: 지정한 키(display·range·description 등)의 문자열에 나온 숫자만 허용한다. */
  public static Set<BigDecimal> serverNumbersStrict(Object spec, Set<String> keys) {
    Set<BigDecimal> allowed = new HashSet<>();
    for (String text : leafStrings(spec, keys)) {
      allowed.addAll(numbersIn(text));
    }
    return allowed;
  }

  /** explain_result용: 입력 JSON의 모든 숫자·문자열 속 숫자. 반올림(0~2자리)과 0~1 값의 퍼센트 표기도 허용한다. */
  public static Set<BigDecimal> serverNumbersLoose(Object data) {
    Set<BigDecimal> allowed = new HashSet<>();
    List<Map.Entry<String, Object>> leaves = new ArrayList<>();
    walk(data, "", leaves);
    for (Map.Entry<String, Object> e : leaves) {
      Object value = e.getValue();
      if (value instanceof Boolean || isIdKey(e.getKey())) {
        continue;
      }
      if (value instanceof Number n) {
        double v = n.doubleValue();
        allowed.add(dec(v));
        for (int digits = 0; digits <= 2; digits++) {
          allowed.add(dec(round(v, digits)));
        }
        if (Math.abs(v) <= 1) {
          allowed.add(dec(round(v * 100, 0)));
          allowed.add(dec(round(v * 100, 1)));
        }
      } else if (value instanceof String s && !ID_LIKE.matcher(s).matches()) {
        allowed.addAll(numbersIn(s));
      }
    }
    return allowed;
  }

  // 파이썬 round()와 같은 반올림(정확한 이진 값 기준, 동점이면 짝수 쪽)
  private static double round(double v, int digits) {
    return new BigDecimal(v).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
  }

  /** texts에 allowed에 없는 숫자가 하나라도 있으면 true. */
  public static boolean hasForeignNumber(Iterable<String> texts, Set<BigDecimal> allowed) {
    for (String t : texts) {
      if (!allowed.containsAll(numbersIn(t))) {
        return true;
      }
    }
    return false;
  }

  /** 3.4 프리셋 단계 라벨이 출력에 나오는데 입력 JSON 문자열에는 없으면 true. */
  public static boolean hasForeignLevelLabel(List<String> texts, Object data) {
    String source = String.join(" ", leafStrings(data, null));
    String joined = String.join(" ", texts);
    for (String label : ALL_LEVEL_LABELS) {
      if (joined.contains(label) && !source.contains(label)) {
        return true;
      }
    }
    return false;
  }
}

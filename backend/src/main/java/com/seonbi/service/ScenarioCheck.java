package com.seonbi.service;

import com.seonbi.engine.Criterion;
import com.seonbi.engine.QuestionSpec.ScenarioSpec;
import com.seonbi.service.QuestionText.CriterionMeta;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * AI가 다듬은 상황형 선택지 설명이 서버가 정한 내용과 같은 뜻인지 확인한다.
 *
 * <p>숫자가 입력에 있는지만 보면 "가격 20만 원 더 좋음"을 "가격 20만 원 더 나쁨"으로 뒤집어도 통과한다. 그러면 사용자는 뒤집힌 설명을 보고 답하는데 엔진은 원래
 * Δ로 해석해 반대 선호를 배우게 된다. 그래서 기준마다 (이름, 크기, 좋음/나쁨)을 서버 값에서 다시 만들고, AI 문장에서 그 기준 이름 뒤 구간에 같은 크기와 같은
 * 방향의 말이 있고 반대 방향의 말이 없을 때만 받아들인다. 판단하기 애매하면 받지 않고 템플릿 문장을 쓴다(안전한 쪽).
 */
final class ScenarioCheck {

  /** 선택지 하나에서 기준 하나가 어떻게 다른가. magnitude는 화면 표기(없으면 null), better는 상대보다 좋은 쪽인가. */
  record Expect(String name, String magnitude, boolean better, Criterion.Type type) {}

  private static final List<String> GOOD = List.of("좋", "유리", "우수", "나은", "낫");
  private static final List<String> BAD = List.of("나쁘", "나빠", "나쁨", "나쁜", "불리", "못함", "못한", "뒤처");
  private static final List<String> UP = List.of("높", "많");
  private static final List<String> DOWN = List.of("낮", "적");

  private ScenarioCheck() {}

  /** [A의 기준 2개, B의 기준 2개]. A는 첫 기준이 좋고 둘째가 나쁘며 B는 반대다(QuestionText와 같은 규칙). */
  static List<List<Expect>> expectations(ScenarioSpec spec, List<CriterionMeta> criteria) {
    CriterionMeta first = criteria.get(spec.a());
    CriterionMeta second = criteria.get(spec.b());
    return List.of(
        List.of(expect(first, spec.rawA(), true), expect(second, spec.rawB(), false)),
        List.of(expect(first, spec.rawA(), false), expect(second, spec.rawB(), true)));
  }

  private static Expect expect(CriterionMeta c, double magnitude, boolean better) {
    String text =
        switch (c.type()) {
          case NUMERIC -> QuestionText.formatValue(magnitude, c.unit());
          case RATING -> QuestionText.formatNumber(magnitude) + "단계";
          case BOOLEAN -> null;
        };
    return new Expect(c.name(), text, better, c.type());
  }

  /** candidate가 expected와 같은 뜻인가. */
  static boolean consistent(List<Expect> expected, String candidate) {
    if (candidate == null) {
      return false;
    }
    // 기준 이름이 나온 자리 순서대로 구간을 나눈다
    List<int[]> spans = new ArrayList<>(); // {시작, 이름 끝, expect 번호}
    for (int i = 0; i < expected.size(); i++) {
      int at = candidate.indexOf(expected.get(i).name());
      if (at < 0) {
        return false;
      }
      spans.add(new int[] {at, at + expected.get(i).name().length(), i});
    }
    spans.sort(Comparator.comparingInt(s -> s[0]));
    for (int k = 0; k < spans.size(); k++) {
      int[] s = spans.get(k);
      int end = k + 1 < spans.size() ? spans.get(k + 1)[0] : candidate.length();
      String segment = candidate.substring(s[1], end);
      Expect e = expected.get(s[2]);
      if (e.magnitude() != null && !segment.contains(e.magnitude())) {
        return false;
      }
      if (!direction(e, segment)) {
        return false;
      }
    }
    return true;
  }

  // "좋지 않고"·"나쁘지 않음"처럼 부정하면 좋음/나쁨 글자가 있어도 뜻이 뒤집힌다. 부정을 풀이하지 않고 받지 않는 쪽으로 둔다
  private static final List<String> NEGATION = List.of("않", "아니", "못", "없");

  private static boolean direction(Expect e, String segment) {
    if (e.type() != Criterion.Type.BOOLEAN && NEGATION.stream().anyMatch(segment::contains)) {
      return false;
    }
    if (e.type() == Criterion.Type.BOOLEAN) {
      boolean has = segment.contains("있");
      boolean none = segment.contains("없");
      return e.better() ? has && !none : none && !has;
    }
    List<String> good = new ArrayList<>(GOOD);
    List<String> bad = new ArrayList<>(BAD);
    if (e.type() == Criterion.Type.RATING) { // 단계형은 높을수록 좋은 쪽이다
      good.addAll(UP);
      bad.addAll(DOWN);
    }
    List<String> want = e.better() ? good : bad;
    List<String> reject = e.better() ? bad : good;
    return want.stream().anyMatch(segment::contains)
        && reject.stream().noneMatch(segment::contains);
  }
}

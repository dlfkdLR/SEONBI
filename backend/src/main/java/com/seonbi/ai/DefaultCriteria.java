package com.seonbi.ai;

import com.seonbi.ai.LlmSchemas.RecommendCriterion;
import java.util.List;
import java.util.Map;

/**
 * 카테고리별 기본 기준 목록 (개발기획서 6.2 표, 3.4 프리셋). LLM 장애·쿼터 초과 시 fallback이자 Fake LLM의 응답 데이터다. 항목은
 * recommend_criteria 출력 스키마와 같은 모양이라 그대로 쓴다.
 */
public final class DefaultCriteria {

  /** 3.4 단계 라벨 프리셋 (나쁜 것 → 좋은 것). */
  public static final Map<String, List<String>> PRESETS =
      Map.of(
          "수준", List.of("매우 낮음", "낮음", "보통", "높음", "매우 높음"),
          "만족도", List.of("매우 불만", "불만", "보통", "만족", "매우 만족"),
          "적합도", List.of("안 맞음", "그럭저럭", "잘 맞음", "매우 잘 맞음"),
          "끌림", List.of("별로", "그럭저럭", "끌림", "매우 끌림"));

  // 6.2 표에서 프리셋을 적지 않은 "단계"는 3.4의 기본 프리셋(수준)이다
  private static final String DEFAULT_PRESET = "수준";
  public static final String DEFAULT_CATEGORY = "etc";

  private DefaultCriteria() {}

  private static RecommendCriterion num(String name, String unit, String reason) {
    return new RecommendCriterion(name, "numeric", "lower_better", unit, null, null, reason);
  }

  private static RecommendCriterion rating(String name, String reason, String preset) {
    return new RecommendCriterion(name, "rating", null, null, PRESETS.get(preset), "score", reason);
  }

  private static RecommendCriterion rating(String name, String reason) {
    return rating(name, reason, DEFAULT_PRESET);
  }

  /** 순위 입력 단계형: levels 생략(23번 career 예시). */
  private static RecommendCriterion rank(String name, String reason) {
    return new RecommendCriterion(name, "rating", null, null, null, "rank", reason);
  }

  private static final Map<String, List<RecommendCriterion>> BY_CATEGORY =
      Map.of(
          "purchase",
          List.of(
              num("가격", "만 원", "구매 결정의 기본 기준"),
              rating("성능", "쓰임새에 맞는 성능인지 비교"),
              rating("디자인", "오래 쓰면서 만족할 겉모습"),
              num("무게/크기", null, "들고 다니거나 놓을 공간에 영향"),
              rating("내구성·A/S", "오래 안심하고 쓸 수 있는지")),
          "career",
          List.of(
              rank("흥미", "점수보다 순서로 답하기 쉬운 기준"),
              rating("적성", "잘 맞는지 가늠하는 핵심 기준", "적합도"),
              rating("취업·진로 전망", "졸업 후 진로에 미치는 영향"),
              num("통학 시간", "분", "매일 쓰는 시간 부담"),
              num("학비", "만 원", "비용 부담")),
          "study",
          List.of(
              rating("효과 기대", "공부 효과를 얼마나 기대하는지"),
              num("비용", "만 원", "들어가는 돈"),
              num("시간 부담", "시간/주", "매주 써야 하는 시간"),
              rating("난이도 적합", "내 수준에 맞는 정도", "적합도"),
              num("이동 거리", "분", "오가는 데 드는 시간")),
          "activity",
          List.of(
              rating("흥미", "얼마나 끌리는지", "끌림"),
              rating("배울 수 있는 것", "얻을 수 있는 경험과 지식"),
              num("시간 부담", "시간/주", "매주 써야 하는 시간"),
              rating("함께하는 사람", "같이 하는 사람들과의 만족", "만족도"),
              rating("포트폴리오 도움", "진학·취업 준비에 도움이 되는 정도")),
          // 6.2 표에서 "비용·수입"만 방향 표시가 없다 → 가장 단순하게 다른 비용 기준처럼 lower_better로 둔다
          "living",
          List.of(
              num("비용·수입", "만 원", "돈과 관련된 조건"),
              num("거리", "분", "생활 동선에 미치는 영향"),
              rating("분위기·환경", "지내기 편한 환경인지", "만족도"),
              rating("시간 유연성", "시간을 자유롭게 쓸 수 있는지"),
              rating("안전", "안심하고 지낼 수 있는지")),
          "etc",
          List.of(
              rating("만족도 기대", "결과에 대한 기대 만족", "만족도"),
              num("비용", null, "들어가는 돈"),
              num("시간", null, "들어가는 시간"),
              rating("위험 부담", "잘못됐을 때의 부담")));

  /** 카테고리별 기본 기준. 모르는 카테고리는 etc(가장 일반적인 목록)로 본다. */
  public static List<RecommendCriterion> forCategory(String category) {
    return BY_CATEGORY.getOrDefault(category, BY_CATEGORY.get(DEFAULT_CATEGORY));
  }
}

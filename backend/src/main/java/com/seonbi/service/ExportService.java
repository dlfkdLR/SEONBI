package com.seonbi.service;

import static com.seonbi.service.FlowErrors.details;

import com.seonbi.domain.Decision;
import com.seonbi.domain.FinalChoice;
import jakarta.persistence.EntityManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 48번 결과 내보내기 (개발기획서 7.3). Markdown만 만든다. {@code ANALYZED}·{@code FINALIZED}에서만 되고 그 외는 422
 * NOT_READY다. PDF는 프론트가 브라우저 인쇄로 만든다.
 *
 * <p>구성: 제목·설명·카테고리 → 선택지 × 기준 값 표 → 가중치 → 순위·1위 확률 → 안정성 → 전환 조건 → 숨은 선호 → 제외된 선택지 → 최종 선택·메모.
 */
@Service
@Transactional
public class ExportService {

  private final EntityManager em;
  private final DecisionResolver resolver;
  private final ConfigurationService configuration;
  private final AnalysisService analysis;

  public ExportService(
      EntityManager em,
      DecisionResolver resolver,
      ConfigurationService configuration,
      AnalysisService analysis) {
    this.em = em;
    this.resolver = resolver;
    this.configuration = configuration;
    this.analysis = analysis;
  }

  /** 파일 이름에 쓸 결정 ID를 확인하려고 노출한다. */
  public record Exported(UUID decisionId, String markdown) {}

  @SuppressWarnings("unchecked")
  public Exported markdown(UUID decisionId, UUID clientId) {
    Decision d = resolver.get(decisionId, clientId);
    if (!List.of("ANALYZED", "FINALIZED").contains(d.getStatus())) {
      throw FlowErrors.notReady(
          "분석이 끝난 결정만 내보낼 수 있습니다.",
          details("decisionId", d.getId().toString(), "status", d.getStatus()));
    }
    Map<String, Object> result = analysis.analysisOf(d);
    Map<String, Object> detail = configuration.detail(d);
    List<Map<String, Object>> options = (List<Map<String, Object>>) detail.get("options");
    List<Map<String, Object>> criteria = (List<Map<String, Object>>) detail.get("criteria");
    Map<String, String> optionNames = new HashMap<>();
    for (Map<String, Object> o : options) {
      optionNames.put(String.valueOf(o.get("id")), String.valueOf(o.get("name")));
    }

    StringBuilder md = new StringBuilder();
    md.append("# ").append(d.getTitle()).append("\n\n");
    md.append("- 카테고리: ").append(d.getCategory()).append('\n');
    md.append("- 상태: ").append(d.getStatus()).append("\n\n");
    if (d.getDescription() != null && !d.getDescription().isBlank()) {
      md.append(d.getDescription()).append("\n\n");
    }

    md.append("## 선택지와 기준 값\n\n");
    md.append("| 선택지 |");
    for (Map<String, Object> c : criteria) {
      md.append(' ').append(cell(String.valueOf(c.get("name")))).append(" |");
    }
    md.append("\n|---|");
    md.append("---|".repeat(criteria.size())).append('\n');
    Map<String, Object> values = new HashMap<>();
    for (Map<String, Object> v : (List<Map<String, Object>>) detail.get("values")) {
      values.put(v.get("optionId") + "/" + v.get("criterionId"), v.get("value"));
    }
    Map<String, Integer> ranks = new HashMap<>();
    for (Map<String, Object> r : (List<Map<String, Object>>) detail.get("rankings")) {
      int place = 1;
      for (List<Object> group : (List<List<Object>>) r.get("order")) {
        for (Object oid : group) {
          ranks.put(oid + "/" + r.get("criterionId"), place);
        }
        place++;
      }
    }
    for (Map<String, Object> o : options) {
      md.append("| ").append(cell(String.valueOf(o.get("name")))).append(" |");
      for (Map<String, Object> c : criteria) {
        String key = o.get("id") + "/" + c.get("id");
        md.append(' ').append(cell(valueText(c, values.get(key), ranks.get(key)))).append(" |");
      }
      md.append('\n');
    }
    md.append('\n');

    md.append("## 중요도 (평균 · 구간)\n\n");
    for (Map<String, Object> w : (List<Map<String, Object>>) result.get("weights")) {
      List<Number> interval = (List<Number>) w.get("interval");
      md.append("- ")
          .append(w.get("name"))
          .append(": ")
          .append(percent(w.get("mean")))
          .append(" (")
          .append(percent(interval.get(0)))
          .append(" ~ ")
          .append(percent(interval.get(1)))
          .append(")\n");
    }

    md.append("\n## 순위 · 1위 확률\n\n");
    for (Map<String, Object> r : (List<Map<String, Object>>) result.get("rankings")) {
      md.append(r.get("rank"))
          .append(". ")
          .append(r.get("name"))
          .append(" — 점수 ")
          .append(percent(r.get("score")))
          .append(", 1위 확률 ")
          .append(percent(r.get("topProbability")))
          .append('\n');
    }

    Map<String, Object> stability = (Map<String, Object>) result.get("stability");
    md.append("\n## 결과 안정성\n\n").append(stability.get("text")).append('\n');

    md.append("\n## 추천이 바뀌는 조건\n\n");
    for (Map<String, Object> c : (List<Map<String, Object>>) result.get("changeConditions")) {
      md.append("- ").append(c.get("text")).append('\n');
    }

    List<Map<String, Object>> hidden = (List<Map<String, Object>>) result.get("hiddenPreferences");
    if (!hidden.isEmpty()) {
      md.append("\n## 답변에서 발견한 숨은 선호\n\n");
      for (Map<String, Object> h : hidden) {
        md.append("- ").append(h.get("text")).append('\n');
      }
    }

    List<Map<String, Object>> excluded = (List<Map<String, Object>>) result.get("excludedOptions");
    if (!excluded.isEmpty()) {
      md.append("\n## 제외된 선택지\n\n");
      for (Map<String, Object> e : excluded) {
        md.append("- ")
            .append(optionNames.getOrDefault(String.valueOf(e.get("optionId")), "?"))
            .append(": ")
            .append(String.join(", ", (List<String>) e.get("reasons")))
            .append('\n');
      }
    }

    FinalChoice choice =
        em.createQuery("select f from FinalChoice f where f.decision.id=:id", FinalChoice.class)
            .setParameter("id", d.getId())
            .getResultStream()
            .findFirst()
            .orElse(null);
    if (choice != null) {
      md.append("\n## 최종 선택\n\n");
      md.append("- 선택: ").append(choice.getOption().getName()).append('\n');
      md.append("- 추천과 ").append(choice.getMatchedRecommendation() ? "같음" : "다름").append('\n');
      if (choice.getMemo() != null && !choice.getMemo().isBlank()) {
        md.append("- 메모: ").append(choice.getMemo()).append('\n');
      }
    }
    return new Exported(d.getId(), md.toString());
  }

  // 표 칸 안에서 표를 깨는 문자(|, 줄바꿈)를 바꾼다
  private static String cell(String text) {
    return text.replace("|", "\\|").replace("\n", " ");
  }

  private static String percent(Object x) {
    return Math.round(((Number) x).doubleValue() * 1000) / 10.0 + "%";
  }

  @SuppressWarnings("unchecked")
  private static String valueText(Map<String, Object> criterion, Object value, Integer rank) {
    if (rank != null) {
      return rank + "위";
    }
    if (value == null) {
      return "—";
    }
    String type = String.valueOf(criterion.get("type"));
    if ("boolean".equals(type)) {
      return Boolean.TRUE.equals(value) ? "예" : "아니요";
    }
    String text = QuestionText.formatNumber(((Number) value).doubleValue());
    if ("rating".equals(type)) {
      List<String> levels = (List<String>) criterion.get("levels");
      int i = (int) Math.round(((Number) value).doubleValue()) - 1;
      return levels != null && i >= 0 && i < levels.size() ? levels.get(i) : text;
    }
    Object unit = criterion.get("unit");
    return unit == null ? text : text + unit;
  }
}

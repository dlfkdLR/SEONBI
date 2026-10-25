package com.seonbi.service;

import com.seonbi.engine.Answer;
import com.seonbi.engine.DecisionAnalysis;
import com.seonbi.engine.DecisionAnalysis.ChangeCondition;
import com.seonbi.engine.DecisionAnalysis.Stability;
import com.seonbi.engine.DecisionAnalysis.ValueCondition;
import com.seonbi.engine.HiddenPreference.Mismatch;
import com.seonbi.engine.Likelihood;
import com.seonbi.engine.Likelihood.PreferenceKind;
import com.seonbi.engine.Posterior;
import com.seonbi.engine.Response;
import com.seonbi.engine.Stopping;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 분석·질문 API 응답(7.3 31·37·39~45번)의 조각을 엔진 결과로 만드는 순수 함수 (camelCase 맵). 엔진은 기준·선택지를 인덱스로 가리킨다. 호출하는
 * 쪽이 기준·선택지 ID와 이름을 엔진 인덱스 순서대로 넘기면 여기서 API ID·이름으로 바꾼다. DB·Spring은 쓰지 않는다.
 *
 * <p>반올림 규칙: 확률·평균 가중치·점수·신뢰 구간은 소수 둘째 자리(PROB_DIGITS), 전환 조건의 현재 가중치·임계값과 값 전환 조건의 값은 소수 넷째
 * 자리(THRESHOLD_DIGITS). 문장 속 퍼센트는 정수로 반올림한다. 등급·종료 판단 같은 분기는 반올림 전 값으로 한다.
 */
public final class AnalysisHelpers {

  public static final int PROB_DIGITS = 2;
  public static final int THRESHOLD_DIGITS = 4;
  public static final double INTERVAL_LEVEL = 0.8; // 40번 intervalLevel — 10~90% 구간
  public static final double COMPETITOR_MIN_P = 0.05; // 44번: 1위가 아닌 선택지 중 P_best가 이 값 이상이면 경쟁자
  private static final double TIE_TOL = 1e-12;

  /** 5.6: 숨은 선호 결과에 항상 붙이는 고정 문구 (text에는 넣지 않고 화면이 따로 보여 준다). */
  public static final String HIDDEN_PREFERENCE_NOTICE = "응답에서 추정한 경향이며 단정하지 않습니다";

  public static final String STABILITY_TEXT_HIGH = "가중치가 조금 달라져도 1위가 유지될 가능성이 높아요.";
  // 명세에 없는 경우(전환 조건이나 경쟁 선택지가 하나도 없는데 등급이 HIGH가 아님)에 쓰는 고정 문장
  public static final String STABILITY_TEXT_FALLBACK = "중요도를 어떻게 두느냐에 따라 1위가 바뀔 수 있어요.";

  private AnalysisHelpers() {}

  private static Map<String, Object> obj(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  // ── 31번 · 37번 · 35/39번 ──────────────────────────────────────

  /** progress.answered. 자유 입력으로 만든 가상 답변(FREE_TEXT)은 뺀다. 잘 모르겠어요는 센다. */
  public static int answeredCount(List<Answer> answers) {
    return Stopping.countedAnswers(answers);
  }

  /** 31번 progress.expectedRange. 첫 라운드 [3, maxQuestions(K)], 추가 라운드 [답변 수 + 1, questionLimit]. */
  public static List<Integer> expectedRange(
      StateRules.RoundKind kind, int k, int answered, int questionLimit) {
    if (kind == StateRules.RoundKind.INITIAL) {
      return List.of(Stopping.MIN_QUESTIONS, Stopping.maxQuestions(k));
    }
    return List.of(answered + 1, questionLimit);
  }

  /**
   * 화면에 보여 주는 1위 확률의 상한. 입자 4,000개로 어림한 값이 100%여도 "반례를 4,000번 안에 못 찾았다"는 뜻일 뿐 완전히 확실하다는 뜻이 아니므로
   * 99%까지만 보여 준다(모든 기준에서 앞서는 경우는 설명 문장으로 따로 알려 준다). 낮은 확신 판정(lowConfidence)과 저장되는 값은 원래 값을 쓴다.
   */
  public static final double MAX_SHOWN_PROBABILITY = 0.99;

  public static double shown(double probability) {
    return Math.min(probability, MAX_SHOWN_PROBABILITY);
  }

  /** 추천 선택지가 모든 기준에서 다른 선택지와 같거나 더 좋고 하나 이상은 더 좋은가 (가중치와 상관없이 1위가 확정). */
  public static boolean dominatesAll(double[][] v, int top) {
    boolean strictlyBetterThanAll = v.length > 1;
    for (int i = 0; i < v.length; i++) {
      if (i == top) {
        continue;
      }
      boolean atLeast = true;
      boolean better = false;
      for (int k = 0; k < v[top].length; k++) {
        if (v[top][k] < v[i][k] - 1e-12) {
          atLeast = false;
        }
        if (v[top][k] > v[i][k] + 1e-12) {
          better = true;
        }
      }
      if (!atLeast || !better) {
        strictlyBetterThanAll = false;
      }
    }
    return strictlyBetterThanAll;
  }

  /** 35번·39번 lowConfidence — 같은 값을 쓰도록 이 함수 하나만 거친다 (P_best &lt; 0.55). */
  public static boolean lowConfidence(double pBestTop) {
    return Stopping.isLowConfidence(pBestTop);
  }

  /**
   * 평균 가중치 기준 1위 선택지의 P_best (39번 recommendation.topProbability, 37번 topProbability, 44번 score).
   */
  public static double topProbability(Posterior.Summary s, double[][] v) {
    return s.pBest()[DecisionAnalysis.ranking(s.mean(), v).get(0)];
  }

  /** 37·38번 estimate. */
  public static Map<String, Object> estimatePayload(
      Posterior.Summary s, double[][] v, List<String> criterionIds) {
    List<Object> weights = new ArrayList<>();
    for (int k = 0; k < criterionIds.size(); k++) {
      weights.add(
          obj(
              "criterionId", criterionIds.get(k),
              "mean", p(s.mean()[k]),
              "interval", List.of(p(s.intervals()[k][0]), p(s.intervals()[k][1]))));
    }
    return obj("weights", weights, "topProbability", p(shown(topProbability(s, v))));
  }

  // ── 40번 · 41번 ────────────────────────────────────────────────

  // 같은 사전 입자에 kind 답변만 재가중해 구한 평균 가중치의 기준별 순위(1 = 가장 큼). 근거 답변이 없으면 null
  private static int[] rankFromKind(double[][] wPrior, List<Answer> answers, PreferenceKind kind) {
    List<Answer> used =
        answers.stream()
            .filter(
                a ->
                    !(a.response() instanceof Response.Unknown)
                        && Likelihood.preferenceKind(a.spec()) == kind)
            .toList();
    if (used.isEmpty()) {
      return null;
    }
    double[] pw = Posterior.reweight(wPrior, used);
    int k = wPrior[0].length;
    double[] mean = new double[k];
    for (int i = 0; i < wPrior.length; i++) {
      for (int j = 0; j < k; j++) {
        mean[j] += pw[i] * wPrior[i][j];
      }
    }
    int[] rank = new int[k];
    for (int j = 0; j < k; j++) {
      int greater = 0;
      for (int x = 0; x < k; x++) {
        if (mean[x] > mean[j] + TIE_TOL) {
          greater++;
        }
      }
      rank[j] = 1 + greater;
    }
    return rank;
  }

  /**
   * 40번 items. 응답의 intervalLevel은 {@link #INTERVAL_LEVEL}(0.8). wPrior는 {@link
   * Posterior#priorParticles}, answers는 유효 답변 전체(자유 입력의 가상 슬라이더 답변도 추정에 쓰이므로 진술 순위에 포함한다).
   */
  public static List<Map<String, Object>> weightsItems(
      Posterior.Summary s,
      List<String> criterionIds,
      List<String> criterionNames,
      List<Answer> answers,
      double[][] wPrior) {
    int[] stated = rankFromKind(wPrior, answers, PreferenceKind.STATED);
    int[] revealed = rankFromKind(wPrior, answers, PreferenceKind.REVEALED);
    List<Map<String, Object>> out = new ArrayList<>();
    for (int k = 0; k < criterionIds.size(); k++) {
      out.add(
          obj(
              "criterionId", criterionIds.get(k),
              "name", criterionNames.get(k),
              "mean", p(s.mean()[k]),
              "interval", List.of(p(s.intervals()[k][0]), p(s.intervals()[k][1])),
              "statedRank", stated == null ? null : stated[k],
              "revealedRank", revealed == null ? null : revealed[k]));
    }
    return out;
  }

  /** 41번 items. v·pBest·option*는 제외되지 않은 선택지만 같은 순서로 넣는다. 동점이면 입력 순서. */
  public static List<Map<String, Object>> rankingsItems(
      double[] mean,
      double[][] v,
      double[] pBest,
      List<String> optionIds,
      List<String> optionNames) {
    double[] u = DecisionAnalysis.scores(mean, v);
    List<Map<String, Object>> out = new ArrayList<>();
    int rank = 1;
    for (int i : DecisionAnalysis.ranking(mean, v)) {
      out.add(
          obj(
              "rank", rank++,
              "optionId", optionIds.get(i),
              "name", optionNames.get(i),
              "score", p(u[i]),
              "topProbability", p(shown(pBest[i]))));
    }
    return out;
  }

  // ── 44번 ───────────────────────────────────────────────────────

  /**
   * 44번 응답. pBest는 Summary.pBest(선택지별 1위 확률). text: HIGH면 고정 문장. 그 외에는 "{가장 가까운 전환 조건의 기준}을(를) 얼마나
   * 중시하는지에 따라 {1순위 경쟁 선택지}(으)로 바뀔 수 있어요." — "가장 가까운"은 임계값이 있는 전환 조건 중 |임계값 − 현재 가중치|가 가장 작은 것으로
   * 읽었다. 그런 전환 조건이나 경쟁 선택지가 없으면 STABILITY_TEXT_FALLBACK을 쓴다(명세에 없음).
   */
  public static Map<String, Object> stabilityPayload(
      Stability st,
      double[] pBest,
      List<ChangeCondition> changeConds,
      List<String> optionIds,
      List<String> optionNames,
      List<String> criterionNames) {
    record Competitor(int index, double p) {}
    List<Competitor> competitors = new ArrayList<>();
    for (int i = 0; i < pBest.length; i++) {
      if (i != st.top() && pBest[i] >= COMPETITOR_MIN_P) {
        competitors.add(new Competitor(i, pBest[i]));
      }
    }
    competitors.sort(Comparator.comparingDouble((Competitor c) -> -c.p())); // 안정 정렬: 동률이면 선택지 순서
    String text;
    if (st.grade() == DecisionAnalysis.Grade.HIGH) {
      text = STABILITY_TEXT_HIGH;
    } else {
      ChangeCondition nearest = null;
      for (ChangeCondition c : changeConds) {
        if (c.threshold() != null
            && (nearest == null
                || Math.abs(c.threshold() - c.current())
                    < Math.abs(nearest.threshold() - nearest.current()))) {
          nearest = c;
        }
      }
      if (nearest != null && !competitors.isEmpty()) {
        text =
            Korean.eulReul(criterionNames.get(nearest.criterion()))
                + " 얼마나 중시하는지에 따라 "
                + Korean.euRo(optionNames.get(competitors.get(0).index()))
                + " 바뀔 수 있어요.";
      } else {
        text = STABILITY_TEXT_FALLBACK;
      }
    }
    List<Object> comps = new ArrayList<>();
    for (Competitor c : competitors) {
      comps.add(obj("optionId", optionIds.get(c.index()), "probability", p(c.p())));
    }
    return obj(
        "optionId", optionIds.get(st.top()),
        "score", p(st.pBest()),
        "level", st.grade().name(),
        "localPerturbationKeepRate", p(st.perturbKeep()),
        "competitors", comps,
        "text", text);
  }

  // ── 45번 ───────────────────────────────────────────────────────

  /**
   * 45번 응답. 가중치 항목(기준 순서) 뒤에 값 항목(P1, valueConditions가 준 순서)을 붙인다.
   *
   * <ul>
   *   <li>가중치 항목에 임계값이 있으면 명세 예시의 키 전부, 없으면 예시처럼 type·criterionId·threshold(null)·text만 낸다.
   *   <li>값 항목은 type·optionId·criterionId·currentValue·threshold·text. 방향은 text에만 담는다(명세 키에 없음).
   *   <li>criterionUnits는 숫자형 기준의 단위("만 원", "kg"; 없으면 ""), criterionLevels는 단계형 기준의 단계 라벨(나쁜 것 → 좋은
   *       것). 둘 다 기준 인덱스 순서이고, 값 항목이 없으면 비워도 된다.
   * </ul>
   */
  public static Map<String, Object> changeConditionsPayload(
      List<ChangeCondition> conds,
      int currentTop,
      List<String> criterionIds,
      List<String> criterionNames,
      List<String> optionIds,
      List<String> optionNames,
      List<ValueCondition> valueConds,
      List<String> criterionUnits,
      List<List<String>> criterionLevels) {
    List<Object> items = new ArrayList<>();
    for (ChangeCondition c : conds) {
      String name = criterionNames.get(c.criterion());
      if (c.threshold() == null || c.newTop() == null || c.direction() == null) {
        items.add(
            obj(
                "type",
                "weight",
                "criterionId",
                criterionIds.get(c.criterion()),
                "threshold",
                null,
                "text",
                name + " 중요도가 어떻게 바뀌어도 1위가 유지됩니다."));
        continue;
      }
      String bound = c.direction() == DecisionAnalysis.Direction.INCREASE ? "이상이" : "이하가";
      items.add(
          obj(
              "type", "weight",
              "criterionId", criterionIds.get(c.criterion()),
              "currentWeight", t(c.current()),
              "threshold", t(c.threshold()),
              "direction", c.direction().name().toLowerCase(),
              "newTop", optionIds.get(c.newTop()),
              "text",
                  name
                      + " 중요도가 "
                      + pct(c.current())
                      + "% → "
                      + pct(c.threshold())
                      + "% "
                      + bound
                      + " 되면 "
                      + Korean.iGa(optionNames.get(c.newTop()))
                      + " 1위가 됩니다."));
    }
    for (ValueCondition v : valueConds) {
      items.add(
          obj(
              "type", "value",
              "optionId", optionIds.get(v.option()),
              "criterionId", criterionIds.get(v.criterion()),
              "currentValue", num(v.current()),
              "threshold", num(v.threshold()),
              "text", valueText(v, criterionNames, optionNames, criterionUnits, criterionLevels)));
    }
    return obj("currentTop", optionIds.get(currentTop), "items", items);
  }

  private static String valueText(
      ValueCondition v,
      List<String> criterionNames,
      List<String> optionNames,
      List<String> criterionUnits,
      List<List<String>> criterionLevels) {
    String head =
        optionNames.get(v.option()) + "의 " + Korean.iGa(criterionNames.get(v.criterion()));
    List<String> levels =
        v.criterion() < criterionLevels.size() ? criterionLevels.get(v.criterion()) : List.of();
    if (!levels.isEmpty()) { // 단계형: 라벨로 말한다
      String now = levelLabel(levels, v.current());
      String target = levelLabel(levels, v.threshold());
      return head + " '" + now + "'에서 " + quotedIGa(target) + " 되면 1위가 됩니다.";
    }
    String unit = v.criterion() < criterionUnits.size() ? criterionUnits.get(v.criterion()) : "";
    String amount =
        formatNum(num(round(Math.abs(v.current() - v.threshold()), THRESHOLD_DIGITS))) + unit;
    String verb = v.direction() == DecisionAnalysis.Direction.INCREASE ? "올라가면" : "내려가면";
    return head + " " + amount + " 이상 " + verb + " 1위가 됩니다.";
  }

  private static String levelLabel(List<String> levels, double level) {
    int idx = (int) Math.rint(level) - 1; // 파이썬 round()와 같은 짝수 쪽 반올림
    return idx >= 0 && idx < levels.size() ? levels.get(idx) : (int) Math.rint(level) + "단계";
  }

  private static String quotedIGa(String label) {
    return "'" + label + "'" + (Korean.hasBatchim(label) ? "이" : "가");
  }

  // ── 39번 hiddenPreferences ─────────────────────────────────────

  /**
   * 39번 hiddenPreferences. A = 진술에서 더 중요하다고 한 기준(statedOver), B = 실제 선택에서 더 중시한 기준(revealedOver)이라
   * stated는 항상 A_MORE_IMPORTANT, revealed는 항상 B_MORE_IMPORTANT다(명세 예시와 같다). strength = min(진술 쪽
   * P(w_a &gt; w_b), 1 − 드러난 쪽 P(w_a &gt; w_b)).
   */
  public static List<Map<String, Object>> hiddenPreferencesPayload(
      List<Mismatch> mismatches, List<String> criterionIds, List<String> criterionNames) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Mismatch m : mismatches) {
      String a = criterionNames.get(m.statedOver());
      String b = criterionNames.get(m.revealedOver());
      out.add(
          obj(
              "criterionA",
              criterionIds.get(m.statedOver()),
              "criterionB",
              criterionIds.get(m.revealedOver()),
              "stated",
              "A_MORE_IMPORTANT",
              "revealed",
              "B_MORE_IMPORTANT",
              "strength",
              p(Math.min(m.statedP(), 1.0 - m.revealedP())),
              "text",
              Korean.iGa(a)
                  + " 더 중요하다고 답했지만, 실제 선택에서는 "
                  + Korean.eulReul(b)
                  + " 위해 "
                  + Korean.eulReul(a)
                  + " 양보하는 경향이 보였어요."));
    }
    return out;
  }

  // ── 반올림 ─────────────────────────────────────────────────────

  // 파이썬 round()와 같다: 정확한 이진 값 기준, 동점이면 짝수 쪽
  private static double round(double x, int digits) {
    return new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
  }

  static double p(double x) {
    return round(x, PROB_DIGITS);
  }

  static double t(double x) {
    return round(x, THRESHOLD_DIGITS);
  }

  /** 원래 값 표기: 소수 넷째 자리까지 반올림하고 정수값이면 정수로 낸다(210.0 → 210). */
  static Number num(double x) {
    double r = round(x, THRESHOLD_DIGITS);
    return r == Math.floor(r) ? (Number) (long) r : (Number) r;
  }

  private static String formatNum(Number n) {
    return n instanceof Long ? n.toString() : QuestionText.formatNumber(n.doubleValue());
  }

  static int pct(double x) {
    return (int) Math.floor(x * 100 + 0.5);
  }
}

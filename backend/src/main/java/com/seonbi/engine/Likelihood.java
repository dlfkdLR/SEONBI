package com.seonbi.engine;

import com.seonbi.engine.QuestionSpec.CriteriaABSpec;
import com.seonbi.engine.QuestionSpec.ScenarioSpec;
import com.seonbi.engine.QuestionSpec.SliderSpec;
import java.util.ArrayList;
import java.util.List;

/** 질문 유형별 로그 우도 (개발기획서 5.3). */
public final class Likelihood {

  public static final double BT_LAMBDA = 1.0; // Bradley-Terry 노이즈 계수 λ
  public static final double SLIDER_C = 0.3; // 슬라이더 한 칸이 뜻하는 log(w_a/w_b)
  public static final double SLIDER_SIGMA = 0.6;
  public static final int SLIDER_MAX = 5; // 응답 범위 −5..+5
  public static final double SCENARIO_BETA = 8.0; // 상황형 로지스틱 β
  private static final double EPS = 1e-12;

  private Likelihood() {}

  public enum PreferenceKind {
    STATED,
    REVEALED
  }

  /** 슬라이더·기준 A/B는 진술 선호, 상황형은 드러난 선호 (5.6). */
  public static PreferenceKind preferenceKind(QuestionSpec spec) {
    return spec instanceof ScenarioSpec ? PreferenceKind.REVEALED : PreferenceKind.STATED;
  }

  /** 질문이 받을 수 있는 응답 목록. 열 순서는 {@link #responseLogProbs}와 같다. */
  public static List<Response> responseOptions(QuestionSpec spec) {
    List<Response> out = new ArrayList<>();
    if (spec instanceof SliderSpec) {
      for (int s = -SLIDER_MAX; s <= SLIDER_MAX; s++) {
        out.add(new Response.Slider(s));
      }
    } else {
      out.add(Response.Choice.A);
      out.add(Response.Choice.B);
    }
    return out;
  }

  /** 입자마다 가능한 모든 응답의 로그 확률 (N × 응답 수). */
  public static double[][] responseLogProbs(QuestionSpec spec, double[][] w) {
    if (spec instanceof SliderSpec s) {
      return sliderLogProbs(w, s.a(), s.b());
    }
    double[][] ab = logProbAB(spec, w);
    double[][] out = new double[w.length][2];
    for (int i = 0; i < w.length; i++) {
      out[i][0] = ab[0][i];
      out[i][1] = ab[1][i];
    }
    return out;
  }

  /** 입자마다 log P(응답 | w). "잘 모르겠어요"는 0 (정보 없음). */
  public static double[] logLikelihood(QuestionSpec spec, Response response, double[][] w) {
    double[] out = new double[w.length];
    if (response instanceof Response.Unknown) {
      return out;
    }
    if (spec instanceof SliderSpec s) {
      if (!(response instanceof Response.Slider r) || Math.abs(r.value()) > SLIDER_MAX) {
        throw new IllegalArgumentException(
            "슬라이더 응답은 -" + SLIDER_MAX + ".." + SLIDER_MAX + " 정수: " + response);
      }
      double[][] lp = sliderLogProbs(w, s.a(), s.b());
      for (int i = 0; i < w.length; i++) {
        out[i] = lp[i][r.value() + SLIDER_MAX];
      }
      return out;
    }
    if (!(response instanceof Response.Choice c)) {
      throw new IllegalArgumentException("A/B 질문 응답은 'A' 또는 'B': " + response);
    }
    double[][] ab = logProbAB(spec, w);
    return c.a() ? ab[0] : ab[1];
  }

  // P(s | w) ∝ exp(−(log(w_a/w_b) − c·s)² / 2σ²), 11개 응답에 대해 합이 1
  private static double[][] sliderLogProbs(double[][] w, int a, int b) {
    int r = 2 * SLIDER_MAX + 1;
    double[][] out = new double[w.length][r];
    for (int i = 0; i < w.length; i++) {
      double lr = Math.log(Math.max(w[i][a], EPS) / Math.max(w[i][b], EPS));
      double[] logits = new double[r];
      for (int j = 0; j < r; j++) {
        double d = lr - SLIDER_C * (j - SLIDER_MAX);
        logits[j] = -(d * d) / (2 * SLIDER_SIGMA * SLIDER_SIGMA);
      }
      double lse = MathUtil.logSumExp(logits);
      for (int j = 0; j < r; j++) {
        out[i][j] = logits[j] - lse;
      }
    }
    return out;
  }

  /** {log P(A), log P(B)} 각 길이 N. */
  private static double[][] logProbAB(QuestionSpec spec, double[][] w) {
    int n = w.length;
    double[] logA = new double[n];
    double[] logB = new double[n];
    if (spec instanceof CriteriaABSpec s) {
      // Bradley-Terry: P(A) = w_a^λ / (w_a^λ + w_b^λ)
      for (int i = 0; i < n; i++) {
        double la = BT_LAMBDA * Math.log(Math.max(w[i][s.a()], EPS));
        double lb = BT_LAMBDA * Math.log(Math.max(w[i][s.b()], EPS));
        double norm = MathUtil.logAddExp(la, lb);
        logA[i] = la - norm;
        logB[i] = lb - norm;
      }
    } else {
      // 베이지안 로지스틱: P(A) = σ(β · Σ_k w_k (v_Ak − v_Bk))
      double[] delta = ((ScenarioSpec) spec).delta();
      for (int i = 0; i < n; i++) {
        double dot = 0;
        for (int k = 0; k < delta.length; k++) {
          dot += w[i][k] * delta[k];
        }
        double z = SCENARIO_BETA * dot;
        logA[i] = -MathUtil.logAddExp(0.0, -z);
        logB[i] = -MathUtil.logAddExp(0.0, z);
      }
    }
    return new double[][] {logA, logB};
  }
}

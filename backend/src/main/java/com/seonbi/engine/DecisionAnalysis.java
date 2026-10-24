package com.seonbi.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;

/** 점수·순위·안정성·전환 조건·민감도 (개발기획서 5.7~5.9). */
public final class DecisionAnalysis {

  public static final double STABILITY_HIGH = 0.80;
  public static final double STABILITY_MEDIUM = 0.55;
  public static final double PERTURB_SIZE = 0.10; // 보조 지표: 각 기준 ±10%p
  public static final int PERTURB_TRIALS = 500;
  public static final int SENSITIVITY_POINTS = 21;
  private static final double CHECK_EPS = 1e-3; // 전환점 양옆에서 실제 1위를 확인할 간격

  public enum Grade {
    HIGH,
    MEDIUM,
    LOW
  }

  public enum Direction {
    INCREASE,
    DECREASE
  }

  /** top: 사후 평균 가중치 기준 1위 선택지 인덱스, pBest: 안정성 점수, perturbKeep: 보조 지표(툴팁용). */
  public record Stability(int top, double pBest, Grade grade, double perturbKeep) {}

  /** threshold가 null이면 어떻게 바꿔도 1위 유지. */
  public record ChangeCondition(
      int criterion, double current, Double threshold, Direction direction, Integer newTop) {}

  /** 다른 선택지의 값 하나가 이만큼 좋아지면 그 선택지가 1위가 된다 (5.8 값 전환 조건, P1). */
  public record ValueCondition(
      int option,
      int criterion,
      double current,
      double threshold,
      Direction direction,
      double neededV) {}

  public record Curve(double[] t, double[][] scores) {}

  private DecisionAnalysis() {}

  /** U_i = Σ_k w_k · v_ik. */
  public static double[] scores(double[] mean, double[][] v) {
    return MathUtil.matVec(v, mean);
  }

  /** 점수 높은 순 선택지 인덱스 (동점이면 입력 순서). */
  public static List<Integer> ranking(double[] mean, double[][] v) {
    double[] s = scores(mean, v);
    List<Integer> idx = new ArrayList<>();
    for (int i = 0; i < s.length; i++) {
      idx.add(i);
    }
    idx.sort((x, y) -> s[x] > s[y] ? -1 : s[x] < s[y] ? 1 : 0); // 안정 정렬
    return idx;
  }

  public static Stability stability(Posterior post, double[][] v, long seed) {
    double[] mean = post.mean();
    int top = ranking(mean, v).get(0);
    double pBest = Posterior.pBest(post.p(), Posterior.bestShares(post.w(), v))[top];
    Grade grade =
        pBest >= STABILITY_HIGH ? Grade.HIGH : pBest >= STABILITY_MEDIUM ? Grade.MEDIUM : Grade.LOW;
    RandomGenerator rng = new Well19937c(seed);
    int k = mean.length;
    int keep = 0;
    for (int t = 0; t < PERTURB_TRIALS; t++) {
      double[] w = new double[k];
      double sum = 0;
      for (int j = 0; j < k; j++) {
        double u = -PERTURB_SIZE + 2 * PERTURB_SIZE * rng.nextDouble();
        w[j] = Math.max(mean[j] + u, 1e-9);
        sum += w[j];
      }
      for (int j = 0; j < k; j++) {
        w[j] /= sum;
      }
      if (argmax(MathUtil.matVec(v, w)) == top) {
        keep++;
      }
    }
    return new Stability(top, pBest, grade, (double) keep / PERTURB_TRIALS);
  }

  /**
   * 기준마다 1위가 바뀌는 가장 가까운 가중치 전환점 1개. 기준 k의 가중치를 t로 바꾸고 나머지는 원래 비율로 나누면 점수가 t의 1차식이 되므로 t* = (R_B −
   * R_A) / ((v_Ak − R_A) − (v_Bk − R_B)) 로 구한 뒤, 그 지점에서 B가 전체 1위인지 확인한다.
   */
  public static List<ChangeCondition> changeConditions(double[] mean, double[][] v) {
    int top = ranking(mean, v).get(0); // 지금 1위
    double[] u = MathUtil.matVec(v, mean);
    List<ChangeCondition> out = new ArrayList<>();
    for (int k = 0; k < mean.length; k++) {
      double wk = mean[k];
      double[] bestT = new double[2]; // [INCREASE, DECREASE]
      int[] bestJ = {-1, -1};
      if (wk < 1.0) {
        double[] r = new double[v.length];
        for (int i = 0; i < v.length; i++) {
          r[i] = (u[i] - wk * v[i][k]) / (1 - wk);
        }
        for (int j = 0; j < v.length; j++) {
          if (j == top) {
            continue;
          }
          double den = (v[top][k] - r[top]) - (v[j][k] - r[j]);
          if (Math.abs(den) < 1e-12) {
            continue;
          }
          double t = (r[j] - r[top]) / den;
          if (!(0 < t && t < 1) || Math.abs(t - wk) < 1e-12) {
            continue;
          }
          int side = t > wk ? 0 : 1; // 0 = 가중치를 올려야 뒤집힘, 1 = 내려야 뒤집힘
          double probe = side == 0 ? t + CHECK_EPS : t - CHECK_EPS;
          if (topAt(mean, v, k, Math.min(Math.max(probe, 0.0), 1.0)) != j) {
            continue; // 다른 선택지가 먼저 1위가 됨 → 그 선택지의 전환점이 더 가깝다
          }
          if (bestJ[side] < 0 || Math.abs(t - wk) < Math.abs(bestT[side] - wk)) {
            bestT[side] = t;
            bestJ[side] = j;
          }
        }
      }
      // 어느 쪽으로도 뒤집히지 않으면 "어떻게 바꿔도 1위 유지"로 기록한다
      if (bestJ[0] < 0 && bestJ[1] < 0) {
        out.add(new ChangeCondition(k, wk, null, null, null));
        continue;
      }
      int side;
      if (bestJ[0] < 0) {
        side = 1;
      } else if (bestJ[1] < 0) {
        side = 0;
      } else {
        side = Math.abs(bestT[1] - wk) < Math.abs(bestT[0] - wk) ? 1 : 0; // 거리가 같으면 INCREASE
      }
      out.add(
          new ChangeCondition(
              k,
              wk,
              bestT[side],
              side == 0 ? Direction.INCREASE : Direction.DECREASE,
              bestJ[side]));
    }
    return out;
  }

  /** 기준 k의 가중치를 0~1로 바꿀 때 선택지별 점수. 곡선의 교차점이 전환점이다. */
  public static Curve sensitivity(double[] mean, double[][] v, int k) {
    double[] t = new double[SENSITIVITY_POINTS];
    double[][] s = new double[SENSITIVITY_POINTS][];
    for (int i = 0; i < SENSITIVITY_POINTS; i++) {
      t[i] = (double) i / (SENSITIVITY_POINTS - 1);
      s[i] = MathUtil.matVec(v, shift(mean, k, t[i]));
    }
    return new Curve(t, s);
  }

  /** w'_k = t, w'_j = w_j · (1 − t) / (1 − w_k). */
  private static double[] shift(double[] mean, int k, double t) {
    double rest = 1 - mean[k];
    double[] w = new double[mean.length];
    for (int j = 0; j < w.length; j++) {
      w[j] = rest > 0 ? mean[j] * (1 - t) / rest : (1 - t) / Math.max(mean.length - 1, 1);
    }
    w[k] = t;
    return w;
  }

  private static int topAt(double[] mean, double[][] v, int k, double t) {
    return argmax(MathUtil.matVec(v, shift(mean, k, t)));
  }

  private static int argmax(double[] x) {
    int best = 0;
    for (int i = 1; i < x.length; i++) {
      if (x[i] > x[best]) {
        best = i;
      }
    }
    return best;
  }

  /**
   * 1위가 아닌 선택지의 값 하나를 올렸을 때 그 선택지가 1위가 되는 값 전환 조건. U_j(v') = U_j + w_k · (v' − v_jk)가 1위 점수 U_top과
   * 같아지는 v'를 구한다. v'가 1을 넘으면 만들지 않는다. 숫자형·단계형(score)만 다루고 순위 입력과 예/아니요는 제외한다(5.8). raw는 (선택지 수, 기준
   * 수) 크기의 원래 입력값이다. 가까운 순(정규화 변화량이 작은 순)으로 돌려준다.
   */
  public static List<ValueCondition> valueConditions(
      double[] mean,
      double[][] v,
      List<Criterion> criteria,
      Map<String, Scale> scales,
      double[][] raw) {
    double[] u = MathUtil.matVec(v, mean);
    int top = ranking(mean, v).get(0);
    record Scored(double key, ValueCondition cond) {}
    List<Scored> out = new ArrayList<>();
    for (int k = 0; k < criteria.size(); k++) {
      Criterion c = criteria.get(k);
      if (c.type() == Criterion.Type.BOOLEAN || c.isRank() || mean[k] <= 1e-12) {
        continue;
      }
      for (int j = 0; j < v.length; j++) {
        if (j == top) {
          continue;
        }
        double need = v[j][k] + (u[top] - u[j]) / mean[k];
        if (need > 1 + 1e-9) {
          continue;
        }
        double cur = raw[j][k];
        ValueCondition cond;
        if (c.type() == Criterion.Type.RATING) {
          int levelCount = c.levels() != null ? c.levels() : Normalizer.DEFAULT_LEVELS;
          double target = Math.min(levelCount, Math.ceil(1 + need * (levelCount - 1) - 1e-9));
          if (target <= cur) {
            continue;
          }
          cond = new ValueCondition(j, k, cur, target, Direction.INCREASE, need);
        } else {
          Scale scale = scales.get(c.id());
          if (scale == null || scale.span() == 0) {
            continue;
          }
          double step = QuestionSelector.niceStep(scale.span());
          double thr;
          Direction direction;
          if (c.direction() == Criterion.Direction.LOWER_BETTER) {
            double exact = scale.max() - need * scale.span();
            thr = Math.max(scale.min(), Math.floor(exact / step + 1e-9) * step); // 내림해야 충분하다
            direction = Direction.DECREASE;
          } else {
            double exact = scale.min() + need * scale.span();
            thr = Math.min(scale.max(), Math.ceil(exact / step - 1e-9) * step);
            direction = Direction.INCREASE;
          }
          cond = new ValueCondition(j, k, cur, QuestionSelector.tidy(thr), direction, need);
        }
        out.add(new Scored(need - v[j][k], cond));
      }
    }
    out.sort((x, y) -> Double.compare(x.key(), y.key()));
    return out.stream().map(Scored::cond).toList();
  }
}

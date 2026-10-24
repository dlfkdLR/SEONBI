package com.seonbi.engine;

/** 엔진 내부 수치 보조 함수. */
final class MathUtil {

  private MathUtil() {}

  /** log(exp(a) + exp(b)) — numpy.logaddexp와 같다. */
  static double logAddExp(double a, double b) {
    double m = Math.max(a, b);
    if (Double.isInfinite(m)) {
      return m;
    }
    return m + Math.log(Math.exp(a - m) + Math.exp(b - m));
  }

  static double logSumExp(double[] x) {
    double m = Double.NEGATIVE_INFINITY;
    for (double v : x) {
      m = Math.max(m, v);
    }
    double s = 0;
    for (double v : x) {
      s += Math.exp(v - m);
    }
    return m + Math.log(s);
  }

  /** numpy.searchsorted(a, v, side="left"): v 이상인 첫 위치. 없으면 a.length. */
  static int searchSortedLeft(double[] a, double v) {
    int lo = 0;
    int hi = a.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (a[mid] < v) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }

  /** 행렬 곱 V · w (V: 행 × K, w: K). */
  static double[] matVec(double[][] v, double[] w) {
    double[] out = new double[v.length];
    for (int i = 0; i < v.length; i++) {
      double s = 0;
      for (int k = 0; k < w.length; k++) {
        s += v[i][k] * w[k];
      }
      out[i] = s;
    }
    return out;
  }

  /** 가중 평균 p · W. */
  static double[] weightedMean(double[] p, double[][] w, int k) {
    double[] out = new double[k];
    for (int i = 0; i < w.length; i++) {
      for (int j = 0; j < k; j++) {
        out[j] += p[i] * w[i][j];
      }
    }
    return out;
  }
}

package com.seonbi.engine;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;
import org.apache.commons.math3.special.Gamma;

/**
 * 입자 샘플링·재가중·재샘플링, 요약 통계 (개발기획서 5.2, 5.4). 같은 seed·같은 답변 목록이면 항상 같은 결과가 나온다. 그래서 입자는 저장하지 않고 매번 다시
 * 계산한다.
 *
 * @param w (N, K) 가중치 후보
 * @param p (N) 정규화된 비중
 */
public record Posterior(double[][] w, double[] p) {

  public static final int N_PARTICLES = 4000;
  public static final double PRIOR_ALPHA = 1.0; // Dirichlet(1, …, 1) — 아무것도 모르는 상태
  public static final double ESS_RATIO = 0.1; // ESS < N × 0.1 이면 재샘플링
  public static final double MH_KAPPA = 50.0; // 제안 분포 Dirichlet(κ·w + 1)의 집중도
  public static final int MH_STEPS = 3;
  public static final double[] INTERVAL = {0.10, 0.90}; // 기준별 신뢰 구간 분위수
  public static final double TIE_TOL = 1e-12;

  /** 요약 통계. intervals는 (K, 2), pBest는 선택지별 1위 확률, entropy는 결정 엔트로피(bit). */
  public record Summary(double[] mean, double[][] intervals, double[] pBest, double entropy) {}

  public double ess() {
    double s = 0;
    for (double v : p) {
      s += v * v;
    }
    return 1.0 / s;
  }

  public double[] mean() {
    return MathUtil.weightedMean(p, w, w[0].length);
  }

  /** 사전분포에서 뽑은 입자. fit의 첫 단계와 같은 난수를 쓴다. */
  public static double[][] priorParticles(long seed, int k, int n) {
    return dirichletPrior(new Well19937c(seed), k, n);
  }

  public static double[][] priorParticles(long seed, int k) {
    return priorParticles(seed, k, N_PARTICLES);
  }

  /** 답변을 seq 순서대로 반영해 사후분포를 만든다. */
  public static Posterior fit(long seed, int k, List<Answer> answers, int n) {
    RandomGenerator rng = new Well19937c(seed);
    // 처음엔 아무것도 모르는 상태라 Dirichlet(1,…,1)에서 입자를 뽑는다
    double[][] w = dirichletPrior(rng, k, n);
    double[] logw = new double[n];
    for (int i = 0; i < answers.size(); i++) {
      // 답변이 하나 들어올 때마다 입자의 로그 비중에 우도를 더한다
      addLogLik(logw, answers.get(i), w);
      double[] p = normalize(logw);
      double s = 0;
      for (double v : p) {
        s += v * v;
      }
      // ESS가 너무 작으면 소수 입자만 살아남은 것이라 다시 뽑고 흩뜨린다
      if (1.0 / s < n * ESS_RATIO) {
        w = resampleMove(w, p, answers.subList(0, i + 1), rng);
        logw = new double[n];
      }
    }
    return new Posterior(w, normalize(logw));
  }

  public static Posterior fit(long seed, int k, List<Answer> answers) {
    return fit(seed, k, answers, N_PARTICLES);
  }

  /** 재샘플링 없이 주어진 입자에 답변 묶음만 반영한 비중. 숨은 선호처럼 답변 일부만 볼 때 쓴다. */
  public static double[] reweight(double[][] w, List<Answer> answers) {
    double[] logw = new double[w.length];
    for (Answer a : answers) {
      addLogLik(logw, a, w);
    }
    return normalize(logw);
  }

  /** 입자마다 1위 선택지 표시 (N, 선택지 수). 동점이면 1을 나눠 준다. */
  public static double[][] bestShares(double[][] w, double[][] v) {
    double[][] out = new double[w.length][v.length];
    for (int i = 0; i < w.length; i++) {
      double[] u = MathUtil.matVec(v, w[i]);
      double max = Double.NEGATIVE_INFINITY;
      for (double x : u) {
        max = Math.max(max, x);
      }
      int cnt = 0;
      for (int j = 0; j < u.length; j++) {
        if (max - TIE_TOL <= u[j]) {
          out[i][j] = 1;
          cnt++;
        }
      }
      for (int j = 0; j < u.length; j++) {
        out[i][j] /= cnt;
      }
    }
    return out;
  }

  /** 선택지별 1위 확률 p · shares. */
  public static double[] pBest(double[] p, double[][] shares) {
    double[] out = new double[shares[0].length];
    for (int i = 0; i < p.length; i++) {
      for (int j = 0; j < out.length; j++) {
        out[j] += p[i] * shares[i][j];
      }
    }
    return out;
  }

  public static double entropyBits(double[] pBest) {
    double s = 0;
    for (double q : pBest) {
      if (q > 0) {
        s += q * (Math.log(q) / Math.log(2));
      }
    }
    return -s;
  }

  public Summary summary(double[][] v) {
    double[] pBest = pBest(p, bestShares(w, v));
    int k = w[0].length;
    double[][] intervals = new double[k][];
    for (int j = 0; j < k; j++) {
      double[] col = new double[w.length];
      for (int i = 0; i < w.length; i++) {
        col[i] = w[i][j];
      }
      intervals[j] = weightedQuantiles(col, p, INTERVAL);
    }
    return new Summary(mean(), intervals, pBest, entropyBits(pBest));
  }

  private static void addLogLik(double[] logw, Answer a, double[][] w) {
    double[] ll = Likelihood.logLikelihood(a.spec(), a.response(), w);
    for (int i = 0; i < logw.length; i++) {
      logw[i] += ll[i];
    }
  }

  private static double[] normalize(double[] logw) {
    double max = Double.NEGATIVE_INFINITY;
    for (double v : logw) {
      max = Math.max(max, v);
    }
    double[] p = new double[logw.length];
    double sum = 0;
    for (int i = 0; i < p.length; i++) {
      p[i] = Math.exp(logw[i] - max);
      sum += p[i];
    }
    for (int i = 0; i < p.length; i++) {
      p[i] /= sum;
    }
    return p;
  }

  private static double[] weightedQuantiles(double[] x, double[] p, double[] qs) {
    Integer[] order = new Integer[x.length];
    for (int i = 0; i < order.length; i++) {
      order[i] = i;
    }
    Arrays.sort(order, Comparator.comparingDouble(i -> x[i]));
    double[] cum = new double[x.length];
    double acc = 0;
    for (int i = 0; i < order.length; i++) {
      acc += p[order[i]];
      cum[i] = acc;
    }
    double[] out = new double[qs.length];
    for (int i = 0; i < qs.length; i++) {
      int idx = Math.min(MathUtil.searchSortedLeft(cum, qs[i]), x.length - 1);
      out[i] = x[order[idx]];
    }
    return out;
  }

  private static double[][] dirichletPrior(RandomGenerator rng, int k, int n) {
    double[][] w = new double[n][k];
    for (int i = 0; i < n; i++) {
      w[i] = dirichlet(rng, filled(k, PRIOR_ALPHA));
    }
    return w;
  }

  private static double[] filled(int k, double v) {
    double[] a = new double[k];
    Arrays.fill(a, v);
    return a;
  }

  private static double[] dirichlet(RandomGenerator rng, double[] alpha) {
    double[] g = new double[alpha.length];
    double sum = 0;
    for (int j = 0; j < alpha.length; j++) {
      g[j] = new GammaDistribution(rng, alpha[j], 1.0).sample();
      sum += g[j];
    }
    for (int j = 0; j < g.length; j++) {
      g[j] /= sum;
    }
    return g;
  }

  /** systematic resampling 후 Metropolis-Hastings로 입자를 흩뜨린다. */
  private static double[][] resampleMove(
      double[][] w0, double[] p, List<Answer> answers, RandomGenerator rng) {
    int n = w0.length;
    int k = w0[0].length;
    double[] cum = new double[n];
    double acc = 0;
    for (int i = 0; i < n; i++) {
      acc += p[i];
      cum[i] = acc;
    }
    double offset = rng.nextDouble();
    double[][] w = new double[n][];
    for (int i = 0; i < n; i++) {
      double position = (offset + i) / n;
      int idx = Math.min(MathUtil.searchSortedLeft(cum, position), n - 1);
      w[i] = w0[idx].clone();
    }
    // 뽑은 입자가 같은 자리에 몰려 있으니 Metropolis-Hastings로 조금씩 움직여 준다
    double[] loglik = totalLogLik(w, answers);
    for (int step = 0; step < MH_STEPS; step++) {
      double[][] alphaFwd = new double[n][k];
      double[][] proposal = new double[n][];
      for (int i = 0; i < n; i++) {
        for (int j = 0; j < k; j++) {
          alphaFwd[i][j] = MH_KAPPA * w[i][j] + 1.0;
        }
        double[] g = new double[k];
        double sum = 0;
        for (int j = 0; j < k; j++) {
          g[j] = Math.max(new GammaDistribution(rng, alphaFwd[i][j], 1.0).sample(), 1e-12);
          sum += g[j];
        }
        for (int j = 0; j < k; j++) {
          g[j] /= sum;
        }
        proposal[i] = g;
      }
      double[] loglikNew = totalLogLik(proposal, answers);
      double[] u = new double[n];
      for (int i = 0; i < n; i++) {
        u[i] = rng.nextDouble();
      }
      for (int i = 0; i < n; i++) {
        double[] alphaBack = new double[k];
        for (int j = 0; j < k; j++) {
          alphaBack[j] = MH_KAPPA * proposal[i][j] + 1.0;
        }
        // 사전분포는 Dirichlet(1)이라 상수 → 우도 비 × 제안 분포 비대칭 보정
        double logRatio =
            loglikNew[i]
                - loglik[i]
                + dirichletLogPdf(w[i], alphaBack)
                - dirichletLogPdf(proposal[i], alphaFwd[i]);
        if (Math.log(u[i]) < logRatio) {
          w[i] = proposal[i];
          loglik[i] = loglikNew[i];
        }
      }
    }
    return w;
  }

  private static double[] totalLogLik(double[][] w, List<Answer> answers) {
    double[] total = new double[w.length];
    for (Answer a : answers) {
      addLogLik(total, a, w);
    }
    return total;
  }

  private static double dirichletLogPdf(double[] x, double[] alpha) {
    double sumAlpha = 0;
    double sumLogGamma = 0;
    double dot = 0;
    for (int j = 0; j < alpha.length; j++) {
      sumAlpha += alpha[j];
      sumLogGamma += Gamma.logGamma(alpha[j]);
      dot += (alpha[j] - 1) * Math.log(x[j]);
    }
    return Gamma.logGamma(sumAlpha) - sumLogGamma + dot;
  }
}

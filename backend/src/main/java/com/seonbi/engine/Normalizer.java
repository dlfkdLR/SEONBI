package com.seonbi.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** 척도 고정, 값 정규화, 필수 조건 필터 (개발기획서 5.1, 3.4, 3.5). 불리언 값은 1.0/0.0으로 넘긴다. */
public final class Normalizer {

    public static final int DEFAULT_LEVELS = 5;

    /** normalize 결과: 정규화 행렬과 제외된 선택지(→ 미충족 조건 ID). */
    public record Result(NormalizedMatrix matrix, Map<String, List<String>> excluded) {}

    private Normalizer() {}

    /** 필수 조건을 만족하지 못하는 선택지 → 미충족 조건 ID 목록. 삭제하지 않고 제외만 한다. */
    public static Map<String, List<String>> excludedOptions(
            List<String> optionIds, Map<ValueKey, Double> values, Collection<Condition> conditions) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String oid : optionIds) {
            List<String> failed = new ArrayList<>();
            for (Condition c : conditions) {
                if (!satisfies(oid, values, c)) {
                    failed.add(c.id());
                }
            }
            if (!failed.isEmpty()) {
                result.put(oid, failed);
            }
        }
        return result;
    }

    /** 질문 시작 시 숫자형 기준의 척도를 정한다. 지정 척도가 있으면 그대로, 없으면 제외되지 않은 선택지 값의 최솟값·최댓값. */
    public static Map<String, Scale> freezeScales(
            List<Criterion> criteria, Map<ValueKey, Double> values, Collection<String> excluded) {
        Map<String, Scale> scales = new LinkedHashMap<>();
        for (Criterion c : criteria) {
            if (c.type() != Criterion.Type.NUMERIC) {
                continue;
            }
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            boolean any = false;
            for (Map.Entry<ValueKey, Double> e : values.entrySet()) {
                if (e.getKey().criterionId().equals(c.id()) && !excluded.contains(e.getKey().optionId())) {
                    min = Math.min(min, e.getValue());
                    max = Math.max(max, e.getValue());
                    any = true;
                }
            }
            double lo = c.scaleMin() != null ? c.scaleMin() : (any ? min : 0.0);
            double hi = c.scaleMax() != null ? c.scaleMax() : (any ? max : 0.0);
            scales.put(c.id(), new Scale(Math.min(lo, hi), Math.max(lo, hi)));
        }
        return scales;
    }

    /** 선택지 × 기준 값을 0~1(클수록 좋음)로 바꾸고, 필수 조건 미충족 선택지를 뺀다. 값이 모두 같은 기준은 전부 0.5다. */
    public static Result normalize(
            Map<ValueKey, Double> values,
            List<Criterion> criteria,
            Map<String, Scale> scales,
            List<String> optionIds,
            Collection<Condition> conditions) {
        Map<String, List<String>> excluded = excludedOptions(optionIds, values, conditions);
        List<String> kept = optionIds.stream().filter(o -> !excluded.containsKey(o)).toList();
        double[][] v = new double[kept.size()][criteria.size()];
        for (double[] row : v) {
            Arrays.fill(row, 0.5);
        }
        for (int k = 0; k < criteria.size(); k++) {
            Criterion c = criteria.get(k);
            double[] raw = new double[kept.size()];
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < kept.size(); i++) {
                Double x = values.get(new ValueKey(kept.get(i), c.id()));
                if (x == null) {
                    missing.add(kept.get(i));
                } else {
                    raw[i] = x;
                }
            }
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("기준 " + c.id() + "에 값이 없는 선택지: " + missing);
            }
            double[] col = normalizeColumn(c, raw, scales.get(c.id()));
            for (int i = 0; i < kept.size(); i++) {
                v[i][k] = col[i];
            }
        }
        List<String> criterionIds = criteria.stream().map(Criterion::id).toList();
        return new Result(new NormalizedMatrix(kept, criterionIds, v), excluded);
    }

    private static double[] normalizeColumn(Criterion c, double[] x, Scale scale) {
        double[] out = new double[x.length];
        if (c.type() == Criterion.Type.BOOLEAN) {
            for (int i = 0; i < x.length; i++) {
                out[i] = x[i] > 0 ? 1.0 : 0.0;
            }
            return out;
        }
        if (c.isRank()) {
            // 남은 선택지 기준으로 그룹 번호를 1..G로 다시 매긴 뒤 v = (G − g) / (G − 1)
            TreeSet<Double> groups = new TreeSet<>();
            for (double v : x) {
                groups.add(v);
            }
            List<Double> sorted = new ArrayList<>(groups);
            int g = sorted.size();
            for (int i = 0; i < x.length; i++) {
                out[i] = g == 1 ? 0.5 : (double) (g - (sorted.indexOf(x[i]) + 1)) / (g - 1);
            }
            return out;
        }
        if (c.type() == Criterion.Type.RATING) {
            int levels = c.levels() != null ? c.levels() : DEFAULT_LEVELS;
            for (int i = 0; i < x.length; i++) {
                out[i] = clip((x[i] - 1) / (levels - 1));
            }
            return out;
        }
        if (scale == null) {
            throw new IllegalArgumentException("숫자형 기준 " + c.id() + "의 척도가 고정되지 않았다");
        }
        for (int i = 0; i < x.length; i++) {
            if (scale.span() == 0) {
                out[i] = 0.5;
                continue;
            }
            double v = (x[i] - scale.min()) / scale.span();
            if (c.direction() == Criterion.Direction.LOWER_BETTER) {
                v = 1 - v;
            }
            out[i] = clip(v);
        }
        return out;
    }

    private static double clip(double v) {
        return Math.min(1.0, Math.max(0.0, v));
    }

    private static boolean satisfies(String optionId, Map<ValueKey, Double> values, Condition cond) {
        if (cond.kind() == Condition.Kind.REQUIRED_TAG) {
            return cond.satisfiedBy().contains(optionId);
        }
        if (cond.criterionId() == null || cond.operator() == null || cond.value() == null) {
            return true;
        }
        Double x = values.get(new ValueKey(optionId, cond.criterionId()));
        if (x == null) {
            return true; // 값이 없으면 판단 보류 (값 입력 완료 전)
        }
        return switch (cond.operator()) {
            case LE -> x <= cond.value();
            case GE -> x >= cond.value();
            case EQ -> x.doubleValue() == cond.value();
        };
    }
}

package com.seonbi.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NormalizerGoldenTest {

    @Test
    void 골든_파일과_정규화_결과가_같다() {
        for (JsonNode c : Golden.load("normalize")) {
            String name = c.get("name").asText();
            List<Criterion> criteria = Golden.criteria(c.get("criteria"));
            List<String> options = Golden.strings(c.get("options"));
            Map<ValueKey, Double> values = new LinkedHashMap<>();
            c.get("values").forEach(v -> values.put(new ValueKey(v.get(0).asText(), v.get(1).asText()), v.get(2).asDouble()));
            List<Condition> conds = new ArrayList<>();
            c.get("conditions").forEach(x -> conds.add(new Condition(
                    x.get("id").asText(),
                    Condition.Kind.valueOf(x.get("kind").asText().toUpperCase()),
                    x.get("criterionId").isNull() ? null : x.get("criterionId").asText(),
                    x.get("operator").isNull() ? null : switch (x.get("operator").asText()) {
                        case "<=" -> Condition.Operator.LE;
                        case ">=" -> Condition.Operator.GE;
                        default -> Condition.Operator.EQ;
                    },
                    x.get("value").isNull() ? null : x.get("value").asDouble(),
                    Set.copyOf(Golden.strings(x.get("satisfiedBy"))))));

            Set<String> excludedIds = Normalizer.excludedOptions(options, values, conds).keySet();
            Map<String, Scale> scales = Normalizer.freezeScales(criteria, values, excludedIds);
            Map<String, Scale> expectedScales = Golden.scales(c.get("scales"));
            assertThat(scales.keySet()).as(name).isEqualTo(expectedScales.keySet());
            expectedScales.forEach((id, s) -> {
                assertThat(scales.get(id).min()).as(name + " " + id).isCloseTo(s.min(), offset(Golden.TOL));
                assertThat(scales.get(id).max()).as(name + " " + id).isCloseTo(s.max(), offset(Golden.TOL));
            });

            Normalizer.Result r = Normalizer.normalize(values, criteria, scales, options, conds);
            assertThat(r.matrix().optionIds()).as(name).isEqualTo(Golden.strings(c.get("keptOptions")));
            double[][] expected = Golden.mat(c.get("matrix"));
            assertThat(r.matrix().values()).as(name).hasDimensions(expected.length, criteria.size());
            for (int i = 0; i < expected.length; i++) {
                for (int k = 0; k < criteria.size(); k++) {
                    assertThat(r.matrix().values()[i][k]).as(name + "[" + i + "][" + k + "]")
                            .isCloseTo(expected[i][k], offset(Golden.TOL));
                }
            }
            assertThat(r.excluded().keySet()).as(name).hasSize(c.get("excluded").size());
            c.get("excluded").fields().forEachRemaining(e ->
                    assertThat(r.excluded().get(e.getKey())).as(name).isEqualTo(Golden.strings(e.getValue())));
        }
    }

    @Test
    void 값이_없는_선택지가_있으면_오류다() {
        Criterion price = new Criterion("price", Criterion.Type.NUMERIC, Criterion.Direction.LOWER_BETTER, null);
        Map<ValueKey, Double> values = Map.of(new ValueKey("a", "price"), 1.0);
        Map<String, Scale> scales = Map.of("price", new Scale(0, 1));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> Normalizer.normalize(values, List.of(price), scales, List.of("a", "b"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price");
    }
}

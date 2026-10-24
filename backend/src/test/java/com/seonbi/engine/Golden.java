package com.seonbi.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seonbi.engine.QuestionSpec.CriteriaABSpec;
import com.seonbi.engine.QuestionSpec.ScenarioSpec;
import com.seonbi.engine.QuestionSpec.SliderSpec;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** engine-reference/golden/generate.py가 만든 JSON을 읽는 테스트 보조 도구. */
public final class Golden {

  /** 결정적 계산 비교 허용 오차 (개발기획서 14). */
  public static final double TOL = 1e-9;

  /** 난수가 들어가는 값 비교 허용 오차. */
  public static final double RANDOM_TOL = 0.03;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private Golden() {}

  public static JsonNode load(String name) {
    try (InputStream in = Golden.class.getResourceAsStream("/golden/" + name + ".json")) {
      if (in == null) {
        throw new IllegalStateException("골든 파일 없음: " + name);
      }
      return MAPPER.readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public static double[] vec(JsonNode n) {
    double[] out = new double[n.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = n.get(i).asDouble();
    }
    return out;
  }

  public static double[][] mat(JsonNode n) {
    double[][] out = new double[n.size()][];
    for (int i = 0; i < out.length; i++) {
      out[i] = vec(n.get(i));
    }
    return out;
  }

  public static List<String> strings(JsonNode n) {
    List<String> out = new ArrayList<>();
    n.forEach(x -> out.add(x.asText()));
    return out;
  }

  public static QuestionSpec spec(JsonNode n) {
    int a = n.get("a").asInt();
    int b = n.get("b").asInt();
    return switch (n.get("type").asText()) {
      case "slider" -> new SliderSpec(a, b);
      case "ab" -> new CriteriaABSpec(a, b);
      default ->
          new ScenarioSpec(
              a, b, vec(n.get("delta")), n.get("rawA").asDouble(), n.get("rawB").asDouble());
    };
  }

  public static Response response(JsonNode n) {
    if (n == null || n.isNull()) {
      return Response.DONT_KNOW;
    }
    if (n.isNumber()) {
      return new Response.Slider(n.asInt());
    }
    return "A".equals(n.asText()) ? Response.Choice.A : Response.Choice.B;
  }

  public static List<Answer> answers(JsonNode n) {
    List<Answer> out = new ArrayList<>();
    n.forEach(x -> out.add(new Answer(spec(x.get("spec")), response(x.get("response")))));
    return out;
  }

  public static Criterion criterion(JsonNode n) {
    return new Criterion(
        n.get("id").asText(),
        Criterion.Type.valueOf(n.get("type").asText().toUpperCase()),
        Criterion.Direction.valueOf(n.get("direction").asText().toUpperCase()),
        n.get("levels").isNull() ? null : n.get("levels").asInt(),
        Criterion.InputMode.valueOf(n.get("inputMode").asText().toUpperCase()),
        n.get("scaleMin").isNull() ? null : n.get("scaleMin").asDouble(),
        n.get("scaleMax").isNull() ? null : n.get("scaleMax").asDouble());
  }

  public static List<Criterion> criteria(JsonNode n) {
    List<Criterion> out = new ArrayList<>();
    n.forEach(x -> out.add(criterion(x)));
    return out;
  }

  public static Map<String, Scale> scales(JsonNode n) {
    Map<String, Scale> out = new LinkedHashMap<>();
    n.properties()
        .forEach(
            e ->
                out.put(
                    e.getKey(),
                    new Scale(e.getValue().get(0).asDouble(), e.getValue().get(1).asDouble())));
    return out;
  }

  public static Integer optInt(JsonNode n) {
    return n == null || n.isNull() ? null : n.asInt();
  }
}

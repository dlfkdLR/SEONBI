package com.seonbi.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;

/**
 * 출력 record에서 JSON 스키마를 만든다 (개발기획서 6.4, victools jsonschema-generator). 제공자마다 요구가 달라서 두 가지를 둔다.
 *
 * <ul>
 *   <li>{@link #generic}: Gemini·Anthropic용. 모든 하위 스키마를 펼쳐 $ref가 없다.
 *   <li>{@link #strict}: OpenAI json_schema(strict)용. 모든 필드가 required이고 없는 값은 null 허용,
 *       additionalProperties=false.
 * </ul>
 */
final class JsonSchemas {

  private static final SchemaGenerator GENERIC = new SchemaGenerator(base().build());

  private static final SchemaGenerator STRICT =
      new SchemaGenerator(
          base()
              .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
              .with(Option.NULLABLE_FIELDS_BY_DEFAULT)
              .build());

  private JsonSchemas() {}

  private static SchemaGeneratorConfigBuilder base() {
    // $ref 없이 한 덩어리로 펼친다 (제공자가 $defs를 못 읽는 경우를 피한다)
    SchemaGeneratorConfigBuilder builder =
        new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(new JacksonModule())
            .with(Option.INLINE_ALL_SCHEMAS)
            .with(Option.DEFINITIONS_FOR_ALL_OBJECTS);
    return builder;
  }

  static ObjectNode generic(Class<?> schema) {
    return clean(GENERIC.generateSchema(schema));
  }

  static ObjectNode strict(Class<?> schema) {
    ObjectNode node = clean(STRICT.generateSchema(schema));
    requireAll(node);
    return node;
  }

  // 제공자 요청에 필요 없는 메타 키($schema, $defs)를 뺀다
  private static ObjectNode clean(ObjectNode node) {
    node.remove("$schema");
    node.remove("$defs");
    return node;
  }

  // strict에서는 properties에 있는 모든 키가 required여야 한다. 중첩 객체도 같이 맞춘다
  private static void requireAll(JsonNode node) {
    if (node instanceof ObjectNode obj) {
      JsonNode props = obj.get("properties");
      if (props != null && props.isObject()) {
        var required = obj.putArray("required");
        props.fieldNames().forEachRemaining(required::add);
        obj.put("additionalProperties", false);
      }
      obj.elements().forEachRemaining(JsonSchemas::requireAll);
    } else if (node.isArray()) {
      node.elements().forEachRemaining(JsonSchemas::requireAll);
    }
  }
}

package com.seonbi.core;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfiguration {

  private static final String INTRO =
      """
      **선비**는 사용자가 정한 기준과 질문 답변으로 선택지를 비교하고 추천해 주는 의사결정 지원 API입니다.

      ### 쓰는 방법
      - 로그인은 없습니다. 모든 요청에 `X-Client-Id` 헤더(UUID)를 보내야 합니다(헬스 체크 제외). 결정은 모든 방문자가 함께 봅니다.
      - 흐름: 결정 생성(9) → 선택지(14)·평가 기준(19) → 값(25)·필수 조건(27) → 질문 시작(31) → 답변(37) 반복 → 분석(39) → 확정(47)·내보내기(48)
      - 결정 상태: `DRAFT`(작성 중) → `QUESTIONING`(질문 중) → `ANALYZED`(분석됨) → `FINALIZED`(확정됨)
      - 오류는 모두 `{ "error": { "code", "message", "details" } }` 형식입니다. 번호는 개발기획서 7장의 API 번호입니다.
      - 아래 "Try it out"은 **실제 서버의 데이터를 바꿉니다.**
      """;

  @Bean
  OpenAPI seonbiOpenApi() {
    return new OpenAPI()
        .info(new Info().title("선비 API").version("v1").description(INTRO))
        .servers(
            java.util.List.of(
                new io.swagger.v3.oas.models.servers.Server().url("/").description("지금 접속한 서버")));
  }

  // 컨트롤러의 @Tag가 만든 태그를 화면에 보여 줄 순서대로 정렬한다 (개발기획서 7.2 순서)
  private static final java.util.List<String> TAG_ORDER =
      java.util.List.of("상태 확인", "결정 구성", "자유 입력", "질문·답변", "분석", "결과 확정·내보내기");

  @Bean
  OpenApiCustomizer tagOrder() {
    return api -> {
      if (api.getTags() != null) {
        api.getTags()
            .sort(
                java.util.Comparator.comparingInt(
                    t -> {
                      int i = TAG_ORDER.indexOf(t.getName());
                      return i < 0 ? Integer.MAX_VALUE : i;
                    }));
      }
    };
  }

  private static final java.util.Map<String, String> ERROR_TEXT =
      java.util.Map.ofEntries(
          java.util.Map.entry("400", "요청 형식이나 값이 올바르지 않음 (VALIDATION_ERROR, CLIENT_ID_REQUIRED)"),
          java.util.Map.entry("404", "결정·선택지·기준 등을 찾을 수 없음 (NOT_FOUND)"),
          java.util.Map.entry("405", "이 경로에서 지원하지 않는 HTTP 메서드"),
          java.util.Map.entry("406", "지원하지 않는 응답 형식(Accept)"),
          java.util.Map.entry(
              "409", "상태 충돌·개수 제한 (DECISION_FINALIZED, INVALID_STATE, LIMIT_EXCEEDED 등)"),
          java.util.Map.entry("415", "지원하지 않는 요청 형식(Content-Type)"),
          java.util.Map.entry("422", "지금은 처리할 수 없음 (NOT_READY: 값 미입력 등, IRRELEVANT_INPUT)"),
          java.util.Map.entry("429", "AI 일일 호출 상한 초과 (AI_QUOTA_EXCEEDED)"),
          java.util.Map.entry("500", "예상하지 못한 서버 오류 (INTERNAL_ERROR)"),
          java.util.Map.entry("503", "AI 또는 DB를 지금 쓸 수 없음 (AI_UNAVAILABLE 등)"));

  private static final java.util.Map<String, String> PATH_TEXT =
      java.util.Map.of(
          "decisionId", "결정 ID (UUID)",
          "optionId", "선택지 ID (UUID)",
          "criterionId", "평가 기준 ID (UUID)",
          "answerId", "답변 ID (UUID)",
          "inputId", "자유 입력 ID (UUID)");

  @Bean
  OpenApiCustomizer clientHeader() {
    return api ->
        api.getPaths()
            .forEach(
                (path, item) -> {
                  if (path.startsWith("/api/v1/"))
                    item.readOperations()
                        .forEach(
                            operation -> {
                              if (operation.getParameters() != null)
                                operation.getParameters().removeIf(p -> "cid".equals(p.getName()));
                              var body =
                                  new io.swagger.v3.oas.models.media.ObjectSchema()
                                      .addProperty(
                                          "error",
                                          new io.swagger.v3.oas.models.media.ObjectSchema()
                                              .addProperty("code", new StringSchema())
                                              .addProperty("message", new StringSchema())
                                              .addProperty(
                                                  "details",
                                                  new io.swagger.v3.oas.models.media.ObjectSchema()
                                                      .additionalProperties(true)));
                              for (String status :
                                  java.util.List.of(
                                      "400", "404", "405", "406", "409", "415", "422", "429", "500",
                                      "503")) {
                                operation
                                    .getResponses()
                                    .addApiResponse(
                                        status,
                                        new io.swagger.v3.oas.models.responses.ApiResponse()
                                            .description(ERROR_TEXT.getOrDefault(status, "오류"))
                                            .content(
                                                new io.swagger.v3.oas.models.media.Content()
                                                    .addMediaType(
                                                        "application/json",
                                                        new io.swagger.v3.oas.models.media
                                                                .MediaType()
                                                            .schema(body))));
                              }
                              operation.addParametersItem(
                                  new Parameter()
                                      .name("X-Client-Id")
                                      .in("header")
                                      .required(true)
                                      .description(
                                          "방문자를 구분하는 UUID. 브라우저가 처음 한 번 만들어 계속 보낸다(로그인 없음)."
                                              + " 직접 시험할 때는 아무 UUID나 넣으면 된다.")
                                      .example("3f2b8c1e-5a7d-4e9a-8b1c-2d3e4f5a6b7c")
                                      .schema(new StringSchema().format("uuid")));
                              translate(operation);
                            });
                });
  }

  /** 성공 응답 설명과 경로 매개변수 설명을 한국어로 채운다. */
  private static void translate(io.swagger.v3.oas.models.Operation operation) {
    if (operation.getParameters() != null) {
      for (Parameter parameter : operation.getParameters()) {
        if ("path".equals(parameter.getIn()) && parameter.getDescription() == null) {
          String text = PATH_TEXT.get(parameter.getName());
          if (text != null) {
            parameter.setDescription(text);
          }
        }
      }
    }
    operation
        .getResponses()
        .forEach(
            (status, response) -> {
              String d = response.getDescription();
              if (d == null || d.equals("OK")) {
                response.setDescription("성공");
              } else if (d.equals("Created")) {
                response.setDescription("생성됨");
              } else if (d.equals("No Content")) {
                response.setDescription("성공 (응답 본문 없음)");
              }
            });
  }
}

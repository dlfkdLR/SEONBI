package com.seonbi.core;

import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfiguration {
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
                                                  new io.swagger.v3.oas.models.media
                                                      .ObjectSchema()));
                              for (String status :
                                  java.util.List.of("400", "404", "409", "422", "429", "503")) {
                                operation
                                    .getResponses()
                                    .addApiResponse(
                                        status,
                                        new io.swagger.v3.oas.models.responses.ApiResponse()
                                            .description("공통 오류 형식")
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
                                      .schema(new StringSchema().format("uuid")));
                            });
                });
  }
}

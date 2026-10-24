package com.seonbi;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seonbi.core.ApiErrors;
import com.seonbi.core.ClientId;
import com.seonbi.web.InputController;
import com.seonbi.web.ResultController;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class CControllerApiTest {
  private static MockMvc mvc;
  private final String clientId = UUID.randomUUID().toString();
  private final String decisionId = UUID.randomUUID().toString();

  @BeforeAll
  static void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(new InputController(), new ResultController())
            .setControllerAdvice(new ApiErrors())
            .setCustomArgumentResolvers(
                new HandlerMethodArgumentResolver() {
                  @Override
                  public boolean supportsParameter(MethodParameter parameter) {
                    return parameter.hasParameterAnnotation(ClientId.class);
                  }

                  @Override
                  public Object resolveArgument(
                      MethodParameter parameter,
                      ModelAndViewContainer container,
                      NativeWebRequest request,
                      WebDataBinderFactory binderFactory) {
                    return UUID.fromString(request.getHeader("X-Client-Id"));
                  }
                })
            .build();
  }

  @Test
  void validInputReturnsExplicitStubError() throws Exception {
    mvc.perform(
            post("/api/v1/decisions/{decisionId}/inputs", decisionId)
                .header("X-Client-Id", clientId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"context":"reason","text":"가격이 중요해요"}
                    """))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"))
        .andExpect(jsonPath("$.error.details.stub").value(true));
  }

  @Test
  void invalidInputIsRejectedBeforeStubResponse() throws Exception {
    mvc.perform(
            post("/api/v1/decisions/{decisionId}/inputs", decisionId)
                .header("X-Client-Id", clientId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"context":"unknown","text":"가격이 중요해요"}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void confirmAllowsMissingConfidenceButDoesNotClaimItPersisted() throws Exception {
    mvc.perform(
            post(
                    "/api/v1/decisions/{decisionId}/inputs/{inputId}/confirm",
                    decisionId,
                    UUID.randomUUID())
                .header("X-Client-Id", clientId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"items":[{"type":"criterion","name":"가격","criterionType":"rating","levels":["낮음","보통","높음"]}]}
                    """))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"));
  }

  @Test
  void finalChoiceDoesNotClaimItFinalized() throws Exception {
    mvc.perform(
            post("/api/v1/decisions/{decisionId}/final-choice", decisionId)
                .header("X-Client-Id", clientId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"optionId":"%s"}
                    """
                        .formatted(UUID.randomUUID())))
        .andExpect(status().isNotImplemented())
        .andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"));
  }
}

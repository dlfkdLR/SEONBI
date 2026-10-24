package com.seonbi.web;

import com.seonbi.core.ClientId;
import com.seonbi.core.FlowError;
import com.seonbi.dto.CRequests;
import com.seonbi.dto.CResponses;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/decisions/{decisionId}")
public class ResultController {
  @PostMapping("/final-choice")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "finalizeChoice", summary = "최종 선택 확정")
  public CResponses.FinalChoice finalizeChoice(
      @ClientId UUID clientId,
      @PathVariable UUID decisionId,
      @Valid @RequestBody CRequests.FinalChoice request) {
    throw stub("47");
  }

  @GetMapping(value = "/export", produces = "text/markdown")
  @Operation(operationId = "exportDecision", summary = "결정 분석 결과를 Markdown 파일로 내보내기")
  public ResponseEntity<String> export(
      @ClientId UUID clientId,
      @PathVariable UUID decisionId,
      @RequestParam(defaultValue = "md") String format) {
    if (!"md".equals(format)) {
      throw new com.seonbi.core.FlowError(
          HttpStatus.BAD_REQUEST,
          "VALIDATION_ERROR",
          "Markdown(md) 형식만 지원합니다.",
          java.util.Map.of());
    }
    throw stub("48");
  }

  private FlowError stub(String apiNumber) {
    return new FlowError(
        HttpStatus.NOT_IMPLEMENTED,
        "NOT_IMPLEMENTED",
        "API " + apiNumber + "은(는) A/B 서비스 및 DB 연동 전인 스텁입니다.",
        java.util.Map.of("apiNumber", apiNumber, "stub", true));
  }
}

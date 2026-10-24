package com.seonbi.web;

import com.seonbi.core.ClientId;
import com.seonbi.core.FlowError;
import com.seonbi.dto.CRequests;
import com.seonbi.dto.CResponses;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/decisions/{decisionId}/inputs")
public class InputController {
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "extractInput", summary = "자유 입력에서 조건과 선호 추출")
  public CResponses.ExtractInput extract(
      @ClientId UUID clientId,
      @PathVariable UUID decisionId,
      @Valid @RequestBody CRequests.ExtractInput request) {
    throw stub("28");
  }

  @PostMapping("/{inputId}/confirm")
  @Operation(operationId = "confirmInput", summary = "확인한 자유 입력을 결정에 반영")
  public CResponses.ConfirmInput confirm(
      @ClientId UUID clientId,
      @PathVariable UUID decisionId,
      @PathVariable UUID inputId,
      @Valid @RequestBody CRequests.ConfirmInput request) {
    throw stub("29");
  }

  @PostMapping("/{inputId}/reject")
  @Operation(operationId = "rejectInput", summary = "자유 입력 추출 결과 거절")
  public CResponses.RejectInput reject(
      @ClientId UUID clientId, @PathVariable UUID decisionId, @PathVariable UUID inputId) {
    throw stub("30");
  }

  private FlowError stub(String apiNumber) {
    return new FlowError(
        HttpStatus.NOT_IMPLEMENTED,
        "NOT_IMPLEMENTED",
        "API " + apiNumber + "은(는) A/B 서비스 및 DB 연동 전인 스텁입니다.",
        Map.of("apiNumber", apiNumber, "stub", true));
  }
}

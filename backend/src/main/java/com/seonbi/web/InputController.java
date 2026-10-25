package com.seonbi.web;

import com.seonbi.core.ClientId;
import com.seonbi.dto.CRequests;
import com.seonbi.dto.CResponses;
import com.seonbi.service.InputService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 자유 입력 28~30번. 처리는 {@link InputService}가 한다. */
@RestController
@RequestMapping("/api/v1/decisions/{decisionId}/inputs")
public class InputController {

  private final InputService inputs;

  public InputController(InputService inputs) {
    this.inputs = inputs;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "extractInput", summary = "자유 입력에서 조건과 선호 추출")
  public CResponses.ExtractInput extract(
      @ClientId UUID clientId,
      @PathVariable UUID decisionId,
      @Valid @RequestBody CRequests.ExtractInput request) {
    return inputs.extract(decisionId, clientId, request);
  }

  @PostMapping("/{inputId}/confirm")
  @Operation(operationId = "confirmInput", summary = "확인한 자유 입력을 결정에 반영")
  public CResponses.ConfirmInput confirm(
      @ClientId UUID clientId,
      @PathVariable UUID decisionId,
      @PathVariable UUID inputId,
      @Valid @RequestBody CRequests.ConfirmInput request) {
    return inputs.confirm(decisionId, clientId, inputId, request);
  }

  @PostMapping("/{inputId}/reject")
  @Operation(operationId = "rejectInput", summary = "자유 입력 추출 결과 거절")
  public CResponses.RejectInput reject(
      @ClientId UUID clientId, @PathVariable UUID decisionId, @PathVariable UUID inputId) {
    return inputs.reject(decisionId, clientId, inputId);
  }
}

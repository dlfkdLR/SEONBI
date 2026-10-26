package com.seonbi.web;

import com.seonbi.core.ClientId;
import com.seonbi.dto.CRequests;
import com.seonbi.dto.CResponses;
import com.seonbi.service.InputService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 자유 입력 28~30번. 처리는 {@link InputService}가 한다. */
@Tag(name = "자유 입력", description = "문장으로 쓴 조건·선호를 AI가 뽑아 주고, 확인해서 결정에 반영한다 (28~30번).")
@RestController
@RequestMapping("/api/v1/decisions/{decisionId}/inputs")
public class InputController {

  private final InputService inputs;

  public InputController(InputService inputs) {
    this.inputs = inputs;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "extractInput",
      summary = "28. 자유 입력에서 조건과 선호 추출",
      description =
          "문장(1~500자)에서 기준·필수 조건·선택지·선호를 AI가 뽑는다. 결과는 PENDING이며 29번으로 확정하거나 30번으로 거절한다. 관련 없는 입력은"
              + " 422 IRRELEVANT_INPUT, AI 일일 상한 초과는 429.")
  public CResponses.ExtractInput extract(
      @ClientId UUID clientId,
      @PathVariable UUID decisionId,
      @Valid @RequestBody CRequests.ExtractInput request) {
    return inputs.extract(decisionId, clientId, request);
  }

  @PostMapping("/{inputId}/confirm")
  @Operation(
      operationId = "confirmInput",
      summary = "29. 확인한 자유 입력을 결정에 반영",
      description =
          "사용자가 확인·수정한 항목을 실제 기준·선택지·필수 조건·가상 슬라이더 답변으로 만든다. 이미 처리한 입력은 409 INVALID_STATE.")
  public CResponses.ConfirmInput confirm(
      @ClientId UUID clientId,
      @PathVariable UUID decisionId,
      @PathVariable UUID inputId,
      @Valid @RequestBody CRequests.ConfirmInput request) {
    return inputs.confirm(decisionId, clientId, inputId, request);
  }

  @PostMapping("/{inputId}/reject")
  @Operation(
      operationId = "rejectInput",
      summary = "30. 자유 입력 추출 결과 거절",
      description = "PENDING 입력을 REJECTED로 바꾼다.")
  public CResponses.RejectInput reject(
      @ClientId UUID clientId, @PathVariable UUID decisionId, @PathVariable UUID inputId) {
    return inputs.reject(decisionId, clientId, inputId);
  }
}

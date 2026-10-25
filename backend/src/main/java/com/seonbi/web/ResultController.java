package com.seonbi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seonbi.core.ClientId;
import com.seonbi.core.FlowError;
import com.seonbi.dto.CRequests;
import com.seonbi.dto.CResponses;
import com.seonbi.service.ExportService;
import com.seonbi.service.FinalizeService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 최종 선택 47번과 내보내기 48번. 처리는 {@link FinalizeService}·{@link ExportService}가 한다. */
@RestController
@RequestMapping("/api/v1/decisions/{decisionId}")
public class ResultController {

  private final FinalizeService finalizer;
  private final ExportService exporter;
  private final ObjectMapper mapper;

  public ResultController(FinalizeService finalizer, ExportService exporter, ObjectMapper mapper) {
    this.finalizer = finalizer;
    this.exporter = exporter;
    this.mapper = mapper;
  }

  @PostMapping("/final-choice")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "finalizeChoice", summary = "최종 선택 확정")
  public CResponses.FinalChoice finalizeChoice(
      @ClientId UUID clientId,
      @PathVariable UUID decisionId,
      @Valid @RequestBody CRequests.FinalChoice request) {
    Map<String, Object> done =
        finalizer.finalizeDecision(decisionId, clientId, request.optionId(), request.memo());
    return mapper.convertValue(done, CResponses.FinalChoice.class);
  }

  @GetMapping("/export")
  @Operation(operationId = "exportDecision", summary = "결정 분석 결과를 Markdown 파일로 내보내기")
  public ResponseEntity<String> export(
      @ClientId UUID clientId,
      @PathVariable UUID decisionId,
      @RequestParam(defaultValue = "md") String format) {
    if (!"md".equals(format)) {
      throw new FlowError(
          HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Markdown(md) 형식만 지원합니다.", Map.of());
    }
    ExportService.Exported out = exporter.markdown(decisionId, clientId);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"decision-" + out.decisionId() + ".md\"")
        .contentType(new MediaType("text", "markdown", java.nio.charset.StandardCharsets.UTF_8))
        .body(out.markdown());
  }
}

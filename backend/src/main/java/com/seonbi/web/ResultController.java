package com.seonbi.web;

import com.seonbi.core.ClientId;
import com.seonbi.core.Ids;
import com.seonbi.dto.CRequests;
import com.seonbi.dto.CResponses;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.time.Instant;
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
    // Contract stub. Connect A's state/finalization service and B's repositories after integration.
    return new CResponses.FinalChoice(
        decisionId, request.optionId(), true, Instant.now(), "FINALIZED");
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
    // Contract stub. Replace with the full analysis payload once A's result service is integrated.
    String markdown =
        "# Seonbi 결정 결과\n\n"
            + "> 결과 내보내기 API 스텁입니다. 분석 서비스 연동 후 실제 결과가 표시됩니다.\n";
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename("decision-" + decisionId + ".md")
                .build()
                .toString())
        .body(markdown);
  }
}

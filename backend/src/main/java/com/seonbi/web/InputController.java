package com.seonbi.web;

import com.seonbi.core.ClientId;
import com.seonbi.core.Ids;
import com.seonbi.dto.CRequests;
import com.seonbi.dto.CResponses;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
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
    // Contract stub. Connect A's extraction/quota services and B's persistence after integration.
    var example =
        new CRequests.ConstraintItem(
            "constraint",
            "required_tag",
            null,
            null,
            null,
            "Windows 지원",
            List.of(),
            "high");
    return new CResponses.ExtractInput(Ids.next(), "PENDING", List.of(example), null);
  }

  @PostMapping("/{inputId}/confirm")
  @Operation(operationId = "confirmInput", summary = "확인한 자유 입력을 결정에 반영")
  public CResponses.ConfirmInput confirm(
      @ClientId UUID clientId,
      @PathVariable UUID decisionId,
      @PathVariable UUID inputId,
      @Valid @RequestBody CRequests.ConfirmInput request) {
    // Contract stub. No decision data is changed until the persistence integration is added.
    List<UUID> conditions = new ArrayList<>();
    List<UUID> criteria = new ArrayList<>();
    List<UUID> options = new ArrayList<>();
    List<UUID> answers = new ArrayList<>();
    for (CRequests.ExtractedItem item : request.items()) {
      UUID id = Ids.next();
      switch (item.type()) {
        case "criterion" -> criteria.add(id);
        case "constraint" -> conditions.add(id);
        case "option" -> options.add(id);
        case "preference" -> answers.add(id);
        default -> throw new IllegalArgumentException("Unsupported extracted item type");
      }
    }
    return new CResponses.ConfirmInput(
        "CONFIRMED", new CResponses.CreatedItems(conditions, criteria, options, answers), null);
  }

  @PostMapping("/{inputId}/reject")
  @Operation(operationId = "rejectInput", summary = "자유 입력 추출 결과 거절")
  public CResponses.RejectInput reject(
      @ClientId UUID clientId, @PathVariable UUID decisionId, @PathVariable UUID inputId) {
    // Contract stub. Persistence/state checks are added with the B/A service integration.
    return new CResponses.RejectInput("REJECTED");
  }
}

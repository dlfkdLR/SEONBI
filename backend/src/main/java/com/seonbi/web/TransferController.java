package com.seonbi.web;

import com.seonbi.core.ClientId;
import com.seonbi.dto.TransferDtos;
import com.seonbi.service.TransferService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 기록 이동 49-1·49-2번. 처리는 {@link TransferService}가 한다. */
@Tag(name = "기록 이동", description = "로그인 없이 다른 기기로 내 결정 기록을 옮긴다 (49-1·49-2번).")
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

  private final TransferService transfers;

  public TransferController(TransferService transfers) {
    this.transfers = transfers;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      operationId = "issueTransferCode",
      summary = "49-1. 기록 이동 코드 발급",
      description = "옛 기기에서 호출한다. 10분 동안 한 번만 쓸 수 있는 코드를 돌려준다. 새로 발급하면 이전 코드는 쓸 수 없다.")
  public TransferDtos.IssuedCode issue(@ClientId UUID clientId) {
    return transfers.issue(clientId);
  }

  @PostMapping("/claim")
  @Operation(
      operationId = "claimTransferCode",
      summary = "49-2. 코드로 기록 이어받기",
      description =
          "새 기기에서 호출한다. 맞는 코드면 옛 기기의 clientId를 돌려주고, 새 기기에 있던 결정은 그 ID로 합친다. 코드가 틀리거나 만료·사용됐으면 404"
              + " TRANSFER_CODE_INVALID, 여러 번 틀리면 429 TRANSFER_RATE_LIMITED.")
  public TransferDtos.ClaimResult claim(
      @ClientId UUID clientId, @Valid @RequestBody TransferDtos.ClaimRequest request) {
    return transfers.claim(clientId, request.code());
  }
}

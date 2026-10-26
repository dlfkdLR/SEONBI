package com.seonbi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seonbi.core.ClientId;
import com.seonbi.dto.Requests.*;
import com.seonbi.dto.Responses;
import com.seonbi.service.ConfigurationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "결정 구성", description = "결정·선택지·평가 기준·값·필수 조건을 만들고 고친다 (9~27, 49번).")
@RestController
@RequestMapping(value = "/api/v1/decisions", produces = "application/json")
public class ConfigurationController {
  private final ConfigurationService service;
  private final ObjectMapper mapper;

  public ConfigurationController(ConfigurationService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @Operation(
      summary = "9. 결정 생성",
      description =
          "새 결정을 만든다. 선택지는 함께 보낼 수 있다(최대 8개). 상태는 DRAFT로 시작한다. category는"
              + " purchase·career·study·activity·living·etc 중 하나다.")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Responses.CreatedDecision create(
      @ClientId UUID cid, @Valid @RequestBody CreateDecision r) {
    return mapper.convertValue(service.create(cid, r), Responses.CreatedDecision.class);
  }

  @Operation(
      summary = "10. 결정 기록 조회",
      description = "결정 목록(최근 수정 순). 모든 방문자의 결정이 함께 보인다. status로 거르고 cursor·limit으로 이어서 가져온다.")
  @GetMapping
  public Responses.Decisions list(
      @ClientId UUID cid,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    return mapper.convertValue(service.list(cid, status, cursor, limit), Responses.Decisions.class);
  }

  @Operation(
      summary = "11. 결정 상세 조회",
      description = "결정 하나의 선택지·기준·값·순위·필수 조건·준비 상태(readiness)를 한 번에 돌려준다.")
  @GetMapping("/{decisionId}")
  public Responses.Decision detail(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(service.detail(id, cid), Responses.Decision.class);
  }

  @Operation(
      summary = "12. 결정 수정",
      description = "제목·설명·카테고리를 바꾼다. 확정(FINALIZED)된 결정은 409 DECISION_FINALIZED.")
  @PatchMapping("/{decisionId}")
  public Responses.Decision patch(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @Valid @RequestBody PatchDecision r) {
    return mapper.convertValue(service.patch(id, cid, r), Responses.Decision.class);
  }

  @Operation(summary = "13. 결정 삭제", description = "결정을 삭제한다(목록과 상세에서 사라지는 소프트 삭제).")
  @DeleteMapping("/{decisionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    service.delete(id, cid);
  }

  @Operation(
      summary = "49. 결정 복제",
      description = "선택지·기준·값·조건을 그대로 복사해 DRAFT 상태의 새 결정을 만든다. 답변과 분석 결과는 복사하지 않는다.")
  @PostMapping("/{decisionId}/duplicate")
  @ResponseStatus(HttpStatus.CREATED)
  public Responses.Decision duplicate(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @Valid @RequestBody(required = false) Duplicate r) {
    return mapper.convertValue(service.duplicate(id, cid, r), Responses.Decision.class);
  }

  @Operation(summary = "15. 선택지 조회", description = "선택지 목록.")
  @GetMapping("/{decisionId}/options")
  public Responses.Options options(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(service.getOptions(id, cid), Responses.Options.class);
  }

  @Operation(
      summary = "14. 선택지 추가",
      description = "선택지를 추가한다(최대 8개, 이름 중복 불가). 순위 입력 기준이 있으면 새 선택지는 꼴찌로 들어가고 warning이 온다.")
  @PostMapping("/{decisionId}/options")
  @ResponseStatus(HttpStatus.CREATED)
  public Responses.Option option(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @Valid @RequestBody OptionRequest r) {
    return mapper.convertValue(service.addOption(id, cid, r), Responses.Option.class);
  }

  @Operation(summary = "16. 선택지 수정", description = "이름·설명·URL을 바꾼다.")
  @PatchMapping("/{decisionId}/options/{optionId}")
  public Responses.Option patchOption(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @PathVariable("optionId") UUID oid,
      @Valid @RequestBody PatchOption r) {
    return mapper.convertValue(service.patchOption(id, cid, oid, r), Responses.Option.class);
  }

  @Operation(summary = "17. 선택지 삭제", description = "선택지와 그 값을 지운다. 2개 미만이 되면 질문을 시작할 수 없다.")
  @DeleteMapping("/{decisionId}/options/{optionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteOption(
      @ClientId UUID cid, @PathVariable("decisionId") UUID id, @PathVariable("optionId") UUID oid) {
    service.deleteOption(id, cid, oid);
  }

  @Operation(
      summary = "18. 선택지 추천 (AI)",
      description =
          "AI가 후보를 추천한다. AI 일일 상한을 넘으면 429 AI_QUOTA_EXCEEDED, AI를 쓸 수 없으면 503 AI_UNAVAILABLE.")
  @PostMapping("/{decisionId}/options/recommendations")
  public Responses.OptionRecommendations recommendOptions(
      @ClientId UUID cid, @PathVariable("decisionId") UUID id, @Valid @RequestBody Count r) {
    return mapper.convertValue(
        service.recommendOptions(id, cid, r.count() == null ? 3 : r.count()),
        Responses.OptionRecommendations.class);
  }

  @Operation(summary = "20. 평가 기준 조회", description = "평가 기준 목록.")
  @GetMapping("/{decisionId}/criteria")
  public Responses.Criteria criteria(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(service.getCriteria(id, cid), Responses.Criteria.class);
  }

  @Operation(
      summary = "19. 평가 기준 추가",
      description =
          "기준을 추가한다(2~7개, 이름 중복 불가). type은 numeric·rating·boolean. 질문 중에 추가하면 상태 전환(statusChanged)이"
              + " 올 수 있다.")
  @PostMapping("/{decisionId}/criteria")
  @ResponseStatus(HttpStatus.CREATED)
  public Responses.Criterion criterion(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @Valid @RequestBody CriterionRequest r) {
    return mapper.convertValue(service.addCriterion(id, cid, r), Responses.Criterion.class);
  }

  @Operation(
      summary = "21. 평가 기준 수정",
      description = "이름·방향·단위·단계 라벨·입력 방식(inputMode)·척도를 바꾼다. 답변한 뒤 척도를 바꾸면 warning이 온다.")
  @PatchMapping("/{decisionId}/criteria/{criterionId}")
  public Responses.Criterion patchCriterion(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @PathVariable("criterionId") UUID crid,
      @Valid @RequestBody CriterionRequest r) {
    return mapper.convertValue(service.patchCriterion(id, cid, crid, r), Responses.Criterion.class);
  }

  @Operation(
      summary = "22. 평가 기준 삭제",
      description = "기준을 지운다. 그 기준이 들어간 답변은 무효 처리되고 무효 처리된 수가 돌아온다.")
  @DeleteMapping("/{decisionId}/criteria/{criterionId}")
  public Responses.DeletedCriterion deleteCriterion(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @PathVariable("criterionId") UUID crid) {
    return mapper.convertValue(
        service.deleteCriterion(id, cid, crid), Responses.DeletedCriterion.class);
  }

  @Operation(
      summary = "23. 평가 기준 추천 (AI)",
      description = "AI가 기준을 추천한다. 상한을 넘거나 AI가 실패하면 카테고리별 기본 기준 목록(source=template)을 대신 준다.")
  @PostMapping("/{decisionId}/criteria/recommendations")
  public Responses.CriterionRecommendations recommendCriteria(
      @ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(
        service.recommendCriteria(id, cid), Responses.CriterionRecommendations.class);
  }

  @Operation(
      summary = "24. 선택지 값 조회",
      description = "선택지 × 기준 값과 순위 입력, 아직 비어 있는 칸(missing)을 돌려준다.")
  @GetMapping("/{decisionId}/values")
  public Responses.Values values(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(service.getValues(id, cid), Responses.Values.class);
  }

  @Operation(
      summary = "25. 선택지 값 일괄 저장",
      description =
          "값(items)과 순위(rankings)를 한 번에 저장한다. value를 null로 보내면 그 칸을 지운다. 숫자는 0이거나 절댓값 1e-9~1e15,"
              + " 유효숫자 15자리 이하.")
  @PutMapping("/{decisionId}/values")
  public Responses.Values values(
      @ClientId UUID cid, @PathVariable("decisionId") UUID id, @Valid @RequestBody Values r) {
    return mapper.convertValue(service.putValues(id, cid, r), Responses.Values.class);
  }

  @Operation(summary = "26. 필수 조건 조회", description = "필수 조건 목록과 그 조건으로 제외되는 선택지.")
  @GetMapping("/{decisionId}/conditions")
  public Responses.Conditions conditions(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(service.getConditions(id, cid), Responses.Conditions.class);
  }

  @Operation(
      summary = "27. 필수 조건 설정",
      description = "필수 조건 목록 전체를 바꾼다(최대 50개). threshold(기준값 비교)와 required_tag(충족하는 선택지 지정) 두 종류다.")
  @PutMapping("/{decisionId}/conditions")
  public Responses.Conditions conditions(
      @ClientId UUID cid, @PathVariable("decisionId") UUID id, @Valid @RequestBody Conditions r) {
    return mapper.convertValue(service.putConditions(id, cid, r), Responses.Conditions.class);
  }
}

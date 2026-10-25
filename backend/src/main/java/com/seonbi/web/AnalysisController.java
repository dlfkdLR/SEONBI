package com.seonbi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seonbi.core.ClientId;
import com.seonbi.dto.AnalysisDtos.AnalysisResponse;
import com.seonbi.dto.AnalysisDtos.ChangeConditionsResponse;
import com.seonbi.dto.AnalysisDtos.RankingsResponse;
import com.seonbi.dto.AnalysisDtos.ScoresResponse;
import com.seonbi.dto.AnalysisDtos.SensitivityResponse;
import com.seonbi.dto.AnalysisDtos.SimulationRequest;
import com.seonbi.dto.AnalysisDtos.SimulationResponse;
import com.seonbi.dto.AnalysisDtos.StabilityResponse;
import com.seonbi.dto.AnalysisDtos.WeightsResponse;
import com.seonbi.service.AnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * 분석 API 39~46번 (개발기획서 7.2·7.3 "분석"). 계산은 모두 {@link AnalysisService}에 있고, 여기서는 응답 record(7.3)로 바꾸는
 * 일만 한다. 서비스가 만든 맵이 7.3 키와 다르면 변환에서 실패해 계약 위반이 바로 드러난다.
 */
@RestController
@RequestMapping(value = "/api/v1/decisions/{decisionId}", produces = "application/json")
public class AnalysisController {

  private final AnalysisService analysis;
  private final ObjectMapper mapper;

  public AnalysisController(AnalysisService analysis, ObjectMapper mapper) {
    this.analysis = analysis;
    this.mapper = mapper;
  }

  @GetMapping("/analysis")
  @Operation(operationId = "getAnalysis", summary = "39. 분석 결과 조회(종합)")
  public AnalysisResponse analysis(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(analysis.getAnalysis(id, cid), AnalysisResponse.class);
  }

  @GetMapping("/weights")
  @Operation(operationId = "getWeights", summary = "40. 가중치 조회")
  public WeightsResponse weights(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(analysis.getWeights(id, cid), WeightsResponse.class);
  }

  @GetMapping("/rankings")
  @Operation(operationId = "getRankings", summary = "41. 최종 순위 조회")
  public RankingsResponse rankings(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(analysis.getRankings(id, cid), RankingsResponse.class);
  }

  @GetMapping("/scores")
  @Operation(operationId = "getScores", summary = "42. 조건별 점수 비교")
  public ScoresResponse scores(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(analysis.getScores(id, cid), ScoresResponse.class);
  }

  @GetMapping("/sensitivity")
  @Operation(operationId = "getSensitivity", summary = "43. 민감도 분석")
  public SensitivityResponse sensitivity(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @RequestParam(value = "criterionId", required = false) String criterionId) {
    return mapper.convertValue(
        analysis.getSensitivity(id, cid, criterionId), SensitivityResponse.class);
  }

  @GetMapping("/stability")
  @Operation(operationId = "getStability", summary = "44. 결정 안정성 분석")
  public StabilityResponse stability(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(analysis.getStability(id, cid), StabilityResponse.class);
  }

  @GetMapping("/change-conditions")
  @Operation(operationId = "getChangeConditions", summary = "45. 결정 변경 조건 분석")
  public ChangeConditionsResponse changeConditions(
      @ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(
        analysis.getChangeConditions(id, cid), ChangeConditionsResponse.class);
  }

  @PostMapping("/simulations")
  @Operation(operationId = "simulateWeights", summary = "46. 가중치 시뮬레이션")
  public SimulationResponse simulate(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @Valid @RequestBody SimulationRequest request) {
    // null 항목이나 criterionId 없는 항목도 서비스의 400 검증으로 넘기려고 null을 허용하는 맵으로 만든다
    List<Map<String, Object>> weights =
        request.weights() == null
            ? null
            : request.weights().stream()
                .map(
                    w -> {
                      Map<String, Object> item = new java.util.LinkedHashMap<>();
                      if (w != null) {
                        item.put("criterionId", w.criterionId());
                        item.put("weight", w.weight());
                      }
                      return item;
                    })
                .toList();
    return mapper.convertValue(analysis.simulate(id, cid, weights), SimulationResponse.class);
  }
}

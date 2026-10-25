package com.seonbi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seonbi.core.ClientId;
import com.seonbi.dto.AnswerDtos.AnswerObject;
import com.seonbi.dto.AnswerDtos.EditAnswerRequest;
import com.seonbi.dto.AnswerDtos.EditAnswerResponse;
import com.seonbi.dto.AnswerDtos.Estimate;
import com.seonbi.dto.AnswerDtos.SubmitAnswerRequest;
import com.seonbi.dto.AnswerDtos.SubmitAnswerResponse;
import com.seonbi.dto.QuestionDtos.AnswerListResponse;
import com.seonbi.dto.QuestionDtos.NextQuestionContinue;
import com.seonbi.dto.QuestionDtos.NextQuestionDone;
import com.seonbi.dto.QuestionDtos.NextQuestionResponse;
import com.seonbi.dto.QuestionDtos.ProgressResponse;
import com.seonbi.dto.QuestionDtos.QuestionObject;
import com.seonbi.dto.QuestionDtos.StartQuestionsResponse;
import com.seonbi.dto.QuestionDtos.StatusResponse;
import com.seonbi.dto.QuestionDtos.StopResponse;
import com.seonbi.service.QuestionFlowService;
import com.seonbi.service.QuestionPhrasing;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 질문·답변 API 31~38번 (개발기획서 7.2·7.3 "질문·답변"). 상태·검증 로직은 모두 {@link QuestionFlowService}에 있고, 여기서는 요청을
 * 받아 응답 record(7.3)로 바꾸는 일만 한다. 서비스가 만든 맵이 7.3 키와 다르면 변환에서 실패해 계약 위반이 바로 드러난다.
 */
@RestController
@RequestMapping(value = "/api/v1/decisions/{decisionId}", produces = "application/json")
public class QuestionController {

  private final QuestionFlowService flow;
  private final ObjectMapper mapper;

  private final QuestionPhrasing phrasing;

  public QuestionController(
      QuestionFlowService flow, ObjectMapper mapper, QuestionPhrasing phrasing) {
    this.phrasing = phrasing;
    this.flow = flow;
    this.mapper = mapper;
  }

  @PostMapping("/questions/start")
  @Operation(operationId = "startQuestions", summary = "31. 질문 시작")
  public StartQuestionsResponse start(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(
        phrasing.polish(id, cid, flow.startQuestions(id, cid)), StartQuestionsResponse.class);
  }

  @GetMapping("/questions/next")
  @Operation(operationId = "getNextQuestion", summary = "32. 다음 질문 조회")
  public NextQuestionResponse next(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return nextOf(phrasing.polish(id, cid, flow.nextQuestion(id, cid)));
  }

  @GetMapping("/questions/status")
  @Operation(operationId = "getQuestionStatus", summary = "33. 질문 종료 판단")
  public StatusResponse status(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(flow.questionStatus(id, cid), StatusResponse.class);
  }

  @GetMapping("/questions/progress")
  @Operation(operationId = "getQuestionProgress", summary = "34. 질문 진행률 조회")
  public ProgressResponse progress(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(flow.questionProgress(id, cid), ProgressResponse.class);
  }

  @PostMapping("/questions/stop")
  @Operation(operationId = "stopQuestions", summary = "35. 질문 조기 종료")
  public StopResponse stop(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(flow.stopQuestions(id, cid), StopResponse.class);
  }

  @GetMapping("/answers")
  @Operation(operationId = "listAnswers", summary = "36. 답변 목록 조회")
  public AnswerListResponse answers(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(flow.listAnswers(id, cid), AnswerListResponse.class);
  }

  @PostMapping("/answers")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(operationId = "submitAnswer", summary = "37. 질문 답변 제출")
  public SubmitAnswerResponse submit(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @Valid @RequestBody SubmitAnswerRequest request) {
    UUID questionId;
    try {
      questionId = UUID.fromString(request.questionId());
    } catch (IllegalArgumentException e) {
      throw new com.seonbi.core.FlowError(
          HttpStatus.BAD_REQUEST,
          "VALIDATION_ERROR",
          "questionId 형식이 올바르지 않습니다.",
          Map.of("questionId", "UUID 형식이 아닙니다."));
    }
    Map<String, Object> result =
        phrasing.polish(
            id, cid, flow.submitAnswer(id, cid, questionId, answerMap(request.response())));
    return new SubmitAnswerResponse(
        mapper.convertValue(result.get("answer"), AnswerObject.class),
        mapper.convertValue(result.get("estimate"), Estimate.class),
        nextOf(castMap(result.get("next"))));
  }

  @PatchMapping("/answers/{answerId}")
  @Operation(operationId = "editAnswer", summary = "38. 질문 답변 수정")
  public EditAnswerResponse edit(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @PathVariable("answerId") UUID answerId,
      @Valid @RequestBody EditAnswerRequest request) {
    return mapper.convertValue(
        flow.editAnswer(id, cid, answerId, answerMap(request.response())),
        EditAnswerResponse.class);
  }

  /** 서비스가 보는 답변 값: 값이 있는 키 하나만 담은 맵. */
  private Map<String, Object> answerMap(com.seonbi.dto.QuestionDtos.AnswerValue value) {
    return mapper.convertValue(value, new com.fasterxml.jackson.core.type.TypeReference<>() {});
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object o) {
    return (Map<String, Object>) o;
  }

  private NextQuestionResponse nextOf(Map<String, Object> next) {
    if (Boolean.TRUE.equals(next.get("done"))) {
      return new NextQuestionDone((String) next.get("reason"));
    }
    return new NextQuestionContinue(
        mapper.convertValue(next.get("question"), QuestionObject.class));
  }
}

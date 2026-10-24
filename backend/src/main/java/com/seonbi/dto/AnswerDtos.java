package com.seonbi.dto;

import com.seonbi.dto.QuestionDtos.AnswerValue;
import com.seonbi.dto.QuestionDtos.NextQuestionResponse;
import java.time.Instant;
import java.util.List;

/**
 * 답변 API 요청·응답: 37·38번 (개발기획서 7.3 "질문·답변").
 *
 * <p>가정: 37번 answer: { id: "a7", ... }의 ...는 36번 items의 question을 뺀 필드(id, questionId, response,
 * isValid, createdAt)로 읽었다. 38번 응답은 "37번과 같은 estimate"라는 문장 그대로 estimate만 둔다(수정된 answer는 넣지 않는다).
 */
public final class AnswerDtos {

  private AnswerDtos() {}

  /** 37번 요청. response는 choice | value | skip 중 하나. */
  public record SubmitAnswerRequest(String questionId, AnswerValue response) {}

  /** 38번 요청. */
  public record EditAnswerRequest(AnswerValue response) {}

  public record AnswerObject(
      String id, String questionId, AnswerValue response, boolean isValid, Instant createdAt) {}

  public record WeightEstimate(String criterionId, double mean, List<Double> interval) {
    public WeightEstimate {
      QuestionDtos.require(interval != null && interval.size() == 2, "interval은 [하한, 상한]입니다");
    }
  }

  public record Estimate(List<WeightEstimate> weights, double topProbability) {}

  /** 37번 201. next는 32번과 같은 형식(다음 질문을 함께 반환). */
  public record SubmitAnswerResponse(
      AnswerObject answer, Estimate estimate, NextQuestionResponse next) {}

  /** 38번 200 — 전체 재추정 후 37번과 같은 estimate. */
  public record EditAnswerResponse(Estimate estimate) {}
}

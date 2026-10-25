package com.seonbi.service;

import static com.seonbi.service.FlowErrors.details;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.seonbi.ai.Json;
import com.seonbi.ai.LlmCalls.ExtractResult;
import com.seonbi.ai.LlmSchemas;
import com.seonbi.core.FlowError;
import com.seonbi.domain.Condition;
import com.seonbi.domain.Criterion;
import com.seonbi.domain.Decision;
import com.seonbi.domain.FreeTextInput;
import com.seonbi.domain.Question;
import com.seonbi.dto.CRequests;
import com.seonbi.dto.CResponses;
import com.seonbi.dto.Requests.ConditionRequest;
import com.seonbi.dto.Requests.CriterionRequest;
import com.seonbi.dto.Requests.OptionRequest;
import com.seonbi.service.StateRules.Action;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자유 입력 API 28~30번 (개발기획서 7.3). 추출은 A의 {@link ExtractionService}가 하고, 여기서는 입력 기록과 확인 후 반영을 맡는다.
 *
 * <ul>
 *   <li>28번: 관련 없는 입력은 {@code status = IRRELEVANT}로 기록만 남기고 422 IRRELEVANT_INPUT을 낸다(오류로 끝나도 기록이 남게
 *       롤백하지 않는다).
 *   <li>29번: 확정한 항목을 B의 구성 서비스(기준·선택지·필수 조건)와 A의 질문 서비스(가상 슬라이더 질문·답변)로 만든다. 기준이 생기면 19번과 같은 상태 규칙을
 *       따른다.
 *   <li>30번: PENDING 입력을 REJECTED로 바꾼다.
 * </ul>
 */
@Service
public class InputService {

  private final EntityManager em;
  private final DecisionResolver resolver;
  private final ExtractionService extraction;
  private final ConfigurationService configuration;
  private final QuestionFlowService questions;

  public InputService(
      EntityManager em,
      DecisionResolver resolver,
      ExtractionService extraction,
      ConfigurationService configuration,
      QuestionFlowService questions) {
    this.em = em;
    this.resolver = resolver;
    this.extraction = extraction;
    this.configuration = configuration;
    this.questions = questions;
  }

  /** 28번. */
  @Transactional(noRollbackFor = FlowError.class)
  public CResponses.ExtractInput extract(
      UUID decisionId, UUID clientId, CRequests.ExtractInput request) {
    Decision d = resolver.get(decisionId, clientId);
    Question question = null;
    String questionContext = null;
    if (request.questionId() != null) {
      question = em.find(Question.class, request.questionId());
      if (question == null || !question.getDecision().getId().equals(d.getId())) {
        throw FlowErrors.notFound(
            "질문을 찾을 수 없습니다.", details("questionId", request.questionId().toString()));
      }
      Object title = question.getDisplay() == null ? null : question.getDisplay().get("title");
      questionContext = title == null ? null : title.toString();
    }
    ExtractResult result = extraction.extract(d, request.text(), questionContext);
    FreeTextInput row = new FreeTextInput();
    row.setDecision(d);
    row.setQuestion(question);
    row.setContext(request.context());
    row.setText(request.text());
    if (result.irrelevant()) {
      row.setExtracted(List.of());
      row.setStatus("IRRELEVANT");
      em.persist(row);
      throw new FlowError(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "IRRELEVANT_INPUT",
          "현재 결정과 관련된 조건이나 이유를 입력해 주세요.",
          Map.of());
    }
    List<Map<String, Object>> items = new ArrayList<>();
    for (LlmSchemas.ExtractedItem item : result.items()) {
      items.add(itemToMap(item));
    }
    row.setExtracted(items);
    row.setStatus("PENDING");
    em.persist(row);
    List<CRequests.ExtractedItem> extracted =
        items.stream()
            .map(m -> Json.MAPPER.convertValue(m, CRequests.ExtractedItem.class))
            .toList();
    return new CResponses.ExtractInput(row.getId(), "PENDING", extracted, result.ambiguity());
  }

  private FreeTextInput pendingInput(Decision d, UUID inputId) {
    FreeTextInput row = em.find(FreeTextInput.class, inputId);
    if (row == null || !row.getDecision().getId().equals(d.getId())) {
      throw FlowErrors.notFound("입력을 찾을 수 없습니다.", details("inputId", inputId.toString()));
    }
    if (!"PENDING".equals(row.getStatus())) {
      throw new FlowError(
          HttpStatus.CONFLICT,
          "INVALID_STATE",
          "이미 처리된 입력입니다.",
          details("inputId", inputId.toString(), "status", row.getStatus()));
    }
    return row;
  }

  /** 29번. */
  @Transactional
  public CResponses.ConfirmInput confirm(
      UUID decisionId, UUID clientId, UUID inputId, CRequests.ConfirmInput request) {
    Decision d = resolver.getForUpdate(decisionId, clientId);
    resolver.ensureNotFinalized(d);
    FlowErrors.raiseIf(
        StateRules.check(Action.FREE_INPUT, DecisionStatus.valueOf(d.getStatus())),
        details("decisionId", d.getId().toString()));
    FreeTextInput row = pendingInput(d, inputId);

    List<UUID> conditions = new ArrayList<>(), criteria = new ArrayList<>();
    List<UUID> optionIds = new ArrayList<>(), answers = new ArrayList<>();
    String statusChanged = null;
    // 선호는 새 기준 이름을 가리킬 수 있으므로 기준 → 선택지 → 조건 → 선호 순서로 만든다
    for (CRequests.ExtractedItem item : request.items()) {
      if (item instanceof CRequests.CriterionItem c) {
        Map<String, Object> made =
            configuration.createCriterion(
                d,
                new CriterionRequest(
                    c.name(),
                    c.criterionType(),
                    c.direction(),
                    c.unit(),
                    c.levels(),
                    c.inputMode(),
                    null,
                    null,
                    null), // source는 createCriterion이 origin(free_text)으로 채운다
                "free_text");
        criteria.add((UUID) made.get("id"));
        if (made.get("statusChanged") != null) {
          statusChanged = String.valueOf(made.get("statusChanged"));
        }
      }
    }
    for (CRequests.ExtractedItem item : request.items()) {
      if (item instanceof CRequests.OptionItem o) {
        Map<String, Object> made =
            configuration.createOption(d, new OptionRequest(o.name(), o.description(), o.url()));
        optionIds.add((UUID) made.get("id"));
      }
    }
    for (CRequests.ExtractedItem item : request.items()) {
      if (item instanceof CRequests.ConstraintItem k) {
        Condition made =
            configuration.appendCondition(
                d,
                new ConditionRequest(
                    k.kind(),
                    k.criterionId(),
                    k.operator(),
                    numberOf(k.value()),
                    k.label(),
                    k.satisfiedBy(),
                    "free_text"),
                "free_text");
        conditions.add(made.getId());
      }
    }
    for (CRequests.ExtractedItem item : request.items()) {
      if (item instanceof CRequests.PreferenceItem p) {
        Map<String, UUID> byName = criterionIdsByName(d);
        // AI 추출 규격은 기준 ID를, 사용자가 화면에서 고친 값은 이름을 줄 수 있어 둘 다 받는다
        UUID a = resolveCriterion(byName, p.criterionA());
        UUID b = resolveCriterion(byName, p.criterionB());
        if (a == null || b == null) {
          throw FlowErrors.notFound(
              "선호가 가리키는 기준을 찾을 수 없습니다.",
              details("criterionA", p.criterionA(), "criterionB", p.criterionB()));
        }
        Map<String, Object> made = questions.addStatedPreference(d, a, b, p.value());
        answers.add(UUID.fromString((String) made.get("answerId")));
      }
    }
    row.setStatus("CONFIRMED");
    resolver.bumpVersion(d);
    return new CResponses.ConfirmInput(
        "CONFIRMED",
        new CResponses.CreatedItems(conditions, criteria, optionIds, answers),
        statusChanged);
  }

  /** 30번. */
  @Transactional
  public CResponses.RejectInput reject(UUID decisionId, UUID clientId, UUID inputId) {
    Decision d = resolver.getForUpdate(decisionId, clientId);
    resolver.ensureNotFinalized(d);
    FreeTextInput row = pendingInput(d, inputId);
    row.setStatus("REJECTED");
    return new CResponses.RejectInput("REJECTED");
  }

  // type 속성이 빠지지 않도록 선언 타입(ExtractedItem)으로 직렬화한 뒤 맵으로 읽는다
  private static Map<String, Object> itemToMap(LlmSchemas.ExtractedItem item) {
    try {
      String json = Json.MAPPER.writerFor(LlmSchemas.ExtractedItem.class).writeValueAsString(item);
      return Json.MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("추출 항목을 읽을 수 없습니다", e);
    }
  }

  private static UUID resolveCriterion(Map<String, UUID> byName, String ref) {
    if (ref == null) {
      return null;
    }
    UUID byNameHit = byName.get(ref);
    if (byNameHit != null) {
      return byNameHit;
    }
    try {
      UUID id = UUID.fromString(ref.trim());
      return byName.containsValue(id) ? id : null;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private Map<String, UUID> criterionIdsByName(Decision d) {
    Map<String, UUID> out = new HashMap<>();
    for (Criterion c :
        em.createQuery("select c from Criterion c where c.decision.id=:id", Criterion.class)
            .setParameter("id", d.getId())
            .getResultList()) {
      out.put(c.getName(), c.getId());
    }
    return out;
  }

  // 임계 조건의 값: 숫자는 그대로, 예/아니요(true/false)는 1/0
  private static BigDecimal numberOf(JsonNode value) {
    if (value == null || value.isNull()) {
      return null;
    }
    if (value.isBoolean()) {
      return value.booleanValue() ? BigDecimal.ONE : BigDecimal.ZERO;
    }
    return value.decimalValue();
  }
}

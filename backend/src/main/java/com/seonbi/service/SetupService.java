package com.seonbi.service;

import static com.seonbi.service.ConfigurationService.require;

import com.seonbi.core.FlowError;
import com.seonbi.domain.Decision;
import com.seonbi.dto.Requests.*;
import com.seonbi.repository.DecisionRepository;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 9-1번 초기 설정 완료 저장. 제목·후보·평가 기준·값·필수 조건을 한 번에 받아 한 트랜잭션으로 저장하고 그때 처음 결정 ID를 발급한다. 중간에 하나라도 틀리면 전체를
 * 되돌려서 DB에는 아무것도 남지 않는다.
 *
 * <p>같은 setupKey로 다시 오면(더블 클릭·네트워크 재시도·동시 요청) 새로 만들지 않고 이미 만든 결정을 돌려준다.
 */
@Service
public class SetupService {

  /** 저장 결과. created가 false면 같은 setupKey로 이미 만들어 둔 결정을 다시 돌려준 것이다. */
  public record Result(Map<String, Object> decision, boolean created) {}

  private final ConfigurationService configuration;
  private final DecisionRepository decisions;
  private final EntityManager em;
  private final TransactionTemplate tx;

  public SetupService(
      ConfigurationService configuration,
      DecisionRepository decisions,
      EntityManager em,
      PlatformTransactionManager transactions) {
    this.configuration = configuration;
    this.decisions = decisions;
    this.em = em;
    this.tx = new TransactionTemplate(transactions);
  }

  public Result setup(UUID clientId, SetupDecision request) {
    Optional<Map<String, Object>> replay = replay(clientId, request.setupKey());
    if (replay.isPresent()) {
      return new Result(replay.get(), false);
    }
    try {
      Map<String, Object> created = tx.execute(status -> create(clientId, request));
      return new Result(created, true);
    } catch (FlowError e) {
      throw e; // 검증 실패: 전체가 롤백됐고 아무것도 저장되지 않았다
    } catch (RuntimeException e) {
      // 같은 키의 요청이 동시에 먼저 커밋했으면(유일 키 충돌) 그 결정을 돌려준다. 아니면 진짜 실패다
      Optional<Map<String, Object>> winner = replay(clientId, request.setupKey());
      if (winner.isPresent()) {
        return new Result(winner.get(), false);
      }
      throw e;
    }
  }

  private Optional<Map<String, Object>> replay(UUID clientId, UUID setupKey) {
    return tx.execute(
        status ->
            decisions
                .findByClientIdAndSetupKey(clientId, setupKey)
                .map(
                    d -> {
                      if (d.getDeletedAt() != null) {
                        throw new FlowError(
                            HttpStatus.CONFLICT,
                            "INVALID_STATE",
                            "이미 처리된 요청입니다. 새로 시작해 주세요.",
                            Map.of("decisionId", d.getId()));
                      }
                      return configuration.detail(d);
                    }));
  }

  private Map<String, Object> create(UUID clientId, SetupDecision r) {
    validateRefs(r);
    ConfigurationService.category(r.category());
    ConfigurationService.text(r.title(), 100, "title");

    Decision d = new Decision();
    d.setClientId(clientId);
    d.setSetupKey(r.setupKey());
    d.setTitle(r.title());
    d.setDescription(r.description());
    d.setCategory(r.category());
    em.persist(d);
    em.flush(); // 같은 키가 이미 있으면 여기서 유일 키 충돌이 난다

    Map<String, UUID> optionIds = new HashMap<>(), criterionIds = new HashMap<>();
    for (SetupOption o : r.options()) {
      Map<String, Object> made =
          configuration.createOption(d, new OptionRequest(o.name(), o.description(), o.url()));
      optionIds.put(o.ref(), (UUID) made.get("id"));
    }
    for (SetupCriterion c : r.criteria()) {
      Map<String, Object> made =
          configuration.createCriterion(
              d,
              new CriterionRequest(
                  c.name(),
                  c.type(),
                  c.direction(),
                  c.unit(),
                  c.levels(),
                  c.inputMode(),
                  c.scaleMin(),
                  c.scaleMax(),
                  c.source()),
              "user");
      criterionIds.put(c.ref(), (UUID) made.get("id"));
    }

    List<ValueItem> items = new ArrayList<>();
    for (SetupValue v : orEmpty(r.values())) {
      items.add(
          new ValueItem(
              known(optionIds, v.optionRef(), "values"),
              known(criterionIds, v.criterionRef(), "values"),
              v.value()));
    }
    List<Ranking> rankings = new ArrayList<>();
    for (SetupRanking k : orEmpty(r.rankings())) {
      List<List<UUID>> order = new ArrayList<>();
      for (List<String> group : k.order() == null ? List.<List<String>>of() : k.order()) {
        List<UUID> ids = new ArrayList<>();
        for (String ref : group) ids.add(known(optionIds, ref, "rankings"));
        order.add(ids);
      }
      rankings.add(new Ranking(known(criterionIds, k.criterionRef(), "rankings"), order));
    }
    configuration.putValues(d, new Values(items, rankings));

    for (SetupCondition c : orEmpty(r.conditions())) {
      List<UUID> satisfiedBy = null;
      if (c.satisfiedByRefs() != null) {
        satisfiedBy = new ArrayList<>();
        for (String ref : c.satisfiedByRefs()) {
          satisfiedBy.add(known(optionIds, ref, "conditions"));
        }
      }
      configuration.appendCondition(
          d,
          new ConditionRequest(
              c.kind(),
              c.criterionRef() == null ? null : known(criterionIds, c.criterionRef(), "conditions"),
              c.operator(),
              c.value(),
              c.label(),
              satisfiedBy,
              c.source()),
          "user");
    }
    em.flush();

    // 5단계를 다 채운 요청만 저장한다. 비어 있는 값·순위가 있으면 롤백해서 DB에 남기지 않는다
    @SuppressWarnings("unchecked")
    List<String> missing = (List<String>) configuration.computeReadiness(d).get("missing");
    if (!missing.isEmpty()) {
      throw new FlowError(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "NOT_READY",
          "모든 후보의 평가값을 입력한 뒤에 저장할 수 있습니다.",
          Map.of("missing", missing));
    }
    return configuration.detail(d);
  }

  /** 임시 이름(ref)이 비어 있거나 겹치지 않는지 먼저 본다. 가리키는 대상이 없는 참조는 {@link #known}이 잡는다. */
  private static void validateRefs(SetupDecision r) {
    Set<String> options = new HashSet<>(), criteria = new HashSet<>();
    for (SetupOption o : r.options()) require(options.add(o.ref()), "options");
    for (SetupCriterion c : r.criteria()) require(criteria.add(c.ref()), "criteria");
  }

  private static UUID known(Map<String, UUID> ids, String ref, String field) {
    UUID id = ids.get(ref);
    require(id != null, field);
    return id;
  }

  private static <T> List<T> orEmpty(List<T> list) {
    return list == null ? List.of() : list;
  }
}

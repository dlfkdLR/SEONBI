package com.seonbi.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.seonbi.core.ClientId;
import com.seonbi.dto.Requests.*;
import com.seonbi.dto.Responses;
import com.seonbi.service.ConfigurationService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = "/api/v1/decisions", produces = "application/json")
public class ConfigurationController {
  private final ConfigurationService service;
  private final ObjectMapper mapper;

  public ConfigurationController(ConfigurationService service, ObjectMapper mapper) {
    this.service = service;
    this.mapper = mapper;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Responses.CreatedDecision create(
      @ClientId UUID cid, @Valid @RequestBody CreateDecision r) {
    return mapper.convertValue(service.create(cid, r), Responses.CreatedDecision.class);
  }

  @GetMapping
  public Responses.Decisions list(
      @ClientId UUID cid,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    return mapper.convertValue(service.list(cid, status, cursor, limit), Responses.Decisions.class);
  }

  @GetMapping("/{decisionId}")
  public Responses.Decision detail(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(service.detail(id, cid), Responses.Decision.class);
  }

  @PatchMapping("/{decisionId}")
  public Responses.Decision patch(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @Valid @RequestBody PatchDecision r) {
    return mapper.convertValue(service.patch(id, cid, r), Responses.Decision.class);
  }

  @DeleteMapping("/{decisionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    service.delete(id, cid);
  }

  @PostMapping("/{decisionId}/duplicate")
  @ResponseStatus(HttpStatus.CREATED)
  public Responses.Decision duplicate(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @Valid @RequestBody(required = false) Duplicate r) {
    return mapper.convertValue(service.duplicate(id, cid, r), Responses.Decision.class);
  }

  @GetMapping("/{decisionId}/options")
  public Responses.Options options(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(service.getOptions(id, cid), Responses.Options.class);
  }

  @PostMapping("/{decisionId}/options")
  @ResponseStatus(HttpStatus.CREATED)
  public Responses.Option option(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @Valid @RequestBody OptionRequest r) {
    return mapper.convertValue(service.addOption(id, cid, r), Responses.Option.class);
  }

  @PatchMapping("/{decisionId}/options/{optionId}")
  public Responses.Option patchOption(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @PathVariable("optionId") UUID oid,
      @Valid @RequestBody PatchOption r) {
    return mapper.convertValue(service.patchOption(id, cid, oid, r), Responses.Option.class);
  }

  @DeleteMapping("/{decisionId}/options/{optionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteOption(
      @ClientId UUID cid, @PathVariable("decisionId") UUID id, @PathVariable("optionId") UUID oid) {
    service.deleteOption(id, cid, oid);
  }

  @PostMapping("/{decisionId}/options/recommendations")
  public Responses.OptionRecommendations recommendOptions(
      @ClientId UUID cid, @PathVariable("decisionId") UUID id, @Valid @RequestBody Count r) {
    return mapper.convertValue(
        service.recommendOptions(id, cid, r.count() == null ? 3 : r.count()),
        Responses.OptionRecommendations.class);
  }

  @GetMapping("/{decisionId}/criteria")
  public Responses.Criteria criteria(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(service.getCriteria(id, cid), Responses.Criteria.class);
  }

  @PostMapping("/{decisionId}/criteria")
  @ResponseStatus(HttpStatus.CREATED)
  public Responses.Criterion criterion(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @Valid @RequestBody CriterionRequest r) {
    return mapper.convertValue(service.addCriterion(id, cid, r), Responses.Criterion.class);
  }

  @PatchMapping("/{decisionId}/criteria/{criterionId}")
  public Responses.Criterion patchCriterion(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @PathVariable("criterionId") UUID crid,
      @Valid @RequestBody CriterionRequest r) {
    return mapper.convertValue(service.patchCriterion(id, cid, crid, r), Responses.Criterion.class);
  }

  @DeleteMapping("/{decisionId}/criteria/{criterionId}")
  public Responses.DeletedCriterion deleteCriterion(
      @ClientId UUID cid,
      @PathVariable("decisionId") UUID id,
      @PathVariable("criterionId") UUID crid) {
    return mapper.convertValue(
        service.deleteCriterion(id, cid, crid), Responses.DeletedCriterion.class);
  }

  @PostMapping("/{decisionId}/criteria/recommendations")
  public Responses.CriterionRecommendations recommendCriteria(
      @ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(
        service.recommendCriteria(id, cid), Responses.CriterionRecommendations.class);
  }

  @GetMapping("/{decisionId}/values")
  public Responses.Values values(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(service.getValues(id, cid), Responses.Values.class);
  }

  @PutMapping("/{decisionId}/values")
  public Responses.Values values(
      @ClientId UUID cid, @PathVariable("decisionId") UUID id, @Valid @RequestBody Values r) {
    return mapper.convertValue(service.putValues(id, cid, r), Responses.Values.class);
  }

  @GetMapping("/{decisionId}/conditions")
  public Responses.Conditions conditions(@ClientId UUID cid, @PathVariable("decisionId") UUID id) {
    return mapper.convertValue(service.getConditions(id, cid), Responses.Conditions.class);
  }

  @PutMapping("/{decisionId}/conditions")
  public Responses.Conditions conditions(
      @ClientId UUID cid, @PathVariable("decisionId") UUID id, @Valid @RequestBody Conditions r) {
    return mapper.convertValue(service.putConditions(id, cid, r), Responses.Conditions.class);
  }
}

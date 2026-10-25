package com.seonbi.service;

import com.seonbi.core.*;
import com.seonbi.domain.*;
import com.seonbi.domain.Option;
import com.seonbi.dto.Requests.*;
import com.seonbi.repository.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ConfigurationService {
  private final EntityManager em;
  private final DecisionResolver resolver;
  private final DecisionRepository decisions;
  private final ObjectProvider<ConfigurationHooks.CriterionAdded> transition;
  private final ObjectProvider<ConfigurationHooks.Recommendations> recommendations;

  public ConfigurationService(
      EntityManager em,
      DecisionResolver resolver,
      DecisionRepository decisions,
      ObjectProvider<ConfigurationHooks.CriterionAdded> transition,
      ObjectProvider<ConfigurationHooks.Recommendations> recommendations) {
    this.em = em;
    this.resolver = resolver;
    this.decisions = decisions;
    this.transition = transition;
    this.recommendations = recommendations;
  }

  static Map<String, Object> obj(Object... args) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i += 2) m.put((String) args[i], args[i + 1]);
    return m;
  }

  static void require(boolean valid, String field) {
    if (!valid)
      throw new FlowError(
          HttpStatus.BAD_REQUEST,
          "VALIDATION_ERROR",
          "입력 형식이나 범위를 확인해 주세요.",
          Map.of(field, "입력 형식이나 범위를 확인해 주세요."));
  }

  static FlowError conflict(String code) {
    return new FlowError(HttpStatus.CONFLICT, code, "현재 결정에서 수행할 수 없는 작업입니다.", Map.of());
  }

  static void text(String s, int max, String field) {
    require(s != null && !s.isBlank() && s.length() <= max, field);
  }

  static void category(String s) {
    require(
        s != null && Set.of("purchase", "career", "study", "activity", "living", "etc").contains(s),
        "category");
  }

  static String source(String s) {
    String v = s == null ? "user" : s;
    require(Set.of("user", "ai_recommended").contains(v), "source");
    return v;
  }

  public <T> List<T> rows(Class<T> type, Decision d) {
    return em.createQuery(
            "select x from " + type.getSimpleName() + " x where x.decision.id=:id", type)
        .setParameter("id", d.getId())
        .getResultList();
  }

  private <T> T owned(Class<T> type, UUID id, Decision d) {
    T row = em.find(type, id);
    boolean found = rows(type, d).contains(row);
    if (!found) throw new FlowError(HttpStatus.NOT_FOUND, "NOT_FOUND", "항목을 찾을 수 없습니다.", Map.of());
    return row;
  }

  private Decision edit(UUID id, UUID cid) {
    Decision d = resolver.get(id, cid);
    em.lock(d, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
    resolver.ensureNotFinalized(d);
    return d;
  }

  private void bump(Decision d) {
    resolver.bumpVersion(d);
  }

  public Map<String, Object> create(UUID cid, CreateDecision r) {
    category(r.category());
    Decision d = new Decision();
    d.setClientId(cid);
    d.setTitle(r.title());
    d.setDescription(r.description());
    d.setCategory(r.category());
    em.persist(d);
    if (r.options() != null) for (OptionRequest o : r.options()) createOption(d, o);
    return obj(
        "id",
        d.getId(),
        "title",
        d.getTitle(),
        "description",
        d.getDescription(),
        "category",
        d.getCategory(),
        "status",
        d.getStatus(),
        "options",
        options(d),
        "createdAt",
        d.getCreatedAt(),
        "updatedAt",
        d.getUpdatedAt());
  }

  public Map<String, Object> list(UUID cid, String status, String cursor, int limit) {
    require(limit >= 1 && limit <= 100, "limit");
    if (status != null)
      require(Set.of("DRAFT", "QUESTIONING", "ANALYZED", "FINALIZED").contains(status), "status");
    Instant time = null;
    UUID last = null;
    if (cursor != null)
      try {
        String[] parts =
            new String(
                    Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8)
                .split("[|]", 2);
        time = Instant.parse(parts[0]);
        last = UUID.fromString(parts[1]);
      } catch (RuntimeException e) {
        require(false, "cursor");
      }
    String q =
        "select d from Decision d where d.deletedAt is null"
            + (status == null ? "" : " and d.status=:status")
            + (time == null ? "" : " and (d.updatedAt<:time or (d.updatedAt=:time and d.id<:last))")
            + " order by d.updatedAt desc,d.id desc";
    var query = em.createQuery(q, Decision.class).setMaxResults(limit + 1);
    if (status != null) query.setParameter("status", status);
    if (time != null) {
      query.setParameter("time", time);
      query.setParameter("last", last);
    }
    List<Decision> ds = query.getResultList();
    boolean more = ds.size() > limit;
    ds = ds.subList(0, Math.min(limit, ds.size()));
    List<Object> items = new ArrayList<>();
    Map<UUID, Long> counts = new HashMap<>();
    Map<UUID, Map<String, Object>> choices = new HashMap<>();
    if (!ds.isEmpty()) {
      var ids = ds.stream().map(Decision::getId).toList();
      em.createQuery(
              "select o.decision.id,count(o) from Option o where o.decision.id in :ids group by o.decision.id",
              Object[].class)
          .setParameter("ids", ids)
          .getResultList()
          .forEach(row -> counts.put((UUID) row[0], (Long) row[1]));
      em.createQuery(
              "select f.decision.id,f.option.id,f.option.name from FinalChoice f where f.decision.id in :ids",
              Object[].class)
          .setParameter("ids", ids)
          .getResultList()
          .forEach(row -> choices.put((UUID) row[0], obj("optionId", row[1], "name", row[2])));
    }
    for (Decision d : ds) {
      items.add(
          obj(
              "id",
              d.getId(),
              "title",
              d.getTitle(),
              "status",
              d.getStatus(),
              "optionCount",
              counts.getOrDefault(d.getId(), 0L),
              "finalChoice",
              choices.get(d.getId()),
              "updatedAt",
              d.getUpdatedAt()));
    }
    String next = null;
    if (more) {
      Decision d = ds.getLast();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  (d.getUpdatedAt() + "|" + d.getId())
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    return obj("items", items, "nextCursor", next);
  }

  public Map<String, Object> detail(UUID id, UUID cid) {
    return detail(resolver.get(id, cid));
  }

  public Map<String, Object> detail(Decision d) {
    var v = values(d);
    return obj(
        "id",
        d.getId(),
        "title",
        d.getTitle(),
        "description",
        d.getDescription(),
        "category",
        d.getCategory(),
        "status",
        d.getStatus(),
        "options",
        options(d),
        "criteria",
        criteria(d),
        "values",
        v.get("items"),
        "rankings",
        v.get("rankings"),
        "conditions",
        conditions(d).get("items"),
        "readiness",
        computeReadiness(d),
        "createdAt",
        d.getCreatedAt(),
        "updatedAt",
        d.getUpdatedAt());
  }

  public Map<String, Object> patch(UUID id, UUID cid, PatchDecision r) {
    Decision d = edit(id, cid);
    if (r.title() != null) {
      text(r.title(), 100, "title");
      d.setTitle(r.title());
    }
    if (r.description() != null) d.setDescription(r.description());
    if (r.category() != null) {
      category(r.category());
      d.setCategory(r.category());
    }
    d.setUpdatedAt(Instant.now());
    return detail(d);
  }

  public void delete(UUID id, UUID cid) {
    Decision d = resolver.get(id, cid);
    d.setDeletedAt(Instant.now());
    d.setUpdatedAt(Instant.now());
  }

  private List<Option> optionRows(Decision d) {
    return rows(Option.class, d).stream()
        .sorted(Comparator.comparing(Option::getPosition))
        .toList();
  }

  private List<Criterion> criterionRows(Decision d) {
    return rows(Criterion.class, d).stream()
        .sorted(Comparator.comparing(Criterion::getPosition))
        .toList();
  }

  public List<Map<String, Object>> options(Decision d) {
    var exclusions = computeExclusions(d);
    return optionRows(d).stream()
        .map(
            o ->
                obj(
                    "id",
                    o.getId(),
                    "name",
                    o.getName(),
                    "description",
                    o.getDescription(),
                    "url",
                    o.getUrl(),
                    "excluded",
                    !exclusions.get(o.getId()).isEmpty(),
                    "excludedReasons",
                    exclusions.get(o.getId())))
        .toList();
  }

  public Map<String, Object> getOptions(UUID id, UUID cid) {
    return obj("items", options(resolver.get(id, cid)));
  }

  public Map<String, Object> addOption(UUID id, UUID cid, OptionRequest r) {
    return createOption(edit(id, cid), r);
  }

  public Map<String, Object> createOption(Decision d, OptionRequest r) {
    resolver.ensureNotFinalized(d);
    text(r.name(), 60, "name");
    List<Option> existing = optionRows(d);
    if (existing.size() >= 8) throw conflict("LIMIT_EXCEEDED");
    require(existing.stream().noneMatch(o -> o.getName().equals(r.name())), "name");
    Option o = new Option();
    o.setDecision(d);
    o.setName(r.name());
    o.setDescription(r.description());
    o.setUrl(r.url());
    o.setPosition(existing.stream().mapToInt(Option::getPosition).max().orElse(-1) + 1);
    em.persist(o);
    boolean ranked = false;
    if (!"DRAFT".equals(d.getStatus()))
      for (Criterion c : criterionRows(d))
        if ("rank".equals(c.getInputMode())) {
          int rank =
              valueRows(d).stream()
                      .filter(
                          v ->
                              v.getCriterion().getId().equals(c.getId())
                                  && v.getValueRank() != null)
                      .mapToInt(v -> v.getValueRank())
                      .max()
                      .orElse(0)
                  + 1;
          OptionValue v = new OptionValue();
          v.setOption(o);
          v.setCriterion(c);
          v.setValueRank((short) rank);
          em.persist(v);
          ranked = true;
        }
    bump(d);
    var result =
        options(d).stream().filter(m -> m.get("id").equals(o.getId())).findFirst().orElseThrow();
    if (ranked) result.put("warning", "RANKING_UPDATED");
    return result;
  }

  public Map<String, Object> patchOption(UUID id, UUID cid, UUID oid, PatchOption r) {
    Decision d = edit(id, cid);
    Option o = owned(Option.class, oid, d);
    if (r.name() != null) {
      text(r.name(), 60, "name");
      require(
          optionRows(d).stream()
              .noneMatch(x -> !x.getId().equals(oid) && x.getName().equals(r.name())),
          "name");
      o.setName(r.name());
    }
    if (r.description() != null) o.setDescription(r.description());
    if (r.url() != null) o.setUrl(r.url());
    bump(d);
    return options(d).stream().filter(x -> x.get("id").equals(oid)).findFirst().orElseThrow();
  }

  public void deleteOption(UUID id, UUID cid, UUID oid) {
    Decision d = edit(id, cid);
    Option o = owned(Option.class, oid, d);
    if (!"DRAFT".equals(d.getStatus()) && optionRows(d).size() <= 2)
      throw conflict("INVALID_STATE");
    em.remove(o);
    em.flush();
    bump(d);
  }

  private Map<String, Object> criterion(Criterion c) {
    return obj(
        "id",
        c.getId(),
        "name",
        c.getName(),
        "type",
        c.getType(),
        "direction",
        c.getDirection(),
        "unit",
        c.getUnit(),
        "levels",
        c.getLevels(),
        "inputMode",
        c.getInputMode(),
        "scaleMin",
        c.getScaleMin(),
        "scaleMax",
        c.getScaleMax(),
        "scaleFrozen",
        c.getScaleFrozen(),
        "source",
        c.getSource());
  }

  public List<Map<String, Object>> criteria(Decision d) {
    return criterionRows(d).stream().map(this::criterion).toList();
  }

  public Map<String, Object> getCriteria(UUID id, UUID cid) {
    return obj("items", criteria(resolver.get(id, cid)));
  }

  private void configure(Criterion c, CriterionRequest r, Decision d, boolean creating) {
    if (creating || r.name() != null) {
      text(r.name(), 40, "name");
      require(
          criterionRows(d).stream()
              .noneMatch(x -> !x.getId().equals(c.getId()) && x.getName().equals(r.name())),
          "name");
      c.setName(r.name());
    }
    if (r.type() != null && !r.type().equals(c.getType())) {
      c.setType(r.type());
      c.setLevels(null);
      c.setInputMode("score");
      c.setScaleMin(null);
      c.setScaleMax(null);
      c.setDirection(null);
    }
    require(
        c.getType() != null && Set.of("numeric", "rating", "boolean").contains(c.getType()),
        "type");
    if (r.unit() != null) {
      require(r.unit().length() <= 20, "unit");
      c.setUnit(r.unit());
    }
    if ("numeric".equals(c.getType())) {
      require(r.levels() == null, "levels");
      require(r.inputMode() == null, "inputMode");
      if (r.direction() != null) c.setDirection(r.direction());
      require(
          Set.of("higher_better", "lower_better")
              .contains(c.getDirection() == null ? "" : c.getDirection()),
          "direction");
      if (r.scaleMin() != null) c.setScaleMin(r.scaleMin());
      if (r.scaleMax() != null) c.setScaleMax(r.scaleMax());
      require(
          c.getScaleMin() == null
              || c.getScaleMax() == null
              || c.getScaleMin().compareTo(c.getScaleMax()) < 0,
          "scaleMin");
    } else if ("rating".equals(c.getType())) {
      require(r.scaleMin() == null, "scaleMin");
      require(r.scaleMax() == null, "scaleMax");
      require(r.direction() == null || r.direction().equals("higher_better"), "direction");
      c.setDirection("higher_better");
      if (r.inputMode() != null) c.setInputMode(r.inputMode());
      require(Set.of("score", "rank").contains(c.getInputMode()), "inputMode");
      if (r.levels() != null) c.setLevels(r.levels());
      if (c.getLevels() == null) c.setLevels(List.of("매우 낮음", "낮음", "보통", "높음", "매우 높음"));
      List<String> l = c.getLevels();
      require(l.size() >= 3 && l.size() <= 7 && new HashSet<>(l).size() == l.size(), "levels");
      l.forEach(x -> text(x, 12, "levels"));
    } else {
      require(r.levels() == null, "levels");
      require(r.inputMode() == null, "inputMode");
      require(r.scaleMin() == null, "scaleMin");
      require(r.scaleMax() == null, "scaleMax");
      require(r.direction() == null || r.direction().equals("true_better"), "direction");
      c.setDirection("true_better");
    }
    if (creating || r.source() != null) c.setSource(source(r.source()));
  }

  public Map<String, Object> addCriterion(UUID id, UUID cid, CriterionRequest r) {
    return createCriterion(edit(id, cid), r, "user");
  }

  public Map<String, Object> createCriterion(Decision d, CriterionRequest r, String origin) {
    resolver.ensureNotFinalized(d);
    if (criterionRows(d).size() >= 7) throw conflict("LIMIT_EXCEEDED");
    Criterion c = new Criterion();
    c.setDecision(d);
    configure(c, r, d, true);
    if ("free_text".equals(origin)) c.setSource(origin);
    c.setPosition(criterionRows(d).stream().mapToInt(Criterion::getPosition).max().orElse(-1) + 1);
    em.persist(c);
    var result = criterion(c);
    if (!"DRAFT".equals(d.getStatus())) {
      var hook = transition.getIfAvailable();
      if (hook == null)
        throw new FlowError(
            HttpStatus.CONFLICT, "INVALID_STATE", "기준 추가 상태 전이 서비스 연결이 필요합니다.", Map.of());
      result.put("statusChanged", hook.onCriterionAdded(d, c.getId()));
    }
    bump(d);
    return result;
  }

  public Map<String, Object> patchCriterion(UUID id, UUID cid, UUID crid, CriterionRequest r) {
    Decision d = edit(id, cid);
    Criterion c = owned(Criterion.class, crid, d);
    String oldMode = c.getInputMode();
    String oldType = c.getType();
    BigDecimal min = c.getScaleMin(), max = c.getScaleMax();
    int count = c.getLevels() == null ? 0 : c.getLevels().size();
    List<OptionValue> old =
        valueRows(d).stream().filter(v -> v.getCriterion().getId().equals(crid)).toList();
    configure(c, r, d, false);
    if ("rating".equals(c.getType())
        && "score".equals(c.getInputMode())
        && Objects.equals(oldType, c.getType())
        && Objects.equals(oldMode, c.getInputMode())) {
      int newCount = c.getLevels().size();
      require(
          old.stream()
              .allMatch(
                  v ->
                      v.getValueNum() == null
                          || (v.getValueNum().compareTo(BigDecimal.ONE) >= 0
                              && v.getValueNum().compareTo(BigDecimal.valueOf(newCount)) <= 0)),
          "levels");
    }
    var result = criterion(c);
    if (Boolean.TRUE.equals(c.getScaleFrozen())
        && (!Objects.equals(min, c.getScaleMin())
            || !Objects.equals(max, c.getScaleMax())
            || count != (c.getLevels() == null ? 0 : c.getLevels().size())))
      result.put("warning", "SCALE_CHANGED_AFTER_ANSWERS");
    if (!Objects.equals(oldMode, c.getInputMode()) || !Objects.equals(oldType, c.getType())) {
      if ("score".equals(oldMode) && "rank".equals(c.getInputMode())) {
        List<List<UUID>> groups = new ArrayList<>();
        var sorted =
            old.stream()
                .filter(v -> v.getValueNum() != null)
                .sorted(Comparator.comparing(OptionValue::getValueNum).reversed())
                .toList();
        BigDecimal prev = null;
        for (var v : sorted) {
          if (prev == null || prev.compareTo(v.getValueNum()) != 0) groups.add(new ArrayList<>());
          groups.getLast().add(v.getOption().getId());
          prev = v.getValueNum();
        }
        result.put("prefilledRanking", groups);
      }
      old.forEach(em::remove);
    }
    bump(d);
    return result;
  }

  public Map<String, Object> deleteCriterion(UUID id, UUID cid, UUID crid) {
    Decision d = edit(id, cid);
    Criterion c = owned(Criterion.class, crid, d);
    int invalid = 0;
    for (Answer a : rows(Answer.class, d))
      if (Boolean.TRUE.equals(a.getIsValid())
          && contains(a.getQuestion().getSpec(), crid.toString())) {
        a.setIsValid(false);
        invalid++;
      }
    em.remove(c);
    em.flush();
    bump(d);
    return obj("invalidatedAnswers", invalid);
  }

  private boolean contains(Object o, String id) {
    if (o instanceof Map<?, ?> m) return m.values().stream().anyMatch(v -> contains(v, id));
    if (o instanceof Collection<?> c) return c.stream().anyMatch(v -> contains(v, id));
    return id.equals(String.valueOf(o));
  }

  private List<OptionValue> valueRows(Decision d) {
    return em.createQuery(
            "select v from OptionValue v where v.option.decision.id=:id", OptionValue.class)
        .setParameter("id", d.getId())
        .getResultList();
  }

  public Map<String, Object> getValues(UUID id, UUID cid) {
    return values(resolver.get(id, cid));
  }

  public Map<String, Object> values(Decision d) {
    List<Object> items = new ArrayList<>(),
        rankings = new ArrayList<>(),
        missing = new ArrayList<>();
    var all = valueRows(d);
    for (Criterion c : criterionRows(d)) {
      Map<UUID, OptionValue> vs =
          all.stream()
              .filter(v -> v.getCriterion().getId().equals(c.getId()))
              .collect(Collectors.toMap(v -> v.getOption().getId(), Function.identity()));
      if ("rank".equals(c.getInputMode())) {
        TreeMap<Short, List<UUID>> groups = new TreeMap<>();
        for (Option o : optionRows(d)) {
          var v = vs.get(o.getId());
          if (v == null || v.getValueRank() == null)
            missing.add(obj("optionId", o.getId(), "criterionId", c.getId()));
          else groups.computeIfAbsent(v.getValueRank(), x -> new ArrayList<>()).add(o.getId());
        }
        rankings.add(obj("criterionId", c.getId(), "order", new ArrayList<>(groups.values())));
      } else
        for (Option o : optionRows(d)) {
          var v = vs.get(o.getId());
          Object value =
              v == null ? null : "boolean".equals(c.getType()) ? v.getValueBool() : v.getValueNum();
          if (value == null) missing.add(obj("optionId", o.getId(), "criterionId", c.getId()));
          else items.add(obj("optionId", o.getId(), "criterionId", c.getId(), "value", value));
        }
    }
    return obj("items", items, "rankings", rankings, "missing", missing);
  }

  private OptionValue value(Option o, Criterion c) {
    OptionValue.Key key = new OptionValue.Key();
    key.option = o.getId();
    key.criterion = c.getId();
    OptionValue v = em.find(OptionValue.class, key);
    if (v == null) {
      v = new OptionValue();
      v.setOption(o);
      v.setCriterion(c);
      em.persist(v);
    }
    return v;
  }

  public Map<String, Object> putValues(UUID id, UUID cid, Values r) {
    Decision d = edit(id, cid);
    Set<String> seen = new HashSet<>();
    if (r.items() != null)
      for (var x : r.items()) {
        require(x != null && x.optionId() != null && x.criterionId() != null, "value");
        require(seen.add(x.optionId() + "/" + x.criterionId()), "items");
        Option o = owned(Option.class, x.optionId(), d);
        Criterion c = owned(Criterion.class, x.criterionId(), d);
        require(!"rank".equals(c.getInputMode()), "inputMode");
        if (x.value() == null || x.value().isNull()) {
          OptionValue.Key key = new OptionValue.Key();
          key.option = o.getId();
          key.criterion = c.getId();
          OptionValue existing = em.find(OptionValue.class, key);
          if (existing != null) em.remove(existing);
          continue;
        }
        OptionValue v = value(o, c);
        if ("boolean".equals(c.getType())) {
          require(x.value().isBoolean(), "value");
          v.setValueBool(x.value().booleanValue());
          v.setValueNum(null);
        } else {
          require(x.value().isNumber(), "value");
          BigDecimal n = x.value().decimalValue();
          if ("rating".equals(c.getType()))
            require(
                n.stripTrailingZeros().scale() <= 0
                    && n.compareTo(BigDecimal.ONE) >= 0
                    && n.compareTo(BigDecimal.valueOf(c.getLevels().size())) <= 0,
                "value");
          v.setValueNum(n);
          v.setValueBool(null);
        }
        v.setUpdatedAt(Instant.now());
      }
    Set<UUID> ranked = new HashSet<>();
    if (r.rankings() != null)
      for (var x : r.rankings()) {
        require(
            x != null
                && x.criterionId() != null
                && x.order() != null
                && ranked.add(x.criterionId()),
            "rankings");
        Criterion c = owned(Criterion.class, x.criterionId(), d);
        require("rank".equals(c.getInputMode()), "inputMode");
        if (x.order().isEmpty()) {
          for (OptionValue v : valueRows(d))
            if (v.getCriterion().getId().equals(c.getId())) em.remove(v);
          continue;
        }
        List<UUID> flat = new ArrayList<>();
        for (var group : x.order()) {
          require(group != null && !group.isEmpty(), "rankings");
          flat.addAll(group);
        }
        Set<UUID> expected = optionRows(d).stream().map(Option::getId).collect(Collectors.toSet());
        if (flat.size() != expected.size() || !new HashSet<>(flat).equals(expected))
          throw new FlowError(
              HttpStatus.BAD_REQUEST,
              "VALIDATION_ERROR",
              "모든 선택지를 정확히 한 번씩 포함해 주세요.",
              Map.of("criterionId", c.getId()));
        short rank = 1;
        for (var group : x.order()) {
          for (UUID oid : group) {
            var v = value(owned(Option.class, oid, d), c);
            v.setValueRank(rank);
            v.setValueNum(null);
            v.setValueBool(null);
            v.setUpdatedAt(Instant.now());
          }
          rank++;
        }
      }
    em.flush();
    bump(d);
    return values(d);
  }

  public Map<String, Object> computeReadiness(Decision d) {
    List<String> missing = new ArrayList<>();
    if (optionRows(d).size() < 2) missing.add("OPTIONS_LT_2");
    if (criterionRows(d).size() < 2) missing.add("CRITERIA_LT_2");
    var v = values(d);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> gaps = (List<Map<String, Object>>) v.get("missing");
    for (var c : criterionRows(d))
      if (gaps.stream().anyMatch(x -> x.get("criterionId").equals(c.getId()))) {
        String code = "rank".equals(c.getInputMode()) ? "RANKING_INCOMPLETE" : "VALUES_INCOMPLETE";
        if (!missing.contains(code)) missing.add(code);
      }
    return obj("canStartQuestions", missing.isEmpty(), "missing", missing);
  }

  public Map<UUID, List<String>> computeExclusions(Decision d) {
    Map<UUID, List<String>> result = new LinkedHashMap<>();
    var vs = valueRows(d);
    for (Option o : optionRows(d)) {
      List<String> reasons = new ArrayList<>();
      for (Condition c : rows(Condition.class, d)) {
        if ("required_tag".equals(c.getKind())) {
          ConditionSatisfaction.Key k = new ConditionSatisfaction.Key();
          k.condition = c.getId();
          k.option = o.getId();
          var sat = em.find(ConditionSatisfaction.class, k);
          if (sat == null || !Boolean.TRUE.equals(sat.getSatisfied()))
            reasons.add(c.getLabel() + " 미충족");
        } else {
          var v =
              vs.stream()
                  .filter(
                      x ->
                          x.getOption().getId().equals(o.getId())
                              && x.getCriterion().getId().equals(c.getCriterion().getId()))
                  .findFirst()
                  .orElse(null);
          BigDecimal n = v == null ? null : v.getValueNum();
          if (n == null && v != null && v.getValueBool() != null)
            n = v.getValueBool() ? BigDecimal.ONE : BigDecimal.ZERO;
          if (n != null) {
            int cmp = n.compareTo(c.getValue());
            boolean ok =
                switch (c.getOperator()) {
                  case "<=" -> cmp <= 0;
                  case ">=" -> cmp >= 0;
                  default -> cmp == 0;
                };
            if (!ok)
              reasons.add(
                  c.getCriterion().getName() + " " + c.getOperator() + " " + c.getValue() + " 미충족");
          }
        }
      }
      result.put(o.getId(), reasons);
    }
    return result;
  }

  public Map<String, Object> getConditions(UUID id, UUID cid) {
    return conditions(resolver.get(id, cid));
  }

  public Map<String, Object> conditions(Decision d) {
    List<Object> items = new ArrayList<>();
    for (Condition c : rows(Condition.class, d)) {
      if ("threshold".equals(c.getKind()))
        items.add(
            obj(
                "id",
                c.getId(),
                "kind",
                c.getKind(),
                "criterionId",
                c.getCriterion().getId(),
                "operator",
                c.getOperator(),
                "value",
                c.getValue(),
                "source",
                c.getSource()));
      else {
        var satisfied =
            em.createQuery(
                    "select s.option.id from ConditionSatisfaction s where s.condition.id=:id and s.satisfied=true",
                    UUID.class)
                .setParameter("id", c.getId())
                .getResultList();
        items.add(
            obj(
                "id",
                c.getId(),
                "kind",
                c.getKind(),
                "label",
                c.getLabel(),
                "satisfiedBy",
                satisfied,
                "source",
                c.getSource()));
      }
    }
    return obj("items", items);
  }

  public Condition appendCondition(Decision d, ConditionRequest r, String origin) {
    resolver.ensureNotFinalized(d);
    require(
        r != null && r.kind() != null && Set.of("threshold", "required_tag").contains(r.kind()),
        "kind");
    Condition c = new Condition();
    c.setDecision(d);
    c.setKind(r.kind());
    c.setSource("free_text".equals(origin) ? origin : source(r.source()));
    if ("threshold".equals(r.kind())) {
      require(r.criterionId() != null, "criterionId");
      require(r.operator() != null && Set.of("<=", ">=", "==").contains(r.operator()), "operator");
      require(r.value() != null, "value");
      Criterion criterion = owned(Criterion.class, r.criterionId(), d);
      require(!"rank".equals(criterion.getInputMode()), "criterionId");
      c.setCriterion(criterion);
      c.setOperator(r.operator());
      c.setValue(r.value());
      em.persist(c);
    } else {
      text(r.label(), 60, "label");
      c.setLabel(r.label());
      require(
          r.satisfiedBy() != null
              && new HashSet<>(r.satisfiedBy()).size() == r.satisfiedBy().size(),
          "satisfiedBy");
      em.persist(c);
      for (UUID oid : r.satisfiedBy()) {
        require(oid != null, "satisfiedBy");
        var s = new ConditionSatisfaction();
        s.setCondition(c);
        s.setOption(owned(Option.class, oid, d));
        s.setSatisfied(true);
        em.persist(s);
      }
    }
    bump(d);
    return c;
  }

  public Map<String, Object> putConditions(UUID id, UUID cid, Conditions r) {
    Decision d = edit(id, cid);
    rows(Condition.class, d).forEach(em::remove);
    em.flush();
    int version = d.getVersion();
    for (var x : r.items()) appendCondition(d, x, "user");
    d.setVersion(version);
    bump(d);
    em.flush();
    var result = conditions(d);
    var exclusions = computeExclusions(d);
    result.put(
        "excludedOptions",
        exclusions.entrySet().stream()
            .filter(e -> !e.getValue().isEmpty())
            .map(e -> obj("optionId", e.getKey(), "reasons", e.getValue()))
            .toList());
    result.put(
        "warning",
        !exclusions.isEmpty() && exclusions.values().stream().allMatch(x -> !x.isEmpty())
            ? "ALL_OPTIONS_EXCLUDED"
            : null);
    return result;
  }

  public Map<String, Object> duplicate(UUID id, UUID cid, Duplicate request) {
    Decision old = resolver.get(id, cid);
    String title =
        request == null || request.title() == null ? old.getTitle() + " (복사본)" : request.title();
    text(title, 100, "title");
    Decision d = new Decision();
    d.setTitle(title);
    d.setDescription(old.getDescription());
    d.setCategory(old.getCategory());
    d.setClientId(cid);
    em.persist(d);
    Map<UUID, UUID> options = new HashMap<>(), criteria = new HashMap<>();
    for (Option o : optionRows(old)) {
      var created = createOption(d, new OptionRequest(o.getName(), o.getDescription(), o.getUrl()));
      options.put(o.getId(), (UUID) created.get("id"));
    }
    for (Criterion c : criterionRows(old)) {
      Criterion clone = new Criterion();
      clone.setDecision(d);
      clone.setName(c.getName());
      clone.setType(c.getType());
      clone.setDirection(c.getDirection());
      clone.setUnit(c.getUnit());
      clone.setLevels(c.getLevels() == null ? null : new ArrayList<>(c.getLevels()));
      clone.setInputMode(c.getInputMode());
      clone.setScaleMin(c.getScaleMin());
      clone.setScaleMax(c.getScaleMax());
      clone.setScaleFrozen(false);
      clone.setPosition(c.getPosition());
      clone.setSource(c.getSource());
      em.persist(clone);
      criteria.put(c.getId(), clone.getId());
    }
    for (OptionValue v : valueRows(old)) {
      var n =
          value(
              em.find(Option.class, options.get(v.getOption().getId())),
              em.find(Criterion.class, criteria.get(v.getCriterion().getId())));
      n.setValueNum(v.getValueNum());
      n.setValueBool(v.getValueBool());
      n.setValueRank(v.getValueRank());
    }
    for (Condition c : rows(Condition.class, old)) {
      List<UUID> satisfied =
          em
              .createQuery(
                  "select s.option.id from ConditionSatisfaction s where s.condition.id=:id and s.satisfied=true",
                  UUID.class)
              .setParameter("id", c.getId())
              .getResultList()
              .stream()
              .map(options::get)
              .toList();
      appendCondition(
          d,
          new ConditionRequest(
              c.getKind(),
              c.getCriterion() == null ? null : criteria.get(c.getCriterion().getId()),
              c.getOperator(),
              c.getValue(),
              c.getLabel(),
              satisfied,
              "free_text".equals(c.getSource()) ? null : c.getSource()),
          c.getSource());
    }
    em.flush();
    return detail(d);
  }

  public Map<String, Object> recommendCriteria(UUID id, UUID cid) {
    Decision d = resolver.get(id, cid);
    resolver.ensureNotFinalized(d);
    var ai = recommendations.getIfAvailable();
    if (ai != null)
      try {
        return ai.criteria(d);
      } catch (RuntimeException ignored) {
        /* Spec requires template fallback. */
      }
    List<Map<String, Object>> items =
        switch (d.getCategory()) {
          case "purchase" ->
              List.of(
                  numeric("가격", "만 원"),
                  rating("성능", false),
                  rating("디자인", false),
                  numeric("무게/크기", null),
                  rating("내구성·A/S", false));
          case "career" ->
              List.of(
                  rating("흥미", true),
                  rating("적성", false, "적합도"),
                  rating("취업·진로 전망", false),
                  numeric("통학 시간", "분"),
                  numeric("학비", "만 원"));
          case "study" ->
              List.of(
                  rating("효과 기대", false),
                  numeric("비용", "만 원"),
                  numeric("시간 부담", "시간/주"),
                  rating("난이도 적합", false, "적합도"),
                  numeric("이동 거리", "분"));
          case "activity" ->
              List.of(
                  rating("흥미", false, "끌림"),
                  rating("배울 수 있는 것", false),
                  numeric("시간 부담", "시간/주"),
                  rating("함께하는 사람", false, "만족도"),
                  rating("포트폴리오 도움", false));
          case "living" ->
              List.of(
                  numeric("비용·수입", "만 원"),
                  numeric("거리", "분"),
                  rating("분위기·환경", false, "만족도"),
                  rating("시간 유연성", false),
                  rating("안전", false));
          default ->
              List.of(
                  rating("만족도 기대", false, "만족도"),
                  numeric("비용", null),
                  numeric("시간", null),
                  rating("위험 부담", false));
        };
    return obj("items", items, "source", "template");
  }

  private Map<String, Object> numeric(String name, String unit) {
    return obj(
        "name",
        name,
        "type",
        "numeric",
        "direction",
        "lower_better",
        "unit",
        unit,
        "reason",
        "카테고리 기본 기준");
  }

  private static final Map<String, List<String>> RATING_PRESETS =
      Map.of(
          "수준", List.of("매우 낮음", "낮음", "보통", "높음", "매우 높음"),
          "적합도", List.of("안 맞음", "그럭저럭", "잘 맞음", "매우 잘 맞음"),
          "끌림", List.of("별로", "그럭저럭", "끌림", "매우 끌림"),
          "만족도", List.of("매우 불만", "불만", "보통", "만족", "매우 만족"));

  private Map<String, Object> rating(String name, boolean rank) {
    return rating(name, rank, "수준");
  }

  private Map<String, Object> rating(String name, boolean rank, String preset) {
    return obj(
        "name",
        name,
        "type",
        "rating",
        "direction",
        "higher_better",
        "inputMode",
        rank ? "rank" : "score",
        "levels",
        rank ? null : RATING_PRESETS.get(preset),
        "reason",
        "카테고리 기본 기준");
  }

  public Map<String, Object> recommendOptions(UUID id, UUID cid, int count) {
    Decision d = resolver.get(id, cid);
    resolver.ensureNotFinalized(d);
    var ai = recommendations.getIfAvailable();
    if (ai == null)
      throw new FlowError(
          HttpStatus.SERVICE_UNAVAILABLE, "AI_UNAVAILABLE", "선택지 추천은 아직 연결되지 않았습니다.", Map.of());
    return ai.options(d, count);
  }
}

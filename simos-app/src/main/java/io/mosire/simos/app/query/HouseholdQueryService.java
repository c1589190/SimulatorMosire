package io.mosire.simos.app.query;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.household.HouseholdPositionResolver;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarAge;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.calendar.CalendarDate;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdPopulationEvent;
import io.mosire.simos.social.api.population.HouseholdVitalRate;
import io.mosire.simos.social.api.population.PopulationEventType;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 家户聚合查询服务（D1 施工契约 §2）：按维度/指标把 Social 家户、Economy 行、Actor 账户与 Unit 容纳关系聚合成 JSON 友好视图。
 *
 * <p>★★ <b>纯只读</b>：不写 state、不落 revision、不依赖任何工具上下文；同样的 {@code (state, mapId, spec, visibility)}
 * 必得同样的输出。
 *
 * <p>★★ <b>权威分工</b>：人口永远从 {@link SocialData#households()} 的成员份额现算（绝不用 {@code
 * HouseholdEconomy.population} 替代）；经济行只提供阶层/生产方式/参与率/债务；商品与货币账户在 actor 切片；位置由 {@link
 * HouseholdPositionResolver} 解析（{@code UNIT} 家户按单位当刻有效位置算）。
 *
 * <p>★★ <b>缺数据不填 0</b>：不可算的指标既不写键，也在行 {@code unavailable} 与顶层 {@code unavailable} 具名； 能算出来的 0
 * 照发。坏数据（家户成员批次不存在、劳动/需求系数缺失、生死率缺键）由 {@link SocialData} 具名抛出， 本类不吞——工具层折 {@code BAD_REQUEST}。
 *
 * <p>★ <b>Visibility 的 D1 增补</b>：契约 §2 的接口没有 actor 面的判据，而 goods/money 必须区分 {@code actor-denied} 与
 * {@code actor-account-missing} ⇒ 本类给接口加了一个 <b>fail-closed 的 default 方法</b> {@link
 * Visibility#actorAllowed()}（默认 false）；{@link Visibility#all()} 覆写为 true。工具层按调用者在这个命名空间上 是否
 * unrestricted 覆写。这是纯增量，不改变契约里的任何签名。
 */
public final class HouseholdQueryService {

  private static final String ACTOR_DENIED = "actor-denied";
  private static final String ACTOR_ACCOUNT_MISSING = "actor-account-missing";
  private static final String ECONOMY_ROW_MISSING = "economy-row-missing";
  private static final String HOUSEHOLD_LEVEL_METRIC_SPLIT = "household-level-metric-split";
  private static final String POPULATION_ZERO = "population-zero";
  private static final String WINDOW_ZERO = "window-zero";
  private static final String HEX_HIDDEN_BY_SCOPE = "hex-hidden-by-scope";
  private static final String UNIT_UNRESOLVED = "unit-unresolved";
  private static final String MEMBER_FILTER_PARTIAL = "member-filter-partial";
  private static final String UNCLASSIFIED = "unclassified";
  private static final String NO_PRODUCTION_MODE = "none";

  /** 成员级维度。 */
  private static final Set<Dimension> MEMBER_DIMENSIONS =
      EnumSet.of(Dimension.AGE_BRACKET, Dimension.SEX, Dimension.RESIDENCE);

  private final CalendarService calendarService;

  public HouseholdQueryService(CalendarService calendarService) {
    this.calendarService = Objects.requireNonNull(calendarService, "calendarService");
  }

  /** 家户读面的可见性判据（D1 契约 §4）。实现只回答"这只家户/这一格看得见吗"；{@code ALL} 的 GM 判定在工具层做 （服务不认工具名、不认身份字符串）。 */
  public interface Visibility {

    /** 某家户是否可见（位置、有效格与容纳单位一并给出）。 */
    boolean householdAllowed(
        HouseholdId id,
        HouseholdLocation location,
        Optional<HexCoord> effectiveHex,
        Set<UnitId> containingUnits);

    /** 某格的人口面是否可见。 */
    boolean hexAllowed(HexCoord hex);

    /**
     * actor 面（家户账户）是否可见。D1 契约的接口没有这一维，但 {@code goods}/{@code money} 必须区分 {@code actor-denied} 与
     * {@code actor-account-missing} ⇒ 这里加一个<b>纯增量</b>的 default：缺省 fail-closed（不可见），GM/{@link
     * #all()} 覆写为 true。
     */
    default boolean actorAllowed() {
      return false;
    }

    /** 全可见（仅 GM/测试用；工具层只有 GM 的 social 面 unrestricted 时才允许 scope=ALL）。 */
    static Visibility all() {
      return new Visibility() {
        @Override
        public boolean householdAllowed(
            HouseholdId id,
            HouseholdLocation location,
            Optional<HexCoord> effectiveHex,
            Set<UnitId> containingUnits) {
          return true;
        }

        @Override
        public boolean hexAllowed(HexCoord hex) {
          return true;
        }

        @Override
        public boolean actorAllowed() {
          return true;
        }
      };
    }

    /** 全不可见（fail-closed 起点；{@link #all()} 的逆）。 */
    static Visibility none() {
      return new Visibility() {
        @Override
        public boolean householdAllowed(
            HouseholdId id,
            HouseholdLocation location,
            Optional<HexCoord> effectiveHex,
            Set<UnitId> containingUnits) {
          return false;
        }

        @Override
        public boolean hexAllowed(HexCoord hex) {
          return false;
        }
      };
    }
  }

  public record Spec(
      Scope scope,
      Filters filters,
      List<Dimension> groupBy,
      Set<Metric> metrics,
      Window window,
      RateMode rateMode,
      Include include) {}

  public enum ScopeKind {
    HEX,
    UNIT,
    HOUSEHOLD,
    VISIBLE,
    ALL
  }

  public record Scope(ScopeKind kind, Integer q, Integer r, String unitId, String householdId) {}

  public record Filters(
      Set<AgeBracket> ageBrackets,
      Set<Sex> sexes,
      Set<String> strata,
      Set<String> productionModes,
      Set<ResidenceKind> residences,
      Set<UnitId> unitIds,
      Set<HouseholdId> householdIds,
      Set<HexCoord> hexes,
      Boolean hasEconomyRow,
      boolean aliveOnly) {}

  public enum Dimension {
    AGE_BRACKET,
    SEX,
    STRATUM,
    PRODUCTION_MODE,
    RESIDENCE,
    UNIT,
    HEX,
    HOUSEHOLD
  }

  public enum Metric {
    POPULATION,
    HOUSEHOLD_COUNT,
    LOT_COUNT,
    BIRTHS,
    DEATHS,
    BIRTH_RATE_PER_MILLE,
    DEATH_RATE_PER_MILLE,
    LABOR_MILLI,
    NATURAL_NEEDS,
    PARTICIPATION_PER_MILLE,
    GOODS,
    MONEY,
    DEBT_PRINCIPAL,
    CREDIT_PRINCIPAL
  }

  public record Window(long fromTick, long toTick) {}

  public enum RateMode {
    CONFIGURED,
    OBSERVED,
    BOTH
  }

  public record Include(boolean members, boolean economy, boolean units) {}

  /**
   * 执行一次聚合查询。
   *
   * @param state 被查状态（服务只读它）
   * @param mapId 地图 id（D1 的判据按 Social/Unit 身份工作；参数保留给工具/GUI 同形调用）
   * @param spec 查询形状（缺省在工具层填好；本类对 null 字段做 fail-closed 归一）
   * @param visibility 逐家户/逐格可见性
   * @throws IllegalArgumentException 坏 spec（未来窗口、include 非 false、scope 不自洽等）或 Social 坏数据
   */
  public Map<String, Object> query(
      SimulationState state, String mapId, Spec spec, Visibility visibility) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(mapId, "mapId");
    Objects.requireNonNull(spec, "spec");
    Objects.requireNonNull(visibility, "visibility");

    SocialData social = ToolSupport.socialData(state);
    UnitState units = ToolSupport.unitState(state);
    EconomyData economy = ToolSupport.economyData(state);
    SimosTimestamp at = state.meta().timestamp();
    long nowTick = at.tick();
    CalendarClock clock = calendarService.clock();

    Scope scope =
        spec.scope() == null ? new Scope(ScopeKind.VISIBLE, null, null, null, null) : spec.scope();
    if (scope.kind() == null) {
      throw new IllegalArgumentException("scope.kind 不得为 null");
    }
    Filters filters = normalizeFilters(spec.filters());
    List<Dimension> groupBy = normalizeGroupBy(spec.groupBy());
    Set<Metric> metrics = normalizeMetrics(spec.metrics());
    RateMode rateMode = spec.rateMode() == null ? RateMode.BOTH : spec.rateMode();
    Include include = spec.include() == null ? new Include(false, false, false) : spec.include();
    if (include.members() || include.economy() || include.units()) {
      throw new IllegalArgumentException("include.* D1 暂不支持（D1.1 分页/明细）");
    }
    Window window = resolveWindow(spec.window(), nowTick, clock);
    long windowTicks = window.toTick() - window.fromTick();

    // actor 面：只有 metrics 真要 goods/money 且可见时才读账户（不可见 ⇒ actor-denied，连切片都不碰）。
    boolean needActor = metrics.contains(Metric.GOODS) || metrics.contains(Metric.MONEY);
    boolean actorAllowed = needActor && visibility.actorAllowed();
    ActorData actor = actorAllowed ? ApiViews.actorData(state) : null;

    Map<HouseholdId, Set<UnitId>> unitsByHousehold = unitsByHousehold(units);
    Map<HouseholdId, List<HouseholdPopulationEvent>> vitalEvents = vitalEvents(social, window);
    Map<HouseholdId, Map<String, Long>> debtPrincipal =
        metrics.contains(Metric.DEBT_PRINCIPAL) ? indexDebts(economy, true) : Map.of();
    Map<HouseholdId, Map<String, Long>> creditPrincipal =
        metrics.contains(Metric.CREDIT_PRINCIPAL) ? indexDebts(economy, false) : Map.of();

    boolean needBirthConfigured =
        rateMode != RateMode.OBSERVED && metrics.contains(Metric.BIRTH_RATE_PER_MILLE);
    boolean needDeathConfigured =
        rateMode != RateMode.OBSERVED && metrics.contains(Metric.DEATH_RATE_PER_MILLE);

    Selection selection =
        select(
            scope,
            filters,
            social,
            economy,
            units,
            unitsByHousehold,
            vitalEvents,
            visibility,
            at,
            clock,
            nowTick);
    List<Dimension> memberDims = groupBy.stream().filter(MEMBER_DIMENSIONS::contains).toList();

    Map<RowKey, Bucket> buckets = new LinkedHashMap<>();
    if (groupBy.isEmpty()) {
      buckets.put(new RowKey(List.of()), new Bucket());
    }
    Map<HouseholdId, HouseholdResource> resources = new LinkedHashMap<>();
    Map<RateKey, HouseholdVitalRate> rateCache = new HashMap<>();
    for (SelectedHousehold household : selection.households()) {
      EnumMap<Dimension, String> householdDims = householdDimensionValues(household);
      Set<List<String>> memberCombos = new LinkedHashSet<>();
      for (MemberHit hit : household.members()) {
        RowKey key = memberKey(householdDims, hit, memberDims, groupBy);
        Bucket bucket = bucket(buckets, key);
        addMemberMetrics(
            bucket, household, hit, needBirthConfigured, needDeathConfigured, rateCache, social);
        addHexIssues(bucket, household, groupBy);
        if (!memberDims.isEmpty()) {
          memberCombos.add(memberCombo(hit, memberDims));
        }
      }
      for (HouseholdPopulationEvent event : household.vitalEvents()) {
        if (!eventPassesFilters(event, household, filters, social)) {
          continue;
        }
        RowKey key = eventKey(householdDims, household, event, memberDims, groupBy, social);
        Bucket bucket = bucket(buckets, key);
        addEventCount(bucket, event);
        addHexIssues(bucket, household, groupBy);
        if (!memberDims.isEmpty()) {
          memberCombos.add(eventCombo(household, event, memberDims, social));
        }
      }
      HouseholdResource resource =
          computeResource(
              household,
              metrics,
              social,
              actor,
              actorAllowed,
              debtPrincipal,
              creditPrincipal,
              clock,
              nowTick);
      resources.put(household.household().id(), resource);
      if (memberDims.isEmpty()) {
        RowKey key = new RowKey(householdValues(householdDims, groupBy));
        Bucket bucket = bucket(buckets, key);
        bucket.households.add(household.household().id());
        addHexIssues(bucket, household, groupBy);
        placeResource(bucket, household, resource, metrics, actorAllowed);
      } else if (memberCombos.size() == 1) {
        RowKey key = comboKey(householdDims, memberCombos.iterator().next(), memberDims, groupBy);
        Bucket bucket = bucket(buckets, key);
        addHexIssues(bucket, household, groupBy);
        placeResource(bucket, household, resource, metrics, actorAllowed);
      } else if (memberCombos.size() > 1) {
        for (List<String> combo : memberCombos) {
          RowKey key = comboKey(householdDims, combo, memberDims, groupBy);
          Bucket bucket = bucket(buckets, key);
          addHexIssues(bucket, household, groupBy);
          bucket.splitHouseholds.add(household.household().id());
          if (household.memberFilterPartial()) {
            bucket.memberFilterPartial.add(household.household().id());
          }
          bucket.contributors.add(household.household().id());
        }
      }
    }

    UnavailabilityLog log = new UnavailabilityLog();
    List<HouseholdId> selectedIds =
        selection.households().stream().map(entry -> entry.household().id()).toList();
    List<RowKey> keys = new ArrayList<>(buckets.keySet());
    keys.sort(rowKeyComparator(groupBy));
    List<Map<String, Object>> rows = new ArrayList<>(keys.size());
    for (RowKey key : keys) {
      Bucket bucket = buckets.get(key);
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("key", keyView(key, groupBy));
      row.putAll(
          metricView(
              bucket,
              metrics,
              rateMode,
              windowTicks,
              actorAllowed,
              log,
              selectedIds,
              new ArrayList<>(bucket.dimensionUnavailable)));
      rows.add(row);
    }

    Bucket totals = new Bucket();
    totals.contributors.addAll(selectedIds);
    totals.households.addAll(selectedIds);
    for (SelectedHousehold household : selection.households()) {
      for (MemberHit hit : household.members()) {
        addMemberMetrics(
            totals, household, hit, needBirthConfigured, needDeathConfigured, rateCache, social);
      }
      for (HouseholdPopulationEvent event : household.vitalEvents()) {
        if (!eventPassesFilters(event, household, filters, social)) {
          continue;
        }
        addEventCount(totals, event);
      }
      placeResource(
          totals, household, resources.get(household.household().id()), metrics, actorAllowed);
    }
    Map<String, Object> totalsView =
        metricView(
            totals, metrics, rateMode, windowTicks, actorAllowed, log, selectedIds, List.of());

    Map<String, Object> scopeView = new LinkedHashMap<>();
    scopeView.put("kind", scope.kind().name());
    scopeView.put("source", scopeSource(scope.kind()));
    scopeView.put("households", selection.households().size());
    scopeView.put("hiddenHexHouseholds", selection.hiddenHexHouseholds());

    Map<String, Object> atView = new LinkedHashMap<>();
    atView.put("tick", nowTick);
    Map<String, Object> windowView = new LinkedHashMap<>();
    windowView.put("fromTick", window.fromTick());
    windowView.put("toTick", window.toTick());

    Map<String, Object> view = new LinkedHashMap<>();
    view.put("scope", scopeView);
    view.put("at", atView);
    view.put("window", windowView);
    view.put("rateMode", rateMode.name());
    view.put("rows", rows);
    view.put("totals", totalsView);
    view.put("unavailable", log.view());
    view.put("notes", List.of("lotCount 按桶内 PeopleLotId 去重；跨户共享 lot 可能出现在多个桶"));
    return view;
  }

  // ── 选择（scope + visibility + filters）─────────────────────────────────────────────

  private record Selection(List<SelectedHousehold> households, long hiddenHexHouseholds) {}

  private record SelectedHousehold(
      Household household,
      Optional<HexCoord> effectiveHex,
      Set<UnitId> containingUnits,
      HouseholdEconomy economyRow,
      String stratum,
      String productionMode,
      List<MemberHit> members,
      List<HouseholdPopulationEvent> vitalEvents,
      boolean unitUnresolved,
      boolean hexHidden,
      boolean memberFilterPartial) {

    Optional<UnitId> containingUnit() {
      return containingUnits.stream().sorted(Comparator.comparing(UnitId::value)).findFirst();
    }

    String hexValue() {
      if (effectiveHex.isEmpty() || hexHidden) {
        return null;
      }
      return effectiveHex.get().toString();
    }
  }

  private record MemberHit(
      PeopleLotId lot,
      long share,
      AgeBracket bracket,
      Sex sex,
      ResidenceKind residence,
      long ageYears) {}

  private Selection select(
      Scope scope,
      Filters filters,
      SocialData social,
      EconomyData economy,
      UnitState units,
      Map<HouseholdId, Set<UnitId>> unitsByHousehold,
      Map<HouseholdId, List<HouseholdPopulationEvent>> vitalEvents,
      Visibility visibility,
      SimosTimestamp at,
      CalendarClock clock,
      long nowTick) {
    List<Household> candidates =
        switch (scope.kind()) {
          case ALL, VISIBLE -> sortedHouseholds(social.households().values());
          case HEX ->
              sortedHouseholds(
                  social.householdsAt(
                      new HexCoord(
                          requireInt(scope.q(), "scope.q"), requireInt(scope.r(), "scope.r"))));
          case UNIT ->
              sortedHouseholds(
                  social.householdsInUnit(requireText(scope.unitId(), "scope.unitId")));
          case HOUSEHOLD -> {
            HouseholdId id =
                HouseholdId.parse(requireText(scope.householdId(), "scope.householdId"));
            Household household = social.households().get(id);
            if (household == null) {
              throw new IllegalArgumentException("家户不存在: " + id.value());
            }
            yield List.of(household);
          }
        };

    boolean memberFiltered =
        !filters.ageBrackets().isEmpty()
            || !filters.sexes().isEmpty()
            || !filters.residences().isEmpty();
    List<SelectedHousehold> selected = new ArrayList<>();
    long hiddenHexHouseholds = 0L;
    for (Household household : candidates) {
      Optional<HexCoord> effectiveHex =
          HouseholdPositionResolver.effectiveHex(household.id(), social, units, at);
      Set<UnitId> containingUnits = unitsByHousehold.getOrDefault(household.id(), Set.of());
      if (!visibility.householdAllowed(
          household.id(), household.location(), effectiveHex, containingUnits)) {
        continue;
      }
      HouseholdEconomy economyRow = economy.classes().get(household.id());
      String stratum = economyRow == null ? UNCLASSIFIED : economyRow.view().stratum().value();
      String productionMode = productionModeOf(economy, household.id());
      if (!householdFiltersPass(
          filters,
          household,
          effectiveHex,
          containingUnits,
          economyRow != null,
          stratum,
          productionMode)) {
        continue;
      }
      MemberHits memberHits = memberHits(household, social, clock, nowTick, filters);
      List<MemberHit> members = memberHits.hits();
      if (memberFiltered && members.isEmpty()) {
        continue;
      }
      if (filters.aliveOnly() && social.householdPopulation(household.id()) <= 0L) {
        continue;
      }
      boolean hexHidden = effectiveHex.isPresent() && !visibility.hexAllowed(effectiveHex.get());
      if (hexHidden) {
        hiddenHexHouseholds++;
      }
      selected.add(
          new SelectedHousehold(
              household,
              effectiveHex,
              containingUnits,
              economyRow,
              stratum,
              productionMode,
              members,
              vitalEvents.getOrDefault(household.id(), List.of()),
              effectiveHex.isEmpty(),
              hexHidden,
              memberHits.partial()));
    }
    return new Selection(List.copyOf(selected), hiddenHexHouseholds);
  }

  private static boolean householdFiltersPass(
      Filters filters,
      Household household,
      Optional<HexCoord> effectiveHex,
      Set<UnitId> containingUnits,
      boolean hasEconomyRow,
      String stratum,
      String productionMode) {
    if (!filters.householdIds().isEmpty() && !filters.householdIds().contains(household.id())) {
      return false;
    }
    if (!filters.unitIds().isEmpty() && Collections.disjoint(filters.unitIds(), containingUnits)) {
      return false;
    }
    if (!filters.hexes().isEmpty()
        && (effectiveHex.isEmpty() || !filters.hexes().contains(effectiveHex.get()))) {
      return false;
    }
    if (filters.hasEconomyRow() != null && filters.hasEconomyRow() != hasEconomyRow) {
      return false;
    }
    if (!filters.strata().isEmpty() && !filters.strata().contains(stratum)) {
      return false;
    }
    if (!filters.productionModes().isEmpty()
        && !filters.productionModes().contains(productionMode)) {
      return false;
    }
    return true;
  }

  private record MemberHits(List<MemberHit> hits, boolean partial) {}

  /**
   * 逐成员计算命中集合；{@code partial=true} 表示这个家户有成员被成员级过滤器挡掉——家户级指标（劳动/需求/账户/参与率/债务） 不能只按命中成员拆，故后续置为
   * {@code member-filter-partial} unavailable（见 §5 的正确性补条）。
   */
  private static MemberHits memberHits(
      Household household, SocialData social, CalendarClock clock, long nowTick, Filters filters) {
    long currentDayNumber = clock.dayNumberOfTick(nowTick);
    List<MemberHit> hits = new ArrayList<>();
    boolean partial = false;
    boolean memberFiltered =
        !filters.ageBrackets().isEmpty()
            || !filters.sexes().isEmpty()
            || !filters.residences().isEmpty();
    for (Map.Entry<PeopleLotId, Long> entry : household.members().entrySet()) {
      PopulationGroup group = social.groups().get(entry.getKey());
      if (group == null) {
        throw new IllegalArgumentException(
            "家户 " + household.id().value() + " 的成员批次不存在: " + entry.getKey().value());
      }
      AgeBracket bracket =
          AgeBracket.of(clock.system(), currentDayNumber, group.ageDaysAt(nowTick));
      Sex sex = group.sex();
      ResidenceKind residence =
          PopulationLots.isUrban(group) ? ResidenceKind.URBAN : ResidenceKind.RURAL;
      boolean matches =
          (filters.ageBrackets().isEmpty() || filters.ageBrackets().contains(bracket))
              && (filters.sexes().isEmpty() || filters.sexes().contains(sex))
              && (filters.residences().isEmpty() || filters.residences().contains(residence));
      if (!matches) {
        if (memberFiltered && entry.getValue() > 0L) {
          partial = true;
        }
        continue;
      }
      long ageDays = group.ageDaysAt(nowTick);
      long ageYears =
          CalendarAge.ageInYears(
              clock.system(), Math.subtractExact(currentDayNumber, ageDays), currentDayNumber);
      hits.add(new MemberHit(entry.getKey(), entry.getValue(), bracket, sex, residence, ageYears));
    }
    return new MemberHits(List.copyOf(hits), partial);
  }

  private static String productionModeOf(EconomyData economy, HouseholdId householdId) {
    HouseholdClassMembership standing = economy.classStandings().get(householdId);
    if (standing == null) {
      return NO_PRODUCTION_MODE;
    }
    ProductionRole position = economy.classPositions().get(standing.currentPositionId());
    return position == null ? NO_PRODUCTION_MODE : position.modeId().value();
  }

  private static Map<HouseholdId, Set<UnitId>> unitsByHousehold(UnitState units) {
    Map<HouseholdId, Set<UnitId>> out = new HashMap<>();
    for (Unit unit : units.units().values()) {
      for (HouseholdId household : unit.households()) {
        out.computeIfAbsent(household, ignored -> new LinkedHashSet<>()).add(unit.id());
      }
    }
    return out;
  }

  private static Map<HouseholdId, List<HouseholdPopulationEvent>> vitalEvents(
      SocialData social, Window window) {
    Map<HouseholdId, List<HouseholdPopulationEvent>> out = new HashMap<>();
    for (HouseholdPopulationEvent event : social.populationEvents().values()) {
      if (event.type() != PopulationEventType.BIRTH && event.type() != PopulationEventType.DEATH) {
        continue;
      }
      if (event.day() < window.fromTick() || event.day() >= window.toTick()) {
        continue;
      }
      out.computeIfAbsent(event.householdId(), ignored -> new ArrayList<>()).add(event);
    }
    return out;
  }

  private static Map<HouseholdId, Map<String, Long>> indexDebts(
      EconomyData economy, boolean debtorDirection) {
    Map<HouseholdId, Map<String, Long>> out = new HashMap<>();
    for (DebtContract contract : economy.debtContracts().values()) {
      HouseholdId owner = debtorDirection ? contract.debtor() : contract.creditor();
      out.computeIfAbsent(owner, ignored -> new TreeMap<>())
          .merge(contract.unit().key(), contract.principal(), Long::sum);
    }
    return out;
  }

  private static List<Household> sortedHouseholds(Collection<Household> households) {
    List<Household> out = new ArrayList<>(households);
    out.sort(Comparator.comparing(household -> household.id().value()));
    return List.copyOf(out);
  }

  // ── 指标累加 ─────────────────────────────────────────────────────────────────────

  private static final class Bucket {
    long population;
    final Set<PeopleLotId> lots = new LinkedHashSet<>();
    final Set<HouseholdId> households = new LinkedHashSet<>();
    final Set<HouseholdId> contributors = new LinkedHashSet<>();
    final Set<HouseholdId> splitHouseholds = new LinkedHashSet<>();
    final Set<HouseholdId> memberFilterPartial = new LinkedHashSet<>();
    final Set<HouseholdId> economyRowMissing = new LinkedHashSet<>();
    final Set<HouseholdId> accountMissing = new LinkedHashSet<>();
    final Set<String> dimensionUnavailable = new TreeSet<>();
    final Map<String, Long> naturalNeeds = new TreeMap<>();
    final Map<String, Long> goods = new TreeMap<>();
    final Map<String, Long> money = new TreeMap<>();
    final Map<String, Long> debtPrincipal = new TreeMap<>();
    final Map<String, Long> creditPrincipal = new TreeMap<>();
    long births;
    long deaths;
    long birthNumerator;
    long deathNumerator;
    long laborMilli;
    long participationWeight;
    long participationWeighted;
  }

  private record RateKey(HouseholdId household, AgeBracket bracket, Sex sex) {}

  private record HouseholdResource(
      Long laborMilli,
      Map<String, Long> naturalNeeds,
      Map<String, Long> goods,
      Map<String, Long> money,
      boolean accountMissing,
      Map<String, Long> debtPrincipal,
      Map<String, Long> creditPrincipal) {}

  private static Bucket bucket(Map<RowKey, Bucket> buckets, RowKey key) {
    return buckets.computeIfAbsent(key, ignored -> new Bucket());
  }

  private static void addMemberMetrics(
      Bucket bucket,
      SelectedHousehold household,
      MemberHit hit,
      boolean needBirthConfigured,
      boolean needDeathConfigured,
      Map<RateKey, HouseholdVitalRate> rateCache,
      SocialData social) {
    bucket.population += hit.share();
    bucket.lots.add(hit.lot());
    bucket.households.add(household.household().id());
    bucket.contributors.add(household.household().id());
    if (needDeathConfigured) {
      HouseholdVitalRate rate =
          vitalRate(social, household.household().id(), hit.bracket(), hit.sex(), rateCache);
      bucket.deathNumerator += hit.share() * rate.deathRatePerMillionPerTick();
    }
    if (needBirthConfigured
        && hit.sex() == Sex.FEMALE
        && hit.ageYears() >= 15L
        && hit.ageYears() < 45L) {
      HouseholdVitalRate rate =
          vitalRate(social, household.household().id(), AgeBracket.ADULT, Sex.FEMALE, rateCache);
      bucket.birthNumerator += hit.share() * rate.birthRatePerMillionPerTick();
    }
  }

  private static HouseholdVitalRate vitalRate(
      SocialData social,
      HouseholdId household,
      AgeBracket bracket,
      Sex sex,
      Map<RateKey, HouseholdVitalRate> rateCache) {
    RateKey key = new RateKey(household, bracket, sex);
    HouseholdVitalRate cached = rateCache.get(key);
    if (cached != null) {
      return cached;
    }
    HouseholdVitalRate rate = social.findVitalRate(household, bracket, sex);
    rateCache.put(key, rate);
    return rate;
  }

  private static void addEventCount(Bucket bucket, HouseholdPopulationEvent event) {
    if (event.type() == PopulationEventType.BIRTH) {
      bucket.births += event.count();
    } else {
      bucket.deaths += event.count();
    }
    bucket.households.add(event.householdId());
    bucket.contributors.add(event.householdId());
  }

  /** 事件是否通过成员级 filters（年龄档/性别/城乡）；不通过则不落桶、不计人数。 */
  private static boolean eventPassesFilters(
      HouseholdPopulationEvent event,
      SelectedHousehold household,
      Filters filters,
      SocialData social) {
    AgeBracket bracket = eventBracket(event);
    if (!filters.ageBrackets().isEmpty() && !filters.ageBrackets().contains(bracket)) {
      return false;
    }
    if (!filters.sexes().isEmpty() && !filters.sexes().contains(event.sex())) {
      return false;
    }
    if (!filters.residences().isEmpty()) {
      String residence = eventResidence(household, event, social);
      if (residence == null) {
        return false;
      }
      ResidenceKind kind;
      try {
        kind = ResidenceKind.valueOf(residence);
      } catch (IllegalArgumentException e) {
        return false;
      }
      if (!filters.residences().contains(kind)) {
        return false;
      }
    }
    return true;
  }

  private static void addHexIssues(
      Bucket bucket, SelectedHousehold household, List<Dimension> groupBy) {
    if (!groupBy.contains(Dimension.HEX) || household.hexValue() != null) {
      return;
    }
    if (household.unitUnresolved()) {
      bucket.dimensionUnavailable.add(UNIT_UNRESOLVED);
    }
    if (household.hexHidden()) {
      bucket.dimensionUnavailable.add(HEX_HIDDEN_BY_SCOPE);
    }
  }

  private static HouseholdResource computeResource(
      SelectedHousehold household,
      Set<Metric> metrics,
      SocialData social,
      ActorData actor,
      boolean actorAllowed,
      Map<HouseholdId, Map<String, Long>> debtPrincipal,
      Map<HouseholdId, Map<String, Long>> creditPrincipal,
      CalendarClock clock,
      long nowTick) {
    HouseholdId id = household.household().id();
    Long laborMilli =
        metrics.contains(Metric.LABOR_MILLI)
            ? social.householdLaborMilli(id, nowTick, clock)
            : null;
    Map<String, Long> naturalNeeds =
        metrics.contains(Metric.NATURAL_NEEDS)
            ? commodityKeyed(social.householdNaturalNeeds(id, nowTick, clock))
            : null;
    boolean needGoods = metrics.contains(Metric.GOODS);
    boolean needMoney = metrics.contains(Metric.MONEY);
    Map<String, Long> goods = null;
    Map<String, Long> money = null;
    boolean accountMissing = false;
    if ((needGoods || needMoney) && actorAllowed) {
      HouseholdInventory inventory = actor.accounts().get(new HouseholdAccountKey(id));
      if (inventory == null) {
        accountMissing = true;
      } else {
        if (needGoods) {
          goods = commodityKeyed(inventory.balances());
        }
        if (needMoney) {
          money = currencyKeyed(inventory.money());
        }
      }
    }
    Map<String, Long> debts =
        metrics.contains(Metric.DEBT_PRINCIPAL) ? debtPrincipal.getOrDefault(id, Map.of()) : null;
    Map<String, Long> credits =
        metrics.contains(Metric.CREDIT_PRINCIPAL)
            ? creditPrincipal.getOrDefault(id, Map.of())
            : null;
    return new HouseholdResource(
        laborMilli, naturalNeeds, goods, money, accountMissing, debts, credits);
  }

  private static void placeResource(
      Bucket bucket,
      SelectedHousehold household,
      HouseholdResource resource,
      Set<Metric> metrics,
      boolean actorAllowed) {
    HouseholdId id = household.household().id();
    bucket.contributors.add(id);
    if (household.memberFilterPartial()) {
      bucket.memberFilterPartial.add(id);
      return; // 家户级指标无法只按命中成员拆；由 metricView 置 member-filter-partial
    }
    if (metrics.contains(Metric.LABOR_MILLI)) {
      bucket.laborMilli += resource.laborMilli();
    }
    if (metrics.contains(Metric.NATURAL_NEEDS)) {
      mergeInto(bucket.naturalNeeds, resource.naturalNeeds());
    }
    if (metrics.contains(Metric.PARTICIPATION_PER_MILLE)) {
      HouseholdEconomy row = household.economyRow();
      if (row == null) {
        bucket.economyRowMissing.add(id);
      } else {
        bucket.participationWeight += row.population();
        bucket.participationWeighted += (long) row.participationPerMille() * row.population();
      }
    }
    if (metrics.contains(Metric.GOODS) || metrics.contains(Metric.MONEY)) {
      if (actorAllowed) {
        if (resource.accountMissing()) {
          bucket.accountMissing.add(id);
        } else {
          if (metrics.contains(Metric.GOODS)) {
            mergeInto(bucket.goods, resource.goods());
          }
          if (metrics.contains(Metric.MONEY)) {
            mergeInto(bucket.money, resource.money());
          }
        }
      }
    }
    if (metrics.contains(Metric.DEBT_PRINCIPAL)) {
      mergeInto(bucket.debtPrincipal, resource.debtPrincipal());
    }
    if (metrics.contains(Metric.CREDIT_PRINCIPAL)) {
      mergeInto(bucket.creditPrincipal, resource.creditPrincipal());
    }
  }

  private static void mergeInto(Map<String, Long> target, Map<String, Long> source) {
    if (source == null) {
      return;
    }
    for (Map.Entry<String, Long> entry : source.entrySet()) {
      target.merge(entry.getKey(), entry.getValue(), Long::sum);
    }
  }

  private static Map<String, Long> commodityKeyed(Map<CommodityId, Long> source) {
    Map<String, Long> out = new TreeMap<>();
    for (Map.Entry<CommodityId, Long> entry : source.entrySet()) {
      out.put(entry.getKey().value(), entry.getValue());
    }
    return out;
  }

  private static Map<String, Long> currencyKeyed(Map<CurrencyId, Long> source) {
    Map<String, Long> out = new TreeMap<>();
    for (Map.Entry<CurrencyId, Long> entry : source.entrySet()) {
      out.put(entry.getKey().value(), entry.getValue());
    }
    return out;
  }

  // ── 行键与排序 ───────────────────────────────────────────────────────────────────

  private record RowKey(List<String> values) {
    private RowKey {
      values = Collections.unmodifiableList(new ArrayList<>(values));
    }
  }

  private static EnumMap<Dimension, String> householdDimensionValues(SelectedHousehold household) {
    EnumMap<Dimension, String> values = new EnumMap<>(Dimension.class);
    values.put(Dimension.STRATUM, household.stratum());
    values.put(Dimension.PRODUCTION_MODE, household.productionMode());
    values.put(Dimension.UNIT, household.containingUnit().map(UnitId::value).orElse(null));
    values.put(Dimension.HEX, household.hexValue());
    values.put(Dimension.HOUSEHOLD, household.household().id().value());
    return values;
  }

  private static RowKey memberKey(
      EnumMap<Dimension, String> householdDims,
      MemberHit hit,
      List<Dimension> memberDims,
      List<Dimension> groupBy) {
    EnumMap<Dimension, String> values = new EnumMap<>(householdDims);
    for (Dimension dimension : memberDims) {
      values.put(dimension, memberValue(dimension, hit));
    }
    return new RowKey(householdValues(values, groupBy));
  }

  private static RowKey eventKey(
      EnumMap<Dimension, String> householdDims,
      SelectedHousehold household,
      HouseholdPopulationEvent event,
      List<Dimension> memberDims,
      List<Dimension> groupBy,
      SocialData social) {
    EnumMap<Dimension, String> values = new EnumMap<>(householdDims);
    for (Dimension dimension : memberDims) {
      values.put(dimension, eventValue(household, event, dimension, social));
    }
    return new RowKey(householdValues(values, groupBy));
  }

  private static RowKey comboKey(
      EnumMap<Dimension, String> householdDims,
      List<String> memberValues,
      List<Dimension> memberDims,
      List<Dimension> groupBy) {
    EnumMap<Dimension, String> values = new EnumMap<>(householdDims);
    for (int index = 0; index < memberDims.size(); index++) {
      values.put(memberDims.get(index), memberValues.get(index));
    }
    return new RowKey(householdValues(values, groupBy));
  }

  private static List<String> householdValues(
      EnumMap<Dimension, String> values, List<Dimension> groupBy) {
    List<String> out = new ArrayList<>(groupBy.size());
    for (Dimension dimension : groupBy) {
      out.add(values.get(dimension));
    }
    return out;
  }

  private static String memberValue(Dimension dimension, MemberHit hit) {
    return switch (dimension) {
      case AGE_BRACKET -> hit.bracket().key();
      case SEX -> hit.sex().name();
      case RESIDENCE -> hit.residence().name();
      default -> throw new IllegalStateException("非成员级维度: " + dimension);
    };
  }

  private static List<String> memberCombo(MemberHit hit, List<Dimension> memberDims) {
    List<String> out = new ArrayList<>(memberDims.size());
    for (Dimension dimension : memberDims) {
      out.add(memberValue(dimension, hit));
    }
    return out;
  }

  private static String eventValue(
      SelectedHousehold household,
      HouseholdPopulationEvent event,
      Dimension dimension,
      SocialData social) {
    return switch (dimension) {
      case SEX -> event.sex().name();
      case AGE_BRACKET -> eventBracket(event).key();
      case RESIDENCE -> eventResidence(household, event, social);
      default -> throw new IllegalStateException("非成员级维度: " + dimension);
    };
  }

  private static List<String> eventCombo(
      SelectedHousehold household,
      HouseholdPopulationEvent event,
      List<Dimension> memberDims,
      SocialData social) {
    List<String> out = new ArrayList<>(memberDims.size());
    for (Dimension dimension : memberDims) {
      out.add(eventValue(household, event, dimension, social));
    }
    return out;
  }

  private static AgeBracket eventBracket(HouseholdPopulationEvent event) {
    String id = event.ageBracketId();
    for (AgeBracket bracket : AgeBracket.values()) {
      if (bracket.key().equals(id) || bracket.name().equals(id)) {
        return bracket;
      }
    }
    throw new IllegalArgumentException("人口事件 " + event.id() + " 的 ageBracketId 无法解析: " + id);
  }

  private static String eventResidence(
      SelectedHousehold household, HouseholdPopulationEvent event, SocialData social) {
    if (event.lotId() != null) {
      PopulationGroup group = social.groups().get(event.lotId());
      if (group != null) {
        return PopulationLots.isUrban(group)
            ? ResidenceKind.URBAN.name()
            : ResidenceKind.RURAL.name();
      }
      return ResidenceKind.ofLot(event.lotId()).name();
    }
    Set<ResidenceKind> distinct = EnumSet.noneOf(ResidenceKind.class);
    for (MemberHit hit : household.members()) {
      distinct.add(hit.residence());
    }
    return distinct.size() == 1 ? distinct.iterator().next().name() : null;
  }

  private static Comparator<RowKey> rowKeyComparator(List<Dimension> groupBy) {
    return (left, right) -> {
      for (int index = 0; index < groupBy.size(); index++) {
        String leftValue = left.values().get(index);
        String rightValue = right.values().get(index);
        if (leftValue == null && rightValue == null) {
          continue;
        }
        if (leftValue == null) {
          return 1;
        }
        if (rightValue == null) {
          return -1;
        }
        int compared = leftValue.compareTo(rightValue);
        if (compared != 0) {
          return compared;
        }
      }
      return 0;
    };
  }

  private static Map<String, Object> keyView(RowKey key, List<Dimension> groupBy) {
    Map<String, Object> view = new LinkedHashMap<>();
    for (int index = 0; index < groupBy.size(); index++) {
      view.put(groupBy.get(index).name(), key.values().get(index));
    }
    return view;
  }

  // ── 指标视图与 unavailable ────────────────────────────────────────────────────────

  private Map<String, Object> metricView(
      Bucket bucket,
      Set<Metric> metrics,
      RateMode rateMode,
      long windowTicks,
      boolean actorAllowed,
      UnavailabilityLog log,
      Collection<HouseholdId> allSelected,
      Collection<String> dimensionReasons) {
    Map<String, Object> view = new LinkedHashMap<>();
    TreeSet<String> unavailable = new TreeSet<>(dimensionReasons);
    boolean split = !bucket.splitHouseholds.isEmpty();
    if (metrics.contains(Metric.POPULATION)) {
      view.put("population", bucket.population);
    }
    if (metrics.contains(Metric.HOUSEHOLD_COUNT)) {
      view.put("householdCount", bucket.households.size());
    }
    if (metrics.contains(Metric.LOT_COUNT)) {
      view.put("lotCount", bucket.lots.size());
    }
    if (metrics.contains(Metric.BIRTHS)) {
      view.put("births", bucket.births);
    }
    if (metrics.contains(Metric.DEATHS)) {
      view.put("deaths", bucket.deaths);
    }
    if (metrics.contains(Metric.BIRTH_RATE_PER_MILLE)) {
      rateView(view, unavailable, log, bucket, true, rateMode, windowTicks);
    }
    if (metrics.contains(Metric.DEATH_RATE_PER_MILLE)) {
      rateView(view, unavailable, log, bucket, false, rateMode, windowTicks);
    }
    if (metrics.contains(Metric.LABOR_MILLI)) {
      if (!bucket.memberFilterPartial.isEmpty()) {
        markReason(
            unavailable, log, "laborMilli", MEMBER_FILTER_PARTIAL, bucket.memberFilterPartial);
      } else if (split) {
        markSplit(unavailable, log, bucket, "laborMilli");
      } else {
        view.put("laborMilli", bucket.laborMilli);
      }
    }
    if (metrics.contains(Metric.NATURAL_NEEDS)) {
      if (!bucket.memberFilterPartial.isEmpty()) {
        markReason(
            unavailable, log, "naturalNeeds", MEMBER_FILTER_PARTIAL, bucket.memberFilterPartial);
      } else if (split) {
        markSplit(unavailable, log, bucket, "naturalNeeds");
      } else {
        view.put("naturalNeeds", bucket.naturalNeeds);
      }
    }
    if (metrics.contains(Metric.PARTICIPATION_PER_MILLE)) {
      if (!bucket.memberFilterPartial.isEmpty()) {
        markReason(
            unavailable,
            log,
            "participationPerMille",
            MEMBER_FILTER_PARTIAL,
            bucket.memberFilterPartial);
      } else if (split) {
        markSplit(unavailable, log, bucket, "participationPerMille");
      } else if (!bucket.economyRowMissing.isEmpty()) {
        markReason(
            unavailable,
            log,
            "participationPerMille",
            ECONOMY_ROW_MISSING,
            bucket.economyRowMissing);
      } else if (bucket.participationWeight == 0L) {
        view.put("participationPerMille", 0.0);
      } else {
        view.put(
            "participationPerMille",
            (double) bucket.participationWeighted / (double) bucket.participationWeight);
      }
    }
    if (metrics.contains(Metric.GOODS)) {
      boolean valueKnown = true;
      if (!bucket.memberFilterPartial.isEmpty()) {
        markReason(unavailable, log, "goods", MEMBER_FILTER_PARTIAL, bucket.memberFilterPartial);
        valueKnown = false;
      } else if (split) {
        markSplit(unavailable, log, bucket, "goods");
        valueKnown = false;
      }
      if (!actorAllowed) {
        markReason(unavailable, log, "goods", ACTOR_DENIED, allSelected);
        valueKnown = false;
      } else if (!bucket.accountMissing.isEmpty()) {
        markReason(unavailable, log, "goods", ACTOR_ACCOUNT_MISSING, bucket.accountMissing);
        valueKnown = false;
      }
      if (valueKnown) {
        view.put("goods", bucket.goods);
      }
    }
    if (metrics.contains(Metric.MONEY)) {
      boolean valueKnown = true;
      if (!bucket.memberFilterPartial.isEmpty()) {
        markReason(unavailable, log, "money", MEMBER_FILTER_PARTIAL, bucket.memberFilterPartial);
        valueKnown = false;
      } else if (split) {
        markSplit(unavailable, log, bucket, "money");
        valueKnown = false;
      }
      if (!actorAllowed) {
        markReason(unavailable, log, "money", ACTOR_DENIED, allSelected);
        valueKnown = false;
      } else if (!bucket.accountMissing.isEmpty()) {
        markReason(unavailable, log, "money", ACTOR_ACCOUNT_MISSING, bucket.accountMissing);
        valueKnown = false;
      }
      if (valueKnown) {
        view.put("money", bucket.money);
      }
    }
    if (metrics.contains(Metric.DEBT_PRINCIPAL)) {
      if (!bucket.memberFilterPartial.isEmpty()) {
        markReason(
            unavailable, log, "debtPrincipal", MEMBER_FILTER_PARTIAL, bucket.memberFilterPartial);
      } else if (split) {
        markSplit(unavailable, log, bucket, "debtPrincipal");
      } else {
        view.put("debtPrincipal", bucket.debtPrincipal);
      }
    }
    if (metrics.contains(Metric.CREDIT_PRINCIPAL)) {
      if (!bucket.memberFilterPartial.isEmpty()) {
        markReason(
            unavailable, log, "creditPrincipal", MEMBER_FILTER_PARTIAL, bucket.memberFilterPartial);
      } else if (split) {
        markSplit(unavailable, log, bucket, "creditPrincipal");
      } else {
        view.put("creditPrincipal", bucket.creditPrincipal);
      }
    }
    if (!unavailable.isEmpty()) {
      view.put("unavailable", new ArrayList<>(unavailable));
    }
    return view;
  }

  private static void rateView(
      Map<String, Object> view,
      TreeSet<String> unavailable,
      UnavailabilityLog log,
      Bucket bucket,
      boolean birth,
      RateMode rateMode,
      long windowTicks) {
    String metricKey = birth ? "birthRatePerMille" : "deathRatePerMille";
    List<String> reasons = new ArrayList<>();
    if (bucket.population == 0L) {
      reasons.add(POPULATION_ZERO);
    }
    if (windowTicks == 0L) {
      reasons.add(WINDOW_ZERO);
    }
    if (!reasons.isEmpty()) {
      for (String reason : reasons) {
        unavailable.add(metricKey + ":" + reason);
        log.add(reason, metricKey, bucket.contributors);
        if (rateMode == RateMode.BOTH) {
          String observedKey = metricKey + "Observed";
          unavailable.add(observedKey + ":" + reason);
          log.add(reason, observedKey, bucket.contributors);
        }
      }
      return;
    }
    long numerator = birth ? bucket.birthNumerator : bucket.deathNumerator;
    long events = birth ? bucket.births : bucket.deaths;
    double configured = (double) numerator / (double) bucket.population / 1000.0;
    double observed =
        (double) events * 1000.0 / ((double) bucket.population * (double) windowTicks);
    switch (rateMode) {
      case CONFIGURED -> view.put(metricKey, configured);
      case OBSERVED -> view.put(metricKey, observed);
      case BOTH -> {
        view.put(metricKey, configured);
        view.put(metricKey + "Observed", observed);
      }
    }
  }

  private static void markSplit(
      TreeSet<String> unavailable, UnavailabilityLog log, Bucket bucket, String metricKey) {
    unavailable.add(metricKey + ":" + HOUSEHOLD_LEVEL_METRIC_SPLIT);
    log.add(HOUSEHOLD_LEVEL_METRIC_SPLIT, metricKey, bucket.splitHouseholds);
  }

  private static void markReason(
      TreeSet<String> unavailable,
      UnavailabilityLog log,
      String metricKey,
      String reason,
      Collection<HouseholdId> households) {
    unavailable.add(metricKey + ":" + reason);
    log.add(reason, metricKey, households);
  }

  /** 顶层 {@code unavailable} 的汇总器：按 reason 归并 metrics 与受影响家户计数。 */
  private static final class UnavailabilityLog {

    private final Map<String, Group> groups = new TreeMap<>();

    void add(String reason, String metricKey, Collection<HouseholdId> households) {
      Group group = groups.computeIfAbsent(reason, ignored -> new Group());
      group.metrics.add(metricKey);
      group.households.addAll(households);
    }

    List<Map<String, Object>> view() {
      List<Map<String, Object>> out = new ArrayList<>(groups.size());
      for (Map.Entry<String, Group> entry : groups.entrySet()) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("reason", entry.getKey());
        view.put("metrics", new ArrayList<>(entry.getValue().metrics));
        view.put("households", entry.getValue().households.size());
        out.add(view);
      }
      return out;
    }

    private static final class Group {
      final Set<String> metrics = new TreeSet<>();
      final Set<HouseholdId> households = new TreeSet<>(Comparator.comparing(HouseholdId::value));
    }
  }

  // ── spec 归一与窗口 ───────────────────────────────────────────────────────────────

  private static Filters normalizeFilters(Filters raw) {
    if (raw == null) {
      return new Filters(
          Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null,
          true);
    }
    return new Filters(
        raw.ageBrackets() == null ? Set.of() : Set.copyOf(raw.ageBrackets()),
        raw.sexes() == null ? Set.of() : Set.copyOf(raw.sexes()),
        raw.strata() == null ? Set.of() : Set.copyOf(raw.strata()),
        raw.productionModes() == null ? Set.of() : Set.copyOf(raw.productionModes()),
        raw.residences() == null ? Set.of() : Set.copyOf(raw.residences()),
        raw.unitIds() == null ? Set.of() : Set.copyOf(raw.unitIds()),
        raw.householdIds() == null ? Set.of() : Set.copyOf(raw.householdIds()),
        raw.hexes() == null ? Set.of() : Set.copyOf(raw.hexes()),
        raw.hasEconomyRow(),
        raw.aliveOnly());
  }

  private static List<Dimension> normalizeGroupBy(List<Dimension> raw) {
    if (raw == null) {
      return List.of();
    }
    List<Dimension> out = new ArrayList<>(raw.size());
    Set<Dimension> seen = EnumSet.noneOf(Dimension.class);
    for (Dimension dimension : raw) {
      if (dimension == null) {
        throw new IllegalArgumentException("groupBy 不得含 null");
      }
      if (!seen.add(dimension)) {
        throw new IllegalArgumentException("groupBy 不得重复: " + dimension.name());
      }
      out.add(dimension);
    }
    return List.copyOf(out);
  }

  private static Set<Metric> normalizeMetrics(Set<Metric> raw) {
    if (raw == null) {
      return Collections.unmodifiableSet(EnumSet.allOf(Metric.class));
    }
    Set<Metric> out = EnumSet.noneOf(Metric.class);
    for (Metric metric : raw) {
      if (metric == null) {
        throw new IllegalArgumentException("metrics 不得含 null");
      }
      out.add(metric);
    }
    return Collections.unmodifiableSet(out);
  }

  private static Window resolveWindow(Window raw, long nowTick, CalendarClock clock) {
    if (raw == null) {
      CalendarDate today = clock.dateOfTick(nowTick);
      long yearStartDayNumber = clock.system().dayNumberOf(new CalendarDate(today.year(), 1, 1));
      long fromTick = Math.max(0L, clock.tickOfDayNumber(yearStartDayNumber));
      return new Window(fromTick, nowTick + 1L);
    }
    if (raw.fromTick() < 0L) {
      throw new IllegalArgumentException("window.fromTick 不得为负: " + raw.fromTick());
    }
    if (raw.toTick() < raw.fromTick()) {
      throw new IllegalArgumentException(
          "window.toTick 不得小于 fromTick: " + raw.fromTick() + " → " + raw.toTick());
    }
    if (raw.toTick() > nowTick + 1L) {
      throw new IllegalArgumentException(
          "window.toTick 不得晚于当前 tick + 1（未来窗口）: " + raw.toTick() + " > " + (nowTick + 1L));
    }
    return raw;
  }

  private static String scopeSource(ScopeKind kind) {
    return switch (kind) {
      case ALL -> "all";
      case HEX -> "hex";
      case UNIT -> "unit";
      case HOUSEHOLD -> "household";
      case VISIBLE -> "decision-scope";
    };
  }

  private static int requireInt(Integer value, String label) {
    if (value == null) {
      throw new IllegalArgumentException(label + " 必填且为整数");
    }
    return value;
  }

  private static String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(label + " 必填且为非空文本");
    }
    return value;
  }
}

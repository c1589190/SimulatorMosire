package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.household.HouseholdPositionResolver;
import io.mosire.simos.app.query.HouseholdQueryService;
import io.mosire.simos.app.query.HouseholdQueryService.Dimension;
import io.mosire.simos.app.query.HouseholdQueryService.Filters;
import io.mosire.simos.app.query.HouseholdQueryService.Include;
import io.mosire.simos.app.query.HouseholdQueryService.Metric;
import io.mosire.simos.app.query.HouseholdQueryService.RateMode;
import io.mosire.simos.app.query.HouseholdQueryService.Scope;
import io.mosire.simos.app.query.HouseholdQueryService.ScopeKind;
import io.mosire.simos.app.query.HouseholdQueryService.Spec;
import io.mosire.simos.app.query.HouseholdQueryService.Visibility;
import io.mosire.simos.app.query.HouseholdQueryService.Window;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code simos.social.households}（D1 决策包计划的共享读工具）：家户聚合查询——GM 与决策人共用；{@code scope=ALL} 仅 GM。
 *
 * <p>★★ <b>纯只读</b>：查询体由 {@link HouseholdQueryService} 现算，工具只做参数解析、可见性前置检查与错误折叠。 资源面声明 {@code
 * map/social/unit/economy/actor} 全 {@code READ_ONLY}（actor 对决策人由 scope 函数置 none/窄前缀 ⇒ 账户读数具名 {@code
 * actor-denied}，不是静默 0）。
 *
 * <p>★ <b>可见性与"不存在"同文案</b>（参考 {@code simos.social.population} / {@code simos.economy.hex}）：scope
 * 指定的 HEX/UNIT/HOUSEHOLD 不可见或不存在一律 {@code NOT_FOUND}，不给存在性侧信道。
 *
 * <p>★ <b>不靠工具名/身份字符串判 GM</b>：{@code scope=ALL} 只看调用者的 {@code social} 命名空间是否 {@code
 * unrestricted()}（与 {@code Shell#gmPermissionSet} 同源）。
 */
public final class SocialHouseholdsTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.social.households";

  /** 五个读命名空间：人口/单位/经济/账户 + 地图（与 GUI/工具面同口径的读声明）。 */
  private static final ResourceManifest RESOURCES =
      ResourceManifest.of(
          Map.of(
              ToolSupport.MAP_NAMESPACE, ResourcePolicy.READ_ONLY,
              ToolSupport.SOCIAL_NAMESPACE, ResourcePolicy.READ_ONLY,
              ToolSupport.UNIT_NAMESPACE, ResourcePolicy.READ_ONLY,
              ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.READ_ONLY,
              ToolSupport.ACTOR_NAMESPACE, ResourcePolicy.READ_ONLY));

  private final QueryService query;
  private final HouseholdQueryService householdQueryService;
  private final String mapId;

  /**
   * @param query 查询门面（按 branch/revision 取状态）
   * @param calendarService 世界历法时钟（窗口/年龄档）
   * @param mapId 地图 id（资源路径与可见性判据用）
   */
  public SocialHouseholdsTool(QueryService query, CalendarService calendarService, String mapId) {
    this.query = Objects.requireNonNull(query, "query");
    Objects.requireNonNull(calendarService, "calendarService");
    this.householdQueryService = new HouseholdQueryService(calendarService);
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "家户聚合查询（GM 与决策人共用）：按 scope 选定家户集合，按 groupBy 维度分桶，对 metrics 逐项求和/加权。"
        + "scope 缺省 VISIBLE（逐户按调用者可见性过滤）；groupBy 缺省空 = 总量单行；metrics 缺省全部；"
        + "rateMode 缺省 BOTH（birthRatePerMille/deathRatePerMille 给 CONFIGURED 加权值，另写 *Observed 观测值）；"
        + "window 缺省 = 当前历法年第一天（clamp 0）到 now+1（半开 [from,to)）；filters.aliveOnly 缺省 true。"
        + "缺数据的指标不写键、在行与顶层 unavailable 具名（goods/money 的 actor-denied/actor-account-missing、"
        + "participation 的 economy-row-missing、家户级指标在成员级维度上跨桶的 household-level-metric-split 等）；"
        + "能算出的 0 照发。scope=ALL 仅 GM（调用者 social 命名空间 unrestricted）；决策人只能用 VISIBLE/HEX/UNIT/"
        + "HOUSEHOLD 且看不到辖区外的家户。HEX 维度对'仅 unit 面可见'的家户输出 null 并记 hex-hidden-by-scope。"
        + "include.members/economy/units 在 D1 只接受 false（true ⇒ BAD_REQUEST，明细留 D1.1）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put(
        "scope",
        objectSchema(
            Map.of(
                "kind",
                stringEnum(
                    List.of("VISIBLE", "HEX", "UNIT", "HOUSEHOLD", "ALL"), "缺省 VISIBLE；ALL 仅 GM"),
                "q",
                ToolSupport.prop("integer", "scope.kind=HEX 时的列坐标 q"),
                "r",
                ToolSupport.prop("integer", "scope.kind=HEX 时的行坐标 r"),
                "unitId",
                ToolSupport.prop("string", "scope.kind=UNIT 时的单位 id"),
                "householdId",
                ToolSupport.prop("string", "scope.kind=HOUSEHOLD 时的家户 id")),
            "选择家户集合的方式；缺省 VISIBLE"));
    props.put(
        "filters",
        objectSchema(
            Map.of(
                "ageBracket", stringArray(List.of("0-14", "15-59", "60+"), "成员级：年龄档（命中成员才参与人口/率）"),
                "sex", stringArray(List.of("MALE", "FEMALE"), "成员级：性别"),
                "stratum",
                    stringArray(
                        List.of("poor_peasant", "middle_peasant", "rich_peasant", "landlord"),
                        "家户级：阶层（Economy 行的 view.stratum().value()；无行 = unclassified）"),
                "productionMode",
                    stringArray(
                        List.of("self_farm", "tenant_farm", "wage_labor"),
                        "家户级：生产方式（classStandings → classPositions → modeId；缺 = none）"),
                "residence", stringArray(List.of("RURAL", "URBAN"), "成员级：城乡（按批次 id 前缀）"),
                "unitId", stringArray(List.of("u-1"), "家户级：单位（Unit.households 反查）"),
                "householdId", stringArray(List.of("hh-1"), "家户级：家户 id"),
                "hex", hexArraySchema(),
                "hasEconomyRow", ToolSupport.prop("boolean", "true=只含有经济行；false=只含无经济行；缺省不过滤"),
                "aliveOnly",
                    ToolSupport.prop("boolean", "缺省 true=只含 population>0 的家户；显式 false 才含 0 人口家户")),
            "筛选；成员级筛选命中一个成员即纳入该家户（人口只累加命中成员）"));
    props.put(
        "groupBy",
        stringArray(
            List.of(
                "AGE_BRACKET",
                "SEX",
                "STRATUM",
                "PRODUCTION_MODE",
                "RESIDENCE",
                "UNIT",
                "HEX",
                "HOUSEHOLD"),
            "分组维度，按数组顺序出现在 key 里；缺省空 = 总量单行；不允许重复"));
    props.put(
        "metrics",
        stringArray(
            List.of(
                "population",
                "householdCount",
                "lotCount",
                "births",
                "deaths",
                "birthRatePerMille",
                "deathRatePerMille",
                "laborMilli",
                "naturalNeeds",
                "participationPerMille",
                "goods",
                "money",
                "debtPrincipal",
                "creditPrincipal"),
            "指标；缺省全部；缺数据不写键并进 unavailable"));
    props.put(
        "window",
        objectSchema(
            Map.of(
                "fromTick", ToolSupport.prop("integer", "半开窗口起点（含）"),
                "toTick", ToolSupport.prop("integer", "半开窗口终点（不含）；不得晚于 now+1")),
            "事件窗口；缺省当前历法年第一天（clamp 0）到 now+1"));
    props.put(
        "rateMode",
        stringEnum(
            List.of("CONFIGURED", "OBSERVED", "BOTH"),
            "缺省 BOTH：主键给 CONFIGURED 加权率，另写 *Observed；OBSERVED 主键给观测率且不写别名"));
    props.put(
        "include",
        objectSchema(
            Map.of(
                "members", ToolSupport.prop("boolean", "D1 只接受 false"),
                "economy", ToolSupport.prop("boolean", "D1 只接受 false"),
                "units", ToolSupport.prop("boolean", "D1 只接受 false")),
            "D1 只支持全 false（明细/分页留 D1.1）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return RESOURCES;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      SimulationState state = query.stateAt(ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH));
      Spec spec = parseSpec(args);
      Visibility visibility = visibilityFor(context);
      ToolResult precheck = precheckScope(spec.scope(), context, state, visibility);
      if (precheck != null) {
        return precheck;
      }
      return ToolSupport.ok(householdQueryService.query(state, mapId, spec, visibility));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  // ── 前置检查与可见性 ──────────────────────────────────────────────────────────────

  private ToolResult precheckScope(
      Scope scope, ToolContext context, SimulationState state, Visibility visibility) {
    switch (scope.kind()) {
      case ALL -> {
        if (!socialScopeUnrestricted(context)) {
          return ToolResult.error("FORBIDDEN", "scope=ALL 仅 GM 可用");
        }
        return null;
      }
      case HEX -> {
        HexCoord coord =
            new HexCoord(requireInt(scope.q(), "scope.q"), requireInt(scope.r(), "scope.r"));
        GameMap map = ToolSupport.gameMap(state);
        if (!map.hexes().containsKey(coord) || !ToolSupport.populationVisible(context, coord)) {
          return ToolResult.error("NOT_FOUND", "六角格不存在: " + coord.q() + "_" + coord.r());
        }
        return null;
      }
      case UNIT -> {
        UnitId unitId = UnitId.parse(requireText(scope.unitId(), "scope.unitId"));
        UnitState units = ToolSupport.unitState(state);
        if (!units.units().containsKey(unitId) || !ToolSupport.unitVisible(context, unitId)) {
          return ToolResult.error("NOT_FOUND", "单位不存在: " + unitId.value());
        }
        return null;
      }
      case HOUSEHOLD -> {
        HouseholdId householdId =
            HouseholdId.parse(requireText(scope.householdId(), "scope.householdId"));
        SocialData social = ToolSupport.socialData(state);
        var household = social.households().get(householdId);
        UnitState units = ToolSupport.unitState(state);
        Optional<HexCoord> effectiveHex =
            HouseholdPositionResolver.effectiveHex(
                householdId, social, units, state.meta().timestamp());
        Set<UnitId> containingUnits = containingUnits(units, householdId);
        if (household == null
            || !visibility.householdAllowed(
                householdId, household.location(), effectiveHex, containingUnits)) {
          return ToolResult.error("NOT_FOUND", "家户不存在: " + householdId.value());
        }
        return null;
      }
      case VISIBLE -> {
        return null;
      }
    }
    return null;
  }

  private static boolean socialScopeUnrestricted(ToolContext context) {
    var scopes = context.permissions().resourceScopes();
    ResourceScope social = scopes.byNamespace().get(ToolSupport.SOCIAL_NAMESPACE);
    if (social != null) {
      return social.unrestricted();
    }
    // 旧式 AgentPermissionSet.unrestricted(...) 的 resourceScopes 为空图 = "未表态"，工具 manifest 缺省放行 ⇒
    // 对该调用者视作不受限（GM 窄工具夹具/系统身份仍走这条）；只认**整张图为空**，不认"少了 social 键"。
    return scopes.byNamespace().isEmpty();
  }

  private Visibility visibilityFor(ToolContext context) {
    return new Visibility() {
      @Override
      public boolean householdAllowed(
          HouseholdId id,
          HouseholdLocation location,
          Optional<HexCoord> effectiveHex,
          Set<UnitId> containingUnits) {
        if (location instanceof HouseholdLocation.Hex hex && hexAllowed(hex.hex())) {
          return true;
        }
        if (location instanceof HouseholdLocation.Unit unit
            && ToolSupport.unitVisible(context, UnitId.parse(unit.unitId()))) {
          return true;
        }
        for (UnitId containing : containingUnits) {
          if (ToolSupport.unitVisible(context, containing)) {
            return true;
          }
        }
        return effectiveHex.isPresent() && hexAllowed(effectiveHex.get());
      }

      @Override
      public boolean hexAllowed(HexCoord hex) {
        return ToolSupport.populationVisible(context, hex);
      }

      @Override
      public boolean actorAllowed() {
        // actor 账户读面：整命名空间的读权（不是某一格国库路径）。受限调用者（含 GOV 的国库窄前缀）一律 false，
        // 只有 GM 的 unlimited 过得了 ⇒ goods/money 对决策人具名 actor-denied，而不是泄出账户。
        return context
            .resources()
            .allows(Operation.READ, ResourceId.of(ToolSupport.ACTOR_NAMESPACE, mapId));
      }
    };
  }

  private static Set<UnitId> containingUnits(UnitState units, HouseholdId householdId) {
    Set<UnitId> out = new LinkedHashSet<>();
    for (Unit unit : units.units().values()) {
      if (unit.households().contains(householdId)) {
        out.add(unit.id());
      }
    }
    return out;
  }

  // ── 参数解析（坏输入一律 IAE，由 execute 折 BAD_REQUEST）──────────────────────────────

  private static Spec parseSpec(Map<String, Object> args) {
    return new Spec(
        parseScope(args.get("scope")),
        parseFilters(args.get("filters")),
        parseGroupBy(args.get("groupBy")),
        parseMetrics(args.get("metrics")),
        parseWindow(args.get("window")),
        parseRateMode(args.get("rateMode")),
        parseInclude(args.get("include")));
  }

  private static Scope parseScope(Object raw) {
    if (raw == null) {
      return new Scope(ScopeKind.VISIBLE, null, null, null, null);
    }
    Map<String, Object> object = asObject(raw, "scope");
    ScopeKind kind =
        object.get("kind") == null
            ? ScopeKind.VISIBLE
            : parseScopeKind(requireText(object.get("kind"), "scope.kind"));
    Integer q = optionalInt(object.get("q"), "scope.q");
    Integer r = optionalInt(object.get("r"), "scope.r");
    String unitId = optionalText(object.get("unitId"), "scope.unitId");
    String householdId = optionalText(object.get("householdId"), "scope.householdId");
    switch (kind) {
      case HEX -> {
        if (q == null || r == null) {
          throw new IllegalArgumentException("scope.kind=HEX 必须给 q 与 r");
        }
      }
      case UNIT -> {
        if (unitId == null) {
          throw new IllegalArgumentException("scope.kind=UNIT 必须给 unitId");
        }
      }
      case HOUSEHOLD -> {
        if (householdId == null) {
          throw new IllegalArgumentException("scope.kind=HOUSEHOLD 必须给 householdId");
        }
      }
      default -> {}
    }
    return new Scope(kind, q, r, unitId, householdId);
  }

  private static Filters parseFilters(Object raw) {
    if (raw == null) {
      return new Filters(
          Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), null,
          true);
    }
    Map<String, Object> object = asObject(raw, "filters");
    Set<AgeBracket> ageBrackets = parseAgeBrackets(object.get("ageBracket"));
    Set<Sex> sexes = parseSexes(object.get("sex"));
    Set<String> strata = parseTexts(object.get("stratum"), "filters.stratum");
    Set<String> productionModes =
        parseTexts(object.get("productionMode"), "filters.productionMode");
    Set<ResidenceKind> residences = parseResidences(object.get("residence"));
    Set<UnitId> unitIds = parseIds(object.get("unitId"), "filters.unitId", UnitId::parse);
    Set<HouseholdId> householdIds =
        parseIds(object.get("householdId"), "filters.householdId", HouseholdId::parse);
    Set<HexCoord> hexes = parseHexes(object.get("hex"));
    Boolean hasEconomyRow =
        object.get("hasEconomyRow") == null
            ? null
            : requireBoolean(object.get("hasEconomyRow"), "filters.hasEconomyRow");
    boolean aliveOnly =
        object.get("aliveOnly") == null
            || requireBoolean(object.get("aliveOnly"), "filters.aliveOnly");
    return new Filters(
        ageBrackets,
        sexes,
        strata,
        productionModes,
        residences,
        unitIds,
        householdIds,
        hexes,
        hasEconomyRow,
        aliveOnly);
  }

  private static List<Dimension> parseGroupBy(Object raw) {
    if (raw == null) {
      return List.of();
    }
    List<Object> items = asArray(raw, "groupBy");
    List<Dimension> out = new ArrayList<>(items.size());
    Set<Dimension> seen = EnumSet.noneOf(Dimension.class);
    for (Object item : items) {
      Dimension dimension = parseDimension(requireText(item, "groupBy[]"));
      if (!seen.add(dimension)) {
        throw new IllegalArgumentException("groupBy 不得重复: " + dimension.name());
      }
      out.add(dimension);
    }
    return List.copyOf(out);
  }

  private static Set<Metric> parseMetrics(Object raw) {
    if (raw == null) {
      return Collections.unmodifiableSet(EnumSet.allOf(Metric.class));
    }
    List<Object> items = asArray(raw, "metrics");
    Set<Metric> out = EnumSet.noneOf(Metric.class);
    for (Object item : items) {
      out.add(parseMetric(requireText(item, "metrics[]")));
    }
    return Collections.unmodifiableSet(out);
  }

  private static Window parseWindow(Object raw) {
    if (raw == null) {
      return null;
    }
    Map<String, Object> object = asObject(raw, "window");
    return new Window(
        requireLong(object.get("fromTick"), "window.fromTick"),
        requireLong(object.get("toTick"), "window.toTick"));
  }

  private static RateMode parseRateMode(Object raw) {
    if (raw == null) {
      return RateMode.BOTH;
    }
    String text = requireText(raw, "rateMode");
    try {
      return RateMode.valueOf(text.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知 rateMode: " + text + "；合法值: " + List.of(RateMode.values()));
    }
  }

  private static Include parseInclude(Object raw) {
    if (raw == null) {
      return new Include(false, false, false);
    }
    Map<String, Object> object = asObject(raw, "include");
    return new Include(
        optionalBoolean(object.get("members"), "include.members"),
        optionalBoolean(object.get("economy"), "include.economy"),
        optionalBoolean(object.get("units"), "include.units"));
  }

  private static ScopeKind parseScopeKind(String text) {
    String normalized = text.toUpperCase(Locale.ROOT);
    for (ScopeKind kind : ScopeKind.values()) {
      if (kind.name().equals(normalized)) {
        return kind;
      }
    }
    throw new IllegalArgumentException(
        "未知 scope.kind: " + text + "；合法值: " + List.of(ScopeKind.values()));
  }

  private static Dimension parseDimension(String text) {
    String normalized = normalizeEnumToken(text);
    for (Dimension dimension : Dimension.values()) {
      if (normalizeEnumToken(dimension.name()).equals(normalized)) {
        return dimension;
      }
    }
    throw new IllegalArgumentException(
        "未知 groupBy 维度: " + text + "；合法值: " + List.of(Dimension.values()));
  }

  private static Metric parseMetric(String text) {
    String normalized = normalizeEnumToken(text);
    for (Metric metric : Metric.values()) {
      if (normalizeEnumToken(metric.name()).equals(normalized)) {
        return metric;
      }
    }
    throw new IllegalArgumentException(
        "未知 metrics 值: " + text + "；合法值: " + List.of(Metric.values()));
  }

  private static String normalizeEnumToken(String text) {
    return text.replace("_", "").toUpperCase(Locale.ROOT);
  }

  private static Set<AgeBracket> parseAgeBrackets(Object raw) {
    if (raw == null) {
      return Set.of();
    }
    List<Object> items = asArray(raw, "filters.ageBracket");
    Set<AgeBracket> out = new LinkedHashSet<>();
    for (Object item : items) {
      String text = requireText(item, "filters.ageBracket[]");
      AgeBracket bracket = null;
      for (AgeBracket candidate : AgeBracket.values()) {
        if (candidate.key().equals(text) || candidate.name().equalsIgnoreCase(text)) {
          bracket = candidate;
          break;
        }
      }
      if (bracket == null) {
        throw new IllegalArgumentException("未知 ageBracket: " + text + "；合法值: 0-14 / 15-59 / 60+");
      }
      out.add(bracket);
    }
    return Collections.unmodifiableSet(out);
  }

  private static Set<Sex> parseSexes(Object raw) {
    if (raw == null) {
      return Set.of();
    }
    List<Object> items = asArray(raw, "filters.sex");
    Set<Sex> out = new LinkedHashSet<>();
    for (Object item : items) {
      String text = requireText(item, "filters.sex[]");
      try {
        out.add(Sex.valueOf(text.toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("未知 sex: " + text + "；合法值: " + List.of(Sex.values()));
      }
    }
    return Collections.unmodifiableSet(out);
  }

  private static Set<ResidenceKind> parseResidences(Object raw) {
    if (raw == null) {
      return Set.of();
    }
    List<Object> items = asArray(raw, "filters.residence");
    Set<ResidenceKind> out = new LinkedHashSet<>();
    for (Object item : items) {
      String text = requireText(item, "filters.residence[]");
      ResidenceKind kind = null;
      for (ResidenceKind candidate : ResidenceKind.values()) {
        if (candidate.name().equalsIgnoreCase(text) || candidate.value().equalsIgnoreCase(text)) {
          kind = candidate;
          break;
        }
      }
      if (kind == null) {
        throw new IllegalArgumentException("未知 residence: " + text + "；合法值: RURAL / URBAN");
      }
      out.add(kind);
    }
    return Collections.unmodifiableSet(out);
  }

  private static Set<String> parseTexts(Object raw, String label) {
    if (raw == null) {
      return Set.of();
    }
    List<Object> items = asArray(raw, label);
    Set<String> out = new LinkedHashSet<>();
    for (Object item : items) {
      out.add(requireText(item, label + "[]"));
    }
    return Collections.unmodifiableSet(out);
  }

  private static <T> Set<T> parseIds(Object raw, String label, Function<String, T> parser) {
    if (raw == null) {
      return Set.of();
    }
    List<Object> items = asArray(raw, label);
    Set<T> out = new LinkedHashSet<>();
    for (Object item : items) {
      out.add(parser.apply(requireText(item, label + "[]")));
    }
    return Collections.unmodifiableSet(out);
  }

  private static Set<HexCoord> parseHexes(Object raw) {
    if (raw == null) {
      return Set.of();
    }
    List<Object> items = asArray(raw, "filters.hex");
    Set<HexCoord> out = new LinkedHashSet<>();
    for (Object item : items) {
      Map<String, Object> object = asObject(item, "filters.hex[]");
      out.add(
          new HexCoord(
              requireInt(object.get("q"), "filters.hex[].q"),
              requireInt(object.get("r"), "filters.hex[].r")));
    }
    return Collections.unmodifiableSet(out);
  }

  private static Map<String, Object> asObject(Object raw, String label) {
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 " + label + " 必须是对象");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IllegalArgumentException("参数 " + label + " 的键必须是字符串");
      }
      out.put(key, entry.getValue());
    }
    return out;
  }

  private static List<Object> asArray(Object raw, String label) {
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("参数 " + label + " 必须是数组");
    }
    return new ArrayList<>(list);
  }

  private static String requireText(Object raw, String label) {
    if (!(raw instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("参数 " + label + " 必须是非空文本");
    }
    return text.trim();
  }

  private static String optionalText(Object raw, String label) {
    return raw == null ? null : requireText(raw, label);
  }

  private static Integer optionalInt(Object raw, String label) {
    return raw == null ? null : requireInt(raw, label);
  }

  private static int requireInt(Object raw, String label) {
    if (raw instanceof Number number) {
      double value = number.doubleValue();
      if (!Double.isFinite(value) || Double.compare(value, Math.rint(value)) != 0) {
        throw new IllegalArgumentException("参数 " + label + " 必须是整数");
      }
      long longValue = number.longValue();
      if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("参数 " + label + " 超出整数范围: " + longValue);
      }
      return (int) longValue;
    }
    if (raw instanceof String text && !text.isBlank()) {
      try {
        return Integer.parseInt(text.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("参数 " + label + " 必须是整数: " + text);
      }
    }
    throw new IllegalArgumentException("参数 " + label + " 必须是整数");
  }

  private static long requireLong(Object raw, String label) {
    if (raw instanceof Number number) {
      double value = number.doubleValue();
      if (!Double.isFinite(value) || Double.compare(value, Math.rint(value)) != 0) {
        throw new IllegalArgumentException("参数 " + label + " 必须是整数");
      }
      return number.longValue();
    }
    if (raw instanceof String text && !text.isBlank()) {
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("参数 " + label + " 必须是整数: " + text);
      }
    }
    throw new IllegalArgumentException("参数 " + label + " 必须是整数");
  }

  private static boolean optionalBoolean(Object raw, String label) {
    return raw != null && requireBoolean(raw, label);
  }

  private static boolean requireBoolean(Object raw, String label) {
    if (raw instanceof Boolean value) {
      return value;
    }
    if (raw instanceof String text && !text.isBlank()) {
      if ("true".equalsIgnoreCase(text.trim())) {
        return true;
      }
      if ("false".equalsIgnoreCase(text.trim())) {
        return false;
      }
    }
    throw new IllegalArgumentException("参数 " + label + " 必须是布尔值");
  }

  // ── JSON Schema 小件 ─────────────────────────────────────────────────────────────

  private static Map<String, Object> objectSchema(
      Map<String, Object> properties, String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("description", description);
    schema.put("properties", properties);
    schema.put("additionalProperties", false);
    return schema;
  }

  private static Map<String, Object> stringEnum(List<String> values, String description) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "string");
    schema.put("description", description);
    schema.put("enum", values);
    return schema;
  }

  private static Map<String, Object> stringArray(List<String> values, String description) {
    Map<String, Object> items = new LinkedHashMap<>();
    items.put("type", "string");
    items.put("enum", values);
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "array");
    schema.put("description", description);
    schema.put("items", items);
    return schema;
  }

  private static Map<String, Object> hexArraySchema() {
    Map<String, Object> itemProperties = new LinkedHashMap<>();
    itemProperties.put("q", ToolSupport.prop("integer", "六角列坐标 q"));
    itemProperties.put("r", ToolSupport.prop("integer", "六角行坐标 r"));
    Map<String, Object> items = new LinkedHashMap<>();
    items.put("type", "object");
    items.put("properties", itemProperties);
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "array");
    schema.put("description", "格坐标数组，如 [{\"q\":0,\"r\":0}]");
    schema.put("items", items);
    return schema;
  }
}

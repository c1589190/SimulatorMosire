package io.mosire.simos.app.gui;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.access.DecisionScopeView;
import io.mosire.simos.app.crisis.CrisisMonitor;
import io.mosire.simos.app.decision.DecisionAgentRunner;
import io.mosire.simos.app.gm.GmToolUsage;
import io.mosire.simos.app.query.SdQueryService;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatOutcome;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatUnitLoss;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.calendar.CalendarDate;
import io.mosire.simos.calendar.ClimatePhase;
import io.mosire.simos.calendar.JulianCalendar;
import io.mosire.simos.calendar.SeasonState;
import io.mosire.simos.calendar.SolarTerm;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.money.CurrencyDef;
import io.mosire.simos.economy.api.money.InstrumentKind;
import io.mosire.simos.economy.api.money.MoneyInstrument;
import io.mosire.simos.economy.api.money.MoneyIssuanceKind;
import io.mosire.simos.economy.api.money.MoneyIssuanceRecord;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.api.relation.SubsistenceObligation;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassShare;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.DebtCapacity;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DebtIndex;
import io.mosire.simos.economy.model.DemandEntry;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionCandidate;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.time.ClassTransition;
import io.mosire.simos.economy.time.ClassTransitionFeed;
import io.mosire.simos.economy.time.DebtCapacityBook;
import io.mosire.simos.economy.time.EntryOutcome;
import io.mosire.simos.economy.time.EntryOutcomeFeed;
import io.mosire.simos.economy.time.HouseholdClassRule;
import io.mosire.simos.economy.time.HouseholdCondition;
import io.mosire.simos.economy.time.MarketReport;
import io.mosire.simos.economy.time.ProductionLedger;
import io.mosire.simos.economy.time.ProductionUnitBook;
import io.mosire.simos.map.City;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.BlockId;
import io.mosire.simos.map.block.TerrainBlock;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexVertex;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.pathway.PathwayGroup;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.id.CombatOutcomeId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.DiplomaticEventId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DiplomaticEvent;
import io.mosire.simos.sd.model.DiplomaticRelation;
import io.mosire.simos.sd.model.DiplomaticRelationKey;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveCommand;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.lookup.HouseholdLookup;
import io.mosire.simos.social.api.lookup.PopulationLookup;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationHeadline;
import io.mosire.simos.social.population.PopulationLots;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.population.UrbanRural;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.GovernmentHouseholdPost;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.MilitaryHouseholdDuty;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitModule;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.move.MovementState;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.unit.move.UnitMoves;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.facet.FacetEntry;
import io.mosire.simos.util.identity.QueryResult;
import io.mosire.simos.util.identity.ResolvedSubject;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * JSON 视图装配（M5 T8）：把领域类型转成 GUI 直读的 {@link Map} / {@link List} 树，**只读、无副作用**。
 *
 * <p>★ **为什么单列一类**：{@link GuiServer} 只该管"路由 + 触
 * CoreSimos/QueryService"，把"领域对象长什么样"抽在这里，**不重算领域语义**—— 位置一律走 {@link
 * UnitState#effectivePosition(UnitId, SimosTimestamp)}、人口一律走 {@link
 * PopulationSeries#valueAt(SimosTimestamp)}，与 facet 同口径（GUI 不造第二份真相）。
 *
 * <p>★ **不碰存储、不碰时间线**：本类是纯函数，R1 的扫描对象之一（main 源码不得出现 store/timeline 写面）。
 *
 * <p>★★ **2026-09-24（工具面 M4）：本类同时是 MCP 读工具的视图层** —— GUI 端点与 {@code app.tools.read} 下的读工具**共用同一份**
 * view builder（{@link #timeline} / {@link #regionDetail} / {@link #pathResult} / {@link
 * #decisionMakers} / {@link #decisionMaker} 等）。理由：侦察报告 {@code
 * .superpowers/sdd/2026-09-22-tool-surface/m4-inventory.md} 实测过"同一资源的两个形状"（GUI 与 MCP 各写一份视图 ⇒
 * 语义安全品发散，无判据能发现）。共用一份 = 形状发散在**结构上**不可能，而不是靠人记得。 故类与上述方法从包内可见提升为 {@code public}（仍是 app
 * 层内部的类型，不外发）。
 *
 * <p>★★ <b>M1.6（2026-09-27）：货币读数按「逐工具守恒」组织，<u>没有</u>"全世界总量永远不变"这条总不变量。</b> 可成立的等式是<b>逐工具</b>的
 *
 * <pre>
 * Σ该工具的持有账户 = 创世 + 累计发行 − 累计注销
 * </pre>
 *
 * 发行/注销会让总量变 ⇒ {@code MoneyIssuance.REGISTERED} 为空、{@code moneyIssuances} 为空时它才退化成 "逐币种
 * Σ持有恒定"。读口因此给 {@code moneyLayers} 三栏（私人流通 / 全部基础货币 /（将来）银行存款）： 它们读的是<b>账户事实</b>，不是发行量。E3
 * 起发行/回笼的权威记录是 {@code EconomyData.moneyIssuances}，本类另发 {@code moneyIssuance} 一栏（initialEndowment /
 * fiscalIssue / cumulativeIssuance / cumulativeWithdrawal / circulation / netIssuance）——
 * 只从状态求和，不另存一份。
 */
public final class ApiViews {

  /** 粮食商品 id（与 {@code EconomySeeder.COMMODITY_GRAIN} / 结算侧同字面量：粮 = 1 公斤，库存按毫粮）。 */
  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);

  /** ★ M0.3：衣着（粮布换算比用；唯一拼写点在 {@code EconomyVocabulary}）。 */
  private static final CommodityId CLOTH = new CommodityId(EconomyVocabulary.CLOTH_COMMODITY_ID);

  // ── F2 热力图指标 id（前端按 id 选层；这里是 id 的唯一拼写点）──────────────────────────────

  private static final String HEATMAP_POPULATION_TOTAL = "populationTotal";
  private static final String HEATMAP_POPULATION_RURAL = "populationRural";
  private static final String HEATMAP_POPULATION_URBAN = "populationUrban";
  private static final String HEATMAP_GRAIN_STOCK = "grainStock";
  private static final String HEATMAP_GRAIN_DAILY_NEED = "grainDailyNeed";
  private static final String HEATMAP_GRAIN_COVERAGE_DAYS = "grainCoverageDays";
  private static final String HEATMAP_GRAIN_CYCLE_UNMET = "grainCycleUnmet";
  private static final String HEATMAP_MONEY_SILVER = "moneySilver";

  /** F2 人口热力图的三种取法（与 {@link #heatmap} 的三个 id 一一对应）。 */
  private enum PopulationHeatmapMetric {
    TOTAL,
    RURAL,
    URBAN
  }

  private ApiViews() {}

  /** {@code {q,r}}（六角坐标的 JSON 形；身份仍是类型，这个串只在边界）。 */
  static Map<String, Object> hexCoord(HexCoord coord) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("q", coord.q());
    view.put("r", coord.r());
    return view;
  }

  /**
   * {@code {tick,calendarLabel,date}}（设计稿 §八；D-020：日期由服务端算好随只读口下发）。
   *
   * <p>★ 本方法**只认 tick、不认坐标** ⇒ 只发 {@code date}，不假装能算季节；季节只有拿到格子的 {@link #mapHex}
   * 那条路才发。日历/锚点一律来自世界唯一的 {@link CalendarService}，本类不自造缺省时钟。
   */
  public static Map<String, Object> timestamp(SimosTimestamp at, CalendarService calendars) {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(calendars, "calendars");
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("tick", at.tick());
    view.put("calendarLabel", at.calendarLabel().orElse(null));
    view.put("date", dateView(calendars.dateOfTick(at.tick())));
    return view;
  }

  /** 历法日期四件套（设计稿 §八）：{@code {calendar,year,month,day,dayOfYear}}。 */
  public static Map<String, Object> dateView(CalendarDate date) {
    Objects.requireNonNull(date, "date");
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("calendar", JulianCalendar.ID);
    view.put("year", date.year());
    view.put("month", date.month());
    view.put("day", date.day());
    view.put("dayOfYear", JulianCalendar.INSTANCE.dayOfYear(date));
    return view;
  }

  /** 当天节气（设计稿 §八）：{@code {key,name,longitude}}。 */
  public static Map<String, Object> solarTermView(SolarTerm term) {
    Objects.requireNonNull(term, "term");
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("key", term.key());
    view.put("name", term.chineseName());
    view.put("longitude", term.longitude());
    return view;
  }

  /**
   * 某格季节（设计稿 §八；D-020）：{@code
   * {phase,key,name,dayOfSeason,daysInSeason,progressPerMille,zone,zoneSource}}。
   *
   * <p>★ 分带与来源**成对**：分带未配置 ⇒ {@code zoneSource=fallback} 且 {@code zone} 恒 {@code
   * NORTH_TEMPERATE}（不假装已分带）；配置过 ⇒ {@code zoneSource=store}。判带读的是 {@code at.r()}。
   */
  public static Map<String, Object> seasonView(
      CalendarService calendars, SeasonState season, HexCoord at) {
    Objects.requireNonNull(calendars, "calendars");
    Objects.requireNonNull(season, "season");
    Objects.requireNonNull(at, "at");
    ClimatePhase phase = season.phase();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("phase", phase.getClass().getSimpleName());
    view.put("key", phase.key());
    view.put("name", phase.chineseName());
    view.put("dayOfSeason", season.dayOfSeason());
    view.put("daysInSeason", season.daysInSeason());
    view.put("progressPerMille", progressPerMille(season.progress()));
    view.put("zone", calendars.bands().zoneOf(at).name());
    view.put("zoneSource", calendars.zoneSource().key());
    return view;
  }

  /** 进度千分位（四舍五入；{@code progress ∈ [0,1)} ⇒ 值域 0..999）。 */
  private static int progressPerMille(double progress) {
    return (int) Math.round(progress * 1000.0);
  }

  /** {@code {branch,revision}}。 */
  static Map<String, Object> stateRef(StateRef ref) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("branch", ref.branch().value());
    view.put("revision", ref.revision().value());
    return view;
  }

  /** 写端点三种结局的统一视图（spec §8.2：{@code {result,ref?/current?/reason?}}）。 */
  static Map<String, Object> committed(StateRef ref) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "committed");
    view.put("ref", stateRef(ref));
    return view;
  }

  static Map<String, Object> conflict(StateRef current) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "conflict");
    view.put("current", stateRef(current));
    return view;
  }

  static Map<String, Object> rejected(String reason) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("result", "rejected");
    view.put("reason", reason);
    return view;
  }

  /** resolver 候选逐条（id 的 namespace/localId 显式拆开，不让 Jackson 猜 record 组件名）。 */
  static List<Map<String, Object>> resolveResult(QueryResult result) {
    List<Map<String, Object>> out = new ArrayList<>(result.candidates().size());
    for (ResolvedSubject subject : result.candidates()) {
      Map<String, Object> id = new LinkedHashMap<>();
      id.put("namespace", subject.id().namespace());
      id.put("localId", subject.id().localId());
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", id);
      view.put("canonicalAddress", subject.canonicalAddress());
      view.put("typeName", subject.typeName());
      out.add(view);
    }
    return out;
  }

  /** facet 条目逐条（{@code value} 原样透出，不做二次格式化——spec §〇.3-5 的类型契约）。 */
  static List<Map<String, Object>> facets(List<FacetEntry> entries) {
    List<Map<String, Object>> out = new ArrayList<>(entries.size());
    for (FacetEntry entry : entries) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("namespace", entry.namespace());
      view.put("label", entry.label());
      view.put("typeName", entry.typeName());
      view.put("value", entry.value());
      out.add(view);
    }
    return out;
  }

  /**
   * 时间轴节点清单（spec §3.1，M7 T1；★ C6a 加 {@code date}）：{@code {branch,head,nodes:[…]}}。节点**不含 {@code
   * changesetJson}**（体积大且对 UI 无用），{@code parent} 为 {@code {branch,revision}} 或 {@code null}。
   *
   * <p>★ 日期一律由**该 row 自己的 tick** 纯算（不看 head state）⇒ 同 tick 多条 revision 必同日；历史节点不会跟着 head
   * 的锚点之外的东西漂移。
   */
  public static Map<String, Object> timeline(
      BranchId branch, long head, List<RevisionRow> rows, CalendarService calendars) {
    Objects.requireNonNull(calendars, "calendars");
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("branch", branch.value());
    view.put("head", head);
    List<Map<String, Object>> nodes = new ArrayList<>(rows.size());
    for (RevisionRow row : rows) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("revision", row.revision().value());
      node.put("tick", row.timestamp().tick());
      node.put("date", dateView(calendars.dateOfTick(row.timestamp().tick())));
      node.put("commandType", row.commandType());
      node.put("initiator", row.initiator());
      node.put("parent", row.parent().map(ApiViews::stateRef).orElse(null));
      nodes.add(node);
    }
    view.put("nodes", nodes);
    return view;
  }

  /**
   * 地图只读总览（地图形状，T9 的 Canvas 数据源；M9 T13 起**发块多边形**）。
   *
   * <p>★ **不再发逐格地形**（M9 T13，spec §七.4「载荷从 1,043,837 B 降到块数量级」）：地形由 {@code terrainBlocks} 的权威块承载，客户端
   * Pass 1 直接画带洞多边形、拾取走"点在多边形内" —— 逐格 {@code {q,r,terrain}} 数组整个移除 （{@code hexCount}
   * 仍发，供图例与"块是否覆盖全图"的一致性核对）。
   *
   * <p>★ **逐字节确定**：块按 {@link BlockId} 全序发（不靠 Map 迭代序）；顶点以**整数标签**发（见下）；环按 {@code RegionBoundary}
   * 的规范序并**闭合**（首点补到末尾）—— 同一状态两次调用必得逐字节相同的响应。
   */
  static Map<String, Object> mapOverview(String mapId, GameMap map) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("mapId", mapId);
    view.put("hexCount", map.hexes().size());
    view.put("blocks", blockViews(map));

    List<Map<String, Object>> regions = new ArrayList<>(map.regions().size());
    for (Region region : map.regions().values()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", region.id().value());
      item.put("name", region.name());
      item.put("hexCount", region.hexes().size());
      item.put("label", regionLabelHex(region));
      item.put("meta", regionMeta(region.meta()));
      regions.add(item);
    }
    view.put("regions", regions);

    List<Map<String, Object>> cities = new ArrayList<>(map.cities().size());
    for (City city : map.cities().values()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", city.id().value());
      item.put("name", city.name());
      item.put("at", hexCoord(city.at()));
      item.put("region", city.region() == null ? null : city.region().value());
      cities.add(item);
    }
    view.put("cities", cities);

    view.put("terrainTypes", terrainTypeDefinitions(map));
    view.put("pathwayGroups", pathwayGroupDefinitions(map));
    view.put("edges", allEdgeViews(map.edges()));
    return view;
  }

  /**
   * ★ F1：**社交城市列表**（{@code GET /api/social/cities} 的视图；GUI 与未来 MCP 共用同一份装配）。
   *
   * <p>★ 数据源是 {@link SocialData#cities()}（social 侧城市），**不是** {@code GameMap.cities()} —— 后者在
   * worldgen 之后仍为空，拿它当城市源会得到一张永远没有城市的地图。{@code population} 走 {@link
   * SocialData#urbanPopulationAt(io.mosire.simos.map.CityId)} **现算**（social 侧不存该字段）。
   *
   * <p>★ {@code tier} 只认 {@code props.tier}；缺失 ⇒ {@code null}（不猜等级）。{@code props} 原样透出 （worldgen 写的
   * {@code tier} / {@code catchmentHexes} / … 都在里面）。排序按 city id 字典序。
   *
   * @param social 社会切片
   * @param regionFilter 只列 {@code SocialCity.region} 逐字等于它的城；null/空白 = 不筛（**不按落点猜归属**）
   * @param visible 资源级可见谓词（非 null 时逐城按 {@code at} 过滤，口径与 {@code seesHex} 相同）；null = 全量
   */
  public static Map<String, Object> cities(
      SocialData social, String regionFilter, Predicate<HexCoord> visible) {
    Objects.requireNonNull(social, "social");
    List<SocialCity> ordered = new ArrayList<>(social.cities().values());
    ordered.sort(Comparator.comparing(city -> city.id().value()));
    List<Map<String, Object>> out = new ArrayList<>();
    for (SocialCity city : ordered) {
      if (regionFilter != null && !regionFilter.isBlank()) {
        boolean matched =
            city.region().map(region -> region.value().equals(regionFilter)).orElse(false);
        if (!matched) {
          continue;
        }
      }
      if (visible != null && !visible.test(city.at())) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", city.id().value());
      item.put("name", city.name());
      item.put("at", hexCoord(city.at()));
      item.put("region", city.region().map(RegionId::value).orElse(null));
      item.put("tier", city.props().get("tier"));
      item.put("population", social.urbanPopulationAt(city.id()));
      item.put("props", new LinkedHashMap<>(city.props()));
      out.add(item);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("cities", out);
    return view;
  }

  /**
   * ★ F1：**区域汇总**（{@code GET /api/map/regions/summary} 的视图）。
   *
   * <p>★ 口径（逐条对上 F1 计划 §2.2）：
   *
   * <ul>
   *   <li>{@code population} = Region 的 hex 集合逐格 {@link SocialData#populationAt(HexCoord)} 之和。
   *       区域可以重叠 ⇒ 同一格会在两个区域里各计一次；**这不是世界守恒量**，是"区域格集求和"（F1 有意如此）。
   *   <li>{@code cityCount} / {@code cityPopulation} **只认** {@code SocialCity.region == id}；region
   *       为空的城 不按落点猜归属（缺 region 的城不进入任何区域汇总）。
   *   <li>★ R2a 追加：{@code containedCityCount} / {@code containedCityPopulation} = 该 Region 的 hex 集内
   *       <b>按城市落点</b>（{@code SocialCity.at}）统计的城市数与城市人口（人口仍现算）；国家/省/首都区各自按自己的 hex 集计，重叠区域各自计数。旧
   *       {@code cityCount} / {@code cityPopulation} 口径与字段名一字不变。
   *   <li>{@code unitCount} = 该单位在 {@code at} 时刻的 {@link UnitState#effectivePosition} 落在 Region hex
   *       集合内的数量（含 GOV）；{@code govCount} 是其中 {@code unit.module()} 为 {@link GovFormation} 的数量。
   *   <li>★ F2 追加：{@code ruralPopulation} / {@code urbanPopulation} = region hex 集内**有 {@link
   *       PopulationGroup}** 的格按城乡二分求和（旧序列格无法二分、不计入）；{@code grainStock} / {@code silverMoney} /
   *       {@code goodsTotal} = region hex 集内 actor 账本的粮 / silver / 逐商品余额 合计（键按商品 id
   *       字典序）。区域重叠口径同上：逐区域格集求和，不是世界守恒量。
   * </ul>
   *
   * <p>★ 按 region id 字典序发；同一份状态两次调用逐字节相同。
   */
  public static Map<String, Object> regionSummaries(
      GameMap map, SocialData social, UnitState units, ActorData actors, SimosTimestamp at) {
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(actors, "actors");
    Objects.requireNonNull(at, "at");
    // ★ 人口按格预聚合一次（region 总格数可能上万，逐区域逐格调 populationAt 会退化成 O(格 × 批次)）。
    Map<HexCoord, Long> populationByHex = new LinkedHashMap<>();
    for (Region region : map.regions().values()) {
      for (HexCoord hex : region.hexes()) {
        populationByHex.putIfAbsent(hex, 0L);
      }
    }
    // ★ F2：城乡人口与人口总量在同一趟 groups 遍历里预聚合（与 SocialData.urbanRuralAt 同判 ——
    //   PopulationLots.isUrban 是城乡归属的唯一拼写点）。long[2] = [urban, rural]。
    Map<HexCoord, long[]> urbanRuralByHex = new LinkedHashMap<>();
    for (PopulationGroup group : social.groups().values()) {
      // ★ S2：位置来自所属家户（UNIT 家户没有格，不进逐格汇总）。
      HexCoord lotHex = social.hexOfLot(group.id()).orElse(null);
      if (lotHex == null) {
        continue;
      }
      Long current = populationByHex.get(lotHex);
      if (current != null) {
        populationByHex.put(lotHex, current + group.count());
      }
      long[] counts = urbanRuralByHex.computeIfAbsent(lotHex, ignored -> new long[2]);
      if (PopulationLots.isUrban(group)) {
        counts[0] += group.count();
      } else {
        counts[1] += group.count();
      }
    }
    // ★ F2：actor 账本按 hex 预聚合一次（粮库存 / 银货币 / 逐商品合计），region 只在自己的 hex 集上查表——
    //   不为每个 region 重扫全表。
    Map<HexCoord, Long> grainStockByHex = new LinkedHashMap<>();
    Map<HexCoord, Long> silverByHex = new LinkedHashMap<>();
    Map<HexCoord, Map<String, Long>> goodsByHex = new LinkedHashMap<>();
    for (GoodsAccount account : actors.accounts().values()) {
      HexCoord hex = householdHex(social, account.key().household());
      grainStockByHex.merge(hex, account.balances().getOrDefault(GRAIN, 0L), Long::sum);
      silverByHex.merge(
          hex, account.money().getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L), Long::sum);
      Map<String, Long> goods = goodsByHex.computeIfAbsent(hex, ignored -> new LinkedHashMap<>());
      for (Map.Entry<CommodityId, Long> entry : account.balances().entrySet()) {
        goods.merge(entry.getKey().value(), entry.getValue(), Long::sum);
      }
    }
    // 城市数 / 城市人口按**显式 region 归属**预聚合；region 缺失的城不进任何区域。
    Map<String, long[]> cityByRegion = new TreeMap<>();
    for (SocialCity city : social.cities().values()) {
      if (city.region().isEmpty()) {
        continue;
      }
      long[] aggregate =
          cityByRegion.computeIfAbsent(city.region().get().value(), ignored -> new long[2]);
      aggregate[0] += 1L;
      aggregate[1] += social.urbanPopulationAt(city.id());
    }
    // ★ R2a：containedCityCount / containedCityPopulation 按**落点** at 预聚合一次；区域可重叠 ⇒ 各自计数。
    Map<HexCoord, long[]> containedCityByHex = new LinkedHashMap<>();
    for (SocialCity city : social.cities().values()) {
      long[] aggregate = containedCityByHex.computeIfAbsent(city.at(), ignored -> new long[2]);
      aggregate[0] += 1L;
      aggregate[1] += social.urbanPopulationAt(city.id());
    }
    List<RegionId> ordered = new ArrayList<>(map.regions().keySet());
    ordered.sort(Comparator.comparing(RegionId::value));
    List<Map<String, Object>> out = new ArrayList<>(ordered.size());
    for (RegionId id : ordered) {
      Region region = map.regions().get(id);
      long population = 0L;
      long ruralPopulation = 0L;
      long urbanPopulation = 0L;
      long containedCityCount = 0L;
      long containedCityPopulation = 0L;
      long grainStock = 0L;
      long silverMoney = 0L;
      // 逐商品合计只收 region 格集里出现的键；TreeMap 保证商品 id 字典序（与状态插入序无关）。
      Map<String, Long> goodsTotal = new TreeMap<>();
      for (HexCoord hex : region.hexes()) {
        Long value = populationByHex.get(hex);
        if (value != null) {
          population += value;
        }
        long[] urbanRural = urbanRuralByHex.get(hex);
        if (urbanRural != null) {
          urbanPopulation += urbanRural[0];
          ruralPopulation += urbanRural[1];
        }
        long[] containedCity = containedCityByHex.get(hex);
        if (containedCity != null) {
          containedCityCount += containedCity[0];
          containedCityPopulation += containedCity[1];
        }
        Long grain = grainStockByHex.get(hex);
        if (grain != null) {
          grainStock += grain;
        }
        Long silver = silverByHex.get(hex);
        if (silver != null) {
          silverMoney += silver;
        }
        Map<String, Long> goods = goodsByHex.get(hex);
        if (goods != null) {
          for (Map.Entry<String, Long> entry : goods.entrySet()) {
            goodsTotal.merge(entry.getKey(), entry.getValue(), Long::sum);
          }
        }
      }
      long[] cityAggregate = cityByRegion.getOrDefault(id.value(), new long[2]);
      long unitCount = 0L;
      long govCount = 0L;
      for (Unit unit : units.units().values()) {
        Optional<HexCoord> position = units.effectivePosition(unit.id(), at);
        if (position.isEmpty() || !region.hexes().contains(position.get())) {
          continue;
        }
        unitCount += 1L;
        if (unit.module().map(module -> module instanceof GovFormation).orElse(false)) {
          govCount += 1L;
        }
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", id.value());
      item.put("name", region.name());
      item.put("hexCount", region.hexes().size());
      item.put("meta", regionMeta(region.meta()));
      item.put("population", population);
      item.put("cityCount", cityAggregate[0]);
      item.put("cityPopulation", cityAggregate[1]);
      // ★ R2a 追加：按落点 at 统计的 contained 口径（旧 cityCount/cityPopulation 一字不动，只追加）。
      item.put("containedCityCount", containedCityCount);
      item.put("containedCityPopulation", containedCityPopulation);
      item.put("unitCount", unitCount);
      item.put("govCount", govCount);
      // ★ F2 新增字段（既有字段名与形状一字不动，只追加）：城乡人口 / 粮库存 / 银货币 / 逐商品合计。
      item.put("ruralPopulation", ruralPopulation);
      item.put("urbanPopulation", urbanPopulation);
      item.put("grainStock", grainStock);
      item.put("silverMoney", silverMoney);
      item.put("goodsTotal", goodsTotal);
      out.add(item);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("regions", out);
    return view;
  }

  /**
   * ★ F2：**地图热力图**（{@code GET /api/map/heatmap} 的视图）。
   *
   * <p>★ 只读、紧凑聚合：每个指标只发**有事实**的格（{@code cells}），不整份发 {@code economyHex} 的 84 KB 逐格视图。 {@code cells}
   * 按 {@link HexCoord} 自然序（先 {@code q} 后 {@code r}）发；同状态两次调用逐字节相同。
   *
   * <p>★★ **算不出的整层具名不可用**（{@code cells: []}、{@code unavailable} 写明原因，绝不填 0 冒充）：未知 id、 以及
   * production-runtime 没有逐格来源的 {@code grainCycleUnmet}。
   *
   * <p>口径逐条对上 F2 设计增补（用户 2026-10-01 已确认）：
   *
   * <ul>
   *   <li>人口：{@code populations} ∪ 家户成员批次落点；有家户批次的格用家户成员求和，无批次的旧序列格按 {@link
   *       SocialData#headlinePopulationAt} 回退；城乡二分只对有 {@link PopulationGroup} 的格。
   *   <li>粮食库存 / 日耗 / 覆盖天数：逐格 actor {@code GoodsAccount} 与 {@code ClassRow.naturalNeeds} 同源。
   *   <li>银货币：逐格 actor {@code GoodsAccount.money} 的 silver 分栏。
   *   <li>{@code grainCycleUnmet}：R3a 起旧市场报告组件已删除，production-runtime 不产生逐格周期缺口 ⇒ 整层 unavailable。
   * </ul>
   *
   * <p>★ {@code stats.median} 的算法是确定的：排序后奇数取中位、偶数取两中位平均（double）；空 {@code cells} ⇒ {@code count=0} 且
   * min/median/max 为 {@code null}（不填 0 冒充）。
   *
   * @param metric 指标 id；未知 id ⇒ {@code unavailable} 具名、{@code cells: []}
   */
  public static Map<String, Object> heatmap(SimulationState state, String metric) {
    Objects.requireNonNull(state, "state");
    String id = metric == null ? "" : metric;
    return switch (id) {
      case HEATMAP_POPULATION_TOTAL ->
          populationHeatmap(state, id, "人口·总", PopulationHeatmapMetric.TOTAL);
      case HEATMAP_POPULATION_RURAL ->
          populationHeatmap(state, id, "人口·农村", PopulationHeatmapMetric.RURAL);
      case HEATMAP_POPULATION_URBAN ->
          populationHeatmap(state, id, "人口·城市", PopulationHeatmapMetric.URBAN);
      case HEATMAP_GRAIN_STOCK -> grainStockHeatmap(state, id);
      case HEATMAP_GRAIN_DAILY_NEED -> grainDailyNeedHeatmap(state, id);
      case HEATMAP_GRAIN_COVERAGE_DAYS -> grainCoverageDaysHeatmap(state, id);
      case HEATMAP_GRAIN_CYCLE_UNMET ->
          unavailableHeatmap(
              state,
              id,
              "粮食·周期缺口",
              "毫粮",
              "本周期累计（旧 MarketReport 口径）；production-runtime 无逐格来源",
              "R3a 起旧市场报告组件已删除；production-runtime 不产生逐格周期缺口"
                  + "（旧 FlowRow.unmetNeed 不作为代理，避免把 0 读成没有缺口）",
              new LinkedHashMap<>());
      case HEATMAP_MONEY_SILVER -> moneySilverHeatmap(state, id);
      default ->
          unavailableHeatmap(state, id, null, null, null, "未知指标: " + id, new LinkedHashMap<>());
    };
  }

  /** F2 人口三指标（总 / 农村 / 城市）：同一份 groups 预聚合，只改取值那一步。 */
  private static Map<String, Object> populationHeatmap(
      SimulationState state, String id, String label, PopulationHeatmapMetric kind) {
    SocialData social = socialData(state);
    SimosTimestamp at = state.meta().timestamp();
    // 一趟 groups 预聚合：long[2] = [urban, rural]（与 SocialData.urbanRuralAt 同判）。
    Map<HexCoord, long[]> groupsByHex = new LinkedHashMap<>();
    for (PopulationGroup group : social.groups().values()) {
      HexCoord lotHex = social.hexOfLot(group.id()).orElse(null);
      if (lotHex == null) {
        continue; // UNIT 家户不进逐格热力图（S3 再接 unit 口径）
      }
      long[] counts = groupsByHex.computeIfAbsent(lotHex, ignored -> new long[2]);
      if (PopulationLots.isUrban(group)) {
        counts[0] += group.count();
      } else {
        counts[1] += group.count();
      }
    }
    long legacySeriesHexes = 0L;
    for (HexCoord hex : social.populations().keySet()) {
      if (!groupsByHex.containsKey(hex)) {
        legacySeriesHexes++;
      }
    }
    TreeMap<HexCoord, Long> values = new TreeMap<>();
    if (kind == PopulationHeatmapMetric.TOTAL) {
      for (Map.Entry<HexCoord, PopulationSeries> entry : social.populations().entrySet()) {
        PopulationHeadline headline =
            social.headlinePopulationAt(entry.getKey(), entry.getValue(), at);
        values.put(entry.getKey(), headline.value());
      }
      // 只有 groups、没有旧序列的格（构造期不变式之外，防御性保留）：值 = 批次求和。
      for (Map.Entry<HexCoord, long[]> entry : groupsByHex.entrySet()) {
        values.computeIfAbsent(
            entry.getKey(), ignored -> entry.getValue()[0] + entry.getValue()[1]);
      }
    } else {
      int index = kind == PopulationHeatmapMetric.URBAN ? 0 : 1;
      for (Map.Entry<HexCoord, long[]> entry : groupsByHex.entrySet()) {
        values.put(entry.getKey(), entry.getValue()[index]);
      }
    }
    Map<String, Object> notes = new LinkedHashMap<>();
    notes.put("legacySeriesHexes", legacySeriesHexes);
    notes.put(
        "legacySeriesNote",
        kind == PopulationHeatmapMetric.TOTAL
            ? "无批次的格按 headlinePopulationAt 回退旧序列；有批次的格用批次求和"
            : "旧序列格无法城乡二分（仅旧 populations 序列、没有 PopulationGroup 的格未计入本层）");
    return longHeatmap(state, id, label, "人", "批次口径（有批次的格）；时点快照", values, notes);
  }

  /** F2 粮食库存：逐格 actor GoodsAccount 的 grain 余额合计（有账户的格全发，0 也是事实）。 */
  private static Map<String, Object> grainStockHeatmap(SimulationState state, String id) {
    TreeMap<HexCoord, Long> values = new TreeMap<>();
    for (GoodsAccount account : actorData(state).accounts().values()) {
      values.merge(householdHex(state, account.key().household()), account.balances().getOrDefault(GRAIN, 0L), Long::sum);
    }
    return longHeatmap(
        state, id, "粮食·库存", "毫粮", "逐格 actor GoodsAccount 粮余额合计（时点）", values, new LinkedHashMap<>());
  }

  /** F2 粮食日耗：逐格 ClassRow.naturalNeeds 的 grain 合计（与 economyHex.grainDailyConsumption 同源）。 */
  private static Map<String, Object> grainDailyNeedHeatmap(SimulationState state, String id) {
    TreeMap<HexCoord, Long> values = new TreeMap<>();
    for (ClassRow row : economyData(state).classes().values()) {
      values.merge(row.view().hex(), row.naturalNeeds().getOrDefault(GRAIN, 0L), Long::sum);
    }
    return longHeatmap(
        state,
        id,
        "粮食·日耗",
        "毫粮/日",
        "最近一次结算日 naturalNeeds；与 economyHex.grainDailyConsumption 同源",
        values,
        new LinkedHashMap<>());
  }

  /** F2 粮食覆盖天数：同一趟聚合 stock 与 dailyNeed；分母 ≤ 0 或库存账缺失的格不入层，notes 记数。 */
  private static Map<String, Object> grainCoverageDaysHeatmap(SimulationState state, String id) {
    TreeMap<HexCoord, Long> stockByHex = new TreeMap<>();
    for (GoodsAccount account : actorData(state).accounts().values()) {
      stockByHex.merge(
          householdHex(state, account.key().household()),
          account.balances().getOrDefault(GRAIN, 0L),
          Long::sum);
    }
    TreeMap<HexCoord, Long> dailyNeedByHex = new TreeMap<>();
    for (ClassRow row : economyData(state).classes().values()) {
      dailyNeedByHex.merge(row.view().hex(), row.naturalNeeds().getOrDefault(GRAIN, 0L), Long::sum);
    }
    TreeMap<HexCoord, Double> values = new TreeMap<>();
    long skippedDailyNeedZero = 0L;
    long skippedNoGrainAccount = 0L;
    for (Map.Entry<HexCoord, Long> entry : dailyNeedByHex.entrySet()) {
      long dailyNeed = entry.getValue();
      if (dailyNeed <= 0L) {
        skippedDailyNeedZero++;
        continue;
      }
      Long grain = stockByHex.get(entry.getKey());
      if (grain == null) {
        skippedNoGrainAccount++;
        continue;
      }
      values.put(entry.getKey(), (double) grain / (double) dailyNeed);
    }
    Map<String, Object> notes = new LinkedHashMap<>();
    notes.put("skippedDailyNeedZero", skippedDailyNeedZero);
    notes.put("skippedNoGrainAccount", skippedNoGrainAccount);
    return doubleHeatmap(
        state,
        id,
        "粮食·覆盖天数",
        "天",
        "派生：grainStock ÷ grainDailyNeed（仅 dailyNeed > 0 的格）；库存时点、日耗取最近一次结算日",
        values,
        notes);
  }

  /** F2 银货币：逐格 actor GoodsAccount.money 的 silver 余额合计（有账户的格全发，0 也是事实）。 */
  private static Map<String, Object> moneySilverHeatmap(SimulationState state, String id) {
    TreeMap<HexCoord, Long> values = new TreeMap<>();
    for (GoodsAccount account : actorData(state).accounts().values()) {
      values.merge(
          householdHex(state, account.key().household()),
          account.money().getOrDefault(MoneyVocabulary.SILVER_CURRENCY, 0L),
          Long::sum);
    }
    return longHeatmap(
        state,
        id,
        "货币·银",
        "毫银",
        "逐格 actor GoodsAccount silver 余额合计（时点）",
        values,
        new LinkedHashMap<>());
  }

  /** 整层不可用（未知 id / 结构性缺源）：cells=[]、stats 全 null、unavailable 具名，绝不填 0。 */
  private static Map<String, Object> unavailableHeatmap(
      SimulationState state,
      String id,
      String label,
      String unit,
      String scope,
      String reason,
      Map<String, Object> notes) {
    Map<String, Object> view = heatmapBase(state, id, label, unit, scope);
    view.put("cells", List.of());
    view.put("stats", emptyStats());
    view.put("unavailable", reason);
    view.put("notes", notes);
    return view;
  }

  /** 热力图公共头：键序 metric,label,unit,scope,tick。 */
  private static Map<String, Object> heatmapBase(
      SimulationState state, String id, String label, String unit, String scope) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("metric", id);
    view.put("label", label);
    view.put("unit", unit);
    view.put("scope", scope);
    view.put("tick", state.meta().timestamp().tick());
    return view;
  }

  /** 整数格值的最终装配：cells 已按 (q,r) 有序，值为 Long 原样发（不折 double）。 */
  private static Map<String, Object> longHeatmap(
      SimulationState state,
      String id,
      String label,
      String unit,
      String scope,
      TreeMap<HexCoord, Long> values,
      Map<String, Object> notes) {
    List<Map<String, Object>> cells = new ArrayList<>(values.size());
    List<Long> allValues = new ArrayList<>(values.size());
    for (Map.Entry<HexCoord, Long> entry : values.entrySet()) {
      cells.add(heatmapCell(entry.getKey(), entry.getValue()));
      allValues.add(entry.getValue());
    }
    Map<String, Object> view = heatmapBase(state, id, label, unit, scope);
    view.put("cells", cells);
    view.put("stats", longStats(allValues));
    view.put("unavailable", null);
    view.put("notes", notes);
    return view;
  }

  /** 派生（double）格值的最终装配：cells 已按 (q,r) 有序。 */
  private static Map<String, Object> doubleHeatmap(
      SimulationState state,
      String id,
      String label,
      String unit,
      String scope,
      TreeMap<HexCoord, Double> values,
      Map<String, Object> notes) {
    List<Map<String, Object>> cells = new ArrayList<>(values.size());
    List<Double> allValues = new ArrayList<>(values.size());
    for (Map.Entry<HexCoord, Double> entry : values.entrySet()) {
      cells.add(heatmapCell(entry.getKey(), entry.getValue()));
      allValues.add(entry.getValue());
    }
    Map<String, Object> view = heatmapBase(state, id, label, unit, scope);
    view.put("cells", cells);
    view.put("stats", doubleStats(allValues));
    view.put("unavailable", null);
    view.put("notes", notes);
    return view;
  }

  /** 一个热力格：{@code {q,r,value}}（键序固定）。 */
  private static Map<String, Object> heatmapCell(HexCoord hex, Number value) {
    Map<String, Object> cell = new LinkedHashMap<>();
    cell.put("q", hex.q());
    cell.put("r", hex.r());
    cell.put("value", value);
    return cell;
  }

  /** 空 stats：count=0，min/median/max = null（不填 0 冒充）。 */
  private static Map<String, Object> emptyStats() {
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("count", 0);
    stats.put("min", null);
    stats.put("median", null);
    stats.put("max", null);
    return stats;
  }

  /** 整数指标的 stats：median 为 double（偶数取两中位平均）。 */
  private static Map<String, Object> longStats(List<Long> values) {
    if (values.isEmpty()) {
      return emptyStats();
    }
    List<Long> sorted = new ArrayList<>(values);
    sorted.sort(Comparator.naturalOrder());
    int size = sorted.size();
    double median =
        size % 2 == 1
            ? sorted.get(size / 2).doubleValue()
            : ((double) sorted.get(size / 2 - 1) + (double) sorted.get(size / 2)) / 2.0;
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("count", size);
    stats.put("min", sorted.get(0));
    stats.put("median", median);
    stats.put("max", sorted.get(size - 1));
    return stats;
  }

  /** 派生指标的 stats（值本就是 double）。 */
  private static Map<String, Object> doubleStats(List<Double> values) {
    if (values.isEmpty()) {
      return emptyStats();
    }
    List<Double> sorted = new ArrayList<>(values);
    sorted.sort(Comparator.naturalOrder());
    int size = sorted.size();
    double median =
        size % 2 == 1
            ? sorted.get(size / 2)
            : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2.0;
    Map<String, Object> stats = new LinkedHashMap<>();
    stats.put("count", size);
    stats.put("min", sorted.get(0));
    stats.put("median", median);
    stats.put("max", sorted.get(size - 1));
    return stats;
  }

  /**
   * 连通性组的**完整定义**（T3，spec §三.6）：{@code [{id,name,color,description,visible,properties}…]}，按组 id 字典序
   * （与状态插入序无关 ⇒ 响应字节可复现）。UI 的 kind 候选**只能**来自这里（不硬编码 river/road）。
   */
  private static List<Map<String, Object>> pathwayGroupDefinitions(GameMap map) {
    List<String> ids = new ArrayList<>(map.pathwayGroups().keySet());
    ids.sort(Comparator.naturalOrder());
    List<Map<String, Object>> out = new ArrayList<>(ids.size());
    for (String id : ids) {
      PathwayGroup group = map.pathwayGroups().get(id);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", group.id());
      view.put("name", group.name());
      view.put("color", group.color());
      view.put("description", group.description());
      view.put("visible", group.visible());
      List<Map<String, Object>> properties = new ArrayList<>(group.properties().size());
      for (Map.Entry<String, PathwayGroup.PropertyDef> entry : group.properties().entrySet()) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", entry.getValue().type());
        def.put("defaultValue", entry.getValue().defaultValue());
        def.put("description", entry.getValue().description());
        properties.add(def);
      }
      view.put("properties", properties);
      out.add(view);
    }
    return out;
  }

  /** 全图边表（T3）：{@code [{edge,pathways}…]}，边按 {@link EdgeRef} 自然序、pathways 按字典序。 */
  private static List<Map<String, Object>> allEdgeViews(Map<EdgeRef, EdgeTags> edges) {
    List<EdgeRef> ordered = new ArrayList<>(edges.keySet());
    ordered.sort(Comparator.naturalOrder());
    List<Map<String, Object>> out = new ArrayList<>(ordered.size());
    for (EdgeRef edge : ordered) {
      out.add(edgeEntry(edge, edges.get(edge)));
    }
    return out;
  }

  /**
   * 权威地形块的 JSON 视图（M9 T13）：{@code [{id,terrain,hexCount,boundaries:[[u,w,u,w,…]…]}…]}。
   *
   * <p>★ **顶点是整数标签**（{@link HexVertex#u()}/{@link HexVertex#w()}，M9 T11 起）：格角顶点的 {@code (u,w)}
   * **本就是整数**，直接发标签既**逐字节精确**（不再有浮点量化）又**显著省流**（比 {@code {"x":…,"y":…}} 对象少约 2/3 字节）。客户端按 {@code x
   * = u·√3/2、y = w/2} 还原（唯一的格式转换点在 {@code webui/blocks.js}）。★ 块按 {@link BlockId} 全序 ——
   * 与状态里的插入顺序无关，跨进程 / 跨重放都可复现。
   */
  private static List<Map<String, Object>> blockViews(GameMap map) {
    List<BlockId> ids = new ArrayList<>(map.terrainBlocks().keySet());
    ids.sort(Comparator.naturalOrder());
    List<Map<String, Object>> out = new ArrayList<>(ids.size());
    for (BlockId id : ids) {
      TerrainBlock block = map.terrainBlocks().get(id);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", id.toString());
      item.put("terrain", block.terrain());
      item.put("hexCount", block.hexes().size());
      List<List<Integer>> rings = new ArrayList<>(block.boundary().rings().size());
      for (List<HexVertex> ring : block.boundary().rings()) {
        rings.add(closedLabelRing(ring));
      }
      item.put("boundaries", rings);
      out.add(item);
    }
    return out;
  }

  /**
   * 一条环的**整数顶点标签**：{@code [u0,w0,u1,w1,…]}，首顶点补到末尾（闭合）。
   *
   * <p>每条环的顶点数 &gt; 2，且首尾同点（闭合）。客户端据此逐对还原世界坐标。
   */
  private static List<Integer> closedLabelRing(List<HexVertex> ring) {
    List<Integer> out = new ArrayList<>(ring.size() * 2 + 2);
    for (HexVertex vertex : ring) {
      out.add(vertex.u());
      out.add(vertex.w());
    }
    out.add(ring.get(0).u());
    out.add(ring.get(0).w());
    return out;
  }

  /**
   * 地形词表的**完整定义**（M7 T2，spec §3.2）：由 {@code [key…]} 切换为 {@code [{完整定义}…]}。
   *
   * <p>★ **数据来源仍是状态里的** {@code map.terrainTypes()}（不查 {@link TerrainCatalog#defaults()}——那是第二份真相）；
   * {@code TerrainCatalog.KEYS} **只用来定序**（高度升序）。词表里出现 KEYS 之外的 key（理论不该有）时排到末尾并按字典序， 保证响应字节可复现。
   *
   * <p>★ 十字段与 {@link TerrainType} 的 record 组件一一对应——前端图例/色板因此可**完全**取自后端（T4 删硬编码色表）。
   */
  private static List<Map<String, Object>> terrainTypeDefinitions(GameMap map) {
    List<String> keys = new ArrayList<>(map.terrainTypes().keySet());
    keys.sort(
        Comparator.comparingInt(
                (String key) -> {
                  int index = TerrainCatalog.KEYS.indexOf(key);
                  return index < 0 ? Integer.MAX_VALUE : index;
                })
            .thenComparing(Comparator.naturalOrder()));
    List<Map<String, Object>> out = new ArrayList<>(keys.size());
    for (String key : keys) {
      out.add(terrainType(map.terrainTypes().get(key)));
    }
    return out;
  }

  /** 单个 {@link TerrainType} 的 JSON 形（十字段，顺序与 record 组件一致）。 */
  private static Map<String, Object> terrainType(TerrainType type) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("key", type.key());
    view.put("name", type.name());
    view.put("color", type.color());
    view.put("minHeight", type.minHeight());
    view.put("maxHeight", type.maxHeight());
    view.put("food", type.food());
    view.put("gold", type.gold());
    view.put("stone", type.stone());
    view.put("moveCost", type.moveCost());
    view.put("description", type.description());
    return view;
  }

  /**
   * ★★ **经济切片该格的读数**（R2a 的 G1 读口）——GUI（{@code GET /api/economy/hex}）与 MCP 读工具（{@code
   * simos.economy.hex}）**共用这一份**（AGENT.md §8.3：不许在路由层另拼一份视图）。
   *
   * <p>★ **逐值、可复现**：该格的产业按 {@code IndustryId} 字典序、家户行按 **(居住类型, 阶层 id)** 字典序、库存/需求表按键字典序
   * 发出——状态里的插入序不是内容的纯函数，跟着它走响应字节会抖。
   *
   * <p>★ **该格的产业怎么认出来**：经 {@link IndustryHexKeys#at}（"产业属于哪一格"的**唯一拼写点**）；本方法**不**自己拼/拆 id。
   *
   * <p>★★ **H0.2 的两处形状变化**：① 家户行（{@code ClassRow}）**挂在格上**（键 = {@code (格, 居住类型, 阶层)}）， 故 {@code
   * classes} 是**格级**数组、每个产业对象里**没有** {@code classes}；② 土地不再在行上 ⇒ 该格的 {@code landMilliMu} 由该格产业的
   * {@code capacity[LAND]} 合计而来（K3），行级不再发这个恒为 0 的字段。
   *
   * <p>★ **未激活**（§6.6：{@code meta} 空）或该格没有产业 ⇒ 各聚合量为 0、{@code industries} 为空数组——**不 404**：
   * "这一格没有经济数据"与"这一格不存在"是两件事，前者要能在界面上看见。
   *
   * <p>★★ <b>M2.7 追加</b>：本视图新增 {@code tick} / {@code lastSettledDay} / {@code
   * cycleNaturalNeedMilli}（丙条累加器合计）与 {@code marketReadout}（焦点区逐商品读数；进程内报告派生部分见其类注）。 不传状态的 3
   * 参重载仍保留（没有 tick / 没有进程内报告 ⇒ 那一栏具名 unavailable，不填 0）。
   */
  public static Map<String, Object> economyHex(HexCoord coord, EconomyData data, ActorData actors) {
    return economyHex(
        coord,
        data,
        actors,
        Optional.empty(),
        -1L,
        "读口没有 SimulationState 上下文（3 参重载）⇒ 拿不到 tick 与进程内市场报告");
  }

  /** ★★ M2.7：GUI / MCP 的正式入口（从状态装配焦点区读数与 tick；R3a 起旧市场读数组件已删除）。 */
  public static Map<String, Object> economyHex(HexCoord coord, SimulationState state) {
    EconomyData data = economyData(state);
    ActorData actors = actorData(state);
    long tick = state.meta().timestamp().tick();
    // ★★ R3a：旧市场读数组件（MarketReadoutAssembly / MarketReadout / MarketReportFeed）已随旧撮合引擎删除；
    //   production-runtime 生产路径不产生进程内市场报告 ⇒ 这一栏具名不可得，不填 0、不假装空数组。
    return economyHex(
        coord,
        data,
        actors,
        Optional.empty(),
        tick,
        "R3a 起旧市场读数组件已删除（production-runtime 生产路径不产生进程内市场报告）⇒ 没有区级市场读数");
  }

  private static Map<String, Object> economyHex(
      HexCoord coord,
      EconomyData data,
      ActorData actors,
      Optional<MarketReport> report,
      long tick,
      String readoutUnavailable) {
    Map<String, Object> view = hexCoord(coord);
    view.put("activated", data.meta().isPresent());
    // ★★ M2.7：把读数的坐标与窗口随视图发出去（此前读口没有 tick，读者无法判断"这个数属于哪一天"）。
    view.put("tick", tick < 0L ? null : tick);
    Long lastSettledDay =
        data.meta().isPresent() && tick >= data.meta().orElseThrow().activatedDay() ? tick : null;
    view.put("lastSettledDay", lastSettledDay);
    view.put("lastSettledDayNote", "由当前 state tick 派生（经济日结算与状态推进同步）；naturalNeeds 写于最近一次结算日");
    long population = 0L;
    long laborMilli = 0L;
    // ★★ M1.8：该格**按阶层参与率折扣后的**每日劳动合计（逐值 = Σ 各行的 participationAdjustedLaborMilli）
    //   —— 与 {@code laborMilli}（毛量）并排发，四档人均劳动因此可逐值核对（见 classRowView）。
    long participationAdjustedLaborMilli = 0L;
    long landMilliMu = 0L;
    long debtPrincipal = 0L;
    long debtCount = 0L;
    // ★★ M1.5：债权人侧合计（今天这两栏不存在 ⇒ 读口只能看见"谁欠着"这一半）。
    long creditPrincipal = 0L;
    long creditCount = 0L;
    long grainDailyConsumption = 0L;
    // ★★ M2.7 丙条仪器：该格 Σ 各行的**本周期累计自然口粮需要**（毫粮；人口逐日变时唯一与
    //   "本周期累计未满足需求"同窗口的分母）。★ 不再用"某一天人口 × 整周期配额"并排冒充它。
    long cycleNaturalNeedMilli = 0L;
    // ★★ H1：**商品库存的唯一真源是 actor 侧的 {@code GoodsAccount}**（裁定 D3-C/K1；{@code ClassRow} 里没有 goods）
    //   ⇒ 本视图的商品读数从**该格的全部账户**求和，逐值等于 {@link #economyOwnership} 的 {@code actorGoodsTotal}。
    //   ★ 行侧那一栏（旧版的 Σ{@code row.goods()}）**结构性消失** —— 不是"读不到"，是"那里已经没有这本账"。
    Map<String, Long> goods = new TreeMap<>();
    // ★★ H4：**货币与商品同住一本 {@code GoodsAccount}**（裁定 M2：两个独立身份、两张余额表）⇒ 货币读数走
    //   **同一趟**遍历（两次遍历会在"账本中途变化"时给出两个不同世界的读数）。
    Map<String, Long> actorMoneyTotal = new TreeMap<>();
    for (GoodsAccount account : accountsAt(data, actors, coord)) {
      mergeInto(goods, account.balances());
      mergeMoneyInto(actorMoneyTotal, account.money());
    }
    long grainStock = goods.getOrDefault(EconomyVocabulary.GRAIN_COMMODITY_ID, 0L);
    List<Map<String, Object>> industries = new ArrayList<>();
    for (IndustryId id : IndustryHexKeys.at(data.industries(), coord.q(), coord.r())) {
      Industry industry = data.industries().get(id);
      // ★★ R3B.2：该行业在本格的 unit（按 unit id canonical 序；旧口径"一产业一 unit"下恰一条）。
      List<ProductionUnit> units = new ArrayList<>();
      for (ProductionUnit unit : data.units().values()) {
        if (unit.industry().equals(id)) {
          units.add(unit);
        }
      }
      units.sort(Comparator.comparing(unit -> unit.id().value()));
      // ★★ R3B.2：土地不再是**产业模板**的产能，而是 unit 从 AssetShare 派生的可用资产 ⇒ 该格的亩数
      //   由各 unit 的 usableAssets[LAND] 求和（一产业一 unit 时与旧 capacity 逐值相同）。
      Map<AssetKind, Long> unitAssets = new TreeMap<>();
      Map<String, Long> cycleInputUsed = new TreeMap<>();
      for (ProductionUnit unit : units) {
        for (Map.Entry<AssetKind, Long> asset :
            ProductionUnitBook.usableAssets(unit, data.assetShares()).entrySet()) {
          unitAssets.merge(asset.getKey(), asset.getValue(), Long::sum);
        }
        for (Map.Entry<CommodityId, Long> used : unit.cycleInputUsedMilli().entrySet()) {
          cycleInputUsed.merge(used.getKey().value(), used.getValue(), Long::sum);
        }
      }
      landMilliMu += unitAssets.getOrDefault(AssetKind.LAND, 0L);
      // ★★ M1.7：给养义务读口要"按本周期实际劳动量" —— 走结算侧的**同一个函数**（{@code 旧结算引擎（R3a 已删除）.laborOfCohort}），
      //   不在视图层另写一套（口径两处各写一遍 = 读到的义务与实付的义务会漂开）。
      Map<HouseholdId, Long> cycleLabor =
          laborOfCohort(data.classes(), coord, industry.cycleDays());
      // ★★ R3B.2：关系挂在 unit 上 ⇒ 产业行的"给养义务"兼容字段取**第一条 unit** 的关系
      //   （一产业一 unit 时与旧读法逐值相同；多 unit 时逐条见 units[].relation）。
      ProductionUnit compatUnit = units.isEmpty() ? null : units.get(0);
      Map<String, Object> industryView =
          industryView(
              industry,
              compatUnit == null ? null : data.relations().get(compatUnit.id()),
              cycleLabor);
      // ★★ 旧 industry 字段的兼容一版：operator/progressDays/capacity/cycleInputUsedMilli 从 unit 汇总
      //   （一产业一 unit 时逐值等于旧读法；多 unit 时是确定性聚合，读口新增 units[] 逐条可见）。
      ProductionUnit first = compatUnit;
      industryView.put("operator", first == null ? null : actorRefView(first.operator()));
      long progressDays = 0L;
      for (ProductionUnit unit : units) {
        progressDays = Math.max(progressDays, unit.progressDays());
      }
      industryView.put("progressDays", progressDays);
      Map<String, Long> capacityCompat = new TreeMap<>();
      for (Map.Entry<AssetKind, Long> asset : unitAssets.entrySet()) {
        capacityCompat.put(asset.getKey().name(), asset.getValue());
      }
      industryView.put("capacity", capacityCompat);
      industryView.put("cycleInputUsedMilli", cycleInputUsed);
      // ★★ R3B.2 的正式读口：逐 unit 一行（operator/mode/assets/progress/condition/relation）。
      List<Map<String, Object>> unitViews = new ArrayList<>(units.size());
      for (ProductionUnit unit : units) {
        unitViews.add(
            productionUnitView(
                unit,
                industry,
                data.assetShares(),
                data.relations().get(unit.id()),
                data.operatorConditions().get(unit.id())));
      }
      industryView.put("units", unitViews);
      // ★★ S3：经营者状态机读数（空表 = 旧档/还没关账；不伪造 ACTIVE）。兼容字段 = 第一条 unit 的条件。
      OperatorCondition condition =
          first == null ? null : data.operatorConditions().get(first.id());
      industryView.put("condition", condition == null ? null : operatorConditionView(condition));
      industries.add(industryView);
    }
    // ★★ H0.2：**家户行挂在格上**（键 = {@code (格, 居住类型, 阶层)}），不再属于任何产业 ⇒ 视图里它们是该格的一个数组。
    // ★★ M1.5：债权人侧索引**一次派生、整格复用**（{@link DebtIndex#byCreditor}；不在每一行里 O(债务) 重扫）。
    Map<HouseholdId, List<DebtContractId>> creditsByCohort =
        DebtIndex.byCreditor(data.debtContracts());
    List<Map<String, Object>> classes = new ArrayList<>();
    List<HouseholdId> classKeys = new ArrayList<>();
    for (HouseholdId key : cohortKeysAt(data, coord)) {
      ClassRow row = data.classes().get(key);
      population += row.population();
      laborMilli += row.laborMilli();
      participationAdjustedLaborMilli += row.participationAdjustedLaborMilli();
      grainDailyConsumption += row.naturalNeeds().getOrDefault(GRAIN, 0L);
      cycleNaturalNeedMilli += row.cycleNaturalNeedMilli();
      // 债务人侧：仍按行里的引用清点（它是放贷时写下的权威清单）。
      for (DebtContractId debtId : row.debts()) {
        DebtContract debt = data.debtContracts().get(debtId);
        if (debt != null) {
          debtCount++;
          debtPrincipal += debt.principal();
        }
      }
      // ★★ M1.5：债权人侧——"这一格的家户应收多少"以前完全读不到；逐条走同一张债务表（方向只是挂给谁）。
      List<DebtContractId> credits = creditsByCohort.getOrDefault(key, List.of());
      for (DebtContractId debtId : credits) {
        DebtContract debt = data.debtContracts().get(debtId);
        if (debt != null) {
          creditCount++;
          creditPrincipal += debt.principal();
        }
      }
      Map<String, Object> classView =
          classRowView(key, row, data.flows().get(key), actors, credits, data.debtContracts());
      // ★★ E5a：该户的阶层归属读数（含 consecutiveDebtStressCycles）。没有 ClassStanding ⇒ null + 具名原因，
      //   不伪造一个默认归属、也不填 0 冒充（旧路径以 ClassRow.view 为准；E5a 不产生任何阶层变动）。
      ClassStanding standing = data.classStandings().get(key);
      classView.put("classStanding", standing == null ? null : classStandingView(standing));
      classView.put(
          "classStandingUnavailable", standing == null ? CLASS_STANDING_UNAVAILABLE : null);
      classes.add(classView);
      classKeys.add(key);
    }
    view.put("population", population);
    view.put("laborMilli", laborMilli);
    // ★★ M1.8：毛量旁边发**折扣后**的可用劳动（同一批行的两个口径并排 ⇒ 参与率是否真的进了计算可逐值核对）。
    view.put("participationAdjustedLaborMilli", participationAdjustedLaborMilli);
    view.put("landMilliMu", landMilliMu);
    view.put("goods", goods);
    // ★ R3a：该格粮库存合计与日耗合计（"看变化"的两个直接读数；单位 = 毫粮）。
    //   ★★ **日耗读的是结算写下的 {@code ClassRow.naturalNeeds}**（V5；spec §八.8 的"读数与结算同源"）：
    //     结算每天把**当日需求**（累计口粮的逐日差分）写进该字段，读口只是把它加起来 ⇒ 同一面板上的"人口"与"日耗"
    //     不会再分叉。★ 代价如实记：读口显示的是"**最近一次结算那天**"的需求（两次结算之间不刷新；本方法入参没有 tick，
    //     物理上复算不出结算当天那个数）。
    view.put("grainStock", grainStock);
    view.put("grainDailyConsumption", grainDailyConsumption);
    // ★★ M2.7 丙条仪器：本周期累计自然需要（与 unmetNeed/income/consumed 同为"本周期累计"窗口；新周期第一天重置）。
    //   ★ 人口快照口径随值发出：它用**每个结算日的日初人口**逐日累加，不是读口时刻的人口。
    view.put("cycleNaturalNeedMilli", cycleNaturalNeedMilli);
    view.put("cycleNaturalNeedClock", CYCLE_NATURAL_NEED_CLOCK);
    // ★ 日耗那一栏的窗口（与上面那栏不可并排当同一分母：丙条已查清两者口径本就不可比）。
    view.put("grainDailyConsumptionClock", GRAIN_DAILY_CONSUMPTION_CLOCK);
    // ★★ H6：**旧的行侧货币栏（{@code money}）已删** —— 它读的是 {@code Σ ClassRow.money()}，而 H1 起行里没有钱
    //   （{@code EconomySeeder} 写下的 {@code ClassRow.money} 恒为 0）⇒ 那是一栏**结构性的 0**：不是"这一格没钱"，
    //   是"那本账不存在"，读数的人只会把它当成真值（本仓最反对的"看起来在记"）。
    //   ★ 钱的真值只有一处：actor 侧 {@code GoodsAccount} 的**逐币种**合计（下面那一栏）—— 币种各自守恒，
    //     "跨币种求和的 money"本来也不是一个有意义的量（同 {@link #economyOwnership} 的口径）。
    // ★★ H4：**actor 侧的货币合计**（逐币种）—— 该格每一本 {@code GoodsAccount} 的钱，与 {@code goods} 同一趟遍历。
    view.put("actorMoneyTotal", actorMoneyTotal);
    // ★★ M1.6：**逐工具守恒的三个分栏**（私人流通 / 全部基础货币 /（将来）银行存款）—— 纯派生自上面那一趟
    //   同源遍历，不在视图层再扫一账；逐条口径见 {@link #moneyLayers}。
    view.put("moneyLayers", moneyLayers(actorMoneyTotal));
    // ★★ E3：发行/回笼/流通量与 actor kind / 家户阶层聚合（世界级时点口径；见方法注释）。
    view.put("moneyIssuance", moneyIssuanceView(data, moneyTotals(actors)));
    // ★★ E6c：这两栏同时进 dashboard.stocks ⇒ 只调用一次 E3 的唯一聚合算法，两处共用同一份。
    Map<String, Map<String, Long>> moneyByKind = moneyByActorKind(actors);
    Map<String, Map<String, Long>> moneyByClass = moneyByHouseholdClass(data, actors);
    view.put("moneyByActorKind", moneyByKind);
    view.put("moneyByHouseholdClass", moneyByClass);
    // ★★ H4：**本格的市场**（M1-A：单一计价货币 + 固定价表）；★ 该格没有市场 ⇒ {@code null}（**合法状态**：
    //   "这一格没有市场"与"这一格读不到数据"是两件事，前者要能在界面上看见）。★ 视图只**读**，不重算价表。
    view.put("market", marketView(data.markets().get(coord)));
    // ★★ R4-E2：**需求账本**只读视图（本格相关：HEX 范围命中本格 + HOUSEHOLD 范围住在该格的家户）。
    //   ★ 与订单路径同一份状态（{@code EconomyData.demands()}），视图不重算摊分；{@code effective} 由当前 tick 现判。
    view.put("demands", demandViews(data, coord, tick));
    // ★★ R4-E2：**候选预设**只读视图（世界级、与格无关 ⇒ 全量发；按 id 值排序 ⇒ 响应字节是内容的纯函数）。
    view.put("candidates", candidateViews(data));
    // ★★ E5a：**清算政策**（世界级、与格无关 ⇒ 全量发；按 ruleId 排序 ⇒ 响应字节是内容的纯函数）与
    //   **本格 hex 危机信号**（按 (hex, kind) 派生的 id 排序）。两张表都是持久状态：空表 = 没有登记，
    //   不是"读不到"；E5a 不产生任何信号/清算（生成留 E5b），这里只如实反映为空。
    view.put("liquidationPolicies", liquidationPolicyViews(data));
    view.put("crisisSignals", crisisSignalViews(data, coord));
    // ★★ E5b：本格质押只读视图（清算的输入侧；状态真值 = EconomyData.pledges）。
    view.put("pledges", pledgeViews(data, coord));
    // ★★ E6a：模式变迁与阶层保留份额（**世界级、按 id 稳定序**；两者都是持久状态，空列表 = 没有记录，
    //   不是"读不到"）。★ 视图只读 EconomyData 的规范表，不重算派生读数、不按 hex 过滤伪造局部空表：
    //   变迁的目标组织 id 自带格键，读的人可逐条对格；classShares 的家户/位置身份也逐条发出。
    view.put("modeTransitions", modeTransitionViews(data));
    view.put("classShares", classShareViews(data));
    // ★★ R4-E2b：**本格的实物资产份额**只读视图（逐条 id/industry/asset/owner/operator/quantity/kind）——
    //   份额的 owner/operator 是"谁拥有/谁经营"的唯一实物总账，进入动作的拆分必须在这里逐条可见（守恒靠它核对）。
    view.put("assetShares", assetShareViews(data, coord));
    view.put("debtCount", debtCount);
    view.put("debtPrincipal", debtPrincipal);
    // ★★ M1.5：同一条事实的另一半（债权人侧）。两个方向来自同一张债务表 ⇒ 逐条本金一致。
    view.put("creditCount", creditCount);
    view.put("creditPrincipal", creditPrincipal);
    // ★★ H5 ④：**商品词表（含留位）** —— 世界级常量，与格无关；放在这里是因为这是 economy 切片唯一的读口。
    //   ★ 为什么必须有：H5 起 IRON 不再进任何配方（作坊的投入由铁改成工具）⇒ 若读口不列"世界有哪些商品"，
    //     "铁"就退化成没人读得到的孤字面量（本仓禁"看起来在记、其实永远不被读"）。逐条口径见
    //     {@link EconomyVocabulary#allCommodityIds()}。
    view.put("commodityIds", EconomyVocabulary.allCommodityIds());
    // ★★ M1.1：**货币词表**（同样是世界级常量、与格无关；同因住在这个唯一的 economy 读口里）。
    //   ★ 为什么必须发出来：M1.1 新增的 `CurrencyDef` / `MoneyInstrument` 若没有读口，就是"没人读得到的孤类型"
    //     （本仓禁"看起来在记"）—— 钱的**工具身份**（币种 ≠ 工具）从今天起在报表里看得见。
    //   ★ `currencyDefs` 给"这个币种的最小单位精度是几位"（scale；币种总量恒定 ≠ 逐工具恒定，M1.6 要用它）。
    //   ★ `moneyInstruments` 逐条给 {id, currency, kind, issuer, redeemer}：issuer/redeemer 为 null =
    // **没有**
    //     （金属币没有发行人；兑现属 M4+，`redeemer` 是具名留位）—— 不是"读不到"。逐条口径见 MoneyVocabulary 的类注。
    view.put("currencyDefs", currencyDefViews());
    view.put("moneyInstruments", moneyInstrumentViews());
    view.put("classes", classes);
    view.put("industries", industries);
    // ★★ S3：逐家户的状态读数与阶层分化（派生；来源与边界见 HouseholdCondition / HouseholdClassRule 的类注）。
    // ★★ R3a：旧每日 ledger 投递点（EconomyDayFeed）已随旧结算引擎删除；production-runtime 路径不产生当日
    //   ProductionLedger ⇒ 这里恒空，下面的 arrears / 资本化 / 清算读数走"具名不可得"分支。
    Optional<ProductionLedger> dayLedger = Optional.empty();
    String mapId = data.meta().map(meta -> meta.mapId()).orElse(null);
    List<HouseholdId> householdKeys = cohortKeysAt(data, coord);
    Set<HouseholdId> hexHouseholds = new LinkedHashSet<>(householdKeys);
    // ★ E1：库存粮是 actor 侧的账（economy 状态里没有），这里按家户 actor 现取一份只读映射；
    //   账缺席的键不填 0（保留"读不到"的哨兵），由 HouseholdCondition 的 grainCoveragePerMille 原样标出。
    Map<ActorRef, HouseholdId> householdOfActorAtHex = new LinkedHashMap<>();
    for (HouseholdId key : householdKeys) {
      householdOfActorAtHex.put(HouseholdActors.of(key), key);
    }
    Map<HouseholdId, Long> grainStockByHousehold = new LinkedHashMap<>();
    for (GoodsAccount account : accountsAt(data, actors, coord)) {
      HouseholdId key = householdOfActorAtHex.get(HouseholdActors.of(account.key().household()));
      if (key == null) {
        continue;
      }
      grainStockByHousehold.merge(key, account.balances().getOrDefault(GRAIN, 0L), Long::sum);
    }
    // ★★ E4b：逐户 debtCapacity（F 四项 / 可质押余粮 / 既有债 / unpriced / headroom；唯一算法在
    //   {@link DebtCapacityBook}）。库存从 actor 侧账本现取：账缺席 ⇒ 该行的 pledgeable/headroom 记 null +
    //   具名原因（{@link #DEBT_CAPACITY_STOCK_UNREADABLE}），不填 0 冒充。
    Map<HouseholdId, DebtCapacity> debtCapacities =
        DebtCapacityBook.capacitiesForState(
            data,
            classKeys,
            key ->
                grainStockByHousehold.containsKey(key)
                    ? OptionalLong.of(grainStockByHousehold.get(key))
                    : OptionalLong.empty());
    for (int i = 0; i < classKeys.size(); i++) {
      classes
          .get(i)
          .put(
              "debtCapacity",
              debtCapacityView(classKeys.get(i), debtCapacities.get(classKeys.get(i))));
    }
    // ★★ E6c：同一份 E4b 块视图（唯一算法只算一次）同时挂顶层旧键与 dashboard.derived.creditPosition。
    Map<String, Object> debtCapacityBlock = debtCapacityBlockView(classKeys, debtCapacities);
    view.put("debtCapacity", debtCapacityBlock);
    // ★ 索引一次、逐户 O(1)：读口一格里通常 4 行，但分类要扫 UsesRight/配额/租规则，不能每户各扫一遍。
    HouseholdClassRule.Index classIndex = HouseholdClassRule.Index.of(data);
    List<Map<String, Object>> householdConditions = new ArrayList<>();
    List<Map<String, Object>> classifications = new ArrayList<>();
    for (HouseholdId key : householdKeys) {
      ClassRow row = data.classes().get(key);
      OptionalLong grainStockMilli =
          grainStockByHousehold.containsKey(key)
              ? OptionalLong.of(grainStockByHousehold.get(key))
              : OptionalLong.empty();
      HouseholdCondition condition =
          HouseholdCondition.derive(data, key, dayLedger, grainStockMilli);
      HouseholdClassRule.Classification classification = classIndex.classify(key, dayLedger);
      householdConditions.add(householdConditionView(condition, classification.stratum().value()));
      classifications.add(
          classificationView(key, row == null ? null : row.view().stratum(), classification));
    }
    view.put("householdConditions", householdConditions);
    // ★★ S3：写回结果与"从哪一档跳来"的具名读数——classes[].slot 是当前真值；classifications 给逐户证据；
    //   classTransitions 是关账日写回的审计（进程内、不落盘；读不到时具名，不填 0/空数组冒充）。
    view.put("classifications", classifications);
    if (tick >= 0L && mapId != null) {
      Optional<ClassTransitionFeed.Snapshot> classTransitions =
          ClassTransitionFeed.last(mapId, tick);
      if (classTransitions.isPresent()) {
        view.put(
            "classTransitions",
            classTransitionsView(classTransitions.orElseThrow(), hexHouseholds));
        view.put("classTransitionsUnavailable", null);
      } else {
        view.put("classTransitions", null);
        view.put("classTransitionsUnavailable", CLASS_TRANSITIONS_PROCESS_ONLY);
      }
    } else {
      view.put("classTransitions", null);
      view.put("classTransitionsUnavailable", CLASS_TRANSITIONS_PROCESS_ONLY);
    }
    // ★★ R4-E2b：**候选进入评估结果**（进程内瞬态；本格相关项）。读不到时 null + 具名原因，不填假空数组。
    if (tick >= 0L && mapId != null) {
      Optional<EntryOutcomeFeed.Snapshot> entryOutcomes = EntryOutcomeFeed.last(mapId, tick);
      if (entryOutcomes.isPresent()) {
        view.put("entryOutcomes", entryOutcomeView(entryOutcomes.orElseThrow(), coord));
        view.put("entryOutcomesUnavailable", null);
      } else {
        view.put("entryOutcomes", null);
        view.put("entryOutcomesUnavailable", ENTRY_OUTCOMES_PROCESS_ONLY);
      }
    } else {
      view.put("entryOutcomes", null);
      view.put("entryOutcomesUnavailable", ENTRY_OUTCOMES_PROCESS_ONLY);
    }
    // ★★ S3：当日欠款（WageArrears / RentArrears / SubsistenceArrears；进程内瞬态，读不到 ⇒ null + 具名原因）。
    if (dayLedger.isPresent()) {
      ProductionLedger ledger = dayLedger.orElseThrow();
      view.put("arrears", arrearsView(ledger));
      view.put("arrearsUnavailable", null);
      // ★★ E4c：资本化明细/具名跳过与偿还跳过（同一份当日 ledger；不新增路由/工具）。
      view.put("debtCapitalizations", debtCapitalizationView(ledger));
      view.put("debtCapitalizationsUnavailable", null);
      view.put("debtRepaymentSkips", debtRepaymentSkipView(ledger));
      view.put("debtRepaymentSkipsUnavailable", null);
      // ★★ E5b：本日清算/阶层下滑/投影回退的瞬态审计（与上面同一份当日 ledger；读不到 ⇒ null + 具名原因）。
      view.put("liquidationAudits", liquidationAuditView(ledger, data, coord));
      view.put("liquidationAuditsUnavailable", null);
    } else {
      view.put("arrears", null);
      view.put("arrearsUnavailable", ARREARS_PROCESS_ONLY);
      view.put("debtCapitalizations", null);
      view.put("debtCapitalizationsUnavailable", ARREARS_PROCESS_ONLY);
      view.put("debtRepaymentSkips", null);
      view.put("debtRepaymentSkipsUnavailable", ARREARS_PROCESS_ONLY);
      view.put("liquidationAudits", null);
      view.put("liquidationAuditsUnavailable", LIQUIDATION_AUDIT_PROCESS_ONLY);
    }
    // ★★ R3a：旧区级市场读数（MarketReadout）已随旧撮合引擎删除 ⇒ 这一栏恒为 null + 具名原因，不填 0 冒充。
    view.put("marketReadout", null);
    view.put("marketReadoutUnavailable", readoutUnavailable);
    // ★★ M0.3：**逐格粮食诊断**（七项里今天做得到的四项 + 三项"做不到"的具名占位）。
    //   ★ 它挂在**同一个视图**里（不另开读口）：报表脚本按格 dump 的就是这一份，多一栏即多一栏读数。
    //   ★ M2.7 复评：logisticsGap 在拿到进程内报告时就可算；productionSelfSufficiency / paymentInstrumentGap
    //     仍具名不可算（见方法注释），绝不填 0。
    view.put(
        "grainDiagnosis",
        grainDiagnosis(coord, data, actors, grainStock, goods, actorMoneyTotal, report));
    // ★★ E6c：统一 dashboard（stocks/flows/derived/crisis/windows）——只新增这一个键，旧键名与形状不变。
    //   ★ GUI 与 MCP 共用本函数（ToolSupport.economyHex = ApiViews.economyHex），不新开路由/工具。
    view.put(
        "dashboard",
        economyDashboard(
            coord,
            data,
            actors,
            moneyByKind,
            moneyByClass,
            classKeys,
            debtCapacities,
            debtCapacityBlock,
            report,
            tick,
            readoutUnavailable));
    return view;
  }

  /**
   * ★ F1：**世界级经济总览**（{@code GET /api/economy/overview} 的视图）。
   *
   * <p>★ <b>它不逐格</b>：货币发行/回笼、actor kind / 家户阶层聚合都是**世界级**量；逐格读仍走 {@link #economyHex}。本方法只是把 {@code
   * economyHex} 用的同一批私有装配函数（{@link #moneyIssuanceView} / {@link #moneyByActorKind} / {@link
   * #moneyByHouseholdClass} / {@link #moneyLayers} / {@link #currencyDefViews} / {@link
   * #moneyInstrumentViews}）按**同一口径**组装一次 —— 不复制第二份公式，两个读口的数字不会漂移。
   *
   * <p>★ {@code activated} / {@code tick} 来自 economy 切片；{@code scope} 具名写出"世界级"。
   */
  public static Map<String, Object> economyOverview(SimulationState state) {
    Objects.requireNonNull(state, "state");
    EconomyData data = economyData(state);
    ActorData actors = actorData(state);
    long tick = state.meta().timestamp().tick();
    Map<String, Long> circulation = moneyTotals(actors);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("activated", data.meta().isPresent());
    view.put("tick", tick < 0L ? null : tick);
    view.put("scope", WORLD_ECONOMY_SCOPE);
    view.put("moneyIssuance", moneyIssuanceView(data, circulation));
    view.put("moneyByActorKind", moneyByActorKind(actors));
    view.put("moneyByHouseholdClass", moneyByHouseholdClass(data, actors));
    view.put("moneyLayers", moneyLayers(circulation));
    view.put("currencyDefs", currencyDefViews());
    view.put("moneyInstruments", moneyInstrumentViews());
    return view;
  }

  /**
   * ★★ <b>P1.4：内置政府的只读读数</b>（{@code GET /api/economy/gov} 的视图）。
   *
   * <p>★★ <b>它只读三件事、不写任何状态</b>：
   *
   * <ul>
   *   <li>{@code governments[]}：{@code EconomyData.governments} 每个政府的身份 / 国库 actor / 可发行币种 /
   *       <b>周期铸币与周期发债政策</b>（{@code seignioragePerCycle} / {@code debtIssuePerCycle}）；
   *   <li>{@code treasuryAccounts[]}：国库 actor 的 {@code GoodsAccount}（production-runtime 里国库 = 内置
   *       GOV 家户账户）：逐格商品 / 货币 / 冻结 / 可支配；找不到账 ⇒ 该条为空数组（不填 0 冒充）；
   *   <li>{@code issuance}：按政府分组的 {@code MoneyIssuanceRecord} 累计（创世禀赋 / 财政发行 / 回笼 / 净额）——
   *       这是"周期铸币"的<b>事实读数</b>，与上面的<b>政策旋钮</b>并排；{@code moneyIssuance} 另给世界级全量（同一份算法）。
   * </ul>
   *
   * <p>★ 与 {@link #economyOverview} 同款：{@code economy} 切片未激活时返回 {@code activated=false} +
   * 空表，不抛、不伪造。
   */
  public static Map<String, Object> economyGovernment(SimulationState state) {
    Objects.requireNonNull(state, "state");
    EconomyData data = economyData(state);
    ActorData actors = actorData(state);
    long tick = state.meta().timestamp().tick();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("activated", data.meta().isPresent());
    view.put("tick", tick < 0L ? null : tick);
    view.put("scope", GOVERNMENT_SCOPE);
    List<Government> ordered = new ArrayList<>(data.governments().values());
    ordered.sort(Comparator.comparing(government -> government.id().value()));
    List<Map<String, Object>> governments = new ArrayList<>(ordered.size());
    for (Government government : ordered) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("id", government.id().value());
      node.put("nationRef", government.nationRef());
      node.put("treasury", actorRefView(government.treasury()));
      List<String> issuable = new ArrayList<>(government.issuable().size());
      for (CurrencyId currency : government.issuable()) {
        issuable.add(currency.value());
      }
      issuable.sort(Comparator.naturalOrder());
      node.put("issuable", issuable);
      // ★ 政策旋钮（每产业大周期）：写进 Government 的出厂值就是这两个；读口不重算、不补缺省。
      node.put("seignioragePerCycle", government.seignioragePerCycle());
      node.put("debtIssuePerCycle", government.debtIssuePerCycle());
      node.put("mintCurrency", issuable.isEmpty() ? null : issuable.get(0));
      // ★ production-runtime 的国库是 GOV 家户（kind=HOUSEHOLD）⇒ 这里能反查出它的家户行（人口 0、slot=official）。
      HouseholdId treasuryHousehold =
          government.treasury().kind() == ActorKind.HOUSEHOLD
              ? HouseholdActors.householdOf(government.treasury())
              : null;
      node.put("treasuryHousehold", treasuryHousehold == null ? null : treasuryHousehold.value());
      ClassRow row = treasuryHousehold == null ? null : data.classes().get(treasuryHousehold);
      node.put("treasuryClassRow", row == null ? null : governmentClassRowView(row));
      node.put("treasuryAccounts", treasuryAccountViews(data, actors, government.treasury()));
      node.put("issuance", governmentIssuanceView(data, government.id()));
      governments.add(node);
    }
    view.put("governments", governments);
    view.put("moneyIssuance", moneyIssuanceView(data, moneyTotals(actors)));
    return view;
  }

  /** ★★ P1.4：内置政府读数的 scope 说明（唯一拼写点）。 */
  private static final String GOVERNMENT_SCOPE =
      "世界级：EconomyData.governments 的内置政府（production-runtime 政府家户）；"
          + "国库 = 该家户的 GoodsAccount，逐币种不跨币种求和";

  /**
   * 政府家户的 {@code ClassRow} 读侧形：**它是 GOV 的口袋行**（production-runtime 里 population=0、slot=official），
   * 不是第二本人账；人口/劳动/参与率都来自行本身，读口不重算。
   */
  private static Map<String, Object> governmentClassRowView(ClassRow row) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("householdId", row.id().value());
    view.put("q", row.view().hex().q());
    view.put("r", row.view().hex().r());
    view.put("residence", row.view().residence().value());
    view.put("stratum", row.view().stratum().value());
    view.put("population", row.population());
    view.put("laborMilli", row.laborMilli());
    view.put("participationPerMille", row.participationPerMille());
    return view;
  }

  /**
   * 国库 actor 的全部 {@code GoodsAccount}（按 {@code (q,r)} 排序；一本账一个条目）。
   *
   * <p>★ "找不到账"是合法结果（主体还没播账）：空数组，不给伪造的 0 条目——否则读的人会把"没这回事"当成"余额为零"。
   */
  private static List<Map<String, Object>> treasuryAccountViews(
      EconomyData data, ActorData actors, ActorRef treasury) {
    List<GoodsAccount> accounts = new ArrayList<>();
    for (GoodsAccount account : actors.accounts().values()) {
      if (HouseholdActors.of(account.key().household()).equals(treasury)) {
        accounts.add(account);
      }
    }
    accounts.sort(
        Comparator.comparingInt(
                (GoodsAccount account) -> householdHex(data, account.key().household()).q())
            .thenComparingInt(account -> householdHex(data, account.key().household()).r()));
    List<Map<String, Object>> views = new ArrayList<>(accounts.size());
    for (GoodsAccount account : accounts) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("location", hexCoord(householdHex(data, account.key().household())));
      item.put("goods", sortedCommodities(account.balances()));
      item.put("money", sortedCurrencies(account.money()));
      item.put("frozenGoods", sortedCommodities(account.frozenBalances()));
      item.put("frozenMoney", sortedCurrencies(account.frozenMoney()));
      item.put("availableGoods", availableCommodities(account));
      item.put("availableMoney", availableCurrencies(account));
      views.add(item);
    }
    return views;
  }

  /** 单个政府的发行审计累计（逐币种；与 {@code moneyIssuanceView} 同一套 kind 判据，只是按 governmentId 过滤）。 */
  private static Map<String, Object> governmentIssuanceView(
      EconomyData data, GovernmentId governmentId) {
    Map<String, Long> initial = new TreeMap<>();
    Map<String, Long> fiscal = new TreeMap<>();
    Map<String, Long> withdrawal = new TreeMap<>();
    int count = 0;
    for (MoneyIssuanceRecord record : data.moneyIssuances().values()) {
      if (!record.governmentId().equals(governmentId)) {
        continue;
      }
      count++;
      if (record.kind() == MoneyIssuanceKind.INITIAL_ENDOWMENT) {
        initial.merge(record.currency().value(), record.amount(), Long::sum);
      } else if (record.kind() == MoneyIssuanceKind.FISCAL_ISSUE) {
        fiscal.merge(record.currency().value(), record.amount(), Long::sum);
      } else {
        withdrawal.merge(record.currency().value(), record.amount(), Long::sum);
      }
    }
    Map<String, Long> net = new TreeMap<>(initial);
    mergeIssuance(net, fiscal);
    for (Map.Entry<String, Long> entry : withdrawal.entrySet()) {
      net.merge(entry.getKey(), -entry.getValue(), Long::sum);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("window", "累计（EconomyMeta.activatedDay 至当前 revision；无按日窗口）");
    view.put("recordCount", count);
    view.put("initialEndowment", initial);
    view.put("fiscalIssue", fiscal);
    view.put("withdrawal", withdrawal);
    view.put("netIssuance", net);
    return view;
  }

  /** ★ F1：世界经济总览的 scope 说明（唯一拼写点；与逐格读口分开）。 */
  private static final String WORLD_ECONOMY_SCOPE =
      "世界级：economy 切片与 actor 账本的全量聚合（三国合并）；不含逐格分解，逐格读 /api/economy/hex";

  /**
   * ★★ <b>M0.3：逐格粮食诊断</b>（master plan §三 M0.3 的七项；M2.7 复评后的现状）。
   *
   * <p>★★ <b>为什么它是一个独立函数、又嵌在 {@link #economyHex} 里</b>：能算的项从**状态 + 进程内报告**算出来，
   * 算不出的项<b>具名列出原因</b>，读的人就不会把缺栏当成 0（本仓最反对的"看起来在记、其实没有"）。
   *
   * <pre>
   * 项目                              本批  算法 / 为什么做不到
   * ① 生产自给率（标是否扣种子与损耗）   ✗   要读**本周期 ledger 的毛产/损耗/投入** —— 而 ledger 是**当日瞬态**
   *                                        （{@code ProductionLedger.Accumulator} 在协调器落账后即丢）⇒ 状态里没有
   *                                        周期累计的毛产/损耗/投入。FlowRow.income/consumed 不是毛产/损耗/投入，
   *                                        不许冒充。落点：将来把 ledger 的周期累计落成一个持久读数组件
   * ② 可用库存覆盖天数                  ✓   grainStock ÷ 该格当日口粮（日初人口那一份）
   * ③ 预计进口需求                      ✓   = 本周期累计未满足需求（unmetNeed）
   * ④ 有效购买力缺口                    ✓   = 未满足 − 该格全部账本按本格粮价**ask**能买到的量（上限）
   * ⑤ 物流缺口                          ~   M2.7：拿到进程内 MarketReport 时可算（买方侧未成交 × 物流原因档：
   *                                        运力/时限/无路/无邻区）；拿不到报告 ⇒ 具名 unavailable，不填 0
   * ⑥ 币种/支付缺口                     ~   只给"逐币种货币"与"能买到多少"；★ M1.1 起工具的**身份**已读得出、
   *                                        M2.1 起订单里也有 payWith/receiveWith 字段，但**接受规则**（谁收哪种
   *                                        工具、多工具校验/兑现）仍未实现（本层市场只收 silver-specie）⇒ 不判
   * ⑦ 实际满足率与未满足人日            ✓   分母 = **cycleNaturalNeedMilli**（逐日累加，见丙条），
   *                                        满足率 = (周期自然需要 − 本周期未满足) ÷ 周期自然需要；人日由未满足反解
   * </pre>
   *
   * <p>★★ <b>窗口标注（M0.2 的纪律 + 丙条裁定）</b>：{@code importDemand} / {@code satisfactionPerMille} / {@code
   * unmetPersonDays} / {@code cycleNaturalNeedMilli} 都是**本周期累计**（新周期第一天重置）⇒ 只有关账日读到的 才是整周期的量；{@code
   * grainDailyNeed} / {@code coverageDays} 是**最近一次结算日**的日口径。 ★ <b>严禁</b>再用"某一天人口 × 整周期配额"（{@code
   * Σpop×10,000}）与"日耗 × 120"并排当同一分母 —— 两者口径本就不可比 （丙条查清）；可比的需求只有 {@code cycleNaturalNeedMilli}。
   *
   * <p>★ <b>不设任何门槛</b>（用户裁定：自给率是**诊断读数**，不是"必须 ≥100%"的创世门槛）—— 本函数只报数、不判断。
   *
   * @param grainStock 该格 Σ 商品库存里的粮（毫粮；由 {@link #economyHex} 那趟遍历给出，本函数不重算）
   * @param goods 该格 Σ 商品库存（逐商品；算"粮布换算比"用）
   * @param actorMoneyTotal 该格 Σ 货币（逐币种；算购买力用）
   * @param report 进程内最近一轮市场报告（可为空：为空时物流缺口具名 unavailable）
   */
  private static Map<String, Object> grainDiagnosis(
      HexCoord coord,
      EconomyData data,
      ActorData actors,
      long grainStock,
      Map<String, Long> goods,
      Map<String, Long> actorMoneyTotal,
      Optional<MarketReport> report) {
    Map<String, Object> view = new LinkedHashMap<>();
    List<HouseholdId> keys = cohortKeysAt(data, coord);
    long population = 0L;
    long dailyNeed = 0L;
    long cycleNeed = 0L;
    long unmet = 0L;
    for (HouseholdId key : keys) {
      ClassRow row = data.classes().get(key);
      population += row.population();
      // ★ 日耗读结算写下的 naturalNeeds（与面板同源）；★ 结算还没跑过 ⇒ 那一栏是 0，此处**按口粮公式兜底**
      //   （兜底也要有，否则"未激活的世界"会显示成"这一格没有需求"——那是假的）。
      long rowDaily = row.naturalNeeds().getOrDefault(GRAIN, 0L);
      if (rowDaily <= 0L && row.population() > 0L) {
        rowDaily = EconomyVocabulary.dailyRationMilli(row.population(), 1L);
      }
      dailyNeed += rowDaily;
      // ★★ M2.7：周期分母改读**丙条累加器**（逐日、日初人口累加）—— 不再用"读口时刻人口 × 整周期配额"现算。
      cycleNeed += row.cycleNaturalNeedMilli();
      FlowRow flow = data.flows().get(key);
      if (flow == null) {
        continue;
      }
      unmet += flow.unmetNeed().getOrDefault(GRAIN, 0L);
    }
    view.put("unit", "milli-grain");
    view.put("population", population);
    view.put(
        "populationSnapshot", "读口时刻的行人口（日末口径，月末回写之后）；cycleNaturalNeedMilli 用的是逐日日初人口，两者不可并排当同一时点");
    view.put("grainStock", grainStock);
    view.put("grainDailyNeed", dailyNeed);
    view.put("grainDailyNeedClock", GRAIN_DAILY_CONSUMPTION_CLOCK);
    view.put("cycleNaturalNeedMilli", cycleNeed);
    view.put("cycleNaturalNeedClock", CYCLE_NATURAL_NEED_CLOCK);
    // ② 覆盖天数（**向下取整**；日耗为 0 ⇒ null = 无定义，不是 0 天）。
    view.put("coverageDays", dailyNeed <= 0L ? null : grainStock / dailyNeed);
    // ③ 预计进口需求 = 本周期累计未满足（★ 这是"已经缺掉的那部分"，对下一周期的外推要生产预测 ⇒ M2 才做）。
    view.put("importDemand", unmet);
    // ④ 有效购买力缺口：该格全部账本按本格粮价能买到的量（**上限**，见下面的 caveat）。
    //   ★ M2.6：另给一栏按 ask（买方限价）算的更紧上限 —— 两者都只是账本级上限，不是市场的有效需求。
    Market market = data.markets().get(coord);
    Long price = market == null ? null : market.prices().get(GRAIN);
    Long ask = market == null ? null : market.askPriceOf(GRAIN);
    CurrencyId numeraire = market == null ? null : market.numeraire();
    long money = numeraire == null ? 0L : actorMoneyTotal.getOrDefault(numeraire.value(), 0L);
    long affordable = price == null || price <= 0L ? 0L : money * MILLI_PER_GRAIN / price;
    long affordableAtAsk = ask == null || ask <= 0L ? 0L : money * MILLI_PER_GRAIN / ask;
    view.put("numeraire", numeraire == null ? null : numeraire.value());
    view.put("grainPrice", price);
    view.put("grainAskPrice", ask);
    // ★★ M1.6：它只是 `actorMoneyTotal` 里计价货币那一个标量 —— 私人流通 / 全部基础货币 / 银行存款的**分栏**
    //   在父视图（economyHex / economyOwnership）的 {@code moneyLayers} 那一栏，不在这里另算一份。
    view.put("numeraireMoney", money);
    view.put("affordableGrain", affordable);
    view.put("affordableGrainAtAsk", affordableAtAsk);
    view.put("purchasingGap", Math.max(0L, unmet - affordable));
    view.put(
        "purchasingCaveat",
        "affordableGrain / affordableGrainAtAsk 都是**账本级上限**：它们把该格全部账本的钱与全部库存混在一起算，"
            + "而市场的有效需求只算「本轮有预算、按参考价买得起且限价内」的家户"
            + "（旧区级市场读数已随旧撮合引擎删除，R3a；历史口径见原 MarketReadout 的 effectiveDemandMilli / needsButCannotAffordMilli 两栏）⇒ 实际能成交的量 ≤ 它们");
    // ⑦ 满足率（千分）与未满足人日 —— 分母 = 丙条的**逐日累加周期需要**。
    view.put(
        "satisfactionPerMille",
        cycleNeed <= 0L ? null : Math.max(0L, (cycleNeed - unmet)) * 1000L / cycleNeed);
    view.put(
        "unmetPersonDays",
        unmet / (EconomyVocabulary.RATION_MILLI_PER_PERSON / EconomyVocabulary.RATION_CYCLE_DAYS));
    view.put(
        "window",
        "importDemand / satisfactionPerMille / unmetPersonDays / cycleNaturalNeedMilli 是**本周期累计**"
            + "（新周期第一天重置）⇒ 只有关账日读到的才是整周期的量；cycleNeed="
            + cycleNeed
            + "（= Σ_d dailyRationMilli(pop_d, d)，pop_d = 每日结算前的日初人口）");
    // ★★ 七项里**仍做不到**的项：具名列出（不是留空、更不是填 0 —— 那会被读成"没有缺口"）。
    Map<String, Object> unavailable = new LinkedHashMap<>();
    unavailable.put("productionSelfSufficiency", PRODUCTION_NEEDS_LEDGER);
    // ★ M2.7 复评：物流缺口在拿到进程内报告时可算；拿不到就具名说清缺的是哪一份（不填 0）。
    if (report.isPresent()) {
      view.put("logisticsGap", report.orElseThrow().logisticsGapMilli(coord));
      view.put(
          "logisticsGapWindow",
          "本市场轮该格买方侧未成交、原因 ∈ {运力不足/到货超时限/无路/无邻区} 的量（毫商品）；" + "只报缺口，不掩饰为 0：0 表示本轮确实没有被物流挡住的买方需求");
    } else {
      unavailable.put("logisticsGap", LOGISTICS_GAP_NO_REPORT);
    }
    unavailable.put("paymentInstrumentGap", PAYMENT_INSTRUMENT_GAP);
    view.put("unavailable", unavailable);
    // ★ 粮布换算比：顺带给出"库存里那匹布在这个价下折多少粮"（向下取整 ⇒ 0 只表示"不足 1 毫粮"，不是无价值）。
    long cloth = goods.getOrDefault(EconomyVocabulary.CLOTH_COMMODITY_ID, 0L);
    Long clothPrice = market == null ? null : market.prices().get(CLOTH);
    view.put(
        "clothValueInGrain",
        price == null || price <= 0L || clothPrice == null ? null : cloth * clothPrice / price);
    return view;
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────
  // ★★ E6c：统一经济 dashboard（只读聚合；GUI 与 MCP 共用同一份，不新增路由/工具）
  //
  // 字段树（每个字段的窗口与来源见 {@link #economyDashboard} 的类注与同一响应里的 windows 块）：
  //   stocks  —— 时点存量：债务本金（按 unit，逐户+合计）/ 资产份额（按 AssetKind）/ 货币分布 /
  //              人口阶层分布
  //   flows   —— 本期流量：本格家户 FlowRow 的逐字段合计（本周期至今；关账日读到的即整周期）
  //   derived —— 本期派生：F/headroom（复用 E4b 唯一算法）/ 债务对产出 / 利息对 F / 基本需求缺口 /
  //              下一轮投入缺口 + SHORTAGE 具名汇总
  //   crisis  —— 本格 crisisSignals（复用 E5a 唯一来源）
  //   windows —— 逐字段组窗口 + 缺库存/缺价格/缺数据的具名 unavailable
  //
  // ★★ 只读：全部从 EconomyData / ActorData 的**现值**与既有唯一算法现算，不写任何状态、不落新组件。
  // ★★ 旧键保持：dashboard 只是 economyHex / economyOwnership 的新增键，旧键名与形状一字不动。
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  /** ★★ E6c：dashboard 缺 FlowRow 的具名原因（唯一拼写点；缺失不是 0，也不拿别的行顶替）。 */
  private static final String DASHBOARD_FLOW_ROW_MISSING =
      "该家户在本 EconomyData.flows 里没有 FlowRow（29 组件空表/旧档缺键，或该户尚未结算）⇒ " + "它未计入任何流量合计；缺失不是 0，也不拿别的行顶替";

  /** ★★ E6c：货币单位没有「本周期产出」口径的具名原因（唯一拼写点）。 */
  private static final String DASHBOARD_MONEY_OUTPUT_UNAVAILABLE =
      "money 单位没有本周期货币产出口径：FlowRow.income 只有商品（没有货币收入字段）；"
          + "进程内 MarketReport 只有最近一轮开市的成交，不是本周期累计 ⇒ 债务/产出的 money 分母具名缺失，"
          + "不拿单轮成交当周期量、不硬折成粮";

  /** ★★ E6c：economy 切片没有国家维的具名原因（唯一拼写点）。 */
  private static final String DASHBOARD_NATION_SCOPE_UNAVAILABLE =
      "economy 切片没有国家/政区维（Nation 在 map/sd 侧；economyHex/economyOwnership 的入参不含 map/SD）"
          + "⇒ 债务/产出不能按「本国」汇总；本读口给 hex 与 world 两档，world 是全 EconomyData，不是某个国家";

  /** ★★ E6c：非粮商品没有同窗口自然需求分母的具名原因（唯一拼写点）。 */
  private static final String DASHBOARD_NON_GRAIN_NEED_DENOMINATOR =
      "ClassRow.cycleNaturalNeedMilli 只累计自然口粮（grain）需要；该商品的 ClassRow.naturalNeeds 是"
          + "最近一次结算日的日口径、不是本周期累计 ⇒ 相对缺口没有同窗口分母，只报绝对 unmetNeed，不硬折";

  /** ★★ E6c：无有效价格/不硬折的具名原因（唯一拼写点；E4b 的 NO_UNIT_PRICES 同侧）。 */
  private static final String DASHBOARD_PRICE_UNAVAILABLE =
      "非粮/货币债务折成粮需要有效价格；E4b 起 DebtCapacityBook 的唯一价格钩子仍是 NO_UNIT_PRICES"
          + "（E5 的价格源未落地）⇒ 不硬折：债务只按 unit 分栏，unpriced 部分见 debtCapacity.unpricedDebt*";

  /** ★★ E6c：资产市值没有可信口径的具名原因（唯一拼写点）。 */
  private static final String DASHBOARD_ASSET_MARKET_VALUE_UNAVAILABLE =
      "资产市值/非粮折粮没有可信价格口径（Market 只服务商品现货、LiquidationPolicy 的政策价不是市值）" + "⇒ dashboard 只报实物数量，不折算成粮/钱";

  /** ★★ E6c：ClassRow.debts 引用悬空的具名原因（唯一拼写点）。 */
  private static final String DASHBOARD_DEBT_REF_DANGLING =
      "ClassRow.debts 引用的 DebtContractId 不在 debtContracts 表中（状态不完整）⇒ 这些合同未计入"
          + "逐户/合计本金与条数；缺失不是 0，不用别的债顶替";

  /** ★★ E6c：进程内 MarketReport 不可得时的具名原因（唯一拼写点）。 */
  private static final String DASHBOARD_MARKET_REPORT_UNAVAILABLE =
      "进程内 MarketReport 不可得（economyOwnership 与 economyHex 的 3 参重载没有 SimulationState；"
          + "或重启/换进程/本轮没开市）⇒ 最近一轮市场成交参考口径具名缺失；它不是本周期货币产出";

  /** ★★ E6c：比值分母为 0 的具名原因（F=0 / 自然需要=0 / 产出=0；唯一拼写点）。 */
  private static final String DASHBOARD_DENOMINATOR_ZERO =
      "分母合计 = 0 ⇒ 比值无定义；给 null + 本具名原因，绝不除零、也不填 0 冒充";

  /** ★★ E6c：比值数值超出安全范围的具名原因（避免乘 1000 溢出时抛异常/给错值）。 */
  private static final String DASHBOARD_RATIO_OVERFLOW =
      "比值超出 long 安全范围（分子 > Long.MAX_VALUE/1000）⇒ 给 null + 本具名原因，不抛、不截断";

  /** ★★ E6c：economyOwnership 入口没有 SimulationState 上下文的具名原因（唯一拼写点）。 */
  private static final String ECONOMY_OWNERSHIP_NO_STATE =
      "economyOwnership（3 参）没有 SimulationState 上下文（与 economyHex 的 3 参重载同款）⇒ tick 与进程内"
          + " MarketReport/readout 不可得；dashboard 的时点/本周期字段仍从 EconomyData 现算，只有"
          + "「最近一轮市场成交」等进程内参考口径具名缺失";

  // dashboard.windows 的逐字段组窗口字符串（唯一拼写点）。
  private static final String DASHBOARD_STOCK_DEBT_WINDOW =
      "时点：当前 revision 的 ClassRow.debts + debtContracts；逐 DebtUnit.key()（commodity:<id>/money:<id>）分栏，粮与钱不合并、不硬折";
  private static final String DASHBOARD_STOCK_ASSET_WINDOW =
      "时点：当前 revision 的 assetShares（industry 的格键 = 本格）；数量单位随 AssetKind（LAND 千分亩、其余件）";
  private static final String DASHBOARD_STOCK_MONEY_WINDOW =
      "时点：当前 revision 的 actor GoodsAccount.money；世界级（复用 moneyByActorKind / moneyByHouseholdClass 的唯一算法），逐币种不跨币种求和";
  private static final String DASHBOARD_STOCK_POPULATION_WINDOW =
      "时点：ClassRow.population / ClassStanding.currentPositionId；无 ClassStanding 的旧档按 ClassRow.view.stratum 投影";
  private static final String DASHBOARD_FLOW_WINDOW =
      "本周期至今：FlowRow 逐字段（新周期第一天清零）；关账日读到的就是整周期（上周期读数归档在关账那一支 revision 里）";
  private static final String DASHBOARD_DERIVED_CREDIT_WINDOW =
      "F/headroom：混合窗口（income/taxPaid=本周期、basicRation=本周期累计、nextRoundNecessaryInput=读口时点配方、"
          + "grainStock=读口时点）；逐字段口径见 debtCapacity.window（E4b 唯一算法 DebtCapacityBook）";
  private static final String DASHBOARD_DERIVED_DEBT_OUTPUT_WINDOW =
      "债务本金=时点；commodity 产出=本周期至今（FlowRow.income[commodity]）；money 产出=具名缺失；跨窗口结构比只作趋势诊断";
  private static final String DASHBOARD_DERIVED_INTEREST_F_WINDOW =
      "interestDue=本周期至今（FlowRow.interestDue 合计）；F=混合窗口（同 DebtCapacity）";
  private static final String DASHBOARD_DERIVED_NEED_GAP_WINDOW =
      "unmetNeed=本周期至今（FlowRow.unmetNeed 合计）；cycleNaturalNeedMilli=本周期累计（逐日日初人口累加；仅粮）";
  private static final String DASHBOARD_DERIVED_NEXT_INPUT_WINDOW =
      "nextRoundNecessaryInput=读口时点配方口径；source=NON_RATION_CONSUMED_PROXY 时是本周期实际非口粮投入的代理，"
          + "不是真实下一轮投入；SHORTAGE=时点（ProductionOrganization.statusReason 具名）";
  private static final String DASHBOARD_CRISIS_WINDOW =
      "时点：EconomyData.crisisSignals（本格；signal.day 是发生日）；空列表 = 本格没有信号，不是读不到";

  /**
   * ★★ <b>E6c：统一经济 dashboard</b>（GUI / MCP 共用的唯一装配点；只读、无副作用）。
   *
   * <pre>
   * dashboard
   * ├─ scope                    本格坐标 / 家户数 / 经济激活 / 各分组口径范围
   * ├─ stocks                   【时点存量】
   * │  ├─ debtPrincipal         按 unit：principal / contractCount / defaultedCount / delinquentCount；
   * │  │                        逐户 + 合计（债务人侧；复用 ClassRow.debts 权威引用，按 id 去重）
   * │  ├─ assetSharesByKind     按 AssetKind：totalQuantity / ownershipByActor / operationByActor /
   * │  │                        selfOperatedQuantity / ownerNotOperatorQuantity / tenancy* / kind*
   * │  ├─ moneyByActorKind      复用 {@link #moneyByActorKind(ActorData)}
   * │  ├─ moneyByHouseholdClass 复用 {@link #moneyByHouseholdClass(EconomyData, ActorData)}
   * │  └─ populationByClassPosition 按 ClassStanding.currentPositionId（无 standing 用 view.stratum 投影）
   * ├─ flows                   【本周期流量】FlowRow 的 income/consumed/taxPaid/interestDue/newBorrowing/
   * │                          repaid/repaidMoney/capitalizedArrears/netSurplus/unmetNeed 合计
   * ├─ derived                 【本期派生】
   * │  ├─ creditPosition       复用 DebtCapacityBook（E4b 唯一 F/headroom 算法）的同一份块视图
   * │  ├─ debtToOutput         按 unit 的债务本金与 FlowRow.income[commodity]；money unit 具名缺失
   * │  ├─ interestToF          interestDue 合计 ÷ F 合计（F=0 ⇒ null + 具名 reason）
   * │  ├─ basicNeedGap         unmetNeed 逐商品；粮对 cycleNaturalNeedMilli，非粮具名缺失
   * │  └─ nextRoundInputGap    DebtCapacity.nextRoundNecessaryInput + source；SHORTAGE 具名按原因汇总
   * ├─ crisis                  本格 crisisSignals（复用 {@link #crisisSignalViews(EconomyData, HexCoord)}）
   * └─ windows                 逐字段组窗口 + 缺库存/缺价格/缺数据的具名 unavailable
   * </pre>
   *
   * <p>★★ <b>唯一算法/唯一拼写点</b>：本方法只做「按键求和 + 组织视图」，不复制任何 economy 公式—— F/headroom 走 {@link
   * DebtCapacityBook} 与 {@link DebtCapacity}；货币聚合走 {@link #moneyByActorKind}/{@link
   * #moneyByHouseholdClass}；危机信号走 {@link #crisisSignalViews}； 流量的逐字段 shape 与 {@link #flowView}
   * 同源（窗口字符串亦同源）。新增的聚合 helper 都住本文件： {@link #hexDebtStock}/{@link #UnitDebtAggregate}/{@link
   * #AssetKindAggregate}/ {@link #ClassPositionAggregate}/{@link #HexFlowAggregate}。
   *
   * <p>★★ <b>缺数据纪律</b>：29 个组件空表/缺键时，数组为空、比值为 null，并给出具名 reason；绝不填 0 冒充 「没有发生」「没有缺口」「没有信用」。库存读不到时沿用
   * E4b 的 {@link #DEBT_CAPACITY_STOCK_UNREADABLE}。
   */
  private static Map<String, Object> economyDashboard(
      HexCoord coord,
      EconomyData data,
      ActorData actors,
      Map<String, Map<String, Long>> moneyByKind,
      Map<String, Map<String, Long>> moneyByClass,
      List<HouseholdId> householdKeys,
      Map<HouseholdId, DebtCapacity> debtCapacities,
      Map<String, Object> debtCapacityBlock,
      Optional<MarketReport> report,
      long tick,
      String readoutUnavailable) {
    Map<String, Object> dashboard = new LinkedHashMap<>();
    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("hex", hexCoord(coord));
    scope.put("householdCount", householdKeys.size());
    scope.put("economyActivated", data.meta().isPresent());
    scope.put("tick", tick < 0L ? null : tick);
    scope.put("stockScope", "债务/资产/人口 = 本格家户（ClassRow.view.hex == 本格）；货币分布 = 世界级（唯一算法不按格过滤）");
    scope.put("flowScope", "本格家户（ClassRow.view.hex == 本格）");
    scope.put("derivedScope", "F/headroom/nextRoundInput = 本格家户；债务/产出另给 world 档（economy 无国家维）");
    dashboard.put("scope", scope);

    HexDebtStock hexDebt = hexDebtStock(data, householdKeys);
    HexFlowAggregate hexFlows = hexFlowAggregate(data, householdKeys);
    dashboard.put(
        "stocks", dashboardStocks(data, coord, moneyByKind, moneyByClass, householdKeys, hexDebt));
    dashboard.put("flows", hexFlows.view());
    dashboard.put(
        "derived",
        dashboardDerived(
            data,
            actors,
            coord,
            householdKeys,
            debtCapacities,
            debtCapacityBlock,
            hexDebt,
            hexFlows,
            report));
    Map<String, Object> crisis = new LinkedHashMap<>();
    crisis.put("scope", "本格");
    crisis.put("source", "EconomyData.crisisSignals（E5a 唯一来源；空列表 = 本格没有信号，不是读不到）");
    crisis.put("signals", crisisSignalViews(data, coord));
    crisis.put("unavailable", null);
    dashboard.put("crisis", crisis);
    dashboard.put("windows", dashboardWindows(report, tick, readoutUnavailable));
    return dashboard;
  }

  /** dashboard 的 stocks 分组（时点存量；只读聚合）。 */
  private static Map<String, Object> dashboardStocks(
      EconomyData data,
      HexCoord coord,
      Map<String, Map<String, Long>> moneyByKind,
      Map<String, Map<String, Long>> moneyByClass,
      List<HouseholdId> householdKeys,
      HexDebtStock hexDebt) {
    Map<String, Object> stocks = new LinkedHashMap<>();
    stocks.put("debtPrincipal", debtPrincipalStockView(hexDebt));
    stocks.put("assetSharesByKind", assetSharesByKindStockView(data, coord));
    stocks.put(
        "assetSharesNote",
        "assetSharesByKind 只统计 industry 格键 = 本格的份额；ownershipByActor/operationByActor 的 Σ 都等于"
            + " totalQuantity（资产守恒的读侧证据）；TENANCY 数量单列，不把租佃份额混进所有权");
    // ★★ 复用 E3 的唯一算法：传入的正是顶层 moneyByActorKind/moneyByHouseholdClass 的同一份结果。
    stocks.put("moneyByActorKind", moneyByKind);
    stocks.put("moneyByHouseholdClass", moneyByClass);
    stocks.put("moneyWindow", DASHBOARD_STOCK_MONEY_WINDOW);
    stocks.put("moneyNote", "逐币种表，不跨币种求和；发行/回笼/流通量见顶层 moneyIssuance（同一状态源，不重复发）");
    stocks.put(
        "populationByClassPosition", populationByClassPositionStockView(data, householdKeys));
    return stocks;
  }

  /**
   * ★★ E6c：本格家户的债务存量读数（一次聚合，stocks 与 debtToOutput 共用）。
   *
   * <p>方向：债务人侧 = {@link ClassRow#debts()}（放贷时写下的权威清单；按 id 去重，避免同一引用重复计数）。 债权人侧不在本块重复：旧键 {@code
   * creditCount}/{@code creditPrincipal} 与逐行 {@code credits} 已发出。
   */
  private static HexDebtStock hexDebtStock(EconomyData data, List<HouseholdId> householdKeys) {
    HexDebtStock stock = new HexDebtStock();
    for (HouseholdId key : householdKeys) {
      ClassRow row = data.classes().get(key);
      if (row == null) {
        Map<String, Object> missing = new LinkedHashMap<>();
        missing.put("household", key.value());
        missing.put("byUnit", new TreeMap<>());
        missing.put("unavailable", "该家户在 EconomyData.classes 里没有行（状态不完整）⇒ 债务存量不算，不填 0");
        stock.households.add(missing);
        continue;
      }
      Map<String, UnitDebtAggregate> byUnit = new TreeMap<>();
      int dangling = 0;
      for (DebtContractId debtId : new LinkedHashSet<>(row.debts())) {
        DebtContract debt = data.debtContracts().get(debtId);
        if (debt == null) {
          dangling++;
          continue;
        }
        String unitKey = debt.unit().key();
        byUnit.computeIfAbsent(unitKey, ignored -> new UnitDebtAggregate(debt.unit())).add(debt);
        stock
            .totalByUnit
            .computeIfAbsent(unitKey, ignored -> new UnitDebtAggregate(debt.unit()))
            .add(debt);
      }
      stock.unresolvedReferences += dangling;
      stock.households.add(householdDebtStockView(key, byUnit, dangling));
    }
    return stock;
  }

  /** 一个家户的债务存量视图（byUnit + 逐 unit 的合同条数/违约条数）。 */
  private static Map<String, Object> householdDebtStockView(
      HouseholdId key, Map<String, UnitDebtAggregate> byUnit, int dangling) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("household", key.value());
    Map<String, Object> units = new TreeMap<>();
    long contracts = 0L;
    long defaulted = 0L;
    long delinquent = 0L;
    for (Map.Entry<String, UnitDebtAggregate> entry : byUnit.entrySet()) {
      units.put(entry.getKey(), entry.getValue().view());
      contracts += entry.getValue().contractCount;
      defaulted += entry.getValue().defaultedCount;
      delinquent += entry.getValue().delinquentCount;
    }
    view.put("byUnit", units);
    view.put("principalNote", "本金不跨 unit 合计（粮/钱/其它商品各自计量）；逐 unit 本金见 byUnit");
    view.put("contractCount", contracts);
    view.put("defaultedCount", defaulted);
    view.put("delinquentCount", delinquent);
    view.put("unresolvedDebtReferenceCount", dangling);
    view.put("unresolvedDebtReferenceReason", dangling > 0 ? DASHBOARD_DEBT_REF_DANGLING : null);
    return view;
  }

  /** 本格家户债务人侧的债务存量块：逐户 + 按 unit 合计 + 总条数/违约条数。 */
  private static Map<String, Object> debtPrincipalStockView(HexDebtStock stock) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put(
        "scope",
        "本格家户（ClassRow.view.hex == 本格）的债务人侧；逐条引用 ClassRow.debts（按 id 去重）；"
            + "债权人侧见顶层 creditCount/creditPrincipal 与逐行 credits");
    view.put("window", DASHBOARD_STOCK_DEBT_WINDOW);
    Map<String, Object> byUnit = new TreeMap<>();
    long contracts = 0L;
    long defaulted = 0L;
    long delinquent = 0L;
    Map<String, Long> statusCounts = new TreeMap<>();
    for (Map.Entry<String, UnitDebtAggregate> entry : stock.totalByUnit.entrySet()) {
      UnitDebtAggregate aggregate = entry.getValue();
      byUnit.put(entry.getKey(), aggregate.view());
      contracts += aggregate.contractCount;
      defaulted += aggregate.defaultedCount;
      delinquent += aggregate.delinquentCount;
      for (Map.Entry<String, Long> status : aggregate.statusCounts.entrySet()) {
        statusCounts.merge(status.getKey(), status.getValue(), Long::sum);
      }
    }
    Map<String, Object> total = new LinkedHashMap<>();
    total.put("principalNote", "本金不跨 unit 合计（粮与钱不硬折、不同商品不硬折）；逐 unit 本金见 byUnit");
    total.put("contractCount", contracts);
    total.put("defaultedCount", defaulted);
    total.put("delinquentCount", delinquent);
    total.put("statusCounts", statusCounts);
    view.put("byUnit", byUnit);
    view.put("total", total);
    // ★ 便于直读的顶层合计（与 total 同一份来源；本金不跨 unit 合计，故顶层不发 principal）。
    view.put("contractCount", contracts);
    view.put("defaultedCount", defaulted);
    view.put("delinquentCount", delinquent);
    view.put("households", stock.households);
    view.put("householdCount", stock.households.size());
    view.put("unresolvedDebtReferenceCount", stock.unresolvedReferences);
    view.put(
        "unresolvedDebtReferenceReason",
        stock.unresolvedReferences > 0 ? DASHBOARD_DEBT_REF_DANGLING : null);
    view.put("unitNote", "每个 unit key 各自计量；不把 money 折成粮、也不把粮折成钱（跨 unit 求和无意义）");
    return view;
  }

  /**
   * ★★ E6c：按 {@link AssetKind} 的本格资产份额聚合。
   *
   * <p>ownershipByActor = 按 {@code owner} 求和；operationByActor = 按 {@code operator} 求和； 两者各自的 Σ 都等于
   * totalQuantity（产权/经营数量来自同一批份额）。TENANCY 数量单列； selfOperatedQuantity = owner ==
   * operator，ownerNotOperatorQuantity = owner != operator。
   */
  private static List<Map<String, Object>> assetSharesByKindStockView(
      EconomyData data, HexCoord coord) {
    String hexKey = IndustryHexKeys.hexKey(coord.q(), coord.r());
    Map<AssetKind, AssetKindAggregate> byKind = new TreeMap<>();
    for (AssetShare share : data.assetShares().values()) {
      if (IndustryHexKeys.hexKeyOf(share.industry()).filter(hexKey::equals).isEmpty()) {
        continue;
      }
      byKind
          .computeIfAbsent(share.asset(), ignored -> new AssetKindAggregate(share.asset()))
          .add(share);
    }
    List<Map<String, Object>> out = new ArrayList<>(byKind.size());
    for (AssetKindAggregate aggregate : byKind.values()) {
      out.add(aggregate.view());
    }
    return out;
  }

  /** 单个 AssetKind 的聚合中间量（读口私有；只在本文件的 dashboard 聚合里用）。 */
  private static final class AssetKindAggregate {
    private final AssetKind asset;
    private final Map<String, Long> kindQuantity = new TreeMap<>();
    private final Map<String, Long> kindCount = new TreeMap<>();
    private final Map<String, Long> ownershipByActor = new TreeMap<>();
    private final Map<String, Long> operationByActor = new TreeMap<>();
    private final Map<String, ActorRef> actorRefs = new LinkedHashMap<>();
    private long totalQuantity;
    private long shareCount;
    private long selfOperatedQuantity;
    private long ownerNotOperatorQuantity;
    private long tenancyQuantity;
    private long tenancyShareCount;

    private AssetKindAggregate(AssetKind asset) {
      this.asset = asset;
    }

    private void add(AssetShare share) {
      long quantity = share.quantity();
      totalQuantity += quantity;
      shareCount++;
      kindQuantity.merge(share.kind().name(), quantity, Long::sum);
      kindCount.merge(share.kind().name(), 1L, Long::sum);
      actorRefs.putIfAbsent(share.owner().toString(), share.owner());
      actorRefs.putIfAbsent(share.operator().toString(), share.operator());
      ownershipByActor.merge(share.owner().toString(), quantity, Long::sum);
      operationByActor.merge(share.operator().toString(), quantity, Long::sum);
      if (share.owner().equals(share.operator())) {
        selfOperatedQuantity += quantity;
      } else {
        ownerNotOperatorQuantity += quantity;
      }
      if (share.kind() == AssetShare.RightKind.TENANCY) {
        tenancyQuantity += quantity;
        tenancyShareCount++;
      }
    }

    private Map<String, Object> view() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("asset", asset.name());
      view.put("totalQuantity", totalQuantity);
      view.put("shareCount", shareCount);
      view.put("selfOperatedQuantity", selfOperatedQuantity);
      view.put("ownerNotOperatorQuantity", ownerNotOperatorQuantity);
      view.put("tenancyQuantity", tenancyQuantity);
      view.put("tenancyShareCount", tenancyShareCount);
      view.put("ownershipByActor", actorQuantityViews(ownershipByActor, actorRefs));
      view.put("operationByActor", actorQuantityViews(operationByActor, actorRefs));
      view.put("kindQuantity", new TreeMap<>(kindQuantity));
      view.put("kindCount", new TreeMap<>(kindCount));
      return view;
    }
  }

  /** 把「actor 规范串 → 数量」展开成保序（字典序）的读侧形；同一份 actor 引用表服务 ownership/operation。 */
  private static List<Map<String, Object>> actorQuantityViews(
      Map<String, Long> quantities, Map<String, ActorRef> actorRefs) {
    List<Map<String, Object>> out = new ArrayList<>(quantities.size());
    for (Map.Entry<String, Long> entry : quantities.entrySet()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("actor", actorRefView(actorRefs.get(entry.getKey())));
      item.put("quantity", entry.getValue());
      out.add(item);
    }
    return out;
  }

  /**
   * ★★ E6c：本格人口的阶层分布（时点）。
   *
   * <p>位置来源：有 {@link ClassStanding} ⇒ {@code currentPositionId}；无 standing 的旧档 ⇒ {@code "legacy:" +
   * ClassRow.view.stratum}（显式投影，不改写状态、不伪造默认 position）。
   */
  private static Map<String, Object> populationByClassPositionStockView(
      EconomyData data, List<HouseholdId> householdKeys) {
    Map<String, ClassPositionAggregate> groups = new TreeMap<>();
    int standingHouseholds = 0;
    int legacyFallbackHouseholds = 0;
    for (HouseholdId key : householdKeys) {
      ClassRow row = data.classes().get(key);
      if (row == null) {
        continue;
      }
      ClassStanding standing = data.classStandings().get(key);
      String classPosition;
      boolean fromStanding;
      if (standing != null) {
        // ★ 位置键就是 currentPositionId（不与 legacy 投影共享命名空间前缀）。
        classPosition = standing.currentPositionId().value();
        fromStanding = true;
        standingHouseholds++;
      } else {
        // ★ 旧档兼容：把 view.stratum 直接投影成位置键；来源由每条 source 字段标明。
        classPosition = row.view().stratum().value();
        fromStanding = false;
        legacyFallbackHouseholds++;
      }
      groups
          .computeIfAbsent(classPosition, ignored -> new ClassPositionAggregate())
          .add(row, fromStanding);
    }
    List<Map<String, Object>> out = new ArrayList<>(groups.size());
    for (Map.Entry<String, ClassPositionAggregate> entry : groups.entrySet()) {
      out.add(entry.getValue().view(entry.getKey()));
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("scope", "本格家户（ClassRow.view.hex == 本格）；按当前阶层位置聚合人口/家户数");
    view.put("window", DASHBOARD_STOCK_POPULATION_WINDOW);
    view.put("standingHouseholds", standingHouseholds);
    view.put("legacyFallbackHouseholds", legacyFallbackHouseholds);
    view.put(
        "legacyFallbackNote",
        "位置键取 ClassStanding.currentPositionId；无 ClassStanding 的旧档把 ClassRow.view.stratum 直接投影成位置键"
            + "（source=ClassRow.view.stratum）。同一键同时来自两种来源时 source=mixed，并给出 standing/legacy 两栏户数，"
            + "不静默二选一");
    view.put("groupCount", out.size());
    view.put("groups", out);
    return view;
  }

  /** 一个阶层位置组的聚合中间量（读口私有）。 */
  private static final class ClassPositionAggregate {
    private long population;
    private long householdCount;
    private long laborMilli;
    private long participationAdjustedLaborMilli;
    private long standingHouseholds;
    private long legacyFallbackHouseholds;

    private void add(ClassRow row, boolean fromStanding) {
      population += row.population();
      householdCount++;
      laborMilli += row.laborMilli();
      participationAdjustedLaborMilli += row.participationAdjustedLaborMilli();
      if (fromStanding) {
        standingHouseholds++;
      } else {
        legacyFallbackHouseholds++;
      }
    }

    /** 来源标记（短稳定串：三条来源各恰一个值；解释见块级 legacyFallbackNote）。 */
    private String source() {
      if (standingHouseholds > 0L && legacyFallbackHouseholds > 0L) {
        return "mixed";
      }
      return standingHouseholds > 0L ? "ClassStanding.currentPositionId" : "ClassRow.view.stratum";
    }

    private Map<String, Object> view(String classPosition) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("classPosition", classPosition);
      view.put("source", source());
      view.put("population", population);
      view.put("householdCount", householdCount);
      view.put("laborMilli", laborMilli);
      view.put("participationAdjustedLaborMilli", participationAdjustedLaborMilli);
      view.put("standingHouseholds", standingHouseholds);
      view.put("legacyFallbackHouseholds", legacyFallbackHouseholds);
      return view;
    }
  }

  /** 本格家户 FlowRow 的一次合计（stocks 之外唯一一处流量聚合；flows 与 derived 共用）。 */
  private static HexFlowAggregate hexFlowAggregate(
      EconomyData data, List<HouseholdId> householdKeys) {
    HexFlowAggregate aggregate = new HexFlowAggregate();
    for (HouseholdId key : householdKeys) {
      FlowRow flow = data.flows().get(key);
      if (flow == null) {
        aggregate.missingFlowHouseholds++;
        continue;
      }
      aggregate.add(flow);
    }
    return aggregate;
  }

  /** FlowRow 的本格合计中间量（读口私有；逐字段口径与 {@link FlowRow} 类注同源）。 */
  private static final class HexFlowAggregate {
    private final Map<String, Long> income = new TreeMap<>();
    private final Map<String, Long> consumed = new TreeMap<>();
    private final Map<String, Long> unmetNeed = new TreeMap<>();
    private final Map<String, Long> repaidMoney = new TreeMap<>();
    private final Map<String, Long> capitalizedArrears = new TreeMap<>();
    private long taxPaid;
    private long interestDue;
    private long newBorrowing;
    private long repaid;
    private long netSurplus;
    private int flowRowHouseholds;
    private int missingFlowHouseholds;

    private void add(FlowRow flow) {
      flowRowHouseholds++;
      mergeInto(income, flow.income());
      mergeInto(consumed, flow.consumed());
      mergeInto(unmetNeed, flow.unmetNeed());
      mergeMoneyInto(repaidMoney, flow.repaidMoney());
      mergeStringLong(capitalizedArrears, flow.capitalizedArrears());
      taxPaid += flow.taxPaid();
      interestDue += flow.interestDue();
      newBorrowing += flow.newBorrowing();
      repaid += flow.repaid();
      netSurplus += flow.netSurplus();
    }

    private Map<String, Object> view() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("scope", "本格家户（ClassRow.view.hex == 本格）");
      view.put("window", DASHBOARD_FLOW_WINDOW);
      view.put("householdCount", flowRowHouseholds + missingFlowHouseholds);
      view.put("flowRowHouseholds", flowRowHouseholds);
      view.put("missingFlowHouseholds", missingFlowHouseholds);
      view.put("missingFlowReason", missingFlowHouseholds > 0 ? DASHBOARD_FLOW_ROW_MISSING : null);
      view.put("income", new TreeMap<>(income));
      view.put("consumed", new TreeMap<>(consumed));
      view.put("taxPaid", taxPaid);
      view.put("interestDue", interestDue);
      view.put("newBorrowing", newBorrowing);
      view.put("repaid", repaid);
      view.put("repaidMoney", new TreeMap<>(repaidMoney));
      view.put("capitalizedArrears", new TreeMap<>(capitalizedArrears));
      view.put("netSurplus", netSurplus);
      view.put("unmetNeed", new TreeMap<>(unmetNeed));
      view.put(
          "netSurplusNote",
          "netSurplus 是**粮口径标量**（income[grain]−consumed[grain]−taxPaid−interestDue），"
              + "不是跨商品折算；其余商品的净额在 income/consumed 两张表里分开读");
      view.put("capitalizedArrearsNote", "值单位 = 各 DebtUnit.key() 的最小计量单位；资本化只把制度未付落成债权，不搬库存/货币");
      return view;
    }
  }

  /** 把一个 String→Long 表并入目标 TreeMap（{@code capitalizedArrears} 的 DebtUnit.key() 分组；唯一拼写点）。 */
  private static void mergeStringLong(Map<String, Long> target, Map<String, Long> source) {
    for (Map.Entry<String, Long> entry : source.entrySet()) {
      target.merge(entry.getKey(), entry.getValue(), Long::sum);
    }
  }

  /** dashboard 的 derived 分组（只读派生；F/headroom 复用 E4b 块视图，不重算公式）。 */
  private static Map<String, Object> dashboardDerived(
      EconomyData data,
      ActorData actors,
      HexCoord coord,
      List<HouseholdId> householdKeys,
      Map<HouseholdId, DebtCapacity> debtCapacities,
      Map<String, Object> debtCapacityBlock,
      HexDebtStock hexDebt,
      HexFlowAggregate hexFlows,
      Optional<MarketReport> report) {
    Map<String, Object> derived = new LinkedHashMap<>();
    derived.put("creditPosition", creditPositionDerivedView(debtCapacityBlock));
    derived.put(
        "debtToOutput", debtToOutputDerivedView(data, householdKeys, hexDebt, report, coord));
    derived.put("interestToF", interestToFDerivedView(householdKeys, debtCapacities, hexFlows));
    derived.put("basicNeedGap", basicNeedGapDerivedView(data, householdKeys, hexFlows));
    derived.put(
        "nextRoundInputGap",
        nextRoundInputGapDerivedView(data, actors, coord, householdKeys, debtCapacities));
    return derived;
  }

  /** F/headroom/信用头寸：直接嵌 E4b 的同一份块视图（唯一算法已经算好，不在这里做第二份代数）。 */
  private static Map<String, Object> creditPositionDerivedView(
      Map<String, Object> debtCapacityBlock) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put(
        "source",
        "DebtCapacityBook.capacitiesForState（E4b 唯一 F/headroom 算法；与顶层 debtCapacity 同一份对象、同一份窗口）");
    view.put("window", DASHBOARD_DERIVED_CREDIT_WINDOW);
    view.put("readout", debtCapacityBlock);
    return view;
  }

  /**
   * ★★ E6c：债务/产出（按 unit；债务=时点本金，产出=本周期已实现所得）。
   *
   * <p>commodity unit 的分母 = {@code Σ FlowRow.income[commodity]}（粮即 income[grain]）；money unit
   * 没有本周期货币产出口径 ⇒ 具名缺失（见 {@link #DASHBOARD_MONEY_OUTPUT_UNAVAILABLE}）。 「本国」口径因 economy
   * 切片无国家维而具名缺失；world 档是整份 EconomyData，不是某个国家。
   */
  private static Map<String, Object> debtToOutputDerivedView(
      EconomyData data,
      List<HouseholdId> householdKeys,
      HexDebtStock hexDebt,
      Optional<MarketReport> report,
      HexCoord coord) {
    List<Map<String, Object>> entries = new ArrayList<>();
    entries.addAll(
        debtToOutputEntries(
            "hex", hexDebt.totalByUnit, incomeTotalsForHouseholds(data, householdKeys)));
    entries.addAll(debtToOutputEntries("world", debtTotalsWorld(data), incomeTotalsWorld(data)));
    entries.sort(
        Comparator.comparing((Map<String, Object> entry) -> (String) entry.get("scope"))
            .thenComparing(entry -> (String) entry.get("unitKey")));
    Map<String, Object> nation = new LinkedHashMap<>();
    nation.put("scope", "nation");
    nation.put("debtPrincipalByUnit", null);
    nation.put("output", null);
    nation.put("debtToOutputPerMille", null);
    nation.put("unavailable", DASHBOARD_NATION_SCOPE_UNAVAILABLE);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("window", DASHBOARD_DERIVED_DEBT_OUTPUT_WINDOW);
    view.put("entries", entries);
    // ★ 「本国」口径在 economy 切片无国家维 ⇒ 具名缺失；同时给 nationUnavailable 这个直白键名。
    view.put("nation", nation);
    view.put("nationUnavailable", nation);
    view.put("moneyOutputReference", marketTurnoverReferenceView(report, coord));
    view.put(
        "caveat",
        "债务本金是时点、产出是本周期流量 ⇒ 该比值是跨窗口结构比，只作趋势诊断，不得与其他窗口的量并排比较；"
            + "commodity 单位各自用自己的 income；money 单位不硬折成粮");
    return view;
  }

  /** 一个 scope 的「逐 unit 债务对产出」条目（commodity 用同商品 income；money 具名缺失）。 */
  private static List<Map<String, Object>> debtToOutputEntries(
      String scope, Map<String, UnitDebtAggregate> totals, Map<String, Long> incomeByCommodity) {
    List<Map<String, Object>> entries = new ArrayList<>(totals.size());
    for (Map.Entry<String, UnitDebtAggregate> entry : totals.entrySet()) {
      UnitDebtAggregate aggregate = entry.getValue();
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("scope", scope);
      item.put("unitKey", entry.getKey());
      item.put("debtPrincipal", aggregate.principal);
      item.put("debtContractCount", aggregate.contractCount);
      item.put("numeratorWindow", "时点：当前 revision 的债务本金合计（逐 unit；全部状态）");
      if (aggregate.unit instanceof DebtUnit.Commodity commodity) {
        String commodityId = commodity.commodity().value();
        long output = incomeByCommodity.getOrDefault(commodityId, 0L);
        item.put("unitKind", "commodity");
        item.put("commodity", commodityId);
        item.put("currency", null);
        item.put("output", output);
        item.put("outputMeasure", "本周期已实现所得 Σ FlowRow.income[" + commodityId + "]");
        item.put("denominatorWindow", "本周期至今：Σ FlowRow.income[" + commodityId + "]（新周期第一天清零）");
        OptionalLong perMille = perMilleOrEmpty(aggregate.principal, output);
        if (perMille.isPresent()) {
          item.put("debtToOutputPerMille", perMille.getAsLong());
          item.put("unavailable", null);
        } else {
          item.put("debtToOutputPerMille", null);
          item.put(
              "unavailable",
              output <= 0L
                  ? "本周期产出 Σ FlowRow.income["
                      + commodityId
                      + "] = 0 ⇒ "
                      + DASHBOARD_DENOMINATOR_ZERO
                  : DASHBOARD_RATIO_OVERFLOW);
        }
      } else {
        DebtUnit.Money money = (DebtUnit.Money) aggregate.unit;
        item.put("unitKind", "money");
        item.put("currency", money.currency().value());
        item.put("commodity", null);
        item.put("output", null);
        item.put("outputMeasure", null);
        item.put("denominatorWindow", null);
        item.put("debtToOutputPerMille", null);
        item.put("unavailable", DASHBOARD_MONEY_OUTPUT_UNAVAILABLE);
      }
      entries.add(item);
    }
    return entries;
  }

  /** 整份 EconomyData 的逐 unit 债务本金合计（world 档；只读）。 */
  private static Map<String, UnitDebtAggregate> debtTotalsWorld(EconomyData data) {
    Map<String, UnitDebtAggregate> totals = new TreeMap<>();
    for (DebtContract debt : data.debtContracts().values()) {
      totals
          .computeIfAbsent(debt.unit().key(), ignored -> new UnitDebtAggregate(debt.unit()))
          .add(debt);
    }
    return totals;
  }

  /** 本格家户的本周期 income（商品 id → 合计；即债务/产出的 commodity 分母来源）。 */
  private static Map<String, Long> incomeTotalsForHouseholds(
      EconomyData data, List<HouseholdId> householdKeys) {
    Map<String, Long> totals = new TreeMap<>();
    for (HouseholdId key : householdKeys) {
      FlowRow flow = data.flows().get(key);
      if (flow != null) {
        mergeInto(totals, flow.income());
      }
    }
    return totals;
  }

  /** 整份 EconomyData 的本周期 income（world 档；只读）。 */
  private static Map<String, Long> incomeTotalsWorld(EconomyData data) {
    Map<String, Long> totals = new TreeMap<>();
    for (FlowRow flow : data.flows().values()) {
      mergeInto(totals, flow.income());
    }
    return totals;
  }

  /** money 产出的参考口径：最近一轮市场成交（进程内；**明确不是**债务/产出的分母）。 */
  private static Map<String, Object> marketTurnoverReferenceView(
      Optional<MarketReport> report, HexCoord coord) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("window", "最近一轮市场开市（进程内 MarketReport；不落盘、重启即失；不是本周期累计）");
    if (report.isEmpty()) {
      view.put("hexTurnoverMilli", null);
      view.put("worldTurnoverMilli", null);
      view.put("unavailable", DASHBOARD_MARKET_REPORT_UNAVAILABLE);
      return view;
    }
    MarketReport marketReport = report.orElseThrow();
    long hexTurnover = 0L;
    long hexFills = 0L;
    long worldTurnover = 0L;
    for (MarketReport.Fill fill : marketReport.fills()) {
      worldTurnover += fill.goodsPaymentMilli();
      if (fill.from().equals(coord) || fill.to().equals(coord)) {
        hexTurnover += fill.goodsPaymentMilli();
        hexFills++;
      }
    }
    view.put("hexTurnoverMilli", hexTurnover);
    view.put("hexFillCount", hexFills);
    view.put("worldTurnoverMilli", worldTurnover);
    view.put("worldFillCount", marketReport.fills().size());
    view.put("unavailable", null);
    view.put("note", "只作参考：money 债务/产出不拿它当分母（单轮成交 ≠ 本周期货币产出；禁止混窗口比较）");
    return view;
  }

  /** 利息/F：interestDue 合计 ÷ F 合计；F=0 或溢出 ⇒ null + 具名 reason（不除零）。 */
  private static Map<String, Object> interestToFDerivedView(
      List<HouseholdId> householdKeys,
      Map<HouseholdId, DebtCapacity> debtCapacities,
      HexFlowAggregate hexFlows) {
    long fTotal = 0L;
    int unavailableHouseholds = 0;
    List<Map<String, Object>> households = new ArrayList<>(householdKeys.size());
    for (HouseholdId key : householdKeys) {
      DebtCapacity capacity = debtCapacities.get(key);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("household", key.value());
      if (capacity == null) {
        item.put("F", null);
        item.put("nextRoundNecessaryInputSource", null);
        item.put("unavailable", "该家户没有 DebtCapacity（ClassRow 缺失或容量算法未覆盖）⇒ F 不计入合计，不填 0");
        unavailableHouseholds++;
      } else {
        item.put("F", capacity.F());
        item.put("nextRoundNecessaryInputSource", capacity.nextRoundNecessaryInputSource().name());
        item.put("unavailable", null);
        fTotal += capacity.F();
      }
      households.add(item);
    }
    OptionalLong ratio = perMilleOrEmpty(hexFlows.interestDue, fTotal);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("scope", "本格家户（interestDue = FlowRow 合计；F = DebtCapacity 合计）");
    view.put("window", DASHBOARD_DERIVED_INTEREST_F_WINDOW);
    view.put("interestDue", hexFlows.interestDue);
    view.put("F", fTotal);
    view.put("interestToFPerMille", ratio.isPresent() ? ratio.getAsLong() : null);
    view.put(
        "unavailable",
        ratio.isPresent()
            ? null
            : (fTotal > 0L ? DASHBOARD_RATIO_OVERFLOW : DASHBOARD_DENOMINATOR_ZERO));
    view.put("unavailableFHouseholds", unavailableHouseholds);
    view.put(
        "partialTotalNote",
        unavailableHouseholds > 0 ? "F 合计只含可算行；缺失行数见 unavailableFHouseholds" : null);
    view.put("households", households);
    view.put("note", "利息/F = 本周期应付利息 ÷ F；F=0 时比值无定义（null + reason），绝不给 0 或除零");
    return view;
  }

  /**
   * 基本需求缺口：{@link FlowRow#unmetNeed()}（逐商品、本周期至今）对 {@link
   * ClassRow#cycleNaturalNeedMilli()}（本周期累计、仅粮）。
   */
  private static Map<String, Object> basicNeedGapDerivedView(
      EconomyData data, List<HouseholdId> householdKeys, HexFlowAggregate hexFlows) {
    long naturalNeedGrain = 0L;
    for (HouseholdId key : householdKeys) {
      ClassRow row = data.classes().get(key);
      if (row != null) {
        naturalNeedGrain += row.cycleNaturalNeedMilli();
      }
    }
    Set<String> commodities = new TreeSet<>(hexFlows.unmetNeed.keySet());
    if (naturalNeedGrain > 0L) {
      commodities.add(GRAIN.value());
    }
    List<Map<String, Object>> byCommodity = new ArrayList<>(commodities.size());
    for (String commodity : commodities) {
      long unmet = hexFlows.unmetNeed.getOrDefault(commodity, 0L);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("commodity", commodity);
      item.put("unmetNeed", unmet);
      if (GRAIN.value().equals(commodity)) {
        item.put("naturalNeed", naturalNeedGrain);
        item.put("naturalNeedWindow", "本周期累计（ClassRow.cycleNaturalNeedMilli；逐日按日初人口累加，新周期第一天重置）");
        OptionalLong gap = perMilleOrEmpty(unmet, naturalNeedGrain);
        item.put("gapPerMille", gap.isPresent() ? gap.getAsLong() : null);
        item.put(
            "unavailable",
            gap.isPresent()
                ? null
                : (naturalNeedGrain > 0L ? DASHBOARD_RATIO_OVERFLOW : DASHBOARD_DENOMINATOR_ZERO));
      } else {
        item.put("naturalNeed", null);
        item.put("naturalNeedWindow", null);
        item.put("gapPerMille", null);
        item.put("unavailable", DASHBOARD_NON_GRAIN_NEED_DENOMINATOR);
      }
      byCommodity.add(item);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("scope", "本格家户；unmetNeed 与 cycleNaturalNeedMilli 是同窗口（本周期）");
    view.put("window", DASHBOARD_DERIVED_NEED_GAP_WINDOW);
    view.put("unmetNeed", new TreeMap<>(hexFlows.unmetNeed));
    view.put("naturalNeedGrain", naturalNeedGrain);
    view.put("byCommodity", byCommodity);
    view.put("missingFlowHouseholds", hexFlows.missingFlowHouseholds);
    view.put(
        "missingFlowReason",
        hexFlows.missingFlowHouseholds > 0 ? DASHBOARD_FLOW_ROW_MISSING : null);
    if (byCommodity.isEmpty()) {
      view.put("note", "本格没有 FlowRow.unmetNeed 且本周期自然需要为 0（空表/旧档合法态）⇒ 无可聚合缺口");
    }
    return view;
  }

  /**
   * 下一轮投入缺口：复用 E4b 的 {@link DebtCapacity#nextRoundNecessaryInput()} 与 {@link
   * DebtCapacity#nextRoundNecessaryInputSource()}；另附本格相关 {@link
   * ProductionOrganization.Status#SHORTAGE} 的具名 statusReason 汇总。
   */
  private static Map<String, Object> nextRoundInputGapDerivedView(
      EconomyData data,
      ActorData actors,
      HexCoord coord,
      List<HouseholdId> householdKeys,
      Map<HouseholdId, DebtCapacity> debtCapacities) {
    long total = 0L;
    long proxyHouseholds = 0L;
    int unavailableHouseholds = 0;
    Map<String, Long> sourceCounts = new TreeMap<>();
    List<Map<String, Object>> households = new ArrayList<>(householdKeys.size());
    for (HouseholdId key : householdKeys) {
      DebtCapacity capacity = debtCapacities.get(key);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("household", key.value());
      if (capacity == null) {
        item.put("nextRoundNecessaryInput", null);
        item.put("nextRoundNecessaryInputSource", null);
        item.put("nextRoundNecessaryInputIsProxy", null);
        item.put("unavailable", "该家户没有 DebtCapacity（ClassRow 缺失或容量算法未覆盖）⇒ 投入缺口不算，不填 0");
        unavailableHouseholds++;
      } else {
        item.put("nextRoundNecessaryInput", capacity.nextRoundNecessaryInput());
        item.put("nextRoundNecessaryInputSource", capacity.nextRoundNecessaryInputSource().name());
        item.put("nextRoundNecessaryInputIsProxy", capacity.nextRoundNecessaryInputIsProxy());
        item.put("unavailable", null);
        total += capacity.nextRoundNecessaryInput();
        sourceCounts.merge(capacity.nextRoundNecessaryInputSource().name(), 1L, Long::sum);
        if (capacity.nextRoundNecessaryInputIsProxy()) {
          proxyHouseholds++;
        }
      }
      households.add(item);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("scope", "本格家户；SHORTAGE 另按本格相关组织汇总");
    view.put("window", DASHBOARD_DERIVED_NEXT_INPUT_WINDOW);
    view.put("total", total);
    view.put("sourceCounts", sourceCounts);
    view.put("proxyHouseholds", proxyHouseholds);
    view.put(
        "proxyNote",
        proxyHouseholds > 0
            ? "source=NON_RATION_CONSUMED_PROXY 的行用的是本周期实际非口粮投入的代理，不是真实下一轮投入"
            : null);
    view.put("unavailableHouseholds", unavailableHouseholds);
    view.put("households", households);
    view.put("shortageOrganizations", shortageOrganizationSummary(data, actors, coord));
    return view;
  }

  /**
   * ★★ E6c：本格相关的 {@link ProductionOrganization.Status#SHORTAGE} 汇总（同时给 world 总数）。
   *
   * <p>「本格相关」唯一判据（唯一拼写点，见 {@link #productionOrganizationTouchesHex}）：unit 产业格 = 本格，或 organizer
   * 的账户在本格，或 laborSource 家户住在该格，或 assetSource 份额登记在本格；四者任一命中。 无 unit 且四路线索都不在本格的组织不冒名计入本格，但仍进 world
   * 汇总。
   */
  private static Map<String, Object> shortageOrganizationSummary(
      EconomyData data, ActorData actors, HexCoord coord) {
    Set<ActorRef> organizersAtHex = new LinkedHashSet<>();
    for (GoodsAccount account : accountsAt(data, actors, coord)) {
      organizersAtHex.add(HouseholdActors.of(account.key().household()));
    }
    List<ProductionOrganization> organizations =
        new ArrayList<>(data.productionOrganizations().values());
    organizations.sort(Comparator.comparing(organization -> organization.id().value()));
    List<Map<String, Object>> local = new ArrayList<>();
    Map<String, Long> localByReason = new TreeMap<>();
    Map<String, Long> worldByReason = new TreeMap<>();
    long worldTotal = 0L;
    for (ProductionOrganization organization : organizations) {
      if (organization.status() != ProductionOrganization.Status.SHORTAGE) {
        continue;
      }
      worldTotal++;
      worldByReason.merge(organization.statusReason(), 1L, Long::sum);
      if (!productionOrganizationTouchesHex(data, organization, coord, organizersAtHex)) {
        continue;
      }
      localByReason.merge(organization.statusReason(), 1L, Long::sum);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", organization.id().value());
      item.put("modeId", organization.modeId().value());
      item.put("classPositionId", organization.classPositionId().value());
      item.put("unitId", organization.unitId().map(unitId -> unitId.value()).orElse(null));
      item.put("organizer", actorRefView(organization.organizer()));
      item.put("status", organization.status().name());
      item.put("statusReason", organization.statusReason());
      local.add(item);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("scope", "本格相关（unit 产业格/organizer 账户/laborSources 家户/assetSources 份额任一命中）");
    view.put("window", "时点：ProductionOrganization 现值（status=SHORTAGE；statusReason 由模型保证非空白）");
    view.put("total", local.size());
    view.put("byReason", reasonCountViews(localByReason));
    view.put("organizations", local);
    view.put("worldTotal", worldTotal);
    view.put("worldByReason", reasonCountViews(worldByReason));
    return view;
  }

  /** ProductionOrganization 是否与本格相关（见 {@link #shortageOrganizationSummary} 的四路判据）。 */
  private static boolean productionOrganizationTouchesHex(
      EconomyData data,
      ProductionOrganization organization,
      HexCoord coord,
      Set<ActorRef> organizersAtHex) {
    String hexKey = IndustryHexKeys.hexKey(coord.q(), coord.r());
    if (organization.unitId().isPresent()) {
      ProductionUnit unit = data.units().get(organization.unitId().get());
      if (unit != null
          && IndustryHexKeys.hexKeyOf(unit.industry()).filter(hexKey::equals).isPresent()) {
        return true;
      }
    }
    if (organizersAtHex.contains(organization.organizer())) {
      return true;
    }
    for (HouseholdId household : organization.laborSources()) {
      ClassRow row = data.classes().get(household);
      if (row != null && row.view().hex().equals(coord)) {
        return true;
      }
    }
    for (AssetShareId shareId : organization.assetSources()) {
      AssetShare share = data.assetShares().get(shareId);
      if (share != null
          && IndustryHexKeys.hexKeyOf(share.industry()).filter(hexKey::equals).isPresent()) {
        return true;
      }
    }
    return false;
  }

  /** 「原因 → 条数」按原因字典序展开（稳定序）。 */
  private static List<Map<String, Object>> reasonCountViews(Map<String, Long> counts) {
    List<Map<String, Object>> out = new ArrayList<>(counts.size());
    for (Map.Entry<String, Long> entry : counts.entrySet()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("statusReason", entry.getKey());
      item.put("count", entry.getValue());
      out.add(item);
    }
    return out;
  }

  /** dashboard 的 windows 块：逐字段组窗口 + 具名 unavailable（时点/本周期/累计不许混读）。 */
  private static Map<String, Object> dashboardWindows(
      Optional<MarketReport> report, long tick, String readoutUnavailable) {
    Map<String, Object> windows = new LinkedHashMap<>();
    windows.put("stocks.debtPrincipal", DASHBOARD_STOCK_DEBT_WINDOW);
    windows.put("stocks.assetSharesByKind", DASHBOARD_STOCK_ASSET_WINDOW);
    windows.put("stocks.moneyByActorKind", DASHBOARD_STOCK_MONEY_WINDOW);
    windows.put("stocks.moneyByHouseholdClass", DASHBOARD_STOCK_MONEY_WINDOW);
    windows.put("stocks.populationByClassPosition", DASHBOARD_STOCK_POPULATION_WINDOW);
    windows.put("flows", DASHBOARD_FLOW_WINDOW);
    windows.put("flows.income", DASHBOARD_FLOW_WINDOW + "；逐商品，值单位 = 该商品最小计量单位");
    windows.put("flows.consumed", DASHBOARD_FLOW_WINDOW + "；逐商品（不含生产损耗）");
    windows.put("flows.taxPaid", DASHBOARD_FLOW_WINDOW + "；粮口径（毫粮）");
    windows.put("flows.interestDue", DASHBOARD_FLOW_WINDOW + "；粮口径（毫粮；计息只增本金）");
    windows.put("flows.newBorrowing", DASHBOARD_FLOW_WINDOW + "；粮口径（毫粮）");
    windows.put("flows.repaid", DASHBOARD_FLOW_WINDOW + "；**只含粮债本金**（毫粮）");
    windows.put("flows.repaidMoney", DASHBOARD_FLOW_WINDOW + "；逐币种、最小币值；只含货币债本金");
    windows.put(
        "flows.capitalizedArrears",
        DASHBOARD_FLOW_WINDOW + "；键 = DebtUnit.key()，值单位 = 该 unit 最小计量单位");
    windows.put("flows.netSurplus", DASHBOARD_FLOW_WINDOW + "；粮口径标量（可为负）");
    windows.put("flows.unmetNeed", DASHBOARD_FLOW_WINDOW + "；逐商品（需求 − 实得的逐日累加）");
    windows.put("derived.creditPosition", DASHBOARD_DERIVED_CREDIT_WINDOW);
    windows.put("derived.debtToOutput", DASHBOARD_DERIVED_DEBT_OUTPUT_WINDOW);
    windows.put("derived.interestToF", DASHBOARD_DERIVED_INTEREST_F_WINDOW);
    windows.put("derived.basicNeedGap", DASHBOARD_DERIVED_NEED_GAP_WINDOW);
    windows.put("derived.nextRoundInputGap", DASHBOARD_DERIVED_NEXT_INPUT_WINDOW);
    windows.put("crisis.signals", DASHBOARD_CRISIS_WINDOW);
    windows.put(
        "reference.moneyIssuance",
        "累计（EconomyMeta.activatedDay 至当前 revision）；发行/回笼/流通量见顶层 moneyIssuance"
            + "（dashboard 不重复发，避免同一事实两处读）");
    Map<String, Object> unavailable = new TreeMap<>();
    unavailable.put("inventory", DEBT_CAPACITY_STOCK_UNREADABLE);
    unavailable.put("unitPrice", DASHBOARD_PRICE_UNAVAILABLE);
    unavailable.put("assetMarketValue", DASHBOARD_ASSET_MARKET_VALUE_UNAVAILABLE);
    unavailable.put("moneyOutput", DASHBOARD_MONEY_OUTPUT_UNAVAILABLE);
    unavailable.put("nonGrainNaturalNeed", DASHBOARD_NON_GRAIN_NEED_DENOMINATOR);
    unavailable.put("nationScope", DASHBOARD_NATION_SCOPE_UNAVAILABLE);
    unavailable.put("danglingDebtReferences", DASHBOARD_DEBT_REF_DANGLING);
    if (report.isPresent()) {
      unavailable.put("marketReport", null);
    } else {
      unavailable.put(
          "marketReport",
          readoutUnavailable == null || readoutUnavailable.isEmpty()
              ? DASHBOARD_MARKET_REPORT_UNAVAILABLE
              : readoutUnavailable);
    }
    windows.put("unavailable", unavailable);
    windows.put(
        "clock",
        "tick="
            + (tick < 0L ? "null（本入口没有 SimulationState 上下文）" : Long.toString(tick))
            + "；时点 = 当前 revision；本周期 = FlowRow 自新周期第一天起的累计（关账日即整周期）");
    return windows;
  }

  /**
   * ★★ E6c：整数千分比 numerator/denominator（向下取整）。
   *
   * <p>denominator ≤ 0 或 numerator > Long.MAX_VALUE/1000 ⇒ {@link OptionalLong#empty()}， 由调用方给具名
   * reason（不除零、不抛、不截断成错值）。
   */
  private static OptionalLong perMilleOrEmpty(long numerator, long denominator) {
    if (denominator <= 0L || numerator > Long.MAX_VALUE / 1000L) {
      return OptionalLong.empty();
    }
    return OptionalLong.of(numerator * 1000L / denominator);
  }

  /** 本格家户债务存量的一次聚合结果（读口私有；stocks 与 debtToOutput 共用）。 */
  private static final class HexDebtStock {
    private final Map<String, UnitDebtAggregate> totalByUnit = new TreeMap<>();
    private final List<Map<String, Object>> households = new ArrayList<>();
    private int unresolvedReferences;
  }

  /** 单个 DebtUnit.key() 的债务聚合中间量（读口私有；principal/条数/违约条数/状态分布）。 */
  private static final class UnitDebtAggregate {
    private final DebtUnit unit;
    private final Map<String, Long> statusCounts = new TreeMap<>();
    private final Set<HouseholdId> households = new LinkedHashSet<>();
    private long principal;
    private long contractCount;
    private long defaultedCount;
    private long delinquentCount;

    private UnitDebtAggregate(DebtUnit unit) {
      this.unit = unit;
    }

    private void add(DebtContract debt) {
      principal += debt.principal();
      contractCount++;
      statusCounts.merge(debt.status().name(), 1L, Long::sum);
      if (debt.status() == DebtStatus.DEFAULTED) {
        defaultedCount++;
      }
      if (debt.status() == DebtStatus.DELINQUENT) {
        delinquentCount++;
      }
      households.add(debt.debtor());
    }

    private Map<String, Object> view() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("unitKey", unit.key());
      switch (unit) {
        case DebtUnit.Commodity commodity -> {
          view.put("unitKind", "commodity");
          view.put("commodity", commodity.commodity().value());
          view.put("currency", null);
        }
        case DebtUnit.Money money -> {
          view.put("unitKind", "money");
          view.put("commodity", null);
          view.put("currency", money.currency().value());
        }
      }
      view.put("principal", principal);
      view.put("contractCount", contractCount);
      view.put("defaultedCount", defaultedCount);
      view.put("delinquentCount", delinquentCount);
      view.put("householdCount", households.size());
      view.put("statusCounts", new TreeMap<>(statusCounts));
      return view;
    }
  }

  /** ★ S3：经营者状态（制度状态机读数；字段口径见 {@code OperatorCondition} 类注）。 */
  private static Map<String, Object> operatorConditionView(OperatorCondition condition) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("status", condition.status().name().toLowerCase(java.util.Locale.ROOT));
    view.put("consecutiveUnsoldCycles", condition.consecutiveUnsoldCycles());
    view.put("consecutiveInputShortfallCycles", condition.consecutiveInputShortfallCycles());
    view.put("cashReserveMilli", condition.cashReserveMilli());
    view.put("debtPrincipalMilli", condition.debtPrincipalMilli());
    view.put("debtServiceDueMilli", condition.debtServiceDueMilli());
    view.put("lastCycleRevenueMilli", condition.lastCycleRevenueMilli());
    view.put("lastCycleCostMilli", condition.lastCycleCostMilli());
    view.put("lastCycleNetMilli", condition.lastCycleNetMilli());
    view.put("unsoldStockMilli", condition.unsoldStockMilli());
    view.put("selfUsableStockMilli", condition.selfUsableStockMilli());
    view.put("consecutiveDebtStressCycles", condition.consecutiveDebtStressCycles());
    view.put("consecutiveSuspendedCycles", condition.consecutiveSuspendedCycles());
    view.put("reopens", condition.reopens());
    view.put("lastReason", condition.lastReason());
    // ★★ S3 修复：本周期累计市场证据（每轮市场结束后累加；关账消费后清零）。无市场轮的周期 cycleMarketRounds=0，
    //   读口因此能分清"整周期没开市"与"开了市但没卖出去"。
    view.put("cycleOfferedQty", condition.cycleOfferedQty());
    view.put("cycleFilledQty", condition.cycleFilledQty());
    view.put("cycleUnfilledQty", condition.cycleUnfilledQty());
    view.put("cycleRevenueMilli", condition.cycleRevenueMilli());
    view.put("cycleOutcompetedActors", condition.cycleOutcompetedActors());
    view.put("cycleOutcompetedQty", condition.cycleOutcompetedQty());
    view.put("cycleMarketRounds", condition.cycleMarketRounds());
    view.put("cycleInputShortfallCycles", condition.cycleInputShortfallCycles());
    view.put(
        "cycleEvidenceNote",
        "cycle* 市场证据跨市场轮累计、关账日 advance 消费后清零；cycleInputShortfallCycles 保留最近一次关账的投入不足读数 0/1");
    return view;
  }

  /** ★ S3：一家户的状态读数 + 派生阶层（可观察量见两个领域类的类注）。 */
  private static Map<String, Object> householdConditionView(
      HouseholdCondition condition, String derivedClass) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("householdId", condition.household().value());
    view.put("status", condition.status().name().toLowerCase(java.util.Locale.ROOT));
    view.put("unmetNeedMilliGrain", condition.unmetNeedMilliGrain());
    view.put("unmetNeedMilliCloth", condition.unmetNeedMilliCloth());
    view.put("debtStress", condition.debtStress());
    view.put("laborSoldMilli", condition.laborSoldMilli());
    view.put("laborSelfMilli", condition.laborSelfMilli());
    view.put(
        "rentPaidMilli",
        condition.rentPaidMilli().isPresent() ? condition.rentPaidMilli().getAsLong() : null);
    view.put(
        "wageArrearsMilli",
        condition.wageArrearsMilli().isPresent() ? condition.wageArrearsMilli().getAsLong() : null);
    view.put("derivedClass", derivedClass);
    view.put("stressCycles", condition.stressCycles());
    view.put(
        "grainCoveragePerMille",
        condition.grainCoveragePerMille().isPresent()
            ? condition.grainCoveragePerMille().getAsLong()
            : null);
    view.put(
        "grainCoverageNote",
        "grainCoveragePerMille=库存粮 ÷ cumulativeRationMilli(人口, 本户 cycleDays)，封顶 1000；null=读不到账/算不出分母（不填 0）");
    view.put(
        "statusNote",
        "status=派生（AssetShare owner/operator、laborSource、自用粮覆盖、未满足、债务）；stressCycles 只给当前周期证据 0/1，不冒充历史连续计数");
    return view;
  }

  /**
   * ★ S3：一家户的分类证据（关账日写回之后 {@code rowClass} 与 {@code classifiedAs} 应当逐值相同；还没到关账日/本 revision
   * 尚未写回时，读的人能当场看见"当前档 vs 派生档"的差，而不是被静默抹平）。
   */
  private static Map<String, Object> classificationView(
      HouseholdId household,
      SocialClassId rowClass,
      HouseholdClassRule.Classification classification) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("householdId", household.value());
    view.put("rowClass", rowClass == null ? null : rowClass.value());
    view.put("classifiedAs", classification.stratum().value());
    view.put("matches", rowClass != null && rowClass.equals(classification.stratum()));
    view.put("ownLandMilliMu", classification.ownLandMilliMu());
    view.put("rightQuantity", classification.rightQuantity());
    view.put("laborSoldMilli", classification.laborSoldMilli());
    view.put("laborHiredMilli", classification.laborHiredMilli());
    view.put("netLaborSoldMilli", classification.netLaborSoldMilli());
    view.put("rentEntitled", classification.rentEntitled());
    view.put(
        "rentPaidMilli",
        classification.rentPaidMilli().isPresent()
            ? classification.rentPaidMilli().getAsLong()
            : null);
    view.put("rentPaidComplete", classification.rentPaidComplete());
    view.put("debtPrincipalMilli", classification.debtPrincipalMilli());
    view.put("debtStressPerMille", classification.debtStressPerMille());
    view.put("reason", classification.reason());
    view.put(
        "rentPaidNote",
        classification.rentPaidMilli().isPresent()
            ? "租金实付来自当日 ProductionLedger（关账日的 ledger 即本周期分配）；本户全部租权规则都完整可归属"
            : "当日 ProductionLedger 读不到，或本户租权规则没有全部出现在该账本里（含规则无法唯一归属）⇒ 租金实付为空，不给部分数冒充整周期");
    return view;
  }

  /** ★ S3：本格最近一次关账日的阶层写回审计（只保留本格的 HouseholdId；没有写回时 items 为空数组）。 */
  private static Map<String, Object> classTransitionsView(
      ClassTransitionFeed.Snapshot snapshot, Set<HouseholdId> hexHouseholds) {
    List<Map<String, Object>> items = new ArrayList<>();
    List<ClassTransition> transitions = new ArrayList<>(snapshot.transitions());
    transitions.sort(Comparator.comparing(transition -> transition.household().value()));
    for (ClassTransition transition : transitions) {
      if (!hexHouseholds.contains(transition.household())) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("householdId", transition.household().value());
      item.put("fromClass", transition.fromClass().value());
      item.put("toClass", transition.toClass().value());
      item.put("reason", transition.reason());
      items.add(item);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("day", snapshot.day());
    view.put("items", items);
    view.put(
        "provenance", "关账日 旧结算引擎（R3a 已删除） 写回 ClassRow.view 时投递的进程内审计；day = 最近一次不晚于当前 tick 的关账日");
    view.put(
        "classReasonNote", "reason 字段保留 lastClassReason 语义（见 HouseholdClassRule.Classification）");
    return view;
  }

  /** ★ S3：当日欠款（WageArrears/RentArrears/SubsistenceArrears）的 JSON 形；逐条给 due/paid/owed。 */
  private static Map<String, Object> arrearsView(ProductionLedger ledger) {
    List<Map<String, Object>> items = new ArrayList<>();
    long wageOwed = 0L;
    long rentOwed = 0L;
    long subsistenceOwed = 0L;
    for (ProductionLedger.Arrear arrear : ledger.arrears()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("kind", arrear.kind().name().toLowerCase(java.util.Locale.ROOT));
      item.put("ruleType", arrear.rule().type().name());
      item.put("recipient", arrear.rule().recipient().toString());
      // ★★ E4c：付款人/受款人 actor 与活动身份随读数发出（资本化解析端点的唯一依据；不靠猜）。
      item.put("payer", actorRefView(arrear.payer()));
      item.put("payee", actorRefView(arrear.payee()));
      item.put("activity", arrear.activity().value());
      item.put("commodity", arrear.commodity().map(CommodityId::value).orElse(null));
      item.put("currency", arrear.currency().map(CurrencyId::value).orElse(null));
      item.put("dueAmount", arrear.dueAmount());
      item.put("paidNow", arrear.paidNow());
      item.put("owed", arrear.owed());
      items.add(item);
      switch (arrear.kind()) {
        case WAGE -> wageOwed += arrear.owed();
        case RENT -> rentOwed += arrear.owed();
        case SUBSISTENCE -> subsistenceOwed += arrear.owed();
        case OTHER -> {
          // 其它档不合并进上面三个具名数（不静默混同）。
        }
      }
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("items", items);
    view.put("wageArrearsMilli", wageOwed);
    view.put("rentArrearsMilli", rentOwed);
    view.put("subsistenceArrearsMilli", subsistenceOwed);
    view.put(
        "provenance",
        "来自本日 ProductionLedger 的逐规则读数（进程内瞬态；重启即失）。★ E4c 起 owed 会走资本化落成债权，"
            + "但读数本身仍原样保留（制度规定未付的事实）；是否真的落成债权看同视图的 debtCapitalizations（含 unresolvedItems）");
    return view;
  }

  /**
   * ★★ <b>E4c：本日欠租/欠薪资本化的明细与具名跳过</b>（进程内瞬态；数据来自当日 {@link ProductionLedger}）。
   *
   * <p>★ {@code items} 是合同写口的审计（contractId/unit/amount/principalAfter/termsSource/eventCount）；
   * {@code unresolvedItems} 是端点解析不到家户时<b>不伪造端点</b>的具名记录。资本化<b>不搬任何库存/货币</b>。
   */
  private static Map<String, Object> debtCapitalizationView(ProductionLedger ledger) {
    List<Map<String, Object>> items = new ArrayList<>();
    Map<String, Long> capitalizedByUnit = new TreeMap<>();
    for (ProductionLedger.DebtCapitalization capitalization : ledger.debtCapitalizations()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("contractId", capitalization.contractId().value());
      item.put("payer", actorRefView(capitalization.payer()));
      item.put("payee", actorRefView(capitalization.payee()));
      item.put("activity", capitalization.activity().value());
      item.put("debtor", capitalization.debtor().value());
      item.put("creditor", capitalization.creditor().value());
      item.put("ruleType", capitalization.rule().type().name());
      switch (capitalization.unit()) {
        case DebtUnit.Commodity commodity -> {
          item.put("unitKind", "commodity");
          item.put("commodity", commodity.commodity().value());
          item.put("currency", null);
        }
        case DebtUnit.Money money -> {
          item.put("unitKind", "money");
          item.put("commodity", null);
          item.put("currency", money.currency().value());
        }
      }
      item.put("amount", capitalization.amount());
      item.put("day", capitalization.day());
      item.put("dueCycle", capitalization.dueCycle());
      item.put("terms", capitalization.terms().stableKey());
      item.put("termsSource", capitalization.termsSource());
      item.put("principalAfter", capitalization.principalAfter());
      item.put("eventCount", capitalization.eventCount());
      items.add(item);
      // ★ 按 unit.key() 分组：粮/布/银各自一个键，绝不把它们加成同一个"总价值"。
      capitalizedByUnit.merge(capitalization.unit().key(), capitalization.amount(), Long::sum);
    }
    List<Map<String, Object>> unresolved = new ArrayList<>();
    for (ProductionLedger.UnresolvedDebtCapitalization skipped :
        ledger.unresolvedDebtCapitalizations()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("payer", actorRefView(skipped.payer()));
      item.put("payee", actorRefView(skipped.payee()));
      item.put("activity", skipped.activity().value());
      item.put("ruleType", skipped.rule().type().name());
      item.put("commodity", skipped.commodity().map(CommodityId::value).orElse(null));
      item.put("currency", skipped.currency().map(CurrencyId::value).orElse(null));
      item.put("owed", skipped.owed());
      item.put("reason", skipped.reason());
      unresolved.add(item);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("items", items);
    view.put("unresolvedItems", unresolved);
    // ★ 按 DebtUnit.key() 分组（如 commodity:grain / money:silver）：不同 unit 绝不混加成一个"总价值"。
    view.put("capitalizedByUnit", capitalizedByUnit);
    view.put(
        "note",
        "资本化 = 把制度规定未付（Arrear.owed）落成连续债务合同本金；不移动任何商品/货币库存；"
            + "termsSource=E4c_LEGACY_DEFAULT 表示本阶段统一用 DebtTerms.legacyDefault()；"
            + "unresolvedItems 里的 owed 保持读数、不伪造债务人/债权人端点；capitalizedByUnit 的键是 DebtUnit.key()，"
            + "值单位是该 unit 的最小计量单位（不同键之间不可相加）");
    return view;
  }

  /** ★★ E4c：本日偿还被具名跳过的条目（目前唯一来源 = 无价格源的货币折偿；不硬折）。 */
  private static Map<String, Object> debtRepaymentSkipView(ProductionLedger ledger) {
    List<Map<String, Object>> items = new ArrayList<>();
    for (ProductionLedger.DebtRepaymentSkip skip : ledger.debtRepaymentSkips()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("debtor", skip.debtor().value());
      item.put("contractId", skip.contractId().value());
      switch (skip.unit()) {
        case DebtUnit.Commodity commodity -> {
          item.put("unitKind", "commodity");
          item.put("commodity", commodity.commodity().value());
          item.put("currency", null);
        }
        case DebtUnit.Money money -> {
          item.put("unitKind", "money");
          item.put("commodity", null);
          item.put("currency", money.currency().value());
        }
      }
      item.put("principalOutstanding", skip.principalOutstanding());
      item.put("reason", skip.reason());
      items.add(item);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("items", items);
    return view;
  }

  /** ★ S3：欠款读不到的具名原因（唯一拼写点）。 */
  private static final String ARREARS_PROCESS_ONLY =
      "旧当日 ProductionLedger 已随旧结算运行时删除（R3a）：租与工资欠款明细读不到；缺失不是 0。";

  /** ★ S3：阶层写回审计读不到的具名原因（唯一拼写点）。 */
  private static final String CLASS_TRANSITIONS_PROCESS_ONLY =
      "旧阶层写回审计（ClassTransitionFeed）已无生产写入方（R3a 起旧结算不再投递）：读不到\"从哪一档跳来\"的旧口径";

  /** ★ R4-E2b：候选进入评估结果读不到的具名原因（唯一拼写点）。 */
  private static final String ENTRY_OUTCOMES_PROCESS_ONLY =
      "候选进入评估结果是进程内瞬态（EntryOutcomeFeed；不落盘、不新增 EconomyData 组件）：重启/换进程/还没结算时"
          + "读不到\"哪些户被评估、为什么没进\"；unit 与份额的真值仍在 units[] 与 assetShares[] 两栏";

  /** ★ E1/E5a：某家户没有 ClassStanding 时的具名原因（唯一拼写点；不是 0，也不是伪造一个默认归属）。 */
  private static final String CLASS_STANDING_UNAVAILABLE =
      "该家户没有 ClassStanding 记录（economy.classStandings 为空或未覆盖此户）：E1 起新状态为空时旧路径仍以 "
          + "ClassRow.view 为准；E5a 不产生任何阶层变动，不伪造 current/original/consecutiveDebtStressCycles";

  /** ★★ E5b：清算审计读不到的具名原因（唯一拼写点）。 */
  private static final String LIQUIDATION_AUDIT_PROCESS_ONLY =
      "旧清算/阶层下滑审计（当日 ProductionLedger.liquidationAudits）已随旧结算运行时删除（R3a）：" + "逐条\"处置了什么、跳过了什么\"读不到";

  /** ① 生产自给率报不出来的原因（唯一拼写点：主函数与类注引同一句）。 */
  private static final String PRODUCTION_NEEDS_LEDGER =
      "要读本周期 ledger 的毛产/损耗/投入（含留种与损耗，故不能说\"自给率\"而不交代扣没扣）——"
          + "而 ProductionLedger 是**当日瞬态**（Accumulator 交成 ledger 后由协调器落账即丢），"
          + "状态里没有周期累计；FlowRow.income/consumed 不是毛产/损耗/投入，不许冒充。"
          + "落点：将来把 ledger 的周期累计落成一个持久读数组件";

  /** ⑤ 物流缺口在**没有进程内报告**时的具名原因（唯一拼写点）。 */
  private static final String LOGISTICS_GAP_NO_REPORT =
      "物流缺口要读 L2 的进程内 MarketReport（逐笔未成交原因）：它不落盘、重启/换进程即失，" + "当前读口没有同 tick 的那份报告 ⇒ 这一项读不到（不是 0）";

  /**
   * ⑥ 币种/支付缺口报不出来的原因（唯一拼写点）。
   *
   * <p>★ <b>M2.7 复评（如实记：M1.1 之后措辞更新过一次，这次再收一半）</b>：工具的<b>身份</b>已经落地（见 {@code currencyDefs} / {@code
   * moneyInstruments} 两栏），M2.1 的订单里也有 {@code payWith} / {@code receiveWith}
   * 字段；但<b>接受规则</b>（谁收哪种工具、按什么条件收、多工具如何校验与兑现）仍未实现 —— 本层市场只收 {@code silver-specie} 单一工具（见 {@code
   * MarketSettlement} 的硬编码）⇒ "付得出去吗"照样判不出来。★ 兑现属 M4+。
   */
  private static final String PAYMENT_INSTRUMENT_GAP =
      "货币工具的**身份**已由 M1.1 给出（见本视图 currencyDefs / moneyInstruments 两栏）、订单也带了 payWith/receiveWith 字段，"
          + "但**接受规则**（谁收哪种工具、按什么条件收、多工具校验与兑现）仍未实现 —— 本层市场只收 silver-specie 单一工具"
          + "⇒ 今天不判「付得出去吗」";

  /** ★ M2.7 丙条：{@code cycleNaturalNeedMilli} 的人口快照口径（唯一拼写点）。 */
  private static final String CYCLE_NATURAL_NEED_CLOCK =
      "Σ_d dailyRationMilli(pop_d, d)：pop_d = 第 d 天经济结算前的行人口（= 日初人口）；本周期第一天重置为"
          + "当天那一份，此后逐日累加。它是与「本周期累计未满足需求」同窗口的唯一自然需求分母";

  /** ★ M2.7 丙条：日耗那一栏的窗口（与周期累加器不可并排当同一分母）。 */
  private static final String GRAIN_DAILY_CONSUMPTION_CLOCK =
      "最近一次结算日 d 的当天自然口粮需要（逐日覆盖，不是周期累计、也不是周期均值）；"
          + "人口时点 = 该日结算前的日初人口。不得用它 × 120 与周期量并排比较（丙条：口径不可比）";

  /** 千分率的分母（口粮折算用；与 {@code 旧结算引擎（R3a 已删除）.MILLI_PER_GRAIN} 同值，此处只服务读口）。 */
  private static final long MILLI_PER_GRAIN = EconomyVocabulary.MILLI_PER_COMMODITY_UNIT;

  /**
   * ★★ **某格的产权读口**（H0.6）：把**行侧的家户账**与 **actor 侧的库存账**并排发出来。
   *
   * <pre>
   * {"q":0,"r":0,
   *  "accounts":[{"actor":"HOUSEHOLD:0_0:rural|poor_peasant"…→ 实为 ActorRef.toString()（{@code <KIND>:<id>}）,
   *               "kind":"HOUSEHOLD",
   *               "goods":{"grain":123,"cloth":4},"money":{"silver":12},          // 余额（事实）
   *               "frozenGoods":{"grain":23},"frozenMoney":{"silver":2},          // ★ M1.2 冻结（事实）
   *               "availableGoods":{"grain":100},"availableMoney":{"silver":10}}],// ★ M1.2 可支配（派生）
   *  "actorGoodsTotal":{"grain":123,"cloth":4},   // actor 侧：该格各本 GoodsAccount 的**商品**合计
   *  "actorMoneyTotal":{"silver":12},          // ★ H4：actor 侧：同一批账的**货币**合计（逐币种）
   *  "moneyLayers":{"privateCirculation":{"silver":12},   // ★ M1.6：私人流通 / 全部基础货币 /
   *                 "baseMoney":{"silver":12},           //    （将来）银行存款三栏（纯派生）
   *                 "bankDeposits":{},"unclassifiedCurrencies":[]},
   *  "rowGoodsTotal":{"grain":456,"cloth":0}}     // 行侧：= {@link #economyHex} 里那份 Σ 行库存（结构性的空表）
   * </pre>
   *
   * <p>★★ <b>M1.2：一本账的四个表 + 两栏派生量全在同一处</b>（余额 / 冻结 / 可支配一次读全）—— 冻结额只表达"<b>已明确的占用</b>"
   * （挂单要卖的货、已承诺的交付），<b>不含</b>生活保留 / 必要生产投入 / 经营储备（那些是决策层的策略，落点在 M2）。 ★ <b>读口只读、不重算</b>：{@code
   * available*} 两栏逐键调 {@link AvailableStock#available(GoodsAccount,
   * CommodityId)}（唯一算法），本层<b>没有</b>第二处减法。★ 两张 {@code available*} 的键集 = 余额表 ∪ 冻结表（冻结表里可能有 余额表没有的 0 键
   * —— "缺键 = 0"那条守卫的合法形态）。
   *
   * <p>★★ **为什么两个 total 必须一起给**（这是本视图存在的理由）：行侧的 {@code ClassRow.goods} 与 actor 侧的 {@code
   * GoodsAccount} 是**两本不同性质的账**（前者是"这批人当期可用/持有"的视图，后者是本切片里商品余额的唯一真源），
   * 任何一方被单独读成"全系统有多少"都是一次口径错。并排发出来 ⇒ 读的人当场看得见两者差多少，而不是靠注释提醒。
   *
   * <p>★★ <b>H4：货币在同一个 {@code accounts} 里、同一个 actor 下</b>（{@code money} 与 {@code goods} 并列）—— 裁定 M2
   * 说 {@code CurrencyId} 与 {@code CommodityId} 是**两个独立身份**，而它们**住同一本账**：读口因此
   * 既分得开（两张表、逐币种），又不会让人以为有两本账。★ {@code actorMoneyTotal} 是**逐币种**的 （"跨币种求和"是没有意义的运算）。
   *
   * <p>★ **本轮（H0）家户 actor 还没播种**（H1 的事）⇒ {@code accounts} 是空表、{@code actorGoodsTotal} 全 0 —— 那是
   * **合法且正确**的状态，不是"读口坏了"。★ 排序：{@code accounts} 按 {@code actor} 规范串字典序、两个 total 的商品/币种键字典序 （可复现；照
   * {@link #economyHex} 的口径）。
   *
   * <p>★ {@code actor} 走 {@link ActorRef#toString()}（{@code <KIND>:<id>}）—— 本层**不自己拼 id**（家户 id
   * 的拼法在 {@code HouseholdActors}，H1）。
   */
  public static Map<String, Object> economyOwnership(
      HexCoord coord, EconomyData economy, ActorData actors) {
    Map<String, Object> view = hexCoord(coord);
    List<GoodsAccount> atHex = accountsAt(economy, actors, coord);
    List<Map<String, Object>> accounts = new ArrayList<>(atHex.size());
    Map<String, Long> actorGoodsTotal = new TreeMap<>();
    Map<String, Long> actorMoneyTotal = new TreeMap<>();
    for (GoodsAccount account : atHex) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("actor", HouseholdActors.of(account.key().household()).toString());
      entry.put("kind", HouseholdActors.of(account.key().household()).kind().name());
      entry.put("goods", sortedCommodities(account.balances()));
      // ★★ H4：同一个 actor 的**货币账**（逐币种；缺币种 = 这个家户没有那种钱）。
      entry.put("money", sortedCurrencies(account.money()));
      // ★★ M1.2：**余额 / 冻结 / 可支配三者一次读全**（同一处、同一本账）——
      //   前两栏是**事实**（账户里存的两个表），后两栏是**派生量**，且派生只经唯一那个算法
      //   `AvailableStock.available`（★ 读口**不重算**：这里没有第二处减法）。
      entry.put("frozenGoods", sortedCommodities(account.frozenBalances()));
      entry.put("frozenMoney", sortedCurrencies(account.frozenMoney()));
      entry.put("availableGoods", availableCommodities(account));
      entry.put("availableMoney", availableCurrencies(account));
      accounts.add(entry);
      mergeInto(actorGoodsTotal, account.balances());
      mergeMoneyInto(actorMoneyTotal, account.money());
    }
    // ★★ H1：行侧**没有商品了**（{@code ClassRow} 无 goods，裁定 D3-C/K1）⇒ 这一栏是**结构性的空表**
    //   （不是"读不到"，是"那里已经没有这本账"）。它照旧发出来，正是为了让"一本账"这条判据**并排可见**：
    //   {@code accounts} / {@code actorGoodsTotal} 有数，{@code rowGoodsTotal} 恒空。
    Map<String, Long> rowGoodsTotal = new TreeMap<>();
    view.put("accounts", accounts);
    view.put("actorGoodsTotal", actorGoodsTotal);
    view.put("actorMoneyTotal", actorMoneyTotal);
    // ★★ M1.6：与 {@link #economyHex} **同一份**分栏（同一趟遍历的派生量；两处不许各算一套）。
    view.put("moneyLayers", moneyLayers(actorMoneyTotal));
    // ★★ E3：发行/回笼/流通量与 actor kind / 家户阶层聚合（世界级时点口径；见方法注释）。
    view.put("moneyIssuance", moneyIssuanceView(economy, moneyTotals(actors)));
    Map<String, Map<String, Long>> moneyByKind = moneyByActorKind(actors);
    Map<String, Map<String, Long>> moneyByClass = moneyByHouseholdClass(economy, actors);
    view.put("moneyByActorKind", moneyByKind);
    view.put("moneyByHouseholdClass", moneyByClass);
    // ★★ E4b：本格每户/合计 debtCapacity（与 economyHex 同一份读数与窗口；见 DebtCapacityBlock）。
    //   ★★ E6c：容量与块视图一次算好，dashboard.derived.creditPosition 复用同一份（不重跑 F/headroom 算法）。
    List<HouseholdId> householdKeys = cohortKeysAt(economy, coord);
    DebtCapacityReadout capacityReadout =
        debtCapacityReadout(economy, actors, coord, householdKeys);
    view.put("debtCapacity", capacityReadout.block());
    view.put("rowGoodsTotal", rowGoodsTotal);
    // ★★ E6c：统一 dashboard（本入口没有 SimulationState ⇒ 进程内参考口径具名缺失；其余仍从现值现算）。
    view.put(
        "dashboard",
        economyDashboard(
            coord,
            economy,
            actors,
            moneyByKind,
            moneyByClass,
            householdKeys,
            capacityReadout.capacities(),
            capacityReadout.block(),
            Optional.empty(),
            -1L,
            ECONOMY_OWNERSHIP_NO_STATE));
    return view;
  }

  /**
   * 该格上的全部库存账（**保序**：按 owner 的规范串字典序）—— {@link #economyOwnership} 与 {@link #economyHex}
   * 读的是**同一个集合**（后者的 {@code goods} 就是前者 {@code actorGoodsTotal} 的来源）。
   */
  private static List<GoodsAccount> accountsAt(
      EconomyData data, ActorData actors, HexCoord coord) {
    List<GoodsAccount> atHex = new ArrayList<>();
    for (GoodsAccount account : actors.accounts().values()) {
      if (householdHex(data, account.key().household()).equals(coord)) {
        atHex.add(account);
      }
    }
    atHex.sort(Comparator.comparing(account -> account.key().household().value()));
    return atHex;
  }

  /** 该格的家户**稳定身份**（{@code HouseholdId}），**按 (居住类型, 阶层 id) 字典序**（可复现；见 {@link #economyHex}）。 */
  private static List<HouseholdId> cohortKeysAt(EconomyData data, HexCoord coord) {
    List<HouseholdId> keys = new ArrayList<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : data.classes().entrySet()) {
      if (entry.getValue().view().hex().equals(coord)) {
        keys.add(entry.getKey());
      }
    }
    keys.sort(
        Comparator.comparing(
                (HouseholdId key) -> data.classes().get(key).view().residence().value())
            .thenComparing(key -> data.classes().get(key).view().stratum().value()));
    return keys;
  }

  /**
   * ★★ <b>R3B.2：一个 unit 的读口行</b>（{@code id/industry/operator/modeKey/progressDays/cycleLaborMilli/
   * cycleInputUsedMilli/assets/condition/relation}）。资产走 {@link ProductionUnitBook#usableAssets}
   * 纯派生。
   */
  private static Map<String, Object> productionUnitView(
      ProductionUnit unit,
      Industry industry,
      Map<io.mosire.simos.economy.api.id.AssetShareId, AssetShare> assetShares,
      ProductionRelation relation,
      OperatorCondition condition) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", unit.id().value());
    view.put("industry", unit.industry().value());
    view.put("operator", actorRefView(unit.operator()));
    view.put("modeKey", unit.modeKey());
    view.put("progressDays", unit.progressDays());
    view.put("cycleDays", industry.cycleDays());
    view.put("cycleLaborMilli", unit.cycleLaborMilli());
    view.put("cycleInputUsedMilli", sortedCommodities(unit.cycleInputUsedMilli()));
    Map<String, Object> assets = new TreeMap<>();
    for (Map.Entry<AssetKind, Long> entry :
        ProductionUnitBook.usableAssets(unit, assetShares).entrySet()) {
      assets.put(entry.getKey().name(), entry.getValue());
    }
    view.put("assets", assets);
    view.put("condition", condition == null ? null : operatorConditionView(condition));
    view.put("relation", relation == null ? null : relationView(relation));
    return view;
  }

  /**
   * 一个产业（§3.1 的读侧：制度 / **经营主体** / 周期 / 进度 / **V7 配方** / 分配函数 / 槽位）与该产业的阶层行。
   *
   * <p>★★ **R3（T6）起把配方发出来**（{@code capacityPerUnit} / {@code inputPerUnit} / {@code laborPerUnit} /
   * {@code outputPerUnit} + 本周期实际扣到的投入）：这几项原来**不在任何读口里**，而"每单位**什么**"正是 R3 变成数据的那一维 —— 不读出来，"每座作坊产
   * N 匹布"在报表里就只是数字。★ {@code inputPerUnit} 走 {@link Industry#inputPerUnit()}（={@code
   * cycleInputPerUnit} 的合计），**不在视图层另算一遍**。
   *
   * <p>★★ <b>M1.7：把"实物给养义务"发出来</b>（{@code subsistenceObligations} / {@code subsistencePromised}）——
   * 改前"谁给谁多少给养"只能从规则表 + 劳动账现算，读口里根本不存在；现在它由**契约层的** {@link
   * SubsistenceObligation#of(ProductionRelation, Map)} 纯派生（受方 / 按什么劳动量 / 每周期应付 / 商品），
   * 而"按什么量"用的是**结算侧的同一个** {@code 旧结算引擎（R3a 已删除）.laborOfCohort}（M1.8 的折扣后口径）⇒ 读到的义务与实付的应付**同源**。 ★ 缺
   * relation ⇒ 空表（"没有规则 ⇒ 全归 residualOwner"的等价路径，不是读不到）；{@code subsistencePromised} = 逐商品 Σ 应付，也正是
   * M2 保留算式经 {@link SubsistenceObligation#retentionOf} 封顶时用的"承诺额"。
   *
   * @param relation 该产业的生产关系（{@code EconomyData.relations}；可为 null = 没有规则）
   * @param laborOfCohort 本周期各 cohort 的劳动量（由 {@code 旧结算引擎（R3a 已删除）.laborOfCohort} 算好传入；不得为 null）
   */
  private static Map<String, Object> industryView(
      Industry industry, ProductionRelation relation, Map<HouseholdId, Long> laborOfHousehold) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", industry.id().value());
    view.put("name", industry.name());
    view.put("regime", industry.regime().value());
    // ★★ R3B.2：Industry 只留模板 —— 经营者/进度/产能/累计投入改由调用方从 units 汇总发兼容字段（见 economyHex）。
    view.put("cycleDays", industry.cycleDays());
    Map<String, Object> capacityPerUnit = new TreeMap<>();
    for (Map.Entry<AssetKind, Long> entry : industry.capacityPerUnit().entrySet()) {
      capacityPerUnit.put(entry.getKey().name(), entry.getValue());
    }
    view.put("capacityPerUnit", capacityPerUnit);
    view.put("inputPerUnit", sortedCommodities(industry.inputPerUnit()));
    view.put("laborPerUnit", industry.laborPerUnit());
    view.put("outputPerUnit", sortedCommodities(industry.outputPerUnit()));
    view.put("allocation", allocationView(industry.allocation()));
    List<Map<String, Object>> slots = new ArrayList<>(industry.slots().size());
    for (ClassSlot slot : industry.slots()) {
      Map<String, Object> slotView = new LinkedHashMap<>();
      slotView.put("id", slot.id().value());
      slotView.put("name", slot.name());
      slotView.put("laborParticipationPerMille", slot.laborParticipationPerMille());
      slots.add(slotView);
    }
    view.put("slots", slots);
    // ★★ M1.7：实物给养义务（谁 → 向谁 / 按什么劳动量 / 每周期应付多少 / 什么商品）—— 纯派生、不落状态。
    //   ★ 缺 relation ⇒ 空表（没有规则 = 全归 residualOwner 的等价路径，见方法注释）。
    List<SubsistenceObligation> obligations =
        relation == null ? List.of() : SubsistenceObligation.of(relation, laborOfHousehold);
    List<Map<String, Object>> obligationViews = new ArrayList<>(obligations.size());
    for (SubsistenceObligation obligation : obligations) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("provider", actorRefView(obligation.provider())); // 谁（= 本产业的 operator，自含一份便于逐条核）
      item.put("recipient", recipientView(obligation.recipient())); // 向谁
      item.put("commodity", obligation.commodity().value()); // 给养是什么
      item.put("laborMilli", obligation.laborMilli()); // 按什么量（本周期劳动量）
      item.put("perLaborMilli", obligation.perLaborMilli()); // 每 1000 千分劳动给多少
      item.put("dueAmount", obligation.dueAmount()); // 本周期应付
      item.put("rulePriority", obligation.rule().priority()); // 来源规则在付款次序里的位置（可回查）
      obligationViews.add(item);
    }
    view.put("subsistenceObligations", obligationViews);
    view.put(
        "subsistencePromised",
        sortedCommodities(SubsistenceObligation.promisedByCommodity(obligations)));
    return view;
  }

  /**
   * ★★ <b>R3B.2：一条生产关系的读口形状</b>（activity/operator/inputSupplier/residualOwner/laborSource） ——
   * 规则的逐条明细不在这里（读口用 {@code subsistenceObligations} 与市场读数回答"谁拿多少"）。
   */
  private static Map<String, Object> relationView(ProductionRelation relation) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("activity", relation.activity().value());
    view.put("operator", actorRefView(relation.operator()));
    view.put("inputSupplier", recipientView(relation.inputSupplier()));
    view.put("residualOwner", actorRefView(relation.residualOwner()));
    view.put("laborSource", relation.laborSource().name());
    return view;
  }

  /**
   * 一个 {@link Recipient} 的读口形状（M1.7 给养义务用；两档恰其一）。
   *
   * <p>★ {@code ToActor} → {@code {kind:"actor", actor:{kind,id}}}；{@code ToCohort} → {@code
   * {kind:"cohort", cohort:"<q>_<r>|<residence>|<stratum>"}} —— cohort 用契约自带的规范串（{@link
   * CohortKey#toString()}），视图层不另拼一套。★ 保序 {@code LinkedHashMap} ⇒ 同状态两次响应逐字节相同。
   */
  private static Map<String, Object> recipientView(Recipient recipient) {
    Map<String, Object> view = new LinkedHashMap<>();
    switch (recipient) {
      case Recipient.ToActor toActor -> {
        view.put("kind", "actor");
        view.put("actor", actorRefView(toActor.actor()));
      }
      case Recipient.ToHousehold toHousehold -> {
        view.put("kind", "household");
        view.put("household", toHousehold.household().value());
      }
      // ★ 旧档变体（S1 迁移前）：仍按旧视图规范串发出来（读口兼容；S3 再解释为视图选择器）。
      case Recipient.ToCohort toCohort -> {
        view.put("kind", "cohort");
        view.put("cohort", toCohort.cohort().toString());
      }
    }
    return view;
  }

  /**
   * 人均劳动（M1.8 读口派生量，单位 = 千分劳动/人）：{@code laborMilli ÷ population}。
   *
   * <p>★ <b>不再乘 1000</b>：{@code laborMilli} 本身就是"千分劳动"（一个人满劳动 = 1000）⇒ 它除以人口得到的就已经是 千分数（真档四阶层 =
   * 562.0‰ 的那条读数就是它）；再乘 1000 会变成"每百万人"的假单位。人口为 0 ⇒ 0（不做除零、不臆造）。 ★ 整数除法逐行向下取整（读口不发明小数精度）。
   */
  private static long perCapitaLaborMilli(long laborMilli, long population) {
    return population == 0L ? 0L : laborMilli / population;
  }

  /** 制度分配函数（§5 的两种形状逐字段；不含任何"算出来的"结果——那是 R4 的活）。 */
  private static Map<String, Object> allocationView(AllocationRule rule) {
    Map<String, Object> view = new LinkedHashMap<>();
    if (rule instanceof AllocationRule.Split split) {
      view.put("kind", "split");
      view.put("meansWeightPerMille", split.meansWeightPerMille());
      view.put("laborWeightPerMille", split.laborWeightPerMille());
      return view;
    }
    AllocationRule.WageFirst wage = (AllocationRule.WageFirst) rule;
    view.put("kind", "wage_first");
    view.put("wagePerLaborMilli", wage.wagePerLaborMilli());
    Map<String, Long> residual = new TreeMap<>();
    for (Map.Entry<CommodityId, Long> entry : wage.ownerResidual().entrySet()) {
      residual.put(entry.getKey().value(), entry.getValue());
    }
    view.put("ownerResidual", residual);
    return view;
  }

  /**
   * 一个家户行（§3.2 逐字段：**居住类型** / 人口 / 有效劳动 / 投入率 / 库存 / 货币 / **债权债务** / 两类需求）；{@code flow} =
   * 本期流水（R3a，可为 null）。
   *
   * <p>★ **没有"土地"这一项**：H0.3（K3）把生产资料搬到 {@code Industry.capacity} ⇒ 行侧只报"这本账有多少商品/多少钱/欠谁"。
   *
   * <p>★★ <b>M1.5 起债务双向可查</b>：{@code debts}（我欠谁，保持旧形状 = id 字符串数组）、{@code credits}
   * （谁欠我，新增）两个方向并列；{@code debtDetails} 给每一条债的**明细**（含此前零读口的 {@code dueCycle}）， 一条债在两个方向上读到的本金 / 利率
   * / 到期周期逐值相同（它们回的是同一条 {@link DebtContract} 记录）。
   *
   * <p>★★ <b>M1.8：劳动口径可逐值核对</b>：{@code laborMilli}（未折算的每日毛劳动）+ {@code participationPerMille} 旁边发
   * {@code participationAdjustedLaborMilli}（{@link ClassRow#participationAdjustedLaborMilli()}
   * 的**唯一算法**）与两个"人均" 读数（千分/人）—— 改前四阶层 {@code labor/pop} 全部相同（真档 562.0‰）；改后参与率 950‰ 的贫农与 100‰
   * 的地主的人均有效劳动相差 **9.5 倍**（如 534.0‰ vs 56.2‰）。 ★ 两个"人均"都是本层派生量（行里不存第二份），分母为 0 ⇒ 0（不做除零、也不臆造）。
   *
   * @param credits 该行的债权人侧 {@link DebtContractId}（由 {@link DebtIndex#byCreditor} 一次派生、整格复用；可为空表）
   * @param debtBook 该切片的债务表（{@code EconomyData.debtContracts()}；只读，不在本层改）
   */
  private static Map<String, Object> classRowView(
      HouseholdId key,
      ClassRow row,
      FlowRow flow,
      ActorData actors,
      List<DebtContractId> credits,
      Map<DebtContractId, DebtContract> debtBook) {
    Map<String, Object> view = new LinkedHashMap<>();
    // ★★ H0.2：**居住类型随行一起发出来**（农村贫农与城镇贫农是两本账，读口必须分得开）；
    //   ★ 字面量取自契约的 {@code ResidenceKind#value()} 的产物（{@code key.toString()} 的那一段），本层不写第二份词表。
    view.put("residence", row.view().residence().value());
    view.put("slot", row.view().stratum().value());
    view.put("population", row.population());
    view.put("laborMilli", row.laborMilli());
    view.put("participationPerMille", row.participationPerMille());
    // ★★ M1.8：按阶层参与率折扣后的可用劳动（唯一算法在 ClassRow）+ 两个人均读数 —— 四档差别在报表里可逐值核对。
    view.put("participationAdjustedLaborMilli", row.participationAdjustedLaborMilli());
    view.put("laborPerCapitaPerMille", perCapitaLaborMilli(row.laborMilli(), row.population()));
    view.put(
        "participationAdjustedLaborPerCapitaPerMille",
        perCapitaLaborMilli(row.participationAdjustedLaborMilli(), row.population()));
    // ★★ H1：这个家户的商品余额**只在 actor 侧的账本上**（{@code GoodsAccount}，键 =
    //   {@code (HouseholdActors.of(key), key.hex())}）—— 行里没有 goods 这一栏。★ 键的拼法只经
    //   {@link #accountKeyOf(HouseholdId, HexCoord)}（本层不复述家户 id / 账户键的形状）；账本缺席 ⇒ 空表（读口不抛）。
    GoodsAccount account = actors.accounts().get(accountKeyOf(key));
    view.put("goods", sortedCommodities(account == null ? Map.of() : account.balances()));
    // ★★ H4：这个家户的**货币账**（actor 侧；与 {@code goods} 同住一本 {@code GoodsAccount}）——
    //   与下面那个行侧恒 0 的 {@code money} 并排（同 goods 与 rowGoodsTotal 的处置：真值在 actor 侧）。
    view.put("actorMoney", sortedCurrencies(account == null ? Map.of() : account.money()));
    view.put("money", row.money());
    // 债务人方向：旧形状保持不变（id 字符串数组），另在 debtDetails 里补明细。
    List<String> debts = new ArrayList<>(row.debts().size());
    for (DebtContractId debt : row.debts()) {
      debts.add(debt.value());
    }
    view.put("debts", debts);
    // ★★ M1.5：债权人方向（此前完全读不到）——"谁欠我"。
    List<String> creditsView = new ArrayList<>(credits.size());
    for (DebtContractId credit : credits) {
      creditsView.add(credit.value());
    }
    view.put("credits", creditsView);
    // ★★ M1.5：同一批债务的明细（两个方向同源；dueCycle 由此接入读口，它此前零 reader）。
    List<Map<String, Object>> debtDetails = new ArrayList<>(row.debts().size() + credits.size());
    for (DebtContractId debtId : row.debts()) {
      DebtContract debt = debtBook.get(debtId);
      if (debt != null) {
        debtDetails.add(debtDetailView(debt, false));
      }
    }
    for (DebtContractId debtId : credits) {
      DebtContract debt = debtBook.get(debtId);
      if (debt != null) {
        debtDetails.add(debtDetailView(debt, true));
      }
    }
    view.put("debtDetails", debtDetails);
    view.put("naturalNeeds", sortedCommodities(row.naturalNeeds()));
    // ★★ M2.7 丙条：本行**本周期累计自然口粮需要**（毫粮；与自然需求并排，窗口标注见 economyHex 的 clock 两栏）。
    view.put("cycleNaturalNeedMilli", row.cycleNaturalNeedMilli());
    view.put("effectiveDemand", sortedCommodities(row.effectiveDemand()));
    view.put("flow", flowView(flow));
    return view;
  }

  /** ★★ E4b：粮库存读不到时 headroom/可质押余粮的具名缺失（唯一拼写点；绝不填 0 冒充）。 */
  private static final String DEBT_CAPACITY_STOCK_UNREADABLE =
      "该家户在本格 ActorData 里没有 GoodsAccount（粮库存读不到）⇒ 可质押余粮/可质押真实资产价值/headroom"
          + "记 null；缺失不是 0，也不拿别的账本顶替";

  /** ★★ E4b：三个流量与库存的窗口标注（挂在 debtCapacity 块上；逐字段口径与 {@link DebtCapacity} 类注同源）。 */
  private static Map<String, Object> debtCapacityWindowView() {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("afterAllocationGrainIncome", "本周期已实现（FlowRow.income[grain]；逐日累加，新周期第一天清零；没有 = 0）");
    view.put("basicRation", "本周期累计（ClassRow.cycleNaturalNeedMilli；逐日按日初人口累加，新周期第一天重置为当天那一份）");
    view.put(
        "nextRoundNecessaryInput",
        "下一周期配方口径（读口时点的 unit/资产/状态）；nextRoundNecessaryInputSource=NON_RATION_CONSUMED_PROXY 时"
            + "是本周期实际非口粮投入的代理，不是真实下一轮投入");
    view.put("taxPaid", "本周期已缴（FlowRow.taxPaid；当前生产路径恒 0，照实读）");
    view.put("pledgeableGrainSurplusValue", "时点：max(0, 粮库存 − 本周期自需)，与放贷方余粮同一算式、同一保留额；库存读不到 ⇒ null");
    view.put(
        "pledgeableAssetPolicyValue", "时点：E4b 的显式钩子恒 0（E5 的 LiquidationPolicy/价格源未落地）；单独列出，不静默省略");
    view.put("existingDebt", "时点：该家户名下同 unit（粮）的债务本金合计；只减这一部分");
    view.put("unpricedDebtAmount", "时点：非粮 unit 且不能折算的债务本金原始和（各债各自计量单位；仅审计，不参与 headroom）");
    view.put("headroom", "时点：max(0, κ×F÷1000 + 可质押真实资产价值 − existingDebt)");
    return view;
  }

  /** ★★ E4b：一个家户的 debtCapacity 读数（F 四项、headroom、既有债、unpriced 部分）。 */
  private static Map<String, Object> debtCapacityView(HouseholdId key, DebtCapacity capacity) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("household", key.value());
    if (capacity == null) {
      view.put("unavailable", "该家户在 EconomyData.classes 里没有行（状态不完整）⇒ F/headroom 一律不计算，不填 0");
      return view;
    }
    view.put("afterAllocationGrainIncome", capacity.afterAllocationGrainIncome());
    view.put("basicRation", capacity.basicRation());
    view.put("nextRoundNecessaryInput", capacity.nextRoundNecessaryInput());
    view.put("nextRoundNecessaryInputSource", capacity.nextRoundNecessaryInputSource().name());
    view.put("nextRoundNecessaryInputIsProxy", capacity.nextRoundNecessaryInputIsProxy());
    view.put("taxPaid", capacity.taxPaid());
    view.put("F", capacity.F());
    // ★ 空 = 库存读不到（具名缺失）；≠ 0（0 是一个真实结论：库存够吃但一点余粮都没有）。
    boolean stockReadable = capacity.pledgeableGrainSurplusValue().isPresent();
    view.put("grainStockUnavailable", stockReadable ? null : DEBT_CAPACITY_STOCK_UNREADABLE);
    view.put(
        "pledgeableGrainSurplusValue",
        stockReadable ? capacity.pledgeableGrainSurplusValue().getAsLong() : null);
    view.put("pledgeableAssetPolicyValue", capacity.pledgeableAssetPolicyValue());
    view.put(
        "pledgeableRealAssetValue",
        capacity.pledgeableRealAssetValue().isPresent()
            ? capacity.pledgeableRealAssetValue().getAsLong()
            : null);
    view.put("existingDebt", capacity.existingDebt());
    view.put("unpricedDebtAmount", capacity.unpricedDebtAmount());
    view.put("unpricedDebtCount", capacity.unpricedDebtCount());
    view.put("unpricedDebtNote", "非粮 unit 不能折算的那部分本金只在这里列出：不硬折、不进 existingDebt、不参与 headroom");
    view.put("headroom", capacity.headroom().isPresent() ? capacity.headroom().getAsLong() : null);
    view.put("unit", capacity.unitNote());
    return view;
  }

  /**
   * ★★ E4b：一格（本格家户）的 debtCapacity 块 —— 逐户读数 + 已知行合计 + 读不到的行数。
   *
   * <p>★ 合计只累加"读得到"的行；{@code headroomUnavailableHouseholds} 与具名原因并排发出来，读的人不会把 "有几行没算"漏成 0。缺行（{@code
   * classes} 里没有）同样计入 unavailable。
   */
  private static Map<String, Object> debtCapacityBlockView(
      List<HouseholdId> householdKeys, Map<HouseholdId, DebtCapacity> capacities) {
    List<Map<String, Object>> households = new ArrayList<>(householdKeys.size());
    long afterAllocationGrainIncome = 0L;
    long basicRation = 0L;
    long nextRoundNecessaryInput = 0L;
    long taxPaid = 0L;
    long totalF = 0L;
    long pledgeableGrainSurplusValue = 0L;
    long pledgeableAssetPolicyValue = 0L;
    long pledgeableRealAssetValue = 0L;
    long existingDebt = 0L;
    long unpricedDebtAmount = 0L;
    long unpricedDebtCount = 0L;
    long headroom = 0L;
    int unavailable = 0;
    for (HouseholdId key : householdKeys) {
      DebtCapacity capacity = capacities.get(key);
      households.add(debtCapacityView(key, capacity));
      if (capacity == null) {
        unavailable++;
        continue;
      }
      afterAllocationGrainIncome += capacity.afterAllocationGrainIncome();
      basicRation += capacity.basicRation();
      nextRoundNecessaryInput += capacity.nextRoundNecessaryInput();
      taxPaid += capacity.taxPaid();
      totalF += capacity.F();
      if (capacity.pledgeableGrainSurplusValue().isPresent()) {
        pledgeableGrainSurplusValue += capacity.pledgeableGrainSurplusValue().getAsLong();
      }
      pledgeableAssetPolicyValue += capacity.pledgeableAssetPolicyValue();
      if (capacity.pledgeableRealAssetValue().isPresent()) {
        pledgeableRealAssetValue += capacity.pledgeableRealAssetValue().getAsLong();
      }
      existingDebt += capacity.existingDebt();
      unpricedDebtAmount += capacity.unpricedDebtAmount();
      unpricedDebtCount += capacity.unpricedDebtCount();
      if (capacity.headroom().isPresent()) {
        headroom += capacity.headroom().getAsLong();
      } else {
        unavailable++;
      }
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("unit", DebtCapacity.UNIT_NOTE);
    view.put("window", debtCapacityWindowView());
    view.put("households", households);
    Map<String, Object> total = new LinkedHashMap<>();
    total.put("householdCount", householdKeys.size());
    total.put("afterAllocationGrainIncome", afterAllocationGrainIncome);
    total.put("basicRation", basicRation);
    total.put("nextRoundNecessaryInput", nextRoundNecessaryInput);
    total.put("taxPaid", taxPaid);
    total.put("F", totalF);
    total.put("pledgeableGrainSurplusValue", pledgeableGrainSurplusValue);
    total.put("pledgeableAssetPolicyValue", pledgeableAssetPolicyValue);
    total.put("pledgeableRealAssetValue", pledgeableRealAssetValue);
    total.put("existingDebt", existingDebt);
    total.put("unpricedDebtAmount", unpricedDebtAmount);
    total.put("unpricedDebtCount", unpricedDebtCount);
    total.put("headroom", headroom);
    total.put("headroomUnavailableHouseholds", unavailable);
    total.put("headroomUnavailableNote", unavailable == 0 ? null : DEBT_CAPACITY_STOCK_UNREADABLE);
    total.put("totalsPartial", unavailable > 0);
    total.put(
        "totalsPartialNote",
        unavailable == 0
            ? null
            : "pledgeableGrainSurplusValue/pledgeableRealAssetValue/headroom 三项合计只含库存读得到的行；"
                + "F 与四个输入项仍是全量合计");
    view.put("total", total);
    return view;
  }

  /**
   * ★★ E4b/E6c：{@link #economyOwnership} 的 debtCapacity 一次性读数（容量 + 块视图）。
   *
   * <p>与 {@link #economyHex} 同一份逐户读数与窗口标注；E6c 的 dashboard 复用同一份 capacities， 不在这里重跑 E4b 的 F/headroom
   * 公式。
   */
  private record DebtCapacityReadout(
      Map<HouseholdId, DebtCapacity> capacities, Map<String, Object> block) {}

  private static DebtCapacityReadout debtCapacityReadout(
      EconomyData data, ActorData actors, HexCoord coord, List<HouseholdId> householdKeys) {
    Map<ActorRef, HouseholdId> householdOfActorAtHex = new LinkedHashMap<>();
    for (HouseholdId key : householdKeys) {
      householdOfActorAtHex.put(HouseholdActors.of(key), key);
    }
    Map<HouseholdId, Long> grainStockByHousehold = new LinkedHashMap<>();
    for (GoodsAccount account : accountsAt(data, actors, coord)) {
      HouseholdId key = householdOfActorAtHex.get(HouseholdActors.of(account.key().household()));
      if (key == null) {
        continue;
      }
      grainStockByHousehold.merge(key, account.balances().getOrDefault(GRAIN, 0L), Long::sum);
    }
    Map<HouseholdId, DebtCapacity> capacities =
        DebtCapacityBook.capacitiesForState(
            data,
            householdKeys,
            key ->
                grainStockByHousehold.containsKey(key)
                    ? OptionalLong.of(grainStockByHousehold.get(key))
                    : OptionalLong.empty());
    return new DebtCapacityReadout(capacities, debtCapacityBlockView(householdKeys, capacities));
  }

  /**
   * ★★ <b>一条债的双向明细</b>（M1.5）：两个方向读的是<b>同一条</b> {@link DebtContract} 记录 ⇒ {@code principal} / {@code
   * ratePerMillePerCycle} / {@code dueCycle} / {@code defaulted} <b>逐值相同</b>，不同的只有 {@code
   * direction} 与 {@code counterparty}。
   *
   * <p>★ {@code commodity = null} ⇒ 货币债（沿用 {@code Optional.empty()} 的既有口径，不是"读不到"）。 ★ {@code
   * principal} 已含周期末并入的利息（{@code chargeInterest} 只增本金、不自动增可花余额 —— 债权人侧的"应收"<b>不进</b> {@code
   * FlowRow.income}，那是粮口径；见 {@code chargeInterest} 的类注）。
   */
  private static Map<String, Object> debtDetailView(DebtContract debt, boolean creditorSide) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", debt.id().value());
    view.put("direction", creditorSide ? "receivable" : "payable");
    view.put("counterparty", (creditorSide ? debt.debtor() : debt.creditor()).toString());
    // ★ 旧键形状保持：实物债发商品名、货币债发 null（不是“读不到”）；另补 unitKind/currency 两栏把显式 unit 发出来。
    switch (debt.unit()) {
      case DebtUnit.Commodity commodity -> {
        view.put("commodity", commodity.commodity().value());
        view.put("unitKind", "commodity");
        view.put("currency", null);
      }
      case DebtUnit.Money money -> {
        view.put("commodity", null);
        view.put("unitKind", "money");
        view.put("currency", money.currency().value());
      }
    }
    view.put("unitKey", debt.unit().key());
    view.put("principal", debt.principal());
    view.put("ratePerMillePerCycle", debt.terms().interestRatePerMillePerCycle());
    view.put("dueCycle", debt.dueCycle().isPresent() ? debt.dueCycle().getAsLong() : null);
    view.put("defaulted", debt.defaulted());
    view.put("status", debt.status().name());
    view.put("openedDay", debt.openedDay());
    view.put(
        "lastInterestDay",
        debt.lastInterestDay().isPresent() ? debt.lastInterestDay().getAsLong() : null);
    // ★ 旧键形状保持 = 规范串；另发 termsDetail 把每个条款维展开（读的人不用解析规范串）。
    view.put("terms", debt.terms().stableKey());
    view.put("termsDetail", termsDetailView(debt));
    return view;
  }

  /** ★★ E4c：债务条款的逐维读口（单位与 E4c 资本化读数的 {@code termsSource} 并排可核）。 */
  private static Map<String, Object> termsDetailView(DebtContract debt) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("stableKey", debt.terms().stableKey());
    view.put("interestRatePerMillePerCycle", debt.terms().interestRatePerMillePerCycle());
    view.put("interestTiming", debt.terms().interestTiming().name());
    view.put("repaymentRule", debt.terms().repaymentRule().name());
    view.put("monetaryConversion", debt.terms().monetaryConversion().name());
    view.put("defaultRemedy", debt.terms().defaultRemedy().name());
    view.put(
        "termsDueCycle",
        debt.terms().dueCycle().isPresent() ? debt.terms().dueCycle().getAsLong() : null);
    view.put(
        "termsDueDay",
        debt.terms().dueDay().isPresent() ? debt.terms().dueDay().getAsLong() : null);
    return view;
  }

  /**
   * 本期流水（§3.3 表 3）的读侧形：所得 / 消费 / 新借 / 偿还 / 净盈余 / **未满足需求 / 饿死**（税与利息 v1 恒 0）。{@code flow} 为 null ⇒
   * 全 0（该行本期无事）。
   *
   * <p>★★ **V4 起 {@code deaths} 在默认路径上恒 0**：致死率默认 {@code 旧结算引擎（R3a 已删除）.FAMINE_MORTALITY_PER_MILLE
   * = 0‰}（"先不做饿死人系统"）⇒ **缺口照记**（{@code unmetNeed} 非 0 是常态），但**不死人**。字段照发不删（旋钮还在）； 读到的 0
   * 是**结论**，不是"没在记"。 ★ 它由端到端用例钉住："缺粮 ⇒ 读口读到非 0 的 {@code unmetNeed}、且 {@code deaths == 0}、人口一个不少"
   * （{@code 旧结算端到端测试（已随旧路径删除）}）。
   *
   * <p>★ **本期口径**（§八.5）：各字段是**本周期**的发生额，新周期第一天归零 ⇒ 关账日读到的是整周期的量（含那次收获）。
   */
  private static Map<String, Object> flowView(FlowRow flow) {
    Map<String, Object> view = new LinkedHashMap<>();
    // ★★ **R3：{@code income} 由标量改成逐商品的表**（田里同时出粮与纤维 ⇒ "一条标量"表达不了"所得是什么"）。
    //   形状与 {@code consumed} 对称；键序都走 {@link #sortedCommodities}（字典序，可复现）。
    view.put("income", flow == null ? Map.of() : sortedCommodities(flow.income()));
    view.put("consumed", flow == null ? Map.of() : sortedCommodities(flow.consumed()));
    view.put("taxPaid", flow == null ? 0L : flow.taxPaid());
    view.put("interestDue", flow == null ? 0L : flow.interestDue());
    view.put("newBorrowing", flow == null ? 0L : flow.newBorrowing());
    // ★★ E4c：{@code repaid} 仍是**粮债**口径；货币债偿还另发逐币种表（不把钱塞进粮标量冒充粮）。
    view.put("repaid", flow == null ? 0L : flow.repaid());
    view.put("repaidMoney", flow == null ? Map.of() : sortedCurrencies(flow.repaidMoney()));
    // ★★ E4c：本周期资本化的欠租/欠薪（按 DebtUnit.key() 分组；资本化只记债权，不搬粮/钱）。
    view.put(
        "capitalizedArrears", flow == null ? Map.of() : new TreeMap<>(flow.capitalizedArrears()));
    // ★ 两个新读数的窗口/单位写清楚（与 FlowRow 类注同源；读的人不用回代码猜）。
    view.put(
        "repaidMoneyWindow", "最小币值；本周期累计（新周期第一天归零）；只记货币债本金偿还（粮债在 repaid，其它商品债只从合同 principal 下降读）");
    view.put(
        "capitalizedArrearsWindow",
        "值单位 = 各 DebtUnit.key() 对应的最小计量单位；本周期累计（新周期第一天归零）；资本化只把制度未付落成债权，不移动库存/货币");
    view.put("netSurplus", flow == null ? 0L : flow.netSurplus());
    // ★★ **R4：{@code unmetNeed} 也逐商品**（spec §七："粮食不足与衣物不足对死亡的时间尺度显然不能一样"）
    //   ⇒ 读口必须把两种缺口**各自发出来**（加成一个数就再也分不开了）。键序同样走 sortedCommodities。
    view.put("unmetNeed", flow == null ? Map.of() : sortedCommodities(flow.unmetNeed()));
    view.put("deaths", flow == null ? 0L : flow.deaths());
    view.put("births", flow == null ? 0L : flow.births()); // ★ R4：与 deaths 对称的那一项
    return view;
  }

  /** 商品表按键字典序（可复现）。 */
  private static Map<String, Long> sortedCommodities(Map<CommodityId, Long> source) {
    Map<String, Long> out = new TreeMap<>();
    for (Map.Entry<CommodityId, Long> entry : source.entrySet()) {
      out.put(entry.getKey().value(), entry.getValue());
    }
    return out;
  }

  /** 把一个商品表并入目标（键字典序由 {@link TreeMap} 保证）。 */
  private static void mergeInto(Map<String, Long> target, Map<CommodityId, Long> source) {
    for (Map.Entry<CommodityId, Long> entry : source.entrySet()) {
      target.merge(entry.getKey().value(), entry.getValue(), Long::sum);
    }
  }

  /**
   * 货币表按**币种名**字典序（可复现）—— ★ 与 {@link #sortedCommodities} 同一个形制，但**键的类型不同**： {@code CurrencyId} 与
   * {@code CommodityId} 是两个独立身份（裁定 M2）⇒ 两张表**不合并**（"银"作为货币与作为商品 是两笔账）。
   */
  private static Map<String, Long> sortedCurrencies(Map<CurrencyId, Long> source) {
    Map<String, Long> out = new TreeMap<>();
    for (Map.Entry<CurrencyId, Long> entry : source.entrySet()) {
      out.put(entry.getKey().value(), entry.getValue());
    }
    return out;
  }

  /** 把一个货币表并入目标（**逐币种**：跨币种求和无意义 ⇒ 键是币种名，绝不加总成一个数）。 */
  private static void mergeMoneyInto(Map<String, Long> target, Map<CurrencyId, Long> source) {
    for (Map.Entry<CurrencyId, Long> entry : source.entrySet()) {
      target.merge(entry.getKey().value(), entry.getValue(), Long::sum);
    }
  }

  /**
   * ★★ <b>M1.6：货币的逐工具分栏</b>（私人流通 / 全部基础货币 /（将来）银行存款）—— <b>纯派生、零新状态组件</b>。
   *
   * <pre>
   * privateCirculation  这一格里**全部已落账的 actor 账本余额**（逐币种）—— 账户事实，不是发行量
   * baseMoney           币种的已登记工具档**全部**落在 {SPECIE, STATE_NOTE} 的那部分持有量
   * bankDeposits        币种的工具档含 BANK_DEPOSIT 的那部分持有量（今天没有这一档 ⇒ 空表）
   * unclassifiedCurrencies  账上有、但分不进上面任何一栏的币种（具名列出来，绝不静默塞进某一栏）
   * </pre>
   *
   * <p>★★ <b>为什么必须明写"没有全世界总量永远不变这条总不变量"</b>（master plan M1.6 的否定判据）： 可成立的是<b>逐工具</b>的 {@code
   * Σ该工具的持有账户 = 创世 + 累计发行 − 累计注销}；发行/注销会让总量变。 {@code MoneyIssuance.REGISTERED} 为空、发行记录为空 ⇒
   * 数值上退化成"逐币种 Σ持有恒定"。★ E3 起累计发行/回笼的权威记录在 {@code EconomyData.moneyIssuances}， {@link
   * #moneyIssuanceView(EconomyData, Map)} 只读它求和，本层不另存一份。
   *
   * <p>★ <b>为什么按币种而不是按工具分</b>：账户余额的键是 {@code CurrencyId}（M1.1 明文不动它）⇒ 同一币种登记了
   * 多种工具时，账户层<b>分不出</b>"这张钱是哪种工具"。本栏不假装能分：既含基础档又含存款档的币种落进 {@code
   * unclassifiedCurrencies}，让读的人看见"这里读不出来"，而不是看到一个编出来的 0 或半数。 今天 {@code silver} 只有 {@code SPECIE}
   * 一种工具 ⇒ {@code baseMoney} 与 {@code privateCirculation} 逐值相同。
   */
  private static Map<String, Object> moneyLayers(Map<String, Long> actorMoneyTotal) {
    Map<String, Set<InstrumentKind>> kindsByCurrency = new LinkedHashMap<>();
    for (MoneyInstrument instrument : MoneyVocabulary.allInstruments()) {
      kindsByCurrency
          .computeIfAbsent(instrument.currency().value(), ignored -> new LinkedHashSet<>())
          .add(instrument.kind());
    }
    Map<String, Long> privateCirculation = new TreeMap<>(actorMoneyTotal);
    Map<String, Long> baseMoney = new TreeMap<>();
    Map<String, Long> bankDeposits = new TreeMap<>();
    List<String> unclassified = new ArrayList<>();
    for (Map.Entry<String, Long> entry : privateCirculation.entrySet()) {
      Set<InstrumentKind> kinds = kindsByCurrency.get(entry.getKey());
      if (kinds == null || kinds.isEmpty()) {
        unclassified.add(entry.getKey()); // 词表里没有任何工具认领这个币种 ⇒ 分不进任何一栏
        continue;
      }
      boolean deposit = kinds.contains(InstrumentKind.BANK_DEPOSIT);
      boolean base =
          kinds.contains(InstrumentKind.SPECIE) || kinds.contains(InstrumentKind.STATE_NOTE);
      if (deposit && base) {
        unclassified.add(entry.getKey()); // 同一币种混两档：账户按币种记账，读不出各占多少
      } else if (deposit) {
        bankDeposits.put(entry.getKey(), entry.getValue());
      } else {
        baseMoney.put(entry.getKey(), entry.getValue());
      }
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("privateCirculation", privateCirculation);
    view.put("baseMoney", baseMoney);
    view.put("bankDeposits", bankDeposits);
    view.put("unclassifiedCurrencies", unclassified);
    return view;
  }

  /** ★★ E3：全部 actor 账本的货币合计（逐币种时点；世界级，不分局）。 */
  private static Map<String, Long> moneyTotals(ActorData actors) {
    Map<String, Long> totals = new TreeMap<>();
    for (GoodsAccount account : actors.accounts().values()) {
      mergeMoneyInto(totals, account.money());
    }
    return totals;
  }

  /**
   * ★★ <b>E3：货币发行/回笼/流通量的只读视图</b>（时点 = 当前 revision；窗口 = activatedDay 至当前，累计）。
   *
   * <pre>
   * initialEndowment       Σ kind=INITIAL_ENDOWMENT 的 amount（逐币种，累计）
   * fiscalIssue            Σ kind=FISCAL_ISSUE 的 amount（逐币种，累计）
   * cumulativeIssuance     = initialEndowment + fiscalIssue
   * cumulativeWithdrawal   Σ kind=WITHDRAWAL 的 amount（逐币种，累计）
   * circulation            Σ 全部 actor GoodsAccount.money 余额（逐币种时点）
   * netIssuance            = cumulativeIssuance − cumulativeWithdrawal（逐币种）
   * </pre>
   *
   * <p>★ 记录来自 {@code EconomyData.moneyIssuances}（可持久/回放），本层只求和、不伪造缺失字段；发行腿的实时差额由 {@code
   * FISCAL_ISSUE} 记录，创世钱包由 {@code INITIAL_ENDOWMENT} 记录。
   */
  private static Map<String, Object> moneyIssuanceView(
      EconomyData data, Map<String, Long> circulation) {
    Map<String, Long> initial = new TreeMap<>();
    Map<String, Long> fiscal = new TreeMap<>();
    Map<String, Long> withdrawal = new TreeMap<>();
    for (MoneyIssuanceRecord record : data.moneyIssuances().values()) {
      if (record.kind() == MoneyIssuanceKind.INITIAL_ENDOWMENT) {
        initial.merge(record.currency().value(), record.amount(), Long::sum);
      } else if (record.kind() == MoneyIssuanceKind.FISCAL_ISSUE) {
        fiscal.merge(record.currency().value(), record.amount(), Long::sum);
      } else {
        withdrawal.merge(record.currency().value(), record.amount(), Long::sum);
      }
    }
    Map<String, Long> cumulativeIssuance = new TreeMap<>();
    mergeIssuance(cumulativeIssuance, initial);
    mergeIssuance(cumulativeIssuance, fiscal);
    Map<String, Long> net = new TreeMap<>(cumulativeIssuance);
    for (Map.Entry<String, Long> entry : withdrawal.entrySet()) {
      net.merge(entry.getKey(), -entry.getValue(), Long::sum);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("window", "累计（EconomyMeta.activatedDay 至当前 revision；无按日窗口）");
    view.put("recordCount", data.moneyIssuances().size());
    view.put("initialEndowment", initial);
    view.put("fiscalIssue", fiscal);
    view.put("cumulativeIssuance", cumulativeIssuance);
    view.put("cumulativeWithdrawal", withdrawal);
    view.put("netIssuance", net);
    view.put("circulation", new TreeMap<>(circulation));
    return view;
  }

  private static void mergeIssuance(Map<String, Long> target, Map<String, Long> source) {
    for (Map.Entry<String, Long> entry : source.entrySet()) {
      target.merge(entry.getKey(), entry.getValue(), Long::sum);
    }
  }

  /** ★★ E3：货币余额按 actor kind 聚合（逐币种；世界级时点）。键序 = kind 名 / 币种名字典序。 */
  private static Map<String, Map<String, Long>> moneyByActorKind(ActorData actors) {
    Map<String, Map<String, Long>> byKind = new TreeMap<>();
    for (GoodsAccount account : actors.accounts().values()) {
      mergeMoneyInto(
          byKind.computeIfAbsent(HouseholdActors.of(account.key().household()).kind().name(), ignored -> new TreeMap<>()),
          account.money());
    }
    return byKind;
  }

  /**
   * ★★ E3：家户货币余额按 {@code ClassRow.view.stratum} 聚合（逐币种；世界级时点）。 只认 {@code ActorKind.HOUSEHOLD} 且能在
   * economy 行集里定位的家户；定位不到的家户不静默塞进某一阶层，而是记进 {@code "__unmapped__"}。
   */
  private static Map<String, Map<String, Long>> moneyByHouseholdClass(
      EconomyData data, ActorData actors) {
    Map<String, Map<String, Long>> byClass = new TreeMap<>();
    for (GoodsAccount account : actors.accounts().values()) {
      if (HouseholdActors.of(account.key().household()).kind() != ActorKind.HOUSEHOLD) {
        continue;
      }
      HouseholdId household = HouseholdActors.householdOf(HouseholdActors.of(account.key().household()));
      ClassRow row = data.classes().get(household);
      String stratum = row == null ? "__unmapped__" : row.view().stratum().value();
      mergeMoneyInto(byClass.computeIfAbsent(stratum, ignored -> new TreeMap<>()), account.money());
    }
    return byClass;
  }

  /**
   * ★★ <b>可支配商品表</b>（M1.2）：逐商品问 {@link AvailableStock#available(GoodsAccount, CommodityId)} —— 读口
   * <b>只读不重算</b>（这一栏里没有第二处 `余额 − 冻结`）。键集 = 余额表 ∪ 冻结表，键序 = 商品名字典序。
   */
  private static Map<String, Long> availableCommodities(GoodsAccount account) {
    Map<String, Long> out = new TreeMap<>();
    for (CommodityId id : union(account.balances().keySet(), account.frozenBalances().keySet())) {
      out.put(id.value(), AvailableStock.available(account, id));
    }
    return out;
  }

  /** ★★ <b>可支配货币表</b>（M1.2）：与 {@link #availableCommodities} 逐条同款（**逐币种**，不跨币种求和）。 */
  private static Map<String, Long> availableCurrencies(GoodsAccount account) {
    Map<String, Long> out = new TreeMap<>();
    for (CurrencyId id : union(account.money().keySet(), account.frozenMoney().keySet())) {
      out.put(id.value(), AvailableStock.available(account, id));
    }
    return out;
  }

  /** 两张表的键的并集（保序：先 A 后补 B 的新键）—— `available*` 两栏的键集就是它（缺键 = 0）。 */
  private static <A> Set<A> union(Set<A> first, Set<A> second) {
    Set<A> all = new LinkedHashSet<>(first);
    all.addAll(second);
    return all;
  }

  /**
   * ★★ <b>币种定义的读侧形</b>（M1.1）：{@code [{id:"silver", scale:3}]} —— **保序**（词表序）、只读不重算。
   *
   * <p>★ 唯一来源是 {@link MoneyVocabulary#allCurrencyDefs()}（世界级货币词表的唯一拼写点）—— 本层**不**自己拼币种名、 也不自己定精度。★
   * {@code scale} 是"1 个币种单位 = 10^scale 个最小单位"（毫银 ⇒ 3）。
   */
  private static List<Map<String, Object>> currencyDefViews() {
    List<Map<String, Object>> defs = new ArrayList<>();
    for (CurrencyDef def : MoneyVocabulary.allCurrencyDefs()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", def.id());
      item.put("scale", def.scale());
      defs.add(item);
    }
    return defs;
  }

  /**
   * ★★ <b>货币工具的读侧形</b>（M1.1）：{@code [{id, currency, kind, issuer, redeemer}]} —— **保序**（词表序）。
   *
   * <p>★★ <b>{@code issuer / redeemer} 为 {@code null} = 这张工具**没有**发行人/兑现人</b>（金属币没有发行人，兑现属 M4+）——
   * 不是"读不到"。★ 形状照本仓读口对主体的口径：{@code {kind, id}}（**不**折算成 {@code ActorRef.toString()} 的规范串 ——
   * R6：那是**键**的形制，不是读口的形制）。★ 唯一来源是 {@link MoneyVocabulary#allInstruments()}。
   */
  private static List<Map<String, Object>> moneyInstrumentViews() {
    List<Map<String, Object>> instruments = new ArrayList<>();
    for (MoneyInstrument instrument : MoneyVocabulary.allInstruments()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", instrument.id().value());
      item.put("currency", instrument.currency().value());
      item.put("kind", instrument.kind().name());
      item.put("issuer", instrument.issuer().map(ApiViews::actorRefView).orElse(null));
      item.put("redeemer", instrument.redeemer().map(ApiViews::actorRefView).orElse(null));
      instruments.add(item);
    }
    return instruments;
  }

  /**
   * 主体引用的读侧形：{@code {kind, id}}（与 {@code labor.actors[]} 及写侧载荷的 {@code actor} 同形）。
   *
   * <p>★ 键序固定为 {@code kind, id}（{@code LinkedHashMap} + 不重排 ⇒ 同状态两次响应逐字节相同）。★ <b>不</b>走 {@code
   * ActorRef.toString()}（R6：那是**键**的形制）。
   */
  private static Map<String, Object> actorRefView(ActorRef ref) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("kind", ref.kind().name());
    view.put("id", ref.id());
    return view;
  }

  /**
   * ★★ <b>一格市场的读侧形</b>（H4；{@link #economyHex} 用）：{@code {numeraire, prices}} —— {@code {"silver",
   * {"grain":1,"cloth":5,…}}}。
   *
   * <p>★★ <b>只读、不重算</b>（AGENT.md §8.3：视图装配只在 {@code ApiViews}，且**没有第二个价表拼写点**）： 计价货币与价格都直接取自 {@link
   * Market}（它们是 economy 侧从创世载荷读进来的**数据**，GM 可调，信条十二）。 ★ 键序走 {@link #sortedCommodities}（字典序）⇒
   * 响应字节是内容的纯函数。 ★ <b>该格没有市场 ⇒ {@code null}</b>：那是**合法状态**（缺格的格没有市场），不是缺数据。
   */
  private static Map<String, Object> marketView(Market market) {
    if (market == null) {
      return null;
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("numeraire", market.numeraire().value());
    view.put("prices", sortedCommodities(market.prices()));
    return view;
  }

  /**
   * ★★ <b>R4-E2：本格相关需求账本的只读视图</b>：HEX 范围命中本格的需求 + HOUSEHOLD 范围住在该格家户的需求。
   *
   * <p>★ 只读、不重算摊分；{@code effective} 由当前 {@code tick} 现判（{@code tick < 0} ⇒ {@code null} =
   * 读口没有世界时钟，不猜）。
   */
  private static List<Map<String, Object>> demandViews(
      EconomyData data, HexCoord coord, long tick) {
    List<Map.Entry<DemandId, DemandEntry>> entries = new ArrayList<>(data.demands().entrySet());
    entries.sort(Comparator.comparing(entry -> entry.getKey().value()));
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map.Entry<DemandId, DemandEntry> entry : entries) {
      DemandEntry demand = entry.getValue();
      if (!demandTouchesHex(data, demand, coord)) {
        continue;
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", demand.id().value());
      view.put("scope", demand.scope().name());
      view.put("household", demand.household().map(HouseholdId::value).orElse(null));
      view.put("hex", demand.hex().map(ApiViews::hexCoord).orElse(null));
      view.put("commodity", demand.commodity().value());
      view.put("kind", demand.kind().name());
      view.put("unit", demand.unit().name());
      view.put("quantityPerCycle", demand.quantityPerCycle());
      view.put("createdDay", demand.createdDay());
      view.put("expiresDay", demand.expiresDay());
      view.put("priority", demand.priority());
      view.put("source", demand.source());
      view.put("effective", tick < 0L ? null : demand.effectiveOn(tick));
      out.add(view);
    }
    return out;
  }

  /** 本格相关：HEX 需求命中本格，或 HOUSEHOLD 需求的家户住在该格。 */
  private static boolean demandTouchesHex(EconomyData data, DemandEntry demand, HexCoord coord) {
    if (demand.scope() == DemandEntry.DemandScope.HEX) {
      return demand.hex().map(coord::equals).orElse(false);
    }
    ClassRow row = data.classes().get(demand.household().orElse(null));
    return row != null && row.view().hex().equals(coord);
  }

  /** ★★ R4-E2：候选预设的全量只读视图（世界级、与格无关；按 id 值升序）。 */
  private static List<Map<String, Object>> candidateViews(EconomyData data) {
    List<ProductionCandidate> candidates = new ArrayList<>(data.candidates().values());
    candidates.sort(Comparator.comparing(candidate -> candidate.id().value()));
    List<Map<String, Object>> out = new ArrayList<>(candidates.size());
    for (ProductionCandidate candidate : candidates) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", candidate.id().value());
      view.put("version", candidate.version());
      view.put("modeKey", candidate.modeKey());
      view.put("name", candidate.name());
      view.put("output", candidate.output().value());
      view.put("outputPerUnit", sortedCommodities(candidate.outputPerUnit()));
      view.put("inputPerUnit", sortedCommodities(candidate.inputPerUnit()));
      Map<String, Long> requiredAssets = new TreeMap<>();
      for (Map.Entry<AssetKind, Long> asset : candidate.requiredAssets().entrySet()) {
        requiredAssets.put(asset.getKey().name(), asset.getValue());
      }
      view.put("requiredAssets", requiredAssets);
      view.put("laborPerUnit", candidate.laborPerUnit());
      view.put("buildDays", candidate.buildDays());
      view.put("cycleDays", candidate.cycleDays());
      view.put("regime", candidate.regime().value());
      view.put("laborSource", candidate.laborSource().name());
      List<String> rights = new ArrayList<>();
      for (AssetShare.RightKind right : candidate.acceptedRightKinds()) {
        rights.add(right.name());
      }
      rights.sort(Comparator.naturalOrder());
      view.put("acceptedRightKinds", rights);
      view.put("assetSource", candidate.assetSource().map(ApiViews::actorRefView).orElse(null));
      out.add(view);
    }
    return out;
  }

  /**
   * ★★ <b>R4-E2b：本格的实物资产份额只读视图</b>（{@code id/industry/asset/owner/operator/quantity/kind}）—— 进入动作只拆
   * {@code assetSource} 名下的份额：owner 不变、operator 改本户、kind 按 acceptedRightKinds，总量不变。
   * 这一栏是那条守恒的逐条证据（{@code EconomyData.assetShares()} 的唯一真源，视图不重算）。
   */
  private static List<Map<String, Object>> assetShareViews(EconomyData data, HexCoord coord) {
    String hexKey = IndustryHexKeys.hexKey(coord.q(), coord.r());
    List<AssetShare> shares = new ArrayList<>();
    for (AssetShare share : data.assetShares().values()) {
      if (IndustryHexKeys.hexKeyOf(share.industry()).filter(hexKey::equals).isPresent()) {
        shares.add(share);
      }
    }
    shares.sort(Comparator.comparing(share -> share.id().value()));
    List<Map<String, Object>> out = new ArrayList<>(shares.size());
    for (AssetShare share : shares) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", share.id().value());
      view.put("industry", share.industry().value());
      view.put("asset", share.asset().name());
      view.put("owner", actorRefView(share.owner()));
      view.put("operator", actorRefView(share.operator()));
      view.put("quantity", share.quantity());
      view.put("kind", share.kind().name());
      out.add(view);
    }
    return out;
  }

  /**
   * ★★ <b>E5a：清算政策只读视图</b>（世界级；按 {@code ruleId} 排序 ⇒ 响应字节是内容的纯函数）。它只<b>读</b> {@code
   * EconomyData.liquidationPolicies()}：E5a 不执行清算，空列表 = 没有登记政策（不是"读不到"）。
   */
  private static List<Map<String, Object>> liquidationPolicyViews(EconomyData data) {
    List<LiquidationPolicy> policies = new ArrayList<>(data.liquidationPolicies().values());
    policies.sort(Comparator.comparing(policy -> policy.ruleId().value()));
    List<Map<String, Object>> out = new ArrayList<>(policies.size());
    for (LiquidationPolicy policy : policies) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("ruleId", policy.ruleId().value());
      view.put("maxLiquidatePerMille", policy.maxLiquidatePerMille());
      view.put("protectedReserve", policy.protectedReserve());
      view.put("priceSource", policy.priceSource().name());
      view.put("policyValuePerUnitMilli", policy.policyValuePerUnitMilli());
      view.put("recipientRule", policy.recipientRule().name());
      out.add(view);
    }
    return out;
  }

  /**
   * ★★ <b>E5a：本格 hex 危机信号只读视图</b>（按 id 排序；空列表 = 本格没有信号，不是"读不到"）。★ {@code evidence} 按
   * 原始量发出（<b>可为负</b>；负值不等于无证据，见 {@code HexCrisisSignal} 类注），视图不折算、不填 0。
   */
  private static List<Map<String, Object>> crisisSignalViews(EconomyData data, HexCoord coord) {
    List<HexCrisisSignal> signals = new ArrayList<>();
    for (HexCrisisSignal signal : data.crisisSignals().values()) {
      if (signal.hex().equals(coord)) {
        signals.add(signal);
      }
    }
    signals.sort(Comparator.comparing(signal -> signal.id().value()));
    List<Map<String, Object>> out = new ArrayList<>(signals.size());
    for (HexCrisisSignal signal : signals) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", signal.id().value());
      view.put("hex", hexCoord(signal.hex()));
      view.put("kind", signal.kind().name());
      view.put("severity", signal.severity());
      view.put("day", signal.day());
      view.put("evidence", new TreeMap<>(signal.evidence()));
      List<String> households = new ArrayList<>(signal.households().size());
      for (HouseholdId household : signal.households()) {
        households.add(household.value());
      }
      view.put("households", households);
      List<String> classes = new ArrayList<>(signal.classes().size());
      for (SocialClassId socialClass : signal.classes()) {
        classes.add(socialClass.value());
      }
      view.put("classes", classes);
      view.put("reason", signal.reason());
      out.add(view);
    }
    return out;
  }

  /**
   * ★★ <b>E5b：本格质押只读视图</b>（按 {@code pledgeId} 排序 ⇒ 响应字节是内容的纯函数）。★ 只列份额登记在本格的质押；
   * 份额在别的格的质押请查那一格。{@code quantity} 与 AssetShare 同单位；{@code status} 是 ACTIVE/RELEASED/EXECUTED。
   */
  private static List<Map<String, Object>> pledgeViews(EconomyData data, HexCoord coord) {
    String hexKey = IndustryHexKeys.hexKey(coord.q(), coord.r());
    List<Pledge> pledges = new ArrayList<>();
    for (Pledge pledge : data.pledges().values()) {
      AssetShare share = data.assetShares().get(pledge.assetShareId());
      if (share == null) {
        continue; // 份额缺失 = 坏状态；逐条具名留给 ownership/资产读数，不在这里伪造
      }
      if (IndustryHexKeys.hexKeyOf(share.industry()).filter(hexKey::equals).isPresent()) {
        pledges.add(pledge);
      }
    }
    pledges.sort(Comparator.comparing(pledge -> pledge.id().value()));
    List<Map<String, Object>> out = new ArrayList<>(pledges.size());
    for (Pledge pledge : pledges) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", pledge.id().value());
      view.put("debtContractId", pledge.debtContractId().value());
      view.put("assetShareId", pledge.assetShareId().value());
      view.put("quantity", pledge.quantity());
      view.put("modeId", pledge.modeId().value());
      view.put("priority", pledge.priority());
      view.put("status", pledge.status().name());
      out.add(view);
    }
    return out;
  }

  /**
   * ★★ <b>E6a：模式变迁只读视图</b>（世界级；按 {@code ModeTransitionId} 字典序 ⇒ 响应字节是内容的纯函数）。它只 <b>读</b> {@code
   * EconomyData.modeTransitions()}：PENDING/APPLIED/FAILED 逐条原样发出（FAILED 的 reason 具名）， 空列表 =
   * 没有变迁记录（不是"读不到"）。视图不重算位置匹配、不猜格键。
   */
  private static List<Map<String, Object>> modeTransitionViews(EconomyData data) {
    List<ModeTransition> transitions = new ArrayList<>(data.modeTransitions().values());
    transitions.sort(Comparator.comparing(transition -> transition.id().value()));
    List<Map<String, Object>> out = new ArrayList<>(transitions.size());
    for (ModeTransition transition : transitions) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", transition.id().value());
      view.put("organizationId", transition.organizationId().value());
      view.put("fromModeId", transition.fromModeId().value());
      view.put("toModeId", transition.toModeId().value());
      view.put("retainOriginalPerMille", transition.retainOriginalPerMille());
      view.put("requestedDay", transition.requestedDay());
      view.put("effectiveDay", transition.effectiveDay());
      view.put("status", transition.status().name());
      view.put("reason", transition.reason());
      out.add(view);
    }
    return out;
  }

  /**
   * ★★ <b>E6a：阶层保留份额只读视图</b>（世界级；按 {@code ClassShareId} 字典序）。它只 <b>读</b> {@code
   * EconomyData.classShares()}：每条的 {@code sharePerMille} 与 {@link ClassStanding#retainedShares()}
   * 同源； 同一 {@code (transitionId, householdId)} 的 Σ = 1000‰ 由构造期守卫判死。空列表 = 没有份额记录。
   */
  private static List<Map<String, Object>> classShareViews(EconomyData data) {
    List<ClassShare> shares = new ArrayList<>(data.classShares().values());
    shares.sort(Comparator.comparing(share -> share.id().value()));
    List<Map<String, Object>> out = new ArrayList<>(shares.size());
    for (ClassShare share : shares) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("id", share.id().value());
      view.put("transitionId", share.transitionId().value());
      view.put("householdId", share.householdId().value());
      view.put("classPositionId", share.classPositionId().value());
      view.put("sharePerMille", share.sharePerMille());
      out.add(view);
    }
    return out;
  }

  /**
   * ★★ <b>E5b：本日清算/阶层下滑/投影回退的瞬态审计</b>（{@code ProductionLedger.liquidationAudits}；只列与本格相关的条目）。
   *
   * <p>★ <b>窗口</b>：只在读数 tick 与最近一次结算 tick 相同时可读（旧 EconomyDayFeed，R3a 已删除）；不落盘。 ★ <b>单位</b>：{@code
   * quantity} 与 AssetShare 同单位；{@code *Milli} 为毫值（粮债口径 = 毫粮）。 ★ <b>读不到</b>：上层返回 null + {@link
   * #LIQUIDATION_AUDIT_PROCESS_ONLY}，<b>不填空数组</b>冒充"当天没有清算"。
   */
  private static Map<String, Object> liquidationAuditView(
      ProductionLedger ledger, EconomyData data, HexCoord coord) {
    List<ProductionLedger.LiquidationAudit> audits = new ArrayList<>();
    for (ProductionLedger.LiquidationAudit audit : ledger.liquidationAudits()) {
      if (liquidationAuditBelongsToHex(audit, data, coord)) {
        audits.add(audit);
      }
    }
    List<Map<String, Object>> items = new ArrayList<>(audits.size());
    for (ProductionLedger.LiquidationAudit audit : audits) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("day", audit.day());
      item.put("action", audit.action());
      item.put("household", audit.household().map(HouseholdId::value).orElse(null));
      item.put("contractId", audit.contractId().map(DebtContractId::value).orElse(null));
      item.put("pledgeId", audit.pledgeId().map(pledgeId -> pledgeId.value()).orElse(null));
      item.put(
          "sourceAssetShareId",
          audit.sourceAssetShareId().map(assetShareId -> assetShareId.value()).orElse(null));
      item.put(
          "createdAssetShareId",
          audit.createdAssetShareId().map(assetShareId -> assetShareId.value()).orElse(null));
      item.put("quantity", audit.quantity());
      item.put("pricePerUnitMilli", audit.pricePerUnitMilli());
      item.put("debtReductionMilli", audit.debtReductionMilli());
      item.put("debtPrincipalAfter", audit.debtPrincipalAfter());
      item.put("pledgeQuantityAfter", audit.pledgeQuantityAfter());
      item.put("reason", audit.reason());
      item.put("evidence", new TreeMap<>(audit.evidence()));
      items.add(item);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("items", items);
    view.put(
        "provenance",
        "来自本日 ProductionLedger.liquidationAudits（进程内瞬态；不落盘）。quantity 与 AssetShare 同单位；"
            + "*Milli 为毫值（粮债口径 = 毫粮）；action/reason 是具名动作与跳过原因；"
            + "createdAssetShareId 是本次转移新建的接收方份额 id");
    return view;
  }

  /** 审计是否与本格相关：家户本格 / 合同债务人本格 / 被处置份额的产业本格，三者任一命中。 */
  private static boolean liquidationAuditBelongsToHex(
      ProductionLedger.LiquidationAudit audit, EconomyData data, HexCoord coord) {
    String hexKey = IndustryHexKeys.hexKey(coord.q(), coord.r());
    if (audit.household().isPresent()) {
      ClassRow row = data.classes().get(audit.household().get());
      if (row != null && row.view().hex().equals(coord)) {
        return true;
      }
    }
    if (audit.contractId().isPresent()) {
      DebtContract contract = data.debtContracts().get(audit.contractId().get());
      if (contract != null) {
        ClassRow debtorRow = data.classes().get(contract.debtor());
        if (debtorRow != null && debtorRow.view().hex().equals(coord)) {
          return true;
        }
      }
    }
    if (audit.sourceAssetShareId().isPresent()) {
      AssetShare share = data.assetShares().get(audit.sourceAssetShareId().get());
      if (share != null
          && IndustryHexKeys.hexKeyOf(share.industry()).filter(hexKey::equals).isPresent()) {
        return true;
      }
    }
    return false;
  }

  /**
   * ★★ <b>E5a：家户阶层归属只读视图</b>：把 {@code ClassStanding} 的每个字段（含 {@code
   * consecutiveDebtStressCycles}）逐值发出；视图不解释、不重算，也不改旧路径权威。
   */
  private static Map<String, Object> classStandingView(ClassStanding standing) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("householdId", standing.householdId().value());
    view.put("originalPositionId", standing.originalPositionId().value());
    view.put("currentPositionId", standing.currentPositionId().value());
    Map<String, Long> retained = new TreeMap<>();
    for (Map.Entry<ClassPositionId, Long> entry : standing.retainedShares().entrySet()) {
      retained.put(entry.getKey().value(), entry.getValue());
    }
    view.put("retainedShares", retained);
    view.put("consecutiveDebtStressCycles", standing.consecutiveDebtStressCycles());
    view.put("lastTransitionDay", standing.lastTransitionDay());
    view.put("reason", standing.reason());
    return view;
  }

  /**
   * ★★ <b>R4-E2b：候选进入评估结果的本格切片</b>—— 只读 {@link EntryOutcomeFeed} 的进程内审计，<b>不重算</b>； {@code
   * accepted=false} 的 {@code reason} 是具名拒绝码（缺资产/缺劳动/缺投入/需求窗口太短/无价…）， 让"需求有买单但没有新 unit"在读口可解释。
   */
  private static Map<String, Object> entryOutcomeView(
      EntryOutcomeFeed.Snapshot snapshot, HexCoord coord) {
    String hexKey = IndustryHexKeys.hexKey(coord.q(), coord.r());
    List<EntryOutcome> outcomes = new ArrayList<>();
    for (EntryOutcome outcome : snapshot.outcomes()) {
      if (outcome.hexKey().equals(hexKey)) {
        outcomes.add(outcome);
      }
    }
    outcomes.sort(
        Comparator.comparing((EntryOutcome outcome) -> outcome.household().value())
            .thenComparing(outcome -> outcome.candidateId().value())
            .thenComparingInt(EntryOutcome::version));
    List<Map<String, Object>> items = new ArrayList<>(outcomes.size());
    for (EntryOutcome outcome : outcomes) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("household", outcome.household().value());
      item.put("candidateId", outcome.candidateId().value());
      item.put("version", outcome.version());
      item.put("modeKey", outcome.modeKey());
      item.put("industry", outcome.industryId().value());
      item.put("accepted", outcome.accepted());
      item.put("reason", outcome.reason());
      item.put("trialScale", outcome.trialScale());
      item.put("expectedDay", outcome.expectedDay());
      item.put("inputPlan", new TreeMap<>(outcome.inputPlan()));
      item.put("laborMilli", outcome.laborMilli());
      items.add(item);
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("day", snapshot.day());
    view.put("items", items);
    view.put(
        "provenance",
        "EntryOutcomeFeed：进程内瞬态（不落盘、不新增 EconomyData 组件）；day = 最近一次不晚于当前 tick 的日结算日；"
            + "unit/份额的真值看 units[] 与 assetShares[]");
    return view;
  }

  /**
   * 单格：格内容 + 所属区域**列表**（多从属，M8-U1）+ **完整地形定义** + facet 汇总（facet 由调用方按 **canonical** 地址查询，spec
   * §5.2）+ **入射连通性**（M8 T11）。{@code regions} 按**定义序**（{@code GameMap.regions} 插入序，★ **V3 取代 M8-Q6**
   * 的字典序； **末位 = 最顶层区域**），**空数组 = 无从属**。
   *
   * <p>★ {@code terrainType} 由调用方从**状态里的** {@code map.terrainTypes()} 取出（spec §3.2）——本类不查 {@code
   * TerrainCatalog}，词表只有一个来源。
   *
   * <p>★ {@code edges}（T11 新增）是**只读**增量：连通性此前在 app 层**没有任何读路径**（overview 只有块， mapHex
   * 只有地形），于是"`merge` 后既有 tag 仍在 / `replace` 后只剩新的"这条判据在**浏览器里根本观测不到** （断不了言 = 装饰）。字段形如 {@code
   * [{edge:"1_1|1_2", pathways:["river","road"]}]}，语义照 {@link EdgeTags#byPathway()} 的键。
   *
   * <p>★ <b>C6a（D-020）</b>：末尾追加 {@code date} 与 {@code season}——两者都用**该 state 的 tick**；季节按**该格
   * {@code coord.r()}** 判带（{@link CalendarService#seasonAt}），未配置分带时 {@code zoneSource=fallback}。 旧页
   * {@code map.html} 与工作台 {@code index.html} 共用这一份响应，前端只渲染、不重算。
   */
  static Map<String, Object> mapHex(
      HexCoord coord,
      HexCell cell,
      String terrain,
      List<FacetEntry> facets,
      List<RegionId> regions,
      TerrainType terrainType,
      Map<EdgeRef, EdgeTags> edges,
      SimosTimestamp at,
      CalendarService calendars) {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(calendars, "calendars");
    Map<String, Object> view = hexCoord(coord);
    view.put("terrain", terrain);
    view.put("height", cell.height());
    List<String> regionIds = new ArrayList<>(regions.size());
    for (RegionId region : regions) {
      regionIds.add(region.value());
    }
    view.put("regions", regionIds);
    view.put("terrainType", terrainType);
    view.put("facets", facets(facets));
    view.put("edges", incidentEdges(coord, edges));
    view.put("date", dateView(calendars.dateOfTick(at.tick())));
    view.put("season", seasonView(calendars, calendars.seasonAt(at.tick(), coord), coord));
    return view;
  }

  /**
   * 单格所在的**地形块**（整块）：{@code {q,r,terrain,hexCount,hexes:[{q,r}…]}}。
   *
   * <p>用途：前端「油漆桶」——把**整块连通同地形**一次换成另一种地形：先看有多大（{@code hexCount} 供超量确认）， 再把 {@code hexes} 原样喂给一条
   * {@code map.SetTerrain}。{@code hexes} 顺序 = 块自身的自然序（不另排序，前端与后端看到同一个序）。
   */
  public static Map<String, Object> mapBlock(HexCoord coord, String terrain, List<HexCoord> hexes) {
    Map<String, Object> view = hexCoord(coord);
    view.put("terrain", terrain);
    view.put("hexCount", hexes.size());
    List<Map<String, Object>> out = new ArrayList<>(hexes.size());
    for (HexCoord hex : hexes) {
      out.add(hexCoord(hex));
    }
    view.put("hexes", out);
    return view;
  }

  /**
   * 入射到 {@code coord} 的边（M8 T11）：{@code [{edge:"1_1|1_2", pathways:["river","road"]}]}。
   *
   * <p>★ **两种排序都是为了可复现**：边按 {@link EdgeRef} 自然序（`(q,r)`，**不是字符串序**——`1_10` 与 `1_2`
   * 的字符串序会把规范序倒过来），`pathways` 按字典序。`/api/map/hex` 有一条"同一 revision 两次响应**逐字节 相同**"的既有断言（{@code
   * GuiApiTest}）：任何一处跟着 `Map` 的迭代序走，那条断言就会随 JVM 的散列盐抖动。
   *
   * <p>★ 只发 pathway **键**（当前载荷里 props 恒为空表），与"哪种连通性在这条边上"这一个判据同名。
   */
  private static List<Map<String, Object>> incidentEdges(
      HexCoord coord, Map<EdgeRef, EdgeTags> edges) {
    List<EdgeRef> incident = new ArrayList<>();
    for (EdgeRef edge : edges.keySet()) {
      if (edge.a().equals(coord) || edge.b().equals(coord)) {
        incident.add(edge);
      }
    }
    incident.sort(Comparator.naturalOrder());
    List<Map<String, Object>> out = new ArrayList<>(incident.size());
    for (EdgeRef edge : incident) {
      out.add(edgeEntry(edge, edges.get(edge)));
    }
    return out;
  }

  /** 一条边的视图：{@code {edge:"a|b", pathways:["river","road"]}}，pathways 按字典序。 */
  private static Map<String, Object> edgeEntry(EdgeRef edge, EdgeTags tags) {
    Map<String, Object> entry = new LinkedHashMap<>();
    entry.put("edge", edge.toString());
    List<String> pathways = new ArrayList<>(tags.byPathway().keySet());
    pathways.sort(Comparator.naturalOrder());
    entry.put("pathways", pathways);
    return entry;
  }

  /**
   * 区域详情（spec §3.3，M7 T1）：{@code {id,name,meta,hexCount,hexes:[{q,r}…]}}。
   *
   * <p>★ {@code hexes} 按 {@code (q,r)} 升序——{@link Region#hexes()} 是 {@code Set.copyOf}（迭代序不稳定），
   * 不排序的响应字节不可复现，测试也钉不住。
   */
  public static Map<String, Object> regionDetail(Region region) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", region.id().value());
    view.put("name", region.name());
    view.put("meta", regionMeta(region.meta()));
    view.put("hexCount", region.hexes().size());
    List<HexCoord> sorted = new ArrayList<>(region.hexes());
    sorted.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    List<Map<String, Object>> hexes = new ArrayList<>(sorted.size());
    for (HexCoord coord : sorted) {
      hexes.add(hexCoord(coord));
    }
    view.put("hexes", hexes);
    return view;
  }

  /**
   * 区域名的**锚点**（U2 设计 §二）：取区域 hex 集合的质心（{@code round(Σq/n), round(Σr/n)}）。
   *
   * <p>★ 为什么在服务端算：overview 已是块级载荷，逐区域 hex 会让客户端再拉 N+1 次 + MB 级 payload（M9 教训）。 质心只是两个整数。{@code
   * hexes} 为空 ⇒ {@code null}（不编造锚点）。{@code long} 累加避免溢出。 质心**可能落在区域外**（带洞 / 细长区域）——它只是标签锚点，与
   * GSimulator 同口径。
   */
  private static Map<String, Object> regionLabelHex(Region region) {
    Set<HexCoord> hexes = region.hexes();
    if (hexes.isEmpty()) {
      return null;
    }
    long sumQ = 0L;
    long sumR = 0L;
    for (HexCoord hex : hexes) {
      sumQ += hex.q();
      sumR += hex.r();
    }
    int n = hexes.size();
    return hexCoord(
        new HexCoord((int) Math.round((double) sumQ / n), (int) Math.round((double) sumR / n)));
  }

  /** 区域元数据（四字段可空；spec §3.3）。 */
  private static Map<String, Object> regionMeta(RegionMeta meta) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("color", meta.color());
    view.put("tag", meta.tag());
    view.put("description", meta.description());
    view.put("annexedBy", meta.annexedBy());
    return view;
  }

  /** 单位列表（每个单位带 head 时刻的有效位置与在途移动视图；★ 2026-09-24 起带所属交战）。 */
  static List<Map<String, Object>> units(
      UnitState units, SimosTimestamp at, GameMap map, SdState sd, CalendarService calendars) {
    return units(units, at, map, sd, calendars, null, null);
  }

  /** S3a：单位清单的每条视图同样带 {@code households[]} 与实时 {@code population}（与 {@link #unit} 的 8 参形态同源）。 */
  static List<Map<String, Object>> units(
      UnitState units,
      SimosTimestamp at,
      GameMap map,
      SdState sd,
      CalendarService calendars,
      PopulationLookup population,
      HouseholdLookup householdLookup) {
    List<Map<String, Object>> out = new ArrayList<>(units.units().size());
    for (Unit unit : units.units().values()) {
      out.add(unit(unit, units, at, map, sd, calendars, population, householdLookup));
    }
    return out;
  }

  /**
   * 旧签名兼容（测试/旧路径）：缺省历法配置；生产路径由 {@code GuiServer}/{@code ToolSupport} 传世界的 {@link CalendarService}。
   */
  static List<Map<String, Object>> units(
      UnitState units, SimosTimestamp at, GameMap map, SdState sd) {
    return units(units, at, map, sd, CalendarService.defaults());
  }

  /**
   * 单位详情：冻结字段 + {@code parent}（head 时刻）+ **位置**（head 时刻）+ **在途移动视图** + **编制视图**（v2）+
   * **所属交战**（2026-09-24，无 ⇒ {@code null}）。
   *
   * <p>★★ **两边共用这一份**（AGENT.md §8.3 的纪律）：MCP 读工具（{@code simos.unit.list} / {@code simos.unit.get}）
   * 经 {@code ToolSupport.unit} 直接调本方法 ⇒ 新字段**一处加、两面同形**（原先两处各写一份，加字段就得记得改两处）。
   *
   * <p>★ {@code combat} 是**真实交战记录**（不是 GUI 现推的"同格多军队"）：见 {@link #combatOf}。
   *
   * <p>★★ <b>P4 只读 additive（2026-10-01）</b>：{@code unit.module()} 存在 ⇒ 追加 {@code module}（{@code
   * kind} + gov 的 {@code level/superiorGov/staff/policy} 或 army 的 {@code masterGov/role}）；{@code
   * unit.jurisdiction()} 存在 ⇒ 追加 {@code jurisdiction}（{@code regions}→税率、三个 levy 单命令上限、已退役的 {@code
   * administrationPerMille}）。两者缺席 ⇒ <b>键缺席</b>（不是 null/空对象），旧键逐字不变——与 {@code map.overview} 的 {@code
   * neighbors} 同款"你不是这种单位"与"你这种单位没有"必须可分。
   *
   * <p>★★ <b>D1 只读 additive（2026-10-02 / D-012）</b>：追加 {@code stateDescriptions} = 当前回合状态 →
   * canonical 状态描述地址的链接表。★ 与上面两项不同，它**空表也照发**：这是单位状态本体的一部分（与 {@code equipment} 同款），"没有链接"用空对象表达，
   * 读侧不必分"字段缺席"与"空表"两态。
   */
  public static Map<String, Object> unit(
      Unit unit,
      UnitState units,
      SimosTimestamp at,
      GameMap map,
      SdState sd,
      CalendarService calendars) {
    return unit(unit, units, at, map, sd, calendars, null, null);
  }

  /**
   * ★★ <b>S3a（2026-10-09）带家户/人口只读 SPI 的重载</b>：在 6 参形态之上追加
   *
   * <ul>
   *   <li>{@code households[]}：{@code unit.households()} 的逐项视图（{@code id} + 可得时带 {@code
   *       name/location/memberLots/population}）；
   *   <li>{@code population}：单位总人口 = {@code PopulationLookup.unitPopulation(unitId)}
   *       现算（<b>不是</b>单位状态里的第二本 headcount，架构 §5）。
   * </ul>
   *
   * 两个 lookup 可取同一个 {@code SocialLookupAdapter} 实例；{@code null} ⇒ {@code households[]} 只发 id、
   * {@code population} 为 {@code null}（旧调用点的"没有注入"必须与"是 0 人"可分）。
   */
  public static Map<String, Object> unit(
      Unit unit,
      UnitState units,
      SimosTimestamp at,
      GameMap map,
      SdState sd,
      CalendarService calendars,
      PopulationLookup population,
      HouseholdLookup householdLookup) {
    Objects.requireNonNull(calendars, "calendars");
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", unit.id().value());
    view.put("name", unit.name());
    // ★★ S3b（2026-10-09）：`Unit.manpower` 已退役——人员人口读口走下面的 `population`（Social 家户现算）；
    //   装备仍是 unit 状态本体，原样透出有序条目列表。
    view.put("equipment", compositionView(unit.equipment()));
    view.put("speed", unit.speed());
    view.put("mobilityPerMille", unit.mobilityPerMille());
    // ★ B9（用户 2026-09-23 实测）：单位**自身**状态（MOVING/RESTING/ENGAGED）此前两处视图都没发 ⇒
    //   前端"这个单位是什么"里缺"状态"一项。这里是领域真值（`Unit.status()` 的普通字段）的原样透出。
    view.put("status", unit.status().name());
    // ★★ P1.2 / A6 只读 additive：视野半径（六角圈数，0 = 只看自身格）。字段早已存在，本次补命令后在此读回，
    //   让"设了没有"不用读代码/查状态就能从 GUI 与 MCP 两个面看到。
    view.put("visionRadius", unit.visionRadius());
    view.put("parent", unit.parent().valueAt(at).map(UnitId::value).orElse(null));
    view.put(
        "position", units.effectivePosition(unit.id(), at).map(ApiViews::hexCoord).orElse(null));
    view.put("movement", movement(unit, at, map, calendars));
    // ★ 所属交战（2026-09-24）：真实 {@code CombatState} 记录优先；不在任何交战 ⇒ null。
    view.put("combat", combatOf(unit.id(), sd, units, at));
    // ★★ 编制 v2（2026-09-24）：`attached` = 我是不是跟别人一起走；`formationRootId` = 该对谁下令；
    //   `formationSize` / `formationSpeed` = **这一支**（从顶层算）多大、一起走多快（= 支内 `effectiveSpeed`
    // 最小值，含状态折算）。
    //   ★★ **规模与速度一律从顶层量**（不是从"我自己"这个子树量）：`轻骑兵` 那一行若报"1 个单位"，
    //   读的人（模型/界面）会以为它是一支独立的单兵编队——同一个字段在两行里必须说同一件事。
    Optional<UnitId> rootId = units.formationRoot(unit.id(), at);
    view.put("attached", unit.attached().valueAt(at));
    view.put("formationRootId", rootId.map(UnitId::value).orElse(null));
    view.put(
        "formationSize", rootId.map(root -> units.formationMembers(root, at).size()).orElse(1));
    view.put("formationSpeed", rootId.map(root -> units.formationSpeed(root, at)).orElse(0));
    // ★★ P4：编制标签与管辖的只读读回（缺席 ⇒ 键缺席，不改任何旧键）。
    unit.module().ifPresent(module -> view.put("module", unitModuleView(module)));
    unit.jurisdiction()
        .ifPresent(jurisdiction -> view.put("jurisdiction", unitJurisdictionView(jurisdiction)));
    // ★★ D1（2026-10-02 / D-012）：当前回合状态 ↔ 状态描述地址的链接表**原样透出**（空表也发——与
    //   `equipment` 同款：这栏本身是单位状态的一部分，"没有链接"就用空对象表达；GUI 与 MCP 同源这一份）。
    view.put("stateDescriptions", new LinkedHashMap<>(unit.stateDescriptions()));
    // ★★ S3a（2026-10-09）：unit 侧容纳的家户列表 + 实时人口（人口从 Social 家户汇总现算，不落第二本 headcount）。
    //   空列表也发（与 stateDescriptions/equipment 同款："没有家户"是单位状态本体的一部分）。
    view.put("households", unitHouseholdViews(unit, householdLookup, population));
    view.put(
        "population", population == null ? null : population.unitPopulation(unit.id().value()));
    return view;
  }

  /**
   * {@code unit.households()} 的逐项视图：{@code id} 恒有；注入 {@link HouseholdLookup} 时补 {@code
   * name/location/memberLots}；注入 {@link PopulationLookup} 时补该家户人数。★ 顺序 = unit 列表顺序（保序是内容）。
   */
  private static List<Map<String, Object>> unitHouseholdViews(
      Unit unit, HouseholdLookup householdLookup, PopulationLookup population) {
    List<Map<String, Object>> out = new ArrayList<>(unit.households().size());
    for (HouseholdId id : unit.households()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", id.value());
      if (householdLookup != null) {
        householdLookup
            .household(id)
            .ifPresent(
                household -> {
                  row.put("name", household.profile().name());
                  row.put("location", household.location().toString());
                  row.put("memberLots", household.memberLots().size());
                });
      }
      if (population != null) {
        row.put("population", population.householdPopulation(id));
      }
      out.add(row);
    }
    return out;
  }

  /**
   * 旧签名兼容（测试/旧路径）：缺省历法配置；生产路径由 {@code GuiServer}/{@code ToolSupport} 传世界的 {@link CalendarService}。
   */
  public static Map<String, Object> unit(
      Unit unit, UnitState units, SimosTimestamp at, GameMap map, SdState sd) {
    return unit(unit, units, at, map, sd, CalendarService.defaults());
  }

  /** 人力/装备有序表 → JSON 视图（{@code [{type,amount}…]}，顺序原样；D3a 的读侧唯一形状）。 */
  private static List<Map<String, Object>> compositionView(List<CompositionEntry> entries) {
    List<Map<String, Object>> view = new ArrayList<>(entries.size());
    for (CompositionEntry entry : entries) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", entry.type());
      row.put("amount", entry.amount());
      view.add(row);
    }
    return view;
  }

  /**
   * 编制标签视图（P4 gov 读回）：两个 {@link UnitModule} sealed 子类型各自的字段<b>逐值透出</b>，不派生任何力量/效率数值。
   *
   * <p>★ {@code kind} 取 {@code "gov"|"army"}：与 {@code UnitModule} 线格式的 {@code @class} 子类型名、以及
   * {@code Affiliation.Gov} 的 {@code kind:"gov"} 同一口径；{@code superiorGov}/{@code masterGov} 缺席 ⇒
   * {@code null}（"无上级/未认主子"是编制自身的状态，不是键缺失）。
   */
  private static Map<String, Object> unitModuleView(UnitModule module) {
    if (module instanceof GovFormation gov) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("kind", "gov");
      view.put("level", gov.level().name());
      view.put("superiorGov", gov.superiorGov().map(UnitId::value).orElse(null));
      view.put("staff", unitStaffView(gov.staff()));
      // ★★ S3a（2026-10-09）：官府下辖家户（保序原样透出；空表也发——"没有下辖"是编制自身状态）。
      List<String> govHouseholds = new ArrayList<>(gov.households().size());
      for (HouseholdId household : gov.households()) {
        govHouseholds.add(household.value());
      }
      view.put("households", govHouseholds);
      // ★★ S3b（2026-10-09）：领导层家户配置（以 HouseholdId 为键的具名状态；空表也发）。
      List<Map<String, Object>> posts = new ArrayList<>(gov.householdPosts().size());
      for (GovernmentHouseholdPost post : gov.householdPosts().values()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("household", post.householdId().value());
        row.put("role", post.role().name());
        row.put("level", post.level().name());
        row.put("head", post.headOfGovernment());
        posts.add(row);
      }
      view.put("householdPosts", posts);
      view.put("policy", officePolicyView(gov.policy()));
      return view;
    }
    if (module instanceof ArmyFormation army) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("kind", "army");
      view.put("masterGov", army.masterGov().map(UnitId::value).orElse(null));
      view.put("role", army.role());
      // ★★ S3b（2026-10-09）：军官/军职家户配置（以 HouseholdId 为键的具名状态；空表也发）。
      List<Map<String, Object>> duties = new ArrayList<>(army.householdDuties().size());
      for (MilitaryHouseholdDuty duty : army.householdDuties().values()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("household", duty.householdId().value());
        row.put("kind", duty.kind().name());
        row.put("appointment", duty.appointment());
        row.put("commandOf", duty.commandOf().map(UnitId::value).orElse(null));
        duties.add(row);
      }
      view.put("householdDuties", duties);
      return view;
    }
    throw new IllegalStateException("未知的 UnitModule 实现: " + module.getClass().getName());
  }

  /** 行政在编人数视图（角色名 → 人数；{@link GovFormation#staff()} 的插入序原样保留）。 */
  private static Map<String, Object> unitStaffView(Map<StaffRole, Long> staff) {
    Map<String, Object> view = new LinkedHashMap<>();
    for (Map.Entry<StaffRole, Long> entry : staff.entrySet()) {
      view.put(entry.getKey().name(), entry.getValue());
    }
    return view;
  }

  /** {@link OfficePolicy} 视图（五个字段全给；{@code staffCap} 角色名键、保序）。 */
  private static Map<String, Object> officePolicyView(OfficePolicy policy) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("grainPerStaffPerTick", policy.grainPerStaffPerTick());
    view.put("clothPerStaffPerCycle", policy.clothPerStaffPerCycle());
    view.put("moneyPerStaffPerTick", policy.moneyPerStaffPerTick());
    view.put("retirementPerStaff", policy.retirementPerStaff());
    view.put("staffCap", unitStaffView(policy.staffCap()));
    return view;
  }

  /**
   * 管辖视图（P4 gov 读回）：{@code regions} = region id → 每周期长期税率（‰）的<b>有序</b>映射（{@code
   * taxRatePerMilleByRegion} 的 JSON 形），另附三个 {@code levy*CapPerCommand}（一条抽取命令的上限；0 = 无额度） 与已退役的
   * {@code administrationPerMille}。
   *
   * <p>★ {@code administrationPerMille} 仅旧档兼容、生产路径零读取（阶段 11b）；这里如实透出，<b>不回写、不参与任何判定</b>。
   */
  private static Map<String, Object> unitJurisdictionView(Jurisdiction jurisdiction) {
    Map<String, Object> regions = new LinkedHashMap<>();
    for (Map.Entry<RegionId, Long> entry : jurisdiction.taxRatePerMilleByRegion().entrySet()) {
      regions.put(entry.getKey().value(), entry.getValue());
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("regions", regions);
    view.put("levyGrainCapPerCommand", jurisdiction.levyGrainCapPerCommand());
    view.put("levyMoneyCapPerCommand", jurisdiction.levyMoneyCapPerCommand());
    view.put("levyManpowerCapPerCommand", jurisdiction.levyManpowerCapPerCommand());
    view.put("administrationPerMille", jurisdiction.administrationPerMille());
    return view;
  }

  /**
   * 交战清单（GUI / MCP 的「真实交战记录」读口，2026-09-24）：每条 = 一个 {@link CombatState} + 它的 {@link Combat} 汇总。
   *
   * <p>字段：{@code combatId} / {@code combatStateId} / {@code name} / {@code hex} / {@code
   * currentStage} （阶段 id）/ {@code currentStageName} / {@code selectedOutcome}（未选 ⇒ {@code null}）/
   * {@code participants}（阶段参与 ∪ 交战汇总，字典序）/ {@code participantsAtHex}（**真的在交战格**的参与单位，走 {@link
   * UnitState#effectivePosition}）/ {@code participantCount} / {@code participantsAtHexCount}。
   *
   * <p>★ **为什么发 participantsAtHex**：引擎**不要求**交战双方同格（已核实的语义）——"这场交战有谁"与 "此刻谁真在那一格"是两件事，GUI
   * 要能分别表达（这正是"跨格也能看出属于同一场交战"的判据）。
   *
   * <p>★ **逐字节可复现**：{@code combatStates} 按 id 字典序、参与单位按字典序发（状态插入序不是内容的纯函数）。
   */
  public static List<Map<String, Object>> combats(SdState sd, UnitState units, SimosTimestamp at) {
    List<CombatStateId> ids = sortedCombatStateIds(sd);
    List<Map<String, Object>> out = new ArrayList<>(ids.size());
    for (CombatStateId id : ids) {
      CombatState state = sd.combatStates().get(id);
      out.add(combat(state, sd.combats().get(state.combatId()), units, at));
    }
    return out;
  }

  /**
   * 外交关系边清单（D-003 / R6；GUI 与 MCP 的 {@code simos.sd.diplomacy} 共用这一份形状）。
   *
   * <p>每条 = 一条**有向边** {@code from → to}：字段 {@code from}/{@code to}（Nation id 裸值）、{@code kind}（无
   * kind ⇒ {@code null}）、{@code text}（自然语言；谈判状态就记在它里面）、{@code updatedTick}。
   *
   * <p>★ <b>逐字节可复现</b>：边先按 {@code from}、再按 {@code to} 的 Nation id 字典序发（插入序不是内容的纯函数）。 ★
   * <b>自然语言是世界级文本</b>（D-002/R6）：这里不做逐格视野过滤；调用方要看的是"谁和谁是什么关系"。
   *
   * @param relations 关系边表（{@code SdState.diplomaticRelations()}）
   * @param nation 可选过滤：只看 from 或 to 等于该 Nation 的边（{@code null} = 全部）
   */
  public static List<Map<String, Object>> diplomaticRelations(
      Map<DiplomaticRelationKey, DiplomaticRelation> relations, NationId nation) {
    List<DiplomaticRelationKey> keys = new ArrayList<>(relations.keySet());
    keys.sort(
        Comparator.comparing((DiplomaticRelationKey key) -> key.from().value())
            .thenComparing(key -> key.to().value()));
    List<Map<String, Object>> out = new ArrayList<>(keys.size());
    for (DiplomaticRelationKey key : keys) {
      if (nation != null && !key.from().equals(nation) && !key.to().equals(nation)) {
        continue;
      }
      DiplomaticRelation relation = relations.get(key);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("from", key.from().value());
      view.put("to", key.to().value());
      view.put("kind", relation.kind().orElse(null));
      view.put("text", relation.text());
      view.put("updatedTick", relation.updatedTick());
      out.add(view);
    }
    return out;
  }

  /**
   * 外交事件清单（D-005 / R6；GUI 与 MCP 的 {@code simos.sd.diplomatic-events} 共用这一份形状）。
   *
   * <p>每条 = {@code {eventId,tick,participants,text}}；{@code participants} 按记录时的顺序发（自然语言事件记录的一部分）。
   *
   * <p>★ <b>逐字节可复现</b>：事件按 id 字典序发。★ 过滤（tick / participant）由调用方在视图层之外做，本方法保持"投影 +
   * 排序"两件事，不替调用方定过滤语义。
   */
  public static List<Map<String, Object>> diplomaticEvents(
      Map<DiplomaticEventId, DiplomaticEvent> events) {
    List<DiplomaticEventId> ids = new ArrayList<>(events.keySet());
    ids.sort(Comparator.comparing(DiplomaticEventId::value));
    List<Map<String, Object>> out = new ArrayList<>(ids.size());
    for (DiplomaticEventId id : ids) {
      DiplomaticEvent event = events.get(id);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("eventId", event.id().value());
      view.put("tick", event.tick());
      List<String> participants = new ArrayList<>(event.participants().size());
      for (NationId participant : event.participants()) {
        participants.add(participant.value());
      }
      view.put("participants", participants);
      view.put("text", event.text());
      out.add(view);
    }
    return out;
  }

  /** 单个 {@link CombatState} 的 JSON 形（交战记录的独立视图）。 */
  private static Map<String, Object> combat(
      CombatState state, Combat combat, UnitState units, SimosTimestamp at) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("combatId", state.combatId().value());
    view.put("combatStateId", state.id().value());
    view.put("name", combat == null ? null : combat.name());
    view.put("hex", hexCoord(state.hex()));
    view.put("currentStage", state.currentStage().value());
    view.put("currentStageName", stageName(combat, state.currentStage()));
    view.put("selectedOutcome", state.selectedOutcome().map(CombatOutcomeId::value).orElse(null));
    List<UnitId> participants = participantsOf(state, combat);
    view.put("participants", unitIdValues(participants));
    List<String> atHex = new ArrayList<>();
    for (UnitId unit : participants) {
      if (units.effectivePosition(unit, at).filter(state.hex()::equals).isPresent()) {
        atHex.add(unit.value());
      }
    }
    view.put("participantsAtHex", atHex);
    view.put("participantCount", participants.size());
    view.put("participantsAtHexCount", atHex.size());
    return view;
  }

  /**
   * 单位**所属交战**（无 ⇒ {@code null}）。
   *
   * <p>归属判据 = 参与集合（{@link CombatState#participants()} ∪ 其 {@code Combat.participants()}）含本单位；同一单位
   * 同时在多场交战时取 **combatState id 字典序第一条**（可复现，不靠插入序）。
   *
   * <p>★ {@code atHex} = 本单位此刻的 {@code effectivePosition} 是否恰在交战格——引擎**不要求**交战双方同格，
   * 故"参与了这把交战"与"人就在那把交战的格上"必须分开看（{@code false} 时 GUI 要能明说"不在交战格，交战格在 q,r"）。
   */
  private static Map<String, Object> combatOf(
      UnitId unitId, SdState sd, UnitState units, SimosTimestamp at) {
    for (CombatStateId id : sortedCombatStateIds(sd)) {
      CombatState state = sd.combatStates().get(id);
      Combat combat = sd.combats().get(state.combatId());
      if (!participantsOf(state, combat).contains(unitId)) {
        continue;
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("combatId", state.combatId().value());
      view.put("combatStateId", state.id().value());
      view.put("name", combat == null ? null : combat.name());
      view.put("currentStage", state.currentStage().value());
      view.put("currentStageName", stageName(combat, state.currentStage()));
      view.put("selectedOutcome", state.selectedOutcome().map(CombatOutcomeId::value).orElse(null));
      view.put("hex", hexCoord(state.hex()));
      view.put(
          "atHex", units.effectivePosition(unitId, at).filter(state.hex()::equals).isPresent());
      return view;
    }
    return null;
  }

  /** 参与单位 = 阶段参与 ∪ 交战汇总（字典序、去重）——两层集合都要算"这场交战有谁"。 */
  private static List<UnitId> participantsOf(CombatState state, Combat combat) {
    Set<UnitId> union = new LinkedHashSet<>(state.participants());
    if (combat != null) {
      union.addAll(combat.participants());
    }
    List<UnitId> out = new ArrayList<>(union);
    out.sort(Comparator.comparing(UnitId::value));
    return out;
  }

  /** 交战状态按 id **字典序**——{@code combatStates} 是插入序表，不排序则响应字节不可复现。 */
  private static List<CombatStateId> sortedCombatStateIds(SdState sd) {
    List<CombatStateId> ids = new ArrayList<>(sd.combatStates().keySet());
    ids.sort(Comparator.comparing(CombatStateId::value));
    return ids;
  }

  /** 阶段名（查不到 ⇒ {@code null}，**不拿 id 顶替**）。 */
  private static String stageName(Combat combat, CombatStageId stageId) {
    if (combat == null) {
      return null;
    }
    for (CombatStage stage : combat.stages()) {
      if (stage.id().equals(stageId)) {
        return stage.name();
      }
    }
    return null;
  }

  /** 单位 id 列表的 JSON 形（保序）。 */
  private static List<String> unitIdValues(List<UnitId> ids) {
    List<String> out = new ArrayList<>(ids.size());
    for (UnitId id : ids) {
      out.add(id.value());
    }
    return out;
  }

  /**
   * 单条交战记录视图（阶段 D1 落地、阶段 D4 扩展 / 用户设计 D-009 补裁 + D-010 + D-012，2026-10-02）：**GUI / MCP
   * 读工具共用这一份形状**。
   *
   * <p>字段：{@code id} / {@code kind}（自定义交战状态，自由文本，如"野战"/"轰城"）/ {@code tick}（世界日）/ {@code hex}（交战格）/
   * {@code participants}（参与单位 id，保序）/ {@code text}（自然语言过程与结局）/ {@code
   * stages}（有序阶段：id/name/participants/text/ outcomes/selectedOutcomeId/rollSeed/resolved/losses）/
   * {@code losses}（**已判定阶段**的选中结局损失，扁平行 {@code {stageId,unit,manpower,equipment}}，保序；未判定 ⇒ 空数组）。★
   * 列表读口与详情读口都调本方法 ⇒ "同一资源的两个形状"在结构上不可能（AGENT.md §8.3 的纪律）。
   *
   * <p>★ <b>不发 {@code participantsAtHex} 那类派生量</b>：交战记录是**历史**（写记录时单位在哪就是哪），而不是"此刻谁在哪"—— 与 sd 的
   * {@code CombatState} 不同，这里没有"现算"的一栏。
   */
  public static Map<String, Object> armyCombat(CombatRecord record) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", record.id().value());
    view.put("kind", record.kind());
    view.put("tick", record.tick());
    view.put("hex", hexCoord(record.hex()));
    view.put("participants", unitIdValues(record.participants()));
    view.put("text", record.text());
    List<Map<String, Object>> stages = new ArrayList<>(record.stages().size());
    for (io.mosire.simos.army.CombatStage stage : record.stages()) {
      stages.add(combatStage(stage));
    }
    view.put("stages", List.copyOf(stages));
    List<Map<String, Object>> losses = new ArrayList<>();
    for (io.mosire.simos.army.CombatStage stage : record.stages()) {
      stage
          .selectedOutcome()
          .ifPresent(
              outcome -> {
                for (CombatUnitLoss loss : outcome.losses()) {
                  losses.add(combatLossRow(stage.id().value(), loss));
                }
              });
    }
    view.put("losses", List.copyOf(losses));
    return view;
  }

  /**
   * 阶段视图：{@code
   * {id,name,participants,text,outcomes,selectedOutcomeId,selectedOutcome,rollSeed,resolved,losses}}
   * （{@code selectedOutcome} 与 {@code selectedOutcomeId} 同值：前者是读口惯用名、后者是记录字段名）。
   *
   * <p>★ 本方法与 {@code selectedOutcomeId} 的拼写用**全限定名**：本类同时 import 了 sd 的 {@code CombatStage} /
   * {@code CombatOutcomeId}（另一场交战的视图），简单名会撞（编译期实测）。
   */
  private static Map<String, Object> combatStage(io.mosire.simos.army.CombatStage stage) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", stage.id().value());
    view.put("name", stage.name());
    view.put("participants", unitIdValues(stage.participants()));
    view.put("text", stage.text());
    List<Map<String, Object>> outcomes = new ArrayList<>(stage.outcomes().size());
    for (CombatOutcome outcome : stage.outcomes()) {
      outcomes.add(combatOutcome(outcome));
    }
    view.put("outcomes", List.copyOf(outcomes));
    String selectedOutcomeId =
        stage.selectedOutcomeId().map(io.mosire.simos.army.CombatOutcomeId::value).orElse(null);
    view.put("selectedOutcomeId", selectedOutcomeId);
    // ★ 与 sd 的交战视图同名的键（D4 任务书列的形状）：值就是选中的 outcome id；两个键同值，不各算一份。
    view.put("selectedOutcome", selectedOutcomeId);
    view.put("rollSeed", stage.rollSeed().orElse(null));
    view.put("resolved", stage.resolved());
    List<Map<String, Object>> losses = new ArrayList<>();
    stage
        .selectedOutcome()
        .ifPresent(
            outcome -> {
              for (CombatUnitLoss loss : outcome.losses()) {
                losses.add(combatLossRow(stage.id().value(), loss));
              }
            });
    view.put("losses", List.copyOf(losses));
    return view;
  }

  /** 结局视图：{@code {id,label,weight,losses}}（losses 的形状见 {@link #combatLossRow}）。 */
  private static Map<String, Object> combatOutcome(CombatOutcome outcome) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", outcome.id().value());
    view.put("label", outcome.label());
    view.put("weight", outcome.weight());
    List<Map<String, Object>> losses = new ArrayList<>(outcome.losses().size());
    for (CombatUnitLoss loss : outcome.losses()) {
      losses.add(combatLossRow(null, loss));
    }
    view.put("losses", List.copyOf(losses));
    return view;
  }

  /**
   * 单位损失视图：{@code {stageId?,unit,manpower,equipment,empty}}。{@code amount} 是**有符号增量**（负 = 损失，正 =
   * 补充/新建）， 不是绝对值——与 {@code unit.AdjustComposition} 载荷同形（工具的结算原样提交这一份）。
   */
  private static Map<String, Object> combatLossRow(String stageId, CombatUnitLoss loss) {
    Map<String, Object> row = new LinkedHashMap<>();
    if (stageId != null) {
      row.put("stageId", stageId);
    }
    row.put("unit", loss.unit().value());
    List<Map<String, Object>> manpower = new ArrayList<>(loss.manpower().size());
    for (CompositionDelta delta : loss.manpower()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("type", delta.type());
      entry.put("amount", delta.amount());
      manpower.add(entry);
    }
    List<Map<String, Object>> equipment = new ArrayList<>(loss.equipment().size());
    for (CompositionDelta delta : loss.equipment()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("type", delta.type());
      entry.put("amount", delta.amount());
      equipment.add(entry);
    }
    row.put("manpower", List.copyOf(manpower));
    row.put("equipment", List.copyOf(equipment));
    row.put("empty", loss.empty());
    return row;
  }

  /**
   * 在途移动视图（M7b T2）：无路线 ⇒ {@code null}；有 ⇒ 路线 + **现算**的在途状态。
   *
   * <p>★ {@code status}/{@code currentHex}/{@code nextHex}/{@code remainingMillis} **一律由 {@link
   * UnitMoves#evaluate} 现算**——app 层不重写"预算 / 每格成本 / 付清到哪一段"的算法，那是第二份真相（与位置走 {@code
   * effectivePosition} 同一条纪律）。
   */
  private static Object movement(
      Unit unit, SimosTimestamp at, GameMap map, CalendarService calendars) {
    if (unit.movement().isEmpty()) {
      return null;
    }
    Movement movement = unit.movement().orElseThrow();
    MovementState state = UnitMoves.evaluate(unit, at, map, TerrainMovementCost.INSTANCE);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("route", route(movement.route()));
    view.put("departedAt", timestamp(movement.departedAt(), calendars));
    view.put("speedAtDeparture", movement.speedAtDeparture());
    view.put("mobilityPerMilleAtDeparture", movement.mobilityAtDeparture());
    view.put("status", state.status().name());
    view.put("currentHex", hexCoord(state.currentHex()));
    view.put("nextHex", state.nextHex().map(ApiViews::hexCoord).orElse(null));
    view.put(
        "remainingMillis",
        state.remainingEdgeCostMillis().isPresent()
            ? state.remainingEdgeCostMillis().getAsLong()
            : null);
    return view;
  }

  /** 路线视图：{@code {waypoints:[{q,r}…], path:[{q,r}…]}}（path 是逐格相邻的完整格序列）。 */
  private static Map<String, Object> route(Route route) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("waypoints", hexCoords(route.waypoints()));
    view.put("path", hexCoords(route.path()));
    return view;
  }

  /** 六角坐标列表的 JSON 形（保序）。 */
  private static List<Map<String, Object>> hexCoords(List<HexCoord> coords) {
    List<Map<String, Object>> out = new ArrayList<>(coords.size());
    for (HexCoord coord : coords) {
      out.add(hexCoord(coord));
    }
    return out;
  }

  /**
   * 寻路结果视图（M7b T3）：{@code {reachable, path:[{q,r}…]}}。
   *
   * <p>★ {@code path} 由 {@code PathFinder.findPath} 产出（**逐格相邻、含首尾**）⇒ 可原样当作 {@code unit.PlanRoute}
   * 的 {@code waypoints}（该命令把 waypoints 当 path 用，见 {@code PlanRouteHandler}）。不可达 ⇒ {@code
   * reachable=false} 且 {@code path=[]}（不省略字段，前端一次判空即可）。
   */
  public static Map<String, Object> pathResult(boolean reachable, List<HexCoord> path) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("reachable", reachable);
    view.put("path", hexCoords(reachable ? path : List.of()));
    return view;
  }

  /**
   * ★★ **该格的人口读口**（R1.5 把面打开 + R2 的 T0/T4）：{@code {q,r,at,population,source,groups,labor}} —— GUI 的
   * {@code GET /api/social/population} 与 MCP 读工具 {@code simos.social.population} **共用这一份**（AGENT.md
   * §8.3：不许在路由层/工具层另拼一份）。
   *
   * <p>★★ **R2 的 T0 改了口径**（控制器已裁定）：{@code population} **不再**是"农村序列的取值"，而是 **有批次 ⇒ 批次求和（真值源）；无批次 ⇒
   * 回退旧序列**，并用 {@code source}（{@code batches} / {@code legacySeries}） 标明用的是哪一个（判据与算式在 {@link
   * SocialData#headlinePopulationAt} 一处）。★ 回退不是"兜底逻辑"，而是口径的一部分： 随包 bootstrap 的 {@code
   * worlds/v17levant.json} 与升级前的每条 revision **只有旧序列**，一律读批次会让"世界还没初始化"看起来像"这格没人"。
   *
   * <p>★ **四张面孔一个口径**：本方法、{@code PopulationFacet}（{@code /api/facets}、{@code simos.map.facets}）与
   * MCP 的 {@code simos.social.population} 全部转调同一份派生量 —— R1.5 留下的"facet
   * 报农村序列、端点报批次"那处**同一资源两个形状**就此收口。
   *
   * <p>★ **键序固定**（{@code q,r,at,population,source,groups,labor}；{@code groups} 内 {@code
   * total,urban,rural,ageBrackets,sex}；{@code labor} 内 {@code
   * availableMilli,allocatedMilli,utilizationPerMille,actors}； 年龄档按词表序、性别按词表序、{@code actors} 按
   * (kind,id) 序）：同状态两次响应逐字节相同，是 GUI/MCP 的既有前提。
   *
   * <p>★ **该格没有人口序列 ⇒ 抛**（fail-closed）：调用方（路由 / 读工具）本就在此之前把它折成 {@code NOT_FOUND}， 走不到这里；静默给一个 0
   * 会让"id 拼错 / 格不存在"看起来像"这格没人"。
   *
   * @param at 查询时刻；{@code at.tick()} 同时是年龄档的现算输入（世界日），也是回退旧序列时的取值时刻。
   * @param calendars 世界唯一的历法/气候配置服务：{@code at} 的日期与年龄档的 tick→历法年换算都用**它的时钟** （C6a 起不再有旧占位时钟）；生产路径由
   *     {@code GuiServer}/{@code ToolSupport} 传入。
   * @param economy 经济切片（R2 的 T4：劳动分配读口要从它取"这一格的劳动被哪个主体占了多少"）
   */
  public static Map<String, Object> population(
      SocialData data,
      EconomyData economy,
      HexCoord coord,
      SimosTimestamp at,
      CalendarService calendars) {
    Objects.requireNonNull(calendars, "calendars");
    Map<String, Object> view = hexCoord(coord);
    view.put("at", timestamp(at, calendars));
    PopulationSeries series = data.populations().get(coord);
    if (series == null) {
      throw new IllegalArgumentException("该格没有人口序列: " + coord.q() + "_" + coord.r());
    }
    // ★ R2（T0）：口径与来源**同源产生**（同一个方法返回两件）——先判来源再取值，两处各写一次就会漂。
    PopulationHeadline headline = data.headlinePopulationAt(coord, series, at);
    view.put("population", headline.value());
    view.put("source", headline.source().key());
    CalendarClock clock = calendars.clock();
    Map<String, Object> groups = groupsView(data, coord, at, clock);
    view.put("groups", groups);
    // ★ R2（T4）：劳动分配一维（各主体占用劳动 / 该格可用劳动 / 占用率）。
    view.put("labor", laborView(data, economy, coord));
    // ★★ R4（T3）：**危机红灯** —— 这一格有没有触发生活资料/社会再生产危机，以及**是哪一类**（不是概率）。
    //   ★ 它挂在本读口上（**沿用既有权限判定**：该格有 populations 序列 + 人口可见），不另开更宽的判据。
    List<Map<String, Object>> crisis = new ArrayList<>();
    for (CrisisMonitor.Light light :
        CrisisMonitor.lightsAt(coord, economy, data, at.tick(), clock)) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("kind", light.kind().name());
      entry.put("evidence", new LinkedHashMap<>(light.evidence()));
      crisis.add(entry);
    }
    view.put("crisis", crisis);
    return view;
  }

  /**
   * 旧签名兼容（测试/旧路径）：缺省历法配置；生产路径由 {@code GuiServer}/{@code ToolSupport} 传世界的 {@link CalendarService}。
   */
  public static Map<String, Object> population(
      SocialData data, EconomyData economy, HexCoord coord, SimosTimestamp at) {
    return population(data, economy, coord, at, CalendarService.defaults());
  }

  /** {@code groups} 块（R1.5 的形状，一字不动）：{@code total,urban,rural,ageBrackets,sex}。 */
  private static Map<String, Object> groupsView(
      SocialData data, HexCoord coord, SimosTimestamp at, CalendarClock clock) {
    Map<String, Object> groups = new LinkedHashMap<>();
    UrbanRural urbanRural = data.urbanRuralAt(coord);
    groups.put("total", urbanRural.total());
    groups.put("urban", urbanRural.urban());
    groups.put("rural", urbanRural.rural());
    Map<String, Object> ageBrackets = new LinkedHashMap<>();
    for (Map.Entry<AgeBracket, Long> entry :
        data.ageStructureAt(coord, at.tick(), clock).entrySet()) {
      ageBrackets.put(entry.getKey().key(), entry.getValue());
    }
    groups.put("ageBrackets", ageBrackets);
    Map<String, Object> sex = new LinkedHashMap<>();
    for (Map.Entry<Sex, Long> entry : data.sexRatioAt(coord).entrySet()) {
      sex.put(entry.getKey().name(), entry.getValue());
    }
    groups.put("sex", sex);
    // ★★ R4：**生理压力**一维（"缺粮不直接对应死亡人数，而是累积压力"的读口落点）：人均压力 + 最大值。
    //   ★ 两个数都从这里读得到，且**逐格**给（压力是批次的属性，但读口按格汇总才有用）。
    long stressSum = 0L;
    long stressMax = 0L;
    long stressed = 0L;
    for (PopulationGroup group : data.groupsAt(coord)) {
      stressSum += group.physiologicalStress() * group.count();
      stressMax = Math.max(stressMax, group.physiologicalStress());
      stressed += group.count();
    }
    Map<String, Object> stress = new LinkedHashMap<>();
    stress.put("average", stressed == 0L ? 0L : stressSum / stressed);
    stress.put("max", stressMax);
    groups.put("physiologicalStress", stress);
    // ★★ P1.2：**人口批次 id 读口**（只读 additive）——`social.MovePopulationLots` 需要批次身份才能迁移；
    //   这里把该格的每个批次逐条发出去（id 是身份，不是给人看的名字），并带上位置真值来源（所属家户）。
    //   顺序 = groupsAt 的保序（与状态插入序同序）；空数组也发，读侧一次判空即可。
    List<Map<String, Object>> lots = new ArrayList<>();
    for (PopulationGroup group : data.groupsAt(coord)) {
      Map<String, Object> lot = new LinkedHashMap<>();
      lot.put("id", group.id().value());
      lot.put("count", group.count());
      lot.put("sex", group.sex().name());
      lot.put("ageDays", group.ageDaysAt(at.tick()));
      lot.put("anchorTick", group.anchorTick());
      lot.put("physiologicalStress", group.physiologicalStress());
      lot.put("urban", PopulationLots.isUrban(group));
      lot.put(
          "household",
          data.householdOfLot(group.id()).map(household -> household.id().value()).orElse(null));
      lots.add(lot);
    }
    groups.put("lots", lots);
    return groups;
  }

  /**
   * ★★ **该格的劳动分配读口**（R2 的 T4）：{@code {availableMilli,allocatedMilli,utilizationPerMille,actors}}。
   *
   * <pre>
   * availableMilli        = Σ 该格各批次供给记录的 availableLabor()   // = 毛额 − 已服役 − 已承诺
   * allocatedMilli        = Σ 该格各批次的全部劳动配额
   * utilizationPerMille   = available == 0 ? 0 : allocated × 1000 ÷ available   // 整数、向下取整
   * actors                = [{kind,id,laborMilli}…] 该格各主体收到的劳动之和（**产业**就在其中：actor.id 即产业 id）
   * </pre>
   *
   * <p>★★ **"这一格的劳动被哪个产业占了多少"因此读得出来**（R2 的 T3 明列的判据）：{@code actors} 逐主体列出占用劳动， 而"占用率"把 {@code
   * allocated} 与 {@code available} 并排 —— 同一批人被两个产业各算一次满额时，占用率会**超过 1000‰**
   * （构造期守卫已经不允许这种状态，故它是"状态坏了"的可见信号）。
   *
   * <p>★ **归属靠 social 的批次落点**（{@code residence}），不靠 id 前缀解析：配额的行内只有 {@code group}（不透明 id），
   * 而"这个批次住在哪一格"是 {@code SocialData} 的事 —— 视图层不猜 id 的拼法（那是 {@code PopulationLots} 的私事）。
   *
   * <p>★ **权限**：本块与人口块在**同一个响应**里，判据仍是 R1.5 那一条（该格有 {@code populations} 序列 + 该格人口可见），
   * **不**新开更宽的判据（T4 的原文）。★ economy 切片缺席 ⇒ 装配故障当场炸（与 {@link #economyData} 同口径），不静默给 0。
   *
   * <p>★ **空态**：该格没有任何批次 ⇒ 三个数都是 0、{@code actors} 为空数组（不是缺键）——{@code legacySeries} 那一形态的读口照样成形。
   */
  private static Map<String, Object> laborView(
      SocialData data, EconomyData economy, HexCoord coord) {
    List<PopulationGroup> groups = data.groupsAt(coord);
    // ★★ P2-A A4：可用量 = 该格各家的**每 tick 时间预算**（ClassRow.laborMilli，毫小时）；
    //   批次级供给表已删除（唯一权威是 Social 人口组成 × 系数表）。
    long available = 0L;
    for (ClassRow row : economy.classes().values()) {
      if (row.view().hex().equals(coord)) {
        available += row.laborMilli();
      }
    }
    long allocated = 0L;
    Map<String, long[]> byActor = new TreeMap<>(); // 键 = "KIND|id"（字典序可复现），值 = [劳动量]
    for (LaborAllocation allocation : economy.allocations().values()) {
      if (!belongsTo(coord, data, allocation.group())) {
        continue;
      }
      allocated += allocation.laborMilli();
      String key = allocation.actor().kind().name() + "|" + allocation.actor().id();
      long[] slot = byActor.computeIfAbsent(key, ignored -> new long[1]);
      slot[0] += allocation.laborMilli();
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("availableMilli", available);
    view.put("allocatedMilli", allocated);
    // ★ 占用率 = 已分配 ÷ 可用（整数、向下取整）；可用为 0 ⇒ 0（不做除零，也不臆造 1000‰）。
    view.put("utilizationPerMille", available == 0L ? 0L : allocated * 1000L / available);
    List<Map<String, Object>> actors = new ArrayList<>(byActor.size());
    for (Map.Entry<String, long[]> entry : byActor.entrySet()) {
      int bar = entry.getKey().indexOf('|');
      Map<String, Object> actor = new LinkedHashMap<>();
      actor.put("kind", entry.getKey().substring(0, bar));
      actor.put("id", entry.getKey().substring(bar + 1));
      actor.put("laborMilli", entry.getValue()[0]);
      actors.add(actor);
    }
    view.put("actors", actors);
    return view;
  }

  /** 该批次是不是住在这一格（视图层只从家户取位置，不解析 id 的拼法）。 */
  private static boolean belongsTo(HexCoord coord, SocialData data, PeopleLotId group) {
    return group != null && data.hexOfLot(group).filter(coord::equals).isPresent();
  }

  /**
   * 决策人列表（T5，spec §六.1）：{@code [{id,affiliation,allowedTools,cadence,accessLimit,due}…]}。
   *
   * <p>★ 列表顺序**由服务端决定**（{@link SdQueryService#listDecisionMakers} 按 id 字典序）——本类只做装配，不重排，
   * 否则"同状态两次响应逐字节相同"这条前提会破。
   */
  public static List<Map<String, Object>> decisionMakers(
      List<SdQueryService.DecisionMakerInfo> infos) {
    List<Map<String, Object>> out = new ArrayList<>(infos.size());
    for (SdQueryService.DecisionMakerInfo info : infos) {
      out.add(decisionMaker(info));
    }
    return out;
  }

  /**
   * 单个决策人视图（T5，D13：**复用现有字段，不新增 sd 数据**）。
   *
   * <p>字段：{@code id} / {@code affiliation}（kind + id + 解析出的显示名 + 国家 id + 军队认领的 GOV + 军队的根单位）/
   * {@code allowedTools} / {@code cadence} / {@code accessLimit} 摘要 / {@code due} / {@code
   * lastDirectiveTick} / {@code ticksSinceLast}。
   *
   * <p>★ <b>阶段 12</b>：Army 去 {@code NationId} 后，军队的 {@code nationId} 为 {@code null}，认领主子改看 {@code
   * masterGovUnitId}；Nation 的 {@code nationId} 照旧。两个槽位都显式给（不写空串顶替）。
   *
   * <p>★ **T9 起 {@code due} 是真值**（D7 公式，由 {@link SdQueryService#listDecisionMakers} / {@link
   * SdQueryService#decisionMaker} 算出），不再有占位：首次（无任何 Directive）⇒ {@code true}， {@code
   * lastDirectiveTick}/{@code ticksSinceLast} 为 {@code null}。
   */
  public static Map<String, Object> decisionMaker(SdQueryService.DecisionMakerInfo info) {
    DecisionMaker maker = info.maker();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", maker.id().value());
    Map<String, Object> affiliation = new LinkedHashMap<>();
    affiliation.put("kind", info.affiliationKind());
    affiliation.put("id", info.affiliationId());
    affiliation.put("displayName", info.displayName());
    affiliation.put("nationId", info.nationId());
    affiliation.put("masterGovUnitId", info.masterGovUnitId());
    affiliation.put("rootUnit", info.rootUnit());
    view.put("affiliation", affiliation);
    view.put("allowedTools", sorted(maker.allowedTools()));
    view.put("cadence", maker.decisionCadenceTicks());
    // M11：绑定的 LLM provider 引用（null = 未绑定，不拿空串顶替）。providerId 是不透明 id，sd 不解释其内容。
    view.put("providerId", maker.providerId().orElse(null));
    view.put("accessLimit", accessLimit(maker.accessLimit()));
    // ★ 会话世代与派生出的会话 id（世界事实 → 纯函数派生，见 DecisionAgentRunner.conversationIdOf）：
    //   GM 据此知道"这个人换过几次会话"。派生**走那个唯一拼写点**，不在这里另拼一份（拼错了不报错、只失忆）。
    view.put("conversationGeneration", maker.conversationGeneration());
    view.put("conversationId", DecisionAgentRunner.conversationIdOf(maker));
    SdQueryService.PendingSignal pending = info.pending();
    view.put("due", pending.due());
    view.put("lastDirectiveTick", pending.lastDirectiveTick());
    view.put("ticksSinceLast", pending.ticksSinceLast());
    return view;
  }

  /**
   * 决策记录列表（{@code GET /api/sd/directives}）：每条 = 一条真 {@link Directive} + 取回的执行原文。
   *
   * <p>★ **顺序由服务端决定**（{@link SdQueryService#listDirectives} 按 tick 降序、同 tick 按 id 字典序）——本类只做
   * 装配，不重排，否则"同状态两次响应逐字节相同"这条前提会破（与 {@link #decisionMakers} 同口径）。
   *
   * <p>★ P7c 起 MCP 读口 {@code simos.sd.directives} 也直接调它（工具在 {@code tools.read} 包）⇒ 公开；装配逻辑与 GUI
   * 仍是同一行，不另写一份视图。
   */
  public static List<Map<String, Object>> directives(List<SdQueryService.DirectiveInfo> infos) {
    List<Map<String, Object>> out = new ArrayList<>(infos.size());
    for (SdQueryService.DirectiveInfo info : infos) {
      out.add(directive(info));
    }
    return out;
  }

  /**
   * 单条决策记录视图（字段名**以 sd 域的既有类型为准**，不另起名）： {@code directiveId} / {@code decisionMakerId} / {@code
   * tick} / {@code target} / {@code intentInfoKey} / {@code intentInfo} / {@code commands} / {@code
   * effects} / {@code verdict} / {@code status}。
   *
   * <p>★ {@code target} 是 {@link Address#canonical()}（{@code Directive.target} 是 {@code
   * Optional<Address>}； 无目标 ⇒ {@code null}，**不拿空串顶替**）。
   *
   * <p>★ **{@code intentInfoKey} 与 {@code intentInfo} 两样都报**：前者是 record 里真正存着的东西（INFO 覆盖层的
   * key），后者是照它取回来的原文。取不到原文而只报 key，界面就只能显示一个 key 名（"决策内容看不见"的老问题换个形状回来）。
   */
  static Map<String, Object> directive(SdQueryService.DirectiveInfo info) {
    Directive directive = info.directive();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("directiveId", directive.id().value());
    view.put("decisionMakerId", directive.decisionMakerId().value());
    view.put("tick", directive.tick());
    view.put("target", directive.target().map(Address::canonical).orElse(null));
    view.put("intentInfoKey", directive.intentInfoKey());
    view.put("intentInfo", info.intentInfo());
    List<Map<String, Object>> commands = new ArrayList<>(directive.commands().size());
    for (DirectiveCommand command : directive.commands()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", command.type());
      row.put("payloadJson", command.payloadJson());
      commands.add(row);
    }
    view.put("commands", commands);
    List<String> effects = new ArrayList<>(directive.effects().size());
    for (EffectId effect : directive.effects()) {
      effects.add(effect.value());
    }
    effects.sort(null); // 响应字节可复现（Set 的迭代序不是内容的纯函数）
    view.put("effects", effects);
    view.put("verdict", directive.verdict().map(VerdictId::value).orElse(null));
    view.put("status", directive.status().name());
    return view;
  }

  /**
   * {@code accessLimit} 摘要（T9，原 {@code viewScope} 摘要）：GM 配的**额外限制**本身。
   *
   * <p>★★ **不再"把限制冒充成可见集合"**：旧摘要是 {@code visibleRegions}/{@code visibleHexes} 的**计数**——那时 GM
   * 存的就是绝对可见集合。新语义下真正可见的是 **app 层范围函数现算 ∩ 本限制**（spec §3.1/§4.2），而这个端点**算不出**它 （要读地图与单位状态）。⇒
   * 这里只如实报"限制说了什么"：每个命名空间的前缀**条数**（真档 59223 hex 下前缀可几十条，故给计数而非全量， 与左栏的用途相称）+ 字段级剔除 +
   * 判决披露档。要看**算出来的**范围请走 {@code /api/map/overview?as=<dm>}（那是同一套判定器的输出）。
   */
  private static Map<String, Object> accessLimit(AccessLimit limit) {
    Map<String, Object> view = new LinkedHashMap<>();
    Map<String, Object> counts = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> entry : limit.prefixesByNamespace().entrySet()) {
      counts.put(entry.getKey(), entry.getValue().size());
    }
    view.put("prefixesByNamespace", counts);
    view.put("redactedFields", sorted(limit.redactedFields()));
    view.put("adjudicationDisclosure", limit.adjudicationDisclosure().name());
    return view;
  }

  /**
   * 决策人的**现算可见范围**视图：{@code GET /api/sd/decision-makers/{id}/scope} 的体。
   *
   * <p>★★ **与 {@link #accessLimit} 摘要的分工**：那个报"GM 配了什么"（限制原文的计数），这个报"**算出来收到了多少**"
   * ——限制是**交集**的一半，另一半是范围函数（随世界状态变：国家圈地、军队移动）。GM 要判断"我这条限制管不管用"， 只有这一个端点能回答。
   *
   * <p>★ {@code affiliation} 直接复用 {@link #decisionMaker} 的那一块：两级范围（国家/军队）在前端就靠它分辨， 各拼一份会分叉。
   *
   * <p>★ {@code unparsedPrefixes} **照原样送出**（不吞）：认不出形状的 {@code map} 前缀仍是生效的限制，只是投影不出区域/格 ——吞掉它会让 GM
   * 把"我没看懂"读成"没生效"。
   */
  public static Map<String, Object> decisionScope(
      SdQueryService.DecisionMakerInfo info, StateRef ref, DecisionScopeView view) {
    Map<String, Object> maker = decisionMaker(info);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("decisionMakerId", maker.get("id"));
    body.put("affiliation", maker.get("affiliation"));
    body.put("branch", ref.branch().value());
    body.put("revision", ref.revision().value());
    body.put("visible", visibleScope(view));
    body.put("namespaces", namespaceSummaries(view));
    body.put("unparsedPrefixes", view.unparsedPrefixes());
    return body;
  }

  /** 范围里"看得见什么"（区域 / 格 / 边界 / 单位）——全是**解码好的**事实，前端不再自己算。 */
  private static Map<String, Object> visibleScope(DecisionScopeView view) {
    Map<String, Object> visible = new LinkedHashMap<>();
    List<String> regionIds = new ArrayList<>();
    for (RegionId id : view.regions()) {
      regionIds.add(id.value());
    }
    visible.put("regionIds", regionIds);
    List<List<Integer>> hexes = new ArrayList<>();
    for (HexCoord coord : view.hexes()) {
      hexes.add(List.of(coord.q(), coord.r()));
    }
    visible.put("hexes", hexes);
    visible.put("hexCount", view.hexCount());
    visible.put("offMapHexCount", view.offMapHexCount());
    List<String> unitIds = new ArrayList<>();
    for (UnitId id : view.units()) {
      unitIds.add(id.value());
    }
    visible.put("unitIds", unitIds);
    visible.put(
        "boundingBox",
        view.boundingBox()
            .map(
                box -> {
                  Map<String, Object> out = new LinkedHashMap<>();
                  out.put("minQ", box.minQ());
                  out.put("maxQ", box.maxQ());
                  out.put("minR", box.minR());
                  out.put("maxR", box.maxR());
                  return (Object) out;
                })
            .orElse(null));
    return visible;
  }

  /** 各命名空间的前缀摘要（含"不限"标记）——GM 据此看"限制原文长什么样"。 */
  private static Map<String, Object> namespaceSummaries(DecisionScopeView view) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, DecisionScopeView.NamespaceSummary> entry :
        view.namespaces().entrySet()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("unrestricted", entry.getValue().unrestricted());
      item.put("prefixCount", entry.getValue().prefixes().size());
      item.put("prefixes", entry.getValue().prefixes());
      out.put(entry.getKey(), item);
    }
    return out;
  }

  /**
   * 集合型字段的**字典序**副本：{@code allowedTools}/{@code redactedFields} 在状态里是 {@link java.util.Set}
   * （语义无序，其迭代序不是内容的纯函数——M2 Task 5 实测）⇒ 进响应前排序，让"同一状态两次响应逐字节相同"跨 JVM 也成立。
   */
  private static List<String> sorted(Set<String> values) {
    List<String> out = new ArrayList<>(values);
    out.sort(Comparator.naturalOrder());
    return out;
  }

  /**
   * GM 口工具使用记录的只读视图（工具面 M4，2026-09-25 起 GUI 与 MCP 共用这一份）：{@code {tool,ok,code,atEpochMs}}。
   *
   * <p>★ **它不是世界状态**：来源是进程内的 {@link GmToolUsage} 有界记录（进程重启即空），故与 GUI 的 {@code GET
   * /api/gm/tool-usage} 一样**不接 redaction**；MCP 侧只给 GM 桶读这条。
   *
   * <p>★ 顺序由调用方给定（{@link GmToolUsage#recent()} 已是"最新在前"的不可变快照）——本层**不重排**， 与 GUI 逐字同形。
   */
  public static List<Map<String, Object>> gmToolUsage(List<GmToolUsage.Entry> entries) {
    List<Map<String, Object>> views = new ArrayList<>(entries.size());
    for (GmToolUsage.Entry entry : entries) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("tool", entry.tool());
      item.put("ok", entry.ok());
      item.put("code", entry.code());
      item.put("atEpochMs", entry.atEpochMs());
      views.add(item);
    }
    return List.copyOf(views);
  }

  /** map 切片（缺席或类型不对是装配故障，不是"没有候选"）。 */
  public static GameMap gameMap(SimulationState state) {
    Snapshot snapshot =
        state
            .module("map")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 map 模块切片——装配故障，不是\"没有候选\""));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalArgumentException("map 模块切片不是 MapSnapshot：" + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }

  /** unit 切片。 */
  public static UnitState unitState(SimulationState state) {
    Snapshot snapshot =
        state
            .module("unit")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 unit 模块切片——装配故障，不是\"没有候选\""));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalArgumentException(
          "unit 模块切片不是 UnitSnapshot：" + snapshot.getClass().getName());
    }
    return unitSnapshot.state();
  }

  /**
   * sd 切片（交战记录 / 参与单位的唯一只读来源；铁律 3）。
   *
   * <p>缺席或类型不对是装配故障，不是"没有候选"（与 {@link #unitState} 同口径）。
   */
  public static SdState sdState(SimulationState state) {
    Snapshot snapshot =
        state
            .module("sd")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 sd 模块切片——装配故障，不是\"没有候选\""));
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalArgumentException("sd 模块切片不是 SdSnapshot：" + snapshot.getClass().getName());
    }
    return sdSnapshot.state();
  }

  /**
   * army 切片（阶段 D1 / 用户设计 D-012）：交战记录的唯一只读来源；GUI / MCP 读工具 / 可见性判定共用这一份提取。
   *
   * <p>★ 缺席或类型不对是装配故障，不是"没有候选"（与 {@link #sdState}/{@link #unitState} 同口径），当场抛。
   */
  public static ArmyData armyData(SimulationState state) {
    Snapshot snapshot =
        state
            .module("army")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 army 模块切片——装配故障"));
    if (!(snapshot instanceof ArmySnapshot armySnapshot)) {
      throw new IllegalArgumentException(
          "army 模块切片不是 ArmySnapshot：" + snapshot.getClass().getName());
    }
    return armySnapshot.data();
  }

  /** social 切片（GUI / MCP 读工具 / 渲染层共用同一份提取）。 */
  public static SocialData socialData(SimulationState state) {
    Snapshot snapshot =
        state
            .module("social")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 social 模块切片——装配故障，不是\"没有候选\""));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalArgumentException(
          "social 模块切片不是 SocialSnapshot：" + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }

  /** economy 切片（R2a 起 GUI 与 MCP 读工具共用同一份提取；缺席或类型不对是装配故障）。 */
  public static EconomyData economyData(SimulationState state) {
    Snapshot snapshot =
        state
            .module("economy")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 economy 模块切片——装配故障，不是\"没有候选\""));
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalArgumentException(
          "economy 模块切片不是 EconomySnapshot：" + snapshot.getClass().getName());
    }
    return economySnapshot.data();
  }

  /** actor 切片（S1 阶段 2 起；形制同 {@link #economyData}——GUI 与 MCP 读工具共用同一份提取，缺席或类型不对是装配故障）。 */
  public static ActorData actorData(SimulationState state) {
    Snapshot snapshot =
        state
            .module("actor")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 actor 模块切片——装配故障，不是\"没有候选\""));
    if (!(snapshot instanceof ActorSnapshot actorSnapshot)) {
      throw new IllegalArgumentException(
          "actor 模块切片不是 ActorSnapshot：" + snapshot.getClass().getName());
    }
    return actorSnapshot.data();
  }

  /**
   * ★★ <b>本格各家庭行的"本周期劳动量"</b>（每日千分劳动 × 周期天数；R3a 从{@code 旧结算引擎（R3a 已删除）} 原样搬来）。
   *
   * <p>★ 口径与量纲一字未改：{@code participationAdjustedLaborMilli} 是参与率折算后的**每日**劳动；{@code LABOR_AMOUNT}
   * 一族量的是**本周期**劳动 ⇒ 乘周期天数。R3a 之后旧结算引擎已删除，本方法只服务这一条读口。
   */
  private static Map<HouseholdId, Long> laborOfCohort(
      Map<HouseholdId, ClassRow> rows, HexCoord location, long cycleDays) {
    Map<HouseholdId, Long> byCohort = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : rows.entrySet()) {
      if (!entry.getValue().view().hex().equals(location)) {
        continue;
      }
      long rowLabor = entry.getValue().participationAdjustedLaborMilli() * cycleDays;
      if (rowLabor <= 0L) {
        continue;
      }
      byCohort.put(entry.getKey(), rowLabor);
    }
    return byCohort;
  }

  /** 家户账键 = 家户身份（P2-A §13.3：一本账，键不再带格）。 */
  private static GoodsAccountKey accountKeyOf(HouseholdId household) {
    return new GoodsAccountKey(household);
  }

  /**
   * 家户账本的显示格：账户键不再带格（P2-A）⇒ 从 economy 的家户行视图派生（A1 起两侧同一 {@code HouseholdId}）。
   * 定位不到（无经济行的家户）⇒ 抛（读口不静默造一个假格）。
   */
  private static HexCoord householdHex(EconomyData economy, HouseholdId household) {
    ClassRow row = economy.classes().get(household);
    if (row == null) {
      throw new IllegalStateException(
          "家户没有经济行（无法定位账户所在格；A1 起 Social/Economy 应共用同一 HouseholdId）: " + household);
    }
    return row.view().hex();
  }

  /** {@link #householdHex(EconomyData, HouseholdId)} 的 state 形态（读口便利重载）。 */
  private static HexCoord householdHex(SimulationState state, HouseholdId household) {
    return householdHex(economyData(state), household);
  }

  /** {@link #householdHex(EconomyData, HouseholdId)} 的 social 形态（region 汇总没有 economy 上下文时用）。 */
  private static HexCoord householdHex(SocialData social, HouseholdId household) {
    var householdData = social.households().get(household);
    if (householdData == null
        || !(householdData.location()
            instanceof io.mosire.simos.social.api.household.HouseholdLocation.Hex hex)) {
      throw new IllegalStateException("家户不在 HEX 上或不存在（无法定位账户所在格）: " + household);
    }
    return hex.hex();
  }
}

package io.mosire.simos.app.gui;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.app.access.DecisionScopeView;
import io.mosire.simos.app.crisis.CrisisMonitor;
import io.mosire.simos.app.decision.DecisionAgentRunner;
import io.mosire.simos.app.gm.GmToolUsage;
import io.mosire.simos.app.query.SdQueryService;
import io.mosire.simos.app.time.EconomyDayFeed;
import io.mosire.simos.app.time.MarketReadoutAssembly;
import io.mosire.simos.app.time.OwnershipBooks;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.market.MarketUnfilledReason;
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
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionCandidate;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.time.ClassTransition;
import io.mosire.simos.economy.time.ClassTransitionFeed;
import io.mosire.simos.economy.time.DebtCapacityBook;
import io.mosire.simos.economy.time.EconomySettlement;
import io.mosire.simos.economy.time.EntryOutcome;
import io.mosire.simos.economy.time.EntryOutcomeFeed;
import io.mosire.simos.economy.time.HouseholdClassRule;
import io.mosire.simos.economy.time.HouseholdCondition;
import io.mosire.simos.economy.time.MarketReadout;
import io.mosire.simos.economy.time.MarketReport;
import io.mosire.simos.economy.time.ProductionLedger;
import io.mosire.simos.economy.time.ProductionSettlement;
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
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveCommand;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationHeadline;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.social.population.Sex;
import io.mosire.simos.social.population.UrbanRural;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
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
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

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

  private ApiViews() {}

  /** {@code {q,r}}（六角坐标的 JSON 形；身份仍是类型，这个串只在边界）。 */
  static Map<String, Object> hexCoord(HexCoord coord) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("q", coord.q());
    view.put("r", coord.r());
    return view;
  }

  /** {@code {tick,calendarLabel}}。 */
  static Map<String, Object> timestamp(SimosTimestamp at) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("tick", at.tick());
    view.put("calendarLabel", at.calendarLabel().orElse(null));
    return view;
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
   * 时间轴节点清单（spec §3.1，M7 T1）：{@code {branch,head,nodes:[…]}}。节点**不含 {@code changesetJson}**（体积大且对
   * UI 无用），{@code parent} 为 {@code {branch,revision}} 或 {@code null}。
   */
  public static Map<String, Object> timeline(BranchId branch, long head, List<RevisionRow> rows) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("branch", branch.value());
    view.put("head", head);
    List<Map<String, Object>> nodes = new ArrayList<>(rows.size());
    for (RevisionRow row : rows) {
      Map<String, Object> node = new LinkedHashMap<>();
      node.put("revision", row.revision().value());
      node.put("tick", row.timestamp().tick());
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
        Optional.empty(),
        -1L,
        "读口没有 SimulationState 上下文（3 参重载）⇒ 拿不到 tick 与进程内市场报告");
  }

  /** ★★ M2.7：GUI / MCP 的正式入口（从状态装配焦点区市场读数与进程内报告；与 {@link MarketReadoutAssembly} 同源）。 */
  public static Map<String, Object> economyHex(HexCoord coord, SimulationState state) {
    EconomyData data = economyData(state);
    ActorData actors = actorData(state);
    MarketReadoutAssembly.MarketReadoutContext context =
        MarketReadoutAssembly.contextFor(state, coord);
    return economyHex(
        coord,
        data,
        actors,
        context.readout(),
        context.report(),
        context.tick(),
        context.readoutUnavailable());
  }

  private static Map<String, Object> economyHex(
      HexCoord coord,
      EconomyData data,
      ActorData actors,
      Optional<MarketReadout> readout,
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
    for (GoodsAccount account : accountsAt(actors, coord)) {
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
      // ★★ M1.7：给养义务读口要"按本周期实际劳动量" —— 走结算侧的**同一个函数**（{@code EconomySettlement.laborOfCohort}），
      //   不在视图层另写一套（口径两处各写一遍 = 读到的义务与实付的义务会漂开）。
      Map<HouseholdId, Long> cycleLabor =
          EconomySettlement.laborOfCohort(data.classes(), coord, industry.cycleDays());
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
    view.put("moneyByActorKind", moneyByActorKind(actors));
    view.put("moneyByHouseholdClass", moneyByHouseholdClass(data, actors));
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
    Optional<ProductionLedger> dayLedger =
        data.meta().isPresent()
            ? EconomyDayFeed.last(data.meta().orElseThrow().mapId(), tick)
            : Optional.empty();
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
    for (GoodsAccount account : accountsAt(actors, coord)) {
      HouseholdId key = householdOfActorAtHex.get(account.key().owner());
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
    view.put("debtCapacity", debtCapacityBlockView(classKeys, debtCapacities));
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
    // ★★ M2.7：**焦点区的逐区逐商品市场读数**（与 MCP / GUI 共用同一份视图；进程内报告缺失时 match=null 且具名）。
    //   ★ 挂进同一个 economyHex 而不新开路由/工具：GUI 与 MCP 的读口数量不变（工具面测试不需要改名单）。
    view.put("marketReadout", readout.map(ApiViews::marketReadoutView).orElse(null));
    if (readout.isEmpty()) {
      view.put("marketReadoutUnavailable", readoutUnavailable);
    } else if (!readoutUnavailable.isEmpty()) {
      view.put("marketReadoutUnavailable", readoutUnavailable);
    }
    // ★★ M0.3：**逐格粮食诊断**（七项里今天做得到的四项 + 三项"做不到"的具名占位）。
    //   ★ 它挂在**同一个视图**里（不另开读口）：报表脚本按格 dump 的就是这一份，多一栏即多一栏读数。
    //   ★ M2.7 复评：logisticsGap 在拿到进程内报告时就可算；productionSelfSufficiency / paymentInstrumentGap
    //     仍具名不可算（见方法注释），绝不填 0。
    view.put(
        "grainDiagnosis",
        grainDiagnosis(coord, data, actors, grainStock, goods, actorMoneyTotal, report));
    return view;
  }

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
            + "（见 MarketReadout 的 effectiveDemandMilli / needsButCannotAffordMilli 两栏）⇒ 实际能成交的量 ≤ 它们");
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

  /**
   * ★★ <b>M2.7：{@link MarketReadout} → JSON 视图</b>（GUI 与 MCP 共用；逐区逐商品）。
   *
   * <p>★ <b>进程内/重启即失</b>由读数对象自己的 {@code unavailable} 标注原样带出；{@code match = null} 不是 0。
   */
  private static Map<String, Object> marketReadoutView(MarketReadout readout) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("tick", readout.tick());
    view.put(
        "lastSettledDay",
        readout.lastSettledDay().isPresent() ? readout.lastSettledDay().getAsLong() : null);
    view.put("priceMode", readout.priceMode().value());
    view.put("adaptivePricingEnabled", readout.adaptivePricingEnabled());
    view.put("crossRegionSettlementImmediate", readout.crossRegionSettlementImmediate());
    List<Map<String, Object>> priceUpdates = new ArrayList<>(readout.priceUpdates().size());
    for (MarketReport.PriceUpdate update : readout.priceUpdates()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("anchor", update.anchor().toString());
      item.put("commodity", update.commodity().value());
      item.put("previousPriceMilli", update.previousPriceMilli());
      item.put("nextPriceMilli", update.nextPriceMilli());
      priceUpdates.add(item);
    }
    view.put("priceUpdates", priceUpdates);
    view.put("provenance", readout.provenance());
    view.put("unavailable", readout.unavailable());
    List<Map<String, Object>> regions = new ArrayList<>(readout.regions().size());
    for (MarketReadout.RegionReadout region : readout.regions()) {
      Map<String, Object> regionView = new LinkedHashMap<>();
      regionView.put("regionId", region.regionId());
      regionView.put("anchor", region.anchor().toString());
      regionView.put("radiusHex", region.radiusHex());
      regionView.put("numeraire", region.numeraire().value());
      List<String> members = new ArrayList<>(region.members().size());
      for (HexCoord member : region.members()) {
        members.add(member.toString());
      }
      regionView.put("members", members);
      List<Map<String, Object>> commodities = new ArrayList<>(region.commodities().size());
      for (MarketReadout.CommodityReadout commodity : region.commodities()) {
        Map<String, Object> commodityView = new LinkedHashMap<>();
        commodityView.put("commodity", commodity.commodity().value());
        commodityView.put("referencePriceMilli", commodity.referencePriceMilli());
        commodityView.put("bidPriceMilli", commodity.bidPriceMilli());
        commodityView.put("askPriceMilli", commodity.askPriceMilli());
        commodityView.put("supplyMilli", commodity.supplyMilli());
        commodityView.put("naturalNeedMilli", commodity.naturalNeedMilli());
        commodityView.put("naturalNeedWindow", commodity.naturalNeedWindow());
        commodityView.put("cycleNaturalNeedMilli", commodity.cycleNaturalNeedMilli());
        commodityView.put("effectiveDemandMilli", commodity.effectiveDemandMilli());
        commodityView.put("needsButCannotAffordMilli", commodity.needsButCannotAffordMilli());
        commodityView.put(
            "needsButCannotAffordHouseholds", commodity.needsButCannotAffordHouseholds());
        commodityView.put(
            "match", commodity.match().map(ApiViews::commodityMatchView).orElse(null));
        commodities.add(commodityView);
      }
      regionView.put("commodities", commodities);
      regions.add(regionView);
    }
    view.put("regions", regions);
    return view;
  }

  /** 撮合结果读数的 JSON 形（{@code landedPriceMilli} 无成交 ⇒ null；四张原因分布逐档给）。 */
  private static Map<String, Object> commodityMatchView(MarketReadout.CommodityMatchReadout match) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("tradedMilli", match.tradedMilli());
    view.put(
        "landedPriceMilli",
        match.landedPriceMilli().isPresent() ? match.landedPriceMilli().getAsLong() : null);
    view.put("freightMilli", match.freightMilli());
    view.put("lossMilli", match.lossMilli());
    view.put("unusedCapacityMilli", match.unusedCapacityMilli());
    view.put("capacityBottleneck", match.capacityBottleneck());
    view.put("sellerOutcomeCount", match.sellerOutcomeCount());
    view.put("sellerSelfUsableQtyMilli", match.sellerSelfUsableQtyMilli());
    view.put("sellerOutcompetedCount", match.sellerOutcompetedCount());
    view.put("sellerOutcompetedQtyMilli", match.sellerOutcompetedQtyMilli());
    view.put("sellerPriceMissingCount", match.sellerPriceMissingCount());
    view.put(
        "cheapestSellerUnitCostMilli",
        match.cheapestSellerUnitCostMilli().isPresent()
            ? match.cheapestSellerUnitCostMilli().getAsLong()
            : null);
    view.put(
        "dearestSellerUnitCostMilli",
        match.dearestSellerUnitCostMilli().isPresent()
            ? match.dearestSellerUnitCostMilli().getAsLong()
            : null);
    view.put("buyerOutcomeCount", match.buyerOutcomeCount());
    view.put("buyerStockSufficientCount", match.buyerStockSufficientCount());
    view.put("buyerNoBudgetCount", match.buyerNoBudgetCount());
    view.put("buyerGapMilli", match.buyerGapMilli());
    view.put("unfilledBuyCounts", reasonCountsView(match.unfilledBuyCounts()));
    view.put("unfilledSellCounts", reasonCountsView(match.unfilledSellCounts()));
    view.put("unfilledBuyQuantities", reasonCountsView(match.unfilledBuyQuantities()));
    view.put("unfilledSellQuantities", reasonCountsView(match.unfilledSellQuantities()));
    return view;
  }

  /** 未成交原因分布：键 = 规范字面量（小写下划线），保序（原因档声明序已在读数组件里保序）。 */
  private static Map<String, Long> reasonCountsView(Map<MarketUnfilledReason, Long> counts) {
    Map<String, Long> view = new LinkedHashMap<>();
    for (Map.Entry<MarketUnfilledReason, Long> entry : counts.entrySet()) {
      view.put(entry.getKey().value(), entry.getValue());
    }
    return view;
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
        "provenance", "关账日 EconomySettlement 写回 ClassRow.view 时投递的进程内审计；day = 最近一次不晚于当前 tick 的关账日");
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
    for (ProductionSettlement.Arrear arrear : ledger.arrears()) {
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
      "当日 ProductionLedger 是进程内瞬态（重启/换进程/还没结算即失）：租与工资欠款读不到；缺失不是 0，" + "而是\"这一轮没有可读账本\"";

  /** ★ S3：阶层写回审计读不到的具名原因（唯一拼写点）。 */
  private static final String CLASS_TRANSITIONS_PROCESS_ONLY =
      "阶层写回的审计是进程内瞬态（ClassTransitionFeed；不落盘）：重启/换进程/本轮推进没跨关账日时"
          + "读不到\"从哪一档跳来\"；当前阶层真值仍在 classes[].slot 与 classifications[] 两栏";

  /** ★ R4-E2b：候选进入评估结果读不到的具名原因（唯一拼写点）。 */
  private static final String ENTRY_OUTCOMES_PROCESS_ONLY =
      "候选进入评估结果是进程内瞬态（EntryOutcomeFeed；不落盘、不新增 EconomyData 组件）：重启/换进程/还没结算时"
          + "读不到\"哪些户被评估、为什么没进\"；unit 与份额的真值仍在 units[] 与 assetShares[] 两栏";

  /** ★ E1/E5a：某家户没有 ClassStanding 时的具名原因（唯一拼写点；不是 0，也不是伪造一个默认归属）。 */
  private static final String CLASS_STANDING_UNAVAILABLE =
      "该家户没有 ClassStanding 记录（economy.classStandings 为空或未覆盖此户）：E1 起新状态为空时旧路径仍以 "
          + "ClassRow.view 为准；E5a 不产生任何阶层变动，不伪造 current/original/consecutiveDebtStressCycles";

  /** ★★ E5b：清算审计读不到的具名原因（唯一拼写点；不落盘、只在同一 tick 的当日 ledger 里可读）。 */
  private static final String LIQUIDATION_AUDIT_PROCESS_ONLY =
      "清算/阶层下滑审计是进程内瞬态（当日 ProductionLedger.liquidationAudits；不落盘）：重启/换进程/"
          + "本轮推进没跨关账日时读不到\"处置了什么、跳过了什么\"；状态真值仍在 assetShares/pledges/debtContracts/"
          + "classStandings/crisisSignals 五栏";

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

  /** 千分率的分母（口粮折算用；与 {@code EconomySettlement.MILLI_PER_GRAIN} 同值，此处只服务读口）。 */
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
    List<GoodsAccount> atHex = accountsAt(actors, coord);
    List<Map<String, Object>> accounts = new ArrayList<>(atHex.size());
    Map<String, Long> actorGoodsTotal = new TreeMap<>();
    Map<String, Long> actorMoneyTotal = new TreeMap<>();
    for (GoodsAccount account : atHex) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("actor", account.key().owner().toString());
      entry.put("kind", account.key().owner().kind().name());
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
    view.put("moneyByActorKind", moneyByActorKind(actors));
    view.put("moneyByHouseholdClass", moneyByHouseholdClass(economy, actors));
    // ★★ E4b：本格每户/合计 debtCapacity（与 economyHex 同一份读数与窗口；见 DebtCapacityBlock）。
    view.put("debtCapacity", debtCapacityBlock(economy, actors, coord));
    view.put("rowGoodsTotal", rowGoodsTotal);
    return view;
  }

  /**
   * 该格上的全部库存账（**保序**：按 owner 的规范串字典序）—— {@link #economyOwnership} 与 {@link #economyHex}
   * 读的是**同一个集合**（后者的 {@code goods} 就是前者 {@code actorGoodsTotal} 的来源）。
   */
  private static List<GoodsAccount> accountsAt(ActorData actors, HexCoord coord) {
    List<GoodsAccount> atHex = new ArrayList<>();
    for (GoodsAccount account : actors.accounts().values()) {
      if (account.key().location().equals(coord)) {
        atHex.add(account);
      }
    }
    atHex.sort(Comparator.comparing(account -> account.key().owner().toString()));
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
   * 而"按什么量"用的是**结算侧的同一个** {@code EconomySettlement.laborOfCohort}（M1.8 的折扣后口径）⇒ 读到的义务与实付的应付**同源**。
   * ★ 缺 relation ⇒ 空表（"没有规则 ⇒ 全归 residualOwner"的等价路径，不是读不到）；{@code subsistencePromised} = 逐商品 Σ
   * 应付，也正是 M2 保留算式经 {@link SubsistenceObligation#retentionOf} 封顶时用的"承诺额"。
   *
   * @param relation 该产业的生产关系（{@code EconomyData.relations}；可为 null = 没有规则）
   * @param laborOfCohort 本周期各 cohort 的劳动量（由 {@code EconomySettlement.laborOfCohort} 算好传入；不得为 null）
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
    //   {@link OwnershipBooks#accountKeyOf}（本层不复述家户 id / 账户键的形状）；账本缺席 ⇒ 空表（读口不抛）。
    GoodsAccount account =
        actors.accounts().get(OwnershipBooks.accountKeyOf(key, row.view().hex()));
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
   * ★★ E4b：{@link #economyOwnership} 用的 debtCapacity 块（与 {@link #economyHex} 同一份逐户读数与窗口标注；
   * 那里另有一份可挂进各 class 行的逐户 map，故不重复构建视图）。
   */
  private static Map<String, Object> debtCapacityBlock(
      EconomyData data, ActorData actors, HexCoord coord) {
    List<HouseholdId> householdKeys = cohortKeysAt(data, coord);
    Map<ActorRef, HouseholdId> householdOfActorAtHex = new LinkedHashMap<>();
    for (HouseholdId key : householdKeys) {
      householdOfActorAtHex.put(HouseholdActors.of(key), key);
    }
    Map<HouseholdId, Long> grainStockByHousehold = new LinkedHashMap<>();
    for (GoodsAccount account : accountsAt(actors, coord)) {
      HouseholdId key = householdOfActorAtHex.get(account.key().owner());
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
    return debtCapacityBlockView(householdKeys, capacities);
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
   * <p>★★ **V4 起 {@code deaths} 在默认路径上恒 0**：致死率默认 {@code
   * EconomySettlement.FAMINE_MORTALITY_PER_MILLE = 0‰}（"先不做饿死人系统"）⇒ **缺口照记**（{@code unmetNeed} 非 0
   * 是常态），但**不死人**。字段照发不删（旋钮还在）； 读到的 0 是**结论**，不是"没在记"。 ★ 它由端到端用例钉住："缺粮 ⇒ 读口读到非 0 的 {@code
   * unmetNeed}、且 {@code deaths == 0}、人口一个不少" （{@code EconomySettlementEndToEndTest}）。
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
          byKind.computeIfAbsent(account.key().owner().kind().name(), ignored -> new TreeMap<>()),
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
      if (account.key().owner().kind() != ActorKind.HOUSEHOLD) {
        continue;
      }
      HouseholdId household = HouseholdActors.householdOf(account.key().owner());
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
   * <p>★ <b>窗口</b>：只在读数 tick 与最近一次结算 tick 相同时可读（{@link EconomyDayFeed}）；不落盘。 ★ <b>单位</b>：{@code
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
   */
  static Map<String, Object> mapHex(
      HexCoord coord,
      HexCell cell,
      String terrain,
      List<FacetEntry> facets,
      List<RegionId> regions,
      TerrainType terrainType,
      Map<EdgeRef, EdgeTags> edges) {
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
      UnitState units, SimosTimestamp at, GameMap map, SdState sd) {
    List<Map<String, Object>> out = new ArrayList<>(units.units().size());
    for (Unit unit : units.units().values()) {
      out.add(unit(unit, units, at, map, sd));
    }
    return out;
  }

  /**
   * 单位详情：冻结字段 + {@code parent}（head 时刻）+ **位置**（head 时刻）+ **在途移动视图** + **编制视图**（v2）+
   * **所属交战**（2026-09-24，无 ⇒ {@code null}）。
   *
   * <p>★★ **两边共用这一份**（AGENT.md §8.3 的纪律）：MCP 读工具（{@code simos.unit.list} / {@code simos.unit.get}）
   * 经 {@code ToolSupport.unit} 直接调本方法 ⇒ 新字段**一处加、两面同形**（原先两处各写一份，加字段就得记得改两处）。
   *
   * <p>★ {@code combat} 是**真实交战记录**（不是 GUI 现推的"同格多军队"）：见 {@link #combatOf}。
   */
  public static Map<String, Object> unit(
      Unit unit, UnitState units, SimosTimestamp at, GameMap map, SdState sd) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", unit.id().value());
    view.put("name", unit.name());
    view.put("member", unit.member());
    view.put("equipment", new LinkedHashMap<>(unit.equipment()));
    view.put("speed", unit.speed());
    view.put("mobilityPerMille", unit.mobilityPerMille());
    // ★ B9（用户 2026-09-23 实测）：单位**自身**状态（MOVING/RESTING/ENGAGED）此前两处视图都没发 ⇒
    //   前端"这个单位是什么"里缺"状态"一项。这里是领域真值（`Unit.status()` 的普通字段）的原样透出。
    view.put("status", unit.status().name());
    view.put("parent", unit.parent().valueAt(at).map(UnitId::value).orElse(null));
    view.put(
        "position", units.effectivePosition(unit.id(), at).map(ApiViews::hexCoord).orElse(null));
    view.put("movement", movement(unit, at, map));
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
   * 在途移动视图（M7b T2）：无路线 ⇒ {@code null}；有 ⇒ 路线 + **现算**的在途状态。
   *
   * <p>★ {@code status}/{@code currentHex}/{@code nextHex}/{@code remainingMillis} **一律由 {@link
   * UnitMoves#evaluate} 现算**——app 层不重写"预算 / 每格成本 / 付清到哪一段"的算法，那是第二份真相（与位置走 {@code
   * effectivePosition} 同一条纪律）。
   */
  private static Object movement(Unit unit, SimosTimestamp at, GameMap map) {
    if (unit.movement().isEmpty()) {
      return null;
    }
    Movement movement = unit.movement().orElseThrow();
    MovementState state = UnitMoves.evaluate(unit, at, map, TerrainMovementCost.INSTANCE);
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("route", route(movement.route()));
    view.put("departedAt", timestamp(movement.departedAt()));
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
   * @param at 查询时刻；{@code at.tick()} 同时是年龄档的现算输入（世界日），也是回退旧序列时的取值时刻
   * @param economy 经济切片（R2 的 T4：劳动分配读口要从它取"这一格的劳动被哪个主体占了多少"）
   */
  public static Map<String, Object> population(
      SocialData data, EconomyData economy, HexCoord coord, SimosTimestamp at) {
    Map<String, Object> view = hexCoord(coord);
    view.put("at", timestamp(at));
    PopulationSeries series = data.populations().get(coord);
    if (series == null) {
      throw new IllegalArgumentException("该格没有人口序列: " + coord.q() + "_" + coord.r());
    }
    // ★ R2（T0）：口径与来源**同源产生**（同一个方法返回两件）——先判来源再取值，两处各写一次就会漂。
    PopulationHeadline headline = data.headlinePopulationAt(coord, series, at);
    view.put("population", headline.value());
    view.put("source", headline.source().key());
    Map<String, Object> groups = groupsView(data, coord, at);
    view.put("groups", groups);
    // ★ R2（T4）：劳动分配一维（各主体占用劳动 / 该格可用劳动 / 占用率）。
    view.put("labor", laborView(data, economy, coord));
    // ★★ R4（T3）：**危机红灯** —— 这一格有没有触发生活资料/社会再生产危机，以及**是哪一类**（不是概率）。
    //   ★ 它挂在本读口上（**沿用既有权限判定**：该格有 populations 序列 + 人口可见），不另开更宽的判据。
    List<Map<String, Object>> crisis = new ArrayList<>();
    for (CrisisMonitor.Light light : CrisisMonitor.lightsAt(coord, economy, data, at.tick())) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("kind", light.kind().name());
      entry.put("evidence", new LinkedHashMap<>(light.evidence()));
      crisis.add(entry);
    }
    view.put("crisis", crisis);
    return view;
  }

  /** {@code groups} 块（R1.5 的形状，一字不动）：{@code total,urban,rural,ageBrackets,sex}。 */
  private static Map<String, Object> groupsView(
      SocialData data, HexCoord coord, SimosTimestamp at) {
    Map<String, Object> groups = new LinkedHashMap<>();
    UrbanRural urbanRural = data.urbanRuralAt(coord);
    groups.put("total", urbanRural.total());
    groups.put("urban", urbanRural.urban());
    groups.put("rural", urbanRural.rural());
    Map<String, Object> ageBrackets = new LinkedHashMap<>();
    for (Map.Entry<AgeBracket, Long> entry : data.ageStructureAt(coord, at.tick()).entrySet()) {
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
    long available = 0L;
    for (PopulationGroup group : groups) {
      LaborSupply supply = economy.laborSupply().get(group.id());
      if (supply != null) {
        available += supply.availableLabor();
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

  /** 该批次是不是住在这一格（视图层只读 {@code groups} 的落点，不解析 id 的拼法）。 */
  private static boolean belongsTo(HexCoord coord, SocialData data, PeopleLotId group) {
    PopulationGroup lot = data.groups().get(group);
    return lot != null && coord.equals(lot.residence());
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
   * <p>字段：{@code id} / {@code affiliation}（kind + id + 解析出的显示名 + 国家 id + 军队的根单位）/ {@code
   * allowedTools} / {@code cadence} / {@code accessLimit} 摘要 / {@code due} / {@code
   * lastDirectiveTick} / {@code ticksSinceLast}。
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
   */
  static List<Map<String, Object>> directives(List<SdQueryService.DirectiveInfo> infos) {
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
}

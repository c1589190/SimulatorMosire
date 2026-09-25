package io.mosire.simos.app.gui;

import io.mosire.simos.app.access.DecisionScopeView;
import io.mosire.simos.app.decision.DecisionAgentRunner;
import io.mosire.simos.app.gm.GmToolUsage;
import io.mosire.simos.app.query.SdQueryService;
import io.mosire.simos.core.timeline.RevisionRow;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.model.AllocationRule;
import io.mosire.simos.economy.model.AssetKind;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
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
 */
public final class ApiViews {

  /** 粮食商品 id（与 {@code EconomySeeder.COMMODITY_GRAIN} / 结算侧同字面量：粮 = 1 公斤，库存按毫粮）。 */
  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);

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
   * <p>★ **逐值、可复现**：该格的产业按 {@code IndustryId} 字典序、每产业的阶层行按**槽位 id** 字典序、库存/需求表按键字典序
   * 发出——状态里的插入序不是内容的纯函数，跟着它走响应字节会抖。
   *
   * <p>★ **该格的产业怎么认出来**：经 {@link IndustryHexKeys#at}（"产业属于哪一格"的**唯一拼写点**）；本方法**不**自己拼/拆 id。
   *
   * <p>★ **未激活**（§6.6：{@code meta} 空）或该格没有产业 ⇒ 各聚合量为 0、{@code industries} 为空数组——**不 404**：
   * "这一格没有经济数据"与"这一格不存在"是两件事，前者要能在界面上看见。
   */
  public static Map<String, Object> economyHex(HexCoord coord, EconomyData data) {
    Map<String, Object> view = hexCoord(coord);
    view.put("activated", data.meta().isPresent());
    long population = 0L;
    long laborMilli = 0L;
    long landMilliMu = 0L;
    long money = 0L;
    long debtPrincipal = 0L;
    long debtCount = 0L;
    long grainStock = 0L;
    long grainDailyConsumption = 0L;
    Map<String, Long> goods = new TreeMap<>();
    List<Map<String, Object>> industries = new ArrayList<>();
    for (IndustryId id : IndustryHexKeys.at(data.industries(), coord.q(), coord.r())) {
      Industry industry = data.industries().get(id);
      List<Map<String, Object>> classes = new ArrayList<>();
      for (ClassKey key : classKeysOf(data, id)) {
        ClassRow row = data.classes().get(key);
        population += row.population();
        laborMilli += row.laborMilli();
        landMilliMu += row.meansOfProduction().getOrDefault(AssetKind.LAND, 0L);
        money += row.money();
        mergeInto(goods, row.goods());
        grainStock += row.goods().getOrDefault(GRAIN, 0L);
        grainDailyConsumption += row.naturalNeeds().getOrDefault(GRAIN, 0L);
        for (DebtId debtId : row.debts()) {
          Debt debt = data.debts().get(debtId);
          if (debt != null) {
            debtCount++;
            debtPrincipal += debt.principal();
          }
        }
        classes.add(classRowView(key, row, data.flows().get(key)));
      }
      industries.add(industryView(industry, classes));
    }
    view.put("population", population);
    view.put("laborMilli", laborMilli);
    view.put("landMilliMu", landMilliMu);
    view.put("goods", goods);
    // ★ R3a：该格粮库存合计与日耗合计（"看变化"的两个直接读数；单位 = 毫粮）。
    //   ★★ **日耗读的是结算写下的 {@code ClassRow.naturalNeeds}**（V5；spec §八.8 的"读数与结算同源"）：
    //     结算每天把**当日需求**（累计口粮的逐日差分）写进该字段，读口只是把它加起来 ⇒ 同一面板上的"人口"与"日耗"
    //     不会再分叉。★ 代价如实记：读口显示的是"**最近一次结算那天**"的需求（两次结算之间不刷新；本方法入参没有 tick，
    //     物理上复算不出结算当天那个数）。
    view.put("grainStock", grainStock);
    view.put("grainDailyConsumption", grainDailyConsumption);
    view.put("money", money);
    view.put("debtCount", debtCount);
    view.put("debtPrincipal", debtPrincipal);
    view.put("industries", industries);
    return view;
  }

  /** 该产业在本格的阶层行键，**按槽位 id 字典序**（可复现；见 {@link #economyHex}）。 */
  private static List<ClassKey> classKeysOf(EconomyData data, IndustryId industry) {
    List<ClassKey> keys = new ArrayList<>();
    for (ClassKey key : data.classes().keySet()) {
      if (key.industry().equals(industry)) {
        keys.add(key);
      }
    }
    keys.sort(Comparator.comparing(key -> key.slot().value()));
    return keys;
  }

  /**
   * 一个产业（§3.1 的读侧：制度 / 周期 / 进度 / **V7 配方** / 分配函数 / 槽位）与该产业的阶层行。
   *
   * <p>★★ **R3（T6）起把配方发出来**（{@code capacityPerUnit} / {@code inputPerUnit} / {@code laborPerUnit} /
   * {@code outputPerUnit} + 本周期实际扣到的投入）：这几项原来**不在任何读口里**，而"每单位**什么**"正是 R3 变成数据的那一维 —— 不读出来，"每座作坊产
   * N 匹布"在报表里就只是数字。★ {@code inputPerUnit} 走 {@link Industry#inputPerUnit()}（={@code
   * cycleInputPerUnit} 的合计），**不在视图层另算一遍**。
   */
  private static Map<String, Object> industryView(
      Industry industry, List<Map<String, Object>> classes) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", industry.id().value());
    view.put("name", industry.name());
    view.put("regime", industry.regime().value());
    view.put("cycleDays", industry.cycleDays());
    view.put("progressDays", industry.progressDays());
    Map<String, Object> capacity = new TreeMap<>();
    for (Map.Entry<AssetKind, Long> entry : industry.capacityPerUnit().entrySet()) {
      capacity.put(entry.getKey().name(), entry.getValue());
    }
    view.put("capacityPerUnit", capacity);
    view.put("inputPerUnit", sortedCommodities(industry.inputPerUnit()));
    view.put("laborPerUnit", industry.laborPerUnit());
    view.put("outputPerUnit", sortedCommodities(industry.outputPerUnit()));
    view.put("cycleInputUsedMilli", sortedCommodities(industry.cycleInputUsedMilli()));
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
    view.put("classes", classes);
    return view;
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
   * 一个阶层行（§3.2 逐字段：人口 / 有效劳动 / 投入率 / 土地 / 库存 / 货币 / 债务 / 两类需求）；{@code flow} = 本期流水（R3a，可为 null）。
   */
  private static Map<String, Object> classRowView(ClassKey key, ClassRow row, FlowRow flow) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("slot", key.slot().value());
    view.put("population", row.population());
    view.put("laborMilli", row.laborMilli());
    view.put("participationPerMille", row.participationPerMille());
    view.put("landMilliMu", row.meansOfProduction().getOrDefault(AssetKind.LAND, 0L));
    view.put("goods", sortedCommodities(row.goods()));
    view.put("money", row.money());
    List<String> debts = new ArrayList<>(row.debts().size());
    for (DebtId debt : row.debts()) {
      debts.add(debt.value());
    }
    view.put("debts", debts);
    view.put("naturalNeeds", sortedCommodities(row.naturalNeeds()));
    view.put("effectiveDemand", sortedCommodities(row.effectiveDemand()));
    view.put("flow", flowView(flow));
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
    view.put("repaid", flow == null ? 0L : flow.repaid());
    view.put("netSurplus", flow == null ? 0L : flow.netSurplus());
    view.put("unmetNeed", flow == null ? 0L : flow.unmetNeed());
    view.put("deaths", flow == null ? 0L : flow.deaths());
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
}

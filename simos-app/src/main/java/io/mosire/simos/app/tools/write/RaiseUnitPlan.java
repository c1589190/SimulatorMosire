package io.mosire.simos.app.tools.write;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.EconomyCommodities;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code simos.unit.raiseUnit} 的<b>纯推导</b>（辖区阶段 8 / 计划 §5）：从一份 {@link SimulationState} 与参数算出
 * {@link Plan}——<b>不碰 {@link io.mosire.agentlib.tool.ToolContext}、不碰 {@code CoreSimos}</b>，preview
 * 与 apply 因此共用同一份语义（工具只负责读态、组批、折叠结局）。
 *
 * <p>★★ <b>它为什么不是一条命令</b>：组军同时动 {@code unit}（新单位）、{@code actor}（家户出粮/钱 + 新单位国库入账）、 {@code
 * social}（批次出人）、{@code sd}（行动记录）四片，单条命令只能落一个命名空间。本类只推导"现在能不能落、各项来源是谁"， 组批与提交在 {@link
 * RaiseUnitTool}。
 *
 * <p>★★ <b>共享同一份分摊</b>：人力与粮/钱来源一律走 {@link RegionAllocations}（阶段 6/8 唯一的家户账瀑布与人力瀑布）—— 口径（可支配 = 余额 −
 * 冻结、MALE + {@code AgeBracket.ADULT}、降序瀑布、不足整条拒）与 {@code simos.unit.levyRegion} 逐字同源，本类不写第二份排序或减法。
 *
 * <p>★★ <b>推导口径（逐条对应计划 §5）</b>：
 *
 * <ol>
 *   <li><b>输入校验</b>：{@code newUnitId}/{@code name}/{@code regionId} 非空白；{@code manpower ≥ 1}；{@code
 *       grain/money ≥ 0}； {@code at} 非空；{@code speed ≥ 1}、{@code mobilityPerMille ∈
 *       [1,1000]}、{@code equipment} 键非空且值 ≥ 0 ——后三条的下界以领域权威 {@code Unit} 的构造期守卫为准（{@code
 *       unit.CreateUnit} 的载荷字段 {@code speed} / {@code mobilityPerMille} / {@code
 *       equipment}），不是本类另立的宽口径；
 *   <li><b>新 id 不得复用</b>：{@code unit} 切片里已有 {@code newUnitId} ⇒ 具名拒（与 {@code UnitOperations.create}
 *       同口径）；
 *   <li><b>region + 落点</b>：region 必须存在于 {@code GameMap.regions()}，且 {@code at} 在其 {@code hexes()}
 *       里（<b>不默认、 不猜中心</b>）；
 *   <li><b>parent</b>：若给 ⇒ 必须存在且<b>当刻有效位置与 {@code at} 同格</b>（{@code unit.CreateUnit} 的硬要求：
 *       只有同格的单位才能编入同一支）；新单位自身位置恒为 {@code at}；
 *   <li><b>三项来源</b>：人力 = region 内 MALE + 成年档批次；粮 / 钱 = region 各 hex 上 HOUSEHOLD 账；不足 ⇒ 整条具名拒
 *       （不部分、不截断）；
 *   <li><b>国库落点 = {@code at}</b>：新单位国库账 = {@code ActorRef(UNIT, newUnitId)} @ {@code at}，与 levy /
 *       债同族；
 *   <li><b>产出</b>：新 {@code Unit} 的人力表 = 单条 {@code {type:"人员", amount:实抽人力}}；粮 / 钱进新单位国库；来源逐键进
 *       {@code actor.AdjustAccounts} / {@code social.SeedGroups}，行动记录进 {@code
 *       sd.PutInfo}；<b>不另造第二份账</b>。
 * </ol>
 *
 * <p>★ <b>为什么载荷组装也在这个类</b>：四条命令的载荷都是这份计划的纯函数（照 {@code LevyRegionPlan} 的拆法）——把载荷留在 工具里会多出一条"视图与载荷各读一次
 * Plan 字段"的缝，漏一个字段没有症状。载荷一律 {@link LinkedHashMap} 保序构造、 {@link ToolSupport#json} 序列化 ⇒ 同状态同参数逐字节相同。
 *
 * <p>★ <b>确定性 / 保序不可变</b>：本类不碰墙钟、不用随机量（{@code tick} 是状态 meta 的函数）；来源表由 {@link RegionAllocations}
 * 冻住，equipment 冻在 Plan 的赋值处（{@code List.copyOf} + 保序转表，不用 {@code Map.copyOf}）。
 */
final class RaiseUnitPlan {

  /** 新单位的命令类型（{@link io.mosire.simos.unit.spi.CreateUnitHandler#type()} 的字面量；handler 未导出常量）。 */
  static final String CREATE_UNIT_TYPE = "unit.CreateUnit";

  /** 家户出账 + 新单位国库入账的命令类型（与 {@code AdjustAccountsHandler.type()} 同字面；该 handler 未导出常量）。 */
  static final String ADJUST_ACCOUNTS_TYPE = "actor.AdjustAccounts";

  /** 人力来源整组覆盖的命令类型（与 {@code SeedGroupsHandler.type()} 同字面）。 */
  static final String SEED_GROUPS_TYPE = "social.SeedGroups";

  /** 行动记录的命令类型（{@code PutInfoHandler.type()}）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 粮的商品 id（{@link EconomyCommodities#GRAIN} 的<b>唯一</b>字面量来源；本类不另写 {@code "grain"}）。 */
  private static final CommodityId GRAIN = EconomyCommodities.GRAIN;

  /**
   * 从地方抽取人力时，新单位人力表的 type 字面量（D3a 无受控词表，取自然语义）。
   *
   * <p>★ 为什么是固定 {@code "人员"} 而不是让调用方给 type：本工具抽取的是"region 内 MALE + 成年档"的人口，不是某个兵种；
   * 抽来的人尚未分兵种，给它一个中性的自然语义类型最诚实。需要任意的多类型人力搭配时，走 {@code simos.unit.create} 窄工具或 {@code
   * simos.army.formatUnit}。
   */
  static final String DEFAULT_MANPOWER_TYPE = "人员";

  private RaiseUnitPlan() {}

  /**
   * 纯推导入口（见类注的七条口径）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param newUnitId 新单位 id（不得与既有单位重复）
   * @param name 新单位名（非空白）
   * @param regionId 抽人 / 抽粮钱的区域（必须存在，且 {@code at} 在它的 hex 集里）
   * @param at 新单位落点 = 国库落点（不得为 null；不默认、不猜中心）
   * @param manpower 人力请求量（&ge; 1）
   * @param grain 粮请求量（&ge; 0；0 = 本维度整段跳过）
   * @param money 钱请求量（&ge; 0；0 = 本维度整段跳过）
   * @param speed 新单位速度（&ge; 1；领域 {@code Unit} 的硬约束）
   * @param mobilityPerMille 新单位机动性（&ge; 1 且 &le; 1000；领域 {@code Unit} 的硬约束 + 千分上限）
   * @param equipment 新单位装备（可空表；键非空、值 &ge; 0）
   * @param parent 父单位 id（可选；给了就必须存在且当刻有效位置与 {@code at} 同格）
   * @throws IllegalArgumentException 任一具名拒（工具折成 {@code BAD_REQUEST}）
   */
  // ★ 测试/旧路径：全缺省儒略历时钟；生产路径由 CalendarService.clock() 传入。
  static Plan plan(
      SimulationState state,
      String newUnitId,
      String name,
      String regionId,
      HexCoord at,
      long manpower,
      long grain,
      long money,
      int speed,
      int mobilityPerMille,
      Map<String, Integer> equipment,
      Optional<String> parent) {
    return plan(
        state,
        newUnitId,
        name,
        regionId,
        at,
        manpower,
        grain,
        money,
        speed,
        mobilityPerMille,
        equipment,
        parent,
        CalendarClock.julianDefault());
  }

  /**
   * 生产入口：历法时钟由调用方传入（本类的人力年龄档判定只认这台钟）。
   *
   * @param clock 历法时钟（非空；生产路径 = CalendarService.clock()）
   */
  static Plan plan(
      SimulationState state,
      String newUnitId,
      String name,
      String regionId,
      HexCoord at,
      long manpower,
      long grain,
      long money,
      int speed,
      int mobilityPerMille,
      Map<String, Integer> equipment,
      Optional<String> parent,
      CalendarClock clock) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(parent, "parent");
    requireNonBlank(newUnitId, "newUnitId");
    requireNonBlank(name, "name");
    requireNonBlank(regionId, "regionId");
    if (at == null) {
      throw new IllegalArgumentException("at 不得为 null（必须给新单位落点，不默认、不猜中心）");
    }
    if (manpower < 1L) {
      throw new IllegalArgumentException("manpower 必须 ≥ 1: " + manpower);
    }
    requireNonNegative(grain, "grain");
    requireNonNegative(money, "money");
    if (speed < 1) {
      throw new IllegalArgumentException("speed 必须 ≥ 1（unit.CreateUnit 领域约束）: " + speed);
    }
    if (mobilityPerMille < 1 || mobilityPerMille > 1000) {
      throw new IllegalArgumentException(
          "mobilityPerMille 必须在 [1,1000]（unit.CreateUnit 领域约束 + 千分上限）: " + mobilityPerMille);
    }
    requireEquipment(equipment);

    SimosTimestamp timestamp = state.meta().timestamp();
    long tick = timestamp.tick();
    UnitState units = ToolSupport.unitState(state);
    UnitId id = UnitId.parse(newUnitId);
    if (units.units().containsKey(id)) {
      throw new IllegalArgumentException("单位 id 已存在（newUnitId 不得复用）: " + newUnitId);
    }
    GameMap map = ToolSupport.gameMap(state);
    Region region = map.regions().get(RegionId.parse(regionId));
    if (region == null) {
      throw new IllegalArgumentException("地图里没有区域: " + regionId + "（先 map.CreateRegion，或改指到既有区域）");
    }
    if (!region.hexes().contains(at)) {
      throw new IllegalArgumentException(
          "at "
              + hexText(at)
              + " 不在区域 "
              + regionId
              + " 的 hex 集里（不默认、不猜中心；先 map.UpdateRegion 把落点纳入区域）");
    }
    parent.ifPresent(parentId -> requireParentAt(units, parentId, at, timestamp));

    // ★ 粮/钱/人力的来源一律走 RegionAllocations（阶段 6/8 共享的唯一瀑布）；requested = 0 的维度整段跳过。
    ActorData actors = ApiViews.actorData(state);
    RegionAllocations.AccountAllocation grainAllocation =
        grain == 0L
            ? RegionAllocations.AccountAllocation.skipped()
            : RegionAllocations.allocateAccounts(
                actors, region, "粮", grain, account -> AvailableStock.available(account, GRAIN));
    RegionAllocations.AccountAllocation moneyAllocation =
        money == 0L
            ? RegionAllocations.AccountAllocation.skipped()
            : RegionAllocations.allocateAccounts(
                actors,
                region,
                "钱",
                money,
                account -> AvailableStock.available(account, MoneyVocabulary.SILVER_CURRENCY));
    RegionAllocations.ManpowerAllocation manpowerAllocation =
        RegionAllocations.allocateManpower(
            ToolSupport.socialData(state), region, tick, manpower, clock);
    return new Plan(
        newUnitId,
        name,
        regionId,
        at,
        tick,
        manpower,
        grainAllocation,
        moneyAllocation,
        manpowerAllocation,
        speed,
        mobilityPerMille,
        ToolSupport.compositionEntries(equipment),
        parent);
  }

  // ── 引用 / 数值校验小件 ─────────────────────────────────────────────────────────────

  /** 父单位必须存在且当刻有效位置与 {@code at} 同格（{@code unit.CreateUnit} 的硬要求；新单位自身位置恒为 {@code at}）。 */
  private static void requireParentAt(
      UnitState units, String parentId, HexCoord at, SimosTimestamp timestamp) {
    requireNonBlank(parentId, "parent");
    UnitId parentKey = UnitId.parse(parentId);
    if (!units.units().containsKey(parentKey)) {
      throw new IllegalArgumentException("父单位不存在: " + parentId + "（parent 必须指向既有单位）");
    }
    Optional<HexCoord> parentAt = units.effectivePosition(parentKey, timestamp);
    if (parentAt.isEmpty() || !parentAt.get().equals(at)) {
      throw new IllegalArgumentException(
          "父单位 "
              + parentId
              + " 当刻有效位置 "
              + parentAt.map(RaiseUnitPlan::hexText).orElse("(不可确定)")
              + " 与新单位落点 "
              + hexText(at)
              + " 不同格：unit.CreateUnit 要求同格才能编入同一支");
    }
  }

  /** 装备形状校验：可为空表；键非空、值非负（值已在工具面折成 int）。 */
  private static void requireEquipment(Map<String, Integer> equipment) {
    Objects.requireNonNull(equipment, "equipment");
    for (Map.Entry<String, Integer> entry : equipment.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("equipment 的键不得空白");
      }
      if (entry.getValue() == null || entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "equipment 的值必须 ≥ 0: " + entry.getKey() + "=" + entry.getValue());
      }
    }
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " 必须是非空文本");
    }
  }

  private static void requireNonNegative(long value, String name) {
    if (value < 0L) {
      throw new IllegalArgumentException(name + " 不得为负: " + value);
    }
  }

  /** 格的可读文本（拒因与行动记录共用；格式不与任何资源路径语法绑定）。 */
  private static String hexText(HexCoord at) {
    return "(" + at.q() + "," + at.r() + ")";
  }

  /** 行内 owner 视图（{@code {kind,id}}；与 AdjustAccounts 载荷的 owner 同形）。 */
  private static Map<String, Object> actorRefView(ActorRef owner) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("kind", owner.kind().name());
    view.put("id", owner.id());
    return view;
  }

  /**
   * 单位 canonical 地址（{@code unit:<unitId>}）：只经 {@link Address#parse} → {@link Address#canonical()}（与
   * {@code LevyRegionTool}/{@code RejectDirectiveTool} 同款），行动记录的唯一拼写点。
   */
  static String unitAddress(String unitId) {
    Objects.requireNonNull(unitId, "unitId");
    return Address.parse(ToolSupport.UNIT_NAMESPACE + ":" + unitId).canonical();
  }

  // ── Plan：四片载荷与视图材料 ─────────────────────────────────────────────────────────

  /**
   * 一份组军计划（全部字段是状态的纯函数）。
   *
   * @param unitId 新单位 id
   * @param name 新单位名
   * @param regionId 来源区域
   * @param at 新单位落点 = 国库落点
   * @param tick 推导时的世界日（行动记录用）
   * @param manpowerCount 新单位实抽人数（= 新单位 manpower 表中 {@value #DEFAULT_MANPOWER_TYPE} 的 amount）
   * @param grain 粮来源分摊（requested = 0 = 本维度整段跳过）
   * @param money 钱来源分摊（requested = 0 = 本维度整段跳过）
   * @param manpower 人力来源分摊（manpower ≥ 1 ⇒ 恒有实际来源）
   * @param speed 新单位速度（&ge; 1）
   * @param mobilityPerMille 新单位机动性（[1,1000]）
   * @param equipment 新单位装备（输入 map 按其迭代序转成有序表；缺省空表）
   * @param parent 父单位 id（可选；给了必与 {@code at} 同格）
   */
  record Plan(
      String unitId,
      String name,
      String regionId,
      HexCoord at,
      long tick,
      long manpowerCount,
      RegionAllocations.AccountAllocation grain,
      RegionAllocations.AccountAllocation money,
      RegionAllocations.ManpowerAllocation manpower,
      int speed,
      int mobilityPerMille,
      List<CompositionEntry> equipment,
      Optional<String> parent) {

    Plan {
      requireNonBlank(unitId, "unitId");
      requireNonBlank(name, "name");
      requireNonBlank(regionId, "regionId");
      if (at == null) {
        throw new IllegalArgumentException("at 不得为 null");
      }
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      if (manpowerCount < 1L) {
        throw new IllegalArgumentException("manpowerCount 必须 ≥ 1: " + manpowerCount);
      }
      Objects.requireNonNull(grain, "grain");
      Objects.requireNonNull(money, "money");
      Objects.requireNonNull(manpower, "manpower");
      if (manpower.requested() != manpowerCount) {
        throw new IllegalArgumentException(
            "manpower.requested 必须等于 manpowerCount: "
                + manpower.requested()
                + " vs "
                + manpowerCount);
      }
      if (speed < 1) {
        throw new IllegalArgumentException("speed 必须 ≥ 1: " + speed);
      }
      if (mobilityPerMille < 1 || mobilityPerMille > 1000) {
        throw new IllegalArgumentException("mobilityPerMille 必须在 [1,1000]: " + mobilityPerMille);
      }
      Objects.requireNonNull(parent, "parent");
      parent.ifPresent(parentId -> requireNonBlank(parentId, "parent"));
      // ★ 装备冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的 Collections.unmodifiableList）；
      //   输入 map 已由 plan() 的 requireEquipment 校过，这里按迭代序转成有序条目表。
      equipment = List.copyOf(Objects.requireNonNull(equipment, "equipment"));
    }

    /** 新单位的人力表：单条 {@value RaiseUnitPlan#DEFAULT_MANPOWER_TYPE}（抽取来的人尚未分兵种）。 */
    List<CompositionEntry> manpowerEntries() {
      return List.of(new CompositionEntry(DEFAULT_MANPOWER_TYPE, manpowerCount));
    }

    /** 是否需要落 {@code actor.AdjustAccounts}（粮 / 钱任一 &gt; 0）。 */
    boolean hasGrainOrMoney() {
      return grain.requested() > 0L || money.requested() > 0L;
    }

    /** 本工具提交的命令类型（按批内固定顺序；preview 视图与 apply 组批共用同一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(4);
      types.add(CREATE_UNIT_TYPE);
      if (hasGrainOrMoney()) {
        types.add(ADJUST_ACCOUNTS_TYPE);
      }
      types.add(SEED_GROUPS_TYPE);
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /**
     * {@code unit.CreateUnit} 载荷（字段名逐字照 handler 的 {@code UnitPayloads}：{@code
     * id/name/position/manpower/equipment/speed/mobilityPerMille/parent?}）。
     *
     * <p>★ {@code jurisdiction} 不在载荷里：{@code CreateUnitHandler} 对新建单位一律取 {@code Optional.empty()}
     * （新单位尚无管辖），本工具不发明第二个字段。
     */
    String createUnitPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", unitId);
      payload.put("name", name);
      payload.put("position", ToolSupport.hexCoord(at));
      payload.put("manpower", ToolSupport.compositionView(manpowerEntries()));
      payload.put("equipment", ToolSupport.compositionView(equipment));
      payload.put("speed", speed);
      payload.put("mobilityPerMille", mobilityPerMille);
      parent.ifPresent(parentId -> payload.put("parent", parentId));
      return ToolSupport.json(payload);
    }

    /**
     * {@code actor.AdjustAccounts} 载荷：各来源家户账的<b>负增量</b> + 新单位国库一条<b>正增量</b>。
     *
     * <p>★★ 粮与钱<b>按 {@code (owner,格)} 合并</b>：同一本账同时供粮与供钱时只能出现一条（该命令明令拒同键重复）。条目顺序 = 粮来源瀑布序 →
     * 仅钱的来源瀑布序（首次出现的位置保留）→ 国库（恒在最后），是内容的纯函数。
     */
    String adjustAccountsPayloadJson() {
      if (!hasGrainOrMoney()) {
        throw new IllegalStateException("组军批不自洽：无粮/钱请求却要组装 actor.AdjustAccounts 载荷");
      }
      LinkedHashMap<GoodsAccountKey, Long> grainByKey = new LinkedHashMap<>();
      for (RegionAllocations.AccountSource source : grain.sources()) {
        grainByKey.put(new GoodsAccountKey(source.owner(), source.at()), -source.amount());
      }
      LinkedHashMap<GoodsAccountKey, Long> moneyByKey = new LinkedHashMap<>();
      for (RegionAllocations.AccountSource source : money.sources()) {
        moneyByKey.put(new GoodsAccountKey(source.owner(), source.at()), -source.amount());
      }
      LinkedHashSet<GoodsAccountKey> order = new LinkedHashSet<>(grainByKey.keySet());
      order.addAll(moneyByKey.keySet());
      List<Map<String, Object>> entries = new ArrayList<>(order.size() + 1);
      for (GoodsAccountKey key : order) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("owner", actorRefView(key.owner()));
        entry.put("q", key.location().q());
        entry.put("r", key.location().r());
        if (grainByKey.containsKey(key)) {
          Map<String, Object> goods = new LinkedHashMap<>();
          goods.put(GRAIN.toString(), grainByKey.get(key));
          entry.put("goods", goods);
        }
        if (moneyByKey.containsKey(key)) {
          Map<String, Object> moneyMap = new LinkedHashMap<>();
          moneyMap.put(MoneyVocabulary.SILVER_CURRENCY.toString(), moneyByKey.get(key));
          entry.put("money", moneyMap);
        }
        entries.add(entry);
      }
      // ★ 新单位国库账户一条正增量 @ at：粮 / 钱各自 +requested（分配不变量保证 Σ扣减 == requested，逐值相等）。
      Map<String, Object> treasury = new LinkedHashMap<>();
      treasury.put("owner", actorRefView(new ActorRef(ActorKind.UNIT, unitId)));
      treasury.put("q", at.q());
      treasury.put("r", at.r());
      if (grain.requested() > 0L) {
        Map<String, Object> goods = new LinkedHashMap<>();
        goods.put(GRAIN.toString(), grain.requested());
        treasury.put("goods", goods);
      }
      if (money.requested() > 0L) {
        Map<String, Object> moneyMap = new LinkedHashMap<>();
        moneyMap.put(MoneyVocabulary.SILVER_CURRENCY.toString(), money.requested());
        treasury.put("money", moneyMap);
      }
      entries.add(treasury);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("entries", entries);
      return ToolSupport.json(payload);
    }

    /**
     * {@code social.SeedGroups} 载荷：每个被动批次一条<b>整组覆盖</b>，必须带原 {@code ageDays}/{@code anchorTick} 与
     * {@code stress} 保真（否则重写会把压力静默清零）；{@code count} 取扣后、可为 0。
     */
    String seedGroupsPayloadJson() {
      List<Map<String, Object>> entries = new ArrayList<>(manpower.sources().size());
      for (RegionAllocations.GroupSource source : manpower.sources()) {
        PopulationGroup group = source.group();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", group.id().value());
        entry.put("q", source.at().q());
        entry.put("r", source.at().r());
        entry.put("sex", group.sex().name());
        entry.put("count", source.countAfter());
        // ★ 保真三件：锚点年龄 / 锚点 tick / 生理压力——整组覆盖不重新解释这批人。
        entry.put("ageDays", group.ageAtAnchorDays());
        entry.put("anchorTick", group.anchorTick());
        entry.put("stress", group.physiologicalStress());
        entries.add(entry);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("entries", entries);
      return ToolSupport.json(payload);
    }

    /**
     * {@code sd.PutInfo} 的 {@code value}（JSON <b>字符串</b>；字段序固定：unit/region/at/三项数量/来源计数/reason）。
     */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("unitId", unitId);
      value.put("regionId", regionId);
      value.put("at", ToolSupport.hexCoord(at));
      value.put("manpower", ToolSupport.compositionView(manpowerEntries()));
      value.put("manpowerRequested", manpower.requested());
      value.put("grain", grain.requested());
      value.put("money", money.requested());
      Map<String, Object> sourceCounts = new LinkedHashMap<>();
      sourceCounts.put("grain", grain.sources().size());
      sourceCounts.put("money", money.sources().size());
      sourceCounts.put("manpower", manpower.sources().size());
      value.put("sourceCounts", sourceCounts);
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireNonBlank(reason, "reason");
      return "组军 "
          + unitId
          + "（"
          + name
          + "，tick "
          + tick
          + "）：region="
          + regionId
          + "，落点 "
          + hexText(at)
          + "，人力 "
          + manpower.requested()
          + "（批次 "
          + manpower.sources().size()
          + "）、粮 "
          + grain.requested()
          + "（来源 "
          + grain.sources().size()
          + "）、钱 "
          + money.requested()
          + "（来源 "
          + money.sources().size()
          + "）；reason="
          + reason;
    }
  }
}

package io.mosire.simos.unit.tools.write;

import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.tools.UnitToolAllocations;
import io.mosire.simos.unit.tools.UnitToolCommandTypes;
import io.mosire.simos.unit.tools.UnitToolPerspective;
import io.mosire.simos.unit.tools.UnitToolReads;
import io.mosire.simos.unit.tools.UnitToolViews;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.unit.raiseUnit} 的<b>纯推导</b>（辖区阶段 8 / 计划 §5）：从一份 {@link SimulationState} 与参数算出
 * {@link Plan}——<b>不碰 {@link io.mosire.agentlib.tool.ToolContext}、不碰 {@code CoreSimos}</b>，preview
 * 与 apply 因此共用同一份语义（工具只负责读态、组批、折叠结局）。
 *
 * <p>★★ <b>它为什么不是一条命令</b>：组军同时动 {@code unit}（新单位）、{@code actor}（家户出粮/钱 + 新单位国库入账 + 新家户账户）、{@code
 * social}（批次出人）、{@code economy}（新家户经济行登记）、{@code sd}（行动记录）五片， 单条命令只能落一个命名空间。本类只推导"现在能不能落、各项来源是谁"，
 * 组批与提交在 {@link RaiseUnitTool}。
 *
 * <p>★★ <b>共享同一份分摊</b>：人力的唯一选人层 = app 的 {@code HouseholdManpowerAllocator}（share-aware 的 Social
 * 家户份额瀑布：MALE + {@code AgeBracket.ADULT}、家户/lot 全序、同一 lot 可被多户按份额持有）；粮 / 钱仍走 app 的 {@code
 * RegionAllocations}（阶段 6/8 唯一的家户账瀑布：可支配 = 余额 − 冻结、降序瀑布、不足整条拒）——两者经 {@link UnitToolAllocations}
 * 注入（{@code simos-actor} 在 unit 的禁列里，历法装配也在组合根）。本类不写第二份排序、过滤或减法。
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
 *   <li><b>三项来源</b>：人力 = region 内 MALE + 成年档的 Social 家户份额（share-aware；同一 lot 多户持有各自成候选）； 粮 / 钱 =
 *       region 各 hex 上 HOUSEHOLD 账；不足 ⇒ 整条具名拒（不部分、不截断）；
 *   <li><b>国库落点 = {@code at}</b>：新单位国库账 = {@code ActorRef(UNIT, newUnitId)} @ {@code at}，与 levy /
 *       债同族；
 *   <li><b>产出（P1.3）</b>：人口由新人口家户承载（{@code unit.CreateUnit} 的 {@code households=[新人口家户]}）， <b>Unit
 *       不再写已退役的 {@code manpower}</b>；粮 / 钱进新单位国库；人口腿收成<b>一张</b> {@code
 *       social.SubmitHouseholdWorkOrder}（{@code CREATE_HOUSEHOLD} + 逐来源 {@code TRANSFER_MEMBERS}；旧
 *       {@code social.CreateHousehold}/{@code social.TransferHouseholdMembers}×N 两条腿已不再发），行动记录进
 *       {@code ga.PutInfo}；<b>不另造第二份账</b>。
 * </ol>
 *
 * <p>★ <b>为什么载荷组装也在这个类</b>：各条命令的载荷都是这份计划的纯函数（照 {@code LevyRegionPlan} 的拆法）——把载荷留在
 * 工具里会多出一条"视图与载荷各读一次 Plan 字段"的缝，漏一个字段没有症状。载荷一律 {@link LinkedHashMap} 保序构造、 {@link ToolResults#json}
 * 序列化 ⇒ 同状态同参数逐字节相同。
 *
 * <p>★ <b>确定性 / 保序不可变</b>：本类不碰墙钟、不用随机量（{@code tick} 是状态 meta 的函数）；人力来源表由 {@link
 * UnitToolAllocations.ManpowerAllocation} 的全序瀑布冻住，粮/钱来源表由 {@link
 * UnitToolAllocations.AccountAllocation} 冻住，equipment 冻在 Plan 的赋值处（{@code List.copyOf} + 保序转表，不用
 * {@code Map.copyOf}）。
 */
final class RaiseUnitPlan {

  /** 新单位的命令类型（{@link io.mosire.simos.unit.spi.CreateUnitHandler#type()} 的字面量；handler 未导出常量）。 */
  static final String CREATE_UNIT_TYPE = "unit.CreateUnit";

  /** 家户出账 + 新单位国库入账的命令类型（与 {@code AdjustAccountsHandler.type()} 同字面；该 handler 未导出常量）。 */
  static final String ADJUST_ACCOUNTS_TYPE = "actor.AdjustAccounts";

  /** P1.3：人口腿统一走 Social 家户工单（引用 social handler 常量，本类不另抄字面量）。 */
  static final String SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE = SubmitHouseholdWorkOrderHandler.TYPE;

  /** 行动记录的命令类型（{@code PutInfoHandler.type()}）。 */
  static final String PUT_INFO_TYPE = "ga.PutInfo";

  /**
   * 粮的商品 id：取 {@link EconomyVocabulary#GRAIN_COMMODITY_ID} 这个<b>唯一</b>字面量来源（本类不另写 {@code "grain"}）。
   *
   * <p>★ 迁入 unit 前它是 {@code economy.EconomyCommodities.GRAIN}（那个类只是把同一个 util 字面量包成类型化常量）； {@code
   * simos-economy} 在 unit 的禁列里 ⇒ 这里直接包 util 的字面量，<b>值逐字相同</b>。
   */
  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);

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
  /**
   * 推导入口（见类注的七条口径）。
   *
   * <p>★ <b>历法时钟不进本签名</b>：它由 {@code allocations} 的实现持有（生产路径 = {@code CalendarService.clock()}，
   * 旧测试路径 = {@code CalendarService.defaults()}）——{@code simos-calendar} 不在 unit 的允许面里。
   *
   * @param views 视图口（{@code {q,r}} / 装备表 / 工具入参→装备表三份形状的唯一实现）
   * @param allocations 两座瀑布的入口（家户账 + 选人层；实现在 app）
   * @param perspective 视角口（GOV 国库家户解析 + 落点城乡判据）
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
      UnitToolViews views,
      UnitToolAllocations allocations,
      UnitToolPerspective perspective) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(views, "views");
    Objects.requireNonNull(allocations, "allocations");
    Objects.requireNonNull(perspective, "perspective");
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
    UnitState units = UnitToolReads.unitState(state);
    UnitId id = UnitId.parse(newUnitId);
    if (units.units().containsKey(id)) {
      throw new IllegalArgumentException("单位 id 已存在（newUnitId 不得复用）: " + newUnitId);
    }
    GameMap map = UnitToolReads.gameMap(state);
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

    // ★ 粮/钱/人力的来源一律走注入的分摊口（阶段 6/8 共享的唯一瀑布）；requested = 0 的维度整段跳过。
    // ★★ P2-A：账户键不再带格 ⇒ 家户→格从 social 的家户位置派生（唯一拼写点）。
    SocialData social = UnitToolReads.socialData(state);
    UnitToolAllocations.AccountAllocation grainAllocation =
        grain == 0L
            ? UnitToolAllocations.AccountAllocation.skipped()
            : allocations.allocateCommodityAccounts(state, region, "粮", grain, GRAIN);
    UnitToolAllocations.AccountAllocation moneyAllocation =
        money == 0L
            ? UnitToolAllocations.AccountAllocation.skipped()
            : allocations.allocateMoneyAccounts(
                state, region, "钱", money, MoneyVocabulary.SILVER_CURRENCY);
    // ★★ P1.3：人口来源 = region 内 Social 家户的 share-aware 份额瀑布（MALE + 成年档；同一 lot 多户持有各自成候选）。
    //   旧 RegionAllocations.allocateManpower / householdOfLot 单户读法不再进入本路径（拆分批次可用）。
    String householdId = householdIdFor(newUnitId);
    HouseholdId household = HouseholdId.parse(householdId);
    if (social.households().containsKey(household)) {
      throw new IllegalArgumentException(
          "P1.3 新单位的人口家户 id 已被占用: " + householdId + "（先清掉同名家户，或换 newUnitId）");
    }
    UnitToolAllocations.ManpowerAllocation manpowerAllocation =
        allocations.allocateMalesOfAdult(social, region, manpower, tick, Set.of());
    // ★★ P1.3：人口家户只建在工单里（CREATE_HOUSEHOLD + 逐来源 TRANSFER_MEMBERS），本类不再手工投影/转移；
    //   守恒由选人层与 Plan 构造期逐值互校（Σ share.taken == manpower）。
    // ★★ P3：新家户的 economy 视图居住类型按落点判——at 是某座 SocialCity 的 at ⇒ urban，否则 rural。
    ResidenceKind residence = perspective.residenceAt(social, at);
    return new Plan(
        newUnitId,
        name,
        regionId,
        at,
        residence,
        tick,
        manpower,
        manpowerAllocation.available(),
        householdId,
        manpowerAllocation.shares(),
        grainAllocation,
        moneyAllocation,
        speed,
        mobilityPerMille,
        views.compositionEntries(equipment),
        parent);
  }

  /**
   * 新单位人口家户的稳定 id：转发 {@link UnitToolReads#householdIdFor}（域内唯一拼写点，public 供仍在 app 的 gov 族与创世播种调用）。
   */
  static String householdIdFor(String unitId) {
    return UnitToolReads.householdIdFor(unitId);
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

  /** 饱和加法（非负 long；溢出取 {@link Long#MAX_VALUE}）——只用于守恒合计，不参与逐值扣减。 */
  private static long saturatedAdd(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  /** 格的可读文本（拒因与行动记录共用；格式不与任何资源路径语法绑定）。 */
  private static String hexText(HexCoord at) {
    return "(" + at.q() + "," + at.r() + ")";
  }

  // ── Plan：五片载荷与视图材料 ─────────────────────────────────────────────────────────

  /**
   * 一份组军计划（全部字段是状态的纯函数）。
   *
   * @param unitId 新单位 id
   * @param name 新单位名
   * @param regionId 来源区域
   * @param at 新单位落点 = 国库落点 = 新家户 economy 行落点
   * @param residence P3：新人口家户 economy 视图的居住类型（at 是某城 at ⇒ URBAN，否则 RURAL）
   * @param tick 推导时的世界日（行动记录与工单幂等键用）
   * @param manpowerCount 新单位实抽人数 = 新人口家户的成员之和（S3b 起不再落 unit.manpower）
   * @param available 全部合格家户份额合计（选人层读数；成功时 available ≥ manpowerCount）
   * @param householdId 新单位人口家户 id（{@code hh-unit:<unitId>}；location = UNIT(newUnitId)）
   * @param sources 逐来源家户份额（share-aware 瀑布序；Σtaken == manpowerCount）
   * @param grain 粮来源分摊（requested = 0 = 本维度整段跳过）
   * @param money 钱来源分摊（requested = 0 = 本维度整段跳过）
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
      ResidenceKind residence,
      long tick,
      long manpowerCount,
      long available,
      String householdId,
      List<UnitToolAllocations.ManpowerShare> sources,
      UnitToolAllocations.AccountAllocation grain,
      UnitToolAllocations.AccountAllocation money,
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
      Objects.requireNonNull(residence, "residence");
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      if (manpowerCount < 1L) {
        throw new IllegalArgumentException("manpowerCount 必须 ≥ 1: " + manpowerCount);
      }
      if (available < 0L) {
        throw new IllegalArgumentException("available 不得为负: " + available);
      }
      if (available < manpowerCount) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：available=" + available + " < manpowerCount=" + manpowerCount);
      }
      requireNonBlank(householdId, "householdId");
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      long total = 0L;
      for (UnitToolAllocations.ManpowerShare share : sources) {
        total = saturatedAdd(total, share.taken());
      }
      if (total != manpowerCount) {
        throw new IllegalArgumentException(
            "守恒破坏：Σ来源 share.taken=" + total + " != manpowerCount=" + manpowerCount);
      }
      Objects.requireNonNull(grain, "grain");
      Objects.requireNonNull(money, "money");
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

    /**
     * P1.3 工单确定性幂等键：{@code raise-unit:<unitId>:<tick>:<manpower>}。同一批参数在同一 tick 重放 ⇒ 命中幂等键、
     * 整单具名拒，不重复改人口。
     */
    String orderId() {
      return "raise-unit:" + unitId + ":" + tick + ":" + manpowerCount;
    }

    /**
     * ★★ P1.3：人口腿的<b>唯一</b>命令载荷（{@code social.SubmitHouseholdWorkOrder}）——第一步 {@code
     * CREATE_HOUSEHOLD}（位置 = {@code UNIT(unitId)}、画像 {@code name+"·人口家户"}、vitalRates 空表），随后逐来源
     * {@code TRANSFER_MEMBERS(from=share.householdId, to=hh-unit:<unitId>, lotId,
     * count=taken)}。{@code orderId} = {@link #orderId()}，{@code source.module="unit"}，reason = 工具
     * reason。
     */
    String submitHouseholdWorkOrderPayloadJson(String reason) {
      requireNonBlank(reason, "reason");
      List<Map<String, Object>> steps = new ArrayList<>(sources.size() + 1);
      Map<String, Object> create = new LinkedHashMap<>();
      create.put("op", "CREATE_HOUSEHOLD");
      create.put("household", householdId);
      Map<String, Object> location = new LinkedHashMap<>();
      location.put("type", "UNIT");
      location.put("unitId", unitId);
      create.put("location", location);
      Map<String, Object> profile = new LinkedHashMap<>();
      profile.put("name", name + "·人口家户");
      create.put("profile", profile);
      create.put("vitalRates", List.of());
      steps.add(create);
      for (UnitToolAllocations.ManpowerShare share : sources) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("op", "TRANSFER_MEMBERS");
        step.put("from", share.householdId().value());
        step.put("to", householdId);
        step.put("lotId", share.lotId().value());
        step.put("count", share.taken());
        steps.add(step);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("orderId", orderId());
      payload.put("target", householdId);
      payload.put("reason", reason);
      payload.put("source", Map.of("module", "unit"));
      payload.put("plan", steps);
      return ToolResults.json(payload);
    }

    /**
     * ★★ P3：新人口家户的 economy 登记载荷（{@code economy.RegisterHousehold}）——落点 = {@code at}，居住类型 = {@link
     * #residence()}，阶层 = {@code landless_laborer}（无资产的中性档），参与率 = 0（由 Social 逐户劳动预算在后续日循环注入，
     * 不在登记时猜）。
     */
    String registerHouseholdPayloadJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("household", householdId);
      payload.put("q", at.q());
      payload.put("r", at.r());
      payload.put("residence", residence.value());
      payload.put("stratum", SocialClassId.LANDLESS_LABORER.value());
      payload.put("participationPerMille", 0);
      payload.put("reason", reason);
      return ToolResults.json(payload);
    }

    /** ★★ P3：新人口家户的零余额 actor 账户载荷（{@code actor.EnsureHouseholdAccount}，幂等；账户归 actor 切片）。 */
    String ensureHouseholdAccountPayloadJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("household", householdId);
      payload.put("reason", reason);
      return ToolResults.json(payload);
    }

    /** 是否需要落 {@code actor.AdjustAccounts}（粮 / 钱任一 &gt; 0）。 */
    boolean hasGrainOrMoney() {
      return grain.requested() > 0L || money.requested() > 0L;
    }

    /**
     * 本工具提交的命令类型（按批内固定顺序；preview 视图与 apply 组批共用同一处）。
     *
     * @param registerHouseholdType {@code economy.RegisterHousehold} 的类型串（唯一拼写点在 economy 的 handler，
     *     经 {@link UnitToolCommandTypes} 取——unit 看不见 economy 实现）
     * @param ensureHouseholdAccountType {@code actor.EnsureHouseholdAccount} 的类型串（同上，拼写点在 actor 的
     *     handler）
     */
    List<String> commandTypes(String registerHouseholdType, String ensureHouseholdAccountType) {
      List<String> types = new ArrayList<>(6);
      types.add(SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE);
      types.add(CREATE_UNIT_TYPE);
      types.add(registerHouseholdType);
      types.add(ensureHouseholdAccountType);
      if (hasGrainOrMoney()) {
        types.add(ADJUST_ACCOUNTS_TYPE);
      }
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /**
     * {@code unit.CreateUnit} 载荷（字段名逐字照 handler 的 {@code UnitPayloads}：{@code
     * id/name/position/households/equipment/speed/mobilityPerMille/parent?}）。
     *
     * <p>★ S3b：不再发 {@code manpower}（已退役）；新单位人口由 {@code households=[新家户]} 承载。
     *
     * <p>★ {@code jurisdiction} 不在载荷里：{@code CreateUnitHandler} 对新建单位一律取 {@code Optional.empty()}
     * （新单位尚无管辖），本工具不发明第二个字段。
     */
    String createUnitPayloadJson(UnitToolViews views) {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", unitId);
      payload.put("name", name);
      payload.put("position", views.hexCoord(at));
      payload.put("households", List.of(householdId));
      payload.put("equipment", views.compositionView(equipment));
      payload.put("speed", speed);
      payload.put("mobilityPerMille", mobilityPerMille);
      parent.ifPresent(parentId -> payload.put("parent", parentId));
      return ToolResults.json(payload);
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
      // ★ 合并键 = 家户 id 本身（迁移前的 `HouseholdAccountKey` 是它的 1:1 包装，`toString()` 就是 `value()`
      //   ⇒ 去重与「首次出现的位置保留」的次序逐字不变；`simos-actor` 在 unit 的禁列里，且该键只用于本表）。
      LinkedHashMap<HouseholdId, Long> grainByKey = new LinkedHashMap<>();
      for (UnitToolAllocations.AccountSource source : grain.sources()) {
        grainByKey.put(HouseholdActors.householdOf(source.owner()), -source.amount());
      }
      LinkedHashMap<HouseholdId, Long> moneyByKey = new LinkedHashMap<>();
      for (UnitToolAllocations.AccountSource source : money.sources()) {
        moneyByKey.put(HouseholdActors.householdOf(source.owner()), -source.amount());
      }
      LinkedHashSet<HouseholdId> order = new LinkedHashSet<>(grainByKey.keySet());
      order.addAll(moneyByKey.keySet());
      List<Map<String, Object>> entries = new ArrayList<>(order.size() + 1);
      for (HouseholdId key : order) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("household", key.value());
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
      treasury.put("household", householdId);
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
      return ToolResults.json(payload);
    }

    /**
     * {@code ga.PutInfo} 的 {@code value}（JSON
     * <b>字符串</b>；字段序固定：unit/region/at/三项数量/来源计数/sources/reason）。 {@code sources} = 逐来源 {@code
     * {householdId, lotId, taken, hex}}（share-aware 瀑布序）。
     */
    String infoValueJson(String reason, UnitToolViews views) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("unitId", unitId);
      value.put("regionId", regionId);
      value.put("at", views.hexCoord(at));
      // ★ P1.3：不再有 unit.manpower 表；记录新单位的人口家户与抽取人口（均来自 Social 家户份额）。
      value.put("householdId", householdId);
      value.put("manpowerRequested", manpowerCount);
      value.put("grain", grain.requested());
      value.put("money", money.requested());
      Map<String, Object> sourceCounts = new LinkedHashMap<>();
      sourceCounts.put("grain", grain.sources().size());
      sourceCounts.put("money", money.sources().size());
      sourceCounts.put("manpower", sources.size());
      value.put("sourceCounts", sourceCounts);
      value.put("sources", sourcesView(views));
      value.put("reason", reason);
      return ToolResults.json(value);
    }

    /**
     * 逐来源视图（工具结果与 {@code ga.PutInfo.value.sources} 共用；保序）。{@code hex} 只有 {@code HEX} 来源家户才有； {@code
     * UNIT} 来源没有格 ⇒ 值为 {@code null}（不伪造位置）。
     */
    List<Map<String, Object>> sourcesView(UnitToolViews views) {
      List<Map<String, Object>> rows = new ArrayList<>(sources.size());
      for (UnitToolAllocations.ManpowerShare share : sources) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("householdId", share.householdId().value());
        row.put("lotId", share.lotId().value());
        row.put("taken", share.taken());
        row.put("hex", share.hex() == null ? null : views.hexCoord(share.hex()));
        rows.add(row);
      }
      return rows;
    }

    /** 人可读行动摘要（工具结果与 {@code ga.PutInfo.note} 共用）。 */
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
          + "，人口 "
          + manpowerCount
          + "（家户 "
          + householdId
          + "，来源份额 "
          + sources.size()
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

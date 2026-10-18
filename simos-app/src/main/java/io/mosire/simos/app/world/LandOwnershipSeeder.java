package io.mosire.simos.app.world;

import static io.mosire.simos.app.world.SeederPayloadSupport.actorNode;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.Pool;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.relation.Weight;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 资产份额、主/副 unit 拆分与家户副 unit 的产权/关系构造。 */
class LandOwnershipSeeder extends WorldBalanceSeeder {

  /**
   * ★★ <b>P5b1：农村四阶层家户的初始 LAND 分配权重（‰；按 {@link #CLASS_IDS} 序）。</b>
   *
   * <p>这是一个**确定性工程权重**（不是玩家裁定、也不是从人口反推）：贫农 100 / 中农 250 / 富农 300 / 地主 350，合计
   * 1000‰。它让初始土地有差异、同一 hex 的 Σ 恰好等于可分配土地；无地/流民户不在本表（权重 0）。改变它 = 改变新世界初态。
   *
   * <p>★ <b>为什么不用人口份额</b>：人口份额（450/350/150/50）会把土地按人头摊平，失去"有地/少地"的初始差异；本表只服务创世
   * 土地分布，阶级标签仍不进生产选择。
   */
  static final int[] RURAL_FARM_LAND_WEIGHT_PER_MILLE = {100, 250, 300, 350};

  /**
   * ★★ <b>把一个 hex 的可分配 LAND 按 {@link #RURAL_FARM_LAND_WEIGHT_PER_MILLE} 切成逐户份额</b>。
   *
   * <p>Σ 结果 == {@code landMilliMu}（最大余数法；权重合计 1000‰）。值允许 0（沙漠格 / 四舍五入落到 0 的户）。
   */
  static long[] ruralFarmLandShares(long landMilliMu, int classHouseholdCount) {
    if (classHouseholdCount != RURAL_FARM_LAND_WEIGHT_PER_MILLE.length) {
      throw new IllegalStateException(
          "农村家户数 "
              + classHouseholdCount
              + " 与 LAND 分配权重表长度 "
              + RURAL_FARM_LAND_WEIGHT_PER_MILLE.length
              + " 不一致（拒绝静默摊平）");
    }
    long[] weights = new long[classHouseholdCount];
    for (int i = 0; i < weights.length; i++) {
      weights[i] = RURAL_FARM_LAND_WEIGHT_PER_MILLE[i];
    }
    return splitProportional(landMilliMu, weights);
  }

  /**
   * ★★ <b>P5b1：一个农村 hex 的 grain_farm 逐户 unit 种子</b> —— 四个阶层家户各拿一份自有 LAND；流民/无地户额外保留
   * 0 土地 unit（下一轮买地后可组织）。owner = operator = 家户，LAND kind = OWNED。
   *
   * <p>★ 跨 hex 不伪造：本方法只处理传入的 {@code hex}，每个家户的土地份额都登记在该 hex。
   */
  static List<GrainFarmUnit> grainFarmUnits(
      IndustryPlan plan,
      HexCoord hex,
      List<HouseholdId> classHouseholds,
      HouseholdId displacedHousehold) {
    long landMilliMu = plan.capacity().getOrDefault(AssetKind.LAND.name(), 0L);
    long[] shares = ruralFarmLandShares(landMilliMu, classHouseholds.size());
    List<GrainFarmUnit> units =
        new ArrayList<>(classHouseholds.size() + (displacedHousehold == null ? 0 : 1));
    for (int i = 0; i < classHouseholds.size(); i++) {
      units.add(grainFarmUnit(plan, hex, classHouseholds.get(i), shares[i]));
    }
    if (displacedHousehold != null) {
      // ★ 无地/流民户：保留零土地 unit，便于下一轮买地后组织；不伪造土地/劳动。
      units.add(grainFarmUnit(plan, hex, displacedHousehold, 0L));
    }
    return List.copyOf(units);
  }

  /** 一个家户的 grain_farm unit：显式空规则关系 + OWNED LAND 份额（可为 0）+ 稳定 unit id。 */
  private static GrainFarmUnit grainFarmUnit(
      IndustryPlan plan, HexCoord hex, HouseholdId household, long landMilliMu) {
    ActorRef operator = HouseholdActors.of(household);
    ProductionUnitId unitId = ProductionUnitId.idOf(new IndustryId(plan.id()), operator);
    ProductionRules relation =
        new ProductionRules(
            unitId,
            operator,
            new Payee.ToHousehold(household),
            List.of(),
            operator,
            LaborSource.SELF);
    Map<String, Object> unitNode = SeederPayloadSupport.unitOf(plan, operator, relation);
    Map<String, Object> shareNode =
        ownershipStakeNode(
            plan.id(),
            AssetKind.LAND.name(),
            operator,
            operator,
            AssetRightKind.OWNED,
            landMilliMu,
            IndustryHexKeys.hexKey(hex.q(), hex.r()));
    return new GrainFarmUnit(household, operator, unitId, unitNode, shareNode);
  }

  /**
   * ★★ <b>P5b1：一个家户 grain_farm unit 的显式生产名册</b>（多角色模板必须显式，不能依赖单角色自动名册）。
   *
   * <p>当前只写一个 {@code operator} 参与项（organizer=true）。**不塞假雇工**：没有真实 {@code hired_laborer} 关系时，
   * {@code hired_laborer} 角色保持无参与项；等 M3/M6 产生真实雇佣并锁定时再由运行期名册写入。
   */
  static Map<String, Object> grainFarmRosterNode(
      GrainFarmUnit unit, long plannedLaborMilliPerDay) {
    Map<String, Object> participant = new LinkedHashMap<>();
    participant.put("household", unit.household().value());
    participant.put("role", DefaultProductionModes.ROLE_OPERATOR);
    participant.put("organizer", true);
    participant.put("plannedLaborMilliPerDay", plannedLaborMilliPerDay);
    participant.put("rightsStakes", List.of());
    participant.put("configuredWeight", 0L);
    Map<String, Object> roster = new LinkedHashMap<>();
    roster.put("unit", unit.unitId().value());
    roster.put("participants", List.of(participant));
    return roster;
  }

  /** 某 unit 在配额表里的计划劳动合计（毫小时/日）—— 名册 plannedLabor 的唯一取数口。 */
  static long plannedLaborOf(List<Map<String, Object>> allocations, ProductionUnitId unitId) {
    long total = 0L;
    for (Map<String, Object> allocation : allocations) {
      if (unitId.value().equals(allocation.get("activity"))) {
        total =
            Math.addExact(total, ((Number) allocation.get("laborMilli")).longValue());
      }
    }
    return total;
  }

  /**
   * ★★ <b>P5b1：把创世配额行从"产业占位 unit"改挂到该家户自己的 grain_farm unit</b>。
   *
   * <p>创世仍用 {@code appendAllocation} 的既有性别/预算算法算出一批逐 (批次, 家户) 配额；本方法只改落的 unit 与 actor，
   * 不动 laborMilli、不动批次/家户 —— 家户劳动守恒不变。
   */
  static void retargetFarmAllocation(Map<String, Object> allocation, GrainFarmUnit unit) {
    Object householdText = allocation.get("household");
    if (!(householdText instanceof String household) || !unit.household().value().equals(household)) {
      throw new IllegalStateException(
          "farm 配额的家户与目标 unit 不一致: allocation="
              + allocation
              + " unit="
              + unit.unitId().value());
    }
    allocation.put(
        "id",
        HouseholdLaborCommitment.idOf(
                unit.unitId(),
                PeopleLotId.parse((String) allocation.get("group")),
                unit.household())
            .value());
    allocation.put("activity", unit.unitId().value());
    allocation.put("actor", actorNode(unit.operator()));
  }

  /**
   * 一条 {@code OwnershipStake} 载荷节点：{@code {industry, owner, operator, asset, quantity, kind,
   * location}} —— 新形状显式给 owner/operator（租佃时两者不等）与真实格键 location；id 由 {@code
   * EconomyPayloads.addOwnershipStake} 的确定性序号生成（同一 {@code (industry, asset, owner, operator, kind,
   * location)} 从 0 递增）。
   */
  static Map<String, Object> ownershipStakeNode(
      String industry,
      String asset,
      ActorRef owner,
      ActorRef operator,
      AssetRightKind kind,
      long quantity,
      String location) {
    Map<String, Object> share = new LinkedHashMap<>();
    share.put("industry", industry);
    share.put("owner", actorNode(owner));
    share.put("operator", actorNode(operator));
    share.put("asset", asset);
    share.put("quantity", quantity);
    share.put("kind", kind.name());
    share.put("location", location);
    return share;
  }

  /**
   * ★★ <b>主 unit 的显式关系</b>（P2-A §13.3）：{@code operator}/{@code residualOwner} = 组织者家户， {@code
   * inputSupplier} 仍指原产业主体 actor（它在 {@code supplierAccountsOf} 里代理到 unit 名下劳动家户，
   * 播种期的"谁出种/出料"因此逐字不变）。
   *
   * <p>★★★ <b>D-032 C3（控制方 2026-10-17 裁定选项 (a)）：本方法只产出**旧关系原样**</b>；映射/清空/回退统一由 {@link
   * #applyLegacyRoleMapping} 在全部 unit 建好后按 plan 决定：
   *
   * <ul>
   *   <li>可整体映射 ⇒ {@code roles} 写入 plan、旧 {@code rules} 清空（D2-I17，不双分）；
   *   <li>不可表示（{@code FIXED_IN_KIND_*} / 合计 &gt; 1000 / 副 unit 覆盖不了等）⇒ <b>原样保留旧 rules</b>、 不加新
   *       roles/roster、INFO 记 {@code legacy-compensation-rule-blocked:<plan>:<reason>}，继续创世。
   * </ul>
   *
   * <p>★ 因此本方法不抛"不可表示"异常、不阻断创世。
   */
  static ProductionRules organizerRelation(
      IndustryPlan plan, Set<ResidenceKind> residences, ActorRef inputSupplierOrg) {
    IndustryId industryId = new IndustryId(plan.id());
    ProductionUnitId activity = ProductionUnitId.idOf(industryId, plan.operator());
    ProductionRules base =
        RegimeRelations.defaultRelation(
            new RegimeId(plan.regime()), activity, industryId, plan.operator(), residences);
    // ★★★ D-032 C3（控制方裁定选项 (a)）：这里**只产出旧关系**；plan-level 的
    //   {@link #applyLegacyRoleMapping} 在全部 unit 建好后统一决定"整体映射并清空 rules"或"原样保留走旧路径"。
    //   因此本方法不抛异常、不在此处清空 rules、也不在此处写 roles。
    return new ProductionRules(
        base.activity(),
        base.operator(),
        new Payee.ToActor(inputSupplierOrg),
        base.rules(),
        base.residualOwner(),
        base.laborSource());
  }

  /**
   * ★★ <b>集体经营 unit 的最小关系</b>（P2-E；当前 = 家户纺织主 unit）：{@code operator}/{@code residualOwner} =
   * 聚合经营主体，{@code inputSupplier} 也指它（{@code supplierAccountsOf} 对聚合主体按 {@code
   * index.householdsOf(unit)} 代理到各劳动家户账）；<b>规则为空</b> —— 净产出已由 harvest 的 {@code creditOutput}
   * 按劳动权重分给各家家户账，不再叠一层制度模板。
   *
   * <p>★ <b>为什么必须显式空规则</b>：HOUSEHOLD 模板的四个受方 cohort 与这个集体 unit 的成员是同一批家户 ⇒
   * 模板规则会让成员之间互相转移（同一对家户既是付方又是受方 ⇒ 自转移，{@code Transfer} 的两端不得相等， 实测首个收获日当场抛）。集体经营的产出归属由 {@code
   * HouseholdRouting} 的劳动权重表达，模板在这里是第二本账。
   *
   * <p>★★ 2026-10-23 裁定 B：这个集体 unit <b>不是市场主体</b>（产出/库存落成员家户、各家自行入市，{@code
   * MarketSettlement.participantsFor} 的集体分支静默跳过）；{@code plan.operator()} 仍按本关系继续作为结算
   * payee（清理/迁移它是另一责任区）。
   */
  static ProductionRules collectiveRelation(IndustryPlan plan) {
    return new ProductionRules(
        plan.unitId(),
        plan.operator(),
        new Payee.ToActor(plan.operator()),
        List.of(),
        plan.operator(),
        LaborSource.FAMILY);
  }

  /**
   * ★★ <b>R3B.2：一个产业 plan → 它的整额 OWNED 实物资产份额</b>（capacity 的逐项；{@code owner} 与 {@code operator} 都 =
   * plan.operator，即新世界播种的"自有自营"档）。
   *
   * <p>★ <b>逐项含 0 值</b>：0 产能是合法形态（沙漠格 LAND=0），但"非 EXITED/ABANDONED 的 unit 必须至少有一条同 industry 的
   * OwnershipStake"这条守卫要求 0 也登记；数量 0 不改变规模（capacityScale 对每键读到 0 ⇒ 规模 0，与旧档 capacity 0 等价）。 {@code
   * kind = OWNED} 是创世默认档。★ B.2 不拆多 unit：一块 capacity 只发一条整额份额。
   */
  static List<Map<String, Object>> ownershipStakesOf(
      IndustryPlan plan, Map<String, Long> quantities, String location) {
    List<Map<String, Object>> shares = new ArrayList<>();
    for (Map.Entry<String, Long> entry : quantities.entrySet()) {
      // ★★ 逐项**含 0 值**：0 产能是合法形态（沙漠格 LAND=0），但"非退出 unit 必须有至少一条同 industry 的
      //   OwnershipStake"这条守卫要求 0 也要登记（数量 0 不改变规模：capacityScale 对每键读到 0 ⇒ 规模 0）。
      shares.add(
          ownershipStakeNode(
              plan.id(),
              entry.getKey(),
              plan.operator(),
              plan.operator(),
              AssetRightKind.OWNED,
              entry.getValue(),
              location));
    }
    return shares;
  }

  /**
   * ★★ <b>R4-B.3a：把一个产业的现有配额行拆成"主 unit 余量 + 家户副 unit"</b>（只做静态初值，不改运行期结算）。
   *
   * <pre>
   * 劳动：moved = ⌊laborMilli × SECONDARY_PER_MILLE ÷ 1000⌋
   *       主行减 moved；副 unit 新发一条 (unit, group, household) 的配额（Σ 逐批次不变）
   * 资产：secondaryTotal = ⌊capacity × SECONDARY_PER_MILLE ÷ 1000⌋
   *       按各户 moved 劳动为权重用最大余数法切给候选户；每户每个 asset 都 ≥ capacityPerUnit 才建副 unit
   *       被跳过的户：份额与劳动都留在主 unit（只拆不加；主 unit 拿 total − 已建成副 unit 份额）
   * </pre>
   *
   * <p>★★ <b>守恒由构造保证</b>：{@code Σ 主+副 assetShares == plan.capacity}、{@code Σ 主行+副行 laborMilli ==
   * 原行}。 ★ <b>确定性</b>：候选户按 {@link HouseholdId#value()} 升序（{@code ProportionalSplit} 的"同余数按下标序"
   * 因此正好是 canonical id 升序）；不读 {@code HashMap} 迭代序，不用随机数/时间。
   *
   * <p>★★ <b>P5b1：farm 不再走本方法</b> —— 农村 grain_farm 由 {@link #grainFarmUnits} 逐户直接物化；本方法只服务
   * weave / craft 等非 farm 产业（城市作坊的 TENANCY 副 unit 不在本批范围）。
   */
  static IndustrySplit splitIndustry(
      IndustryPlan plan, List<Map<String, Object>> allocations, HexCoord hex) {
    String mainUnitId = plan.unitId().value();
    // ① 只拆"指向主 unit"的行；moved ≤ 0 的行原样留下（小配额不拆 = 合法，不是丢数据）。
    Map<HouseholdId, Long> movedLaborByHousehold = new LinkedHashMap<>();
    List<MovedRow> movedRows = new ArrayList<>();
    for (Map<String, Object> allocation : allocations) {
      if (!mainUnitId.equals(allocation.get("activity"))) {
        continue;
      }
      long laborMilli = ((Number) allocation.get("laborMilli")).longValue();
      long moved = laborMilli * SECONDARY_PER_MILLE / 1000L;
      if (moved <= 0L) {
        continue;
      }
      HouseholdId household = HouseholdId.parse((String) allocation.get("household"));
      if (HouseholdActors.of(household).equals(plan.operator())) {
        continue; // ★ P2-A：组织者家户（地主/作坊主）自己的劳动与产能留在主 unit，不另建同 id 的副 unit。
      }
      movedLaborByHousehold.merge(household, moved, Math::addExact);
      movedRows.add(new MovedRow(allocation, household, moved));
    }
    if (movedLaborByHousehold.isEmpty()) {
      // 没有可拆的劳动 ⇒ 一产业一 unit（与 B.2 逐值相同）；主 unit 仍拿整份 capacity。
      return new IndustrySplit(plan, plan.capacity(), List.of(), List.of());
    }
    List<HouseholdId> households = new ArrayList<>(movedLaborByHousehold.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    long[] weights = new long[households.size()];
    for (int i = 0; i < households.size(); i++) {
      weights[i] = movedLaborByHousehold.get(households.get(i));
    }
    // ② 逐 asset 切副 unit 总量；"至少一份"不满足的户整体不建（份额与劳动都退回主 unit）。
    Map<String, long[]> sharesByAsset = new LinkedHashMap<>();
    boolean[] kept = new boolean[households.size()];
    Arrays.fill(kept, true);
    for (Map.Entry<String, Long> capacity : plan.capacity().entrySet()) {
      long secondaryTotal = capacity.getValue() * SECONDARY_PER_MILLE / 1000L;
      long[] shares = splitProportional(secondaryTotal, weights);
      sharesByAsset.put(capacity.getKey(), shares);
      Long capacityPerUnit = plan.capacityPerUnit().get(capacity.getKey());
      if (capacityPerUnit == null) {
        throw new IllegalStateException(
            "产业 " + plan.id() + " 的资产 " + capacity.getKey() + " 没有 capacityPerUnit（无法判一份最小规模）");
      }
      for (int i = 0; i < households.size(); i++) {
        if (shares[i] < capacityPerUnit) {
          kept[i] = false;
        }
      }
    }
    // ③ 主 unit 剩余 = capacity − 已建成副 unit 的份额；被跳过户的份额不动（就在主 unit 里）。
    Map<String, Long> mainCapacity = new LinkedHashMap<>(plan.capacity());
    List<HouseholdUnit> secondaries = new ArrayList<>();
    for (int i = 0; i < households.size(); i++) {
      if (!kept[i]) {
        continue;
      }
      HouseholdId household = households.get(i);
      Map<String, Long> quantities = new LinkedHashMap<>();
      for (Map.Entry<String, Long> capacity : plan.capacity().entrySet()) {
        long quantity = sharesByAsset.get(capacity.getKey())[i];
        quantities.put(capacity.getKey(), quantity);
        mainCapacity.merge(capacity.getKey(), -quantity, Math::addExact);
      }
      secondaries.add(householdUnit(plan, hex, household, quantities));
    }
    // ④ 只把"建成了副 unit"的家户的劳动行移过去；其余行原样留在主 unit。
    Map<HouseholdId, HouseholdUnit> secondaryByHousehold = new LinkedHashMap<>();
    for (HouseholdUnit secondary : secondaries) {
      secondaryByHousehold.put(secondary.household(), secondary);
    }
    List<LaborMove> laborMoves = new ArrayList<>();
    for (MovedRow movedRow : movedRows) {
      HouseholdUnit secondary = secondaryByHousehold.get(movedRow.household());
      if (secondary != null) {
        laborMoves.add(new LaborMove(movedRow.row(), secondary, movedRow.moved()));
      }
    }
    return new IndustrySplit(plan, mainCapacity, secondaries, laborMoves);
  }

  /**
   * 一条副 unit 的劳动配额载荷：{@code id = HouseholdLaborCommitment.idOf(副 unit, 批次, 家户)}、{@code actor = 家户
   * actor}、 {@code activity = 副 unit id}，其余字段（group/household/period）照原行。★ 调用方先把主行的 {@code
   * laborMilli} 减掉 moved。
   */
  static Map<String, Object> secondaryAllocation(IndustryPlan plan, LaborMove move) {
    HouseholdUnit unit = move.unit();
    ProductionUnitId unitId = ProductionUnitId.idOf(new IndustryId(plan.id()), unit.operator());
    Map<String, Object> row = move.row();
    Map<String, Object> allocation = new LinkedHashMap<>();
    allocation.put(
        "id",
        HouseholdLaborCommitment.idOf(
                unitId, PeopleLotId.parse((String) row.get("group")), unit.household())
            .value());
    allocation.put("group", row.get("group"));
    allocation.put("household", unit.household().value());
    allocation.put("actor", actorNode(unit.operator()));
    allocation.put("activity", unitId.value());
    allocation.put("laborMilli", move.moved());
    allocation.put("period", row.get("period"));
    return allocation;
  }

  /**
   * ★★ <b>一个家户副 unit 的完整形态</b>（owner/kind/relation 按产业档位显式给出，<b>不靠制度默认关系猜</b>）。
   *
   * <pre>
   * farm  ⇒ operator=owner=家户 · OWNED · relation 空 rules（全自留） + SELF（P5b1：不再有 TENANCY/300‰ 地租）
   * weave ⇒ operator=owner=家户 · OWNED · relation 空 rules（全自留） + FAMILY
   * craft ⇒ operator=家户 · owner=WORKSHOP 主 unit · TENANCY · relation=TENANT + 300‰ 布给作坊主（城市，非本批范围）
   * </pre>
   *
   * <p>★ <b>家户 actor 的身份拼写点</b> = {@link HouseholdActors#of(HouseholdId)}（本类不另拼 actor id）。
   */
  private static HouseholdUnit householdUnit(
      IndustryPlan plan, HexCoord hex, HouseholdId household, Map<String, Long> quantities) {
    ActorRef operator = HouseholdActors.of(household);
    // ★ D-032 C3（控制方裁定选项 (a)）：副 unit 的旧规则先原样产出；plan-level 的
    //   {@link #applyLegacyRoleMapping} 在全部 unit 建好后统一决定"整体映射并清空"或"原样保留走旧路径"。
    return switch (plan.kind()) {
      case FARM ->
          new HouseholdUnit(
              household,
              operator,
              operator,
              AssetRightKind.OWNED,
              quantities,
              new ProductionRules(
                  ProductionUnitId.idOf(new IndustryId(plan.id()), operator),
                  operator,
                  new Payee.ToHousehold(household),
                  List.of(),
                  operator,
                  LaborSource.SELF));
      case WEAVE ->
          new HouseholdUnit(
              household,
              operator,
              operator,
              AssetRightKind.OWNED,
              quantities,
              new ProductionRules(
                  ProductionUnitId.idOf(new IndustryId(plan.id()), operator),
                  operator,
                  new Payee.ToHousehold(household),
                  List.of(),
                  operator,
                  LaborSource.FAMILY));
      case CRAFT ->
          new HouseholdUnit(
              household,
              operator,
              plan.operator(),
              AssetRightKind.TENANCY,
              quantities,
              new ProductionRules(
                  ProductionUnitId.idOf(new IndustryId(plan.id()), operator),
                  operator,
                  new Payee.ToActor(plan.operator()),
                  // 旧 OUTPUT_SHARE（给作坊主 300‰ 布）：plan 级可整体映射时由角色承接并清空；
                  // 否则原样保留走旧路径（不静默丢、不双分）。
                  List.of(outputShareRule(new Payee.ToActor(plan.operator()), COMMODITY_CLOTH)),
                  operator,
                  LaborSource.TENANT));
      default -> throw new IllegalStateException("未知产业 kind：" + plan.kind());
    };
  }

  /** 一条 {@code OUTPUT_SHARE × GROSS_OUTPUT} 实物分成规则（匠户分成；农村 farm 分支 P5b1 起不再使用）。 */
  private static CompensationRule outputShareRule(Payee recipient, String commodity) {
    return new CompensationRule(
        RuleType.OUTPUT_SHARE,
        recipient,
        Pool.GROSS_OUTPUT,
        Weight.NONE,
        TENANT_RENT_PER_MILLE,
        0L,
        Optional.of(new CommodityId(commodity)),
        Optional.empty(),
        10);
  }
}

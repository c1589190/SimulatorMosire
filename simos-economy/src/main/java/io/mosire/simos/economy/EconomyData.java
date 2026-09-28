package io.mosire.simos.economy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.migrate.DebtReferenceReconciler;
import io.mosire.simos.economy.migrate.LegacyHouseholdMigration;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 经济切片的完整状态树（新经济设计 §3 逐字）：激活元信息 + 产业表 + 阶层行 + 债务表 + 周期流水 + **劳动供给表 + 劳动分配表**（R2） + 生产关系表（T2）+
 * **市场表**（H4）。
 *
 * <p>★★ **{@code meta} 为空 {@code Optional} = 经济未激活**（§3.3 + §6.6）：未激活时日制世界仍可沿用简化人口查询（人口查询走 {@code
 * social}），但**日推进仍要求切片在场**。空快照 ≠ 已激活。
 *
 * <p>★★ **本切片只写自己的数据**（§2 + §6.1）：商品/货币/人口的总量守恒由**命令层/协调器**校验，**不落成第二份真相**——这里只有状态，
 * 没有"校验结论"。任何经济公式（产量/分配/税/市场盈亏）都不在本切片（§八 R1 行："模块化、无公式"）。
 *
 * <p>★ **十四个组件与 {@link io.mosire.simos.economy.change.EconomyChangeSet} 的十四个组件一一对应**（铁律 5）：
 * 新增状态组件必须同时进变更集，由 {@code EconomyRoundTripTest} 的反射枚举把守。
 *
 * <p>★★ **跨表同键不变式**（§6.2 的身份部分）：{@code classes} 的每个键必须等于其 {@link ClassRow#key()}；{@code flows}
 * 的每个键必须等于其 {@link FlowRow#key()}；{@code laborSupply} / {@code allocations} 同理各自等于行内的 group / id。
 * 否则同一份身份就有两处可能不一致的记录。
 *
 * <p>★★ **R2：本阶段最重要的不变量在这里判死**（第三阶段设计稿 §四）：
 *
 * <pre>
 * Σ_{a ∈ allocations(group)} a.laborMilli  ≤  availableLabor(supply(group))
 * </pre>
 *
 * 它**必须**在构造期判，而不是在结算里"顺手算对"：劳动是**可分配但不能凭空重复**的资源（本轮的目标原话），而"同一批人被两个产业各算一次满额" 正是设计稿 §一.2
 * 实测出的空洞。判在构造期 ⇒ 任何一条路径（命令、旧档读入、夹具、将来的协调器）都不可能造出"配额超过可支配劳动"的状态—— 那正是本仓栽过的同族教训（{@code progressDays
 * == cycleDays} 与 {@code WageFirst}：模型允许的状态，结算与用例都得处理）。
 *
 * <p>★ **两条配套的结构判据**（同上，都判在构造期）：
 *
 * <ol>
 *   <li>**actor ↔ 产业 的对应关系**：结算按 {@code actor.id()} 把配额归到产业（"这一格的劳动被哪个产业占了多少"的唯一判据）⇒ 产业型主体（{@link
 *       ActorKind#ESTATE}/{@link ActorKind#WORKSHOP}）**必须**指名一个已存在的产业，而非产业型主体的 id **不得**与任何产业 id
 *       撞名（撞名 ⇒ 家户的配额被静默算进那个产业）。★ **R3 起 {@link ActorKind#HOUSEHOLD} 是自由档**：农村家庭纺织是"家户
 *       自己承担的一个生产过程"（spec §四）⇒ 家户的 actor id 可以命名一个产业（那时配额照进该产业的 {@code cycleLaborMilli}），
 *       也可以不命名（那时它只是消费主体）；其余四个非产业型种类仍不许撞名；
 *   <li>**每条配额必须有一份同期的供给记录**（{@code (group, period)} 命中）：没有供给记录的配额**没有上限**——
 *       那等于把"不能凭空重复"这条判据本身留成后门。
 * </ol>
 *
 * <p>★★ **缺键 = 空**（§11 的旧档兼容口径，照 {@code LedgerData} 的先例）：全部组件在本切片**都是新引入的**， 故 Jackson 绑成 null
 * 时一律收成空表 / 未激活，**此处不抛** —— 抛了等于"旧档全部读不回来"。方向是 fail-closed： 缺键 ⇒
 * 没有产业/没有阶层/没有债务/没有流水/没有劳动供给与配额/没有生产关系/<b>没有市场</b>/未激活。
 *
 * <p>★ **十四张表都保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——它的迭代序不是内容的纯函数（字节级往返因此不成立）。冻结那一步**写在字段赋值处** （SpotBugs 的 {@code EI_EXPOSE_REP}
 * 不做跨过程分析，只认它看得见的包装）。
 *
 * <p>★★ **{@code relations} 是第 8 个组件**（S1 阶段 4+5 Task 2；计划 R3）：键 = {@code IndustryId}（ {@link
 * ProductionRelation} 不另造 id —— 身份就是它结算的那个 {@code activity}，铁律 1），值 = 一次生产的结算规则。 ★ 两条**跨表守卫**：
 *
 * <ol>
 *   <li>每个键**必须**是该格上已存在的产业（"{@code 关系指名的产业不存在}"）——否则结算时按 id 取不到产业；
 *   <li>{@code relations[k].operator()} **必须**等于 {@code industries[k].operator()}：**同一件事不许有两处拼写**
 *       （"谁经营"若能在关系表里另写一遍，两边不一致时没有任何一处能判谁对）；键还**必须**等于 {@code ProductionRelation.activity()}（同
 *       {@code classes}/{@code flows} 的"键 == 值内 key"口径）。
 * </ol>
 *
 * <p>★ <b>S1 起 {@code classes}/{@code flows} 的键都是稳定家户身份 {@link HouseholdId}</b>（旧档的 {@link
 * CohortKey} 由 {@code EconomyCodec} 读入时映射成 {@code HouseholdId.ofLegacy}）：键不再随地点/阶层变化（铁律 1），
 * 行的当前视图住在 {@code ClassRow.view}。⇒ "这个产业有哪些行"不再由键的产业段回答，而由**劳动配额表**推（{@code
 * EconomySettlement.householdKeysOf}，唯一拼写点）—— 一个家户给两个产业出劳动时，它<b>只有一行</b>（V9/I1.2）。
 *
 * <p>★★ **{@code markets} 是第 9 个组件**（H4；裁定 M1-A）：键 = {@link HexCoord}（**格**），值 = {@link Market}
 * （每格**单一计价货币** + 一张商品价格表）。★ 三条口径：
 *
 * <ol>
 *   <li><b>缺格 = 该格没有市场</b>（合法状态，不抛）：结算对它什么都不做 —— 不造默认价、不猜一种货币；
 *   <li><b>键是格而不是市场 id</b>：本批的市场就是"某格的现货池"（同格供需直接撮合，见 {@code MarketSettlement}）， 多一个 {@code
 *       MarketId} 只会多一处可以漂开的身份（{@code MarketId} 那个契约留给"跨格市场节点"的后续增量）；
 *   <li>★ <b>它不含任何数量</b>：价格是数据、供需是每周期现算的（{@code MarketSettlement} 从家户账的会话工作副本读） ——
 *       市场表里没有"本期成交量"这类会过期的读数（读数在当天的 {@code ProductionLedger} 里；★ M2.7 的逐区读数是 {@code MarketReadout}
 *       在**读时**用它 + 进程内 {@code MarketReport} 现算的，同样不落进本切片）。
 * </ol>
 *
 * <p>★★ **{@code shipments} 是第 10 个组件**（M2.4）：键 = {@link ShipmentId}，值 = {@link
 * ShipmentBatch}（在途批次）。★ 它是**跨 tick 状态**：发运日建、到货日销；到货前目的地消费不到它。
 *
 * <p>★★ **S1 的第 11/12 个组件**：{@code memberships}（键 = {@link MembershipId}；成员份额，Σcount ==
 * 行人口的全局守卫在这里判）与 {@code assetShares}（键 = {@link AssetShareId}；R3B.1 起是<b>独立的实物资产份额总账</b>， 逐行判"键 ==
 * 值内 id / industry 存在 / 非空 / quantity ≥ 0"；★ <b>没有</b>"Σ quantity ≤ Industry.capacity" 这类
 * 把技术模板与实物账本绑死的上界守卫）。★ 第 13 个组件 {@code operatorConditions}（{@link OperatorCondition}；S3.2 的经营者状态机）由
 * S1 一次性补齐，本阶段空表缺省。
 *
 * <p>★ <b>守卫**不**检查 cohort 侧的行是否存在</b>（有意不加，同 {@code ActorData}「表与表之间没有引用完整性约束」的口径）： 逐组件增量落盘 ⇒
 * **关系先到、行后到是合法写序**；而 cohort 解析不到行在结算里是**正常状态**（人口为 0 的那些 cohort 就是如此，那一笔留在 {@code
 * residualOwner}）——把它判成非法会让"人口尚未种入"的世界构造不出来。
 */
public record EconomyData(
    Optional<EconomyMeta> meta,
    Map<IndustryId, Industry> industries,
    Map<HouseholdId, ClassRow> classes,
    Map<DebtId, Debt> debts,
    Map<HouseholdId, FlowRow> flows,
    Map<PeopleLotId, LaborSupply> laborSupply,
    Map<LaborAllocationId, LaborAllocation> allocations,
    Map<ProductionUnitId, ProductionRelation> relations,
    Map<HexCoord, Market> markets,
    Map<ShipmentId, ShipmentBatch> shipments,
    Map<MembershipId, Membership> memberships,
    Map<AssetShareId, AssetShare> assetShares,
    Map<ProductionUnitId, OperatorCondition> operatorConditions,
    Map<ProductionUnitId, ProductionUnit> units) {

  /** 往返用例的起点：未激活 + 十四张空表。 */
  public static EconomyData empty() {
    return new EconomyData(
        Optional.empty(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of());
  }

  public EconomyData {
    // ★ 缺键（null）⇒ 未激活 / 空表，见类注释（旧档兼容，fail-closed 方向）。
    if (meta == null) {
      meta = Optional.empty();
    }
    if (industries == null) {
      industries = Map.of();
    }
    if (classes == null) {
      classes = Map.of();
    }
    if (debts == null) {
      debts = Map.of();
    }
    if (flows == null) {
      flows = Map.of();
    }
    // ★ R2 的两个新组件：同一口径（缺键 ⇒ 空表，见类注释）。
    if (laborSupply == null) {
      laborSupply = Map.of();
    }
    if (allocations == null) {
      allocations = Map.of();
    }
    // ★ S1 的两个新组件：同一口径（缺键 ⇒ 空表，见类注释）。★ memberships 空表 = 旧档尚未迁移
    //   （由 LegacyHouseholdMigration 补齐）；运行期的类状态与成员份额必须同源（见下面的守恒守卫）。
    if (memberships == null) {
      memberships = Map.of();
    }
    if (assetShares == null) {
      assetShares = Map.of();
    }
    // ★ S3 预留的第 13 个组件（S3.2 的经营者状态机）：缺键 ⇒ 空表（与其余组件同一条旧档兼容口径）。
    if (operatorConditions == null) {
      operatorConditions = Map.of();
    }
    // ★★ R3B.2 第 14 个组件（生产单元）：缺键 ⇒ 空表（旧档由迁移器从资产份额反推默认 unit）。
    if (units == null) {
      units = Map.of();
    }
    // ★ 第 8 个组件（S1 阶段 4+5 Task 2）：同一口径（缺键 ⇒ 空表，见类注释）。★ 迁移器要读它，故提到迁移之前。
    if (relations == null) {
      relations = Map.of();
    }
    // ★★ R3B.2b：旧档 Industry 兼容位（record 末尾 5 个）→ 默认 unit + 整额 OWNED AssetShare。
    //   ★ **必须在 LegacyHouseholdMigration 之前**：迁移器要靠新造的 unit 才能把旧 relations /
    //     operatorConditions 键与 allocations.activity 对齐。
    //   ★ 它是 **Timeline 直读旧 changeset** 的兜底（那条路径不经过 EconomyCodec.decodeChangeSet 的节点整形）；
    //     codec/载荷已把兼容位摘成中性的路径下，这里的判据不成立 ⇒ 本段 no-op，两条路径幂等共存。
    //   ★ 判据 = "兼容位非中性"（operator 非 null / progress/劳动 > 0 / 两张表非空）；新形状恒中性。
    if (hasLegacyProductionBits(industries)) {
      Map<IndustryId, Industry> normalizedIndustries = new LinkedHashMap<>();
      Map<ProductionUnitId, ProductionUnit> normalizedUnits = new LinkedHashMap<>(units);
      Map<AssetShareId, AssetShare> normalizedShares = new LinkedHashMap<>(assetShares);
      Set<IndustryId> unitIndustries = new LinkedHashSet<>();
      for (ProductionUnit unit : normalizedUnits.values()) {
        unitIndustries.add(unit.industry());
      }
      Set<IndustryId> shareIndustries = new LinkedHashSet<>();
      for (AssetShare share : normalizedShares.values()) {
        shareIndustries.add(share.industry());
      }
      for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
        Industry industry = entry.getValue();
        IndustryId industryId = entry.getKey();
        if (industry == null || !hasLegacyProductionBits(industry)) {
          normalizedIndustries.put(industryId, industry);
          continue;
        }
        // ★ operator 缺省：与 EconomyCodec.migrateLegacyProductionComponents / EconomyPayloads 同一拼写点 ——
        //   industry.operator 有就用；否则 RegimeOperators.defaultOperator(regime, industryId)（不另拼）。
        ActorRef operator =
            industry.operator() != null
                ? industry.operator()
                : RegimeOperators.defaultOperator(industry.regime(), industryId);
        // ★ AssetShare：该 industry 尚无任何份额时才物化；只造 quantity > 0 的项（0 值不造行、也不构成生产规模）。
        //   id 走 AssetShare.idOf(..., sequence=0)：与既有迁移同一条 id 规则 ⇒ 重放/重跑不会重复。
        boolean hasExistingShare = shareIndustries.contains(industryId);
        boolean hasPositiveCapacity = false;
        for (Map.Entry<AssetKind, Long> capacity : industry.capacity().entrySet()) {
          if (capacity.getValue() <= 0L) {
            continue;
          }
          hasPositiveCapacity = true;
          if (hasExistingShare) {
            continue;
          }
          AssetShareId shareId =
              AssetShare.idOf(
                  industryId,
                  capacity.getKey(),
                  operator,
                  operator,
                  AssetShare.RightKind.OWNED,
                  0L);
          normalizedShares.putIfAbsent(
              shareId,
              new AssetShare(
                  shareId,
                  industryId,
                  capacity.getKey(),
                  operator,
                  operator,
                  capacity.getValue(),
                  AssetShare.RightKind.OWNED));
        }
        // ★ 默认 unit：同 industry 已有 unit 则不动（幂等）；capacity 全 0/空 ⇒ 旧档"规模恒 0、无生产"，不造 unit。
        //   进度/劳动/投入从兼容位原样带过。
        if (!unitIndustries.contains(industryId) && hasPositiveCapacity) {
          ProductionUnitId unitId = ProductionUnitId.idOf(industryId, operator);
          normalizedUnits.putIfAbsent(
              unitId,
              new ProductionUnit(
                  unitId,
                  industryId,
                  operator,
                  industryId.value(),
                  industry.progressDays(),
                  industry.cycleLaborMilli(),
                  industry.cycleInputUsedMilli()));
          unitIndustries.add(industryId);
        }
        // ★ 归一化后换成 12 参模板：兼容位清成中性 ⇒ EconomyChangeSet.between/diff 不再产生兼容位漂移。
        normalizedIndustries.put(
            industryId,
            new Industry(
                industryId,
                industry.name(),
                industry.regime(),
                industry.cycleDays(),
                industry.capacityPerUnit(),
                industry.dailyInputPerUnit(),
                industry.dailyLaborPerUnit(),
                industry.laborPerUnit(),
                industry.outputPerUnit(),
                industry.cycleInputPerUnit(),
                industry.slots(),
                industry.allocation()));
      }
      industries = normalizedIndustries;
      units = normalizedUnits;
      assetShares = normalizedShares;
    }
    // ★★ S1 旧档迁移（显式、幂等、可重放；见 LegacyHouseholdMigration 的类注）：
    //   旧 LaborAllocation 没有 household / 旧档没有 memberships 组件时，在这里一次性补齐。
    //   ★ 它必须发生在**所有守卫之前**：迁移后的状态才参与 pending 检查、Σ 守卫与关系归一。
    //   ★★ Minor-5（2026-09-28 评审）：这一步是**构造期自动**跑的 ⇒ 任何旧形状测试夹具（classes 非空但
    //   memberships/assetShares 为空、或 allocation 的 household 是 pending 占位）都会被静默补齐
    //   memberships/assetShares 后才进守卫。V 阶段适配旧测试/旧档时必须点名这条自动迁移（它不是夹具"本来就有"的
    //   组件，是构造期补出来的）；本批不改行为、只留提示。
    if (LegacyHouseholdMigration.needed(
        industries,
        units,
        classes,
        allocations,
        memberships,
        assetShares,
        relations,
        operatorConditions,
        meta)) {
      LegacyHouseholdMigration.Result migrated =
          LegacyHouseholdMigration.migrate(
              industries,
              units,
              classes,
              allocations,
              memberships,
              assetShares,
              relations,
              operatorConditions,
              meta);
      units = migrated.units();
      allocations = migrated.allocations();
      memberships = migrated.memberships();
      assetShares = migrated.assetShares();
      relations = migrated.relations();
      operatorConditions = migrated.operatorConditions();
      meta = migrated.meta();
    }
    // ★ 空表 = **全归 residualOwner 的等价路径**（裁定 E9）：没有规则不是坏数据，是"全部自留"。
    // ★ 第 9 个组件（H4）：同一口径（缺键 ⇒ 空表 = 世界上一个市场都没有，见类注释）。
    if (markets == null) {
      markets = Map.of();
    }
    Map<IndustryId, Industry> industriesCopy = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("industries 的键与值都不得为 null: " + entry.getKey());
      }
      industriesCopy.put(entry.getKey(), entry.getValue());
    }
    industries = Collections.unmodifiableMap(industriesCopy); // ★ 冻在赋值处
    Map<HouseholdId, ClassRow> classesCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : classes.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classes 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "classes 的键必须与 ClassRow.id 一致（S1 起键 = 家户稳定身份）：键="
                + entry.getKey()
                + "，行内 id="
                + entry.getValue().id());
      }
      ClassRow row = entry.getValue();
      // ★★ R4-B.4（R3 决策单 §0.1/§1.5，R3B.4）：**view 不再受 Industry.slots 约束**。
      //   旧守卫（view 必须命中该格产业的 slots 且 participationPerMille ≤ 该 slot 的
      //   laborParticipationPerMille）已删除；Industry.slots / ClassSlot 只作为生产方式内部的角色/劳动配置。
      //   ClassRow 自己的 [0,1000] 参与率守卫仍在（见 ClassRow 构造期）。
      classesCopy.put(entry.getKey(), row);
    }
    classes = Collections.unmodifiableMap(classesCopy); // ★ 冻在赋值处
    Map<DebtId, Debt> debtsCopy = new LinkedHashMap<>();
    for (Map.Entry<DebtId, Debt> entry : debts.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("debts 的键与值都不得为 null: " + entry.getKey());
      }
      debtsCopy.put(entry.getKey(), entry.getValue());
    }
    debts = Collections.unmodifiableMap(debtsCopy); // ★ 冻在赋值处
    // ★★ B.3b（R3 决策单 §0.3）：c1 孤儿债对账 —— 以 debts 表为唯一权威，按 debtor 分组、DebtId canonical
    //   升序重建每个 ClassRow.debts 引用。★ 必须在**跨表守卫之前**：守卫要求"引用的债存在"，而孤儿债是
    //   "债存在、引用缺失"；对账不碰债务表本身（principal/defaulted 守恒），只在 debtor/creditor 家户行缺失时
    //   fail-closed 具名抛。★ 迁移器之后：迁移只对齐 unit/劳动键，不改债务引用。
    classesCopy = DebtReferenceReconciler.reconcile(debtsCopy, classesCopy);
    classes = Collections.unmodifiableMap(classesCopy); // ★ 冻在赋值处（可能与上面同一实例）
    // ★ v2 spec §八.2：两张表的**交叉引用完整性**。★ 必须等两张表都建完再判 ——
    //   在任一段内查对方会陷入循环依赖（debts 要查 classes、classes 要查 debts），故不能靠调顺序解决。
    //   v1 的 debts 循环只查 null ⇒ 悬空主体能安静入库，错在结算里现形、根在状态里。
    //   ★ B.3b 起 classes 侧的引用已由上面的 DebtReferenceReconciler 重建过，本循环是对账后的兜底断言。
    for (Map.Entry<DebtId, Debt> entry : debtsCopy.entrySet()) {
      Debt debt = entry.getValue();
      if (!classesCopy.containsKey(debt.debtor()) || !classesCopy.containsKey(debt.creditor())) {
        throw new IllegalArgumentException(
            "债务的 debtor/creditor 必须是已存在的阶层行（v2 spec §八.2）："
                + entry.getKey()
                + " "
                + debt.debtor()
                + " → "
                + debt.creditor());
      }
    }
    for (Map.Entry<HouseholdId, ClassRow> entry : classesCopy.entrySet()) {
      for (DebtId debtId : entry.getValue().debts()) {
        if (!debtsCopy.containsKey(debtId)) {
          throw new IllegalArgumentException(
              "ClassRow.debts 引用了不存在的债务（v2 spec §八.2）：" + entry.getKey() + " → " + debtId);
        }
      }
    }
    Map<HouseholdId, FlowRow> flowsCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, FlowRow> entry : flows.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("flows 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "flows 的键必须与 FlowRow.id 一致（S1 起键 = 家户稳定身份）：键="
                + entry.getKey()
                + "，行内 id="
                + entry.getValue().id());
      }
      ClassRow flowRow = classesCopy.get(entry.getKey());
      if (flowRow == null) {
        throw new IllegalArgumentException("flows 的键必须是已存在的家户（S1 起两表同键）：" + entry.getKey());
      }
      requireIndustryRegistered(industriesCopy, flowRow.view(), "flows");
      flowsCopy.put(entry.getKey(), entry.getValue());
    }
    flows = Collections.unmodifiableMap(flowsCopy); // ★ 冻在赋值处
    // ── R2：劳动供给表 ────────────────────────────────────────────────────────────────────
    Map<PeopleLotId, LaborSupply> supplyCopy = new LinkedHashMap<>();
    for (Map.Entry<PeopleLotId, LaborSupply> entry : laborSupply.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("laborSupply 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().group())) {
        throw new IllegalArgumentException(
            "laborSupply 的键必须与 LaborSupply.group 一致：键="
                + entry.getKey()
                + "，行内 group="
                + entry.getValue().group());
      }
      supplyCopy.put(entry.getKey(), entry.getValue());
    }
    laborSupply = Collections.unmodifiableMap(supplyCopy); // ★ 冻在赋值处
    // ── R2：劳动分配表 + 三条结构判据（见类注释）────────────────────────────────────────────
    Set<String> industryIds = new LinkedHashSet<>();
    for (IndustryId id : industriesCopy.keySet()) {
      industryIds.add(id.value());
    }
    // ★★ R3B.2：劳动配额的 activity 是 unit id（String）⇒ "这份劳动喂哪条生产活动"按值查 unit。
    //   ★ 用 values 建索引（id 值 → unit）：units 表自身的键一致性由后面的 unit 守卫判。
    Map<String, ProductionUnit> unitsByValue = new LinkedHashMap<>();
    for (ProductionUnit unit : units.values()) {
      unitsByValue.put(unit.id().value(), unit);
    }
    Map<LaborAllocationId, LaborAllocation> allocationsCopy = new LinkedHashMap<>();
    Map<PeopleLotId, Long> allocatedPerGroup = new LinkedHashMap<>();
    for (Map.Entry<LaborAllocationId, LaborAllocation> entry : allocations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("allocations 的键与值都不得为 null: " + entry.getKey());
      }
      LaborAllocation allocation = entry.getValue();
      if (!entry.getKey().equals(allocation.id())) {
        throw new IllegalArgumentException(
            "allocations 的键必须与 LaborAllocation.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + allocation.id());
      }
      // ①-b ★★ S1：这份劳动必须属于一个已存在的家户（或旧档迁移期的 pending 占位 —— 只有
      //   EconomyCodec 的旧档反序列化器会造它，迁移器必须在交回状态前换成真实家户）。
      HouseholdId allocatedHousehold = allocation.household();
      if (!allocatedHousehold.isPending() && !classesCopy.containsKey(allocatedHousehold)) {
        throw new IllegalArgumentException(
            "劳动分配的家户必须是已存在的家户（S1 起身份与视图分离）："
                + entry.getKey()
                + " 的 household="
                + allocatedHousehold
                + " 不在 classes 里");
      }
      // ① actor ↔ 产业 的**双条件**（结算按 actor id 归属劳动，故两侧都得判：产业型必须指名存在的产业；
      //    非产业型不得与产业 id 撞名 —— 撞名会让"家户的配额"静默算进那个产业）。
      //   ★★ **R3 起 HOUSEHOLD 是"自由档"**：农村家庭纺织是一个**由家户承担的生产过程**（spec §四 的压力测试）
      //     ⇒ 家户的 actor id **可以**命名一个产业（那时它的配额照进该产业的 cycleLaborMilli），**也可以不命名**
      //     （那时它只是一个消费主体，配额只进守恒与读口）。这两种都是**有意为之**，故不判错。
      //     其余非产业型（PEOPLE_LOT/UNIT/GOVERNMENT/ORGANIZATION）仍**不许**撞产业 id。
      ActorKind kind = allocation.actor().kind();
      boolean mustResolveToIndustry = kind == ActorKind.ESTATE || kind == ActorKind.WORKSHOP;
      boolean mayResolveToIndustry = mustResolveToIndustry || kind == ActorKind.HOUSEHOLD;
      boolean resolvesToIndustry = industryIds.contains(allocation.actor().id());
      if (mustResolveToIndustry && !resolvesToIndustry) {
        throw new IllegalArgumentException(
            "劳动分配的 actor 与产业 id 的对应关系不成立（结算按 actor id 把配额归给产业）：kind="
                + kind
                + "，id="
                + allocation.actor().id()
                + " ⇒ 产业型主体的 id 必须是一个已存在的产业");
      }
      if (resolvesToIndustry && !mayResolveToIndustry) {
        throw new IllegalArgumentException(
            "劳动分配的 actor 与产业 id 的对应关系不成立（结算按 actor id 把配额归给产业）：kind="
                + kind
                + "，id="
                + allocation.actor().id()
                + " ⇒ 非产业型主体的 id 不得与任何产业 id 相同");
      }
      // ①-c ★★ R3B.2：activity 若指名了现存 unit，则收劳动的主体必须就是该 unit 的 operator ——
      //   "劳动喂了谁"（activity）与"谁收劳动"（actor）是同一件事的两处拼写，不一致时没有哪一处能判谁对。
      //   ★ activity 不命中 unit 的配额合法（自由家户劳动/旧档未接线档）：它不喂任何生产，只进守恒与读口。
      ProductionUnit referencedUnit = unitsByValue.get(allocation.activity());
      if (referencedUnit == null && allocation.activity().startsWith(UNIT_ID_PREFIX)) {
        throw new IllegalArgumentException(
            "劳动配额的 activity 看起来是 unit id（以 \""
                + UNIT_ID_PREFIX
                + "\" 开头）但该 unit 不存在（悬空引用；自由家户劳动请用非 unit 的活动词）: "
                + allocation);
      }
      if (referencedUnit != null && !referencedUnit.operator().equals(allocation.actor())) {
        throw new IllegalArgumentException(
            "劳动配额的 activity 指名的 unit 与 actor 不一致（同一件事不许两处拼写）：activity="
                + allocation.activity()
                + " unit.operator="
                + referencedUnit.operator()
                + "，actor="
                + allocation.actor());
      }
      // ② 配额必须有**同期**的供给记录（没有供给的配额没有上限）。
      LaborSupply supply = supplyCopy.get(allocation.group());
      if (supply == null || supply.period() != allocation.period()) {
        throw new IllegalArgumentException(
            "劳动分配 "
                + entry.getKey()
                + " 的批次 "
                + allocation.group()
                + " 在第 "
                + allocation.period()
                + " 周期没有劳动供给记录（没有供给的配额没有上限，拒绝）："
                + (supply == null ? "该批次完全没有供给记录" : "供给记录在第 " + supply.period() + " 周期"));
      }
      allocatedPerGroup.merge(allocation.group(), allocation.laborMilli(), Long::sum);
      allocationsCopy.put(entry.getKey(), allocation);
    }
    allocations = Collections.unmodifiableMap(allocationsCopy); // ★ 冻在赋值处
    // ③ ★★ **Σ allocated ≤ available**（本阶段最重要的不变量，见类注释）。
    for (Map.Entry<PeopleLotId, Long> entry : allocatedPerGroup.entrySet()) {
      long available = supplyCopy.get(entry.getKey()).availableLabor();
      if (entry.getValue() > available) {
        throw new IllegalArgumentException(
            "批次 "
                + entry.getKey()
                + " 的劳动配额之和 "
                + entry.getValue()
                + " 超过其可用劳动 "
                + available
                + "（同一批人的劳动不得被两个产业各算一次满额，设计稿 §四）");
      }
    }
    // ── R3B.1 第 12 个组件：实物资产份额表 ──────────────────────────────────────────────
    //   ★ 键 == 值内 id；industry 必须存在；asset/owner/operator/kind 非空、quantity ≥ 0（非空与 quantity
    //     由 AssetShare 构造期判，这里判跨表的 industry 引用与键身份）。
    //   ★★ **这里不再有"Σ quantity ≤ Industry.capacity"的上界守卫，也不写 Σ == capacity**：AssetShare 是
    //     独立的实物资产总账，Industry.capacity 在 B.1 里只是过渡字段（B.2 移出生产模型）；把技术模板当
    //     实物账本上界，会把"实物已存在、模板尚未及更新"这类合法状态误判成坏数据。
    //   ★ 关系表的 operator 与 unit 的关系由**第 14 个组件（units）**的守卫判（R3B.2 起关系挂在 unit 上）。
    Map<AssetShareId, AssetShare> assetSharesCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetShareId, AssetShare> entry : assetShares.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("assetShares 的键与值都不得为 null: " + entry.getKey());
      }
      AssetShare share = entry.getValue();
      if (!entry.getKey().equals(share.id())) {
        throw new IllegalArgumentException(
            "assetShares 的键必须与 AssetShare.id 一致：键=" + entry.getKey() + "，行内 id=" + share.id());
      }
      if (!industriesCopy.containsKey(share.industry())) {
        throw new IllegalArgumentException("资产份额指名的产业不存在: " + share.industry());
      }
      assetSharesCopy.put(entry.getKey(), share);
    }
    assetShares = Collections.unmodifiableMap(assetSharesCopy); // ★ 冻在赋值处

    // ── 第 8 个组件：生产关系表（S1 阶段 4+5 Task 2；计划 R3；R3B.2 起键/activity = unit id）────────
    Map<ProductionUnitId, ProductionRelation> relationsRaw = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionRelation> entry : relations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("relations 的键与值都不得为 null: " + entry.getKey());
      }
      relationsRaw.put(entry.getKey(), entry.getValue());
    }
    // ★★ S1：把旧档/默认关系里的 {@code ToCohort(view)} 一对一归一到 {@code ToHousehold(id)}
    //   （同一 view 恰有一个家户时才归一 —— 一一对应是旧档的既有事实；view 有歧义时保留 ToCohort
    //   作为 S3 的视图选择器，不在构造期猜）。归一化是"键/受方迁移"的唯一落点，幂等。
    Map<ProductionUnitId, ProductionRelation> relationsCopy =
        normalizeRecipients(relationsRaw, classesCopy);
    relations = Collections.unmodifiableMap(relationsCopy); // ★ 冻在赋值处
    // ★★ 两条跨表守卫（R3B.2 改口径）：① 键 == 值内 activity（同 classes/flows 的键身份口径）；
    //   ② 关系挂在一个**已存在的 unit** 上、且 operator 与 unit.operator 逐值相等
    //      （"谁经营"若能在关系表里另写一遍，两边不一致时没有任何一处能判谁对）。
    for (Map.Entry<ProductionUnitId, ProductionRelation> entry : relationsCopy.entrySet()) {
      ProductionRelation relation = entry.getValue();
      if (!entry.getKey().equals(relation.activity())) {
        throw new IllegalArgumentException(
            "relations 的键必须与 ProductionRelation.activity 一致：键="
                + entry.getKey()
                + "，行内 activity="
                + relation.activity());
      }
      ProductionUnit unit = unitsByValue.get(entry.getKey().value());
      if (unit == null) {
        throw new IllegalArgumentException(
            "关系指名的生产单元（unit）不存在："
                + entry.getKey()
                + "（旧档请先由 LegacyHouseholdMigration 把旧 industry 键对齐到 unit）");
      }
      if (!relation.operator().equals(unit.operator())) {
        throw new IllegalArgumentException(
            "关系的 operator 必须与 unit.operator 一致（同一件事不许两处拼写）："
                + entry.getKey()
                + " 关系="
                + relation.operator()
                + "，unit="
                + unit.operator());
      }
    }
    // ── S1 第 11 个组件：成员份额表 ───────────────────────────────────────────────────────
    //   ★ 键 = MembershipId；键 == 值内 id；household 必须存在；count ≥ 0。
    //   ★ 全局守恒（计划 §6.1 第 1 条的第二半）：Σ Membership.count == Σ ClassRow.population。
    //     逐批 == PopulationGroup.count 要读 social，由 app 协调器在同一 revision 内判（economy 看不见 social）。
    Map<MembershipId, Membership> membershipsCopy = new LinkedHashMap<>();
    long membershipTotal = 0L;
    for (Map.Entry<MembershipId, Membership> entry : memberships.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("memberships 的键与值都不得为 null: " + entry.getKey());
      }
      Membership membership = entry.getValue();
      if (!entry.getKey().equals(membership.id())) {
        throw new IllegalArgumentException(
            "memberships 的键必须与 Membership.id 一致：键=" + entry.getKey() + "，行内 id=" + membership.id());
      }
      if (membership.household().isPending()) {
        throw new IllegalArgumentException(
            "memberships 的 household 不得是旧档迁移占位（迁移器必须先把它换成真实家户）：" + membership);
      }
      if (!classesCopy.containsKey(membership.household())) {
        throw new IllegalArgumentException(
            "membership 的家户必须是已存在的家户：" + membership.id() + " → " + membership.household());
      }
      membershipTotal = Math.addExact(membershipTotal, membership.count());
      membershipsCopy.put(entry.getKey(), membership);
    }
    memberships = Collections.unmodifiableMap(membershipsCopy); // ★ 冻在赋值处
    if (!membershipsCopy.isEmpty()) {
      long classPopulation = 0L;
      for (ClassRow row : classesCopy.values()) {
        classPopulation = Math.addExact(classPopulation, row.population());
      }
      if (classPopulation != membershipTotal) {
        throw new IllegalArgumentException(
            "Σ Membership.count 必须等于 Σ ClassRow.population（S1 §6.1 第 1 条）：成员份额="
                + membershipTotal
                + "，行人口="
                + classPopulation);
      }
    }
    // ── 第 9 个组件：市场表（H4；每格一个现货市场）──────────────────────────────────────
    //   ★ 键 = 格（{@link HexCoord}）；**缺格 = 该格没有市场**（合法状态 —— 结算对它什么都不做，见 Market 的类注）。
    //   ★ 只判 null 与结构：**价格是数据**（GM 可调），这里没有"价格该是多少""哪些商品该有价"的判据 ——
    //     那些是 GM 的判断（信条十二），不是状态类型的守卫。
    Map<HexCoord, Market> marketsCopy = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, Market> entry : markets.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("markets 的键与值都不得为 null: " + entry.getKey());
      }
      marketsCopy.put(entry.getKey(), entry.getValue());
    }
    markets = Collections.unmodifiableMap(marketsCopy); // ★ 冻在赋值处
    // ── 第 10 个组件：在途批次表（M2.4；跨 tick 状态）───────────────────────────────────────
    //   ★ 键 = {@link ShipmentId}（{@code sh-<day>-<seq>}）；**缺键 = 这个世界的货物都在账上、没有在途**（合法状态）。
    //   ★ 它在到货日由日循环销账、在发运日由区域撮合建账；到货前目的地**消费不到它**（判据落在这一维）。
    if (shipments == null) {
      shipments = Map.of();
    }
    Map<ShipmentId, ShipmentBatch> shipmentsCopy = new LinkedHashMap<>();
    for (Map.Entry<ShipmentId, ShipmentBatch> entry : shipments.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("shipments 的键与值都不得为 null: " + entry.getKey());
      }
      shipmentsCopy.put(entry.getKey(), entry.getValue());
    }
    shipments = Collections.unmodifiableMap(shipmentsCopy); // ★ 冻在赋值处
    // ── 第 13 个组件：经营者状态表（S3.2；R3B.2 起键 = ProductionUnitId，形状/词表不变）──────────
    //   ★ 键 = unit id；值内 {@code industry} 仍是技术模板 id（形状不动）⇒ 守卫判"key 的 unit 存在"且
    //     "值内 industry == unit.industry"。
    //   ★ 空表合法：S3 之前没有状态机读者，空表 = "尚未登记任何经营者状态"（不是坏数据）。
    Map<ProductionUnitId, OperatorCondition> operatorConditionsCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, OperatorCondition> entry : operatorConditions.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("operatorConditions 的键与值都不得为 null: " + entry.getKey());
      }
      ProductionUnit unit = unitsByValue.get(entry.getKey().value());
      if (unit == null) {
        throw new IllegalArgumentException(
            "operatorConditions 指名的生产单元（unit）不存在："
                + entry.getKey()
                + "（旧档请先由 LegacyHouseholdMigration 把旧 industry 键对齐到 unit）");
      }
      if (!unit.industry().equals(entry.getValue().industry())) {
        throw new IllegalArgumentException(
            "operatorConditions 值内的 industry 必须与 unit.industry 一致：键="
                + entry.getKey()
                + "，unit.industry="
                + unit.industry()
                + "，值内="
                + entry.getValue().industry());
      }
      operatorConditionsCopy.put(entry.getKey(), entry.getValue());
    }
    operatorConditions = Collections.unmodifiableMap(operatorConditionsCopy); // ★ 冻在赋值处
    // ── R3B.2 第 14 个组件：生产单元表（units）────────────────────────────────────────────
    //   ★ 键 == unit.id；industry 必须存在；progressDays ≤ industry.cycleDays（跨表上界）。
    //   ★★ **迁移完成态**：每个非 EXITED/ABANDONED 的 unit，其 operator 至少有一条同 industry 的
    //     AssetShare（否则明确抛，不静默）—— 这条守卫是"AssetShare 是实物总账、unit 是实际生产"两件事
    //     之间的桥：没有份额的 unit 没有任何产能来源，让它留在 ACTIVE 或让它在结算里静默产出都是坏数据。
    Map<ProductionUnitId, ProductionUnit> unitsCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionUnit> entry : units.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("units 的键与值都不得为 null: " + entry.getKey());
      }
      ProductionUnit unit = entry.getValue();
      if (!entry.getKey().equals(unit.id())) {
        throw new IllegalArgumentException(
            "units 的键必须与 ProductionUnit.id 一致：键=" + entry.getKey() + "，行内 id=" + unit.id());
      }
      Industry industryTemplate = industriesCopy.get(unit.industry());
      if (industryTemplate == null) {
        throw new IllegalArgumentException("生产单元指名的产业（技术模板）不存在: " + unit.industry());
      }
      if (unit.progressDays() > industryTemplate.cycleDays()) {
        throw new IllegalArgumentException(
            "ProductionUnit.progressDays 必须 ∈ [0, industry.cycleDays]：unit="
                + entry.getKey()
                + " progressDays="
                + unit.progressDays()
                + "，cycleDays="
                + industryTemplate.cycleDays());
      }
      unitsCopy.put(entry.getKey(), unit);
    }
    units = Collections.unmodifiableMap(unitsCopy); // ★ 冻在赋值处
    // ★★ B.3b：**unit 不要求必须有 AssetShare**（R4 计划禁把"资产闲置/退出"判成坏数据）。
    //   资产可以全部转走（见 economy.TransferAssetShare），此时该 unit 的规模由
    //   ProductionUnitBook 纯派生为 0 —— "有经营者、无资产、不生产"是合法状态（也是 E1 退出处置的前态）。
    //   unit↔relation / operatorConditions 的 operator/industry 一致性已由上面两段守卫把守，不因本放宽而松。
  }

  /**
   * ★★ <b>flows 行的结构引用完整性</b>：flow 行的 view 指出它住在哪一格，该格必须登记过至少一个产业 —— 否则这行流水没有落点。
   *
   * <p>★★ <b>R4-B.4 收窄</b>：本方法**只**判"该格存在产业"。旧版还判 view 必须命中该格产业的 {@code ClassSlot}、并返回最紧槽位供调用方限制
   * {@code participationPerMille}；该槽位耦合已删除 —— {@code Industry.slots} / {@code ClassSlot}
   * 只作为生产方式内部的角色/劳动配置， 不再是 {@code ClassRow.view} 的限制来源。{@code classes} 侧也不再调用本方法（view 完全由 {@code
   * HouseholdClassRule} 纯派生）。
   */
  private static void requireIndustryRegistered(
      Map<IndustryId, Industry> industries, CohortKey key, String what) {
    String hexKey = IndustryHexKeys.hexKey(key.hex().q(), key.hex().r());
    for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
      // ★ **没有格键的产业 id**（{@code IndustryId} 允许这种值，真档里不会出现）：说不出它在哪一格 ⇒
      //   **不拿它来否决**（对任何格都算"可能"）。★ 反过来，带了格键的必须**逐字相等**才算命中 ——
      //   `1_10` 与 `1_1` 因此不会互相误命中（同 {@code IndustryHexKeys.at} 的口径）。
      if (IndustryHexKeys.hexKeyOf(entry.getKey()).filter(hexKey::equals).isEmpty()
          && IndustryHexKeys.hexKeyOf(entry.getKey()).isPresent()) {
        continue;
      }
      return; // 该格有产业登记
    }
    // ★ 消息保留"不存在的产业"这几个字：它是既有的判据用语（{@code EconomyInvariantsTest} 逐字钉着）。
    throw new IllegalArgumentException(
        what + " 引用了不存在的产业（该格上没有登记任何产业）: " + hexKey + "（键=" + key + "）");
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withMeta(Optional<EconomyMeta> value) {
    return new EconomyData(
        value,
        industries,
        classes,
        debts,
        flows,
        laborSupply,
        allocations,
        relations,
        markets,
        shipments,
        memberships,
        assetShares,
        operatorConditions,
        units);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withIndustries(Map<IndustryId, Industry> value) {
    return new EconomyData(
        meta,
        value,
        classes,
        debts,
        flows,
        laborSupply,
        allocations,
        relations,
        markets,
        shipments,
        memberships,
        assetShares,
        operatorConditions,
        units);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withClasses(Map<HouseholdId, ClassRow> value) {
    return new EconomyData(
        meta,
        industries,
        value,
        debts,
        flows,
        laborSupply,
        allocations,
        relations,
        markets,
        shipments,
        memberships,
        assetShares,
        operatorConditions,
        units);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withDebts(Map<DebtId, Debt> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        value,
        flows,
        laborSupply,
        allocations,
        relations,
        markets,
        shipments,
        memberships,
        assetShares,
        operatorConditions,
        units);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withFlows(Map<HouseholdId, FlowRow> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debts,
        value,
        laborSupply,
        allocations,
        relations,
        markets,
        shipments,
        memberships,
        assetShares,
        operatorConditions,
        units);
  }

  /** 一个组件一个 with（R2：劳动供给表）；其余十二个组件原样带过。 */
  public EconomyData withLaborSupply(Map<PeopleLotId, LaborSupply> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debts,
        flows,
        value,
        allocations,
        relations,
        markets,
        shipments,
        memberships,
        assetShares,
        operatorConditions,
        units);
  }

  /** 一个组件一个 with（R2：劳动分配表）；其余十二个组件原样带过。 */
  public EconomyData withAllocations(Map<LaborAllocationId, LaborAllocation> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debts,
        flows,
        laborSupply,
        value,
        relations,
        markets,
        shipments,
        memberships,
        assetShares,
        operatorConditions,
        units);
  }

  /** 一个组件一个 with（T2：生产关系表）；其余十二个组件原样带过。 */
  public EconomyData withRelations(Map<ProductionUnitId, ProductionRelation> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debts,
        flows,
        laborSupply,
        allocations,
        value,
        markets,
        shipments,
        memberships,
        assetShares,
        operatorConditions,
        units);
  }

  /**
   * 一个组件一个 with（H4：市场表）；其余十二个组件原样带过。
   *
   * <p>★ <b>它是"GM 定价格"的唯一写入口</b>（铁律 2：所有修改最终表示为 Command → ChangeSet → Revision）——
   * 本批还没有"设价"命令，故它现在只被载荷（创世播种）与用例用到；命令留待 GM 参数目录落地。
   */
  public EconomyData withMarkets(Map<HexCoord, Market> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debts,
        flows,
        laborSupply,
        allocations,
        relations,
        value,
        shipments,
        memberships,
        assetShares,
        operatorConditions,
        units);
  }

  /**
   * ★★ <b>第 10 个组件（M2.4）：在途批次表</b>；其余十二个组件原样带过。
   *
   * <p>★ 与 {@link #withMarkets} 同款：它是"跨 tick 状态"的唯一写入口（在日循环的到货销账与发运建账里被调用）， 不是 GM 命令面。
   */
  public EconomyData withShipments(Map<ShipmentId, ShipmentBatch> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debts,
        flows,
        laborSupply,
        allocations,
        relations,
        markets,
        value,
        memberships,
        assetShares,
        operatorConditions,
        units);
  }

  /** 一个组件一个 with（S1：成员份额表）；其余十二个组件原样带过。 */
  public EconomyData withMemberships(Map<MembershipId, Membership> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debts,
        flows,
        laborSupply,
        allocations,
        relations,
        markets,
        shipments,
        value,
        assetShares,
        operatorConditions,
        units);
  }

  /** 一个组件一个 with（R3B.1：实物资产份额表）；其余十二个组件原样带过。 */
  public EconomyData withAssetShares(Map<AssetShareId, AssetShare> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debts,
        flows,
        laborSupply,
        allocations,
        relations,
        markets,
        shipments,
        memberships,
        value,
        operatorConditions,
        units);
  }

  /** 一个组件一个 with（S3.2：经营者状态表）；其余十二个组件原样带过。 */
  public EconomyData withOperatorConditions(Map<ProductionUnitId, OperatorCondition> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debts,
        flows,
        laborSupply,
        allocations,
        relations,
        markets,
        shipments,
        memberships,
        assetShares,
        value,
        units);
  }

  /** ★★ R3B.2：生产单元表（第 14 个组件）；其余十三个组件原样带过。 */
  public EconomyData withUnits(Map<ProductionUnitId, ProductionUnit> value) {
    return new EconomyData(
        meta,
        industries,
        classes,
        debts,
        flows,
        laborSupply,
        allocations,
        relations,
        markets,
        shipments,
        memberships,
        assetShares,
        operatorConditions,
        value);
  }

  /** {@link ProductionUnitId#idOf} 的固定前缀（唯一拼写点；用来识别"这看起来是一个 unit id"）。 */
  private static final String UNIT_ID_PREFIX = "unit-";

  /** ★★ B.2b：任一 Industry 带非中性旧档兼容位 ⇒ 构造期归一化（见 compact 构造器那一段）。 */
  private static boolean hasLegacyProductionBits(Map<IndustryId, Industry> industries) {
    for (Industry industry : industries.values()) {
      if (industry != null && hasLegacyProductionBits(industry)) {
        return true;
      }
    }
    return false;
  }

  /**
   * ★★ B.2b：单个 {@link Industry} 的旧档兼容位判据（与修复规格逐字一致）：{@code operator != null || progressDays > 0 ||
   * cycleLaborMilli > 0 || !capacity.isEmpty() || !cycleInputUsedMilli.isEmpty()}。
   *
   * <p>★ 新形状（12 参构造器 / 归一化后）恒为 {@code false} —— 这是"codec 已整形路径 no-op、Timeline 直读 changeset
   * 路径兜底"的幂等边界。
   */
  private static boolean hasLegacyProductionBits(Industry industry) {
    return industry.operator() != null
        || industry.progressDays() > 0L
        || industry.cycleLaborMilli() > 0L
        || !industry.capacity().isEmpty()
        || !industry.cycleInputUsedMilli().isEmpty();
  }

  /**
   * ★★ S1：把 {@link Recipient.ToCohort} 一对一归一到 {@link Recipient.ToHousehold}。
   *
   * <p>判据：<b>该 view 恰有一个家户</b>（旧档的既有事实）⇒ 迁移；view 有歧义（S3 才允许）⇒ 保留旧变体交由 S3
   * 的视图选择器解释。归一化是构造期的纯函数、幂等（归一后的规则不再含 ToCohort）。
   *
   * <p>★ <b>S3 审计</b>：索引每次都用**当前** {@code classes} 行集合重建，不缓存历史 view。默认关系在第一次构造时就已归一为 {@code
   * ToHousehold(稳定身份)} ⇒ 之后的阶层写回只改 {@code ClassRow.view}，不会让这些规则改指到别的家户；仍保留的 {@code ToCohort}
   * 是旧档/多义视图的兼容窄口，按当前行集合解释。
   */
  private static Map<ProductionUnitId, ProductionRelation> normalizeRecipients(
      Map<ProductionUnitId, ProductionRelation> raw, Map<HouseholdId, ClassRow> classes) {
    Map<CohortKey, HouseholdId> unique = new LinkedHashMap<>();
    Set<CohortKey> ambiguous = new LinkedHashSet<>();
    for (ClassRow row : classes.values()) {
      if (unique.putIfAbsent(row.view(), row.id()) != null) {
        ambiguous.add(row.view());
      }
    }
    for (CohortKey view : ambiguous) {
      unique.remove(view);
    }
    Map<ProductionUnitId, ProductionRelation> normalized = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionRelation> entry : raw.entrySet()) {
      ProductionRelation relation = entry.getValue();
      List<CompensationRule> rules = new ArrayList<>(relation.rules().size());
      boolean changed = false;
      for (CompensationRule rule : relation.rules()) {
        if (rule.recipient() instanceof Recipient.ToCohort toCohort) {
          HouseholdId household = unique.get(toCohort.cohort());
          if (household != null) {
            rules.add(
                new CompensationRule(
                    rule.type(),
                    new Recipient.ToHousehold(household),
                    rule.pool(),
                    rule.weight(),
                    rule.ratePerMille(),
                    rule.fixedAmount(),
                    rule.commodity(),
                    rule.currency(),
                    rule.priority()));
            changed = true;
            continue;
          }
        }
        rules.add(rule);
      }
      normalized.put(
          entry.getKey(),
          changed
              ? new ProductionRelation(
                  relation.activity(),
                  relation.operator(),
                  relation.inputSupplier(),
                  rules,
                  relation.residualOwner(),
                  relation.laborSource())
              : relation);
    }
    return normalized;
  }
}

package io.mosire.simos.economy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.id.UseRightId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.migrate.LegacyHouseholdMigration;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.UseRight;
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
 * <p>★ **十三个组件与 {@link io.mosire.simos.economy.change.EconomyChangeSet} 的十三个组件一一对应**（铁律 5）：
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
 * <p>★ **十三张表都保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
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
 * 行人口的全局守卫在这里判）与 {@code useRights}（键 = {@link UseRightId}；使用权，Σquantity ≤ Industry.capacity
 * 的守卫在这里判）。★ 第 13 个组件 {@code operatorConditions}（{@link OperatorCondition}；S3.2 的经营者状态机）由 S1
 * 一次性补齐，本阶段空表缺省。
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
    Map<IndustryId, ProductionRelation> relations,
    Map<HexCoord, Market> markets,
    Map<ShipmentId, ShipmentBatch> shipments,
    Map<MembershipId, Membership> memberships,
    Map<UseRightId, UseRight> useRights,
    Map<IndustryId, OperatorCondition> operatorConditions) {

  /** 往返用例的起点：未激活 + 十三张空表。 */
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
    if (useRights == null) {
      useRights = Map.of();
    }
    // ★ S3 预留的第 13 个组件（S3.2 的经营者状态机）：缺键 ⇒ 空表（与其余组件同一条旧档兼容口径）。
    if (operatorConditions == null) {
      operatorConditions = Map.of();
    }
    // ★★ S1 旧档迁移（显式、幂等、可重放；见 LegacyHouseholdMigration 的类注）：
    //   旧 LaborAllocation 没有 household / 旧档没有 memberships 组件时，在这里一次性补齐。
    //   ★ 它必须发生在**所有守卫之前**：迁移后的状态才参与 pending 检查、Σ 守卫与关系归一。
    if (LegacyHouseholdMigration.needed(
        industries, classes, allocations, memberships, useRights, meta)) {
      LegacyHouseholdMigration.Result migrated =
          LegacyHouseholdMigration.migrate(
              industries, classes, allocations, memberships, useRights, meta);
      allocations = migrated.allocations();
      memberships = migrated.memberships();
      useRights = migrated.useRights();
      meta = migrated.meta();
    }
    // ★ 第 8 个组件（S1 阶段 4+5 Task 2）：同一口径（缺键 ⇒ 空表，见类注释）。
    //   ★ 空表 = **全归 residualOwner 的等价路径**（裁定 E9）：没有规则不是坏数据，是"全部自留"。
    if (relations == null) {
      relations = Map.of();
    }
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
      ClassSlot slot = requireStratumAllowed(industriesCopy, entry.getValue().view(), "classes");
      ClassRow row = entry.getValue();
      // ★ v2 spec §八.1：`0 ≤ participationPerMille ≤ slot.laborParticipationPerMille ≤ 1000` 里，
      //   中间那条**只有这里能判**（ClassRow 只守了 [0,1000] 两头，槽位上限要跨对象）。
      //   不守的后果：laborMilli × participationPerMille ÷ 1000 被悄悄放大 ⇒ 劳动瓶颈、产出、
      //   按劳动权重的分配全变大，而账面看不出来（不凭空造粮，但凭空造劳动）。
      if (row.participationPerMille() > slot.laborParticipationPerMille()) {
        throw new IllegalArgumentException(
            "classes 的 participationPerMille 不得超过其槽位上限（v2 spec §八.1）："
                + entry.getKey()
                + "（view="
                + row.view()
                + "）行="
                + row.participationPerMille()
                + "‰ > 槽位="
                + slot.laborParticipationPerMille()
                + "‰");
      }
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
    // ★ v2 spec §八.2：两张表的**交叉引用完整性**。★ 必须等两张表都建完再判 ——
    //   在任一段内查对方会陷入循环依赖（debts 要查 classes、classes 要查 debts），故不能靠调顺序解决。
    //   v1 的 debts 循环只查 null ⇒ 悬空主体能安静入库，错在结算里现形、根在状态里。
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
      requireStratumAllowed(industriesCopy, flowRow.view(), "flows");
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
    // ── S1 第 12 个组件：使用权表 ─────────────────────────────────────────────────────────
    //   ★ 键 == 值内 id；activity 必须存在；每个 (activity, asset) 的 Σ quantity ≤ Industry.capacity；
    //     关系表的 operator 若该产业有使用权，必须是其中一个 holder（S1 §6.1 第 6/7 条）。
    Map<UseRightId, UseRight> useRightsCopy = new LinkedHashMap<>();
    Map<IndustryId, Map<AssetKind, Long>> usedByActivity = new LinkedHashMap<>();
    Map<IndustryId, Set<ActorRef>> holdersByActivity = new LinkedHashMap<>();
    for (Map.Entry<UseRightId, UseRight> entry : useRights.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("useRights 的键与值都不得为 null: " + entry.getKey());
      }
      UseRight useRight = entry.getValue();
      if (!entry.getKey().equals(useRight.id())) {
        throw new IllegalArgumentException(
            "useRights 的键必须与 UseRight.id 一致：键=" + entry.getKey() + "，行内 id=" + useRight.id());
      }
      if (!industriesCopy.containsKey(useRight.activity())) {
        throw new IllegalArgumentException("使用权指名的产业不存在: " + useRight.activity());
      }
      usedByActivity
          .computeIfAbsent(useRight.activity(), ignored -> new LinkedHashMap<>())
          .merge(useRight.asset(), useRight.quantity(), Math::addExact);
      holdersByActivity
          .computeIfAbsent(useRight.activity(), ignored -> new LinkedHashSet<>())
          .add(useRight.holder());
      useRightsCopy.put(entry.getKey(), useRight);
    }
    for (Map.Entry<IndustryId, Map<AssetKind, Long>> entry : usedByActivity.entrySet()) {
      Industry industry = industriesCopy.get(entry.getKey());
      for (Map.Entry<AssetKind, Long> usage : entry.getValue().entrySet()) {
        long capacity = industry.capacity().getOrDefault(usage.getKey(), 0L);
        if (usage.getValue() > capacity) {
          throw new IllegalArgumentException(
              "使用权的 Σ quantity 不得超过 Industry.capacity（S1 §6.1 第 6 条）："
                  + entry.getKey()
                  + " / "
                  + usage.getKey()
                  + " 使用权="
                  + usage.getValue()
                  + " > capacity="
                  + capacity);
        }
      }
    }
    useRights = Collections.unmodifiableMap(useRightsCopy); // ★ 冻在赋值处

    // ── 第 8 个组件：生产关系表（S1 阶段 4+5 Task 2；计划 R3）──────────────────────────────
    Map<IndustryId, ProductionRelation> relationsRaw = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, ProductionRelation> entry : relations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("relations 的键与值都不得为 null: " + entry.getKey());
      }
      relationsRaw.put(entry.getKey(), entry.getValue());
    }
    // ★★ S1：把旧档/默认关系里的 {@code ToCohort(view)} 一对一归一到 {@code ToHousehold(id)}
    //   （同一 view 恰有一个家户时才归一 —— 一一对应是旧档的既有事实；view 有歧义时保留 ToCohort
    //   作为 S3 的视图选择器，不在构造期猜）。归一化是"键/受方迁移"的唯一落点，幂等。
    Map<IndustryId, ProductionRelation> relationsCopy =
        normalizeRecipients(relationsRaw, classesCopy);
    relations = Collections.unmodifiableMap(relationsCopy); // ★ 冻在赋值处
    // ★★ 两条跨表守卫（见类注释）。★ 判在**构造期**：两条都是"同一件事有两处拼写"的口子，事后在结算里
    //   "顺手算对"既不可能（那时两处已经不一致了），也把"谁对"的判断留在了一段没有能力判断的代码里。
    for (Map.Entry<IndustryId, ProductionRelation> entry : relationsCopy.entrySet()) {
      ProductionRelation relation = entry.getValue();
      if (!entry.getKey().equals(relation.activity())) {
        throw new IllegalArgumentException(
            "relations 的键必须与 ProductionRelation.activity 一致：键="
                + entry.getKey()
                + "，行内 activity="
                + relation.activity());
      }
      Industry industry = industriesCopy.get(entry.getKey());
      if (industry == null) {
        throw new IllegalArgumentException("关系指名的产业不存在: " + entry.getKey());
      }
      if (!relation.operator().equals(industry.operator())) {
        throw new IllegalArgumentException(
            "关系的 operator 必须与产业的 operator 一致（同一件事不许两处拼写）："
                + entry.getKey()
                + " 关系="
                + relation.operator()
                + "，产业="
                + industry.operator());
      }
      Set<ActorRef> holders = holdersByActivity.get(entry.getKey());
      if (holders != null && !holders.isEmpty() && !holders.contains(relation.operator())) {
        throw new IllegalArgumentException(
            "关系的 operator 必须是该产业使用权的一个 holder（S1 §6.1 第 7 条）："
                + entry.getKey()
                + " operator="
                + relation.operator()
                + "，holders="
                + holders);
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
    // ── 第 13 个组件：经营者状态表（S3.2；S1 一次性补齐组件/变更集/编解码，避免以后再破 schema）───────
    //   ★ 键 = IndustryId，值内也必须带同一个 industry（键值同身份，同 classes 的判据）。
    //   ★ 空表合法：S3 之前没有状态机读者，空表 = "尚未登记任何经营者状态"（不是坏数据）。
    Map<IndustryId, OperatorCondition> operatorConditionsCopy = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, OperatorCondition> entry : operatorConditions.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("operatorConditions 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().industry())) {
        throw new IllegalArgumentException(
            "operatorConditions 的键必须与 OperatorCondition.industry 一致：键="
                + entry.getKey()
                + "，值内="
                + entry.getValue().industry());
      }
      operatorConditionsCopy.put(entry.getKey(), entry.getValue());
    }
    operatorConditions = Collections.unmodifiableMap(operatorConditionsCopy); // ★ 冻在赋值处
  }

  /**
   * ★★ <b>家户行的结构引用完整性</b>（H0 改写；改前是 {@code (industry, slot)} 必须落在该产业的 {@code slots} 里）：家户行的键 =
   * {@code (格, 居住类型, 阶层)}，判据换成 —— <b>该格上至少有一个产业允许这个阶层</b>；返回其中**最紧**的那个槽位（投入率上限取 min）。
   *
   * <p>★★ <b>为什么"该格上的任一产业"而不是"真供给它的那些产业"</b>：后者要读**劳动配额表** ⇒ 那会把"行 ↔ 配额"的引用完整性
   * 变成一条构造期守卫，而本记录**有意不判跨表引用**（同 {@code ActorData}「表与表之间没有引用完整性约束」的口径）：逐组件增量落盘 ⇒
   * <b>产业/行先到、配额后到是合法写序</b>，判死它等于让"经济状态刚种下、配额还没发"的世界构造不出来。
   *
   * <p>★ <b>上限取 min 的理由</b>：一条家户行的 {@code participationPerMille} 是**一个数**，而它可能同时给几个产业出劳动（农村家户
   * 既种地又织布）—— 取最紧的那个槽位 ⇒ 不会因"某个产业的上限更宽"而把劳动悄悄放大（这条守卫的全部目的）。 真档三个产业的四个槽位共用同一组参与率 ⇒ 与改前逐值相同。
   *
   * <p>★ <b>另有一条如实记的放宽</b>：改前还隐含"居住类型必须与产业对得上"（行键的产业段自带格），现在居住类型那一维**不在这里判** ——
   * 它由"供给关系"决定（配额表的批次前缀，见 {@code EconomySettlement.householdKeysOf}），
   * 而这里判不了它（要读配额表）。一条居住类型没有任何批次供给的家户行是**合法状态**（它只是不参与任何产业的生产）。
   */
  private static ClassSlot requireStratumAllowed(
      Map<IndustryId, Industry> industries, CohortKey key, String what) {
    String hexKey = IndustryHexKeys.hexKey(key.hex().q(), key.hex().r());
    ClassSlot tightest = null;
    boolean anyIndustry = false;
    for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
      // ★ **没有格键的产业 id**（{@code IndustryId} 允许这种值，真档里不会出现）：说不出它在哪一格 ⇒
      //   **不拿它来否决**（对任何格都算"可能"）。★ 反过来，带了格键的必须**逐字相等**才算命中 ——
      //   `1_10` 与 `1_1` 因此不会互相误命中（同 {@code IndustryHexKeys.at} 的口径）。
      if (IndustryHexKeys.hexKeyOf(entry.getKey()).filter(hexKey::equals).isEmpty()
          && IndustryHexKeys.hexKeyOf(entry.getKey()).isPresent()) {
        continue;
      }
      anyIndustry = true;
      for (ClassSlot slot : entry.getValue().slots()) {
        if (!slot.id().equals(key.stratum())) {
          continue;
        }
        if (tightest == null
            || slot.laborParticipationPerMille() < tightest.laborParticipationPerMille()) {
          tightest = slot;
        }
      }
    }
    if (!anyIndustry) {
      // ★ 消息保留"不存在的产业"这几个字：它是既有的判据用语（{@code EconomyInvariantsTest} 逐字钉着）。
      throw new IllegalArgumentException(
          what + " 引用了不存在的产业（该格上没有登记任何产业）: " + hexKey + "（键=" + key + "）");
    }
    if (tightest == null) {
      throw new IllegalArgumentException(
          what + " 引用了该格任何产业都未允许的阶层槽位: " + key.stratum() + " ∉ 格 " + hexKey + " 各产业的 slots");
    }
    return tightest;
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
        useRights,
        operatorConditions);
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
        useRights,
        operatorConditions);
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
        useRights,
        operatorConditions);
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
        useRights,
        operatorConditions);
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
        useRights,
        operatorConditions);
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
        useRights,
        operatorConditions);
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
        useRights,
        operatorConditions);
  }

  /** 一个组件一个 with（T2：生产关系表）；其余十二个组件原样带过。 */
  public EconomyData withRelations(Map<IndustryId, ProductionRelation> value) {
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
        useRights,
        operatorConditions);
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
        useRights,
        operatorConditions);
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
        useRights,
        operatorConditions);
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
        useRights,
        operatorConditions);
  }

  /** 一个组件一个 with（S1：使用权表）；其余十二个组件原样带过。 */
  public EconomyData withUseRights(Map<UseRightId, UseRight> value) {
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
        operatorConditions);
  }

  /** 一个组件一个 with（S3.2：经营者状态表）；其余十二个组件原样带过。 */
  public EconomyData withOperatorConditions(Map<IndustryId, OperatorCondition> value) {
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
        useRights,
        value);
  }

  /**
   * ★★ S1：把 {@link Recipient.ToCohort} 一对一归一到 {@link Recipient.ToHousehold}。
   *
   * <p>判据：<b>该 view 恰有一个家户</b>（旧档的既有事实）⇒ 迁移；view 有歧义（S3 才允许）⇒ 保留旧变体交由 S3
   * 的视图选择器解释。归一化是构造期的纯函数、幂等（归一后的规则不再含 ToCohort）。
   */
  private static Map<IndustryId, ProductionRelation> normalizeRecipients(
      Map<IndustryId, ProductionRelation> raw, Map<HouseholdId, ClassRow> classes) {
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
    Map<IndustryId, ProductionRelation> normalized = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, ProductionRelation> entry : raw.entrySet()) {
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

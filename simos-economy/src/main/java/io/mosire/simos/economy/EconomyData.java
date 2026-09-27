package io.mosire.simos.economy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <p>★ **九个组件与 {@link io.mosire.simos.economy.change.EconomyChangeSet} 的九个组件一一对应**（铁律 5）：
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
 * <p>★★ **缺键 = 空**（§11 的旧档兼容口径，照 {@code LedgerData} 的先例）：九个组件在本切片**都是新引入的**， 故 Jackson 绑成 null
 * 时一律收成空表 / 未激活，**此处不抛** —— 抛了等于"旧档全部读不回来"。方向是 fail-closed： 缺键 ⇒
 * 没有产业/没有阶层/没有债务/没有流水/没有劳动供给与配额/没有生产关系/<b>没有市场</b>/未激活。
 *
 * <p>★ **九张表都保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
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
 * <p>★ <b>H0（2026-09-27，裁定 K2）起两表键都是家户身份 {@link CohortKey}</b>：{@code classes} 的键 = 那一行的家户（格 + 居住类型
 * + 阶层），{@code flows} 同键。⇒ "这个产业有哪些行"不再由键的产业段回答，而由**劳动配额表**推（{@code
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
 *       市场表里没有"本期成交量"这类会过期的读数（读数在当天的 {@code ProductionLedger} 里）。
 * </ol>
 *
 * <p>★ <b>守卫**不**检查 cohort 侧的行是否存在</b>（有意不加，同 {@code ActorData}「表与表之间没有引用完整性约束」的口径）： 逐组件增量落盘 ⇒
 * **关系先到、行后到是合法写序**；而 cohort 解析不到行在结算里是**正常状态**（人口为 0 的那些 cohort 就是如此，那一笔留在 {@code
 * residualOwner}）——把它判成非法会让"人口尚未种入"的世界构造不出来。
 */
public record EconomyData(
    Optional<EconomyMeta> meta,
    Map<IndustryId, Industry> industries,
    Map<CohortKey, ClassRow> classes,
    Map<DebtId, Debt> debts,
    Map<CohortKey, FlowRow> flows,
    Map<PeopleLotId, LaborSupply> laborSupply,
    Map<LaborAllocationId, LaborAllocation> allocations,
    Map<IndustryId, ProductionRelation> relations,
    Map<HexCoord, Market> markets) {

  /** 往返用例的起点：未激活 + 九张空表。 */
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
    Map<CohortKey, ClassRow> classesCopy = new LinkedHashMap<>();
    for (Map.Entry<CohortKey, ClassRow> entry : classes.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classes 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().key())) {
        throw new IllegalArgumentException(
            "classes 的键必须与 ClassRow.key 一致：键="
                + entry.getKey()
                + "，行内 key="
                + entry.getValue().key());
      }
      ClassSlot slot = requireStratumAllowed(industriesCopy, entry.getKey(), "classes");
      ClassRow row = entry.getValue();
      // ★ v2 spec §八.1：`0 ≤ participationPerMille ≤ slot.laborParticipationPerMille ≤ 1000` 里，
      //   中间那条**只有这里能判**（ClassRow 只守了 [0,1000] 两头，槽位上限要跨对象）。
      //   不守的后果：laborMilli × participationPerMille ÷ 1000 被悄悄放大 ⇒ 劳动瓶颈、产出、
      //   按劳动权重的分配全变大，而账面看不出来（不凭空造粮，但凭空造劳动）。
      if (row.participationPerMille() > slot.laborParticipationPerMille()) {
        throw new IllegalArgumentException(
            "classes 的 participationPerMille 不得超过其槽位上限（v2 spec §八.1）："
                + entry.getKey()
                + " 行="
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
    for (Map.Entry<CohortKey, ClassRow> entry : classesCopy.entrySet()) {
      for (DebtId debtId : entry.getValue().debts()) {
        if (!debtsCopy.containsKey(debtId)) {
          throw new IllegalArgumentException(
              "ClassRow.debts 引用了不存在的债务（v2 spec §八.2）：" + entry.getKey() + " → " + debtId);
        }
      }
    }
    Map<CohortKey, FlowRow> flowsCopy = new LinkedHashMap<>();
    for (Map.Entry<CohortKey, FlowRow> entry : flows.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("flows 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().key())) {
        throw new IllegalArgumentException(
            "flows 的键必须与 FlowRow.key 一致：键=" + entry.getKey() + "，行内 key=" + entry.getValue().key());
      }
      requireStratumAllowed(industriesCopy, entry.getKey(), "flows");
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
    // ── 第 8 个组件：生产关系表（S1 阶段 4+5 Task 2；计划 R3）──────────────────────────────
    Map<IndustryId, ProductionRelation> relationsCopy = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, ProductionRelation> entry : relations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("relations 的键与值都不得为 null: " + entry.getKey());
      }
      relationsCopy.put(entry.getKey(), entry.getValue());
    }
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
        value, industries, classes, debts, flows, laborSupply, allocations, relations, markets);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withIndustries(Map<IndustryId, Industry> value) {
    return new EconomyData(
        meta, value, classes, debts, flows, laborSupply, allocations, relations, markets);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withClasses(Map<CohortKey, ClassRow> value) {
    return new EconomyData(
        meta, industries, value, debts, flows, laborSupply, allocations, relations, markets);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withDebts(Map<DebtId, Debt> value) {
    return new EconomyData(
        meta, industries, classes, value, flows, laborSupply, allocations, relations, markets);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withFlows(Map<CohortKey, FlowRow> value) {
    return new EconomyData(
        meta, industries, classes, debts, value, laborSupply, allocations, relations, markets);
  }

  /** 一个组件一个 with（R2：劳动供给表）；其余八个组件原样带过。 */
  public EconomyData withLaborSupply(Map<PeopleLotId, LaborSupply> value) {
    return new EconomyData(
        meta, industries, classes, debts, flows, value, allocations, relations, markets);
  }

  /** 一个组件一个 with（R2：劳动分配表）；其余八个组件原样带过。 */
  public EconomyData withAllocations(Map<LaborAllocationId, LaborAllocation> value) {
    return new EconomyData(
        meta, industries, classes, debts, flows, laborSupply, value, relations, markets);
  }

  /** 一个组件一个 with（T2：生产关系表）；其余八个组件原样带过。 */
  public EconomyData withRelations(Map<IndustryId, ProductionRelation> value) {
    return new EconomyData(
        meta, industries, classes, debts, flows, laborSupply, allocations, value, markets);
  }

  /**
   * 一个组件一个 with（H4：市场表）；其余八个组件原样带过。
   *
   * <p>★ <b>它是"GM 定价格"的唯一写入口</b>（铁律 2：所有修改最终表示为 Command → ChangeSet → Revision）——
   * 本批还没有"设价"命令，故它现在只被载荷（创世播种）与用例用到；命令留待 GM 参数目录落地。
   */
  public EconomyData withMarkets(Map<HexCoord, Market> value) {
    return new EconomyData(
        meta, industries, classes, debts, flows, laborSupply, allocations, relations, value);
  }
}

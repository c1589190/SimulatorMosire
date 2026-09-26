package io.mosire.simos.economy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.labor.LaborSupply;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 经济切片的完整状态树（新经济设计 §3 逐字）：激活元信息 + 产业表 + 阶层行 + 债务表 + 周期流水 + **劳动供给表 + 劳动分配表**（R2）。
 *
 * <p>★★ **{@code meta} 为空 {@code Optional} = 经济未激活**（§3.3 + §6.6）：未激活时日制世界仍可沿用简化人口查询（人口查询走 {@code
 * social}），但**日推进仍要求切片在场**。空快照 ≠ 已激活。
 *
 * <p>★★ **本切片只写自己的数据**（§2 + §6.1）：商品/货币/人口的总量守恒由**命令层/协调器**校验，**不落成第二份真相**——这里只有状态，
 * 没有"校验结论"。任何经济公式（产量/分配/税/市场盈亏）都不在本切片（§八 R1 行："模块化、无公式"）。
 *
 * <p>★ **七个组件与 {@link io.mosire.simos.economy.change.EconomyChangeSet} 的七个组件一一对应**（铁律 5）：
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
 * <p>★★ **缺键 = 空**（§11 的旧档兼容口径，照 {@code LedgerData} 的先例）：七个组件在本切片**都是新引入的**， 故 Jackson 绑成 null
 * 时一律收成空表 / 未激活，**此处不抛** —— 抛了等于"旧档全部读不回来"。方向是 fail-closed： 缺键 ⇒ 没有产业/没有阶层/没有债务/没有流水/没有劳动供给与配额/未激活。
 *
 * <p>★ **七张表都保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——它的迭代序不是内容的纯函数（字节级往返因此不成立）。冻结那一步**写在字段赋值处** （SpotBugs 的 {@code EI_EXPOSE_REP}
 * 不做跨过程分析，只认它看得见的包装）。
 */
public record EconomyData(
    Optional<EconomyMeta> meta,
    Map<IndustryId, Industry> industries,
    Map<ClassKey, ClassRow> classes,
    Map<DebtId, Debt> debts,
    Map<ClassKey, FlowRow> flows,
    Map<PeopleLotId, LaborSupply> laborSupply,
    Map<LaborAllocationId, LaborAllocation> allocations) {

  /** 往返用例的起点：未激活 + 七张空表。 */
  public static EconomyData empty() {
    return new EconomyData(
        Optional.empty(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
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
    Map<IndustryId, Industry> industriesCopy = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, Industry> entry : industries.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("industries 的键与值都不得为 null: " + entry.getKey());
      }
      industriesCopy.put(entry.getKey(), entry.getValue());
    }
    industries = Collections.unmodifiableMap(industriesCopy); // ★ 冻在赋值处
    Map<ClassKey, ClassRow> classesCopy = new LinkedHashMap<>();
    for (Map.Entry<ClassKey, ClassRow> entry : classes.entrySet()) {
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
      ClassSlot slot = requireSlotExists(industriesCopy, entry.getKey(), "classes");
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
    for (Map.Entry<ClassKey, ClassRow> entry : classesCopy.entrySet()) {
      for (DebtId debtId : entry.getValue().debts()) {
        if (!debtsCopy.containsKey(debtId)) {
          throw new IllegalArgumentException(
              "ClassRow.debts 引用了不存在的债务（v2 spec §八.2）：" + entry.getKey() + " → " + debtId);
        }
      }
    }
    Map<ClassKey, FlowRow> flowsCopy = new LinkedHashMap<>();
    for (Map.Entry<ClassKey, FlowRow> entry : flows.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("flows 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().key())) {
        throw new IllegalArgumentException(
            "flows 的键必须与 FlowRow.key 一致：键=" + entry.getKey() + "，行内 key=" + entry.getValue().key());
      }
      requireSlotExists(industriesCopy, entry.getKey(), "flows");
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
  }

  /**
   * 引用完整性（§6.2 的槽位侧，2026-09-25 修正后新增）：阶层行/流水行的 {@code (industry, slot)} 必须真落在该产业的 {@code slots}
   * 里——悬空行说明状态坏了（或有人在两个切片之间手改了键），宁可构造期当场炸。
   */
  private static ClassSlot requireSlotExists(
      Map<IndustryId, Industry> industries, ClassKey key, String what) {
    Industry industry = industries.get(key.industry());
    if (industry == null) {
      throw new IllegalArgumentException(
          what + " 引用了不存在的产业: " + key.industry() + "（键=" + key + "）");
    }
    for (ClassSlot slot : industry.slots()) {
      if (slot.id().equals(key.slot())) {
        return slot;
      }
    }
    throw new IllegalArgumentException(
        what + " 引用了该产业未允许的阶层槽位: " + key.slot() + " ∉ " + key.industry() + " 的 slots");
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withMeta(Optional<EconomyMeta> value) {
    return new EconomyData(value, industries, classes, debts, flows, laborSupply, allocations);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withIndustries(Map<IndustryId, Industry> value) {
    return new EconomyData(meta, value, classes, debts, flows, laborSupply, allocations);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withClasses(Map<ClassKey, ClassRow> value) {
    return new EconomyData(meta, industries, value, debts, flows, laborSupply, allocations);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withDebts(Map<DebtId, Debt> value) {
    return new EconomyData(meta, industries, classes, value, flows, laborSupply, allocations);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withFlows(Map<ClassKey, FlowRow> value) {
    return new EconomyData(meta, industries, classes, debts, value, laborSupply, allocations);
  }

  /** 一个组件一个 with（R2：劳动供给表）；其余六个组件原样带过。 */
  public EconomyData withLaborSupply(Map<PeopleLotId, LaborSupply> value) {
    return new EconomyData(meta, industries, classes, debts, flows, value, allocations);
  }

  /** 一个组件一个 with（R2：劳动分配表）；其余六个组件原样带过。 */
  public EconomyData withAllocations(Map<LaborAllocationId, LaborAllocation> value) {
    return new EconomyData(meta, industries, classes, debts, flows, laborSupply, value);
  }
}

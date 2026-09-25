package io.mosire.simos.economy;

import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.ClassKey;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 经济切片的完整状态树（新经济设计 §3 逐字）：激活元信息 + 产业表 + 阶层行 + 债务表 + 周期流水。
 *
 * <p>★★ **{@code meta} 为空 {@code Optional} = 经济未激活**（§3.3 + §6.6）：未激活时日制世界仍可沿用简化人口查询（人口查询走 {@code
 * social}），但**日推进仍要求切片在场**。空快照 ≠ 已激活。
 *
 * <p>★★ **本切片只写自己的数据**（§2 + §6.1）：商品/货币/人口的总量守恒由**命令层/协调器**校验，**不落成第二份真相**——这里只有状态，
 * 没有"校验结论"。任何经济公式（产量/分配/税/市场盈亏）都不在本切片（§八 R1 行："模块化、无公式"）。
 *
 * <p>★ **五个组件与 {@link io.mosire.simos.economy.change.EconomyChangeSet} 的五个组件一一对应**（铁律 5）：
 * 新增状态组件必须同时进变更集，由 {@code EconomyRoundTripTest} 的反射枚举把守。
 *
 * <p>★★ **跨表同键不变式**（§6.2 的身份部分）：{@code classes} 的每个键必须等于其 {@link ClassRow#key()}；{@code flows}
 * 的每个键必须等于其 {@link FlowRow#key()}。否则同一份"阶层身份"就有两处可能不一致的记录。
 *
 * <p>★★ **缺键 = 空**（§11 的旧档兼容口径，照 {@code LedgerData} 的先例）：五个组件在本切片**都是新引入的**， 故 Jackson 绑成 null
 * 时一律收成空表 / 未激活，**此处不抛** —— 抛了等于"旧档全部读不回来"。方向是 fail-closed： 缺键 ⇒ 没有产业/没有阶层/没有债务/没有流水/未激活。
 *
 * <p>★ **四张表都保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——它的迭代序不是内容的纯函数（字节级往返因此不成立）。冻结那一步**写在字段赋值处** （SpotBugs 的 {@code EI_EXPOSE_REP}
 * 不做跨过程分析，只认它看得见的包装）。
 */
public record EconomyData(
    Optional<EconomyMeta> meta,
    Map<IndustryId, Industry> industries,
    Map<ClassKey, ClassRow> classes,
    Map<DebtId, Debt> debts,
    Map<ClassKey, FlowRow> flows) {

  /** 往返用例的起点：未激活 + 四张空表。 */
  public static EconomyData empty() {
    return new EconomyData(Optional.empty(), Map.of(), Map.of(), Map.of(), Map.of());
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
      requireSlotExists(industriesCopy, entry.getKey(), "classes");
      classesCopy.put(entry.getKey(), entry.getValue());
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
  }

  /**
   * 引用完整性（§6.2 的槽位侧，2026-09-25 修正后新增）：阶层行/流水行的 {@code (industry, slot)} 必须真落在该产业的 {@code slots}
   * 里——悬空行说明状态坏了（或有人在两个切片之间手改了键），宁可构造期当场炸。
   */
  private static void requireSlotExists(
      Map<IndustryId, Industry> industries, ClassKey key, String what) {
    Industry industry = industries.get(key.industry());
    if (industry == null) {
      throw new IllegalArgumentException(
          what + " 引用了不存在的产业: " + key.industry() + "（键=" + key + "）");
    }
    for (ClassSlot slot : industry.slots()) {
      if (slot.id().equals(key.slot())) {
        return;
      }
    }
    throw new IllegalArgumentException(
        what + " 引用了该产业未允许的阶层槽位: " + key.slot() + " ∉ " + key.industry() + " 的 slots");
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withMeta(Optional<EconomyMeta> value) {
    return new EconomyData(value, industries, classes, debts, flows);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withIndustries(Map<IndustryId, Industry> value) {
    return new EconomyData(meta, value, classes, debts, flows);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withClasses(Map<ClassKey, ClassRow> value) {
    return new EconomyData(meta, industries, value, debts, flows);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withDebts(Map<DebtId, Debt> value) {
    return new EconomyData(meta, industries, classes, value, flows);
  }

  /** 一个组件一个 with（照 {@code LedgerData} 的形制）。 */
  public EconomyData withFlows(Map<ClassKey, FlowRow> value) {
    return new EconomyData(meta, industries, classes, debts, value);
  }
}

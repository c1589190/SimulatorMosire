package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ClassSlotId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.RegimeId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 产业（新经济设计 §3.1 逐字）：**一整个产业**的聚合状态——制度、周期与进度、每日投入、周期产出、允许的阶层槽位、分配函数。
 *
 * <p>★ **不再是逐生产单位**（§1 取代表）：周期/进度/日投入/产出函数都挂在产业这一层，复杂度不随人口或单位数线性增长。
 *
 * <p>★ **本类只存形状，不含公式**（§八 R1 行："模块化、无公式"）：{@code progressDays} 怎么推进、{@code dailyInputPerUnit}
 * 怎么扣、{@code outputPerUnit} 怎么乘，都是 R3/R4 的结算逻辑，不在本类。
 *
 * <p>★ **不变量（构造期判，§3.1 + §6）**：
 *
 * <ul>
 *   <li>{@code cycleDays ≥ 1}、{@code 0 ≤ progressDays ≤ cycleDays}（§3.1"当前进度 0..cycleDays"）
 *   <li>{@code dailyLaborPerUnit ≥ 0}；{@code dailyInputPerUnit}/{@code outputPerUnit} 逐值 {@code ≥
 *       0}（§6.4 存量非负的下界）
 *   <li>**{@code slots} 非空、逐项非空、{@code id} 不重复**（槽位是"制度允许的角色"，**不含人口占比**—— 占比是 {@code
 *       ClassRow.population} 的观测派生，见 {@link ClassSlot} 的类注释）
 * </ul>
 *
 * <p>★ **两张表都保序不可变**：{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，**绝不用 {@code
 * Map.copyOf}**——迭代序不是内容的纯函数（会产出不同字节）。冻结那一步**写在字段赋值处**（SpotBugs 的 {@code EI_EXPOSE_REP}
 * 不做跨过程分析，只认它看得见的包装）。
 *
 * @param id 稳定身份
 * @param name 展示名
 * @param regime 生产制度（小农 / 封建租佃 / 手工业 / 资本主义工业）：决定允许哪些阶层槽位
 * @param cycleDays 生产周期（天）；农业 120、手工业可短；必须 ≥ 1
 * @param progressDays 当前进度（天）；必须 ∈ [0, cycleDays]
 * @param dailyInputPerUnit 每单位生产资料每日原料需求（可为空 map）；键值非空、逐值 ≥ 0
 * @param dailyLaborPerUnit 每单位生产资料每日劳动需求（千分劳动）；不得为负
 * @param outputPerUnit 周期末每单位生产资料的基准产出（农业 = 每亩 7 粮）；键值非空、逐值 ≥ 0
 * @param slots 该制度允许的阶层槽位；非空、id 不重复（**不含人口占比**）
 * @param allocation 制度分配函数（版本化参数；本类不执行它）
 */
public record Industry(
    IndustryId id,
    String name,
    RegimeId regime,
    long cycleDays,
    long progressDays,
    Map<AssetKind, Long> dailyInputPerUnit,
    long dailyLaborPerUnit,
    Map<CommodityId, Long> outputPerUnit,
    List<ClassSlot> slots,
    AllocationRule allocation) {

  public Industry {
    if (id == null) {
      throw new IllegalArgumentException("Industry.id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Industry.name 不得为空白");
    }
    if (regime == null) {
      throw new IllegalArgumentException("Industry.regime 不得为 null");
    }
    if (allocation == null) {
      throw new IllegalArgumentException("Industry.allocation 不得为 null");
    }
    if (cycleDays < 1) {
      throw new IllegalArgumentException("Industry.cycleDays 必须 ≥ 1: " + cycleDays);
    }
    if (progressDays < 0 || progressDays > cycleDays) {
      throw new IllegalArgumentException(
          "Industry.progressDays 必须 ∈ [0, cycleDays]：progressDays="
              + progressDays
              + ", cycleDays="
              + cycleDays);
    }
    if (dailyInputPerUnit == null) {
      throw new IllegalArgumentException("Industry.dailyInputPerUnit 不得为 null（无投入用空 map）");
    }
    if (outputPerUnit == null) {
      throw new IllegalArgumentException("Industry.outputPerUnit 不得为 null（无产出用空 map）");
    }
    if (dailyLaborPerUnit < 0) {
      throw new IllegalArgumentException("Industry.dailyLaborPerUnit 不得为负: " + dailyLaborPerUnit);
    }
    if (slots == null) {
      throw new IllegalArgumentException("Industry.slots 不得为 null");
    }
    Map<AssetKind, Long> inputCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : dailyInputPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "Industry.dailyInputPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "Industry.dailyInputPerUnit 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      inputCopy.put(entry.getKey(), entry.getValue());
    }
    dailyInputPerUnit = Collections.unmodifiableMap(inputCopy); // ★ 冻在赋值处
    Map<CommodityId, Long> outputCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : outputPerUnit.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "Industry.outputPerUnit 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "Industry.outputPerUnit 的数量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      outputCopy.put(entry.getKey(), entry.getValue());
    }
    outputPerUnit = Collections.unmodifiableMap(outputCopy); // ★ 冻在赋值处
    List<ClassSlot> slotsCopy = new ArrayList<>();
    Set<ClassSlotId> slotIds = new LinkedHashSet<>();
    for (ClassSlot slot : slots) {
      if (slot == null) {
        throw new IllegalArgumentException("Industry.slots 不得含 null");
      }
      if (!slotIds.add(slot.id())) {
        throw new IllegalArgumentException("Industry.slots 的 id 不得重复: " + slot.id());
      }
      slotsCopy.add(slot);
    }
    if (slotsCopy.isEmpty()) {
      throw new IllegalArgumentException("Industry.slots 不得为空");
    }
    slots = Collections.unmodifiableList(slotsCopy); // ★ 冻在赋值处
  }
}

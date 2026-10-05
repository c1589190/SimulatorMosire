package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★ <b>家户阶层归属</b>（理想架构 §2.3/§2.9）：持久化一个家户的当前阶层位置、原所属位置与保留份额。它是 E1 建立的"家户 → 阶层"权威状态； 旧 {@code
 * HouseholdEconomy.view.stratum} 在 E1 仍是旧结算路径的权威，<b>本状态不接线结算、不改旧路径</b>。
 *
 * <p>★★ <b>为什么保留 {@code originalPositionId} 与 {@code
 * retainedShares}</b>：模式变迁时，一个家户可能只把一部分成员/权利迁入新位置，其余保留在原所属； 单一 {@code currentPositionId}
 * 表达不了这种混合归属（设计稿 §2.9）。{@code retainedShares} 以位置 id 为键、千分比为值，显式记下"保留了多少"。
 *
 * <p>★ <b>E1 只做形状与守恒边界</b>：不解释迁移比例、不把份额乘进人口/资产，也不改 {@code HouseholdClassRule} 的分类。份额的 {@code ≥ 0}
 * 与键身份是状态合法性判据；"份额合计是否等于 1000"等规则留到 E6 模式变迁裁决。
 *
 * <p>★★ <b>E5a 追加 {@code consecutiveDebtStressCycles}</b>：连续债务压力周期数（≥ 0）。★ <b>旧 JSON 缺这个键 ⇒
 * 0</b>（Jackson 对 record 的缺失原始 {@code long} 取类型默认值，与 {@code HouseholdEconomy.cycleNaturalNeedMilli} 同款约定），
 * 故本字段不需要另写迁移层；E5a 只落字段与构造期 ≥ 0 守卫，递增/清零在 E5b。
 *
 * <p>★★ <b>P2-B §13.5 追加 {@code participatingPositionIds}</b>：本家户**除当前位置之外还参与**的阶层位置集合
 * （"可参与生产方式"的状态表达；每个位置的 {@code modeId} 给出它属于哪个 {@code ProductionMode}）。★ <b>空集 =
 * 只参与 {@link #currentPositionId}（旧档/旧口径，逐值不变）</b>；非空集是**追加**集合，不是替换集合
 * —— {@link #effectivePositionIds()} 给出"当前位置 ∪ 追加集合"的规范化结果。这样"不能再由
 * currentPositionId 单值决定唯一生产方式"（计划 P2-B.1）与"旧档只有单值"两个口径在同一个字段里表达，
 * 不需要第二张状态表。位置的引用完整性（必须已在 {@code classPositions} 里、且 {@code modeId} 已在 {@code modes} 里）
 * 由 {@code EconomyData} 构造期判死；本类型只判形状。
 *
 * @param householdId 家户稳定身份；不得为 null（键 = 值内 householdId）
 * @param originalPositionId 原所属阶层位置；不得为 null（创世迁移时通常等于当前位置）
 * @param currentPositionId 当前阶层位置；不得为 null
 * @param participatingPositionIds 追加参与的生产位置（当前位置之外；可空集 = 只参与当前位置）；不得为 null（缺省给空集）、
 *     不得含 null、保序不可变
 * @param retainedShares 保留份额（位置 → 千分比）；不得为 null、键值不得为 null、逐值 ≥ 0，保序不可变
 * @param consecutiveDebtStressCycles 连续债务压力周期数（≥ 0）；E5a 只落字段，递增/清零在 E5b
 * @param lastTransitionDay 最近一次阶层变更日；不得为负
 * @param reason 最近一次变更原因（具名文本；可为空串 = 尚未发生变更）；不得为 null
 */
public record HouseholdClassMembership(
    HouseholdId householdId,
    ClassPositionId originalPositionId,
    ClassPositionId currentPositionId,
    Set<ClassPositionId> participatingPositionIds,
    Map<ClassPositionId, Long> retainedShares,
    long consecutiveDebtStressCycles,
    long lastTransitionDay,
    String reason) {

  /**
   * ★ <b>旧七参构造（P2-B 之前）的源码兼容别名</b>：追加集合取空集（= 只参与 {@code currentPositionId} 的旧口径），
   * 使尚未迁移的旧调用方/用例仍能按原签名构造。新代码请直接给 {@code participatingPositionIds}（没有就给
   * {@code Set.of()}，语义相同）。
   */
  public HouseholdClassMembership(
      HouseholdId householdId,
      ClassPositionId originalPositionId,
      ClassPositionId currentPositionId,
      Map<ClassPositionId, Long> retainedShares,
      long consecutiveDebtStressCycles,
      long lastTransitionDay,
      String reason) {
    this(
        householdId,
        originalPositionId,
        currentPositionId,
        Set.of(),
        retainedShares,
        consecutiveDebtStressCycles,
        lastTransitionDay,
        reason);
  }

  public HouseholdClassMembership {
    if (householdId == null) {
      throw new IllegalArgumentException("ClassStanding.householdId 不得为 null");
    }
    if (originalPositionId == null) {
      throw new IllegalArgumentException("ClassStanding.originalPositionId 不得为 null");
    }
    if (currentPositionId == null) {
      throw new IllegalArgumentException("ClassStanding.currentPositionId 不得为 null");
    }
    if (participatingPositionIds == null) {
      participatingPositionIds = Set.of(); // ★ 旧 JSON / 手写载荷缺键 ⇒ 空集 = 只参与当前位置（旧口径逐值不变）
    }
    Set<ClassPositionId> positionsCopy = new LinkedHashSet<>();
    for (ClassPositionId position : participatingPositionIds) {
      if (position == null) {
        throw new IllegalArgumentException("ClassStanding.participatingPositionIds 不得含 null");
      }
      positionsCopy.add(position);
    }
    participatingPositionIds = Collections.unmodifiableSet(positionsCopy); // ★ 冻在赋值处
    if (retainedShares == null) {
      throw new IllegalArgumentException("ClassStanding.retainedShares 不得为 null（没有保留给空表）");
    }
    if (consecutiveDebtStressCycles < 0L) {
      throw new IllegalArgumentException(
          "ClassStanding.consecutiveDebtStressCycles 不得为负: " + consecutiveDebtStressCycles);
    }
    if (lastTransitionDay < 0L) {
      throw new IllegalArgumentException(
          "ClassStanding.lastTransitionDay 不得为负: " + lastTransitionDay);
    }
    if (reason == null) {
      throw new IllegalArgumentException("ClassStanding.reason 不得为 null（没有就给空串）");
    }
    Map<ClassPositionId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<ClassPositionId, Long> entry : retainedShares.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ClassStanding.retainedShares 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ClassStanding.retainedShares 不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    retainedShares = Collections.unmodifiableMap(copy); // ★ 冻在赋值处（SpotBugs 只认它看得见的包装）
  }

  /**
   * ★★ <b>P2-B：本户本 tick 的有效参与位置集合</b>（唯一的"多生产方式"读口）= {@code {currentPositionId} ∪
   * participatingPositionIds}，按位置 id 字典序去重。
   *
   * <p>★ <b>为什么要规范成"并集"</b>：{@code currentPositionId} 是旧档/创世唯一的归属表达，必须继续生效；追加集合只表达
   * "除当前职业外还能参与哪些生产活动"。两处分开写、合并只在这一处发生 ⇒ 组织阶段与读口不可能各自解释一遍。
   *
   * @return 保序不可变的位置清单（至少含 {@link #currentPositionId()}）
   */
  public List<ClassPositionId> effectivePositionIds() {
    Set<ClassPositionId> all = new LinkedHashSet<>();
    all.add(currentPositionId);
    all.addAll(participatingPositionIds);
    List<ClassPositionId> sorted = new ArrayList<>(all);
    sorted.sort(Comparator.comparing(ClassPositionId::value));
    return Collections.unmodifiableList(sorted);
  }
}

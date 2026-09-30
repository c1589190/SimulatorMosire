package io.mosire.simos.economy.classfirst;

import io.mosire.simos.economy.api.id.ClassFirstAccountId;
import io.mosire.simos.economy.api.id.ClassFlowEventId;
import io.mosire.simos.economy.api.id.ClassPoolId;
import io.mosire.simos.economy.api.id.ExternalLenderId;
import io.mosire.simos.economy.api.id.HouseholdProductionAccountId;
import io.mosire.simos.economy.api.id.MobilityPolicyId;
import io.mosire.simos.economy.api.id.ModeParticipationId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>阶层池经济（class-first）的单一持久状态组件</b>（计划 R1）：一个 record 装下全部跨 tick 状态，避免 {@link
 * io.mosire.simos.economy.EconomyData} 的 arity 逐表扩散。
 *
 * <p>本 record 的构造是<b>不可变边界</b>：全部 Map 拷成保序不可变表，{@code classPools} 的每个 {@link ClassPool} 都做深拷贝 ——
 * 状态一旦构造，外部拿不到能改动它的引用。引擎在 {@code snapshot()} 时构造它，在 {@code restore()} 时再把
 * 值深拷回引擎工作表；因此"同一份状态重放必然得到同一轨迹"。
 *
 * <p>七张必需表 + 三张 R1 实际结算必需的扩展表（{@code accounts}/{@code lenders}/{@code meta}）：
 *
 * <ul>
 *   <li>{@code modeParticipations}：{@code (mode, 阶层位置) → 参与记录}；
 *   <li>{@code classPools}：{@code (mode, 阶层位置) → 资产/人口/劳动/债务池}；
 *   <li>{@code householdAccounts}：池内家户子账户（人口/劳动/份额）；
 *   <li>{@code assetStateSchemas}：{@code mode → A_C 需求/权重 schema}；
 *   <li>{@code classBounds}：{@code pool → 阶层边界（x_C 的 L/U）}；
 *   <li>{@code mobilityPolicies}：{@code mode → GM 可调人口流动政策}；
 *   <li>{@code classFlowEvents}：上下行迁移审计；
 *   <li>{@code accounts}：双边滚动账户（利息/催收跨 tick 的权威）；
 *   <li>{@code lenders}：外部放贷主体账户（商品/货币）；
 *   <li>{@code meta}：tick/托管/累计读数/初始基数/参数（见 {@link ClassFirstMeta}）。
 * </ul>
 *
 * <p>★ 缺键（null）⇒ 空表 / 空元信息（旧档兼容口径）。{@code empty()} 是"salt 未播"的唯一字面量。
 */
public record ClassFirstState(
    Map<ModeParticipationId, ModeParticipation> modeParticipations,
    Map<ClassPoolId, ClassPool> classPools,
    Map<HouseholdProductionAccountId, HouseholdProductionAccount> householdAccounts,
    Map<ProductionModeId, AssetStateSchema> assetStateSchemas,
    Map<ClassPoolId, ClassBounds> classBounds,
    Map<MobilityPolicyId, MobilityPolicy> mobilityPolicies,
    Map<ClassFlowEventId, ClassFlowEvent> classFlowEvents,
    Map<ClassFirstAccountId, ClassFirstAccount> accounts,
    Map<ExternalLenderId, PilotModel.Lender> lenders,
    ClassFirstMeta meta) {

  public ClassFirstState {
    if (modeParticipations == null) {
      modeParticipations = Map.of();
    }
    if (classPools == null) {
      classPools = Map.of();
    }
    if (householdAccounts == null) {
      householdAccounts = Map.of();
    }
    if (assetStateSchemas == null) {
      assetStateSchemas = Map.of();
    }
    if (classBounds == null) {
      classBounds = Map.of();
    }
    if (mobilityPolicies == null) {
      mobilityPolicies = Map.of();
    }
    if (classFlowEvents == null) {
      classFlowEvents = Map.of();
    }
    if (accounts == null) {
      accounts = Map.of();
    }
    if (lenders == null) {
      lenders = Map.of();
    }
    if (meta == null) {
      meta = ClassFirstMeta.empty();
    }

    Map<ClassPoolId, ClassPool> poolsCopy = new LinkedHashMap<>();
    for (Map.Entry<ClassPoolId, ClassPool> entry : classPools.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classPools 的键与值都不得为 null: " + entry.getKey());
      }
      ClassPool pool = entry.getValue();
      ClassPoolId derived = ClassPoolId.idOf(pool.modeId(), pool.classPositionId());
      if (!entry.getKey().equals(derived)) {
        throw new IllegalArgumentException(
            "classPools 的键必须由 (modeId, classPositionId) 确定性派生：键="
                + entry.getKey()
                + "，派生="
                + derived);
      }
      poolsCopy.put(entry.getKey(), pool.copy());
    }
    classPools = Collections.unmodifiableMap(poolsCopy); // ★ 冻在赋值处

    Map<ModeParticipationId, ModeParticipation> participationsCopy = new LinkedHashMap<>();
    for (Map.Entry<ModeParticipationId, ModeParticipation> entry : modeParticipations.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("modeParticipations 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "modeParticipations 的键必须与 ModeParticipation.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + entry.getValue().id());
      }
      participationsCopy.put(entry.getKey(), entry.getValue());
    }
    modeParticipations = Collections.unmodifiableMap(participationsCopy);

    Map<HouseholdProductionAccountId, HouseholdProductionAccount> householdsCopy =
        new LinkedHashMap<>();
    for (Map.Entry<HouseholdProductionAccountId, HouseholdProductionAccount> entry :
        householdAccounts.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("householdAccounts 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "householdAccounts 的键必须与 HouseholdProductionAccount.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + entry.getValue().id());
      }
      if (!classPools.isEmpty() && !classPools.containsKey(entry.getValue().poolId())) {
        throw new IllegalArgumentException(
            "householdAccounts 指名的阶层池不存在：" + entry.getKey() + " → " + entry.getValue().poolId());
      }
      householdsCopy.put(entry.getKey(), entry.getValue());
    }
    householdAccounts = Collections.unmodifiableMap(householdsCopy);

    Map<ProductionModeId, AssetStateSchema> schemasCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionModeId, AssetStateSchema> entry : assetStateSchemas.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("assetStateSchemas 的键与值都不得为 null: " + entry.getKey());
      }
      schemasCopy.put(entry.getKey(), entry.getValue());
    }
    assetStateSchemas = Collections.unmodifiableMap(schemasCopy);

    Map<ClassPoolId, ClassBounds> boundsCopy = new LinkedHashMap<>();
    for (Map.Entry<ClassPoolId, ClassBounds> entry : classBounds.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classBounds 的键与值都不得为 null: " + entry.getKey());
      }
      if (!classPools.isEmpty() && !classPools.containsKey(entry.getKey())) {
        throw new IllegalArgumentException("classBounds 指名的阶层池不存在: " + entry.getKey());
      }
      boundsCopy.put(entry.getKey(), entry.getValue());
    }
    classBounds = Collections.unmodifiableMap(boundsCopy);

    Map<MobilityPolicyId, MobilityPolicy> policiesCopy = new LinkedHashMap<>();
    for (Map.Entry<MobilityPolicyId, MobilityPolicy> entry : mobilityPolicies.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("mobilityPolicies 的键与值都不得为 null: " + entry.getKey());
      }
      policiesCopy.put(entry.getKey(), entry.getValue());
    }
    mobilityPolicies = Collections.unmodifiableMap(policiesCopy);

    Map<ClassFlowEventId, ClassFlowEvent> flowsCopy = new LinkedHashMap<>();
    for (Map.Entry<ClassFlowEventId, ClassFlowEvent> entry : classFlowEvents.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classFlowEvents 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "classFlowEvents 的键必须与 ClassFlowEvent.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + entry.getValue().id());
      }
      flowsCopy.put(entry.getKey(), entry.getValue());
    }
    classFlowEvents = Collections.unmodifiableMap(flowsCopy);

    Map<ClassFirstAccountId, ClassFirstAccount> accountsCopy = new LinkedHashMap<>();
    for (Map.Entry<ClassFirstAccountId, ClassFirstAccount> entry : accounts.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classFirst.accounts 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "classFirst.accounts 的键必须与 ClassFirstAccount.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + entry.getValue().id());
      }
      accountsCopy.put(entry.getKey(), entry.getValue());
    }
    accounts = Collections.unmodifiableMap(accountsCopy);

    Map<ExternalLenderId, PilotModel.Lender> lendersCopy = new LinkedHashMap<>();
    for (Map.Entry<ExternalLenderId, PilotModel.Lender> entry : lenders.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("classFirst.lenders 的键与值都不得为 null: " + entry.getKey());
      }
      ExternalLenderId derived = ExternalLenderId.of(entry.getValue().id());
      if (!entry.getKey().equals(derived)) {
        throw new IllegalArgumentException(
            "classFirst.lenders 的键必须由放贷方 id 派生：键=" + entry.getKey() + "，派生=" + derived);
      }
      lendersCopy.put(entry.getKey(), entry.getValue());
    }
    lenders = Collections.unmodifiableMap(lendersCopy);
  }

  /** 旧档缺键 / 尚未播种的唯一字面量。 */
  public static ClassFirstState empty() {
    return new ClassFirstState(
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        ClassFirstMeta.empty());
  }

  /** 是否与 {@link #empty()} 逐值相同（放进变更集时"不存在"的判据）。 */
  public boolean isEmpty() {
    return modeParticipations.isEmpty()
        && classPools.isEmpty()
        && householdAccounts.isEmpty()
        && assetStateSchemas.isEmpty()
        && classBounds.isEmpty()
        && mobilityPolicies.isEmpty()
        && classFlowEvents.isEmpty()
        && accounts.isEmpty()
        && lenders.isEmpty()
        && meta.equals(ClassFirstMeta.empty());
  }

  /**
   * 追加合并：{@code this} 的插入序在前、{@code other} 覆盖同键；{@code meta} 取 tick 较大者（后播/后结算的状态赢）。
   *
   * <p>用途只有一处：{@code economy.Seed} 对已激活世界按格追加时，把新载荷的 classFirst 并进已有状态。同键冲突时后播的值赢， 与 {@code
   * EconomySeedHandler.merge} 的"后播覆盖"口径一致。
   */
  public ClassFirstState merge(ClassFirstState other) {
    if (other == null || other.isEmpty()) {
      return this;
    }
    if (this.isEmpty()) {
      return other;
    }
    return new ClassFirstState(
        concat(modeParticipations, other.modeParticipations),
        concat(classPools, other.classPools),
        concat(householdAccounts, other.householdAccounts),
        concat(assetStateSchemas, other.assetStateSchemas),
        concat(classBounds, other.classBounds),
        concat(mobilityPolicies, other.mobilityPolicies),
        concat(classFlowEvents, other.classFlowEvents),
        concat(accounts, other.accounts),
        concat(lenders, other.lenders),
        other.meta.tick() >= this.meta.tick() ? other.meta : this.meta);
  }

  private static <K, V> Map<K, V> concat(Map<K, V> base, Map<K, V> added) {
    LinkedHashMap<K, V> merged = new LinkedHashMap<>(base);
    merged.putAll(added);
    return merged;
  }
}

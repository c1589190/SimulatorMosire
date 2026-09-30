package io.mosire.simos.economy.classfirst;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
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
    @JsonDeserialize(keyUsing = ClassFirstJsonKeys.ModeParticipationKey.class)
        Map<ModeParticipationId, ModeParticipation> modeParticipations,
    @JsonDeserialize(keyUsing = ClassFirstJsonKeys.ClassPoolKey.class)
        Map<ClassPoolId, ClassPool> classPools,
    @JsonDeserialize(keyUsing = ClassFirstJsonKeys.HouseholdProductionAccountKey.class)
        Map<HouseholdProductionAccountId, HouseholdProductionAccount> householdAccounts,
    @JsonDeserialize(keyUsing = ClassFirstJsonKeys.ProductionModeKey.class)
        Map<ProductionModeId, AssetStateSchema> assetStateSchemas,
    @JsonDeserialize(keyUsing = ClassFirstJsonKeys.ClassPoolKey.class)
        Map<ClassPoolId, ClassBounds> classBounds,
    @JsonDeserialize(keyUsing = ClassFirstJsonKeys.MobilityPolicyKey.class)
        Map<MobilityPolicyId, MobilityPolicy> mobilityPolicies,
    @JsonDeserialize(keyUsing = ClassFirstJsonKeys.ClassFlowEventKey.class)
        Map<ClassFlowEventId, ClassFlowEvent> classFlowEvents,
    @JsonDeserialize(keyUsing = ClassFirstJsonKeys.ClassFirstAccountKey.class)
        Map<ClassFirstAccountId, ClassFirstAccount> accounts,
    @JsonDeserialize(keyUsing = ClassFirstJsonKeys.ExternalLenderKey.class)
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

  /**
   * 是否与 {@link #empty()} 逐值相同（放进变更集时"不存在"的判据）。
   *
   * <p>★ <b>派生判断、不进线格式</b>：Jackson 会把 {@code isEmpty()} 当属性 {@code "empty"} 写出去，而读侧严格模式会拒； 故直接在本类型上
   * {@link JsonIgnore}（{@code EconomyCodec} 的同款 mixin 因此成为冗余，但保留）。
   */
  @JsonIgnore
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
   * ★★ <b>R2c：世界级追加合并（唯一入口）</b>：多国 {@code economy.Seed} 逐国到达时，把新载荷并进世界状态。
   *
   * <p>★★ <b>与 R1 旧口径的区别（这条是 R2c 的核心）</b>：旧版对全部表都是"同键后播覆盖"—— 三国 seed
   * 会把前面国家的池/人口/资产全部盖掉（而池键里没有国家维）。R2c 起：
   *
   * <ul>
   *   <li>{@code classPools}：同 {@code (mode, 阶层位置)} 键的池按 {@link ClassPool#mergedWith} <b>加法</b>合并
   *       （人口/劳动/库存/债务/租约全部逐值求和）；
   *   <li>{@code householdAccounts}：按 household id <b>append</b>；同 id 重复 ⇒
   *       fail-closed（家户身份不能一份变两份）；
   *   <li>{@code lenders}：同 id 的放贷主体把资金/商品 <b>逐值求和</b>（三国的 GOV 放贷窗口合成一个世界级窗口）；
   *   <li>{@code accounts}/{@code classFlowEvents}：按 id 合并；同键且值不同 ⇒ fail-closed（不静默覆盖审计账）；
   *   <li>{@code modeParticipations}/{@code assetStateSchemas}/{@code classBounds}/{@code
   *       mobilityPolicies}： 世界制度面只有一份，保留已有（同键新值不覆盖），新键追加；
   *   <li>{@code meta}：tick 取较大者，托管/累计读数/初始基数 <b>逐值求和</b>，config 的 lender 换成合并后的那一份。
   * </ul>
   *
   * <p>★ <b>空态短路</b>：任一侧为空 ⇒ 直接返回另一侧（保持"尚未播种"的唯一字面量语义）。
   */
  public ClassFirstState merge(ClassFirstState other) {
    if (other == null || other.isEmpty()) {
      return this;
    }
    if (this.isEmpty()) {
      return other;
    }
    LinkedHashMap<ClassPoolId, ClassPool> pools = new LinkedHashMap<>(classPools);
    for (Map.Entry<ClassPoolId, ClassPool> entry : other.classPools.entrySet()) {
      ClassPool existing = pools.get(entry.getKey());
      if (existing == null) {
        pools.put(entry.getKey(), entry.getValue().copy());
      } else {
        pools.put(entry.getKey(), existing.mergedWith(entry.getValue()));
      }
    }

    LinkedHashMap<HouseholdProductionAccountId, HouseholdProductionAccount> households =
        new LinkedHashMap<>(householdAccounts);
    for (Map.Entry<HouseholdProductionAccountId, HouseholdProductionAccount> entry :
        other.householdAccounts.entrySet()) {
      HouseholdProductionAccount previous =
          households.putIfAbsent(entry.getKey(), entry.getValue());
      if (previous != null && !previous.equals(entry.getValue())) {
        throw new IllegalArgumentException(
            "世界级合并时发现重复 householdId（家户身份不能一份变两份）："
                + entry.getKey()
                + " 已有 "
                + previous
                + "，新增 "
                + entry.getValue());
      }
    }

    LinkedHashMap<ModeParticipationId, ModeParticipation> participations =
        new LinkedHashMap<>(modeParticipations);
    other.modeParticipations.forEach(participations::putIfAbsent);
    LinkedHashMap<ProductionModeId, AssetStateSchema> schemas =
        new LinkedHashMap<>(assetStateSchemas);
    other.assetStateSchemas.forEach(schemas::putIfAbsent);
    LinkedHashMap<ClassPoolId, ClassBounds> bounds = new LinkedHashMap<>(classBounds);
    other.classBounds.forEach(bounds::putIfAbsent);
    LinkedHashMap<MobilityPolicyId, MobilityPolicy> policies =
        new LinkedHashMap<>(mobilityPolicies);
    other.mobilityPolicies.forEach(policies::putIfAbsent);

    LinkedHashMap<ClassFlowEventId, ClassFlowEvent> flows = new LinkedHashMap<>(classFlowEvents);
    for (Map.Entry<ClassFlowEventId, ClassFlowEvent> entry : other.classFlowEvents.entrySet()) {
      ClassFlowEvent previous = flows.putIfAbsent(entry.getKey(), entry.getValue());
      if (previous != null && !previous.equals(entry.getValue())) {
        throw new IllegalArgumentException("世界级合并时发现冲突的 classFlowEvent id：" + entry.getKey());
      }
    }
    LinkedHashMap<ClassFirstAccountId, ClassFirstAccount> mergedAccounts =
        new LinkedHashMap<>(accounts);
    for (Map.Entry<ClassFirstAccountId, ClassFirstAccount> entry : other.accounts.entrySet()) {
      ClassFirstAccount previous = mergedAccounts.putIfAbsent(entry.getKey(), entry.getValue());
      if (previous != null && !previous.equals(entry.getValue())) {
        throw new IllegalArgumentException("世界级合并时发现冲突的双边账户 id：" + entry.getKey());
      }
    }
    LinkedHashMap<ExternalLenderId, PilotModel.Lender> mergedLenders = new LinkedHashMap<>(lenders);
    for (Map.Entry<ExternalLenderId, PilotModel.Lender> entry : other.lenders.entrySet()) {
      mergedLenders.merge(entry.getKey(), entry.getValue(), ClassFirstState::mergeLender);
    }

    return new ClassFirstState(
        participations,
        pools,
        households,
        schemas,
        bounds,
        policies,
        flows,
        mergedAccounts,
        mergedLenders,
        mergeMeta(meta, other.meta, mergedLenders));
  }

  /** 同 id 放贷主体的加法合并：资金/商品求和；制度参数必须一致（不一致 = 同一放贷制度两处拼写 ⇒ fail-closed）。 */
  private static PilotModel.Lender mergeLender(PilotModel.Lender base, PilotModel.Lender added) {
    if (!base.id().equals(added.id())
        || base.interestRatePerMille() != added.interestRatePerMille()
        || base.nextDueTick() != added.nextDueTick()
        || base.collectionPower() != added.collectionPower()) {
      throw new IllegalArgumentException(
          "同 id 放贷主体的制度参数不一致，拒绝静默覆盖：base=" + base + " added=" + added);
    }
    LinkedHashMap<String, Long> goods = new LinkedHashMap<>(base.goods());
    for (Map.Entry<String, Long> entry : added.goods().entrySet()) {
      goods.merge(entry.getKey(), entry.getValue(), Math::addExact);
    }
    return new PilotModel.Lender(
        base.id(),
        Math.addExact(base.money(), added.money()),
        goods,
        base.interestRatePerMille(),
        base.nextDueTick(),
        base.collectionPower());
  }

  /** 元信息的加法合并（tick 取 max，读数和求和，config 的 lender 换成合并后的那一份）。 */
  private static ClassFirstMeta mergeMeta(
      ClassFirstMeta base, ClassFirstMeta added, Map<ExternalLenderId, PilotModel.Lender> lenders) {
    if (base.config() != null
        && added.config() != null
        && !base.config().mode().id().equals(added.config().mode().id())) {
      throw new IllegalArgumentException(
          "世界级合并的两侧 mode 不一致：base="
              + base.config().mode().id()
              + " added="
              + added.config().mode().id());
    }
    PilotConfig config = base.config() != null ? base.config() : added.config();
    if (config != null) {
      PilotModel.Lender merged = lenders.get(ExternalLenderId.of(config.lender().id()));
      if (merged != null && !merged.equals(config.lender())) {
        config = config.withLender(merged);
      }
    }
    return new ClassFirstMeta(
        Math.max(base.tick(), added.tick()),
        Math.addExact(base.landForSale(), added.landForSale()),
        Math.addExact(base.landMarketEscrowGrain(), added.landMarketEscrowGrain()),
        Math.addExact(base.landMarketEscrowMoney(), added.landMarketEscrowMoney()),
        Math.addExact(base.totalLeaseHolding(), added.totalLeaseHolding()),
        config,
        addTotals(base.totals(), added.totals()),
        addInitialTotals(base.initial(), added.initial()),
        Math.addExact(base.stockEnrichmentViolations(), added.stockEnrichmentViolations()));
  }

  private static ClassFirstMeta.Totals addTotals(
      ClassFirstMeta.Totals base, ClassFirstMeta.Totals added) {
    return new ClassFirstMeta.Totals(
        Math.addExact(base.producedGrainTotal(), added.producedGrainTotal()),
        Math.addExact(base.seedUsedTotal(), added.seedUsedTotal()),
        Math.addExact(base.rationConsumedTotal(), added.rationConsumedTotal()),
        Math.addExact(base.clothConsumedTotal(), added.clothConsumedTotal()),
        Math.addExact(base.borrowedGrainTotal(), added.borrowedGrainTotal()),
        Math.addExact(base.borrowedMoneyTotal(), added.borrowedMoneyTotal()),
        Math.addExact(base.boughtGrainTotal(), added.boughtGrainTotal()),
        Math.addExact(base.liquidSeizedTotal(), added.liquidSeizedTotal()),
        Math.addExact(base.landSeizedTotal(), added.landSeizedTotal()),
        Math.addExact(base.capitalizedTotal(), added.capitalizedTotal()),
        Math.addExact(base.redLightTotal(), added.redLightTotal()),
        Math.addExact(base.collectionEventCount(), added.collectionEventCount()),
        Math.addExact(base.interestChargedTotal(), added.interestChargedTotal()),
        Math.addExact(base.rentPaidTotal(), added.rentPaidTotal()),
        Math.addExact(base.wagePaidTotal(), added.wagePaidTotal()),
        Math.addExact(base.externalSeedPaidTotal(), added.externalSeedPaidTotal()),
        Math.addExact(base.residualPaidTotal(), added.residualPaidTotal()),
        Math.addExact(base.taxPaidTotal(), added.taxPaidTotal()));
  }

  private static ClassFirstMeta.InitialTotals addInitialTotals(
      ClassFirstMeta.InitialTotals base, ClassFirstMeta.InitialTotals added) {
    return new ClassFirstMeta.InitialTotals(
        Math.addExact(base.grainTotal(), added.grainTotal()),
        Math.addExact(base.clothTotal(), added.clothTotal()),
        Math.addExact(base.householdMoneyTotal(), added.householdMoneyTotal()),
        Math.addExact(base.lenderMoneyTotal(), added.lenderMoneyTotal()),
        Math.addExact(base.populationTotal(), added.populationTotal()),
        Math.addExact(base.ownedLandTotal(), added.ownedLandTotal()),
        Math.addExact(base.toolsTotal(), added.toolsTotal()),
        Math.addExact(base.claimGrainMilli(), added.claimGrainMilli()));
  }

  /**
   * ★★ <b>R2c：更新家户账户人口的唯一入口</b>：把 {@code replacements} 里给出的账户整条替换进 {@code
   * householdAccounts}，并按<b>成员求和</b>重算每个 {@link ClassPool} 的 {@code population/labor}
   * （池与家户账户因此不会各说各话）。
   *
   * <p>用途只有一处：出生/死亡接回时，{@code ClassFirstPopulationWriteback} 按 {@code (格, 居住类型)} 组把生死摊到 household
   * 子账户，再经本方法把"家户人口/劳动"同步回池。★ 不碰库存/债务/账户：人口学不是商品/货币/土地守恒的写口。
   *
   * <p>★ <b>身份不可变</b>：替换必须保持 {@code
   * id/poolId/householdId/name/laborPerCapita/participationSharePerMille} 与现有账户一致（只有 population 与
   * laborUnits 允许变），否则当场抛 —— 人口回写不能变成"偷偷换家户"。
   */
  public ClassFirstState withHouseholdAccounts(
      Map<HouseholdProductionAccountId, HouseholdProductionAccount> replacements) {
    if (replacements == null || replacements.isEmpty()) {
      return this;
    }
    LinkedHashMap<HouseholdProductionAccountId, HouseholdProductionAccount> nextHouseholds =
        new LinkedHashMap<>(householdAccounts);
    for (Map.Entry<HouseholdProductionAccountId, HouseholdProductionAccount> entry :
        replacements.entrySet()) {
      HouseholdProductionAccount existing = householdAccounts.get(entry.getKey());
      HouseholdProductionAccount replacement = entry.getValue();
      if (existing == null) {
        throw new IllegalArgumentException("人口回写指名的家户账户不存在：" + entry.getKey());
      }
      if (replacement == null) {
        throw new IllegalArgumentException("人口回写的家户账户不得为 null：" + entry.getKey());
      }
      if (replacement.population() < 0L || replacement.laborUnits() < 0L) {
        throw new IllegalArgumentException("人口回写的家户账户人口/劳动不得为负：" + replacement);
      }
      if (!replacement.id().equals(existing.id())
          || !replacement.poolId().equals(existing.poolId())
          || !replacement.householdId().equals(existing.householdId())
          || !replacement.name().equals(existing.name())
          || replacement.laborPerCapita() != existing.laborPerCapita()
          || replacement.participationSharePerMille() != existing.participationSharePerMille()) {
        throw new IllegalArgumentException(
            "人口回写只允许改 population/laborUnits，身份或制度字段不得变：" + existing + " -> " + replacement);
      }
      nextHouseholds.put(entry.getKey(), replacement);
    }

    LinkedHashMap<ClassPoolId, ClassPool> nextPools = new LinkedHashMap<>();
    for (Map.Entry<ClassPoolId, ClassPool> entry : classPools.entrySet()) {
      ClassPool pool = entry.getValue().copy();
      long population = 0L;
      long labor = 0L;
      for (HouseholdProductionAccount account : nextHouseholds.values()) {
        if (entry.getKey().equals(account.poolId())) {
          population = Math.addExact(population, account.population());
          labor = Math.addExact(labor, account.laborUnits());
        }
      }
      pool.setPopulation(population);
      pool.setLabor(labor);
      nextPools.put(entry.getKey(), pool);
    }

    return new ClassFirstState(
        modeParticipations,
        nextPools,
        nextHouseholds,
        assetStateSchemas,
        classBounds,
        mobilityPolicies,
        classFlowEvents,
        accounts,
        lenders,
        meta);
  }

  /**
   * ★ 纯 copy-with：把 {@code replacements} 逐条盖进 {@code mobilityPolicies}（同键覆盖），其余组件原样带过。
   *
   * <p>★ <b>只接受替换既有键</b>（与 {@link #withHouseholdAccounts} 同法）：GM 只调既有 mode 的政策参数，
   * 新增/删除政策键属于制度播种，不从这里开写口。保序不可变由 {@link ClassFirstState} 构造器统一冻结（不用 {@code Map.copyOf}，它不保证迭代序）。
   *
   * @param replacements 政策替换表；null/空表 ⇒ 原样返回 {@code this}
   * @throws IllegalArgumentException 指名不存在的政策键或键/值为 null
   */
  public ClassFirstState withMobilityPolicies(Map<MobilityPolicyId, MobilityPolicy> replacements) {
    if (replacements == null || replacements.isEmpty()) {
      return this;
    }
    LinkedHashMap<MobilityPolicyId, MobilityPolicy> nextPolicies =
        new LinkedHashMap<>(mobilityPolicies);
    for (Map.Entry<MobilityPolicyId, MobilityPolicy> entry : replacements.entrySet()) {
      MobilityPolicyId id = entry.getKey();
      MobilityPolicy replacement = entry.getValue();
      if (id == null || replacement == null) {
        throw new IllegalArgumentException("mobilityPolicies 的替换键与值都不得为 null: " + id);
      }
      if (!mobilityPolicies.containsKey(id)) {
        throw new IllegalArgumentException("只允许替换既有 MobilityPolicy，不接受新增/删除: " + id);
      }
      nextPolicies.put(id, replacement);
    }
    return new ClassFirstState(
        modeParticipations,
        classPools,
        householdAccounts,
        assetStateSchemas,
        classBounds,
        nextPolicies,
        classFlowEvents,
        accounts,
        lenders,
        meta);
  }

  /**
   * ★ 纯 copy-with：把 {@code replacements} 逐条盖进 {@code lenders}（同键覆盖），其余组件原样带过。
   *
   * <p>★ <b>只接受替换既有键</b>（同 {@link #withHouseholdAccounts}）：放贷主体的身份 = {@code
   * ExternalLenderId.of(id)}， 键与行内 id 的一致性仍由 {@link ClassFirstState} 构造器 fail-closed
   * 校验；保序不可变由构造器统一冻结。
   *
   * @param replacements 放贷主体替换表；null/空表 ⇒ 原样返回 {@code this}
   * @throws IllegalArgumentException 指名不存在的放贷方键或键/值为 null
   */
  public ClassFirstState withLenders(Map<ExternalLenderId, PilotModel.Lender> replacements) {
    if (replacements == null || replacements.isEmpty()) {
      return this;
    }
    LinkedHashMap<ExternalLenderId, PilotModel.Lender> nextLenders = new LinkedHashMap<>(lenders);
    for (Map.Entry<ExternalLenderId, PilotModel.Lender> entry : replacements.entrySet()) {
      ExternalLenderId id = entry.getKey();
      PilotModel.Lender replacement = entry.getValue();
      if (id == null || replacement == null) {
        throw new IllegalArgumentException("lenders 的替换键与值都不得为 null: " + id);
      }
      if (!lenders.containsKey(id)) {
        throw new IllegalArgumentException("只允许替换既有放贷方，不接受新增/删除: " + id);
      }
      nextLenders.put(id, replacement);
    }
    return new ClassFirstState(
        modeParticipations,
        classPools,
        householdAccounts,
        assetStateSchemas,
        classBounds,
        mobilityPolicies,
        classFlowEvents,
        accounts,
        nextLenders,
        meta);
  }

  /**
   * ★ 纯 copy-with：把 {@code replacements} 逐条盖进 {@code accounts}（同键覆盖），其余组件原样带过。
   *
   * <p>★ <b>只接受替换既有键</b>（同 {@link #withHouseholdAccounts}）：GM 免债只减既有双边账户的净额， 不新增/删除账户；键与 {@code
   * (owner, counterparty, unit)} 派生 id 的一致性由构造器 fail-closed 校验。
   *
   * @param replacements 账户替换表；null/空表 ⇒ 原样返回 {@code this}
   * @throws IllegalArgumentException 指名不存在的账户键或键/值为 null
   */
  public ClassFirstState withAccounts(Map<ClassFirstAccountId, ClassFirstAccount> replacements) {
    if (replacements == null || replacements.isEmpty()) {
      return this;
    }
    LinkedHashMap<ClassFirstAccountId, ClassFirstAccount> nextAccounts =
        new LinkedHashMap<>(accounts);
    for (Map.Entry<ClassFirstAccountId, ClassFirstAccount> entry : replacements.entrySet()) {
      ClassFirstAccountId id = entry.getKey();
      ClassFirstAccount replacement = entry.getValue();
      if (id == null || replacement == null) {
        throw new IllegalArgumentException("accounts 的替换键与值都不得为 null: " + id);
      }
      if (!accounts.containsKey(id)) {
        throw new IllegalArgumentException("只允许替换既有双边账户，不接受新增/删除: " + id);
      }
      nextAccounts.put(id, replacement);
    }
    return new ClassFirstState(
        modeParticipations,
        classPools,
        householdAccounts,
        assetStateSchemas,
        classBounds,
        mobilityPolicies,
        classFlowEvents,
        nextAccounts,
        lenders,
        meta);
  }

  /**
   * ★ 纯 copy-with：只换 {@link #meta}，其余九张表原样带过（class-first 阶段 2：GM 只改 {@code meta.config}）。
   *
   * <p>★ 为什么需要它：{@code setCollectionPolicy}/{@code setProductionParameters} 的权威面是 {@code
   * meta.config}；没有这个 copy-with，调整路径就得手抄十个组件构造 state（铁律 5 的漂移形态）。 保序不可变仍由构造器统一冻结 ——
   * 本方法只做"换一块、带过其余"。
   *
   * @param replacement 新元信息；不得为 null（空元信息口径只在构造器与 {@link ClassFirstMeta#empty()} 处）
   * @throws IllegalArgumentException replacement 为 null
   */
  public ClassFirstState withMeta(ClassFirstMeta replacement) {
    if (replacement == null) {
      throw new IllegalArgumentException("withMeta 的 replacement 不得为 null");
    }
    return new ClassFirstState(
        modeParticipations,
        classPools,
        householdAccounts,
        assetStateSchemas,
        classBounds,
        mobilityPolicies,
        classFlowEvents,
        accounts,
        lenders,
        replacement);
  }
}

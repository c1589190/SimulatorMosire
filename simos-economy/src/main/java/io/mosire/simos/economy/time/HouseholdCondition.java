package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.UseRight;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * ★★ <b>S3.3 劳动家户状态读数（派生、不落盘）</b>—— 计划允许"并入 {@code ClassRow} 的派生读数或独立组件"；本类选择 <b>读时派生</b>：不新增
 * {@code EconomyData} 组件、不改变更集/codec 形状，全部字段都能由 {@code FlowRow + LaborAllocation + UseRight + Debt
 * + ProductionLedger(瞬态)} 逐值复算。
 *
 * <pre>
 * unmetNeedMilliGrain/Cloth = 本周期累计未满足（FlowRow.unmetNeed；与"本周期"同窗口）
 * debtStress               = 债务本金 ÷ max(1, 使用权数量) × 1000（资产估价近似，如实标注为近似）
 * laborSoldMilli           = Σ LaborAllocation(household=本户).laborMilli
 * laborSelfMilli           = Σ LaborAllocation(household=本户 且 actor=本户 actor).laborMilli
 * rentPaidMilli            = 本日 ledger 里本户作为付方的租规则实付（账本缺失 ⇒ empty，不填 0）
 * wageArrearsMilli         = 本日 ledger 里本户作为受方的工资欠款（WageArrears）
 * status                   = 由 laborSource / 使用权 / 未满足 / 债务压力推出
 * </pre>
 *
 * <p>★★ <b>如实边界</b>：本批没有"连续 M 周期"的持久计数器（{@code HouseholdCondition} 不是 {@code EconomyData}
 * 组件）；{@code stressCycles} 只能给"当前周期的压力证据 0/1"，<b>不冒充历史连续计数</b>。 每次读都从当前状态现算，因此重放/1-4-8 线程下逐值一致。
 *
 * @param household 家户稳定身份
 * @param unmetNeedMilliGrain 本周期累计未满足口粮（毫粮）
 * @param unmetNeedMilliCloth 本周期累计未满足衣着（毫衣）
 * @param debtStress 债务压力（债务本金 ÷ 资产数量 × 1000；0 = 没有债务或没有资产可摊）
 * @param laborSoldMilli 本户卖出的劳动（千分劳动·日）
 * @param laborSelfMilli 本户"给自己"的劳动（千分劳动·日）
 * @param rentPaidMilli 本日租规则实付（毫；账本缺失 ⇒ empty，不填 0）
 * @param wageArrearsMilli 本户作为受方的工资欠款（毫；账本缺失或无工资规则 ⇒ empty）
 * @param status 生计状态（派生）
 * @param stressCycles 当前周期的压力证据（0/1；见类注的边界）
 */
public record HouseholdCondition(
    HouseholdId household,
    long unmetNeedMilliGrain,
    long unmetNeedMilliCloth,
    long debtStress,
    long laborSoldMilli,
    long laborSelfMilli,
    OptionalLong rentPaidMilli,
    OptionalLong wageArrearsMilli,
    LivelihoodStatus status,
    long stressCycles) {

  /** 生计状态（计划 §S3.3 的词表）。 */
  public enum LivelihoodStatus {
    /** 自给生产（持有使用权；卖不出去也不自动转业/死亡）。 */
    SELF_PROVISION,
    /** 佃耕。 */
    TENANT,
    /** 庄园义务（农奴）。 */
    SERF,
    /** 雇工/工资劳动。 */
    WAGE,
    /** 失去生计（无使用权、无雇主、无目的地）。 */
    DESTITUTE,
    /** 迁移中（迁移命令/自治迁移的过渡态）。 */
    MIGRATING
  }

  public HouseholdCondition {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(rentPaidMilli, "rentPaidMilli");
    Objects.requireNonNull(wageArrearsMilli, "wageArrearsMilli");
    Objects.requireNonNull(status, "status");
    if (unmetNeedMilliGrain < 0L
        || unmetNeedMilliCloth < 0L
        || debtStress < 0L
        || laborSoldMilli < 0L
        || laborSelfMilli < 0L
        || stressCycles < 0L) {
      throw new IllegalArgumentException("HouseholdCondition 的计数/数量不得为负");
    }
  }

  /**
   * 从当前状态现算一份家户状态读数。
   *
   * @param ledger 本日由 {@code EconomyDayStepper.step} 交出的瞬态账本；{@link Optional#empty()} ⇒ 租/工资欠款两栏
   *     empty（"读不到"而不是 0）
   */
  public static HouseholdCondition derive(
      EconomyData data, HouseholdId household, Optional<ProductionLedger> ledger) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(ledger, "ledger");
    FlowRow flow = data.flows().get(household);
    long unmetGrain =
        flow == null ? 0L : flow.unmetNeed().getOrDefault(EconomySettlement.GRAIN, 0L);
    long unmetCloth =
        flow == null ? 0L : flow.unmetNeed().getOrDefault(EconomySettlement.CLOTH, 0L);
    long principal = 0L;
    for (Debt debt : data.debts().values()) {
      if (debt.debtor().equals(household)) {
        principal += debt.principal();
      }
    }
    long assetQuantity = 0L;
    for (UseRight right : data.useRights().values()) {
      if (right.holder().equals(HouseholdActors.of(household))) {
        assetQuantity += right.quantity();
      }
    }
    long debtStress = principal == 0L ? 0L : principal * 1_000L / Math.max(1L, assetQuantity);
    long laborSold = 0L;
    long laborSelf = 0L;
    for (LaborAllocation allocation : data.allocations().values()) {
      if (!allocation.household().equals(household)) {
        continue;
      }
      if (allocation.actor().equals(HouseholdActors.of(household))) {
        laborSelf += allocation.laborMilli();
      } else {
        laborSold += allocation.laborMilli();
      }
    }
    OptionalLong wageArrears = OptionalLong.empty();
    if (ledger.isPresent()) {
      long wage = 0L;
      for (ProductionSettlement.Arrear arrear : ledger.orElseThrow().arrears()) {
        if (arrear.kind() == ProductionSettlement.Arrear.Kind.WAGE
            && recipientIs(arrear.rule(), household)) {
          wage += arrear.owed();
        }
      }
      wageArrears = OptionalLong.of(wage);
    }
    // ★ 租实付（本户作为付方）在本读数里**读不到**：RuleSettlement 只带规则、不带 operator 归属，
    //   猜一个付方就是编数。故 rentPaidMilli 保持 empty（"读不到"），由将来的逐关系落账读数补。
    OptionalLong rentPaid = OptionalLong.empty();
    LivelihoodStatus status = livelihoodOf(data, household, laborSold);
    long stressCycles = status == LivelihoodStatus.DESTITUTE && unmetGrain > 0L ? 1L : 0L;
    return new HouseholdCondition(
        household,
        unmetGrain,
        unmetCloth,
        debtStress,
        laborSold,
        laborSelf,
        rentPaid,
        wageArrears,
        status,
        stressCycles);
  }

  /** 规则受方是否就是这家户（{@code ToHousehold} / 家户 actor / 旧 {@code ToCohort}）。 */
  private static boolean recipientIs(CompensationRule rule, HouseholdId household) {
    return switch (rule.recipient()) {
      case Recipient.ToHousehold toHousehold -> toHousehold.household().equals(household);
      case Recipient.ToActor toActor -> toActor.actor().equals(HouseholdActors.of(household));
      case Recipient.ToCohort toCohort ->
          HouseholdActors.of(toCohort.cohort()).equals(HouseholdActors.of(household));
    };
  }

  /**
   * 生计状态推导：先按本户供给的产业关系定 {@code TENANT}/{@code SERF}/{@code WAGE}（制度优先）， 再看使用权给出 {@code
   * SELF_PROVISION}，都没有则 {@code DESTITUTE}。
   */
  private static LivelihoodStatus livelihoodOf(
      EconomyData data, HouseholdId household, long laborSold) {
    LaborSource source = null;
    for (LaborAllocation allocation : data.allocations().values()) {
      if (!allocation.household().equals(household)) {
        continue;
      }
      ProductionRelation relation =
          data.relations()
              .get(new io.mosire.simos.economy.api.id.IndustryId(allocation.actor().id()));
      if (relation != null) {
        source = relation.laborSource();
        if (source == LaborSource.TENANT || source == LaborSource.SERF) {
          break; // 佃/奴是制度身份，优先于雇工
        }
      }
    }
    if (source == LaborSource.TENANT) {
      return LivelihoodStatus.TENANT;
    }
    if (source == LaborSource.SERF) {
      return LivelihoodStatus.SERF;
    }
    boolean hasRight = false;
    for (UseRight right : data.useRights().values()) {
      if (right.holder().equals(HouseholdActors.of(household)) && right.quantity() > 0L) {
        hasRight = true;
        break;
      }
    }
    if (hasRight) {
      return LivelihoodStatus.SELF_PROVISION;
    }
    if (laborSold > 0L || source == LaborSource.WAGE) {
      return LivelihoodStatus.WAGE;
    }
    return LivelihoodStatus.DESTITUTE;
  }
}

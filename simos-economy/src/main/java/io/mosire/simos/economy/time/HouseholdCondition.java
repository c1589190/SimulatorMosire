package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.Recipient;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Debt;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * ★★ <b>S3.3 劳动家户状态读数（派生、不落盘）</b>—— 计划允许"并入 {@code ClassRow} 的派生读数或独立组件"；本类选择 <b>读时派生</b>：不新增
 * {@code EconomyData} 组件、不改变更集/codec 形状，字段由 {@code FlowRow + LaborAllocation + AssetShare + Debt +
 * ProductionLedger(瞬态)} 逐值复算；E1 的 {@code grainCoveragePerMille} 另由调用方传入库存粮（库存真源在 actor 侧，economy
 * 不另存一本账）。
 *
 * <pre>
 * unmetNeedMilliGrain/Cloth = 本周期累计未满足（FlowRow.unmetNeed；与"本周期"同窗口）
 * debtStress               = 债务本金 ÷ max(1, 资产份额数量) × 1000（资产估价近似，如实标注为近似）
 * laborSoldMilli           = Σ LaborAllocation(household=本户).laborMilli
 * laborSelfMilli           = Σ LaborAllocation(household=本户 且 actor=本户 actor).laborMilli
 * rentPaidMilli            = 本日 ledger 里本户作为付方的租规则实付（账本缺失 ⇒ empty，不填 0）
 * wageArrearsMilli         = 本日 ledger 里本户作为受方的工资欠款（WageArrears）
 * grainCoveragePerMille    = 库存粮 ÷ cumulativeRationMilli(人口, 本户 cycleDays)，封顶 1000（读不到账 ⇒ empty）
 * status                   = 由 laborSource / 资产份额 / 自用粮覆盖 / 劳动去向推出
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
 * @param grainCoveragePerMille ★ E1：库存粮 ÷ 本周期基本口粮（{@code cumulativeRationMilli(人口, 本户
 *     cycleDays)}），封顶 1000；读不到账/算不出分母 ⇒ {@link OptionalLong#empty()}（明确哨兵，不填 0 冒充"断粮"）
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
    long stressCycles,
    OptionalLong grainCoveragePerMille) {

  /** 生计状态（计划 §S3.3 的词表）。 */
  public enum LivelihoodStatus {
    /** 自给生产（持有资产份额；卖不出去也不自动转业/死亡）。 */
    SELF_PROVISION,
    /** 佃耕。 */
    TENANT,
    /** 庄园义务（农奴）。 */
    SERF,
    /** 雇工/工资劳动。 */
    WAGE,
    /** 失去生计（无资产份额、无雇主、无目的地）。 */
    DESTITUTE,
    /** 迁移中（迁移命令/自治迁移的过渡态）。 */
    MIGRATING
  }

  public HouseholdCondition {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(rentPaidMilli, "rentPaidMilli");
    Objects.requireNonNull(wageArrearsMilli, "wageArrearsMilli");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(grainCoveragePerMille, "grainCoveragePerMille");
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
   * 从当前状态现算一份家户状态读数（不带上限口径的粮覆盖：{@code grainCoveragePerMille} 为 empty，"读不到"）。
   *
   * @param ledger 本日由 {@code EconomyDayStepper.step} 交出的瞬态账本；{@link Optional#empty()} ⇒ 租/工资欠款两栏
   *     empty（"读不到"而不是 0）
   */
  public static HouseholdCondition derive(
      EconomyData data, HouseholdId household, Optional<ProductionLedger> ledger) {
    return derive(data, household, ledger, OptionalLong.empty());
  }

  /**
   * ★★ <b>E1：带"库存粮"入参的家户状态读数</b>。
   *
   * <p>★★ <b>为什么粮库存要由调用方给</b>：商品库存的唯一真源在 actor 侧的 {@code GoodsAccount}，而 economy
   * 状态树里没有它（H1/D3-C）；本类不能自己也去读一份账（那就是第二本账）。{@code OptionalLong.empty()} = "读不到账" ⇒ {@code
   * grainCoveragePerMille} 保持 empty，绝不用 0 冒充"断粮"。
   *
   * @param grainStockMilli 该家户在当前格的库存粮（毫粮）；empty = 读不到账
   */
  public static HouseholdCondition derive(
      EconomyData data,
      HouseholdId household,
      Optional<ProductionLedger> ledger,
      OptionalLong grainStockMilli) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(ledger, "ledger");
    Objects.requireNonNull(grainStockMilli, "grainStockMilli");
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
    for (AssetShare share : data.assetShares().values()) {
      // ★ R3B.1：旧 holder 语义拆成 owner/operator 两栏 —— 拥有或实际经营的份额都算本户的资产基数；
      //   同一条份额（owner == operator）只计一次。
      if (share.owner().equals(HouseholdActors.of(household))
          || share.operator().equals(HouseholdActors.of(household))) {
        assetQuantity += share.quantity();
      }
    }
    long debtStress = principal == 0L ? 0L : principal * 1_000L / Math.max(1L, assetQuantity);
    long laborSold = 0L;
    long laborSelf = 0L;
    long cycleDays = 0L;
    for (LaborAllocation allocation : data.allocations().values()) {
      if (!allocation.household().equals(household)) {
        continue;
      }
      if (allocation.actor().equals(HouseholdActors.of(household))) {
        laborSelf += allocation.laborMilli();
      } else {
        laborSold += allocation.laborMilli();
      }
      // ★ E1：本户的"周期"取它供给的 unit 模板 cycleDays 的最大值（与结算侧 cycleDaysByHousehold 同一条口径）。
      ProductionUnit unit = data.units().get(new ProductionUnitId(allocation.activity()));
      Industry industry = unit == null ? null : data.industries().get(unit.industry());
      if (industry != null) {
        cycleDays = Math.max(cycleDays, industry.cycleDays());
      }
    }
    ClassRow row = data.classes().get(household);
    if (cycleDays == 0L && row != null) {
      // ★ 兜底：一条配额都没有的家户退回"它住的那一格的产业"（同 cycleDaysByHousehold 的兜底）。
      String hexKey = IndustryHexKeys.hexKey(row.view().hex().q(), row.view().hex().r());
      for (Map.Entry<IndustryId, Industry> entry : data.industries().entrySet()) {
        if (IndustryHexKeys.hexKeyOf(entry.getKey()).filter(hexKey::equals).isPresent()) {
          cycleDays = Math.max(cycleDays, entry.getValue().cycleDays());
        }
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
    OptionalLong grainCoverage = grainCoveragePerMille(row, cycleDays, grainStockMilli);
    LivelihoodStatus status = livelihoodOf(data, household, laborSold, grainCoverage);
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
        stressCycles,
        grainCoverage);
  }

  /**
   * ★ E1：库存粮覆盖本周期基本口粮的千分数（封顶 1000）。
   *
   * <p>分母 = {@link EconomyVocabulary#cumulativeRationMilli}(人口, 本户 cycleDays)；读不到账、算不出正分母 ⇒ {@link
   * OptionalLong#empty()}（明确哨兵，不填 0）。
   */
  private static OptionalLong grainCoveragePerMille(
      ClassRow row, long cycleDays, OptionalLong grainStockMilli) {
    if (row == null || grainStockMilli.isEmpty() || cycleDays <= 0L) {
      return OptionalLong.empty();
    }
    long cycleNeed = EconomyVocabulary.cumulativeRationMilli(row.population(), cycleDays);
    if (cycleNeed <= 0L) {
      return OptionalLong.empty(); // 0 人口/0 天：覆盖率没有定义，不猜 1000 也不猜 0
    }
    long stock = Math.max(0L, grainStockMilli.getAsLong());
    return OptionalLong.of(Math.min(1_000L, stock * 1_000L / cycleNeed));
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
   * 生计状态推导：先按本户供给的产业关系定 {@code TENANT}/{@code SERF}（制度优先）， 再看资产份额或自用粮覆盖给出 {@code
   * SELF_PROVISION}，再看劳动卖出给出 {@code WAGE}，都没有则 {@code DESTITUTE}。
   *
   * <p>★ <b>E1：{@code DESTITUTE} 只在"无资产、无劳动卖出、无自用覆盖"时给出</b> —— 市场滞销本身不在这里判，本条也不 因为一次卖不动就降档。
   */
  private static LivelihoodStatus livelihoodOf(
      EconomyData data, HouseholdId household, long laborSold, OptionalLong grainCoveragePerMille) {
    LaborSource source = null;
    for (LaborAllocation allocation : data.allocations().values()) {
      if (!allocation.household().equals(household)) {
        continue;
      }
      ProductionUnitId unitId = new ProductionUnitId(allocation.activity());
      ProductionRelation relation =
          data.units().containsKey(unitId) ? data.relations().get(unitId) : null;
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
    for (AssetShare share : data.assetShares().values()) {
      if (share.quantity() > 0L
          && (share.owner().equals(HouseholdActors.of(household))
              || share.operator().equals(HouseholdActors.of(household)))) {
        hasRight = true;
        break;
      }
    }
    boolean hasSelfCoverage =
        grainCoveragePerMille.isPresent() && grainCoveragePerMille.getAsLong() > 0L;
    if (hasRight || hasSelfCoverage) {
      return LivelihoodStatus.SELF_PROVISION;
    }
    if (laborSold > 0L || source == LaborSource.WAGE) {
      return LivelihoodStatus.WAGE;
    }
    return LivelihoodStatus.DESTITUTE;
  }
}

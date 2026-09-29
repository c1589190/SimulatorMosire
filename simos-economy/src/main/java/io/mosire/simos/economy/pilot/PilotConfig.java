package io.mosire.simos.economy.pilot;

/**
 * 试点的显式制度/技术参数。
 *
 * <p>数量一律是整数：粮食/布匹按件，货币按最小单位，劳动按"千分劳动单位"（1000 = 一个全劳力/ tick），比例按千分比。 {@link #tenancyAgriculture}
 * 给出一套能跑 360 tick 的最小参数；测试只显式构造 lender 与催收政策。
 */
public record PilotConfig(
    PilotModel.Mode mode,
    PilotModel.Lender lender,
    PilotModel.CollectionPolicy collectionPolicy,
    long yieldPerLand,
    long seedPerLand,
    long laborPerLand,
    long toolCapacityPerTool,
    long rentPerLand,
    long wagePerLabor,
    long baseRationPerCapita,
    long laborRationPerLabor,
    long nonEssentialNeedPerMille,
    long nonEssentialEfficiencyPenaltyPerMille,
    long loanInterestRatePerMille,
    long moneyPerGrain,
    long toolPricePerUnit,
    long middlePeasantFloorLandMilli,
    long reserveTicks,
    long collectionIntervalTicks,
    long debtPressureUnitPerCapita) {

  public PilotConfig {
    if (mode == null || lender == null || collectionPolicy == null) {
      throw new IllegalArgumentException("mode/lender/collectionPolicy must be present");
    }
    if (yieldPerLand <= 0 || seedPerLand < 0 || laborPerLand <= 0 || toolCapacityPerTool <= 0) {
      throw new IllegalArgumentException("invalid agronomic parameters");
    }
    if (baseRationPerCapita < 0 || laborRationPerLabor < 0) {
      throw new IllegalArgumentException("invalid ration parameters");
    }
    if (moneyPerGrain <= 0) {
      throw new IllegalArgumentException("moneyPerGrain must be > 0");
    }
  }

  /** 单 mode 佃农制农业的最小可用参数。 */
  public static PilotConfig tenancyAgriculture(
      PilotModel.Lender lender, PilotModel.CollectionPolicy collectionPolicy) {
    return tenancyAgriculture(lender, collectionPolicy, 30L);
  }

  /** 同上，但显式指定催收/到期间隔（installment 冷却）。 */
  public static PilotConfig tenancyAgriculture(
      PilotModel.Lender lender,
      PilotModel.CollectionPolicy collectionPolicy,
      long collectionIntervalTicks) {
    return new PilotConfig(
        PilotModel.tenancyMode(),
        lender,
        collectionPolicy,
        10L, // yieldPerLand
        1L, // seedPerLand
        1L, // laborPerLand（一个劳动单位种一单位地）
        2L, // toolCapacityPerTool
        2L, // rentPerLand（固定实物粮租/单位土地）
        6L, // wagePerLabor（一个劳动单位的工资）
        3L, // baseRationPerCapita
        1L, // laborRationPerLabor（实际出劳动者额外口粮）
        250L, // nonEssentialNeedPerMille（每千人每 tick 250 件布）
        200L, // nonEssentialEfficiencyPenaltyPerMille（布全缺 ⇒ 效率最低 800‰）
        20L, // loanInterestRatePerMille（家户间欠款/欠租，每 tick 2%）
        10L, // moneyPerGrain
        5L, // toolPricePerUnit（催收估值用）
        300L, // middlePeasantFloorLandMilli（中农自有地/人 ≥ 0.3）
        3L, // reserveTicks（保护储备 = 3 tick 的口粮）
        collectionIntervalTicks,
        10L); // debtPressureUnitPerCapita（人均 10 粮当量债务 ⇒ 压力 1000‰）
  }
}

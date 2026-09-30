package io.mosire.simos.economy.classfirst;

/**
 * {@link ClassFirstState} 的标量/参数/累计读数元信息：tick、土地市场托管、累计守恒读数、初始基数、制度参数。
 *
 * <p>它把"不属于七张池/账户表"的跨 tick 状态收进一个不可变记录，避免 {@link io.mosire.simos.economy.EconomyData} 的 arity
 * 继续扩散。{@code config} 只在空态为 {@code null}；非空态必须带完整参数（引擎快照的硬前提）。
 *
 * @param tick 当前已结算到的 tick（0 = 尚未结算）
 * @param landForSale 待售土地（LandForSale）
 * @param landMarketEscrowGrain 土地市场托管的粮
 * @param landMarketEscrowMoney 土地市场托管的钱
 * @param totalLeaseHolding 全池租约持有合计
 * @param config 世界/模式参数（含 mode、lender、催收政策、MobilityPolicy）
 * @param totals 累计流水读数
 * @param initial 初始基数（守恒对账用）
 * @param stockEnrichmentViolations 迁移 bundle 后原池人均库存提高的违例计数（应为 0）
 */
public record ClassFirstMeta(
    long tick,
    long landForSale,
    long landMarketEscrowGrain,
    long landMarketEscrowMoney,
    long totalLeaseHolding,
    PilotConfig config,
    Totals totals,
    InitialTotals initial,
    long stockEnrichmentViolations) {

  public ClassFirstMeta {
    if (totals == null) {
      totals = Totals.empty();
    }
    if (initial == null) {
      initial = InitialTotals.empty();
    }
  }

  /** 空态：尚未播种，无参数。 */
  public static ClassFirstMeta empty() {
    return new ClassFirstMeta(0L, 0L, 0L, 0L, 0L, null, Totals.empty(), InitialTotals.empty(), 0L);
  }

  /** 累计流水读数（与引擎对外的 getter 一一对应）。 */
  public record Totals(
      long producedGrainTotal,
      long seedUsedTotal,
      long rationConsumedTotal,
      long clothConsumedTotal,
      long borrowedGrainTotal,
      long borrowedMoneyTotal,
      long boughtGrainTotal,
      long liquidSeizedTotal,
      long landSeizedTotal,
      long capitalizedTotal,
      long redLightTotal,
      long collectionEventCount,
      long interestChargedTotal,
      long rentPaidTotal,
      long wagePaidTotal,
      long externalSeedPaidTotal,
      long residualPaidTotal,
      long taxPaidTotal) {

    public static Totals empty() {
      return new Totals(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }
  }

  /** 初始基数（引擎构造/播种时冻结，守恒对账的锚）。 */
  public record InitialTotals(
      long grainTotal,
      long clothTotal,
      long householdMoneyTotal,
      long lenderMoneyTotal,
      long populationTotal,
      long ownedLandTotal,
      long toolsTotal,
      long claimGrainMilli) {

    public static InitialTotals empty() {
      return new InitialTotals(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }
  }
}

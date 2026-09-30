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

  /**
   * ★ 纯 copy-with：只换 {@link #config}，其余组件原样带过（class-first 阶段 2 的 GM 参数调整落点）。
   *
   * <p>★ 为什么需要它：{@code meta.config} 是引擎 {@code restore} 直接读的权威源参数面；没有这个 copy-with， GM 调整就得手抄九个组件构造
   * meta（铁律 5 的漂移形态）。{@code config} 只在空态为 null，非空态必须完整 ⇒ null 拒绝。
   *
   * @param replacement 新的世界/模式参数；不得为 null
   * @throws IllegalArgumentException replacement 为 null
   */
  public ClassFirstMeta withConfig(PilotConfig replacement) {
    if (replacement == null) {
      throw new IllegalArgumentException("withConfig 的 replacement 不得为 null");
    }
    return new ClassFirstMeta(
        tick,
        landForSale,
        landMarketEscrowGrain,
        landMarketEscrowMoney,
        totalLeaseHolding,
        replacement,
        totals,
        initial,
        stockEnrichmentViolations);
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

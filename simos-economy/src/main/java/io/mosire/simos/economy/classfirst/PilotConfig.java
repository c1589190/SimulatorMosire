package io.mosire.simos.economy.classfirst;

/**
 * 阶层池经济的显式制度/技术参数。
 *
 * <p>数量一律是整数：粮食/布匹按件，货币按最小单位，劳动按"千分劳动单位"（1000 = 一个全劳力/ tick），比例按千分比。 {@link #tenancyAgriculture}
 * 给出一套能跑 360 tick 的最小参数；测试只显式构造 lender 与催收政策。人口流动/GM 政策单独放在 {@link MobilityPolicy}（{@link
 * #mobilityPolicy}），GM 只改它、不改派生读数。
 */
public record PilotConfig(
    PilotModel.Mode mode,
    PilotModel.Lender lender,
    PilotModel.CollectionPolicy collectionPolicy,
    MobilityPolicy mobilityPolicy,
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
    long reserveTicks,
    long collectionIntervalTicks) {

  public PilotConfig {
    if (mode == null || lender == null || collectionPolicy == null || mobilityPolicy == null) {
      throw new IllegalArgumentException(
          "mode/lender/collectionPolicy/mobilityPolicy must be present");
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
    if (collectionPolicy.landPricePerUnit() <= 0L) {
      throw new IllegalArgumentException("landPricePerUnit must be > 0");
    }
  }

  /**
   * ★ GM 可调的 15 个生产/技术/制度标量（{@code setProductionParameters} 的字段全集；顺序与 {@link PilotConfig}
   * 的对应组件逐项一致）。
   *
   * <p>★ 为什么单独一个 record：{@link #withTuning} 是这 15 个字段的**唯一重建点** —— GM 调整从 {@link #currentTuning()}
   * 起步、只覆盖给到的字段，再走一次 canonical 构造；这样 {@link PilotConfig} 增删组件时不会在调整路径里手抄 19 个组件而静默漂移（铁律 5 的教训）。
   *
   * <p>★ 字段边界由调整工具在写入口先校验（见 {@code EconomyGmAdjustments.setProductionParameters}），这里只承载值。
   *
   * @param yieldPerLand 单位土地产出（&gt; 0）
   * @param seedPerLand 单位土地种子（&ge; 0）
   * @param laborPerLand 单位土地劳动（&gt; 0）
   * @param toolCapacityPerTool 单件工具容量（&gt; 0）
   * @param rentPerLand 单位土地实物粮租（&ge; 0）
   * @param wagePerLabor 单位劳动工资（&ge; 0）
   * @param baseRationPerCapita 人均基础口粮（&ge; 0）
   * @param laborRationPerLabor 实际出劳动者额外口粮（&ge; 0）
   * @param nonEssentialNeedPerMille 每千人每 tick 非必需（布）需求千分比（&ge; 0）
   * @param nonEssentialEfficiencyPenaltyPerMille 非必需全缺时的效率惩罚千分比（&ge; 0）
   * @param loanInterestRatePerMille 家户间欠款/欠租每 tick 利息千分比（&ge; 0）
   * @param moneyPerGrain 单位粮的货币价（&gt; 0）
   * @param toolPricePerUnit 催收估值用单件工具价（&ge; 0）
   * @param reserveTicks 保护储备 = 多少 tick 的口粮（&ge; 0）
   * @param collectionIntervalTicks 催收/到期滚动间隔（&ge; 1；{@code tick + interval} 做到期）
   */
  public record Tuning(
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
      long reserveTicks,
      long collectionIntervalTicks) {}

  /**
   * ★ R2c：只换放贷主体（世界级合并后 lender 资金/商品是各国之和）—— 其余制度/技术参数逐字保留。
   *
   * <p>★ 为什么需要它：{@link ClassFirstState#merge} 要把多国 seed 的池与 lender 求和，而 {@code meta.config.lender}
   * 也必须指向同一份合并后的 lender；否则"状态里的 lender"与"参数里的 lender"会成为同一件事的两处拼写。
   */
  public PilotConfig withLender(PilotModel.Lender replacement) {
    if (replacement == null) {
      throw new IllegalArgumentException("withLender 的 replacement 不得为 null");
    }
    return new PilotConfig(
        mode,
        replacement,
        collectionPolicy,
        mobilityPolicy,
        yieldPerLand,
        seedPerLand,
        laborPerLand,
        toolCapacityPerTool,
        rentPerLand,
        wagePerLabor,
        baseRationPerCapita,
        laborRationPerLabor,
        nonEssentialNeedPerMille,
        nonEssentialEfficiencyPenaltyPerMille,
        loanInterestRatePerMille,
        moneyPerGrain,
        toolPricePerUnit,
        reserveTicks,
        collectionIntervalTicks);
  }

  /**
   * ★ 纯 copy-with：只换催收政策（{@code setCollectionPolicy} 的落点）—— mode/lender/mobilityPolicy 与 15
   * 个标量逐字保留。
   *
   * <p>★ 与 {@link #withLender} 同一条纪律：一次 canonical 构造、逐个组件显式带过；催收政策内部哪个字段可调由 GM 写入口（{@code
   * EconomyGmAdjustments.setCollectionPolicy}）判定，本方法只负责不丢组件。
   */
  public PilotConfig withCollectionPolicy(PilotModel.CollectionPolicy replacement) {
    if (replacement == null) {
      throw new IllegalArgumentException("withCollectionPolicy 的 replacement 不得为 null");
    }
    return new PilotConfig(
        mode,
        lender,
        replacement,
        mobilityPolicy,
        yieldPerLand,
        seedPerLand,
        laborPerLand,
        toolCapacityPerTool,
        rentPerLand,
        wagePerLabor,
        baseRationPerCapita,
        laborRationPerLabor,
        nonEssentialNeedPerMille,
        nonEssentialEfficiencyPenaltyPerMille,
        loanInterestRatePerMille,
        moneyPerGrain,
        toolPricePerUnit,
        reserveTicks,
        collectionIntervalTicks);
  }

  /** 现取 15 个可调标量（与 {@link #withTuning} 成对；GM 调整的起点，未给字段保持原值）。 */
  public Tuning currentTuning() {
    return new Tuning(
        yieldPerLand,
        seedPerLand,
        laborPerLand,
        toolCapacityPerTool,
        rentPerLand,
        wagePerLabor,
        baseRationPerCapita,
        laborRationPerLabor,
        nonEssentialNeedPerMille,
        nonEssentialEfficiencyPenaltyPerMille,
        loanInterestRatePerMille,
        moneyPerGrain,
        toolPricePerUnit,
        reserveTicks,
        collectionIntervalTicks);
  }

  /**
   * ★ <b>这 15 个标量的唯一重建点</b>：用 {@code replacement} 整体替换本 config 的对应字段；{@code mode}/{@code
   * lender}/{@code collectionPolicy}/{@code mobilityPolicy} 原样保留。
   *
   * <p>★ 为什么需要它：{@code setProductionParameters} 必须从 {@link #currentTuning()} 起步、只覆盖给到的字段 —— 若在
   * 调整路径手抄 19 个组件，将来加组件时必然漂移（铁律 5 的教训）。字段范围由 GM 写入口先校验，这里只做 canonical 构造 与 {@link PilotConfig}
   * 构造器的既有硬校验。
   */
  public PilotConfig withTuning(Tuning replacement) {
    if (replacement == null) {
      throw new IllegalArgumentException("withTuning 的 replacement 不得为 null");
    }
    return new PilotConfig(
        mode,
        lender,
        collectionPolicy,
        mobilityPolicy,
        replacement.yieldPerLand(),
        replacement.seedPerLand(),
        replacement.laborPerLand(),
        replacement.toolCapacityPerTool(),
        replacement.rentPerLand(),
        replacement.wagePerLabor(),
        replacement.baseRationPerCapita(),
        replacement.laborRationPerLabor(),
        replacement.nonEssentialNeedPerMille(),
        replacement.nonEssentialEfficiencyPenaltyPerMille(),
        replacement.loanInterestRatePerMille(),
        replacement.moneyPerGrain(),
        replacement.toolPricePerUnit(),
        replacement.reserveTicks(),
        replacement.collectionIntervalTicks());
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
    return tenancyAgriculture(
        lender, collectionPolicy, collectionIntervalTicks, MobilityPolicy.tenancyDefaults());
  }

  /** 同上，但显式给出 GM 可调的 MobilityPolicy。 */
  public static PilotConfig tenancyAgriculture(
      PilotModel.Lender lender,
      PilotModel.CollectionPolicy collectionPolicy,
      long collectionIntervalTicks,
      MobilityPolicy mobilityPolicy) {
    return new PilotConfig(
        PilotModel.tenancyMode(),
        lender,
        collectionPolicy,
        mobilityPolicy,
        12L, // yieldPerLand
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
        3L, // reserveTicks（保护储备 = 3 tick 的口粮）
        collectionIntervalTicks);
  }
}

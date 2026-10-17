package io.mosire.simos.production.contract;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>① 试算：结果</b>（D-032 约束设计书 §4.2「输出：可行性(具名 reason) · 可生产规模 · 劳动需求 · 原料/资产需求 · 预计产出（物理量）」）。
 *
 * <p>★★ <b>不可行也必须给结果对象</b>（而不是抛）：不可行是<b>业务拒绝</b>（INFO 具名理由，I15）， 世界照常推进；只有<b>契约故障</b>（公式求值错、引用悬空）才抛
 * {@link ProductionContractFault}。
 *
 * <p>★ <b>{@code scale == 0} 与 {@code feasible == false} 的区别</b>：不可行 ⇒ 规模 0；但"可行、只是这一份
 * 投入连一个规模单位都不够"也会给 {@code feasible=true, scale=0}。调用方按 {@link #producible()} 判"值不值得排进分配"。
 *
 * <p>★★ <b>预计与实际共用同一方案、同一公式、同一覆盖参数、同一时间单位</b>（I6）：本结果的每一个数字都由 {@code ProductionRuntime.execute}
 * 走<b>同一批公式函数</b>算出；实际值按最终投入重算，允许与预计不同， 但<b>不得</b>用预计值顶替实际值。
 *
 * <p>★★ <b>A′ 批（§13.8 裁定一）："产出规模"与"资源需求"是两个不同的规模口径</b>—— {@link #scale()} 是<b>产出</b>规模（{@code
 * finalScale} = 基准规模整体乘一次效率倍率）， 而 {@link #laborRequiredMilli()}/{@link #assetRequired()}/{@link
 * #inputRequired()} 按<b>基准规模</b> （{@code baseScale} = 三通道都不乘 E 的最紧约束）计 ⇒ <b>同样的投入产出更多</b>，
 * 加成<b>不</b>凭空多占土地/工具/原料。★ 调用方<b>不要</b>拿 {@code scale} 自己乘每单位需求 （那是旧的"规模即投入"口径）；要多少资源看 {@code
 * *Required} 三件。
 *
 * @param plan 方案稳定 ID（回显，便于日志/断言）
 * @param feasible 是否可行
 * @param reason 不可行时的<b>具名</b>理由（稳定短 id，如 {@code plan-unknown}/{@code locked}/ {@code
 *     condition-unsatisfied}/{@code asset-short}/{@code labor-short}/{@code input-short}）； 可行时为
 *     null
 * @param scale <b>产出</b>规模（{@code finalScale}，最紧基准约束整体乘一次效率倍率后向下取整）； 不得为负
 * @param laborRequiredMilli <b>基准规模</b>对应的劳动需求（千分劳动·日；{@code baseScale × laborPerUnit}）； 不得为负。★
 *     LABOR 局部模型下它可能大于调用方传入的可用劳动——那正是"劳动被折算成更多规模"的后果
 * @param assetRequired <b>基准规模</b>对应的资产需求（按种类；{@code baseScale × capacityPerUnit}）； 不得为 null、逐值不得为负
 * @param inputRequired <b>基准规模</b>对应的原料需求（本周期一次性，毫单位；{@code baseScale × inputPerUnit}）； 不得为
 *     null、逐值不得为负
 * @param expectedOutput 预计产出（物理量，毫单位；按 {@code scale} 即 {@code finalScale} 算）； 不得为 null、逐值不得为负
 * @param expectedMint 预计铸币候选数量（毫货币；按 {@code finalScale} 算；空表 = 这一路不存在）；不得为 null
 * @param efficiencyScalePerMille 本次试算采用的效率倍率（千分制；1000 = 不加成）——★ 回显它是为了让
 *     "预计/实际同源"可核对（V2/V7），不是让调用方自己再乘一遍
 */
public record ProductionProbeResult(
    IndustryId plan,
    boolean feasible,
    String reason,
    long scale,
    long laborRequiredMilli,
    Map<AssetKind, Long> assetRequired,
    Map<CommodityId, Long> inputRequired,
    Map<CommodityId, Long> expectedOutput,
    Map<CurrencyId, Long> expectedMint,
    long efficiencyScalePerMille) {

  public ProductionProbeResult {
    Objects.requireNonNull(plan, "ProductionProbeResult.plan 不得为 null");
    if (!feasible && (reason == null || reason.isBlank())) {
      throw new IllegalArgumentException("ProductionProbeResult 不可行时必须给具名 reason");
    }
    if (feasible && reason != null && !reason.isBlank()) {
      throw new IllegalArgumentException("ProductionProbeResult 可行时不得带拒绝理由: " + reason);
    }
    if (scale < 0L) {
      throw new IllegalArgumentException("ProductionProbeResult.scale 不得为负: " + scale);
    }
    if (laborRequiredMilli < 0L) {
      throw new IllegalArgumentException(
          "ProductionProbeResult.laborRequiredMilli 不得为负: " + laborRequiredMilli);
    }
    if (efficiencyScalePerMille < 0L) {
      throw new IllegalArgumentException(
          "ProductionProbeResult.efficiencyScalePerMille 不得为负: " + efficiencyScalePerMille);
    }
    // ★★ B11：四张表**就地**逐字展开冻结（照 MarketTable.hexes / EconomyMeta 政策表 / ProductionData 的形制）。
    //   ★ 为什么必须写在本构造器里、不能抽私有 helper：SpotBugs 的 EI_EXPOSE_REP **不做跨过程分析**，只认它在这
    //     一个方法里看得见的 Collections.unmodifiableMap ⇒ 抽成 helper 会让生成的 record 访问器被报成"暴露内部
    //     表示"（本类 4 条）。★ 语义与抽 helper 时**逐字节相同**：同一保序拷贝、同一组守卫、同一批拒绝消息。
    if (assetRequired == null) {
      throw new IllegalArgumentException("ProductionProbeResult.assetRequired 不得为 null");
    }
    Map<AssetKind, Long> assetCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : assetRequired.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionProbeResult.assetRequired 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionProbeResult.assetRequired 不得为负：" + entry.getKey());
      }
      assetCopy.put(entry.getKey(), entry.getValue());
    }
    assetRequired = Collections.unmodifiableMap(assetCopy); // ★ 冻在赋值处

    if (inputRequired == null) {
      throw new IllegalArgumentException("ProductionProbeResult.inputRequired 不得为 null");
    }
    Map<CommodityId, Long> inputCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : inputRequired.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionProbeResult.inputRequired 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionProbeResult.inputRequired 不得为负：" + entry.getKey());
      }
      inputCopy.put(entry.getKey(), entry.getValue());
    }
    inputRequired = Collections.unmodifiableMap(inputCopy); // ★ 冻在赋值处

    if (expectedOutput == null) {
      throw new IllegalArgumentException("ProductionProbeResult.expectedOutput 不得为 null");
    }
    Map<CommodityId, Long> outputCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : expectedOutput.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionProbeResult.expectedOutput 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionProbeResult.expectedOutput 不得为负：" + entry.getKey());
      }
      outputCopy.put(entry.getKey(), entry.getValue());
    }
    expectedOutput = Collections.unmodifiableMap(outputCopy); // ★ 冻在赋值处

    if (expectedMint == null) {
      throw new IllegalArgumentException("ProductionProbeResult.expectedMint 不得为 null");
    }
    Map<CurrencyId, Long> mintCopy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : expectedMint.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ProductionProbeResult.expectedMint 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionProbeResult.expectedMint 不得为负：" + entry.getKey());
      }
      mintCopy.put(entry.getKey(), entry.getValue());
    }
    expectedMint = Collections.unmodifiableMap(mintCopy); // ★ 冻在赋值处
  }

  /** ★ 可产出的规模是否为正（"可行但一个规模单位都做不了"不算能排进分配）。 */
  public boolean producible() {
    return feasible && scale > 0L;
  }

  /** ★ 具名拒绝结果（业务拒绝 = INFO，不抛）。 */
  public static ProductionProbeResult rejected(IndustryId plan, String reason) {
    return new ProductionProbeResult(
        plan, false, reason, 0L, 0L, Map.of(), Map.of(), Map.of(), Map.of(), 1_000L);
  }

  /**
   * ★★★ <b>{@code laborRequiredMilli} 的取整 slack（D-032 §13.11.3 裁定七第 2 条）</b>。
   *
   * <p>★★ <b>为什么需要它</b>：{@code laborRequiredMilli} 在 {@code LABOR} 声明下按 {@code ⌈baseScale ×
   * laborPerUnit × 1000 ÷ E_cycle⌉} <b>向上取整</b>（欠投会让规模差一个单位）， 而 {@code baseScale}
   * 本身来自<b>向下取整</b>的规模派生 ⇒ 在"可用劳动恰好等于需求"的临界点上， 两个方向的取整可能差出 <b>1 个劳动单位（千分劳动·日）</b>。
   *
   * <p>★★ <b>契约口径</b>：经济侧判"可行即需求可被满足"时，用 {@code laborRequiredMilli ≤ 可用劳动 + }{@link
   * #LABOR_REQUIRED_ROUNDING_SLACK_MILLI} —— 命中 slack 内时把需求<b>钳到可用劳动</b>（多出的那 1 个单位是取整余量，不是真实欠投）；
   * <b>超出 slack 才</b>是契约故障（{@code probe-labor-required-exceeds-available}，ERROR 不降级）。
   *
   * <p>★ 这不是"放宽校验"：它把**取整伪阳**与**口径写错**分开 —— 前者是数值表示的正常余量（1 个单位）， 后者是要终止整个候选提交的真实故障。
   */
  public static final long LABOR_REQUIRED_ROUNDING_SLACK_MILLI = 1L;
}

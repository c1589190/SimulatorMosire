package io.mosire.simos.production;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.production.contract.PerCycleCarry;
import io.mosire.simos.production.contract.ProductionContractFault;
import io.mosire.simos.production.contract.ProductionEffectScope;
import io.mosire.simos.production.contract.ProductionEfficiencyState;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import java.util.Map;

/**
 * ★★★ <b>生产效率公式（★ A′ 批按 §13.8 裁定一改成"资源约束后整体乘一次"）与精度余数结转</b> （D-032 约束设计书
 * §3「效率累计与精度余数」；`...-experience-choice-design.md` §2.4）。
 *
 * <p>★★ <b>与旧 {@code ProductionEfficiencyBook} 的关系：重建，不是照搬</b>。旧面把"文化/行政注入的逐 tick 修正 {@code m_t} ∈
 * [0,2000]‰"累进 {@code cycleModifierSumPerMille}，再在周期末做 {@code scale = scaleBase × avgModifier ÷
 * 1000}。本批：
 *
 * <ul>
 *   <li><b>删掉</b>逐 tick 修正累加与 {@code [0,2000]‰} 的有效域（那条通路随 D2 一起删：设计原文明令 "不得把现有 {@code [0,2000]‰}
 *       接收口的历史限制直接冒充用户对新公式的数值上限"）；
 *   <li><b>换成</b>用户 U4 的熟练度加成，且<b>默认是"总生产效率倍率"</b>（见 {@link ProductionEffectScope}）： 三通道都不乘 E
 *       先取基准规模，再整体乘一次（§13.8 裁定一原文"资源约束后的基准总产出 100 再应用 1.5 倍效率则为 150"）；方案显式声明"只作用于劳动效率"时才按局部模型算（复核裁决
 *       §3 原文）；
 *   <li><b>保留</b>整数除法的<b>余数跨周期结转</b>机制（那是"长期不再系统性丢精度"的落点， 旧面的 {@code laborDayRemainderMilli}/{@code
 *       laborScaleRemainderMilli} 就是它）。
 * </ul>
 *
 * <p>★★ <b>A′ 批新公式（唯一拼写点；全程 long 整数、禁 double）</b>：
 *
 * <pre>
 * ⓪ 逐 tick（{@code ProductionRuntimeEngine.execute} 每个生产日做，不在这里）：
 *      加权劳动_h += 劳动_h,tick × E_h,tick        // ★ 精确乘积，不除 1000、不截断（无"折算余数"）
 * ① 周期 → 日均：   avgLabor       = (Σ_h 劳动_h + carry₁) ÷ cycleDays              ; carry₁' = 余数
 * ② 日均 → 规模：   baseLaborScale = (avgLabor + carry₂) ÷ laborPerUnit             ; carry₂' = 余数
 *                  （laborPerUnit == 0 ⇒ 劳动那一路不构成约束，carry₂ 原样结转）
 * ③ 资产通道：      assetScale     = min_k ⌊实占资产_k ÷ capacityPerUnit_k⌋          // 物理占用，不乘 E
 * ④ 原料通道：      inputScale     = min_j ⌊实扣原料_j ÷ inputPerUnit_j⌋             // 物理实扣，不乘 E
 * ⑤ 基准规模：      baseScale      = min(baseLaborScale, assetScale, inputScale)     // ★ 三通道都不乘 E
 *                  baseScale      = min(baseScale, 授予上限)                         // economy 统一预算的硬上限
 * ⑥ 总效率倍率：    E_cycle        = ⌊Σ_h 加权劳动_h ÷ Σ_h 劳动_h⌋                    // 按实际劳动贡献加权
 *                  （Σ劳动 == 0 ⇒ 1000 中性；见"退化口径"）
 * ⑦ 唯一乘算点：
 *      TOTAL（默认）：finalScale = ⌊(baseScale × E_cycle + carry₃) ÷ 1000⌋ ; carry₃' = 余数
 *                     投入消耗与占用按 baseScale；产出/回执的实毛产按 finalScale
 *      LABOR（显式）：劳动通道先折算 劳动规模' = ⌊(min(baseLaborScale, 上限) × E_cycle + carry₃) ÷ 1000⌋
 *                     finalScale = baseScale' = min(劳动规模', assetScale, inputScale, 上限)
 * </pre>
 *
 * <p>★★ <b>为什么 TOTAL 是"约束后整体乘一次"</b>（§13.8 裁定一）：把 E 乘进某一条通道再取 min，在 <b>另一条通道主导</b>时加成整段归零（复核 §3
 * 反例：劳动 100、资产 100、倍率 1.5 ⇒ min(150,100)=100）。 基准规模是"资源允许做多少单位"，效率是"同样的资源做出多少产出" ⇒ 倍率只能作用在 min
 * 之后、且只一次。 ★ <b>资产通道不得在 TOTAL 下单独乘 E</b>（A 批现状即此，裁定明文：结构不对称是症状、不是问题本身）。
 *
 * <p>★★ <b>不凭空多占资源</b>（裁决 §3「加成不意味着凭空多占土地、工具或原料」）：TOTAL 下投入消耗与 占用按 <b>baseScale</b>
 * 计（同样投入产出更多），倍率<b>不</b>新增任何资产/原料单位；原料通道<b>始终是 物理实扣量</b>。不足一个整数规模的零头进 carry（不丢、也不凭空进整）。
 *
 * <p>★★ <b>退化口径必须与"缺事实 fail-closed"分列</b>（§13.8 裁定一）：
 *
 * <ul>
 *   <li><b>零劳动</b>（Σ_h 劳动_h == 0）：{@code E_cycle = 1000}（<b>中性</b>，不是故障，也不做除法） —— "本周期没人出工 ⇒
 *       没有可测的加权效率"。此时劳动通道规模通常为 0 ⇒ 规模 0；若 {@code laborPerUnit == 0}（劳动不构成约束），则 {@code finalScale =
 *       baseScale}（<b>不</b>给加成）。
 *   <li><b>除零</b>：不存在——除法的分母是 Σ劳动，零的情形由上一条截住（{@code cycleDays ≥ 1} 与 {@code laborPerUnit == 0}
 *       分支各自的守卫见下）。
 *   <li><b>无经验</b>（方案没挂经验条 / 熟练度为 0）：E 由<b>方案配置的公式</b>求值给出（用户 U5「公式可 配置」）——标准例式在零熟练度处恒为
 *       1000‰；若某方案把零熟练度配成别的值（含 0），那是<b>配置 语义</b>，不是故障。★ 倍率为负才是契约故障。
 *   <li><b>缺事实 fail-closed</b>（与上面几条<b>分列</b>）：方案引用悬空、公式求值失败、余数越域、 逐家户读数出现负数 ⇒ {@link
 *       ProductionContractFault}（ERROR 不降级，整个候选提交被拒）， <b>绝不</b>退化成"中性倍率"继续算。
 * </ul>
 *
 * <p>★★ <b>唯一入口</b>：试算（{@code probe}）、实际（{@code execute} 关账）与家户利润比较都走本类的 {@link
 * #evaluate}（I6/V2；§13.8 裁定一"试算/实际/家户利润比较共用同一入口"）。本类<b>不</b>读状态、
 * <b>不</b>查经验账——纯函数让"预计"与"实际"能走同一个函数、同一份输入。
 */
final class ProductionEfficiency {

  /** 千分制乘算的模（与 {@link PerCycleCarry#EFFICIENCY_REMAINDER_MODULUS} 同源）。 */
  private static final long PER_MILLE = 1_000L;

  /** 中性效率倍率（千分制；1000 = 不加成）。 */
  static final long NEUTRAL_EFFICIENCY_PER_MILLE = 1_000L;

  private ProductionEfficiency() {}

  /**
   * ★ <b>周期末求值结果</b>（★ A′ 批：三通道基准规模 + 唯一乘算点）。
   *
   * @param efficiencyWeightedLaborMilli Σ 逐家户<b>加权劳动</b>（{@code Σ(劳动 × 当 tick 生效效率)}； 单位 = 千分劳动·日 ×
   *     千分倍率，<b>不</b>除 1000）—— E_cycle 的分子
   * @param avgLaborMilli ① 之后的日均<b>实际</b>劳动（千分劳动·日）
   * @param baseLaborScale ② 之后的劳动通道<b>基准</b>规模（未乘 E）
   * @param assetScale ③ 之后的资产通道基准规模（未乘 E；= {@code min_k ⌊实占_k ÷ 每单位_k⌋}）
   * @param inputScale ④ 之后的原料通道基准规模（未乘 E；= {@code min_j ⌊实扣_j ÷ 每单位_j⌋}）
   * @param baseScale ⑤ 之后的最紧基准规模（三通道 min，且已受授予上限约束）——★ <b>投入消耗与占用</b> 按它计
   * @param finalScale ⑦ 之后的<b>产出</b>规模（TOTAL：⌊baseScale × E ÷ 1000⌋；LABOR：等于 baseScale'） ——★
   *     <b>产出/回执的实毛产</b>按它计
   * @param efficiencyScalePerMille ⑥ 的代表效率倍率 E_cycle（Σ加权劳动 ÷ Σ劳动；零劳动 ⇒ 1000）
   * @param nextCarry 写回的余数行（三段全零 ⇒ {@code null} = 不建行/移除该行）
   */
  record Evaluation(
      long efficiencyWeightedLaborMilli,
      long avgLaborMilli,
      long baseLaborScale,
      long assetScale,
      long inputScale,
      long baseScale,
      long finalScale,
      long efficiencyScalePerMille,
      ProductionEfficiencyState nextCarry) {}

  /**
   * ★★ <b>周期末求值</b>（"预计"与"实际"共用的唯一入口；也是"约束后整体乘一次"的唯一乘算点）。
   *
   * @param state 上一周期结转的余数行；{@code null} = 缺行（三段全 0）
   * @param scope 方案声明的加成通道（{@link ProductionExperienceUse#effectScope()}）；不得为 null
   * @param cycleDays 方案的生产周期（天）；必须 ≥ 1
   * @param efficiencyWeightedLaborByHousehold 本周期<b>逐家户</b>加权劳动 （{@code Σ_ticks 劳动 × 当 tick
   *     生效效率}；<b>不</b>除 1000、<b>不</b>截断；保序，迭代序即计算序）
   * @param laborByHousehold 本周期<b>逐家户</b>实际投入劳动（千分劳动·日）；E_cycle 的分母
   * @param assetScaleRaw 资产那一路的原始规模（{@code min_k ⌊可用_k ÷ 每单位_k⌋}）；不得为负
   * @param inputPerUnit 配方每单位投入（逐商品；每单位需求 ≤ 0 的路不施加约束）
   * @param cycleInputUsedMilli 本周期实际扣到的投入（逐商品；缺失键 = 0）
   * @param laborPerUnit 配方每单位规模劳动；{@code 0} = 劳动那一路不适用
   * @param maxBaseScale <b>授予上限</b>（economy 统一预算的硬上限，作用在<b>基准规模</b>即投入侧）； 不给上限传 {@code
   *     Long.MAX_VALUE}；不得为负
   */
  static Evaluation evaluate(
      ProductionEfficiencyState state,
      ProductionEffectScope scope,
      long cycleDays,
      Map<io.mosire.simos.social.api.id.HouseholdId, Long> efficiencyWeightedLaborByHousehold,
      Map<io.mosire.simos.social.api.id.HouseholdId, Long> laborByHousehold,
      long assetScaleRaw,
      Map<CommodityId, Long> inputPerUnit,
      Map<CommodityId, Long> cycleInputUsedMilli,
      long laborPerUnit,
      long maxBaseScale) {
    if (scope == null) {
      contractFault("effectScope 不得为 null（方案必须声明加成作用通道）");
    }
    if (cycleDays < 1L) {
      contractFault("cycleDays 必须 ≥ 1: " + cycleDays);
    }
    if (assetScaleRaw < 0L) {
      contractFault("assetScaleRaw 不得为负: " + assetScaleRaw);
    }
    if (laborPerUnit < 0L) {
      contractFault("laborPerUnit 不得为负: " + laborPerUnit);
    }
    if (maxBaseScale < 0L) {
      contractFault("授予上限 maxBaseScale 不得为负: " + maxBaseScale);
    }
    PerCycleCarry carry =
        state == null || state.carry() == null ? PerCycleCarry.ZERO : state.carry();
    requireValidCarry(carry, cycleDays, laborPerUnit);

    // ── ⓪ 周期累计（逐家户求 Σ；tick 通道已由引擎精确累加，本类不再折算）──────────────────────
    long weightedLabor = 0L;
    for (Map.Entry<io.mosire.simos.social.api.id.HouseholdId, Long> entry :
        efficiencyWeightedLaborByHousehold.entrySet()) {
      long value = entry.getValue() == null ? 0L : entry.getValue();
      if (value < 0L) {
        contractFault("逐家户加权劳动不得为负（家户 " + entry.getKey().value() + "）: " + value);
      }
      weightedLabor = Math.addExact(weightedLabor, value);
    }
    long totalLabor = 0L;
    for (Map.Entry<io.mosire.simos.social.api.id.HouseholdId, Long> entry :
        laborByHousehold.entrySet()) {
      long value = entry.getValue() == null ? 0L : entry.getValue();
      if (value < 0L) {
        contractFault("逐家户实际劳动不得为负（家户 " + entry.getKey().value() + "）: " + value);
      }
      totalLabor = Math.addExact(totalLabor, value);
    }

    // ── ① 周期 → 日均（实际劳动；余数跨周期结转）──────────────────────────────────────────
    long laborNumerator = Math.addExact(totalLabor, carry.laborDayRemainderMilli());
    long avgLabor = laborNumerator / cycleDays;
    long nextLaborDayRemainder = laborNumerator % cycleDays;

    // ── ② 日均 → 基准规模（laborPerUnit == 0 ⇒ 劳动那一路不构成约束，余数原样结转）──────────
    boolean laborPerUnitZero = laborPerUnit <= 0L;
    long baseLaborScale;
    long nextLaborScaleRemainder;
    if (laborPerUnitZero) {
      baseLaborScale = avgLabor;
      nextLaborScaleRemainder = carry.laborScaleRemainderMilli();
    } else {
      long numerator = Math.addExact(avgLabor, carry.laborScaleRemainderMilli());
      baseLaborScale = numerator / laborPerUnit;
      nextLaborScaleRemainder = numerator % laborPerUnit;
    }

    // ── ③ 资产通道：物理占用（★ 不乘 E —— A′ 批废除了 A 批"资产通道单独乘 E"的不对称）─────────
    long assetScale = assetScaleRaw;

    // ── ④ 原料通道：物理实扣量（不乘 E）──────────────────────────────────────────────────
    long inputScale = Long.MAX_VALUE;
    for (Map.Entry<CommodityId, Long> entry : inputPerUnit.entrySet()) {
      Long perUnit = entry.getValue();
      if (perUnit == null || perUnit <= 0L) {
        continue; // 每单位需求 ≤ 0 ⇒ 这一路不构成约束（旧口径：seedPerMu == 0 同款）
      }
      long drawn = cycleInputUsedMilli.getOrDefault(entry.getKey(), 0L);
      inputScale = Math.min(inputScale, drawn / perUnit);
    }

    // ── ⑤ 基准规模：三通道 min（★ 都不乘 E），再受授予上限约束（上限作用在投入侧）────────────
    long baseScale = laborPerUnitZero ? assetScale : Math.min(baseLaborScale, assetScale);
    if (inputScale != Long.MAX_VALUE) {
      baseScale = Math.min(baseScale, inputScale);
    }
    if (baseScale < 0L) {
      baseScale = 0L;
    }
    baseScale = Math.min(baseScale, maxBaseScale);
    long effectiveInputScale = inputScale == Long.MAX_VALUE ? 0L : inputScale;

    // ── ⑥ 总效率倍率：按实际劳动贡献加权的当 tick 生效值累计（零劳动 ⇒ 中性 1000，不是故障）────
    long representativeEfficiency =
        totalLabor <= 0L ? NEUTRAL_EFFICIENCY_PER_MILLE : Math.floorDiv(weightedLabor, totalLabor);
    if (representativeEfficiency < 0L) {
      contractFault("效率倍率不得为负: " + representativeEfficiency);
    }

    // ── ⑦ 唯一乘算点（TOTAL：基准规模整体乘一次；LABOR：只折算劳动通道后再 min）──────────────
    long finalScale;
    long nextEfficiencyRemainder;
    if (scope.scalesWholeBaseScale()) {
      long numerator =
          Math.addExact(
              Math.multiplyExact(baseScale, representativeEfficiency),
              carry.efficiencyRemainderMilli());
      finalScale = numerator / PER_MILLE;
      nextEfficiencyRemainder = numerator % PER_MILLE;
    } else {
      // LABOR：上限先作用在劳动通道（它是本模型里唯一被折算的那一路），折算后再与其余通道取 min。
      long laborBaseCapped = Math.min(baseLaborScale, maxBaseScale);
      long numerator =
          Math.addExact(
              Math.multiplyExact(laborBaseCapped, representativeEfficiency),
              carry.efficiencyRemainderMilli());
      long laborScaleScaled = numerator / PER_MILLE;
      nextEfficiencyRemainder = numerator % PER_MILLE;
      baseScale = Math.min(Math.min(laborScaleScaled, assetScale), maxBaseScale);
      if (inputScale != Long.MAX_VALUE) {
        baseScale = Math.min(baseScale, inputScale);
      }
      if (baseScale < 0L) {
        baseScale = 0L;
      }
      finalScale = baseScale;
    }

    ProductionEfficiencyState next =
        new ProductionEfficiencyState(
            new PerCycleCarry(
                nextLaborDayRemainder, nextLaborScaleRemainder, nextEfficiencyRemainder));
    requireValidCarry(next.carry(), cycleDays, laborPerUnit);
    return new Evaluation(
        weightedLabor,
        avgLabor,
        baseLaborScale,
        assetScale,
        effectiveInputScale,
        baseScale,
        finalScale,
        representativeEfficiency,
        next.allZero() ? null : next);
  }

  /**
   * ★ <b>三段余数的有效域校验</b>（使用与写回前 fail-closed；越域 ⇒ 契约故障 ERROR，不降级、不截断）。
   *
   * <p>★ 有效域依赖 per-plan 的 {@code cycleDays}/{@code laborPerUnit} ⇒ 只能在<b>用的时候</b>判， 不能写进 record
   * 构造期（那里看不见这两个值）。
   */
  static void requireValidCarry(PerCycleCarry carry, long cycleDays, long laborPerUnit) {
    if (carry == null) {
      return; // 缺行 = 全 0 余数，恒合法
    }
    if (carry.laborDayRemainderMilli() >= cycleDays) {
      contractFault(
          "laborDayRemainderMilli 越域（必须 ∈ [0, cycleDays) = [0, "
              + cycleDays
              + ")）: "
              + carry.laborDayRemainderMilli());
    }
    if (laborPerUnit <= 0L) {
      if (carry.laborScaleRemainderMilli() != 0L) {
        contractFault(
            "laborPerUnit == 0 ⇒ laborScaleRemainderMilli 必须为 0，实得 "
                + carry.laborScaleRemainderMilli());
      }
    } else if (carry.laborScaleRemainderMilli() >= laborPerUnit) {
      contractFault(
          "laborScaleRemainderMilli 越域（必须 ∈ [0, laborPerUnit) = [0, "
              + laborPerUnit
              + ")）: "
              + carry.laborScaleRemainderMilli());
    }
    if (carry.efficiencyRemainderMilli() >= PER_MILLE) {
      contractFault(
          "efficiencyRemainderMilli 越域（必须 ∈ [0, 1000)）: " + carry.efficiencyRemainderMilli());
    }
  }

  /** 契约故障（ERROR 不降级；由调用方冒泡到提案级，整个候选提交被拒）。 */
  private static void contractFault(String detail) {
    ProductionLog.runtime().error("event=PRODUCTION_EFFICIENCY_CONTRACT detail={}", detail);
    throw new ProductionContractFault("production-efficiency-contract", detail);
  }
}

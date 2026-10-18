package io.mosire.simos.production.contract;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★★ <b>① 试算（roster 形态）：结果</b>（D-032 P3 约束设计书 §8；审计 A2/A4 的只读结果面）。
 *
 * <p>★★ <b>逐参与户效率是本类型存在的主要理由</b>（A2）：{@link #participantEfficiencyPerMille()} 的每个值都由 {@code
 * ProductionRuntimeEngine} 调<b>与 execute 同一个</b> {@code efficiencyScaleFor}（同一 {@link
 * ProductionEfficiencyState} 余数、同一公式、同一熟练度/经验事实）算出。单户 {@link ProductionProbeResult} 只有
 * 一个代表倍率，无法表达"组织者经验与出工者经验不同"的协作生产。
 *
 * <p>★★ <b>工资招工目标的效率口径</b>（A3）：目标劳动<b>没有具名参与户</b> ⇒ 无工人经验事实可读，按<b>中性效率 1000‰</b>计入 {@link
 * #efficiencyScalePerMille()} 的劳动加权。★ 这不是复制一套公式：{@link #participantEfficiencyPerMille()}
 * 仍逐户给出精确值；只有"尚未招到的目标劳动"这一部分按中性倍率参与代表效率的加权平均。 招到实际工人后，调用方应把工人作为参与项传入，即可得到逐户精确值。
 *
 * <p>★★ <b>A4 的支撑面</b>：economy 需要按"本户产品份额值 + 工资应收 − 工资应付 − 投入负担"排序，而本结果给出 <b>总预计产出</b>（{@link
 * #expectedOutput()}）、<b>逐 (家户×角色) 效率</b>与总投入/资产/劳动需求；角色份额与户间拆分是 economy 的分配规则（{@code
 * RoleDistribution} 等），production 不在这里再算一份收益。⇒ 本批只提供事实，不实现 economy 排序（后续 P3c/economy 接线）。
 *
 * <p>★★ <b>不可行也必须给结果对象</b>（业务拒绝 = INFO 具名理由，I15）：只有契约故障（公式求值错、引用悬空）才抛 {@link
 * ProductionContractFault}。
 *
 * <p>★ <b>规模口径与单户 probe 逐字一致</b>（A′ 批 §13.8 裁定一）：{@link #baseScale()} 是投入侧基准规模 （三通道都不乘效率），{@link
 * #outputScale()} 是产出规模（基准规模整体乘一次效率倍率）； {@link #laborRequiredMilliPerDay()}/{@link
 * #assetRequired()}/{@link #inputRequired()} 按基准规模计， {@link #expectedOutput()}/{@link
 * #expectedMint()} 按产出规模计。
 *
 * @param plan 方案稳定 ID（回显，便于日志/断言）
 * @param feasible 是否可行
 * @param reason 不可行时的<b>具名</b>理由（稳定短 id，如 {@code plan-unknown}/{@code roster-empty}/ {@code
 *     organizer-not-participant}/{@code condition-missing:<household>}/{@code role-unknown:<role>}/
 *     {@code labor-short}/{@code asset-short}/{@code input-short:<commodity>}）；可行时为 null
 * @param baseScale 投入侧基准规模（{@code baseScale}）；不得为负
 * @param outputScale 产出规模（{@code finalScale}，基准规模整体乘一次效率倍率后向下取整）；不得为负
 * @param laborRequiredMilliPerDay 基准规模对应的劳动需求（口径与单户 {@link
 *     ProductionProbeResult#laborRequiredMilli()} 逐字相同；不得为负）
 * @param assetRequired 基准规模对应的资产需求（按种类）；不得为 null、逐值不得为负
 * @param inputRequired 基准规模对应的原料需求（本周期一次性，毫单位）；不得为 null、逐值不得为负
 * @param expectedOutput 预计产出（物理量，毫单位；按 {@link #outputScale()} 算）；不得为 null、逐值不得为负
 * @param expectedMint 预计铸币候选数量（毫货币；按 {@link #outputScale()} 算；空表 = 这一路不存在）；不得为 null
 * @param efficiencyScalePerMille 本次试算的代表效率倍率（千分制；1000 = 不加成）——逐户精确值见 {@link
 *     #participantEfficiencyPerMille()}；未具名招工目标按中性 1000‰ 加权
 * @param participantEfficiencyPerMille 逐 (家户×角色) 效率倍率（千分制；键与 execute 的 {@code
 *     ProductionParticipantKey} 同形）；不得为 null、逐值不得为负。缺键 = 该参与项没有效率事实（不应发生在可行结果里）
 */
public record ProductionRosterProbeResult(
    IndustryId plan,
    boolean feasible,
    String reason,
    long baseScale,
    long outputScale,
    long laborRequiredMilliPerDay,
    Map<AssetKind, Long> assetRequired,
    Map<CommodityId, Long> inputRequired,
    Map<CommodityId, Long> expectedOutput,
    Map<CurrencyId, Long> expectedMint,
    long efficiencyScalePerMille,
    Map<ProductionParticipantKey, Long> participantEfficiencyPerMille) {

  public ProductionRosterProbeResult {
    Objects.requireNonNull(plan, "ProductionRosterProbeResult.plan 不得为 null");
    if (!feasible && (reason == null || reason.isBlank())) {
      throw new IllegalArgumentException("ProductionRosterProbeResult 不可行时必须给具名 reason");
    }
    if (feasible && reason != null && !reason.isBlank()) {
      throw new IllegalArgumentException("ProductionRosterProbeResult 可行时不得带拒绝理由: " + reason);
    }
    if (baseScale < 0L) {
      throw new IllegalArgumentException(
          "ProductionRosterProbeResult.baseScale 不得为负: " + baseScale);
    }
    if (outputScale < 0L) {
      throw new IllegalArgumentException(
          "ProductionRosterProbeResult.outputScale 不得为负: " + outputScale);
    }
    if (laborRequiredMilliPerDay < 0L) {
      throw new IllegalArgumentException(
          "ProductionRosterProbeResult.laborRequiredMilliPerDay 不得为负: " + laborRequiredMilliPerDay);
    }
    if (efficiencyScalePerMille < 0L) {
      throw new IllegalArgumentException(
          "ProductionRosterProbeResult.efficiencyScalePerMille 不得为负: " + efficiencyScalePerMille);
    }
    // ★ 各表就地逐字展开冻结（与 ProductionProbeResult/ProductionReceipt 同形；不抽 helper）。
    if (assetRequired == null) {
      throw new IllegalArgumentException("ProductionRosterProbeResult.assetRequired 不得为 null");
    }
    Map<AssetKind, Long> assetCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : assetRequired.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeResult.assetRequired 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeResult.assetRequired 不得为负：" + entry.getKey());
      }
      assetCopy.put(entry.getKey(), entry.getValue());
    }
    assetRequired = Collections.unmodifiableMap(assetCopy); // ★ 冻在赋值处

    if (inputRequired == null) {
      throw new IllegalArgumentException("ProductionRosterProbeResult.inputRequired 不得为 null");
    }
    Map<CommodityId, Long> inputCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : inputRequired.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeResult.inputRequired 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeResult.inputRequired 不得为负：" + entry.getKey());
      }
      inputCopy.put(entry.getKey(), entry.getValue());
    }
    inputRequired = Collections.unmodifiableMap(inputCopy); // ★ 冻在赋值处

    if (expectedOutput == null) {
      throw new IllegalArgumentException("ProductionRosterProbeResult.expectedOutput 不得为 null");
    }
    Map<CommodityId, Long> outputCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : expectedOutput.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeResult.expectedOutput 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeResult.expectedOutput 不得为负：" + entry.getKey());
      }
      outputCopy.put(entry.getKey(), entry.getValue());
    }
    expectedOutput = Collections.unmodifiableMap(outputCopy); // ★ 冻在赋值处

    if (expectedMint == null) {
      throw new IllegalArgumentException("ProductionRosterProbeResult.expectedMint 不得为 null");
    }
    Map<CurrencyId, Long> mintCopy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : expectedMint.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeResult.expectedMint 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeResult.expectedMint 不得为负：" + entry.getKey());
      }
      mintCopy.put(entry.getKey(), entry.getValue());
    }
    expectedMint = Collections.unmodifiableMap(mintCopy); // ★ 冻在赋值处

    if (participantEfficiencyPerMille == null) {
      throw new IllegalArgumentException(
          "ProductionRosterProbeResult.participantEfficiencyPerMille 不得为 null（空用空表）");
    }
    Map<ProductionParticipantKey, Long> efficiencyCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionParticipantKey, Long> entry :
        participantEfficiencyPerMille.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeResult.participantEfficiencyPerMille 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionRosterProbeResult.participantEfficiencyPerMille 不得为负：" + entry.getKey());
      }
      efficiencyCopy.put(entry.getKey(), entry.getValue());
    }
    participantEfficiencyPerMille = Collections.unmodifiableMap(efficiencyCopy); // ★ 冻在赋值处
  }

  /** ★ 可产出的规模是否为正（"可行但一个规模单位都做不了"不算能排进分配）。 */
  public boolean producible() {
    return feasible && outputScale > 0L;
  }

  /** ★ 某 (家户×角色) 的效率倍率；缺键 ⇒ 0（不得用于替代"没有该参与项"的判断——那应由请求参与者集合判）。 */
  public long participantEfficiencyOf(HouseholdId household, ProductionRoleId role) {
    if (household == null || role == null) {
      return 0L;
    }
    return participantEfficiencyPerMille.getOrDefault(
        new ProductionParticipantKey(household, role), 0L);
  }

  /** ★ 具名拒绝结果（业务拒绝 = INFO，不抛）。 */
  public static ProductionRosterProbeResult rejected(IndustryId plan, String reason) {
    return new ProductionRosterProbeResult(
        plan, false, reason, 0L, 0L, 0L, Map.of(), Map.of(), Map.of(), Map.of(), 1_000L, Map.of());
  }
}

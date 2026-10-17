package io.mosire.simos.production.contract;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>② 执行：请求</b>（D-032 约束设计书 §4.2「执行(实例ID 或 新建请求, <b>最终投入</b>) → 回执」）。
 *
 * <p>★★ <b>它是"分配之后"的那一步</b>（§4.3 数据流第 5 段）：economy 已经按需求—利润排完序、统一分配完 劳动/土地/工具/原料，于是把<b>最终实际投入</b>交给
 * production；production 按最终投入算实际生产、进度与经验。 ⇒ 这里出现的每一个数字都是"已经决定好的"，不是"建议"。
 *
 * <p>★★ <b>统一预算的落点在这一层</b>（I8）：本请求里的劳动与原料必须是 economy 全局分配后<b>分给本实例</b>
 * 的份额；同一份资源不得出现在两个请求里。协议不做重复占用的检测（那是 economy 分配阶段的职责）， 但回执里的实际消耗逐笔可对账。
 *
 * <p>★ <b>时间单位 = 天</b>（与方案 {@code cycleDays} 同一单位，I6）：一次执行 = 推进该实例<b>一天</b>； 累计到 {@code cycleDays}
 * ⇒ 周期关账（回执 {@code cycleClosed=true}）。
 *
 * <p>★ <b>{@link #instance()} 与 {@link #creation()} 恰有其一</b>（构造期判）：两个都给会让"用哪个经营者/方案" 二义，两个都不给则无从执行。
 *
 * @param instance 既有实例 ID；新建时为 empty
 * @param creation 新建实例请求；既有实例时为 empty
 * @param grantedScale 本次实际授予的规模（economy 分配结果的一部分）；不得为负。★★ <b>A′ 批口径 （§13.8
 *     裁定一）：它是<b>基准规模（投入侧）</b>的硬上限</b>——投入消耗与占用不得超过它； <b>产出</b>规模（{@code
 *     finalScale}）再由效率倍率折算，<b>可以大于它</b>（同样的投入产出更多）。 ★ 它与 {@code
 *     ProductionProbeResult.scale}（产出规模）<b>不是</b>同一个口径：分配要多少资源请以 {@code laborRequiredMilli}/{@code
 *     assetRequired}/{@code inputRequired} 三件为准。 ★
 *     它<b>只</b>用于"本次允许投入多少"的预算与对账回显——周期关账时的实际规模仍由<b>实际累计投入</b> 决定（I6：不得用预测值顶替实际值）
 * @param laborContributionsMilli <b>实际出工家户与各自贡献</b>（千分劳动·日）；不得为 null、值必须为正 （0 贡献的家户不进表）；空表 =
 *     本次没有任何家户出工
 * @param assetOccupancy ★ <b>资产占用</b>（按种类的实际占用；§4.2 原文"最终投入（劳动分配、原料、<b>资产占用</b>）"）； 不得为
 *     null、值必须为正（0 不进表）。它是"这一格、这个经营者为此实例实占的生产资料"， 周期关账时的产能那一路规模由它算
 * @param finalInputsMilli 本次最终投入的原料（毫单位）；不得为 null、逐值必须为正（0 不进表）
 * @param attributes 外部属性事实（执行与试算共用同一份口径）；不得为 null。★ R3（§15.3）起 {@code execute} 用它判方案声明的 {@code
 *     ExternalAttribute} 可用性条件（缺事实 ⇒ fail-closed，具名 {@code condition-unsatisfied:...}）；正式日循环若仍传
 *     {@link ExternalAttributeFacts#EMPTY}，则声明外部属性条件的方案在 实际执行里一律具名拒——那是 §4.5 的 fail-closed 口径，不是缺陷
 */
public record ProductionExecutionRequest(
    Optional<ProductionUnitId> instance,
    Optional<ProductionCreationRequest> creation,
    long grantedScale,
    Map<HouseholdId, Long> laborContributionsMilli,
    Map<AssetKind, Long> assetOccupancy,
    Map<CommodityId, Long> finalInputsMilli,
    ExternalAttributeFacts attributes) {

  public ProductionExecutionRequest {
    if (instance == null) {
      throw new IllegalArgumentException(
          "ProductionExecutionRequest.instance 不得为 null（新建请给 Optional.empty()）");
    }
    if (creation == null) {
      throw new IllegalArgumentException(
          "ProductionExecutionRequest.creation 不得为 null（既有实例请给 Optional.empty()）");
    }
    if (instance.isPresent() == creation.isPresent()) {
      throw new IllegalArgumentException(
          "ProductionExecutionRequest 必须恰给一个：instance 或 creation（当前 instance="
              + instance.isPresent()
              + ", creation="
              + creation.isPresent()
              + "）");
    }
    if (grantedScale < 0L) {
      throw new IllegalArgumentException(
          "ProductionExecutionRequest.grantedScale 不得为负: " + grantedScale);
    }
    if (laborContributionsMilli == null) {
      throw new IllegalArgumentException(
          "ProductionExecutionRequest.laborContributionsMilli 不得为 null（无出工用空表）");
    }
    Map<HouseholdId, Long> laborCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Long> entry : laborContributionsMilli.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionExecutionRequest.laborContributionsMilli 的键与值都不得为 null");
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "ProductionExecutionRequest.laborContributionsMilli 的贡献必须为正（0 不进表）: "
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      laborCopy.put(entry.getKey(), entry.getValue());
    }
    laborContributionsMilli = Collections.unmodifiableMap(laborCopy); // ★ 冻在赋值处

    if (assetOccupancy == null) {
      throw new IllegalArgumentException(
          "ProductionExecutionRequest.assetOccupancy 不得为 null（无占用用空表）");
    }
    Map<AssetKind, Long> assetCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : assetOccupancy.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionExecutionRequest.assetOccupancy 的键与值都不得为 null");
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "ProductionExecutionRequest.assetOccupancy 的占用必须为正（0 不进表）: "
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      assetCopy.put(entry.getKey(), entry.getValue());
    }
    assetOccupancy = Collections.unmodifiableMap(assetCopy); // ★ 冻在赋值处

    if (finalInputsMilli == null) {
      throw new IllegalArgumentException(
          "ProductionExecutionRequest.finalInputsMilli 不得为 null（无投入用空表）");
    }
    Map<CommodityId, Long> inputCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : finalInputsMilli.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionExecutionRequest.finalInputsMilli 的键与值都不得为 null");
      }
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "ProductionExecutionRequest.finalInputsMilli 的投入必须为正（0 不进表）: "
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      inputCopy.put(entry.getKey(), entry.getValue());
    }
    finalInputsMilli = Collections.unmodifiableMap(inputCopy); // ★ 冻在赋值处

    Objects.requireNonNull(
        attributes,
        "ProductionExecutionRequest.attributes 不得为 null（无属性用 ExternalAttributeFacts.EMPTY）");
  }

  /** ★ 本次实际出工的家户数（协作生产只记一份总产出，但贡献明细必须留下——I9）。 */
  public int contributingHouseholds() {
    return laborContributionsMilli.size();
  }

  /** ★ 本次实际投入的劳动合计（千分劳动·日）。 */
  public long totalLaborMilli() {
    long total = 0L;
    for (long value : laborContributionsMilli.values()) {
      total = Math.addExact(total, value);
    }
    return total;
  }
}

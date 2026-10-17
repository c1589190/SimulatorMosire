package io.mosire.simos.production.contract;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 * @param laborContributionsMilli <b>逐参与户×角色的实际劳动贡献</b>（保序不可变；{@code (household,role)} 唯一； 逐项
 *     &gt;0，毫小时/日）；不得为 null；空表 = 本次没有任何家户出工
 * @param assetOccupancy ★ <b>资产占用</b>（按种类的实际占用；§4.2 原文"最终投入（劳动分配、原料、<b>资产占用</b>）"）； 不得为
 *     null、值必须为正（0 不进表）。它是"这一格、这个经营者为此实例实占的生产资料"， 周期关账时的产能那一路规模由它算
 * @param finalInputsMilli 本次最终投入的原料（毫单位）；不得为 null、逐值必须为正（0 不进表）
 * @param attributes 方案级/非家户主体的外部属性事实（probe 原样使用；execute 在主体是家户时优先用 {@link #participantAttributes()}
 *     中该户的事实，非家户主体才用它）；不得为 null
 * @param participantAttributes 逐参与户外部属性事实；不得为 null（无条件/无户用空表）。角色级资格条件缺事实 ⇒ fail-closed；计划级资格的主体 =
 *     organizer 家户，也读本表
 * @param householdLaborBudgetMilli 逐户当日可用劳动预算（毫小时/日）；不得为 null。归一化经验口径 （{@code
 *     NORMALIZED_PARTICIPATION}）缺该户或值 ≤0 ⇒ 业务拒绝，不按 0 静默
 * @param managementLaborMilli 逐户管理劳动（毫小时/日）；不得为 null。仅 {@code countsManagement=true} 的角色读取；
 *     该角色需要而该户缺项 ⇒ 业务拒绝
 */
public record ProductionExecutionRequest(
    Optional<ProductionUnitId> instance,
    Optional<ProductionCreationRequest> creation,
    long grantedScale,
    List<ParticipantContribution> laborContributionsMilli,
    Map<AssetKind, Long> assetOccupancy,
    Map<CommodityId, Long> finalInputsMilli,
    ExternalAttributeFacts attributes,
    Map<HouseholdId, ExternalAttributeFacts> participantAttributes,
    Map<HouseholdId, Long> householdLaborBudgetMilli,
    Map<HouseholdId, Long> managementLaborMilli) {

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
    List<ParticipantContribution> contributionCopy = new java.util.ArrayList<>();
    Set<ProductionParticipantKey> participantKeys = new LinkedHashSet<>();
    for (ParticipantContribution contribution : laborContributionsMilli) {
      if (contribution == null) {
        throw new IllegalArgumentException(
            "ProductionExecutionRequest.laborContributionsMilli 不得含 null");
      }
      if (!participantKeys.add(contribution.participantKey())) {
        throw new IllegalArgumentException(
            "ProductionExecutionRequest.laborContributionsMilli 的 (household,role) 不得重复: "
                + contribution.participantKey());
      }
      contributionCopy.add(contribution);
    }
    laborContributionsMilli = Collections.unmodifiableList(contributionCopy); // ★ 冻在赋值处

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

    if (participantAttributes == null) {
      throw new IllegalArgumentException(
          "ProductionExecutionRequest.participantAttributes 不得为 null（无属性用空表）");
    }
    Map<HouseholdId, ExternalAttributeFacts> participantAttributesCopy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ExternalAttributeFacts> entry : participantAttributes.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionExecutionRequest.participantAttributes 的键与值都不得为 null");
      }
      participantAttributesCopy.put(entry.getKey(), entry.getValue());
    }
    participantAttributes = Collections.unmodifiableMap(participantAttributesCopy); // ★ 冻在赋值处

    householdLaborBudgetMilli =
        freezeNonNegativeByHousehold(householdLaborBudgetMilli, "householdLaborBudgetMilli");
    managementLaborMilli =
        freezeNonNegativeByHousehold(managementLaborMilli, "managementLaborMilli");
  }

  private static Map<HouseholdId, Long> freezeNonNegativeByHousehold(
      Map<HouseholdId, Long> source, String field) {
    if (source == null) {
      throw new IllegalArgumentException(
          "ProductionExecutionRequest." + field + " 不得为 null（无值用空表）");
    }
    Map<HouseholdId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Long> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionExecutionRequest." + field + " 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionExecutionRequest." + field + " 不得为负: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  /** ★ 本次实际出工的家户数（同户多角色只算一户；协作生产只记一份总产出，但贡献明细必须留下——I9）。 */
  public int contributingHouseholds() {
    Set<HouseholdId> households = new LinkedHashSet<>();
    for (ParticipantContribution contribution : laborContributionsMilli) {
      households.add(contribution.household());
    }
    return households.size();
  }

  /** ★ 本次实际投入的劳动合计（毫小时/日；由参与项派生，不另存）。 */
  public long totalLaborMilli() {
    long total = 0L;
    for (ParticipantContribution contribution : laborContributionsMilli) {
      total = Math.addExact(total, contribution.laborMilli());
    }
    return total;
  }
}

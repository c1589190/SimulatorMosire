package io.mosire.simos.production.contract;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.inventory.InventoryLotId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * ★★ <b>D-032 L1b：本 tick 从 LAND lot 派生的一个生产机会</b>（架构 v1 §1.2 / §2.3；plan §3）。
 *
 * <p>★★ <b>只活本 tick、不落盘</b>：它是 {@link ProductionOpportunityBook} 的纯派生结果，<b>不是</b>第二份 lot 状态，不落
 * ChangeSet / Codec，不读时钟、不掷随机数、不写任何状态；下一 tick 从 lot state 重新派生。
 *
 * <p>★ <b>IDLE lot</b> 生成“从第一段新开”的机会；<b>InProduction lot</b> 生成“继续当前阶段”的机会。一个 lot 在一个 tick
 * 只有一个生产状态，故每个 lot 最多一个“继续”机会；IDLE lot 若命中多个适用方案则可以各有一个“新开”机会。
 *
 * <p>★ <b>量纲</b>：{@code quantityMilli} 是 lot 的毫单位数量；{@code productionScale = quantityMilli / LAND
 * capacityPerUnit}（整数向下取整，{@code <= 0} 的机会不生成）；{@link #expectedInputs()} 是毫单位； {@link
 * #expectedOutputs()} 是毫商品（{@code outputPerUnit × scale × 1000}）。
 *
 * <p>★ <b>价格估值</b>：{@link #marketValue()} / {@link #expectedNet()} / {@link #score()} 由注入价格纯派生。
 * 任一正投入/正产出缺价 ⇒ 三者按空处理，<b>不伪造</b>；估值一律走 BigInteger 精确乘法后再除以 1000，溢出 fail-closed。
 *
 * <p>★ <b>劳动</b>：L1 只有行业级 {@code plan.laborPerUnit() × scale}（v1 不拆阶段劳动）。
 *
 * <p>★ <b>冻结</b>：{@code carrierLotIds} 与两张商品表拷贝后保序不可变；绝不用 {@code Map.copyOf} / {@code List.copyOf}。
 *
 * @param opportunityId 稳定机会 id：{@code lotId|planId}
 * @param planId 生产方案（产业定义）稳定身份
 * @param location lot 的所在地
 * @param ownerHousehold lot 的 owner 家户
 * @param carrierLotIds 承载本机会的 lot；L1 恒为单元素
 * @param quantityMilli lot 的毫单位数量
 * @param productionScale 由 LAND 容量换算出的整数规模；{@code > 0}
 * @param currentStageId 当前阶段 id（IDLE 为第一段）
 * @param stageProgressDays 当前阶段已推进天数
 * @param cycleProgressDays 当前周期已推进天数（IDLE 为 0）
 * @param expectedInputs 剩余周期的预计投入（毫单位，按商品，保序不可变）
 * @param expectedOutputs 预计最终产出（毫商品，按商品，保序不可变；只取最后一个 HARVEST 阶段）
 * @param marketValue 预计产出市值；缺价时为空
 * @param expectedNet 预计净收益；缺价时为空
 * @param score {@code expectedNet / max(1, inputCost)}；缺价时为空
 * @param laborRequirementMilli 行业级劳动需求（{@code laborPerUnit × scale}）
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "列表与两张商品表在 compact 构造器中拷贝后冻结为 unmodifiable；项目同类 record 既有豁免口径")
public record ProductionOpportunity(
    String opportunityId,
    IndustryId planId,
    HexCoord location,
    HouseholdId ownerHousehold,
    List<InventoryLotId> carrierLotIds,
    long quantityMilli,
    long productionScale,
    String currentStageId,
    long stageProgressDays,
    long cycleProgressDays,
    Map<CommodityId, Long> expectedInputs,
    Map<CommodityId, Long> expectedOutputs,
    OptionalLong marketValue,
    OptionalLong expectedNet,
    OptionalLong score,
    long laborRequirementMilli) {

  public ProductionOpportunity {
    if (opportunityId == null || opportunityId.isBlank()) {
      throw new IllegalArgumentException("ProductionOpportunity.opportunityId 不得为空白");
    }
    Objects.requireNonNull(planId, "ProductionOpportunity.planId 不得为 null");
    Objects.requireNonNull(location, "ProductionOpportunity.location 不得为 null");
    Objects.requireNonNull(ownerHousehold, "ProductionOpportunity.ownerHousehold 不得为 null");
    if (carrierLotIds == null) {
      throw new IllegalArgumentException(
          "ProductionOpportunity.carrierLotIds 不得为 null（L1 至少一个 lot）");
    }
    List<InventoryLotId> carriersCopy = new ArrayList<>();
    for (InventoryLotId carrierLotId : carrierLotIds) {
      if (carrierLotId == null) {
        throw new IllegalArgumentException("ProductionOpportunity.carrierLotIds 不得含 null");
      }
      carriersCopy.add(carrierLotId);
    }
    if (carriersCopy.isEmpty()) {
      throw new IllegalArgumentException("ProductionOpportunity.carrierLotIds 不得为空");
    }
    carrierLotIds = Collections.unmodifiableList(carriersCopy); // ★ 保序不可变
    if (quantityMilli <= 0L) {
      throw new IllegalArgumentException(
          "ProductionOpportunity.quantityMilli 必须大于 0: " + quantityMilli);
    }
    if (productionScale <= 0L) {
      throw new IllegalArgumentException(
          "ProductionOpportunity.productionScale 必须大于 0: " + productionScale);
    }
    if (currentStageId == null || currentStageId.isBlank()) {
      throw new IllegalArgumentException("ProductionOpportunity.currentStageId 不得为空白");
    }
    if (stageProgressDays < 0L) {
      throw new IllegalArgumentException(
          "ProductionOpportunity.stageProgressDays 不得为负: " + stageProgressDays);
    }
    if (cycleProgressDays < 0L) {
      throw new IllegalArgumentException(
          "ProductionOpportunity.cycleProgressDays 不得为负: " + cycleProgressDays);
    }
    expectedInputs = freeze(expectedInputs, "expectedInputs");
    expectedOutputs = freeze(expectedOutputs, "expectedOutputs");
    if (marketValue == null) {
      throw new IllegalArgumentException(
          "ProductionOpportunity.marketValue 不得为 null（缺价用 OptionalLong.empty()）");
    }
    if (expectedNet == null) {
      throw new IllegalArgumentException(
          "ProductionOpportunity.expectedNet 不得为 null（缺价用 OptionalLong.empty()）");
    }
    if (score == null) {
      throw new IllegalArgumentException(
          "ProductionOpportunity.score 不得为 null（缺价用 OptionalLong.empty()）");
    }
    if (laborRequirementMilli < 0L) {
      throw new IllegalArgumentException(
          "ProductionOpportunity.laborRequirementMilli 不得为负: " + laborRequirementMilli);
    }
  }

  /** 一张商品表的冻结：键值非 null、逐值 ≥ 0；保序不可变（{@link LinkedHashMap} + unmodifiable）。 */
  private static Map<CommodityId, Long> freeze(Map<CommodityId, Long> source, String field) {
    if (source == null) {
      throw new IllegalArgumentException("ProductionOpportunity." + field + " 不得为 null（无该项用空 map）");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionOpportunity." + field + " 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionOpportunity."
                + field
                + " 的数量不得为负："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy); // ★ 保序不可变；不用 Map.copyOf
  }
}

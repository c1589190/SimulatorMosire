package io.mosire.simos.production.contract;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>D-032 U4a：一个生产阶段的定义（多阶段生产 schema）</b>。
 *
 * <p>它是 {@link IndustryDefinitionView#stages()} 的元素；<b>只描述“世界必须长成什么样”</b>，不含任何运行实例字段
 * （进度、已投入量、批次状态都归 U4b 的 batch/库存 lot）。
 *
 * <p>★ <b>形状与不变量</b>（spec §5.3 / plan §7.1）：
 *
 * <ul>
 *   <li>{@code stageId} 非空白；同一产业内不重复（跨元素唯一性由 {@link IndustryDefinitionView} 守）。
 *   <li>{@code durationDays >= 1}。
 *   <li>{@code startInputPerUnit} / {@code tickInputPerUnit} / {@code outputPerUnit} 三张表非 null、 键非
 *       null、逐值 {@code >= 0}；<b>保序不可变</b>。
 *   <li>{@code endAction} 非 null；{@code NEXT_STAGE} = 进入列表下一项，{@code HARVEST} = 收获（只允许最后一项， 由
 *       {@link IndustryDefinitionView} 守）。
 * </ul>
 *
 * <p>★★ <b>保序冻结纪律</b>：三张表一律「拷贝进 {@link LinkedHashMap} 再 {@link Collections#unmodifiableMap}」，
 * <b>绝不使用 {@code Map.copyOf}</b>——迭代序不是内容的纯函数。阶段级<b>没有</b> capacity/labor 字段：v1 沿用产业级 {@code
 * capacityPerUnit}/{@code laborPerUnit}（plan §7.1）。
 *
 * <p>★ U4a 只定义 schema，不解析/运行阶段、不新增 {@code ProductionProcess} 字段。
 *
 * @param stageId 阶段稳定 id；非空白，产业内唯一
 * @param durationDays 阶段时长（天）；≥ 1
 * @param startInputPerUnit 每 1 单位规模在阶段开始的一次性投入（商品 → 数量）；非 null，逐值 ≥ 0
 * @param tickInputPerUnit 每 1 单位规模每 tick 的持续投入（商品 → 数量）；非 null，逐值 ≥ 0
 * @param endAction 阶段结束动作；非 null
 * @param outputPerUnit 每 1 单位规模的阶段产出（商品 → 数量）；非 null，逐值 ≥ 0；{@code NEXT_STAGE} 也允许中间产出
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "三张表在 compact 构造器 freeze() 中逐项复制并冻结为 unmodifiable；项目同类 record 既有豁免口径")
public record ProductionStageDefinition(
    String stageId,
    long durationDays,
    Map<CommodityId, Long> startInputPerUnit,
    Map<CommodityId, Long> tickInputPerUnit,
    ProductionStageEndAction endAction,
    Map<CommodityId, Long> outputPerUnit) {

  public ProductionStageDefinition {
    if (stageId == null || stageId.isBlank()) {
      throw new IllegalArgumentException("ProductionStageDefinition.stageId 不得为空白");
    }
    if (durationDays < 1L) {
      throw new IllegalArgumentException(
          "ProductionStageDefinition.durationDays 必须 ≥ 1: " + durationDays);
    }
    if (endAction == null) {
      throw new IllegalArgumentException("ProductionStageDefinition.endAction 不得为 null");
    }
    startInputPerUnit = freeze(startInputPerUnit, "startInputPerUnit");
    tickInputPerUnit = freeze(tickInputPerUnit, "tickInputPerUnit");
    outputPerUnit = freeze(outputPerUnit, "outputPerUnit");
  }

  /**
   * 一张商品表的冻结：非 null、键值非 null、逐值 ≥ 0；保序不可变（{@link LinkedHashMap} + {@link
   * Collections#unmodifiableMap}，<b>不用</b> {@code Map.copyOf}）。
   */
  private static Map<CommodityId, Long> freeze(Map<CommodityId, Long> source, String field) {
    if (source == null) {
      throw new IllegalArgumentException(
          "ProductionStageDefinition." + field + " 不得为 null（无该项用空 map）");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionStageDefinition." + field + " 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionStageDefinition."
                + field
                + " 的数量不得为负："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }
}

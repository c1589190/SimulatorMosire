package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>阶层结构</b>（理想架构 §2.2）：某个生产方式下<b>允许</b>的阶层位置集合，以及各位置的默认人口份额。
 *
 * <p>★★ <b>它是"mode 下的位置清单"的权威</b>：{@link #positions} 是 id → 位置本体；{@link #defaultSharesPerMille} 是 该
 * mode 的初始/默认人口份额（千分比）。位置身份可由多个 mode 复用，但每个 {@link ClassPosition} 的 {@code modeId} 必须与本结构的 {@code
 * modeId} 一致 —— 否则同一个 id 会在两处说不清自己属于哪个 mode。
 *
 * <p>★★ <b>三条构造期判据</b>：
 *
 * <ol>
 *   <li>{@code positions} 键 == 值内 {@link ClassPosition#id()}（同一身份不许两处拼写）；
 *   <li>{@code modeId} 非 null（{@link ProductionModeId} 自身已判空白）；
 *   <li>{@code defaultSharesPerMille} 逐值非负，且键必须是 {@code positions} 里已有的位置（默认份额不许指向清单外的位置）。
 * </ol>
 *
 * <p>★ 份额单位是千分比；<b>不要求合计为 1000</b>：结构可以先只声明一个位置的默认份额，剩下的由调用方/迁移器显式补齐 —— E1 不替规则做决定。
 *
 * @param id 阶层结构稳定身份；不得为 null
 * @param modeId 本结构所属的生产方式身份；不得为 null
 * @param positions 本 mode 允许的阶层位置（键 = 位置身份）；不得为 null、键值不得为 null、键与值内 id 必须一致，值内 modeId 必须等于本结构
 *     modeId
 * @param defaultSharesPerMille 默认人口份额（千分比）；不得为 null、键必须是 positions 里的位置、逐值 ≥ 0
 */
public record ClassStructure(
    ClassStructureId id,
    ProductionModeId modeId,
    Map<ClassPositionId, ClassPosition> positions,
    Map<ClassPositionId, Long> defaultSharesPerMille) {

  public ClassStructure {
    if (id == null) {
      throw new IllegalArgumentException("ClassStructure.id 不得为 null");
    }
    if (modeId == null) {
      throw new IllegalArgumentException("ClassStructure.modeId 不得为 null");
    }
    if (positions == null) {
      throw new IllegalArgumentException("ClassStructure.positions 不得为 null（没有位置给空表）");
    }
    if (defaultSharesPerMille == null) {
      throw new IllegalArgumentException(
          "ClassStructure.defaultSharesPerMille 不得为 null（没有默认份额给空表）");
    }
    Map<ClassPositionId, ClassPosition> positionsCopy = new LinkedHashMap<>();
    for (Map.Entry<ClassPositionId, ClassPosition> entry : positions.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ClassStructure.positions 的键与值都不得为 null");
      }
      ClassPosition position = entry.getValue();
      if (!entry.getKey().equals(position.id())) {
        throw new IllegalArgumentException(
            "ClassStructure.positions 的键必须与 ClassPosition.id 一致：键="
                + entry.getKey()
                + "，行内 id="
                + position.id());
      }
      if (!modeId.equals(position.modeId())) {
        throw new IllegalArgumentException(
            "ClassStructure.positions 的值内 modeId 必须与 ClassStructure.modeId 一致：位置="
                + position.id()
                + "，位置 modeId="
                + position.modeId()
                + "，结构 modeId="
                + modeId);
      }
      positionsCopy.put(entry.getKey(), position);
    }
    positions = Collections.unmodifiableMap(positionsCopy); // ★ 冻在赋值处
    Map<ClassPositionId, Long> sharesCopy = new LinkedHashMap<>();
    for (Map.Entry<ClassPositionId, Long> entry : defaultSharesPerMille.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("ClassStructure.defaultSharesPerMille 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ClassStructure.defaultSharesPerMille 不得为负："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      if (!positionsCopy.containsKey(entry.getKey())) {
        throw new IllegalArgumentException(
            "ClassStructure.defaultSharesPerMille 的键必须是 positions 里已有的位置：" + entry.getKey());
      }
      sharesCopy.put(entry.getKey(), entry.getValue());
    }
    defaultSharesPerMille = Collections.unmodifiableMap(sharesCopy); // ★ 冻在赋值处
  }
}

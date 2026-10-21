package io.mosire.simos.economy.model;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 每个 OWNER 家户维护的一张向下流动表（D-032 C4 / S7-C）。
 *
 * <p>★ 本表只保存“上一市场轮看到的债务本金”和“该轮选中的同 hex PROLETARIAN 目标”。它不保存 LAND、不保存人口权威； 实际迁移只由 population-only
 * 原语完成：只迁人口、只缩 source PRODUCTION 劳动承诺，绝不搬钱/货/债/经验。
 *
 * @param sourceHousehold 源家户稳定身份；不得为 null，且必须等于外层 Map 键
 * @param sourceHex 源家户当前 hex；不得为 null
 * @param targets 目标清单；不得为 null，允许空表（尚未找到正人口目标）
 * @param lastObservedDebtPrincipalMilli 上一市场轮观察到的未偿本金合计；不得为负
 * @param lastFlowDay 最近一次实际 flow 的世界日；-1 = 从未流动
 */
public record HouseholdFlowTable(
    HouseholdId sourceHousehold,
    HexCoord sourceHex,
    List<HouseholdFlowTarget> targets,
    long lastObservedDebtPrincipalMilli,
    long lastFlowDay) {

  public HouseholdFlowTable {
    Objects.requireNonNull(sourceHousehold, "HouseholdFlowTable.sourceHousehold 不得为 null");
    Objects.requireNonNull(sourceHex, "HouseholdFlowTable.sourceHex 不得为 null");
    Objects.requireNonNull(targets, "HouseholdFlowTable.targets 不得为 null（没有目标用空表）");
    if (lastObservedDebtPrincipalMilli < 0L) {
      throw new IllegalArgumentException(
          "HouseholdFlowTable.lastObservedDebtPrincipalMilli 不得为负: "
              + lastObservedDebtPrincipalMilli);
    }
    if (lastFlowDay < -1L) {
      throw new IllegalArgumentException("HouseholdFlowTable.lastFlowDay 不得小于 -1: " + lastFlowDay);
    }
    List<HouseholdFlowTarget> copy = new ArrayList<>(targets.size());
    for (HouseholdFlowTarget target : targets) {
      if (target == null) {
        throw new IllegalArgumentException("HouseholdFlowTable.targets 不得含 null");
      }
      copy.add(target);
    }
    targets = Collections.unmodifiableList(copy);
  }

  /** 只换目标清单的副本（其余字段原样带过）。 */
  public HouseholdFlowTable withTargets(List<HouseholdFlowTarget> newTargets) {
    return new HouseholdFlowTable(
        sourceHousehold, sourceHex, newTargets, lastObservedDebtPrincipalMilli, lastFlowDay);
  }

  /** 只换观察值/流动日的副本（其余字段原样带过）。 */
  public HouseholdFlowTable withObservation(long observedDebtPrincipalMilli, long flowDay) {
    return new HouseholdFlowTable(
        sourceHousehold, sourceHex, targets, observedDebtPrincipalMilli, flowDay);
  }
}

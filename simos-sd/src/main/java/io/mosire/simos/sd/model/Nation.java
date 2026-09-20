package io.mosire.simos.sd.model;

import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.NationId;

/**
 * 国家（spec §三.2）：身份 + 名称 + **国家区域引用** + 行政余额。
 *
 * <p>★ {@code homeRegion} 指向的 {@code Region} 必须存在且有**国家 tag**（R13）——这是 §九 跨模块守卫（不可删区域）的判据来源。 存在性与
 * tag 的校验在**命令期**（{@code sd.CreateNation}），本 record 只守形状与字段不变量。
 *
 * <p>★ {@code adminBudgetPerTick} 承载"行政能力 ⇒ 用行政余额限制"（brainstorm §4）；{@code 0} = 本 tick 无行政能力，合法。
 */
public record Nation(NationId id, String name, RegionId homeRegion, int adminBudgetPerTick) {

  public Nation {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    if (homeRegion == null) {
      throw new IllegalArgumentException("homeRegion 不得为 null");
    }
    if (adminBudgetPerTick < 0) {
      throw new IllegalArgumentException("adminBudgetPerTick 必须 ≥ 0: " + adminBudgetPerTick);
    }
  }
}

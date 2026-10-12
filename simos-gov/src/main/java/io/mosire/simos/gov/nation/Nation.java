package io.mosire.simos.gov.nation;

import io.mosire.simos.map.region.RegionId;

/**
 * 国家（spec §三.2）：身份 + 名称 + **国家区域引用** + 行政余额。
 *
 * <p>★ {@code homeRegion} 指向的 {@code Region} 必须存在且有**国家 tag**（R13）——这是 §九 跨模块守卫（不可删区域）的判据来源。 存在性与
 * tag 的校验在**命令期**（{@code gov.CreateNation}），本 record 只守形状与字段不变量。
 *
 * <p>★ {@code adminBudgetPerTick} 承载"行政能力 ⇒ 用行政余额限制"（brainstorm §4）：它是**每日**行政动作预算（单位：日，2026-09-24
 * 日制裁定）—— **不是国库余额**，且**当前没有任何消费方**（财政余额将归 POLITICAL_ECONOMY_DESIGN.md §7 的 ledger 切片）；{@code 0} =
 * 本日无行政能力，合法。
 *
 * <p>★★ <b>2026-10-12 批次 3c：本类型自 {@code io.mosire.simos.sd.model} 迁入本包</b>（外交是 gov 的事），随 {@code
 * nations} 表一起成为 {@link io.mosire.simos.gov.GovState} 的第 3 个组件；字段与不变量一字未改。
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

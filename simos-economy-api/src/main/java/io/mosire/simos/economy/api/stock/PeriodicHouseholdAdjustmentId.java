package io.mosire.simos.economy.api.stock;

/**
 * ★★ <b>周期家户库存扣增规则的稳定身份</b>（P4a；2026-10-14 用户确认路线 C+PARTIAL）。
 *
 * <p>★ 它只校验非空白，<b>不校验格式</b>—— 规则名的拼法由命令面唯一决定（{@code gm:<id>} / {@code army:<unitId>} 等）。 裸值 {@code
 * toString()} + {@code static parse} 三件套（铁律 1）：它既进 {@code EconomyData.periodicAdjustments} 的
 * 键，也进日志与审计串，必须跨 revision 稳定。
 */
public record PeriodicHouseholdAdjustmentId(String value) {

  public PeriodicHouseholdAdjustmentId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("PeriodicHouseholdAdjustmentId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（命名属命令层）。 */
  public static PeriodicHouseholdAdjustmentId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("PeriodicHouseholdAdjustmentId 不得为空白: " + text);
    }
    return new PeriodicHouseholdAdjustmentId(text);
  }
}

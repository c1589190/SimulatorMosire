package io.mosire.simos.army;

/**
 * 交战**结局** id（阶段 D4 / 用户设计 D-009 补裁 + D-010，2026-10-02）：同一阶段的结局概率表内唯一的短名。
 *
 * <p>★ 作用域是**本阶段**；跨阶段可以重名（由 {@link CombatStage} 构造期判表内唯一）。形态与 {@link CombatStageId} 同制。
 */
public record CombatOutcomeId(String value) {

  public CombatOutcomeId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CombatOutcomeId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static CombatOutcomeId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("CombatOutcomeId 不得为空白: " + text);
    }
    return new CombatOutcomeId(text);
  }
}

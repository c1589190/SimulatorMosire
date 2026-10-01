package io.mosire.simos.army;

/**
 * 交战**阶段** id（阶段 D4 / 用户设计 D-009 + D-010，2026-10-02）：同一场交战记录内唯一的短名。
 *
 * <p>★ 与 {@link CombatRecordId} 同制：裸值 {@code toString()} + {@code static parse} 三件套（铁律 1：id
 * 是身份）；只校验非空白， 词表/前缀 不是本类型的职责。阶段 id 的作用域是**本记录**（不同交战可以有同名阶段），由 {@link CombatRecord} 构造期判重。
 */
public record CombatStageId(String value) {

  public CombatStageId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CombatStageId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static CombatStageId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("CombatStageId 不得为空白: " + text);
    }
    return new CombatStageId(text);
  }
}

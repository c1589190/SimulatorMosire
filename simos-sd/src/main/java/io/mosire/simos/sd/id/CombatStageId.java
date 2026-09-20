package io.mosire.simos.sd.id;

/**
 * 交战阶段 ID（调用方给的短名，spec §二.1）。阶段是**数据对象**（N1：带进入/退出条件，条件驱动而非时间驱动）。 裸值 {@code toString()} + {@code
 * static parse} 三件套（铁律 1）；不自增、不用随机 UUID。
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

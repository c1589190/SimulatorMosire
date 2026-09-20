package io.mosire.simos.sd.id;

/**
 * 结局 ID（调用方给的短名，spec §二.1）。结局表（{@code OutcomeTable}）的条目 = categorical distribution（N2）。 裸值 {@code
 * toString()} + {@code static parse} 三件套（铁律 1）；不自增、不用随机 UUID。
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

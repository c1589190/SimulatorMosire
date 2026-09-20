package io.mosire.simos.sd.id;

/**
 * 决策人 ID（调用方给的短名，spec §二.1）。★ **不可用 {@code agent:}**（N15：实测该命名空间无 resolver）——决策人走 {@code
 * sd:decision-maker.<id>}。<b>归属是默认权限边界，不是硬约束</b>（spec §二.3）。 裸值 {@code toString()} + {@code static
 * parse} 三件套（铁律 1）；不自增、不用随机 UUID。
 */
public record DecisionMakerId(String value) {

  public DecisionMakerId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("DecisionMakerId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static DecisionMakerId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("DecisionMakerId 不得为空白: " + text);
    }
    return new DecisionMakerId(text);
  }
}

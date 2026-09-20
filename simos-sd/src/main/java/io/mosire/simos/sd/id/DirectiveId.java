package io.mosire.simos.sd.id;

/**
 * 决策 ID（调用方给的短名，spec §二.1）。★ 唯一性键 = ({@code DecisionMakerId}, tick)（R4 硬不变量）。 裸值 {@code toString()}
 * + {@code static parse} 三件套（铁律 1）；不自增、不用随机 UUID。
 */
public record DirectiveId(String value) {

  public DirectiveId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("DirectiveId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static DirectiveId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("DirectiveId 不得为空白: " + text);
    }
    return new DirectiveId(text);
  }
}

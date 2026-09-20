package io.mosire.simos.sd.id;

/**
 * 交战（场）ID（调用方给的短名，spec §二.1）。{@code Combat} 是跨时间的稳定身份（"其实只是一个 tag 作用，防止重名"）。 裸值 {@code toString()}
 * + {@code static parse} 三件套（铁律 1）；不自增、不用随机 UUID。
 */
public record CombatId(String value) {

  public CombatId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CombatId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static CombatId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("CombatId 不得为空白: " + text);
    }
    return new CombatId(text);
  }
}

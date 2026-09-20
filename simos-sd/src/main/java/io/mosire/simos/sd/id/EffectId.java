package io.mosire.simos.sd.id;

/**
 * 效果 ID（调用方给的短名，spec §二.1）。{@code Effect} 是 ECA 规则——**此时决定、未来发生**（R6）。 裸值 {@code toString()} +
 * {@code static parse} 三件套（铁律 1）；不自增、不用随机 UUID。
 */
public record EffectId(String value) {

  public EffectId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("EffectId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static EffectId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("EffectId 不得为空白: " + text);
    }
    return new EffectId(text);
  }
}

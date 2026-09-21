package io.mosire.simos.sd.channel;

/**
 * 决策提交者身份（spec §十三.1，N16）：**裸值 {@code toString()} + {@code static parse} 三件套**（铁律 1 同族）。
 *
 * <p>★ 决策人地址**不可用 {@code agent:}**（N15）——actor 的取值就是 {@code DecisionMakerId} 的裸值。
 */
public record ActorId(String value) {

  public ActorId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ActorId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static ActorId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ActorId 不得为空白: " + text);
    }
    return new ActorId(text);
  }
}

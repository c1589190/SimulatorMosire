package io.mosire.simos.sd.id;

/**
 * 决策包 ID（D2 决策包计划）：一次（决策人 × tick）的提议容器身份。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；id 由工具按 {@code pkt-<dmId>-<tick>}
 * 确定性派生（见 {@code ProposeCallTool}），不自增、不用随机 UUID——同一决策人同一 tick 只有一个包。
 */
public record DecisionPacketId(String value) {

  public DecisionPacketId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("DecisionPacketId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static DecisionPacketId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("DecisionPacketId 不得为空白: " + text);
    }
    return new DecisionPacketId(text);
  }
}

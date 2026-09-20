package io.mosire.simos.sd.id;

/**
 * 判决 ID（spec §二.1）。★ **由代码确定性生成**（不自增、不用随机 UUID）——判决是**数据**、落进 revision、回放/分岔不重跑
 * LLM（N7）；"同一操作两次不同"会破坏可复现与可断言。裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）。
 */
public record VerdictId(String value) {

  public VerdictId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("VerdictId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  public static VerdictId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("VerdictId 不得为空白: " + text);
    }
    return new VerdictId(text);
  }
}

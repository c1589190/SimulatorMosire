package io.mosire.simos.sd.id;

/**
 * 国家 ID（调用方给的短名，spec §二.1）。**裸值 {@code toString()} + {@code static parse} 三件套**（铁律 1）：它是 id 的
 * **地址形式**，供变更集的 String key 与 sd 地址使用，**不是**调试输出。★ 与 {@code RegionId} 分离——国家区域用 tag 关联（R13）。
 * 不自增、不用随机 UUID。
 */
public record NationId(String value) {

  public NationId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("NationId 不得为空白");
    }
  }

  /** 裸值。见类注释：它是**地址形式**，不是给人看的调试输出。 */
  @Override
  public String toString() {
    return value;
  }

  /** 解析 {@link #toString()} 的产物；空白与 {@code null} 抛（与构造器同口径：宁抛不静默）。 */
  public static NationId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("NationId 不得为空白: " + text);
    }
    return new NationId(text);
  }
}

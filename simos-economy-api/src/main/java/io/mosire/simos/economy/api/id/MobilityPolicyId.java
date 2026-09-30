package io.mosire.simos.economy.api.id;

/**
 * ★ <b>人口流动政策（{@code MobilityPolicy}）的稳定身份</b>：一个生产方式当前生效的一套 GM 可调政策。身份 = mode 的纯函数 —— 同一 mode
 * 至多一套生效政策（改参数 = 覆盖同键）。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套；规范串不含 {@code "."}。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record MobilityPolicyId(String value) {

  /** {@link #of(String)} 的固定前缀（唯一拼写点）。 */
  public static final String PREFIX = "mobility-";

  public MobilityPolicyId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MobilityPolicyId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("MobilityPolicyId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束。 */
  public static MobilityPolicyId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("MobilityPolicyId 不得为空白: " + text);
    }
    return new MobilityPolicyId(text);
  }

  /** 新 id 的唯一拼写点：{@code mobility-<modeId>}（不含 {@code "."}）。 */
  public static MobilityPolicyId of(String modeId) {
    if (modeId == null || modeId.isBlank()) {
      throw new IllegalArgumentException("MobilityPolicyId.of 的 modeId 不得为空白");
    }
    String value = PREFIX + modeId;
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("MobilityPolicyId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
    return new MobilityPolicyId(value);
  }
}

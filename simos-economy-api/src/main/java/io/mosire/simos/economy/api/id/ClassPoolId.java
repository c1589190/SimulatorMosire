package io.mosire.simos.economy.api.id;

/**
 * ★ <b>阶层池（{@code ClassPool}）的稳定身份</b>：一个生产方式下一个阶层位置的池。身份 = {@code (modeId, classPositionId)} 的纯函数
 * —— 同一 mode 同一位置恒得同一个 id，重放/重跑不会新开池。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）；{@code parse} 是 opaque 的，不做格式再解释。 ★
 * 规范串不含 {@code "."}：地址会被第一个点截断（同 {@link ProductionModeId} 的理由）。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record ClassPoolId(String value) {

  /** {@link #idOf(String, String)} 的固定前缀（唯一拼写点）。 */
  public static final String PREFIX = "pool-";

  public ClassPoolId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ClassPoolId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ClassPoolId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束。 */
  public static ClassPoolId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ClassPoolId 不得为空白: " + text);
    }
    return new ClassPoolId(text);
  }

  /** 新 id 的唯一拼写点：{@code pool-<modeId>:<classPositionId>}（不含 {@code "."}）。 */
  public static ClassPoolId idOf(String modeId, String classPositionId) {
    if (modeId == null || modeId.isBlank()) {
      throw new IllegalArgumentException("ClassPoolId.idOf 的 modeId 不得为空白");
    }
    if (classPositionId == null || classPositionId.isBlank()) {
      throw new IllegalArgumentException("ClassPoolId.idOf 的 classPositionId 不得为空白");
    }
    String value = PREFIX + modeId + ":" + classPositionId;
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ClassPoolId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
    return new ClassPoolId(value);
  }
}

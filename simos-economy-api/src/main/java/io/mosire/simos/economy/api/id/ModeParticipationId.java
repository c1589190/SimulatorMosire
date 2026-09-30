package io.mosire.simos.economy.api.id;

/**
 * ★ <b>模式参与（{@code ModeParticipation}）的稳定身份</b>：一个生产方式下某阶层位置参与该模式的记录身份。身份 = {@code (modeId,
 * classPositionId)} 的纯函数。
 *
 * <p>★ 裸值 {@code toString()} + {@code static parse} 三件套；规范串不含 {@code "."}。
 *
 * @param value 非空白且不含 {@code "."} 的稳定规范串
 */
public record ModeParticipationId(String value) {

  /** {@link #idOf(String, String)} 的固定前缀（唯一拼写点）。 */
  public static final String PREFIX = "part-";

  public ModeParticipationId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ModeParticipationId 不得为空白");
    }
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ModeParticipationId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白与不含 {@code "."}；不做格式约束。 */
  public static ModeParticipationId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ModeParticipationId 不得为空白: " + text);
    }
    return new ModeParticipationId(text);
  }

  /** 新 id 的唯一拼写点：{@code part-<modeId>:<classPositionId>}（不含 {@code "."}）。 */
  public static ModeParticipationId idOf(String modeId, String classPositionId) {
    if (modeId == null || modeId.isBlank()) {
      throw new IllegalArgumentException("ModeParticipationId.idOf 的 modeId 不得为空白");
    }
    if (classPositionId == null || classPositionId.isBlank()) {
      throw new IllegalArgumentException("ModeParticipationId.idOf 的 classPositionId 不得为空白");
    }
    String value = PREFIX + modeId + ":" + classPositionId;
    if (value.indexOf('.') >= 0) {
      throw new IllegalArgumentException("ModeParticipationId 不得含 '.'（地址会被第一个点截断）: " + value);
    }
    return new ModeParticipationId(value);
  }
}

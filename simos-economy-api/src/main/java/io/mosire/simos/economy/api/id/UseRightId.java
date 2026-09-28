package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>使用权的稳定身份</b>（S1）：一条 {@code UseRight} = "某主体对某产业的某种生产资料持有多少使用权"。
 *
 * <p>★ 拼写规则（唯一拼写点在 {@code UseRight.idOf(activity, asset, holder, kind, sequence)}）： {@code
 * use-<activity>-<asset>-<holder>-<kind>-<sequence>}；<b>不含 {@code "."}</b>（同 {@link DebtId}
 * 的地址截断理由）。 {@code sequence} 由状态内确定性计数给出（同一状态重放得到同一批 id；不得用随机数/时间戳）。
 *
 * <p>★ opaque 值对象：{@link #toString()} 与 {@link #parse(String)} 互逆；{@code parse} 只校验非空白。
 */
public record UseRightId(String value) {

  public UseRightId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("UseRightId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（格式的权威在 {@code UseRight.idOf}）。 */
  public static UseRightId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("UseRightId 不得为空白: " + text);
    }
    return new UseRightId(text);
  }
}

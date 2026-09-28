package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>成员份额的稳定身份</b>（S1）：一条 {@code Membership} = "某人口批次里有 {@code count} 人属于某家户"。
 *
 * <p>★ 拼写规则（唯一拼写点在 {@code Membership.idOf(lot, household)}）：{@code membership-<lot>-<household>}；
 * <b>不含 {@code "."}</b>（同 {@link DebtId}/{@link LaborAllocationId} 的地址截断理由）。{@code lot} 与 {@code
 * household} 都是稳定 id ⇒ 同一对主体恒得同一个成员份额身份（重放/分支可比）。
 *
 * <p>★ opaque 值对象：{@link #toString()} 与 {@link #parse(String)} 互逆；{@code parse} 只校验非空白，不重实现格式。
 */
public record MembershipId(String value) {

  public MembershipId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MembershipId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（格式的权威在 {@code Membership.idOf}）。 */
  public static MembershipId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("MembershipId 不得为空白: " + text);
    }
    return new MembershipId(text);
  }
}

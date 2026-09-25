package io.mosire.simos.ledger.model;

import java.util.Arrays;

/**
 * 索取权（应收应付）的种类（增量 2 spec §3 / 设计稿 §5/§6.2 逐字）：贷款、欠薪、欠税、地租、留种欠。
 *
 * <p>★ 这是 {@link Claim#kind()} 的词表；**语义解释不在本切片**——哪种债怎么清、何时计息，都在命令层与 {@code production}/{@code
 * government} 的规则里（本切片只记账）。
 */
public enum ClaimKind {
  LOAN,
  WAGE,
  TAX,
  RENT,
  SEED_DEBT;

  /**
   * 按词表解析：词表外的输入即抛，消息里列出全部合法值——静默返回 {@code null} 或默认值都会让"写错债权种类" 变成运行时幽灵（照 {@code ActorKind.parse}
   * 的形制）。
   */
  public static ClaimKind parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ClaimKind 不得为空白: " + text);
    }
    try {
      return valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知 ClaimKind: " + text + "；合法值: " + Arrays.toString(values()));
    }
  }
}

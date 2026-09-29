package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>货币发行审计记录的稳定身份</b>（E3）：一条 {@code INITIAL_ENDOWMENT} / {@code FISCAL_ISSUE} /
 * {@code WITHDRAWAL} 记录的键；格式只有一个拼写点（本文件），与 {@link #parse(String)} 互为逆。
 *
 * <p>★ 运行期由发行腿（余额不足、付方为发行主体）产生，id 从<b>那条转移的稳定身份</b>确定性派生（见
 * {@link #forTransfer(TransferId, CurrencyId)}：同一笔转移、同一币种、同一次发行在重放/分支里给出同一 id）——
 * 不用随机数、时间戳、UUID。
 */
public record MoneyIssuanceId(String value) {

  /** 派生前缀（唯一拼写点）。 */
  private static final String PREFIX = "mi-";

  public MoneyIssuanceId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("MoneyIssuanceId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层/结算层）。 */
  public static MoneyIssuanceId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("MoneyIssuanceId 不得为空白: " + text);
    }
    return new MoneyIssuanceId(text);
  }

  /**
   * ★ 发行腿的确定性 id：{@code mi-<transferId>-<currency>} —— 同一笔转移对同一币种最多发生一条单边发行记录。
   *
   * <p>★ 币种名禁止 {@code '.'}（地址解析器在第一个 {@code '.'} 处切名字，同 {@code transfer} 的 id 约束）与
   * {@code '|'}（账户键的分段符）；真档的 {@code silver} 两者都没有，非法币种名当场抛。
   */
  public static MoneyIssuanceId forTransfer(TransferId transferId, CurrencyId currency) {
    if (transferId == null) {
      throw new IllegalArgumentException("MoneyIssuanceId.forTransfer 的 transferId 不得为 null");
    }
    if (currency == null) {
      throw new IllegalArgumentException("MoneyIssuanceId.forTransfer 的 currency 不得为 null");
    }
    String currencyValue = currency.value();
    if (currencyValue.indexOf('.') >= 0 || currencyValue.indexOf('|') >= 0) {
      throw new IllegalArgumentException(
          "币种名不得含 '.' 或 '|'（发行 id 的分段约定）: " + currencyValue);
    }
    return new MoneyIssuanceId(PREFIX + transferId.value() + "-" + currencyValue);
  }
}

package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>货币发行审计记录的稳定身份</b>（E3）：一条 {@code INITIAL_ENDOWMENT} / {@code FISCAL_ISSUE} / {@code
 * WITHDRAWAL} 记录的键；格式只有一个拼写点（本文件），与 {@link #parse(String)} 互为逆。
 *
 * <p>★ 运行期由发行腿（余额不足、付方为发行主体）产生，id 从<b>那条转移的稳定身份</b>确定性派生（见 {@link #forTransfer(TransferId,
 * CurrencyId)}：同一笔转移、同一币种、同一次发行在重放/分支里给出同一 id）—— 不用随机数、时间戳、UUID。★ P5 的回笼 （{@link
 * #forWithdrawal(String, CurrencyId)}）从<b>回笼命令的稳定 ref</b> 派生，同一命令重放给出同一 id。
 */
public record MoneyIssuanceId(String value) {

  /** 派生前缀（唯一拼写点）。 */
  private static final String PREFIX = "mi-";

  /** ★ P5 回笼派生的前缀（唯一拼写点；与转移派生的 {@code mi-<transferId>} 区分，回放不会与发行腿撞 id）。 */
  private static final String WITHDRAWAL_PREFIX = "mi-wd-";

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
   * <p>★ 币种名禁止 {@code '.'}（地址解析器在第一个 {@code '.'} 处切名字，同 {@code transfer} 的 id 约束）与 {@code
   * '|'}（账户键的分段符）；真档的 {@code silver} 两者都没有，非法币种名当场抛。
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
      throw new IllegalArgumentException("币种名不得含 '.' 或 '|'（发行 id 的分段约定）: " + currencyValue);
    }
    return new MoneyIssuanceId(PREFIX + transferId.value() + "-" + currencyValue);
  }

  /**
   * ★★ <b>P5：回笼（WITHDRAWAL）审计记录的确定性 id</b>：{@code mi-wd-<operationRef>-<currency>} —— 同一回笼命令 （ref
   * 稳定）对同一币种最多发生一条回笼记录；重放/分支不会产生第二份 id。
   *
   * <p>★★ <b>为什么不用金额/日号拼 id</b>：同一天可以对同一币种回笼两笔 <b>同额</b> 的钱，金额拼不出唯一身份（会撞 id、 或逼调用方加随机数）。
   * 回笼是<b>命令</b>驱动的，命令身份（{@code operationRef}）由命令层提供，正是稳定的那一维；本方法只做拼写与非法字符守卫。
   *
   * <p>★ {@code operationRef} 与币种名都禁止 {@code '.'}（地址解析器在第一个 {@code '.'} 处切名字）与 {@code '|'}
   * （账户/编码分段符）；非法输入当场抛。
   */
  public static MoneyIssuanceId forWithdrawal(String operationRef, CurrencyId currency) {
    if (operationRef == null || operationRef.isBlank()) {
      throw new IllegalArgumentException("MoneyIssuanceId.forWithdrawal 的 operationRef 不得为空白");
    }
    if (currency == null) {
      throw new IllegalArgumentException("MoneyIssuanceId.forWithdrawal 的 currency 不得为 null");
    }
    if (operationRef.indexOf('.') >= 0 || operationRef.indexOf('|') >= 0) {
      throw new IllegalArgumentException("回笼命令 ref 不得含 '.' 或 '|'（发行 id 的分段约定）: " + operationRef);
    }
    String currencyValue = currency.value();
    if (currencyValue.indexOf('.') >= 0 || currencyValue.indexOf('|') >= 0) {
      throw new IllegalArgumentException("币种名不得含 '.' 或 '|'（发行 id 的分段约定）: " + currencyValue);
    }
    return new MoneyIssuanceId(WITHDRAWAL_PREFIX + operationRef + "-" + currencyValue);
  }
}

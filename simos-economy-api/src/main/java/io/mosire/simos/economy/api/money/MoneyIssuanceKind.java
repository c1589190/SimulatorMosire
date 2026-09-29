package io.mosire.simos.economy.api.money;

/**
 * ★★ <b>货币发行审计记录的类别</b>（E3；设计稿 §2.6）。
 *
 * <ul>
 *   <li>{@link #INITIAL_ENDOWMENT}：GM 代 GOV 的创世一次性发行（发给家户/经营者/官署）；</li>
 *   <li>{@link #FISCAL_ISSUE}：运行期财政发行（发行主体在余额不足时单边支付形成的差额）；</li>
 *   <li>{@link #WITHDRAWAL}：回笼（发行主体收钱/注销；E3 已留枚举与读口，运行期回笼命令留给 E6）。</li>
 * </ul>
 *
 * <p>★ 金额一律记<b>正数</b>；方向由类别决定（发行加、回笼减），不靠负号。
 */
public enum MoneyIssuanceKind {
  INITIAL_ENDOWMENT,
  FISCAL_ISSUE,
  WITHDRAWAL;

  /** 该类别是否让流通量增加（发行方向）。 */
  public boolean issuance() {
    return this != WITHDRAWAL;
  }

  /** 该类别是否让流通量减少（回笼方向）。 */
  public boolean withdrawal() {
    return this == WITHDRAWAL;
  }
}

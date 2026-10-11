package io.mosire.simos.economy.api.money;

/**
 * ★★ <b>货币发行审计记录的类别</b>（E3；设计稿 §2.6）。
 *
 * <ul>
 *   <li>{@link #INITIAL_ENDOWMENT}：GM 代 GOV 的创世一次性发行（发给家户/经营者/官署）；
 *   <li>{@link #MINT_PRODUCTION}：★★ <b>W5（2026-10-11）铸币生产方式</b> —— 产业产出货币（消耗劳动 + 工具/金属等投入）， 组织者 =
 *       政府家户；钱是<b>生产出来的</b>，不是"缺钱就加一个数字"。★ 它是<b>具名的新类别</b>，<b>不复用</b> {@code
 *       FISCAL_ISSUE}：后者的语义是"发行主体余额不足时单边支付形成的差额"（= 透支腿），与"生产出一批钱" 是两件不同的事实（W5 判据 ⑤
 *       要求"含铸币生产方式这条新路径；kind 要具名"）；
 *   <li>{@link #FISCAL_ISSUE}：运行期财政发行（发行主体在余额不足时单边支付形成的差额）；
 *   <li>{@link #WITHDRAWAL}：回笼（发行主体收钱/注销；E3 已留枚举与读口，运行期回笼命令留给 E6）。
 * </ul>
 *
 * <p>★ 金额一律记<b>正数</b>；方向由类别决定（发行加、回笼减），不靠负号。
 */
public enum MoneyIssuanceKind {
  INITIAL_ENDOWMENT,
  MINT_PRODUCTION,
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

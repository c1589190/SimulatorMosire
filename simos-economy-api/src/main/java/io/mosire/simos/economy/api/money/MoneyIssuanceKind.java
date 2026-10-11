package io.mosire.simos.economy.api.money;

/**
 * ★★ <b>货币发行审计记录的类别</b>（E3；设计稿 §2.6）。
 *
 * <ul>
 *   <li>{@link #INITIAL_ENDOWMENT}：GM 代 GOV 的创世一次性发行（发给家户/经营者/官署）；
 *   <li>{@link #GENESIS_ENDOWMENT}：★★ <b>W5-fix6（2026-10-11）创世国库注资</b> —— 创世把一笔钱<b>凭空</b>记进某个国库余额
 *       （{@code actor.AdjustAccounts} 的纯正增量）时，必须在审计链上留下这一条（否则那笔钱在发行链上"查无此人"）。 ★
 *       它归<b>发行方向</b>（{@link #issuance()} = true）：这是世界被造出来时的一次性发行，不是回笼。 ★ 与 {@link
 *       #INITIAL_ENDOWMENT} 的分工：后者是"发给家户/经营者/官署"的创世钱包（{@code EconomySeeder}），本类别是"发给
 *       <b>国库</b>"的创世注资（{@code GovWorldBootstrap}）—— 两者的收款人、注入点、金额口径都不同，故<b>不合并</b>成一个类别。
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
  /** ★★ W5-fix6：创世<b>国库注资</b>的一次性发行（收款人是国库；见类注与 {@link #INITIAL_ENDOWMENT} 的分工）。 */
  GENESIS_ENDOWMENT,
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

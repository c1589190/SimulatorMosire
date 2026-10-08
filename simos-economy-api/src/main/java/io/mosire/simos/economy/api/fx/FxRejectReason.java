package io.mosire.simos.economy.api.fx;

/**
 * ★★ <b>外汇面上的具名拒因</b>（阶段 2-A2a；约束设计书 §7 的 M2/M3/M4/M8 与 §5 的 I18/I21）。
 *
 * <p>★★ <b>本仓最反对"静默付 0 / 静默不成交"</b>：外汇面上每一处"没换成"都要落到本表的一个短名上，并进日志与读数 （{@code
 * MarketReport.fx().rejections()}）。
 */
public enum FxRejectReason {

  /** 该币对没有官方汇率 ⇒ 没有政府窗口（合法状态：没定政策就没有政策价）。 */
  NO_OFFICIAL_RATE("no_official_rate"),

  /**
   * ★★ <b>已退役（2026-10-09）：不再由任何生产路径发出</b> —— 原义是"政府该币种储备 ≥ 储备上限 {@code R_max} ⇒ <b>停止买入</b>"；用户
   * 2026-10-08 裁定取消这条政策线（「什么叫有仓库上限值，取消掉」）⇒ 买入侧只由"国库可付的 quote"封顶 （{@link
   * #INSUFFICIENT_FUNDS}），储备上限不再参与容量。
   *
   * <p>★ <b>为什么保留这个枚举值</b>：{@code wire()} 是发布后不改的日志/读数契约（旧档与旧日志里 {@code reserve_cap}
   * 仍然要可读可解释），删值会让历史读数失去落点。★ 新代码<b>不得</b>再发它； 判据从"库存触顶 ⇒ 停买"改为 "无上限买入 + 不许卖空（{@link
   * #RESERVE_EXHAUSTED}）+ 每笔买入必须有对手方（{@link #NO_COUNTERPARTY}）"。
   */
  RESERVE_CAP("reserve_cap"),

  /** 政府该币种储备 = 0（或不足本次请求）⇒ <b>停止卖出</b>；<b>不许卖空</b> —— 不许凭空造外币（I21 / M2）。 */
  RESERVE_EXHAUSTED("reserve_exhausted"),

  /** 政府买入没有对手方（订单簿里没有任何家户卖单）⇒ 拒（I21 / M3）。 */
  NO_COUNTERPARTY("no_counterparty"),

  /** 付方余额不足（买它要付的 quote / 卖它要出的 base 不够）⇒ 拒（N2：不许静默按 0 成交）。 */
  INSUFFICIENT_FUNDS("insufficient_funds"),

  /** 异币（I19）：买方支付的币种 ≠ 卖方要收的币种 ⇒ 具名拒（不许静默 1:1）。 */
  CURRENCY_MISMATCH("currency_mismatch"),

  /** base == quote（同币对没有汇率）。 */
  SAME_CURRENCY("same_currency"),

  /** 限价不交叉（买方愿付 &lt; 卖方愿收）⇒ 这一轮没有对手盘可言（不是"被谁挤掉"，是价格上就不成立）。 */
  NO_CROSS("no_cross"),

  /** 自成交（同一主体的两边）⇒ 不撮合（不是成交，也不能算拒了谁的钱）。 */
  SELF_TRADE("self_trade"),

  /** 量低于最小手（避免逐毫的尘埃成交把读数搅浑）。 */
  BELOW_MIN_LOT("below_min_lot"),

  /** 窗口两侧都停（既不能买也不能卖）⇒ 本轮窗口不参与。 */
  WINDOW_INACTIVE("window_inactive"),

  /**
   * 官方报价<b>相反</b>（{@code buyPerMille >= sellPerMille}）⇒ 窗口两侧都停。
   *
   * <p>★ 理由：政府"买得比卖得贵"时，窗口两侧自成交会变成一台无风险抽水机（政府自己给自己送钱）。本批 fail-closed
   * 停做并具名；真要补贴某种钱，那是另一条政策通道，不是外汇窗口的默认语义。
   */
  INVERTED_QUOTE("inverted_quote"),

  /**
   * 实际汇率<b>无读数</b>：本区最近窗口内一笔 FX 成交都没有（I18 / M4）。
   *
   * <p>★ 它是 {@link FxMarketRate#noReadingReason()} 的唯一生产原因；出现它时<b>不得</b>回落官方汇率。
   */
  NO_FX_FILLS("no_fx_fills");

  private final String wire;

  FxRejectReason(String wire) {
    this.wire = wire;
  }

  /** 稳定短名（进日志、进读数、便于 grep；发布后不改）；★ 与枚举名分开是为了"改名不改日志契约"。 */
  public String wire() {
    return wire;
  }
}

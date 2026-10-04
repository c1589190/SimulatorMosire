package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★★ <b>一个格上的现货市场</b>（H4；裁定 M1-A）：<b>每格单一计价货币</b>（{@link #numeraire()}）+ <b>一张商品价格表</b> （{@link
 * #prices()}）。
 *
 * <p>★★ <b>为什么计价货币只有一个、且是格的一维</b>：同格的买卖要能互相比较（"一斗粮值几尺布"），而这需要一个共同尺度 —— 两种计价货币并存时， "这一格的价格"就不是一个数了。★
 * <b>不做汇率</b>（那要另一套制度与另一批数据）：跨币种的兑换在本批<b>不存在</b>， 币种之间不许求和、也不许折算。
 *
 * <p>★★ <b>价格是数据，不是公式</b>（信条十二：模型只提供口径、GM 提供判断）：本类只<b>存</b>价格， 不生成、不平滑、不"按供需算一个" —— 谁定这个数、多久调一次，是
 * GM 的事（将来由参数表/命令给它）。 ⇒ 结算里出现的只有"按这个价成交"这一个动作 （见 {@code MarketSettlement}）。
 *
 * <p>★★ <b>价格的量纲 = 毫计价货币 / 1 商品单位</b>（1 商品单位 = {@code EconomyVocabulary.MILLI_PER_COMMODITY_UNIT} =
 * 1000 毫商品，与全仓"数量一律毫单位"同一条口径）：
 *
 * <pre>
 * 货款(毫钱) = 数量(毫商品) × 价格 ÷ 1000      // 整数、向下取整（同全仓"数量向下取整"）
 * 买得起多少 = 钱(毫钱) × 1000 ÷ 价格          // 与上式互逆（恒有 货款(买得起多少) ≤ 钱）
 * </pre>
 *
 * <p>★★ <b>0 价的两种含义（2026-10-09 用户口径）</b>：本表<b>没有这一行</b> = 这一格从未给该商品定价 ⇒
 * <b>不交易</b>（合法状态，不抛）；本表<b>有这一行且值为 0</b> = <b>明确 0 价免费交易</b> —— 买方不付货款（货款腿为 0）， 但<b>运费照付</b>（运费与商品价格解耦，见
 * {@code MarketSettlement} 的运费算式）。⇒ 构造期只拒绝<b>负价</b>；"有没有定价"由 {@link #hasPrice(CommodityId)}
 * 回答、"是不是免费"由 {@link #isFree(CommodityId)} 回答。{@link #priceOf(CommodityId)} 对"未定价"与"明确 0 价"
 * 都返回 0，因此<b>凡是要区分这两种状态的判定</b>必须先查 {@code hasPrice}。
 *
 * <p>★★ <b>M2.6：本类同时给出买卖两侧的挂牌限价</b>（{@link #bidPriceOf} / {@link #askPriceOf}）—— 参考价仍在 {@link
 * #prices()} 里，两个限价由 {@link #BID_PER_MILLE} / {@link #ASK_PER_MILLE} <b>两个各自独立</b>的具名常量现算；
 * 订单按它们过滤，成交仍走参考价。★ 为什么参考价不换成"成交价"：市场撮合在本层是买卖双方直接配对（没有做市商库存）， 价差只表达"双方愿意让步的范围"，不构成一笔要落账的收入。
 *
 * <p>★ <b>缺格的格 = 该格没有市场</b>（{@code EconomyData.markets} 里没有那个键）：<b>合法状态</b>，不是坏数据 ——
 * 本仓的世界可以只有一部分格子有市场（真档创世只给城市格播种），结算对它们<b>什么都不做</b>（不抛、不造一个默认价）。
 *
 * <p>★ <b>不可变写在赋值处</b>（照 {@code Industry.outputPerUnit} 的先例）：{@code EI_EXPOSE_REP} 只认字面上的包装 —— 但
 * {@code Collections.unmodifiableMap} 不能直接用在**参数**上（那会改掉参数绑定的类型），故这里用"先拷进不可变副本、再重新绑定"的同一形制。
 *
 * @param numeraire 本格唯一的计价货币（结算里所有的钱都是它）；不得为 null
 * @param prices 商品 → 单价（毫计价货币 / 商品单位；**逐值 ≥ 0**，0 = 明确免费）；不得为 null（没有价格就请给空表）；键值非 null
 */
public record Market(CurrencyId numeraire, Map<CommodityId, Long> prices) {

  public Market {
    if (numeraire == null) {
      throw new IllegalArgumentException("Market.numeraire 不得为 null（每格恰一种计价货币，裁定 M1-A）");
    }
    if (prices == null) {
      throw new IllegalArgumentException("Market.prices 不得为 null（没有价格请给空表）");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : prices.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("Market.prices 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "Market.prices 的单价不得为负（0 = 明确免费交易；没有定价请整行缺省）："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    prices = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }

  /** 该商品的单价（毫计价货币 / 商品单位）；**没有定价的商品 ⇒ 0 = 本格不交易它**（合法状态，不抛）。 */
  public long priceOf(CommodityId commodity) {
    return prices.getOrDefault(commodity, 0L);
  }

  /**
   * ★★ <b>这一格有没有给该商品定价</b>：{@code prices} 里有这一行即为 true —— 值为 <b>0 也算定价</b>
   * （明确 0 价免费交易）。{@link #priceOf} 无法区分"未定价"与"定价为 0"，故凡是要区分这两种状态的判定必须先查本方法。
   */
  public boolean hasPrice(CommodityId commodity) {
    return prices.containsKey(commodity);
  }

  /** ★★ <b>是不是"明确 0 价免费交易"</b>：有定价行且值为 0（与"未定价 ⇒ 不交易"是两件事）。 */
  public boolean isFree(CommodityId commodity) {
    Long price = prices.get(commodity);
    return price != null && price == 0L;
  }

  /**
   * ★★ <b>M2.6：卖方的挂牌底价（bid）</b>：{@code ⌊参考价 × {@value #BID_PER_MILLE} ÷ 1000⌋}，且至少 1 毫。
   *
   * <p>★★ <b>为什么 bid/ask 必须是两个具名常量</b>：价差的两条腿（买方最多愿付、卖方最少愿收）是**两件事** —— 用一个
   * "价差"常量同时推两边，改一边就会悄悄改另一边；照本仓"不许一个常量兼两职"的纪律拆成两个数，各自可调、各自可读。
   *
   * <p>★ <b>它只决定限价，不决定成交价</b>：成交仍按参考价（区内 = 集散节点市价、跨区 = 卖方格市价）—— 买卖双方都比自己的限价占优，
   * 价差没有中间人截留（钱不许凭空消失）。未定价 ⇒ 返回 {@code 0}；明确 0 价（免费）也返回 {@code 0} —— 调用方必须用
   * {@link #hasPrice(CommodityId)} 区分"不交易"与"免费"。
   *
   * @param commodity 商品；不得为 null
   * @return 卖方最低可接受价（毫计价货币 / 商品单位）；没有定价 ⇒ 0；明确 0 价 ⇒ 0
   */
  public long bidPriceOf(CommodityId commodity) {
    long price = priceOf(commodity);
    return price <= 0L ? 0L : Math.max(1L, price * BID_PER_MILLE / 1000L);
  }

  /**
   * ★★ <b>M2.6：买方的最高限价（ask）</b>：{@code ⌈参考价 × {@value #ASK_PER_MILLE} ÷ 1000⌉}，且严格高于 {@link
   * #bidPriceOf}（极小的价格上价差退化成 1 毫 —— 那仍是"分开的两个限价"，不是同一个数）。
   *
   * <p>★ 口径与 {@link #bidPriceOf} 对称：只进限价过滤，不决定成交价。未定价 ⇒ 0；明确 0 价 ⇒ 0（免费交易的买方货款上限为 0）。
   *
   * <p>★★ <b>整数网格的如实边界</b>：价格是毫单位的整数 ⇒ 当 {@code p} 小到 1% 不足 1 毫时（真档粮价 = 1）， 价差退化成"两侧各让 1 毫"（bid 至少
   * 1、ask 至少 bid+1），相对幅度会大于 1%。这是网格的必然，不是公式走样 —— 成交仍按参考价，价差只放宽/收紧**限价过滤**。
   *
   * @param commodity 商品；不得为 null
   * @return 买方最高可接受价（毫计价货币 / 商品单位）；没有定价 ⇒ 0；明确 0 价 ⇒ 0
   */
  public long askPriceOf(CommodityId commodity) {
    if (!hasPrice(commodity)) {
      return 0L; // 未定价 ⇒ 不交易（无价可挂）
    }
    long price = priceOf(commodity);
    if (price == 0L) {
      return 0L; // 明确 0 价 ⇒ 免费交易：买方只出运费，货款上限 0
    }
    long ask = (price * ASK_PER_MILLE + 999L) / 1000L;
    return Math.max(bidPriceOf(commodity) + 1L, ask);
  }

  /**
   * ★ <b>卖方底价的千分比</b>：{@code 990‰} ⇒ 挂牌 bid 比参考价低约 1%。
   *
   * <p>★ 它是 GM 可调出厂值（V7 参数目录落地后迁入），与 {@link #ASK_PER_MILLE} 各自独立可调。
   */
  public static final long BID_PER_MILLE = 990L;

  /**
   * ★ <b>买方限价的千分比</b>：{@code 1010‰} ⇒ 挂牌 ask 比参考价高约 1%。两腿合计 ≈2% 价差。
   *
   * <p>★ 命名纪律：**不许**用本常量同时推 bid —— 两个方向各有一个具名常量，改哪边只影响哪边。
   */
  public static final long ASK_PER_MILLE = 1010L;
}

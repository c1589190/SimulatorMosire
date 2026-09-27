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
 * <p>★ <b>为什么价格必须 &gt; 0</b>：它是"可花的钱 ÷ 价格"那个式子的分母 —— 取 0 会让"买得起多少"除零， 而"白送"不是一种价格（它是另一套制度：配给/救济）。 ⇒
 * 逐值 {@code > 0}，构造期判死（不静默归一）。
 *
 * <p>★ <b>缺格的格 = 该格没有市场</b>（{@code EconomyData.markets} 里没有那个键）：<b>合法状态</b>，不是坏数据 ——
 * 本仓的世界可以只有一部分格子有市场（真档创世只给城市格播种），结算对它们<b>什么都不做</b>（不抛、不造一个默认价）。
 *
 * <p>★ <b>不可变写在赋值处</b>（照 {@code Industry.outputPerUnit} 的先例）：{@code EI_EXPOSE_REP} 只认字面上的包装 —— 但
 * {@code Collections.unmodifiableMap} 不能直接用在**参数**上（那会改掉参数绑定的类型），故这里用"先拷进不可变副本、再重新绑定"的同一形制。
 *
 * @param numeraire 本格唯一的计价货币（结算里所有的钱都是它）；不得为 null
 * @param prices 商品 → 单价（毫计价货币 / 商品单位；**逐值 &gt; 0**）；不得为 null（没有价格就请给空表）；键值非 null
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
      if (entry.getValue() <= 0L) {
        throw new IllegalArgumentException(
            "Market.prices 的单价必须 > 0（价格是可花的钱 ÷ 价格 那个式子的分母，"
                + "而'白送'不是一种价格）："
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
}

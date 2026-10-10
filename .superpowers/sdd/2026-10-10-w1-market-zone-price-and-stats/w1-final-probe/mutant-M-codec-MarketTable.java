package io.mosire.simos.economy.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>一张市场价表 = 一个市场的全部价格事实</b>（W1，2026-10-10：<b>逐格价表退役 ⇒ 每区一张</b>）。
 *
 * <p>★★ <b>它取代了什么</b>：旧 {@code Market(CurrencyId numeraire, Map<CommodityId, Long> prices)} 是
 * <b>逐格</b>的（{@code EconomyData.markets} 键 = {@link HexCoord}）⇒ 同一市场区里每一个成员格各存一份"逐字相同"的价表，
 * 而"这个区的价"实际由集散节点（锚格）的那一份说了算（{@code MarketSettlement} 的区内撮合取锚格价）。存储形态与语义口径 因此长期不一致，还派生出 {@code
 * adaptPrices} 的"逐成员格对齐循环"这条补丁。W1 把存储改成<b>每区一张</b>： {@code EconomyData.marketTables} 键 = {@link
 * MarketZoneId}，循环随之删除。
 *
 * <pre>
 * numeraire  本市场的计价币（毫计价货币 / 商品单位 里的"计价货币"）
 * hexes      ★ 本市场的成员格：**派生形态的唯一权威**（见下）；持久形态必须为空集合
 * prices     商品 → 单价（毫计价货币 / 商品单位；**逐值 ≥ 0**，0 = 明确免费）
 * </pre>
 *
 * <p>★★ <b>两种形态（同一个类型、同一个键空间，互斥）</b> —— 与 {@code EconomyData.marketZones} 是否为空一一对应：
 *
 * <ol>
 *   <li><b>持久形态</b>（{@code marketZones} 非空）：键必须是<b>区 id</b>，成员格由 {@code MarketZone.hexes}
 *       <b>唯一给定</b>（I22：一个 hex 只属于一个区，成员格只有一个权威）⇒ 本记录的 {@link #hexes()} 必须为空， 而"有市场的格" =
 *       "该区的成员格"（区内任何格取价一致，区内一格格都有本区价表）。契约字段 {@code numeraire} 必须逐值等于该区 {@code legalTender}（构造期守卫，见
 *       {@code EconomyData}）；
 *   <li><b>派生形态</b>（{@code marketZones} 为空）：键 = <b>派生市场 id</b>（稳定：同一份状态恒同一个 id）， 成员格由本记录的 {@link
 *       #hexes()} 唯一给定，计价币由 {@link #numeraire()} 唯一给定 —— 这一形态下 {@code MarketZone}
 *       根本不存在，"这个市场在哪几格、用什么钱"只能由本记录回答。
 * </ol>
 *
 * <p>★★ <b>为什么 {@code hexes} 必须存在</b>：派生形态没有 {@code MarketZone} 可问"哪些格有市场"，而"哪些格有市场"是
 * <b>状态</b>（创世只给有经济状态的格播种市场），不是可以现算的派生量 —— 它必须随价格一起落盘，否则 {@code 0→360} 之后 重开档会得到"哪些格能交易"与存档前不同的世界。
 *
 * <p>★★ <b>价格是数据，不是公式</b>（信条十二：模型只提供口径、GM 提供判断）：本类只<b>存</b>价格，不生成、不平滑、 不"按供需算一个" —— 改价只发生在 {@code
 * MarketSettlement} 的自适应写回与 GM 命令两处。
 *
 * <p>★★ <b>价格的量纲 = 毫计价货币 / 1 商品单位</b>（1 商品单位 = {@code EconomyVocabulary.MILLI_PER_COMMODITY_UNIT} =
 * 1000 毫商品，与全仓"数量一律毫单位"同一条口径）：
 *
 * <pre>
 * 货款(毫钱) = 数量(毫商品) × 价格 ÷ 1000      // 整数、向下取整（同全仓"数量向下取整"）
 * 买得起多少 = 钱(毫钱) × 1000 ÷ 价格          // 与上式互逆（恒有 货款(买得起多少) ≤ 钱）
 * </pre>
 *
 * <p>★★ <b>0 价的两种含义（2026-10-09 用户口径）</b>：本表<b>没有这一行</b> = 这个市场从未给该商品定价 ⇒
 * <b>不交易</b>（合法状态，不抛）；本表<b>有这一行且值为 0</b> = <b>明确 0 价免费交易</b> —— 买方不付货款（货款腿为 0）， 但<b>运费照付</b>。⇒
 * 构造期只拒绝<b>负价</b>；"有没有定价"由 {@link #hasPrice(CommodityId)} 回答、"是不是免费"由 {@link #isFree(CommodityId)}
 * 回答。{@link #priceOf(CommodityId)} 对"未定价"与"明确 0 价"都返回 0， 因此<b>凡是要区分这两种状态的判定</b>必须先查 {@code
 * hasPrice}。
 *
 * <p>★★ <b>买卖两侧的挂牌限价</b>（{@link #bidPriceOf} / {@link #askPriceOf}）与两个具名常量 （{@link #BID_PER_MILLE}
 * / {@link #ASK_PER_MILLE}）逐字沿用退役的 {@code MarketTable}：它们只决定限价过滤，不决定成交价 （成交按本表价）。★
 * 随"家户不挂价"（S-9）退役属后续批次，W1 不动。
 *
 * <p>★ <b>不可变写在赋值处</b>（照 {@code Industry.outputPerUnit} 的先例）：{@code Collections.unmodifiableMap}
 * 不能直接用在<b>参数</b>上（那会改掉参数绑定的类型），故这里用"先拷进不可变副本、再重新绑定"的同一形制。★ 成员格取 <b>规范序</b>（{@code (q, r)}
 * 升序）：迭代序是内容的纯函数（I7；禁 {@code Set.copyOf}）。
 *
 * @param numeraire 本市场的计价币；不得为 null
 * @param hexes 本市场的成员格（派生形态的唯一权威；持久形态必须为空表）；不得为 null；逐值非 null、规范序、不可变
 * @param prices 商品 → 单价（毫计价货币 / 商品单位；**逐值 ≥ 0**，0 = 明确免费）；不得为 null（没有价格就请给空表）
 */
public record MarketTable(
    CurrencyId numeraire, Set<HexCoord> hexes, Map<CommodityId, Long> prices) {

  public MarketTable {
    Objects.requireNonNull(numeraire, "MarketTable.numeraire 不得为 null（一个市场恰一种计价币）");
    if (hexes == null) {
      throw new IllegalArgumentException("MarketTable.hexes 不得为 null（没有成员格请给空表）");
    }
    List<HexCoord> sorted = new ArrayList<>(hexes.size());
    for (HexCoord hex : hexes) {
      if (hex == null) {
        throw new IllegalArgumentException("MarketTable.hexes 不得含 null");
      }
      sorted.add(hex);
    }
    // ★ 规范序（(q,r) 升序）：迭代序 = 内容的纯函数 ⇒ 逐格结算序与读数序可复现（与 MarketZone.hexes 同一口径）。
    sorted.sort(HexCoord::compareTo);
    hexes = Collections.unmodifiableSet(new LinkedHashSet<>(sorted)); // ★ 冻在赋值处
    if (prices == null) {
      throw new IllegalArgumentException("MarketTable.prices 不得为 null（没有价格请给空表）");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : prices.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("MarketTable.prices 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "MarketTable.prices 的单价不得为负（0 = 明确免费交易；没有定价请整行缺省）："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    prices = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }

  /** ★ 只有计价币的单格形态（派生形态的夹具/播种常用）：成员格 = 一个格，价格表 = 给定表。 */
  public static MarketTable singleHex(
      CurrencyId numeraire, HexCoord hex, Map<CommodityId, Long> prices) {
    Objects.requireNonNull(hex, "hex");
    return new MarketTable(numeraire, new LinkedHashSet<>(List.of(hex)), prices);
  }

  /** 该商品的单价（毫计价货币 / 商品单位）；**没有定价的商品 ⇒ 0 = 本市场不交易它**（合法状态，不抛）。 */
  public long priceOf(CommodityId commodity) {
    return prices.getOrDefault(commodity, 0L);
  }

  /**
   * ★★ <b>这个市场有没有给该商品定价</b>：{@code prices} 里有这一行即为 true —— 值为 <b>0 也算定价</b> （明确 0 价免费交易）。{@link
   * #priceOf} 无法区分"未定价"与"定价为 0"，故凡是要区分这两种状态的判定必须先查本方法。
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
   * ★ 本市场的最小毫：0 价（免费）也算"有价"，故只看有没有定价行。空表 ⇒ 无价可言。
   *
   * <p>★★ <b>线格式契约（W1f，2026-10-11）</b>：这是<b>派生判断</b>、不是状态组件 ⇒ 必须 {@link JsonIgnore}。Jackson 的 bean
   * 内省把 {@code isEmpty()} 当成属性 {@code empty} <b>写进字节</b>，而全仓读侧 （{@code FAIL_ON_UNKNOWN_PROPERTIES}
   * 保持默认的严格，见 {@code SimosObjectMapper}）随即炸在 {@code UnrecognizedPropertyException: Unrecognized
   * field "empty" … not marked as ignorable (3 known properties: "hexes", "prices", "numeraire")} ⇒
   * <b>含市场的快照/检查点/变更集自己写出来的字节读不回</b>（W1 引入的往返缺陷）。
   *
   * <p>★★ <b>为什么必须落在类型上、不能只靠 {@code EconomyCodec} 的 mixin</b>（与 {@link
   * EconomyMeta#isCurrentRuntimeVersion()} 逐字同一条理由）：重放走 Core 的第四台 mapper（{@code
   * Timeline.CHANGESET_MAPPER}）与真实 Shell 的 {@code SimosObjectMapper.create()} 裸
   * mapper，两者都<b>看不见模块私有 mixin</b> ⇒ 不摘掉本属性，任何改过 {@code marketTables} 的 revision 都会在重放时因未知键 {@code
   * empty} fail-closed。
   *
   * <p>★ <b>它不是"把严格关掉"</b>：{@code @JsonIgnore} <b>只具名</b>摘掉这一个派生属性，其余未知字段照旧 fail-closed
   * （漂移信号不丢）；读侧同时把它登记成<b>可忽略</b>属性 ⇒ W1 期间写出的那些带 {@code "empty"} 的旧字节也仍能读回， 不必重写存档。★ 同一族先例：{@code
   * EconomyMeta} 的 {@code isCurrentRuntimeVersion()} / {@code currentCycleNumber()}、 以及九个 codec 里给各
   * {@code XChangeSet.isEmpty()} 装的 mixin。
   */
  public boolean isEmpty() {
    return prices.isEmpty();
  }

  /** ★ 只换整张价格表的拷贝（计价币与成员格逐值不变）：自适应写回与 GM 定价的唯一构造形态。 */
  public MarketTable withPrices(Map<CommodityId, Long> nextPrices) {
    return new MarketTable(numeraire, hexes, nextPrices);
  }

  /** ★ 只换一条价格行（其余逐值不变）：GM 逐商品定价的最小写入形态。 */
  public MarketTable withPrice(CommodityId commodity, long price) {
    Objects.requireNonNull(commodity, "commodity");
    Map<CommodityId, Long> next = new LinkedHashMap<>(prices);
    next.put(commodity, price);
    return new MarketTable(numeraire, hexes, next);
  }

  /** ★ 只换计价币（价格表与成员格逐值不变）：GM 改本区计价币的最小写入形态。 */
  public MarketTable withNumeraire(CurrencyId nextNumeraire) {
    return new MarketTable(nextNumeraire, hexes, prices);
  }

  /** ★ 只换成员格（计价币与价格表逐值不变）：派生形态的划界/退让写入形态。 */
  public MarketTable withHexes(Set<HexCoord> nextHexes) {
    return new MarketTable(numeraire, nextHexes, prices);
  }

  /**
   * ★★ <b>卖方的挂牌底价（bid）</b>：{@code ⌊参考价 × {@value #BID_PER_MILLE} ÷ 1000⌋}，且至少 1 毫。
   *
   * <p>★ <b>它只决定限价，不决定成交价</b>：成交仍按本表价（区内 = 集散节点市价、跨区 = 卖方所在市场市价）—— 买卖双方都比自己的限价占优，
   * 价差没有中间人截留（钱不许凭空消失）。未定价 ⇒ 返回 {@code 0}；明确 0 价（免费）也返回 {@code 0} —— 调用方必须用 {@link
   * #hasPrice(CommodityId)} 区分"不交易"与"免费"。
   *
   * @param commodity 商品；不得为 null
   * @return 卖方最低可接受价（毫计价货币 / 商品单位）；没有定价 ⇒ 0；明确 0 价 ⇒ 0
   */
  public long bidPriceOf(CommodityId commodity) {
    long price = priceOf(commodity);
    return price <= 0L ? 0L : Math.max(1L, price * BID_PER_MILLE / 1000L);
  }

  /**
   * ★★ <b>买方的最高限价（ask）</b>：{@code ⌈参考价 × {@value #ASK_PER_MILLE} ÷ 1000⌉}，且严格高于 {@link
   * #bidPriceOf}（极小的价格上价差退化成 1 毫 —— 那仍是"分开的两个限价"，不是同一个数）。
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

  /** 一行人类可读摘要（日志/探针用；只输出稳定 id 与计数，§一.9 的日志纪律）。 */
  public String describe() {
    return numeraire.value() + "[" + hexes.size() + "格," + prices.size() + "价]";
  }

  /** ★ <b>卖方底价的千分比</b>：{@code 990‰} ⇒ 挂牌 bid 比参考价低约 1%（GM 可调出厂值）。 */
  public static final long BID_PER_MILLE = 990L;

  /** ★ <b>买方限价的千分比</b>：{@code 1010‰} ⇒ 挂牌 ask 比参考价高约 1%。两腿合计 ≈2% 价差。 */
  public static final long ASK_PER_MILLE = 1010L;
}

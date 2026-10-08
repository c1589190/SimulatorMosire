package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>§3.3 家户价目表（HouseholdPriceTable）升为一等输入</b> —— 本阶段<b>逐 tick 派生、不进状态</b>（不变量 P2）。
 *
 * <p>★★ <b>形状复用既有接口</b>：{@link DebtValuation.HouseholdPriceTable}（{@code numeraire() +
 * priceOf(household, commodity)}）——<b>不新造类型</b>。该接口此前"留好了、没人喂它"（类注自述"当前正式状态没有这个字段 ⇒ 调用方传 null"），
 * 本类就是喂它的那一处。
 *
 * <p>★★ <b>语义 = 保留价（reservation price）</b>：
 *
 * <ul>
 *   <li>对<b>买入</b>是该户愿付的<b>上限</b>；
 *   <li>对<b>卖出</b>（本阶段的 HOLD 方向）是该户愿接受的<b>下限</b>。
 * </ul>
 *
 * <p>★★ <b>它从哪来（三条来源，按优先级）</b>：
 *
 * <ol>
 *   <li><b>必要投入价 / 生活保留影子价</b>：目标保有量 = 本户 {@code expectedNeedMilli(商品, }{@value #HOLD_DAYS}{@code
 *       )} （与市场"安全库存 = 30 天"同尺；<b>需求系数的唯一权威仍是 Social</b>，本类只对它线性外推）； 库存越低于目标，保留价越高（缺这一单位要饿肚子/停产）；
 *   <li><b>库存饱和衰减</b>：库存越超过目标，保留价越低（多出来的这一单位对自己不值市场价）；
 *   <li><b>真实成交校准</b>（{@link TradeHistory}）：本户<b>自己</b>最近成交价的滚动均值。★ 本阶段默认 {@link #NO_TRADE_HISTORY}
 *       = 没有校准（跨 tick 的成交史需要状态组件，而 P2 明令第一版不许把派生量塞进状态 —— 要接就得另提状态组件方案，走 Codec + 往返不变式）。
 * </ol>
 *
 * <p>★★ <b>不变量 P1（它不是新权威）</b>：每一格保留价都可由"本户 {@code expectedNeedMilli}（Social 权威的当日物化视图） + 本户库存 +
 * 本格价表"<b>当场重算</b>；本类<b>不持有</b>任何人口/需求账，也不写任何状态。缺项 ⇒ {@link #priceOf} 回 0（{@code 0 = 本户没有对应价格 ⇒
 * 回落市场默认}，与接口契约逐字一致；两者都缺 ⇒ 调用方具名跳过， <b>不猜价</b> —— 负向用例 N7）。
 *
 * <p>★ <b>量纲</b>：价格 = 毫 {@code numeraire} / 商品单位（与 {@link Market#prices()} 同量纲）。
 */
final class HouseholdValuationBook implements DebtValuation.HouseholdPriceTable {

  /**
   * ★ <b>目标保有天数</b>（天）：保留价的"够/不够"分界。{@code 30} 与 {@code MarketSettlement.MARKET_SAFETY_STOCK_DAYS}
   * 同尺 —— 两处若用不同的天数，"市场认为的安全库存"与"家户认为的够用"就会各说各话。
   */
  static final long HOLD_DAYS = 30L;

  /**
   * ★ <b>完全短缺时的上浮上限（‰）</b>：库存为 0 时保留价 = 市价 × (1 + 500‰) = 1.5 倍。
   *
   * <p>★ 依据：生活保留（口粮）对断粮家户的边际价值必须显著高于市价，否则"先卖再挨饿"会成为更划算的选择（N12 要防的形态）； 但也不能无上限 ——
   * 上限太大会让套利买盘把价格一次推到天花板（E1 要求"不是被某户一次吃光后长期不动"）。
   */
  static final long NECESSITY_PREMIUM_PER_MILLE = 500L;

  /**
   * ★ <b>完全饱和时的下沉上限（‰）</b>：库存达到目标保有量的 2 倍时保留价 = 市价 × (1 − 400‰) = 0.6 倍。
   *
   * <p>★ 依据：多出来的那一单位对自己只值"未来的保险"，低于市场买价才愿意出手（这正是 HOLD 方向的判据）。
   */
  static final long SATURATION_DISCOUNT_PER_MILLE = 400L;

  /** ★ <b>成交校准的权重（‰）</b>：有成交史时，保留价 = 需求价 × (1 − w) + 本户历史成交均价 × w。 */
  static final long HISTORY_WEIGHT_PER_MILLE = 300L;

  /**
   * ★★ <b>本户自有成交史的只读查询口</b>（§3.3 第 3 条来源）。
   *
   * <p>★ <b>为什么是一个接口而不是状态字段</b>：成交史要跨 tick 累积 ⇒ 那就是一个<b>状态组件</b>，必须走 Codec + 往返不变式 （AGENTS 铁律 5）；而
   * §3.3 P2 明令第一版不许先把派生量塞进状态。故本阶段把它做成<b>可注入的只读口</b>：生产路径注入 {@link #NO_TRADE_HISTORY}（=
   * 没有校准，逐值确定），后续批次要接成交史时换一份实现即可 —— 不改本类、不改调用点。
   *
   * <p>★ 返回 {@code <= 0} = <b>没有可用历史</b>（不许用 0 冒充"历史价是 0"）。
   */
  @FunctionalInterface
  interface TradeHistory {

    /** 该户在该商品上的最近成交均价（毫 numeraire / 商品单位）；{@code <= 0} = 无历史。 */
    long lastTradePriceOf(HouseholdId household, CommodityId commodity);
  }

  /** ★ 默认：没有成交史（P2：第一版不把派生量塞进状态；接成交史需另提状态组件方案）。 */
  static final TradeHistory NO_TRADE_HISTORY = (household, commodity) -> 0L;

  /**
   * ★ <b>内部刻度：1 毫 = {@value #MICRO_PER_MILLI} 微</b>。
   *
   * <p>★★ <b>为什么本类内部存"微"而不是"毫"</b>：真档粮价 = 1 毫/商品单位，而压力项（±数百‰）在毫网格上会被整数除法 抹平（{@code 1 + 1×500÷1000 =
   * 1}）⇒ 保留价与市价的差恒为 0 ⇒ 套利永远是死分支。对外契约 （{@link DebtValuation.HouseholdPriceTable#priceOf}，量纲 = 毫）与
   * {@link TradeArbitrageActivity}（用微） 因此都从这一份存储派生：{@code 毫 = 微 ÷ 1000}。两处同源，不是两把尺。
   */
  static final long MICRO_PER_MILLI = 1000L;

  private final CurrencyId numeraire;
  private final Map<HouseholdId, Map<CommodityId, Long>> reservationsMicro;

  private HouseholdValuationBook(
      CurrencyId numeraire, Map<HouseholdId, Map<CommodityId, Long>> reservations) {
    this.numeraire = Objects.requireNonNull(numeraire, "家户价目表 numeraire 不得为 null");
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : reservations.entrySet()) {
      Objects.requireNonNull(entry.getKey(), "家户价目表的家户键不得为 null");
      LinkedHashMap<CommodityId, Long> prices = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> price : entry.getValue().entrySet()) {
        if (price.getKey() == null || price.getValue() == null || price.getValue() < 0L) {
          throw new IllegalArgumentException("家户价目表的键/值必须非 null 且非负: " + price.getKey());
        }
        prices.put(price.getKey(), price.getValue());
      }
      copy.put(entry.getKey(), Collections.unmodifiableMap(prices));
    }
    this.reservationsMicro = Collections.unmodifiableMap(copy); // ★ 保序冻结（不用 Map.copyOf：迭代序不是纯函数）
  }

  @Override
  public CurrencyId numeraire() {
    return numeraire;
  }

  /**
   * ★★ 该户对某商品的<b>保留价</b>（毫 numeraire / 商品单位）。
   *
   * <p>★ {@code <= 0} = <b>本户没有对应价格</b> ⇒ 调用方回落市场区默认价表（接口契约逐字）；两者都缺 ⇒ 具名跳过（N7）。
   * 本实现只在"本格价表有该商品定价"时才给价，故"没有价格"与"价是 0"两件事不会被混淆（0 价商品在 {@link TradeArbitrageActivity}
   * 里被具名跳过：免费商品无价差可言）。
   */
  @Override
  public long priceOf(HouseholdId household, CommodityId commodity) {
    return reservationMicroOf(household, commodity) / MICRO_PER_MILLI;
  }

  /**
   * ★★ 该户对某商品的<b>保留价（微 numeraire / 商品单位）</b>——本活动的内部刻度（1 毫 = {@value #MICRO_PER_MILLI} 微）。
   *
   * <p>★ {@code <= 0} = <b>本户没有对应价格</b> ⇒ 调用方回落市场区默认价表；两者都缺 ⇒ 具名跳过（N7）。 ★ 与 {@link #priceOf}
   * 同源（{@code priceOf = 本值 ÷ 1000}），不是第二把尺。
   */
  long reservationMicroOf(HouseholdId household, CommodityId commodity) {
    Objects.requireNonNull(household, "HouseholdValuationBook.reservationMicroOf 的家户不得为 null");
    Objects.requireNonNull(commodity, "HouseholdValuationBook.reservationMicroOf 的商品不得为 null");
    Map<CommodityId, Long> prices = reservationsMicro.get(household);
    return prices == null ? 0L : prices.getOrDefault(commodity, 0L);
  }

  /**
   * ★★ <b>逐 tick 派生一份家户价目表</b>（纯函数；不改任何状态、不读时钟、不用随机）。
   *
   * <pre>
   * 对本户居住格价表里每一个"有定价"的商品 c：
   *   base      = market.priceOf(c)                                  // 区价（§7.0：单市场区内逐格同价）
   *   target    = row.expectedNeedMilli(c, HOLD_DAYS)                // Social 权威的当日物化视图 × 天数
   *   stock     = snapshot.goodsOf(c)                                // 已扣冻结
   *   shortfall = target > 0 ? clamp((target − stock) × 1000 ÷ target, 0, 1000) : 0
   *   saturation= target > 0 ? clamp((stock − target) × 1000 ÷ target, 0, 1000)
   *                          : (stock > 0 ? 1000 : 0)
   *   pressure  = NECESSITY_PREMIUM_PER_MILLE × shortfall ÷ 1000
   *             − SATURATION_DISCOUNT_PER_MILLE × saturation ÷ 1000          // 逐千分，可为负
   *   derived   = max(1, base × 1000 + base × 1000 × pressure ÷ 1000)       // 微刻度（见 MICRO_PER_MILLI）
   *   history   = tradeHistory.lastTradePriceOf(户, c) × 1000               // 默认 0 = 无历史
   *   price     = history > 0 ? (derived × (1000 − HISTORY_WEIGHT) + history × HISTORY_WEIGHT) ÷ 1000
   *                           : derived
   * 市场为 null、或缺该商品定价、或 base <= 0 ⇒ 该商品不给价（= 0 = 没有对应价格）
   * </pre>
   *
   * <p>★ <b>遍历序</b>：家户按 {@code HouseholdId.value()} 升序、商品按 {@code CommodityId.value()} 升序
   * （不依赖任何表/Map 的迭代序 —— §4.3.5）。
   *
   * @param households 逐户行（只读；键序无关，本方法自己重排）
   * @param snapshots 逐户资源快照（键必须覆盖 {@code households}）
   * @param tradeHistory 成交史查询口（不得为 null；没有就给 {@link #NO_TRADE_HISTORY}）
   * @return 一份不可变的家户价目表；没有任何"有价家户" ⇒ 空表（保留价表为空，逐项回 0 = 回落市场默认）
   */
  static HouseholdValuationBook derive(
      Map<HouseholdId, HouseholdEconomy> households,
      Map<HouseholdId, HouseholdResourceSnapshot> snapshots,
      TradeHistory tradeHistory) {
    Objects.requireNonNull(households, "derive 的家户行表不得为 null");
    Objects.requireNonNull(snapshots, "derive 的资源快照表不得为 null");
    Objects.requireNonNull(tradeHistory, "derive 的成交史查询口不得为 null（没有就给 NO_TRADE_HISTORY）");
    List<HouseholdId> ordered = new ArrayList<>(households.keySet());
    ordered.sort(Comparator.comparing(HouseholdId::value));
    CurrencyId numeraire = null;
    LinkedHashMap<HouseholdId, Map<CommodityId, Long>> derived = new LinkedHashMap<>();
    for (HouseholdId household : ordered) {
      HouseholdEconomy row = households.get(household);
      HouseholdResourceSnapshot snapshot = snapshots.get(household);
      if (row == null || snapshot == null || snapshot.market() == null) {
        // ★ 没有行/没有快照/没有市场 ⇒ 该户整户不给价（= 逐项回 0 = 回落市场默认；负向用例 N7 由此成立）
        derived.put(household, Map.of());
        continue;
      }
      Market market = snapshot.market();
      if (numeraire == null) {
        numeraire = market.numeraire();
      }
      LinkedHashMap<CommodityId, Long> prices = new LinkedHashMap<>();
      for (CommodityId commodity : snapshot.pricedCommodities()) {
        if (!market.hasPrice(commodity)) {
          continue; // "从未定价"与"明确 0 价"是两件事（Market 的口径）；这里只在有定价行时给保留价
        }
        long base = market.priceOf(commodity);
        if (base <= 0L) {
          continue; // 明确 0 价（免费）：保留价算式无意义（会在套利里变成无限收益）⇒ 不给价，调用方具名跳过
        }
        long baseMicro = Math.multiplyExact(base, MICRO_PER_MILLI);
        long target = row.expectedNeedMilli(commodity, HOLD_DAYS);
        long stock = snapshot.goodsOf(commodity);
        long shortfall = perMilleOf(target, target - stock);
        long saturation =
            target > 0L ? perMilleOf(target, stock - target) : (stock > 0L ? 1000L : 0L);
        long pressure =
            Math.addExact(
                Math.multiplyExact(NECESSITY_PREMIUM_PER_MILLE, shortfall) / 1000L,
                -Math.multiplyExact(SATURATION_DISCOUNT_PER_MILLE, saturation) / 1000L);
        long reservationMicro = applyPressure(baseMicro, pressure);
        long history = tradeHistory.lastTradePriceOf(household, commodity);
        if (history > 0L) {
          reservationMicro = blend(reservationMicro, Math.multiplyExact(history, MICRO_PER_MILLI));
        }
        prices.put(commodity, reservationMicro);
      }
      derived.put(household, prices.isEmpty() ? Map.of() : Collections.unmodifiableMap(prices));
    }
    return new HouseholdValuationBook(
        numeraire == null
            ? io.mosire.simos.economy.api.money.MoneyVocabulary.SILVER_CURRENCY
            : numeraire,
        derived);
  }

  /** {@code share × 1000 ÷ total}，夹在 {@code [0, 1000]}（{@code total <= 0} ⇒ 0）。 */
  private static long perMilleOf(long total, long share) {
    if (total <= 0L || share <= 0L) {
      return 0L;
    }
    long perMille = Math.multiplyExact(share, 1000L) / total;
    return Math.min(1000L, perMille);
  }

  /**
   * {@code base + base × pressure ÷ 1000}（{@code pressure} 可为负），结果夹到 {@code >= 1}。
   *
   * <p>★ 入参是<b>微</b>刻度（{@code baseMicro}），pressure 是千分整数 ⇒ 分辨率 = 市价的千分之一。 ★ 溢出 fail-closed：与既有"估价一律
   * {@code multiplyExact} ＋ 具名异常"同口径（不静默回退到 double）。
   */
  private static long applyPressure(long baseMicro, long pressure) {
    long delta;
    try {
      delta = Math.multiplyExact(baseMicro, pressure) / 1000L;
    } catch (ArithmeticException overflow) {
      throw new IllegalStateException(
          "家户保留价整数溢出的具名失败（拒绝回退到 double）: baseMicro=" + baseMicro + " pressure=" + pressure,
          overflow);
    }
    return Math.max(1L, Math.addExact(baseMicro, delta));
  }

  /** 需求价与自有成交史的加权平均（{@code HISTORY_WEIGHT_PER_MILLE} 权重；整数、floor；两侧同为微刻度）。 */
  private static long blend(long reservationMicro, long historyMicro) {
    long weighted =
        Math.addExact(
            Math.multiplyExact(reservationMicro, 1000L - HISTORY_WEIGHT_PER_MILLE),
            Math.multiplyExact(historyMicro, HISTORY_WEIGHT_PER_MILLE));
    return Math.max(1L, weighted / 1000L);
  }
}

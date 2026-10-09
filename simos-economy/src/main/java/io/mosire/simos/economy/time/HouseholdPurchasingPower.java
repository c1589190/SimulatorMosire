package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * ★★ <b>家户购买力（P-T5；口岸设计书 §20.2 的 F-1 口径 —— 本类是它<b>唯一</b>拼写点）</b>。
 *
 * <pre>
 * F-1（用户裁定，逐字）："看价格，不是有生活消费品需求吗，算根据手头持有的货币，用哪个货币付的最少，
 *                          付得最少的那个购买力最强"
 *
 * 对持有币 c：cost(c) = Σ_g  naturalNeeds[g] × price_{该币法定区}(g) ÷ 1000      // 毫 c
 *   需求篮子 = 本户注入的 naturalNeeds（Social 是唯一权威，I-C6；economy 不造第二本）
 *   价格     = 该币<b>法定区</b>锚格的市场价表（{@link Market#prices()}，毫计价币 / 商品单位）
 *   cost 越小 = 付清同一篮子越便宜 = 购买力越强；cost 最小者 = 最强
 * </pre>
 *
 * <p>★★ <b>缺价 ⇒ 整币不可比 ⇒ 不换</b>（F-1 的硬边界；V-7 冻结）：篮子里<b>任何一件需求量 &gt; 0 的商品</b>在该币法定区 的价表里没有定价行 ⇒
 * 该币<b>算不出</b>购买力（返回 {@link OptionalLong#empty()}），调用方<b>不得</b>猜、更不得按 1:1 静默顶上。★ "定价为 0" 与 "从未定价"
 * 是两件事（{@link Market#hasPrice(CommodityId)} 区分）：0 价是明确免费，照算 0。
 *
 * <p>★★ <b>强度全序（F-2 的破平，唯一拼写点 = {@link #strengthOrder}）</b>：{@code (cost 升序, 币种 id 升序)} ⇒ <b>首项 =
 * 购买力最强</b>；反向遍历即"最弱先换"。全部排序键都是内容的纯函数（I7 / I-C5），不依赖任何 map 的迭代序。
 *
 * <p>★★ <b>限价（F-4）只从这里来</b>：{@link #limitPerMille} = {@code 1000 × cost(quote) ÷ cost(base)}（毫
 * quote / 1000 毫 base）—— 与 F-1 同一份价格口径（<b>不</b>用家户估值表）。说不出价（0 分母 / 免费 / 溢出）⇒ 返回 {@code 0} =
 * <b>不挂单</b>（fail-closed，绝不猜一个价）。
 *
 * <p>★ <b>它不是状态</b>：本类逐轮由"家户行 + 市场价表"现算，不进 {@code EconomyData}、不进变更集、不落盘（与 {@code
 * CurrencyValuation} 同一形制）。★ 与 {@code CurrencyValuation} 的关系：后者是"钱的价"（官方中价 + 家户估值 +
 * 口岸减项），回答"这笔异币支付划不划算"；本类回答"我手里哪种钱更能买到我要过日子要的东西" —— <b>两者不合并、不互相引用</b> （设计书 §19.4 明令别混为一谈）。
 */
final class HouseholdPurchasingPower {

  /** 千分制换算点（per-mille 的分母）；与 F-1 的"毫"量纲配套。 */
  private static final long PER_MILLE = 1000L;

  private HouseholdPurchasingPower() {}

  /**
   * ★ <b>某币法定区锚格上的价表</b>（逐轮瞬态读数）。
   *
   * @param currency 该区的法定币（= 价表的计价币）
   * @param anchor 该区锚格（"区内参考价"的取价点；{@code MarketRegion.anchor()}）
   * @param market 锚格的市场（{@link Market#prices()} 就是 F-1 用的价格）
   */
  record ZoneTable(CurrencyId currency, HexCoord anchor, Market market) {

    ZoneTable {
      Objects.requireNonNull(currency, "ZoneTable.currency 不得为 null");
      Objects.requireNonNull(anchor, "ZoneTable.anchor 不得为 null");
      Objects.requireNonNull(market, "ZoneTable.market 不得为 null");
    }
  }

  /**
   * ★★ <b>"币种 → 该币法定区的价表"（唯一装配点）</b>：遍历本轮的拓扑区，取 {@code numeraire == 该币} 的区，用它的 <b>锚格</b>市场当价表（与
   * {@code MarketTopologyBook} 的"锚格 = 区内参考价取价点"同一口径）。
   *
   * <p>★ <b>两个 fail-closed 守卫</b>：① 锚格没有市场（{@code anchorWithoutMarket} 是已记档的合法状态）⇒ 该币没有价表； ②
   * 锚格市场的计价币与该区法定币漂开（写入侧守卫本该挡住）⇒ 也当"说不出价"，绝不拿另一币的价表冒充。
   *
   * <p>★ <b>同一币种被多个区当法定币时取"锚格最小 (q, r)"</b>：遍历序本身是纯函数（更稳），但排序反而把"哪个区说了算"钉成 内容函数，与拓扑装配序无关（I7）。★
   * 家户自己所在区的价表由 {@link #tableFor} 优先（"我自己的市场上要付多少"）。
   */
  static Map<CurrencyId, ZoneTable> zoneTables(
      Map<HexCoord, Market> markets, MarketTopology topology) {
    Map<CurrencyId, ZoneTable> tables = new LinkedHashMap<>();
    for (MarketRegion region : topology.regions()) {
      CurrencyId currency = region.numeraire();
      Market market = markets.get(region.anchor());
      if (market == null || !market.numeraire().equals(currency)) {
        continue;
      }
      ZoneTable candidate = new ZoneTable(currency, region.anchor(), market);
      ZoneTable current = tables.get(currency);
      if (current == null || compareAnchors(candidate.anchor(), current.anchor()) < 0) {
        tables.put(currency, candidate);
      }
    }
    return tables;
  }

  /**
   * ★ <b>该户对某币读哪张价表</b>：优先它<b>自己所在区</b>（如果那区的法定币就是它）——"付清我的需求要付多少"问的是我在自己市场上
   * 要掏多少；自己区对不上（或锚格没市场）才回落到 {@link #zoneTables} 的规范选取。两边都没有 ⇒ {@code null}（缺价）。
   */
  static ZoneTable tableFor(
      CurrencyId currency,
      MarketRegion ownRegion,
      Map<CurrencyId, ZoneTable> tables,
      Map<HexCoord, Market> markets) {
    if (currency != null && ownRegion != null && ownRegion.numeraire().equals(currency)) {
      Market own = markets.get(ownRegion.anchor());
      if (own != null && own.numeraire().equals(currency)) {
        return new ZoneTable(currency, ownRegion.anchor(), own);
      }
    }
    return currency == null ? null : tables.get(currency);
  }

  /**
   * ★★ <b>F-1：付清本户生活消费品需求要付多少毫该币</b>（购买力读数的唯一算式）。
   *
   * <pre>
   * cost = Σ_{naturalNeeds[g] &gt; 0}  naturalNeeds[g] × price(g) ÷ 1000        // 毫该币
   * </pre>
   *
   * <p>★ <b>口径三点（写清楚，别让下游猜）</b>：
   *
   * <ol>
   *   <li>篮子 = {@link HouseholdEconomy#naturalNeeds()} <b>全部键</b>（需求量 &gt; 0 的那些）——
   *       不动库存（不扣"已有多少货"）， 因为 F-1 问的是"<b>付清</b>需求"，而扣库存会让口径混进商品面；★ 需求量为 0 的键不进篮子（缺它的价不影响"付清需求"）；
   *   <li>价格 = 该币法定区价表（{@link ZoneTable}）；<b>缺定价行 ⇒ 整币 {@link OptionalLong#empty()}</b>；
   *   <li>除法向下取整（与 {@code MarketSettlement.moneyReserveOfHousehold} 的花钱口径逐字同源）；溢出按饱和处理（{@code
   *       Long.MAX_VALUE}，不抛、不静默变负）。
   * </ol>
   *
   * <p>★ <b>空篮子 ⇒ 空读数</b>（没有需求就没有"付清需求要付多少"这件事 ⇒ 不换；这也是"未注入 naturalNeeds 的旧世界一个数都不动" 的落点）。
   */
  static OptionalLong needCostMilli(HouseholdEconomy row, ZoneTable table) {
    if (row == null || table == null) {
      return OptionalLong.empty();
    }
    Market market = table.market();
    long total = 0L;
    boolean any = false;
    for (Map.Entry<CommodityId, Long> need : row.naturalNeeds().entrySet()) {
      Long quantity = need.getValue();
      if (need.getKey() == null || quantity == null || quantity <= 0L) {
        continue;
      }
      if (!market.hasPrice(need.getKey())) {
        // ★ 缺价 ⇒ 整币不可比（不猜、不按 1:1 顶上）—— 逐币一条具名读数留给调用方的 DEBUG。
        return OptionalLong.empty();
      }
      any = true;
      total = safeAdd(total, safeMulDiv(quantity, market.priceOf(need.getKey()), 1000L));
    }
    return any ? OptionalLong.of(total) : OptionalLong.empty();
  }

  /**
   * ★★ <b>强度全序（F-1/F-2 的唯一拼写点）</b>：{@code cost 升序、币种 id 升序} ⇒ <b>首项 = 购买力最强</b>（F-1）。
   *
   * <p>★ 破平键与全仓 canonical 序同一个（币种 id 升序），<b>不另设特权队列</b>（§20.3 "走市场自然议价"）。★ 缺读数的币（不在 {@code
   * costMilli} 里）排在最后：调用方本就不该拿它们参与比较。★ <b>挂单顺序不取本比较器的 reverse</b>（那会把 id 破平也翻过来，违反 F-2 的"同强度按币种 id
   * 升序"）—— 用 {@link #weakestFirstOrder}。
   */
  static Comparator<CurrencyId> strengthOrder(Map<CurrencyId, Long> costMilli) {
    Objects.requireNonNull(costMilli, "strengthOrder 的 costMilli 不得为 null");
    return Comparator.<CurrencyId, Long>comparing(
            currency -> costMilli.getOrDefault(currency, Long.MAX_VALUE))
        .thenComparing(CurrencyId::value);
  }

  /**
   * ★★ <b>最强持有币 = 强度全序首项（F-1/F-2 的<b>唯一</b>拼写点）</b>：{@code cost} 最小者；同 cost 按币种 id 升序取小。
   *
   * <p>★ <b>它是"换成哪种币"（P-T5 的 FX 定向）与"用哪种币付"（P-T5b 的订单支付币）共用的同一个答案</b> ——
   * 两处若各写一遍"谁最强"，换汇换出来的那种币与买单付出去的那种币就可能在破平处漂开（同一份世界状态给出两种答案）。
   *
   * @param costMilli 币种 → 付清本户需求要付多少毫该币（{@link #needCostMilli} 的产出；缺读数的币不该进表）
   * @return 最强币；{@code costMilli} 为空 ⇒ {@code null}（调用方不得在空表上问"谁最强"）
   */
  static CurrencyId strongest(Map<CurrencyId, Long> costMilli) {
    Objects.requireNonNull(costMilli, "strongest 的 costMilli 不得为 null");
    Comparator<CurrencyId> order = strengthOrder(costMilli);
    CurrencyId best = null;
    for (CurrencyId currency : costMilli.keySet()) {
      if (best == null || order.compare(currency, best) < 0) {
        best = currency;
      }
    }
    return best;
  }

  /**
   * ★★ <b>F-2 的换汇顺序（唯一拼写点）：最弱先换 —— cost 降序；同强度按币种 id 升序</b>。
   *
   * <p>★ 与 {@link #strengthOrder} 的关系：那个是"谁最强"（首项 = 最强），这个是"先挂谁的单"（首项 = 最弱）。 <b>破平方向刻意不同</b>：强度相同时
   * id 小的更强（canonical），而挂单顺序按要求是 id 升序 —— 用户裁定的两句 （"最弱先换" + "同强度按币种 id
   * 升序"）各自管一件事，本方法把后一句钉在<b>顺序</b>上。
   */
  static Comparator<CurrencyId> weakestFirstOrder(Map<CurrencyId, Long> costMilli) {
    Objects.requireNonNull(costMilli, "weakestFirstOrder 的 costMilli 不得为 null");
    return Comparator.<CurrencyId, Long>comparing(
            currency -> costMilli.getOrDefault(currency, Long.MAX_VALUE))
        .reversed()
        .thenComparing(CurrencyId::value);
  }

  /**
   * ★★ <b>F-4：按 F-1 的价格口径给限价</b>（{@code 毫 quote / 1000 毫 base}）：
   *
   * <pre>
   * limitPerMille = ⌊1000 × cost(quote) ÷ cost(base)⌋
   *   1 毫 base 值 ⌊cost(quote)/cost(base)⌋ 毫 quote（同一篮子两种币的"买得起同样东西"的比价）
   * </pre>
   *
   * <p>★ <b>说不出价 ⇒ 0 = 不挂单</b>（fail-closed）：分母 ≤ 0（免费篮子 / 没有读数）、分子 ≤ 0（目标币免费）、或 {@code 1000 × cost}
   * 溢出 —— 一律 0，绝不猜一个价、更不用家户估值表。
   *
   * <p>★ 为什么向下取整：本仓"整数、向下取整、至少 1"的换算式先例（{@code CurrencyValuation} 的跨币值）同一取向；它让限价落在 保守一侧，不会凭空造出购买力。
   */
  static long limitPerMille(long costBaseMilli, long costQuoteMilli) {
    if (costBaseMilli <= 0L || costQuoteMilli <= 0L) {
      return 0L;
    }
    long scaled;
    try {
      scaled = Math.multiplyExact(PER_MILLE, costQuoteMilli);
    } catch (ArithmeticException overflow) {
      return 0L;
    }
    long limit = scaled / costBaseMilli;
    return limit <= 0L ? 0L : limit;
  }

  /** 锚格的规范序（{@code (q, r)} 升序）："同一个币种被多个区当法定币时谁说了算"的破平键。 */
  private static int compareAnchors(HexCoord left, HexCoord right) {
    int byQ = Integer.compare(left.q(), right.q());
    return byQ != 0 ? byQ : Integer.compare(left.r(), right.r());
  }

  private static long safeMulDiv(long value, long multiplier, long divisor) {
    if (divisor <= 0L) {
      return Long.MAX_VALUE;
    }
    try {
      return Math.multiplyExact(value, multiplier) / divisor;
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  private static long safeAdd(long left, long right) {
    if (left <= 0L || right <= 0L) {
      return Math.addExact(left, right);
    }
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }
}

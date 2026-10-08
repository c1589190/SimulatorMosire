package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>§4.3.4 的家户活动契约</b>：<b>一个共同接口</b>，生产与套利实现同一份 —— {@code 收益率(快照)} / {@code resourceNeed()} /
 * {@code execute(分配到的资源) → 变更}。
 *
 * <p>★★ <b>为什么要有它（用户 §1.6 的落点）</b>：「这个家户是不能有脑子吗，投入劳动力，先定个顺序，把根据现有资源核算后，
 * 最划算的先做，不划算的后做，以此分配劳动力，不就行了？」⇒ "要不要种地"和"要不要套利"因此是<b>同一个问题</b>： 把各活动在<b>该户自己的价目表</b>上的收益率放进同一队列比大小。
 *
 * <p>★★ <b>排序器只有一份</b>：{@link ActivitySelector}。本接口只回答"我值多少"，<b>不</b>自带排序、不读时钟、不用随机。
 *
 * <p>★★ <b>execute 的语义（与铁律 2 的关系）</b>：它交回的是<b>声明式变更意向</b>（{@link Intent}），<b>不是</b>账户写口。
 * 真正的库存/货币变动一律走既有的唯一写口 —— 市场腿走 {@code MarketSettlement} 的 {@code ledger.mint(...)} + {@code
 * EconomySettlement.applyTransfer}，劳动腿走既有配额表。任何实现都<b>不得</b>在本方法里 直接改 {@code
 * HouseholdInventory}（那会绕过"所有修改 = Command → ChangeSet → Revision"）。
 *
 * <p>★ <b>本阶段的两个实现</b>：
 *
 * <ul>
 *   <li><b>生产</b> = 既有的 {@code LaborQueueBook.Offer}（它的收益率算式是<u>唯一拼写点</u>，本接口不为它重写第二套 —— 由 {@code
 *       LaborQueueBook} 在排队入口转成 {@link ActivitySelector.RankKey} 参与同一份排序）；
 *   <li><b>套利</b> = {@link TradeArbitrageActivity}（§4.1 的"换出比率"在单币种实物下的形态：保留价 vs 市价的价差）。
 * </ul>
 */
interface HouseholdActivity {

  /** 活动种类（只服务日志/读数的分类，不参与排序键）。 */
  enum Kind {
    /** 生产（务农/作坊/商号）：既有 {@code ProductionProcess} + 产业配方。 */
    PRODUCTION,
    /** 套利/贸易：保留价与市价之间的价差（本阶段 = 单币种实物）。 */
    ARBITRAGE
  }

  /**
   * 一个活动对稀缺资源的需求（§4.3.3 的"稀缺资源消耗"）。
   *
   * @param laborMilli 需要的<b>劳动</b>（毫小时；= 该活动规模上限 × 单位劳动）
   * @param moneyMilli 需要的<b>计价货币</b>（毫钱；纯劳动活动给 0）
   * @param goods 需要的<b>逐商品库存</b>（毫商品；缺键 = 0；保序不可变）
   */
  record Need(long laborMilli, long moneyMilli, Map<CommodityId, Long> goods) {

    public Need {
      if (laborMilli < 0L || moneyMilli < 0L) {
        throw new IllegalArgumentException(
            "HouseholdActivity.Need 的劳动/货币不得为负: " + laborMilli + "/" + moneyMilli);
      }
      Objects.requireNonNull(goods, "HouseholdActivity.Need.goods 不得为 null（无商品需求给空表）");
      LinkedHashMap<CommodityId, Long> copy = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry : goods.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0L) {
          throw new IllegalArgumentException(
              "HouseholdActivity.Need.goods 的键/值必须非 null 且非负: " + entry.getKey());
        }
        if (entry.getValue() > 0L) {
          copy.put(entry.getKey(), entry.getValue());
        }
      }
      goods = Collections.unmodifiableMap(copy);
    }
  }

  /**
   * 分配到这个活动的稀缺资源（§4.3.1 的"能给多少给多少"的结果）。
   *
   * <p>★ 分配结果恒 ≤ {@link #resourceNeed}（分配器只减不增）；{@code execute} 必须按它<b>缩量</b>，
   * <b>不得</b>按需求满额执行（否则"不划算的后做"这条纪律失效）。
   */
  record Allocation(long laborMilli, long moneyMilli, Map<CommodityId, Long> goods) {

    public Allocation {
      if (laborMilli < 0L || moneyMilli < 0L) {
        throw new IllegalArgumentException(
            "HouseholdActivity.Allocation 的劳动/货币不得为负: " + laborMilli + "/" + moneyMilli);
      }
      Objects.requireNonNull(goods, "HouseholdActivity.Allocation.goods 不得为 null（无商品给空表）");
      LinkedHashMap<CommodityId, Long> copy = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> entry : goods.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null || entry.getValue() < 0L) {
          throw new IllegalArgumentException(
              "HouseholdActivity.Allocation.goods 的键/值必须非 null 且非负: " + entry.getKey());
        }
        if (entry.getValue() > 0L) {
          copy.put(entry.getKey(), entry.getValue());
        }
      }
      goods = Collections.unmodifiableMap(copy);
    }

    /** 有没有分到任何资源（全 0 ⇒ 该活动本 tick 不产生任何变更）。 */
    boolean isEmpty() {
      return laborMilli <= 0L && moneyMilli <= 0L && goods.isEmpty();
    }
  }

  /** 买卖方向（§4.1 的边方向；本阶段只有"拿钱换货"与"留货不卖"两种）。 */
  enum Direction {
    /** 买入：市场报价低于本户保留价 ⇒ 按自己算得起的量吃货。 */
    BUY,
    /** 留在手里不卖：市场买价低于本户保留价 ⇒ 少卖（不把货按低于自己估值的价出手）。 */
    HOLD
  }

  /**
   * ★★ <b>一次活动执行的声明式变更意向</b>（sealed：本阶段只有"市场交易"一种）。
   *
   * <p>它<b>不改任何账户</b>：由市场阶段按既有路径落账（订单 → 撮合 → 唯一写口 {@code applyTransfer}）， 因此铁律 2（"所有修改 = Command →
   * ChangeSet → Revision"）不被绕过。
   */
  sealed interface Intent permits Trade {

    /** 这个意向属于哪个活动（日志/读数用；与 {@link #activityId()} 同源）。 */
    String activityId();
  }

  /**
   * 市场交易意向：某家户在某商品上、某个方向、某个量（毫商品），以及<u>当时</u>的保留价与市价（只读证据，不参与落账）。
   *
   * @param direction 买卖方向
   * @param commodity 商品
   * @param quantityMilli 数量（毫商品；恒 &gt; 0）
   * @param reservationMicro 该户保留价（**微** numeraire / 商品单位；1 毫 = 1000 微；恒 &gt; 0）
   * @param marketMicro 当时的市价（**微** numeraire / 商品单位；恒 &gt; 0）
   * @param edgeMicro 单位价差（**微** numeraire / 商品单位；BUY = 保留价 − 卖价，HOLD = 保留价 − 买价；恒 &gt; 0）
   *     <p>★★ <b>为什么三个价格都用"微"而不是"毫"</b>（2026-10-08 诊断缺陷修复）：真档粮价 = 1 毫，
   *     而保留价/价差的真实值常在毫的**分数**上（例如短缺户保留价 1.5 毫、价差 0.49 毫）。 若按毫取整，日志会把"1.5 / 1.0 / 0.49"显示成"1 / 1 /
   *     1"——看起来像**零价差**， 正是"日志本身在骗人"。微刻度下三者天然 &gt; 0（保留价 ≥ 1 微、市价 = 参考价×1000、 平均价差在具名跳过 ≤ 0 的分支之后）⇒
   *     本记录的 {@code > 0} 不变式**不需要任何地板**就成立。 ★ 这三个字段仍然**只进日志与读数**（不参与任何落账/判定：订单只取 {@code
   *     quantityMilli}）。
   */
  record Trade(
      String activityId,
      Direction direction,
      CommodityId commodity,
      long quantityMilli,
      long reservationMicro,
      long marketMicro,
      long edgeMicro)
      implements Intent {

    public Trade {
      if (activityId == null || activityId.isBlank()) {
        throw new IllegalArgumentException("HouseholdActivity.Trade.activityId 不得为空白");
      }
      Objects.requireNonNull(direction, "HouseholdActivity.Trade.direction 不得为 null");
      Objects.requireNonNull(commodity, "HouseholdActivity.Trade.commodity 不得为 null");
      if (quantityMilli <= 0L) {
        throw new IllegalArgumentException(
            "HouseholdActivity.Trade.quantityMilli 必须 > 0（没量就不要发意向）: " + quantityMilli);
      }
      if (reservationMicro <= 0L || marketMicro <= 0L || edgeMicro <= 0L) {
        throw new IllegalArgumentException(
            "HouseholdActivity.Trade 的保留价/市价/价差（微刻度）必须 > 0（没有价差就不要发意向）: "
                + reservationMicro
                + "/"
                + marketMicro
                + "/"
                + edgeMicro);
      }
    }
  }

  /** 稳定活动 id（排序的 tie-break 键之一；同一世界同一天必为同一串）。 */
  String activityId();

  /** 活动种类（日志分类）。 */
  Kind kind();

  /**
   * ★★ <b>§4.3.3 的收益率</b>：{@code [Σ产出估值 − Σ投入估值 − 劳动机会成本] ÷ 稀缺资源消耗}， 定点化成<b>每单位劳动的净收益</b>（刻度见
   * {@link ActivitySelector#PER_LABOR_SCALE}）。
   *
   * <p>★ 与既有 {@code LaborQueueBook} 的排序键<b>同尺同口径</b>（{@code ⌊net × 1_000_000 ÷ labor⌋}）——
   * 两把尺不同，排序就没有意义（这是"一个算法"能被比较的前提）。
   *
   * @param snapshot 本 tick 的资源快照（§4.3.5；调用方保证同一份）
   */
  long yieldScaled(HouseholdResourceSnapshot snapshot);

  /** 本活动在快照下想要多少稀缺资源（§4.3.1 第 ④ 步的"规模上限"）。 */
  Need resourceNeed(HouseholdResourceSnapshot snapshot);

  /**
   * 按分配到的资源交回变更意向（§4.3.1 第 ④ 步的执行）。
   *
   * @param allocation 分配结果（恒 ≤ {@link #resourceNeed}；全 0 ⇒ 返回 {@link Optional#empty()}）
   * @return 变更意向；分不到资源、或该活动本 tick 不产生变更 ⇒ 空（<b>不猜、不静默按 0 执行</b>）
   */
  Optional<Intent> execute(Allocation allocation);
}

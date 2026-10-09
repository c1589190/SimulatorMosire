package io.mosire.simos.economy.time;

/**
 * ★★ <b>M-A2：运力需求的口径（"数量 × 距离"，全仓唯一拼写点）</b>。
 *
 * <p>★★ <b>它是什么</b>：把"某家户对某商品的一份需求"折算成<b>需要多少运力</b>。用户 2026-10-10 原话
 * 「单个家户每需求一个商品就算运力，算完之后算一下总共需要多少运力」；设计书 §15.5 K-5 冻结口径：「沿用<b>既有运费口径</b> （基础运费 × 路线费率 × 承运成本），单位 =
 * 毫商品·程，<b>不新造量纲</b>」。
 *
 * <pre>
 * 运力需求（毫商品·程）= 数量（毫商品） × 每毫商品走这条 lane 的运力耗用（{@link #workPerGoodPerMille}，‰）
 *
 * 每毫商品…耗用（‰） = 既有运费算式 {@code MarketSettlement.freightUnitMilli} 的**物理维**：
 *      max(1, 商品基础费 × (1000 + 路线费率‰))        ← 物理维：既有的两个物理量（基础费的"每单位每程"维 × 距离维）
 *   去掉的是**钱维**：承运成本因子 (1000 + 承运成本‰) 与末尾的 ÷1,000,000 钱换算 —— 前者是"谁报价"（进钱的腿），
 *   后者把量纲从物理量变成"毫钱/商品单位"。去掉它们之后剩下的正是"毫商品·程 / 毫商品"的千分比。
 * </pre>
 *
 * <p>★ <b>为什么不直接拿 {@code freightUnitMilli(…, 承运成本 = 0)} 当耗用</b>（本批实测过的错路，如实记）：那个值是 <b>毫钱 /
 * 商品单位</b>的价格（去掉 ÷1e6 前的量级），对粮/纤维这类基础费 1 的商品只有 <b>1~2‰</b> ⇒ 一份需求只吃 千分之几的运力，658,000 毫商品/轮的运力池能承运
 * <b>3 亿毫商品</b> ⇒ <b>运力不再是硬约束</b>（"供给不足 ⇒ 不成交" 这条判据在真档里几乎永不触发，等于把 M-A1 的运力约束静默删掉）。⇒
 * 需求量必须与"劳动派生出来的运力预算"同量级， 这就是下面取"基础费 × 距离因子"（而不是"钱"）的理由。
 *
 * <p>★ <b>量级（本批标定，供核对）</b>：粮/纤维 基础费 = 1、路线费率 5‰（基础）+ 5‰/hex + 20‰/hex（离城辐射） ⇒ 3 hex 的 lane 费率 ≈ 60‰
 * ⇒ 耗用 ≈ 1060‰（≈1.06 × 数量）；布 2 ⇒ ≈2.1×；工具 3 ⇒ ≈3.2×。 <b>重货/远路吃更多运力</b>，且与 M-A1 的"1 毫商品 = 1 运力单位"同量级
 * ⇒ 运力池仍是硬约束（K-4/Q-27 的洞才真的可见）。
 *
 * <p>★ <b>{@code 0 基础费} 仍算 1‰</b>：沿用 {@code freightUnitMilli} 的既有语义（{@code max(1, …)}："免基础费"
 * 不等于"免费运输"）⇒ 需求量恒 &gt; 0，不会出现"某商品运力需求恒 0"的静默洞。
 *
 * <p>★★ <b>缺省口径 {@link #GOODS_ONLY_WORK_PER_GOOD_PER_MILLE}（= 1000‰，即 1:1）</b>：无报价的世界沿用 M-A1 的
 * 消耗口径（1 毫商品 = 1 个运力单位，距离只经派生半径判触达）。这不是"兼容位"，而是<b>缺省语义中性</b>： 没有运力单 ⇒ 没有"买运力"这件事 ⇒ 逐值等于 M-A1（I-C2）。
 */
public final class CapacityDemand {

  private CapacityDemand() {}

  /** 千分比口径（与 {@code MarketSettlement}/{@code MerchantCapacity.PER_MILLE} 同值）。 */
  public static final long PER_MILLE = 1000L;

  /**
   * ★ <b>缺省口径的运力耗用（‰）</b>：<b>1000</b> ⇒ 1 毫商品 = 1 运力单位（M-A1 的既有消耗口径）。
   *
   * <p>只在<b>无报价</b>（{@link CapacityQuoteBook#empty()}）时使用 ⇒ 缺省世界逐值等于 M-A1。
   */
  public static final long GOODS_ONLY_WORK_PER_GOOD_PER_MILLE = PER_MILLE;

  /**
   * ★★ <b>每毫商品走这条 lane 的运力耗用（‰；毫商品·程 / 毫商品）</b>——"数量 × 距离口径"的唯一入口。
   *
   * <p>{@code = max(1, 商品基础费 × (1000 + 路线费率‰))}——既有运费算式里的两个物理维（见类注）。
   *
   * @param commodityBaseMilli 该商品的基础运费（毫计价货币 / 商品单位 / 程；读 {@code
   *     EconomyData.commodityFreightBaseMilli}，缺键 ⇒ 具名缺省分档），在此作<b>每单位商品的搬运重量维</b>
   * @param ratePerMille 该 lane 的路线费率（‰；距离/辐射/道路，不含商品维 —— {@code
   *     MarketTopology.freightPerMilleBetween}）
   */
  public static long workPerGoodPerMille(long commodityBaseMilli, long ratePerMille) {
    if (commodityBaseMilli < 0L) {
      throw new IllegalArgumentException("商品基础费不得为负（0 = 该商品免基础费）: " + commodityBaseMilli);
    }
    if (ratePerMille < 0L) {
      throw new IllegalArgumentException("路线费率不得为负: " + ratePerMille);
    }
    long routeFactor = Math.addExact(PER_MILLE, ratePerMille);
    return Math.max(1L, Math.multiplyExact(commodityBaseMilli, routeFactor));
  }

  /**
   * ★★ <b>一份需求的运力需求量（毫商品·程）</b> = {@code ⌈数量 × 耗用 ÷ 1000⌉}（向上取整：需求不许被抹成 0）。
   *
   * @param quantityMilli 需求数量（毫商品）
   * @param workPerGoodPerMille {@link #workPerGoodPerMille}；{@code ≤ 0} ⇒ 0
   */
  public static long workMilliOf(long quantityMilli, long workPerGoodPerMille) {
    if (quantityMilli <= 0L || workPerGoodPerMille <= 0L) {
      return 0L;
    }
    return ceilDiv(Math.multiplyExact(quantityMilli, workPerGoodPerMille), PER_MILLE);
  }

  /**
   * ★ <b>一份运力预算还能承接多少商品（毫商品）</b> = {@code ⌊剩余运力 × 1000 ÷ 耗用⌋}（向下取整：绝不超发）。
   *
   * <p>与 {@link #workMilliOf} 是同一换算的两面：{@code workMilliOf(maxGoods, f) ≤ remainingWork}。
   */
  public static long maxGoodsFor(long remainingWorkMilli, long workPerGoodPerMille) {
    if (remainingWorkMilli <= 0L || workPerGoodPerMille <= 0L) {
      return 0L;
    }
    return Math.multiplyExact(remainingWorkMilli, PER_MILLE) / workPerGoodPerMille;
  }

  /**
   * ★ <b>承接 {@code goodsMilli} 实际耗掉的运力（毫商品·程）</b> = {@code ⌈商品 × 耗用 ÷ 1000⌉}。
   *
   * <p>取整方向与 {@link #maxGoodsFor}（向下）配对 ⇒ 恒有 {@code workConsumedBy(maxGoodsFor(w, f), f) ≤ w}
   * （绝不超发）；这里取<b>向上</b>而不是向下，是因为 {@code 耗用 = 1‰} 这类极小系数下"向下"会把一次真实的承接算成 0 运力（静默的免费运力）。{@code 耗用 =
   * 1000‰}（缺省口径）时两向都是恒等式 ⇒ 缺省世界逐值不变。
   */
  public static long workConsumedBy(long goodsMilli, long workPerGoodPerMille) {
    if (goodsMilli <= 0L || workPerGoodPerMille <= 0L) {
      return 0L;
    }
    return ceilDiv(Math.multiplyExact(goodsMilli, workPerGoodPerMille), PER_MILLE);
  }

  private static long ceilDiv(long numerator, long denominator) {
    return -Math.floorDiv(-numerator, denominator);
  }
}

package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>M-A1/M-A2：逐 hex 运力池（派生量，一轮一份、瞬态、不落状态）</b>。
 *
 * <p>★★ <b>它取代了什么</b>：旧 {@code MerchantSettlement.CarrierPool}（"货主挑商号"：按服务半径 + 有效到货费率序 挑 {@code
 * MerchantFirm}，容量就地扣、周期末把 {@code lastFee/upkeep/profit} 写回持久组件）。用户 2026-10-10 裁定 （设计书 §11/§14）：商号
 * = <b>提供运力的家户</b>；运力是<b>每轮算出来的派生量</b>；商号行（{@code MerchantFirm} / {@code
 * EconomyData.merchantFirms}）整体退役。
 *
 * <p>★★ <b>M-A2 新口径（本批冻结）</b>：
 *
 * <pre>
 * 报价     每个提供者有一个**自报价（限价）**：{@link CapacityQuote#askPerMilleOf}（成本维 × 规模维 + 具名固定"上门"附加费；
 *          表见 {@link CapacityQuoteBook}）。买方按**限价升序**买（价格优先）⇒ 同一 lane 上各户各有各的价。
 * 同价序   限价相同 ⇒ 沿用 M-A1 的 canonical 序（市场议价权占比‰ 降序 → 家户 id 升序）——"价格管买方的选择、
 *          议价权管稀缺时的分配"（Q-25），**不另设特权队列**。
 * 需求口径 报价口径下运力按**数量 × 距离**（{@link CapacityDemand#workPerGoodPerMille}，毫商品·程）扣减预算；
 *          缺省口径（无报价）仍按毫商品 1:1（M-A1 逐值不变）。
 * 缺省中性 无报价（{@link CapacityQuoteBook#empty()}）⇒ 序与价都退回 M-A1（I-C2），**不是**兼容位而是缺省语义中性。
 * </pre>
 *
 * <p>★★ <b>冻结口径（逐条落点）</b>：
 *
 * <pre>
 * J-A 「选择跑商」本身就是提供运力 ⇒ 成员判据 = effectivePositionIds() 里含 modeId == merchant 的位置
 * J-C 运力池 = 逐 hex 汇总该格"选了跑商"的家户的运力（{@link MerchantCapacity}）
 * ③  该格运力总量 ⇒ 该格商品的硬上限（本类 select 的 Σ 承接量 ≤ 该格运力预算）
 * ④  分配顺序 = 限价升序（报价口径）→ 市场议价权（占比‰ 降序 → 家户 id 升序；I7 确定性）
 * G-2 运力池挂**发货格**（select 的 from）
 * </pre>
 *
 * <p>★★ <b>一轮一份、不累积</b>：每次装配都从当刻状态重算（{@code 不落状态、不储存、不转卖}）。{@code select} 就地扣 剩余运力 ⇒
 * 本类<b>只允许协调器单线程使用</b>；并行 worker 副本拿 {@link #empty()}（与旧 CarrierPool 同款约定）。
 *
 * <p>★★ <b>缺省中性（冻结项 6）</b>：无报价（家户未挂运力单）⇒ 分配口径与价格逐值退回 M-A1 ⇒ 世界里没有"选了跑商的家户"时 {@link #hasCapacityAt}
 * 恒 false ⇒ 跨格（跨区 + 同区跨格）路线在候选生成处就<b>不建</b>、具名 {@code LOGISTICS_CAPACITY}；<b>同 hex
 * 成交一字不动</b>（它不走运力）。
 */
public final class MerchantCapacityPool {

  /** 运力池日志（market 分类：它的生命周期就是市场轮）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.market();

  /** 工具商品（V-22：跑商消耗走**商品账**，不动 {@code AssetKind.TOOL} 产权份额）—— 唯一拼写点。 */
  public static final CommodityId TOOL_COMMODITY = MerchantHaul.TOOL_COMMODITY;

  /** 空池（没有跑商家户的世界 / worker 本地副本）。 */
  private static final MerchantCapacityPool EMPTY =
      new MerchantCapacityPool(Map.of(), Map.of(), Map.of(), 0L, false, 0L);

  /** 逐格池（键序 = hex 的规范序；值按分配序、不可变）。 */
  private final Map<HexCoord, List<Entry>> byHex;

  /** 逐格运力预算（毫商品·程＝报价口径 / 毫商品＝缺省口径；与 {@link #byHex} 同键集）。 */
  private final Map<HexCoord, Long> totalCapacityByHex;

  /** 有运力的家户数（INFO 汇总用）。 */
  private final long householdCount;

  /** 本轮的运力预算总额（毫商品·程＝报价口径 / 毫商品＝缺省口径；INFO 汇总用）。 */
  private final long totalCapacityMilli;

  /** 家户 → 它的池条目（{@link #recordFee} 用；键域 = 上面所有条目的家户）。 */
  private final Map<HouseholdId, Entry> byHousehold;

  /** 是否成市（报价口径；见 {@link CapacityQuoteBook}）。{@code false} ⇒ 缺省口径（M-A1 逐值不变）。 */
  private final boolean priced;

  /** 本轮所有提供者的最高限价（‰；报价口径下 "计划单位运费" 的上界）。 */
  private final long maxAskPerMille;

  private MerchantCapacityPool(
      Map<HexCoord, List<Entry>> byHex,
      Map<HexCoord, Long> totalCapacityByHex,
      Map<HouseholdId, Entry> byHousehold,
      long householdCount,
      boolean priced,
      long maxAskPerMille) {
    this.byHex = byHex;
    this.totalCapacityByHex = totalCapacityByHex;
    this.byHousehold = byHousehold;
    this.householdCount = householdCount;
    this.priced = priced;
    this.maxAskPerMille = maxAskPerMille;
    long sum = 0L;
    for (long value : totalCapacityByHex.values()) {
      sum = Math.addExact(sum, value);
    }
    this.totalCapacityMilli = sum;
  }

  public static MerchantCapacityPool empty() {
    return EMPTY;
  }

  /**
   * ★★ <b>装配一轮的运力池（缺省口径：无报价）</b>—— 价格与分配序逐值退回 M-A1（I-C2）。
   *
   * <p>夹具 / 纯状态读者 / 旧调用方走这一条；生产路径走 {@link #of(Map, Map, Map, Map, CapacityQuoteBook)}。
   */
  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    return of(classStandings, classPositions, rows, goods, CapacityQuoteBook.empty());
  }

  /**
   * ★★ <b>装配一轮的运力池（唯一入口；每轮重算，不累积）</b>。
   *
   * <p>成员判据（J-A）：{@code classStandings} 里该家户的 {@code effectivePositionIds()} 含任一 {@code
   * classPositions} 中 {@code modeId == merchant} 的位置。运力算式见 {@link MerchantCapacity}；限价见 {@link
   * CapacityQuoteBook#askPerMilleOf}（报价口径）。
   *
   * @param classStandings 家户 → 阶层归属（主业 = current、副业 = participating）
   * @param classPositions 位置 → 生产方式（判"是不是跑商"）
   * @param rows 家户行（劳动投入 + 所在格）
   * @param goods 会话商品账（读 {@code tool} 可投入量；没有商品账时传空表 ⇒ 工具项 = 0，见 J-2 下界）
   * @param quotes 本轮的运力报价表（{@link CapacityQuoteBook#empty()} = 无报价 ⇒ 缺省口径；逐轮瞬态）
   */
  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      CapacityQuoteBook quotes) {
    Objects.requireNonNull(classStandings, "classStandings");
    Objects.requireNonNull(classPositions, "classPositions");
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(goods, "goods");
    Objects.requireNonNull(quotes, "quotes");
    List<HouseholdId> households = new ArrayList<>(rows.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    Map<HexCoord, List<Entry>> pool = new LinkedHashMap<>();
    Map<HexCoord, Long> totals = new LinkedHashMap<>();
    long counted = 0L;
    long maxAsk = 0L;
    for (HouseholdId household : households) {
      HouseholdEconomy row = rows.get(household);
      HouseholdClassMembership standing = classStandings.get(household);
      if (row == null || !MerchantIdentity.selectsMerchant(standing, classPositions)) {
        continue;
      }
      long toolMilli = goods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
      MerchantCapacity capacity =
          MerchantCapacity.of(
              household, row.view().hex(), row.participationAdjustedLaborMilli(), toolMilli);
      if (capacity.capacityMilli() <= 0L) {
        continue; // 0 运力 = 不进池（"有这家户"与"它提供运力"是两件事）
      }
      long askPerMille = quotes.askPerMilleOf(capacity);
      maxAsk = Math.max(maxAsk, askPerMille);
      HexCoord hex = capacity.hex();
      // ★★ M-C：纯商号（H-2：**主业** ∈ merchant.*）/ 顺便跑商（merchant 只在副业）—— 免运费与利润算式的分流依据；
      //   判据的唯一拼写点在 {@link MerchantIdentity}。★ 工具预算（H-5 的门槛维）= 装配时点该户的 tool 商品存量，
      //   每次跑商扣 {@link MerchantHaul#TOOL_MILLI_PER_HAUL}（一次性消耗、不返还）。
      boolean pureMerchant = MerchantIdentity.isPureMerchant(standing, classPositions);
      pool.computeIfAbsent(hex, ignored -> new ArrayList<>())
          .add(new Entry(capacity, askPerMille, pureMerchant));
      totals.merge(hex, capacity.capacityMilli(), Math::addExact);
      counted++;
    }
    LinkedHashMap<HexCoord, List<Entry>> frozen = new LinkedHashMap<>();
    LinkedHashMap<HexCoord, Long> frozenTotals = new LinkedHashMap<>();
    LinkedHashMap<HouseholdId, Entry> frozenByHousehold = new LinkedHashMap<>();
    for (Map.Entry<HexCoord, List<Entry>> entry : pool.entrySet()) {
      long total = totals.getOrDefault(entry.getKey(), 0L);
      List<Entry> entries = entry.getValue();
      for (Entry item : entries) {
        item.sharePerMille = item.capacity.sharePerMilleOf(total);
      }
      // ★★ 池内序 = 分配序（I7：内容的纯函数，不读哈希/插入序）：
      //   报价口径 ⇒ 限价升序（价格优先）→ 议价权降序 → 家户 id 升序（同价按 M-A1 的 canonical 序）
      //   缺省口径 ⇒ 议价权降序 → 家户 id 升序（逐字等于 M-A1）
      Comparator<Entry> order =
          Comparator.comparingLong((Entry item) -> -item.sharePerMille)
              .thenComparing(item -> item.capacity.household().value());
      if (quotes.isPriced()) {
        order = Comparator.comparingLong((Entry item) -> item.askPerMille).thenComparing(order);
      }
      entries.sort(order);
      frozen.put(entry.getKey(), Collections.unmodifiableList(entries));
      frozenTotals.put(entry.getKey(), total);
      for (Entry item : entries) {
        frozenByHousehold.put(item.capacity.household(), item);
      }
    }
    MerchantCapacityPool built =
        new MerchantCapacityPool(
            Collections.unmodifiableMap(frozen),
            Collections.unmodifiableMap(frozenTotals),
            Collections.unmodifiableMap(frozenByHousehold),
            counted,
            quotes.isPriced(),
            maxAsk);
    if (LOG.isDebugEnabled()) {
      for (Map.Entry<HexCoord, List<Entry>> entry : built.byHex.entrySet()) {
        long total = built.totalCapacityByHex.getOrDefault(entry.getKey(), 0L);
        for (Entry item : entry.getValue()) {
          EventLog.channel(LOG)
              .debug(
                  LogEvent.of(
                      "MERCHANT_CAPACITY_HOUSEHOLD",
                      EconomyLogSource.ECONOMY_ORGANIZATION,
                      "hex",
                      entry.getKey(),
                      "household",
                      item.capacity.household().value(),
                      "laborMilli",
                      item.capacity.laborMilli(),
                      "toolMilli",
                      item.capacity.toolMilli(),
                      "capacityMilli",
                      item.capacity.capacityMilli(),
                      "hexTotalMilli",
                      total,
                      "sharePerMille",
                      item.sharePerMille,
                      "tier",
                      item.capacity.tier().name(),
                      "serviceRadiusHex",
                      item.capacity.serviceRadiusHex(),
                      "askPerMille",
                      item.askPerMille,
                      "posted",
                      quotes.hasPosted(item.capacity.household()),
                      "pureMerchant",
                      item.pureMerchant,
                      "toolRemainingMilli",
                      item.remainingToolMilli,
                      "runsAffordable",
                      MerchantHaul.runsAffordable(item.remainingToolMilli),
                      "priced",
                      built.priced));
        }
      }
    }
    return built;
  }

  /** 纯状态派生查询（工具维取 0 ⇒ 运力下界，J-2）：该格有没有"选了跑商且有运力"的家户。 */
  public static boolean hasCapacityAt(EconomyData base, HexCoord hex) {
    return hexCapacityMilli(base, hex, Map.of()) > 0L;
  }

  /**
   * ★★ <b>M-D：该格运力总量（纯函数；与 {@link #of} 装配同一条算式）</b>—— 逐户 {@link MerchantCapacity#of}（劳动投入 +
   * 工具存量）后求和。
   *
   * @param goods 会话商品账（读 {@code tool} 存量）；没有商品账 ⇒ 传空表（工具项 = 0 ⇒ **运力下界**，J-2）
   */
  public static long hexCapacityMilli(
      EconomyData base, HexCoord hex, Map<HouseholdId, Map<CommodityId, Long>> goods) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(goods, "goods");
    long total = 0L;
    List<HouseholdId> households = new ArrayList<>(base.classStandings().keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId household : households) {
      long capacity = memberCapacityMilli(base, household, hex, goods);
      total = Math.addExact(total, capacity);
    }
    return total;
  }

  /**
   * ★★ <b>M-D：一个家户在本格的运力（纯函数；不是池成员 ⇒ 0）</b>—— 成员判据与装配同源（{@link
   * MerchantIdentity#selectsMerchant}），算式同源（{@link MerchantCapacity#of}）。
   */
  private static long memberCapacityMilli(
      EconomyData base,
      HouseholdId household,
      HexCoord hex,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    HouseholdClassMembership standing = base.classStandings().get(household);
    if (!MerchantIdentity.selectsMerchant(standing, base.classPositions())) {
      return 0L;
    }
    HouseholdEconomy row = base.classes().get(household);
    if (row == null || !row.view().hex().equals(hex)) {
      return 0L;
    }
    long toolMilli = goods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
    return MerchantCapacity.of(household, hex, row.participationAdjustedLaborMilli(), toolMilli)
        .capacityMilli();
  }

  /**
   * ★★ <b>M-D / §11.4 G-1：一个家户在该格作为运力提供者的**市场议价权**（占比‰）</b>—— 排序输入之一（计划 §7 Q-19： 与 {@code
   * MerchantCapacityPool} 的分配序**同一口径、同一拼写点**，不得两套）。
   *
   * <pre>
   * 该户**就住在本格且已是池成员**（{@code selectsMerchant}）⇒ share = 该户运力 × 1000 ÷ 本格运力总量
   * 其余（本格候选新进入者 / 邻格准备迁入的候选）        ⇒ share = 该户运力 × 1000 ÷ (本格运力总量 + 该户运力)
   * </pre>
   *
   * <p>★★ <b>为什么邻格候选不返回 0</b>：运力池挂在**发货格**（G-2）且成员判据是"住在那格"；一个住在 A 格、 考虑把跑商当主业并**迁到** B 格的家户，迁到 B
   * 之后就在 B 的池里 —— 因此 B 格的议价权必须按"**新进入者**" 算（分母加上它自己），不能用"它现在不在 B"判成 0（那会把跨格进入跑商的路整条堵死）。
   *
   * <p>★ 除法与占比口径的唯一拼写点 = {@link MerchantCapacity#sharePerMilleOf}（本方法只负责"分母是哪一个"）。 ★ 纯状态读者（没有商品账）传
   * {@code Map.of()} ⇒ 工具项 0 ⇒ 占比是**下界**（J-2 的具名偏差，方向 fail-closed）。
   */
  public static long sharePerMilleAsProviderAt(
      EconomyData base,
      HouseholdId household,
      HexCoord hex,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(goods, "goods");
    HouseholdEconomy row = base.classes().get(household);
    if (row == null) {
      return 0L;
    }
    long toolMilli = goods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
    MerchantCapacity capacity =
        MerchantCapacity.of(household, hex, row.participationAdjustedLaborMilli(), toolMilli);
    if (capacity.capacityMilli() <= 0L) {
      return 0L; // 劳动 + 工具都是 0 ⇒ 没有运力可提供（占比 0，不猜）
    }
    boolean residentMember =
        row.view().hex().equals(hex)
            && MerchantIdentity.selectsMerchant(
                base.classStandings().get(household), base.classPositions());
    long total = hexCapacityMilli(base, hex, goods);
    if (!residentMember) {
      // 本格候选新进入者 / 邻格准备迁入的候选：把自己算进分母（"我进去之后占多少"）
      total = Math.addExact(total, capacity.capacityMilli());
    }
    return capacity.sharePerMilleOf(total);
  }

  /** 池里一条家户运力（可变剩余量；只允许协调器单线程触碰）。 */
  private static final class Entry {
    private final MerchantCapacity capacity;
    private final long askPerMille;
    private final boolean pureMerchant;
    private long remainingWorkMilli;
    private long allocatedGoodsMilli;
    private long allocatedWorkMilli;
    private long allocationCount;
    private long sharePerMille;

    /**
     * ★★ M-C：本轮的**工具预算**（毫工具；装配时点该户 {@code tool} 商品存量）—— 每次跑商扣 {@link
     * MerchantHaul#TOOL_MILLI_PER_HAUL}（一次性消耗、不返还，H-1），不足 ⇒ 该次跑商不成立（H-5，具名归因）。
     */
    private long remainingToolMilli;

    /** 本轮因**缺工具**未成立的跑商次数（具名归因的计数；读数/日志用）。 */
    private long toolBlockedRuns;

    /** ★ 本轮的运费实收（按币分列）—— **每轮算出的读数**，只进日志/读口，不落状态（冻结项 6 的"不落状态"）。 */
    private final Map<CurrencyId, Long> earnedByCurrency = new LinkedHashMap<>();

    private Entry(MerchantCapacity capacity, long askPerMille, boolean pureMerchant) {
      this.capacity = capacity;
      this.askPerMille = askPerMille;
      this.pureMerchant = pureMerchant;
      this.remainingWorkMilli = capacity.capacityMilli();
      this.remainingToolMilli = capacity.toolMilli();
    }
  }

  public boolean isEmpty() {
    return byHex.isEmpty();
  }

  /** 本轮是否成市（报价口径：家户挂了运力单/自报价）。{@code false} ⇒ 缺省口径（M-A1 逐值不变）。 */
  public boolean isPriced() {
    return priced;
  }

  /** 本轮所有提供者的最高限价（‰）；空池/无报价 ⇒ 0。用作"计划单位运费"的上界。 */
  public long maxAskPerMille() {
    return maxAskPerMille;
  }

  /** 这一格有没有运力（静态：装配时的运力 > 0；被本轮别处吃光不影响本判据 —— 那由 select/executeTrade 判）。 */
  public boolean hasCapacityAt(HexCoord hex) {
    return byHex.containsKey(hex);
  }

  /** 这一格的运力总量（毫商品·程＝报价口径 / 毫商品＝缺省口径；没有 ⇒ 0）。 */
  public long totalCapacityAt(HexCoord hex) {
    return totalCapacityByHex.getOrDefault(hex, 0L);
  }

  /** 这一格的提供者家户数（INFO 汇总用；没有 ⇒ 0）。 */
  public long householdCountAt(HexCoord hex) {
    return byHex.getOrDefault(hex, List.of()).size();
  }

  /** 这一格**剩下**的运力（毫商品·程＝报价口径 / 毫商品＝缺省口径；分配后就地减少）。 */
  public long remainingCapacityAt(HexCoord hex) {
    long remaining = 0L;
    for (Entry item : byHex.getOrDefault(hex, List.of())) {
      remaining = Math.addExact(remaining, item.remainingWorkMilli);
    }
    return remaining;
  }

  /** 有运力的家户数（INFO 汇总用）。 */
  public long householdCount() {
    return householdCount;
  }

  /**
   * ★★ <b>M-C：本轮的**跑商家户**（= 池成员，判据 {@link MerchantIdentity#selectsMerchant}）</b>——
   * 商号利润读数的**范围**（没有跑商家户 ⇒ 空集 ⇒ 读数不产出，缺省语义中性 I-C2）。
   *
   * <p>★ 返回的是 {@link #byHousehold} 的键集（构造期已冻结、不可改）；顺序由调用方自己规范（本仓 I7）。
   */
  public java.util.Set<HouseholdId> householdIds() {
    return byHousehold.keySet();
  }

  /** 有运力的格数（INFO 汇总用）。 */
  public int hexCount() {
    return byHex.size();
  }

  /** 本轮的运力总预算（毫商品·程＝报价口径 / 毫商品＝缺省口径；INFO 汇总用）。 */
  public long totalCapacityMilli() {
    return totalCapacityMilli;
  }

  /**
   * ★★ <b>本 lane 的运力耗用系数（‰）</b>——报价口径按"数量 × 距离"（{@link CapacityDemand}，毫商品·程）， 缺省口径按 M-A1 的 1:1（毫商品
   * ⇒ 逐值不变）。
   */
  public long workPerGoodPerMilleOf(long commodityBaseMilli, long ratePerMille) {
    if (!priced) {
      return CapacityDemand.GOODS_ONLY_WORK_PER_GOOD_PER_MILLE;
    }
    return CapacityDemand.workPerGoodPerMille(commodityBaseMilli, ratePerMille);
  }

  /**
   * ★★ <b>按买方选择键分配一条 lane 的运力</b>（缺省口径 1:1；等价于 M-A1 的旧签名）。
   *
   * @param from 发货格（运力池所在的格）
   * @param to 收货格（判半径）
   * @param quantityMilli 本笔请求承运量（毫商品）
   */
  public CarrierAllocation select(HexCoord from, HexCoord to, long quantityMilli) {
    return select(from, to, quantityMilli, CapacityDemand.GOODS_ONLY_WORK_PER_GOOD_PER_MILLE);
  }

  /**
   * ★★ <b>按买方选择键（限价）分配一条 lane 的运力</b>（K-C/K-2/Q-25/Q-27）。
   *
   * <pre>
   * 候选 = 发货格 from 的池 ∩（剩余运力预算 > 0）∩（lane 长度 ≤ 该家户派生半径）
   * 序   = 限价升序（报价口径；买方从**最低价**起买）→ 同价按市场议价权（占比‰ 降序 → 家户 id 升序）
   * 分配 = 逐条 min(剩余需求, 该户剩余预算还能承接的商品量)，就地扣减（报价口径按"数量 × 距离"扣）
   * 不变量 Σ choices.quantityMilli + unallocated == requested，且 Σ 扣减 ≤ 该格运力预算（A3）
   * </pre>
   *
   * <p>★ <b>排"限价"而不是排"容量"</b>：这正是用户裁定的"按市场上最低价的运力提供商买运力"；同价时才由市场议价权 （稀缺分配键）决定先后 ——
   * 两条各管一层（Q-25），<b>不另设特权队列</b>。
   *
   * <p>★ <b>供给不足（K-4/Q-27）</b>：没分到的部分（{@code unallocatedMilli}）由调用方收缩成交量 ⇒ <b>不成交、不成债、
   * 不计价</b>；被挡下的部分不提价不压价（V-20 的截断剔除）。本类不静默丢。
   *
   * @param from 发货格（运力池所在的格）
   * @param to 收货格（判半径）
   * @param quantityMilli 本笔请求承运量（毫商品）
   * @param workPerGoodPerMille 本 lane 的运力耗用（‰；{@link CapacityDemand}。缺省口径传 1000 = 1:1）
   */
  public CarrierAllocation select(
      HexCoord from, HexCoord to, long quantityMilli, long workPerGoodPerMille) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (quantityMilli < 0L) {
      throw new IllegalArgumentException("承运需求不得为负: " + quantityMilli);
    }
    if (workPerGoodPerMille <= 0L) {
      throw new IllegalArgumentException("运力耗用系数必须为正: " + workPerGoodPerMille);
    }
    List<Entry> pool = byHex.getOrDefault(from, List.of());
    List<CarrierChoice> choices = new ArrayList<>();
    long demandLeft = quantityMilli;
    long unreachableWork = 0L;
    long subUnitWork = 0L;
    long toolBlockedRuns = 0L;
    for (Entry item : pool) {
      if (demandLeft <= 0L) {
        break;
      }
      if (!item.capacity.servesLane(to)) {
        unreachableWork = Math.addExact(unreachableWork, item.remainingWorkMilli);
        continue;
      }
      if (item.remainingWorkMilli <= 0L) {
        continue;
      }
      // ★★ M-C：跑商门槛 = 工具（H-D/H-5）。手里的工具不够一趟 ⇒ **该次跑商不成立**（具名归因，绝不静默跳过）：
      //   该条承运不产生，需求转成"运力未获服务"（K-4/Q-27：不成交、不成债、不计价）。
      //   ★ 判据只看**工具预算够不够一趟**，与 lane 长短/批量无关 —— 它是**门槛**，不是按量计的费。
      if (!MerchantHaul.affordsRun(item.remainingToolMilli)) {
        item.toolBlockedRuns++;
        toolBlockedRuns++;
        continue;
      }
      long maxGoods = CapacityDemand.maxGoodsFor(item.remainingWorkMilli, workPerGoodPerMille);
      if (maxGoods <= 0L) {
        // 剩余预算不足一个整商品单位（报价口径下"数量 × 距离"放大了耗用 ⇒ 这一步会真的发生）：
        // 它没有被吃掉，只是这一笔用不上；具名计数，不静默。
        subUnitWork = Math.addExact(subUnitWork, item.remainingWorkMilli);
        continue;
      }
      long take = Math.min(demandLeft, maxGoods);
      long consumed = CapacityDemand.workConsumedBy(take, workPerGoodPerMille);
      item.remainingWorkMilli -= consumed;
      item.remainingToolMilli -= MerchantHaul.TOOL_MILLI_PER_HAUL; // 一次性消耗（H-1）
      item.allocatedGoodsMilli = Math.addExact(item.allocatedGoodsMilli, take);
      item.allocatedWorkMilli = Math.addExact(item.allocatedWorkMilli, consumed);
      item.allocationCount++;
      demandLeft -= take;
      choices.add(
          new CarrierChoice(
              item.capacity.carrier(),
              item.capacity.household(),
              item.capacity.hex(),
              item.capacity.tier(),
              item.askPerMille,
              item.pureMerchant,
              MerchantHaul.TOOL_MILLI_PER_HAUL,
              take,
              consumed));
    }
    long allocated = quantityMilli - demandLeft;
    if (LOG.isDebugEnabled() && demandLeft > 0L) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "MERCHANT_CAPACITY_LANE_TRUNCATED",
                  EconomyLogSource.ECONOMY_ORGANIZATION,
                  "from",
                  from,
                  "to",
                  to,
                  "requestedMilli",
                  quantityMilli,
                  "allocatedMilli",
                  allocated,
                  "unallocatedMilli",
                  demandLeft,
                  "workPerGoodPerMille",
                  workPerGoodPerMille,
                  "requestedWorkMilli",
                  CapacityDemand.workMilliOf(quantityMilli, workPerGoodPerMille),
                  "allocatedWorkMilli",
                  CapacityDemand.workMilliOf(allocated, workPerGoodPerMille),
                  "hexTotalCapacityMilli",
                  totalCapacityByHex.getOrDefault(from, 0L),
                  "outOfRadiusWorkMilli",
                  unreachableWork,
                  "subUnitResidualWorkMilli",
                  subUnitWork,
                  "toolBlockedRuns",
                  toolBlockedRuns,
                  "toolMilliPerHaul",
                  MerchantHaul.TOOL_MILLI_PER_HAUL,
                  "priced",
                  priced,
                  "reason",
                  laneBlockedReason(pool, unreachableWork, subUnitWork, toolBlockedRuns)));
    }
    return new CarrierAllocation(choices, quantityMilli, demandLeft, priced);
  }

  /**
   * ★ <b>这一笔为什么没走完</b>（具名归因，按固定次序拼接：缺工具 / 半径外 / 亚单位残余 / 运力耗尽 / 本格没有跑商家户）—— 只在 DEBUG
   * 行里出现，判据本身不改任何行为。★ 多个原因同时成立时**全部列出**（不挑一个代表性说法）。
   */
  private static String laneBlockedReason(
      List<Entry> pool, long unreachableWork, long subUnitWork, long toolBlockedRuns) {
    if (pool.isEmpty()) {
      return "no-merchant-household-in-shipping-hex";
    }
    List<String> reasons = new ArrayList<>(3);
    if (toolBlockedRuns > 0L) {
      reasons.add("tool-short");
    }
    if (unreachableWork > 0L) {
      reasons.add("out-of-derived-radius");
    }
    if (subUnitWork > 0L) {
      reasons.add("sub-unit-residual");
    }
    if (reasons.isEmpty()) {
      reasons.add("capacity-exhausted");
    }
    return String.join("+", reasons);
  }

  /**
   * ★★ <b>M-C：把一条承运条目的耗用折算成承运家户的**劳动投入**（毫小时）</b>—— 利润读数的"劳动力成本"维。
   *
   * <p>运力 = 劳动项 + 工具项（{@link MerchantCapacity}）⇒ 本条的劳动份额 = {@code 耗用 × 劳动 ÷ 运力}
   * （向上取整：宁可多算一分劳动成本，不静默少算）。查不到该户 ⇒ 0（不猜）。
   */
  public long laborHoursOf(HouseholdId household, long consumedWorkMilli) {
    Entry entry = byHousehold.get(household);
    if (entry == null || consumedWorkMilli <= 0L || entry.capacity.capacityMilli() <= 0L) {
      return 0L;
    }
    long product = Math.multiplyExact(consumedWorkMilli, entry.capacity.laborMilli());
    return (product + entry.capacity.capacityMilli() - 1L) / entry.capacity.capacityMilli();
  }

  /** 该户本轮的工具预算还剩多少（毫工具；读数/日志用；不在池里 ⇒ 0）。 */
  public long remainingToolMilliOf(HouseholdId household) {
    Entry entry = byHousehold.get(household);
    return entry == null ? 0L : entry.remainingToolMilli;
  }

  /** 本轮因缺工具未成立的跑商次数（全部家户之和；INFO 汇总用）。 */
  public long toolBlockedRuns() {
    long total = 0L;
    for (List<Entry> entries : byHex.values()) {
      for (Entry item : entries) {
        total = Math.addExact(total, item.toolBlockedRuns);
      }
    }
    return total;
  }

  /**
   * ★★ <b>记一条本轮的运费实收</b>（唯一致谢点：{@code MarketSettlement} 铸 {@code CARRIER_FEE} 时调用）。
   *
   * <p>它是"利润 = 每轮算出的读数"在本批的**可读部分**：只累计运费实收（按币分列），进日志/读口，<b>不落状态</b>。 完整的利润算式（含纯商号/顺便分流与三层税）属
   * M-C（见实现账本 D-3）。
   */
  public void recordFee(HouseholdId household, CurrencyId currency, long amountMilli) {
    Entry entry = byHousehold.get(household);
    if (entry == null || amountMilli <= 0L) {
      return;
    }
    entry.earnedByCurrency.merge(currency, amountMilli, Math::addExact);
  }

  /** ★ 一轮结束后的逐格分配汇总（INFO = 每格运力池与分配汇总；§一.9）。 */
  public void logRoundSummary(long day) {
    if (!LOG.isInfoEnabled()) {
      return;
    }
    long usedGoodsTotal = 0L;
    long usedWorkTotal = 0L;
    long allocationsTotal = 0L;
    long toolBlockedTotal = 0L;
    long remainingToolTotal = 0L;
    for (Map.Entry<HexCoord, List<Entry>> entry : byHex.entrySet()) {
      long usedGoods = 0L;
      long usedWork = 0L;
      long allocations = 0L;
      long askMin = Long.MAX_VALUE;
      long askMax = 0L;
      Map<CurrencyId, Long> earned = new LinkedHashMap<>();
      for (Entry item : entry.getValue()) {
        usedGoods = Math.addExact(usedGoods, item.allocatedGoodsMilli);
        usedWork = Math.addExact(usedWork, item.allocatedWorkMilli);
        allocations = Math.addExact(allocations, item.allocationCount);
        askMin = Math.min(askMin, item.askPerMille);
        askMax = Math.max(askMax, item.askPerMille);
        for (Map.Entry<CurrencyId, Long> leg : item.earnedByCurrency.entrySet()) {
          earned.merge(leg.getKey(), leg.getValue(), Math::addExact);
        }
      }
      usedGoodsTotal = Math.addExact(usedGoodsTotal, usedGoods);
      usedWorkTotal = Math.addExact(usedWorkTotal, usedWork);
      allocationsTotal = Math.addExact(allocationsTotal, allocations);
      for (Entry item : entry.getValue()) {
        toolBlockedTotal = Math.addExact(toolBlockedTotal, item.toolBlockedRuns);
        remainingToolTotal = Math.addExact(remainingToolTotal, item.remainingToolMilli);
      }
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MERCHANT_CAPACITY_POOL_HEX",
                  EconomyLogSource.ECONOMY_ORGANIZATION,
                  "day",
                  day,
                  "hex",
                  entry.getKey(),
                  "households",
                  entry.getValue().size(),
                  "capacityMilli",
                  totalCapacityByHex.getOrDefault(entry.getKey(), 0L),
                  "allocatedMilli",
                  usedGoods,
                  "allocatedWorkMilli",
                  usedWork,
                  "allocations",
                  allocations,
                  "askMinPerMille",
                  askMin == Long.MAX_VALUE ? 0L : askMin,
                  "askMaxPerMille",
                  askMax,
                  "freightEarnedByCurrency",
                  earned));
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "MERCHANT_CAPACITY_POOL",
                EconomyLogSource.ECONOMY_ORGANIZATION,
                "day",
                day,
                "hexes",
                byHex.size(),
                "households",
                householdCount,
                "capacityMilli",
                totalCapacityMilli,
                "allocatedMilli",
                usedGoodsTotal,
                "allocatedWorkMilli",
                usedWorkTotal,
                "allocations",
                allocationsTotal,
                "priced",
                priced,
                "maxAskPerMille",
                maxAskPerMille,
                // ★★ M-C（§一.9：INFO = 门槛与利润汇总的门槛面）：本轮因缺工具未成立的跑商次数 + 池里剩余工具。
                "toolBlockedRuns",
                toolBlockedTotal,
                "toolMilliRemaining",
                remainingToolTotal,
                "toolMilliPerHaul",
                MerchantHaul.TOOL_MILLI_PER_HAUL));
  }

  /**
   * ★ <b>一条分配结果（承运条目 = 一次跑商）</b>。
   *
   * @param pureMerchant 该提供者是不是**纯商号**（H-2：主业 ∈ merchant.*）—— 免运费（H-A）与利润算式的分流依据
   * @param toolMilli 本次跑商**要烧掉**的工具（毫工具；H-D/H-1 的一次性消耗，成交时从商品账扣）
   * @param askPerMille 该提供者的成交限价（‰；报价口径 = 自报价 + 上门附加费；缺省口径 = M-A1 派生承运成本）
   * @param quantityMilli 本条实际承运的商品量（毫商品）
   * @param consumedWorkMilli 本条的运力耗用（毫商品·程＝报价口径 / 毫商品＝缺省口径）
   */
  public record CarrierChoice(
      ActorRef carrier,
      HouseholdId household,
      HexCoord hex,
      MerchantPolicy.MerchantTier tier,
      long askPerMille,
      boolean pureMerchant,
      long toolMilli,
      long quantityMilli,
      long consumedWorkMilli) {

    public CarrierChoice {
      Objects.requireNonNull(carrier, "carrier");
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(tier, "tier");
      if (askPerMille < 0L) {
        throw new IllegalArgumentException("CarrierChoice.askPerMille 不得为负: " + askPerMille);
      }
      if (toolMilli < 0L) {
        throw new IllegalArgumentException("CarrierChoice.toolMilli 不得为负: " + toolMilli);
      }
      if (quantityMilli <= 0L) {
        throw new IllegalArgumentException("CarrierChoice.quantityMilli 必须为正: " + quantityMilli);
      }
      if (consumedWorkMilli <= 0L) {
        throw new IllegalArgumentException(
            "CarrierChoice.consumedWorkMilli 必须为正: " + consumedWorkMilli);
      }
    }
  }

  /**
   * ★★ <b>一次 select 的完整结果</b>：分给了哪些提供者、各多少、以及没分出去的剩余需求。
   *
   * <p>不变量：{@code Σ choices.quantityMilli + unallocatedMilli == requestedMilli}（A3 退化为"分配总量 ≤
   * 运力总量"）。{@code unallocatedMilli} 必须由调用方显式处理（记 {@code LOGISTICS_CAPACITY} 与"被运力截断量"）， 本类不静默丢。
   *
   * @param priced 本次分配是否走报价口径（决定运费怎么收：报价口径逐条按各自限价，缺省口径沿用 M-A1 的按量比例分摊）
   */
  public record CarrierAllocation(
      List<CarrierChoice> choices, long requestedMilli, long unallocatedMilli, boolean priced) {

    public CarrierAllocation {
      Objects.requireNonNull(choices, "choices");
      choices = List.copyOf(choices);
      if (requestedMilli < 0L || unallocatedMilli < 0L || unallocatedMilli > requestedMilli) {
        throw new IllegalArgumentException(
            "CarrierAllocation 的 requested/unallocated 非法: requested="
                + requestedMilli
                + " unallocated="
                + unallocatedMilli);
      }
      long allocated = 0L;
      for (CarrierChoice choice : choices) {
        allocated = Math.addExact(allocated, choice.quantityMilli());
      }
      if (allocated != requestedMilli - unallocatedMilli) {
        throw new IllegalArgumentException(
            "CarrierAllocation 不守恒: allocated="
                + allocated
                + " requested="
                + requestedMilli
                + " unallocated="
                + unallocatedMilli);
      }
    }

    /** 已分配总量（毫商品）= {@code requestedMilli − unallocatedMilli}。 */
    public long allocatedMilli() {
      return requestedMilli - unallocatedMilli;
    }

    /** 一条都没分出去（该格没有跑商家户 / 半径外 / 运力耗尽）。 */
    public boolean isEmpty() {
      return choices.isEmpty();
    }
  }
}

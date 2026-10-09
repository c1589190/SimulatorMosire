package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
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
 * ★★ <b>M-A1：逐 hex 运力池（派生量，一轮一份、瞬态、不落状态）</b>。
 *
 * <p>★★ <b>它取代了什么</b>：旧 {@code MerchantSettlement.CarrierPool}（"货主挑商号"：按服务半径 + 有效到货费率序 挑 {@code
 * MerchantFirm}，容量就地扣、周期末把 {@code lastFee/upkeep/profit} 写回持久组件）。用户 2026-10-10 裁定 （设计书 §11/§14）：商号
 * = <b>提供运力的家户</b>；运力是<b>每轮算出来的派生量</b>；分配按<b>提供方的市场议价权</b>； 商号行（{@code MerchantFirm} / {@code
 * EconomyData.merchantFirms}）整体退役。
 *
 * <p>★★ <b>冻结口径（逐条落点）</b>：
 *
 * <pre>
 * J-A 「选择跑商」本身就是提供运力 ⇒ 成员判据 = effectivePositionIds() 里含 modeId == merchant 的位置
 * J-C 运力池 = 逐 hex 汇总该格"选了跑商"的家户的运力（{@link MerchantCapacity}）
 * ③  该格运力总量 ⇒ 该格跨格商品的硬上限（本类 select 的 Σ 分配量 ≤ 该格运力总量）
 * ④  分配顺序 = 提供方市场议价权（占比‰ 降序 → 家户 id 升序；I7 确定性）
 * G-2 运力池挂**发货格**（select 的 from）
 * </pre>
 *
 * <p>★★ <b>一轮一份、不累积</b>：每次装配都从当刻状态重算（{@code 不落状态、不储存、不转卖}）。{@code select} 就地扣 剩余运力 ⇒
 * 本类<b>只允许协调器单线程使用</b>；并行 worker 副本拿 {@link #empty()}（与旧 CarrierPool 同款约定）。
 *
 * <p>★★ <b>缺省中性（冻结项 8）</b>：世界里没有"选了跑商的家户" ⇒ 池为空 ⇒ {@link #hasCapacityAt} 恒 false ⇒ 跨格（跨区 +
 * 同区跨格）路线在候选生成处就<b>不建</b>、具名 {@code LOGISTICS_CAPACITY}；<b>同 hex 成交一字不动</b> （它不走运力）。
 */
public final class MerchantCapacityPool {

  /** 运力池日志（market 分类：它的生命周期就是市场轮）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.market();

  /** 工具商品（V-22：跑商消耗走**商品账**，不动 {@code AssetKind.TOOL} 产权份额）。 */
  public static final CommodityId TOOL_COMMODITY =
      new CommodityId(EconomyVocabulary.TOOL_COMMODITY_ID);

  /** 空池（没有跑商家户的世界 / worker 本地副本）。 */
  private static final MerchantCapacityPool EMPTY =
      new MerchantCapacityPool(Map.of(), Map.of(), Map.of(), 0L);

  /** 逐格池（键序 = hex 的规范序；值按家户 id 升序、不可变）。 */
  private final Map<HexCoord, List<Entry>> byHex;

  /** 逐格运力总量（毫商品；与 {@link #byHex} 同键集）。 */
  private final Map<HexCoord, Long> totalCapacityByHex;

  /** 有运力的家户数（INFO 汇总用）。 */
  private final long householdCount;

  /** 家户 → 它的池条目（{@link #recordFee} 用；键域 = 上面所有条目的家户）。 */
  private final Map<HouseholdId, Entry> byHousehold;

  private MerchantCapacityPool(
      Map<HexCoord, List<Entry>> byHex,
      Map<HexCoord, Long> totalCapacityByHex,
      Map<HouseholdId, Entry> byHousehold,
      long householdCount) {
    this.byHex = byHex;
    this.totalCapacityByHex = totalCapacityByHex;
    this.byHousehold = byHousehold;
    this.householdCount = householdCount;
  }

  public static MerchantCapacityPool empty() {
    return EMPTY;
  }

  /**
   * ★★ <b>装配一轮的运力池</b>（唯一入口；每轮重算，不累积）。
   *
   * <p>成员判据（J-A）：{@code classStandings} 里该家户的 {@code effectivePositionIds()} 含任一 {@code
   * classPositions} 中 {@code modeId == merchant} 的位置。运力算式见 {@link MerchantCapacity}。
   *
   * @param classStandings 家户 → 阶层归属（主业 = current、副业 = participating）
   * @param classPositions 位置 → 生产方式（判"是不是跑商"）
   * @param rows 家户行（劳动投入 + 所在格）
   * @param goods 会话商品账（读 {@code tool} 可投入量；没有商品账时传空表 ⇒ 工具项 = 0，见 J-2 下界）
   */
  public static MerchantCapacityPool of(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    Objects.requireNonNull(classStandings, "classStandings");
    Objects.requireNonNull(classPositions, "classPositions");
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(goods, "goods");
    List<HouseholdId> households = new ArrayList<>(rows.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    Map<HexCoord, List<Entry>> pool = new LinkedHashMap<>();
    Map<HexCoord, Long> totals = new LinkedHashMap<>();
    long counted = 0L;
    for (HouseholdId household : households) {
      HouseholdEconomy row = rows.get(household);
      if (row == null || !selectsMerchant(classStandings.get(household), classPositions)) {
        continue;
      }
      long toolMilli = goods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
      MerchantCapacity capacity =
          MerchantCapacity.of(
              household, row.view().hex(), row.participationAdjustedLaborMilli(), toolMilli);
      if (capacity.capacityMilli() <= 0L) {
        continue; // 0 运力 = 不进池（"有这家户"与"它提供运力"是两件事）
      }
      HexCoord hex = capacity.hex();
      pool.computeIfAbsent(hex, ignored -> new ArrayList<>()).add(new Entry(capacity));
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
      // ★ 池内序 = 议价权降序 → 家户 id 升序（select 的候选序就是它；I7 内容的纯函数）。
      entries.sort(
          Comparator.comparingLong((Entry item) -> -item.sharePerMille)
              .thenComparing(item -> item.capacity.household().value()));
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
            counted);
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
                      item.capacity.serviceRadiusHex()));
        }
      }
    }
    return built;
  }

  /** 纯状态派生查询（工具维取 0 ⇒ 运力下界，J-2）：该格有没有"选了跑商且有运力"的家户。 */
  public static boolean hasCapacityAt(EconomyData base, HexCoord hex) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(hex, "hex");
    for (Map.Entry<HouseholdId, HouseholdClassMembership> entry :
        base.classStandings().entrySet()) {
      HouseholdEconomy row = base.classes().get(entry.getKey());
      if (row == null || !row.view().hex().equals(hex)) {
        continue;
      }
      if (!selectsMerchant(entry.getValue(), base.classPositions())) {
        continue;
      }
      if (MerchantCapacity.laborCapacityMilli(row.participationAdjustedLaborMilli()) > 0L) {
        return true;
      }
    }
    return false;
  }

  /** 家户是否"选了跑商"（主业或副业含 merchant 生产方式的位置）。 */
  private static boolean selectsMerchant(
      HouseholdClassMembership standing, Map<ClassPositionId, ProductionRole> positions) {
    if (standing == null || positions == null) {
      return false;
    }
    for (ClassPositionId positionId : standing.effectivePositionIds()) {
      ProductionRole position = positions.get(positionId);
      if (position != null && DefaultProductionModes.MERCHANT.equals(position.modeId())) {
        return true;
      }
    }
    return false;
  }

  /** 池里一条家户运力（可变剩余量；只允许协调器单线程触碰）。 */
  private static final class Entry {
    private final MerchantCapacity capacity;
    private long remainingMilli;
    private long allocatedMilli;
    private long allocationCount;
    private long sharePerMille;

    /** ★ 本轮的运费实收（按币分列）—— **每轮算出的读数**，只进日志/读口，不落状态（冻结项 6 的"不落状态"）。 */
    private final Map<CurrencyId, Long> earnedByCurrency = new LinkedHashMap<>();

    private Entry(MerchantCapacity capacity) {
      this.capacity = capacity;
      this.remainingMilli = capacity.capacityMilli();
    }
  }

  public boolean isEmpty() {
    return byHex.isEmpty();
  }

  /** 这一格有没有运力（静态：装配时的运力 > 0；被本轮别处吃光不影响本判据 —— 那由 select/executeTrade 判）。 */
  public boolean hasCapacityAt(HexCoord hex) {
    return byHex.containsKey(hex);
  }

  /** 这一格的运力总量（毫商品；没有 ⇒ 0）。 */
  public long totalCapacityAt(HexCoord hex) {
    return totalCapacityByHex.getOrDefault(hex, 0L);
  }

  /** 有运力的家户数（INFO 汇总用）。 */
  public long householdCount() {
    return householdCount;
  }

  /** 有运力的格数（INFO 汇总用）。 */
  public int hexCount() {
    return byHex.size();
  }

  /**
   * ★★ <b>按提供方市场议价权分配一条 lane 的运力</b>（G-1/G-2/④）。
   *
   * <pre>
   * 候选 = 发货格 from 的池 ∩（剩余运力 > 0）∩（lane 长度 ≤ 该家户派生半径）
   * 序   = 占比‰ 降序 → 家户 id 升序（I7）
   * 分配 = 逐条 min(剩余需求, 该户剩余运力)，就地扣减
   * 不变量 Σ choices.quantityMilli + unallocated == requested，且 Σ ≤ 该格运力总量
   * </pre>
   *
   * <p>★ 排序用"占比‰"（整数）而不是容量本身：这正是"市场议价权"的口径（G-1）。占比相同的两条按家户 id 升序 （同占比不许由哈希/插入序决定）。
   *
   * @param from 发货格（运力池所在的格）
   * @param to 收货格（判半径）
   * @param quantityMilli 本笔请求承运量（毫商品）
   */
  public CarrierAllocation select(HexCoord from, HexCoord to, long quantityMilli) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (quantityMilli < 0L) {
      throw new IllegalArgumentException("承运需求不得为负: " + quantityMilli);
    }
    List<Entry> pool = byHex.getOrDefault(from, List.of());
    List<CarrierChoice> choices = new ArrayList<>();
    long demandLeft = quantityMilli;
    long unreachableMilli = 0L;
    for (Entry item : pool) {
      if (demandLeft <= 0L) {
        break;
      }
      if (!item.capacity.servesLane(to)) {
        unreachableMilli = Math.addExact(unreachableMilli, item.remainingMilli);
        continue;
      }
      if (item.remainingMilli <= 0L) {
        continue;
      }
      long take = Math.min(demandLeft, item.remainingMilli);
      item.remainingMilli -= take;
      item.allocatedMilli = Math.addExact(item.allocatedMilli, take);
      item.allocationCount++;
      demandLeft -= take;
      choices.add(
          new CarrierChoice(
              item.capacity.carrier(),
              item.capacity.household(),
              item.capacity.hex(),
              item.capacity.tier(),
              take));
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
                  "hexTotalCapacityMilli",
                  totalCapacityByHex.getOrDefault(from, 0L),
                  "outOfRadiusCapacityMilli",
                  unreachableMilli,
                  "reason",
                  pool.isEmpty()
                      ? "no-merchant-household-in-shipping-hex"
                      : "capacity-exhausted-or-out-of-derived-radius"));
    }
    return new CarrierAllocation(choices, quantityMilli, demandLeft);
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
    long usedTotal = 0L;
    long allocationsTotal = 0L;
    for (Map.Entry<HexCoord, List<Entry>> entry : byHex.entrySet()) {
      long used = 0L;
      long allocations = 0L;
      Map<CurrencyId, Long> earned = new LinkedHashMap<>();
      for (Entry item : entry.getValue()) {
        used = Math.addExact(used, item.allocatedMilli);
        allocations = Math.addExact(allocations, item.allocationCount);
        for (Map.Entry<CurrencyId, Long> leg : item.earnedByCurrency.entrySet()) {
          earned.merge(leg.getKey(), leg.getValue(), Math::addExact);
        }
      }
      usedTotal = Math.addExact(usedTotal, used);
      allocationsTotal = Math.addExact(allocationsTotal, allocations);
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
                  used,
                  "allocations",
                  allocations,
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
                "allocatedMilli",
                usedTotal,
                "allocations",
                allocationsTotal));
  }

  /** ★ 一条分配结果（承运条目）。 */
  public record CarrierChoice(
      ActorRef carrier,
      HouseholdId household,
      HexCoord hex,
      MerchantPolicy.MerchantTier tier,
      long quantityMilli) {

    public CarrierChoice {
      Objects.requireNonNull(carrier, "carrier");
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(tier, "tier");
      if (quantityMilli <= 0L) {
        throw new IllegalArgumentException("CarrierChoice.quantityMilli 必须为正: " + quantityMilli);
      }
    }
  }

  /**
   * ★★ <b>一次 select 的完整结果</b>：分给了哪些提供者、各多少、以及没分出去的剩余需求。
   *
   * <p>不变量：{@code Σ choices.quantityMilli + unallocatedMilli == requestedMilli}（A3 退化为"分配总量 ≤
   * 运力总量"）。{@code unallocatedMilli} 必须由调用方显式处理（记 {@code LOGISTICS_CAPACITY} 与"被运力截断量"）， 本类不静默丢。
   */
  public record CarrierAllocation(
      List<CarrierChoice> choices, long requestedMilli, long unallocatedMilli) {

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

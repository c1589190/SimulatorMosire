package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.ShipmentBatch;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ★★ <b>D-024 / 2026-10-06 设计 §3.1：逐（市场区 × 商品）的预期需求簿</b>（纯函数；只读入参，不写状态）。
 *
 * <pre>
 * consumerNeed[c]  = Σ_{该区家户} row.naturalNeeds[c] × horizonDays        // 自然需求（当天口径 × 视界）
 * inputNeed[c]     = Σ_{该区现有 unit} recipe.inputPerUnit[c] × 计划规模 × ceil(horizonDays / cycleDays)
 * unfilledBuyer[c] = 最新一轮 MarketReport.unfilled（buyerSide && commodity==c && 该区）的 quantity 之和
 * stock[c]         = 该区家户账 + 经营者账的该商品余额之和
 * inTransit[c]     = 到该区（按 topology 区归属）、commodity==c、arrivalTick > day 的在途批次数量
 * externalDemand[c]= 最新一轮 MarketReport.routes() 里起始区为该区的 demandMilli 之和
 * addressable[c]   = max(0, max(unfilledBuyer[c],
 *                               consumerNeed[c] + inputNeed[c] + externalDemand[c] − stock[c] − inTransit[c]))
 * </pre>
 *
 * <p>★★ <b>为什么取 max 而不是相加</b>：上一轮买方未成交额已经过真实价格与购买力的筛选；再与自然需求相加会重复计同一批需求。两者取大，
 * 既保留"有报告时用真实筛选后的需求"，又在没有报告时退回基本需求残差。
 *
 * <p>★★ <b>"最新一轮"的读法</b>：调用方传入的是本周期逐日报告（保序）。本类只取<b>最后一条非 null 报告</b>作为"上一轮"——
 * 逐日累加会把一周期的缺口与路线需求重复乘上天数（报告是每日发生额，不是周期累计），那正是本仓 §9.4 "没核统计窗口"的同族错误。
 *
 * <p>★★ <b>区归属</b>：用 {@link MarketTopology#regionOf(HexCoord)} 的等价成员表（构造期已保证每个市场格都有归属；退化单格拓扑
 * 每格自成一区）。不在拓扑里的 hex 按"自身一区"兜底，绝不静默把它并到别的区。
 *
 * <p>★★ <b>确定性</b>：全部遍历按 hex (q,r) / household id / unit id / shipment id / commodity value 的规范序；
 * 无随机、无时钟、无 UUID；同一输入两次 build 逐值相同。
 */
public final class MarketDemandBook {

  private MarketDemandBook() {}

  /** 一条 (市场区 × 商品) 的需求读数（不可变；所有数量为毫商品）。 */
  public record Demand(
      HexCoord marketHex,
      CommodityId commodity,
      long consumerNeedMilli,
      long inputNeedMilli,
      long unfilledBuyerMilli,
      long stockMilli,
      long inTransitMilli,
      long externalDemandMilli,
      long addressableMilli,
      List<String> evidence) {

    public Demand {
      Objects.requireNonNull(marketHex, "Demand.marketHex 不得为 null");
      Objects.requireNonNull(commodity, "Demand.commodity 不得为 null");
      Objects.requireNonNull(evidence, "Demand.evidence 不得为 null（没有来源给空表）");
      if (consumerNeedMilli < 0L
          || inputNeedMilli < 0L
          || unfilledBuyerMilli < 0L
          || stockMilli < 0L
          || inTransitMilli < 0L
          || externalDemandMilli < 0L
          || addressableMilli < 0L) {
        throw new IllegalArgumentException(
            "Demand 的各项数量不得为负: "
                + consumerNeedMilli
                + "/"
                + inputNeedMilli
                + "/"
                + unfilledBuyerMilli
                + "/"
                + stockMilli
                + "/"
                + inTransitMilli
                + "/"
                + externalDemandMilli
                + "/"
                + addressableMilli);
      }
      List<String> copy = new ArrayList<>(evidence.size());
      for (String source : evidence) {
        if (source == null || source.isBlank()) {
          throw new IllegalArgumentException("Demand.evidence 不得含 null/空白项");
        }
        copy.add(source);
      }
      evidence = Collections.unmodifiableList(copy); // ★ 冻在赋值处
    }
  }

  /**
   * 一整本需求簿。
   *
   * <p>★ {@code byMarketHex} 的键 = 该市场区的<b>每个成员 hex</b>（含集散节点），值 = 同一份"区级"需求表 ⇒
   * 调用方可以直接用任意一个格去查它所在区的需求，不必自己再做一次归属。
   */
  public record Book(
      Map<HexCoord, Map<CommodityId, Demand>> byMarketHex, long day, long horizonDays) {

    public Book {
      Objects.requireNonNull(byMarketHex, "Book.byMarketHex 不得为 null");
      if (day < 0L) {
        throw new IllegalArgumentException("Book.day 不得为负: " + day);
      }
      if (horizonDays < 1L) {
        throw new IllegalArgumentException("Book.horizonDays 必须 ≥ 1: " + horizonDays);
      }
      Map<HexCoord, Map<CommodityId, Demand>> outer = new LinkedHashMap<>();
      for (Map.Entry<HexCoord, Map<CommodityId, Demand>> entry : byMarketHex.entrySet()) {
        HexCoord hex = Objects.requireNonNull(entry.getKey(), "byMarketHex 的键不得为 null");
        Map<CommodityId, Demand> inner = Objects.requireNonNull(entry.getValue(), "需求表不得为 null");
        Map<CommodityId, Demand> copy = new LinkedHashMap<>();
        for (Map.Entry<CommodityId, Demand> demand : inner.entrySet()) {
          copy.put(
              Objects.requireNonNull(demand.getKey(), "需求表的商品键不得为 null"),
              Objects.requireNonNull(demand.getValue(), "需求值不得为 null"));
        }
        outer.put(hex, Collections.unmodifiableMap(copy)); // ★ 冻在赋值处
      }
      byMarketHex = Collections.unmodifiableMap(outer); // ★ 冻在赋值处
    }

    /** 空簿（没有拓扑/没有商品时的合法形态）。 */
    public static Book empty(long day, long horizonDays) {
      return new Book(Map.of(), day, horizonDays);
    }

    /** 该格（所在区）该商品的可寻址需求（毫商品）；未知格/未知商品 ⇒ 0（"真的没有需求"，不是"读不到"）。 */
    public long addressable(HexCoord hex, CommodityId commodity) {
      Demand demand = demandOrNull(hex, commodity);
      return demand == null ? 0L : demand.addressableMilli();
    }

    /** 该格（所在区）该商品的路线外部需求（毫商品）；未知格/未知商品 ⇒ 0。 */
    public long externalDemand(HexCoord hex, CommodityId commodity) {
      Demand demand = demandOrNull(hex, commodity);
      return demand == null ? 0L : demand.externalDemandMilli();
    }

    /** 该格（所在区）全部商品的路线外部需求之和（毫商品）；未知格 ⇒ 0。 */
    public long externalDemandTotal(HexCoord hex) {
      long total = 0L;
      for (Demand demand : demandsIn(hex).values()) {
        total = Math.addExact(total, demand.externalDemandMilli());
      }
      return total;
    }

    /** 该格（所在区）全部商品的上一轮买方未成交之和（毫商品）；未知格 ⇒ 0。 */
    public long unfilledBuyerTotal(HexCoord hex) {
      long total = 0L;
      for (Demand demand : demandsIn(hex).values()) {
        total = Math.addExact(total, demand.unfilledBuyerMilli());
      }
      return total;
    }

    private Demand demandOrNull(HexCoord hex, CommodityId commodity) {
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(commodity, "commodity");
      return demandsIn(hex).get(commodity);
    }

    private Map<CommodityId, Demand> demandsIn(HexCoord hex) {
      return byMarketHex.getOrDefault(hex, Map.of());
    }
  }

  /**
   * ★★ 构建需求簿（唯一公开静态入口；只读入参、不写状态、同输入同输出）。
   *
   * @param reports 本周期逐日市场报告（保序；可为空表；只取最后一条非 null 作"上一轮"）
   * @param topology 区域拓扑（区归属的唯一来源；退化单格拓扑每格自成一区）
   * @param rows 家户表（自然需求的来源）
   * @param units 生产单元表（生产引致需求的来源）
   * @param industries 产业模板（配方）
   * @param shares 实物资产份额（判 unit 的产能规模）
   * @param shipments 在途批次
   * @param accounts 账户会话（家户/经营者库存；只读）
   * @param markets 市场表（有市场的格参与区归属）
   * @param day 当前世界日（在途判据 {@code arrivalTick > day}）
   * @param horizonDays 视界（天）；必须 ≥ 1
   */
  public static Book build(
      List<MarketReport> reports,
      MarketTopology topology,
      Map<HouseholdId, ClassRow> rows,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<IndustryId, Industry> industries,
      Map<AssetShareId, AssetShare> shares,
      Map<ShipmentId, ShipmentBatch> shipments,
      AccountSession accounts,
      Map<HexCoord, Market> markets,
      long day,
      long horizonDays) {
    Objects.requireNonNull(reports, "reports");
    Objects.requireNonNull(topology, "topology");
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(industries, "industries");
    Objects.requireNonNull(shares, "shares");
    Objects.requireNonNull(shipments, "shipments");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(markets, "markets");
    if (day < 0L) {
      throw new IllegalArgumentException("MarketDemandBook.build 的 day 不得为负: " + day);
    }
    if (horizonDays < 1L) {
      throw new IllegalArgumentException(
          "MarketDemandBook.build 的 horizonDays 必须 ≥ 1: " + horizonDays);
    }
    // ★ 每次调用只取一次活视图（AccountSession 的视图是协调器线程专用；本方法在结算协调器线程里跑）。
    Map<HouseholdId, Map<CommodityId, Long>> householdGoods = accounts.householdGoods();
    Map<ActorRef, Map<CommodityId, Long>> operatorGoods = accounts.operatorGoods();

    Map<HexCoord, HexCoord> regionAnchorByHex = regionAnchors(topology, markets);
    // ★ 覆盖全部会用到的 hex：家户、unit（产业 id 里的格）、市场、在途收货格、报告里的格。
    Set<CommodityId> commodities = commodityUniverse(rows, units, industries, householdGoods, operatorGoods, shipments, markets);
    MarketReport latest = latestReport(reports);
    addReportCommodities(commodities, latest);

    // 逐区聚合累加器（[consumer, input, unfilled, stock, inTransit, external]）；按 anchor (q,r) 规范序。
    Map<HexCoord, Map<CommodityId, long[]>> byRegion = new TreeMap<>(HEX_ORDER);
    Map<ActorRef, HexCoord> anchorByActor = anchorByActor(rows, units);

    // ★ 同一 actor 可能同时登记了家户账与经营者账（同键 = 同一本账）⇒ 按账户键去重，库存只计一次。
    Set<AccountPartitionKey> countedAccountKeys = new LinkedHashSet<>();
    for (HouseholdId household : sortedHouseholds(rows)) {
      ClassRow row = rows.get(household);
      HexCoord anchor = anchorOf(regionAnchorByHex, row.view().hex());
      for (Map.Entry<CommodityId, Long> need : row.naturalNeeds().entrySet()) {
        long[] consumer = bucketOf(byRegion, anchor, need.getKey());
        consumer[0] = Math.addExact(consumer[0], Math.multiplyExact(need.getValue(), horizonDays));
      }
      AccountPartitionKey key = accounts.householdKeyOf(household);
      if (key != null) {
        countedAccountKeys.add(key);
      }
      Map<CommodityId, Long> goods = householdGoods.getOrDefault(household, Map.of());
      for (Map.Entry<CommodityId, Long> stock : goods.entrySet()) {
        long[] stockBucket = bucketOf(byRegion, anchor, stock.getKey());
        stockBucket[3] = Math.addExact(stockBucket[3], stock.getValue());
      }
    }

    for (ProductionUnitId unitId : sortedUnits(units)) {
      ProductionUnit unit = units.get(unitId);
      Industry industry = industries.get(unit.industry());
      HexCoord unitHex = unitHexOrNull(unit);
      if (unitHex == null) {
        continue; // 产业 id 里没有格键 ⇒ 不猜地点（该 unit 不计入任何区的引致需求/库存）
      }
      HexCoord anchor = anchorOf(regionAnchorByHex, unitHex);
      if (industry == null) {
        continue; // 认不出模板 ⇒ 不猜配方（该 unit 的引致需求记 0；读数组件可另判 "missing-industry"）
      }
      long scale = ProductionUnitBook.capacityScaleOf(unit, industry, shares);
      long cycles = ceilDiv(horizonDays, industry.cycleDays());
      for (Map.Entry<CommodityId, Long> input : industry.recipe().inputPerUnit().entrySet()) {
        if (input.getValue() <= 0L || scale <= 0L) {
          continue;
        }
        long amount = Math.multiplyExact(Math.multiplyExact(input.getValue(), scale), cycles);
        long[] inputBucket = bucketOf(byRegion, anchor, input.getKey());
        inputBucket[1] = Math.addExact(inputBucket[1], amount);
      }
    }
    // ★ 经营者账：按 (unit/家户) 反查到的格归区；聚合主体没有 unit 行且不是家户 ⇒ 跳过（不猜区）。
    //   已作为家户账计过的同键账户在这里跳过（同一本账不得加两次）。
    for (ActorRef actor : sortedActors(operatorGoods.keySet())) {
      HexCoord anchor = anchorByActor.get(actor);
      if (anchor == null) {
        continue;
      }
      AccountPartitionKey key = accounts.actorKeyOrNull(actor);
      if (key != null && !countedAccountKeys.add(key)) {
        continue;
      }
      for (Map.Entry<CommodityId, Long> stock : operatorGoods.getOrDefault(actor, Map.of()).entrySet()) {
        long[] stockBucket = bucketOf(byRegion, anchor, stock.getKey());
        stockBucket[3] = Math.addExact(stockBucket[3], stock.getValue());
      }
    }

    for (ShipmentId shipmentId : sortedShipments(shipments)) {
      ShipmentBatch shipment = shipments.get(shipmentId);
      if (shipment == null || shipment.arrivalTick() <= day) {
        continue;
      }
      HexCoord anchor = anchorOf(regionAnchorByHex, shipment.route().to());
      long[] transitBucket = bucketOf(byRegion, anchor, shipment.commodity());
      transitBucket[4] = Math.addExact(transitBucket[4], shipment.quantity());
    }

    if (latest != null) {
      for (MarketReport.Unfilled unfilled : latest.unfilled()) {
        if (!unfilled.buyerSide()) {
          continue;
        }
        HexCoord anchor = anchorOf(regionAnchorByHex, unfilled.hex());
        long[] unfilledBucket = bucketOf(byRegion, anchor, unfilled.commodity());
        unfilledBucket[2] = Math.addExact(unfilledBucket[2], unfilled.quantity());
      }
      for (MarketReport.RouteUsage route : latest.routes()) {
        HexCoord anchor = anchorOf(regionAnchorByHex, route.from());
        long[] routeBucket = bucketOf(byRegion, anchor, route.commodity());
        routeBucket[5] = Math.addExact(routeBucket[5], route.demandMilli());
      }
    }

    Map<HexCoord, Map<CommodityId, Demand>> demandsByAnchor = new TreeMap<>(HEX_ORDER);
    for (Map.Entry<HexCoord, Map<CommodityId, long[]>> region : byRegion.entrySet()) {
      Map<CommodityId, Demand> demands = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, long[]> commodity : region.getValue().entrySet()) {
        long[] acc = commodity.getValue();
        long needs = Math.addExact(Math.addExact(acc[0], acc[1]), acc[5]);
        long supply = Math.addExact(acc[3], acc[4]);
        long residual = Math.max(0L, Math.subtractExact(needs, supply));
        long addressable = Math.max(acc[2], residual);
        if (acc[0] == 0L
            && acc[1] == 0L
            && acc[2] == 0L
            && acc[3] == 0L
            && acc[4] == 0L
            && acc[5] == 0L) {
          continue; // 全零不是需求读数（查不到即 0，不制造满表的空行）
        }
        demands.put(
            commodity.getKey(),
            new Demand(
                region.getKey(),
                commodity.getKey(),
                acc[0],
                acc[1],
                acc[2],
                acc[3],
                acc[4],
                acc[5],
                addressable,
                evidenceOf(acc, latest)));
      }
      if (!demands.isEmpty()) {
        demandsByAnchor.put(region.getKey(), Collections.unmodifiableMap(demands));
      }
    }

    // ★ 键覆盖：拓扑成员 + 市场格 + 任何在聚合里出现过（但不属于任何区）的格 —— 后者按"自身一区"如实给读数，
    //   不把它静默并到别的区、也不把已算出的需求丢掉。
    Set<HexCoord> demandHexes = new TreeSet<>(HEX_ORDER);
    demandHexes.addAll(regionAnchorByHex.keySet());
    demandHexes.addAll(demandsByAnchor.keySet());
    Map<HexCoord, Map<CommodityId, Demand>> byMarketHex = new LinkedHashMap<>();
    for (HexCoord hex : demandHexes) {
      HexCoord anchor = anchorOf(regionAnchorByHex, hex);
      byMarketHex.put(hex, demandsByAnchor.getOrDefault(anchor, Collections.emptyMap()));
    }
    return new Book(byMarketHex, day, horizonDays);
  }

  // ── 小工具 ────────────────────────────────────────────────────────────────────────────────

  private static final Comparator<HexCoord> HEX_ORDER =
      Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r);

  /**
   * 每个 hex → 它所属市场区的 anchor。来源 = 拓扑成员表（{@code regionOf} 的等价物，且对不在拓扑里的市场格兜底为自身一区） +
   * 市场表键。迭代序不参与结果（只做查表），故用 HashMap。
   */
  private static Map<HexCoord, HexCoord> regionAnchors(
      MarketTopology topology, Map<HexCoord, Market> markets) {
    Map<HexCoord, HexCoord> anchors = new HashMap<>();
    for (MarketRegion region : topology.regions()) {
      for (HexCoord member : region.members()) {
        anchors.putIfAbsent(member, region.anchor());
      }
    }
    for (HexCoord hex : markets.keySet()) {
      anchors.putIfAbsent(hex, hex); // 拓扑构造期已为无归属市场格建单格区；这里是防坏输入的兜底
    }
    return anchors;
  }

  /** 该 hex 的区 anchor；不在拓扑/市场表里 ⇒ 自身一区（不并到别的区）。 */
  private static HexCoord anchorOf(Map<HexCoord, HexCoord> regionAnchorByHex, HexCoord hex) {
    Objects.requireNonNull(hex, "hex");
    return regionAnchorByHex.getOrDefault(hex, hex);
  }

  private static Map<ActorRef, HexCoord> anchorByActor(
      Map<HouseholdId, ClassRow> rows, Map<ProductionUnitId, ProductionUnit> units) {
    Map<ActorRef, HexCoord> anchors = new HashMap<>();
    for (ClassRow row : rows.values()) {
      anchors.putIfAbsent(HouseholdActors.of(row.id()), row.view().hex());
    }
    for (ProductionUnit unit : units.values()) {
      HexCoord hex = unitHexOrNull(unit);
      if (hex != null) {
        anchors.putIfAbsent(unit.operator(), hex);
      }
    }
    return anchors;
  }

  /** unit 的地点（产业 id 里的格键）；拿不到 ⇒ null（不猜坐标）。 */
  private static HexCoord unitHexOrNull(ProductionUnit unit) {
    return IndustryHexKeys.hexKeyOf(unit.industry()).map(HexCoord::parse).orElse(null);
  }

  private static Set<CommodityId> commodityUniverse(
      Map<HouseholdId, ClassRow> rows,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<IndustryId, Industry> industries,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ShipmentId, ShipmentBatch> shipments,
      Map<HexCoord, Market> markets) {
    Set<CommodityId> commodities = new TreeSet<>(Comparator.comparing(CommodityId::value));
    for (ClassRow row : rows.values()) {
      commodities.addAll(row.naturalNeeds().keySet());
    }
    for (ProductionUnit unit : units.values()) {
      Industry industry = industries.get(unit.industry());
      if (industry != null) {
        commodities.addAll(industry.recipe().inputPerUnit().keySet());
        commodities.addAll(industry.recipe().outputPerUnit().keySet());
      }
    }
    for (Map<CommodityId, Long> goods : householdGoods.values()) {
      commodities.addAll(goods.keySet());
    }
    for (Map<CommodityId, Long> goods : operatorGoods.values()) {
      commodities.addAll(goods.keySet());
    }
    for (ShipmentBatch shipment : shipments.values()) {
      commodities.add(shipment.commodity());
    }
    for (Market market : markets.values()) {
      commodities.addAll(market.prices().keySet());
    }
    return commodities;
  }

  private static void addReportCommodities(Set<CommodityId> commodities, MarketReport report) {
    if (report == null) {
      return;
    }
    for (MarketReport.Unfilled unfilled : report.unfilled()) {
      commodities.add(unfilled.commodity());
    }
    for (MarketReport.RouteUsage route : report.routes()) {
      commodities.add(route.commodity());
    }
  }

  private static MarketReport latestReport(List<MarketReport> reports) {
    MarketReport latest = null;
    for (MarketReport report : reports) {
      if (report != null) {
        latest = report;
      }
    }
    return latest;
  }

  private static long[] bucketOf(
      Map<HexCoord, Map<CommodityId, long[]>> byRegion, HexCoord anchor, CommodityId commodity) {
    Objects.requireNonNull(commodity, "commodity");
    return byRegion
        .computeIfAbsent(anchor, ignored -> new LinkedHashMap<>())
        .computeIfAbsent(commodity, ignored -> new long[6]);
  }

  private static List<String> evidenceOf(long[] acc, MarketReport latest) {
    List<String> evidence = new ArrayList<>();
    if (acc[2] > 0L) {
      evidence.add("lastReport");
    }
    if (acc[0] > 0L || acc[1] > 0L) {
      evidence.add("fundamental");
    }
    if (acc[5] > 0L) {
      evidence.add("route");
    }
    if (evidence.size() > 1) {
      evidence.add("mixed");
    }
    if (evidence.isEmpty()) {
      evidence.add("fundamental");
    }
    if (latest == null) {
      evidence.add("noReport");
    }
    return evidence;
  }

  private static long ceilDiv(long numerator, long denominator) {
    if (denominator <= 0L) {
      throw new IllegalArgumentException("ceilDiv 的分母必须 > 0: " + denominator);
    }
    return Math.floorDiv(numerator + denominator - 1L, denominator);
  }

  private static List<HouseholdId> sortedHouseholds(Map<HouseholdId, ClassRow> rows) {
    List<HouseholdId> keys = new ArrayList<>(rows.keySet());
    keys.sort(Comparator.comparing(HouseholdId::value));
    return keys;
  }

  private static List<ProductionUnitId> sortedUnits(Map<ProductionUnitId, ProductionUnit> units) {
    List<ProductionUnitId> keys = new ArrayList<>(units.keySet());
    keys.sort(Comparator.comparing(ProductionUnitId::value));
    return keys;
  }

  private static List<ShipmentId> sortedShipments(Map<ShipmentId, ShipmentBatch> shipments) {
    List<ShipmentId> keys = new ArrayList<>(shipments.keySet());
    keys.sort(Comparator.comparing(ShipmentId::value));
    return keys;
  }

  private static List<ActorRef> sortedActors(Set<ActorRef> actors) {
    List<ActorRef> keys = new ArrayList<>(actors);
    keys.sort(Comparator.comparing(ActorRef::toString));
    return keys;
  }

}

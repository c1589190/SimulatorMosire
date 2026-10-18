package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★★ <b>D-032 P3d：M3 土地意向的唯一职责类</b>（商品意向仍由 {@link MarketIntentBook} 负责）。
 *
 * <p>本类只把“生产位置的土地需求 / 本轮未使用的自有土地”转成整块 {@link MarketIntent}（LAND BUY / LAND
 * SELL）。它<b>不</b>生成订单、不写库存/货币/土地、不碰撮合器；订单仍由 M4 的同一个统一入口处理。
 *
 * <pre>
 * 需求（BUY）：
 *   ① 已入选生产位置：requiredLand = probe.assetRequired().get(LAND)
 *      → 与“本户在该 hex 当前可用 LAND”比较，正缺口才生成整块买盘；
 *   ② 无地户：按该 hex 的农业生产定义（capacityPerUnit[LAND] / laborPerUnit / outputPerUnit /
 *      inputPerUnit + 市场区价格）估一个最小整块的预期净收益，正收益且现金够付区级地价才生成买盘。
 *      ★ 只读生产定义与市场价格，不复制 production runtime 的效率/经验公式。
 * 供给（SELL）：
 *   本轮已入选 unit/roster 引用的 LAND rightsStakes（+ 等价事实：unit 所在 hex、组织者名下、覆盖
 *   requiredLand 的整块）视为“使用中”；其余自有 LAND 整块自动挂卖。
 *   卖出正在使用的地块不在自动路径里；外部显式挂卖仍由撮合层按 M6 失地校验处理。
 * </pre>
 *
 * <p>★ <b>价格/预算</b>：一律用该 hex 所属市场区的 {@link MarketTable#landPrice()}（empty 不生成、0 合法）；
 * 买盘 {@code budgetMilli} 只给本户该币种当刻余额，撮合时再取商品冻结后的剩余可花额（{@link
 * MarketTradeExecutor#executeLandTrade} 的 {@code spendableMoneyOf}）⇒ 土地永远吃商品之后的剩余预算。
 *
 * <p>★ <b>本轮新取得土地</b>（{@link SettlementDayContext#landAcquiredThisRound()}）：既不计入“当前可用”，
 * 也不生成卖单（M2/M4 已规定本轮不可再卖）。整块不拆分、不找零、不伪造。
 */
final class MarketLandIntentBook {

  private MarketLandIntentBook() {}

  /** M3 的土地意向入口（由 {@link MarketIntentBook#marketIntents} 在商品意向之后追加）。 */
  static List<MarketIntent> landIntents(
      SettlementDayContext ctx, List<ProductionPlanBridge.PositionFacts> chosenFacts) {
    MarketIndex markets = MarketIndex.of(ctx.markets, ctx.base.marketZones());
    Map<ProductionUnitId, ProductionPlanBridge.PositionFacts> unitFacts =
        firstFactPerUnit(chosenFacts);
    Set<AssetShareId> usedLandStakes = usedLandStakes(ctx, unitFacts);
    Map<HouseholdId, Map<HexCoord, Long>> deficits = landDeficits(ctx, unitFacts);
    List<SellBlock> sellBlocks = new ArrayList<>();
    List<MarketIntent> intents = new ArrayList<>();
    collectSellIntents(ctx, markets, usedLandStakes, deficits, sellBlocks, intents);
    collectDeficitBuyIntents(ctx, deficits, sellBlocks, intents);
    collectLandlessBuyIntents(ctx, sellBlocks, deficits, intents);
    logLandIntents(ctx, deficits, sellBlocks, intents);
    return intents;
  }

  // ── 生产位置 → 土地需求 ─────────────────────────────────────────────────────────────────

  private static Map<ProductionUnitId, ProductionPlanBridge.PositionFacts> firstFactPerUnit(
      List<ProductionPlanBridge.PositionFacts> chosenFacts) {
    Map<ProductionUnitId, ProductionPlanBridge.PositionFacts> byUnit = new LinkedHashMap<>();
    for (ProductionPlanBridge.PositionFacts fact : chosenFacts) {
      byUnit.putIfAbsent(fact.position().unit(), fact);
    }
    return byUnit;
  }

  /**
   * 每个已入选土地型 unit：{@code requiredLand = probe.assetRequired().get(LAND)}，按 organizer×hex 汇总
   * （同一 hex 多个 unit 累加 —— 同一块地不能被两份需求重复计）。
   */
  private static Map<HouseholdId, Map<HexCoord, Long>> requiredLandByHouseholdHex(
      SettlementDayContext ctx,
      Map<ProductionUnitId, ProductionPlanBridge.PositionFacts> unitFacts) {
    Map<HouseholdId, Map<HexCoord, Long>> required = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionPlanBridge.PositionFacts> entry :
        unitFacts.entrySet()) {
      ProductionProcess unit = ctx.units.get(entry.getKey());
      if (unit == null) {
        continue;
      }
      IndustryDefinitionView industry = ctx.industries.get(unit.plan());
      if (industry == null || !ProductionPlanBridge.isLandBased(industry)) {
        continue;
      }
      HexCoord hex = ProductionPlanBridge.hexOf(unit.location());
      if (hex == null) {
        continue;
      }
      long requiredLand =
          entry.getValue().probe().assetRequired().getOrDefault(AssetKind.LAND, 0L);
      if (requiredLand <= 0L) {
        continue;
      }
      required
          .computeIfAbsent(entry.getValue().organizer(), ignored -> new LinkedHashMap<>())
          .merge(hex, requiredLand, Math::addExact);
    }
    return required;
  }

  /**
   * 生产位置缺口：{@code requiredLand − 当前可用土地}，正缺口才进返回表。当前可用土地见 {@link
   * #usableOwnedLandAt}。
   */
  private static Map<HouseholdId, Map<HexCoord, Long>> landDeficits(
      SettlementDayContext ctx,
      Map<ProductionUnitId, ProductionPlanBridge.PositionFacts> unitFacts) {
    Map<HouseholdId, Map<HexCoord, Long>> required =
        requiredLandByHouseholdHex(ctx, unitFacts);
    Map<HouseholdId, Map<HexCoord, Long>> deficits = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Map<HexCoord, Long>> byHousehold : required.entrySet()) {
      HouseholdId household = byHousehold.getKey();
      for (Map.Entry<HexCoord, Long> byHex : byHousehold.getValue().entrySet()) {
        long usable = usableOwnedLandAt(ctx, household, byHex.getKey());
        long deficit = byHex.getValue() - usable;
        if (deficit > 0L) {
          deficits
              .computeIfAbsent(household, ignored -> new LinkedHashMap<>())
              .put(byHex.getKey(), deficit);
        }
      }
    }
    return deficits;
  }

  // ── 未使用土地 → 供给 ───────────────────────────────────────────────────────────────────

  /**
   * 本轮“使用中”的 LAND 块：
   *
   * <ul>
   *   <li>已入选 unit 的 roster 里每个 participant 的 {@code rightsStakes}（LAND 才算）；
   *   <li>等价事实：unit 所在 hex、组织者名下、按 share id 稳定序累计到 {@code requiredLand} 的整块
   *       （roster 还没有 rightsStakes 的旧档/Seeder 也能识别“这块地在支撑本轮生产”）。
   * </ul>
   */
  private static Set<AssetShareId> usedLandStakes(
      SettlementDayContext ctx,
      Map<ProductionUnitId, ProductionPlanBridge.PositionFacts> unitFacts) {
    Set<AssetShareId> used = new LinkedHashSet<>();
    Map<ProductionUnitId, ProductionRoster> persisted =
        ctx.session.sheet().productionRostersOrBase();
    for (Map.Entry<ProductionUnitId, ProductionPlanBridge.PositionFacts> entry :
        unitFacts.entrySet()) {
      ProductionProcess unit = ctx.units.get(entry.getKey());
      if (unit == null) {
        continue;
      }
      IndustryDefinitionView industry = ctx.industries.get(unit.plan());
      if (industry == null || !ProductionPlanBridge.isLandBased(industry)) {
        continue;
      }
      ProductionRoster roster =
          ProductionPlanBridge.effectiveRoster(ctx, unit, industry, persisted);
      if (roster == null) {
        continue;
      }
      for (ProductionParticipant participant : roster.participants()) {
        for (AssetShareId shareId : participant.rightsStakes()) {
          OwnershipStake share = ctx.assetShares.get(shareId);
          if (share != null && share.asset() == AssetKind.LAND) {
            used.add(shareId);
          }
        }
      }
    }
    // 等价事实：按 organizer×hex 汇总的需求，一次性把该 hex 上组织者名下的整块按 share id 稳定序
    // 累计到 requiredLand（同一 hex 多 unit 不重复计；required=0 时保守地把该 hex 全部自有块视为使用中）。
    for (Map.Entry<HouseholdId, Map<HexCoord, Long>> byHousehold :
        requiredLandByHouseholdHex(ctx, unitFacts).entrySet()) {
      ActorRef organizer = HouseholdActors.of(byHousehold.getKey());
      for (Map.Entry<HexCoord, Long> byHex : byHousehold.getValue().entrySet()) {
        long requiredLand = byHex.getValue();
        long covered = 0L;
        for (OwnershipStake share : sortedLandAt(ctx, organizer, byHex.getKey())) {
          if (requiredLand > 0L && covered >= requiredLand) {
            break;
          }
          if (ctx.landAcquiredThisRound().contains(share.id())) {
            continue;
          }
          used.add(share.id());
          covered = Math.addExact(covered, share.quantity());
        }
      }
    }
    return used;
  }

  private static void collectSellIntents(
      SettlementDayContext ctx,
      MarketIndex markets,
      Set<AssetShareId> usedLandStakes,
      Map<HouseholdId, Map<HexCoord, Long>> deficits,
      List<SellBlock> sellBlocks,
      List<MarketIntent> intents) {
    List<OwnershipStake> landShares = new ArrayList<>();
    for (OwnershipStake share : ctx.assetShares.values()) {
      if (share.asset() == AssetKind.LAND && share.quantity() > 0L) {
        landShares.add(share);
      }
    }
    landShares.sort(Comparator.comparing(share -> share.id().value()));
    for (OwnershipStake share : landShares) {
      if (usedLandStakes.contains(share.id())
          || ctx.landAcquiredThisRound().contains(share.id())) {
        continue;
      }
      HouseholdId owner = ctx.householdOfActor.get(share.owner());
      if (owner == null || !ctx.householdEconomies.containsKey(owner)) {
        continue;
      }
      HexCoord hex = MarketLandBook.hexOf(share);
      if (deficits.getOrDefault(owner, Map.of()).containsKey(hex)) {
        // 该 hex 尚缺地还去买：不把同一户的自有块当“未使用”卖出去（避免自相矛盾的自动清仓）。
        continue;
      }
      MarketTable market = marketAt(markets, hex);
      if (market == null || !market.hasLandPrice()) {
        continue;
      }
      long landPrice = market.landPrice().getAsLong();
      sellBlocks.add(new SellBlock(share, hex, market, landPrice));
      intents.add(
          new MarketIntent(
              MarketIntentKind.LAND,
              MarketIntentSide.SELL,
              HouseholdActors.of(owner),
              hex,
              Optional.empty(),
              Optional.of(share.id()),
              share.quantity(),
              landPrice,
              market.numeraire(),
              0L,
              2,
              Optional.empty()));
    }
  }

  // ── 买盘：已选位置缺口 + 无地户最小整块 ──────────────────────────────────────────────────

  private static void collectDeficitBuyIntents(
      SettlementDayContext ctx,
      Map<HouseholdId, Map<HexCoord, Long>> deficits,
      List<SellBlock> sellBlocks,
      List<MarketIntent> intents) {
    for (HouseholdId household : sortedHouseholds(deficits.keySet())) {
      ActorRef actor = HouseholdActors.of(household);
      Map<HexCoord, Long> byHex = deficits.get(household);
      List<HexCoord> hexes = new ArrayList<>(byHex.keySet());
      hexes.sort(
          Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
      for (HexCoord hex : hexes) {
        long deficit = byHex.get(hex);
        long covered = 0L;
        for (SellBlock block : sellBlocksAt(sellBlocks, hex, actor)) {
          if (covered >= deficit) {
            break;
          }
          if (!affordable(ctx, household, block)) {
            continue;
          }
          intents.add(buyIntent(ctx, household, block));
          covered = Math.addExact(covered, block.share().quantity());
        }
      }
    }
  }

  private static void collectLandlessBuyIntents(
      SettlementDayContext ctx,
      List<SellBlock> sellBlocks,
      Map<HouseholdId, Map<HexCoord, Long>> deficits,
      List<MarketIntent> intents) {
    for (HouseholdId household : sortedHouseholds(ctx.householdEconomies.keySet())) {
      if (deficits.containsKey(household)) {
        continue; // “少地”已由已选位置缺口路径覆盖，避免重复买盘
      }
      HouseholdEconomy row = ctx.householdEconomies.get(household);
      if (row == null || row.laborMilli() <= 0L) {
        continue;
      }
      ActorRef actor = HouseholdActors.of(household);
      HexCoord home = row.view().hex();
      // “无地/少地”在本批的可执行口径 = 本户在 home hex 没有任何已拥有的 LAND（含本轮新取得，
      // 本轮新取得也不能用 ⇒ 不重复加买）；已能入选生产位置但地不够的家户走上面的缺口路径。
      if (ownsLandAt(ctx, household, home)) {
        continue;
      }
      for (SellBlock block : sellBlocksAt(sellBlocks, home, actor)) {
        if (!affordable(ctx, household, block)
            || !positiveFarmNet(ctx, household, block)) {
          continue;
        }
        intents.add(buyIntent(ctx, household, block));
        break; // “最小整块”：一个无地户本轮至多挂一块买盘。
      }
    }
  }

  /**
   * 无地户最小整块正收益口径：只用生产定义 + 市场区价格，不复制 production runtime 的效率/经验公式。
   *
   * <pre>
   * units          = blockQuantity ÷ capacityPerUnit[LAND]
   * revenue        = bid(outputPerUnit × units)
   * inputCost      = ask(inputPerUnit × units)
   * laborPerCycle  = laborPerUnit × units 必须 ≤ 家户劳动预算 × cycleDays
   * 正收益          = revenue &gt; inputCost
   * </pre>
   */
  private static boolean positiveFarmNet(
      SettlementDayContext ctx,
      HouseholdId household,
      SellBlock block) {
    HouseholdEconomy row = ctx.householdEconomies.get(household);
    if (row == null || ctx.hexToIndustries == null) {
      return false;
    }
    List<IndustryId> industryIds =
        new ArrayList<>(
            ctx.hexToIndustries.getOrDefault(
                IndustryHexKeys.hexKey(block.hex().q(), block.hex().r()), List.of()));
    industryIds.sort(Comparator.comparing(IndustryId::value));
    for (IndustryId industryId : industryIds) {
      IndustryDefinitionView industry = ctx.industries.get(industryId);
      if (industry == null || !ProductionPlanBridge.isLandBased(industry)) {
        continue;
      }
      try {
        long capacityPerLand = industry.capacityPerUnit().getOrDefault(AssetKind.LAND, 0L);
        if (capacityPerLand <= 0L) {
          continue;
        }
        long units = block.share().quantity() / capacityPerLand;
        if (units <= 0L) {
          continue;
        }
        long cycleDays = Math.max(1L, industry.cycleDays());
        long laborPerCycle = Math.multiplyExact(industry.laborPerUnit(), units);
        if (industry.laborPerUnit() > 0L
            && laborPerCycle > Math.multiplyExact(row.laborMilli(), cycleDays)) {
          continue;
        }
        long revenue =
            ProductionPlanBridge.bidValueMilli(
                block.market(), scaled(industry.outputPerUnit(), units));
        long inputCost =
            ProductionPlanBridge.askValueMilli(
                block.market(), scaled(industry.inputPerUnit(), units));
        if (revenue > inputCost) {
          return true;
        }
      } catch (ArithmeticException overflow) {
        // 溢出不是“正收益”的证据：fail-closed 跳过该定义。
      }
    }
    return false;
  }

  // ── 事实读取/工具 ───────────────────────────────────────────────────────────────────────

  private static MarketTable marketAt(MarketIndex markets, HexCoord hex) {
    return hex == null ? null : markets.at(hex);
  }

  /** 本户在该 hex 是否已拥有/经营任何 LAND（含本轮新取得；不看是否可用 —— 用于“无地户”判据）。 */
  private static boolean ownsLandAt(
      SettlementDayContext ctx, HouseholdId household, HexCoord hex) {
    ActorRef actor = HouseholdActors.of(household);
    for (OwnershipStake share : ctx.assetShares.values()) {
      if (share.asset() == AssetKind.LAND
          && share.quantity() > 0L
          && MarketLandBook.hexOf(share).equals(hex)
          && (share.owner().equals(actor) || share.operator().equals(actor))) {
        return true;
      }
    }
    return false;
  }

  private static long usableOwnedLandAt(
      SettlementDayContext ctx, HouseholdId household, HexCoord hex) {
    ActorRef actor = HouseholdActors.of(household);
    long total = 0L;
    for (OwnershipStake share : ctx.assetShares.values()) {
      if (share.asset() != AssetKind.LAND
          || share.quantity() <= 0L
          || !MarketLandBook.hexOf(share).equals(hex)
          || ctx.landAcquiredThisRound().contains(share.id())) {
        continue;
      }
      if (share.owner().equals(actor) || share.operator().equals(actor)) {
        total = Math.addExact(total, share.quantity());
      }
    }
    return total;
  }

  private static List<OwnershipStake> sortedLandAt(
      SettlementDayContext ctx, ActorRef owner, HexCoord hex) {
    List<OwnershipStake> matches = new ArrayList<>();
    for (OwnershipStake share : ctx.assetShares.values()) {
      if (share.asset() == AssetKind.LAND
          && share.quantity() > 0L
          && share.owner().equals(owner)
          && MarketLandBook.hexOf(share).equals(hex)
          && !ctx.landAcquiredThisRound().contains(share.id())) {
        matches.add(share);
      }
    }
    matches.sort(Comparator.comparing(share -> share.id().value()));
    return matches;
  }

  private static List<SellBlock> sellBlocksAt(
      List<SellBlock> sellBlocks, HexCoord hex, ActorRef buyer) {
    List<SellBlock> matches = new ArrayList<>();
    for (SellBlock block : sellBlocks) {
      if (block.hex().equals(hex) && !block.share().owner().equals(buyer)) {
        matches.add(block);
      }
    }
    matches.sort(
        Comparator.comparingLong((SellBlock block) -> block.share().quantity())
            .thenComparing(block -> block.share().id().value()));
    return matches;
  }

  private static boolean affordable(
      SettlementDayContext ctx, HouseholdId household, SellBlock block) {
    long payment = MarketLandBook.landValueMilli(block.share().quantity(), block.landPrice());
    long money = ProductionPlanBridge.moneyOf(ctx, household, block.market().numeraire());
    return money >= payment;
  }

  private static MarketIntent buyIntent(
      SettlementDayContext ctx, HouseholdId household, SellBlock block) {
    long money = ProductionPlanBridge.moneyOf(ctx, household, block.market().numeraire());
    return new MarketIntent(
        MarketIntentKind.LAND,
        MarketIntentSide.BUY,
        HouseholdActors.of(household),
        block.hex(),
        Optional.empty(),
        Optional.of(block.share().id()),
        block.share().quantity(),
        block.landPrice(),
        block.market().numeraire(),
        money,
        2,
        Optional.empty());
  }

  private static Map<CommodityId, Long> scaled(
      Map<CommodityId, Long> perUnit, long units) {
    Map<CommodityId, Long> scaled = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : perUnit.entrySet()) {
      long quantity = Math.multiplyExact(entry.getValue(), units);
      if (quantity > 0L) {
        scaled.put(entry.getKey(), quantity);
      }
    }
    return scaled;
  }

  private static List<HouseholdId> sortedHouseholds(Set<HouseholdId> households) {
    List<HouseholdId> sorted = new ArrayList<>(households);
    sorted.sort(Comparator.comparing(HouseholdId::value));
    return sorted;
  }

  private static void logLandIntents(
      SettlementDayContext ctx,
      Map<HouseholdId, Map<HexCoord, Long>> deficits,
      List<SellBlock> sellBlocks,
      List<MarketIntent> intents) {
    if (!EconomyLog.settlement().isInfoEnabled()) {
      return;
    }
    if (intents.isEmpty()) {
      if (EconomyLog.settlement().isDebugEnabled()
          && (!sellBlocks.isEmpty() || !deficits.isEmpty())) {
        EventLog.channel(EconomyLog.settlement())
            .debug(
                LogEvent.of(
                    "D032_P3_LAND_INTENTS",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    ctx.day,
                    "landIntents",
                    0,
                    "sellBlocks",
                    sellBlocks.size(),
                    "deficitHouseholds",
                    deficits.size(),
                    "reason",
                    "no-affordable-or-positive-return-land-demand"));
      }
      return;
    }
    long buys = intents.stream().filter(i -> i.side() == MarketIntentSide.BUY).count();
    long sells = intents.size() - buys;
    EventLog.channel(EconomyLog.settlement())
        .info(
            LogEvent.of(
                "D032_P3_LAND_INTENTS",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.day,
                "landIntents",
                intents.size(),
                "landBuys",
                buys,
                "landSells",
                sells,
                "sellBlocks",
                sellBlocks.size(),
                "deficitHouseholds",
                deficits.size(),
                "reason",
                "land-demand-from-production-positions-plus-unused-land-supply"));
  }

  /** 一张待撮合的土地卖块（M3 内部只读事实；不落账、不进任何持久状态）。 */
  record SellBlock(OwnershipStake share, HexCoord hex, MarketTable market, long landPrice) {}
}

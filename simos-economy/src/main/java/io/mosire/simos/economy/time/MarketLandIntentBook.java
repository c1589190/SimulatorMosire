package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.land.LandLedgerState;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * ★★★ <b>D-032 RA1b2c2：M3 标量 LAND 意向的唯一职责类</b>（商品意向仍由 {@link MarketIntentBook} 负责）。
 *
 * <p>本类只把“生产位置的土地需求 / 本轮未使用的自有土地”转成标量 {@link MarketIntent}（LAND BUY / LAND SELL）： <b>按 {@code
 * (actor, LAND, hex, side)} 合并、数量累加、限价取更严格值</b>。它<b>不</b>生成订单、不写库存/货币/土地、不碰撮合器； 订单仍由 M4 的同一个统一入口处理。
 *
 * <pre>
 * 需求（BUY）：
 *   ① 已入选生产位置：requiredLand = probe.assetRequired().get(LAND)
 *      → 与“本户在该 hex 当前可用 landHoldings（持有 − 本轮已取得）”比较，正缺口才生成买盘；
 *   ② 无地/少地户：若该 hex 有土地型生产定义，买盘数量 = max(capacityPerUnit[LAND],
 *      minLandPerPersonMilliMu)。minLandPerPerson 与 S7-B 共用同一动态口径（Social naturalNeeds + production 配方），
 *      保证一次买盘足够覆盖至少一名迁移人口；不做跨市场轮的临时 LAND 累计。
 *      ★ 不以当前现金/库存/正收益作为挂单门：无供给只是 unmet，不记债；实际支付在撮合层走现金 + 普通货币债。
 * 供给（SELL）：
 *   本轮预期未使用的自有土地 = usable landHoldings − 已入选生产位置覆盖的 (hex,owner) 数量；
 *   本轮新取得土地按数量扣减，不进入卖盘。
 * </pre>
 *
 * <p>★★ <b>RF1 价格/现金口径</b>：市场区必须有 {@link MarketTable#landPrice()}（empty 不生成、0 合法）。BUY 是<b>数量型价格
 * 接受者</b> —— 意向形状不带买价，撮合给任何非负地价都接受；现金不足由 {@link MarketTradeExecutor#executeLandTrade}
 * 的既有普通货币债分支处理，数量不得折成现金可买量。SELL 侧最低保护价仍取该区地价。
 */
final class MarketLandIntentBook {

  private MarketLandIntentBook() {}

  /** M3 的土地意向入口（由 {@link MarketIntentBook#marketIntents} 在商品意向之后追加）。 */
  static List<MarketIntent> landIntents(
      SettlementDayContext ctx, List<ProductionPlanBridge.PositionFacts> chosenFacts) {
    LandLedgerState ledger =
        LandLedgerState.from(
            ctx.session.sheet().landArableSupply(), ctx.session.sheet().landHoldingsOrBase());
    Map<LandHoldingKey, Long> acquired = ctx.landAcquiredQuantityThisRound();
    Map<ProductionUnitId, ProductionPlanBridge.PositionFacts> unitFacts =
        firstFactPerUnit(chosenFacts);
    Map<HouseholdId, Map<HexCoord, Long>> required = requiredLandByHouseholdHex(ctx, unitFacts);
    MarketIndex markets = MarketIndex.of(ctx.markets, ctx.base.marketZones());

    Map<String, Draft> drafts = new LinkedHashMap<>();
    collectSellDrafts(ctx, markets, ledger, required, acquired, drafts);
    collectDeficitBuyDrafts(ctx, markets, ledger, required, acquired, drafts);
    collectLandlessBuyDrafts(ctx, markets, ledger, required, acquired, drafts);
    netOutSameActorHex(ctx, drafts);
    List<MarketIntent> intents = materialize(ctx, drafts);
    logLandIntents(ctx, required, ledger, intents);
    return intents;
  }

  /**
   * ★ 同一 actor 同一 hex 若同时出现自动买/卖（净额化）：只保留净方向；净额为 0 ⇒ 两单都不留。
   *
   * <p>★ 正常构造下 {@code unused>0} 与 {@code gap>0} 互斥，本步骤是防御式收口；具名 INFO，不静默做 actor 级全删。
   */
  private static void netOutSameActorHex(SettlementDayContext ctx, Map<String, Draft> drafts) {
    Map<String, List<Draft>> byActorHex = new LinkedHashMap<>();
    for (Draft draft : drafts.values()) {
      String actorHex = draft.household.value() + "|" + draft.hex.q() + "," + draft.hex.r();
      byActorHex.computeIfAbsent(actorHex, ignored -> new ArrayList<>()).add(draft);
    }
    List<String> remove = new ArrayList<>();
    for (List<Draft> pair : byActorHex.values()) {
      if (pair.size() < 2) {
        continue;
      }
      Draft buy = null;
      Draft sell = null;
      for (Draft draft : pair) {
        if (draft.side == MarketIntentSide.BUY) {
          buy = draft;
        } else {
          sell = draft;
        }
      }
      if (buy == null || sell == null) {
        continue;
      }
      long net = sell.quantityMilliMu - buy.quantityMilliMu;
      String buyKey = draftKey(buy);
      String sellKey = draftKey(sell);
      if (net > 0L) {
        sell.quantityMilliMu = net;
        remove.add(buyKey);
      } else if (net < 0L) {
        buy.quantityMilliMu = -net;
        remove.add(sellKey);
      } else {
        remove.add(buyKey);
        remove.add(sellKey);
      }
      if (EconomyLog.settlement().isInfoEnabled()) {
        EventLog.channel(EconomyLog.settlement())
            .info(
                LogEvent.of(
                    "LAND_INTENT_NETTED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    ctx.day,
                    "household",
                    buy.household.value(),
                    "hex",
                    buy.hex.q() + "," + buy.hex.r(),
                    "buyQuantityMilliMu",
                    buy.quantityMilliMu,
                    "sellQuantityMilliMu",
                    sell.quantityMilliMu,
                    "netDirection",
                    net > 0L ? "SELL" : net < 0L ? "BUY" : "NONE",
                    "netQuantityMilliMu",
                    Math.abs(net),
                    "reason",
                    "same-actor-same-hex-auto-land-orders-netted-not-actor-level-deleted"));
      }
    }
    for (String key : remove) {
      drafts.remove(key);
    }
  }

  private static String draftKey(Draft draft) {
    return draft.household.value()
        + "|"
        + draft.hex.q()
        + ","
        + draft.hex.r()
        + "|"
        + draft.side.name();
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
   * 每个已入选土地型 unit：{@code requiredLand = probe.assetRequired().get(LAND)}，按 organizer×hex 汇总 （同一 hex
   * 多个 unit 累加 —— 同一块地不能被两份需求重复计）。
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
      long requiredLand = entry.getValue().probe().assetRequired().getOrDefault(AssetKind.LAND, 0L);
      if (requiredLand <= 0L) {
        continue;
      }
      required
          .computeIfAbsent(entry.getValue().organizer(), ignored -> new LinkedHashMap<>())
          .merge(hex, requiredLand, Math::addExact);
    }
    return required;
  }

  // ── 未使用土地 → 供给 ───────────────────────────────────────────────────────────────────

  private static void collectSellDrafts(
      SettlementDayContext ctx,
      MarketIndex markets,
      LandLedgerState ledger,
      Map<HouseholdId, Map<HexCoord, Long>> required,
      Map<LandHoldingKey, Long> acquired,
      Map<String, Draft> drafts) {
    List<LandHolding> holdings = new ArrayList<>(ledger.holdingsByKey().values());
    holdings.sort(Comparator.comparing(LandHolding::key));
    for (LandHolding holding : holdings) {
      HouseholdId owner = holding.owner();
      if (!ctx.householdEconomies.containsKey(owner)) {
        continue;
      }
      HexCoord hex = holding.hex();
      MarketTable market = marketAt(markets, hex);
      if (market == null || !market.hasLandPrice()) {
        continue;
      }
      long usable = ledger.usableQuantity(hex, owner, acquired);
      long needed = required.getOrDefault(owner, Map.of()).getOrDefault(hex, 0L);
      long unused = Math.max(0L, usable - needed);
      if (unused > 0L) {
        addDraft(
            ctx,
            drafts,
            MarketIntentSide.SELL,
            owner,
            hex,
            unused,
            // ★ SELL 侧最低保护价 = 该区当前地价（语义不变）。
            OptionalLong.of(market.landPrice().getAsLong()),
            market.numeraire());
      }
    }
  }

  // ── 买盘：已选位置缺口 + 无地户最小一份 ──────────────────────────────────────────────────

  private static void collectDeficitBuyDrafts(
      SettlementDayContext ctx,
      MarketIndex markets,
      LandLedgerState ledger,
      Map<HouseholdId, Map<HexCoord, Long>> required,
      Map<LandHoldingKey, Long> acquired,
      Map<String, Draft> drafts) {
    for (HouseholdId household : sortedHouseholds(required.keySet())) {
      Map<HexCoord, Long> byHex = required.get(household);
      List<HexCoord> hexes = new ArrayList<>(byHex.keySet());
      hexes.sort(HexCoord::compareTo);
      for (HexCoord hex : hexes) {
        MarketTable market = marketAt(markets, hex);
        if (market == null || !market.hasLandPrice()) {
          continue;
        }
        long usable = usableAt(ledger, hex, household, acquired);
        long gap = byHex.get(hex) - usable;
        if (gap > 0L) {
          addDraft(
              ctx,
              drafts,
              MarketIntentSide.BUY,
              household,
              hex,
              gap,
              // ★ BUY 是价格接受者：数量只由真实缺口决定，不带买价。
              OptionalLong.empty(),
              market.numeraire());
        }
      }
    }
  }

  private static void collectLandlessBuyDrafts(
      SettlementDayContext ctx,
      MarketIndex markets,
      LandLedgerState ledger,
      Map<HouseholdId, Map<HexCoord, Long>> required,
      Map<LandHoldingKey, Long> acquired,
      Map<String, Draft> drafts) {
    for (HouseholdId household : sortedHouseholds(ctx.householdEconomies.keySet())) {
      if (required.containsKey(household)) {
        continue; // “少地”已由已选位置缺口路径覆盖，避免重复买盘
      }
      HouseholdEconomy row = ctx.householdEconomies.get(household);
      if (row == null || row.laborMilli() <= 0L) {
        continue;
      }
      HexCoord home = row.view().hex();
      MarketTable market = marketAt(markets, home);
      if (market == null || !market.hasLandPrice()) {
        continue;
      }
      if (usableAt(ledger, home, household, acquired) > 0L) {
        continue; // 本户在 home hex 已有可用土地，不伪造“无地户”需求
      }
      long minimumBlock = minimumLandCapacityPerUnit(ctx, home);
      long minLandPerPerson = PostLandTradeClassCheck.minLandPerPersonMilliMu(ctx, household, home);
      long quantity = Math.max(minimumBlock, minLandPerPerson);
      if (quantity <= 0L) {
        continue;
      }
      addDraft(
          ctx,
          drafts,
          MarketIntentSide.BUY,
          household,
          home,
          quantity,
          // ★ BUY 是价格接受者：数量只由真实缺口决定，不带买价。
          OptionalLong.empty(),
          market.numeraire());
    }
  }

  /** 该 hex 土地型生产定义的 {@code capacityPerUnit[LAND]} 最小一份；没有土地型定义 ⇒ 0。 */
  private static long minimumLandCapacityPerUnit(SettlementDayContext ctx, HexCoord hex) {
    if (ctx.hexToIndustries == null || hex == null) {
      return 0L;
    }
    List<IndustryId> industryIds =
        new ArrayList<>(
            ctx.hexToIndustries.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of()));
    industryIds.sort(Comparator.comparing(IndustryId::value));
    long minimum = Long.MAX_VALUE;
    for (IndustryId industryId : industryIds) {
      IndustryDefinitionView industry = ctx.industries.get(industryId);
      if (industry == null || !ProductionPlanBridge.isLandBased(industry)) {
        continue;
      }
      long capacityPerLand = industry.capacityPerUnit().getOrDefault(AssetKind.LAND, 0L);
      if (capacityPerLand > 0L) {
        minimum = Math.min(minimum, capacityPerLand);
      }
    }
    return minimum == Long.MAX_VALUE ? 0L : minimum;
  }

  // ── 合并 / 物化 ─────────────────────────────────────────────────────────────────────────

  /**
   * ★ 自动订单按 {@code (actor, LAND, hex, side)} 合并：同 key 只保留一笔，数量 {@link Math#addExact} 累加；SELL
   * 限价不一致时取 更严格（更高）值并具名 INFO。BUY 没有限价（价格接受者），因此合并时不需要也不允许折价。不允许 actor 级全删。
   */
  private static void addDraft(
      SettlementDayContext ctx,
      Map<String, Draft> drafts,
      MarketIntentSide side,
      HouseholdId household,
      HexCoord hex,
      long quantityMilliMu,
      OptionalLong limitPricePerMu,
      CurrencyId currency) {
    String key = household.value() + "|" + hex.q() + "," + hex.r() + "|" + side.name();
    Draft existing = drafts.get(key);
    if (existing == null) {
      drafts.put(key, new Draft(side, household, hex, quantityMilliMu, limitPricePerMu, currency));
      return;
    }
    existing.quantityMilliMu = Math.addExact(existing.quantityMilliMu, quantityMilliMu);
    if (side == MarketIntentSide.SELL
        && (existing.limitPricePerMu.isEmpty()
            || limitPricePerMu.isEmpty()
            || existing.limitPricePerMu.getAsLong() != limitPricePerMu.getAsLong())) {
      long previous = existing.limitPricePerMu.orElse(Long.MIN_VALUE);
      long next = limitPricePerMu.orElse(Long.MIN_VALUE);
      long stricter = Math.max(previous, next);
      if (EconomyLog.settlement().isInfoEnabled()) {
        EventLog.channel(EconomyLog.settlement())
            .info(
                LogEvent.of(
                    "LAND_INTENT_MERGED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    ctx.day,
                    "household",
                    household.value(),
                    "hex",
                    hex.q() + "," + hex.r(),
                    "side",
                    side.name(),
                    "previousLimitPricePerMu",
                    existing.limitPricePerMu.isPresent()
                        ? existing.limitPricePerMu.getAsLong()
                        : "price-taker",
                    "newLimitPricePerMu",
                    limitPricePerMu.isPresent() ? limitPricePerMu.getAsLong() : "price-taker",
                    "stricterLimitPricePerMu",
                    stricter,
                    "quantityMilliMu",
                    existing.quantityMilliMu,
                    "reason",
                    "same-actor-hex-side-land-intents-merged-with-stricter-limit"));
      }
      existing.limitPricePerMu = OptionalLong.of(stricter);
    }
  }

  private static List<MarketIntent> materialize(
      SettlementDayContext ctx, Map<String, Draft> drafts) {
    List<Draft> ordered = new ArrayList<>(drafts.values());
    ordered.sort(
        Comparator.comparing((Draft draft) -> draft.hex)
            .thenComparing(draft -> draft.side.ordinal())
            .thenComparing(draft -> draft.household.value()));
    List<MarketIntent> intents = new ArrayList<>(ordered.size());
    for (Draft draft : ordered) {
      intents.add(
          new MarketIntent(
              MarketIntentKind.LAND,
              draft.side,
              HouseholdActors.of(draft.household),
              draft.hex,
              Optional.empty(),
              draft.quantityMilliMu,
              draft.limitPricePerMu,
              draft.currency,
              2,
              Optional.empty()));
      if (draft.side == MarketIntentSide.BUY) {
        logLandBuyIntent(ctx, draft);
      }
    }
    return intents;
  }

  // ── 事实读取/工具 ───────────────────────────────────────────────────────────────────────

  private static MarketTable marketAt(MarketIndex markets, HexCoord hex) {
    return hex == null || markets == null ? null : markets.at(hex);
  }

  /** 可用土地：持有 − 本轮已取得；不在土地供应表里的 hex ⇒ 0（不抛，非农 hex 没有土地）。 */
  private static long usableAt(
      LandLedgerState ledger,
      HexCoord hex,
      HouseholdId household,
      Map<LandHoldingKey, Long> acquired) {
    if (hex == null || !ledger.arableSupply().containsHex(hex)) {
      return 0L;
    }
    return ledger.usableQuantity(hex, household, acquired);
  }

  private static List<HouseholdId> sortedHouseholds(Set<HouseholdId> households) {
    List<HouseholdId> sorted = new ArrayList<>(households);
    sorted.sort(Comparator.comparing(HouseholdId::value));
    return sorted;
  }

  private static void logLandIntents(
      SettlementDayContext ctx,
      Map<HouseholdId, Map<HexCoord, Long>> required,
      LandLedgerState ledger,
      List<MarketIntent> intents) {
    if (!EconomyLog.settlement().isInfoEnabled()) {
      return;
    }
    long buys = intents.stream().filter(i -> i.side() == MarketIntentSide.BUY).count();
    long sells = intents.size() - buys;
    if (intents.isEmpty()) {
      if (EconomyLog.settlement().isDebugEnabled()) {
        EventLog.channel(EconomyLog.settlement())
            .debug(
                LogEvent.of(
                    "D032_P3_LAND_INTENTS",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    ctx.day,
                    "landIntents",
                    0,
                    "holdings",
                    ledger.holdingsByKey().size(),
                    "requiredHouseholds",
                    required.size(),
                    "reason",
                    "no-land-demand-or-unused-supply-at-priced-markets"));
      }
      return;
    }
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
                "holdings",
                ledger.holdingsByKey().size(),
                "requiredHouseholds",
                required.size(),
                "reason",
                "scalar-land-demand-from-production-positions-plus-unused-land-supply"));
  }

  /** ★ RF1：土地 BUY 意向的生成证据（谁、哪块 hex、多少、为什么是价格接受者）—— DEBUG 逐笔，不参与任何判据。 */
  private static void logLandBuyIntent(SettlementDayContext ctx, Draft draft) {
    if (!EconomyLog.market().isDebugEnabled()) {
      return;
    }
    EventLog.channel(EconomyLog.market())
        .debug(
            LogEvent.of(
                "MARKET_INTENT_BUY",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                ctx.day,
                "actor",
                HouseholdActors.of(draft.household).id(),
                "household",
                draft.household.value(),
                "kind",
                "LAND",
                "commodity",
                "-",
                "hex",
                draft.hex.q() + "," + draft.hex.r(),
                "quantity",
                draft.quantityMilliMu,
                "priceTaker",
                true,
                "currency",
                draft.currency.value(),
                "sourceUnit",
                "-",
                "reason",
                "household-land-demand-quantity-from-real-gap-not-price-or-cash"));
  }

  /** 一张待物化的标量土地意向（M3 内部只读事实；不落账、不进任何持久状态）。 */
  private static final class Draft {
    final MarketIntentSide side;
    final HouseholdId household;
    final HexCoord hex;
    long quantityMilliMu;
    OptionalLong limitPricePerMu;
    final CurrencyId currency;

    Draft(
        MarketIntentSide side,
        HouseholdId household,
        HexCoord hex,
        long quantityMilliMu,
        OptionalLong limitPricePerMu,
        CurrencyId currency) {
      this.side = side;
      this.household = household;
      this.hex = hex;
      this.quantityMilliMu = quantityMilliMu;
      this.limitPricePerMu = limitPricePerMu;
      this.currency = currency;
    }
  }
}

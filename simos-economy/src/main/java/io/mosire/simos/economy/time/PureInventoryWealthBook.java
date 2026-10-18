package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.ProductionLocation;
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
import java.util.OptionalLong;
import org.slf4j.Logger;

/**
 * ★★ <b>P2e：纯库存财富（Pure Inventory Wealth）的唯一拼写点</b>。
 *
 * <pre>
 * wealth(household) = 钱 + 商品库存 + LAND
 *
 * 钱   = 逐币种 max(0, 余额 − 冻结) × CurrencyValuation.valuationMicro(本区 numeraire, 币种, 本区) ÷ 1000
 *        valuationMicro <= 0 ⇒ 该币种不计入并 DEBUG 具名（不得 1:1 冒充）
 * 商品 = 逐商品 max(0, 余额 − 冻结) × 本区 MarketTable.priceOf ÷ 1000
 *        只算 MarketTable.hasPrice；未定价不计入并 DEBUG 具名
 * LAND = 逐块 quantityMilliMu × 土地所属市场区 MarketTable.landPrice ÷ 1000
 *        landPrice empty ⇒ 不计入并 DEBUG；0 ⇒ 计入且价值 0
 * </pre>
 *
 * <p>★ <b>不扣债务</b>；只算 {@link io.mosire.simos.actor.api.asset.AssetKind#LAND} 这一档资产，工具/作坊/机器/船全部不计入
 * （它们不是本类读取的 {@code assetShares} 口径）。★ 输入只读：本类不改账户、不改产权、不写状态。
 *
 * <p>★ <b>快照固定</b>：M2 开始时算一次，逐户结果按 {@link HouseholdId#value()} 升序冻结；收款过程中不重排、不重算。 这是 P2e
 * 债权人序的唯一财富尺（纯库存财富降序 → 债权人 id → 债务 id）。
 */
final class PureInventoryWealthBook {

  private static final Logger DEBT = EconomyLog.debt();

  /** 每 1 个整单位（商品/亩）的毫价格换算到毫价值的分母。 */
  private static final long MILLI_PER_UNIT = 1000L;

  private PureInventoryWealthBook() {}

  /**
   * M2 开始时冻结的纯库存财富快照。
   *
   * @param wealthByHousehold 家户 → 毫 numeraire 财富（键序 = HouseholdId.value 升序；不可变）
   * @param regionByHousehold 家户 → 其所在市场区 id（没有市场区 ⇒ 缺键；不可变）
   */
  record Snapshot(
      Map<HouseholdId, Long> wealthByHousehold, Map<HouseholdId, String> regionByHousehold) {

    Snapshot {
      Objects.requireNonNull(
          wealthByHousehold, "PureInventoryWealthBook.Snapshot.wealthByHousehold");
      Objects.requireNonNull(
          regionByHousehold, "PureInventoryWealthBook.Snapshot.regionByHousehold");
      wealthByHousehold = Collections.unmodifiableMap(new LinkedHashMap<>(wealthByHousehold));
      regionByHousehold = Collections.unmodifiableMap(new LinkedHashMap<>(regionByHousehold));
    }

    long wealthOf(HouseholdId household) {
      return wealthByHousehold.getOrDefault(household, 0L);
    }

    String regionOf(HouseholdId household) {
      return regionByHousehold.get(household);
    }
  }

  /**
   * 计算并冻结 M2 的纯库存财富快照。
   *
   * @param day 结算日（仅日志；不得为负）
   * @param households 活家户表（只读）
   * @param householdGoods 家户商品库存工作副本（只读）
   * @param householdMoney 家户货币工作副本（只读）
   * @param assetShares 资产份额工作副本（只读；只取 LAND）
   * @param markets 市场区价目表（只读）
   * @param topology 市场拓扑（可为 null = 没有市场区；土地/货币的区价随之为缺）
   * @param currencyValuation 本轮行情（不得为 null；没有行情给 {@link CurrencyValuation#none()}）
   */
  static Snapshot snapshot(
      long day,
      Map<HouseholdId, HouseholdEconomy> households,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<MarketZoneId, MarketTable> markets,
      MarketTopology topology,
      CurrencyValuation currencyValuation) {
    Objects.requireNonNull(households, "PureInventoryWealthBook.households 不得为 null");
    Objects.requireNonNull(householdGoods, "PureInventoryWealthBook.householdGoods 不得为 null");
    Objects.requireNonNull(householdMoney, "PureInventoryWealthBook.householdMoney 不得为 null");
    Objects.requireNonNull(assetShares, "PureInventoryWealthBook.assetShares 不得为 null");
    Objects.requireNonNull(markets, "PureInventoryWealthBook.markets 不得为 null");
    Objects.requireNonNull(
        currencyValuation, "PureInventoryWealthBook.currencyValuation 不得为 null（给 none()）");
    if (day < 0L) {
      throw new IllegalArgumentException("PureInventoryWealthBook.snapshot 的 day 不得为负: " + day);
    }

    Map<ActorRef, List<OwnershipStake>> landByOwner = landByOwner(assetShares);
    List<HouseholdId> ordered = new ArrayList<>(households.keySet());
    ordered.sort(Comparator.comparing(HouseholdId::value));

    LinkedHashMap<HouseholdId, Long> wealth = new LinkedHashMap<>();
    LinkedHashMap<HouseholdId, String> regions = new LinkedHashMap<>();
    for (HouseholdId household : ordered) {
      HouseholdEconomy row = households.get(household);
      if (row == null) {
        continue;
      }
      HexCoord hex = row.view().hex();
      String regionId = regionIdOf(hex, topology);
      if (regionId != null) {
        regions.put(household, regionId);
      }
      MarketTable market = DebtSettlement.marketForHex(hex, markets, topology);
      long total = 0L;
      List<String> unpricedMoney = new ArrayList<>();
      List<String> unpricedGoods = new ArrayList<>();
      List<String> unpricedLand = new ArrayList<>();

      // ① 钱：本区 numeraire 计价；说不出价的币种不计（保守下界），逐项 DEBUG 具名。
      if (market != null) {
        CurrencyId numeraire = market.numeraire();
        Map<CurrencyId, Long> money = householdMoney.getOrDefault(household, Map.of());
        List<CurrencyId> currencies = new ArrayList<>(money.keySet());
        currencies.sort(Comparator.comparing(CurrencyId::value));
        for (CurrencyId currency : currencies) {
          long amount = money.getOrDefault(currency, 0L);
          if (amount <= 0L) {
            continue;
          }
          long valueMicro = currencyValuation.valuationMicro(numeraire, currency, regionId);
          if (valueMicro <= 0L) {
            unpricedMoney.add("money:" + currency.value());
            continue;
          }
          total =
              SaturatedArithmetic.add(
                  total,
                  milliValue(amount, valueMicro),
                  DEBT,
                  EconomyLogSource.ECONOMY_DEBT,
                  "pure-inventory-wealth-money",
                  "day",
                  day,
                  "household",
                  household.value(),
                  "currency",
                  currency.value());
        }
      } else {
        Map<CurrencyId, Long> money = householdMoney.getOrDefault(household, Map.of());
        List<CurrencyId> currencies = new ArrayList<>(money.keySet());
        currencies.sort(Comparator.comparing(CurrencyId::value));
        for (CurrencyId currency : currencies) {
          if (money.getOrDefault(currency, 0L) > 0L) {
            unpricedMoney.add("money:" + currency.value());
          }
        }
      }

      // ② 商品库存：只算本区市场表 hasPrice 的；未定价不计。
      Map<CommodityId, Long> goods = householdGoods.getOrDefault(household, Map.of());
      List<CommodityId> commodities = new ArrayList<>(goods.keySet());
      commodities.sort(Comparator.comparing(CommodityId::value));
      for (CommodityId commodity : commodities) {
        long quantity = goods.getOrDefault(commodity, 0L);
        if (quantity <= 0L) {
          continue;
        }
        if (market == null || !market.hasPrice(commodity)) {
          unpricedGoods.add("commodity:" + commodity.value());
          continue;
        }
        total =
            SaturatedArithmetic.add(
                total,
                milliValue(quantity, market.priceOf(commodity)),
                DEBT,
                EconomyLogSource.ECONOMY_DEBT,
                "pure-inventory-wealth-goods",
                "day",
                day,
                "household",
                household.value(),
                "commodity",
                commodity.value());
      }

      // ③ LAND：只算土地所属市场区当前地价；empty 不计，0 计入且价值 0。
      List<OwnershipStake> lands =
          landByOwner.getOrDefault(HouseholdActors.of(household), List.of());
      for (OwnershipStake land : lands) {
        MarketTable landMarket =
            DebtSettlement.marketForHex(hexOf(land.location()), markets, topology);
        if (landMarket == null || !landMarket.hasLandPrice()) {
          unpricedLand.add("land:" + land.id().value());
          continue;
        }
        OptionalLong landPrice = landMarket.landPrice();
        if (landPrice.isEmpty()) {
          unpricedLand.add("land:" + land.id().value());
          continue;
        }
        total =
            SaturatedArithmetic.add(
                total,
                milliValue(land.quantity(), landPrice.getAsLong()),
                DEBT,
                EconomyLogSource.ECONOMY_DEBT,
                "pure-inventory-wealth-land",
                "day",
                day,
                "household",
                household.value(),
                "land",
                land.id().value());
      }

      wealth.put(household, total);
      if (!unpricedMoney.isEmpty() || !unpricedGoods.isEmpty() || !unpricedLand.isEmpty()) {
        EventLog.channel(DEBT)
            .debug(
                LogEvent.of(
                    "PURE_INVENTORY_WEALTH_UNPRICED",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    day,
                    "household",
                    household.value(),
                    "unpricedMoney",
                    unpricedMoney,
                    "unpricedGoods",
                    unpricedGoods,
                    "unpricedLand",
                    unpricedLand,
                    "why",
                    "unpriced-assets-count-as-zero-conservative-lower-bound"));
      }
    }
    return new Snapshot(wealth, regions);
  }

  /** 单户纯库存财富的便利读口（不冻结多户；仅供读/测试；M2 排序仍用 {@link #snapshot} 的冻结结果）。 */
  static long wealthOf(
      HouseholdId household,
      Map<HouseholdId, HouseholdEconomy> households,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<MarketZoneId, MarketTable> markets,
      MarketTopology topology,
      CurrencyValuation currencyValuation) {
    Objects.requireNonNull(household, "PureInventoryWealthBook.wealthOf 的 household 不得为 null");
    return snapshot(
            0L,
            households,
            householdGoods,
            householdMoney,
            assetShares,
            markets,
            topology,
            currencyValuation)
        .wealthOf(household);
  }

  /** 家户 actor → 它拥有的 LAND 份额（按 AssetShareId.value 升序；只含 quantity > 0 的块）。 */
  private static Map<ActorRef, List<OwnershipStake>> landByOwner(
      Map<AssetShareId, OwnershipStake> assetShares) {
    List<OwnershipStake> ordered = new ArrayList<>(assetShares.values());
    ordered.sort(Comparator.comparing(share -> share.id().value()));
    LinkedHashMap<ActorRef, List<OwnershipStake>> byOwner = new LinkedHashMap<>();
    for (OwnershipStake share : ordered) {
      if (share.asset() != io.mosire.simos.actor.api.asset.AssetKind.LAND
          || share.quantity() <= 0L) {
        continue;
      }
      byOwner.computeIfAbsent(share.owner(), ignored -> new ArrayList<>()).add(share);
    }
    return byOwner;
  }

  /** {@code quantityMilli × pricePerWholeUnit ÷ 1000}：商品与土地同量纲，整数 floor。 */
  private static long milliValue(long quantityMilli, long pricePerWholeUnit) {
    if (quantityMilli <= 0L || pricePerWholeUnit <= 0L) {
      return 0L;
    }
    return SaturatedArithmetic.floorMultiplyDivide(
        quantityMilli,
        pricePerWholeUnit,
        MILLI_PER_UNIT,
        DEBT,
        EconomyLogSource.ECONOMY_DEBT,
        "pure-inventory-wealth-value");
  }

  /** 家户所在格 → 市场区 id；没有拓扑/没有归属 ⇒ null（合法：该格没有市场区）。 */
  private static String regionIdOf(HexCoord hex, MarketTopology topology) {
    if (topology == null || hex == null || !topology.contains(hex)) {
      return null;
    }
    return topology.regionOf(hex).node().nodeId();
  }

  /** 资产份额的规范格键 → HexCoord（{@code OwnershipStake.location} 已是规范 {@code <q>_<r>}）。 */
  private static HexCoord hexOf(ProductionLocation location) {
    return HexCoord.parse(location.value());
  }
}

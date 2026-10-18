package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/** P0.5-E1 机械拆分自 {@code ModeMigrationPolicy}；职责见类名与各方法注释。 */
final class ModeMigrationRules {

  private ModeMigrationRules() {}

  private static final long DEFAULT_DEMAND_HORIZON_DAYS = 120L;

  /** 本格价表：优先本格市场；无则退到该格所在区的集散节点市场（区内同价）；都没有 ⇒ null。 */
  static MarketTable marketOf(
      Map<MarketZoneId, MarketTable> markets, MarketTopology topology, HexCoord hex) {
    return PrimaryModeRanking.marketOf(markets, topology, hex);
  }

  /**
   * ★★ <b>M-A1：merchant 允许条件用 —— 该格有没有"选了跑商且有运力"的家户</b>（运力 0 不算）。
   *
   * <p>运力是派生量（{@link MerchantCapacity}）：成员判据 = {@code classStandings} 的 {@code
   * effectivePositionIds()} 含 merchant 生产方式的位置；本查询走纯状态口径（工具维取 0 = 运力下界，见 {@link
   * MerchantCapacityPool#hasCapacityAt(EconomyData, HexCoord)}）。旧"查商号行"的实现已随 {@code merchantFirms}
   * 退役。
   */
  static boolean hasMerchantCapacityAt(EconomyDayView base, HexCoord hex) {
    return MerchantCapacityPool.hasCapacityAt(base, hex);
  }

  /**
   * 需求簿视界：<b>世界现有 unit 所属产业 cycleDays 的最大者</b>（多个不同值时取最大并在此具名说明；本轮真实世界 所有产业都是 120）。没有 unit（或 unit
   * 模板缺失）时退回"全部产业的最大者"，再没有 ⇒ 120。
   */
  static long demandHorizonDays(
      EconomyDayView base, Map<ProductionUnitId, ProductionProcess> units) {
    long horizon = 0L;
    for (ProductionProcess unit : units.values()) {
      IndustryDefinitionView industry = base.industries().get(unit.industry());
      if (industry != null) {
        horizon = Math.max(horizon, industry.cycleDays());
      }
    }
    if (horizon > 0L) {
      return horizon;
    }
    for (IndustryDefinitionView industry : base.industries().values()) {
      if (industry != null) {
        horizon = Math.max(horizon, industry.cycleDays());
      }
    }
    return horizon <= 0L ? DEFAULT_DEMAND_HORIZON_DAYS : horizon;
  }

  /**
   * ★★ <b>该 mode 在该格能用哪些产业模板</b>（策略/执行/预期利润三处共用，避免各写一份 mode→regime 映射）：
   *
   * <ol>
   *   <li>按 mode 的默认 regime（{@link RegimeOperators#defaultRegimeForMode}）筛；有匹配 ⇒ 只回匹配的（按 id 升序）；
   *   <li>没有匹配：merchant ⇒ <b>空</b>（绝不把农场模板当商号）；其余 mode/未登记制度 ⇒ 该格全部模板（保持既有回退）。
   * </ol>
   */
  static List<IndustryId> industriesForMode(
      EconomyDayView base, HexCoord hex, ProductionModeId modeId) {
    Objects.requireNonNull(base, "base");
    return industriesForMode(base.industries(), hex, modeId);
  }

  /**
   * ★★★ <b>W7（2026-10-11）：上一条的"显式产业表"重载</b>（唯一判据逐字相同）。
   *
   * <p>★ <b>为什么要它</b>：W7 起 {@code trade} 可以在迁移时**按需创建**（写进 {@code EconomySession} 的工作副本）， 而"这一格有没有
   * merchant 模板"从创建那一刻起就必须读**工作副本**（不是当日只读视图）—— 否则同一 tick 内后面的 迁移笔看不到刚落的那一条，会重复创建或走错分支。★
   * 判据本体只有这一处拼写。
   */
  static List<IndustryId> industriesForMode(
      Map<IndustryId, IndustryDefinitionView> industries, HexCoord hex, ProductionModeId modeId) {
    Objects.requireNonNull(industries, "industries");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(modeId, "modeId");
    List<IndustryId> candidates = new ArrayList<>(IndustryHexKeys.at(industries, hex.q(), hex.r()));
    candidates.sort(Comparator.comparing(IndustryId::value));
    Optional<String> wanted = RegimeOperators.defaultRegimeForMode(modeId);
    if (wanted.isEmpty()) {
      return List.copyOf(candidates);
    }
    List<IndustryId> matching = new ArrayList<>();
    for (IndustryId industryId : candidates) {
      IndustryDefinitionView industry = industries.get(industryId);
      if (industry != null && wanted.get().equals(industry.regime().value())) {
        matching.add(industryId);
      }
    }
    if (!matching.isEmpty()) {
      return List.copyOf(matching);
    }
    if (DefaultProductionModes.MERCHANT.equals(modeId)) {
      return List.of();
    }
    return List.copyOf(candidates);
  }

  static HexCoord unitHexOrNull(ProductionProcess unit) {
    return IndustryHexKeys.hexKeyOf(unit.industry()).map(HexCoord::parse).orElse(null);
  }

  static List<ProductionOrganizationId> sortedEnterpriseIds(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    List<ProductionOrganizationId> ids = new ArrayList<>(enterprises.keySet());
    ids.sort(Comparator.comparing(ProductionOrganizationId::value));
    return ids;
  }

  // ── 钱/债的比例切分（逐笔 floor；源户人口归零时余数随最后一笔走）────────────────────────────

  static HouseholdMode currentOf(
      Map<HouseholdId, HouseholdMode> modeByHousehold, HouseholdId source) {
    HouseholdMode mode = modeByHousehold.get(source);
    if (mode == null) {
      throw new IllegalStateException("计划的源家户没有 mode 索引（坏输入）: " + source);
    }
    return mode;
  }

  static CurrencyId currentCurrency(
      HouseholdMode current,
      AccountSession accounts,
      Map<MarketZoneId, MarketTable> markets,
      MarketZone zone) {
    // ★ W1：本格价表 = 本格所属市场的那一张（价表每区一张；区内任何格同一张）。
    MarketTable market = zone == null ? null : markets.get(zone.zoneId());
    if (market != null) {
      return market.numeraire();
    }
    Map<CurrencyId, Long> money =
        accounts.householdMoney().getOrDefault(current.household, Map.of());
    CurrencyId smallest = null;
    for (CurrencyId currency : money.keySet()) {
      if (smallest == null || currency.value().compareTo(smallest.value()) < 0) {
        smallest = currency;
      }
    }
    return smallest;
  }

  /**
   * ★★ <b>P11.1 / D-023：按人口比例切源户的“全部币种”余额</b>（逐币种独立、逐笔 floor、<b>不做 FX</b>）。
   *
   * <pre>
   * 逐币种 c（CurrencyId.value 升序）：remaining = 当前余额
   *   第 i 笔（i = 0..n-1）：denom = max(1, sourcePopulation − movedBefore)
   *     share_i = (源户迁空 && i == n−1) ? remaining : ⌊remaining × populations[i] ÷ denom⌋
   *     remaining -= share_i；movedBefore += populations[i]
   * 源户不迁空 ⇒ 每个币种的 floor 余数留在源户；迁空 ⇒ 余数随最后一笔走。
   * </pre>
   *
   * <p>★ 返回的第 i 个 map 就是第 i 笔 move 的 {@code moneyByCurrency}；不同币种并列存在，不互相折算。
   */
  static List<Map<CurrencyId, Long>> splitMoneyByCurrency(
      AccountSession accounts,
      long[] populations,
      long sourcePopulation,
      HouseholdId source,
      boolean emptiesSource) {
    List<Map<CurrencyId, Long>> shares = new ArrayList<>(populations.length);
    for (int i = 0; i < populations.length; i++) {
      shares.add(new LinkedHashMap<>());
    }
    Map<CurrencyId, Long> balances = accounts.householdMoney().getOrDefault(source, Map.of());
    List<CurrencyId> currencies = new ArrayList<>(balances.keySet());
    currencies.sort(Comparator.comparing(CurrencyId::value));
    for (CurrencyId currency : currencies) {
      long remainingBalance = balances.getOrDefault(currency, 0L);
      if (remainingBalance <= 0L) {
        continue;
      }
      long movedBefore = 0L;
      for (int i = 0; i < populations.length; i++) {
        long denom = Math.max(1L, sourcePopulation - movedBefore);
        long share =
            emptiesSource && i == populations.length - 1
                ? remainingBalance
                : Math.multiplyExact(remainingBalance, populations[i]) / denom;
        if (share < 0L || share > remainingBalance) {
          throw new IllegalStateException(
              "货币切分出现非法份额（坏数据）: source="
                  + source
                  + " currency="
                  + currency
                  + " share="
                  + share
                  + " remaining="
                  + remainingBalance);
        }
        remainingBalance -= share;
        movedBefore = Math.addExact(movedBefore, populations[i]);
        if (share > 0L) {
          shares.get(i).put(currency, share);
        }
      }
      if (remainingBalance < 0L) {
        throw new IllegalStateException("货币切分出现负余额（坏数据）: source=" + source);
      }
    }
    return shares;
  }

  static long[] splitDebt(
      long[] populations,
      long sourcePopulation,
      HouseholdId source,
      Map<DebtContractId, DebtContract> debts,
      boolean emptiesSource) {
    long[] shares = new long[populations.length];
    List<DebtContract> contracts = new ArrayList<>();
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(source) && contract.principal() > 0L) {
        contracts.add(contract);
      }
    }
    contracts.sort(Comparator.comparing(contract -> contract.id().value()));
    long[] remainingPrincipal = new long[contracts.size()];
    for (int i = 0; i < contracts.size(); i++) {
      remainingPrincipal[i] = contracts.get(i).principal();
    }
    long movedBefore = 0L;
    for (int moveIndex = 0; moveIndex < populations.length; moveIndex++) {
      long denom = Math.max(1L, sourcePopulation - movedBefore);
      long total = 0L;
      for (int contractIndex = 0; contractIndex < contracts.size(); contractIndex++) {
        long take =
            emptiesSource && moveIndex == populations.length - 1
                ? remainingPrincipal[contractIndex]
                : Math.multiplyExact(remainingPrincipal[contractIndex], populations[moveIndex])
                    / denom;
        take = Math.min(take, remainingPrincipal[contractIndex]);
        remainingPrincipal[contractIndex] -= take;
        total = Math.addExact(total, take);
      }
      shares[moveIndex] = total;
      movedBefore = Math.addExact(movedBefore, populations[moveIndex]);
    }
    return shares;
  }

  // ── 小工具 ────────────────────────────────────────────────────────────────────────────────

  static boolean hasIndustryTemplate(EconomyDayView base, HexCoord hex) {
    return !IndustryHexKeys.at(base.industries(), hex.q(), hex.r()).isEmpty();
  }

  static Map<ProductionModeId, List<ProductionRole>> producingPositionsByMode(
      EconomyDayView base, List<ProductionModeDefinitionView> modes) {
    Map<ProductionModeId, List<ProductionRole>> result = new LinkedHashMap<>();
    for (ProductionModeDefinitionView mode : modes) {
      ClassStructure structure = base.classStructures().get(mode.classStructureId());
      if (structure == null) {
        result.put(mode.id(), List.of());
        continue;
      }
      List<ProductionRole> positions = new ArrayList<>();
      for (ProductionRole position : structure.positions().values()) {
        if (shouldProduce(position)) {
          positions.add(position);
        }
      }
      positions.sort(Comparator.comparing(position -> position.id().value()));
      result.put(mode.id(), positions);
    }
    return result;
  }

  static boolean shouldProduce(ProductionRole position) {
    return position.laborRole() != ProductionRole.LaborRole.NONE
        && position.surplusRole() != ProductionRole.SurplusRole.DEPENDENT;
  }

  static List<ProductionModeDefinitionView> sortedModes(EconomyDayView base) {
    List<ProductionModeDefinitionView> modes = new ArrayList<>(base.modes().values());
    modes.sort(Comparator.comparing(mode -> mode.id().value()));
    return modes;
  }

  static List<HouseholdId> sortedHouseholds(Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    List<HouseholdId> keys = new ArrayList<>(householdEconomies.keySet());
    keys.sort(Comparator.comparing(HouseholdId::value));
    return keys;
  }

  static List<HexCoord> globalHexes(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<MarketZoneId, MarketTable> markets,
      Map<MarketZoneId, MarketZone> marketZones) {
    TreeSet<HexCoord> hexes =
        new TreeSet<>(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      hexes.add(householdEconomy.view().hex());
    }
    hexes.addAll(
        MarketTables.allMarketHexes(markets, marketZones)); // ★ W1：有市场的格（持久 = 有价表的区成员格；派生 = 价表成员格）
    for (ProductionProcess unit : units.values()) {
      Optional<String> key = IndustryHexKeys.hexKeyOf(unit.industry());
      if (key.isPresent()) {
        int separator = key.get().lastIndexOf('_');
        try {
          hexes.add(
              new HexCoord(
                  Integer.parseInt(key.get().substring(0, separator)),
                  Integer.parseInt(key.get().substring(separator + 1))));
        } catch (RuntimeException ignored) {
          // 不猜坐标
        }
      }
    }
    return new ArrayList<>(hexes);
  }

  static String canonicalKey(HexCoord hex, ProductionModeId modeId) {
    return modeId.value() + "@" + hex.q() + "_" + hex.r();
  }

  static HouseholdId newHouseholdId(
      HouseholdId source, HexCoord hex, ProductionModeId modeId, int ordinal) {
    // 迁移新建户 id 的唯一拼写点：确定性、无随机、不含 '.'；ordinal 只服务"同源同目标要建多户"。
    return HouseholdId.parse(
        "hh-mig-"
            + hex.q()
            + "_"
            + hex.r()
            + "-"
            + modeId.value()
            + "-"
            + source.value()
            + "-"
            + ordinal);
  }

  static long sum(long[] values) {
    long total = 0L;
    for (long value : values) {
      total = Math.addExact(total, value);
    }
    return total;
  }

  /** 内部家户 mode/hex/position 索引。 */
  record HouseholdMode(
      HouseholdId household, HexCoord hex, ProductionModeId modeId, ClassPositionId positionId) {
    HouseholdMode {
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(modeId, "modeId");
      Objects.requireNonNull(positionId, "positionId");
    }
  }
}

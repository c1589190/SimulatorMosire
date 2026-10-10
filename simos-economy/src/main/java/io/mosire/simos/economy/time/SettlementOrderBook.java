package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * ★★ <b>W4（2026-10-11）：结算序的数量口径（S-4 / N-1）—— "谁排前面"的唯一拼写点</b>。
 *
 * <p>★★ <b>用户原话（第二批第 4 条，逐字）</b>：「不是，我说的是单个家户有就买/借啊，<b>家户之间的顺序还是得排序的啊</b>；
 * 另外，<b>同一市场区内的结算顺序也可以根据经济发达程度排序，乃至市场区之间的结算顺序也可以根据市场区的总 GDP 排序</b>； 」+ 冻结口径
 * <b>S-4</b>：区内按<b>经济发达程度</b>、区间按<b>总 GDP</b>（★ 必须用上周期快照，<b>禁自指</b>）。
 *
 * <pre>
 * 家户序（N-1）  = 资产 − 负债净值（毫 numeraire ×1000 的同一把尺）降序 → HouseholdId 升序
 *   资产 = 货币余额（本格计价币）+ 商品库存按本格牌价折算          ← {@link PrimaryModeRanking#inventoryMilli}
 *   负债 = Σ 该户名下"未偿本金 > 0"的债务按 {@link DebtValuation#commonValueMilli} 折成同一把尺
 * 区序（N-1）    = 上周期名义成交额（{@link MarketRoundStatsBook#previousCycleTurnover}，W1 统计簿）降序
 *                  → 本周期累计名义成交额降序 → 区 id 升序
 * </pre>
 *
 * <p>★★★ <b>禁自指（I7）怎么保证</b>：本类<b>只在市场轮开盘之前</b>被调用一次（{@code EconomySettlement} 在 {@code
 * MarketSettlement.clearOncePerCycle} 之前构造），产出的是<b>不可变快照</b>；撮合期间一个字段都不再回读。 因此"当日成交额翻倍"不可能影响本轮序 ——
 * 本轮的成交量根本还没有产生（判据：{@code MARKET_ORDER_SNAPSHOT} 一行发生在 {@code MARKET_ROUND_START} 之前，且快照量取自 W1
 * 统计簿的<b>上周期/本周期累计窗口</b>，不含当轮）。
 *
 * <p>★★ <b>区序的量纲口径（W1 明确要求调用方自己说清，本类在此具名）</b>：{@code MarketRoundStats.turnoverMilli} 是
 * <b>名义货值</b>（全额货款，含赊购部分），单位 = 各笔成交的<b>计价币</b>；世界没有汇率 ⇒ <b>跨币不折算、直接原值相加</b>。
 * 所以区序读作"**以各区自己的计价币计的、名义成交额最大的区排前面**"，不是"折算成一个购买力之后的 GDP"。 ★ 现金口径（{@code
 * cashTurnoverMilli}）<b>不</b>参与排序：债务式清算下它只数真钱，会把序退化成"谁付过现金"（W2-fix7 / D4）。
 *
 * <p>★★ <b>资产/负债的取不到值怎么办（fail-closed，不静默当 0）</b>：
 *
 * <ul>
 *   <li>该户所在格没有市场表（说不出价）⇒ 资产只算货币余额（{@code inventoryMilli} 的既有口径），并计入 {@link #unpricedHouseholds()}；
 *   <li>某笔债的单位在该户牌价表下<b>不可定价</b>（外币债 / 缺价商品）⇒ <b>该笔不计入负债</b>，只计入 {@link #unpricedDebtContracts()} 与
 *       {@link #unpricedDebtAmountRawMilli()}（各自计量单位的原始本金和，跨单位混合， <b>只作审计</b>）——与 {@code
 *       DebtCapacity} 对不可定价债务的同一条处置（不硬折、不冒充 0）。
 * </ul>
 *
 * <p>★ <b>不写状态、不落盘</b>：一次市场轮一份瞬态快照；它不进 {@code EconomyData}、不进 Codec/ChangeSet（铁律 5 不触发）。
 */
public final class SettlementOrderBook {

  /** 未知家户/区的名次（排最后）。 */
  public static final int UNKNOWN_RANK = Integer.MAX_VALUE;

  /**
   * ★ <b>资产侧与负债侧的公共尺度</b>：{@code inventoryMilli} 以"毫计价币"计，而 {@link DebtValuation#commonValueMilli}
   * 的共同尺度是"毫 × 单位价值" = 毫计价币 × 1000（见 {@code DebtValuation} 类注的量纲推导） ⇒ 两侧对齐时资产侧乘本常量，免去逐笔除法取整。
   */
  public static final long ASSET_SCALE_TO_COMMON_VALUE = 1000L;

  private final long day;
  private final List<HouseholdId> householdsByRank;
  private final Map<HouseholdId, Integer> householdRank;
  private final Map<HouseholdId, Long> householdNetWorth;
  private final Map<HouseholdId, Long> householdAssets;
  private final Map<HouseholdId, Long> householdDebt;
  private final Map<HouseholdId, Boolean> householdDebtUnpriced;
  private final long householdAssetsMilli;
  private final long householdDebtMilli;
  private final int unpricedDebtContracts;
  private final long unpricedDebtAmountRawMilli;
  private final int unpricedHouseholds;
  private final List<String> zonesByRank;
  private final Map<String, Integer> zoneRank;
  private final Map<String, Long> zonePreviousCycleTurnover;
  private final Map<String, Long> zoneCycleTurnover;

  private SettlementOrderBook(
      long day,
      List<HouseholdId> householdsByRank,
      Map<HouseholdId, Long> householdNetWorth,
      Map<HouseholdId, Long> householdAssets,
      Map<HouseholdId, Long> householdDebt,
      Map<HouseholdId, Boolean> householdDebtUnpriced,
      long householdAssetsMilli,
      long householdDebtMilli,
      int unpricedDebtContracts,
      long unpricedDebtAmountRawMilli,
      int unpricedHouseholds,
      List<String> zonesByRank,
      Map<String, Long> zonePreviousCycleTurnover,
      Map<String, Long> zoneCycleTurnover) {
    this.day = day;
    // ★★ W4-fix（2026-10-11）：**不可变包装必须是赋值右侧最外层那次调用** —— SpotBugs 的 EI_EXPOSE_REP 只在
    //   赋值处认得出包装，写进辅助方法或三元表达式就识别不了（W1e 的 {@code MarketReadout.marketRoundStats} 同一处置）。
    //   保序口径不变：ArrayList 副本的迭代序 = 入参插入序（I7"内容的纯函数"）；同族禁 {@code Map.copyOf}。
    this.householdsByRank = Collections.unmodifiableList(new ArrayList<>(householdsByRank));
    // ★ 下面四个 Map 不进本批冻结：它们**没有任何"整表返回"的访问器**（只有 {@code householdNetWorthOf} 这类逐键取值），
    //   且唯一的非空调用方 {@link #snapshot} 已在传参处包了 {@code Collections.unmodifiableMap}。
    this.householdNetWorth = householdNetWorth;
    this.householdAssets = householdAssets;
    this.householdDebt = householdDebt;
    this.householdDebtUnpriced = householdDebtUnpriced;
    Map<HouseholdId, Integer> ranks = new LinkedHashMap<>();
    for (int i = 0; i < householdsByRank.size(); i++) {
      ranks.put(householdsByRank.get(i), i);
    }
    this.householdRank = Collections.unmodifiableMap(ranks);
    this.householdAssetsMilli = householdAssetsMilli;
    this.householdDebtMilli = householdDebtMilli;
    this.unpricedDebtContracts = unpricedDebtContracts;
    this.unpricedDebtAmountRawMilli = unpricedDebtAmountRawMilli;
    this.unpricedHouseholds = unpricedHouseholds;
    this.zonesByRank = Collections.unmodifiableList(new ArrayList<>(zonesByRank));
    Map<String, Integer> zoneRanks = new LinkedHashMap<>();
    for (int i = 0; i < zonesByRank.size(); i++) {
      zoneRanks.put(zonesByRank.get(i), i);
    }
    this.zoneRank = Collections.unmodifiableMap(zoneRanks);
    this.zonePreviousCycleTurnover =
        Collections.unmodifiableMap(new LinkedHashMap<>(zonePreviousCycleTurnover));
    this.zoneCycleTurnover = Collections.unmodifiableMap(new LinkedHashMap<>(zoneCycleTurnover));
  }

  /** 没有排序面的世界（夹具 / 空市场）⇒ 全部名次未知，撮合退化为"按稳定 id"（与改动前的稳定序相容）。 */
  public static SettlementOrderBook empty(long day) {
    return new SettlementOrderBook(
        day, List.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0L, 0L, 0, 0L, 0, List.of(),
        Map.of(), Map.of());
  }

  /**
   * ★★ <b>开盘前快照</b>（唯一构造入口；只在市场轮之前调用一次）。
   *
   * @param day 世界日（快照日）
   * @param topology 本轮市场拓扑（区 = {@code region.node().nodeId()}）
   * @param marketTables 本轮价表（区 id → 表；区内一张）
   * @param householdEconomies 家户行（人口 &gt; 0 才参与排序）
   * @param debts 债务合同（读当刻本金；未偿本金 &gt; 0 才计入负债）
   * @param accounts 账户会话（货币 + 商品库存的活视图）
   * @param statsView W1 统计簿的只读视图（区序的量取自它的上周期/本周期累计窗口）
   */
  public static SettlementOrderBook snapshot(
      long day,
      MarketTopology topology,
      Map<MarketZoneId, MarketTable> marketTables,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<DebtContractId, DebtContract> debts,
      AccountSession accounts,
      EconomyDayView statsView) {
    Objects.requireNonNull(topology, "topology");
    Objects.requireNonNull(marketTables, "marketTables");
    Objects.requireNonNull(householdEconomies, "householdEconomies");
    Objects.requireNonNull(debts, "debts");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(statsView, "statsView");
    if (day < 0L) {
      throw new IllegalArgumentException("SettlementOrderBook.day 不得为负: " + day);
    }

    // ── ①② 逐户估值（资产/负债同一把尺，见 {@link #valuate}）→ 净值 ─────────────────────────
    //   ★ 先按债务人建索引（一轮一次 O(债)），避免逐户扫全表（性能红线：排序是 O(n log n) + 一次建表）。
    Map<HouseholdId, List<DebtContract>> debtsByDebtor = debtsByDebtor(debts);
    Map<HouseholdId, Long> netWorthByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, Long> assetsByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, Long> debtCommonByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, Boolean> debtUnpricedByHousehold = new LinkedHashMap<>();
    long assetsTotal = 0L;
    long debtTotal = 0L;
    int unpricedDebtContracts = 0;
    long unpricedDebtAmountRaw = 0L;
    int unpricedHouseholds = 0;
    for (HouseholdId household : sortedHouseholds(householdEconomies)) {
      HouseholdEconomy row = householdEconomies.get(household);
      if (row == null || row.population() <= 0L) {
        continue;
      }
      if (PrimaryModeRanking.marketOf(marketTables, topology, row.view().hex()) == null) {
        unpricedHouseholds++; // 该格没有价表 ⇒ 商品库存折算不出价（货币仍计入）
      }
      HouseholdValuation valuation =
          valuate(
              accounts,
              marketTables,
              topology,
              household,
              row.view().hex(),
              debtsByDebtor.getOrDefault(household, List.of()));
      assetsTotal = saturatedAdd(assetsTotal, valuation.assetsCommonValue());
      debtTotal = saturatedAdd(debtTotal, valuation.debtCommonValue());
      unpricedDebtContracts += valuation.unpricedDebtContracts();
      unpricedDebtAmountRaw = saturatedAdd(unpricedDebtAmountRaw, valuation.unpricedDebtRawMilli());
      assetsByHousehold.put(household, valuation.assetsCommonValue());
      debtCommonByHousehold.put(household, valuation.debtCommonValue());
      debtUnpricedByHousehold.put(household, !valuation.debtFullyPriced());
      netWorthByHousehold.put(
          household, valuation.assetsCommonValue() - valuation.debtCommonValue());
    }

    // ── ③ 家户序：净值降序 → HouseholdId 升序（同值 tie-break = 稳定 id 的 canonical 序）──────
    List<HouseholdId> households = new ArrayList<>(netWorthByHousehold.keySet());
    households.sort(
        Comparator.comparingLong((HouseholdId id) -> netWorthByHousehold.get(id))
            .reversed()
            .thenComparing(HouseholdId::value));
    Map<HouseholdId, Long> orderedNetWorth = new LinkedHashMap<>();
    for (HouseholdId household : households) {
      orderedNetWorth.put(household, netWorthByHousehold.get(household));
    }

    // ── ④ 区序：上周期名义成交额降序 → 本周期累计降序 → 区 id 升序 ─────────────────────────
    List<String> zoneIds = new ArrayList<>();
    for (MarketRegion region : topology.regions()) {
      zoneIds.add(region.node().nodeId());
    }
    zoneIds.sort(Comparator.naturalOrder());
    Map<String, Long> previousTurnover = new LinkedHashMap<>();
    Map<String, Long> cycleTurnover = new LinkedHashMap<>();
    for (String zoneId : zoneIds) {
      MarketZoneId marketZoneId = new MarketZoneId(zoneId);
      previousTurnover.put(
          zoneId, MarketRoundStatsBook.previousCycleTurnover(statsView, marketZoneId).orElse(0L));
      cycleTurnover.put(
          zoneId, MarketRoundStatsBook.cycleTurnover(statsView, marketZoneId).orElse(0L));
    }
    List<String> zones = new ArrayList<>(zoneIds);
    zones.sort(
        Comparator.comparingLong((String id) -> previousTurnover.get(id))
            .reversed()
            .thenComparing(
                Comparator.comparingLong((String id) -> cycleTurnover.get(id)).reversed())
            .thenComparing(Comparator.naturalOrder()));

    return new SettlementOrderBook(
        day,
        List.copyOf(households),
        Collections.unmodifiableMap(orderedNetWorth),
        Collections.unmodifiableMap(assetsByHousehold),
        Collections.unmodifiableMap(debtCommonByHousehold),
        Collections.unmodifiableMap(debtUnpricedByHousehold),
        assetsTotal,
        debtTotal,
        unpricedDebtContracts,
        unpricedDebtAmountRaw,
        unpricedHouseholds,
        List.copyOf(zones),
        Collections.unmodifiableMap(previousTurnover),
        Collections.unmodifiableMap(cycleTurnover));
  }

  /**
   * ★★ <b>逐户估值（W4 的唯一算式；开盘前快照与市场轮末的阶层滑落共用）</b>：
   *
   * <pre>
   * 资产 = 货币余额（本格计价币）+ 商品库存按本格牌价折算（{@link PrimaryModeRanking#inventoryMilli}）× 1000
   * 负债 = Σ 未偿本金(&gt;0) × 该单位在本户牌价表下的共同价值（{@link DebtValuation#commonValueMilli}）
   * </pre>
   *
   * <p>★★ <b>为什么是静态 helper 而不是"把快照里的数搬去用"</b>：排序量必须取<b>开盘前</b>快照（禁自指），
   * 而阶层滑落的速度必须取<b>市场轮末</b>的债务规模（用户原话「当轮算出这个家户的债务规模后进行」）⇒ 两处 <b>时点不同、算式必须同一份</b>。两边都调本方法 ⇒ 不可能漂成两把尺。
   *
   * @param householdDebts 该户<b>未偿本金 &gt; 0</b> 的债务（调用方各自按自己的时点索引；见 {@link #debtsByDebtor}）
   */
  public static HouseholdValuation valuate(
      AccountSession accounts,
      Map<MarketZoneId, MarketTable> marketTables,
      MarketTopology topology,
      HouseholdId household,
      HexCoord hex,
      List<DebtContract> householdDebts) {
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(householdDebts, "householdDebts");
    MarketTable market = PrimaryModeRanking.marketOf(marketTables, topology, hex);
    long assetsCommon =
        saturatedMul(
            PrimaryModeRanking.inventoryMilli(accounts, marketTables, topology, household, hex),
            ASSET_SCALE_TO_COMMON_VALUE);
    long debtCommon = 0L;
    long unpricedRaw = 0L;
    int unpricedCount = 0;
    for (DebtContract debt : householdDebts) {
      if (debt.principal() <= 0L) {
        continue;
      }
      OptionalLong common =
          DebtValuation.commonValueMilli(debt.principal(), debt.unit(), household, market, null);
      if (common.isPresent()) {
        debtCommon = saturatedAdd(debtCommon, common.getAsLong());
      } else {
        unpricedCount++;
        unpricedRaw = saturatedAdd(unpricedRaw, debt.principal());
      }
    }
    return new HouseholdValuation(assetsCommon, debtCommon, unpricedRaw, unpricedCount);
  }

  /** ★ 未偿本金 &gt; 0 的债务按债务人分桶（规范序：合同 id 升序）；两个调用方共用，避免各写一遍索引。 */
  public static Map<HouseholdId, List<DebtContract>> debtsByDebtor(
      Map<DebtContractId, DebtContract> debts) {
    Objects.requireNonNull(debts, "debts");
    Map<HouseholdId, List<DebtContract>> byDebtor = new LinkedHashMap<>();
    for (DebtContract debt : sortedDebts(debts)) {
      if (debt.principal() <= 0L) {
        continue;
      }
      byDebtor.computeIfAbsent(debt.debtor(), ignored -> new ArrayList<>()).add(debt);
    }
    return byDebtor;
  }

  /**
   * ★ 逐户估值结果（共同价值刻度 = 毫计价币 × {@link #ASSET_SCALE_TO_COMMON_VALUE}）。
   *
   * @param assetsCommonValue 资产（货币 + 商品库存折价）
   * @param debtCommonValue <b>可定价</b>负债合计（不可定价的那部分不硬折，见 {@code unpricedDebtRawMilli}）
   * @param unpricedDebtRawMilli 不可定价债务的原始本金和（各自计量单位，跨单位混合 ⇒ 只作审计）
   * @param unpricedDebtContracts 不可定价债务条数（&gt; 0 ⇒ 负债被低估，调用方须具名）
   */
  public record HouseholdValuation(
      long assetsCommonValue,
      long debtCommonValue,
      long unpricedDebtRawMilli,
      int unpricedDebtContracts) {

    public HouseholdValuation {
      if (assetsCommonValue < 0L
          || debtCommonValue < 0L
          || unpricedDebtRawMilli < 0L
          || unpricedDebtContracts < 0) {
        throw new IllegalArgumentException(
            "HouseholdValuation 的金额/条数不得为负: "
                + assetsCommonValue
                + "/"
                + debtCommonValue
                + "/"
                + unpricedDebtRawMilli
                + "/"
                + unpricedDebtContracts);
      }
    }

    /** 负债是否全部可定价（false ⇒ 速度读数按"低估"处理，且**不**把不可定价部分当 0 混入）。 */
    public boolean debtFullyPriced() {
      return unpricedDebtContracts == 0;
    }
  }

  /** 快照日。 */
  public long day() {
    return day;
  }

  /** 家户名次（0 = 最靠前）；不在快照里 ⇒ {@link #UNKNOWN_RANK}（排最后）。 */
  public int householdRankOf(HouseholdId household) {
    Integer rank = householdRank.get(household);
    return rank == null ? UNKNOWN_RANK : rank;
  }

  /** 区名次（0 = 最靠前）；不在快照里 ⇒ {@link #UNKNOWN_RANK}。 */
  public int zoneRankOf(String regionId) {
    Integer rank = zoneRank.get(regionId);
    return rank == null ? UNKNOWN_RANK : rank;
  }

  /** 该户的净值（共同价值刻度；不在快照里 ⇒ null）。 */
  public Long householdNetWorthOf(HouseholdId household) {
    return householdNetWorth.get(household);
  }

  /** ★ W4：该户的<b>资产</b>（货币 + 商品库存折价，共同价值刻度；不在快照里 ⇒ null）—— 阶层滑落的"债务/资产"分母。 */
  public Long householdAssetsOf(HouseholdId household) {
    return householdAssets.get(household);
  }

  /** ★ W4：该户的<b>可定价负债</b>合计（共同价值刻度；不在快照里 ⇒ null）—— 阶层滑落的分子。 */
  public Long householdDebtOf(HouseholdId household) {
    return householdDebt.get(household);
  }

  /** ★ W4：该户有没有<b>不可定价</b>的债务（有 ⇒ 负债被低估，滑落速度读数按具名口径处理）。 */
  public boolean householdDebtUnpriced(HouseholdId household) {
    return Boolean.TRUE.equals(householdDebtUnpriced.get(household));
  }

  /** 参与排序的家户数。 */
  public int householdCount() {
    return householdsByRank.size();
  }

  /** 参与排序的区数。 */
  public int zoneCount() {
    return zonesByRank.size();
  }

  /** 家户序（保序：净值降序 → id 升序）。 */
  public List<HouseholdId> householdsByRank() {
    return householdsByRank;
  }

  /** 区序（保序：上周期名义成交额降序 → 本周期 → id 升序）。 */
  public List<String> zonesByRank() {
    return zonesByRank;
  }

  /** 逐户资产合计（共同价值刻度；读数用）。 */
  public long householdAssetsCommonValue() {
    return householdAssetsMilli;
  }

  /** 逐户负债合计（共同价值刻度；读数用）。 */
  public long householdDebtCommonValue() {
    return householdDebtMilli;
  }

  /** 不可定价的债务条数（不硬折 ⇒ 不计入负债；具名计数）。 */
  public int unpricedDebtContracts() {
    return unpricedDebtContracts;
  }

  /** 不可定价债务的原始本金和（各自计量单位，跨单位混合 ⇒ 只作审计）。 */
  public long unpricedDebtAmountRawMilli() {
    return unpricedDebtAmountRawMilli;
  }

  /** 所在格没有价表的家户数（其商品库存折算不出价，货币仍计入）。 */
  public int unpricedHouseholds() {
    return unpricedHouseholds;
  }

  /** 逐区排序量：上周期名义成交额（毫计价币；跨币不折算）。 */
  public Map<String, Long> zonePreviousCycleTurnover() {
    return zonePreviousCycleTurnover;
  }

  /** 逐区 tie-break 读数：本周期累计名义成交额。 */
  public Map<String, Long> zoneCycleTurnover() {
    return zoneCycleTurnover;
  }

  /** 区序一行（日志用）：{@code rank:zone=turnover/tie}。 */
  public List<String> describeZones() {
    List<String> lines = new ArrayList<>(zonesByRank.size());
    for (int i = 0; i < zonesByRank.size(); i++) {
      String zone = zonesByRank.get(i);
      lines.add(
          i
              + ":"
              + zone
              + "="
              + zonePreviousCycleTurnover.getOrDefault(zone, 0L)
              + "/"
              + zoneCycleTurnover.getOrDefault(zone, 0L));
    }
    return List.copyOf(lines);
  }

  /** 家户序一行（TRACE 用）：{@code rank:household=netWorth}。 */
  public List<String> describeHouseholds() {
    List<String> lines = new ArrayList<>(householdsByRank.size());
    for (int i = 0; i < householdsByRank.size(); i++) {
      HouseholdId household = householdsByRank.get(i);
      lines.add(i + ":" + household.value() + "=" + householdNetWorth.getOrDefault(household, 0L));
    }
    return List.copyOf(lines);
  }

  /** 家户按 HouseholdId 规范序（读入序不能是内容的纯函数 ⇒ 先排序，见 I7）。 */
  private static List<HouseholdId> sortedHouseholds(
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    List<HouseholdId> sorted = new ArrayList<>(householdEconomies.keySet());
    sorted.sort(Comparator.comparing(HouseholdId::value));
    return sorted;
  }

  /** 债务按合同 id 规范序。 */
  private static List<DebtContract> sortedDebts(Map<DebtContractId, DebtContract> debts) {
    List<DebtContract> sorted = new ArrayList<>(debts.values());
    sorted.sort(Comparator.comparing(debt -> debt.id().value()));
    return sorted;
  }

  private static long saturatedAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      return left > 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }

  private static long saturatedMul(long left, long right) {
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException overflow) {
      return left > 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
  }
}

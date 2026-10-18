package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MerchantCapacityMath.TOOL_COMMODITY;
import static io.mosire.simos.economy.time.MerchantCapacityMath.askPerMilleOf;
import static io.mosire.simos.economy.time.MerchantCapacityPool.NO_SERVICE_GOODS_REASON;

import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** MerchantCapacityPool 的状态与装配实现（门面保留 public API）。 */
class MerchantCapacityCore extends MerchantFreightQuoteBook {

  MerchantCapacityCore(
      Map<HexCoord, List<Entry>> byHex,
      Map<HexCoord, Long> totalCapacityByHex,
      Map<HouseholdId, Entry> byHousehold,
      List<Entry> globalOrder,
      long householdCount,
      boolean priced,
      long maxAskPerMille,
      Set<HexCoord> haulServiceHexes,
      Map<String, Long> serviceAbsentTally,
      long day) {
    super(
        byHex,
        totalCapacityByHex,
        byHousehold,
        globalOrder,
        householdCount,
        priced,
        maxAskPerMille,
        haulServiceHexes,
        serviceAbsentTally,
        day);
  }

  static final long NO_WORLD_DAY = -1L;

  static MerchantCapacityCore emptyCore() {
    return new MerchantCapacityCore(
        Map.of(),
        Map.of(),
        Map.of(),
        List.of(),
        0L,
        false,
        0L,
        Set.of(),
        new LinkedHashMap<>(),
        NO_WORLD_DAY);
  }

  /** <b>装配体（唯一拼写点）</b>。 */
  static MerchantCapacityCore assemble(
      Map<HouseholdId, HouseholdClassMembership> classStandings,
      Map<ClassPositionId, ProductionRole> classPositions,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<HouseholdId, Map<CommodityId, Long>> goods,
      Map<HouseholdId, Map<CommodityId, Long>> frozenGoods,
      boolean priced,
      Set<HexCoord> haulServiceHexes,
      long day) {
    Objects.requireNonNull(classStandings, "classStandings");
    Objects.requireNonNull(classPositions, "classPositions");
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(goods, "goods");
    Objects.requireNonNull(frozenGoods, "frozenGoods");
    List<HouseholdId> households = new ArrayList<>(rows.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    Map<HexCoord, List<Entry>> pool = new LinkedHashMap<>();
    Map<HexCoord, Long> totals = new LinkedHashMap<>();
    // ★★ F-2：装配期的缺货计数（**局部**，装配完交给池；见字段注：瞬态、只进日志、不是状态组件）。
    Map<String, Long> serviceAbsent = new LinkedHashMap<>();
    long counted = 0L;
    long maxAsk = 0L;
    for (HouseholdId household : households) {
      HouseholdEconomy row = rows.get(household);
      HouseholdClassMembership standing = classStandings.get(household);
      if (row == null || !MerchantIdentity.selectsMerchant(standing, classPositions)) {
        continue;
      }
      HexCoord hex0 = row.view().hex();
      // ★★ A3（2026-10-10）：工具维 = {@link MerchantCapacity} 的**作业层**可投入量（装配点的存量 − 冻结）。
      //   它**不再是任何门槛**（旧 "每趟烧 1,000 毫工具" 的门槛已随 MerchantHaul 删除）：工具由 trade 产业声明的
      //   周期投入经标准生产管线消耗（设计书 §3.2 / I-H6）⇒ 这里读它只为把"运力规模"（tier/share 的派生依据）
      //   算出来，改它不改任何一次承运的成败。
      long toolStockMilli =
          goods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
      long toolFrozenMilli =
          frozenGoods.getOrDefault(household, Map.of()).getOrDefault(TOOL_COMMODITY, 0L);
      long toolMilli = Math.max(0L, toolStockMilli - toolFrozenMilli);
      // ★★ A2：服务成市的格 ⇒ 本轮运力预算 = 该户手上的**运输服务货**（max(0, 现货 − 冻结)）——
      //   卖出多少服务就得有多少货（I-H2），"服务不能凭空造"由此结构性成立；其余格逐值退回劳动+工具算式。
      //   ★ 单位锚（A1）：1 商品单位 haul = 1,000 毫服务 = 1,000 毫商品·程 ⇒ 这里不需要第二次换算。
      boolean servicePriced = haulServiceHexes != null && haulServiceHexes.contains(hex0);
      long serviceAvailableMilli =
          Math.max(
              0L,
              goods.getOrDefault(household, Map.of()).getOrDefault(HaulService.HAUL_COMMODITY, 0L)
                  - frozenGoods
                      .getOrDefault(household, Map.of())
                      .getOrDefault(HaulService.HAUL_COMMODITY, 0L));
      // ★★★ F-1（2026-10-10，A3 残留修复）：**进池/提供者判据改成与"有服务货"一致**。
      //
      //   服务成市的格：**进池不再要求"劳动 + 工具 > 0"**（那是已退役的门槛家族残留）——该户只要"选了跑商"
      //     就进池，它的运力预算 = **手上可交付的服务货**（{@code max(0, 现货 − 冻结)}，I-H2）。
      //     ⇒ 一个劳动/工具为 0 但持有服务货的户**结构上不再卖不出服务**（设计书 §3.2「运力 = 该产业本周期
      //       可产出的服务量」）。★ 预算为 0 的条目留在池里但**分不到任何量**（{@code select} 只看预算），
      //       这样"本格**有**跑商家户、但没有服务货"这条事实在读数面仍然看得见（{@code householdCountAt} /
      //       {@code MERCHANT_CAPACITY_HOUSEHOLD}）—— 它是 F-2 "没有服务卖" 与 "没有承运人" 分辨的依据。
      //   其余格（无牌价）：判据**一字不变**（{@code capacityMilli > 0}）⇒ 逐值退回改前（I-H3 缺省中性）。
      //   ★ 分格分流的开关仍是 HaulService.pricedAt（唯一拼写点）——不是"算出来恰好相等"。
      MerchantCapacity capacity =
          servicePriced
              // ★★ F-3：服务成市格的 tier / 半径由**服务货可用量**派生（{@link MerchantCapacity#ofService}），
              //   不再由劳动 + 工具派生；labor/tool 两栏降为读数（见该方法注）。
              ? MerchantCapacity.ofService(
                  household,
                  hex0,
                  serviceAvailableMilli,
                  row.participationAdjustedLaborMilli(),
                  toolMilli)
              : MerchantCapacity.of(
                  household, hex0, row.participationAdjustedLaborMilli(), toolMilli);
      if (capacity.capacityMilli() <= 0L && !servicePriced) {
        continue; // 0 运力 = 不进池（无牌价格：逐字等于改前；"有这家户"与"它提供运力"是两件事）
      }
      if (servicePriced && serviceAvailableMilli <= 0L) {
        // F-2 具名归因：**有提供商、但没有货**（与"没有提供商"分开计数；只进读数，不改判据）。
        serviceAbsent.merge(NO_SERVICE_GOODS_REASON, 1L, Math::addExact);
      }
      long askPerMille = askPerMilleOf(capacity, priced);
      maxAsk = Math.max(maxAsk, askPerMille);
      HexCoord hex = capacity.hex();
      // ★★ M-C：纯商号（H-2：**主业** ∈ merchant.*）/ 顺便跑商（merchant 只在副业）—— 免运费与利润算式的分流依据；
      //   判据的唯一拼写点在 {@link MerchantIdentity}。
      boolean pureMerchant = MerchantIdentity.isPureMerchant(standing, classPositions);
      long workBudgetMilli = servicePriced ? serviceAvailableMilli : capacity.capacityMilli();
      // ★★ A3：`posted`（有没有逐户挂运力单）随自报价簿退役 —— 现在**不存在**逐户覆写，"限价"是条目的纯派生量
      //   （{@link #askPerMilleOf}）⇒ 该读数栏恒为假、已从条目与日志里删除。
      pool.computeIfAbsent(hex, ignored -> new ArrayList<>())
          .add(new Entry(capacity, askPerMille, pureMerchant, workBudgetMilli));
      totals.merge(hex, workBudgetMilli, Math::addExact);
      counted++;
    }
    LinkedHashMap<HexCoord, List<Entry>> frozen = new LinkedHashMap<>();
    LinkedHashMap<HexCoord, Long> frozenTotals = new LinkedHashMap<>();
    LinkedHashMap<HouseholdId, Entry> frozenByHousehold = new LinkedHashMap<>();
    // ★★★ W7（2026-10-11）：议价权占比的分母改成**全池总量**（用户裁定：运力池全市场区共享）。
    //   改前分母 = 本格总量 ⇒ "占比"只在本格内有意义；全局共享之后分配序是**一份**全局序，
    //   分母必须与那个序的作用域一致（否则"占比 1000‰"的脚夫会排在"占比 100‰"的富商前面，与规模无关，
    //   是改前逐格语境的残留）。★ 只有一个非空格的世界里，两种分母**逐值相同** ⇒ 既有世界逐值可比。
    long poolTotalMilli = 0L;
    for (long value : totals.values()) {
      poolTotalMilli = Math.addExact(poolTotalMilli, value);
    }
    List<Entry> allEntries = new ArrayList<>((int) Math.min(counted, 4096L));
    for (Map.Entry<HexCoord, List<Entry>> entry : pool.entrySet()) {
      List<Entry> entries = entry.getValue();
      for (Entry item : entries) {
        item.sharePerMille = item.capacity.sharePerMilleOf(poolTotalMilli);
      }
      // ★★ 逐格序（保留）：分配序 = 议价权降序 → 家户 id 升序；报价口径前面加限价升序。
      //   它现在只服务逐格读数（"这一格的承运方怎么排"）与既有夹具/测试的可比性。
      Comparator<Entry> order =
          Comparator.comparingLong((Entry item) -> -item.sharePerMille)
              .thenComparing(item -> item.capacity.household().value());
      if (priced) {
        order = Comparator.comparingLong((Entry item) -> item.askPerMille).thenComparing(order);
      }
      entries.sort(order);
      frozen.put(entry.getKey(), Collections.unmodifiableList(entries));
      frozenTotals.put(entry.getKey(), totals.getOrDefault(entry.getKey(), 0L));
      allEntries.addAll(entries);
      for (Entry item : entries) {
        frozenByHousehold.put(item.capacity.household(), item);
      }
    }
    // ★★★ W7：**全局分配序**（唯一分配次序）—— 同一把比较器，作用域 = 全池。
    Comparator<Entry> globalComparator =
        Comparator.comparingLong((Entry item) -> -item.sharePerMille)
            .thenComparing(item -> item.capacity.household().value());
    if (priced) {
      globalComparator =
          Comparator.comparingLong((Entry item) -> item.askPerMille)
              .thenComparing(globalComparator);
    }
    allEntries.sort(globalComparator);
    List<Entry> globalOrder = Collections.unmodifiableList(allEntries);
    MerchantCapacityCore built =
        new MerchantCapacityCore(
            Collections.unmodifiableMap(frozen),
            Collections.unmodifiableMap(frozenTotals),
            Collections.unmodifiableMap(frozenByHousehold),
            globalOrder,
            counted,
            priced,
            maxAsk,
            haulServiceHexes,
            serviceAbsent,
            day);
    // ★★ G3-leftovers：本池的逐户**装配**读数（事件 {@code MERCHANT_CAPACITY_HOUSEHOLD}）**不在这里发射** ——
    //   发射点仍是 tick 面的装配调用方（{@code EconomySettlement} 紧随本装配之后，与 {@code
    //   CAPACITY_QUOTE_BOOK} 同款先例）：{@link #logHouseholdAssembly(long)}。★ W7-fix3 起本方法**拿到了**世界日
    //   （第 8 参）⇒ 原注释"本方法拿不到世界日"已不成立；但那条事件的发射点**不动**（读数一条不多、一条不少）：
    //   日在本类只有两个落点 —— 装配点注入的 {@link #day}（供 {@link #select} 的 lane 读数）与
    //   {@link #logRoundSummary} / {@link #logHouseholdAssembly} 的入参。
    return built;
  }
}

package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.EconomySettlement.DEBT_REPAYMENT_SHARE_PER_MILLE;
import static io.mosire.simos.economy.time.EconomySettlement.GRAIN;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class SettlementMath {

  /** 把"逐家户 × 逐商品"的本地累加器并进全局累加器（整数加法；键序 = 首次出现序）。 */
  static void mergeGoodsInto(
      Map<HouseholdId, Map<CommodityId, Long>> target,
      Map<HouseholdId, Map<CommodityId, Long>> source) {
    for (Map.Entry<HouseholdId, Map<CommodityId, Long>> entry : source.entrySet()) {
      for (Map.Entry<CommodityId, Long> quantity : entry.getValue().entrySet()) {
        addGoods(target, entry.getKey(), quantity.getKey(), quantity.getValue());
      }
    }
  }

  /**
   * ★★ <b>把一笔需求按各家的<b>人口</b>分派</b>（{@link ProportionalSplit#byDenominator}：**最大余数法** ⇒ Σ分派 ==
   * total）。
   *
   * <p>★ <b>口径与改前的分界</b>：改前这件事发生在 {@code rowSharesOf} 里、且分母是"该产业家户的总人口"、**逐行向下取整后余数丢弃** ⇒
   * 需求被切碎（实测：50 台织机只开 48 台）。现在同一件事只做一次、余数按**最大余数法**分派 ⇒ <b>总量一分不丢</b>。
   *
   * <p>★ 人口为 0 的家户**不进表**（份额恒 0 ⇒ 与"它不出工"同义 —— 与改前的门槛逐字一致）；Σ人口为 0 ⇒ 空表（没有人 ⇒ 没有分摊， 于是"料筹不到"由 {@code
   * usableScale} 那一路表达）。
   *
   * @return 键序 = {@code keys} 的传入序（保序，可复现）
   */
  static Map<HouseholdId, Long> apportionToHouseholds(
      List<HouseholdId> keys, Map<HouseholdId, HouseholdEconomy> householdEconomies, long total) {
    Map<HouseholdId, Long> apportioned = new LinkedHashMap<>();
    if (keys.isEmpty() || total <= 0L) {
      return apportioned;
    }
    long population = 0L;
    for (HouseholdId key : keys) {
      population += householdEconomies.get(key).population();
    }
    if (population <= 0L) {
      return apportioned;
    }
    long[] weights = new long[keys.size()];
    for (int i = 0; i < keys.size(); i++) {
      weights[i] = householdEconomies.get(keys.get(i)).population();
    }
    long[] parts = ProportionalSplit.byDenominator(total, weights, population);
    for (int i = 0; i < keys.size(); i++) {
      if (parts[i] > 0L) {
        apportioned.put(keys.get(i), parts[i]);
      }
    }
    return apportioned;
  }

  /**
   * 第二层兜底的取用次序：**持仓量降序**（谁缸里多谁先出）；<b>并列时保持传入序</b>（{@code List.sort} 是稳定排序 ⇒ 结果是"传入序 +
   * 持仓量"的纯函数，可复现）。
   */
  static List<HouseholdId> byStockDescending(
      List<HouseholdId> keys,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      CommodityId commodity) {
    List<HouseholdId> sorted = new ArrayList<>(keys);
    sorted.sort(
        Comparator.comparingLong((HouseholdId key) -> stockOf(householdGoods, key, commodity))
            .reversed());
    return sorted;
  }

  /** {@code 余额 − 冻结}（逐键；缺冻结 = 0；结果夹到 ≥ 0；保序；空 ⇒ 空表）。 */
  static <K> Map<K, Long> netOf(Map<K, Long> stock, Map<K, Long> frozen) {
    if (stock == null || stock.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<K, Long> net = new LinkedHashMap<>();
    for (Map.Entry<K, Long> entry : stock.entrySet()) {
      long held = entry.getValue() == null ? 0L : entry.getValue();
      long blocked = frozen == null ? 0L : frozen.getOrDefault(entry.getKey(), 0L);
      long available = Math.max(0L, held - blocked);
      if (available > 0L) {
        net.put(entry.getKey(), available);
      }
    }
    return net;
  }

  /**
   * ★★★ <b>v3.6（2026-10-11）：可动商品 = max(0, 余额 − 冻结)</b> —— <b>不扣任何保留量</b>。
   *
   * <p>★★ <b>用户原话（第六批）</b>：「<b>为啥要设还债窗口，应当是有库存就还，没库存就不还啊</b>」⇒ 还债可用量 = 家户手头库存本身 （含货币；介质序仍按 S-5 =
   * 预估价值最低优先）。
   *
   * <p>★★ <b>它作废了什么（两条，均不再保留）</b>：① 更早的 "自然口粮日需求 × {@code
   * DEBTOR_SUBSISTENCE_RESERVE_DAYS}"（那条独立的一日口粮账）；② W2 上一版的"下轮生产方式的生产花费（内含口粮，S-10）"保留 —— 后者连同
   * {@code productionCostsByHousehold} 一并删除（AGENTS §一.11：旧设计不留）。 ★ 唯一保留的"前瞻扣除"在<b>卖侧</b>（{@code
   * MarketSettlement} 的 {@code retention}，用户 0–8 第 5 条），本方法不碰它。
   *
   * <p>★ 冻结不越：任何腿都不越过 {@code householdFrozenGoods}。还款份额（{@link #DEBT_REPAYMENT_SHARE_PER_MILLE}，出厂
   * 1000‰ = 全还）对**每一种可动商品**同式（出厂值 ⇒ 逐值等于"不乘"）。
   */
  static Map<CommodityId, Long> spendableGoods(
      HouseholdId debtor,
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods) {
    Map<CommodityId, Long> stock = householdGoods.getOrDefault(debtor, Map.of());
    if (stock.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<CommodityId, Long> spendable = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : stock.entrySet()) {
      long amount = entry.getValue() == null ? 0L : entry.getValue();
      if (amount <= 0L) {
        continue;
      }
      long free =
          Math.max(0L, amount - frozenGoodsOf(householdFrozenGoods, debtor, entry.getKey()));
      // ★★ v3.6：**这里不再扣任何保留量**（旧的两条：一日口粮 / 下轮生产花费 —— 均已作废）。
      // ★ 还款份额（{@link #DEBT_REPAYMENT_SHARE_PER_MILLE}，出厂 1000‰ = 全还）。
      free = free * DEBT_REPAYMENT_SHARE_PER_MILLE / 1000L;
      if (free > 0L) {
        spendable.put(entry.getKey(), free);
      }
    }
    return spendable;
  }

  /** 可动货币 = max(0, 逐币种余额 − 冻结)；不跨币种求和/折换（D-023：不做 FX）。 */
  static Map<CurrencyId, Long> spendableMoney(
      HouseholdId debtor,
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney) {
    Map<CurrencyId, Long> wallet = householdMoney.getOrDefault(debtor, Map.of());
    if (wallet.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<CurrencyId, Long> spendable = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : wallet.entrySet()) {
      long amount = entry.getValue() == null ? 0L : entry.getValue();
      if (amount <= 0L) {
        continue;
      }
      long free =
          Math.max(0L, amount - frozenMoneyOf(householdFrozenMoney, debtor, entry.getKey()));
      if (free > 0L) {
        spendable.put(entry.getKey(), free);
      }
    }
    return spendable;
  }

  /** 产业 id 里的格键 → 坐标（{@link IndustryHexKeys} 是唯一拼写点）；拿不到 ⇒ 抛（产权账户必须有地点）。 */
  static HexCoord hexOfIndustry(IndustryId id) {
    return HexCoord.parse(
        IndustryHexKeys.hexKeyOf(id)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "产业 id 里没有格键，无法为产出落产权账户（账户 = (actor, location)，不许拿 (0,0) 顶替）: " + id)));
  }

  /**
   * 两张**逐商品**发生额表相加（{@code first} 可为 null = 无这一份；两份都保序）。
   *
   * <p>→ R3 的用途：① 当日的 {@code consumed} = 现扣投入 + 日耗 + 生产损耗；② 跨日把当天发生额并入本期流水（§十一）。 空表进空表出（{@code 空 +
   * 空 == 空}），故 {@code Map.of()} 的纯形态不会退化成"一个键值为 0 的假键"。
   */
  static Map<CommodityId, Long> mergeGoods(
      Map<CommodityId, Long> first, Map<CommodityId, Long> second) {
    if ((first == null || first.isEmpty()) && (second == null || second.isEmpty())) {
      return Map.of();
    }
    LinkedHashMap<CommodityId, Long> merged = new LinkedHashMap<>();
    if (first != null) {
      merged.putAll(first);
    }
    if (second != null) {
      for (Map.Entry<CommodityId, Long> entry : second.entrySet()) {
        merged.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    return merged;
  }

  /** ★ E4c：逐币种偿还表相加（与 {@link #mergeGoods} 同一条"空表进空表出、同 key 相加"口径）。 */
  static Map<CurrencyId, Long> mergeMoney(
      Map<CurrencyId, Long> first, Map<CurrencyId, Long> second) {
    return mergeLongMaps(first, second);
  }

  /** ★ E4c：按稳定 unit 串分组的资本化读数相加（键 = {@code DebtUnit.key()}，如 {@code "money:silver"}）。 */
  static Map<String, Long> mergeNamedQuantities(Map<String, Long> first, Map<String, Long> second) {
    return mergeLongMaps(first, second);
  }

  /** 任意键 → long 的发生额表相加（整数加法；两份都保序；空表进空表出）。 */
  static <K> Map<K, Long> mergeLongMaps(Map<K, Long> first, Map<K, Long> second) {
    if ((first == null || first.isEmpty()) && (second == null || second.isEmpty())) {
      return Map.of();
    }
    LinkedHashMap<K, Long> merged = new LinkedHashMap<>();
    if (first != null) {
      merged.putAll(first);
    }
    if (second != null) {
      for (Map.Entry<K, Long> entry : second.entrySet()) {
        merged.merge(entry.getKey(), entry.getValue(), Long::sum);
      }
    }
    return merged;
  }

  /** 把一笔发生额记进"逐行 × 逐商品"的累加器（{@code amount == 0} ⇒ 不落键，保持空表的纯形态）。 */
  static void addGoods(
      Map<HouseholdId, Map<CommodityId, Long>> acc,
      HouseholdId key,
      CommodityId commodity,
      long amount) {
    if (amount == 0L) {
      return;
    }
    acc.computeIfAbsent(key, ignored -> new LinkedHashMap<>()).merge(commodity, amount, Long::sum);
  }

  /**
   * ★★ **定点整数分配**（§4 周期结算第 4 步；v2 spec §八.7）：{@code parts[i] = total × weights[i] ÷
   * Σ权重}，残差按**最大余数法** 分派（余数 {@code total × weights[i] mod Σ权重} 大者先得、同余数按下标序）⇒ **Σparts == total**。
   *
   * <p>★★ **分母是 Σ权重**（这与 v1 不同：v1 的分母是恒定的 1000‰，故 {@code Σ权重 < 1000} 时那个缺口会被"人人均摊" —— 按格净产
   * 5,950,000 算，地主实得 2,975 而按权重只该得 429，**实得是应得的 7 倍**，制度分配被摊成了平均分配）。 用 Σ权重 后，各行所得**恰好**与权重成比例（差 ≤ 1
   * 个最小单位），残差只是"取整余数"。
   *
   * <p>★ **与 {@code EconomySeeder.splitProportional} 是同一个函数**（都走 {@link
   * ProportionalSplit#byDenominator}），故 "口径统一"是一条**可执行**的事实而不是注释 —— 跨模块一致性用例逐值对拍（{@code
   * EconomyAllocationConsistencyTest}）。
   *
   * @param total 待分配的总量（毫粮）；不得为负
   * @param weights 各行的分配权重（非负；v1 = 生产资料权重 × 土地占比 + 劳动权重 × 劳动占比，量纲是千分）
   */
  static long[] allocate(long total, long[] weights) {
    long weightSum = 0L;
    for (long weight : weights) {
      weightSum += weight;
    }
    return ProportionalSplit.byDenominator(total, weights, weightSum);
  }

  // ── 分组与排序 ─────────────────────────────────────────────────────────────────────

  /**
   * 按格（{@link IndustryHexKeys} 的 {@code <q>_<r>}）分组，格的顺序与行序都显式排序（可复现）。
   *
   * <p>★ H4：可见性从 {@code private} 放宽到**包内** —— {@code MarketSettlement} 要问同一个问题（"这一格有哪些家户"），
   * 而它**只能有一个答案**（两处各写一份分组 = 同一个量的第二处拼写点）。
   */
  static Map<String, List<HouseholdId>> rowsByHex(
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    Map<String, List<HouseholdId>> byHex = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> householdEconomyEntry :
        householdEconomies.entrySet()) {
      HexCoord hex = householdEconomyEntry.getValue().view().hex();
      // ★ S1：格来自行的**视图**（键本身不再带格；{@link IndustryHexKeys#hexKey} 是拼写点）。
      byHex
          .computeIfAbsent(IndustryHexKeys.hexKey(hex.q(), hex.r()), ignored -> new ArrayList<>())
          .add(householdEconomyEntry.getKey());
    }
    LinkedHashMap<String, List<HouseholdId>> sorted = new LinkedHashMap<>();
    List<String> hexKeys = new ArrayList<>(byHex.keySet());
    hexKeys.sort(Comparator.naturalOrder());
    for (String hexKey : hexKeys) {
      List<HouseholdId> members = byHex.get(hexKey);
      // ★ 行序 = 阶层（字典序）→ 居住类型（直接读视图；键不再带这两维）。
      //   ★ S3：landless_laborer/artisan/official 也按同一个 value 字典序参与，只用于确定性；不映射回旧四档。
      members.sort(
          Comparator.comparing(
                  (HouseholdId id) -> householdEconomies.get(id).view().stratum().value())
              .thenComparing(id -> householdEconomies.get(id).view().residence()));
      sorted.put(hexKey, members);
    }
    return sorted;
  }

  // ★★★ R2（2026-10-11，控制方裁定）：原 `unitExclusionsMinusGovernmentTreasuries` /
  //   `governmentTreasuryHouseholds` 一对助手**已删除** —— 它们做的"把政府国库户从单位户集合里摘出来"这一步，
  //   现在由**唯一判据** `MarketRound.householdMarketRole` 承担（国库户口径优先于单位户集合判定，更严的一条赢）。
  //   ★ 删掉而不是留着：多一份"谁退出/谁只买"的拼法就是第二个权威（与 R1 删 `mergeMarketExclusions` 同一条理由）。
  //   ★ 组合根注入的 Z7b 集合自本批起**原样照收**（`settleOneDayInto` 的 `effectiveMarketExcludedHouseholds`）。

  // ── 家户账（会话工作副本）的读写助手 ────────────────────────────────────────────────

  // ★★ R1（2026-10-09）：{@code mergeMarketExclusions}（"单位户 ∪ 政府国库户"的有效排除集）**已删除** ——
  //   本批撤销的正是"国库户退出商品市场"那一半。国库户现在的身份是"只按授权下单的市场参与者"（见
  //   GovernmentMarketMandatePlan 与 MarketSettlement 的同名分支），不再是排除集的一员；单位户那一半由组合根
  //   经 marketExcludedHouseholds 照旧传入。★ 删掉它而不是留着：多一份"谁退出商品市场"的拼法就是第二个权威。

  // ── 家户账（会话工作副本）的读写助手 ────────────────────────────────────────────────
  //
  // ★★ H1：商品库存在**会话工作副本**里（|Map<HouseholdId, Map<CommodityId, Long>>|；裁定 K1），行里没有 goods 了。
  //   三个助手是这份副本的**唯一读写点**（口径只有一处）：
  //     · 缺失键 = 该家户没有该商品（同 HouseholdEconomy.goods 原来的口径，见 EconomyDayStepper 的类注）；
  //     · 写 ≤ 0 ⇒ **去掉该键**（保持"空商品表"的纯形态，不落一个值为 0 的假键）；
  //     · 内层表**只读**：换值一律 put 一张新表（绝不在调用方给的表上做增删）⇒ 任何拿到的快照都不会被后续结算改掉。

  /** 某家户在某商品上的余额（没有这个键 ⇒ 0）。 */
  static long stockOf(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      HouseholdId key,
      CommodityId commodity) {
    return householdGoods.getOrDefault(key, Map.of()).getOrDefault(commodity, 0L);
  }

  /** 粮的余额（{@link #stockOf} 的粮特化 —— 借粮那一段读得最频繁）。 */
  static long grainOf(Map<HouseholdId, Map<CommodityId, Long>> householdGoods, HouseholdId key) {
    return stockOf(householdGoods, key, GRAIN);
  }

  /** ★ E4c：某家户在某商品上的冻结量（没有键 ⇒ 0）—— 偿还预算不得越过冻结。 */
  static long frozenGoodsOf(
      Map<HouseholdId, Map<CommodityId, Long>> householdFrozenGoods,
      HouseholdId key,
      CommodityId commodity) {
    Map<CommodityId, Long> frozen = householdFrozenGoods.get(key);
    return frozen == null ? 0L : frozen.getOrDefault(commodity, 0L);
  }

  /**
   * 把某家户在某商品上的余额**换成** {@code amount}（{@code ≤ 0} ⇒ 去掉该键）。
   *
   * <p>★ **只动那一个键**：同一个家户的账上同时住着粮、纤维、布、工具…… ⇒ 换一种商品绝不能把别的商品顺手抹掉 （旧版 {@code withGoodsGrain}
   * 只搬粮，多商品下那样写会静默清空其它商品）。
   */
  static void setStock(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      HouseholdId key,
      CommodityId commodity,
      long amount) {
    Map<CommodityId, Long> goods = new LinkedHashMap<>(householdGoods.getOrDefault(key, Map.of()));
    if (amount <= 0L) {
      goods.remove(commodity);
    } else {
      goods.put(commodity, amount);
    }
    householdGoods.put(key, goods);
  }

  /** 在某家户的账上**加一笔**（{@code delta} 可为负；走 {@link #setStock} 的同一口径 ⇒ 归零即去键）。 */
  static void addStock(
      Map<HouseholdId, Map<CommodityId, Long>> householdGoods,
      HouseholdId key,
      CommodityId commodity,
      long delta) {
    if (delta == 0L) {
      return;
    }
    setStock(householdGoods, key, commodity, stockOf(householdGoods, key, commodity) + delta);
  }

  // ── 家户**货币**账（会话工作副本）的读写助手（H4）────────────────────────────────────
  //
  // ★★ 形制与上面那三个**逐字同款**（K14：货币副本与商品副本同形、同生命周期、同样不进 EconomyData）：
  //   · 外层键缺失 = 该家户没有账 ⇒ **fail-closed**（requireHouseholdMoney 判死，同 requireHouseholdAccounts）；
  //   · 内层缺失币种 = 该币种余额 0；
  //   · 写 ≤ 0 ⇒ **去掉该币种键**（保持"空钱包"的纯形态）；
  //   · 内层表**只读**：换值一律 put 一张新表。

  /** 某家户在某币种上的余额（没有这个键 ⇒ 0）。 */
  static long moneyOf(
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      HouseholdId key,
      CurrencyId currency) {
    return householdMoney.getOrDefault(key, Map.of()).getOrDefault(currency, 0L);
  }

  /** ★ E4c：某家户在某币种上的冻结量（没有键 ⇒ 0）—— 货币债偿还预算不得越过冻结。 */
  static long frozenMoneyOf(
      Map<HouseholdId, Map<CurrencyId, Long>> householdFrozenMoney,
      HouseholdId key,
      CurrencyId currency) {
    Map<CurrencyId, Long> frozen = householdFrozenMoney.get(key);
    return frozen == null ? 0L : frozen.getOrDefault(currency, 0L);
  }

  /** 把某家户在某币种上的余额**换成** {@code amount}（{@code ≤ 0} ⇒ 去掉该币种键）。 */
  static void setMoney(
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      HouseholdId key,
      CurrencyId currency,
      long amount) {
    Map<CurrencyId, Long> wallet = new LinkedHashMap<>(householdMoney.getOrDefault(key, Map.of()));
    if (amount <= 0L) {
      wallet.remove(currency);
    } else {
      wallet.put(currency, amount);
    }
    householdMoney.put(key, wallet);
  }

  /** 在某家户的钱包上**加一笔**（{@code delta} 可为负；走 {@link #setMoney} 的同一口径 ⇒ 归零即去键）。 */
  static void addMoney(
      Map<HouseholdId, Map<CurrencyId, Long>> householdMoney,
      HouseholdId key,
      CurrencyId currency,
      long delta) {
    if (delta == 0L) {
      return;
    }
    setMoney(householdMoney, key, currency, moneyOf(householdMoney, key, currency) + delta);
  }
}

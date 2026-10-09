package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
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
import java.util.OptionalLong;

/**
 * ★★ <b>P-T5b：家户买单的支付币 = "该户当轮选定的最强持有币"（口岸设计书 §20.4 的前置依赖、本类是它<b>唯一</b>拼写点）</b>。
 *
 * <pre>
 * 用户原话（2026-10-10 F-3，逐字）："挂单全部，能换多少是多少，遵循谁有钱谁先换原则，因为这是在购买环节执行的"
 * 用户原话（2026-10-10 F-1，逐字）："用本格币付的最少的就是购买力最强"
 * ⇒ P-T5 的 FX 轮把弱币换成"最强持有币"之后，本轮的买单必须<b>真的能用那种币付出去</b>
 *   （否则换成的最强币在本格市场付不出去 = 设计书 §20.4 点名的缺口）。
 * </pre>
 *
 * <p>★★ <b>口径（冻结，逐条）</b>：
 *
 * <ol>
 *   <li>{@link #of} 逐户算 F-1 购买力（{@link HouseholdPurchasingPower#needCostMilli}：付清 {@code
 *       naturalNeeds} 要付多少毫该币）， 取<b>强度全序首项</b>（{@link HouseholdPurchasingPower#strongest}：cost
 *       升序、币种 id 升序）= 该户本轮的支付币；
 *   <li><b>缺省 / 单一币 / 不可比 / 无需求篮子</b> ⇒ 该户不进表 ⇒ 调用方回落<b>本格计价币</b> （{@link #payCurrencyFor} 的 {@code
 *       getOrDefault}）⇒ <b>旧世界逐值不变</b>（I-C2 的缺省语义中性）；
 *   <li><b>它不是状态</b>：逐轮由"家户行 + 持币 + 市场价表"现算，不进 {@code EconomyData}、不进变更集、不落盘；
 *   <li><b>确定性（I7 / I-C5）</b>：遍历序 = hex (q,r) 升序 → 家户行序；每户的选币只依赖<b>该户自己</b>的内容 ⇒ 与任何 map
 *       的迭代序无关；保序表用 {@link LinkedHashMap} + {@link Collections#unmodifiableMap} （<b>禁</b> {@code
 *       Map.copyOf}／{@code Set.copyOf} 保序）。
 * </ol>
 *
 * <p>★★ <b>为什么是"一次算好、逐户可查"而不是在订单生成里现算</b>：订单生成按市场区<b>并行</b>跑 （{@code SettlementExecutor} 的
 * worker），逐户现算会变成共享可变缓存 = 数据竞争；且同一户在同一个 hex 的<b>每个商品</b>上都会被问一次 ⇒
 * 现算会把"付清需求要付多少"重复算几十遍。故本表在并行段<b>之前</b>单线程建好，此后<b>只读</b>。
 *
 * <p>★★ <b>它不决定"按什么价成交"</b>：价格尺度仍是卖方格价表的计价币（计划 §2.1 冻结），单价与保留价都在那个尺度上；本类只回答
 * <b>"这一户用哪种钱付"</b>。折算（本格计价币 ↔ 支付币）一律经注入的<b>同一份</b> {@link CurrencyValuation} （{@link
 * #valueMicroOf}）—— 与撮合、税腿、运费腿用的是同一个实例（禁两套估值漂开）。
 *
 * <p>★ <b>缺价的处置</b>：某币说不出价（{@link #valueMicroOf} 返回 {@code 0}）⇒ 调用方<b>具名归因</b>、本单不生成， <b>不</b>静默按
 * 1:1、<b>不</b>静默退回本格计价币（冻结口径第 4 条）。
 */
final class MarketPayChoice {

  private static final org.slf4j.Logger MARKET = EconomyLog.market();

  /** ★ 缺省选择面（谁都不选）：订单币恒 = 本格计价币 ⇒ 旧世界逐值不变（不发一条日志、不建任何价表）。 */
  private static final MarketPayChoice NONE =
      new MarketPayChoice(Collections.emptyMap(), null, null);

  /** 家户 → 本轮支付币（保序不可变；只含"选出来了"的户）。 */
  private final Map<HouseholdId, CurrencyId> byHousehold;

  /** 区域拓扑（用它把 hex 归到区，取"该币在这个区认不认得出"的口径）；{@code null} = 缺省面（不折算）。 */
  private final MarketTopology topology;

  /** 本轮<b>唯一一份</b>"钱的价"（与撮合/税/运费共用同一个实例）。 */
  private final CurrencyValuation valuation;

  private MarketPayChoice(
      Map<HouseholdId, CurrencyId> byHousehold,
      MarketTopology topology,
      CurrencyValuation valuation) {
    this.byHousehold = Objects.requireNonNull(byHousehold, "byHousehold");
    this.topology = topology;
    this.valuation = valuation;
  }

  /** ★ <b>缺省选择面</b>：订单币 = 本格计价币（旧调用点与不注入币种面的世界走它）⇒ 逐值退回改前行为（I-C2）。 */
  static MarketPayChoice none() {
    return NONE;
  }

  /**
   * ★★ <b>逐户选出"最强持有币"（F-1/F-2 的落点，唯一拼写点）</b>。
   *
   * <pre>
   * 单币世界（市场计价币 ∪ 逐户持币）⇒ 空表（连价表都不建，早退门与 FxSettlement 同一条）
   * 逐 hex(q,r 升序) → 逐户（行序）：
   *   跳过：市场排除户 / 国库户（只按授权下单）/ 无家户行 / naturalNeeds 为空 / 可花币 &lt; 2 种
   *   逐币（可花额 &gt; 0，币种 id 升序）：F-1 cost ⇒ 缺价 ⇒ 该币不参与比较（不猜）
   *   可比币 &lt; 2 ⇒ 不选（回落本格计价币）
   *   否则 ⇒ 最强 = 强度全序首项
   * </pre>
   *
   * @param round 本轮只读会话视图（持币/可花额/家户行/排除集都在它上面）
   * @param markets 价格表（键 = 格；缺格 = 该格没有市场）
   * @param topology 区域拓扑（取"该币法定区价表"）
   * @param rowsByHex 格键 → 该格家户（{@code EconomySettlement.rowsByHex} 的产出；调用方已建好，不重复建）
   * @param valuation 本轮<b>唯一一份</b> {@link CurrencyValuation}（同一个实例贯穿订单/撮合/税/运费）
   * @param emitLog 要不要发本轮的事件（★ <b>只有"每轮一次"的结算路径传 true</b>：读口 {@code MarketReadout} 每次 API
   *     请求都会调一遍本方法，在那里发日志会按请求数放大 —— 与 {@code planFor}/{@code logLifeReserves} 的分工同一条纪律）
   */
  static MarketPayChoice of(
      MarketSettlement.MarketRound round,
      Map<HexCoord, Market> markets,
      MarketTopology topology,
      Map<String, List<HouseholdId>> rowsByHex,
      CurrencyValuation valuation,
      boolean emitLog) {
    Objects.requireNonNull(round, "round");
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(topology, "topology");
    Objects.requireNonNull(rowsByHex, "rowsByHex");
    Objects.requireNonNull(valuation, "valuation");
    if (!FxSettlement.hasMultipleCurrencies(round, markets)) {
      // ★ 单币世界：一个数都不动（I-C2 的落点），**也不发任何事件** —— 旧世界的日志面同样一个字不改。
      return new MarketPayChoice(Collections.emptyMap(), topology, valuation);
    }
    Map<CurrencyId, HouseholdPurchasingPower.ZoneTable> zoneTables =
        HouseholdPurchasingPower.zoneTables(markets, topology);
    List<HexCoord> hexes = new ArrayList<>(markets.keySet());
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    Map<HouseholdId, CurrencyId> chosen = new LinkedHashMap<>();
    int excluded = 0;
    int noNeeds = 0;
    int singleCoin = 0;
    int noComparablePower = 0;
    int localNumeraire = 0;
    for (HexCoord hex : hexes) {
      Market market = markets.get(hex);
      if (market == null) {
        continue;
      }
      MarketRegion ownRegion = topology.contains(hex) ? topology.regionOf(hex) : null;
      for (HouseholdId household :
          rowsByHex.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of())) {
        if (household == null
            || round.marketExcludedHouseholds().contains(household)
            || round.govMandates().isAuthorizationOnly(household)) {
          excluded++;
          continue;
        }
        HouseholdEconomy row = round.householdEconomies().get(household);
        if (row == null || row.naturalNeeds().isEmpty()) {
          noNeeds++;
          continue;
        }
        List<CurrencyId> held = FxSettlement.heldCurrencies(round, household);
        if (held.size() < 2) {
          singleCoin++;
          continue;
        }
        Map<CurrencyId, Long> power = new LinkedHashMap<>();
        for (CurrencyId currency : held) {
          HouseholdPurchasingPower.ZoneTable table =
              HouseholdPurchasingPower.tableFor(currency, ownRegion, zoneTables, markets);
          OptionalLong cost =
              table == null
                  ? OptionalLong.empty()
                  : HouseholdPurchasingPower.needCostMilli(row, table);
          if (cost.isPresent()) {
            power.put(currency, cost.getAsLong());
          }
        }
        if (power.size() < 2) {
          noComparablePower++;
          continue;
        }
        CurrencyId pay = HouseholdPurchasingPower.strongest(power);
        chosen.put(household, pay);
        if (pay.equals(market.numeraire())) {
          localNumeraire++;
        }
        if (emitLog && MARKET.isTraceEnabled()) {
          // ★ 逐户明细（§一.9 的 TRACE 档）：F-3 的"换完之后花得出去"就靠这一行读出来是哪一户用哪种币付。
          EventLog.channel(MARKET)
              .trace(
                  LogEvent.of(
                      "MARKET_PAY_CURRENCY_HOUSEHOLD",
                      EconomyLogSource.ECONOMY_MARKET,
                      "day",
                      round.day(),
                      "household",
                      household.value(),
                      "hex",
                      hex.toString(),
                      "payCurrency",
                      pay.value(),
                      "marketNumeraire",
                      market.numeraire().value(),
                      "powerMilli",
                      powerSummary(power)));
        }
      }
    }
    if (emitLog && !chosen.isEmpty()) {
      // ★ 业务上"这一轮有家户按最强持有币下单" ⇒ INFO 一条（具名计数；§一.9：生命周期/这一轮发生了什么）。
      //   ★ payInOtherCurrency = 用**非本格币**付的户数（= 本批真正改变付款币种的规模）。
      EventLog.channel(MARKET)
          .info(
              LogEvent.of(
                  "MARKET_PAY_CURRENCY_ROUND",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  round.day(),
                  "households",
                  chosen.size(),
                  "payInMarketNumeraire",
                  localNumeraire,
                  "payInOtherCurrency",
                  chosen.size() - localNumeraire,
                  "currencies",
                  currencySummary(chosen)));
    }
    if (emitLog && MARKET.isDebugEnabled()) {
      // ★ 判据档（§一.9 的 DEBUG）：为什么没有更多户选币 —— 逐档具名计数，不写成"没发生"。
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MARKET_PAY_CURRENCY_PLAN",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  round.day(),
                  "chosen",
                  chosen.size(),
                  "excluded",
                  excluded,
                  "noNaturalNeeds",
                  noNeeds,
                  "singleCoin",
                  singleCoin,
                  "noComparablePower",
                  noComparablePower));
    }
    return new MarketPayChoice(Collections.unmodifiableMap(chosen), topology, valuation);
  }

  /**
   * ★★ <b>该户本轮用哪种币付</b>：选出来了就用它；否则回落 {@code market.numeraire()}（缺省语义中性）。
   *
   * <p>★ 经营者（{@code household == null}）永远走回落：F-1 的购买力口径是<b>家户</b>的（{@code naturalNeeds} 是家户需求篮子）。
   */
  CurrencyId payCurrencyFor(HouseholdId household, Market market) {
    Objects.requireNonNull(market, "market");
    if (household == null) {
      return market.numeraire();
    }
    return byHousehold.getOrDefault(household, market.numeraire());
  }

  /**
   * ★★ <b>"钱的价"（唯一读取口）：微 {@code market.numeraire()} / 毫 {@code currency}</b>。
   *
   * <pre>
   * currency == 本格计价币 ⇒ 1000（本币对自己 = 面值 1:1，R3 的锚；结构性，不看任何表）
   * 否则                  ⇒ CurrencyValuation.valuationMicro(本格计价币, currency, 该 hex 所属区)
   *                          （世界报价 / 当地实际流通 ⇒ 面值；两者都不是 ⇒ 0 = 说不出价）
   * </pre>
   *
   * @return {@code > 0} = 微本格计价币 / 毫该币；{@code 0} = 说不出价（调用方 fail-closed，禁 1:1）
   */
  long valueMicroOf(Market market, CurrencyId currency, HexCoord hex) {
    Objects.requireNonNull(market, "market");
    Objects.requireNonNull(currency, "currency");
    if (currency.equals(market.numeraire())) {
      return CurrencyValuation.faceValueMicro();
    }
    if (topology == null || valuation == null || hex == null || !topology.contains(hex)) {
      return 0L; // 缺省选择面 / 说不出这是哪个区 ⇒ 不折算（绝不按 1:1 猜）
    }
    return valuation.valuationMicro(
        market.numeraire(), currency, topology.regionOf(hex).node().nodeId());
  }

  /** 本轮选出了几个户（0 = 缺省面；只服务日志/测试断言，不参与判定）。 */
  int size() {
    return byHousehold.size();
  }

  /** 保序的"逐币成本"摘要（TRACE 用；只写日志，不参与任何判定）。 */
  private static String powerSummary(Map<CurrencyId, Long> power) {
    StringBuilder text = new StringBuilder("{");
    for (Map.Entry<CurrencyId, Long> entry : power.entrySet()) {
      if (text.length() > 1) {
        text.append(", ");
      }
      text.append(entry.getKey().value()).append('=').append(entry.getValue());
    }
    return text.append('}').toString();
  }

  /** 保序的"币种 → 用它付的户数"摘要（INFO 用；★ 禁跨币求和，这里只数户、不加金额）。 */
  private static String currencySummary(Map<HouseholdId, CurrencyId> chosen) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    List<CurrencyId> ordered = new ArrayList<>(chosen.values());
    ordered.sort(Comparator.comparing(CurrencyId::value));
    for (CurrencyId currency : ordered) {
      counts.merge(currency.value(), 1, Integer::sum);
    }
    StringBuilder text = new StringBuilder("{");
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      if (text.length() > 1) {
        text.append(", ");
      }
      text.append(entry.getKey()).append('=').append(entry.getValue());
    }
    return text.append('}').toString();
  }
}

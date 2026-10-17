package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtSource;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★★ <b>W5.5（2026-10-11，S-12）：货币市场 —— 钱自己也是市场上的一个商品</b>。
 *
 * <p>★★ <b>用户原话（逐字，本类的唯一依据）</b>：
 *
 * <ul>
 *   <li>「把借粮这条路给取缔掉，这条路才是真的不知道哪里冒出来的；借货币可以把其环节放在最开始，<b>如果家户的库存货币无法满足需求， 先借钱再买货</b>，然后后面不就有钱了？」
 *   <li>「我说的是在购买环节，<b>先拿空气购买货币——也就是借钱</b>，然后再购买其他东西，<b>相当于是同一个市场体系里的改造</b>」
 *   <li>「供给这一轮不是在需求挂单之前吗？应当是，<b>在挂商品的时候最后挂钱，买东西的时候先借钱</b>」
 *   <li>「1 肯定要，但是<b>如果实际对不上也不要紧，该怎么算怎么算</b> 2、<b>国家挂不挂单看国家呗</b>，家户有多少借多少，该咋办咋办；
 *       3、<b>肯定保留啊，算是债务的一种</b>」
 * </ul>
 *
 * <p>★★ <b>本类在市场轮里的位置（次序本身是判据）</b>：
 *
 * <pre>
 * ① 挂商品供给/需求（{@code MarketSettlement.clearOncePerCycle} 的计划段：买卖槽 + 结算序）
 * ② 挂钱供给 + ③ 借钱（★ 本类：{@code settle} —— 必须在 {@code commitFreezes} **之前**、撮合之前）
 * ④ 买商品（冻结 → 区内/跨区撮合 → 未付价款记债；lane/运力容量/三层税一道不少）
 * </pre>
 *
 * <p>★★ <b>它搬什么、不搬什么（与已退役 {@code creditRound} 的关键区别）</b>：<b>钱腿只搬钱</b> —— 只是两个家户账户之间的一次货币转移（唯一写口仍是
 * {@code EconomySettlement.applyTransfer}），<b>不碰任何商品</b>； 货一律仍走正规撮合（lane/运力池/三层税一项不少）。旧 {@code
 * creditRound} 正是"在撮合里直接抓货且绕开运费/运力/税" 才把卖方余量一次搬空、导致 day 8 起市场冻结（W2-fix4 账本）—— 本类结构上不可能复发。
 *
 * <p>★★ <b>逐条口径（S-12②–⑦）</b>：
 *
 * <ol>
 *   <li><b>需求</b>（S-12③ 前半）：家户"本期生产花费所需现金"（{@link Input#needByZone()}，调用方按<b>本区当前牌价</b>
 *       从买盘目标折出）<b>−</b> 它当前可用现金（余额 − 冻结，走 {@code MarketSettlement.spendableMoneyOf} 唯一算式）； &gt; 0
 *       ⇒ 它是借款人；
 *   <li><b>供给</b>（S-12③ 后半）：<b>家户</b>的闲钱 = 余额 − <b>生活储备</b>（{@link
 *       MarketSettlement#LENDER_MONEY_BUFFER_PER_CAPITA_MILLI} × 人口，D-030 §3.1 的既有人均现金缓冲）−
 *       <b>本轮自己的生产花费所需现金</b>；≤ 0 ⇒ <b>不出借</b>（不许为负）。★ <b>国库默认不挂钱</b>（S-12⑥）⇒ 政府国库户两侧都不参与；
 *   <li><b>借款人无额度上限，唯一上限 = 放贷池</b>（S-12④）：逐借款人按<b>结算序</b>取用、池空即止（池空 ⇒ 借不到 ⇒
 *       买货那一步自然退到"未付价款记债"，S-12⑦）；
 *   <li><b>价格 = 利率</b>（S-12⑤）：由供需按<b>与 §2.1 商品价同一套</b>自适应算式逐市场轮更新 （{@code
 *       MarketSettlement.adaptiveNextPrice}：缺钱 ⇒ 升、钱多 ⇒ 降、D=S ⇒ 不动、下限 0 ⇒ 白借合法、α = 0 ⇒ 钉死），α 逐市场取自
 *       {@link MarketTable#loanRateAlphaPerMille()}（GM 可调：{@code
 *       economy.SetMarketZoneLoanRateAlpha}）；
 *   <li><b>成交</b>：货币从贷方账户<b>真转到</b>借方账户（唯一写口），并生成一条<b>真实债权人的</b>债务合同 （债务人 = 借方、债权人 = 贷方、单位 =
 *       本区计价币、利率 = <b>本轮清算出的利率</b>、到期 = 下一个周期）。
 * </ol>
 *
 * <p>★★ <b>市场范围 = 逐市场区（本批的具名口径，理由与代价）</b>：一个市场区一张价表（S-6）⇒ 本类也<b>逐区</b>清算： 放贷池 = <b>本区</b>家户的闲钱，借款人
 * = <b>本区</b>家户。<b>本批不做跨区借贷</b>（fail-closed）：跨区放贷会让钱在没有任何
 * 运力/汇率机制的情况下跨区流动（"钱腿只搬钱"的前提是不引入未建模的跨区物流），跨币种借贷还需要一条本批不存在的折算通道。 区序取<b>区结算序</b>（上周期成交额降序 → 区
 * id，S-4/N-1 的同一份盘前快照）；区内借款人按<b>净值名次</b>升序 （与买槽同一把尺）。★ 代价：某区池空而邻区有闲钱时本区借款人借不到（退到赊购）——
 * 这是<b>方向正确</b>的降级。
 *
 * <p>★ <b>确定性（I7）</b>：区序、借款人序、放贷人序全部是内容的纯函数（结算序快照 + 稳定 id），逐户表的迭代序由 {@code LinkedHashMap}（禁 {@code
 * Map.copyOf}）保证 ⇒ 同一状态两次推进产出同一条转移序列。
 *
 * <p>★ <b>不新增任何状态形状</b>：本类只写 ① 账户（经唯一写口）② 既有的 {@code debtContracts} 工作表 ③ 区表里既有的 {@code
 * loanRatePerMille} 一个字段（经 {@link Round#loanRateNext()} 交回调用方写，走会话工作副本）。
 */
final class MoneyMarketSettlement {

  /**
   * ★★ <b>本类的输入</b>（由 {@code MarketSettlement} 在计划段之后、冻结之前装配：那里是唯一看得见买槽与参与者的地方）。
   *
   * @param zoneTables 当刻持久价表（<b>利率/α 的唯一权威</b>；本类只跑有价表的市场）
   * @param orderedZones 本轮的区清算序（结算序快照给出的区名次 → 区 id；与撮合的区遍历序同源）
   * @param ordering 开盘前结算序快照（借款人名次 = 与本轮买槽同一把尺；禁自指）
   * @param zoneCurrency 市场 → 本市场计价币（{@code MarketTable.numeraire}；持久形态下它逐值等于 {@code
   *     MarketZone.legalTender}）
   * @param zoneHouseholds 区 → 本区参与市场轮的家户（规范序；国库户已由调用方剔除）
   * @param needByZone 逐区逐户"本期生产花费所需现金"（毫本区计价币；由买盘目标按本区当前牌价折算）
   * @param householdHex 家户 → 账户所在格（钱腿的 location 字段；钱没有运距，它只做归属标注）
   * @param dueCycle 借款合同的到期周期（= 下一个周期；与赊购同一条）
   */
  record Input(
      Map<MarketZoneId, MarketTable> zoneTables,
      List<MarketZoneId> orderedZones,
      SettlementOrderBook ordering,
      Map<MarketZoneId, CurrencyId> zoneCurrency,
      Map<MarketZoneId, List<HouseholdId>> zoneHouseholds,
      Map<MarketZoneId, Map<HouseholdId, Long>> needByZone,
      Map<HouseholdId, HexCoord> householdHex,
      long dueCycle) {

    Input {
      Objects.requireNonNull(zoneTables, "zoneTables（没有价表给空表）");
      Objects.requireNonNull(orderedZones, "orderedZones");
      Objects.requireNonNull(ordering, "ordering（没有排序面给 SettlementOrderBook.empty(day)）");
      Objects.requireNonNull(zoneCurrency, "zoneCurrency");
      Objects.requireNonNull(zoneHouseholds, "zoneHouseholds");
      Objects.requireNonNull(needByZone, "needByZone");
      Objects.requireNonNull(householdHex, "householdHex");
      if (dueCycle < 0L) {
        throw new IllegalArgumentException("到期周期不得为负: " + dueCycle);
      }
      // ★ 保序不可变（禁 Map.copyOf：迭代序不是内容的纯函数；I7）。
      zoneTables = Collections.unmodifiableMap(new LinkedHashMap<>(zoneTables));
      orderedZones = List.copyOf(orderedZones);
      zoneCurrency = Collections.unmodifiableMap(new LinkedHashMap<>(zoneCurrency));
      zoneHouseholds = freezeRows(zoneHouseholds);
      needByZone = freezeNested(needByZone);
      householdHex = Collections.unmodifiableMap(new LinkedHashMap<>(householdHex));
    }

    private static Map<MarketZoneId, List<HouseholdId>> freezeRows(
        Map<MarketZoneId, List<HouseholdId>> source) {
      Map<MarketZoneId, List<HouseholdId>> copy = new LinkedHashMap<>();
      for (Map.Entry<MarketZoneId, List<HouseholdId>> entry : source.entrySet()) {
        copy.put(entry.getKey(), List.copyOf(entry.getValue()));
      }
      return Collections.unmodifiableMap(copy);
    }

    private static Map<MarketZoneId, Map<HouseholdId, Long>> freezeNested(
        Map<MarketZoneId, Map<HouseholdId, Long>> source) {
      Map<MarketZoneId, Map<HouseholdId, Long>> copy = new LinkedHashMap<>();
      for (Map.Entry<MarketZoneId, Map<HouseholdId, Long>> entry : source.entrySet()) {
        copy.put(
            entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
      }
      return Collections.unmodifiableMap(copy);
    }
  }

  /**
   * ★★ <b>本类的交出物</b>（读数 + 下一轮利率）。
   *
   * @param loanRateNext 只含<b>真的变了</b>的区（区 id → 下一轮清算利率）—— 调用方按它写回区表工作副本；
   *     没有持久区的派生形态不进本表（它的利率每轮都从出厂缺省起算，见类注）
   * @param demandMilli 本轮全区"想要借的钱"合计（毫；S-12⑤ 的 D）
   * @param supplyMilli 本轮全区"能借出的钱"合计（毫；S-12⑤ 的 S）
   * @param lentMilli 真的借出去的钱合计（毫；= Σ 逐笔本金）
   * @param loans 真的铸出的借款笔数（= 转移条数 = 写进债账的笔数）
   * @param borrowers 至少借到一笔的家户数
   * @param lenders 至少贷出一笔的家户数
   * @param zones 本轮真的跑了货币腿的区数
   * @param govSuppressed 因"国库默认不挂钱"而被排除在供给侧之外的国库户数（S-12⑥ 的读数）
   * @param borrowedByHousehold ★★ <b>逐户逐币的借款本金</b>（借方 → 币种 → 毫）—— 调用方按它把借到的钱补进
   *     <b>同币</b>买槽的订单预算（{@code MarketSettlement.applyMoneyMarketBudgetTopUps}）：订单预算是**下单当刻**的
   *     快照，而借钱发生在下单之后 ⇒ 不补这一笔，借到的钱在冻结/现金腿上不可用（"先借钱再买货"整条空转）。
   */
  record Round(
      Map<MarketZoneId, Long> loanRateNext,
      long demandMilli,
      long supplyMilli,
      long lentMilli,
      int loans,
      int borrowers,
      int lenders,
      int zones,
      int govSuppressed,
      Map<HouseholdId, Map<CurrencyId, Long>> borrowedByHousehold) {

    Round {
      Objects.requireNonNull(loanRateNext, "loanRateNext");
      Objects.requireNonNull(borrowedByHousehold, "borrowedByHousehold");
      loanRateNext = Collections.unmodifiableMap(new LinkedHashMap<>(loanRateNext));
      // ★ 保序不可变（禁 Map.copyOf：迭代序不是内容的纯函数；I7）。
      Map<HouseholdId, Map<CurrencyId, Long>> borrowedCopy = new LinkedHashMap<>();
      for (Map.Entry<HouseholdId, Map<CurrencyId, Long>> entry : borrowedByHousehold.entrySet()) {
        borrowedCopy.put(
            entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
      }
      borrowedByHousehold = Collections.unmodifiableMap(borrowedCopy);
    }

    /** 没跑货币腿（没有债务工作副本 / 没有计价币 / 没有参与家户）⇒ 全 0、利率不动、没有可补的预算。 */
    static Round none() {
      return new Round(Map.of(), 0L, 0L, 0L, 0, 0, 0, 0, 0, Map.of());
    }
  }

  /** 与 {@code MarketSettlement.MARKET} 同一门面（市场分类）；逐笔合同腿另走 {@code debt} 门面。 */
  private static final Logger MARKET = EconomyLog.market();

  private static final Logger DEBT = EconomyLog.debt();

  /**
   * ★★ <b>借款利率写进合同条款时的上界</b>（{@code DebtTerms.interestRatePerMillePerCycle} 是 {@code int}）。
   *
   * <p>★ 为什么需要它：利率由供需逐轮自适应、<b>无上限</b>（S-12⑤「与 §2.1 同一套口径」，价本身无上限），而合同条款的 利率维是 {@code int} ⇒ long
   * 的利率迟早会越过它。这里<b>夹到 int 的上界</b>并在真的夹住时发一条 INFO（不静默）： 到那一步利率已经是"每周期涨 20
   * 亿倍"，是标定问题（W6），不是本类该悄悄回绕的地方。
   */
  private static final long MAX_TERMS_RATE_PER_MILLE = Integer.MAX_VALUE;

  /**
   * ★★ W5.5-fix3：{@code MONEY_MARKET_UNFILLED} 的两条具名归因（**唯一拼写点**；两条是同一件事的两个成因）。
   *
   * <ul>
   *   <li>{@link #POOL_EXHAUSTED_REASON}：池里有闲钱，但被前面的借款人取空了 ⇒ "池被借空"；
   *   <li>{@link #POOL_EMPTY_REASON}：**池里一分钱都没有**（本区没有任何家户有闲钱）⇒ "池全空"。
   * </ul>
   *
   * <p>★ 两者分开拼写是判据要求（§一.9 的"为什么"必须说得出是哪一种）：改前只有前者有读数，后者整段配对被跳过 —— 于是"需求 30 全落空"在日志里读成 {@code
   * unmetMilli=0}（读数说谎）。
   */
  private static final String POOL_EXHAUSTED_REASON = "pool-exhausted（放贷池是本批唯一的借款上限；缺口退到未付价款记债）";

  /** ★★ W5.5-fix3：池**全空**（{@code supplyMilli == 0}）时的具名归因 —— 见 {@link #POOL_EXHAUSTED_REASON}。 */
  private static final String POOL_EMPTY_REASON = "pool-empty（本区放贷池一分钱都没有：没有任何家户有闲钱；缺口退到未付价款记债）";

  private MoneyMarketSettlement() {}

  /**
   * ★★★ <b>跑一次货币腿</b>（在一个市场轮内、商品撮合之前调用一次）。
   *
   * <p>★ <b>前置</b>：{@code round.deferredPaymentsEnabled()} —— 没有债务工作副本 = 没有地方记这笔债权 ⇒ 不借
   * （与"未付价款记债"同一条 fail-closed 判据：不静默造一笔无处记账的钱）。
   *
   * @param round 本轮的只读会话视图（账户 / 台账 / 授权面 / 家户表）
   * @param input 见 {@link Input}
   * @return 见 {@link Round}
   */
  static Round settle(MarketSettlement.MarketRound round, Input input) {
    Objects.requireNonNull(round, "round");
    Objects.requireNonNull(input, "input");
    if (!round.deferredPaymentsEnabled()) {
      if (MARKET.isDebugEnabled()) {
        EventLog.channel(MARKET)
            .debug(
                LogEvent.of(
                    "MONEY_MARKET_SKIPPED",
                    EconomyLogSource.ECONOMY_MARKET,
                    "day",
                    round.day(),
                    "reason",
                    "no-debt-working-copy（没有债务账 ⇒ 借出的钱没有债权凭证，按 fail-closed 不借）"));
      }
      return Round.none();
    }
    Map<MarketZoneId, Long> rateNext = new LinkedHashMap<>();
    Map<HouseholdId, Map<CurrencyId, Long>> borrowedByHousehold = new LinkedHashMap<>();
    long demandTotal = 0L;
    long supplyTotal = 0L;
    long lentTotal = 0L;
    int loans = 0;
    int borrowers = 0;
    int lenders = 0;
    int zones = 0;
    int govSuppressed = 0;
    for (MarketZoneId zoneId : input.orderedZones()) {
      CurrencyId currency = input.zoneCurrency().get(zoneId);
      if (currency == null) {
        // 说不出"这是哪种钱的市场"（不该发生：区表非空时恒有价表 numeraire）⇒ 跳过这一区。
        continue;
      }
      List<HouseholdId> households = input.zoneHouseholds().getOrDefault(zoneId, List.of());
      if (households.isEmpty()) {
        continue;
      }
      ZoneRound zoneRound =
          settleZone(round, input, zoneId, currency, households, borrowedByHousehold);
      zones++;
      govSuppressed += zoneRound.govHouseholds();
      demandTotal = Math.addExact(demandTotal, zoneRound.demandMilli());
      supplyTotal = Math.addExact(supplyTotal, zoneRound.supplyMilli());
      lentTotal = Math.addExact(lentTotal, zoneRound.lentMilli());
      loans += zoneRound.loans();
      borrowers += zoneRound.borrowers();
      lenders += zoneRound.lenders();
      // ★ 价表是本类唯一的状态落点（存在才跑得到这里）⇒ 变了的市场一律交回，由调用方写进价表工作副本。
      if (zoneRound.rateNextPerMille() != zoneRound.ratePerMille()) {
        rateNext.put(zoneId, zoneRound.rateNextPerMille());
      }
    }
    return new Round(
        rateNext,
        demandTotal,
        supplyTotal,
        lentTotal,
        loans,
        borrowers,
        lenders,
        zones,
        govSuppressed,
        borrowedByHousehold);
  }

  /** 一个区的货币腿交出物（逐区读数；只在本方法族内流转）。 */
  private record ZoneRound(
      long demandMilli,
      long supplyMilli,
      long lentMilli,
      int loans,
      int borrowers,
      int lenders,
      int lendersWithSpareCash,
      int govHouseholds,
      long ratePerMille,
      long rateNextPerMille,
      long alphaPerMille,
      long unmetMilli,
      long lifeReserveMilli) {}

  /**
   * ★★ <b>一个区的货币腿</b>：建需求/供给两簿 → 按结算序配对 → 逐笔走唯一写口转移 + 记真实债权人的合同 → 算下一轮利率。
   *
   * <pre>
   * 需求（借）: demand(h)  = max(0, need(h) − spendable(h))                  // 本期生产花费所需现金 − 当前可用现金
   * 供给（贷）: loanable(h) = max(0, spendable(h) − lifeReserve(h) − need(h)) // 生活储备 = 人均缓冲 × 人口
   * 配对       : 借款人按净值名次升序逐个取用，放贷人按可贷额降序（同额按 id 升序）逐个出借，直到池空
   * 利率       : rate' = adaptiveNextPrice(rate, D, S, α)                    // 与 §2.1 商品价同一套算式
   * </pre>
   */
  private static ZoneRound settleZone(
      MarketSettlement.MarketRound round,
      Input input,
      MarketZoneId zoneId,
      CurrencyId currency,
      List<HouseholdId> households,
      Map<HouseholdId, Map<CurrencyId, Long>> borrowedByHousehold) {
    MarketTable table = input.zoneTables().get(zoneId);
    long rate = table == null ? MarketTable.DEFAULT_LOAN_RATE_PER_MILLE : table.loanRatePerMille();
    long alpha =
        table == null
            ? MarketTable.DEFAULT_LOAN_RATE_ALPHA_PER_MILLE
            : table.loanRateAlphaPerMille();
    Map<HouseholdId, Long> need = input.needByZone().getOrDefault(zoneId, Map.of());
    // ── ① 两簿：借款人（缺钱）与放贷人（闲钱）────────────────────────────────────────────
    Map<HouseholdId, Long> demandByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, Long> loanableByLender = new LinkedHashMap<>();
    long lifeReserveTotal = 0L;
    int lendersWithSpareCash = 0;
    int govHouseholds = 0;
    long demandTotal = 0L;
    long supplyTotal = 0L;
    for (HouseholdId household : households) {
      if (round.householdMarketRole(household)
          == MarketSettlement.HouseholdMarketRole.AUTHORIZATION_ONLY) {
        // ★ S-12⑥：国库默认不挂钱 —— 两侧都不参与（政府要出借走显式授权；见账本"偏离记录"）。
        //   ★ R2：判据走 {@code householdMarketRole}（唯一拼写点）—— 单位户**不**在这里被排除（它们是普通借款人）。
        govHouseholds++;
        continue;
      }
      long cash = MarketSettlement.spendableMoneyOf(round, household, currency);
      long productionCost = Math.max(0L, need.getOrDefault(household, 0L));
      long gap = productionCost - cash;
      if (gap > 0L) {
        demandByHousehold.put(household, gap);
        demandTotal = Math.addExact(demandTotal, gap);
        continue;
      }
      HouseholdEconomy row = round.householdEconomies().get(household);
      long population = row == null ? 0L : Math.max(0L, row.population());
      long lifeReserve =
          Math.multiplyExact(population, MarketSettlement.LENDER_MONEY_BUFFER_PER_CAPITA_MILLI);
      lifeReserveTotal = Math.addExact(lifeReserveTotal, lifeReserve);
      // ★ 可贷额 = 余额 − 生活储备 − 本轮自己的生产花费所需现金（S-12③）；
      //   "实际对不上也不要紧，该怎么算怎么算"（用户原话④）⇒ 只做 max(0, ·)，不加任何额外机制。
      long loanable = Math.max(0L, cash - lifeReserve - productionCost);
      if (loanable > 0L) {
        loanableByLender.put(household, loanable);
        supplyTotal = Math.addExact(supplyTotal, loanable);
        lendersWithSpareCash++;
      }
    }
    // ── ② 配对（借款人按净值名次升序；放贷人按可贷额降序、同额按 id 升序）────────────────────
    long lentMilli = 0L;
    long unmetMilli = 0L;
    int loans = 0;
    int borrowers = 0;
    Set<HouseholdId> lendersUsed = new LinkedHashSet<>();
    if (demandTotal > 0L && supplyTotal <= 0L) {
      // ★★★ W5.5-fix3（读数对称，改前缺陷）：**放贷池全空**时也要报缺口 —— 改前整段配对逻辑在
      //   `demandTotal > 0 && supplyTotal > 0` 里 ⇒ 池空时 `unmetMilli` 留 0 且一行 `MONEY_MARKET_UNFILLED`
      //   都不发，"需求 30 全落空"在日志里读成 `unmetMilli=0`（读数说谎；用户直接用日志排查）。
      //   ★ 它**只改读数**：金额、利率（下面仍按 demand/supply 自适应）、状态、债权一条都不动。
      //   ★ 借款人序与"池非空"那条路**同一把尺**（结算序名次 → 稳定 id），逐户各发一条缺口。
      for (HouseholdId borrower : borrowerOrder(input, demandByHousehold)) {
        long remaining = demandByHousehold.getOrDefault(borrower, 0L);
        if (remaining <= 0L) {
          continue;
        }
        unmetMilli = Math.addExact(unmetMilli, remaining);
        logUnfilled(round.day(), zoneId, currency, borrower, 0L, remaining, POOL_EMPTY_REASON);
      }
    } else if (demandTotal > 0L) {
      List<HouseholdId> borrowerOrder = borrowerOrder(input, demandByHousehold);
      List<HouseholdId> lenderOrder = new ArrayList<>(loanableByLender.keySet());
      lenderOrder.sort(
          Comparator.<HouseholdId, Long>comparing(loanableByLender::get, Comparator.reverseOrder())
              .thenComparing(HouseholdId::value));
      long poolLeft = supplyTotal;
      for (HouseholdId borrower : borrowerOrder) {
        long remaining = demandByHousehold.getOrDefault(borrower, 0L);
        if (remaining <= 0L || poolLeft <= 0L) {
          continue;
        }
        long borrowed = 0L;
        for (HouseholdId lender : lenderOrder) {
          if (remaining <= 0L || poolLeft <= 0L) {
            break;
          }
          long available = loanableByLender.getOrDefault(lender, 0L);
          if (available <= 0L) {
            continue;
          }
          long amount = Math.min(remaining, available);
          loanableByLender.put(lender, available - amount);
          poolLeft -= amount;
          remaining -= amount;
          borrowed += amount;
          long outstanding = lend(round, input, zoneId, currency, lender, borrower, amount, rate);
          // ★ 逐户逐币累计（调用方按它把借到的钱补进**同币**买槽的订单预算）。
          borrowedByHousehold
              .computeIfAbsent(borrower, ignored -> new LinkedHashMap<>())
              .merge(currency, amount, Long::sum);
          loans++;
          lendersUsed.add(lender);
          if (DEBT.isTraceEnabled()) {
            EventLog.channel(DEBT)
                .trace(
                    LogEvent.of(
                        "MONEY_MARKET_LOAN",
                        EconomyLogSource.ECONOMY_DEBT,
                        "day",
                        round.day(),
                        "zone",
                        zoneId.value(),
                        "currency",
                        currency.value(),
                        "lender",
                        lender.value(),
                        "borrower",
                        borrower.value(),
                        "amountMilli",
                        amount,
                        "ratePerMillePerCycle",
                        rate,
                        "contractOutstandingMilli",
                        outstanding,
                        "poolLeftMilli",
                        poolLeft,
                        "reason",
                        "cash-gap-borrowed-before-buying-goods（先借钱再买货：钱腿只搬钱，货仍走正规撮合）"));
          }
        }
        if (borrowed > 0L) {
          borrowers++;
          lentMilli = Math.addExact(lentMilli, borrowed);
        }
        if (remaining > 0L) {
          // ★ S-12④：唯一上限 = 放贷池 ⇒ 池空即止；剩下的缺口由买货那一步的"未付价款记债"兜底（S-12⑦）。
          unmetMilli = Math.addExact(unmetMilli, remaining);
          logUnfilled(
              round.day(), zoneId, currency, borrower, borrowed, remaining, POOL_EXHAUSTED_REASON);
        }
      }
    }
    long rateNext = MarketSettlement.adaptiveNextPrice(rate, demandTotal, supplyTotal, alpha);
    logZone(
        round.day(),
        zoneId,
        table,
        currency,
        households.size(),
        govHouseholds,
        borrowers,
        lendersUsed.size(),
        lendersWithSpareCash,
        demandTotal,
        supplyTotal,
        lentMilli,
        loans,
        unmetMilli,
        lifeReserveTotal,
        rate,
        rateNext,
        alpha);
    return new ZoneRound(
        demandTotal,
        supplyTotal,
        lentMilli,
        loans,
        borrowers,
        lendersUsed.size(),
        lendersWithSpareCash,
        govHouseholds,
        rate,
        rateNext,
        alpha,
        unmetMilli,
        lifeReserveTotal);
  }

  /**
   * ★★ W5.5-fix3：<b>借款人序</b>（配对序的唯一拼写点）—— 结算序名次升序、同名词次按稳定 id 升序。
   *
   * <p>★ 为什么抽出来：池**非空**与池**全空**两条路必须用**同一把尺**（否则逐户缺口读数会按两条不同的序发， 日志逐值可比性就没了）。它只排序、不改任何量。
   */
  private static List<HouseholdId> borrowerOrder(
      Input input, Map<HouseholdId, Long> demandByHousehold) {
    List<HouseholdId> order = new ArrayList<>(demandByHousehold.keySet());
    order.sort(
        Comparator.comparingInt(
                (HouseholdId household) -> input.ordering().householdRankOf(household))
            .thenComparing(HouseholdId::value));
    return order;
  }

  /**
   * ★★ W5.5-fix3：<b>一条逐户缺口读数</b>（{@code MONEY_MARKET_UNFILLED} 的唯一发射点；两条归因共用）。
   *
   * <p>★ 它**只发日志**：{@code borrowedMilli=0} 表示这一户一分钱都没借到（池全空），否则是"借到多少、还差多少"。 缺口本身由调用方累加进 {@code
   * ZoneRound.unmetMilli}（与 {@code MONEY_MARKET_WHY} 的同一个数）。
   */
  private static void logUnfilled(
      long day,
      MarketZoneId zoneId,
      CurrencyId currency,
      HouseholdId borrower,
      long borrowedMilli,
      long unmetMilli,
      String reason) {
    if (!MARKET.isDebugEnabled()) {
      return;
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MONEY_MARKET_UNFILLED",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                day,
                "zone",
                zoneId.value(),
                "currency",
                currency.value(),
                "borrower",
                borrower.value(),
                "borrowedMilli",
                borrowedMilli,
                "unmetMilli",
                unmetMilli,
                "reason",
                reason));
  }

  /**
   * ★★ <b>一笔借款的落账（唯一写口 + 真实债权人的合同）</b>。
   *
   * <pre>
   * ① 钱腿：贷方 → 借方（{@link TransferReason#LOAN_PRINCIPAL}），经 {@code EconomySettlement.applyTransfer}
   *    （唯一写口；两端必须解析到已登记家户）—— 钱只搬一次，不碰任何商品。
   * ② 合同：{@code DebtContractBook.upsert}（债务人=借方、债权人=贷方、单位=本区计价币、
   *    利率=本轮清算利率、到期=下一个周期）—— 同一 (债务人, 债权人, 单位, 条款) 自动累加成一条未偿额
   *    （D-023 §3"不做分别核算"）；利率变了 ⇒ 条款不同 ⇒ 另立一条（见账本"关键判断"）。
   * </pre>
   *
   * @return 该合同落账后的未偿额（毫）
   */
  private static long lend(
      MarketSettlement.MarketRound round,
      Input input,
      MarketZoneId zoneId,
      CurrencyId currency,
      HouseholdId lender,
      HouseholdId borrower,
      long amount,
      long ratePerMille) {
    HexCoord location = input.householdHex().get(borrower);
    if (location == null) {
      location = input.householdHex().get(lender);
    }
    if (location == null) {
      // 契约故障：说不出这笔钱落在哪一格 ⇒ 具名抛（钱腿的 location 是账目归属，不许猜）。
      throw new IllegalStateException(
          "货币市场借款说不出账户所在格（借贷双方都不在本轮参与者表里）: zone="
              + zoneId.value()
              + " lender="
              + lender.value()
              + " borrower="
              + borrower.value());
    }
    Transfer loan =
        round
            .ledger()
            .mint(
                HouseholdActors.of(lender),
                HouseholdActors.of(borrower),
                location,
                Map.of(),
                Map.of(currency, amount),
                TransferReason.LOAN_PRINCIPAL);
    // ★★ 唯一写口（两端必须是已登记家户；钱腿只搬钱、不碰冻结表以外的任何东西）。
    EconomySettlement.applyTransfer(
        round.householdGoods(),
        round.householdMoney(),
        round.householdFrozenGoods(),
        round.householdFrozenMoney(),
        round.householdOfActor(),
        loan);
    long termsRate = Math.min(ratePerMille, MAX_TERMS_RATE_PER_MILLE);
    if (termsRate != ratePerMille && MARKET.isInfoEnabled()) {
      EventLog.channel(MARKET)
          .info(
              LogEvent.of(
                  "MONEY_MARKET_RATE_CLAMPED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  round.day(),
                  "zone",
                  zoneId.value(),
                  "zoneRatePerMille",
                  ratePerMille,
                  "termsRatePerMille",
                  termsRate,
                  "reason",
                  "debt-terms-interest-rate-is-int（合同条款的利率维是 int；到这一步已是标定问题，不静默回绕）"));
    }
    DebtContract contract =
        DebtContractBook.upsert(
            round.debts(),
            borrower,
            lender,
            // ★ 单位 = 本区计价币（{@code DebtUnit.money} 是唯一工厂；{@code DebtUnit.Money} 是 record 变体本身）。
            DebtUnit.money(currency),
            DebtTerms.legacyDefault((int) termsRate),
            DebtSource.ORDINARY_LOAN,
            amount,
            round.day(),
            OptionalLong.of(input.dueCycle()));
    return contract.principal();
  }

  /** ★★ <b>一个区一轮的 DEBUG（§一.9：写"为什么"）</b>—— 两簿是怎么算出来的、国库被排除了几户、利率从哪到哪。 */
  private static void logZone(
      long day,
      MarketZoneId zoneId,
      MarketTable table,
      CurrencyId currency,
      int households,
      int govHouseholds,
      int borrowers,
      int lenders,
      int lendersWithSpareCash,
      long demandMilli,
      long supplyMilli,
      long lentMilli,
      int loans,
      long unmetMilli,
      long lifeReserveMilli,
      long ratePerMille,
      long rateNextPerMille,
      long alphaPerMille) {
    if (!MARKET.isDebugEnabled()) {
      return;
    }
    EventLog.channel(MARKET)
        .debug(
            LogEvent.of(
                "MONEY_MARKET_WHY",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                day,
                "zone",
                zoneId.value(),
                "currency",
                currency.value(),
                "households",
                households,
                "govHouseholdsSuppressed",
                govHouseholds,
                "borrowers",
                borrowers,
                "lenders",
                lenders,
                "lendersWithSpareCash",
                lendersWithSpareCash,
                "demandMilli",
                demandMilli,
                "supplyMilli",
                supplyMilli,
                "lentMilli",
                lentMilli,
                "loans",
                loans,
                "unmetMilli",
                unmetMilli,
                "lifeReserveMilli",
                lifeReserveMilli,
                "ratePerMillePerCycle",
                ratePerMille,
                "rateNextPerMillePerCycle",
                rateNextPerMille,
                "alphaPerMille",
                alphaPerMille,
                "persistentTable",
                table != null,
                "why",
                "need=production-cost-at-zone-price; loanable=cash-life-reserve(per-capita)-own-production-cost;"
                    + " rate=supply-demand-adaptive-same-formula-as-goods-price;"
                    + " treasury-not-lending-by-default"));
  }

  /**
   * ★★ <b>一轮货币腿的 INFO 汇总 + 国库抑制读数</b>（整个市场轮各一行；字段口径见 {@link Round}）。
   *
   * <p>★ 一行不刷的条件：一个区都没跑（{@code zones == 0}），或"既没有借贷、也没有任何供需"（缺省世界： 人人现金够用且没有闲钱）—— 那时逐区一行已足够，不再重复汇总。
   */
  static void logRound(long day, Round round) {
    Objects.requireNonNull(round, "round");
    if (round.zones() == 0) {
      return;
    }
    if (round.govSuppressed() > 0 && MARKET.isDebugEnabled()) {
      // ★ 判据 9 的读数：无授权 ⇒ 货币供给里没有国库（被排除了几户）。
      EventLog.channel(MARKET)
          .debug(
              LogEvent.of(
                  "MONEY_MARKET_GOV_SUPPLY_SUPPRESSED",
                  EconomyLogSource.ECONOMY_MARKET,
                  "day",
                  day,
                  "govHouseholds",
                  round.govSuppressed(),
                  "reason",
                  "treasury-does-not-lend-by-default（S-12⑥：国家挂不挂单看国家呗 ⇒ 要出借走显式授权，本批未建写口）"));
    }
    if (round.loans() == 0 && round.demandMilli() <= 0L && round.supplyMilli() <= 0L) {
      return;
    }
    EventLog.channel(MARKET)
        .info(
            LogEvent.of(
                "MONEY_MARKET_ROUND",
                EconomyLogSource.ECONOMY_MARKET,
                "day",
                day,
                "zones",
                round.zones(),
                "demandMilli",
                round.demandMilli(),
                "supplyMilli",
                round.supplyMilli(),
                "lentMilli",
                round.lentMilli(),
                "loans",
                round.loans(),
                "borrowers",
                round.borrowers(),
                "lenders",
                round.lenders(),
                "rateChanges",
                round.loanRateNext().size()));
  }
}

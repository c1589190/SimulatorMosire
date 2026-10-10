package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★★ <b>W4（2026-10-11）：阶层滑落 —— 速度 = f(债务/资产)，结算点 = 市场轮最末</b>（冻结口径 S-1）。
 *
 * <p>★★ <b>用户原话（第二批第 1 条，逐字）</b>：「就按你说的做吧，我的想法是<b>直接根据债务/资产规模估算向其他阶层流动的速度</b>就行，
 * 这个速度的<b>结算必须在当前经济操作搞完之后最后再结算</b>，至于这个速度在什么时候算，其实也是在最后，<b>当轮算出这个家户的债务规模后</b>进行；」 ★ <b>C-3
 * 裁定</b>（2026-10-10 用户）：「<b>阶层滑落算的是速度，并且不能跳级，慢慢滑落就行</b>」。
 *
 * <p>★★★ <b>它取代了什么（§一.11：旧设计直接删）</b>：改动前"阶层下滑"住在 {@code EconomyLiquidationSettlement}
 * （E5b）里，判据是<b>三个阈值事件</b>（核心生产资料被处置 / {@code DEFAULTED} 且连续债务压力 ≥ 2 个关账周期 / 下一轮投入持续不足），
 * 且只在<b>关账日</b>跑。用户 C-3 明确这是"速度、逐级、慢慢滑"而不是阈值事件 ⇒ E5b 的那一段整段删除，本类是阶层滑落的唯一写口。 ★
 * <b>"资不抵债即家破"那一支从未落地</b>（约束设计书 §4.1 记它"是控制方自造的一支，已删"）。
 *
 * <pre>
 * 每市场轮、逐户（稳定 HouseholdId 序）各算一次（资产/负债直接取开盘前快照的那一把尺，不另算第二套口径）：
 *   ratioPerMille = ⌊负债 × 1000 ÷ max(1, 资产)⌋                        // "债务/资产规模"（‰）
 *   speedPerMille = min(SLIDE_MAX_SPEED_PER_MILLE,
 *                       ⌊ratioPerMille × SLIDE_SPEED_PER_MILLE_PER_RATIO ÷ 1000⌋)
 *   requiredDays  = ⌈SLIDE_ROUND_DAYS × SLIDE_COST_PER_LEVEL ÷ speedPerMille⌉
 *   滑落一级 ⟺ speedPerMille &gt; 0 ∧ 负债 &gt; 0 ∧ (day − lastTransitionDay) ≥ requiredDays ∧ 存在"下一级"
 * </pre>
 *
 * <p>★★ <b>逐值可算的例子（斜率 100‰、每级成本 1000‰、轮距 5 天）</b>：
 *
 * <pre>
 * 债务/资产 = 0.5  ⇒ ratio=500  ⇒ speed=50   ⇒ 每级 100 天
 * 债务/资产 = 1.0  ⇒ ratio=1000 ⇒ speed=100  ⇒ 每级 50 天
 * 债务/资产 = 2.0  ⇒ ratio=2000 ⇒ speed=200  ⇒ 每级 25 天
 * 债务/资产 ≥ 10   ⇒ speed 打满 1000         ⇒ 每级 5 天（= 每个市场轮恰好一级，这是"最快"）
 * </pre>
 *
 * <p>★★★ <b>"不能跳级"与"同一市场轮内最多一级"怎么强制</b>：① 目标位置由 {@link ClassLadderBook#nearestLower} 给出 =
 * 所有<b>严格更低</b>位置里<b>最高的那一个</b>（一次只走一格；"级"的具名边界见该类类注）； ②
 * 本类每次调用对<b>同一户只写一次</b>、只写<b>一个</b>新位置，而调用点是市场轮最末（一轮一次）⇒ 结构上不可能一轮连降两级。
 *
 * <p>★★ <b>时点（S-1 / W4 判据⑤）</b>：调用点在 {@code EconomySettlement.settleOneDayInto} 的"4. 区域市场清算"段末 —— 即
 * {@code MarketSettlement.clearOncePerCycle}（撮合 + 自适应价格写回）返回之后、{@code 4b 借粮} 之前。 当轮债务规模（本轮赊购产生的
 * {@code debtContracts}）此时已经算完 ⇒ 正是用户原话的"当轮算出这个家户的债务规模后进行"。
 *
 * <p>★★ <b>货币/人口/资产一个字都不动</b>：本类只写 {@code classStandings}（阶层归属的唯一权威）的 {@code
 * currentPositionId}/{@code lastTransitionDay}/{@code reason}；不改账户、不铸转移、不改人口（滑落的是"位置"）。 ★ 落到 {@code
 * HouseholdEconomy.view.stratum} 的投影仍由既有的 5c 阶层写回在关账日完成（一处投影，两条来源不并存）。
 *
 * <p>★★ <b>本批不动持久形状</b>（铁律 5 不触发）：{@code lastTransitionDay} 是既有字段，本类只给它写"最近一次滑落的日" ——
 * 它原本的语义就是"最近一次阶层变更日"，本类没有扩义，也没有新增组件/字段/枚举。
 */
public final class ClassSlideSettlement {

  /**
   * ★ <b>速度斜率</b>：债务/资产 = 1.0 时每市场轮累计 {@value} ‰（= 每 50 天降一级）。★ 这是<b>政策标定值</b> （控制方实现口径，非用户裁定；列为
   * W6"阶层滑落速度"标定首查项）。
   */
  public static final long SLIDE_SPEED_PER_MILLE_PER_RATIO = 100L;

  /** ★ 速度上限（‰/轮）：配合"一轮最多一级"⇒ 每轮恰好一级就是"最快"。 */
  public static final long SLIDE_MAX_SPEED_PER_MILLE = 1000L;

  /** ★ 升一级的"阶"成本（‰）：速度按轮累加，累计满本值才走<b>一级</b>（"慢慢滑"的量化形态）。 */
  public static final long SLIDE_COST_PER_LEVEL = 1000L;

  /** ★ 一个市场轮多少天（速度的积分步长；与 {@code MarketSettlement} 的开市间隔同一常量）。 */
  public static final long SLIDE_ROUND_DAYS = MarketSettlement.MARKET_RESTOCK_INTERVAL_DAYS;

  /** 滑落原因前缀（读口按它分类；不在多处拼字符串）。 */
  public static final String REASON_PREFIX = "class-slide-speed:";

  private static final org.slf4j.Logger MIGRATION = EconomyLog.migration();

  private ClassSlideSettlement() {}

  /**
   * ★★ <b>一次市场轮末的阶层滑落结算</b>（本批唯一写口；协调器单线程，市场轮最末调用一次）。
   *
   * @param base 段首基态（读 {@code classPositions} 位置表；它无工作副本，段内不变）
   * @param sheet 结算会话的工作表（写 {@code classStandings} 的工作副本）
   * @param day 世界日
   * @param rows 家户行工作副本（读人口/所在格；<b>不写</b>）
   * @param debts 债务合同工作副本（★★ <b>市场轮末的活状态</b>：含本轮赊购新记的债）
   * @param accounts 账户会话（市场轮末的活状态：含本轮买卖后的货币/库存）
   * @param topology 本轮市场拓扑
   * @param marketTables 本轮价表（区内一张；★ 价格已在轮内写回 ⇒ 这里是本轮新价）
   * @return 本轮读数（逐户评估数 / 滑落数 / 跳过原因的具名计数）
   */
  static SlideRound settleAtMarketRoundEnd(
      EconomyData base,
      EconomyStateBuilder sheet,
      long day,
      Map<HouseholdId, HouseholdEconomy> rows,
      Map<DebtContractId, DebtContract> debts,
      AccountSession accounts,
      MarketTopology topology,
      Map<MarketZoneId, MarketTable> marketTables) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(sheet, "sheet");
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(debts, "debts");
    Objects.requireNonNull(accounts, "accounts");
    Map<ClassPositionId, ProductionRole> positions = base.classPositions();
    if (positions.isEmpty()) {
      return new SlideRound(0L, 0L, 0L, 0L, 0L, 0L, 0L, List.of());
    }
    Map<HouseholdId, HouseholdClassMembership> classMemberships = sheet.classMemberships();
    // ★★ 时点（用户原话「当轮算出这个家户的债务规模后进行」）：估值读**市场轮末的活状态** ——
    //   含本轮赊购新记的债、本轮买卖后的货币/库存，以及轮内写回的新价；算式与排序共用
    //   （{@link SettlementOrderBook#valuate} 是唯一拼写点）⇒ 两处只差时点、不差口径。
    Map<HouseholdId, List<DebtContract>> debtsByDebtor = SettlementOrderBook.debtsByDebtor(debts);
    List<HouseholdId> households = new ArrayList<>(rows.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    long evaluated = 0L;
    long slid = 0L;
    long noDebt = 0L;
    long noPosition = 0L;
    long cooldown = 0L;
    long noLowerTarget = 0L;
    long unpricedHouseholds = 0L;
    List<Slide> slides = new ArrayList<>();
    for (HouseholdId household : households) {
      HouseholdEconomy row = rows.get(household);
      if (row == null || row.population() <= 0L) {
        continue;
      }
      evaluated++;
      SettlementOrderBook.HouseholdValuation valuation =
          SettlementOrderBook.valuate(
              accounts,
              marketTables,
              topology,
              household,
              row.view().hex(),
              debtsByDebtor.getOrDefault(household, List.of()));
      if (!valuation.debtFullyPriced()) {
        unpricedHouseholds++;
      }
      long assetCommon = valuation.assetsCommonValue();
      long debtCommon = valuation.debtCommonValue();
      long ratioPerMille = ratioPerMille(debtCommon, assetCommon);
      long speedPerMille = speedPerMille(debtCommon, assetCommon);
      if (speedPerMille <= 0L) {
        noDebt++;
        logSpeed(
            day,
            household,
            assetCommon,
            debtCommon,
            ratioPerMille,
            speedPerMille,
            0L,
            0L,
            "no-debt");
        continue;
      }
      Optional<ClassPositionId> current =
          ClassLadderBook.resolvablePosition(household, classMemberships, positions, row);
      if (current.isEmpty()) {
        noPosition++;
        logSpeed(
            day,
            household,
            assetCommon,
            debtCommon,
            ratioPerMille,
            speedPerMille,
            0L,
            0L,
            "no-current-position");
        continue;
      }
      HouseholdClassMembership membership = classMemberships.get(household);
      long lastTransitionDay = membership == null ? 0L : membership.lastTransitionDay();
      long elapsedDays = Math.max(0L, day - lastTransitionDay);
      long requiredDays = requiredDays(speedPerMille);
      if (elapsedDays < requiredDays) {
        cooldown++;
        logSpeed(
            day,
            household,
            assetCommon,
            debtCommon,
            ratioPerMille,
            speedPerMille,
            elapsedDays,
            requiredDays,
            "cooldown");
        continue;
      }
      ClassLadderBook.DownwardTarget downward =
          ClassLadderBook.nearestLower(current.get(), positions);
      if (downward.target().isEmpty()) {
        noLowerTarget++;
        logSpeed(
            day,
            household,
            assetCommon,
            debtCommon,
            ratioPerMille,
            speedPerMille,
            elapsedDays,
            requiredDays,
            "no-lower-target:" + downward.failureReason());
        continue;
      }
      ClassPositionId target = downward.target().get();
      ClassPositionId original =
          membership == null ? current.get() : membership.originalPositionId();
      Map<ClassPositionId, Long> retainedShares =
          membership == null ? Map.of() : membership.retainedShares();
      String reason =
          REASON_PREFIX
              + current.get().value()
              + "->"
              + target.value()
              + ":debtRatioPerMille="
              + ratioPerMille
              + ":speedPerMille="
              + speedPerMille
              + ":elapsedDays="
              + elapsedDays;
      classMemberships.put(
          household,
          new HouseholdClassMembership(
              household,
              original,
              target,
              // ★ 位置变了 ⇒ 旧的可参与集合（指向旧位置）不再沿用，只参与新位置（与 E5b 的既有口径逐字相同）。
              Set.of(),
              retainedShares,
              // ★ 连续债务压力计数器归零：它服务清算触发（E5b 仍在读），与"滑落速度"是两件事，不在这里扩义。
              0L,
              day,
              reason));
      slid++;
      slides.add(
          new Slide(
              household,
              row.view().hex(),
              current.get(),
              target,
              ratioPerMille,
              speedPerMille,
              elapsedDays,
              requiredDays,
              assetCommon,
              debtCommon));
      if (MIGRATION.isInfoEnabled()) {
        EventLog.channel(MIGRATION)
            .info(
                LogEvent.of(
                    "CLASS_SLIDE",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "household",
                    household.value(),
                    "from",
                    current.get().value(),
                    "to",
                    target.value(),
                    "fromLevel",
                    ClassLadderBook.describeLevel(positions.get(current.get())),
                    "toLevel",
                    ClassLadderBook.describeLevel(positions.get(target)),
                    "levels",
                    1L,
                    "debtRatioPerMille",
                    ratioPerMille,
                    "speedPerMille",
                    speedPerMille,
                    "elapsedDays",
                    elapsedDays,
                    "requiredDays",
                    requiredDays,
                    "assetCommonValue",
                    assetCommon,
                    "debtCommonValue",
                    debtCommon,
                    "reason",
                    "class-slide-speed-one-level-per-market-round"));
      }
      logSpeed(
          day,
          household,
          assetCommon,
          debtCommon,
          ratioPerMille,
          speedPerMille,
          elapsedDays,
          requiredDays,
          "slid-one-level");
    }
    SlideRound round =
        new SlideRound(
            evaluated,
            slid,
            noDebt,
            noPosition,
            cooldown,
            noLowerTarget,
            unpricedHouseholds,
            List.copyOf(slides));
    if (MIGRATION.isInfoEnabled()) {
      EventLog.channel(MIGRATION)
          .info(
              LogEvent.of(
                  "CLASS_SLIDE_ROUND",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "evaluated",
                  evaluated,
                  "slid",
                  slid,
                  "skippedNoDebt",
                  noDebt,
                  "skippedNoPosition",
                  noPosition,
                  "skippedCooldown",
                  cooldown,
                  "skippedNoLowerTarget",
                  noLowerTarget,
                  "unpricedDebtHouseholds",
                  unpricedHouseholds,
                  "costPerLevelPerMille",
                  SLIDE_COST_PER_LEVEL,
                  "speedPerRatioPerMille",
                  SLIDE_SPEED_PER_MILLE_PER_RATIO,
                  "roundDays",
                  SLIDE_ROUND_DAYS,
                  "reason",
                  "speed-f(debt/assets)-settled-at-market-round-end-one-level-max"));
    }
    return round;
  }

  /** 债务/资产（‰）：资产读不到（≤ 0）时用 1 做分母（"没有可折算资产"⇒ 比率按最大口径算，fail-closed 不静默成 0）。 */
  static long ratioPerMille(long debtCommon, long assetCommon) {
    if (debtCommon <= 0L) {
      return 0L;
    }
    long denominator = Math.max(1L, assetCommon);
    long numerator;
    try {
      numerator = Math.multiplyExact(debtCommon, 1000L);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE / 1000L;
    }
    return numerator / denominator;
  }

  /** 速度（‰/轮）= min(上限, ⌊比率 × 斜率 ÷ 1000⌋)；比率 ≤ 0 ⇒ 0（不滑）。 */
  static long speedPerMille(long debtCommon, long assetCommon) {
    long ratio = ratioPerMille(debtCommon, assetCommon);
    if (ratio <= 0L) {
      return 0L;
    }
    // ★ 先除后乘 + 余数补算：避免 ratio × 斜率 在极端值上溢出（逐值等于 ⌊ratio × 斜率 ÷ 1000⌋）。
    long whole = ratio / 1000L * SLIDE_SPEED_PER_MILLE_PER_RATIO;
    long residual = ratio % 1000L * SLIDE_SPEED_PER_MILLE_PER_RATIO / 1000L;
    long speed = saturatedAdd(whole, residual);
    return Math.min(SLIDE_MAX_SPEED_PER_MILLE, speed);
  }

  /** 走一级需要多少天：{@code ⌈轮距 × 每级成本 ÷ 速度⌉}（速度 ≤ 0 ⇒ 不可达 = {@code Long.MAX_VALUE}）。 */
  static long requiredDays(long speedPerMille) {
    if (speedPerMille <= 0L) {
      return Long.MAX_VALUE;
    }
    long numerator = SLIDE_ROUND_DAYS * SLIDE_COST_PER_LEVEL;
    return (numerator + speedPerMille - 1L) / speedPerMille;
  }

  private static void logSpeed(
      long day,
      HouseholdId household,
      long assetCommon,
      long debtCommon,
      long ratioPerMille,
      long speedPerMille,
      long elapsedDays,
      long requiredDays,
      String decision) {
    if (!MIGRATION.isTraceEnabled()) {
      return;
    }
    EventLog.channel(MIGRATION)
        .trace(
            LogEvent.of(
                "CLASS_SLIDE_SPEED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "household",
                household.value(),
                "assetCommonValue",
                assetCommon,
                "debtCommonValue",
                debtCommon,
                "debtRatioPerMille",
                ratioPerMille,
                "speedPerMille",
                speedPerMille,
                "elapsedDays",
                elapsedDays,
                "requiredDays",
                requiredDays,
                "decision",
                decision));
  }

  private static long saturatedAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  /** 一次滑落（逐户明细；进读数与日志）。 */
  record Slide(
      HouseholdId household,
      HexCoord hex,
      ClassPositionId from,
      ClassPositionId to,
      long debtRatioPerMille,
      long speedPerMille,
      long elapsedDays,
      long requiredDays,
      long assetCommonValue,
      long debtCommonValue) {

    Slide {
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(from, "from");
      Objects.requireNonNull(to, "to");
    }
  }

  /** 一轮读数（具名计数 + 逐户明细）。 */
  record SlideRound(
      long evaluated,
      long slid,
      long skippedNoDebt,
      long skippedNoPosition,
      long skippedCooldown,
      long skippedNoLowerTarget,
      long unpricedDebtHouseholds,
      List<Slide> slides) {

    SlideRound {
      Objects.requireNonNull(slides, "slides");
      // ★★ W4-fix（2026-10-11）：record 组件访问器 {@code slides()} 也是"对外暴露内部集合"的读口，
      //   与 {@link SettlementOrderBook} 同一口径就地冻结。唯一构造方已传 {@code List.copyOf} ⇒
      //   {@code List.copyOf} 对不可变入参原样返回：逐值、开销、迭代序都与改前相同（I7 保序）。
      slides = List.copyOf(slides);
    }
  }
}

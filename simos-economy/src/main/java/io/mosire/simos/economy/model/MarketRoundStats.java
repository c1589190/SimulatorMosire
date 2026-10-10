package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>W1（2026-10-10）：一个市场的市场轮统计簿</b>（{@code EconomyData.marketRoundStats} 的值）—— 逐区逐商品的 <b>D / S /
 * 成交量 / 均价 / 未满足量</b> + 逐区<b>成交额两栏</b>（名义货值 / 实付现金），保留窗口固定：
 *
 * <pre>
 * lastRoundDay                    最近一次写入统计的市场轮的绝对日（-1 = 还没有过任何市场轮）
 * lastRound                       最近一轮明细（逐商品）
 * lastRoundTurnoverMilli          ★ 最近一轮**名义货值**（毫）：全额货款，含赊购部分（D4 起）
 * lastRoundCashTurnoverMilli      ★ 最近一轮**实付现金**（毫）：只数真钱（D4 新增）
 * cycleToDate                     本周期累计（本周期已跑过的各轮之和）
 * cycleTurnoverMilli              本周期累计名义货值（毫）
 * cycleCashTurnoverMilli          本周期累计实付现金（毫）
 * previousCycle                   上周期累计（关账轮转，不是"从上周期以来一直加"）
 * previousCycleTurnoverMilli      上周期累计名义货值（毫）
 * previousCycleCashTurnoverMilli  上周期累计实付现金（毫）
 * </pre>
 *
 * <p>★★ <b>为什么窗口是这三个、且固定大小</b>（约束设计书 §6.1 W1 的 N-3）：最近一轮明细回答"眼下这一轮发生了什么"；本周期累计
 * 是本周期动态价/读数的工作集；上周期累计是"**上周期快照**"（W4 的市场区排序量 = 上周期成交额，S-4/N-1 的防自指输入）。 三者都是
 * <b>窗口</b>而不是"历史"：每轮覆盖写、关账轮转 ⇒ 状态大小与推进天数<b>无关</b>（性能红线：一次 advance 不随天数增长）。
 *
 * <p>★★★ <b>成交额的两栏口径（W2-fix7 / D4，控制方裁定 v3.10 §7）</b>：<b>两栏都是毫，都按卖方所在市场归属</b>（区内成交 = 本区；跨区 =
 * 货源市场），单位 = 该笔的**计价币**（世界没有汇率 ⇒ 跨币成交按原值计入，不折算、不求和成"一个购买力"）。★ 需要单一口径排序 GDP
 * 的调用方（W4）必须自己说清用哪种币；本账不替它折算。
 *
 * <ul>
 *   <li><b>{@code turnoverMilli}（三窗）= 名义货值 = {@code ⌊Σ(成交量 × 单价) / 1000⌋}</b> —— 成交那一刻的<b>全额货款</b>，
 *       <b>含赊购部分</b>。★ 它回答"这个市场做了多大的生意"，与买方付不付得出钱无关。
 *   <li><b>{@code cashTurnoverMilli}（三窗）= 实付现金 = {@code Σ goodsPaymentMilli}</b> —— 只数<b>真钱</b>；
 *       未付的那一份是债务（{@code debtContracts} 另记），不进这一栏。
 * </ul>
 *
 * ★ <b>为什么必须分成两栏（D4 的由来）</b>：债务式清算下"没钱也能买（记债）"，改前那一栏只数现金 ⇒ day120 的名义货值 896,973 毫在统计簿里只剩 1,543
 * 毫（**0.17%**）；W4 拿"上周期成交额"当市场区排序量 ⇒ 排序会退化成"谁付过现金"。 两栏并存后：排序用名义（{@code turnover}），现金口径仍可读（{@code
 * cashTurnover}），谁也不冒充谁。
 *
 * <p>★ <b>两栏不互相约束</b>：名义按"量 × 单价"整轮一次取整，现金按逐笔实付累加（逐笔货款是 {@code ⌈量×单价/1000⌉}） ⇒
 * <b>单笔取整差可以让现金略大于名义</b>（小额成交尤甚）—— 故本类<b>不</b>写"现金 ≤ 名义"的守卫（那会把合法数据判死）。
 *
 * <p>★★ <b>它不改变任何行为</b>：本账是**纯读数**（写入发生在市场轮结算完成后，不参与任何撮合/定价判定）。★ 未设（空表）=
 * 这个市场还没有过统计（合法状态：新世界的第一轮之前）。
 */
public record MarketRoundStats(
    long lastRoundDay,
    Map<CommodityId, CommodityRound> lastRound,
    long lastRoundTurnoverMilli,
    long lastRoundCashTurnoverMilli,
    Map<CommodityId, CommodityRound> cycleToDate,
    long cycleTurnoverMilli,
    long cycleCashTurnoverMilli,
    Map<CommodityId, CommodityRound> previousCycle,
    long previousCycleTurnoverMilli,
    long previousCycleCashTurnoverMilli) {

  public MarketRoundStats {
    if (lastRoundTurnoverMilli < 0L
        || cycleTurnoverMilli < 0L
        || previousCycleTurnoverMilli < 0L
        || lastRoundCashTurnoverMilli < 0L
        || cycleCashTurnoverMilli < 0L
        || previousCycleCashTurnoverMilli < 0L) {
      throw new IllegalArgumentException(
          "成交额不得为负: 本轮名义="
              + lastRoundTurnoverMilli
              + " 本轮现金="
              + lastRoundCashTurnoverMilli
              + " 本周期名义="
              + cycleTurnoverMilli
              + " 本周期现金="
              + cycleCashTurnoverMilli
              + " 上周期名义="
              + previousCycleTurnoverMilli
              + " 上周期现金="
              + previousCycleCashTurnoverMilli);
    }
    // ★★ W1e（2026-10-11）：**不可变包装必须写在字段赋值处** —— SpotBugs 的 EI_EXPOSE_REP 只认赋值右侧
    //   最外层那次 `Collections.unmodifiableMap(...)`：写在 `freeze(...)` 里（跨方法调用）它看不见
    //   （仓内先例：MarketReadout.CommodityMatchReadout 的 `Collections.unmodifiableMap(copyMoney(...))`；
    //   反例：MarketRegulation 的同款冻结当初只能靠抑制注解过门禁 —— 见该类的类注）。
    //   ★ 语义与改前逐值相同：`freeze` 仍先做 键/值非空校验 + 商品 id 规范序（I7 确定性），这里再套一层
    //     LinkedHashMap 拷贝只是把"冻结"写在分析器看得见的位置（禁 Map.copyOf：迭代序不是内容的纯函数）。
    //   ★ 拷贝代价 = 该市场本轮出现过的商品数（个位~两位数），每次市场轮构建一次 ⇒ 量级可忽略。
    lastRound = Collections.unmodifiableMap(new LinkedHashMap<>(freeze(lastRound, "lastRound")));
    cycleToDate =
        Collections.unmodifiableMap(new LinkedHashMap<>(freeze(cycleToDate, "cycleToDate")));
    previousCycle =
        Collections.unmodifiableMap(new LinkedHashMap<>(freeze(previousCycle, "previousCycle")));
  }

  /**
   * ★ <b>旧四参读法的便利构造器</b>（★ W2-fix7 / D4）：按"现金栏 = 0"落一份统计。
   *
   * <p>★ 它服务的是**不建模现金腿的夹具/探针**（既有 {@code EconomyCodecTest} / {@code EconomyRoundTripTest}
   * 的三段窗口夹具就是这种形态：它们只钉"三段是三个独立的窗口"这一维）。★ 语义<b>不静默</b>：现金成交额 0 是合法状态 （整轮全是赊购），不是"照抄名义"—— 真要报现金必须走
   * {@link #withRound}（逐轮口径的唯一写口）。
   */
  public MarketRoundStats(
      long lastRoundDay,
      Map<CommodityId, CommodityRound> lastRound,
      long lastRoundTurnoverMilli,
      Map<CommodityId, CommodityRound> cycleToDate,
      long cycleTurnoverMilli,
      Map<CommodityId, CommodityRound> previousCycle,
      long previousCycleTurnoverMilli) {
    this(
        lastRoundDay,
        lastRound,
        lastRoundTurnoverMilli,
        0L,
        cycleToDate,
        cycleTurnoverMilli,
        0L,
        previousCycle,
        previousCycleTurnoverMilli,
        0L);
  }

  /** ★ <b>还没有过任何市场轮</b>（新世界的初值；不是"缺数据"）。 */
  public static final MarketRoundStats EMPTY =
      new MarketRoundStats(-1L, Map.of(), 0L, 0L, Map.of(), 0L, 0L, Map.of(), 0L, 0L);

  /**
   * ★★ <b>记一轮</b>（每市场轮结束时调用一次）：本轮明细覆盖 {@code lastRound}；本周期累计加上本轮； 关账轮（{@code cycleClosed}）⇒
   * 本周期累计**轮转**成上周期累计、本周期清零（本轮先计入本周期，再轮转 —— "关账发生在市场轮之后"，与 {@code EconomySettlement} 的关账时点同源）。
   *
   * @param day 本轮市场轮的绝对日（≥ 0）
   * @param round 本轮逐商品读数（空表 = 本轮没有任何商品成交/报价，仍是合法的一轮）
   * @param turnoverMilli 本轮**名义货值**（毫；全额货款，含赊购部分；≥ 0）
   * @param cashTurnoverMilli 本轮**实付现金**（毫；只数真钱；≥ 0）
   * @param cycleClosed 本轮是否同时是关账轮
   */
  public MarketRoundStats withRound(
      long day,
      Map<CommodityId, CommodityRound> round,
      long turnoverMilli,
      long cashTurnoverMilli,
      boolean cycleClosed) {
    Objects.requireNonNull(round, "round");
    if (day < 0L) {
      throw new IllegalArgumentException("市场轮的绝对日不得为负: " + day);
    }
    if (turnoverMilli < 0L) {
      throw new IllegalArgumentException("本轮名义货值不得为负: " + turnoverMilli);
    }
    if (cashTurnoverMilli < 0L) {
      throw new IllegalArgumentException("本轮实付现金不得为负: " + cashTurnoverMilli);
    }
    Map<CommodityId, CommodityRound> closedCycle = sum(cycleToDate, round);
    long closedTurnover = Math.addExact(cycleTurnoverMilli, turnoverMilli);
    long closedCashTurnover = Math.addExact(cycleCashTurnoverMilli, cashTurnoverMilli);
    if (cycleClosed) {
      return new MarketRoundStats(
          day,
          round,
          turnoverMilli,
          cashTurnoverMilli,
          Map.of(),
          0L,
          0L,
          closedCycle,
          closedTurnover,
          closedCashTurnover);
    }
    return new MarketRoundStats(
        day,
        round,
        turnoverMilli,
        cashTurnoverMilli,
        closedCycle,
        closedTurnover,
        closedCashTurnover,
        previousCycle,
        previousCycleTurnoverMilli,
        previousCycleCashTurnoverMilli);
  }

  /** ★ 逐商品相加（周期累计）：量相加、均价按成交量加权（见 {@link CommodityRound#plus}）。 */
  static Map<CommodityId, CommodityRound> sum(
      Map<CommodityId, CommodityRound> left, Map<CommodityId, CommodityRound> right) {
    Objects.requireNonNull(left, "left");
    Objects.requireNonNull(right, "right");
    Map<CommodityId, CommodityRound> merged = new LinkedHashMap<>(left);
    for (Map.Entry<CommodityId, CommodityRound> entry : right.entrySet()) {
      CommodityRound existing = merged.get(entry.getKey());
      merged.put(
          entry.getKey(), existing == null ? entry.getValue() : existing.plus(entry.getValue()));
    }
    return canonical(merged);
  }

  /**
   * ★ 一行摘要（日志/探针用）：本轮商品数 / <b>名义货值 + 实付现金</b> / 本周期累计商品数 / <b>本周期名义 + 现金</b> / 上周期累计商品数 / <b>上周期名义
   * + 现金</b>。
   *
   * <p>★ W2-fix7 / D4：两栏<b>都</b>打出来（只打一栏会让读者以为另一栏就是同一个数 —— 那正是 D4 的误读形态）。
   */
  public String describe() {
    return "day="
        + lastRoundDay
        + " 本轮商品="
        + lastRound.size()
        + " 本轮成交额="
        + lastRoundTurnoverMilli
        + " 本轮现金="
        + lastRoundCashTurnoverMilli
        + " 本周期商品="
        + cycleToDate.size()
        + " 本周期成交额="
        + cycleTurnoverMilli
        + " 本周期现金="
        + cycleCashTurnoverMilli
        + " 上周期商品="
        + previousCycle.size()
        + " 上周期成交额="
        + previousCycleTurnoverMilli
        + " 上周期现金="
        + previousCycleCashTurnoverMilli;
  }

  /** ★ 一个市场的统计簿按**商品 id 规范序**冻结（迭代序 = 内容的纯函数；禁 {@code Map.copyOf}）。 */
  private static Map<CommodityId, CommodityRound> freeze(
      Map<CommodityId, CommodityRound> source, String what) {
    if (source == null) {
      throw new IllegalArgumentException("MarketRoundStats." + what + " 不得为 null（没有数据请给空表）");
    }
    Map<CommodityId, CommodityRound> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, CommodityRound> entry : source.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "MarketRoundStats." + what + " 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return canonical(copy);
  }

  /** 商品 id 规范序（裸值升序）的冻结表。 */
  static Map<CommodityId, CommodityRound> canonical(Map<CommodityId, CommodityRound> source) {
    if (source.isEmpty()) {
      return Map.of();
    }
    List<CommodityId> ids = new ArrayList<>(source.keySet());
    ids.sort(Comparator.comparing(CommodityId::value));
    Map<CommodityId, CommodityRound> ordered = new LinkedHashMap<>();
    for (CommodityId id : ids) {
      ordered.put(id, source.get(id));
    }
    return Collections.unmodifiableMap(ordered);
  }

  /** ★ 市场 id 规范序的统计簿（{@code EconomyData} 构造期用：迭代序 = 内容的纯函数）。 */
  public static Map<MarketZoneId, MarketRoundStats> frozen(
      Map<MarketZoneId, MarketRoundStats> stats) {
    Objects.requireNonNull(stats, "stats");
    if (stats.isEmpty()) {
      return Map.of();
    }
    List<MarketZoneId> ids = new ArrayList<>(stats.keySet());
    ids.sort(Comparator.comparing(MarketZoneId::value));
    Map<MarketZoneId, MarketRoundStats> ordered = new LinkedHashMap<>();
    for (MarketZoneId id : ids) {
      ordered.put(id, stats.get(id));
    }
    return Collections.unmodifiableMap(ordered);
  }

  /** ★ 成交额相加（周期累计；溢出即抛，不静默回绕 —— 与全仓"精确加法"同一条纪律）。 */
  public static long addTurnover(long left, long right) {
    return Math.addExact(left, right);
  }

  /** ★ 加权均价的中间量类型（包内可见：只有 {@link CommodityRound#plus} 用它，这里只避免魔法类型散落）。 */
  static BigInteger weighted(
      BigInteger traded, BigInteger average, BigInteger otherTraded, BigInteger otherAverage) {
    return traded.multiply(average).add(otherTraded.multiply(otherAverage));
  }
}

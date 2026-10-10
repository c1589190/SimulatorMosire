package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>M-D：家户的「生产方式排序表」</b>（商户设计书 §13 I-A..I-E；计划 §2.4 M5、§6.2 V-23、§7 Q-18..Q-20）。
 *
 * <pre>
 * I-A  主业 / 副业 = **同一张排序表的第 1 项 / 其余项**（不是两个状态位）
 * I-B  排序输入 = **市场议价权 + 库存**（用来算各生产方式的预期收益）；第 1 项 ⇒ 主业
 * I-D  第 1 项是跑商 ⇒ 该家户主业 = 商户（{@code merchant.*} 位置）
 * I-1  库存 = 商品库存 + 货币余额 + 可用生产资料份额（都从既有状态读，不新增权威）
 * I-2  议价权 = 与 §11.4 G-1 **同一口径、同一拼写点**（{@link MerchantCapacity#sharePerMilleOf}）
 * I-3  破平 = **位置 id 升序**（canonical 序，I7）
 * I-4  主业改变写具名 {@code reason}（既有字段；落点在 {@link ModeMigrationSettlement}）
 * </pre>
 *
 * <p>★★ <b>它不新增任何状态</b>（§13.3 冻结）：主业 = 既有 {@code HouseholdClassMembership.currentPositionId}、 副业 =
 * {@code participatingPositionIds}、{@link
 * io.mosire.simos.economy.model.HouseholdClassMembership#effectivePositionIds()} =
 * 并集。本表只是一次**纯函数**读数/裁决：把"同一个 {@link ExpectedProfitBook.Prospect} 家族"排成一张表， 第 1 项就是 {@code current}
 * 应该落在的那一项 —— 落点仍走既有模式变迁路径（A 规则 / {@code ModeMigrationSettlement}）， 本类**不写任何状态**。
 *
 * <p>★★ <b>排序键（唯一拼写点）</b>：
 *
 * <pre>
 * 收益率（每单位劳动的预期净收益，百万分之一刻度）降序
 *   → 位置 id 升序（I-3：同收益率按 canonical 序）
 *   → 格 (q, r) 升序 → 生产方式 id 升序（★ 补足全序：位置 id 是**生产方式级**的，同一位置可以在多个格出现，
 *      只按位置 id 排不是全序 ⇒ 后两级只是"让比较成为内容的纯函数"，不改 I-3 的首级 tie-break）
 * </pre>
 *
 * <p>★★ <b>两个排序输入的具体口径（如实记，Q-18/Q-19）</b>：
 *
 * <ul>
 *   <li><b>库存</b>（I-1）＝ ① 商品库存 + ② 货币余额 折成的"家户流动性"（{@link #inventoryMilli}：本格计价币余额 + 商品库存按本格牌价折算；★
 *       该算式**搬自** {@code ModeMigrationPolicy.liquidityMilli}，全仓只此一处）＋ ③ 可用生产资料份额（由 {@link
 *       ExpectedProfitBook} 的 {@code assetScaleOf} 读，**不在此处重算**）。 ★★ A3（2026-10-10）：{@code tool}
 *       存量**不再是**跑商行的硬门槛（"每趟烧 1,000 毫工具"那一族已退役，见 {@link #merchantGateReason}）—— 跑商的生产资料 由 {@code
 *       trade} 产业的周期投入经标准管线预留与现扣（I-H6）。
 *   <li><b>市场议价权</b>（I-2）＝ **该家户在本格运力总量中的占比‰**，与 {@code MerchantCapacityPool} 的分配序
 *       **同一个拼写点**（{@code MerchantCapacity.sharePerMilleOf}，经 {@link
 *       MerchantCapacityPool#sharePerMilleAsProviderAt} 暴露）。它在本表里是**跑商行的可行性门槛**（占比‰ 向下取整为 0 ⇒
 *       本格按议价权序分不到任何运力 ⇒ 该行不成立，具名 {@code no-bargaining-power}）。 ★ <b>为什么不按占比比例缩放收益</b>：见类注末的"具名偏离
 *       D-1"。
 * </ul>
 *
 * <p>★★ <b>缺省语义中性（冻结项 6 / I-C2）</b>：单候选（只有当前那一项）⇒ 表中只有当前项 ⇒ 第 1 项 = 当前 ⇒ {@link Table#primary()}
 * 与既有 {@code currentPositionId} 同值、{@code participating} 一字不改 ⇒ <b>逐值不变</b>；
 * 没有任何排序输入变化（跑商候选的工具/议价权门槛都成立、各候选收益率不变）⇒ 行序与改前同一口径。
 *
 * <p>★★ <b>具名偏离 D-1（如实记，待控制方裁定）</b>：I-B 的字面读法之一是"议价权按占比缩放该家户的预期收益"。 本实现**不这么做**，只把占比‰ 当**门槛 +
 * 读数**，理由三条：① Q-25 已冻结"价格管买方的选择、议价权管稀缺时的分配" ⇒ 议价权不是收入的比例因子（§11.2③ 的"运力总量 = 硬上限"只在**逐 hex
 * 汇总**层面成立，逐户只能给**上界**）； ② 按比例缩放会同时压低 {@link ExpectedProfitBook} 的 {@code expectedNet} ⇒ 既有商号的 A
 * 规则读数更易变负 ⇒ 已成立的商户可能整批外流，这是本批无法用测试验证的连锁反应；③ 门槛式改动方向 fail-closed（拿不到运力 ⇒ 不把跑商读高），与 H-D「准入门槛要高」同向。★
 * 若控制方裁定改为按占比缩放，改动面 = {@code ExpectedProfitBook.merchantProspect} 里 {@code carryable} 一处 + 本类的门槛判据。
 *
 * <p>★★ <b>具名偏离 D-2（候选集口径）</b>：表的候选集 = **当前项 + 收益率严格更高的候选**（= 能取代它当主业的那些；由 {@code
 * ModeMigrationPolicy.buildTargets} 的 {@code weight > 0} 同一门槛给出）⇒ 表的"其余项"（{@link
 * Table#secondary()}）= 其他候选 + **原主业**（主业换了以后它就是一项副业）—— 与 I-C「主业随之改变」自洽。★ 它**不写** {@code
 * participatingPositionIds}：冻结项 1 只让排序表决定 {@code current} 的落点，副业的写口仍归既有路径（本批不新造第二条写 口）；{@link
 * Table#secondaryPositionIds()} 只是读口。
 *
 * <p>★ <b>确定性（I7）</b>：全部遍历按稳定 id / (q,r) 规范序；无随机、无时钟、无 UUID；不使用 {@code Map.copyOf}/{@code
 * Set.copyOf} 的迭代序；同输入同表。
 */
public final class PrimaryModeRanking {

  /** 排序表日志（migration 分类：它的生命周期就是迁移决策）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.migration();

  /** 主业变更（INFO）与排序表（DEBUG）的具名归因：第 1 项确实换了 mode。 */
  public static final String DECISION_RANKED_HIGHEST = "RANKED_HIGHEST";

  private PrimaryModeRanking() {}

  /**
   * 排序表的一行 = 一个**候选生产位置**（生产方式 × 格 ⇒ 该生产方式下的某个位置）。
   *
   * @param positionId 位置稳定 id（I-3 的 tie-break 键，也是 {@code current} 的落点候选）
   * @param modeId 该位置所属生产方式
   * @param hex 该行的格（候选评估发生在"该格的产业/市场"上）
   * @param yieldPerLaborScaled 每单位劳动的预期净收益（百万分之一刻度；与 {@link ExpectedProfitBook} 同一把尺）
   * @param bargainingPowerPerMille 排序输入①：市场议价权 = 该家户在本格运力总量中的占比‰（I-2；非跑商行 = 0）
   * @param inventoryMilli 排序输入②：库存折价（毫钱）= 本格计价币余额 + 商品库存按本格牌价折算（I-1）
   * @param reason 该行的具名依据（来自 {@link ExpectedProfitBook.Prospect#reason()}）
   */
  public record Row(
      ClassPositionId positionId,
      ProductionModeId modeId,
      HexCoord hex,
      long yieldPerLaborScaled,
      long bargainingPowerPerMille,
      long inventoryMilli,
      String reason) {

    public Row {
      Objects.requireNonNull(positionId, "PrimaryModeRanking.Row.positionId 不得为 null");
      Objects.requireNonNull(modeId, "PrimaryModeRanking.Row.modeId 不得为 null");
      Objects.requireNonNull(hex, "PrimaryModeRanking.Row.hex 不得为 null");
      Objects.requireNonNull(reason, "PrimaryModeRanking.Row.reason 不得为 null（没有就给空串）");
      if (bargainingPowerPerMille < 0L || inventoryMilli < 0L) {
        throw new IllegalArgumentException(
            "PrimaryModeRanking.Row 的议价权/库存读数不得为负: "
                + bargainingPowerPerMille
                + "/"
                + inventoryMilli);
      }
    }
  }

  /**
   * 被排序输入挡下的一行（**不静默**：进 DEBUG 日志与 {@link Table#excluded()}）。
   *
   * @param reason 具名原因（A3 起只剩 {@code no-bargaining-power}；{@code tool-stock-zero} 已随工具门槛退役）
   */
  public record Excluded(ProductionModeId modeId, HexCoord hex, String reason) {

    public Excluded {
      Objects.requireNonNull(modeId, "PrimaryModeRanking.Excluded.modeId 不得为 null");
      Objects.requireNonNull(hex, "PrimaryModeRanking.Excluded.hex 不得为 null");
      Objects.requireNonNull(reason, "PrimaryModeRanking.Excluded.reason 不得为 null");
    }
  }

  /**
   * 一个家户的排序表（不可变、保序）。
   *
   * @param household 家户身份
   * @param rows 可成立的候选行（按 {@link #rank} 的排序键排定；第 1 项 = 主业候选）
   * @param excluded 被排序输入挡下的行（具名，只进日志/读口，不参与排序）
   */
  public record Table(HouseholdId household, List<Row> rows, List<Excluded> excluded) {

    public Table {
      Objects.requireNonNull(household, "PrimaryModeRanking.Table.household 不得为 null");
      Objects.requireNonNull(rows, "PrimaryModeRanking.Table.rows 不得为 null");
      Objects.requireNonNull(excluded, "PrimaryModeRanking.Table.excluded 不得为 null");
      rows = Collections.unmodifiableList(new ArrayList<>(rows));
      excluded = Collections.unmodifiableList(new ArrayList<>(excluded));
    }

    /** 没有可成立的行（该家户无法参与任何生产方式）⇒ 不动它。 */
    public boolean isEmpty() {
      return rows.isEmpty();
    }

    /** ★ I-A：**第 1 项 = 主业**；表空 ⇒ {@code null}（不猜）。 */
    public Row primary() {
      return rows.isEmpty() ? null : rows.get(0);
    }

    /** ★ I-A：**其余项 = 副业**（第 2 项起；表空/单候选 ⇒ 空表）。 */
    public List<Row> secondary() {
      return rows.size() <= 1
          ? List.of()
          : Collections.unmodifiableList(rows.subList(1, rows.size()));
    }

    /** 副业的位置 id 清单（规范化读口；{@code current} 不在其中）。 */
    public List<ClassPositionId> secondaryPositionIds() {
      List<ClassPositionId> ids = new ArrayList<>();
      for (Row row : secondary()) {
        if (!ids.contains(row.positionId())) {
          ids.add(row.positionId());
        }
      }
      return Collections.unmodifiableList(ids);
    }

    /** 表里出现的生产方式（规范序 = 表序去重；读口/日志用）。 */
    public List<ProductionModeId> rankedModes() {
      List<ProductionModeId> modes = new ArrayList<>();
      for (Row row : rows) {
        if (!modes.contains(row.modeId())) {
          modes.add(row.modeId());
        }
      }
      return Collections.unmodifiableList(modes);
    }
  }

  /**
   * ★★ <b>把候选行排成表（唯一排序口）</b>：收益率降序 → 位置 id 升序（I-3）→ 格 (q,r) 升序 → 生产方式 id 升序。
   *
   * <p>★ 排序是**内容的纯函数**：不读哈希/插入序（I7）；同键两项在位置 id 那一级就已不可能（同一 (位置, 格) 只有一行）。
   */
  public static Table rank(
      HouseholdId household, List<Row> candidateRows, List<Excluded> excludedRows) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(candidateRows, "candidateRows");
    Objects.requireNonNull(excludedRows, "excludedRows");
    List<Row> rows = new ArrayList<>(candidateRows);
    rows.sort(
        Comparator.comparingLong((Row row) -> row.yieldPerLaborScaled())
            .reversed()
            .thenComparing(row -> row.positionId().value())
            .thenComparingInt(row -> row.hex().q())
            .thenComparingInt(row -> row.hex().r())
            .thenComparing(row -> row.modeId().value()));
    return new Table(household, rows, excludedRows);
  }

  // ── 排序输入：库存（I-1 的唯一拼写点）─────────────────────────────────────────────────────────

  /**
   * ★★ <b>排序输入"库存"的可读部分（毫钱）= 货币余额 + 商品库存折价</b>（Q-18）。
   *
   * <pre>
   * inventoryMilli(户, hex)
   *   = 该户在本格计价币下的余额（无市场 ⇒ 0）
   *   + Σ_c 库存量(毫商品) × 本格牌价(c)（毫钱/商品单位） ÷ 1000   ← 无价/零价的商品不计（不猜价）
   * </pre>
   *
   * <p>★★ <b>唯一拼写点</b>：本算式**搬自** {@code ModeMigrationPolicy.liquidityMilli}（A 规则的流动性判据）， 迁移后由
   * policy **委托**到这里 —— 全仓只此一处，排序表与 A 规则不会漂开。
   *
   * <p>★ 溢出语义（沿用原口径）：乘法溢出 ⇒ 饱和到 {@link Long#MAX_VALUE}（绝不静默回绕成负读数）。 ★ 第三项"可用生产资料份额"不在这里：它由 {@link
   * ExpectedProfitBook#prospect} 的资产规模算式读（同一权威、不重算）。
   */
  public static long inventoryMilli(
      AccountSession accounts,
      Map<HexCoord, Market> markets,
      MarketTopology topology,
      HouseholdId household,
      HexCoord hex) {
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(hex, "hex");
    Market market = marketOf(markets, topology, hex);
    Map<CommodityId, Long> stock = accounts.householdGoods().getOrDefault(household, Map.of());
    Map<CurrencyId, Long> wallet = accounts.householdMoney().getOrDefault(household, Map.of());
    long cash = market == null ? 0L : wallet.getOrDefault(market.numeraire(), 0L);
    long sellable = 0L;
    if (market != null) {
      for (Map.Entry<CommodityId, Long> good : stock.entrySet()) {
        long price = market.priceOf(good.getKey());
        if (price > 0L) {
          long value =
              saturatedMulDiv(good.getValue(), price, EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
          sellable = saturatedAdd(sellable, value);
        }
      }
    }
    return saturatedAdd(cash, sellable);
  }

  /**
   * ★★ <b>排序输入在"跑商行"上的门槛</b>（Q-18 库存 + Q-19 议价权）：成立 ⇒ {@code null}；否则返回**具名原因**。
   *
   * <pre>
   * no-bargaining-power  该户在本格作为运力提供者的议价权占比‰ = 0（§11.4 G-1 口径）⇒ 本格按议价权序分不到运力
   * </pre>
   *
   * <p>★★ <b>A3（2026-10-10）：原来的 {@code tool-stock-zero} 门槛（"tool 不够一趟 ⇒ 跑商行不成立"）已删</b> —— 它与市场轮的
   * {@code tool-short}/{@code tool-frozen} 是同一个"每趟烧 1,000 毫工具"门槛的两个面（{@code MerchantHaul}），
   * 该族已整体退役：跑商的工具消耗由 {@code trade} 产业声明的**周期投入**经标准生产管线消耗（现扣 + 挂单保留）， 与农业/手工业同口径（设计书 §3.2 / I-H6）。⇒
   * 决策层不再用"手上有多少工具"否决一个生产方式 —— 生产资料由产业投入表达，这正是用户原话「难到家户不会给预估生产方式预留生产资料吗？」指的那条路。
   *
   * <p>★ 两类行都判（候选行 + 当前主业行）。★ 它只影响**排序表的读数与候选集**；A 规则读的 {@code expectedNet} 一字不改（冻结项 5）。
   */
  public static String merchantGateReason(
      EconomyDayView base,
      HouseholdId household,
      HexCoord hex,
      Map<HouseholdId, Map<CommodityId, Long>> goods) {
    if (MerchantCapacityPool.sharePerMilleAsProviderAt(base, household, hex, goods) <= 0L) {
      return "no-bargaining-power";
    }
    return null;
  }

  // ── 日志（§一.9：INFO = 主业变更；DEBUG = 排序表与判据）────────────────────────────────────────

  /**
   * ★★ <b>INFO：主业变更</b>（谁、从哪到哪、为什么）—— 只在排序表第 1 项**换了生产方式**时打点。
   *
   * @param fromPositionId 变更前的主业位置（{@code currentPositionId}）
   * @param fromMode 变更前的主业生产方式
   * @param to 排序表第 1 项（新的主业候选 = {@code current} 的落点）
   * @param fromYield 变更前那一项的收益率读数（同尺，用于回答"为什么"）
   */
  public static void logPrimaryChange(
      long day,
      HouseholdId household,
      ClassPositionId fromPositionId,
      ProductionModeId fromMode,
      Row to,
      long fromYield) {
    Objects.requireNonNull(to, "PrimaryModeRanking.logPrimaryChange 的第 1 项不得为 null");
    if (!LOG.isInfoEnabled()) {
      return;
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "PRIMARY_MODE_CHANGED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "household",
                household.value(),
                "fromPositionId",
                fromPositionId == null ? "" : fromPositionId.value(),
                "fromModeId",
                fromMode == null ? "" : fromMode.value(),
                "toPositionId",
                to.positionId().value(),
                "toModeId",
                to.modeId().value(),
                "toHex",
                to.hex(),
                "fromYieldPerLaborScaled",
                fromYield,
                "toYieldPerLaborScaled",
                to.yieldPerLaborScaled(),
                "bargainingPowerPerMille",
                to.bargainingPowerPerMille(),
                "inventoryMilli",
                to.inventoryMilli(),
                "reason",
                DECISION_RANKED_HIGHEST,
                "rowReason",
                to.reason()));
  }

  /** ★ DEBUG：整张排序表 + 两个排序输入 + 被挡下的行（§一.9 的"判据"档）。 */
  public static void logTable(long day, Table table) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    int rank = 0;
    for (Row row : table.rows()) {
      rank++;
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "PRIMARY_MODE_RANKING_ROW",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "household",
                  table.household().value(),
                  "rank",
                  rank,
                  "modeId",
                  row.modeId().value(),
                  "positionId",
                  row.positionId().value(),
                  "hex",
                  row.hex(),
                  "yieldPerLaborScaled",
                  row.yieldPerLaborScaled(),
                  "bargainingPowerPerMille",
                  row.bargainingPowerPerMille(),
                  "inventoryMilli",
                  row.inventoryMilli(),
                  "reason",
                  row.reason()));
    }
    for (Excluded excluded : table.excluded()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "PRIMARY_MODE_RANKING_EXCLUDED",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "household",
                  table.household().value(),
                  "modeId",
                  excluded.modeId().value(),
                  "hex",
                  excluded.hex(),
                  "reason",
                  excluded.reason()));
    }
  }

  // ── 小工具（与 ModeMigrationPolicy 共用的饱和算术；搬自该类的私有副本）─────────────────────────

  /** 精确 {@code value × multiplier ÷ divisor}；乘法溢出 ⇒ 饱和到 {@link Long#MAX_VALUE}（不静默回绕）。 */
  public static long saturatedMulDiv(long value, long multiplier, long divisor) {
    if (value <= 0L || multiplier <= 0L || divisor <= 0L) {
      return 0L;
    }
    try {
      return Math.multiplyExact(value, multiplier) / divisor;
    } catch (ArithmeticException overflow) {
      return Long.MAX_VALUE;
    }
  }

  /** 饱和加法：真的越过 {@link Long#MAX_VALUE} ⇒ 饱和（不静默回绕）。 */
  public static long saturatedAdd(long left, long right) {
    if (left < 0L || right < 0L) {
      throw new IllegalArgumentException("饱和加法的入参不得为负: " + left + " + " + right);
    }
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  /** 本格价表：优先本格市场；无则退到该格所在区的集散节点市场（区内同价）；都没有 ⇒ null。 */
  static Market marketOf(Map<HexCoord, Market> markets, MarketTopology topology, HexCoord hex) {
    Market direct = markets.get(hex);
    if (direct != null) {
      return direct;
    }
    if (topology != null) {
      try {
        return markets.get(topology.regionOf(hex).anchor());
      } catch (IllegalArgumentException ignored) {
        // 该格不在拓扑里（没有市场）⇒ 没有价
      }
    }
    return null;
  }
}

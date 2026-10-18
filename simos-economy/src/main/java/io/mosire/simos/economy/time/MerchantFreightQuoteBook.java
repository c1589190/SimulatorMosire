package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MerchantCapacityPool.CarrierAllocation;
import static io.mosire.simos.economy.time.MerchantCapacityPool.CarrierChoice;
import static io.mosire.simos.economy.time.MerchantCapacityPool.LogisticsBlock;
import static io.mosire.simos.economy.time.MerchantCapacityPool.RETIRED_PARALLEL_MACHINE_FAMILIES;
import static io.mosire.simos.economy.time.MerchantCapacityPool.RETIRED_PARALLEL_MACHINE_REPLACEMENTS;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CurrencyId;
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
import java.util.Set;

class MerchantFreightQuoteBook {

  /** 运力池日志（market 分类：它的生命周期就是市场轮）。 */
  static final org.slf4j.Logger LOG = EconomyLog.market();

  /** ★★ <b>A2：走"运输服务货"口径的格集</b>（空集 = 缺省口径，逐值退回改前）。只作日志归因（"为什么这一格买不到运力"）。 */
  final Set<HexCoord> haulServiceHexes;

  /**
   * ★★ <b>F-2（2026-10-10）：本轮的"装配期缺货计数"（**瞬态、每轮一份、只进日志**）</b>—— 键 = 具名词表，值 = 该事实
   * 在本轮出现的**家户数**。当前唯一在册的键是 {@link #NO_SERVICE_GOODS_REASON}（服务成市格里"选了跑商、但手上可交付服务货 为 0"的家户数）。
   *
   * <p>★ 它<b>不是</b>状态组件：不落盘、不进 codec / 变更集、不参与任何判据、不改变任何返回值； 每轮装配时新建（与池同生命周期）。 理由是 §一.9
   * 要求"业务拒绝在公开读数面可分辨"——把"没有服务卖"（本表 + 运行期 {@code MARKET} 的 {@code
   * logisticsDetailQuantities}）与"没有承运人"（{@link #NO_MERCHANT_HOUSEHOLD_REASON}）分开，读者不必开 DEBUG。
   */
  final Map<String, Long> serviceAbsentTally;

  /** 逐格池（键序 = hex 的规范序；值按分配序、不可变）。★ W7 起它只服务**逐格读数**与"本格有没有承运方"的查询。 */
  final Map<HexCoord, List<Entry>> byHex;

  /**
   * ★★★ <b>W7（2026-10-11）：全局分配序 —— <b>本池唯一的分配次序</b></b>（用户裁定：运力提供**全市场区共享**，跨区/跨币亦可被雇）。
   *
   * <pre>
   * 序 = 限价升序（报价口径：买方从**最低价**起买，M0d 原文"向市场上最低价的运力提供商买运力"）
   *       → 同价按**全池**议价权占比‰ 降序 → 家户 id 升序（I7：内容的纯函数）
   *
   * 与逐格序（{@link #byHex}）的关系：只含一个非空格的池上，两者**逐项相同** ⇒ 改前改后逐值可比。
   * </pre>
   *
   * <p>★★ <b>为什么必须有它</b>：改前 {@link #select} 只从 {@code byHex.get(发货格)} 里挑 ⇒ 发货格没有承运方（或没有服务货）时， 该
   * lane **一个候选都看不到**，哪怕隔壁格的商号手上正握着一堆卖不掉的运输服务（实测：全世界 19/37 格的运力预算恒 0， 而需求每格都在产生）。W7 起候选集 = 全池。
   */
  final List<Entry> globalOrder;

  /** 逐格运力预算（毫商品·程＝报价口径 / 毫商品＝缺省口径；与 {@link #byHex} 同键集）。 */
  final Map<HexCoord, Long> totalCapacityByHex;

  /** 有运力的家户数（INFO 汇总用）。 */
  final long householdCount;

  /** 本轮的运力预算总额（毫商品·程＝报价口径 / 毫商品＝缺省口径；INFO 汇总用）。 */
  final long totalCapacityMilli;

  /** 家户 → 它的池条目（{@link #recordFee} 用；键域 = 上面所有条目的家户）。 */
  final Map<HouseholdId, Entry> byHousehold;

  /** 是否成市（报价口径）。{@code false} ⇒ 缺省口径（M-A1 逐值不变）。★ A3 起由调用方显式给（不再由报价表推出）。 */
  final boolean priced;

  /** 本轮所有提供者的最高限价（‰；报价口径下 "计划单位运费" 的上界）。 */
  final long maxAskPerMille;

  /**
   * ★★ <b>W7-fix3（2026-10-11）：本轮的世界日</b>—— {@code select} 的 {@code
   * MERCHANT_CAPACITY_LANE_TRUNCATED} 一行里 {@code day} 字段的来源；由 tick 面的装配调用方给（{@code
   * EconomySettlement} 的装配点），与同一轮 {@code MarketSettlement.MarketRound} 的 {@code day}
   * <b>同源同值</b>（同一个 tick 局部量）。
   *
   * <p>★ <b>为什么由装配点给、而不是 {@code select} 的入参</b>：池本身就是"一轮一份"的对象（同 {@code priced} / {@code
   * haulServiceHexes}），日属于<b>轮的身份</b>；放进装配点 ⇒ 本轮所有 lane 必然读到同一个日，不存在"某一笔 传错日"的形态。★
   * 它<b>只被日志读</b>：不进任何判据、不改任何数值、不落状态。
   */
  final long day;

  /**
   * ★★ <b>2026-10-10：池侧"未服务量观察"的净额簿</b>（{@link LaneUnservedObservationBook}）—— <b>只服务日志读数</b>：
   * 同一请求被反复观察时只净记一次，供 {@link #select} 的 DEBUG 字段与 {@link #logRoundSummary} 的日合计读。 ★
   * 它<b>不参与任何判据</b>、不改 {@link CarrierAllocation} 的任何数值；逐轮瞬态（本池本身一轮一份）。
   */
  final LaneUnservedObservationBook unservedObservations = new LaneUnservedObservationBook();

  MerchantFreightQuoteBook(
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
    this.byHex = byHex;
    this.totalCapacityByHex = totalCapacityByHex;
    this.byHousehold = byHousehold;
    this.globalOrder = globalOrder;
    this.householdCount = householdCount;
    this.priced = priced;
    this.maxAskPerMille = maxAskPerMille;
    this.day = day;
    // ★★ F-2（2026-10-10）：缺货计数的**构造期防御性拷贝**（保序 + 不可变；I7 禁 Map.copyOf）。
    this.serviceAbsentTally = Collections.unmodifiableMap(new LinkedHashMap<>(serviceAbsentTally));
    this.haulServiceHexes =
        haulServiceHexes == null
            ? Set.of()
            : Collections.unmodifiableSet(new LinkedHashSet<>(haulServiceHexes));
    long sum = 0L;
    for (long value : totalCapacityByHex.values()) {
      sum = Math.addExact(sum, value);
    }
    this.totalCapacityMilli = sum;
  }

  /**
   * ★★ <b>F-2：本轮的装配期缺货计数（保序、不可变；没有 ⇒ 空表）</b>—— 供逐轮 INFO 汇总读。
   *
   * <p>★ 保序 = {@code LinkedHashMap} + {@code Collections.unmodifiableMap}（I7 确定性；禁 {@code
   * Map.copyOf}）。 它是**只读视图**：键的插入序由首次出现序决定（装配按家户 id 升序遍历 ⇒ 内容的纯函数）。
   */
  public Map<String, Long> serviceAbsentTally() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(serviceAbsentTally));
  }

  /** 池里一条家户运力（可变剩余量；只允许协调器单线程触碰）。 */
  static final class Entry {
    final MerchantCapacity capacity;
    final long askPerMille;
    final boolean pureMerchant;

    long remainingWorkMilli;
    long allocatedGoodsMilli;
    long allocatedWorkMilli;
    long allocationCount;
    long sharePerMille;

    /** ★ 本轮的运费实收（按币分列）—— **每轮算出的读数**，只进日志/读口，不落状态（冻结项 6 的"不落状态"）。 */
    final Map<CurrencyId, Long> earnedByCurrency = new LinkedHashMap<>();

    Entry(MerchantCapacity capacity, long askPerMille, boolean pureMerchant, long workBudgetMilli) {
      this.capacity = capacity;
      this.askPerMille = askPerMille;
      this.pureMerchant = pureMerchant;
      // ★★ A2：本轮运力预算的**唯一产生点** —— 缺省口径 = {@link MerchantCapacity#capacityMilli()}（劳动 + 工具，
      //   逐值不变）；服务成市的格 = 该户手上的运输服务货（{@code assemble} 现算，I-H2）。
      // ★★ F-1（2026-10-10）：服务成市格的 budget 与 capacity 是**同一个操作数**（服务货可用量）——
      //   这正是"进池判据 = 有服务货"的算术落点；labor/tool 两栏在服务口径下只作读数。
      this.remainingWorkMilli = workBudgetMilli;
    }
  }

  public boolean isEmpty() {
    return byHex.isEmpty();
  }

  /** 本轮是否成市（报价口径：家户挂了运力单/自报价）。{@code false} ⇒ 缺省口径（M-A1 逐值不变）。 */
  public boolean isPriced() {
    return priced;
  }

  /** 本轮所有提供者的最高限价（‰）；空池/无报价 ⇒ 0。用作"计划单位运费"的上界。 */
  public long maxAskPerMille() {
    return maxAskPerMille;
  }

  /** 这一格有没有运力（静态：装配时的运力 > 0；被本轮别处吃光不影响本判据 —— 那由 select/executeTrade 判）。 */
  public boolean hasCapacityAt(HexCoord hex) {
    return byHex.containsKey(hex);
  }

  /** 这一格的运力总量（毫商品·程＝报价口径 / 毫商品＝缺省口径；没有 ⇒ 0）。 */
  public long totalCapacityAt(HexCoord hex) {
    return totalCapacityByHex.getOrDefault(hex, 0L);
  }

  /** 这一格的提供者家户数（INFO 汇总用；没有 ⇒ 0）。 */
  public long householdCountAt(HexCoord hex) {
    return byHex.getOrDefault(hex, List.of()).size();
  }

  /** 这一格**剩下**的运力（毫商品·程＝报价口径 / 毫商品＝缺省口径；分配后就地减少）。 */
  public long remainingCapacityAt(HexCoord hex) {
    long remaining = 0L;
    for (Entry item : byHex.getOrDefault(hex, List.of())) {
      remaining = Math.addExact(remaining, item.remainingWorkMilli);
    }
    return remaining;
  }

  /** 有运力的家户数（INFO 汇总用）。 */
  public long householdCount() {
    return householdCount;
  }

  /**
   * ★★★ <b>A5（2026-10-10，T-H3 的读数来源）：本轮**逐户运费实收**（= 服务成交的钱腿收款人侧）</b>—— 按币分列。
   *
   * <pre>
   * 键 = 家户 id（**家户 id 升序**，确定性 I7）；值 = {币种（规范串升序） → 毫}
   * 只含本轮真的收到钱的户（零额/没成交的户不落键）⇒ 空表 = 本轮没有服务成交（不是 0）
   * </pre>
   *
   * <p>★★ <b>它解决什么</b>：服务不进订单簿（需求由其他商品的购买派生），所以 {@code MarketReport.fills()} 里 <b>结构上永远没有</b>服务成交 ⇒
   * 跑商产业的 {@code OperatorCondition.cycleRevenueByCurrency} 恒空、 {@code lastCycleNetMilli} 只剩成本一项（A4
   * 账本 §5-3 实测：{@code trade@0_*} 恒 {@code {} } / net −104，误导）。
   * 本条读数是"服务收入确实体现为该产业经营者的收益"的**唯一来源**：{@code MarketSettlement} 把它随 {@code MarketOutcome} 交出，{@code
   * OperatorSettlement} 按收款家户归入它名下产出运输服务的那个 unit。
   *
   * <p>★ <b>它是读数、不是账</b>：钱腿本身只有一条（{@code MARKET_TRADE}，买方 → 承运家户），{@link #recordFee} 只是把同一条腿按户归集一次
   * ⇒ 与 {@code EnterpriseProfitBook} 读同一批转移腿，<b>不构成第二本账</b>（I-H5 不双记）。 保序 = {@code LinkedHashMap} +
   * {@code Collections.unmodifiableMap}（I7：禁 {@code Map.copyOf}）。
   */
  public Map<HouseholdId, Map<CurrencyId, Long>> freightEarnedByHousehold() {
    List<HouseholdId> households = new ArrayList<>(byHousehold.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    Map<HouseholdId, Map<CurrencyId, Long>> ordered = new LinkedHashMap<>();
    for (HouseholdId household : households) {
      Entry entry = byHousehold.get(household);
      Map<CurrencyId, Long> earned = new LinkedHashMap<>();
      List<CurrencyId> currencies = new ArrayList<>(entry.earnedByCurrency.keySet());
      currencies.sort(Comparator.comparing(CurrencyId::value));
      for (CurrencyId currency : currencies) {
        long amountMilli = entry.earnedByCurrency.getOrDefault(currency, 0L);
        if (amountMilli != 0L) {
          earned.put(currency, amountMilli);
        }
      }
      if (!earned.isEmpty()) {
        ordered.put(household, Collections.unmodifiableMap(earned));
      }
    }
    return Collections.unmodifiableMap(ordered);
  }

  /**
   * ★★ <b>本轮的**跑商家户**（= 池成员，判据 {@link MerchantIdentity#selectsMerchant}）</b>。
   *
   * <p>★ A3 起唯一的生产调用点（"商号利润读数的范围"）已随 {@code MerchantProfitBook} 退役 ⇒ 现在只剩夹具/读口的
   * 键集查询用途（验收测试用它断言"谁进池了"）。★ 返回的是 {@link #byHousehold} 的键集（构造期已冻结、不可改）； 顺序由调用方自己规范（本仓 I7）。
   */
  public java.util.Set<HouseholdId> householdIds() {
    return byHousehold.keySet();
  }

  /** 有运力的格数（INFO 汇总用）。 */
  public int hexCount() {
    return byHex.size();
  }

  /** 本轮的运力总预算（毫商品·程＝报价口径 / 毫商品＝缺省口径；INFO 汇总用）。 */
  public long totalCapacityMilli() {
    return totalCapacityMilli;
  }

  /**
   * ★★ <b>本 lane 的运力耗用系数（‰）</b>——报价口径按"数量 × 距离"（{@link CapacityDemand}，毫商品·程）， 缺省口径按 M-A1 的 1:1（毫商品
   * ⇒ 逐值不变）。
   */
  public long workPerGoodPerMilleOf(long commodityBaseMilli, long ratePerMille) {
    if (!priced) {
      return CapacityDemand.GOODS_ONLY_WORK_PER_GOOD_PER_MILLE;
    }
    return CapacityDemand.workPerGoodPerMille(commodityBaseMilli, ratePerMille);
  }

  /**
   * ★★ <b>按买方选择键分配一条 lane 的运力</b>（缺省口径 1:1；等价于 M-A1 的旧签名）。
   *
   * @param from 发货格（运力池所在的格）
   * @param to 收货格（判半径）
   * @param quantityMilli 本笔请求承运量（毫商品）
   */
  public CarrierAllocation select(HexCoord from, HexCoord to, long quantityMilli) {
    return select(from, to, quantityMilli, CapacityDemand.GOODS_ONLY_WORK_PER_GOOD_PER_MILLE);
  }

  /**
   * ★★ <b>无请求身份的分配入口</b>（等价于 5 参重载传 {@code requestKey = null}）—— 夹具 / 纯状态读者 / 旧调用方走这一条。
   *
   * <p>★ 读数（日志）的差别只有两个：① {@code MERCHANT_CAPACITY_LANE_TRUNCATED} 的 {@code
   * requestKeyTracked=false}、 {@code unallocatedObservationIndex} 恒 1（每次调用自己成一份，因为这里没有"哪一次请求"的身份）；②
   * 若本池是走**不带 世界日**的 {@code of} 重载装配的（夹具 / 纯状态读者），该事件**不带 {@code day} 字段**（{@link #NO_WORLD_DAY}；
   * 生产路径的池带日 ⇒ 照常打头输出）。<b>算法与判据与 5 参重载完全同一条路径</b>（见那个重载的说明），返回值逐值相同。
   *
   * @param from 发货格（运力池所在的格）
   * @param to 收货格（判半径）
   * @param quantityMilli 本笔请求承运量（毫商品）
   * @param workPerGoodPerMille 本 lane 的运力耗用（‰；{@link CapacityDemand}。缺省口径传 1000 = 1:1）
   */
  public CarrierAllocation select(
      HexCoord from, HexCoord to, long quantityMilli, long workPerGoodPerMille) {
    return select(from, to, quantityMilli, workPerGoodPerMille, null);
  }

  /**
   * ★★ <b>按买方选择键（限价）分配一条 lane 的运力</b>（K-C/K-2/Q-25/Q-27）。
   *
   * <pre>
   * 候选 = 发货格 from 的池 ∩（剩余运力预算 > 0）∩（lane 长度 ≤ 该家户派生半径）
   * 序   = 限价升序（报价口径；买方从**最低价**起买）→ 同价按市场议价权（占比‰ 降序 → 家户 id 升序）
   * 分配 = 逐条 min(剩余需求, 该户剩余预算还能承接的商品量)，就地扣减（报价口径按"数量 × 距离"扣）
   * 不变量 Σ choices.quantityMilli + unallocated == requested，且 Σ 扣减 ≤ 该格运力预算（A3）
   * </pre>
   *
   * <p>★ <b>排"限价"而不是排"容量"</b>：这正是用户裁定的"按市场上最低价的运力提供商买运力"；同价时才由市场议价权 （稀缺分配键）决定先后 ——
   * 两条各管一层（Q-25），<b>不另设特权队列</b>。
   *
   * <p>★ <b>供给不足（K-4/Q-27）</b>：没分到的部分（{@code unallocatedMilli}）由调用方收缩成交量 ⇒ <b>不成交、不成债、
   * 不计价</b>；被挡下的部分不提价不压价（V-20 的截断剔除）。本类不静默丢。
   *
   * <p>★★ <b>A3（2026-10-10）：本条能否成立只看服务货</b> —— 判据 = 该条目的 {@code remainingWorkMilli &gt; 0} （服务成市格 =
   * 手上的 {@code haul} 服务货，I-H2）。旧"工具门槛"（每趟 ≥ 1,000 毫工具，{@code tool-frozen} / {@code tool-short}
   * 两支归因）已随 {@code MerchantHaul} 整体退役：工具由 {@code trade} 产业的周期投入经标准生产管线 消耗（设计书 §3.2 / I-H6）⇒
   * 它不再是市场轮的准入判据。
   *
   * <p>★★ <b>2026-10-10 池侧读数去重（只动读数）</b>：{@code demandLeft > 0} 时本方法记一笔"未服务量观察"，并另发 {@code
   * unallocatedNetMilli} / {@code unallocatedObservationIndex} / {@code requestKeyTracked} 三个字段 ——
   * <b>口径写在类注里</b>（"Σ 原始读数不可当真实量、Σ 净额可以"）。★ 去重只读 {@code requestKey}、只写本类的观察簿， 不改本方法的任何返回值。
   *
   * @param from 发货格（运力池所在的格）
   * @param to 收货格（判半径）
   * @param quantityMilli 本笔请求承运量（毫商品）
   * @param workPerGoodPerMille 本 lane 的运力耗用（‰；{@link CapacityDemand}。缺省口径传 1000 = 1:1）
   * @param requestKey <b>请求身份</b>（{@link LaneUnservedBook#pairKey}：车道键 + 买槽序 &gt; 卖槽序）—— 只服务上面那三个
   *     日志字段的去重；{@code null} = 无身份（夹具 / 纯状态读者）⇒ 本次调用自己成一份
   */
  public CarrierAllocation select(
      HexCoord from, HexCoord to, long quantityMilli, long workPerGoodPerMille, String requestKey) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (quantityMilli < 0L) {
      throw new IllegalArgumentException("承运需求不得为负: " + quantityMilli);
    }
    if (workPerGoodPerMille <= 0L) {
      throw new IllegalArgumentException("运力耗用系数必须为正: " + workPerGoodPerMille);
    }
    // ★★★ F1（W7-fix，2026-10-11）：归因的判据必须读**全池**（{@code globalOrder}）—— 候选集自 W7 起就是全池，
    //   用发货格的池切片会把"有承运方但都在半径外"误报成 "no-merchant-household-in-shipping-hex"（测试批
    //   {@code HaulGlobalPoolW7Test} 去掉 {@code emptyLocalMerchant} 即最小复现）。★ 纯读数面：
    //   choices / unallocatedMilli / 任何数值一字不动。★ 逐格运力读数也不经过池切片（它读
    //   {@code totalCapacityByHex}，见下面 DEBUG 行的 {@code hexTotalCapacityMilli}）。
    //   ★★★ W7-fix3（2026-10-11）：改前这里还留着 W7 改写后的**死存储**
    //     `List<Entry> pool = byHex.getOrDefault(from, List.of());`（此后一次也没被读 ⇒ SpotBugs
    //     DLS_DEAD_LOCAL_STORE）—— 已删。★ 删它不动任何数值：`byHex.getOrDefault` 是纯读（无副作用、`byHex`
    //     由构造器保证非 null），本方法此后不再从 {@code byHex} 取任何切片。
    List<Entry> candidatePool = globalOrder;
    List<CarrierChoice> choices = new ArrayList<>();
    long demandLeft = quantityMilli;
    long unreachableWork = 0L;
    long subUnitWork = 0L;
    // ★★★ W7：口径不一致（服务成市 / 未成市）而被跳过的条目数 —— 只作读数（不是"够不着"，见下方口径闸）。
    long differentMarketKindCount = 0L;
    // ★★★ W7（2026-10-11）：候选集 = **全池**（用户裁定：运力提供全市场区共享，跨区/跨币亦可被雇）。
    //   改前是 `byHex.getOrDefault(from, ...)`（只认发货格的池）⇒ 发货格没有承运方/没有服务货时本 lane 一个候选都看不到。
    //   ★ 物理约束仍在（fail-closed，只可能变严不变松）：承运方必须**跑得到**这条 lane —— 行程 = 自家格 → 发货格 → 收货格，
    //     总里程 ≤ 它的派生服务半径（{@link MerchantCapacity#serviceRadiusHex()}）。承运方就在发货格时，
    //     该式**逐值退化**为既有的 `hex.distanceTo(to) <= radius`（改前语义）⇒ 既有世界逐值可比。
    for (Entry item : globalOrder) {
      if (demandLeft <= 0L) {
        break;
      }
      if (!item.capacity.servesTrip(from, to)) {
        unreachableWork = Math.addExact(unreachableWork, item.remainingWorkMilli);
        continue;
      }
      // ★★★ W7（2026-10-11）：**口径必须与这条 lane 一致** —— 服务成市的 lane（运费走"服务商品成交"那条腿、
      //   货腿在 {@code deliverHaulService} 里按**手上的服务货**实扣）只能由**服务货口径**的承运方承接；
      //   未定价的 lane（运费走既有 CARRIER_FEE 腿、运力由"劳动 + 工具"派生）只能由未定价格的承运方承接。
      //   ★ 为什么必须有这一道：全局共享之后，两类承运方**进了同一个候选集** —— 若让一个"劳动 + 工具"口径的
      //     承运方去接一条服务成市的 lane，{@code deliverHaulService} 会在它账上找不到 {@code haul} 货
      //     ⇒ 具名 ERROR {@code HAUL_SERVICE_DELIVERY_FAULT} + 整笔成交作废（改前这种组合结构上到不了，
      //     因为候选只来自发货格、口径必然相同）。
      //   ★ 缺省中性：{@code haulServiceHexes} 为空（未定价世界 / 夹具）时两个判据恒等 ⇒ 逐值退回改前。
      if (haulServiceHexes.contains(from) != haulServiceHexes.contains(item.capacity.hex())) {
        differentMarketKindCount++;
        continue;
      }
      if (item.remainingWorkMilli <= 0L) {
        continue;
      }
      // ★★ A3（2026-10-10）：**这里原本还有一道"工具门槛"（每趟 ≥ 1,000 毫工具，缺则本条不成立）——已删**。
      //   理由（设计书 §3.2 / §3.4 / I-H6）：跑商的手工门槛被"跑商是一种生产方式"取代 ——
      //   工具由 {@code trade} 产业声明的**周期投入**（{@code cycleInputPerUnit = {CATTLE:{tool:100}}}）
      //   经标准生产管线现扣、并经标准挂单保留预留（{@code necessaryInputsOf} 的 {@code industry.inputPerUnit()} 循环）；
      //   市场轮里"每趟烧 1,000 毫工具"那一套（{@code MerchantHaul}）已整体退役。
      //   ⇒ 本条能否成立只看**手上有没有服务货**（{@code remainingWorkMilli}，I-H2），不再看工具存量。
      long maxGoods = CapacityDemand.maxGoodsFor(item.remainingWorkMilli, workPerGoodPerMille);
      if (maxGoods <= 0L) {
        // 剩余预算不足一个整商品单位（报价口径下"数量 × 距离"放大了耗用 ⇒ 这一步会真的发生）：
        // 它没有被吃掉，只是这一笔用不上；具名计数，不静默。
        subUnitWork = Math.addExact(subUnitWork, item.remainingWorkMilli);
        continue;
      }
      long take = Math.min(demandLeft, maxGoods);
      long consumed = CapacityDemand.workConsumedBy(take, workPerGoodPerMille);
      item.remainingWorkMilli -= consumed;
      item.allocatedGoodsMilli = Math.addExact(item.allocatedGoodsMilli, take);
      item.allocatedWorkMilli = Math.addExact(item.allocatedWorkMilli, consumed);
      item.allocationCount++;
      demandLeft -= take;
      choices.add(
          new CarrierChoice(
              item.capacity.carrier(),
              item.capacity.household(),
              item.capacity.hex(),
              item.capacity.tier(),
              item.askPerMille,
              item.pureMerchant,
              take,
              consumed));
    }
    long allocated = quantityMilli - demandLeft;
    // ★★ F-2（2026-10-10）：本笔未获服务的具名归因档（**一次算、两处读** —— DEBUG 行与返回值读的是同一份）。
    //   ★★★ W7（2026-10-11）："没有可交付服务"的判据从**逐格**（发货格的预算合计 ≤ 0）改成**全池**
    //     （{@code totalCapacityMilli <= 0}：装配时全池一件可交付服务货都没有）—— 因为池现在全局共享，
    //     发货格预算为 0 已经**不再**意味着"买不到运力"（这正是本批要修的那件事）。★ 装配后被本轮别的 lane 吃光
    //     走的是 ⑤ capacity-exhausted（既有口径不动）。
    boolean serviceSupplyEmpty = totalCapacityMilli <= 0L;
    LogisticsBlock logisticsBlock =
        logisticsBlockOf(candidatePool, unreachableWork, subUnitWork, serviceSupplyEmpty);
    // ★★ 2026-10-10：真有未服务量时先记一笔"观察"（无条件记 —— 与日志档位无关，否则 INFO 汇总会在只开 INFO 时假报 0），
    //   再按档位发 DEBUG 行。★ 记账结果**只被下面那几个字段读**：不改 demandLeft、不改 choices、不改返回值。
    if (demandLeft > 0L) {
      LaneUnservedObservationBook.Observation observation =
          unservedObservations.observe(requestKey, demandLeft);
      if (LOG.isDebugEnabled()) {
        // ★★ W7-fix3（2026-10-11）：本事件补 `day`（**打头**，字段名与同族 {@code MERCHANT_CAPACITY_POOL} /
        //   {@code MERCHANT_CAPACITY_POOL_HEX} / {@code MERCHANT_CAPACITY_HOUSEHOLD} 同拼写、同位置）——
        //   改前它没有日 ⇒ 读数只能给整轮合计、无法按窗口（日）拆。★ D4 纪律：**只在知道世界日时写**
        //   （{@link #day} ≥ 0 = 池由 tick 面装配）；夹具 / 纯状态读者（{@link #NO_WORLD_DAY}）不带此栏，
        //   绝不用假日凑数 —— 与 {@code EconomySettlement.logEmptiedHouseholdDebtRetained} 同一守卫形态。
        //   ★ 级别、发射条件（{@code demandLeft > 0} 且 DEBUG 开）与其余字段一字未改。
        List<Object> fields = new ArrayList<>(46);
        if (day >= 0L) {
          fields.add("day");
          fields.add(day);
        }
        fields.add("from");
        fields.add(from);
        fields.add("to");
        fields.add(to);
        fields.add("requestedMilli");
        fields.add(quantityMilli);
        fields.add("allocatedMilli");
        fields.add(allocated);
        // ★★ 口径（类注同文）：这是**本次观察**的原始值 —— **不可**逐行相加当真实量（同一请求会被反复观察）。
        fields.add("unallocatedMilli");
        fields.add(demandLeft);
        // ★★ 净额（每份未服务量只算一次）：**对全部行求和 = 真实量**（可当真实量用）。
        fields.add("unallocatedNetMilli");
        fields.add(observation.netMilli());
        // ★★ 该请求的第几次观察（1 起）：≥2 ⇒ 本行与前面某行是**同一份**量（重复倍数的下界读数）。
        fields.add("unallocatedObservationIndex");
        fields.add(observation.observationIndex());
        // ★★ 有没有请求身份：false = 4/5 参旧路径（夹具/纯状态读者）⇒ 本次调用自己成一份。
        fields.add("requestKeyTracked");
        fields.add(observation.requestKeyTracked());
        fields.add("workPerGoodPerMille");
        fields.add(workPerGoodPerMille);
        fields.add("requestedWorkMilli");
        fields.add(CapacityDemand.workMilliOf(quantityMilli, workPerGoodPerMille));
        fields.add("allocatedWorkMilli");
        fields.add(CapacityDemand.workMilliOf(allocated, workPerGoodPerMille));
        fields.add("hexTotalCapacityMilli");
        fields.add(totalCapacityByHex.getOrDefault(from, 0L));
        fields.add("outOfRadiusWorkMilli");
        fields.add(unreachableWork);
        fields.add("subUnitResidualWorkMilli");
        fields.add(subUnitWork);
        fields.add("differentMarketKindCarriers");
        fields.add(differentMarketKindCount);
        fields.add("priced");
        fields.add(priced);
        fields.add("reason");
        fields.add(
            laneBlockedReason(candidatePool, unreachableWork, subUnitWork, serviceSupplyEmpty));
        // ★★ F-2（2026-10-10）：**可分辨的归因档**（与上面 reason 同一组操作数算出的单一支）——
        //   业务拒绝（缺提供商 / 缺服务货）的档位在 INFO 汇总里也出现，不必开 DEBUG。
        fields.add("logisticsDetail");
        fields.add(logisticsBlock.detail());
        fields.add("businessRejection");
        fields.add(logisticsBlock.businessRejection());
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "MERCHANT_CAPACITY_LANE_TRUNCATED",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    fields.toArray()));
      }
    }
    // ★★ F-2：**具名归因随分配结果一起返回**（"没有服务卖" / "运力不足" / …）—— 读数面据此分档，
    //   不再让两件事在公开读数里同为 LOGISTICS_CAPACITY。★ 分配全成功 ⇒ UNSPECIFIED（不硬塞一个假原因）。
    return new CarrierAllocation(choices, quantityMilli, demandLeft, priced, logisticsBlock);
  }

  /**
   * ★ <b>这一笔为什么没走完</b>（具名归因，按固定次序拼接：本格没有跑商家户 / 本格没有服务货 / 半径外 / 亚单位残余 / 运力耗尽）—— 只在 DEBUG
   * 行里出现，判据本身不改任何行为。★ 多个原因同时成立时**全部列出**（不挑一个代表性说法）。
   *
   * <p>★★ <b>A3（2026-10-10）</b>：原来还有两支"缺工具"归因（{@code tool-frozen} / {@code tool-short}）—— 门槛已删 （见
   * {@link #select}），归因随之退役。
   *
   * <p>★★ <b>F-2（2026-10-10）</b>：本方法与 {@link #logisticsBlockOf} 读**同一组**操作数、各司其职 —— 本方法给"全部同时成立的
   * 原因"（DEBUG 行的诊断串），后者给**可分辨的那一支**（见该方法的优先级表）。
   */
  static String laneBlockedReason(
      List<Entry> candidatePool,
      long unreachableWork,
      long subUnitWork,
      boolean serviceSupplyEmpty) {
    LogisticsBlock block =
        logisticsBlockOf(candidatePool, unreachableWork, subUnitWork, serviceSupplyEmpty);
    if (block == LogisticsBlock.NO_MERCHANT_HOUSEHOLD
        || block == LogisticsBlock.NO_CARRIER_WITH_SERVICE) {
      return block.detail();
    }
    List<String> reasons = new ArrayList<>(4);
    if (unreachableWork > 0L) {
      reasons.add(LogisticsBlock.OUT_OF_RADIUS.detail());
    }
    if (subUnitWork > 0L) {
      reasons.add(LogisticsBlock.SUB_UNIT_RESIDUAL.detail());
    }
    if (block == LogisticsBlock.CAPACITY_EXHAUSTED) {
      reasons.add(LogisticsBlock.CAPACITY_EXHAUSTED.detail());
    }
    if (reasons.isEmpty()) {
      reasons.add(LogisticsBlock.UNSPECIFIED.detail());
    }
    return String.join("+", reasons);
  }

  /**
   * ★★ <b>F-2（2026-10-10）：把"这一笔为什么没走完"收敛成**一个可分辨的档**</b>（与 {@link #laneBlockedReason} 同一组操作数）——
   * 这就是"没有服务卖"与"运力不足"在公开读数面分开的落点。
   *
   * <pre>
   * 次序（先命中先返回；次序本身是口径的一部分）：
   * ① 全池为空                                      ⇒ NO_MERCHANT_HOUSEHOLD（业务拒绝）
   * ② 装配时全池一件可交付服务货都没有（预算合计 ≤ 0）⇒ NO_CARRIER_WITH_SERVICE（业务拒绝）
   * ③ 有预算但全部够不着（总行程超半径）               ⇒ OUT_OF_RADIUS
   * ④ 有够得着的预算但不足一个整商品单位              ⇒ SUB_UNIT_RESIDUAL
   * ⑤ 其余（预算被本轮别的 lane 吃光/被扣到 0）        ⇒ CAPACITY_EXHAUSTED
   * ⑥ 都不成立                                       ⇒ UNSPECIFIED（兜底，不静默）
   * </pre>
   *
   * <p>★ <b>为什么②优先于③④⑤</b>：②说的是"全世界根本没有服务可买"（等下一轮产出），③④⑤说的是"有运力但给不了这一笔" （退单/等运力/改路线）——
   * 两者的处置完全不同，这正是 F-2 要分开的那一刀。★ ①与②也分开（缺提供商 vs 缺货）。
   *
   * <p>★★ <b>W7 的口径收窄（与全局共享配套）</b>：②的判据从"**本格**预算 ≤ 0"改成"**全池**装配时预算 ≤ 0" —— 全局共享之后"本格预算
   * 0"不再是拒绝理由（那正是本批修掉的那件事）；仍为 0 的只剩"全世界都没有可交付服务"。
   *
   * <p>★★★ <b>F1（W7-fix，2026-10-11）：①的判据 = <b>全池候选集</b>（{@code globalOrder}），不是"发货格的池切片"</b>。
   *
   * <pre>
   * 改前（错）  发货格没有跑商家户 ⇒ 报 no-merchant-household-in-shipping-hex —— 而全池**有**货、只是都超半径
   * 改后（对）  全池为空才报 NO_MERCHANT_HOUSEHOLD；全池非空而全部够不着 ⇒ ③ OUT_OF_RADIUS
   * </pre>
   *
   * ★ <b>数值面一字不动</b>：本方法只产出 {@link CarrierAllocation#logisticsBlock()}（读数）， {@code choices} /
   * {@code unallocatedMilli} / 任何扣减都不读它。★ 与候选集同源（{@code select} 的候选 = 全池） ⇒ 归因与"谁真的被考虑过"不可能再漂开。★
   * 缺省中性：单格世界里"全池" = "本格"，逐值退回改前。
   */
  static LogisticsBlock logisticsBlockOf(
      List<Entry> candidatePool,
      long unreachableWork,
      long subUnitWork,
      boolean serviceSupplyEmpty) {
    if (candidatePool.isEmpty()) {
      return LogisticsBlock.NO_MERCHANT_HOUSEHOLD;
    }
    if (serviceSupplyEmpty) {
      return LogisticsBlock.NO_CARRIER_WITH_SERVICE;
    }
    if (unreachableWork > 0L) {
      return LogisticsBlock.OUT_OF_RADIUS;
    }
    if (subUnitWork > 0L) {
      return LogisticsBlock.SUB_UNIT_RESIDUAL;
    }
    // ★ 走到这里 = 池非空、有服务预算、也没被吃光 —— 却仍然没分完 ⇒ 本笔没吃到的那份预算是"被本轮别的 lane
    //   吃光/扣到亚单位"（remainingWork 或 subUnitWork 已覆盖前者，本条只剩兜底）：具名 capacity-exhausted 是
    //   唯一不猜的说法（不许硬塞"没有服务卖"）。
    return LogisticsBlock.CAPACITY_EXHAUSTED;
  }

  /**
   * ★★ <b>M-C：把一条承运条目的耗用折算成承运家户的**劳动投入**（毫小时）</b>—— 利润读数的"劳动力成本"维。
   *
   * <p>运力 = 劳动项 + 工具项（{@link MerchantCapacity}）⇒ 本条的劳动份额 = {@code 耗用 × 劳动 ÷ 运力}
   * （向上取整：宁可多算一分劳动成本，不静默少算）。查不到该户 ⇒ 0（不猜）。
   */
  public long laborHoursOf(HouseholdId household, long consumedWorkMilli) {
    Entry entry = byHousehold.get(household);
    if (entry == null || consumedWorkMilli <= 0L || entry.capacity.capacityMilli() <= 0L) {
      return 0L;
    }
    long product = Math.multiplyExact(consumedWorkMilli, entry.capacity.laborMilli());
    return (product + entry.capacity.capacityMilli() - 1L) / entry.capacity.capacityMilli();
  }

  /**
   * ★★ <b>A3（2026-10-10）：本类的"工具门槛"整族已退役</b> —— 原 {@code toolBlockedRuns()} / {@code
   * toolBlockedByReason()} / {@code logToolBlocks(day)}（事件 {@code
   * MERCHANT_HAUL_TOOL_BLOCKED_AT_SELECT}） 与 {@link Entry} 上的全部工具计数一并删除。
   *
   * <p>理由：门槛的判据（"每趟 ≥ 1,000 毫工具"）与它的实扣（{@code MarketSettlement.settleHaulRuns} 的烧工具）都随 {@code
   * MerchantHaul} 退役；工具改由 {@code trade} 产业的**周期投入**经标准生产管线消耗（设计书 §3.2 / I-H6） ⇒ "这一格为什么没走完"只剩服务货 /
   * 半径 / 亚单位残余三支，见 {@link #laneBlockedReason}。
   */

  /**
   * ★★ <b>记一条本轮的运费实收</b>（唯一致谢点：{@code MarketSettlement} 铸 {@code CARRIER_FEE} 时调用）。
   *
   * <p>它是"利润 = 每轮算出的读数"在本批的**可读部分**：只累计运费实收（按币分列），进日志/读口，<b>不落状态</b>。 完整的利润算式（含纯商号/顺便分流与三层税）属
   * M-C（见实现账本 D-3）。
   */
  public void recordFee(HouseholdId household, CurrencyId currency, long amountMilli) {
    Entry entry = byHousehold.get(household);
    if (entry == null || amountMilli <= 0L) {
      return;
    }
    entry.earnedByCurrency.merge(currency, amountMilli, Math::addExact);
  }

  /**
   * ★★ <b>G3-leftovers（2026-10-10）：本轮的逐户<b>装配</b>读数（DEBUG；事件 {@code
   * MERCHANT_CAPACITY_HOUSEHOLD}）</b>—— 一行 = 池里一个家户条目，字段与同族 {@code MERCHANT_CAPACITY_POOL_HEX} /
   * {@code MERCHANT_CAPACITY_POOL} 同形： <b>{@code day} 打头</b>（本事件此前**没有** {@code day} ⇒ 读数无法按日对齐，是本链
   * M-A1 引入的缺口；理由与先例见 {@link #assemble} 末尾的注释）。
   *
   * <p>★ <b>它为什么在装配点由调用方发、而不是池内自发</b>：本类没有世界日的概念（{@link #of} 的入参里没有 tick），而 {@code day} 由 tick
   * 面提供；同款先例 = {@code EconomySettlement} 装配后紧接着发的 {@code CAPACITY_QUOTE_BOOK}。
   *
   * <p>★★ <b>A3（2026-10-10）</b>：原来还有 {@code toolRemainingMilli} / {@code runsAffordable} 两栏（旧"每趟烧
   * 1,000 毫工具"的门槛镜像）—— 门槛退役，两栏已删；{@code toolMilli} 保留，它现在是 {@link MerchantCapacity}
   * 作业层的**可投入量输入**（装配点的存量 − 冻结），不再是任何门槛。
   *
   * <p>★ 只读、只打日志：不写状态、不改任何判据、不影响结算（§一.9 的日志纪律）。
   *
   * @param day 世界日（由 tick 面的调用方传入；见 {@code EconomySettlement} 的装配点）
   */
  public void logHouseholdAssembly(long day) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    for (Map.Entry<HexCoord, List<Entry>> entry : byHex.entrySet()) {
      long total = totalCapacityByHex.getOrDefault(entry.getKey(), 0L);
      for (Entry item : entry.getValue()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "MERCHANT_CAPACITY_HOUSEHOLD",
                    EconomyLogSource.ECONOMY_ORGANIZATION,
                    "day",
                    day,
                    "hex",
                    entry.getKey(),
                    "household",
                    item.capacity.household().value(),
                    "laborMilli",
                    item.capacity.laborMilli(),
                    "toolMilli",
                    item.capacity.toolMilli(),
                    "capacityMilli",
                    item.capacity.capacityMilli(),
                    "hexTotalMilli",
                    total,
                    "sharePerMille",
                    item.sharePerMille,
                    "tier",
                    item.capacity.tier().name(),
                    "serviceRadiusHex",
                    item.capacity.serviceRadiusHex(),
                    "askPerMille",
                    item.askPerMille,
                    "pureMerchant",
                    item.pureMerchant,
                    "priced",
                    priced));
      }
    }
  }

  /**
   * ★★ <b>W7：全池"手上有可交付服务货"的家户数</b>（= 装配时运力预算 &gt; 0 的条目数）—— 全局共享之后， "有多少格能运"不再是有意义的读数（任意格的商号可服务任意
   * lane），"**有多少户手上有货**"才是。
   *
   * <p>★ 与 {@link #householdCount()}（选了跑商的家户数，含预算为 0 的壳户）配对读：前者是"能卖的"，后者是"在册的"。
   */
  public long householdsWithCapacity() {
    long count = 0L;
    for (Entry item : globalOrder) {
      if (item.capacity.capacityMilli() > 0L) {
        count++;
      }
    }
    return count;
  }

  /**
   * ★ 一轮结束后的逐格分配汇总（INFO = 每格运力池与分配汇总；§一.9）。
   *
   * <p>★★ <b>2026-10-10：{@code MERCHANT_CAPACITY_POOL} 另报本轮"因运力未获服务"的钱/量对照</b>（口径写在同一行里）： {@code
   * unallocatedRawMilli} = Σ 逐行原始读数（同一请求被反复观察 ⇒ <b>不可</b>当真实量）、{@code unallocatedNetMilli} = Σ
   * 净额（每份只算一次 ⇒ <b>可</b>当真实量）；另附 {@code unallocatedObservations}（行数）/ {@code
   * unallocatedRequests}（请求数）/ {@code unallocatedMaxObservations}（单个请求最高被观察几次）三栏， 让"放大倍数"当场可算。★
   * 与日志档位无关：观察簿无条件记账 ⇒ 只开 INFO 时这一行也是真值（不是假 0）。
   */
  public void logRoundSummary(long day) {
    if (!LOG.isInfoEnabled()) {
      return;
    }
    long usedGoodsTotal = 0L;
    long usedWorkTotal = 0L;
    long allocationsTotal = 0L;
    for (Map.Entry<HexCoord, List<Entry>> entry : byHex.entrySet()) {
      long usedGoods = 0L;
      long usedWork = 0L;
      long allocations = 0L;
      long askMin = Long.MAX_VALUE;
      long askMax = 0L;
      Map<CurrencyId, Long> earned = new LinkedHashMap<>();
      for (Entry item : entry.getValue()) {
        usedGoods = Math.addExact(usedGoods, item.allocatedGoodsMilli);
        usedWork = Math.addExact(usedWork, item.allocatedWorkMilli);
        allocations = Math.addExact(allocations, item.allocationCount);
        askMin = Math.min(askMin, item.askPerMille);
        askMax = Math.max(askMax, item.askPerMille);
        for (Map.Entry<CurrencyId, Long> leg : item.earnedByCurrency.entrySet()) {
          earned.merge(leg.getKey(), leg.getValue(), Math::addExact);
        }
      }
      usedGoodsTotal = Math.addExact(usedGoodsTotal, usedGoods);
      usedWorkTotal = Math.addExact(usedWorkTotal, usedWork);
      allocationsTotal = Math.addExact(allocationsTotal, allocations);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "MERCHANT_CAPACITY_POOL_HEX",
                  EconomyLogSource.ECONOMY_ORGANIZATION,
                  "day",
                  day,
                  "hex",
                  entry.getKey(),
                  "households",
                  entry.getValue().size(),
                  "capacityMilli",
                  totalCapacityByHex.getOrDefault(entry.getKey(), 0L),
                  "allocatedMilli",
                  usedGoods,
                  "allocatedWorkMilli",
                  usedWork,
                  "allocations",
                  allocations,
                  "askMinPerMille",
                  askMin == Long.MAX_VALUE ? 0L : askMin,
                  "askMaxPerMille",
                  askMax,
                  "freightEarnedByCurrency",
                  earned));
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "MERCHANT_CAPACITY_POOL",
                EconomyLogSource.ECONOMY_ORGANIZATION,
                "day",
                day,
                "hexes",
                byHex.size(),
                "households",
                householdCount,
                "capacityMilli",
                totalCapacityMilli,
                "allocatedMilli",
                usedGoodsTotal,
                "allocatedWorkMilli",
                usedWorkTotal,
                "allocations",
                allocationsTotal,
                "priced",
                priced,
                "maxAskPerMille",
                maxAskPerMille,
                // ★★★ W7（2026-10-11）：**池的作用域**（用户裁定：运力提供全市场区共享）—— 这一栏是"为什么某一格的
                //   货能被别格的商号运走"的公开读数面；assignOrder = 全局序（限价升序 → 全池议价权降序 → 家户 id 升序）。
                "poolScope",
                "global-shared",
                "assignOrder",
                priced ? "ask-asc,pool-share-desc,household-asc" : "pool-share-desc,household-asc",
                "householdsWithDeliverableService",
                householdsWithCapacity(),
                // ★★ A3（2026-10-10）：退役族**唯一**的 INFO 具名行 —— 哪族退役了、被谁取代（§一.9）。
                //   与逐格/逐户的 INFO 同源同步（每轮各一行），不新增状态、不改任何判据。
                "retiredFamilies",
                RETIRED_PARALLEL_MACHINE_FAMILIES,
                "replacedBy",
                RETIRED_PARALLEL_MACHINE_REPLACEMENTS,
                // ★★ 2026-10-10：本轮"因运力未获服务"的读数**净额 vs 原始**（口径与算法见类注与
                //   LaneUnservedObservationBook）——
                //   unallocatedRawMilli = Σ 逐行 unallocatedMilli（同一请求会被反复观察）⇒ ❌ 不可当真实量；
                //   unallocatedNetMilli = Σ 每份只算一次                 ⇒ ✅ 可当真实量（读这一栏）。
                //   后三栏 = 重复结构（行数 / 请求数 / 单个请求的最高观察次数），让"放大倍数"当场可算。
                "unallocatedRawMilli",
                unservedObservations.rawTotalMilli(),
                "unallocatedNetMilli",
                unservedObservations.netTotalMilli(),
                "unallocatedObservations",
                unservedObservations.observationRows(),
                "unallocatedRequests",
                unservedObservations.requestCount(),
                "unallocatedMaxObservations",
                unservedObservations.maxObservations(),
                // ★★ F-2（2026-10-10）：**F-2 归因的公开读数面** —— 业务拒绝（缺提供商 / 缺服务货）在这里
                //   按**具名键**分档计数（INFO = "这一轮发生了什么"），读者不必开 DEBUG 就能把"没有服务卖"
                //   与"运力不足"分开；运行期三支（半径/亚单位/耗尽）由 MERCHANT_CAPACITY_LANE_TRUNCATED 的
                //   logisticsDetail 与逐格竞争读数给出。★ 没有拒绝 ⇒ 空表（缺省中性：这一栏不新增任何噪声）。
                "serviceGoodsAbsentHouseholds",
                serviceAbsentTally()));
  }
}

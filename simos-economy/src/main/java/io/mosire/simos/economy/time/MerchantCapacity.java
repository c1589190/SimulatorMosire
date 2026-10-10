package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.model.MerchantPolicy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★ <b>M-A1：一个家户的运力（派生量，不落状态）</b>。
 *
 * <p>★★ <b>它是什么</b>：该家户**本轮**能承接的跨格商品量（毫商品）。用户 2026-10-10 裁定（设计书 §14）：
 * 「商号行是啥？选择跑商那就算提供运力了，直接分配就完了」⇒ <b>运力 = 派生量</b>，由 {@code MerchantCapacityPool}
 * 在每个市场轮开始前现算，<b>不落状态、不累积、不储存、不转卖</b>（H-E）。 它的载体 {@code MerchantFirm}（旧第 30 个持久组件）已整体退役。
 *
 * <p>★★ <b>算式（本批冻结；具名常量 + 理由）</b>：
 *
 * <pre>
 * 运力_户（毫商品 / 轮）
 *   = 劳动投入_户（毫小时） × {@link #CAPACITY_MILLI_PER_LABOR_HOUR}（= 1）
 *   + 工具可投入量（毫商品） × {@link #CAPACITY_MILLI_PER_TOOL_MILLI_PER_MILLE}（= 1000‰，即 1:1）
 * </pre>
 *
 * <p>① <b>劳动维的量纲锚</b>：{@code HouseholdEconomy.laborMilli} 是"每 tick 家户时间预算（毫小时）"， {@link
 * io.mosire.simos.economy.model.HouseholdEconomy#participationAdjustedLaborMilli()} 是参与率折算后的
 * <b>实际劳动投入</b>（本类收的就是它）。系数取 1 = "1 毫小时劳动推动 1 毫商品走一程"。标定理由：一个百人城镇商号家户 （人均 ≈8000 毫小时/日、参与率 750‰）⇒ 约
 * 6×10<sup>5</sup> 毫商品/轮，与旧创世常量 {@code EconomySeeder.MERCHANT_CAPACITY_PER_CITY = 100_000}（= {@code
 * MerchantPolicy.CITY_CAPACITY_CEILING}） <b>同量级</b>；且它随人口/参与率线性伸缩（旧值是死的，见计划 §6.2 V-14）。
 *
 * <p>② <b>工具维</b>：用户裁定「单次跑商需要花费大量 tool」（§12 H-D）。本批只让工具维**进入算式**（准入门槛与 一次性扣减 = M-C
 * 的冻结范围，见派单书"不做"清单）—— 因此这里读的是该家户 {@code tool} 商品的**可投入量** （存量），不是"已消耗量"。{@code tool} 走**商品账**（V-22
 * 冻结：不动 {@code AssetKind.TOOL} 产权份额）。
 *
 * <p>★★ <b>J-2：工具维只增不减 ⇒ 拿不到工具数据时本方法是下界</b>。工具账只存在于会话工作副本（{@code EconomyData} 里没有商品库存）⇒
 * 迁移政策/迁移前瞻这类纯状态读者传 {@code toolMilli = 0}，得到的是运力下界：它说"没有运力"必然 真的没有运力（fail-closed 方向正确）。该偏差已在实现账本具名。
 *
 * <p>★★ <b>tier / 半径是派生读数</b>（J-D：旧 {@code MerchantFirm.tier}/{@code serviceRadiusHex} 降为派生）：
 * {@link #tierOf(long)} 由运力规模分档、{@link #serviceRadiusOf} 由 tier 取具名默认（2/4/8）。<b>半径仍用于 判"这条 lane
 * 够不够得着"</b>（沿用旧 {@code servesLane} 语义：lane 长度 ≤ 半径；家户就在发货格 ⇒ 到发货格距离恒 0）， 但不落任何状态。
 *
 * <p>★★★ <b>2026-10-10 A3 残留修复（F-1/F-3）：服务成市格的规模维改成"手上的运输服务货"</b>—— {@link #ofService(HouseholdId,
 * HexCoord, long, long, long)} 是第二条装配口：{@code capacityMilli = 服务货可用量} （{@code max(0, haul 现货 − haul
 * 冻结)}，与 {@link MerchantCapacityPool} 的运力预算<b>同一个操作数</b>）， {@code tier}/{@code serviceRadiusHex}
 * 由它派生 ⇒ 设计书 §3.2「运力 = 该产业本周期可产出的服务量」与 §3.3 「市场自然议价（价格优先、同价按既有 canonical 序）」在"谁能提供运力"这一层上 <b>不再由劳动
 * + 工具派生</b>。 ★ {@code laborMilli}/{@code toolMilli} 两栏在服务口径下**只作读数**（日志/读口），不进任何判据。
 *
 * @param household 提供运力的家户（= 收款主体；家户是唯一持账主体）
 * @param carrier 该家户的 actor（{@code HouseholdActors.of(household)}；CARRIER_FEE 收款人）
 * @param hex 家户所在格 = 运力池所在格 = <b>发货格</b>（G-2）
 * @param laborMilli 本轮劳动投入（毫小时；参与率折算后）
 * @param toolMilli 本轮工具可投入量（毫商品；读不到 = 0）
 * @param capacityMilli 本轮运力（毫商品；服务成市格 = 手上的运输服务货，其余格 = 劳动项 + 工具项）
 * @param tier 派生 tier 读数（不落状态）
 * @param serviceRadiusHex 派生服务半径读数（hex；不落状态）
 */
public record MerchantCapacity(
    HouseholdId household,
    ActorRef carrier,
    HexCoord hex,
    long laborMilli,
    long toolMilli,
    long capacityMilli,
    MerchantPolicy.MerchantTier tier,
    long serviceRadiusHex) {

  /**
   * ★ 每 1 毫小时劳动投入产出的运力（毫商品）：<b>1</b>。
   *
   * <p>标定见类注 ①：百人商号家户 ≈ 6×10<sup>5</sup> 毫商品/轮，与旧创世常量 100_000 同量级。改它 = 改世界的 跨格运力总量（不是改某个读数）。
   */
  public static final long CAPACITY_MILLI_PER_LABOR_HOUR = 1L;

  /**
   * ★ 每 1 毫商品工具可投入量产出的运力（‰）：<b>1000</b>（= 1:1）。
   *
   * <p>工具是准入门槛维（§12 H-D：单次跑商要花费大量 tool）⇒ 本批先按"1 毫工具支撑 1 毫商品运力"落形状；门槛与 一次性扣减在
   * M-C，届时若标定要改，<b>只改这一个常量</b>。
   */
  public static final long CAPACITY_MILLI_PER_TOOL_MILLI_PER_MILLE = 1000L;

  /** 千分比口径（与 {@code MerchantPolicy.PER_MILLE} 同值；本类只用于占比换算）。 */
  public static final long PER_MILLE = 1000L;

  /** 派生 tier 的档界：运力 &lt; 本值 ⇒ PORTER（脚夫）。取旧创世常量 100_000（城区当量 1 档的标定）。 */
  public static final long SELF_EMPLOYED_CAPACITY_THRESHOLD_MILLI = 100_000L;

  /** 派生 tier 的档界：运力 ≥ 本值 ⇒ BOSS（老板）= 4 × 脚夫标定（城区当量 1:4 的倍数）。 */
  public static final long BOSS_CAPACITY_THRESHOLD_MILLI = 400_000L;

  /** 派生服务半径（hex）：PORTER。与旧 {@code MerchantFirm.PORTER_SERVICE_RADIUS_HEX} 逐值相同。 */
  public static final long PORTER_SERVICE_RADIUS_HEX = 2L;

  /** 派生服务半径（hex）：SELF_EMPLOYED。 */
  public static final long SELF_EMPLOYED_SERVICE_RADIUS_HEX = 4L;

  /** 派生服务半径（hex）：BOSS。 */
  public static final long BOSS_SERVICE_RADIUS_HEX = 8L;

  public MerchantCapacity {
    Objects.requireNonNull(household, "MerchantCapacity.household 不得为 null");
    Objects.requireNonNull(carrier, "MerchantCapacity.carrier 不得为 null");
    Objects.requireNonNull(hex, "MerchantCapacity.hex 不得为 null");
    Objects.requireNonNull(tier, "MerchantCapacity.tier 不得为 null");
    if (laborMilli < 0L || toolMilli < 0L || capacityMilli < 0L || serviceRadiusHex < 0L) {
      throw new IllegalArgumentException(
          "MerchantCapacity 的劳动/工具/运力/半径不得为负: labor="
              + laborMilli
              + " tool="
              + toolMilli
              + " capacity="
              + capacityMilli
              + " radius="
              + serviceRadiusHex);
    }
  }

  /**
   * ★ 由（劳动投入, 工具可投入量）派生一条运力读数（唯一装配口）。
   *
   * @param laborMilli 劳动投入（毫小时；{@code participationAdjustedLaborMilli()} 的结果）
   * @param toolMilli 工具可投入量（毫商品；读不到传 0 ⇒ 下界）
   */
  public static MerchantCapacity of(
      HouseholdId household, HexCoord hex, long laborMilli, long toolMilli) {
    long capacity = capacityMilliOf(laborMilli, toolMilli);
    MerchantPolicy.MerchantTier tier = tierOf(capacity);
    return new MerchantCapacity(
        household,
        HouseholdActors.of(household),
        hex,
        laborMilli,
        toolMilli,
        capacity,
        tier,
        serviceRadiusOf(tier));
  }

  /**
   * ★★ <b>2026-10-10 A3 残留修复（F-1/F-3）：由"手上的运输服务货可用量"派生一条运力读数</b>（服务成市格的唯一装配口）。
   *
   * <pre>
   * 服务成市格（{@code MerchantCapacityPool} 的 haulServiceHexes 含该格）：
   *   capacityMilli     = serviceAvailableMilli（= max(0, haul 现货 − haul 冻结)；与池的运力预算**同一个操作数**）
   *   tier              = tierOf(capacityMilli)   ← 规模维换成服务货（A3 残留：旧口径由劳动 + 工具派生）
   *   serviceRadiusHex  = serviceRadiusOf(tier)   ← 随 tier 派生（口径一致，不出现"tier 与服务货脱钩"的第二套）
   *   laborMilli/toolMilli 两栏只作读数（日志/读口），不进任何判据
   * </pre>
   *
   * <p>★ <b>缺省中性（I-H3）</b>：本方法**只在服务成市格被调用** —— 没有牌价的格一格都不走它 ⇒ 那些格的产量是 {@link #of} 的逐值结果，改前改后一字不变。★
   * 两条装配口不并存于同一格：调用点按 {@code haulServiceHexes} 分流。
   *
   * <p>★ <b>档界沿用不新造</b>：{@link #tierOf(long)} 的三个档位本身承载的是"承运成本档（25/50/100‰）与触达范围（2/4/8
   * hex）"这两个**规模的函数**，与服务货口径同一量纲（毫商品 ↔ 毫商品·程，A1 单位锚 1:1）⇒ 直接复用，不引入第二套阈值。
   *
   * @param serviceAvailableMilli 该户手上的运输服务货可用量（毫服务；{@code max(0, 现货 − 冻结)}）
   * @param laborMilli 本轮劳动投入（毫小时；<b>只作读数</b>）
   * @param toolMilli 本轮工具可投入量（毫商品；<b>只作读数</b>）
   */
  public static MerchantCapacity ofService(
      HouseholdId household,
      HexCoord hex,
      long serviceAvailableMilli,
      long laborMilli,
      long toolMilli) {
    if (serviceAvailableMilli < 0L) {
      throw new IllegalArgumentException("运输服务货可用量不得为负: " + serviceAvailableMilli);
    }
    MerchantPolicy.MerchantTier tier = tierOf(serviceAvailableMilli);
    return new MerchantCapacity(
        household,
        HouseholdActors.of(household),
        hex,
        laborMilli,
        toolMilli,
        serviceAvailableMilli,
        tier,
        serviceRadiusOf(tier));
  }

  /** 劳动项（毫商品）：{@code 劳动投入 × }{@link #CAPACITY_MILLI_PER_LABOR_HOUR}。 */
  public static long laborCapacityMilli(long laborInvestedMilli) {
    if (laborInvestedMilli < 0L) {
      throw new IllegalArgumentException("劳动投入不得为负: " + laborInvestedMilli);
    }
    return Math.multiplyExact(laborInvestedMilli, CAPACITY_MILLI_PER_LABOR_HOUR);
  }

  /** 工具项（毫商品）：{@code 工具量 × 1000‰ ÷ 1000}（整数向下取整）。 */
  public static long toolCapacityMilli(long toolMilli) {
    if (toolMilli < 0L) {
      throw new IllegalArgumentException("工具可投入量不得为负: " + toolMilli);
    }
    return Math.multiplyExact(toolMilli, CAPACITY_MILLI_PER_TOOL_MILLI_PER_MILLE) / PER_MILLE;
  }

  /** 运力（毫商品）= 劳动项 + 工具项（唯一算式；两个分量各自具名）。 */
  public static long capacityMilliOf(long laborInvestedMilli, long toolMilli) {
    return Math.addExact(laborCapacityMilli(laborInvestedMilli), toolCapacityMilli(toolMilli));
  }

  /** ★ 派生 tier 读数：运力规模分档（J-1；旧 {@code MerchantFirm.tier} 的替代，仅用于日志/读口/承运成本）。 */
  public static MerchantPolicy.MerchantTier tierOf(long capacityMilli) {
    if (capacityMilli < 0L) {
      throw new IllegalArgumentException("运力不得为负: " + capacityMilli);
    }
    if (capacityMilli >= BOSS_CAPACITY_THRESHOLD_MILLI) {
      return MerchantPolicy.MerchantTier.BOSS;
    }
    if (capacityMilli >= SELF_EMPLOYED_CAPACITY_THRESHOLD_MILLI) {
      return MerchantPolicy.MerchantTier.SELF_EMPLOYED;
    }
    return MerchantPolicy.MerchantTier.PORTER;
  }

  /** ★ 派生服务半径读数：按 tier 取具名默认（2/4/8；J-2，§4.4"商号规模决定触达范围"）。 */
  public static long serviceRadiusOf(MerchantPolicy.MerchantTier tier) {
    Objects.requireNonNull(tier, "tier 不得为 null");
    return switch (tier) {
      case PORTER -> PORTER_SERVICE_RADIUS_HEX;
      case SELF_EMPLOYED -> SELF_EMPLOYED_SERVICE_RADIUS_HEX;
      case BOSS -> BOSS_SERVICE_RADIUS_HEX;
    };
  }

  /**
   * ★ <b>市场议价权（G-1）= 该家户运力在本格运力总量中的占比‰</b>：{@code floor(运力 × 1000 ÷ 本格总量)}。 分配序 = 本值降序 → 家户 id
   * 升序（I7）。总量 ≤ 0 ⇒ 0。
   */
  public long sharePerMilleOf(long hexTotalCapacityMilli) {
    if (hexTotalCapacityMilli <= 0L) {
      return 0L;
    }
    return Math.multiplyExact(capacityMilli, PER_MILLE) / hexTotalCapacityMilli;
  }

  /** 这条 lane 够不够得着：lane 长度 ≤ 派生半径（家户就在发货格 ⇒ 只判终点那一端；沿用旧 {@code servesLane} 语义）。 */
  public boolean servesLane(HexCoord to) {
    Objects.requireNonNull(to, "to 不得为 null");
    return serviceRadiusHex > 0L && hex.distanceTo(to) <= serviceRadiusHex;
  }
}

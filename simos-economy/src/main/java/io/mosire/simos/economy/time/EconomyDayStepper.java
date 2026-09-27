package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.population.LotChange;
import io.mosire.simos.economy.model.FlowRow;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ **逐日结算的会话**（R4；给 {@code simos-app} 的人口—经济协调器用）：把"一次推 N 天"的**内部日循环**开放给 **唯一同时看得见两个切片的调用方**。
 *
 * <p>★★ **为什么必须有它**（而不是把 {@code settleOneDay} 直接公开）：日循环里有**跨日存活的可变状态**（本期的流水累加器 + 家户账工作副本；见 {@code
 * EconomySettlement.settle} 的实现）。把它们作为公开方法的入参交出去，等于把"哪一份累加器" 这件事变成调用方的责任 ——
 * 而它错了不会报错，只会让流水少记几天、或者让家户凭空断粮。本类把这些可变状态**收进一个对象**， 对外只出"推进一天 / 交回状态"两件事（与 {@code
 * EconomySettlement.settle} 的纯函数形态**共用同一份实现**： 本类的方法体就是转调它）。
 *
 * <p>★★ **谁用它、为什么它必须存在**：{@code PopulationEconomyTimeParticipant}（住在 {@code simos-app}）要在
 * **每一天**的经济结算之后读当天发生额（算生理压力）、并在月度边界把出生/死亡**回写**经济侧。若它改用 {@code EconomySettlement.settle(base,
 * from, to)} 一次算完，就再也插不进"每一天之后"这一步； 而若把它拆成"N 次独立推进"，{@code range.to} 的语义（一条 revision）与 §十一 等价性都会走样
 * —— **日循环的语义必须留在同一个调用栈里**。
 *
 * <p>★★ **{@link #householdGoods()} / {@link #householdMoney()}：家户账的会话工作副本**（商品 = H1/裁定 K1；★ 货币 =
 * H4/裁定 K14）。两份副本**同形、同生命周期、同样不进 {@code EconomyData}**——为它们为什么"不是第二本账"：
 *
 * <ul>
 *   <li>**唯一的持久真源**是 actor 切片里该家户 actor 的 {@code GoodsAccount} （键 {@code
 *       (HouseholdActors.of(cohort), cohort.hex())}）—— 本类**看不见**它（economy 不认识 actor 切片）；
 *   <li>本副本由调用方（app 协调器）在推进前**从 actor 侧载入**、传进来；本类**就地更新**它（与已经持有的 {@code flows}
 *       累加器完全同形，有先例）；推进结束后调用方把它**落回 actor**；
 *   <li>★ **它们不进 {@link EconomyData}、不进变更集、不跨 revision 存活** —— 这就是"不是第二本账"的可执行判据 （判别口径：{@code
 *       ClassRow} 里没有 {@code goods}，守恒式里没有 {@code ΔΣRowGoods} 这一项）。
 *   <li>★★ <b>货币那一份的创世来源是"禀赋"而不是"发行"</b>（K14）：钱由 app 侧的播种给每一户一笔，economy 这一侧只<b>读/写会话副本</b>；本批没有任何
 *       {@code MoneyAuthority} 实现 ⇒ 结算里<b>造不出一毫钱</b> （付不出就当场抛，见 {@code
 *       EconomySettlement.applyTransferToHouseholds}）。
 * </ul>
 *
 * <p>★★ **副本的形状与语义**（H1 冻结的接口）：{@code Map<CohortKey, Map<CommodityId, Long>>} —— 键 = 家户身份； 值 =
 * 商品余额，<b>缺失键 = 该家户没有该商品</b>（同 {@code ClassRow.goods} 原来的口径）。★ <b>外层键的缺失是 fail-closed 的</b>： 某行
 * {@code population > 0} 而副本里没有它的键 ⇒ 日结算**当场抛**（不许把"没有账"静默当成"库存 0" —— 那正是本仓最反对的"静默付 0"形态）。 {@code
 * population == 0} 的行**跳过消费与投入**（它们不吃饭、不出工）⇒ 它们可以没有键。
 *
 * <p>★ **它是可变对象**（唯一的一个：内部持有累加器与副本引用），故**不共享、不并发**：一次推进一个实例（用完即弃）。
 *
 * <p>★ **{@link #finish()} 之前拿到的 {@link #data()} 里流水还是旧的**（累加器在会话里）：日循环结束后由 {@code finish()} 一次性挂上
 * —— 与 {@code EconomySettlement.settle} 的收尾完全同款。
 */
public final class EconomyDayStepper {

  private final boolean plantingDrawsFirst;
  private final int famineMortalityPerMille;
  private EconomyData data;
  private final LinkedHashMap<CohortKey, FlowRow> flows;

  /** ★★ 家户商品账的会话工作副本（**就地更新**；见类注。★ 调用方持有的那一份才是主人，本类只借它一程）。 */
  private final Map<CohortKey, Map<CommodityId, Long>> householdGoods;

  /**
   * ★★ <b>家户货币账的会话工作副本</b>（H4；裁定 K14）—— 与 {@link #householdGoods} **同形、同生命周期、同样不进 {@link
   * EconomyData}**：推进前由调用方从 actor 侧载入、推进中被就地更新、推进结束后落回 actor。
   */
  private final Map<CohortKey, Map<CurrencyId, Long>> householdMoney;

  /**
   * ★★ <b>经营者账的会话工作副本（H5）</b>—— 键 = 经营者主体（{@code ESTATE: farm@0_0} / {@code WORKSHOP: craft@0_0} /
   * {@code HOUSEHOLD: weave@0_0}），值 = 商品余额；★ 与家户那两份**同形、同生命周期、同样不进 {@code EconomyData}}。
   *
   * <p>★ <b>它装什么</b>：净产（{@code harvest} 的产出计提）、关系实付的付出、投入的付出、货币工资的付出 —— "经营者自己持账"是 H5 ⑤
   * 的题目（改前聚合主体的账 economy 看不见 ⇒ 可用 0 ⇒ 实付 0）。
   *
   * <p>★ <b>缺席是合法的</b>：手搭夹具、或这个世界还没给经营者播种 ⇒ 空表 ⇒ 那些主体照旧"看不见 ⇒ 可用 0" （H4 的既有口径，逐字不改）。
   */
  private final Map<ActorRef, Map<CommodityId, Long>> operatorGoods;

  /** 经营者**货币**账的会话工作副本（H5）：同 {@link #operatorGoods} 的形状（值 = 逐币种余额）。 */
  private final Map<ActorRef, Map<CurrencyId, Long>> operatorMoney;

  /**
   * ★★ <b>M2：家户的冻结额快照（商品）</b>—— 与 {@link #householdGoods} 同键、同生命周期，但本层是<b>只读</b>的： 订单生成用它算"可卖 = 持有
   * − 已冻结 − …"，本类不修改它（L1 的订单是瞬时的，不产生持久冻结）。
   *
   * <p>★ 真档今天没有冻结写者 ⇒ 恒空；带它进来是照 M1.4 的 Javadoc 把冻结一并带上，等 L2 的挂单冻结一到就自动生效。
   */
  private final Map<CohortKey, Map<CommodityId, Long>> householdFrozenGoods;

  /** ★★ M2：家户的冻结额快照（货币）—— 预算算"可花的钱 = 余额 − 冻结货币"。 */
  private final Map<CohortKey, Map<CurrencyId, Long>> householdFrozenMoney;

  /** ★★ M2：经营者的冻结额快照（商品）—— 同 {@link #householdFrozenGoods} 的形状与生命周期。 */
  private final Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods;

  /** ★★ M2：经营者的冻结额快照（货币）—— 同 {@link #householdFrozenMoney}。 */
  private final Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney;

  /**
   * ★★ <b>从 {@code base} 起步，并接管家户账的<strong>两份</strong>会话工作副本</b>：商品（H1）与货币（H4/K14）。
   *
   * @param householdGoods 家户商品账工作副本：键 = 家户身份、值 = 商品余额（缺失键 = 没有该商品）；**会被就地更新**。 ★ 它必须覆盖每一个 {@code
   *     population > 0} 的家户行（否则 {@link #step(long)} 当场抛）； {@code population == 0} 的行可以缺席。★
   *     内层表**只读**（本类换值一律 {@code put} 一张新表，不改旧表）。
   * @param householdMoney 家户货币账工作副本（H4；裁定 K14）：键 = 家户身份、值 = 逐币种余额（**缺失币种 = 该币种 0**，但**外层键同样必须覆盖每一个
   *     {@code population > 0} 的家户行**，否则 {@link #step(long)} 当场抛）—— ★ 创世禀赋由 app 侧播种、由 app 协调器在推进前从
   *     actor 侧载入；economy 不认识 actor 切片。
   */
  public EconomyDayStepper(
      EconomyData base,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney) {
    this(
        base,
        householdGoods,
        householdMoney,
        Map.of(),
        Map.of(),
        EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION,
        EconomySettlement.FAMINE_MORTALITY_PER_MILLE);
  }

  /**
   * ★★ <b>结算一天的会话（H5 的完整构造器）</b>：家户的两份副本 + **经营者的两份副本**（见 {@link #operatorGoods}）。
   *
   * @param operatorGoods 经营者商品账工作副本（键 = 经营者主体；**就地更新**）；没有就给空表
   * @param operatorMoney 经营者货币账工作副本（同上）；没有就给空表
   */
  public EconomyDayStepper(
      EconomyData base,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney) {
    this(
        base,
        householdGoods,
        householdMoney,
        operatorGoods,
        operatorMoney,
        EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION,
        EconomySettlement.FAMINE_MORTALITY_PER_MILLE);
  }

  /**
   * ★★ <b>M2：app 协调器的完整入口</b>（九参）—— 在 H5 的两份余额副本之外，再接管家户/经营者的**两张冻结快照**（商品 + 货币）。
   *
   * <p>★ 冻结由 app 侧 {@code OwnershipBooks} 从 actor 的 {@code GoodsAccount} 载入（只读）；本层不产生持久冻结。
   * 两个行为旋钮取出厂默认值（与 {@link #EconomyDayStepper(EconomyData, Map, Map, Map, Map)} 同款）。
   */
  public EconomyDayStepper(
      EconomyData base,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<CohortKey, Map<CommodityId, Long>> householdFrozenGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney) {
    this(
        base,
        householdGoods,
        householdMoney,
        householdFrozenGoods,
        householdFrozenMoney,
        operatorGoods,
        operatorMoney,
        operatorFrozenGoods,
        operatorFrozenMoney,
        EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION,
        EconomySettlement.FAMINE_MORTALITY_PER_MILLE);
  }

  /**
   * 同 {@link #EconomyDayStepper(EconomyData, Map, Map)}，但**播种次序可注入**（见 {@code
   * EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION}）。
   */
  public EconomyDayStepper(
      EconomyData base,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      boolean plantingDrawsFirst) {
    this(
        base,
        householdGoods,
        householdMoney,
        Map.of(),
        Map.of(),
        plantingDrawsFirst,
        EconomySettlement.FAMINE_MORTALITY_PER_MILLE);
  }

  /**
   * ★ <b>单模块用例的短构造器：世界没有货币</b>（H4）—— 货币副本 = 每个家户一本**空钱包**。
   *
   * <p>★ 它是**包内可见**的（不是公开 API）：{@code simos-app} 的协调器**必须**把 actor 侧的货币余额显式载进来 ——
   * 走这一支会让真档创世的钱<b>在账上静默消失</b>（市场买不动、货币工资付不出，而账面上看不出少了谁）。
   */
  EconomyDayStepper(EconomyData base, Map<CohortKey, Map<CommodityId, Long>> householdGoods) {
    this(
        base,
        householdGoods,
        EconomySettlement.emptyMoneyAccountsFor(base.classes().keySet()),
        Map.of(),
        Map.of(),
        EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION,
        EconomySettlement.FAMINE_MORTALITY_PER_MILLE);
  }

  /**
   * ★ 先播种还是先吃饭（{@code plantingDrawsFirst}）**与致死率都可注入**（**包内可见**）—— 理由逐字见 {@link
   * EconomySettlement#PLANTING_DRAWS_BEFORE_CONSUMPTION} 与 {@link
   * EconomySettlement#FAMINE_MORTALITY_PER_MILLE}。
   *
   * <p>★ <b>H5：经营者账副本给空表</b>（"这个世界没有经营者账"这个**合法状态**，同上面那两支的注释）——
   * 单模块夹具走它；要量"经营者自己持账"的用例走上面那个五参公开构造器。
   */
  EconomyDayStepper(
      EconomyData base,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      boolean plantingDrawsFirst,
      int famineMortalityPerMille) {
    this(
        base,
        householdGoods,
        householdMoney,
        Map.of(),
        Map.of(),
        plantingDrawsFirst,
        famineMortalityPerMille);
  }

  /**
   * ★ 先播种还是先吃饭（{@code plantingDrawsFirst}）**与致死率都可注入**（**包内可见**）—— 两个旋钮的理由逐字见 {@link
   * EconomySettlement#PLANTING_DRAWS_BEFORE_CONSUMPTION} 与 {@link
   * EconomySettlement#FAMINE_MORTALITY_PER_MILLE}：它们**不是死分支**，故必须有路真的走得到，而 {@code simos-app}
   * 的协调器只该看到公开入口那份默认值。
   *
   * <p>★ 旧的七参重载 = "这个世界没有冻结"（M1.4 之前的调用点逐字不改）；带冻结的调用走下面那个十一参重载。
   */
  EconomyDayStepper(
      EconomyData base,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      boolean plantingDrawsFirst,
      int famineMortalityPerMille) {
    this(
        base,
        householdGoods,
        householdMoney,
        Map.of(),
        Map.of(),
        operatorGoods,
        operatorMoney,
        Map.of(),
        Map.of(),
        plantingDrawsFirst,
        famineMortalityPerMille);
  }

  /**
   * ★★ <b>M2：带冻结表的完整构造器</b>（包内可见）—— 两份余额副本 + 两份冻结快照（家户/经营者 × 商品/货币），两个旋钮可注入。
   *
   * <p>★ 冻结是<b>只读快照</b>：本类不改它（L1 的订单是瞬时的，不产生持久冻结）；订单生成用它算可卖量与预算，落回 actor 时按 {@code GoodsAccount}
   * 的原值保留。真档今天没有冻结写者 ⇒ 这些表恒空。
   */
  EconomyDayStepper(
      EconomyData base,
      Map<CohortKey, Map<CommodityId, Long>> householdGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdMoney,
      Map<CohortKey, Map<CommodityId, Long>> householdFrozenGoods,
      Map<CohortKey, Map<CurrencyId, Long>> householdFrozenMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorMoney,
      Map<ActorRef, Map<CommodityId, Long>> operatorFrozenGoods,
      Map<ActorRef, Map<CurrencyId, Long>> operatorFrozenMoney,
      boolean plantingDrawsFirst,
      int famineMortalityPerMille) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(householdGoods, "householdGoods（家户账是会话状态，必须由调用方载入）");
    Objects.requireNonNull(householdMoney, "householdMoney（H4：货币账是会话状态，必须由调用方载入）");
    Objects.requireNonNull(householdFrozenGoods, "householdFrozenGoods（M2：冻结快照；没有就给空表）");
    Objects.requireNonNull(householdFrozenMoney, "householdFrozenMoney（M2：冻结快照；没有就给空表）");
    Objects.requireNonNull(operatorGoods, "operatorGoods（H5：经营者账是会话状态；没有就给空表）");
    Objects.requireNonNull(operatorMoney, "operatorMoney（H5：经营者账是会话状态；没有就给空表）");
    Objects.requireNonNull(operatorFrozenGoods, "operatorFrozenGoods（M2：冻结快照；没有就给空表）");
    Objects.requireNonNull(operatorFrozenMoney, "operatorFrozenMoney（M2：冻结快照；没有就给空表）");
    this.data = base;
    this.householdGoods = householdGoods;
    this.householdMoney = householdMoney;
    this.householdFrozenGoods = householdFrozenGoods;
    this.householdFrozenMoney = householdFrozenMoney;
    this.operatorGoods = operatorGoods;
    this.operatorMoney = operatorMoney;
    this.operatorFrozenGoods = operatorFrozenGoods;
    this.operatorFrozenMoney = operatorFrozenMoney;
    this.plantingDrawsFirst = plantingDrawsFirst;
    this.famineMortalityPerMille = famineMortalityPerMille;
    this.flows = new LinkedHashMap<>(base.flows());
  }

  /** 当前状态（**流水尚未挂上**：见类注）。 */
  public EconomyData data() {
    return data;
  }

  /** 本期的流水累加器（**只读视图**；键序 = 行的插入序）。 */
  public Map<CohortKey, FlowRow> flows() {
    return Collections.unmodifiableMap(flows);
  }

  /**
   * ★★ **家户账工作副本的当前值**（**只读视图**；键序 = 载入时的插入序 + 结算期新建的键）。
   *
   * <p>★★ **它是"当前副本"，不是"副本的副本"**：外层表被包了一层 {@code unmodifiableMap}（改它当场抛），但**内容是活的** —— 每 {@link
   * #step(long)} 一天都变。调用方要么在两个 step 之间读它（那时它是当天的终值），要么在 {@link #finish()} 之后读它
   * （那时它是整段推进的终值，也正是**要落回 actor 的那一份**）。
   *
   * <p>★ <b>内层表不可变</b>（有意的）：本类换值一律 {@code put} 一张新表，绝不改旧表 ⇒ 调用方拿到的任何一张内层快照都不会被后续结算改掉。
   */
  public Map<CohortKey, Map<CommodityId, Long>> householdGoods() {
    return Collections.unmodifiableMap(householdGoods);
  }

  /**
   * ★★ <b>家户货币账工作副本的当前值</b>（H4；只读视图，形状与语义逐字照 {@link #householdGoods()}）：
   *
   * <ul>
   *   <li>★ **它是"当前副本"，不是"副本的副本"**：外层表被包了一层 {@code unmodifiableMap}（改它当场抛）， 但内容是活的；
   *   <li>★ <b>内层表不可变</b>：本类换值一律 {@code put} 一张新表 ⇒ 任何拿到的快照都不会被后续结算改掉；
   *   <li>★★ <b>要在 {@link #finish()} 之后读它</b>：那时它是整段推进的终值，也正是**要落回 actor 的那一份** —— 货币守恒判据（M-J3：逐币种
   *       Σ余额 恒定）读的就是它。
   * </ul>
   */
  public Map<CohortKey, Map<CurrencyId, Long>> householdMoney() {
    return Collections.unmodifiableMap(householdMoney);
  }

  /**
   * ★★ <b>经营者账工作副本的当前值（H5；商品）</b>—— 形状与语义逐字照 {@link #householdGoods()}（只读视图、内容是活的、 要在 {@link
   * #finish()} 之后读它才是整段推进的终值，那时它正是**要落回 actor 的那一份**）。
   */
  public Map<ActorRef, Map<CommodityId, Long>> operatorGoods() {
    return Collections.unmodifiableMap(operatorGoods);
  }

  /** ★★ <b>经营者货币账工作副本的当前值（H5）</b>—— 逐字照 {@link #operatorGoods()}（同一本账的第二个余额表）。 */
  public Map<ActorRef, Map<CurrencyId, Long>> operatorMoney() {
    return Collections.unmodifiableMap(operatorMoney);
  }

  /**
   * ★★ **结算一天**（{@code day} 是绝对世界日）：与 {@code EconomySettlement.settleOneDay} 是**同一条实现**， 并**交回当天**的
   * {@link ProductionLedger}（S1 阶段 4+5 Task 4；裁定 E7 的核心）。
   *
   * <p>★★ **为什么必须交回它**（而不是"结算完就完事"）：产出自本阶段起<b>不再写进阶层行</b> —— 它变成产权条目 （{@code +净产 → operator}
   * 与关系规则的转出/收入），而**产权住在 {@code simos-actor}**：economy 切片刻意不认识它 （铁律 3）。⇒ "把这一天离开 {@code ClassRow}
   * 的东西交给看得见 actor 那一侧的人"就是本方法的返回值。 ★ <b>扔掉它 = 静默丢产出</b>，所以它<b>不是</b>一个可选的回调、也不是一个字段：它是返回值。
   *
   * <p>★★ <b>H1 起家户的收支走两条路</b>（都在这本账里，都要落）：
   *
   * <ul>
   *   <li>**日耗 / 投入 / 同格取材**：只写进 {@link #householdGoods()} 工作副本（它们不是产权条目）⇒ 由调用方**把副本落回 actor**；
   *   <li>**关系实付给家户**：既是本返回值里的一条 {@code +paid}（{@code HouseholdActors.of(cohort)}），**也已经计进工作副本** ——
   *       ★ 故调用方**不许把它再叠加到副本上**（叠加 = 同一笔粮记两遍）。落盘时按副本的**绝对值**写回即可。
   * </ul>
   *
   * <p>★ 单模块用例（只装 economy 的世界）可以照旧忽略返回值 —— 那里<b>没有 actor 账户可落</b>，家户的账由工作副本自己记完。
   *
   * <p>★ 与 {@code settle(base, from, to)} 的等价性因此是构造性的：那边的日循环调的就是这里调的东西。★ 反过来， {@code settle} 已
   * **fail-closed**（它没有家户账 ⇒ 第一天就抛）—— 单模块的多日推进请走本类。
   *
   * @return 当天的发生额（毛产 / 损耗 / 投入 / 产权条目 / 货币待办；什么都没发生 ⇒ {@link ProductionLedger#empty()}）
   * @throws IllegalArgumentException {@code day < 1}（创世是第 0 天，没有"第 0 天"这一天）
   * @throws IllegalStateException 某个 {@code population > 0} 的家户行在 {@link #householdGoods()} 里没有键
   *     （fail-closed：不许把"没有账"静默当成"库存 0"）
   */
  public ProductionLedger step(long day) {
    if (day < 1L) {
      throw new IllegalArgumentException("结算的日号必须 ≥ 1（创世是第 0 天）: " + day);
    }
    // ★ 每天一个**新的**累加器：它记的是"这一天"（跨日累计会让调用方重复落账，见 ProductionLedger 的类注）。
    //   ★ H2：它同时是**转移凭据的铸造口**（id = tr-<day>-<seq>，序号按天自增）⇒ 日号必须交给它。
    ProductionLedger.Accumulator ledger = new ProductionLedger.Accumulator(day);
    data =
        EconomySettlement.settleOneDay(
            data,
            day,
            flows,
            householdGoods,
            householdMoney,
            operatorGoods,
            operatorMoney,
            householdFrozenGoods,
            householdFrozenMoney,
            operatorFrozenGoods,
            operatorFrozenMoney,
            plantingDrawsFirst,
            famineMortalityPerMille,
            ledger);
    return ledger.toLedger();
  }

  /**
   * ★★ **把一份"逐批次的出生/死亡"回写到经济侧**（行人口、劳动配额与流水）—— 转调 {@link
   * EconomySettlement#applyPopulationChange}，并把流水累加器重新对齐（那份实现会带出自己的流水副本）。
   */
  public void applyPopulationChange(List<LotChange> changes) {
    Objects.requireNonNull(changes, "changes");
    if (changes.isEmpty()) {
      return;
    }
    EconomyData attached = data.withFlows(new LinkedHashMap<>(flows));
    data = EconomySettlement.applyPopulationChange(attached, changes);
    flows.clear();
    flows.putAll(data.flows());
  }

  /**
   * 收尾：把累加器挂上，交出可以进变更集的**最终状态**（★ 家户账在 {@link #householdGoods()} 与 {@link #householdMoney()}
   * 里，**不在**这个状态里）。
   */
  public EconomyData finish() {
    data = data.withFlows(flows);
    return data;
  }
}

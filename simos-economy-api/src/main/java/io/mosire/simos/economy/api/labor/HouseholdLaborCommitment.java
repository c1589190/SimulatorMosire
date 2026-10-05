package io.mosire.simos.economy.api.labor;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ProductionUnitId;

/**
 * ★★ **一次劳动分配**（第三阶段设计稿 §四）："**这批人**把**这么多**劳动供给**这个主体**，在这个周期里"。
 *
 * <pre>
 * Household(时间预算) ──→ HouseholdLaborCommitment ──→ ProductionUnit（生产活动）
 * </pre>
 *
 * <p>★★ **它存在的理由**（设计稿 §一.2 实测的空洞）：此前"劳动投入"是**按产业各自累加**的（每格 farm 与 craft 各带一份人口与劳动、 互不知道对方）⇒
 * 同一批人可以**被两个产业各算一次满额**，而全仓没有任何一处能表达"这批人的劳动投入之和不得超过其可用劳动"。 本类型把那笔投入**显式记成一条关系**：{@code group}（谁出的）→
 * {@code actor}（谁收的），于是"同一批人供给多个产业"与"总和守恒" 都成了**可判**的事实。
 *
 * <p>★★ **跨切片引用一律走不透明 {@link ActorRef}**（{@link ActorRef} 的设计意图原文："跨模块引用任何经济主体**而不依赖它所在的切片**"）：
 * 本模块**不认识** {@code PopulationGroup}（social 的类型）—— 只认它的稳定身份 {@link PeopleLotId}。方向是 {@code social →
 * economy-api}（设计稿 §八.1 明文允许），不是反过来。
 *
 * <p>★★ <b>{@code laborMilli} 的口径（P2-A §13.4 起）</b>：它是**本家户这一 tick 分给该生产活动/unit 的时间**，
 * 单位 = <b>毫小时</b>（{@code 1 小时 = 1000 毫小时}，定点整数，无浮点）。它是 {@code HouseholdLaborCommitment} 唯一的量纲；
 * 家户每 tick 的总时间预算 = {@code HouseholdEconomy.laborMilli}（由 Social 人口组成 × {@code HouseholdLaborTimeTable} 每 tick 重算），
 * 不变量 = {@code Σ allocations(household).laborMilli ≤ HouseholdEconomy.laborMilli}。★ 第二权威 {@code LaborSupply} 已删除。
 *
 * <p>★ **{@code period} = 发放周期**（世界周期序号，从 1 起）：本轮配额是**常设**的（跨周期不变，见 {@code 旧结算引擎（R3a 已删除）}
 * 的取用口径），故它现在由**构造期守卫**读（"该批次的供给记录必须与它同期"，见 {@code EconomyData}）；将来有了"按周期重发配额" 的命令，再按 {@code
 * (group, period)} 分桶判上限（设计稿 §四原文："同一 group 在同一 period 内所有 allocation 之和不得超上限"）。
 *
 * <p>★ **不变量（构造期判）**：{@code id}/{@code group}/{@code actor} 非 null；{@code activity} 非空白； {@code
 * laborMilli ≥ 0}（0 = 空配额，合法：见 {@code PopulationGroup.count} 的同款理由）；{@code period ≥ 0}。
 *
 * @param id 稳定身份（由产出方给短名；不含 {@code "."}，见 {@link LaborAllocationId}）
 * @param group 出劳动的人口批次（**人口的真值源在 social**；本类型只持它的稳定身份）
 * @param household ★★ <b>这份劳动属于哪个家户</b>（S1 起；同一批人可按家户分别给不同主体出劳动，见 S1.1）
 * @param actor 收劳动的经济主体（本轮 = 产业 {@code farm@q_r} / {@code craft@q_r}，或家户）
 * @param activity ★★ <b>R3B.2 起 = 生产单元 id（{@code ProductionUnitId.value()}）</b>：结算按它把劳动归集到 unit
 *     （{@code 旧结算引擎（R3a 已删除）.laborByUnit}），"这份劳动喂哪条生产活动"的唯一答案。★ 旧档的旧活动标签 / 旧 actor id 由 {@code
 *     LegacyHouseholdMigration} 在构造期对齐到 unit；对不上任何 unit 的配额**合法**（自由家户劳动，只进守恒与读口， 不喂任何生产）。
 * @param laborMilli 承诺投入的劳动（千分劳动·日）；不得为负
 * @param period 发放周期（世界周期序号）；不得为负
 */
public record HouseholdLaborCommitment(
    LaborAllocationId id,
    PeopleLotId group,
    HouseholdId household,
    ActorRef actor,
    String activity,
    long laborMilli,
    long period) {

  public HouseholdLaborCommitment {
    if (id == null) {
      throw new IllegalArgumentException("LaborAllocation.id 不得为 null");
    }
    if (group == null) {
      throw new IllegalArgumentException("LaborAllocation.group 不得为 null");
    }
    if (household == null) {
      // ★ 旧档缺 household 的兜底**不在这里**：由 EconomyCodec 的旧档反序列化器造 pending 占位、
      //   再由 LegacyHouseholdMigration 换成真实家户（见 HouseholdIds.PENDING_LEGACY_PREFIX）。
      throw new IllegalArgumentException("LaborAllocation.household 不得为 null");
    }
    if (actor == null) {
      throw new IllegalArgumentException("LaborAllocation.actor 不得为 null");
    }
    if (activity == null || activity.isBlank()) {
      throw new IllegalArgumentException("LaborAllocation.activity 不得为空白");
    }
    if (laborMilli < 0L) {
      throw new IllegalArgumentException("LaborAllocation.laborMilli 不得为负: " + laborMilli);
    }
    if (period < 0L) {
      throw new IllegalArgumentException("LaborAllocation.period 不得为负: " + period);
    }
  }

  /**
   * ★★ <b>配额 id 的唯一拼写点</b>（H5；S1 起带上家户）：{@code alloc-<产业 id>-<批次 id>-<家户 id>}。
   *
   * <p>★★ <b>为什么它必须在契约层</b>：H5 之前这个格式只被 {@code EconomySeeder} 写（创世发配额）；H5 起 {@code 旧结算引擎（R3a 已删除）}
   * 的**劳动再分配**也会新发配额（"未吸收的劳动回池 ⇒ 分给有缺口的产业"，裁定 C2）——
   * 同一个格式因此有了第二个写者。把它钉在这里，两个写者读同一处（"同一事实两处拼写点"是本仓明令禁止的形态）。
   *
   * <p>★ <b>确定性</b>：{@code (产业, 批次, 家户)} 的纯函数 ⇒ 同一三元组必然给出同一个 id（重放/分支可比）。 ★ <b>不含 {@code
   * "."}</b>：产业 id 形如 {@code farm@0_0}、批次 id 形如 {@code rural:0_0:MALE:1}， 家户 id 形如 {@code
   * hh-0_0-rural-poor_peasant}/{@code legacy-0_0|rural|poor_peasant} ⇒ 地址 {@code
   * economy:<mapId>:allocation.<id>} 不会被 {@code AddressParser} 在第一个点处截断。
   *
   * @param industry 收劳动的那个产业；不得为 null
   * @param group 出劳动的人口批次；不得为 null
   * @param household 这份劳动所属的家户；不得为 null
   */
  public static LaborAllocationId idOf(
      IndustryId industry, PeopleLotId group, HouseholdId household) {
    if (industry == null) {
      throw new IllegalArgumentException("LaborAllocation.idOf 的 industry 不得为 null");
    }
    if (group == null) {
      throw new IllegalArgumentException("LaborAllocation.idOf 的 group 不得为 null");
    }
    if (household == null) {
      throw new IllegalArgumentException("LaborAllocation.idOf 的 household 不得为 null");
    }
    return new LaborAllocationId(
        "alloc-" + industry.value() + "-" + group.value() + "-" + household.value());
  }

  /**
   * ★★ <b>R3B.2：按 {@code ProductionUnitId} 拼配额 id</b>（{@code alloc-<unit>-<批次>-<家户>}）。
   *
   * <p>★ <b>为什么必须新增而不是复用产业版</b>：同一产业可以有多个 unit（同一批人给两个单位出劳动）⇒ 用产业 id 拼会让两条配额撞同一个 id（{@code
   * FieldDelta} 的 map 键撞车 = 静默丢一条劳动）。unit id 是 {@code unit-<industry>-<operator>}，已含产业段 ⇒ 新旧 id
   * 不会撞。
   */
  public static LaborAllocationId idOf(
      ProductionUnitId unit, PeopleLotId group, HouseholdId household) {
    if (unit == null) {
      throw new IllegalArgumentException("LaborAllocation.idOf 的 unit 不得为 null");
    }
    if (group == null) {
      throw new IllegalArgumentException("LaborAllocation.idOf 的 group 不得为 null");
    }
    if (household == null) {
      throw new IllegalArgumentException("LaborAllocation.idOf 的 household 不得为 null");
    }
    return new LaborAllocationId(
        "alloc-" + unit.value() + "-" + group.value() + "-" + household.value());
  }

  /** ★ 旧档（无 household）的 id 形状 {@code alloc-<产业>-<批次>}；<b>只准旧档迁移读取/对账</b>。 */
  @Deprecated
  public static LaborAllocationId idOfLegacy(IndustryId industry, PeopleLotId group) {
    if (industry == null) {
      throw new IllegalArgumentException("LaborAllocation.idOfLegacy 的 industry 不得为 null");
    }
    if (group == null) {
      throw new IllegalArgumentException("LaborAllocation.idOfLegacy 的 group 不得为 null");
    }
    return new LaborAllocationId("alloc-" + industry.value() + "-" + group.value());
  }
}

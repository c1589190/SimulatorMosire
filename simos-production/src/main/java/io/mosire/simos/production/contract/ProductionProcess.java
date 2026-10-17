package io.mosire.simos.production.contract;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>生产实例：一次实际发生的生产活动</b>（D-032 约束设计书 §3/§4.1；<b>本批从 {@code economy.model} 迁入 production
 * 契约</b>；★ A 批按复核裁决 §1 补<b>显式地点</b>与<b>逐 (家户×角色) 周期贡献</b>）。
 *
 * <p>★★ <b>迁移口径（本批的领域职责调整，不是搬文件）</b>：D-032 边界裁决 §1 原文「生产实例 {@code ProductionProcess} →
 * <b>production</b>：从 {@code EconomyData.units} 迁出。实例身份、方案引用、
 * 经营者引用、地点、生产进度、周期实际劳动与实际投入累计<b>在此维护</b>」。相应地：
 *
 * <ul>
 *   <li>持久权威在 {@code ProductionData.instances}（本记录就是它的值类型）；
 *   <li>economy <b>只读</b>本契约（临时只读视图 / 本轮索引，不得跨 revision 陈旧——I13）；
 *   <li>economy 对实例的<b>推进</b>（进度 / 累计劳动 / 累计投入）经 {@link ProductionRuntime#execute} 的"最终投入 →
 *       回执"通路完成，不再自己写实例（§4.2 两步契约、I1）。
 * </ul>
 *
 * <p>★★ <b>方案 / 实例身份分层</b>（复核裁决 §1；设计书 §13.4 A 批第 4 条）：
 *
 * <pre>
 * IndustryId（方案）      —— 不绑地点的稳定身份；一份定义可被多个地点的实例引用
 * ProductionUnitId（实例）—— 实例自己的稳定身份
 * ProductionLocation（实例）—— ★ 实例的<b>显式地点</b>（A 批新增组件）
 * </pre>
 *
 * <p>⇒ 实例的地点<b>不再</b>从方案 id 的串里反解（旧 {@code hexKeyOf} 反解是"地址即身份"的老病）：
 * 地点是实例自己的组件，方案换名/换定义都不动它；同一方案基础值改变后，两地实例都按<b>新的有效定义</b> 计算（它们引用的是同一个方案 id）。
 *
 * <p>★★ <b>逐 (家户×角色) 周期贡献是实例状态的一部分</b>（D2 §6.1；A 批新增组件、C1 增角色维 {@link
 * #cycleContributions()}）：整周期"谁出了多少工、当时生效效率多少"必须能从本状态正确重建 ——
 * 收获不得只拿最后一天劳动除以整个周期天数，也不得只让最后一天出工者获得整周期影响。★ 该台账由 <b>每个生产日</b>累加（不是关账日才有），关账日只读它、算完清零（一次实际劳动只计一次）。
 *
 * <p>★★ <b>三件事各归各位（原文保留，口径未变）</b>：
 *
 * <pre>
 * OwnershipStake    —— 实物总账：谁拥有、谁使用、多少（唯一的 quantity 真相）
 * IndustryDefinitionView —— 方案定义：怎么做、每单位要什么/产什么（唯一的配方真相）
 * ProductionProcess —— 谁在实际生产：operator + 地点 + 进度 + 本周期实扣投入（结算的唯一主体）
 * </pre>
 *
 * <p>★★ <b>为什么它住在 {@code contract} 而不是 production 内部</b>：economy 是它的<b>主要读者</b>
 * （分配、关系、债务容量、迁移都要知道"这一格有谁在产、经营者是谁、进度到哪"），而 I4 禁止 economy import production 内部状态类。放在公开契约包 =
 * "只读地看"合法、"写"仍只有 production 自己能做。
 *
 * <p>★★ <b>量纲与口径</b>：{@code progressDays} = 本周期已过天数（∈ [0, 方案.cycleDays]，上界由 production 侧的跨表守卫判 ——
 * 本类型看不见方案定义，故不在这里判）；{@code cycleInputUsedMilli} = 本周期<b>实际扣到</b>的投入（毫单位，按商品）；{@code
 * cycleContributions} = 本周期<b>逐 (家户×角色)</b>实际劳动与"按当时生效效率<b>精确加权</b>的劳动"（见 {@link
 * CycleContribution}）。★ 周期劳动总数<b>不再另存</b>： 它是 {@code cycleContributions} 的派生合计（存两份必然漂开）。
 *
 * <p>★ <b>规模不在这里</b>：实例的可用实物资产由资产的 (industry, operator) 归属<b>纯派生</b>， 本记录不存第二份（存了就会与总账漂开）。
 *
 * <p>★ <b>命名</b>：{@code modeKey} 是"这次生产按哪种方式做"的稳定键（经验/学习的读物）； 旧档 = {@code
 * industry.id().value()}，将来的创建请求 = 方案 id 的稳定派生。
 *
 * @param id 稳定身份；新生成走 {@link ProductionUnitId#idOf(IndustryId, ActorRef)}，旧档 id 原样 opaque 读
 * @param plan <b>方案稳定 ID 引用</b>（不复制定义：方案可跨地点共享，实例携带地点）；不得为 null
 * @param location ★ <b>实例的显式地点</b>（A 批新增）；不得为 null（不绑地点的方案也要说清在哪一格生产）
 * @param operator 实际经营者；投入/产出/关系/账户都归它；不得为 null
 * @param modeKey 生产方式键；不得为空白
 * @param progressDays 本周期已过天数；不得为负（上界跨表判）
 * @param cycleContributions 本周期逐 (家户×角色) 实际贡献台账（键 = 家户×角色）；不得为 null、逐值不得为 null、
 *     键必须与值内的家户/角色一致（无出工用空表）
 * @param cycleInputUsedMilli 本周期实际扣到的投入（毫单位，按商品）；不得为 null、逐值不得为负
 */
public record ProductionProcess(
    ProductionUnitId id,
    IndustryId plan,
    ProductionLocation location,
    ActorRef operator,
    String modeKey,
    long progressDays,
    Map<ProductionParticipantKey, CycleContribution> cycleContributions,
    Map<CommodityId, Long> cycleInputUsedMilli) {

  public ProductionProcess {
    Objects.requireNonNull(id, "ProductionProcess.id 不得为 null");
    Objects.requireNonNull(plan, "ProductionProcess.plan 不得为 null");
    // ★★ A 批：地点是实例的<b>必需</b>组件（缺地点 = 实例不知道自己在哪一格生产 ⇒ 状态自相矛盾）。
    Objects.requireNonNull(location, "ProductionProcess.location 不得为 null（实例必须有显式地点）");
    Objects.requireNonNull(operator, "ProductionProcess.operator 不得为 null");
    if (modeKey == null || modeKey.isBlank()) {
      throw new IllegalArgumentException("ProductionProcess.modeKey 不得为空白");
    }
    if (progressDays < 0L) {
      throw new IllegalArgumentException("ProductionProcess.progressDays 不得为负: " + progressDays);
    }
    if (cycleContributions == null) {
      throw new IllegalArgumentException("ProductionProcess.cycleContributions 不得为 null（无出工用空表）");
    }
    // ★ 保序不可变 + 逐值非负：LinkedHashMap + unmodifiableMap（绝不用 Map.copyOf —— 迭代序不是内容的纯函数）。
    Map<ProductionParticipantKey, CycleContribution> contributionCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionParticipantKey, CycleContribution> entry :
        cycleContributions.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionProcess.cycleContributions 的键与值都不得为 null: " + entry.getKey());
      }
      // ★ 键必须等于值内的家户×角色（同 ProductionData 各表的"键 == 值内 id"纪律）：否则同一台账里
      //   "谁在哪个角色出工"有两个说法，回执按值读、索引按键读，必然漂开。
      if (!entry.getKey().equals(entry.getValue().participantKey())) {
        throw new IllegalArgumentException(
            "ProductionProcess.cycleContributions 的键必须与值内的家户/角色一致：键="
                + entry.getKey()
                + "，值内="
                + entry.getValue().participantKey());
      }
      contributionCopy.put(entry.getKey(), entry.getValue());
    }
    cycleContributions = Collections.unmodifiableMap(contributionCopy); // ★ 冻在赋值处

    if (cycleInputUsedMilli == null) {
      throw new IllegalArgumentException("ProductionProcess.cycleInputUsedMilli 不得为 null（未投入用空表）");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : cycleInputUsedMilli.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "ProductionProcess.cycleInputUsedMilli 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "ProductionProcess.cycleInputUsedMilli 不得为负："
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    cycleInputUsedMilli = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }

  /**
   * ★ <b>不提供"不带地点"的过渡构造器</b>（A 批纪律）：地点是实例的必需组件，缺地点的实例不是"半成品" 而是<b>状态自相矛盾</b>。⇒
   * 构造期直接拒绝，调用点必须在编译期就把地点补上；不留兼容位、不留 "先空着回头补"的口子（那会变成长期第二份口径）。
   */

  /**
   * ★ <b>方案引用的别名读口</b>：record 组件名是 {@code plan}（D-032 语义名：实例引用的是<b>方案</b>， 不是"一个格上的产业"），本方法给出历史沿用的
   * {@code industry()} 拼写。
   *
   * <p>★ 两个名字读<b>同一个</b>组件（同一份数据，不是两个权威）。留这个别名是为了让经济侧既有的 只读调用点不必在 A 批被机械重命名（那些重命名属 B 批收口，不是 A
   * 批的语义）。
   */
  public IndustryId industry() {
    return this.plan;
  }

  /**
   * ★★ <b>这个实例属于哪一格</b>（规范 {@code <q>_<r>} 键；<b>唯一拼写点</b>）。
   *
   * <p>★★ <b>判据冻结（A 批地点分层的读法）</b>：<b>先认实例自己的显式地点</b>（{@link #location()}，实例的权威地点）；
   * 地点是"没有落定"的占位（{@link ProductionLocation#UNSPECIFIED}）时，退回按方案 id 的格段读 （{@link
   * ProductionLocation#ofPlan}——"把既有生成约定读成地点"正是该方法的既有用途）。
   *
   * <p>★ <b>两者都判不出来</b> ⇒ {@link Optional#empty()}：调用方<b>不得猜一个格子</b>；写路径必须 fail-closed（B3
   * 起"实例必须有真实地点" 由各构造点各自把关，这个空值只剩读侧具名防御）。
   *
   * <p>★★ <b>B4：本方法是清区归属与"某格是否已有生产实例"的同一处拼写点</b>（此前只写在 {@code
   * ClearRegionDefinitionsHandler.regionHexKeyOf} 里，组合根的只读端口无法复用 ⇒ 会把同一条判据抄成两份）。
   */
  public Optional<String> hexKey() {
    if (location.isSpecified()) {
      return Optional.of(location.value());
    }
    return ProductionLocation.ofPlan(plan).map(ProductionLocation::value);
  }

  /** ★ <b>本周期累计实际劳动</b>（毫小时/日）= 逐 (家户×角色) 贡献的合计（派生值：不存第二份，故不会漂开）。 */
  public long cycleLaborMilli() {
    long total = 0L;
    for (CycleContribution contribution : cycleContributions.values()) {
      total = Math.addExact(total, contribution.laborMilli());
    }
    return total;
  }

  /**
   * ★ <b>本周期累计"按当时生效效率加权"的劳动</b> （{@code Σ (实际劳动 × 当 tick 生效效率倍率)}；单位 = 千分劳动·日 × 千分倍率，<b>不</b>除
   * 1000） = 逐 (家户×角色) 加权劳动的合计（派生值，不存第二份）。★ A′ 批起逐 tick 的乘积不截断 ⇒ 本值是精确量， E_cycle 由它 ÷ Σ实际劳动
   * 得出（{@code ProductionEfficiency}）。
   */
  public long cycleEfficiencyWeightedLaborMilli() {
    long total = 0L;
    for (CycleContribution contribution : cycleContributions.values()) {
      total = Math.addExact(total, contribution.efficiencyWeightedLaborMilli());
    }
    return total;
  }

  /** ★ <b>本周期逐家户实际劳动贡献（跨角色合并）</b>（回执/结算读的形态；与 {@link #cycleContributions()} 同源）。 */
  public Map<HouseholdId, Long> cycleLaborByHousehold() {
    Map<HouseholdId, Long> byHousehold = new LinkedHashMap<>();
    for (CycleContribution contribution : cycleContributions.values()) {
      byHousehold.merge(contribution.household(), contribution.laborMilli(), Math::addExact);
    }
    return Collections.unmodifiableMap(byHousehold);
  }

  /**
   * ★ <b>加一笔本次贡献</b>（同一家户同角色多次出工 ⇒ 累计，不同角色分行）——每个生产日推进时调，<b>不</b>只在关账日调。
   *
   * @param household 出工家户；不得为 null
   * @param role 出工角色；不得为 null
   * @param deltaLaborMilli 本次实际劳动（毫小时/日）；必须为正
   * @param deltaEfficiencyWeightedLaborMilli 本次按当刻效率倍率<b>精确加权</b>的劳动 （{@code 劳动 × E}，<b>不</b>除
   *     1000、<b>不</b>截断）；不得为负
   */
  public ProductionProcess withContribution(
      HouseholdId household,
      io.mosire.simos.economy.api.production.ProductionRoleId role,
      long deltaLaborMilli,
      long deltaEfficiencyWeightedLaborMilli) {
    Objects.requireNonNull(household, "ProductionProcess.withContribution 的 household 不得为 null");
    Objects.requireNonNull(role, "ProductionProcess.withContribution 的 role 不得为 null");
    ProductionParticipantKey key = new ProductionParticipantKey(household, role);
    if (deltaLaborMilli <= 0L) {
      throw new IllegalArgumentException(
          "ProductionProcess.withContribution 的劳动增量必须为正: " + deltaLaborMilli);
    }
    Map<ProductionParticipantKey, CycleContribution> next = new LinkedHashMap<>(cycleContributions);
    CycleContribution current = next.get(key);
    next.put(
        key,
        current == null
            ? new CycleContribution(
                household, role, deltaLaborMilli, deltaEfficiencyWeightedLaborMilli)
            : current.plus(deltaLaborMilli, deltaEfficiencyWeightedLaborMilli));
    return new ProductionProcess(
        id, plan, location, operator, modeKey, progressDays, next, cycleInputUsedMilli);
  }

  /**
   * ★★ <b>换身份位（经营者 / 生产方式键）</b>——进度、周期贡献台账、累计投入、地点一律原样带过。
   *
   * <p>★ <b>为什么它不能顺手改进度</b>：改经营者/生产方式不是"重开一个周期"；本周期已经发生的实际劳动与 投入仍属于这个实例（复核裁决
   * §5：一次实际劳动只计一次、整周期贡献可重建）。⇒ 那些字段是生产事实， 身份位才归调用方。
   *
   * @param nextOperator 新经营者；不得为 null
   * @param nextModeKey 新生产方式键；不得为空白
   */
  public ProductionProcess withIdentity(
      io.mosire.simos.actor.api.actor.ActorRef nextOperator, String nextModeKey) {
    Objects.requireNonNull(nextOperator, "ProductionProcess.withIdentity 的 operator 不得为 null");
    if (nextModeKey == null || nextModeKey.isBlank()) {
      throw new IllegalArgumentException("ProductionProcess.withIdentity 的 modeKey 不得为空白");
    }
    return new ProductionProcess(
        id,
        plan,
        location,
        nextOperator,
        nextModeKey,
        progressDays,
        cycleContributions,
        cycleInputUsedMilli);
  }

  /** 换本周期状态（进度 / 累计投入），身份、地点、经营者与贡献台账逐字带过。 */
  public ProductionProcess withCycleState(
      long nextProgressDays, Map<CommodityId, Long> nextInputUsedMilli) {
    return new ProductionProcess(
        id,
        plan,
        location,
        operator,
        modeKey,
        nextProgressDays,
        cycleContributions,
        nextInputUsedMilli);
  }

  /** 换本周期状态（进度 / 贡献台账 / 累计投入）：关账时用（台账清空 = 一次实际劳动只计一次）。 */
  public ProductionProcess withCycleState(
      long nextProgressDays,
      Map<ProductionParticipantKey, CycleContribution> nextContributions,
      Map<CommodityId, Long> nextInputUsedMilli) {
    return new ProductionProcess(
        id,
        plan,
        location,
        operator,
        modeKey,
        nextProgressDays,
        nextContributions,
        nextInputUsedMilli);
  }
}

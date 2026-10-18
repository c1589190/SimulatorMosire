package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>P2-B §13.4/§13.5：每 tick 的家户劳动利润率排队落点</b>（写 {@code allocations} 工作副本的唯一新写者）。
 *
 * <p>★★ <b>两阶段（为什么不是一个家户一个家户就地写）</b>：一条 unit 是**共享**的生产活动（主 unit 由多个家户按 {@code
 * HouseholdLaborCommitment} 出工；家户自营 unit 通常只有一个 operator 家户）。因此"这个 unit 本 tick 最大可吸收多少劳动"
 * 是<b>全局</b>上界，不能每家各算一次满额。本类分两阶段：
 *
 * <ol>
 *   <li><b>逐家户排队（只算不写）</b>：按 {@link LaborQueueBook#plan} 用家户自己的时间预算排队，得到"想要多少"；
 *   <li><b>逐 unit 全局封顶</b>：Σ 各家户对同一 unit 的想要量若超过该 unit 的最大可吸收量，按各户想要量比例 （最大余数法）缩到上界 —— 公平、确定、且只减不增；
 *   <li><b>逐家户写回</b>：同 (家户, unit) 复用既有配额行的 id/批次/actor，只改 laborMilli（没有才新发）； 最终拿不到量/预期 ≤ 0 的 unit
 *       的旧行整条删除（劳动留在空缺，不塞给别的 unit）。
 * </ol>
 *
 * <p>★★ <b>哪些活动"被保留、不排队"</b>（既有配额原样保留并先占预算，见 {@link LaborQueueBook#isPreservedByQueue}）：{@code
 * laborPerUnit ≤ 0} 的非劳动活动、没有产出配方的承运/贸易、 产出一个价都没有的活动，以及 R4-E2b 当天刚进入的试产 unit。只有"确实定过价、且预期 ≤
 * 0"的活动会被判为空缺。
 *
 * <p>★★ <b>C7（Z1b）：{@code GOV_SERVICE} 承诺是最高优先级、不可缩</b>——不进队列、不被重算、不被按比例缩； {@code
 * preserveIntoBudget} 先整额保留 GOV_SERVICE，只用剩余预算给 PRODUCTION 排队/缩。若某户 {@code Σ GOV_SERVICE > 家户
 * laborMilli}（不变量本不该允许）⇒ 具名 {@code LABOR_COMMITMENT_CONTRACT} ERROR + fail-closed，绝不静默缩/丢；
 * 队列也不得创建/覆盖 GOV_SERVICE（只允许政府工具写，Z3）。
 *
 * <p>★ <b>为什么按家户全局串行而不是按 hex 并行</b>：时间预算是**家户**的资源，可能有多条 unit（多生产方式）跨组织； 按 hex
 * 分区会让"保留配额"与"新分配"在合并后才对账。家户数 × unit 数是可控量级，串行保证 {@code Σallocations(household) ≤ laborMilli}
 * 在写回前就成立（{@code EconomyData} 的构造期守卫是第二道）。
 *
 * <p>★ <b>确定性</b>：家户 / unit / 配额全部按稳定 id 排序遍历；比例缩放在每个 unit 内按下标序（= 家户 id 升序）； 无随机、无时钟、无
 * UUID；同输入同输出。
 */
final class LaborQueueSettlement {

  /**
   * ★★★ <b>D-032 D2 §5.6：套利活动的角色</b>。套利是家户自营活动（没有 ProductionProcess/方案角色）；生产承诺 仍必须携带角色，故用一个稳定的非方案角色
   * id 表达"这是自营套利"，<b>不</b>再引入第二个 role 字符串字段。
   */
  static final ProductionRoleId ARBITRAGE_ROLE = new ProductionRoleId("arbitrage");

  private LaborQueueSettlement() {}

  /**
   * ★ <b>兼容重载（本日没有家户账视图 / 不收套利决定）</b>：等价于传空账视图与一个丢弃用的收集器 ——
   * 套利活动<b>照常评估与排序</b>（行为只有一份），只是决定没有出口（读口/单模块夹具用）。
   */
  static LaborQueueReport apply(
      EconomySession session,
      SettlementIndex index,
      long day,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<ProductionUnitId> entryTrialUnits) {
    return apply(
        session,
        index,
        day,
        composition,
        entryTrialUnits,
        null,
        new MarketArbitragePlan.Collector(),
        Map.of());
  }

  /**
   * 对 {@code session} 的 {@code allocations} 工作副本执行一次全量排队（每个世界日调用一次；旧档 {@code modes}
   * 为空时不调用，旧路径逐值不变）。
   *
   * <p>★★ <b>2026-10-08（阶段 1 统一活动选择器 + 套利作为一种活动）</b>：本方法是 §4.3.1 的落点 —— <b>同一个排序器</b>（{@link
   * ActivitySelector}）对"生产 offer + 套利 offer"排一次序，再按序分配家户的
   * <b>劳动</b>预算；套利活动的<b>库存/现金</b>那一侧由市场阶段按分配结果落成订单（见 {@link TradeArbitrageActivity} 与 {@link
   * MarketArbitragePlan}）。
   *
   * <ul>
   *   <li><b>同一份快照（§4.3.5）</b>：{@link HouseholdResourceSnapshot} 在排序之前<b>一次</b>构建（阶段 0），
   *       排序与规模都用它；执行阶段不回头重排；
   *   <li><b>生活保留优先（I15）</b>：套利只<b>加买</b>、绝不减卖，也不碰 {@code life}/{@code demandParts} 算式；
   *   <li><b>生产投入不被侵占（I14）</b>：本方法的调用点在投入扣减<b>之后</b>（ {@code
   *       EconomySettlement.PLANTING_DRAWS_BEFORE_CONSUMPTION = true}）⇒ 快照里的库存已是扣完料的余额。
   * </ul>
   *
   * @param session 结算会话（读 base/工作副本；只写 {@code allocations}）
   * @param index 当天的派生索引（可用资产/产能/actor→家户只读）
   * @param day 当前世界日（报告/日志用）
   * @param composition 家户人口组成的只读投影（挑新配额挂哪个批次）
   * @param entryTrialUnits 今天刚由候选预设进入的试产 unit（R4-E2b）：它们的 {@code modeKey} 不是 {@code mode:} 前缀 ⇒
   *     **本日保留试产配额原样**；自动组织阶段今天新建的 unit（{@code mode:} 前缀）照常进队列
   * @param accounts 当日家户账的活视图（{@code null} = 没有账视图：套利活动按"无可动库存/货币"评估，
   *     不会凭空吃货，也不会抛）；它只被<b>读</b>，本方法不写任何账户
   * @param arbitrageCollector 套利决定的出口（不得为 null；排序的产出 → 市场阶段的输入）。 ★ 由 {@code EconomySettlement}
   *     传入，市场阶段用 {@code toPlan()} 取走
   * @return 本日的可读排队报告
   */
  static LaborQueueReport apply(
      EconomySession session,
      SettlementIndex index,
      long day,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<ProductionUnitId> entryTrialUnits,
      AccountSession accounts,
      MarketArbitragePlan.Collector arbitrageCollector) {
    return apply(
        session, index, day, composition, entryTrialUnits, accounts, arbitrageCollector, Map.of());
  }

  /**
   * ★★★ <b>D3 §2.5/§4.4：带只读 SurvivalOutlook 的显式入口</b>（dry-run/读口/后续 W4 装配用）。
   *
   * <p>{@code survivalOutlooks} 只提供 {@code idleEligible} 资格；{@code idleChosen} 仍由本类的 {@link
   * LaborQueueBook#plan} 按 {@code SURVIVAL_IDLE_POLICY} 写。真实 {@code settleOneDayInto} 在 W3 未接时 由 B2
   * 预检整 tick 拒绝，因此本入口只服务只读验证。
   */
  static LaborQueueReport apply(
      EconomySession session,
      SettlementIndex index,
      long day,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<ProductionUnitId> entryTrialUnits,
      AccountSession accounts,
      MarketArbitragePlan.Collector arbitrageCollector,
      Map<HouseholdId, SurvivalOutlook> survivalOutlooks) {
    return LaborQueuePlanBook.applyInternal(
        session,
        index,
        day,
        composition,
        entryTrialUnits,
        accounts,
        arbitrageCollector,
        survivalOutlooks,
        null,
        null,
        java.util.List.of());
  }

  /**
   * ★★★ <b>D-032 W4：真实生产 tick 的 Social 投影入口</b>。
   *
   * <p>{@code survivalSocialFacts} 是 app 从 Social 下一 tick 需求/劳动预算接进来的权威只读事实；本类在候选 offer 已算出后， 用 W2b
   * 的 {@link SurvivalOutlookEvaluator} 组装 {@link SurvivalOutlook}，再把 {@code idleEligible} 交给同一个
   * {@link LaborQueueBook#plan} 写 {@code idleChosen}。不复制任何生存/匹配公式。
   *
   * <p>★ P4a：MONEY_WAGE 岗位不再依赖工资库存 lookup；本入口不再注入任何 inventory 读口。
   */
  static LaborQueueReport apply(
      EconomySession session,
      SettlementIndex index,
      long day,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<ProductionUnitId> entryTrialUnits,
      AccountSession accounts,
      MarketArbitragePlan.Collector arbitrageCollector,
      SurvivalSocialFacts survivalSocialFacts) {
    Objects.requireNonNull(survivalSocialFacts, "survivalSocialFacts");
    return LaborQueuePlanBook.applyInternal(
        session,
        index,
        day,
        composition,
        entryTrialUnits,
        accounts,
        arbitrageCollector,
        Map.of(),
        survivalSocialFacts,
        null,
        java.util.List.of());
  }

  /**
   * ★★★ <b>D-032 P3c3：M6 实际编排的锁定入口</b>。
   *
   * <p>{@code allowedSlots} 非 null ⇒ 只排 M3/M6 实际计划里的 (unit, role)，且 {@code locks} 对应的槽保留既有承诺、不进队列；
   * 锁定雇佣不可变。{@code allowedSlots == null} 与旧行为逐值相同。
   */
  static LaborQueueReport apply(
      EconomySession session,
      SettlementIndex index,
      long day,
      Map<HouseholdId, Map<PeopleLotId, Long>> composition,
      Set<ProductionUnitId> entryTrialUnits,
      AccountSession accounts,
      MarketArbitragePlan.Collector arbitrageCollector,
      SurvivalSocialFacts survivalSocialFacts,
      Set<LaborQueueWritebackBook.UnitRole> allowedSlots,
      java.util.List<EmploymentLock> locks) {
    Objects.requireNonNull(survivalSocialFacts, "survivalSocialFacts");
    Objects.requireNonNull(locks, "locks（无锁请用空表）");
    return LaborQueuePlanBook.applyInternal(
        session,
        index,
        day,
        composition,
        entryTrialUnits,
        accounts,
        arbitrageCollector,
        Map.of(),
        survivalSocialFacts,
        allowedSlots,
        locks);
  }
}

package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.ChangeSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★★ <b>production 计算契约（两步调用）</b>（D-032 约束设计书 §4.2/§4.3；用户 U8「生产方式模块干脆提供
 * 单个家户选用某个生产方式，预计/实际的产出和家户状态更新」）。
 *
 * <p>★★ <b>接口在契约包、实现留 production 内部、由组合根装配</b>（§4.2 原文 + 边界裁决 §2）： 本类型是 economy
 * 唯一允许看见的生产计算面；实现类（{@code ProductionRuntimeEngine}）住 {@code io.mosire.simos.production}（非 contract
 * 包），economy <b>不得</b> import 它（I4/V11）。
 *
 * <p>★★ <b>同 tick 数据流（§4.3 原文的落形）</b>：
 *
 * <pre>
 * 共同基态 + 本 tick 外部事实
 *  → economy 整理可用资源、经济约束与候选
 *  → {@link #probe}                ：可行性/规模/劳动与原料要求/预计产出（无副作用）
 *  → economy 按需求—利润排序；统一分配劳动、土地、工具、原料（不得重复占用）
 *  → {@link #execute}              ：按最终实际投入计算生产、进度与经验，产生本域候选态/变更集
 *  → economy 用回执计算估值、工资/租金/分成等经济变化
 *  → 持账方（actor）计算实际库存/货币变化；需要迁移时 Social 与经验账同步处理
 *  → 统一候选态校验 → 同一 Revision 原子提交（{@link #changeSet()} 就是本域那一份）
 * </pre>
 *
 * <p>★★ <b>{@link #changeSet()} 为什么由 production 自己派生</b>（边界裁决 §2 末条原文）： 「具体 {@code
 * ProductionChangeSet} 仍由 production 自己从完整本域候选态派生，economy 不必越过包边界 自行构造它」。⇒ economy 只把这份 {@link
 * ChangeSet} 原样汇入同一次提案（S11 汇合点）， <b>不</b> import {@code ProductionChangeSet}、<b>不</b>拼它的组件。
 *
 * <p>★★ <b>只读视图允许，第二份可写权威禁止</b>（§4 纠正一 / I13）：{@link #units()} / {@link #definitions()} / {@link
 * #outputQuantityOverrides()} 等是<b>本轮候选态</b>的只读投影 （economy 可以拿它作临时索引），但<b>不得</b>被 economy
 * 写、也<b>不得</b>跨 revision 陈旧。
 */
public interface ProductionRuntime {

  /**
   * ★★ <b>① 试算（无副作用）</b>：可行性 / 可生产规模 / 劳动与原料需求 / 预计产出。
   *
   * <p>★ 契约（I5/V1）：本方法<b>不</b>改任何切片、不写 revision、不推进时间、不涨经验。同一份入参 + 同一份 候选态 ⇒ 同一答案（V2）。★
   * 价格与需求不进这里（§4.2）。
   */
  ProductionProbeResult probe(ProductionProbeRequest request);

  /**
   * ★★ <b>② 执行（按最终投入推进实例并给出回执）</b>：实际消耗 / 实毛产（含铸币候选）· 进度变化 · 实际出工家户与贡献 · 精度余数；同时把本域候选态推进一格。
   *
   * <p>★ 本方法<b>会</b>改 production 本域候选态（这就是它的职责），但<b>不</b>改别的领域、不写 revision、
   * 不动账户（账户由持账方在同一次提案里落账，I11）。
   *
   * <p>★★ <b>使用资格（R3 / §15.3）</b>：它是<b>权威实际入口</b>，必须自己判"本次使用资格"，与 {@link #probe} 共用同一套条件求值：①
   * 方案设了解锁门槛 ⇒ 主体家户的 {@link #unlocksOf} 必须含该方案，否则业务拒绝 {@code locked}； ② 方案声明的可用性条件按本次 {@link
   * ProductionExecutionRequest#attributes()} 与本域经验账判，不满足 ⇒ {@code
   * condition-unsatisfied:&lt;理由&gt;}（文本与 probe 逐字相同）；③ <b>主体</b> = 实例经营者的家户（经营者必须是 {@code
   * ActorKind.HOUSEHOLD}）；组织经营者没有家户主体 ⇒ 声明了经验门槛/解锁门槛的方案对它 fail-closed（{@code
   * condition-subject-unknown}），外部属性条件不受影响。★ 被拒时<b>零残留</b>：不建实例、不改进度/经验/投入/占用。
   *
   * @return {@link ProductionExecutionOutcome.Executed} 或业务拒绝 {@link
   *     ProductionExecutionOutcome.Rejected}（具名理由 = INFO）
   * @throws ProductionContractFault 契约故障（引用悬空、本域状态自相矛盾、公式求值失败）⇒ 拒绝整个候选提交
   */
  ProductionExecutionOutcome execute(ProductionExecutionRequest request);

  /**
   * ★★★ <b>② b 批量驱动：推进一个生产日的全部实例</b>（§13.2 第 1 条；复核裁决 §2.2）。
   *
   * <p>★★ <b>它就是"对每一步各调一次 {@link #execute}"</b>——同语义、同公式、同回执；批次级只多三件：
   *
   * <ol>
   *   <li><b>确定序</b>：按 {@link ProductionDayRequest#steps()} 的给定序执行，引擎不重排；
   *   <li><b>失败语义</b>（复核裁决 §2.3）：<b>业务拒绝</b>逐项具名放进 {@link
   *       ProductionDayResult#rejections()}，被拒的那一步<b>零残留</b>（没有新实例、没有进度、
   *       没有经验、没有原料消耗、没有效率行；释放出来的资源如何重分配仍归 economy）；而 <b>契约故障 / 公式错误 / 重复推进 / 跨域不一致</b>一律以 {@link
   *       ProductionContractFault} <b>冒泡出去</b>，终止本次候选计算、由调用方撤销整个候选态 —— <b>禁止</b>吞错继续、
   *       <b>禁止</b>在污染态上收尾（§13.7 第 2 条）；
   *   <li><b>末尾一份 {@link #changeSet()}</b>：本域变更集始终从"本次装配起点 → 当刻候选态"派生， 而不是每天重置基态后只提交最后一天（§13.2 第 1
   *       条②末句）。
   * </ol>
   *
   * <p>★ <b>调用方仍必须先 {@link #beginTick}</b>：否则"同一实例同一 tick 重复推进"无从检测 （§13.2 第 1
   * 条②：不能只在注释里写"调用者别重复"）。
   *
   * @return 本日的回执 / 拒绝 / 关账规模（见 {@link ProductionDayResult}）
   * @throws ProductionContractFault 任一步触发契约故障 ⇒ 整个批次（以及本次 Advance）失败
   */
  ProductionDayResult executeDay(ProductionDayRequest request);

  /**
   * ★★ <b>按需新建一个生产实例</b>（§4.3「economy 在分配阶段提出创建请求，production 在自身状态里创建实例并 返回」）。
   *
   * <p>★★ <b>为什么它是一个独立入口</b>：economy 的"进入/组织/迁移/模式切换"阶段在<b>分配之前</b>就要把实例 立起来（此后同一 tick
   * 的试算与执行才能看见它）。这些阶段与"推进一天"无关，只是<b>建档</b>—— 进度 0、没有周期台账。⇒ 它是 {@code execute(creation=...)}
   * 的<b>纯建档切面</b>：语义相同、 <b>不推进进度</b>；随后本 tick 的 {@link #executeDay} 再让它走第一个生产日（进度 +1）。
   *
   * <p>★ 这一步<b>会</b>改本域候选态（这就是它的职责）；它<b>不</b>改别的领域、不写 revision。
   *
   * @return 新建的实例（只读）；已经存在同 id 的实例 ⇒ 业务拒绝（{@code unit-conflict}，<b>不覆盖</b>）
   * @throws ProductionContractFault 方案不存在、或方案的固有地点与实例地点矛盾（本域状态自相矛盾）
   */
  io.mosire.simos.production.contract.ProductionInstanceOutcome createInstance(
      ProductionCreationRequest request);

  /**
   * ★★★ <b>按需注册/更新一个方案定义</b>（§13.10.3 裁定五：「按需新建定义」的<b>唯一契约口</b>）。
   *
   * <p>★★ <b>为什么它是独立的一个口、而不是"由 economy 自己合成一份定义视图"</b>：方案数值的<b>唯一权威在 production</b>（I1）。economy
   * 在"选用的方案定义尚不存在"时只能<b>提出</b>一份定义候选，由 production 按<b>自己的合并/冲突规则</b>决定接不接受 —— 合并/冲突是生产定义的领域规则，不能在
   * economy 再写一套 （§13.10.3：「实现内部<b>复用</b> {@code DefinitionRegistration}
   * 的合并/冲突规则（<b>不得</b>另写一套合并）」）。
   *
   * <p>★★ <b>与 {@link #createInstance} 的关系</b>：两者是<b>两件事</b>（§4.1 原文「"按需新建定义"与"按需新建实例" 是两件事」）—— 先
   * {@code registerPlan(定义)} 把它立进方案权威，再 {@code createInstance(请求)} 引用它； {@code createInstance}
   * 对"方案不存在"仍然是具名拒绝 {@code plan-unknown}（不隐式建定义）。
   *
   * <p>★★ <b>冲突语义（不得静默覆盖）</b>：同 id <b>逐值相同</b> ⇒ 幂等 no-op（返回 {@code Written}）； 同 id <b>不同值</b> ⇒
   * {@link ProductionContractFault}（{@code plan-conflict}）—— 那是"两个权威对同一方案
   * 各说一套"的契约故障，必须拒绝整个候选提交（ERROR 不降级），<b>不是</b>业务拒绝。
   *
   * <p>★ 本方法<b>会</b>改本域候选态（这就是它的职责）；它<b>不</b>改别的领域、不写 revision、不建实例。
   *
   * @param plan 要登记/更新的方案定义（键 == 值内 id）；不得为 null
   * @return {@link ProductionPlanOutcome.Written}（带回登记后的定义视图，只读）
   * @throws ProductionContractFault 同 id 不同值（拒绝静默覆盖）
   */
  ProductionPlanOutcome registerPlan(IndustryDefinitionView plan);

  /**
   * ★★ <b>退役一个生产实例</b>（迁移搬迁/整户消亡/清区；§4.3 与 profile 与实例分离）。
   *
   * <p>★ 只删<b>实例本身</b>与其效率余数行；经济侧的关系/经营状态/配额由 economy 自己的表负责（同一批里删）。
   *
   * @return true = 真的删掉了一行；false = 本来就没有（幂等，不是故障）
   */
  boolean removeInstance(ProductionUnitId unit);

  /**
   * ★★ <b>按稳定身份改一个已存在实例的非生产字段</b>（经营者/生产方式键；模式切换用）。
   *
   * <p>★★ <b>它只碰"谁在经营、用哪个方式"这两个身份位</b>：进度、周期台账、效率余数一律<b>原样保留</b> —— 那些是生产事实，改经营者不是重开一个周期。
   *
   * @return 改写后的实例（只读）；实例不存在 ⇒ 业务拒绝（{@code instance-unknown}）
   */
  io.mosire.simos.production.contract.ProductionInstanceOutcome reoperateInstance(
      ProductionUnitId unit, io.mosire.simos.actor.api.actor.ActorRef operator, String modeKey);

  /**
   * ★★★ <b>D-032 B3（I10 / §13.13.7 第 6 条）：人口迁移/合户的经验携带与合并</b> —— 迁移的 <b>完整携带</b>（主值 +
   * 余数、<b>不</b>乘人口比例）与合户的<b>人口加权平均</b>两件事的唯一权威在 production （裁决 §5 第 4 条末句"新增状态仍由 production 唯一维护"）。
   *
   * <p>★★ <b>为什么必须走契约口而不是让 economy/app 自己改经验账</b>：{@code householdExperience} / {@code unlocks} 是
   * production 的状态组件（I1）；economy 只读（{@code experienceOf} / {@code
   * experienceStateOf}），它拿不到、也不许拿可写句柄。迁移的调用方（economy 的迁移结算 / 组合根）只需给出
   * <b>人口事实</b>（随迁人数、迁入户原有口数），算术与状态写入都在本域内完成。
   *
   * <p>★ <b>语义（与 V6 的用户例式逐值一致；R4 起按并集口径）</b>：经验条枚举 = <b>来源 ∪ 目标</b>（按经验条 id 排序），缺行按 0 参与平均；{@code
   * mergedExact = ⌊(exact_from × movedPeople + exact_to × targetPeople) ÷ (movedPeople +
   * targetPeople)⌋}；{@code exact = value × 1000 + remainder}；结果全零 ⇒ 目标户该行删除； 解锁取并集（I7 永久记忆）；源户不减（见
   * {@code ProductionData#mergeExperienceInto} 的逐条注）。
   *
   * @param from 迁出户；不得为 null
   * @param movedPeople 随迁人口（> 0）
   * @param to 迁入户；不得为 null、不得等于 {@code from}
   * @param targetPeople 迁入户原有口数（≥ 0）
   * @return 一条不可变回执（搬了几条经验、并入了几条解锁、是否真的变了）；不得为 null
   */
  ProductionExperienceMoveOutcome mergeExperience(
      HouseholdId from, long movedPeople, HouseholdId to, long targetPeople);

  /**
   * ★★ <b>本域变更集</b>：从<b>本域完整候选态</b>与本次装配起点的差派生（铁律 5；边界裁决 §2 末条）。
   *
   * <p>★ 调用时机：本次 Advance 的生产阶段结束后、提案汇合处。economy 把它原样放进 {@code moduleChanges}（与
   * ECONOMY/SOCIAL/ACTOR/GOV 同一个 map ⇒ 同一次 Revision，I11）。
   */
  ChangeSet changeSet();

  /**
   * ★ <b>当刻生产实例的只读投影</b>（含本次已推进的候选态；economy 的临时只读视图/本轮索引来源）。
   *
   * <p>★ 不得跨 revision 陈旧（I13）：实现必须在每次读取时给"当刻候选态"，而不是装配起点的旧表。
   */
  Map<ProductionUnitId, ProductionProcess> units();

  /** ★ <b>当刻方案定义的只读查询口</b>（方案数值的唯一权威；economy 不得另存一份可独立修改的副本）。 */
  ProductionDefinitionLookup definitions();

  /** ★ <b>当刻 GM 产出数值覆盖</b>（方案 → 商品 → 数量；预计与实际读同一份有效参数，I6/V7）。 */
  Map<IndustryId, Map<CommodityId, Long>> outputQuantityOverrides();

  /** ★ 该家户在某经验条上的经验<b>主值</b>（缺 ⇒ 0）；经验账的只读读口。 */
  long experienceOf(HouseholdId household, ExperienceTrackId track);

  /**
   * ★★ <b>该家户在某经验条上的完整读模型</b>（主值 + 精度余数 + 熟练度）——A 批新增读口 （复核裁决 §4 第 3
   * 条：迁移/合户按既有规则处理<b>完整经验特征（含余数）</b>）。
   *
   * <p>★ 只回主值的 {@link #experienceOf} 不足以支撑"整条携带"：余数是完整特征的一半，只搬主值 就是把余数扔掉。⇒ 需要迁移/合户/对账的调用方读本方法。
   */
  ExperienceState experienceStateOf(HouseholdId household, ExperienceTrackId track);

  /** ★ 该家户在某经验条上由经验解释出的熟练度（千分制；缺 ⇒ 0）。 */
  long masteryOf(HouseholdId household, ExperienceTrackId track);

  /**
   * ★★ 该家户<b>已解锁</b>的方案集合（永久记忆，I7）。
   *
   * <p>★ 语义是"资格"而不是"当前正在用"：衰减跌破门槛、经验归零、人口迁移都不撤销它（用户 U10 「肯定算数」）。实际能不能采用还要过可行性条件（可行性另一侧仍受条件约束）。
   */
  Set<IndustryId> unlocksOf(HouseholdId household);

  /** ★ 该家户是否已解锁某方案（含"该方案本就不需解锁"的情况）。 */
  default boolean isUnlocked(HouseholdId household, IndustryId plan) {
    return unlocksOf(household).contains(plan)
        || !definitions()
            .industry(plan)
            .map(definition -> definition.experience().requiresUnlock())
            .orElse(false);
  }

  /** ★ 该实例的当刻效率精度余数（缺行 ⇒ {@code Optional.empty()}；与"全零行"逐值等价）。 */
  Optional<ProductionEfficiencyState> efficiencyOf(ProductionUnitId unit);

  // ── ★★ A 批：同 tick 统一生效条件 + 重复推进检测（§13.2 第 1 条①② / §13.7 第 1 条）──────────────

  /**
   * ★★★ <b>① 开启一个生产 tick</b>（组合根在每日推进里、任何试算/执行之前调一次；tick 身份由既有调度/ 引擎上下文给出）。
   *
   * <p>★★ <b>它冻结什么</b>：经验按<b>明确日序衰减一次</b>之后形成<b>当 tick 生效条件</b> （{@link
   * ProductionTickCondition}：tick 号 + 逐 (家户 × 经验条) <b>熟练度与原始经验</b>）——R5 / §15.5 起"全部 影响当 tick
   * 生产公式的经验输入"都在这里冻结。此后本 tick 的每一次 {@link #probe}/{@link #execute} 都读这份冻结条件 ⇒ <b>实例处理先后不再改变另一实例本
   * tick 的熟练度/效率倍率</b> （裁决 §2.1 原文；旧形态是"先跑的先涨经验、后跑的白得熟练度"）。
   *
   * <p>★★ <b>重复开启必须被检测</b>（不是注释里写"别重复"）：同一 tick 再次开启 ⇒ {@link ProductionContractFault}（ERROR
   * 不降级，整个候选提交被拒）。跨 tick 只能<b>严格递增</b>。
   *
   * @param tick 世界 tick；不得为负
   * @return 本 tick 生效条件（只读；含 tick 号与熟练度/原始经验快照）
   * @throws ProductionContractFault 同一 tick 重复开启、或 tick 非严格递增
   */
  ProductionTickCondition beginTick(long tick);

  /**
   * ★★★ <b>② 逐 tick 衰减一次</b>（用户 U2「随着 tick 增加，所有经验的数值积累都会少量变少」）。
   *
   * <p>★ <b>为什么必须是独立的一步</b>：衰减是<b>世界级</b>状态推进（作用于全部家户的全部经验条），
   * 既不属于某个方案的试算，也不属于某个实例的执行；而它必须<b>先于</b>本 tick 的生效条件形成 （裁决 §2.1：「既有经验按明确日序衰减一次，<b>然后</b>形成当 tick
   * 的经验/熟练度等生产生效条件」）。
   *
   * <p>★ <b>同一 tick 重复衰减必须被检测</b>：同一 tick 第二次调用 ⇒ {@link ProductionContractFault}； 未 {@link
   * #beginTick} 就调用 ⇒ 同样拒绝（理由具名 {@code tick-not-started}）。
   *
   * @return 本次实际衰减的行数（0 = 没有可衰减的行；供日志与断言）
   */
  int advanceTick();

  /**
   * ★★ <b>③ 本 tick 生效条件</b>（未开启 tick ⇒ {@code Optional.empty()}）。
   *
   * <p>★ 它是 {@link #probe}/{@link #execute} 实际读到的熟练度来源；单独暴露是为了让 economy 的试算与
   * 组合根的分配能<b>核对同一份条件</b>（而不是各自读当刻候选态、各读各的）。
   */
  Optional<ProductionTickCondition> currentCondition();

  /** ★ 本 tick 已执行过的实例集合（只读快照）——"同一实例同一 tick 重复推进"的检测台账，见 {@link #execute}。 */
  Set<ProductionUnitId> advancedUnitsThisTick();
}

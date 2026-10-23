package io.mosire.simos.production;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.production.contract.CycleContribution;
import io.mosire.simos.production.contract.ExperienceGrowth;
import io.mosire.simos.production.contract.ExperienceState;
import io.mosire.simos.production.contract.ExperienceTrack;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.GrowthBasis;
import io.mosire.simos.production.contract.HouseholdExperienceKey;
import io.mosire.simos.production.contract.HouseholdProductionConditions;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.IndustryDefinitionView.CurrentStageInput;
import io.mosire.simos.production.contract.MoneyOutput;
import io.mosire.simos.production.contract.ParticipantContribution;
import io.mosire.simos.production.contract.ProductionAvailabilityCondition;
import io.mosire.simos.production.contract.ProductionContractFault;
import io.mosire.simos.production.contract.ProductionCreationRequest;
import io.mosire.simos.production.contract.ProductionDayRequest;
import io.mosire.simos.production.contract.ProductionDayResult;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionEfficiencyState;
import io.mosire.simos.production.contract.ProductionExecutionOutcome;
import io.mosire.simos.production.contract.ProductionExecutionRequest;
import io.mosire.simos.production.contract.ProductionExperienceFeature;
import io.mosire.simos.production.contract.ProductionExperienceGrowthLine;
import io.mosire.simos.production.contract.ProductionExperienceMoveOutcome;
import io.mosire.simos.production.contract.ProductionExperienceUse;
import io.mosire.simos.production.contract.ProductionInstanceOutcome;
import io.mosire.simos.production.contract.ProductionParticipantKey;
import io.mosire.simos.production.contract.ProductionPlanOutcome;
import io.mosire.simos.production.contract.ProductionProbeRequest;
import io.mosire.simos.production.contract.ProductionProbeResult;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionReceipt;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.ProductionRosterProbeParticipant;
import io.mosire.simos.production.contract.ProductionRosterProbeRequest;
import io.mosire.simos.production.contract.ProductionRosterProbeResult;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.production.contract.ProductionStageDefinition;
import io.mosire.simos.production.contract.ProductionStageEndAction;
import io.mosire.simos.production.contract.ProductionStageState;
import io.mosire.simos.production.contract.ProductionTickCondition;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.state.ChangeSet;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;

/**
 * ★★★ <b>生产运行时引擎（{@link ProductionRuntime} 的唯一实现；production 内部类，非契约）</b> （D-032 约束设计书 §4.2/§4.3；★ A
 * 批按复核裁决 §3/§4/§5 修正效率、经验精度与周期贡献）。
 *
 * <p>★★ <b>它是什么</b>：一次 Advance 的<b>本域候选态</b>（{@code current}）加上两步计算： {@link #probe} 无副作用试算、{@link
 * #execute} 按最终投入推进实例并给回执。装配起点 {@code base} 与 当刻 {@code current} 的差就是本域 {@link ChangeSet}（铁律 5；由
 * production 自己派生 —— 边界裁决 §2 末条）。
 *
 * <p>★★ <b>它刻意不接受 economy 的 {@code EconomyData}</b>（§4.2 硬约束）：入口只有 {@link ProductionProbeRequest} /
 * {@link ProductionExecutionRequest} 两种<b>不可变值</b>。 这是"production 不反向读 economy"的机读形态 —— 类型面上就拿不到
 * {@code EconomyData}。
 *
 * <p>★★ <b>预计与实际同源</b>（I6；复核裁决 §3「试算与实际、家户利润比较都读同一份配置与同一入口」）： 两条路径共用 {@link
 * #effectiveOutputPerUnit}（含 GM 覆盖）、{@link ProductionEfficiency}（同一公式、 同一 {@code
 * effectScope}、同一结转余数）、{@link ProductionExperience}（同一增长/衰减）与同一时间单位（天）。
 * 实际值按最终投入重算，<b>可以</b>与预计不同，但<b>不得</b>用预计值顶替。
 *
 * <p>★★ <b>A 批的三条语义修正（复核裁决 §3/§4/§5）</b>：
 *
 * <ol>
 *   <li><b>逐 tick 按实际生产贡献增长经验</b>（§5 第 1 条）：每一个生产日都增长，<b>不</b>等关账日；
 *   <li><b>一次实际劳动只计一次</b>（§5 第 2 条）：经验随每次贡献当场写进账本，关账日只读累计、只算规模、 然后清零周期台账，<b>不</b>重发；
 *   <li><b>整周期逐家户贡献与当时生效效率可重建</b>（§5 第 3/4 条）：{@code cycleContributions} 记录 逐家户实际劳动与"按各 tick
 *       当刻效率<b>精确加权</b>的劳动"，关账日用它算规模（<b>不</b>是 "最后一天劳动 ÷ 周期天数"）。
 * </ol>
 *
 * <p>★★ <b>A′ 批的两条修正（§13.8 裁定一 + 末段）</b>：
 *
 * <ol>
 *   <li><b>效率倍率"约束后整体乘一次"</b>：{@link ProductionEfficiency} 三通道都不乘 E 求 {@code baseScale}， 再 {@code
 *       finalScale = ⌊baseScale × E_cycle ÷ 1000⌋}；投入消耗与占用按 {@code baseScale}、 产出与实毛产按 {@code
 *       finalScale}；
 *   <li><b>经验增量按 家户×经验条 归并并结转余数</b>：同一 tick 内同一 (家户, 经验条) 的贡献先汇总， 再从本 tick 起点的特征<b>一次</b>换算（{@link
 *       ProductionExperience#accrueFrom}）⇒ 归并顺序不影响 结果、同户同条的实际劳动只累加一次；折算余数不再是"每实例一份"（那段已被精确累计取代）。
 * </ol>
 *
 * <p>★ <b>线程与所有权</b>：本类可变（持有候选态）⇒ 一次 Advance 一个实例，由组合根装配并独占使用； 不得跨 Advance 复用（那正是 I13"不得跨 revision
 * 陈旧"的机读形态）。
 */
public final class ProductionRuntimeEngine implements ProductionRuntime {

  private static final Logger LOG = ProductionLog.runtime();

  /** 毫换算：商品单位 → 毫单位（与 {@code EconomySettlement.MILLI_PER_GRAIN} 同一常量语义）。 */
  private static final long MILLI_PER_COMMODITY_UNIT = 1_000L;

  private final ProductionData base;
  private ProductionData current;

  // ── ★★ A 批：同 tick 统一生效条件与重复推进检测（§13.2 第 1 条①② / §13.7 第 1 条）──────────────

  /**
   * <b>本 tick 生效条件</b>（{@code beginTick} 在衰减之后冻结；{@code null} = 还没开启 tick）。
   *
   * <p>★ 它一旦冻结就<b>不</b>随本 tick 后续的 {@code execute} 改（那些执行会改当刻候选态里的经验账，
   * 但本条件里的熟练度不变）——这正是"实例处理先后不改变另一实例本 tick 熟练度"的机制。
   */
  private ProductionTickCondition condition;

  /** 已经开启过、且已经衰减过的那一个 tick（-1 = 尚未开启）。 */
  private long lastAdvancedTick = -1L;

  /** 本 tick 已经执行过的实例（键 = 实例身份；值 = true）——"同一实例同一 tick 重复推进"的检测台账。 */
  private final java.util.Set<ProductionUnitId> advancedThisTick = new java.util.LinkedHashSet<>();

  /** 本 tick 是否已经衰减过（同一 tick 重复衰减必须被拒）。 */
  private boolean decayedThisTick;

  // ── ★★ B1 批：批量驱动口的本 tick 关账读数（§13.2 第 1 条；I12 铸币权威的唯一交接面）──────────────

  /**
   * <b>本 tick 关账的实例 → 产出规模</b>（{@code finalScale}）。
   *
   * <p>★ <b>为什么它是引擎里的一个字段而不是 ChangeSet 的一部分</b>：它是<b>本 tick 的过程读数</b> （economy 的铸币阶段要按"同一个规模"兑现候选产出
   * —— I12），不是持久状态；换一个 tick 就该重来。 放进状态会让"上一轮关账的规模"跨 revision 存活，那正是第二份权威。
   */
  private final Map<ProductionUnitId, Long> closedScalesThisTick = new LinkedHashMap<>();

  /** <b>本 tick 关账的实例 → 实毛产</b>（回执原样；只读交接面）。 */
  private final Map<ProductionUnitId, Map<CommodityId, Long>> closedGrossThisTick =
      new LinkedHashMap<>();

  /** <b>本 tick 关账的实例 → 铸币候选</b>（回执原样；只读交接面）。 */
  private final Map<ProductionUnitId, Map<CurrencyId, Long>> closedMintThisTick =
      new LinkedHashMap<>();

  // ── ★★ A′ 批：同 tick 的"家户×经验条"经验归并账（§13.8 末段 / §13.7 第 1 条 / 复核 §2.1）────────

  /**
   * <b>本 tick 已归并的精确经验增长分子</b>（键 = 家户 × 经验条；值 = 该键在本 tick 累计的“千分之一经验”分子）。
   *
   * <p>★ <b>它是派生账、不是状态</b>（同 {@link #advancedThisTick} 的性质）：归并结果永远由 “本 tick 起点的特征 +
   * 汇总精确分子”算出（{@code ProductionExperience.accrueFromExactNumerator}），故引擎重建后从当刻状态继续累加也得到同一结果。 ★ 开启
   * tick 时清空；未开启 tick（手搭夹具直调）时 <b>一次 execute 自成一窗</b>（见 {@link #execute} 开头）。
   */
  private final Map<HouseholdExperienceKey, Long> pendingExactGrowthThisTick =
      new LinkedHashMap<>();

  /**
   * <b>本 tick 起点（衰减后）的经验特征</b>（键 = 家户 × 经验条；首次触达时登记）。
   *
   * <p>★ 归并换算必须从<b>本 tick 起点</b>算起（而不是"当前特征 + 增量"），这样才能保证 "同户同条只换算一次、且与实例遍历顺序无关"。
   */
  private final Map<HouseholdExperienceKey, ProductionExperienceFeature> tickBaselineExperience =
      new LinkedHashMap<>();

  /**
   * @param base 本次 Advance 的装配起点（不可变快照的本域状态）；不得为 null
   */
  public ProductionRuntimeEngine(ProductionData base) {
    if (base == null) {
      throw new IllegalArgumentException("ProductionRuntimeEngine.base 不得为 null");
    }
    this.base = base;
    this.current = base;
  }

  /** ★ 当刻本域候选态（只读；供组合根/读数用，不得由外部改写）。 */
  public ProductionData state() {
    return current;
  }

  // ── ② a 实例建档 / 退役 / 改写（分配之前的"立起来"；§4.3）──────────────────────────────────

  /**
   * ★★ {@inheritDoc}
   *
   * <p>★★ <b>合并/冲突规则不在这里重写</b>（§13.10.3 明令）：权威合并投影是 {@link
   * io.mosire.simos.production.spi.DefinitionRegistration#registerCreated}（候选态 + 本次按需定义 → 目标态 +
   * 变更集）—— 本方法只做两件事：① 把单条定义包成那次投影的入参；② 把"同 id 不同值"从 {@code IllegalStateException} 翻译成契约故障 {@link
   * ProductionContractFault}（ERROR 不降级：那是 "两个权威对同一方案各说一套"，必须拒绝整个候选提交，而不是业务拒绝）。
   */
  @Override
  public ProductionPlanOutcome registerPlan(IndustryDefinitionView plan) {
    if (plan == null) {
      throw new IllegalArgumentException("ProductionRuntimeEngine.registerPlan 的 plan 不得为 null");
    }
    IndustryDefinitionView existing = current.industries().get(plan.id());
    if (existing != null && existing.equals(plan)) {
      // ★ 幂等 no-op：同 id 逐值相同 ⇒ 不重写、不报冲突（DefinitionRegistration 同款口径）。
      return new ProductionPlanOutcome.Written(existing);
    }
    try {
      io.mosire.simos.production.spi.DefinitionRegistration.Projection projection =
          io.mosire.simos.production.spi.DefinitionRegistration.registerCreated(
              current, Map.of(plan.id(), plan));
      current = projection.projected();
    } catch (IllegalStateException conflict) {
      EventLog.channel(LOG)
          .error(
              LogEvent.of(
                  "PRODUCTION_CONTRACT_FAULT",
                  ProductionLogSource.PRODUCTION_RUNTIME,
                  "reason",
                  "plan-conflict",
                  "plan",
                  plan.id().value(),
                  "detail",
                  "按需登记的方案定义与 production 当刻权威同 id 不同值（拒绝静默覆盖）"));
      throw new ProductionContractFault(
          "plan-conflict",
          "按需登记的方案定义与 production 当刻权威冲突（同 id 不同值）: "
              + plan.id().value()
              + " —— "
              + conflict.getMessage());
    }
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "PRODUCTION_PLAN_REGISTERED",
                ProductionLogSource.PRODUCTION_RUNTIME,
                "plan",
                plan.id().value(),
                "cycleDays",
                plan.cycleDays(),
                "idempotent",
                false));
    return new ProductionPlanOutcome.Written(plan);
  }

  /**
   * ★★ {@inheritDoc}
   *
   * <p>★ 与 {@code execute(creation=...)} 共用同一段建档逻辑（同一条守卫、同一个初始形状），差别只有一处：
   * 本方法<b>不推进进度</b>——它只是把实例立起来，随后本 tick 的 {@link #executeDay} 再让它走第一个生产日。
   */
  @Override
  public ProductionInstanceOutcome createInstance(ProductionCreationRequest request) {
    if (request == null) {
      throw new IllegalArgumentException(
          "ProductionRuntimeEngine.createInstance 的 request 不得为 null");
    }
    if (current.instances().containsKey(request.unit())) {
      // ★ 业务拒绝（INFO）：同一 id 已经是一个实例 ⇒ 不覆盖（那会静默吞掉别人的进度）。
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_INSTANCE_REJECTED",
                  ProductionLogSource.PRODUCTION_RUNTIME,
                  "reason",
                  "unit-conflict",
                  "unit",
                  request.unit().value()));
      return new ProductionInstanceOutcome.Rejected("unit-conflict");
    }
    IndustryDefinitionView planDefinition = current.industries().get(request.plan());
    if (planDefinition == null) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_INSTANCE_REJECTED",
                  ProductionLogSource.PRODUCTION_RUNTIME,
                  "reason",
                  "plan-unknown",
                  "unit",
                  request.unit().value(),
                  "plan",
                  request.plan().value()));
      return new ProductionInstanceOutcome.Rejected("plan-unknown");
    }
    // ★ 方案自带地点 ⇒ 必须与实例地点一致（不一致 = 本域状态自相矛盾，契约故障不降级）。
    requireLocationAgreesWithPlan(request.plan(), planDefinition, request.location());
    ProductionProcess created =
        new ProductionProcess(
            request.unit(),
            request.plan(),
            request.location(),
            request.operator(),
            request.modeKey(),
            0L,
            Map.of(),
            Map.of());
    current = current.withInstance(created);
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "PRODUCTION_INSTANCE_CREATED",
                ProductionLogSource.PRODUCTION_RUNTIME,
                "unit",
                created.id().value(),
                "plan",
                created.plan().value(),
                "location",
                created.location().value(),
                "operator",
                created.operator().id()));
    return new ProductionInstanceOutcome.Written(created);
  }

  /**
   * ★★★ {@inheritDoc}
   *
   * <p>★ <b>D-032 B3（I10）</b>：迁移的"完整携带（含余数、不乘比例）"与合户的"按人口加权平均"都落在这里 —— 算术在 {@link
   * ProductionData#mergeExperienceInto}（纯函数），本方法只做：取人口事实 → 记日志 → 提交到当刻候选态。 ★ 解锁取并集（I7
   * 永久记忆）；源户的行**不删**（那是人口清零后上层的事，不是经验账的事）。
   */
  @Override
  public ProductionExperienceMoveOutcome mergeExperience(
      HouseholdId from, long movedPeople, HouseholdId to, long targetPeople) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (from.equals(to)) {
      // 契约故障（参数自相矛盾），不是业务拒绝：调用方把"同一户"当迁移两端，公式无定义。
      throw new ProductionContractFault(
          "experience-merge-same-household", "mergeExperience 的 from 与 to 不得相同: " + from.value());
    }
    if (movedPeople <= 0L || targetPeople < 0L) {
      throw new ProductionContractFault(
          "experience-merge-bad-population",
          "mergeExperience 的人口参数非法（movedPeople 必须 > 0、targetPeople ≥ 0）: moved="
              + movedPeople
              + " target="
              + targetPeople);
    }
    java.util.List<ExperienceTrackId> sourceTracks = new java.util.ArrayList<>();
    for (HouseholdExperienceKey key : current.householdExperience().keySet()) {
      if (key.household().equals(from)) {
        sourceTracks.add(key.track());
      }
    }
    sourceTracks.sort(java.util.Comparator.comparing(ExperienceTrackId::value));
    java.util.Set<IndustryId> targetUnlocksBefore = current.unlocksOf(to);
    int unlocksAdded = 0;
    for (IndustryId plan : current.unlocksOf(from)) {
      if (!targetUnlocksBefore.contains(plan)) {
        unlocksAdded++;
      }
    }
    ProductionData before = current;
    current = current.mergeExperienceInto(from, movedPeople, to, targetPeople);
    int tracksMerged = 0;
    for (ExperienceTrackId track : sourceTracks) {
      if (!current.experienceFeatureOf(to, track).allZero()) {
        tracksMerged++;
      }
    }
    boolean changed = !current.equals(before);
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "PRODUCTION_EXPERIENCE_MERGED",
                ProductionLogSource.PRODUCTION_RUNTIME,
                "from",
                from.value(),
                "to",
                to.value(),
                "movedPeople",
                movedPeople,
                "targetPeople",
                targetPeople,
                "sourceTracks",
                sourceTracks.size(),
                "tracksMerged",
                tracksMerged,
                "unlocksAdded",
                unlocksAdded,
                "changed",
                changed,
                "note",
                "迁移完整携带（主值+余数、不乘比例）+ 合户按人口加权平均（定点精确）；解锁并集（I7 永久记忆）"));
    return new ProductionExperienceMoveOutcome(
        from, movedPeople, to, targetPeople, tracksMerged, unlocksAdded, changed);
  }

  /** ★★ {@inheritDoc} */
  @Override
  public boolean removeInstance(ProductionUnitId unit) {
    if (unit == null) {
      throw new IllegalArgumentException("ProductionRuntimeEngine.removeInstance 的 unit 不得为 null");
    }
    if (!current.instances().containsKey(unit)) {
      return false;
    }
    current = current.withoutInstance(unit);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "PRODUCTION_INSTANCE_RETIRED",
                  ProductionLogSource.PRODUCTION_RUNTIME,
                  "unit",
                  unit.value()));
    }
    return true;
  }

  /**
   * ★★ {@inheritDoc}
   *
   * <p>★ <b>只换身份位</b>（经营者 / 生产方式键），进度、周期台账、效率余数一律原样带过 —— 改经营者不是 重开一个周期。
   */
  @Override
  public ProductionInstanceOutcome reoperateInstance(
      ProductionUnitId unit, io.mosire.simos.actor.api.actor.ActorRef operator, String modeKey) {
    if (unit == null) {
      throw new IllegalArgumentException(
          "ProductionRuntimeEngine.reoperateInstance 的 unit 不得为 null");
    }
    ProductionProcess existing = current.instances().get(unit);
    if (existing == null) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_INSTANCE_REJECTED",
                  ProductionLogSource.PRODUCTION_RUNTIME,
                  "reason",
                  "instance-unknown",
                  "unit",
                  unit.value()));
      return new ProductionInstanceOutcome.Rejected("instance-unknown");
    }
    ProductionProcess rewritten = existing.withIdentity(operator, modeKey);
    current = current.withInstance(rewritten);
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "PRODUCTION_INSTANCE_REOPERATED",
                ProductionLogSource.PRODUCTION_RUNTIME,
                "unit",
                rewritten.id().value(),
                "operator",
                operator.id(),
                "modeKey",
                modeKey));
    return new ProductionInstanceOutcome.Written(rewritten);
  }

  /**
   * ★★★ {@inheritDoc}
   *
   * <p>★ <b>RC4-B 只改本实例的周期贡献台账</b>：把 {@code from} 户的逐 role 项搬到 {@code to} 户；同 role 精确相加、不同 role
   * 分行，其余字段逐字带过。没有 source 项 ⇒ 幂等 no-op（连候选态都不重写）。
   */
  @Override
  public ProductionInstanceOutcome rekeyCycleContributions(
      ProductionUnitId unit, HouseholdId from, HouseholdId to) {
    if (unit == null) {
      throw new IllegalArgumentException(
          "ProductionRuntimeEngine.rekeyCycleContributions 的 unit 不得为 null");
    }
    Objects.requireNonNull(from, "ProductionRuntimeEngine.rekeyCycleContributions 的 from 不得为 null");
    Objects.requireNonNull(to, "ProductionRuntimeEngine.rekeyCycleContributions 的 to 不得为 null");
    ProductionProcess existing = current.instances().get(unit);
    if (existing == null) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_INSTANCE_REJECTED",
                  ProductionLogSource.PRODUCTION_RUNTIME,
                  "reason",
                  "instance-unknown",
                  "unit",
                  unit.value(),
                  "from",
                  from.value(),
                  "to",
                  to.value()));
      return new ProductionInstanceOutcome.Rejected("instance-unknown");
    }
    if (from.equals(to)) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_INSTANCE_REJECTED",
                  ProductionLogSource.PRODUCTION_RUNTIME,
                  "reason",
                  "same-household",
                  "unit",
                  unit.value(),
                  "household",
                  from.value()));
      return new ProductionInstanceOutcome.Rejected("same-household");
    }

    Map<ProductionParticipantKey, CycleContribution> original = existing.cycleContributions();
    boolean hasSource = false;
    for (ProductionParticipantKey key : original.keySet()) {
      if (key.household().equals(from)) {
        hasSource = true;
        break;
      }
    }
    if (!hasSource) {
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "PRODUCTION_CYCLE_CONTRIBUTIONS_REKEY_NOOP",
                    ProductionLogSource.PRODUCTION_RUNTIME,
                    "unit",
                    unit.value(),
                    "from",
                    from.value(),
                    "to",
                    to.value(),
                    "entries",
                    original.size()));
      }
      return new ProductionInstanceOutcome.Written(existing);
    }

    // ★ 保序不可变：LinkedHashMap 拷贝 + 按输入序处理；非 source 项原样保留，同 role 目标项用 plus
    //   （内部 Math.addExact）精确累计。源键先删、目标键后落 ⇒ 输出由输入序唯一决定。
    Map<ProductionParticipantKey, CycleContribution> next = new LinkedHashMap<>(original);
    int movedEntries = 0;
    int mergedEntries = 0;
    for (Map.Entry<ProductionParticipantKey, CycleContribution> entry : original.entrySet()) {
      ProductionParticipantKey sourceKey = entry.getKey();
      if (!sourceKey.household().equals(from)) {
        continue;
      }
      CycleContribution source = entry.getValue();
      ProductionParticipantKey targetKey = new ProductionParticipantKey(to, sourceKey.role());
      next.remove(sourceKey);
      CycleContribution targetBefore = next.get(targetKey);
      CycleContribution targetAfter;
      boolean mergedIntoExisting = targetBefore != null;
      if (mergedIntoExisting) {
        targetAfter = targetBefore.plus(source.laborMilli(), source.efficiencyWeightedLaborMilli());
        mergedEntries++;
      } else {
        targetAfter =
            new CycleContribution(
                to, sourceKey.role(), source.laborMilli(), source.efficiencyWeightedLaborMilli());
      }
      next.put(targetKey, targetAfter);
      movedEntries++;
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "PRODUCTION_CYCLE_CONTRIBUTIONS_REKEY_ROLE",
                    ProductionLogSource.PRODUCTION_RUNTIME,
                    "unit",
                    unit.value(),
                    "from",
                    from.value(),
                    "to",
                    to.value(),
                    "role",
                    sourceKey.role().value(),
                    "laborMilli",
                    source.laborMilli(),
                    "efficiencyWeightedLaborMilli",
                    source.efficiencyWeightedLaborMilli(),
                    "mergedIntoExisting",
                    mergedIntoExisting,
                    "targetLaborMilli",
                    targetAfter.laborMilli(),
                    "targetEfficiencyWeightedLaborMilli",
                    targetAfter.efficiencyWeightedLaborMilli()));
      }
    }

    ProductionProcess rewritten = existing.withCycleContributions(next);
    current = current.withInstance(rewritten);
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "PRODUCTION_CYCLE_CONTRIBUTIONS_REKEYED",
                ProductionLogSource.PRODUCTION_RUNTIME,
                "unit",
                unit.value(),
                "from",
                from.value(),
                "to",
                to.value(),
                "movedEntries",
                movedEntries,
                "mergedEntries",
                mergedEntries,
                "changed",
                true));
    return new ProductionInstanceOutcome.Written(rewritten);
  }

  // ── ① 试算（无副作用）────────────────────────────────────────────────────────────────

  @Override
  public ProductionProbeResult probe(ProductionProbeRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("ProductionRuntimeEngine.probe 的 request 不得为 null");
    }
    IndustryId planId = request.plan();
    IndustryDefinitionView plan = current.industries().get(planId);
    if (plan == null) {
      return ProductionProbeResult.rejected(planId, "plan-unknown");
    }
    HouseholdProductionConditions household = request.household();

    // ★★ R3（§15.3）：使用资格（可用性条件 + 解锁资格）与 {@link #execute} 共用<b>同一个</b>求值入口 ——
    //   "试算被拒的方案，实际执行也必须被拒"，两处不得各拼一套判据。★ 主体的经验事实由调用方给的函数提供：
    //   probe 读本次请求里的家户条件（无副作用、可复现），execute 读本域经验账。
    String qualificationFailure =
        qualificationFailure(
            plan, household.household(), household::experienceOf, request.attributes());
    if (qualificationFailure != null) {
      return ProductionProbeResult.rejected(planId, qualificationFailure);
    }

    // ★ 熟练度 → 效率倍率（与 execute 同一函数、同一口径；公式求值失败 ⇒ 契约故障）。
    long efficiency = efficiencyScaleFor(plan, household.household(), household);

    // ★ 四路约束（与 execute 共用 ProductionEfficiency 的那一段公式）。
    long assetScaleRaw =
        capacityScaleOf(plan, request.assetOccupancy(), request.durableCommodityOccupancy());
    boolean assetsAvailable =
        !plan.capacityPerUnit().isEmpty() && !request.assetOccupancy().isEmpty();
    if (assetsAvailable && assetScaleRaw <= 0L) {
      return ProductionProbeResult.rejected(planId, "asset-short");
    }
    Map<CommodityId, Long> inputPerUnit = plan.recipe().inputPerUnit();
    long inputScale = Long.MAX_VALUE;
    CommodityId bindingInput = null;
    for (Map.Entry<CommodityId, Long> entry : inputPerUnit.entrySet()) {
      if (entry.getValue() == null || entry.getValue() <= 0L) {
        continue;
      }
      long available = household.inputOf(entry.getKey());
      long route = Math.floorDiv(available, entry.getValue());
      if (route < inputScale) {
        inputScale = route;
        bindingInput = entry.getKey();
      }
    }
    if (inputScale == 0L) {
      return ProductionProbeResult.rejected(
          planId, "input-short" + (bindingInput == null ? "" : ":" + bindingInput.value()));
    }

    // ★★ 与 execute 同一个入口：单家户的"当 tick 加权劳动"就是"劳动 × 当刻效率"的<b>精确乘积</b>
    //    （probe 只有一天的量，故不除法；余额按实例已结转的余数接入 ⇒ 同一份配置、同一入口、同一余数）。
    long weightedLabor = Math.multiplyExact(household.laborMilli(), efficiency);
    Map<HouseholdId, Long> weightedByHousehold = Map.of(household.household(), weightedLabor);
    Map<HouseholdId, Long> laborByHousehold = Map.of(household.household(), household.laborMilli());
    ProductionEfficiency.Evaluation evaluation =
        ProductionEfficiency.evaluate(
            request.carry().orElse(null),
            plan.experience().effectScope(),
            plan.cycleDays(),
            weightedByHousehold,
            laborByHousehold,
            assetScaleRaw,
            inputPerUnit,
            household.inputs(),
            plan.laborPerUnit(),
            Long.MAX_VALUE); // 试算不设授予上限：上限是 economy 分配阶段的预算，不是方案的性质

    if (plan.laborPerUnit() > 0L && evaluation.baseLaborScale() <= 0L) {
      return ProductionProbeResult.rejected(planId, "labor-short");
    }
    if (assetsAvailable && evaluation.assetScale() <= 0L) {
      return ProductionProbeResult.rejected(planId, "asset-short");
    }
    // ★★ B11（DEF-T5-4）：这里原本还有一道 `if (evaluation.inputScale() == 0L && inputScale == 0L)` 的
    //   input-short 兜底 —— 它在**全部可达输入上恒为假**，属死分支，本批移除。SpotBugs 的
    //   UC_USELESS_CONDITION "it's known that inputScale != 0 at this point" 是**真阳性**（不是误报），
    //   处置 = 删掉不可达的重复判断，**不是**放宽或删除任何校验。
    //   ★ 证明（两路的 inputScale 是**同一个函数**，不是两份口径）：上面 `inputScale` = min_j ⌊可用_j ÷ 每单位_j⌋
    //     （j 取 perUnit > 0 者），ProductionEfficiency 里的 `inputScale` = min_j ⌊drawn_j ÷ 每单位_j⌋ ——
    //     同一张 inputPerUnit、同一张 household.inputs()（本方法正是把 household.inputs() 当 cycleInputUsedMilli
    //     传进去）。⇒ 有 perUnit > 0 时两者逐值相等：
    //       ① 相等且 = 0 ⇒ 上面 bindingInput 那道守卫已经 return，走不到这里；
    //       ② 相等且 > 0 ⇒ 合取项 `inputScale == 0L` 为假 ⇒ 恒不进入；
    //       ③ 没有 perUnit > 0（方案本就不吃原料）⇒ `inputScale` 停在 Long.MAX_VALUE（≠ 0），而
    //          evaluation 的 effectiveInputScale 归 0 ⇒ 合取仍为假。
    //   ★★ 因此**不得**把它"修"成 `if (evaluation.inputScale() == 0L)`：那会让**每一个不吃原料的方案**都被判
    //     `input-short`（情形 ③ 立刻命中），是行为回归。原料不足的唯一判定点 = 上面按 bindingInput 给具名理由的
    //     那道守卫（覆盖情形 ①，且消息里带得住是哪一种料）。

    // ★★ A′ 批（§13.8 裁定一）：投入消耗与占用按 <b>baseScale</b>（三通道都不乘 E 的基准规模），
    //    产出/预计产出按 <b>finalScale</b>（基准规模整体乘一次效率倍率）——同样的投入产出更多。
    long baseScale = Math.max(0L, evaluation.baseScale());
    long finalScale = Math.max(0L, evaluation.finalScale());
    Map<AssetKind, Long> assetRequired = requiredAssets(plan, baseScale);
    Map<CommodityId, Long> inputRequired = requiredInputs(plan, baseScale);
    Map<CommodityId, Long> expectedOutput = grossOutput(plan, finalScale);
    Map<CurrencyId, Long> expectedMint = mintCandidates(plan, finalScale);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "PRODUCTION_PROBED",
                  ProductionLogSource.PRODUCTION_RUNTIME,
                  "plan",
                  planId.value(),
                  "household",
                  household.household().value(),
                  "baseScale",
                  baseScale,
                  "finalScale",
                  finalScale,
                  "efficiencyPerMille",
                  evaluation.efficiencyScalePerMille(),
                  "effectScope",
                  plan.experience().effectScope().key()));
    }
    return new ProductionProbeResult(
        planId,
        true,
        null,
        finalScale,
        laborRequiredFor(plan, evaluation, baseScale),
        assetRequired,
        inputRequired,
        expectedOutput,
        expectedMint,
        evaluation.efficiencyScalePerMille());
  }

  // ── ①b roster 试算（D-032 P3a；A2/A3/A4 的只读契约面）──────────────────────────────────────

  /**
   * ★★★ <b>{@link #probeRoster}：协作名册的试算（纯增量；单户 {@link #probe} 逐值不变）</b>。
   *
   * <p>★★ <b>公式复用（A2 的关键）</b>：本方法<b>不</b>自带任何效率/规模公式，逐户效率仍走 {@link #efficiencyScaleFor}，规模仍走 {@link
   * ProductionEfficiency#evaluate}，需求/产出仍走 {@link #requiredAssets}/{@link #requiredInputs}/{@link
   * #grossOutput}/{@link #mintCandidates}/ {@link #laborRequiredFor} —— 与 {@link #probe} 和 {@link
   * #execute} 是<b>同一批函数</b>。 「预计/实际同源」因此在结构上成立，而不是靠两处数字凑巧相等。
   *
   * <p>★★ <b>逐参与户效率与 execute 同源</b>：execute 对每个实际贡献家户调 {@code efficiencyScaleFor(plan, household,
   * null)} （读本 tick 冻结条件）；本方法对每个参与户调 {@code efficiencyScaleFor(plan, household,
   * conditions.get(household))} （读调用方给的同 tick 条件事实）。两者是同一函数的两个数据来源分支（R5/R6 已把"试算读请求条件、执行读冻结条件"
   * 钉成同一口径），故按同一份 tick 事实构造输入时逐值一致。
   *
   * <p>★★ <b>A3（零劳动 organizer + 工资招工）</b>：工资角色允许没有具名参与者。目标劳动按 {@link
   * ProductionRosterProbeRequest#wageTargetLaborMilliPerDay()}（economy 的 posting target 投影）或方案角色的
   * {@link ProductionRoleDefinition#standardLaborMilliPerCycle()} 取需求边界；已有正计划劳动参与项只补差额。★ 目标劳动
   * <b>不</b>伪造 worker、<b>不</b>写进 {@code participantEfficiencyPerMille}；它没有工人经验事实，故只按中性 1000‰ 参与
   * {@code ProductionEfficiency.evaluate} 的代表效率加权（见其结果类注）。
   *
   * <p>★★ <b>聚合口径</b>：同一户多角色只把劳动累加进"逐户劳动"，原料按户各读一次后累加；资产占用取请求级 {@link
   * ProductionRosterProbeRequest#assetOccupancy()}（它就是统一分配后的实占读数，不再从各户 conditions 拼可用资产）。
   *
   * <p>★ <b>无副作用</b>（I5）：只读 {@code current} 与请求值，不写实例/经验/进度/占用。
   */
  @Override
  public ProductionRosterProbeResult probeRoster(ProductionRosterProbeRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("ProductionRuntimeEngine.probeRoster 的 request 不得为 null");
    }
    IndustryId planId = request.plan();
    IndustryDefinitionView plan = current.industries().get(planId);
    if (plan == null) {
      return ProductionRosterProbeResult.rejected(planId, "plan-unknown");
    }
    List<ProductionRosterProbeParticipant> participants = request.participants();
    if (participants.isEmpty()) {
      return ProductionRosterProbeResult.rejected(planId, "roster-empty");
    }
    Map<ProductionRoleId, ProductionRoleDefinition> rolesById = rolesById(plan);
    Map<HouseholdId, HouseholdProductionConditions> conditions = request.conditions();
    Map<HouseholdId, ExternalAttributeFacts> participantFacts = request.participantAttributes();

    // ── 组织者必须出现在 roster 里（名册身份是 economy 的事实；production 只按它判计划级资格主体）──────
    boolean organizerParticipates = false;
    for (ProductionRosterProbeParticipant participant : participants) {
      if (participant.household().equals(request.organizer())) {
        organizerParticipates = true;
        break;
      }
    }
    if (!organizerParticipates) {
      return ProductionRosterProbeResult.rejected(planId, "organizer-not-participant");
    }
    HouseholdProductionConditions organizerConditions = conditions.get(request.organizer());
    if (organizerConditions == null) {
      return ProductionRosterProbeResult.rejected(
          planId, "condition-missing:" + request.organizer().value());
    }

    // ── 逐参与项：角色存在性 + 条件存在性 + 角色级资格（只对本周期真有劳动的参与项判，与 execute 同面）──
    for (ProductionRosterProbeParticipant participant : participants) {
      ProductionRoleDefinition role = rolesById.get(participant.role());
      if (role == null) {
        return ProductionRosterProbeResult.rejected(
            planId, "role-unknown:" + participant.role().value());
      }
      HouseholdProductionConditions condition = conditions.get(participant.household());
      if (condition == null) {
        return ProductionRosterProbeResult.rejected(
            planId, "condition-missing:" + participant.household().value());
      }
      if (participant.plannedLaborMilliPerCycle() <= 0L) {
        continue; // 零劳动参与项（如只组织/收剩余的 organizer）不构成 execute 的 labor contribution
      }
      ExternalAttributeFacts facts =
          participantFacts.getOrDefault(participant.household(), ExternalAttributeFacts.EMPTY);
      String roleFailure =
          roleQualificationFailure(
              plan, role, participant.household(), condition::experienceOf, facts);
      if (roleFailure != null) {
        return ProductionRosterProbeResult.rejected(
            planId,
            "role-qualification:"
                + role.id().value()
                + ":"
                + participant.household().value()
                + ":"
                + roleFailure);
      }
    }

    // ── 计划级资格：主体 = organizer（与 probe/execute 共用同一个 qualificationFailure）──────────────
    String qualificationFailure =
        qualificationFailure(
            plan,
            request.organizer(),
            organizerConditions::experienceOf,
            participantFacts.getOrDefault(request.organizer(), ExternalAttributeFacts.EMPTY));
    if (qualificationFailure != null) {
      return ProductionRosterProbeResult.rejected(planId, qualificationFailure);
    }

    // ── A2：逐 (家户×角色) 效率 = 与 execute 同一个 efficiencyScaleFor（conditions 分支）────────────
    Map<ProductionParticipantKey, Long> participantEfficiency = new LinkedHashMap<>();
    Map<HouseholdId, Long> efficiencyByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, Long> laborPerCycleByHousehold = new LinkedHashMap<>();
    Map<ProductionRoleId, Long> plannedLaborByRole = new LinkedHashMap<>();
    for (ProductionRosterProbeParticipant participant : participants) {
      HouseholdProductionConditions condition = conditions.get(participant.household());
      long efficiency = efficiencyScaleFor(plan, participant.household(), condition);
      participantEfficiency.put(participant.participantKey(), efficiency);
      efficiencyByHousehold.put(participant.household(), efficiency);
      laborPerCycleByHousehold.merge(
          participant.household(), participant.plannedLaborMilliPerCycle(), Math::addExact);
      plannedLaborByRole.merge(
          participant.role(), participant.plannedLaborMilliPerCycle(), Math::addExact);
    }

    // ── A3：工资招工目标（只对 MONEY_WAGE 角色；只补"尚无具名参与劳动"的差额）────────────────────
    for (Map.Entry<ProductionRoleId, Long> target :
        request.wageTargetLaborMilliPerDay().entrySet()) {
      ProductionRoleDefinition role = rolesById.get(target.getKey());
      if (role == null) {
        return ProductionRosterProbeResult.rejected(
            planId, "role-unknown:" + target.getKey().value());
      }
      if (!(role.payment() instanceof RolePayment.MoneyWage)) {
        return ProductionRosterProbeResult.rejected(
            planId, "wage-target-role-not-money-wage:" + role.id().value());
      }
    }
    long targetLaborPerCycle = 0L;
    for (ProductionRoleDefinition role : plan.roles()) {
      if (!(role.payment() instanceof RolePayment.MoneyWage)) {
        continue;
      }
      Long explicitTargetPerDay = request.wageTargetLaborMilliPerDay().get(role.id());
      long targetPerCycle =
          explicitTargetPerDay != null
              ? Math.multiplyExact(explicitTargetPerDay, plan.cycleDays())
              : Math.max(0L, role.standardLaborMilliPerCycle());
      if (targetPerCycle <= 0L) {
        continue;
      }
      long plannedForRole = plannedLaborByRole.getOrDefault(role.id(), 0L);
      long missing = Math.max(0L, targetPerCycle - plannedForRole);
      targetLaborPerCycle = Math.addExact(targetLaborPerCycle, missing);
    }

    // ── 逐户聚合投入（同户多角色只读一次；资产占用按请求级实占读数）──────────────────────────────
    Map<CommodityId, Long> aggregatedInputs = new LinkedHashMap<>();
    for (HouseholdId household : laborPerCycleByHousehold.keySet()) {
      HouseholdProductionConditions condition = conditions.get(household);
      for (Map.Entry<CommodityId, Long> entry : condition.inputs().entrySet()) {
        aggregatedInputs.merge(entry.getKey(), entry.getValue(), Math::addExact);
      }
    }

    // ── 与单户 probe 相同的三通道预检（资产/原料）；劳动由整份 roster + 招工目标共同决定 ─────────────
    long assetScaleRaw =
        capacityScaleOf(plan, request.assetOccupancy(), request.durableCommodityOccupancy());
    boolean assetsAvailable =
        !plan.capacityPerUnit().isEmpty() && !request.assetOccupancy().isEmpty();
    if (assetsAvailable && assetScaleRaw <= 0L) {
      return ProductionRosterProbeResult.rejected(planId, "asset-short");
    }
    Map<CommodityId, Long> inputPerUnit = plan.recipe().inputPerUnit();
    long inputScale = Long.MAX_VALUE;
    CommodityId bindingInput = null;
    for (Map.Entry<CommodityId, Long> entry : inputPerUnit.entrySet()) {
      if (entry.getValue() == null || entry.getValue() <= 0L) {
        continue;
      }
      long available = aggregatedInputs.getOrDefault(entry.getKey(), 0L);
      long route = Math.floorDiv(available, entry.getValue());
      if (route < inputScale) {
        inputScale = route;
        bindingInput = entry.getKey();
      }
    }
    if (inputScale == 0L) {
      return ProductionRosterProbeResult.rejected(
          planId, "input-short" + (bindingInput == null ? "" : ":" + bindingInput.value()));
    }

    long totalParticipantLaborPerCycle = 0L;
    for (long labor : laborPerCycleByHousehold.values()) {
      totalParticipantLaborPerCycle = Math.addExact(totalParticipantLaborPerCycle, labor);
    }
    long totalLaborPerCycle = Math.addExact(totalParticipantLaborPerCycle, targetLaborPerCycle);
    if (plan.laborPerUnit() > 0L && totalLaborPerCycle <= 0L) {
      // ★ A3：零劳动 organizer 只有在"确实没有招工目标"时才判 labor-short；目标 > 0 ⇒ 继续按目标求规模。
      return ProductionRosterProbeResult.rejected(planId, "labor-short");
    }

    // ── 逐户加权劳动（与 execute 同式：labor × 该户当 tick 效率）────────────────────────────────
    Map<HouseholdId, Long> laborByHousehold = new LinkedHashMap<>(laborPerCycleByHousehold);
    Map<HouseholdId, Long> weightedLaborByHousehold = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, Long> entry : laborPerCycleByHousehold.entrySet()) {
      long efficiency = efficiencyByHousehold.getOrDefault(entry.getKey(), 0L);
      weightedLaborByHousehold.put(
          entry.getKey(), Math.multiplyExact(entry.getValue(), efficiency));
    }
    if (targetLaborPerCycle > 0L) {
      // ★ 目标劳动没有具名参与户 ⇒ 无工人经验事实可读。把它的规模贡献并入 organizer 的<b>聚合</b>键（evaluate 只做求和），
      //   权重按中性效率 1000‰ —— 不伪造 worker 参与项，也不把 organizer 的效率冒充成雇工效率。
      laborByHousehold.merge(request.organizer(), targetLaborPerCycle, Math::addExact);
      weightedLaborByHousehold.merge(
          request.organizer(),
          Math.multiplyExact(
              targetLaborPerCycle, ProductionEfficiency.NEUTRAL_EFFICIENCY_PER_MILLE),
          Math::addExact);
    }

    // ── 与单户 probe / execute 同一个 ProductionEfficiency 入口（同一余数、同一 effectScope、同一时间单位）──
    ProductionEfficiency.Evaluation evaluation =
        ProductionEfficiency.evaluate(
            request.carry().orElse(null),
            plan.experience().effectScope(),
            plan.cycleDays(),
            weightedLaborByHousehold,
            laborByHousehold,
            assetScaleRaw,
            inputPerUnit,
            aggregatedInputs,
            plan.laborPerUnit(),
            Long.MAX_VALUE); // 试算不设授予上限：上限是 economy 分配阶段的预算，不是方案的性质

    if (plan.laborPerUnit() > 0L && evaluation.baseLaborScale() <= 0L) {
      return ProductionRosterProbeResult.rejected(planId, "labor-short");
    }
    if (assetsAvailable && evaluation.assetScale() <= 0L) {
      return ProductionRosterProbeResult.rejected(planId, "asset-short");
    }

    long baseScale = Math.max(0L, evaluation.baseScale());
    long finalScale = Math.max(0L, evaluation.finalScale());
    Map<AssetKind, Long> assetRequired = requiredAssets(plan, baseScale);
    Map<CommodityId, Long> inputRequired = requiredInputs(plan, baseScale);
    Map<CommodityId, Long> expectedOutput = grossOutput(plan, finalScale);
    Map<CurrencyId, Long> expectedMint = mintCandidates(plan, finalScale);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "PRODUCTION_ROSTER_PROBED",
                  ProductionLogSource.PRODUCTION_RUNTIME,
                  "plan",
                  planId.value(),
                  "organizer",
                  request.organizer().value(),
                  "participants",
                  participants.size(),
                  "targetLaborPerCycle",
                  targetLaborPerCycle,
                  "baseScale",
                  baseScale,
                  "finalScale",
                  finalScale,
                  "efficiencyPerMille",
                  evaluation.efficiencyScalePerMille(),
                  "effectScope",
                  plan.experience().effectScope().key()));
    }
    return new ProductionRosterProbeResult(
        planId,
        true,
        null,
        baseScale,
        finalScale,
        laborRequiredFor(plan, evaluation, baseScale),
        assetRequired,
        inputRequired,
        expectedOutput,
        expectedMint,
        evaluation.efficiencyScalePerMille(),
        participantEfficiency);
  }

  /**
   * ★★★ <b>{@code probe.laborRequiredMilli} 的口径（§13.9 裁定四）</b>。
   *
   * <p>★★ <b>为什么不能一律给 {@code baseScale × laborPerUnit}</b>：`*Required` 是<b>经济侧分配用的口径</b>
   * ——它必须是"达到该规模<b>实际要投入的量</b>"。而 {@link ProductionEffectScope#LABOR} 声明下加成发生在 劳动通道<b>之内</b>（{@code
   * baseScale} 已经含 E 折算）⇒ 原始劳动需求必须按 E <b>折回</b>：
   *
   * <pre>
   *   LABOR：laborRequiredMilli = ⌈ baseScale × laborPerUnit × 1000 ÷ E_cycle ⌉   // 向上取整以免欠投
   *   TOTAL：laborRequiredMilli =   baseScale × laborPerUnit                      // 加成作用于产出，不减投入
   * </pre>
   *
   * <p>★★ <b>由此成立的契约不变式</b>（进测试判据）：<b>凡 {@code probe} 判可行， {@code laborRequiredMilli ≤
   * 该家户可用劳动}</b>。旧口径在 LABOR 下会报出比可用劳动<b>更大</b>的需求 ⇒ 经济侧会误判不可行或超发劳动。★ 不得靠"另给一个有效劳动读数字段"绕过。
   *
   * <p>★ <b>为什么用 ⌈⌉ 而不是 ⌊⌋</b>：向上取整保证"按这个需求投满就一定能达到 baseScale"（欠投会让
   * 规模差一个单位）；反之累积的正零头不会凭空多占劳动（多出来的那一点只会让规模更宽裕，而规模仍受 其余两通道与授予上限约束）。{@code E_cycle ≤ 0}
   * 是退化输入（契约上倍率非负；0 = 劳动通道不产任何规模） ⇒ 需求给 0（"不需要投劳动"），不是故障。
   */
  private static long laborRequiredFor(
      IndustryDefinitionView plan, ProductionEfficiency.Evaluation evaluation, long baseScale) {
    long base = Math.multiplyExact(baseScale, plan.laborPerUnit());
    if (plan.experience().effectScope().scalesWholeBaseScale()) {
      return base;
    }
    long efficiency = evaluation.efficiencyScalePerMille();
    if (efficiency <= 0L) {
      return 0L;
    }
    // ⌈base × 1000 ÷ E⌉ —— 用 (分子 + 分母 - 1) ÷ 分母，避免浮点与 Math.ceilDiv 的版本依赖。
    long numerator = Math.multiplyExact(base, 1_000L);
    return Math.floorDiv(Math.addExact(numerator, efficiency - 1L), efficiency);
  }

  // ── ② b 批量驱动（一个生产日的全部实例；§13.2 第 1 条）──────────────────────────────────

  /**
   * ★★★ {@inheritDoc}
   *
   * <p>★★ <b>它就是"对每一步各调一次 {@link #execute}"</b>（同语义、同公式、同回执）；本方法只做三件 批次级的事：
   *
   * <ol>
   *   <li>按 {@link ProductionDayRequest#steps()} 的<b>给定序</b>逐步执行（不重排）；
   *   <li><b>业务拒绝</b>不短路、逐项具名收进 {@link ProductionDayResult#rejections()}； <b>契约故障</b>（含 {@code
   *       instance-advanced-twice-in-tick}）由 {@link #execute} 直接抛 ⇒ 本次候选计算终止、整个相关 Advance 被拒（§13.7
   *       第 2 条：禁止吞错继续）；
   *   <li>把本批关账的规模/实毛产/铸币候选收成只读交接面（I12：economy 的铸币阶段按<b>同一个</b>规模兑现）。
   * </ol>
   *
   * <p>★ <b>先探后执行的一步也不跳过</b>：本方法<b>不</b>预判可行性（那会造出第二条业务规则）， 一切判定都发生在 {@link #execute}
   * 里那唯一的入口上；被拒的步<b>零残留</b>（这是 {@code execute} 既有契约的一部分：它在改任何状态之前就返回拒绝）。
   */
  @Override
  public ProductionDayResult executeDay(ProductionDayRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("ProductionRuntimeEngine.executeDay 的 request 不得为 null");
    }
    List<ProductionReceipt> receipts = new ArrayList<>();
    Map<ProductionUnitId, String> rejections = new LinkedHashMap<>();
    Map<ProductionUnitId, Long> closedScales = new LinkedHashMap<>();
    Map<ProductionUnitId, Map<CommodityId, Long>> closedGross = new LinkedHashMap<>();
    Map<ProductionUnitId, Map<CurrencyId, Long>> closedMint = new LinkedHashMap<>();
    for (ProductionDayRequest.Step step : request.steps()) {
      ProductionUnitId unit = step.unitId();
      Optional<String> skipReason = skipStep(unit);
      if (skipReason.isPresent()) {
        // ★ 已经推进过 / 实例不存在：**不调用** execute（否则会撞上"同 tick 重复推进"的契约故障，
        //   或者造出一条新的业务规则），直接按 skipStep 给出的**具名理由**记一笔。★ 它零残留：本方法一个字节都没改。
        rejections.put(unit, skipReason.get());
        continue;
      }
      ProductionExecutionOutcome outcome = execute(step.toExecutionRequest());
      if (outcome instanceof ProductionExecutionOutcome.Rejected rejected) {
        // ★ 业务拒绝：逐项具名、零残留、不短路（世界照常推进）。
        rejections.put(unit, rejected.reason());
        continue;
      }
      ProductionReceipt receipt = ((ProductionExecutionOutcome.Executed) outcome).receipt();
      receipts.add(receipt);
      if (receipt.cycleClosed()) {
        closedScales.put(unit, receipt.scale());
        closedGross.put(unit, receipt.grossOutput());
        closedMint.put(unit, receipt.mintCandidates());
      }
    }
    closedScalesThisTick.putAll(closedScales);
    closedGrossThisTick.putAll(closedGross);
    closedMintThisTick.putAll(closedMint);
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "PRODUCTION_DAY_DRIVEN",
                ProductionLogSource.PRODUCTION_RUNTIME,
                "tick",
                lastAdvancedTick,
                "steps",
                request.steps().size(),
                "executed",
                receipts.size(),
                "rejected",
                rejections.size(),
                "closed",
                closedScales.size()));
    return new ProductionDayResult(
        List.copyOf(receipts), rejections, closedScales, closedGross, closedMint);
  }

  /**
   * ★ <b>本步该以哪条具名业务理由跳过</b>（返回非空 ⇒ 不调 {@code execute}，而把这条理由记进 {@link
   * ProductionDayResult#rejections()}）；{@code Optional.empty()} ⇒ 本步必须真的走 {@code execute}。
   *
   * <p>★ <b>两种情形</b>：① 本 tick 已经推进过它（重复步 —— 检测点其实在 {@code execute} 里，这里只是<b>不制造</b>
   * 那次故障：同一份请求里出现两次同一实例是 economy 排重没做好，按业务拒绝记账比炸掉整次 Advance 更合适）⇒ 具名理由 {@code
   * not-advanced-this-day}； ② 实例不存在 ⇒ 用 {@code execute} 遇到同一情形时会给的**同一条**具名理由 {@code
   * instance-unknown}（前置判只是免得白调一次）。
   *
   * <p>★★ <b>③ 实例引用的方案定义不存在<b>不</b>在这里处理</b>（DEF-T2a-1）：那是本域状态自相矛盾的<b>跨域不一致</b>， 必须由 {@code
   * execute} 当场抛具名契约故障 {@code instance-references-unknown-plan} ⇒ 本次候选计算终止、整个相关 Advance 被拒（I15 /
   * §13.2 第 1 条④：不得降级成"这一步没做"的逐项业务拒绝）。
   */
  private Optional<String> skipStep(ProductionUnitId unit) {
    if (condition != null && advancedThisTick.contains(unit)) {
      return Optional.of("not-advanced-this-day");
    }
    if (current.instances().get(unit) == null) {
      // ★ 与 execute 的"实例不存在"同一条具名理由（DEF-T2a-4：注释与实际必须一致）。
      return Optional.of("instance-unknown");
    }
    return Optional.empty();
  }

  // ── ★★ B1 批：本 tick 关账读数（I12 铸币权威的唯一交接面）──────────────────────────────

  /** ★ 本 tick 关账的实例 → 产出规模（只读快照；未关账的不在表里）。 */
  public Map<ProductionUnitId, Long> closedScalesThisTick() {
    return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(closedScalesThisTick));
  }

  /** ★ 本 tick 关账的实例 → 实毛产（只读快照）。 */
  public Map<ProductionUnitId, Map<CommodityId, Long>> closedGrossThisTick() {
    Map<ProductionUnitId, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, Map<CommodityId, Long>> entry :
        closedGrossThisTick.entrySet()) {
      copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
    }
    return java.util.Collections.unmodifiableMap(copy);
  }

  /** ★ 本 tick 关账的实例 → 铸币候选（只读快照；I12：只是候选，发行/上限/记录/入账留 economy）。 */
  public Map<ProductionUnitId, Map<CurrencyId, Long>> closedMintCandidatesThisTick() {
    Map<ProductionUnitId, Map<CurrencyId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, Map<CurrencyId, Long>> entry : closedMintThisTick.entrySet()) {
      copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
    }
    return java.util.Collections.unmodifiableMap(copy);
  }

  // ── ② 执行（按最终投入推进实例）──────────────────────────────────────────────────────

  @Override
  public ProductionExecutionOutcome execute(ProductionExecutionRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("ProductionRuntimeEngine.execute 的 request 不得为 null");
    }
    boolean created = false;
    ProductionProcess instance;
    if (request.creation().isPresent()) {
      ProductionCreationRequest creation = request.creation().get();
      if (current.instances().containsKey(creation.unit())) {
        // ★ 业务拒绝（INFO）：同一 id 已经是一个实例 ⇒ 不覆盖（那会静默吞掉别人的进度）。
        return reject("unit-conflict", creation.unit().value());
      }
      IndustryDefinitionView planDefinition = current.industries().get(creation.plan());
      if (planDefinition == null) {
        return reject("plan-unknown", creation.plan().value());
      }
      // ★★ A 批：实例带<b>自己的</b>显式地点（不再从方案 id 的串里反解）；方案若自带地点，
      //    两者必须一致——不一致 = 状态自相矛盾（契约故障，不静默取一个）。
      requireLocationAgreesWithPlan(creation.plan(), planDefinition, creation.location());
      instance =
          new ProductionProcess(
              creation.unit(),
              creation.plan(),
              creation.location(),
              creation.operator(),
              creation.modeKey(),
              0L,
              Map.of(),
              Map.of());
      created = true;
      // ★★ R3（§15.3）：实例<b>先不</b>落进候选态 —— 使用资格被拒时请求必须<b>零残留</b>，
      //   实例写入留到资格通过之后（见下方 qualificationFailure 之后的 withInstance）。
    } else {
      ProductionUnitId unit = request.instance().get();
      ProductionProcess existing = current.instances().get(unit);
      if (existing == null) {
        return reject("instance-unknown", unit.value());
      }
      // ★★ A 批：同一实例同一 tick 重复推进必须被检测（§13.2 第 1 条② / §13.7 第 1 条）。
      //   判据是"本 tick 的推进台账里已经有它"——台账只在 beginTick 时清空，故跨 tick 合法、同 tick 重放非法。
      //   ★ 契约故障（不是业务拒绝）：重复推进是"推进协议被违反"，继续算会在污染态上加倍进度与经验。
      if (condition != null && advancedThisTick.contains(unit)) {
        contractFault(
            "instance-advanced-twice-in-tick",
            "同一实例在同一 tick 被重复推进（tick=" + condition.tick() + "，unit=" + unit.value() + "）");
      }
      instance = existing;
    }

    IndustryDefinitionView plan = current.industries().get(instance.plan());
    if (plan == null) {
      // ★ 实例引用悬空 = 契约故障（不是业务拒绝）：本域状态自相矛盾，必须拒绝整个候选提交（I15）。
      contractFault(
          "instance-references-unknown-plan",
          instance.id().value() + " → " + instance.plan().value());
    }

    // ★★★ R3（§15.3）：实际执行是权威入口 ⇒ 必须<b>自己</b>判"本次使用资格"，不能指望调用方先 probe。
    //   主体 = 实例经营者对应的家户（creation 分支的经营者就是 creation.operator()，与 instance.operator()
    //   是同一个引用）：经营者不是 HOUSEHOLD（组织等）⇒ 主体为空；声明了经验门槛/解锁门槛的方案对空主体
    //   fail-closed（condition-subject-unknown）。★ 条件求值只有一处拼写点：与 probe 共用
    //   qualificationFailure（同一条件遍历顺序、同一具名理由文本），经验值由这里给的本域账本读口提供。
    //   ★ "无任何条件、无需解锁 ⇒ 不做额外检查"：主体只在方案确实要读经验面时才反查（不对无条件方案多解析
    //     经营者 id，那会改变无条件方案的可达行为）。
    HouseholdId subject =
        requiresExperienceSubject(plan) || hasExternalAttributeCondition(plan)
            ? householdSubjectOf(instance.operator())
            : null;
    ExternalAttributeFacts planFacts =
        subject == null
            ? request.attributes()
            : request.participantAttributes().getOrDefault(subject, ExternalAttributeFacts.EMPTY);
    String qualificationFailure =
        qualificationFailure(
            plan, subject, track -> current.experienceOf(subject, track), planFacts);
    if (qualificationFailure != null) {
      // ★ 业务拒绝（INFO）+ 零残留：走到这里 current 尚未被本请求改过（实例未建、进度/经验/投入/占用均未动）。
      return reject(qualificationFailure, instance.id().value());
    }

    // ★★ C1/D2 §6.2/§7.3：角色级资格、管理劳动事实与归一化经验预算必须在<b>任何写入之前</b>查完。
    //   下列任一失败都整实例业务拒绝、零状态变化（不是“部分角色满足就生产”）。
    Map<ProductionRoleId, ProductionRoleDefinition> rolesById = rolesById(plan);
    Map<HouseholdId, ExternalAttributeFacts> participantFacts = request.participantAttributes();
    for (ParticipantContribution contribution : request.laborContributionsMilli()) {
      HouseholdId household = contribution.household();
      ProductionRoleDefinition role = rolesById.get(contribution.role());
      if (role == null) {
        return reject("role-unknown:" + contribution.role().value(), instance.id().value());
      }
      ExternalAttributeFacts facts =
          participantFacts.getOrDefault(household, ExternalAttributeFacts.EMPTY);
      String roleFailure =
          roleQualificationFailure(
              plan, role, household, track -> current.experienceOf(household, track), facts);
      if (roleFailure != null) {
        return reject(
            "role-qualification:" + role.id().value() + ":" + household.value() + ":" + roleFailure,
            instance.id().value());
      }
      ExperienceGrowth growth = role.experienceGrowth().orElse(null);
      if (growth == null) {
        continue;
      }
      if (current.experienceTracks().get(growth.track()) == null) {
        contractFault(
            "growth-references-unknown-track", role.id().value() + " → " + growth.track().value());
      }
      if (growth.countsManagement() && !request.managementLaborMilli().containsKey(household)) {
        return reject(
            "management-labor-missing:" + role.id().value() + ":" + household.value(),
            instance.id().value());
      }
      if (growth.basis() == GrowthBasis.NORMALIZED_PARTICIPATION) {
        Long budget = request.householdLaborBudgetMilli().get(household);
        if (budget == null || budget <= 0L) {
          return reject(
              "household-labor-budget-missing:" + role.id().value() + ":" + household.value(),
              instance.id().value());
        }
      }
    }
    // ── ★★ U4b2b：staged path 的当前阶段解析与“按实际投入缩规模”入口 ───────────────────────
    //   ★ 全部在<b>任何写入之前</b>完成：阶段 id 悬空是契约故障；当前阶段有正需求而本 tick 实际投入为 0
    //     是具名业务拒绝。partial（只满足一部分）允许推进，规模在关账时由整周期实际累计投入约束。
    //   legacy（plan.stages().isEmpty()）完全不进入此块，逐值走旧路径。
    boolean staged = !plan.stages().isEmpty();
    ProductionStageDefinition stagedStage = null;
    int stagedStageIndex = -1;
    ProductionStageState stagedStateBefore = null;
    Map<CommodityId, Long> stagedStartInputsUsed = Map.of();
    if (staged) {
      // ★ 阶段选择/startDue/start+tick 合表只在 IndustryDefinitionView.currentStageInput 一处拼写。
      CurrentStageInput currentStageInput = plan.currentStageInput(instance);
      stagedStage = currentStageInput.stage();
      stagedStageIndex = currentStageInput.stageIndex();
      stagedStateBefore = instance.stageState().orElse(null);
      if (stagedStateBefore != null
          && stagedStateBefore.stageProgressDays() > stagedStage.durationDays()) {
        contractFault(
            "stage-progress-out-of-range",
            "实例 "
                + instance.id().value()
                + " 的阶段 "
                + stagedStage.stageId()
                + " 已推进 "
                + stagedStateBefore.stageProgressDays()
                + " 天，超过定义时长 "
                + stagedStage.durationDays());
      }
      if (stagedStateBefore == null) {
        stagedStateBefore = new ProductionStageState(stagedStage.stageId(), 0L, Map.of());
      }

      Map<CommodityId, Long> currentInputPerUnit = currentStageInput.inputPerUnit();
      if (currentStageInput.hasPositiveDemand()
          && actualInputForDemand(currentInputPerUnit, request.finalInputsMilli()) <= 0L) {
        // ★ 业务拒绝（INFO）：本阶段确实要料，但本 tick 没有对任何一项需求商品形成实际投入。
        //   部分满足不拒（那些量已由 economy S4 实际扣出并进入 finalInputsMilli）。
        return reject(
            "stage-input-shortfall",
            "实例 "
                + instance.id().value()
                + " 的阶段 "
                + stagedStage.stageId()
                + " 本 tick 有投入需求但 finalInputsMilli 对需求商品实际为 0");
      }
      // ★ stage-start 标记：把本阶段第一天“实际收到的逐需求商品量”记进 stageStartInputsUsedMilli
      //   （0 也保留键 ⇒ 有需求时该 map 必然非空）。后续 tick 由 progressDays>0 不再 startDue；
      //   若阶段恰在第一天结束，引擎切换下一段时显式清空该表。
      stagedStartInputsUsed =
          currentStageInput.startDue()
              ? firstTickInputMarker(currentInputPerUnit, request.finalInputsMilli())
              : stagedStateBefore.stageStartInputsUsedMilli();
    }

    if (created) {
      current = current.withInstance(instance);
    }

    List<ParticipantContribution> contributions = request.laborContributionsMilli();
    long progressBefore = instance.progressDays();
    long progressAfter = Math.addExact(progressBefore, 1L);
    Map<CommodityId, Long> cycleInputs = new LinkedHashMap<>(instance.cycleInputUsedMilli());
    for (Map.Entry<CommodityId, Long> entry : request.finalInputsMilli().entrySet()) {
      cycleInputs.merge(entry.getKey(), entry.getValue(), Math::addExact);
    }

    // ── ★★ C1：逐 (家户×角色) 累计实际贡献与精确加权劳动；经验按 roles[].experienceGrowth 逐角色算后
    //    按 (家户×经验条) 一次换算 ────────────────────────────────────────────────────────────
    //   ★ 加权劳动 = 劳动 × 当刻效率，<b>不除 1000、不截断</b>；经验增长分子也保持整数精确，归并后只做一次除法/余数结转。
    if (condition == null) {
      // ★ 未开启 tick（手搭夹具直调）：一次 execute = 一个生产日 ⇒ 本调用自成一窗（开启 tick 时由
      //   beginTick 清窗，本 tick 内跨实例的贡献才会先归并、再一次性换算经验）。
      pendingExactGrowthThisTick.clear();
      tickBaselineExperience.clear();
    }
    Map<ProductionParticipantKey, CycleContribution> cycleContributions =
        new LinkedHashMap<>(instance.cycleContributions());
    Map<ProductionParticipantKey, Long> tickLaborByParticipant = new LinkedHashMap<>();
    Map<HouseholdExperienceKey, Long> exactGrowthByTrack = new LinkedHashMap<>();
    Map<HouseholdExperienceKey, Map<ProductionRoleId, Long>> roleNumeratorsByTrack =
        new LinkedHashMap<>();
    Set<IndustryId> newlyUnlocked = new LinkedHashSet<>();

    for (ParticipantContribution contribution : contributions) {
      HouseholdId household = contribution.household();
      ProductionParticipantKey participantKey = contribution.participantKey();
      long labor = contribution.laborMilli();
      long efficiency = efficiencyScaleFor(plan, household, null);
      long weightedLabor = Math.multiplyExact(labor, efficiency);
      tickLaborByParticipant.put(participantKey, labor);
      CycleContribution cycleContribution = cycleContributions.get(participantKey);
      cycleContributions.put(
          participantKey,
          cycleContribution == null
              ? new CycleContribution(household, contribution.role(), labor, weightedLabor)
              : cycleContribution.plus(labor, weightedLabor));

      ProductionRoleDefinition role = rolesById.get(contribution.role());
      ExperienceGrowth growthConfig = role.experienceGrowth().orElse(null);
      if (growthConfig == null || growthConfig.coefficientPerMille() <= 0L) {
        continue;
      }
      long managementLabor =
          growthConfig.countsManagement()
              ? request.managementLaborMilli().getOrDefault(household, 0L)
              : 0L;
      long effectiveLabor =
          effectiveLaborForExperience(
              labor,
              managementLabor,
              growthConfig,
              request.householdLaborBudgetMilli().get(household));
      ExperienceTrack track = current.experienceTracks().get(growthConfig.track());
      long exactNumerator =
          ProductionExperience.capExactGrowthNumerator(
              ProductionExperience.exactGrowthNumerator(
                  track, effectiveLabor, growthConfig.coefficientPerMille()),
              growthConfig.maxPerTickValue());
      if (exactNumerator <= 0L) {
        continue;
      }
      HouseholdExperienceKey experienceKey =
          new HouseholdExperienceKey(household, growthConfig.track());
      exactGrowthByTrack.merge(experienceKey, exactNumerator, Math::addExact);
      roleNumeratorsByTrack
          .computeIfAbsent(experienceKey, ignored -> new LinkedHashMap<>())
          .merge(contribution.role(), exactNumerator, Math::addExact);
    }

    Map<HouseholdExperienceKey, Long> growth = new LinkedHashMap<>();
    for (Map.Entry<HouseholdExperienceKey, Long> entry : exactGrowthByTrack.entrySet()) {
      HouseholdExperienceKey key = entry.getKey();
      ProductionExperienceFeature baseline = tickBaselineExperience.get(key);
      if (baseline == null) {
        baseline = current.experienceFeatureOf(key.household(), key.track());
        tickBaselineExperience.put(key, baseline);
      }
      long aggregated =
          Math.addExact(pendingExactGrowthThisTick.getOrDefault(key, 0L), entry.getValue());
      pendingExactGrowthThisTick.put(key, aggregated);
      ProductionExperienceFeature before =
          current.experienceFeatureOf(key.household(), key.track());
      current = ProductionExperience.accrueFromExactNumerator(current, key, baseline, aggregated);
      ProductionExperienceFeature after = current.experienceFeatureOf(key.household(), key.track());
      long delta = after.value() - before.value();
      if (delta > 0L) {
        growth.merge(key, delta, Math::addExact);
      }
    }
    for (ParticipantContribution contribution : contributions) {
      // ★ 门槛在周期中达到即可授予（复核裁决 §2 第 7 项 / §5 第 5 条）：同样是本 tick 判。
      Set<IndustryId> granted =
          ProductionExperience.grantUnlocks(current, contribution.household());
      if (!granted.isEmpty()) {
        current = ProductionExperience.applyUnlocks(current, contribution.household(), granted);
        newlyUnlocked.addAll(granted);
      }
    }
    List<ProductionExperienceGrowthLine> experienceGrowthLines =
        experienceGrowthLines(roleNumeratorsByTrack, growth);

    long cycleLabor = 0L;
    for (CycleContribution contribution : cycleContributions.values()) {
      cycleLabor = Math.addExact(cycleLabor, contribution.laborMilli());
    }
    // ── ★★ U4b1：staged path 的当日阶段推进 ──────────────────────────────────────────────
    //   ★ 阶段切换/关账判定都在这天所有校验与经验累计完成之后；staged 的非关账写回仍只落在同一个
    //     ProductionProcess：progressDays 只加一次，stageState 由 withStageState 更新。
    boolean cycleClosed;
    ProductionProcess advanced = null;
    if (staged) {
      long nextStageProgress = Math.addExact(stagedStateBefore.stageProgressDays(), 1L);
      boolean stageEnds = nextStageProgress >= stagedStage.durationDays();
      if (stageEnds && stagedStage.endAction() == ProductionStageEndAction.NEXT_STAGE) {
        int nextIndex = stagedStageIndex + 1;
        if (nextIndex >= plan.stages().size()) {
          contractFault(
              "stage-next-missing",
              "实例 "
                  + instance.id().value()
                  + " 的阶段 "
                  + stagedStage.stageId()
                  + " 标记 NEXT_STAGE，但没有下一阶段");
        }
        ProductionStageDefinition nextStage = plan.stages().get(nextIndex);
        advanced =
            instance
                .withCycleState(progressAfter, cycleContributions, cycleInputs)
                .withStageState(new ProductionStageState(nextStage.stageId(), 0L, Map.of()));
        cycleClosed = false;
      } else if (stageEnds) {
        // HARVEST：本周期关账；阶段状态由关账写回的 withCycleState(0, ...) 清空。
        cycleClosed = true;
      } else {
        advanced =
            instance
                .withCycleState(progressAfter, cycleContributions, cycleInputs)
                .withStageState(
                    new ProductionStageState(
                        stagedStage.stageId(), nextStageProgress, stagedStartInputsUsed));
        cycleClosed = false;
      }
    } else {
      // ★ legacy 单周期：逐值保持原有 progressDays >= cycleDays 判据与写回。
      cycleClosed = progressAfter >= plan.cycleDays();
      if (!cycleClosed) {
        advanced = instance.withCycleState(progressAfter, cycleContributions, cycleInputs);
      }
    }

    if (!cycleClosed) {
      current = current.withInstance(advanced);
      advancedThisTick.add(advanced.id()); // ★ 同 tick 重复推进的检测台账
      ProductionReceipt receipt =
          new ProductionReceipt(
              advanced.id(),
              plan.id(),
              false,
              created,
              progressBefore,
              progressAfter,
              0L,
              request.finalInputsMilli(),
              Map.of(),
              Map.of(),
              tickLaborByParticipant,
              cycleContributions,
              cycleLabor,
              current.efficiencyOf(advanced.id()).orElse(null),
              growth,
              experienceGrowthLines,
              newlyUnlocked);
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "PRODUCTION_DAY_ADVANCED",
                    ProductionLogSource.PRODUCTION_RUNTIME,
                    "unit",
                    advanced.id().value(),
                    "progressDays",
                    progressAfter,
                    "cycleDays",
                    plan.cycleDays(),
                    "cycleLaborMilli",
                    cycleLabor,
                    "participants",
                    cycleContributions.size(),
                    "experienceGrowthLines",
                    experienceGrowthLines.size(),
                    "newUnlocks",
                    newlyUnlocked.size()));
      }
      return new ProductionExecutionOutcome.Executed(receipt);
    }

    // ── 周期关账：实际规模由<b>整周期累计</b>决定（I6；复核裁决 §5 第 3 条）──────────────────
    Map<HouseholdId, Long> laborByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, Long> weightedLaborByHousehold = new LinkedHashMap<>();
    for (CycleContribution contribution : cycleContributions.values()) {
      laborByHousehold.merge(contribution.household(), contribution.laborMilli(), Math::addExact);
      weightedLaborByHousehold.merge(
          contribution.household(), contribution.efficiencyWeightedLaborMilli(), Math::addExact);
    }
    long assetScaleRaw =
        capacityScaleOf(plan, request.assetOccupancy(), request.durableCommodityOccupancy());
    // ★★ 唯一入口（试算/实际/家户利润比较共用）：三通道都不乘 E 求 baseScale，再整体乘一次得 finalScale。
    // ★★ U4b2b：staged 的原料约束读 U4b2a 的**整周期有效配方**（start + tick×duration 的聚合），
    //   这样逐 tick 实际累计投入才能约束 baseScale；legacy 继续逐值读 plan.inputPerUnit()。
    Map<CommodityId, Long> efficiencyInputPerUnit =
        staged ? plan.recipe().inputPerUnit() : plan.inputPerUnit();
    ProductionEfficiency.Evaluation evaluation =
        ProductionEfficiency.evaluate(
            current.efficiencyOf(instance.id()).orElse(null),
            plan.experience().effectScope(),
            plan.cycleDays(),
            weightedLaborByHousehold,
            laborByHousehold,
            assetScaleRaw,
            efficiencyInputPerUnit,
            cycleInputs,
            plan.laborPerUnit(),
            request.grantedScale()); // ★ 统一预算：授予规模是<b>基准规模（投入侧）</b>的硬上限
    long baseScale = Math.max(0L, evaluation.baseScale());
    long finalScale = Math.max(0L, evaluation.finalScale());

    // ★★ A′ 批（§13.8 裁定一）：投入消耗与占用按 baseScale（实际扣到的投入见 cycleInputs），
    //    实毛产与铸币候选按 finalScale —— 同一份投入产出更多。
    Map<CommodityId, Long> gross =
        staged ? stagedGrossOutput(stagedStage, finalScale) : grossOutput(plan, finalScale);
    Map<CurrencyId, Long> minted = mintCandidates(plan, finalScale);

    // ── 写回：进度归零、周期台账清零（一次实际劳动只计一次）、余数行更新 ────────────────────
    ProductionProcess closed = instance.withCycleState(0L, Map.of(), Map.of());
    current = current.withInstance(closed);
    current = current.withEfficiencyState(instance.id(), evaluation.nextCarry());

    ProductionReceipt receipt =
        new ProductionReceipt(
            closed.id(),
            plan.id(),
            true,
            created,
            progressBefore,
            progressAfter,
            finalScale,
            cycleInputs,
            gross,
            minted,
            tickLaborByParticipant,
            cycleContributions,
            cycleLabor,
            evaluation.nextCarry(),
            growth,
            experienceGrowthLines,
            newlyUnlocked);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "PRODUCTION_CYCLE_CLOSED",
                  ProductionLogSource.PRODUCTION_RUNTIME,
                  "unit",
                  closed.id().value(),
                  "plan",
                  plan.id().value(),
                  "location",
                  closed.location().value(),
                  "baseScale",
                  baseScale,
                  "finalScale",
                  finalScale,
                  "cycleLaborMilli",
                  cycleLabor,
                  "cycleEfficiencyWeightedLaborMilli",
                  evaluation.efficiencyWeightedLaborMilli(),
                  "participants",
                  cycleContributions.size(),
                  "efficiencyPerMille",
                  evaluation.efficiencyScalePerMille(),
                  "effectScope",
                  plan.experience().effectScope().key(),
                  "grossLines",
                  gross.size(),
                  "mintLines",
                  minted.size(),
                  "experienceGrowthLines",
                  experienceGrowthLines.size(),
                  "newUnlocks",
                  newlyUnlocked.size()));
    }
    advancedThisTick.add(instance.id()); // ★ 同 tick 重复推进的检测台账（见 execute 入口的守卫）
    return new ProductionExecutionOutcome.Executed(receipt);
  }

  // ── 同 tick 统一生效条件与重复推进检测（§13.2 第 1 条①② / §13.7 第 1 条）──────────────

  /**
   * ★★★ {@inheritDoc}
   *
   * <p>★ <b>为什么先衰减再冻结</b>（裁决 §2.1 的日序）：生效条件里的熟练度必须是"本 tick 衰减之后"的值， 否则本 tick 的执行会拿旧熟练度算，而衰减又被推到了下一
   * tick —— 那就是"衰减与生效不同步"。 ⇒ 本方法内部按顺序做两件事：① 经验按明确日序衰减<b>一次</b>；② 把衰减后的熟练度快照成条件。
   */
  @Override
  public ProductionTickCondition beginTick(long tick) {
    if (tick < 0L) {
      contractFault("tick-negative", "beginTick 的 tick 不得为负: " + tick);
    }
    if (tick == lastAdvancedTick) {
      // ★★ 同 tick 重复开启 = 契约故障（重复推进/重复衰减的入口），ERROR 不降级、整个候选提交被拒。
      contractFault("tick-already-started", "同一 tick 重复开启生产推进（tick=" + tick + "）");
    }
    if (tick < lastAdvancedTick) {
      contractFault(
          "tick-regressed", "生产推进的 tick 必须严格递增：last=" + lastAdvancedTick + "，now=" + tick);
    }
    decayedThisTick = false;
    advancedThisTick.clear();
    // ★★ A′ 批：经验归并账按 tick 重开（衰减之后才登记基线 ⇒ 基线是"本 tick 起点、已衰减"的特征）。
    pendingExactGrowthThisTick.clear();
    tickBaselineExperience.clear();
    // ★★★ DEF-T2a-3 修复：衰减走私有 {@link #decayOnce()}，**不**走公共 {@link #advanceTick()} —— 后者的首行守卫
    //   要求"已经开启过 tick"（读 {@link #lastAdvancedTick}/{@link #condition}），而这两者按日序要在**衰减之后**
    //   才登记 ⇒ 旧写法让**新引擎的每一次 beginTick 都抛 {@code tick-not-started}**（tick 协议整体不可用）。
    //   ★ 日序不变（裁决 §2.1：先衰减、再冻结条件）：衰减体一行没改，只是绕开了那道**只该管外部调用者**的守卫。
    decayOnce();
    lastAdvancedTick = tick;
    condition = freezeCondition(tick);
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "PRODUCTION_TICK_BEGUN",
                ProductionLogSource.PRODUCTION_RUNTIME,
                "tick",
                tick,
                "masteryRows",
                condition.masteryPerMille().size(),
                "experienceRows",
                condition.experience().size(),
                "neutral",
                condition.isNeutral()));
    return condition;
  }

  /** ★ {@inheritDoc} */
  @Override
  public int advanceTick() {
    if (lastAdvancedTick < 0L || condition == null) {
      // ★ 未开启 tick 就衰减：拒绝（业务拒绝形态是具名 reason；这里无回执可带 ⇒ 契约故障更合适：
      //   它说明调用方跳过了 beginTick，属"推进协议被违反"，不是世界里的业务规则拒绝）。
      //   ★★ DEF-T2a-3 修复后本条**只**拦"跳过了 beginTick 直接调公开 advanceTick"的外部调用者：
      //      beginTick 自己走私有 decayOnce()（无此守卫），故正常 tick 协议不再被它误伤。
      contractFault("tick-not-started", "advanceTick/beginTick 之前不得衰减经验");
    }
    if (decayedThisTick) {
      contractFault("tick-already-decayed", "同一 tick 重复衰减经验（tick=" + lastAdvancedTick + "）");
    }
    return decayOnce();
  }

  /**
   * ★★ <b>衰减体（每 tick 恰好一次）</b>——{@link #beginTick}（开启 tick 时的那一次）与公共 {@link #advanceTick}
   * 共用同一份实现（DEF-T2a-3：旧写法让 beginTick 去撞 advanceTick 的"未开启即衰减"守卫，导致新引擎第一次 beginTick 恒抛 {@code
   * tick-not-started}）。
   *
   * <p>★★ <b>为什么它<b>不</b>带守卫</b>：守卫（{@code tick-not-started} / {@code
   * tick-already-decayed}）判的是"<b>调用者</b>有没有按 tick 协议调"——{@link #beginTick}
   * 是协议本身（它已经自己判过重复开启与回退），不该再被"外部调用者守卫"拦住； 而 {@code decayedThisTick} 的**置位**留在这里，{@link #beginTick}
   * 开头把它归零 ⇒ "每 tick 恰好一次"仍由同一个 布尔量保证，公共 {@code advanceTick} 的重复衰减语义因此逐字不变（N8②）。
   */
  private int decayOnce() {
    decayedThisTick = true;
    ProductionData next = ProductionExperience.decayTick(current);
    int changed = 0;
    for (Map.Entry<HouseholdExperienceKey, ProductionExperienceFeature> entry :
        current.householdExperience().entrySet()) {
      ProductionExperienceFeature after = next.householdExperience().get(entry.getKey());
      if (after != null && !after.equals(entry.getValue())) {
        changed++;
      }
    }
    current = next;
    if (changed > 0 && LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "PRODUCTION_EXPERIENCE_DECAYED",
                  ProductionLogSource.PRODUCTION_RUNTIME,
                  "tick",
                  lastAdvancedTick,
                  "rows",
                  changed));
    }
    return changed;
  }

  /** ★ {@inheritDoc} */
  @Override
  public Optional<ProductionTickCondition> currentCondition() {
    return Optional.ofNullable(condition);
  }

  /** ★ {@inheritDoc} */
  @Override
  public Set<ProductionUnitId> advancedUnitsThisTick() {
    return java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(advancedThisTick));
  }

  /**
   * ★ <b>把衰减后的经验输入快照成当 tick 生效条件</b>（逐 (家户 × 经验条) 一行；熟练度 + 原始经验各一列）。
   *
   * <p>★ 只快照<b>已经登记了该经验条</b>的行（经验条定义缺失 ⇒ 该行没有可解释的熟练度/经验 = 0，不必快照）； 条件里缺键与 0 逐值等价（{@link
   * ProductionTickCondition#masteryOf} / {@link ProductionTickCondition#experienceOf}）。
   *
   * <p>★★ <b>R5（§15.5）</b>：同一行同时冻结<b>原始经验</b>（{@code current.experienceOf}）—— 它与熟练度一样是 {@code
   * PRIMARY_EXPERIENCE_INPUT} 的来源，必须与熟练度取同一时点，否则"实例处理先后"仍能从原始经验渗进 本 tick 的效率倍率。
   */
  private ProductionTickCondition freezeCondition(long tick) {
    Map<HouseholdExperienceKey, Long> mastery = new LinkedHashMap<>();
    Map<HouseholdExperienceKey, Long> experience = new LinkedHashMap<>();
    for (Map.Entry<HouseholdExperienceKey, ProductionExperienceFeature> entry :
        current.householdExperience().entrySet()) {
      ExperienceTrackId track = entry.getKey().track();
      if (!current.experienceTracks().containsKey(track)) {
        continue;
      }
      HouseholdId household = entry.getKey().household();
      mastery.put(entry.getKey(), current.masteryOf(household, track));
      experience.put(entry.getKey(), current.experienceOf(household, track));
    }
    return new ProductionTickCondition(tick, mastery, experience);
  }

  /** ★ <b>逐条授予解锁</b>（永久记忆；供组合根在人口迁移/合户后补授，I7/I10）。 */
  public Set<IndustryId> grantUnlocks(HouseholdId household) {
    Set<IndustryId> granted = ProductionExperience.grantUnlocks(current, household);
    if (!granted.isEmpty()) {
      current = ProductionExperience.applyUnlocks(current, household, granted);
    }
    return granted;
  }

  // ── 只读投影（economy 的临时只读视图 / 本轮索引）────────────────────────────────────

  @Override
  public ChangeSet changeSet() {
    return io.mosire.simos.production.change.ProductionChangeSet.between(base, current);
  }

  @Override
  public Map<ProductionUnitId, ProductionProcess> units() {
    return current.instances();
  }

  @Override
  public ProductionDefinitionLookup definitions() {
    return current;
  }

  @Override
  public Map<IndustryId, Map<CommodityId, Long>> outputQuantityOverrides() {
    return current.outputQuantityOverrides();
  }

  @Override
  public long experienceOf(HouseholdId household, ExperienceTrackId track) {
    return current.experienceOf(household, track);
  }

  @Override
  public ExperienceState experienceStateOf(HouseholdId household, ExperienceTrackId track) {
    return current.experienceStateOf(household, track);
  }

  @Override
  public long masteryOf(HouseholdId household, ExperienceTrackId track) {
    return current.masteryOf(household, track);
  }

  @Override
  public Set<IndustryId> unlocksOf(HouseholdId household) {
    return current.unlocksOf(household);
  }

  @Override
  public Optional<ProductionEfficiencyState> efficiencyOf(ProductionUnitId unit) {
    return current.efficiencyOf(unit);
  }

  // ── 内部：共享公式（预计与实际共用；I6）─────────────────────────────────────────────

  /**
   * ★★ <b>有效产出表（含 GM 覆盖）</b>——预计与实际<b>共用</b>这唯一一处。
   *
   * <p>★ 覆盖口径<b>逐字沿用</b>既有语义（{@code EconomySettlement} 的 harvest）： {@code quantityPerUnit =
   * overrides.getOrDefault(commodity, recipe.outputPerUnit[commodity])}
   * ——即<b>覆盖只替换配方里已存在的商品的每单位数量</b>，不新增商品（只遍历配方表的键）。
   */
  private Map<CommodityId, Long> effectiveOutputPerUnit(IndustryDefinitionView plan) {
    Map<CommodityId, Long> overrides = current.outputQuantityOverrideOf(plan.id());
    Map<CommodityId, Long> effective = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : plan.recipe().outputPerUnit().entrySet()) {
      effective.put(entry.getKey(), overrides.getOrDefault(entry.getKey(), entry.getValue()));
    }
    return effective;
  }

  /**
   * ★★ <b>毛产</b>（毫单位）：{@code scale × 每单位数量 × 1000}（与既有 harvest 逐字同式，I14）。
   *
   * <p>★ A′ 批口径：传进来的 {@code scale} 是 {@link ProductionEfficiency} 的 <b>finalScale</b>
   * （效率倍率已经<b>在基准规模之后整段乘过一次</b>），故这里<b>不</b>再乘一次效率 —— 倍率只有一处 作用点（复核裁决 §3"不能挪动加成位置"；§13.8 裁定一）。
   */
  private Map<CommodityId, Long> grossOutput(IndustryDefinitionView plan, long scale) {
    Map<CommodityId, Long> gross = new LinkedHashMap<>();
    if (scale <= 0L) {
      return gross;
    }
    for (Map.Entry<CommodityId, Long> entry : effectiveOutputPerUnit(plan).entrySet()) {
      long produced =
          Math.multiplyExact(Math.multiplyExact(scale, entry.getValue()), MILLI_PER_COMMODITY_UNIT);
      if (produced > 0L) {
        gross.put(entry.getKey(), produced);
      }
    }
    return gross;
  }

  /** ★ <b>铸币候选数量</b>（毫货币）：{@code scale × 每单位产出率}（技术权威在本域；I12）。 */
  private Map<CurrencyId, Long> mintCandidates(IndustryDefinitionView plan, long scale) {
    MoneyOutput moneyOutput = plan.moneyOutputPerUnit();
    if (scale <= 0L || moneyOutput.isEmpty()) {
      return Map.of();
    }
    return moneyOutput.grossOf(scale);
  }

  /**
   * ★★ <b>资产/耐久商品资本那一路的原始规模（probe/roster/execute 共用的唯一入口）</b>：
   *
   * <pre>
   * assetScaleRaw = min(
   *   min_k ⌊assetOccupancy_k ÷ capacityPerUnit_k⌋,                 // AssetKind 通道
   *   min_j ⌊durableCommodityOccupancy_j ÷ durableCommodityCapacityPerUnit_j⌋ ) // 耐久商品资本通道
   * </pre>
   *
   * <p>★ <b>无 durable 通道</b>（{@code durableCommodityCapacityPerUnit} 为空）⇒ 结果与旧 {@code
   * capacityScaleOf} 逐值相同。★ durable occupancy <b>缺项按 0</b>（该通道规模 = 0），<b>不</b>给 {@code
   * Long.MAX_VALUE} 那种"看起来不受限" 的假值。★ 两条 capacity map 的逐值 {@code > 0} 已由 {@link
   * IndustryDefinitionView} 守卫。
   *
   * <p>★ <b>只读</b>：本方法不扣减任何商品（马匹不按趟消耗），也不从参与户 conditions 聚合占用。
   */
  private static long capacityScaleOf(
      IndustryDefinitionView plan,
      Map<AssetKind, Long> assetOccupancy,
      Map<CommodityId, Long> durableCommodityOccupancy) {
    long scale = Long.MAX_VALUE;
    for (Map.Entry<AssetKind, Long> entry : plan.capacityPerUnit().entrySet()) {
      long have = assetOccupancy == null ? 0L : assetOccupancy.getOrDefault(entry.getKey(), 0L);
      scale = Math.min(scale, have / entry.getValue());
    }
    for (Map.Entry<CommodityId, Long> entry : plan.durableCommodityCapacityPerUnit().entrySet()) {
      long have =
          durableCommodityOccupancy == null
              ? 0L
              : durableCommodityOccupancy.getOrDefault(entry.getKey(), 0L);
      scale = Math.min(scale, have / entry.getValue());
    }
    return scale == Long.MAX_VALUE ? 0L : Math.max(0L, scale);
  }

  /** ★ 周期关账时的资产需求读数：{@code scale × capacityPerUnit}。 */
  private static Map<AssetKind, Long> requiredAssets(IndustryDefinitionView plan, long scale) {
    Map<AssetKind, Long> required = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : plan.capacityPerUnit().entrySet()) {
      long amount = Math.multiplyExact(scale, entry.getValue());
      if (amount > 0L) {
        required.put(entry.getKey(), amount);
      }
    }
    return required;
  }

  /** ★ probe/roster 的原料需求读数：{@code scale × recipe.inputPerUnit}（staged 时为有效周期投入）。 */
  private static Map<CommodityId, Long> requiredInputs(IndustryDefinitionView plan, long scale) {
    Map<CommodityId, Long> required = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : plan.recipe().inputPerUnit().entrySet()) {
      long amount = Math.multiplyExact(scale, entry.getValue());
      if (amount > 0L) {
        required.put(entry.getKey(), amount);
      }
    }
    return required;
  }

  /**
   * ★★ <b>逐家户效率倍率（唯一入口）</b>：熟练度 a/b 与主经验值按<b>当刻账本</b>读，公式按方案声明的那一份 求值。{@code householdConditions !=
   * null}（试算路径）时优先用调用方传进来的熟练度/经验事实， 否则读本域账本（执行路径）。
   *
   * <p>★ 两条路径共用本方法 = "试算与实际读同一份配置与同一入口"（I6；复核裁决 §3）。
   *
   * <p>★★ <b>A 批：执行路径改读"本 tick 生效条件"而不是当刻候选态</b>（裁决 §2.1：实例处理先后不得改变 另一实例本 tick 的熟练度）。{@link
   * #condition} 非空时熟练度与主经验取自那份 tick 起点冻结的快照； 未开启 tick（{@code condition == null}，如手搭夹具直调）时退回当刻账本 ——
   * 那条路径逐值等于改动前。
   *
   * <p>★★ <b>R5（§15.5）：主经验原始值也是公式输入</b>（{@code PRIMARY_EXPERIENCE_INPUT}）⇒ 与熟练度同口径取冻结条件 （{@link
   * #experienceFromCondition}），<b>不得</b>在 {@code condition != null} 时再读 {@code
   * current.experienceOf} —— 否则同 tick 先执行的实例涨的经验会改变后执行实例本 tick 的效率倍率（同一类顺序依赖）。
   */
  private long efficiencyScaleFor(
      IndustryDefinitionView plan,
      HouseholdId household,
      HouseholdProductionConditions conditions) {
    ProductionExperienceUse use = plan.experience();
    ExperienceTrackId primary = use.primaryTrack().orElse(null);
    ExperienceTrackId shared = use.sharedTrack().orElse(null);
    long masteryA = 0L;
    long masteryB = 0L;
    long primaryExperience = 0L;
    if (conditions != null) {
      masteryA = conditions.masteryOf(primary);
      masteryB = conditions.masteryOf(shared);
      primaryExperience = conditions.experienceOf(primary);
    } else if (condition != null) {
      // ★★ 本 tick 生效条件（同口径：全部实例读同一份 tick 起点熟练度）。
      //   ★ 缺槽位（Optional.empty() ⇒ track == null）按 0 参与公式：这与另两条分支（current.masteryOf /
      //     conditions.masteryOf 都容忍 null）同语义 —— 见 ProductionExperienceUse 类注"缺槽位时其熟练度输入按
      //     0 参与公式"。不得让"只挂 a 或都不挂"的合法方案在**开启 tick 之后**变成 NPE（DEF-T2a-3 修好之前
      //     这条分支根本走不到，故这个不对称一直被 tick 协议不可用掩盖着）。
      masteryA = masteryFromCondition(condition, household, primary);
      masteryB = masteryFromCondition(condition, household, shared);
      // ★★ R5（§15.5）：原始经验同样是公式输入 ⇒ 与熟练度同源（读冻结条件，不读当刻候选态）。
      primaryExperience = experienceFromCondition(condition, household, primary);
    } else {
      masteryA = current.masteryOf(household, primary);
      masteryB = current.masteryOf(household, shared);
      primaryExperience = current.experienceOf(household, primary);
    }
    long efficiency = use.efficiencyScalePerMille(masteryA, masteryB, primaryExperience);
    if (efficiency < 0L) {
      contractFault(
          "efficiency-scale-negative",
          "效率倍率不得为负（方案 " + plan.id().value() + "，家户 " + household.value() + "）: " + efficiency);
    }
    return efficiency;
  }

  /**
   * ★ <b>从本 tick 生效条件里读一条熟练度</b>：经验条槽位<b>可缺</b>（{@code track == null}）⇒ 按 0 参与公式（{@link
   * ProductionExperienceUse} 类注；与 {@link ProductionData#masteryOf} / {@link
   * HouseholdProductionConditions#masteryOf} 同一条"缺槽位 = 0"口径）。
   *
   * <p>★ <b>为什么不能直接调 {@code condition.masteryOf(...)}</b>：{@link ProductionTickCondition#masteryOf}
   * 对 {@code track} 走 {@code Objects.requireNonNull}（它的入参是"一条真实经验条"），而本方法的两侧调用点传的是<b>可缺槽位</b>。
   *
   * <p>★★ <b>B11（DEF-T5-4）</b>：本 tick 生效条件改为<b>显式入参</b>，本方法不再自己去读 {@link #condition} 字段 ——
   * 该字段唯一的解引用点因此回到 {@link #efficiencyScaleFor} 的 {@code else if (condition != null)} 分支里
   * （判空与解引用在同一个方法、同一行可见）。★ <b>可达行为逐值不变</b>：那条 {@code else if} 就是本方法唯一的调用点， 入参由它保证非
   * null；本方法仍然只负责"可缺槽位 ⇒ 0"这一件事。
   */
  private long masteryFromCondition(
      ProductionTickCondition tickCondition, HouseholdId household, ExperienceTrackId track) {
    return track == null ? 0L : tickCondition.masteryOf(household, track);
  }

  /**
   * ★★ <b>R5（§15.5）：从本 tick 生效条件里读一条原始经验</b>——{@link #masteryFromCondition} 的对称方法， 同为"可缺槽位（{@code
   * track == null}）⇒ 0"口径。
   *
   * <p>★ 它与 {@code current.experienceOf(...)} 的区别正是 R5 的判据：前者是 tick 起点冻结值（同 tick 全部实例
   * 读到同一个数），后者是当刻候选态（会被本 tick 先执行的实例改大）⇒ 执行路径只准用前者（{@code condition != null} 分支）。
   */
  private long experienceFromCondition(
      ProductionTickCondition tickCondition, HouseholdId household, ExperienceTrackId track) {
    return track == null ? 0L : tickCondition.experienceOf(household, track);
  }

  /**
   * ★★ <b>方案自带地点与实例地点必须一致</b>（A 批新增守卫）：方案 id 里带 {@code <kind>@<q>_<r>} 时， 它与实例地点是"同一件事的两种说法"⇒
   * 不一致就是状态自相矛盾（契约故障 ERROR，不静默取一个）。
   *
   * <p>★ 方案<b>不带</b>地点（A 批起合法的"一份方案多地复用"）⇒ 不施加这条守卫（实例地点说了算）。
   */
  private static void requireLocationAgreesWithPlan(
      IndustryId planId,
      IndustryDefinitionView plan,
      io.mosire.simos.production.contract.ProductionLocation location) {
    Optional<io.mosire.simos.production.contract.ProductionLocation> planLocation =
        io.mosire.simos.production.contract.ProductionLocation.ofPlan(planId);
    if (planLocation.isEmpty()) {
      return;
    }
    if (!planLocation.get().equals(location)) {
      contractFault(
          "instance-location-mismatch",
          "方案 "
              + planId.value()
              + " 自带地点 "
              + planLocation.get().value()
              + "，实例地点 "
              + location.value());
    }
  }

  /** ★ 方案内角色索引（保序；构造期已保证 id 唯一，这里只防御性复核）。 */
  private static Map<ProductionRoleId, ProductionRoleDefinition> rolesById(
      IndustryDefinitionView plan) {
    Map<ProductionRoleId, ProductionRoleDefinition> roles = new LinkedHashMap<>();
    for (ProductionRoleDefinition role : plan.roles()) {
      if (roles.put(role.id(), role) != null) {
        contractFault("role-definition-duplicate", role.id().value());
      }
    }
    return roles;
  }

  /**
   * ★ <b>角色级资格</b>（D2 §6.2/§7.3）：逐条求值 {@code roles[].conditions}，主体是该参与户；缺属性事实
   * fail-closed。返回第一条不满足的具名理由（null = 有资格）。
   */
  private String roleQualificationFailure(
      IndustryDefinitionView plan,
      ProductionRoleDefinition role,
      HouseholdId household,
      ToLongFunction<ExperienceTrackId> experienceOfTrack,
      ExternalAttributeFacts attributes) {
    if (role.conditions().isEmpty()) {
      return null;
    }
    return unsatisfiedConditions(role.conditions(), plan, experienceOfTrack, attributes);
  }

  /**
   * ★ <b>经验增长的有效劳动</b>（D2 §9.1）：
   *
   * <ul>
   *   <li>{@code ACTUAL_LABOR}：直接使用该角色实际劳动（必要时加管理劳动）；
   *   <li>{@code NORMALIZED_PARTICIPATION}：{@code min(labor+management, budget) × 1000 /
   *       budget}，即“全时当量毫劳动”。
   * </ul>
   *
   * <p>★ 归一化分母缺失或 ≤0 已在 execute 的业务拒绝前置检查里拦下；这里仍 fail-closed（防御调用点绕过）。
   */
  private static long effectiveLaborForExperience(
      long laborMilli,
      long managementLaborMilli,
      ExperienceGrowth growth,
      Long householdLaborBudgetMilli) {
    try {
      long countedLabor = Math.addExact(laborMilli, managementLaborMilli);
      if (growth.basis() == GrowthBasis.ACTUAL_LABOR) {
        return countedLabor;
      }
      if (householdLaborBudgetMilli == null || householdLaborBudgetMilli <= 0L) {
        contractFault(
            "household-labor-budget-missing",
            growth.track().value() + " 的归一化经验分母缺失/≤0: " + householdLaborBudgetMilli);
      }
      long capped = Math.min(countedLabor, householdLaborBudgetMilli);
      long numerator = Math.multiplyExact(capped, 1_000L);
      return numerator / householdLaborBudgetMilli;
    } catch (ArithmeticException ex) {
      contractFault("experience-growth-overflow", ex.getMessage());
      return 0L; // 不可达（contractFault 恒抛）
    }
  }

  /**
   * ★ <b>把逐角色精确增长分子拆成可审计的 {@link ProductionExperienceGrowthLine}</b>。
   *
   * <p>经验主值增量按 {@code (household,track)} 换算一次；同一 tick 同一 track 有多个角色时，用最大余数法把该 整数增量分给各角色，保证 lines
   * 汇总与 {@code growth} 逐项相等。分母为各角色自己的精确增长分子。
   */
  private static List<ProductionExperienceGrowthLine> experienceGrowthLines(
      Map<HouseholdExperienceKey, Map<ProductionRoleId, Long>> roleNumeratorsByTrack,
      Map<HouseholdExperienceKey, Long> growth) {
    List<ProductionExperienceGrowthLine> lines = new ArrayList<>();
    for (Map.Entry<HouseholdExperienceKey, Long> entry : growth.entrySet()) {
      HouseholdExperienceKey key = entry.getKey();
      long totalDelta = entry.getValue();
      if (totalDelta <= 0L) {
        continue;
      }
      Map<ProductionRoleId, Long> numerators = roleNumeratorsByTrack.getOrDefault(key, Map.of());
      if (numerators.isEmpty()) {
        contractFault(
            "experience-growth-lines-missing", "有经验增量但没有角色增长分子: " + key + " delta=" + totalDelta);
      }
      Map<ProductionRoleId, Long> deltas = allocateByNumerators(totalDelta, numerators);
      for (Map.Entry<ProductionRoleId, Long> delta : deltas.entrySet()) {
        if (delta.getValue() > 0L) {
          lines.add(
              new ProductionExperienceGrowthLine(
                  key.household(), delta.getKey(), key.track(), delta.getValue()));
        }
      }
    }
    return List.copyOf(lines);
  }

  /** 按权重（角色精确增长分子）用最大余数法拆一个整数总量；余数降序、角色 id 升序破平。 */
  private static Map<ProductionRoleId, Long> allocateByNumerators(
      long total, Map<ProductionRoleId, Long> numerators) {
    long sum = 0L;
    for (long value : numerators.values()) {
      sum = Math.addExact(sum, value);
    }
    if (sum <= 0L) {
      contractFault("experience-growth-lines-zero-weight", "角色增长分子合计必须 >0");
    }
    Map<ProductionRoleId, Long> base = new LinkedHashMap<>();
    Map<ProductionRoleId, Long> remainders = new LinkedHashMap<>();
    long allocated = 0L;
    BigInteger bigTotal = BigInteger.valueOf(total);
    BigInteger bigSum = BigInteger.valueOf(sum);
    for (Map.Entry<ProductionRoleId, Long> entry : numerators.entrySet()) {
      BigInteger product = bigTotal.multiply(BigInteger.valueOf(entry.getValue()));
      long quotient = product.divide(bigSum).longValueExact();
      long remainder = product.remainder(bigSum).longValueExact();
      base.put(entry.getKey(), quotient);
      remainders.put(entry.getKey(), remainder);
      allocated = Math.addExact(allocated, quotient);
    }
    long leftover = Math.subtractExact(total, allocated);
    if (leftover < 0L || leftover > numerators.size()) {
      contractFault(
          "experience-growth-line-remainder",
          "经验增长行整数余数越界: " + leftover + "，角色数=" + numerators.size());
    }
    List<ProductionRoleId> order = new ArrayList<>(numerators.keySet());
    order.sort(
        java.util.Comparator.comparingLong((ProductionRoleId id) -> remainders.get(id))
            .reversed()
            .thenComparing(ProductionRoleId::value));
    for (int i = 0; i < leftover; i++) {
      ProductionRoleId role = order.get(i);
      base.merge(role, 1L, Math::addExact);
    }
    return base;
  }

  /**
   * ★★★ <b>本次使用资格的具名拒绝理由（{@code null} = 有资格）</b>——{@code probe} 与 {@code execute} 的<b>唯一求值拼写点</b>
   * （R3；设计书 §15.3）。
   *
   * <p>★★ <b>为什么必须共用</b>：probe 只是"无副作用试算"，execute 才是权威实际入口。两处各拼一套条件求值，就会出现 "试算拒绝、实际照产"（R3
   * 的事实）——本方法把条件遍历、经验读取口径与具名理由文本钉在一处。
   *
   * <p>★ <b>求值顺序</b>：
   *
   * <ol>
   *   <li><b>主体缺失</b>（{@code subject == null}，经营者不是 HOUSEHOLD 且方案要读经验面）⇒ {@code
   *       condition-subject-unknown}（fail-closed，§15.3 工程裁定）；
   *   <li><b>可用性条件</b>（外部属性 / 经验门槛）；
   *   <li><b>解锁资格</b>（{@code locked}）。
   * </ol>
   *
   * <p>★ ②先于③<b>沿用 probe 的既有次序</b>：probe 的输出要逐值不变（同一方案既未解锁又有不满足条件时，理由文本保持 probe 原来给的那一条）。
   *
   * @param plan 方案定义（非 null）
   * @param subject 本次使用资格的家户主体；{@code null} = 经营者不是 HOUSEHOLD（外部属性条件仍可判）
   * @param experienceOfTrack 该主体在某经验条上的原始经验读口（probe = 本次请求的家户条件；execute = 本域经验账）
   * @param attributes 外部属性事实（平表，不绑定家户）
   */
  private String qualificationFailure(
      IndustryDefinitionView plan,
      HouseholdId subject,
      ToLongFunction<ExperienceTrackId> experienceOfTrack,
      ExternalAttributeFacts attributes) {
    if (subject == null && requiresExperienceSubject(plan)) {
      return "condition-subject-unknown";
    }
    String conditionFailure =
        unsatisfiedConditions(plan.availability(), plan, experienceOfTrack, attributes);
    if (conditionFailure != null) {
      return conditionFailure;
    }
    return isUnlockedBy(subject, plan) ? null : "locked";
  }

  /**
   * ★ <b>该方案是否要求"有一个可读经验账的主体"</b>：设了解锁门槛（{@code unlockAtPrimaryExperience > 0}）， 或声明了经验门槛条件（{@link
   * ProductionAvailabilityCondition.MinimumExperience}）。
   *
   * <p>★ {@link ProductionAvailabilityCondition.ExternalAttribute} <b>不</b>要求主体：属性事实是平表，组织经营者也判得了。
   */
  private static boolean requiresExperienceSubject(IndustryDefinitionView plan) {
    if (plan.experience().requiresUnlock()) {
      return true;
    }
    for (ProductionAvailabilityCondition condition : plan.availability()) {
      if (condition instanceof ProductionAvailabilityCondition.MinimumExperience) {
        return true;
      }
    }
    return false;
  }

  /** ★ 计划级外部属性条件需要 organizer 家户的属性事实（非家户主体仍退回请求级 attributes）。 */
  private static boolean hasExternalAttributeCondition(IndustryDefinitionView plan) {
    for (ProductionAvailabilityCondition condition : plan.availability()) {
      if (condition instanceof ProductionAvailabilityCondition.ExternalAttribute) {
        return true;
      }
    }
    return false;
  }

  /**
   * ★★ <b>使用资格的主体</b>（§15.3 工程裁定）：实例经营者是 {@link ActorKind#HOUSEHOLD} ⇒ 反查家户身份； 其他种类（组织等）⇒
   * 空主体（经验门槛/解锁门槛对它 fail-closed，外部属性条件不受影响）。
   *
   * <p>★ 反查走 {@link HouseholdActors#householdOf(ActorRef)} 这一唯一拼写点（不在本类另拼 actor id）。 ★ 家户 actor id
   * 解析不出来 = 跨切片状态自相矛盾（不是业务拒绝）⇒ 具名契约故障，ERROR 不降级（"宁抛不静默"）。
   */
  private static HouseholdId householdSubjectOf(ActorRef operator) {
    if (operator.kind() != ActorKind.HOUSEHOLD) {
      return null;
    }
    try {
      return HouseholdActors.householdOf(operator);
    } catch (IllegalArgumentException ex) {
      contractFault(
          "condition-subject-unparsable",
          operator.kind() + " / " + operator.id() + ": " + ex.getMessage());
      return null; // 不可达（contractFault 恒抛）
    }
  }

  /** ★ 解锁资格：门槛 0 ⇒ 天然可用；否则看 {@code unlocks}（<b>只增不减</b>，I7）。 */
  private boolean isUnlockedBy(HouseholdId household, IndustryDefinitionView plan) {
    if (!plan.experience().requiresUnlock()) {
      return true;
    }
    return current.unlocksOf(household).contains(plan.id());
  }

  /**
   * ★ 可用性条件求值：返回<b>第一条不满足</b>的具名理由（全满足 ⇒ null）。
   *
   * <p>★ 外部属性条件经 {@link ExternalAttributeFacts} 判（缺失 ⇒ fail-closed）；
   * 经验门槛条件经<b>调用方给的读口</b>读原始经验（probe 读本次请求的家户条件，execute 读本域经验账）——两路只是数据来源不同，比较与理由文本是同一份。
   *
   * <p>★ 经验条未登记 ⇒ {@code condition-references-unknown-track} 契约故障（不是"条件不满足"：引用悬空是配置错误）。
   */
  private String unsatisfiedConditions(
      List<ProductionAvailabilityCondition> conditions,
      IndustryDefinitionView plan,
      ToLongFunction<ExperienceTrackId> experienceOfTrack,
      ExternalAttributeFacts attributes) {
    for (ProductionAvailabilityCondition condition : conditions) {
      if (condition instanceof ProductionAvailabilityCondition.ExternalAttribute) {
        ExternalAttributeFacts.Verdict verdict = attributes.evaluate(condition);
        if (!verdict.satisfied()) {
          return "condition-unsatisfied:" + verdict.reason();
        }
        continue;
      }
      ProductionAvailabilityCondition.MinimumExperience minimum =
          (ProductionAvailabilityCondition.MinimumExperience) condition;
      ExperienceTrack track = current.experienceTracks().get(minimum.track());
      if (track == null) {
        // ★ 引用了未登记的经验条 = 引用悬空 ⇒ 契约故障（不是"条件不满足"：那是配置错误）。
        contractFault(
            "condition-references-unknown-track",
            plan.id().value() + " → " + minimum.track().value());
      }
      if (experienceOfTrack.applyAsLong(minimum.track()) < minimum.minimumExperience()) {
        return "condition-unsatisfied:" + condition.describe();
      }
    }
    return null;
  }

  /** ★ staged 本 tick 对“当前阶段需求商品”的实际投入合计（只读；用于 0 投入的具名拒绝）。 */
  private static long actualInputForDemand(
      Map<CommodityId, Long> perScale, Map<CommodityId, Long> actual) {
    long total = 0L;
    for (Map.Entry<CommodityId, Long> entry : perScale.entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      total = Math.addExact(total, actual.getOrDefault(entry.getKey(), 0L));
    }
    return total;
  }

  /**
   * ★ staged 阶段第一天的投入台账标记：逐需求商品记录本 tick 实际收到的量（含 0）。
   *
   * <p>★ 为什么用实际量而不是按 {@code grantedScale} 算出的“应有量”：economy S4 已按实际可扣量缩规模， request 里的 {@code
   * finalInputsMilli} 才是本 tick 的真实投入；这里只承担“已进入该阶段开始投入阶段”的 标记与后续 startDue 判据。0 值保留键 ⇒ 有需求时 map 非空，下一
   * tick（progressDays &gt; 0）不会再次 startDue。
   */
  private static Map<CommodityId, Long> firstTickInputMarker(
      Map<CommodityId, Long> perScale, Map<CommodityId, Long> actual) {
    Map<CommodityId, Long> marker = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : perScale.entrySet()) {
      if (entry.getValue() <= 0L) {
        continue;
      }
      marker.put(entry.getKey(), actual.getOrDefault(entry.getKey(), 0L));
    }
    return marker;
  }

  /**
   * ★ 最终 HARVEST 阶段的毛产：{@code stage.outputPerUnit × finalScale × 1000}。
   *
   * <p>★ 只读当前 final stage 自己的输出表（不走 legacy {@code plan.outputPerUnit()}；有 stages 时后者构造期
   * 已要求为空）。{@code transport_capacity_service} 若在此表中就是普通商品键，本单不接 TransportCapacitySink。
   */
  private static Map<CommodityId, Long> stagedGrossOutput(
      ProductionStageDefinition finalStage, long finalScale) {
    Map<CommodityId, Long> gross = new LinkedHashMap<>();
    if (finalScale <= 0L) {
      return gross;
    }
    for (Map.Entry<CommodityId, Long> entry : finalStage.outputPerUnit().entrySet()) {
      long produced =
          Math.multiplyExact(
              Math.multiplyExact(finalScale, entry.getValue()), MILLI_PER_COMMODITY_UNIT);
      if (produced > 0L) {
        gross.put(entry.getKey(), produced);
      }
    }
    return gross;
  }

  /** ★ 业务拒绝（INFO 具名理由；世界照常推进）。 */
  private static ProductionExecutionOutcome reject(String reason, String detail) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "PRODUCTION_EXECUTION_REJECTED",
                ProductionLogSource.PRODUCTION_RUNTIME,
                "reason",
                reason,
                "detail",
                detail));
    return new ProductionExecutionOutcome.Rejected(reason);
  }

  /** ★ 契约故障（ERROR 不降级；由调用方冒泡到提案级，整个候选提交被拒）。 */
  private static void contractFault(String reason, String detail) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "PRODUCTION_CONTRACT_FAULT",
                ProductionLogSource.PRODUCTION_RUNTIME,
                "reason",
                reason,
                "detail",
                detail));
    throw new ProductionContractFault(reason, detail);
  }
}

package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.migrate.LegacyClassStructure;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassPosition.RelationToMeans;
import io.mosire.simos.economy.model.ClassPosition.SurplusRole;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.DebtCapacity;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HexCrisisSignal;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.map.hex.HexCoord;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>E5b：清算阶段 + 阶层下滑 + hex 危机信号</b>（理想架构 §2.8/§5.3/§5.4/§7.2/§7.3；计划 E5）。
 *
 * <p>★★ <b>位置与去重</b>：本阶段在日结算的 <b>4b 借粮 → 4c 偿还 → 5 计息 → 5b 经营者退出</b> 之后、<b>5c 阶层写回</b> 之前执行，只在
 * {@code anyCycleClosed == true} 的关账日推进（与 5 计息同窗口；<b>异质周期世界按关账日/结算日计周期</b>，见报告）。 5b
 * 是"经营者退出"路径：它已经用库存/货币还过一轮、不足才把合同标 {@link DebtStatus#DEFAULTED}；本阶段只对 <b>此刻本金仍 &gt; 0</b> 的合同选路，5b
 * 已结清的合同本金为 0 ⇒ 天然不会被二次处置（去重口径写在这里，不靠两边各自记状态）。
 *
 * <pre>
 * ① 压力判定（逐户逐合同，读 E4b DebtCapacity 与 E4c 偿还读数；见 {@link #contractStressed}）
 * ② 连续压力计数器（ClassStanding.consecutiveDebtStressCycles；阈值 {@link #DEBT_STRESS_CYCLES_THRESHOLD}）
 * ③ 触发清算 = status==DEFAULTED，或连续压力 ≥ 阈值且本合同本期受压
 * ④ 触发阶层下滑 = 核心生产资料被处置，或 DEFAULTED 且压力 ≥ 阈值，或 F 持续不足下一轮必要投入
 * ⑤ planner 只读选路（{@link #plan}）→ apply（{@link #apply}）一次性写入
 * </pre>
 *
 * <p>★★ <b>计数器口径（写清，避免"字段名只像债务"的误读）</b>：{@code consecutiveDebtStressCycles} 统计<b>连续</b>出现
 * "任一合同受压"<b>或</b>"E4b 的 {@code F} 项不足下一轮必要投入（{@code income &lt; 口粮 + 下一轮投入 + 税}）"的关账周期数；
 * 两者都清零的条件是当个周期既无债务压力、下一轮投入又可覆盖。★ 把第三条下滑判据（{@code F} 持续不足）的"持续"持久化在同一个计数器里， 是 E5b
 * 明确允许的<b>等价持久判据</b>（不新增状态组件、不加第二份真值）；没有这个计数器时，{@code DEFAULTED} 只能靠合同状态持久、 而 {@code F} 缺口跨重启就丢了。
 *
 * <p>★★ <b>清算只换生产资料所有权，不搬粮/钱</b>：apply 里只有三条写口 —— {@link AssetShareBook#apply}（资产份额）、 {@link
 * DebtContractBook#reduce}/{@code markStatus}（债务本金/状态）、{@code EconomyStateBuilder.classStandings()}
 * / {@code crisisSignals()}（阶层与信号）。<b>不调用 applyTransfer、不碰账户</b>（§5.4"处置必须与债务本金扣减、资产份额转移、
 * 阶层归属变化在同一原子流程里完成"）。
 *
 * <p>★★ <b>planner 只读、apply 不再抛业务异常</b>：planner 把所有数量（处置量、减本额、质押减量、保护线、退化选择、无法处置的具名原因）
 * 一次算完并校验；apply 只做"照单执行"。唯一的 fail-closed 守卫在 {@link AssetShareBook#apply} 里（源存在/数量够/质押上界）， planner
 * 已按同一批 Move 在覆盖层上预演过 ⇒ apply 正常路径不抛。
 *
 * <p>★★ <b>不硬折</b>：{@code MARKET}/{@code AGREED} 目前没有稳定价格源 ⇒ 具名 {@code unpriced} 跳过；{@code POLICY}
 * 按 {@code policyValuePerUnitMilli} 折算。价格以"毫值/单位"计，只对<b>粮债</b>接线（粮是硬通货；货币/其它商品债需要汇率， 没有就不折，记 {@code
 * unpriced-debt-unit}）。{@code MARKET_FIRST} 没有市场 acteur（资产市场未落地）⇒ 具名跳过，不造第二套市场。
 *
 * <p>★★ <b>稳定序</b>：合同按 {@link DebtContractId#value()}；质押按 {@code priority →
 * AssetRule.liquidationPriority → AssetShareId.value() → PledgeId.value()}；家户按 {@link
 * HouseholdId#value()}。所有排序不依赖 Map 迭代序/哈希序。
 */
public final class EconomyLiquidationSettlement {

  /** ★★ <b>清算阈值：连续债务压力周期数</b>（2 个关账周期）。只有 {@code status == DEFAULTED} 或达到本阈值才触发清算； 未到期的正常债务不卖地。 */
  public static final long DEBT_STRESS_CYCLES_THRESHOLD = 2L;

  /**
   * ★★ <b>债务爆炸阈值</b>：本金 / max(1, F) 达到 4 倍（F &gt; 0 时），或连续压力 ≥ 阈值且本金净增（F = 0 时） ⇒ 写 {@code
   * DEBT_EXPLOSION}。它是 hex 警告的读数阈值，不是自动政治行动。
   */
  public static final long DEBT_EXPLOSION_F_MULTIPLE = 4L;

  /** ★ 下滑目标的规则扩展位键名（优先于机械序，见类注）。 */
  public static final String DOWNWARD_POSITION_EXTENSION_KEY = "downwardPositionId";

  /** ★ 新建 {@code ClassStanding} 的具名原因（尚未发生阶层迁移）。 */
  public static final String STANDING_SEED_REASON = "e5b:classStandingSeed";

  /** ★ 信号/审计的稳定动作词（读口按它分类，不在多处拼字符串）。 */
  static final String ACTION_DISPOSED = "disposed";

  static final String ACTION_SKIPPED = "skipped";
  static final String ACTION_DEGRADED = "degraded-rule-selection";
  static final String ACTION_CLASS_DECLINE = "class-decline";
  static final String ACTION_DEBT_EXPLOSION = "debt-explosion";
  static final String ACTION_PROJECTION_FALLBACK = "class-projection-fallback";

  /** ★ 无 ACTIVE 质押时自动挂质押的确定性 id 前缀（不含 {@code "."}/{@code "|"}）。 */
  private static final String AUTO_PLEDGE_ID_PREFIX = "autopledge-";

  /** 清算/阶层下滑日志（settlement 分类）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.settlement();

  private EconomyLiquidationSettlement() {}

  /** ★ 本阶段是否接线：新表（mode/位置/归属/质押/政策/资产规则）任一非空即启用；全空 = 旧档 ⇒ 整段 no-op，旧路径逐值不变。 */
  public static boolean isActive(EconomyData base) {
    Objects.requireNonNull(base, "base");
    return !base.modes().isEmpty()
        || !base.classPositions().isEmpty()
        || !base.classStandings().isEmpty()
        || !base.pledges().isEmpty()
        || !base.liquidationPolicies().isEmpty()
        || !base.assetRules().isEmpty();
  }

  /**
   * ★★ <b>E5b 阶段入口</b>（包内可见；只由 {@code EconomySettlement} 的关账分支调用，协调器单线程）。
   *
   * @param session 当次推进会话（写 work copy 的唯一入口）
   * @param day 当前结算日（绝对世界日）
   * @param currentCycle 正在进行的周期序号（关账前的口径；与 4c/5 的偿还/计息同源）
   * @param rows 家户行工作副本
   * @param assetShares 资产份额工作副本（唯一写口 {@link AssetShareBook}）
   * @param debts 债务合同工作副本（唯一写口 {@link DebtContractBook}）
   * @param principalAtDayStart 当日起始本金快照（在任何 借/还 之前取；用于"本金未下降"与利息口径）
   * @param repaidPrincipalByDebt E4c 本日逐合同偿还读数（本金减少的具名证据；可为空表）
   * @param debtCapacities 本关账日终态的 E4b 容量（F/headroom 的唯一算法；见 {@code DebtCapacityBook}）
   * @param industries 产业模板表（{@link AssetShareBook#apply} 的存在性守卫读它）
   * @param ledger 当天审计累加器（清算事件只进这里，不落盘）
   */
  static void settle(
      EconomySession session,
      long day,
      long currentCycle,
      Map<HouseholdId, ClassRow> rows,
      Map<AssetShareId, AssetShare> assetShares,
      Map<PledgeId, Pledge> pledges,
      Map<DebtContractId, DebtContract> debts,
      Map<DebtContractId, Long> principalAtDayStart,
      Map<DebtContractId, Long> repaidPrincipalByDebt,
      Map<HouseholdId, DebtCapacity> debtCapacities,
      Map<IndustryId, Industry> industries,
      ProductionLedger.Accumulator ledger) {
    Objects.requireNonNull(session, "session 不得为 null");
    Objects.requireNonNull(ledger, "ledger 不得为 null");
    Objects.requireNonNull(rows, "rows 不得为 null");
    Objects.requireNonNull(assetShares, "assetShares 不得为 null");
    Objects.requireNonNull(pledges, "pledges 不得为 null");
    Objects.requireNonNull(debts, "debts 不得为 null");
    Objects.requireNonNull(principalAtDayStart, "principalAtDayStart 不得为 null");
    Objects.requireNonNull(repaidPrincipalByDebt, "repaidPrincipalByDebt 不得为 null");
    if (day < 0L) {
      throw new IllegalArgumentException("E5b 的 day 不得为负: " + day);
    }
    EconomyData base = session.base();
    Context context =
        new Context(
            session,
            base,
            day,
            currentCycle,
            rows,
            assetShares,
            pledges,
            debts,
            principalAtDayStart,
            repaidPrincipalByDebt,
            debtCapacities,
            industries,
            ledger);
    Plan plan = plan(context);
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "event=LIQUIDATION_PLAN day={} stressUpdates={} debtReductions={} pledgeUpdates={} audits={} declines={} explosions={} autoDefaults={}",
          day,
          plan.stressUpdates().size(),
          plan.debtReductions().size(),
          plan.pledgeUpdates().size(),
          plan.audits().size(),
          plan.declines().size(),
          plan.explosions().size(),
          plan.autoDefaults().size());
    }
    apply(context, plan);
    if (LOG.isDebugEnabled()) {
      LOG.debug("event=LIQUIDATION_APPLIED day={} audits={}", day, plan.audits().size());
    }
  }

  /** planner 的只读输入（所有 Map 都是调用方的当刻工作副本引用；planner 不写它们）。 */
  private record Context(
      EconomySession session,
      EconomyData base,
      long day,
      long currentCycle,
      Map<HouseholdId, ClassRow> rows,
      Map<AssetShareId, AssetShare> assetShares,
      Map<PledgeId, Pledge> pledges,
      Map<DebtContractId, DebtContract> debts,
      Map<DebtContractId, Long> principalAtDayStart,
      Map<DebtContractId, Long> repaidPrincipalByDebt,
      Map<HouseholdId, DebtCapacity> debtCapacities,
      Map<IndustryId, Industry> industries,
      ProductionLedger.Accumulator ledger) {}

  /** 一条已选路的资产转移（新份额 id 在 apply 时由 {@link AssetShareBook} 确定性生成）。 */
  private record PlannedMove(
      DebtContractId debtId,
      PledgeId pledgeId,
      AssetShareId source,
      long quantity,
      long valueMilli,
      long pricePerUnitMilli,
      AssetRuleId ruleId,
      boolean coreMeans,
      String pricing,
      String recipientRule) {}

  /** 一个合同的清算计划：按稳定序的 Move + 质押终态 + 无法处置的具名审计。 */
  private record DebtReductionPlan(
      DebtContract debt,
      List<PlannedMove> moves,
      List<PledgeUpdate> pledgeUpdates,
      long totalValueMilli,
      long principalAfter,
      boolean coreMeansDisposed,
      List<AuditEntry> audits) {}

  /** 一条质押的计划终态（减量/EXECUTED/RELEASED）。 */
  private record PledgeUpdate(PledgeId pledgeId, Pledge after) {}

  /** 一户的压力读数与计数器更新（nextCount 已按"能否持久化"裁决）。 */
  private record StressUpdate(
      HouseholdId household,
      long previousCount,
      long nextCount,
      boolean stressed,
      Map<String, Long> evidence) {}

  /** 一次阶层下滑决策（to == null = 目标缺失，不迁移、仍写信号）。 */
  private record ClassDeclinePlan(
      HouseholdId household,
      HexCoord hex,
      ClassPositionId from,
      ClassPositionId to,
      boolean migrated,
      String reason,
      int severity,
      long stressCycles) {}

  /** 一次债务爆炸读数。 */
  private record DebtExplosionPlan(
      HouseholdId household,
      HexCoord hex,
      long principalTotalMilli,
      long FMilli,
      long principalToFMultiple,
      long netPrincipalDeltaMilli,
      long stressCycles,
      int severity,
      String reason,
      Map<String, Long> evidence) {}

  /** 内部审计条目（apply 时转成 {@link ProductionLedger.LiquidationAudit}；可选引用用 null 表示"没有"）。 */
  private record AuditEntry(
      long day,
      String action,
      HouseholdId household,
      DebtContractId contractId,
      PledgeId pledgeId,
      AssetShareId sourceAssetShareId,
      AssetShareId createdAssetShareId,
      long quantity,
      long pricePerUnitMilli,
      long debtReductionMilli,
      long debtPrincipalAfter,
      long pledgeQuantityAfter,
      String reason,
      Map<String, Long> evidence) {}

  /** planner 结果：只读、完整、可重放；apply 只是它的执行。 */
  private record Plan(
      List<StressUpdate> stressUpdates,
      List<DebtReductionPlan> debtReductions,
      List<PledgeUpdate> pledgeUpdates,
      List<AuditEntry> audits,
      List<ClassDeclinePlan> declines,
      List<DebtExplosionPlan> explosions,
      List<DebtContractId> autoDefaults) {}

  /** 规则/政策选路结果；{@code failureReason != null} = 不处置（具名）。 */
  private record RulePolicySelection(
      AssetRule rule, LiquidationPolicy policy, String degradedFrom, String failureReason) {}

  /** 一个质押候选（选路结果 + 失败原因；失败也留在序里以便写具名审计）。 */
  private record PledgeCandidate(
      Pledge pledge,
      AssetShare share,
      AssetRule rule,
      LiquidationPolicy policy,
      String degradedFrom,
      String failureReason) {}

  /** 自动挂质押的候选（折后可用量的自有份额 + 可质押规则）。 */
  private record AutoPledgeCandidate(AssetShare share, AssetRule rule, long availableQuantity) {}

  /** 价格选路结果：{@code pricePerUnitMilli} 与政策一起返回，保证"价"与"保护线/比例"同源。 */
  private record SelectedPrice(long pricePerUnitMilli, LiquidationPolicy policy) {}

  /** 下滑目标结果（target 空 = 具名失败，不迁移）。 */
  private record DownwardTarget(Optional<ClassPositionId> target, String failureReason) {}

  // ── planner ─────────────────────────────────────────────────────────────────────────────

  private static Plan plan(Context context) {
    EconomyData base = context.base();
    Map<HouseholdId, ClassStanding> baseStandings = base.classStandings();
    Map<ClassPositionId, ClassPosition> positions = base.classPositions();
    List<AuditEntry> audits = new ArrayList<>();

    Map<HouseholdId, List<DebtContract>> debtsByDebtor = indexDebtsByDebtor(context.debts());
    Map<DebtContractId, List<Pledge>> pledgesByDebt = indexActivePledgesByDebt(context.pledges());
    List<HouseholdId> households = sortedHouseholds(context.rows());
    // ★★ 到期即默认：先用有效状态参与本轮的应力/触发判定，需落状态的在 apply 统一 markStatus。
    List<DebtContractId> autoDefaults = new ArrayList<>();
    Map<DebtContractId, DebtStatus> effectiveStatuses = effectiveStatuses(context, autoDefaults);

    // ── 1. 压力判定 + 连续计数器（逐户，稳定 HouseholdId 序）──────────────────────────────
    Map<HouseholdId, Long> nextCounts = new LinkedHashMap<>();
    Map<HouseholdId, Set<DebtContractId>> stressedContractsByHousehold = new LinkedHashMap<>();
    Map<HouseholdId, Boolean> inputShortfallByHousehold = new LinkedHashMap<>();
    List<StressUpdate> stressUpdates = new ArrayList<>();
    for (HouseholdId household : households) {
      List<DebtContract> active = activeDebts(debtsByDebtor.getOrDefault(household, List.of()));
      DebtCapacity capacity = context.debtCapacities().get(household);
      Map<String, Long> evidence = new LinkedHashMap<>();
      Set<DebtContractId> stressedContracts = new LinkedHashSet<>();
      boolean anyStress = false;
      long principalTotal = 0L;
      long startTotal = 0L;
      long interestTotal = 0L;
      long repaidToday = 0L;
      int defaultedContracts = 0;
      int overdueContracts = 0;
      int shortContracts = 0;
      int grainContracts = 0;
      int nonGrainContracts = 0;
      for (DebtContract debt : active) {
        long startPrincipal = context.principalAtDayStart().getOrDefault(debt.id(), 0L);
        long currentPrincipal = debt.principal();
        long cycleInterest = perMille(startPrincipal, debt.terms().interestRatePerMillePerCycle());
        // 到期口径：合同滚动 dueCycle 优先（借入/资本化会刷新它）；没有滚动值时读 terms 的固定期限维。
        boolean overdue = contractDueCycle(debt) <= context.currentCycle;
        long required = overdue ? Math.max(cycleInterest, currentPrincipal) : cycleInterest;
        boolean principalDeclined = currentPrincipal < startPrincipal;
        // ★ F 是粮口径（E4b 只对粮 unit 算 F；非粮债进 unpriced 不硬折）⇒ 只有粮债能用
        //   "F < 本期利息/到期应还"这一路；非粮债只走 DEFAULTED/到期两路，避免跨 unit 比较。
        boolean shortOfRequired =
            isGrainDebt(debt) && capacity != null && capacity.F() < required && !principalDeclined;
        DebtStatus effectiveStatus = effectiveStatuses.getOrDefault(debt.id(), debt.status());
        boolean stressed = contractStressed(effectiveStatus, overdue, shortOfRequired);
        if (stressed) {
          anyStress = true;
          stressedContracts.add(debt.id());
        }
        if (effectiveStatus == DebtStatus.DEFAULTED) {
          defaultedContracts++;
        }
        if (overdue) {
          overdueContracts++;
        }
        if (shortOfRequired) {
          shortContracts++;
        }
        if (!isGrainDebt(debt)) {
          nonGrainContracts++;
        } else {
          // ★ 只对粮 unit 做金额聚合：principal/interest/已偿都是各自 unit 的最小单位，
          //   跨 unit 相加就是把银/布硬折成粮（与 F 的粮口径冲突）。
          grainContracts++;
          principalTotal = saturatedAdd(principalTotal, currentPrincipal);
          startTotal = saturatedAdd(startTotal, startPrincipal);
          interestTotal = saturatedAdd(interestTotal, cycleInterest);
          repaidToday =
              saturatedAdd(
                  repaidToday, context.repaidPrincipalByDebt().getOrDefault(debt.id(), 0L));
        }
      }
      boolean inputShortfall = capacity != null && nextRoundInputNotFunded(capacity);
      boolean counterStress = anyStress || inputShortfall;
      Optional<ClassPositionId> resolvable =
          resolvablePosition(context, household, baseStandings, positions);
      long previous =
          baseStandings.get(household) == null
              ? 0L
              : baseStandings.get(household).consecutiveDebtStressCycles();
      long incremented = previous == Long.MAX_VALUE ? Long.MAX_VALUE : previous + 1L;
      long next =
          counterStress && resolvable.isPresent() ? incremented : (counterStress ? previous : 0L);
      nextCounts.put(household, next);
      stressedContractsByHousehold.put(household, stressedContracts);
      inputShortfallByHousehold.put(household, inputShortfall);
      evidence.put("grainPrincipalTotalMilli", principalTotal);
      evidence.put("grainPrincipalAtDayStartMilli", startTotal);
      evidence.put("grainInterestChargedMilli", interestTotal);
      evidence.put("grainContracts", (long) grainContracts);
      evidence.put("nonGrainContracts", (long) nonGrainContracts);
      evidence.put("defaultedContracts", (long) defaultedContracts);
      evidence.put("overdueContracts", (long) overdueContracts);
      evidence.put("shortOfRequiredContracts", (long) shortContracts);
      if (capacity == null) {
        evidence.put("FUnavailable", 1L);
      } else {
        evidence.put("FMilli", capacity.F());
        evidence.put("basicRationMilli", capacity.basicRation());
        evidence.put("nextRoundNecessaryInputMilli", capacity.nextRoundNecessaryInput());
        evidence.put("afterAllocationGrainIncomeMilli", capacity.afterAllocationGrainIncome());
        evidence.put("inputNotFunded", inputShortfall ? 1L : 0L);
      }
      evidence.put("repaidPrincipalTodayMilli", repaidToday);
      if (next != previous) {
        stressUpdates.add(new StressUpdate(household, previous, next, counterStress, evidence));
      } else if (counterStress && resolvable.isEmpty()) {
        audits.add(
            audit(
                context.day(),
                ACTION_SKIPPED,
                household,
                null,
                null,
                null,
                null,
                "stress-counter-unpersisted:no-current-position",
                evidence));
      }
    }

    // ── 2. 触发合同（默认违约立即；连续压力达标且**本合同本期受压**）────────────────────────
    List<DebtContract> triggered = new ArrayList<>();
    for (HouseholdId household : households) {
      long count = nextCounts.getOrDefault(household, 0L);
      boolean thresholdReached = count >= DEBT_STRESS_CYCLES_THRESHOLD;
      Set<DebtContractId> stressedContracts =
          stressedContractsByHousehold.getOrDefault(household, Set.of());
      for (DebtContract debt : activeDebts(debtsByDebtor.getOrDefault(household, List.of()))) {
        DebtStatus effectiveStatus = effectiveStatuses.getOrDefault(debt.id(), debt.status());
        if (effectiveStatus == DebtStatus.DEFAULTED
            || (thresholdReached && stressedContracts.contains(debt.id()))) {
          triggered.add(debt);
        }
      }
    }
    triggered.sort(Comparator.comparing(debt -> debt.id().value()));

    // ── 3. 无 ACTIVE 质押的触发债务先自动挂质押（同一 pledgeUpdates apply 路径；planner 立即读它）──
    Map<AssetShareId, Long> activePledgeQuantity = activePledgeQuantityByShare(context.pledges());
    List<PledgeUpdate> autoPledgeUpdates = new ArrayList<>();
    for (DebtContract debt : triggered) {
      List<Pledge> active = pledgesByDebt.get(debt.id());
      if (active != null && !active.isEmpty()) {
        continue;
      }
      Optional<Pledge> autoPledge = autoPledgeFor(context, debt, activePledgeQuantity);
      if (autoPledge.isPresent()) {
        Pledge pledge = autoPledge.get();
        pledgesByDebt.computeIfAbsent(debt.id(), ignored -> new ArrayList<>()).add(pledge);
        activePledgeQuantity.merge(
            pledge.assetShareId(), pledge.quantity(), EconomyLiquidationSettlement::saturatedAdd);
        autoPledgeUpdates.add(new PledgeUpdate(pledge.id(), pledge));
      }
    }

    // ── 3b. 逐合同选路（planner 只读；availableQuantity 覆盖层保证同一份额不被超卖）───────────
    Map<AssetShareId, Long> availableQuantity = new LinkedHashMap<>();
    List<DebtReductionPlan> reductions = new ArrayList<>();
    List<PledgeUpdate> pledgeUpdates = new ArrayList<>(autoPledgeUpdates);
    for (DebtContract debt : triggered) {
      DebtReductionPlan reduction =
          planDebtReduction(
              context,
              debt,
              pledgesByDebt.getOrDefault(debt.id(), List.of()),
              availableQuantity,
              audits);
      reductions.add(reduction);
      pledgeUpdates.addAll(reduction.pledgeUpdates());
      audits.addAll(reduction.audits());
    }

    // ── 4. 阶层下滑（核心被处置 / DEFAULTED 且压力≥阈值 / F 持续不足下一轮投入）─────────────
    Map<HouseholdId, Boolean> coreDisposedByHousehold = new LinkedHashMap<>();
    for (DebtReductionPlan reduction : reductions) {
      if (reduction.coreMeansDisposed()) {
        coreDisposedByHousehold.put(reduction.debt().debtor(), true);
      }
    }
    List<ClassDeclinePlan> declines = new ArrayList<>();
    for (HouseholdId household : households) {
      long count = nextCounts.getOrDefault(household, 0L);
      boolean defaultedAtThreshold = false;
      for (DebtContract debt : activeDebts(debtsByDebtor.getOrDefault(household, List.of()))) {
        DebtStatus effectiveStatus = effectiveStatuses.getOrDefault(debt.id(), debt.status());
        if (effectiveStatus == DebtStatus.DEFAULTED && count >= DEBT_STRESS_CYCLES_THRESHOLD) {
          defaultedAtThreshold = true;
          break;
        }
      }
      boolean persistentInputShortfall =
          inputShortfallByHousehold.getOrDefault(household, false)
              && count >= DEBT_STRESS_CYCLES_THRESHOLD;
      boolean decline =
          coreDisposedByHousehold.getOrDefault(household, false)
              || defaultedAtThreshold
              || persistentInputShortfall;
      if (!decline) {
        continue;
      }
      ClassRow row = context.rows().get(household);
      if (row == null) {
        continue;
      }
      Optional<ClassPositionId> current =
          resolvablePosition(context, household, baseStandings, positions);
      ClassPositionId target = null;
      String reason;
      boolean migrated = false;
      int severity;
      if (current.isEmpty()) {
        reason = "class-decline:no-current-position";
        severity = 1;
      } else {
        DownwardTarget downward = resolveDownwardTarget(current.get(), positions);
        if (downward.target().isPresent()) {
          target = downward.target().get();
          migrated = true;
          reason = "class-decline:" + current.get().value() + "->" + target.value();
          severity = 2;
        } else {
          reason =
              "class-decline:no-downward-target:"
                  + current.get().value()
                  + ":"
                  + downward.failureReason();
          severity = 1;
        }
      }
      declines.add(
          new ClassDeclinePlan(
              household,
              row.view().hex(),
              current.orElse(null),
              target,
              migrated,
              reason,
              severity,
              count));
      Map<String, Long> evidence = new LinkedHashMap<>();
      evidence.put("stressCycles", count);
      evidence.put(
          "coreDisposed", coreDisposedByHousehold.getOrDefault(household, false) ? 1L : 0L);
      evidence.put("defaultedAtThreshold", defaultedAtThreshold ? 1L : 0L);
      evidence.put("persistentInputShortfall", persistentInputShortfall ? 1L : 0L);
      evidence.put("migrated", migrated ? 1L : 0L);
      audits.add(
          audit(
              context.day(),
              ACTION_CLASS_DECLINE,
              household,
              null,
              null,
              null,
              null,
              reason,
              evidence));
    }

    // ── 5. DEBT_EXPLOSION 读数（本金/F 比或"压力≥阈值且本金净增"）────────────────────────
    List<DebtExplosionPlan> explosions = new ArrayList<>();
    for (HouseholdId household : households) {
      List<DebtContract> active = activeDebts(debtsByDebtor.getOrDefault(household, List.of()));
      if (active.isEmpty()) {
        continue;
      }
      ClassRow row = context.rows().get(household);
      if (row == null) {
        continue;
      }
      // ★ 只把**粮 unit** 的本金相加：F 是粮口径，混入银/布本金就是把不同 unit 硬折成一个数。
      //   没有粮债 ⇒ 本判据不适用（非粮债各自的到期/违约仍在 E5b 的别处读）。
      long principalTotal = 0L;
      long startTotal = 0L;
      int grainContracts = 0;
      int nonGrainContracts = 0;
      for (DebtContract debt : active) {
        if (!isGrainDebt(debt)) {
          nonGrainContracts++;
          continue;
        }
        grainContracts++;
        principalTotal = saturatedAdd(principalTotal, debt.principal());
        startTotal =
            saturatedAdd(startTotal, context.principalAtDayStart().getOrDefault(debt.id(), 0L));
      }
      if (grainContracts == 0) {
        continue;
      }
      long netDelta = principalTotal - startTotal;
      DebtCapacity capacity = context.debtCapacities().get(household);
      long F = capacity == null ? -1L : capacity.F();
      long stressCycles = nextCounts.getOrDefault(household, 0L);
      // ★ 阈值口径：principal / max(1, F)（F 读不到时只走"连续压力 ≥ 阈值且本金净增"这一路，不用 0 冒充）。
      long multiple = capacity == null ? -1L : principalTotal / Math.max(1L, F);
      boolean ratioExplosion = capacity != null && multiple >= DEBT_EXPLOSION_F_MULTIPLE;
      boolean accumulationExplosion = stressCycles >= DEBT_STRESS_CYCLES_THRESHOLD && netDelta > 0L;
      if (!ratioExplosion && !accumulationExplosion) {
        continue;
      }
      int severity =
          ratioExplosion ? (int) Math.min(5L, 2L + multiple / DEBT_EXPLOSION_F_MULTIPLE) : 2;
      Map<String, Long> evidence = new LinkedHashMap<>();
      evidence.put("principalTotalMilli", principalTotal);
      evidence.put("FMilli", F);
      evidence.put("principalToFMultiple", multiple);
      evidence.put("netPrincipalDeltaMilli", netDelta);
      evidence.put("consecutiveStressCycles", stressCycles);
      evidence.put("thresholdMultiple", DEBT_EXPLOSION_F_MULTIPLE);
      evidence.put("grainDebtContracts", (long) grainContracts);
      evidence.put("nonGrainDebtContracts", (long) nonGrainContracts);
      if (capacity != null) {
        evidence.put("nextRoundNecessaryInputMilli", capacity.nextRoundNecessaryInput());
        evidence.put("afterAllocationGrainIncomeMilli", capacity.afterAllocationGrainIncome());
      } else {
        evidence.put("FUnavailable", 1L);
      }
      String reason =
          "debt-explosion:principalToFMultiple="
              + multiple
              + ";stressCycles="
              + stressCycles
              + ";window=preLiquidation"
              + ";unit=milli";
      explosions.add(
          new DebtExplosionPlan(
              household,
              row.view().hex(),
              principalTotal,
              F,
              multiple,
              netDelta,
              stressCycles,
              severity,
              reason,
              evidence));
      audits.add(
          audit(
              context.day(),
              ACTION_DEBT_EXPLOSION,
              household,
              null,
              null,
              null,
              null,
              reason,
              evidence));
    }

    return new Plan(
        stressUpdates, reductions, pledgeUpdates, audits, declines, explosions, autoDefaults);
  }

  // ── 逐合同选路 ───────────────────────────────────────────────────────────────────────────

  private static DebtReductionPlan planDebtReduction(
      Context context,
      DebtContract debt,
      List<Pledge> activePledges,
      Map<AssetShareId, Long> availableQuantity,
      List<AuditEntry> audits) {
    if (activePledges.isEmpty()) {
      audits.add(
          audit(
              context.day(),
              ACTION_SKIPPED,
              debt.debtor(),
              debt.id(),
              null,
              null,
              null,
              "no-active-pledge",
              Map.of("principalMilli", debt.principal())));
      return new DebtReductionPlan(
          debt, List.of(), List.of(), 0L, debt.principal(), false, List.of());
    }

    List<PledgeCandidate> candidates = new ArrayList<>(activePledges.size());
    for (Pledge pledge : activePledges) {
      candidates.add(selectCandidate(context, debt, pledge));
    }
    candidates.sort(
        Comparator.comparingInt((PledgeCandidate candidate) -> candidate.pledge().priority())
            .thenComparingInt(
                candidate ->
                    candidate.rule() == null
                        ? Integer.MAX_VALUE
                        : candidate.rule().liquidationPriority())
            .thenComparing(candidate -> candidate.pledge().assetShareId().value())
            .thenComparing(candidate -> candidate.pledge().id().value()));

    long remainingDebt = debt.principal();
    List<PlannedMove> moves = new ArrayList<>();
    Map<PledgeId, Pledge> pledgeAfter = new LinkedHashMap<>();
    List<AuditEntry> localAudits = new ArrayList<>();
    boolean coreDisposed = false;
    for (PledgeCandidate candidate : candidates) {
      if (remainingDebt <= 0L) {
        break;
      }
      Pledge pledge = candidate.pledge();
      AssetShare share = candidate.share();
      if (candidate.failureReason() != null) {
        localAudits.add(
            skipAudit(
                context.day(),
                debt,
                pledge,
                candidate.failureReason(),
                Map.of("principalMilli", remainingDebt, "pledgeQuantity", pledge.quantity())));
        continue;
      }
      if (candidate.degradedFrom() != null) {
        localAudits.add(
            audit(
                context.day(),
                ACTION_DEGRADED,
                debt.debtor(),
                debt.id(),
                pledge.id(),
                pledge.assetShareId(),
                null,
                "degraded-selection:" + candidate.degradedFrom(),
                Map.of(
                    "selectedRulePriority",
                    (long) candidate.rule().liquidationPriority(),
                    "pledgeQuantity",
                    pledge.quantity())));
      }
      if (candidate.policy().recipientRule() == LiquidationPolicy.RecipientRule.MARKET_FIRST) {
        // ★ 资产市场尚未落地（没有市场 acteur）：MARKET_FIRST 不硬走"第二套市场"，具名跳过；
        //   降级为 CREDITOR_FIRST 会让政策本身失真，故本阶段选择跳过而不是暗中改受偿对象。
        localAudits.add(
            skipAudit(
                context.day(),
                debt,
                pledge,
                "recipient-rule-market-not-landed",
                Map.of("principalMilli", remainingDebt, "pledgeQuantity", pledge.quantity())));
        continue;
      }
      SelectedPrice price =
          selectedPrice(candidate.policy(), context.day(), debt, pledge, localAudits);
      if (price == null) {
        continue;
      }
      if (!(debt.unit() instanceof DebtUnit.Commodity commodity)
          || !commodity.commodity().equals(EconomySettlement.GRAIN)) {
        localAudits.add(
            skipAudit(
                context.day(),
                debt,
                pledge,
                "unpriced-debt-unit:" + debt.unit().key(),
                Map.of("principalMilli", remainingDebt, "pledgeQuantity", pledge.quantity())));
        continue;
      }
      long available = availableQuantity.getOrDefault(pledge.assetShareId(), share.quantity());
      long capacityProtected = Math.max(0L, available - price.policy().protectedReserve());
      long capacityPledge = perMille(pledge.quantity(), price.policy().maxLiquidatePerMille());
      long capacityDebt = remainingDebt / price.pricePerUnitMilli();
      long quantity =
          Math.min(Math.min(capacityDebt, capacityPledge), Math.min(capacityProtected, available));
      if (quantity <= 0L) {
        localAudits.add(
            skipAudit(
                context.day(),
                debt,
                pledge,
                "no-disposable-quantity",
                Map.of(
                    "principalMilli", remainingDebt,
                    "pricePerUnitMilli", price.pricePerUnitMilli(),
                    "pledgeCapacity", capacityPledge,
                    "protectedCapacity", capacityProtected)));
        continue;
      }
      long value = quantity * price.pricePerUnitMilli();
      if (value <= 0L || value > remainingDebt) {
        localAudits.add(
            skipAudit(
                context.day(),
                debt,
                pledge,
                "value-guard:value=" + value + ";remaining=" + remainingDebt,
                Map.of(
                    "principalMilli",
                    remainingDebt,
                    "pricePerUnitMilli",
                    price.pricePerUnitMilli())));
        continue;
      }
      moves.add(
          new PlannedMove(
              debt.id(),
              pledge.id(),
              pledge.assetShareId(),
              quantity,
              value,
              price.pricePerUnitMilli(),
              candidate.rule().id(),
              candidate.rule().isCoreMeans(),
              candidate.policy().priceSource().name(),
              candidate.policy().recipientRule().name()));
      availableQuantity.put(pledge.assetShareId(), available - quantity);
      remainingDebt -= value;
      if (candidate.rule().isCoreMeans()) {
        coreDisposed = true;
      }
      long pledgeLeft = pledge.quantity() - quantity;
      Pledge after = pledge.withQuantity(pledgeLeft);
      if (pledgeLeft == 0L) {
        after = after.withStatus(Pledge.Status.EXECUTED);
      }
      pledgeAfter.put(pledge.id(), after);
    }

    long totalValue = debt.principal() - remainingDebt;
    if (totalValue > 0L && remainingDebt == 0L) {
      // 债已结清 ⇒ 余下 ACTIVE 质押没有担保对象，全部释放（不留下"无债却占着份额"的质押）。
      for (Pledge pledge : activePledges) {
        Pledge current = pledgeAfter.getOrDefault(pledge.id(), pledge);
        if (current.status() == Pledge.Status.ACTIVE && current.quantity() > 0L) {
          pledgeAfter.put(pledge.id(), current.withStatus(Pledge.Status.RELEASED));
        }
      }
    }
    List<PledgeUpdate> pledgeUpdates = new ArrayList<>(pledgeAfter.size());
    for (Pledge afterPledge : pledgeAfter.values()) {
      pledgeUpdates.add(new PledgeUpdate(afterPledge.id(), afterPledge));
    }
    return new DebtReductionPlan(
        debt,
        List.copyOf(moves),
        List.copyOf(pledgeUpdates),
        totalValue,
        remainingDebt,
        coreDisposed,
        localAudits);
  }

  /** 一个质押的资格 + 规则/政策选路（纯读；失败原因具名，不抛业务异常）。 */
  private static PledgeCandidate selectCandidate(
      Context context, DebtContract debt, Pledge pledge) {
    AssetShare share = context.assetShares().get(pledge.assetShareId());
    if (share == null) {
      return new PledgeCandidate(pledge, null, null, null, null, "pledge-share-missing");
    }
    if (share.kind() != AssetShare.RightKind.OWNED
        && share.kind() != AssetShare.RightKind.COMMUNAL) {
      return new PledgeCandidate(
          pledge, share, null, null, null, "right-kind-not-disposable:" + share.kind().name());
    }
    if (!share.owner().equals(HouseholdActors.of(debt.debtor()))) {
      return new PledgeCandidate(pledge, share, null, null, null, "share-owner-not-debtor");
    }
    if (!context.industries().isEmpty() && !context.industries().containsKey(share.industry())) {
      // 与 AssetShareBook.apply 的存在性守卫同口径：产业模板已提供但缺这一条 ⇒ planner 先具名拒绝，
      // 不让 apply 在提交前才抛（"planner 只读选路、apply 不再抛业务异常"）。
      return new PledgeCandidate(pledge, share, null, null, null, "asset-industry-missing");
    }
    RulePolicySelection selection = selectRulePolicy(context, debt, pledge, share);
    if (selection.failureReason() != null) {
      return new PledgeCandidate(
          pledge, share, null, null, selection.degradedFrom(), selection.failureReason());
    }
    if (!selection.rule().transferRule().transferable()) {
      return new PledgeCandidate(
          pledge,
          share,
          selection.rule(),
          selection.policy(),
          selection.degradedFrom(),
          "asset-not-transferable");
    }
    if (selection.rule().transferRule().requiresOwnerConsent()
        && !share.owner().equals(HouseholdActors.of(debt.debtor()))) {
      // ★ owner == debtor 时，处置的是所有权人自己的份额（同意要求由所有权身份满足）；
      //   owner != debtor 的租佃份额不得由佃户卖 —— 这条在更上面已挡一次，这里再把规则位读实。
      return new PledgeCandidate(
          pledge,
          share,
          selection.rule(),
          selection.policy(),
          selection.degradedFrom(),
          "owner-consent-required");
    }
    if (!selection.rule().pledgeable()) {
      return new PledgeCandidate(
          pledge,
          share,
          selection.rule(),
          selection.policy(),
          selection.degradedFrom(),
          "asset-not-pledgeable");
    }
    return new PledgeCandidate(
        pledge, share, selection.rule(), selection.policy(), selection.degradedFrom(), null);
  }

  /** 优先取"债务人当前位置的 mode + assetKind"对应的规则/政策；否则按 assetKind 取 id 最小的有政策规则。 */
  private static RulePolicySelection selectRulePolicy(
      Context context, DebtContract debt, Pledge pledge, AssetShare share) {
    EconomyData base = context.base();
    ProductionModeId preferredMode =
        preferredMode(context, debt, pledge, base.classStandings(), base.classPositions());
    AssetRule preferredRule =
        preferredMode == null
            ? null
            : base.assetRules().get(AssetRuleId.idOf(preferredMode, share.asset()));
    if (preferredRule != null) {
      LiquidationPolicy policy = base.liquidationPolicies().get(preferredRule.id());
      if (policy != null) {
        return new RulePolicySelection(preferredRule, policy, null, null);
      }
    }
    List<AssetRule> sameKind = rulesOfKind(base, share.asset());
    if (sameKind.isEmpty()) {
      return new RulePolicySelection(
          null, null, null, "no-asset-rule-for-asset-kind:" + share.asset().name());
    }
    for (AssetRule rule : sameKind) {
      LiquidationPolicy policy = base.liquidationPolicies().get(rule.id());
      if (policy != null) {
        String degradedFrom =
            preferredRule == null
                ? (preferredMode == null ? "mode-unresolved" : "no-rule-for-preferred-mode")
                : preferredRule.id().value();
        return new RulePolicySelection(rule, policy, degradedFrom + "->" + rule.id().value(), null);
      }
    }
    String preferred = preferredRule == null ? "none" : preferredRule.id().value();
    return new RulePolicySelection(null, null, null, "no-liquidation-policy-for-rule:" + preferred);
  }

  /** 债务人当前位置的 mode（位置表空时退回 pledge.modeId；再没有 ⇒ 空）。 */
  private static ProductionModeId preferredMode(
      Context context,
      DebtContract debt,
      Pledge pledge,
      Map<HouseholdId, ClassStanding> standings,
      Map<ClassPositionId, ClassPosition> positions) {
    ClassStanding standing = standings.get(debt.debtor());
    if (standing != null) {
      ClassPosition position = positions.get(standing.currentPositionId());
      if (position != null) {
        return position.modeId();
      }
    }
    return context.base().modes().containsKey(pledge.modeId()) ? pledge.modeId() : null;
  }

  /** 同 assetKind 的规则按 ruleId.value() 升序（退化选择的稳定序）。 */
  private static List<AssetRule> rulesOfKind(EconomyData base, AssetKind assetKind) {
    List<AssetRule> rules = new ArrayList<>();
    for (AssetRule rule : base.assetRules().values()) {
      if (rule.assetKind() == assetKind) {
        rules.add(rule);
      }
    }
    rules.sort(Comparator.comparing(rule -> rule.id().value()));
    return rules;
  }

  /** 价格选路：POLICY 有正价才可折算；MARKET/AGREED 目前没有稳定价格源 ⇒ 具名 unpriced 跳过。 */
  private static SelectedPrice selectedPrice(
      LiquidationPolicy policy,
      long day,
      DebtContract debt,
      Pledge pledge,
      List<AuditEntry> audits) {
    switch (policy.priceSource()) {
      case POLICY -> {
        if (policy.policyValuePerUnitMilli() > 0L) {
          return new SelectedPrice(policy.policyValuePerUnitMilli(), policy);
        }
        audits.add(
            skipAudit(
                day,
                debt,
                pledge,
                "unpriced:policy-price-zero-or-missing",
                Map.of("protectedReserve", policy.protectedReserve())));
        return null;
      }
      case MARKET -> {
        audits.add(
            skipAudit(
                day,
                debt,
                pledge,
                "unpriced:market-price-not-landed",
                Map.of("protectedReserve", policy.protectedReserve())));
        return null;
      }
      case AGREED -> {
        audits.add(
            skipAudit(
                day,
                debt,
                pledge,
                "unpriced:agreed-price-not-landed",
                Map.of("protectedReserve", policy.protectedReserve())));
        return null;
      }
    }
    throw new IllegalStateException("未裁决的 LiquidationPolicy.PriceSource: " + policy.priceSource());
  }

  // ── apply：照单执行（资产份额唯一写口 → 债务唯一写口 → 阶层/信号 → 审计）──────────────────

  private static void apply(Context context, Plan plan) {
    // ★ 到期即默认：先落状态，后续 reduce/结清仍按既有唯一写口语义（DEFAULTED + 本金 0 ⇒ SETTLED）。
    for (DebtContractId debtId : plan.autoDefaults()) {
      DebtContractBook.markStatus(context.debts(), debtId, DebtStatus.DEFAULTED);
    }
    List<AssetShareBook.Move> moves = new ArrayList<>();
    for (DebtReductionPlan reduction : plan.debtReductions()) {
      for (PlannedMove move : reduction.moves()) {
        moves.add(
            new AssetShareBook.Move(
                move.source(),
                move.quantity(),
                HouseholdActors.of(reduction.debt().creditor()),
                HouseholdActors.of(reduction.debt().creditor()),
                AssetShare.RightKind.OWNED));
      }
    }

    Map<PledgeId, Pledge> plannedPledges = new LinkedHashMap<>(context.pledges());
    for (PledgeUpdate update : plan.pledgeUpdates()) {
      plannedPledges.put(update.pledgeId(), update.after());
    }
    List<AssetShareId> created =
        moves.isEmpty()
            ? List.of()
            : AssetShareBook.apply(
                context.assetShares(), context.industries(), plannedPledges, moves);

    for (PledgeUpdate update : plan.pledgeUpdates()) {
      context.pledges().put(update.pledgeId(), update.after());
    }

    int moveIndex = 0;
    for (DebtReductionPlan reduction : plan.debtReductions()) {
      if (reduction.moves().isEmpty()) {
        continue;
      }
      DebtContract after =
          DebtContractBook.reduce(
              context.debts(), reduction.debt().id(), reduction.totalValueMilli());
      if (after.principal() == 0L && after.status() == DebtStatus.DEFAULTED) {
        after =
            DebtContractBook.markStatus(context.debts(), reduction.debt().id(), DebtStatus.SETTLED);
      }
      for (PlannedMove move : reduction.moves()) {
        AssetShareId createdId = created.get(moveIndex++);
        context
            .ledger()
            .addLiquidationAudit(
                toLiquidationAudit(context.day(), reduction, move, createdId, after.principal()));
      }
    }

    writeStressAndDecline(context, plan);

    if (!plan.declines().isEmpty() || !plan.explosions().isEmpty()) {
      writeCrisisSignals(context, plan);
    }

    for (AuditEntry entry : plan.audits()) {
      context.ledger().addLiquidationAudit(toLiquidationAudit(entry));
    }
  }

  /** 压力计数器与阶层下滑落到 working copy；只有真的要写才物化 {@code classStandings} 工作副本。 */
  private static void writeStressAndDecline(Context context, Plan plan) {
    if (plan.stressUpdates().isEmpty() && plan.declines().isEmpty()) {
      return;
    }
    LinkedHashMap<HouseholdId, ClassStanding> standings =
        context.session().sheet().classStandings();
    Map<ClassPositionId, ClassPosition> positions = context.base().classPositions();
    Map<HouseholdId, ClassStanding> baseStandings = context.base().classStandings();

    for (StressUpdate update : plan.stressUpdates()) {
      ClassStanding existing = standings.get(update.household());
      if (existing == null) {
        Optional<ClassPositionId> position =
            resolvablePosition(context, update.household(), baseStandings, positions);
        if (position.isEmpty()) {
          continue; // planner 已写具名审计：不伪造位置
        }
        existing =
            new ClassStanding(
                update.household(),
                position.get(),
                position.get(),
                Map.of(),
                update.previousCount(),
                0L,
                STANDING_SEED_REASON);
      }
      standings.put(update.household(), withStressCount(existing, update.nextCount()));
    }

    for (ClassDeclinePlan decline : plan.declines()) {
      ClassStanding existing = standings.get(decline.household());
      if (existing == null) {
        Optional<ClassPositionId> position =
            resolvablePosition(context, decline.household(), baseStandings, positions);
        if (position.isEmpty()) {
          continue; // 无位置可写：信号与审计仍保留，但不伪造归属
        }
        existing =
            new ClassStanding(
                decline.household(),
                position.get(),
                position.get(),
                Map.of(),
                0L,
                0L,
                STANDING_SEED_REASON);
      }
      if (decline.to() != null) {
        standings.put(
            decline.household(),
            new ClassStanding(
                decline.household(),
                existing.originalPositionId(),
                decline.to(),
                existing.retainedShares(),
                0L,
                context.day(),
                decline.reason()));
      } else {
        standings.put(
            decline.household(),
            new ClassStanding(
                decline.household(),
                existing.originalPositionId(),
                existing.currentPositionId(),
                existing.retainedShares(),
                existing.consecutiveDebtStressCycles(),
                existing.lastTransitionDay(),
                decline.reason()));
      }
    }
  }

  /** 同 hex 同 kind 覆盖更新（键 = (hex, kind) 派生 id）；group 内按家户序聚合证据。 */
  private static void writeCrisisSignals(Context context, Plan plan) {
    LinkedHashMap<HexCoord, List<ClassDeclinePlan>> declinesByHex = new LinkedHashMap<>();
    for (ClassDeclinePlan decline : plan.declines()) {
      declinesByHex.computeIfAbsent(decline.hex(), ignored -> new ArrayList<>()).add(decline);
    }
    LinkedHashMap<HexCoord, List<DebtExplosionPlan>> explosionsByHex = new LinkedHashMap<>();
    for (DebtExplosionPlan explosion : plan.explosions()) {
      explosionsByHex.computeIfAbsent(explosion.hex(), ignored -> new ArrayList<>()).add(explosion);
    }
    if (declinesByHex.isEmpty() && explosionsByHex.isEmpty()) {
      return;
    }
    LinkedHashMap<CrisisSignalId, HexCrisisSignal> signals =
        context.session().sheet().crisisSignals();
    List<HexCoord> hexes = new ArrayList<>(declinesByHex.keySet());
    for (HexCoord hex : explosionsByHex.keySet()) {
      if (!declinesByHex.containsKey(hex)) {
        hexes.add(hex);
      }
    }
    hexes.sort(Comparator.naturalOrder());
    for (HexCoord hex : hexes) {
      List<ClassDeclinePlan> declines = declinesByHex.getOrDefault(hex, List.of());
      if (!declines.isEmpty()) {
        HexCrisisSignal signal = classDeclineSignal(context, hex, declines);
        signals.put(signal.id(), signal);
      }
      List<DebtExplosionPlan> explosions = explosionsByHex.getOrDefault(hex, List.of());
      if (!explosions.isEmpty()) {
        HexCrisisSignal signal = debtExplosionSignal(context, hex, explosions);
        signals.put(signal.id(), signal);
      }
    }
  }

  private static HexCrisisSignal classDeclineSignal(
      Context context, HexCoord hex, List<ClassDeclinePlan> declines) {
    List<ClassDeclinePlan> ordered = new ArrayList<>(declines);
    ordered.sort(Comparator.comparing(decline -> decline.household().value()));
    LinkedHashMap<String, Long> evidence = new LinkedHashMap<>();
    List<HouseholdId> households = new ArrayList<>();
    Set<SocialClassId> classes = new LinkedHashSet<>();
    StringBuilder reason = new StringBuilder("class-decline");
    int migrated = 0;
    int noTarget = 0;
    int maxSeverity = 1;
    for (ClassDeclinePlan decline : ordered) {
      households.add(decline.household());
      ClassRow row = context.rows().get(decline.household());
      if (row != null) {
        classes.add(row.view().stratum());
      }
      if (decline.migrated()) {
        migrated++;
      } else {
        noTarget++;
      }
      maxSeverity = Math.max(maxSeverity, decline.severity());
      reason
          .append(';')
          .append(decline.household().value())
          .append('=')
          .append(decline.from() == null ? "no-current-position" : decline.from().value())
          .append("->")
          .append(decline.to() == null ? "none" : decline.to().value());
    }
    evidence.put("declines", (long) ordered.size());
    evidence.put("migrated", (long) migrated);
    evidence.put("noTarget", (long) noTarget);
    evidence.put(
        "minStressCycles",
        ordered.stream().mapToLong(ClassDeclinePlan::stressCycles).min().orElse(0L));
    return new HexCrisisSignal(
        CrisisSignalId.idOf(hex, HexCrisisSignal.Kind.CLASS_DECLINE.name()),
        hex,
        HexCrisisSignal.Kind.CLASS_DECLINE,
        maxSeverity,
        context.day(),
        evidence,
        households,
        new ArrayList<>(classes),
        reason.toString());
  }

  private static HexCrisisSignal debtExplosionSignal(
      Context context, HexCoord hex, List<DebtExplosionPlan> explosions) {
    List<DebtExplosionPlan> ordered = new ArrayList<>(explosions);
    ordered.sort(Comparator.comparing(explosion -> explosion.household().value()));
    LinkedHashMap<String, Long> evidence = new LinkedHashMap<>();
    List<HouseholdId> households = new ArrayList<>();
    Set<SocialClassId> classes = new LinkedHashSet<>();
    StringBuilder reason = new StringBuilder("debt-explosion");
    long principalTotal = 0L;
    long netDelta = 0L;
    long maxCycles = 0L;
    long minF = Long.MAX_VALUE;
    long maxMultiple = 0L;
    int severity = 1;
    int unknownF = 0;
    for (DebtExplosionPlan explosion : ordered) {
      households.add(explosion.household());
      ClassRow row = context.rows().get(explosion.household());
      if (row != null) {
        classes.add(row.view().stratum());
      }
      principalTotal = saturatedAdd(principalTotal, explosion.principalTotalMilli());
      netDelta = saturatedAdd(netDelta, explosion.netPrincipalDeltaMilli());
      maxCycles = Math.max(maxCycles, explosion.stressCycles());
      maxMultiple = Math.max(maxMultiple, Math.max(0L, explosion.principalToFMultiple()));
      if (explosion.FMilli() < 0L) {
        unknownF++;
      } else {
        minF = Math.min(minF, explosion.FMilli());
      }
      severity = Math.max(severity, explosion.severity());
      reason.append(';').append(explosion.reason());
    }
    evidence.put("principalTotalMilli", principalTotal);
    evidence.put("netPrincipalDeltaMilli", netDelta);
    evidence.put("maxConsecutiveStressCycles", maxCycles);
    evidence.put("maxPrincipalToFMultiple", maxMultiple);
    evidence.put("FMilli", minF == Long.MAX_VALUE ? -1L : minF);
    evidence.put("FUnavailable", (long) unknownF);
    evidence.put("thresholdMultiple", DEBT_EXPLOSION_F_MULTIPLE);
    return new HexCrisisSignal(
        CrisisSignalId.idOf(hex, HexCrisisSignal.Kind.DEBT_EXPLOSION.name()),
        hex,
        HexCrisisSignal.Kind.DEBT_EXPLOSION,
        severity,
        context.day(),
        evidence,
        households,
        new ArrayList<>(classes),
        reason.toString());
  }

  // ── 5c 的投影回退审计（由 EconomySettlement 调用）─────────────────────────────────────────

  /** ★ 5c 投影不到旧 stratum 时保留旧 view 并具名报告（不落盘、只进当天审计）。 */
  static void recordClassProjectionFallback(
      ProductionLedger.Accumulator ledger,
      long day,
      HouseholdId household,
      ClassPositionId positionId,
      SocialClassId retainedStratum) {
    Objects.requireNonNull(ledger, "ledger");
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(positionId, "positionId");
    Objects.requireNonNull(retainedStratum, "retainedStratum");
    LinkedHashMap<String, Long> evidence = new LinkedHashMap<>();
    evidence.put("retainedStratumIndex", (long) SocialClassId.all().indexOf(retainedStratum));
    ledger.addLiquidationAudit(
        new ProductionLedger.LiquidationAudit(
            day,
            ACTION_PROJECTION_FALLBACK,
            Optional.of(household),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            0L,
            0L,
            0L,
            0L,
            0L,
            "classStanding-position-not-projectable:" + positionId.value(),
            evidence));
  }

  // ── 纯函数/工具 ─────────────────────────────────────────────────────────────────────────

  /** 粮债判据（F/政策价值都是粮口径）：只有 {@code DebtUnit.Commodity(GRAIN)} 才可做跨口径比较。 */
  private static boolean isGrainDebt(DebtContract debt) {
    return debt.unit() instanceof DebtUnit.Commodity commodity
        && commodity.commodity().equals(EconomySettlement.GRAIN);
  }

  /** 合同压力：有效状态 {@code DEFAULTED} 或 到期未偿 或 {@code F < 本期利息/到期应还 且本金未下降}。 */
  private static boolean contractStressed(
      DebtStatus effectiveStatus, boolean overdue, boolean shortOfRequired) {
    return effectiveStatus == DebtStatus.DEFAULTED || overdue || shortOfRequired;
  }

  /**
   * {@code F 项不足下一轮必要投入}的饱和比较（不溢出）：{@code income &lt; 口粮 + 下一轮投入 + 税}。
   *
   * <p>★ 下一轮必要投入为 0（没有可解析的生产投入）时返回 false：那属于"口粮缺口/断粮"，由 FOOD/famine 一族表达，
   * 不冒充"再生产维持不了"；没有再生产投入的位置不应因本判据机械下滑。
   */
  private static boolean nextRoundInputNotFunded(DebtCapacity capacity) {
    if (capacity.nextRoundNecessaryInput() <= 0L) {
      return false;
    }
    long remaining = capacity.afterAllocationGrainIncome();
    if (remaining < capacity.basicRation()) {
      return true;
    }
    remaining -= capacity.basicRation();
    if (remaining < capacity.nextRoundNecessaryInput()) {
      return true;
    }
    remaining -= capacity.nextRoundNecessaryInput();
    return remaining < capacity.taxPaid();
  }

  /** 户当前位置：有 standing 用 standing；无 standing 用旧 stratum 的默认位置映射（含"位置表空"的兼容口径）。 */
  private static Optional<ClassPositionId> resolvablePosition(
      Context context,
      HouseholdId household,
      Map<HouseholdId, ClassStanding> standings,
      Map<ClassPositionId, ClassPosition> positions) {
    ClassStanding standing = standings.get(household);
    if (standing != null) {
      return Optional.of(standing.currentPositionId());
    }
    ClassRow row = context.rows().get(household);
    if (row == null) {
      return Optional.empty();
    }
    ClassPositionId derived = LegacyClassStructure.positionIdOf(row.view().stratum());
    if (positions.isEmpty() || positions.containsKey(derived)) {
      return Optional.of(derived);
    }
    return Optional.empty();
  }

  /** 下滑目标：ruleExtensions 的 {@code downwardPositionId} 优先；否则同 mode 内按角色元组取最近严格低位。 */
  private static DownwardTarget resolveDownwardTarget(
      ClassPositionId currentId, Map<ClassPositionId, ClassPosition> positions) {
    ClassPosition current = positions.get(currentId);
    if (current == null) {
      return new DownwardTarget(Optional.empty(), "current-position-not-in-table");
    }
    String extension = current.ruleExtensions().get(DOWNWARD_POSITION_EXTENSION_KEY);
    if (extension != null && !extension.isBlank()) {
      try {
        ClassPositionId extensionId = new ClassPositionId(extension);
        ClassPosition extensionPosition = positions.get(extensionId);
        if (extensionPosition != null && !extensionId.equals(currentId)) {
          return new DownwardTarget(Optional.of(extensionId), null);
        }
        return mechanicalDownwardTarget(
            current, positions, "invalid-" + DOWNWARD_POSITION_EXTENSION_KEY);
      } catch (IllegalArgumentException invalid) {
        return mechanicalDownwardTarget(
            current, positions, "invalid-" + DOWNWARD_POSITION_EXTENSION_KEY);
      }
    }
    return mechanicalDownwardTarget(
        current, positions, "absent-" + DOWNWARD_POSITION_EXTENSION_KEY);
  }

  private static DownwardTarget mechanicalDownwardTarget(
      ClassPosition current,
      Map<ClassPositionId, ClassPosition> positions,
      String absentOrInvalidReason) {
    List<ClassPosition> lower = new ArrayList<>();
    for (ClassPosition candidate : positions.values()) {
      if (candidate.modeId().equals(current.modeId()) && compareRoleRank(candidate, current) < 0) {
        lower.add(candidate);
      }
    }
    if (lower.isEmpty()) {
      return new DownwardTarget(
          Optional.empty(), absentOrInvalidReason + ";no-strictly-lower-position-in-mode");
    }
    lower.sort(POSITION_HEIGHT_ORDER);
    return new DownwardTarget(Optional.of(lower.get(0).id()), absentOrInvalidReason);
  }

  /**
   * ★★ <b>机械回退的"严格更低"判据</b>：只在<b>角色元组</b>（relationToMeans 序 → surplusRole 序）上严格更低才算低位。
   *
   * <p>★ 为什么不把 {@code ClassPositionId.value()} 也塞进"更低"的比较：id 是稳定拼写，不是社会位阶。若把同角色元组内 id 更小/更大
   * 也算"更低"，legacy 结构里的"贫农"会被机械序判成"比中农高/低"——会出现<b>向上迁移</b>，违反 §7.2 "阶层下滑"与"不得乱降"的意图。{@code
   * Id.value()} 只在<b>同角色档的多个候选之间</b>做确定性 tiebreak（见 {@link #POSITION_HEIGHT_ORDER}）。语义链仍应显式写 {@code
   * ruleExtensions.downwardPositionId}。
   */
  private static int compareRoleRank(ClassPosition left, ClassPosition right) {
    int relation =
        Integer.compare(
            relationOrdinal(left.relationToMeans()), relationOrdinal(right.relationToMeans()));
    if (relation != 0) {
      return relation;
    }
    return Integer.compare(surplusOrdinal(left.surplusRole()), surplusOrdinal(right.surplusRole()));
  }

  /**
   * 机械下滑序：{@code (relationToMeans 语义序, surplusRole 语义序, ClassPositionId.value())} 的降序。★ 它只是 {@code
   * downwardPositionId} 的兜底；语义链请显式写规则扩展位（类注/报告都写明这条边界）。
   */
  private static final Comparator<ClassPosition> POSITION_HEIGHT_ORDER =
      Comparator.comparingInt(
              (ClassPosition position) -> relationOrdinal(position.relationToMeans()))
          .reversed()
          .thenComparing(
              Comparator.comparingInt(
                      (ClassPosition position) -> surplusOrdinal(position.surplusRole()))
                  .reversed())
          .thenComparing(position -> position.id().value());

  /** 与生产资料的关系序（越大越高）：OWNER &gt; MIXED &gt; OPERATOR &gt; DIRECT_LABORER。 */
  private static int relationOrdinal(RelationToMeans relation) {
    return switch (relation) {
      case OWNER -> 4;
      case MIXED -> 3;
      case OPERATOR -> 2;
      case DIRECT_LABORER -> 1;
    };
  }

  /** 剩余角色序（越大越高）：SURPLUS_RECEIVER &gt; SELF_SUBSISTENCE &gt; WAGE_EARNER &gt; DEPENDENT。 */
  private static int surplusOrdinal(SurplusRole surplus) {
    return switch (surplus) {
      case SURPLUS_RECEIVER -> 3;
      case SELF_SUBSISTENCE -> 2;
      case WAGE_EARNER -> 1;
      case DEPENDENT -> 0;
    };
  }

  private static ClassStanding withStressCount(ClassStanding standing, long count) {
    return new ClassStanding(
        standing.householdId(),
        standing.originalPositionId(),
        standing.currentPositionId(),
        standing.retainedShares(),
        count,
        standing.lastTransitionDay(),
        standing.reason());
  }

  /**
   * ★★ 到期即默认的有效状态：{@code principal > 0}、{@link #contractDueCycle(DebtContract)} 已到（合同滚动字段优先， 否则
   * terms 固定期限；legacy 两者都空 ⇒ 不触发）、且当前状态既非 {@code DEFAULTED}/{@code SETTLED}/{@code FORGIVEN} ⇒ 本轮视为
   * {@code DEFAULTED}，并由 {@code autoDefaults} 收集需在 apply 落状态的合同（稳定 id 序）。
   */
  private static Map<DebtContractId, DebtStatus> effectiveStatuses(
      Context context, List<DebtContractId> autoDefaults) {
    Map<DebtContractId, DebtStatus> statuses = new LinkedHashMap<>();
    for (DebtContract debt : context.debts().values()) {
      DebtStatus status = debt.status();
      boolean terminal =
          status == DebtStatus.DEFAULTED
              || status == DebtStatus.SETTLED
              || status == DebtStatus.FORGIVEN;
      if (debt.principal() > 0L && !terminal && contractDueCycle(debt) <= context.currentCycle()) {
        statuses.put(debt.id(), DebtStatus.DEFAULTED);
        autoDefaults.add(debt.id());
      } else {
        statuses.put(debt.id(), status);
      }
    }
    autoDefaults.sort(Comparator.comparing(DebtContractId::value));
    return statuses;
  }

  /** 现有 overdue 判定：合同滚动 dueCycle 优先，否则 terms 固定期限；两者都空 = 无到期（{@code Long.MAX_VALUE}）。 */
  private static long contractDueCycle(DebtContract debt) {
    return debt.dueCycle().isPresent()
        ? debt.dueCycle().getAsLong()
        : debt.terms().dueCycle().orElse(Long.MAX_VALUE);
  }

  /** ACTIVE 且 quantity &gt; 0 的质押按份额求和（自动挂质押算"剩余可用量"的输入）。 */
  private static Map<AssetShareId, Long> activePledgeQuantityByShare(
      Map<PledgeId, Pledge> pledges) {
    Map<AssetShareId, Long> quantities = new LinkedHashMap<>();
    List<Pledge> sorted = new ArrayList<>(pledges.values());
    sorted.sort(Comparator.comparing(pledge -> pledge.id().value()));
    for (Pledge pledge : sorted) {
      if (pledge.status() == Pledge.Status.ACTIVE && pledge.quantity() > 0L) {
        quantities.merge(
            pledge.assetShareId(), pledge.quantity(), EconomyLiquidationSettlement::saturatedAdd);
      }
    }
    return quantities;
  }

  /**
   * ★★ 无 ACTIVE 质押的触发债务：按债务人自有份额自动挂一笔 ACTIVE 质押。份额条件 = {@code owner ==
   * HouseholdActors.of(debtor)}、{@code kind == OWNED}、同 assetKind 存在 {@code pledgeable} 规则、扣除已有
   * ACTIVE 质押后可用量 &gt; 0；候选按 {@code isCoreMeans → liquidationPriority → share.id → rule.id}
   * 稳定排序。找不到合格份额 ⇒ 空，planner 保留既有 {@code no-active-pledge} 具名审计，不伪造质押。
   */
  private static Optional<Pledge> autoPledgeFor(
      Context context, DebtContract debt, Map<AssetShareId, Long> activePledgeQuantity) {
    List<AssetShare> shares = new ArrayList<>(context.assetShares().values());
    shares.sort(Comparator.comparing(share -> share.id().value()));
    List<AutoPledgeCandidate> candidates = new ArrayList<>();
    for (AssetShare share : shares) {
      if (!share.owner().equals(HouseholdActors.of(debt.debtor()))
          || share.kind() != AssetShare.RightKind.OWNED
          || (!context.industries().isEmpty()
              && !context.industries().containsKey(share.industry()))) {
        continue;
      }
      long available = share.quantity() - activePledgeQuantity.getOrDefault(share.id(), 0L);
      if (available <= 0L) {
        continue;
      }
      for (AssetRule rule : context.base().assetRules().values()) {
        if (rule.assetKind() == share.asset() && rule.pledgeable()) {
          candidates.add(new AutoPledgeCandidate(share, rule, available));
        }
      }
    }
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    candidates.sort(
        Comparator.comparing((AutoPledgeCandidate candidate) -> !candidate.rule().isCoreMeans())
            .thenComparingInt(candidate -> candidate.rule().liquidationPriority())
            .thenComparing(candidate -> candidate.share().id().value())
            .thenComparing(candidate -> candidate.rule().id().value()));
    AutoPledgeCandidate selected = candidates.get(0);
    return Optional.of(
        new Pledge(
            autoPledgeId(context, debt),
            debt.id(),
            selected.share().id(),
            selected.availableQuantity(),
            selected.rule().modeId(),
            selected.rule().liquidationPriority(),
            Pledge.Status.ACTIVE));
  }

  /** 自动质押 id：优先 {@code autopledge-<debtId>}；被非 ACTIVE 旧质押占用时按 cycle 确定性避让。 */
  private static PledgeId autoPledgeId(Context context, DebtContract debt) {
    PledgeId base = new PledgeId(AUTO_PLEDGE_ID_PREFIX + debt.id().value());
    if (!context.pledges().containsKey(base)) {
      return base;
    }
    PledgeId cycled =
        new PledgeId(AUTO_PLEDGE_ID_PREFIX + context.currentCycle() + "-" + debt.id().value());
    if (!context.pledges().containsKey(cycled)) {
      return cycled;
    }
    int suffix = 1;
    while (true) {
      PledgeId candidate = new PledgeId(cycled.value() + "-" + suffix);
      if (!context.pledges().containsKey(candidate)) {
        return candidate;
      }
      suffix++;
    }
  }

  /** ACTIVE 且 quantity &gt; 0 的质押按债务合同索引（一次派生；同债多条按 PledgeId.value 升序）。 */
  private static Map<DebtContractId, List<Pledge>> indexActivePledgesByDebt(
      Map<PledgeId, Pledge> pledges) {
    List<Pledge> sorted = new ArrayList<>(pledges.values());
    sorted.sort(Comparator.comparing(pledge -> pledge.id().value()));
    Map<DebtContractId, List<Pledge>> index = new LinkedHashMap<>();
    for (Pledge pledge : sorted) {
      if (pledge.status() == Pledge.Status.ACTIVE && pledge.quantity() > 0L) {
        index.computeIfAbsent(pledge.debtContractId(), ignored -> new ArrayList<>()).add(pledge);
      }
    }
    return index;
  }

  private static Map<HouseholdId, List<DebtContract>> indexDebtsByDebtor(
      Map<DebtContractId, DebtContract> debts) {
    List<DebtContract> sorted = new ArrayList<>(debts.values());
    sorted.sort(Comparator.comparing(debt -> debt.id().value()));
    Map<HouseholdId, List<DebtContract>> index = new LinkedHashMap<>();
    for (DebtContract debt : sorted) {
      index.computeIfAbsent(debt.debtor(), ignored -> new ArrayList<>()).add(debt);
    }
    return index;
  }

  private static List<HouseholdId> sortedHouseholds(Map<HouseholdId, ClassRow> rows) {
    List<HouseholdId> households = new ArrayList<>(rows.keySet());
    households.sort(Comparator.comparing(HouseholdId::value));
    return households;
  }

  private static List<DebtContract> activeDebts(List<DebtContract> debts) {
    List<DebtContract> active = new ArrayList<>();
    for (DebtContract debt : debts) {
      if (debt.principal() > 0L) {
        active.add(debt);
      }
    }
    return active;
  }

  private static long perMille(long value, int perMille) {
    if (value <= 0L || perMille <= 0) {
      return 0L;
    }
    return BigInteger.valueOf(value)
        .multiply(BigInteger.valueOf(perMille))
        .divide(BigInteger.valueOf(1000L))
        .longValueExact();
  }

  private static long saturatedAdd(long left, long right) {
    long sum = left + right;
    if (((left ^ sum) & (right ^ sum)) < 0L) {
      return Long.MAX_VALUE;
    }
    return sum;
  }

  private static AuditEntry skipAudit(
      long day, DebtContract debt, Pledge pledge, String reason, Map<String, Long> evidence) {
    return audit(
        day,
        ACTION_SKIPPED,
        debt.debtor(),
        debt.id(),
        pledge.id(),
        pledge.assetShareId(),
        null,
        reason,
        evidence);
  }

  private static AuditEntry audit(
      long day,
      String action,
      HouseholdId household,
      DebtContractId contractId,
      PledgeId pledgeId,
      AssetShareId sourceAssetShareId,
      AssetShareId createdAssetShareId,
      String reason,
      Map<String, Long> evidence) {
    return new AuditEntry(
        day,
        action,
        household,
        contractId,
        pledgeId,
        sourceAssetShareId,
        createdAssetShareId,
        0L,
        0L,
        0L,
        0L,
        0L,
        reason,
        Map.copyOf(evidence));
  }

  private static ProductionLedger.LiquidationAudit toLiquidationAudit(
      long day,
      DebtReductionPlan reduction,
      PlannedMove move,
      AssetShareId createdId,
      long debtPrincipalAfter) {
    Pledge after = null;
    for (PledgeUpdate update : reduction.pledgeUpdates()) {
      if (update.pledgeId().equals(move.pledgeId())) {
        after = update.after();
        break;
      }
    }
    long pledgeQuantityAfter = after == null ? move.quantity() : after.quantity();
    LinkedHashMap<String, Long> evidence = new LinkedHashMap<>();
    evidence.put("debtPrincipalBeforeMilli", reduction.debt().principal());
    evidence.put("debtReductionMilli", move.valueMilli());
    evidence.put("coreMeans", move.coreMeans() ? 1L : 0L);
    evidence.put("quantity", move.quantity());
    return new ProductionLedger.LiquidationAudit(
        day,
        ACTION_DISPOSED,
        Optional.of(reduction.debt().debtor()),
        Optional.of(reduction.debt().id()),
        Optional.of(move.pledgeId()),
        Optional.of(move.source()),
        Optional.of(createdId),
        move.quantity(),
        move.pricePerUnitMilli(),
        move.valueMilli(),
        debtPrincipalAfter,
        pledgeQuantityAfter,
        "liquidated:rule="
            + move.ruleId().value()
            + ";pricing="
            + move.pricing()
            + ";recipient="
            + move.recipientRule(),
        evidence);
  }

  private static ProductionLedger.LiquidationAudit toLiquidationAudit(AuditEntry entry) {
    return new ProductionLedger.LiquidationAudit(
        entry.day(),
        entry.action(),
        Optional.ofNullable(entry.household()),
        Optional.ofNullable(entry.contractId()),
        Optional.ofNullable(entry.pledgeId()),
        Optional.ofNullable(entry.sourceAssetShareId()),
        Optional.ofNullable(entry.createdAssetShareId()),
        entry.quantity(),
        entry.pricePerUnitMilli(),
        entry.debtReductionMilli(),
        entry.debtPrincipalAfter(),
        entry.pledgeQuantityAfter(),
        entry.reason(),
        entry.evidence());
  }
}

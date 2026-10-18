package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.model.DebtCapacity;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>E5b（P2d 收窄后）：债务压力计数器 + hex 债务爆炸信号</b>。
 *
 * <p>★★ <b>P2d 删除的是自动资产处置</b>：本阶段不再选路处置资产、不再写 {@code assetShares}、不再创建或改写任何优先权记录。
 * 保留的是与资产处置无关的债务压力读数、连续压力计数器与 {@code crisisSignals} 的 DEBT_EXPLOSION 信号路径。
 *
 * <p>★★ <b>位置与去重</b>：本阶段在日结算的 4b 借粮 → 4c 偿还 → 5 计息 → 5b 经营者退出之后、5c 阶层写回之前执行，只在 {@code
 * anyCycleClosed == true} 的关账日推进。
 *
 * <pre>
 * ① 压力判定（逐户逐合同，读 E4b DebtCapacity 与 E4c 偿还读数）
 * ② 连续压力计数器（HouseholdClassMembership.consecutiveDebtStressCycles）
 * ③ DEBT_EXPLOSION 只读信号（写进 crisisSignals；不改债务/资产/账户）
 * </pre>
 *
 * <p>★ <b>稳定序</b>：合同按 {@link DebtContractId#value()}；家户按 {@link HouseholdId#value()}。排序不依赖 Map
 * 迭代序/哈希序。
 */
public final class EconomyLiquidationSettlement {

  /** ★★ <b>清算阈值：连续债务压力周期数</b>（2 个关账周期）。 */
  public static final long DEBT_STRESS_CYCLES_THRESHOLD = 2L;

  /**
   * ★★ <b>债务爆炸阈值</b>：本金 / max(1, F) 达到 4 倍（F &gt; 0 时），或连续压力 ≥ 阈值且本金净增（F = 0 时） ⇒ 写 {@code
   * DEBT_EXPLOSION}。它是 hex 警告的读数阈值，不是自动政治行动。
   */
  public static final long DEBT_EXPLOSION_F_MULTIPLE = 4L;

  /** ★ 新建 {@code HouseholdClassMembership} 的具名原因（尚未发生阶层迁移）。 */
  public static final String STANDING_SEED_REASON = "e5b:classStandingSeed";

  /** ★ 信号/审计的稳定动作词（读口按它分类，不在多处拼字符串）。 */
  static final String ACTION_SKIPPED = "skipped";

  static final String ACTION_DEBT_EXPLOSION = "debt-explosion";

  static final String ACTION_PROJECTION_FALLBACK = "class-projection-fallback";

  /** 债务压力/危机信号日志（settlement 分类）。 */
  static final org.slf4j.Logger LOG = EconomyLog.settlement();

  /**
   * ★★ W5-fix6（D5）：<b>债务状态面</b>（debt 分类 —— 与 {@code DebtContractBook} 的 {@code DEBT_UPSERT} 同一个
   * logger）—— 自持合同的"不标违约/不计压力"具名 DEBUG 走它。
   */
  static final org.slf4j.Logger DEBT = EconomyLog.debt();

  private EconomyLiquidationSettlement() {}

  /** 本阶段是否启用：只要世界已经有生产方式/阶层/资产规则中的任意一项。 */
  public static boolean isActive(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(definitions, "definitions（B5：生产方式定义的唯一权威在 production）");
    return !definitions.modes().isEmpty()
        || !base.classPositions().isEmpty()
        || !base.classStandings().isEmpty()
        || !base.assetRules().isEmpty();
  }

  static void settle(
      EconomySession session,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      long day,
      long currentCycle,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<DebtContractId, DebtContract> debts,
      Map<DebtContractId, Long> principalAtDayStart,
      Map<DebtContractId, Long> repaidPrincipalByDebt,
      Map<HouseholdId, DebtCapacity> debtCapacities,
      ProductionLedger.Accumulator ledger) {
    Objects.requireNonNull(session, "session 不得为 null");
    Objects.requireNonNull(definitions, "definitions（B5：生产方式定义的唯一权威在 production，必须显式注入）");
    Objects.requireNonNull(ledger, "ledger 不得为 null");
    Objects.requireNonNull(householdEconomies, "rows 不得为 null");
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
            definitions,
            base,
            day,
            currentCycle,
            householdEconomies,
            debts,
            principalAtDayStart,
            repaidPrincipalByDebt,
            debtCapacities,
            ledger);
    Plan plan = DebtStressBook.plan(context);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "DEBT_STRESS_PLAN",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  day,
                  "cycle",
                  currentCycle,
                  "stressUpdates",
                  plan.stressUpdates().size(),
                  "audits",
                  plan.audits().size(),
                  "explosions",
                  plan.explosions().size(),
                  "autoDefaults",
                  plan.autoDefaults().size()));
    }
    LiquidationApplyBook.apply(context, plan);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "DEBT_STRESS_APPLIED",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "day",
                  day,
                  "audits",
                  plan.audits().size()));
    }
  }

  /** planner 的只读输入（所有 Map 都是调用方的当刻工作副本引用；planner 不写它们）。 */
  record Context(
      EconomySession session,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      EconomyData base,
      long day,
      long currentCycle,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<DebtContractId, DebtContract> debts,
      Map<DebtContractId, Long> principalAtDayStart,
      Map<DebtContractId, Long> repaidPrincipalByDebt,
      Map<HouseholdId, DebtCapacity> debtCapacities,
      ProductionLedger.Accumulator ledger) {}

  /** 一户的压力读数与计数器更新。 */
  record StressUpdate(
      HouseholdId household,
      long previousCount,
      long nextCount,
      boolean stressed,
      Map<String, Long> evidence) {}

  /** 一次债务爆炸读数。 */
  record DebtExplosionPlan(
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
  record AuditEntry(
      long day,
      String action,
      HouseholdId household,
      DebtContractId contractId,
      String reason,
      Map<String, Long> evidence) {}

  /** planner 结果：只读、完整、可重放；apply 只是它的执行。 */
  record Plan(
      List<StressUpdate> stressUpdates,
      List<AuditEntry> audits,
      List<DebtExplosionPlan> explosions,
      List<DebtContractId> autoDefaults) {}

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
            0L,
            0L,
            0L,
            0L,
            "classStanding-position-not-projectable:" + positionId.value(),
            evidence));
  }
}

package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.culture.HouseholdCultureKey;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.HouseholdFlowReason;
import io.mosire.simos.economy.model.HouseholdFlowTable;
import io.mosire.simos.economy.model.HouseholdFlowTarget;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★★ <b>D-032 C4 / S7-C：同 hex OWNER → PROLETARIAN 的向下人口流动表。</b>
 *
 * <p>★ <b>父运行序列锚点</b>：{@code
 * docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md}
 * §8.3/§8.4/§11/§13。位置 = {@code SettlementDayMarketPipeline.executeMarketRound} 中市场 LAND 写回 +
 * {@link PostLandTradeClassCheck} 之后； 每个市场轮恰好调用一次。
 *
 * <pre>
 * 每个 OWNER、population &gt; 0 的源户（HouseholdId 稳定序）：
 *   读 sourceHex / cultureKey / 未偿本金合计
 *   维护 table：同 hex + 当刻 OwnershipClass.PROLETARIAN + 正人口（含本轮 projected）的 target；
 *               同 culture 优先（人口→LAND→id），无同 culture 才取最小 id + INFO mismatch
 *   首次见表：lastObserved = 当前债务（仅建立基线），不流动
 *   后续市场轮：currentDebt ≥ 基线 + max(1, 基线/100) 且 day &gt; lastFlowDay ⇒ flowPopulation =
 *               min(floor(P×10/1000), P-1)   // 1% 量级；P&lt;100 时整数 floor 可为 0
 *   债务下降或未达 1% 增量 ⇒ 不触发且基线保持高水位（回落后的轻微反弹不重置）
 *   有正人口流：无正人口 target ⇒ 建确定性 0 人口 PROLETARIAN 壳（稳定 id/空账户，无 mode/position/standing），
 *               然后只调 HouseholdPopulationFlowSettlement.applyPopulationOnly：只迁人口 + 缩 source PRODUCTION 承诺；
 *               targetIsNew=true 只对首次迁入的新壳
 *   LAND / 钱 / 货 / 债 / 非 LAND 资产 / 经验不在本类读写 ⇒ 不随人走；source 仍由资产派生 OWNER；
 *   source 行人口权威仍由 Social 回写，flowPopulation ≤ P-1 保证源户不空。
 * </pre>
 *
 * <p>★★ <b>边界</b>：本类不新增 LAND 市场/债务/工资/需求公式，不做 §8 整组织交易；target 壳只建立经济行与空账户， Social 家户由
 * population-only outbox 的 {@code targetIsNew=true} 交给 App 创建。不得调用会搬钱/货/债/经验的 {@link
 * HouseholdTransferSettlement#apply}。
 */
final class HouseholdFlowBook {

  /** C4 population-only outbox 的原因标签。 */
  static final String HOUSEHOLD_FLOW_DEBT_REASON = "HOUSEHOLD_FLOW_DEBT";

  /** 每市场轮最多流动的比例（‰）：10‰ = 1%。 */
  static final long MAX_FLOW_PER_MILLE = 10L;

  /** 触发流动所需的最小债务本金增量，按基线的千分之（10‰ = 1%）；至少 1 毫。 */
  private static final long FLOW_TRIGGER_DELTA_PER_MILLE = 10L;

  private static final org.slf4j.Logger LOG = EconomyLog.migration();

  private HouseholdFlowBook() {}

  /** 一轮维护/流动的只读交账读数。 */
  record FlowRound(int tablesUpdated, int flows, long population, int createdTargets) {
    FlowRound {
      if (tablesUpdated < 0 || flows < 0 || population < 0L || createdTargets < 0) {
        throw new IllegalArgumentException("FlowRound 的计数/人口不得为负");
      }
    }

    static FlowRound empty() {
      return new FlowRound(0, 0, 0L, 0);
    }

    /** 兼容任务书里的 createdAtTargets 写法。 */
    int createdAtTargets() {
      return createdTargets;
    }
  }

  /** 一个候选目标的本轮只读读数（projectedPopulation 含同一轮先前流进该壳的人口）。 */
  private record Candidate(
      HouseholdId household,
      long population,
      long landMilliMuAtHex,
      HouseholdCultureKey cultureKey) {}

  /** 同 culture 候选的 R1 survivor 全序：人口降序 → 本 hex LAND 降序 → HouseholdId 升序。 */
  private static final Comparator<Candidate> SURVIVOR_ORDER =
      Comparator.comparingLong(Candidate::population)
          .reversed()
          .thenComparing(Comparator.comparingLong(Candidate::landMilliMuAtHex).reversed())
          .thenComparing(candidate -> candidate.household().value());

  /**
   * ★★★ <b>DEF-3（2026-10-20）：清理陈旧的 householdFlowTables。</b>
   *
   * <p>本表只对"当刻仍是 OWNER 的 source + 同 hex 的 PROLETARIAN target"有意义；{@code ownershipClass}
   * 由当刻资产派生、可随资产变化，若陈表留在工作表里，最终 {@code EconomyStateBuilder.build()} 的不变量会 fail-closed。这里在 build
   * 前按当刻事实清理：
   *
   * <ul>
   *   <li>source 行不存在 / population ≤ 0 / ownershipClass 不是 OWNER / sourceHex 与当刻 view().hex() 不一致 ⇒
   *       整表删除；
   *   <li>已有 target 不存在 / population ≤ 0 / ownershipClass 不是 PROLETARIAN / hex 与 sourceHex 不一致 ⇒ 清空
   *       targets， 保留 source 观察基线（若 source 不合法则整表删除优先）；
   * </ul>
   *
   * <p>只删/清本表，不碰人口、账户、债务、LAND；无陈表时是 no-op。调用点：{@code
   * SettlementDayMarketPipeline.executeMarketRound} 市场轮收尾，以及 {@link #settle} 开头（防御上一轮遗留陈表）。
   *
   * @param ctx 当刻日结算上下文；不得为 null
   * @return 删除的表数 + 清空 targets 的表数
   */
  static int pruneStaleTables(SettlementDayContext ctx) {
    Objects.requireNonNull(ctx, "HouseholdFlowBook.pruneStaleTables 的 ctx 不得为 null");
    LinkedHashMap<HouseholdId, HouseholdFlowTable> tables =
        ctx.session.sheet().householdFlowTables();
    int pruned = 0;
    Iterator<Map.Entry<HouseholdId, HouseholdFlowTable>> iterator = tables.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<HouseholdId, HouseholdFlowTable> entry = iterator.next();
      HouseholdId source = entry.getKey();
      HouseholdFlowTable table = entry.getValue();
      String sourceReason = staleSourceReason(ctx, source, table);
      if (sourceReason != null) {
        iterator.remove();
        pruned = Math.addExact(pruned, 1);
        logPruned(source, sourceReason);
        continue;
      }
      String targetReason = null;
      for (HouseholdFlowTarget target : table.targets()) {
        targetReason = staleTargetReason(ctx, table, target.targetHousehold());
        if (targetReason != null) {
          break;
        }
      }
      if (targetReason != null) {
        entry.setValue(table.withTargets(List.of()));
        pruned = Math.addExact(pruned, 1);
        logPruned(source, targetReason);
      }
    }
    return pruned;
  }

  /** source 行/人口/阶层/hex 是否已不合法；合法返回 null。 */
  private static String staleSourceReason(
      SettlementDayContext ctx, HouseholdId source, HouseholdFlowTable table) {
    HouseholdEconomy row = ctx.householdEconomies.get(source);
    if (row == null) {
      return "source-missing";
    }
    if (row.population() <= 0L) {
      return "source-population-nonpositive";
    }
    if (OwnershipClassBook.classOf(ctx, source) != OwnershipClass.OWNER) {
      return "source-not-owner";
    }
    if (!row.view().hex().equals(table.sourceHex())) {
      return "source-hex-mismatch";
    }
    return null;
  }

  /** target 行/人口/阶层/hex 是否已不合法；合法返回 null。 */
  private static String staleTargetReason(
      SettlementDayContext ctx, HouseholdFlowTable table, HouseholdId target) {
    HouseholdEconomy row = ctx.householdEconomies.get(target);
    if (row == null) {
      return "target-missing";
    }
    if (row.population() <= 0L) {
      return "target-population-nonpositive";
    }
    if (OwnershipClassBook.classOf(ctx, target) != OwnershipClass.PROLETARIAN) {
      return "target-not-proletarian";
    }
    if (!row.view().hex().equals(table.sourceHex())) {
      return "target-hex-mismatch";
    }
    return null;
  }

  private static void logPruned(HouseholdId source, String reason) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "HOUSEHOLD_FLOW_TABLE_PRUNED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "source",
                source.value(),
                "reason",
                reason));
  }

  /**
   * 市场轮内调用一次：维护每个 OWNER 户的流动表，并在债务本金扩大时执行一次 population-only 向下流动。
   *
   * @param ctx 当刻日结算上下文；不得为 null
   * @param day 世界日；必须等于 {@code ctx.day}
   */
  static FlowRound settle(SettlementDayContext ctx, long day) {
    Objects.requireNonNull(ctx, "HouseholdFlowBook.settle 的 ctx 不得为 null");
    if (day != ctx.day) {
      throw new IllegalArgumentException(
          "HouseholdFlowBook.settle 的 day 必须等于 ctx.day: day=" + day + " ctx.day=" + ctx.day);
    }
    // ★★ DEF-3：先清上一轮/上一阶段留下的陈表；合法表不满足任何清理条件 ⇒ 逐值不变。
    pruneStaleTables(ctx);

    List<HouseholdId> sourceIds = new ArrayList<>(ctx.householdEconomies.keySet());
    sourceIds.sort(Comparator.comparing(HouseholdId::value));

    LinkedHashMap<HouseholdId, HouseholdFlowTable> tables =
        ctx.session.sheet().householdFlowTables();
    Map<HouseholdId, Long> projectedTargetPopulation = new LinkedHashMap<>();
    int tablesUpdated = 0;
    int flows = 0;
    long movedPopulation = 0L;
    int createdTargets = 0;
    int ordinal = 0;

    for (HouseholdId sourceId : sourceIds) {
      HouseholdEconomy sourceRow = ctx.householdEconomies.get(sourceId);
      if (sourceRow == null || sourceRow.population() <= 0L) {
        continue;
      }
      if (OwnershipClassBook.classOf(ctx, sourceId) != OwnershipClass.OWNER) {
        continue;
      }

      HexCoord sourceHex = sourceRow.view().hex();
      HouseholdCultureKey sourceCulture = cultureKeyOf(ctx, sourceId, true);
      long currentDebt = sourceDebtPrincipal(ctx.debts, sourceId);

      HouseholdFlowTable previous = tables.get(sourceId);
      // ★ RC8e：lastObservedDebtPrincipalMilli 是**触发基线/高水位**，不是每轮无条件刷成当前债务。
      long baselineDebt =
          previous == null ? currentDebt : previous.lastObservedDebtPrincipalMilli();
      long lastFlowDay = previous == null ? -1L : previous.lastFlowDay();
      long debtDelta = Math.subtractExact(currentDebt, baselineDebt);
      long triggerThreshold = Math.max(1L, baselineDebt / (1000L / FLOW_TRIGGER_DELTA_PER_MILLE));
      boolean firstObservation = previous == null;
      boolean cooldownElapsed = day > lastFlowDay;
      boolean debtRiseCoversThreshold = debtDelta > 0L && debtDelta >= triggerThreshold;
      boolean triggered = !firstObservation && debtRiseCoversThreshold && cooldownElapsed;

      HouseholdId selectedTarget =
          selectTarget(ctx, sourceId, sourceHex, sourceCulture, projectedTargetPopulation);
      List<HouseholdFlowTarget> maintainedTargets =
          selectedTarget == null
              ? List.of()
              : List.of(new HouseholdFlowTarget(selectedTarget, HouseholdFlowReason.DEBT_DOWNWARD));

      long flowPopulation = triggered ? flowPopulation(sourceRow.population()) : 0L;
      if (!triggered) {
        logNotTriggered(
            day,
            sourceId,
            currentDebt,
            baselineDebt,
            debtDelta,
            triggerThreshold,
            firstObservation,
            cooldownElapsed);
      } else if (flowPopulation == 0L) {
        logTriggeredZeroPopulation(
            day,
            sourceId,
            currentDebt,
            baselineDebt,
            debtDelta,
            triggerThreshold,
            sourceRow.population());
      }

      boolean createdTarget = false;
      if (triggered && flowPopulation > 0L) {
        HouseholdId target = selectedTarget;
        if (target == null) {
          target = createLandlessShell(ctx, sourceId, sourceHex, day);
          createdTarget = true;
          createdTargets = Math.addExact(createdTargets, 1);
          maintainedTargets =
              List.of(new HouseholdFlowTarget(target, HouseholdFlowReason.DEBT_DOWNWARD));
          projectedTargetPopulation.putIfAbsent(target, 0L);
        }
        HouseholdEconomy targetRow = ctx.householdEconomies.get(target);
        if (targetRow == null) {
          throw fail(
              "household-flow-target-missing",
              "source=" + sourceId.value() + " target=" + target.value());
        }
        long targetPopulationBefore =
            projectedTargetPopulation.getOrDefault(target, targetRow.population());
        // ★★★ C4：唯一 population-only 执行入口；不搬钱/货/债/LAND/非 LAND 资产/经验，
        //   targetMode 恒为 Optional.empty()（目标壳无 mode/position 是合法状态）。
        HouseholdPopulationFlowSettlement.applyPopulationOnly(
            ctx.session,
            ctx.accounts,
            day,
            ordinal,
            sourceId,
            target,
            flowPopulation,
            sourceHex,
            createdTarget,
            HOUSEHOLD_FLOW_DEBT_REASON,
            sourceRow.population(),
            targetPopulationBefore);
        ordinal = Math.addExact(ordinal, 1);
        flows = Math.addExact(flows, 1);
        movedPopulation = Math.addExact(movedPopulation, flowPopulation);
        projectedTargetPopulation.put(
            target, Math.addExact(targetPopulationBefore, flowPopulation));
        lastFlowDay = day;
        logApplied(
            day,
            sourceId,
            target,
            sourceHex,
            currentDebt,
            baselineDebt,
            debtDelta,
            triggerThreshold,
            flowPopulation,
            createdTarget);
      }

      // ★ 触发后基线推进到当前债务；未触发（含债务下降/轻微反弹）保持原高水位。
      long baselineForTable = triggered ? currentDebt : baselineDebt;
      HouseholdFlowTable updated =
          new HouseholdFlowTable(
              sourceId, sourceHex, maintainedTargets, baselineForTable, lastFlowDay);
      if (!updated.equals(previous)) {
        tables.put(sourceId, updated);
        tablesUpdated = Math.addExact(tablesUpdated, 1);
        logTableUpdated(day, sourceId, sourceHex, maintainedTargets, baselineForTable, lastFlowDay);
      }
    }

    return new FlowRound(tablesUpdated, flows, movedPopulation, createdTargets);
  }

  /** 本市场轮应随迁人口：10‰（1%）上限，整数 floor；至少保留 source 1 人（P&lt;100 时可迁 0 人）。 */
  private static long flowPopulation(long sourcePopulation) {
    if (sourcePopulation <= 0L) {
      return 0L;
    }
    long bySpeed = Math.multiplyExact(sourcePopulation, MAX_FLOW_PER_MILLE) / 1000L;
    return Math.min(bySpeed, sourcePopulation - 1L);
  }

  /** 源户未偿本金合计（只货币债；principal &gt; 0）。 */
  private static long sourceDebtPrincipal(
      Map<DebtContractId, DebtContract> debts, HouseholdId source) {
    long total = 0L;
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(source) && contract.principal() > 0L) {
        total = Math.addExact(total, contract.principal());
      }
    }
    return total;
  }

  /**
   * 选同 hex / 当刻 PROLETARIAN / 正人口目标：同 culture 优先，否则最小 HouseholdId + INFO mismatch。
   * projectedPopulation 只用于“同一轮前一笔 flow 已流入的壳”，不把存量 0 人口目标当正人口。
   *
   * <p>★ C4：不读 productionMode/currentPosition/classStanding；target 无 mode 仍合法。
   */
  private static HouseholdId selectTarget(
      SettlementDayContext ctx,
      HouseholdId source,
      HexCoord sourceHex,
      HouseholdCultureKey sourceCulture,
      Map<HouseholdId, Long> projectedTargetPopulation) {
    List<Candidate> candidates = new ArrayList<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> entry : ctx.householdEconomies.entrySet()) {
      HouseholdId candidateId = entry.getKey();
      HouseholdEconomy row = entry.getValue();
      if (candidateId.equals(source) || row == null || !sourceHex.equals(row.view().hex())) {
        continue;
      }
      long candidatePopulation =
          projectedTargetPopulation.getOrDefault(candidateId, row.population());
      if (candidatePopulation <= 0L) {
        continue;
      }
      if (OwnershipClassBook.classOf(ctx, candidateId) != OwnershipClass.PROLETARIAN) {
        continue;
      }
      candidates.add(
          new Candidate(
              candidateId,
              candidatePopulation,
              landMilliMuAtHex(ctx, sourceHex, candidateId),
              cultureKeyOf(ctx, candidateId, false)));
    }
    if (candidates.isEmpty()) {
      return null;
    }

    List<Candidate> sameCulture = new ArrayList<>();
    for (Candidate candidate : candidates) {
      if (candidate.cultureKey().equals(sourceCulture)) {
        sameCulture.add(candidate);
      }
    }
    if (!sameCulture.isEmpty()) {
      sameCulture.sort(SURVIVOR_ORDER);
      return sameCulture.get(0).household();
    }

    candidates.sort(Comparator.comparing(candidate -> candidate.household().value()));
    HouseholdId chosen = candidates.get(0).household();
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "HOUSEHOLD_FLOW_CULTURE_MISMATCH",
                EconomyLogSource.ECONOMY_MIGRATION,
                "source",
                source.value(),
                "hex",
                sourceHex.toString(),
                "target",
                chosen.value(),
                "sourceCulture",
                sourceCulture,
                "targetCulture",
                candidates.get(0).cultureKey(),
                "reason",
                "no-same-culture-candidate-min-household-id"));
    return chosen;
  }

  /**
   * 为触发流但无正人口目标的 source 建一个确定性 0 人口 PROLETARIAN 壳。
   *
   * <p>★ C4 冻结契约：空账户、0 人口/劳动、无 classStanding/currentPosition/productionMode；不复制 LAND、不伪造
   * mode/position、 不复制 source 的库存/债/承诺。class 由零资产派生为 PROLETARIAN（不持久化）。
   */
  private static HouseholdId createLandlessShell(
      SettlementDayContext ctx, HouseholdId source, HexCoord sourceHex, long day) {
    HouseholdEconomy sourceRow = ctx.householdEconomies.get(source);
    if (sourceRow == null) {
      throw fail("household-flow-source-missing", "source=" + source.value());
    }
    ResidenceKind residence = sourceRow.view().residence();
    HouseholdId target = allocateTargetId(ctx, sourceHex, residence);

    HouseholdEconomy shell =
        new HouseholdEconomy(
            target,
            new CohortKey(sourceHex, residence, SocialClassId.LANDLESS_LABORER),
            0L,
            0L,
            0,
            0L,
            Map.of(),
            Map.of(),
            0L);
    if (ctx.householdEconomies.putIfAbsent(target, shell) != null) {
      throw fail(
          "household-flow-target-id-collision",
          "source=" + source.value() + " target=" + target.value());
    }
    if (ctx.accounts.accountOrNull(target) != null) {
      throw fail(
          "household-flow-target-account-collision",
          "source=" + source.value() + " target=" + target.value());
    }
    ctx.accounts.registerHousehold(target, sourceHex, Map.of(), Map.of(), Map.of(), Map.of());
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "HOUSEHOLD_FLOW_TARGET_CREATED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "source",
                source.value(),
                "target",
                target.value(),
                "hex",
                sourceHex.toString(),
                "mode",
                "none",
                "position",
                "none",
                "classStanding",
                "none",
                "population",
                0L,
                "land",
                "not-copied",
                "inventory",
                "not-copied",
                "debt",
                "not-copied",
                "commitments",
                "not-copied"));
    return target;
  }

  /** 稳定 id：同 (hex, residence, LANDLESS_LABORER, flow-n) 的最小未被占用 n；已占账户但无行 ⇒ fail-closed。 */
  private static HouseholdId allocateTargetId(
      SettlementDayContext ctx, HexCoord hex, ResidenceKind residence) {
    for (int n = 1; n < Integer.MAX_VALUE; n++) {
      HouseholdId candidate =
          HouseholdIds.ofSeedRole(hex, residence, SocialClassId.LANDLESS_LABORER, "flow-" + n);
      if (ctx.householdEconomies.containsKey(candidate)) {
        continue;
      }
      if (ctx.accounts.accountOrNull(candidate) != null) {
        throw fail(
            "household-flow-target-id-account-collision",
            "hex=" + hex + " residence=" + residence.value() + " candidate=" + candidate.value());
      }
      return candidate;
    }
    throw fail("household-flow-target-id-exhausted", "hex=" + hex);
  }

  private static long landMilliMuAtHex(
      SettlementDayContext ctx, HexCoord hex, HouseholdId household) {
    LandHolding holding =
        ctx.session.sheet().landHoldingsOrBase().get(new LandHoldingKey(hex, household));
    return holding == null ? 0L : holding.quantityMilliMu();
  }

  /**
   * culture lookup 返回 null 是契约故障；empty 映射成显式 missing key。
   *
   * <p>候选枚举阶段不逐户刷 INFO（大世界会把同一事实刷成噪声）；只有 source 的缺失记 INFO。
   */
  private static HouseholdCultureKey cultureKeyOf(
      SettlementDayContext ctx, HouseholdId household, boolean logMissing) {
    Optional<HouseholdCultureKey> found = ctx.session.cultureKeyOf(household);
    if (found == null) {
      throw fail("household-flow-culture-lookup-null", "household=" + household.value());
    }
    if (found.isEmpty()) {
      if (logMissing) {
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "HOUSEHOLD_FLOW_CULTURE_MISSING",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "household",
                    household.value(),
                    "fallback",
                    "explicit-empty-key"));
      }
      return HouseholdCultureKey.missing();
    }
    return found.get();
  }

  private static void logTableUpdated(
      long day,
      HouseholdId source,
      HexCoord sourceHex,
      List<HouseholdFlowTarget> targets,
      long observedDebt,
      long lastFlowDay) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "HOUSEHOLD_FLOW_TABLE_UPDATED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "source",
                source.value(),
                "hex",
                sourceHex.toString(),
                "targets",
                targets,
                "lastObservedDebtPrincipalMilli",
                observedDebt,
                "lastFlowDay",
                lastFlowDay));
  }

  private static void logApplied(
      long day,
      HouseholdId source,
      HouseholdId target,
      HexCoord hex,
      long currentDebt,
      long baselineDebt,
      long debtDelta,
      long triggerThreshold,
      long population,
      boolean targetIsNew) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "HOUSEHOLD_FLOW_APPLIED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "source",
                source.value(),
                "target",
                target.value(),
                "hex",
                hex.toString(),
                "population",
                population,
                "debtPrincipalBefore",
                currentDebt,
                "triggerBaselineMilli",
                baselineDebt,
                "debtDeltaMilli",
                debtDelta,
                "triggerThresholdMilli",
                triggerThreshold,
                "maxFlowPerMille",
                MAX_FLOW_PER_MILLE,
                "reason",
                HOUSEHOLD_FLOW_DEBT_REASON,
                "targetIsNew",
                targetIsNew,
                "land",
                "not-moved"));
  }

  /** 具名 DEBUG：为什么本轮没有触发（首见建基线 / 冷却 / 未增债 / 未达 1% 阈值）。 */
  private static void logNotTriggered(
      long day,
      HouseholdId source,
      long currentDebt,
      long baselineDebt,
      long debtDelta,
      long triggerThreshold,
      boolean firstObservation,
      boolean cooldownElapsed) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    String reason;
    if (firstObservation) {
      reason = "first-observation-baseline-established";
    } else if (!cooldownElapsed) {
      reason = "cooldown-not-elapsed";
    } else if (debtDelta <= 0L) {
      reason = "debt-not-increased-high-water-kept";
    } else {
      reason = "below-hysteresis-threshold";
    }
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "HOUSEHOLD_FLOW_NOT_TRIGGERED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "source",
                source.value(),
                "debtPrincipalMilli",
                currentDebt,
                "triggerBaselineMilli",
                baselineDebt,
                "debtDeltaMilli",
                debtDelta,
                "triggerThresholdMilli",
                triggerThreshold,
                "maxFlowPerMille",
                MAX_FLOW_PER_MILLE,
                "reason",
                reason));
  }

  /** 具名 INFO：债务已越过阈值但 1% floor 不足 1 人（P&lt;100），本轮不迁人；基线仍推进到当前债务。 */
  private static void logTriggeredZeroPopulation(
      long day,
      HouseholdId source,
      long currentDebt,
      long baselineDebt,
      long debtDelta,
      long triggerThreshold,
      long sourcePopulation) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "HOUSEHOLD_FLOW_TRIGGERED_ZERO_POPULATION",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "source",
                source.value(),
                "debtPrincipalMilli",
                currentDebt,
                "triggerBaselineMilli",
                baselineDebt,
                "debtDeltaMilli",
                debtDelta,
                "triggerThresholdMilli",
                triggerThreshold,
                "sourcePopulation",
                sourcePopulation,
                "maxFlowPerMille",
                MAX_FLOW_PER_MILLE,
                "reason",
                "integer-floor-yields-zero-population"));
  }

  private static IllegalStateException fail(String reason, String detail) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "HOUSEHOLD_FLOW_FAILED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "reason",
                reason,
                "detail",
                detail));
    return new IllegalStateException(reason + ": " + detail);
  }
}

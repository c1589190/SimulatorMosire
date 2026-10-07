package io.mosire.simos.app.time;

import io.mosire.simos.actor.model.AvailableStock;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.household.GovernmentHouseholdResolver;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.stock.DeductionReason;
import io.mosire.simos.economy.api.stock.HouseholdStockDeduction;
import io.mosire.simos.economy.time.AccountSession;
import io.mosire.simos.gov.GovBudgetPolicy;
import io.mosire.simos.gov.GovDaily;
import io.mosire.simos.gov.GovRemittanceState;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.economy.EconomyVocabulary;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * ★★ <b>Z7c remittance：省国库按周期把实收税的一定 ‰ 上缴 superiorGov 国库</b>（设计书 §4；用户原话"省政府上交中央可以搞定期，
 * 但是省政府一定要有能力改，也就是抗税"）。
 *
 * <p>★★ <b>输入与所有权边界</b>：本桥是 app 组合根的日循环件（{@code simos-gov} 不认识账户/家户，{@code simos-economy} 不认识 GOV
 * 编制/上级关系，只有这里同时看得见两者）：
 *
 * <ul>
 *   <li>读：{@link UnitState} 的 {@link GovernmentFormation#superiorGov()} 与 {@link
 *       GovernmentHouseholdResolver} 解析出的两个国库家户；{@link GovState#budgetPolicyOrDefault(UnitId)} 的
 *       {@link GovBudgetPolicy#remittancePerMilleToSuperior()}；{@link
 *       GovState#remittanceStateOrDefault(UnitId)} 的本周期累计；
 *   <li>写：账户只走 {@link StockDeductionService} + {@link
 *       HouseholdStockDeduction#transfer}（与日税同一落账原语：整批 先校验、后一次 commit），gov 状态只写 {@link
 *       GovState#withRemittanceState(UnitId, GovRemittanceState)}（周期账清零 + 最近一次应缴/实缴/缺口读数）；<b>不</b>改
 *       unit/economy/social，不新增第二本余额或第二份税账。
 * </ul>
 *
 * <p>★★ <b>周期与执行时点</b>：每个结算日先把 {@code JurisdictionDailyTax} 的<b>逐 unit 实收</b>加进周期累计；只有 {@code
 * cycleClosed=true}（经济侧报告"有产业周期关账"的同一事实，见 {@code EconomyDayStepper#lastCycleClosed()}）才结算： {@code
 * due = floor(周期实收 × rate / 1000)}（粮/银各自，饱和），然后<b>逐腿</b> {@code paid = min(due, 国库可用)} （{@link
 * AvailableStock} 的唯一"余额 − 冻结"算法）。rate=0 ⇒ 不转移（抗税）、无缺口告警；不足 ⇒ 部分支付 + 具名缺口 + {@code
 * ADMIN_REMITTANCE_SHORTFALL} 信号，<b>不</b>自动注资/借款/铸币/调率；无上级/自己 ⇒ no-op + 具名 INFO。
 *
 * <p>★★ <b>清零与不重复计</b>：关账日一旦走到结算分支（含 no-op/rate=0/契约故障），本周期累计<b>一律清零</b> （{@link
 * GovRemittanceState#afterCycleClose}），同一批实收绝不跨周期重复计。
 *
 * <p>★ <b>日志（AGENTS §一.9）</b>：INFO = 每次关账每个 GOV 一条（{@code GOV_REMITTANCE_DAY} 或具名 {@code
 * GOV_REMITTANCE_SKIPPED}）；TRACE 逐笔落账由 {@link StockDeductionService} 承担；契约故障 ERROR + {@code
 * ADMIN_CONTRACT} 信号（不静默、也不中断整日推进——损坏的那一个 GOV 记 paid=0/短缺口，其余 GOV 照常）。
 */
final class GovRemittanceBridge {

  /** 日结算日志（app 日循环分类）。 */
  private static final Logger LOG = AppLog.time();

  /** 粮的商品 id（{@link EconomyVocabulary} 的唯一拼写点）。 */
  private static final CommodityId GRAIN = CommodityId.parse(EconomyVocabulary.GRAIN_COMMODITY_ID);

  /** 货币 id（{@link MoneyVocabulary} 的唯一拼写点）。 */
  private static final CurrencyId SILVER = MoneyVocabulary.SILVER_CURRENCY;

  private GovRemittanceBridge() {}

  /** 一次 remittance 结算的完整输出：新 gov 状态 + 待折入危机信号的告警草稿（保序不可变）。 */
  record Outcome(GovState nextGov, List<GovDaily.SignalDraft> alerts) {

    Outcome {
      Objects.requireNonNull(nextGov, "nextGov");
      Objects.requireNonNull(alerts, "alerts");
      alerts = Collections.unmodifiableList(new ArrayList<>(alerts));
    }
  }

  /**
   * 结算一个世界日的周期累计（并在关账日执行上缴）。
   *
   * @param govState 当刻 gov 源状态（不修改；输出为新实例）
   * @param units unit 切片（读上级关系与座位；GOV 单位按 id 升序遍历）
   * @param grainCollectedByUnit 本日逐 unit 实收粮（毫；来自 {@code JurisdictionDailyTax.Report}）
   * @param silverCollectedByUnit 本日逐 unit 实收银（毫）
   * @param accounts 本次推进的唯一账户会话（与税/预算同一会话）
   * @param day 本世界日（≥ 1）
   * @param cycleClosed 本日是否有产业周期关账（{@code EconomyDayStepper.lastCycleClosed()} 的同一事实）
   */
  static Outcome settle(
      GovState govState,
      UnitState units,
      Map<UnitId, Long> grainCollectedByUnit,
      Map<UnitId, Long> silverCollectedByUnit,
      AccountSession accounts,
      long day,
      boolean cycleClosed) {
    Objects.requireNonNull(govState, "govState");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(grainCollectedByUnit, "grainCollectedByUnit");
    Objects.requireNonNull(silverCollectedByUnit, "silverCollectedByUnit");
    Objects.requireNonNull(accounts, "accounts");
    if (day < 0L) {
      throw new IllegalArgumentException("remittance 的 day 不得为负: " + day);
    }

    List<Unit> govUnits = new ArrayList<>();
    for (Unit unit : units.units().values()) {
      if (unit.module().orElse(null) instanceof GovernmentFormation) {
        govUnits.add(unit);
      }
    }
    govUnits.sort(Comparator.comparing(unit -> unit.id().value()));

    GovState next = govState;
    List<GovDaily.SignalDraft> alerts = new ArrayList<>();
    for (Unit unit : govUnits) {
      UnitId govId = unit.id();
      GovRemittanceState previous = govState.remittanceStateOrDefault(govId);
      GovRemittanceState accumulated =
          previous.accumulate(
              grainCollectedByUnit.getOrDefault(govId, 0L),
              silverCollectedByUnit.getOrDefault(govId, 0L));
      if (!cycleClosed) {
        next = next.withRemittanceState(govId, accumulated);
        continue;
      }

      GovBudgetPolicy policy = govState.budgetPolicyOrDefault(govId);
      long ratePerMille = policy.remittancePerMilleToSuperior();
      GovernmentFormation formation = (GovernmentFormation) unit.module().orElseThrow();
      Optional<UnitId> superiorGov = formation.superiorGov();

      if (ratePerMille == 0L) {
        next = next.withRemittanceState(govId, accumulated.afterCycleClose(day, 0L, 0L, 0L, 0L));
        logSkipped(govId, day, ratePerMille, superiorGov, "rate-zero");
        continue;
      }
      long dueGrain =
          GovRemittanceState.applyPerMille(accumulated.cycleGrainCollectedMilli(), ratePerMille);
      long dueSilver =
          GovRemittanceState.applyPerMille(accumulated.cycleSilverCollectedMilli(), ratePerMille);
      if (dueGrain == 0L && dueSilver == 0L) {
        // 有实收但 floor 到 0（例如 rate=1‰ 而累计 < 1000 毫）与"本周期根本没实收"分开具名。
        next = next.withRemittanceState(govId, accumulated.afterCycleClose(day, 0L, 0L, 0L, 0L));
        logSkipped(
            govId,
            day,
            ratePerMille,
            superiorGov,
            accumulated.cycleReceiptsPresent() ? "due-zero-floor" : "no-cycle-receipts");
        continue;
      }
      if (superiorGov.isEmpty()) {
        // 无上级 = 设计上的 no-op（不是缺口）：不清零会跨周期重复计，但 last 读数记 0，不发告警。
        next = next.withRemittanceState(govId, accumulated.afterCycleClose(day, 0L, 0L, 0L, 0L));
        logSkipped(govId, day, ratePerMille, superiorGov, "no-superior");
        continue;
      }
      UnitId superiorId = superiorGov.get();
      if (superiorId.equals(govId)) {
        next = next.withRemittanceState(govId, accumulated.afterCycleClose(day, 0L, 0L, 0L, 0L));
        logSkipped(govId, day, ratePerMille, superiorGov, "self-superior");
        continue;
      }

      Unit superior = units.units().get(superiorId);
      if (superior == null || !(superior.module().orElse(null) instanceof GovernmentFormation)) {
        next =
            next.withRemittanceState(
                govId, accumulated.afterCycleClose(day, dueGrain, 0L, dueSilver, 0L));
        logContract(
            govId, superiorId, day, "superior-not-gov", "上级 GOV 不存在或不是 GovernmentFormation");
        contractAlert(unit, units, day, dueGrain, dueSilver, ratePerMille).ifPresent(alerts::add);
        continue;
      }

      HouseholdId source;
      HouseholdId target;
      try {
        source = GovernmentHouseholdResolver.requireGovernmentHousehold(unit, govId.value());
      } catch (IllegalArgumentException notResolvable) {
        next =
            next.withRemittanceState(
                govId, accumulated.afterCycleClose(day, dueGrain, 0L, dueSilver, 0L));
        logContract(
            govId, superiorId, day, "source-treasury-unresolvable", notResolvable.getMessage());
        contractAlert(unit, units, day, dueGrain, dueSilver, ratePerMille).ifPresent(alerts::add);
        continue;
      }
      try {
        target =
            GovernmentHouseholdResolver.requireGovernmentHousehold(superior, superiorId.value());
      } catch (IllegalArgumentException notResolvable) {
        next =
            next.withRemittanceState(
                govId, accumulated.afterCycleClose(day, dueGrain, 0L, dueSilver, 0L));
        logContract(
            govId, superiorId, day, "target-treasury-unresolvable", notResolvable.getMessage());
        contractAlert(unit, units, day, dueGrain, dueSilver, ratePerMille).ifPresent(alerts::add);
        continue;
      }

      AccountSession.ActorAccount sourceAccount = accounts.householdAccount(source);
      if (sourceAccount == null) {
        next =
            next.withRemittanceState(
                govId, accumulated.afterCycleClose(day, dueGrain, 0L, dueSilver, 0L));
        logContract(govId, superiorId, day, "source-account-missing", "源国库账户不在账户会话里");
        contractAlert(unit, units, day, dueGrain, dueSilver, ratePerMille).ifPresent(alerts::add);
        continue;
      }
      AccountSession.ActorAccount targetAccount = accounts.householdAccount(target);
      if (targetAccount == null) {
        next =
            next.withRemittanceState(
                govId, accumulated.afterCycleClose(day, dueGrain, 0L, dueSilver, 0L));
        logContract(govId, superiorId, day, "target-account-missing", "上级国库账户不在账户会话里");
        contractAlert(unit, units, day, dueGrain, dueSilver, ratePerMille).ifPresent(alerts::add);
        continue;
      }

      HouseholdInventory sourceView = inventoryView(sourceAccount);
      long availableGrain = AvailableStock.available(sourceView, GRAIN);
      long availableSilver = AvailableStock.available(sourceView, SILVER);
      long paidGrain = Math.min(dueGrain, availableGrain);
      long paidSilver = Math.min(dueSilver, availableSilver);
      if (paidGrain > 0L || paidSilver > 0L) {
        Map<CommodityId, Long> goods = new LinkedHashMap<>();
        Map<CurrencyId, Long> money = new LinkedHashMap<>();
        if (paidGrain > 0L) {
          goods.put(GRAIN, paidGrain);
        }
        if (paidSilver > 0L) {
          money.put(SILVER, paidSilver);
        }
        StockDeductionService.deduct(
            accounts,
            HouseholdStockDeduction.transfer(
                source,
                target,
                goods,
                money,
                DeductionReason.GOV_REMITTANCE,
                "remittance day="
                    + day
                    + " payer="
                    + govId.value()
                    + " payee="
                    + superiorId.value()),
            day);
      }
      next =
          next.withRemittanceState(
              govId, accumulated.afterCycleClose(day, dueGrain, paidGrain, dueSilver, paidSilver));
      logDay(
          govId,
          superiorId,
          source,
          target,
          day,
          ratePerMille,
          dueGrain,
          paidGrain,
          dueSilver,
          paidSilver);

      long shortfallGrain = dueGrain - paidGrain;
      long shortfallSilver = dueSilver - paidSilver;
      if (shortfallGrain > 0L || shortfallSilver > 0L) {
        shortfallAlert(unit, units, day, dueGrain, paidGrain, dueSilver, paidSilver, ratePerMille)
            .ifPresent(alerts::add);
      }
    }
    return new Outcome(next, alerts);
  }

  /**
   * 一个 GOV 的关账日 INFO；四组量逐值带出（短期缺口也在这条里，另有独立 kind 的信号）。{@code payer}/{@code payee} = GOV 单位
   * id；{@code from}/{@code to} = 真正落账的两个国库家户 id。
   */
  private static void logDay(
      UnitId payer,
      UnitId payee,
      HouseholdId from,
      HouseholdId to,
      long day,
      long ratePerMille,
      long dueGrain,
      long paidGrain,
      long dueSilver,
      long paidSilver) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOV_REMITTANCE_DAY",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "payer",
                payer.value(),
                "payee",
                payee.value(),
                "from",
                from.value(),
                "to",
                to.value(),
                "ratePerMille",
                ratePerMille,
                "dueGrain",
                dueGrain,
                "paidGrain",
                paidGrain,
                "shortfallGrain",
                dueGrain - paidGrain,
                "dueSilver",
                dueSilver,
                "paidSilver",
                paidSilver,
                "shortfallSilver",
                dueSilver - paidSilver));
  }

  /** rate=0 / 本周期无实收 / 无上级 / 自己上级：no-op + 具名 INFO（不报错、不转移）。 */
  private static void logSkipped(
      UnitId unitId, long day, long ratePerMille, Optional<UnitId> superiorGov, String reason) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOV_REMITTANCE_SKIPPED",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "unit",
                unitId.value(),
                "ratePerMille",
                ratePerMille,
                "superior",
                superiorGov.map(UnitId::value).orElse("-"),
                "reason",
                reason));
  }

  /** 契约故障 ERROR（不中断整日推进；状态损坏的那一个 GOV 记 paid=0 + 全缺口，另发 ADMIN_CONTRACT 信号）。 */
  private static void logContract(
      UnitId unitId, UnitId superiorId, long day, String stage, String detail) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "GOV_REMITTANCE_CONTRACT_VIOLATION",
                AppLogSource.DAILY_LOOP,
                "day",
                day,
                "unit",
                unitId.value(),
                "superior",
                superiorId.value(),
                "stage",
                stage,
                "reason",
                detail == null ? "unknown" : detail));
  }

  /** 契约故障信号（座位可解析才有信号；返回空 = 连座位都没有，只能靠日志，不发明 hex）。 */
  private static Optional<GovDaily.SignalDraft> contractAlert(
      Unit unit, UnitState units, long day, long dueGrain, long dueSilver, long ratePerMille) {
    Map<String, Long> evidence = new LinkedHashMap<>();
    evidence.put("ratePerMille", ratePerMille);
    evidence.put("dueGrain", dueGrain);
    evidence.put("dueSilver", dueSilver);
    return seatOf(unit, units, day)
        .map(
            seat ->
                new GovDaily.SignalDraft(
                    seat,
                    GovDaily.KIND_ADMIN_CONTRACT,
                    1L,
                    evidence,
                    "省上缴契约故障：上级/国库账户不可解析；不自动补、不降级"));
  }

  /** 不足告警（部分支付）：只发信号 + INFO 由 {@link #logDay} 承担；不自动注资/调率。 */
  private static Optional<GovDaily.SignalDraft> shortfallAlert(
      Unit unit,
      UnitState units,
      long day,
      long dueGrain,
      long paidGrain,
      long dueSilver,
      long paidSilver,
      long ratePerMille) {
    Map<String, Long> evidence = new LinkedHashMap<>();
    evidence.put("ratePerMille", ratePerMille);
    evidence.put("dueGrain", dueGrain);
    evidence.put("paidGrain", paidGrain);
    evidence.put("shortfallGrain", dueGrain - paidGrain);
    evidence.put("dueSilver", dueSilver);
    evidence.put("paidSilver", paidSilver);
    evidence.put("shortfallSilver", dueSilver - paidSilver);
    return seatOf(unit, units, day)
        .map(
            seat ->
                new GovDaily.SignalDraft(
                    seat,
                    GovDaily.KIND_ADMIN_REMITTANCE_SHORTFALL,
                    1L,
                    evidence,
                    "省上缴不足：逐腿 min(应缴, 国库可用) 已部分支付；不自动注资/调率"));
  }

  private static Optional<HexCoord> seatOf(Unit unit, UnitState units, long day) {
    Objects.requireNonNull(unit, "unit");
    return units.effectivePosition(unit.id(), SimosTimestamp.of(day));
  }

  /** 账户会话活账 → 只读 {@link HouseholdInventory} 视图（只为复用 {@link AvailableStock} 的唯一减法）。 */
  private static HouseholdInventory inventoryView(AccountSession.ActorAccount account) {
    return new HouseholdInventory(
        new HouseholdAccountKey(account.household()),
        account.goods(),
        account.money(),
        account.frozenGoods(),
        account.frozenMoney());
  }
}

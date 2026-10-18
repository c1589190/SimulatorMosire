package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.DebtSettlement.capitalizeArrears;
import static io.mosire.simos.economy.time.SettlementLogSupport.TRACE;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.ProductionWageAnchor;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionParticipantKey;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionReceipt;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;

/** 日结算阶段执行器（包内瞬态；只由 EconomyDayPipeline 按固定顺序调用）。 */
final class SettlementDayWagePipeline {

  /** 兼容旧调用：没有市场日信号 ⇒ 不调锚（fallback 语义）。 */
  static void settleWageAndCapitalize(SettlementDayContext ctx) {
    settleWageAndCapitalize(ctx, false);
  }

  /** 由 EconomySettlement 的日序主方法机械抽取而来。 */
  static void settleWageAndCapitalize(SettlementDayContext ctx, boolean marketDay) {
    // ── ★★★ W3/P4a（D-032 P4a）：工资只付现金 → 未付差额成普通工资债 ─────────────────────────
    //   ★ 位置钉死：`driveProductionDay` 与关账/收获/铸币之后。due 只读本 tick
    //     ProductionReceipt 的实际劳动；M8 在本轮 M2 之后 ⇒ 本 tick 工资债不会被同 tick 二次偿还。
    //   ★ 账户权威 = AccountSession 快照；P4a 起不再读任何商品库存/barter。
    ctx.wageAccountSnapshot = ctx.accounts.snapshot();
    // ★★★ W5-A（缺陷：W3 用 tick 前 unit 快照）：这里**必须**读当刻权威候选态，不得传日结算入口抓的 `units`。
    //   本 tick 更早的阶段（进入执行 / 自动组织 / 模式变迁 / 迁移搬迁）会经契约口 `createInstance` 新建实例；
    //   它们的 ProductionReceipt 就在 `productionDay` 里。旧快照看不见那些实例 ⇒ collectObligations 会把
    //   "回执 unit 不在表里" 误报成 `wage-obligation-unit-unknown`（或跳过本应支付的工资义务）。
    //   ★ 只换数据来源：W3 的现金支付/成债公式与调用参数一律不动。
    ctx.wageSettlementUnits = ctx.session.sheet().units();
    ctx.wageSettlement =
        WageSettlement.settle(
            ctx.day,
            ctx.dueCycle,
            ctx.dayView,
            ctx.wageSettlementUnits,
            ctx.wageAccountSnapshot,
            ctx.householdGoods,
            ctx.householdMoney,
            ctx.householdFrozenGoods,
            ctx.householdFrozenMoney,
            ctx.householdOfActor,
            ctx.debts,
            ctx.ledger,
            ctx.productionDay);
    var wageCarries = ctx.session.sheet().wageCarryRemainders();
    wageCarries.clear();
    wageCarries.putAll(ctx.wageSettlement.carryRemainders());
    // ★ P4a：主 M8 不再生成/传递 sameDayWageDebtIds；保留空集只为旧 capitalizeArrears 退出路径编译适配。
    ctx.sameDayWageDebtIds = new LinkedHashSet<>();
    // ★★★ D-032 W4：把 W3 已算出的读数原样投递给 app 只读面（不重算、不写状态；同日 tick 才可信）。
    WageSettlementReadoutFeed.publish(ctx.meta.mapId(), ctx.day, ctx.wageSettlement.readouts());
    if (TRACE.isTraceEnabled() && !ctx.wageSettlement.readouts().isEmpty()) {
      for (WageSettlementReadout readout : ctx.wageSettlement.readouts()) {
        EventLog.channel(TRACE)
            .trace(
                LogEvent.of(
                    "WAGE_SETTLEMENT_READOUT",
                    EconomyLogSource.ECONOMY_SETTLEMENT,
                    "day",
                    ctx.day,
                    "unit",
                    readout.unit().value(),
                    "role",
                    readout.role().value(),
                    "worker",
                    readout.worker().value(),
                    "payer",
                    readout.payer().value(),
                    "dueMilli",
                    readout.dueMilli(),
                    "paidMoneyMilli",
                    readout.paidMoneyMilli(),
                    "arrearsMilli",
                    readout.arrearsMilli(),
                    "debtContractId",
                    readout.debtContractId().map(DebtContractId::value).orElse("-")));
      }
    }

    // ── ★★★ D-032 P4c2：U20 锚观测/调价（只在市场日、M8 完成之后）───────────────────────────
    //   ★ 本 tick due 使用 WageSettlement 开头冻结的旧锚快照；这里写的是下一 tick 的锚。
    //   ★ 非市场日连聚合都不做 ⇒ 锚表逐值不变；本 tick 的 M3/M6 锁与 due 已经发生，绝不追改。
    observeAndAdjustWageAnchors(ctx, marketDay);

    // ── 3b. ★★ E4c：欠租/欠薪资本化（生产/租金阶段之后）─────────────────────────────────────
    //   ★★ 只对**本日 ledger 的 Arrear 读数**（owed > 0）执行：把"制度规定未付"落成连续合同债权；
    //     **不移动任何商品/货币库存**（欠款本来就是未付），也**不清零 Arrear 读数**（读数仍是制度事实）。
    //   ★★ P2：端点解析改走 {@link DebtPartyResolver}（唯一解析点）：家户 actor 直取；聚合主体先查
    //     E2 生产组织，再按 ESTATE/WORKSHOP 的人口成分回退；多户按人口最大余数拆分。解析不到 ⇒ 具名跳过（不伪造）。
    //   ★ 位置在**借粮/偿还之前**：新增的既有债因此同日进入偿还排序（D-031 起不再进入任何借款额度门）；
    //     但不在当日起始本金快照里 ⇒ 当天不计息（与借粮同口径，见 principalAtDayStart）。
    capitalizeArrears(
        ctx.dayView,
        ctx.settlementIndex,
        ctx.topology,
        ctx.ledger,
        ctx.householdEconomies,
        ctx.debts,
        ctx.capitalizedArrearsToday,
        ctx.day,
        ctx.dueCycle,
        ctx.sameDayWageDebtIds);
    ctx.capitalized = ctx.ledger.toLedger();
    if (!ctx.capitalized.debtCapitalizations().isEmpty()
        || !ctx.capitalized.unresolvedDebtCapitalizations().isEmpty()) {
      EventLog.channel(TRACE)
          .info(
              LogEvent.of(
                  "CAPITALIZE_ARREARS",
                  EconomyLogSource.ECONOMY_DEBT,
                  "day",
                  ctx.day,
                  "capitalized",
                  ctx.capitalized.debtCapitalizations().size(),
                  "unresolved",
                  ctx.capitalized.unresolvedDebtCapitalizations().size()));
    }
    if (TRACE.isDebugEnabled()) {
      if (!ctx.capitalized.debtCapitalizations().isEmpty()
          || !ctx.capitalized.unresolvedDebtCapitalizations().isEmpty()) {
        EventLog.channel(TRACE)
            .debug(
                LogEvent.of(
                    "CAPITALIZE_ARREARS_DETAIL",
                    EconomyLogSource.ECONOMY_DEBT,
                    "day",
                    ctx.day,
                    "capitalized",
                    ctx.capitalized.debtCapitalizations().size(),
                    "unresolved",
                    ctx.capitalized.unresolvedDebtCapitalizations().size()));
      }
    }
  }
  /**
   * 市场日 M8 之后：按锚聚合 M3 target 与 M8 实际 matched，算下一 tick 锚并写回会话工作表。
   *
   * <p>★ target 来源 = {@code ExpectedProductionPlan.wageDemands}（同锚多 unit 求和）；matched 来源 =
   * {@code ProductionReceipt.laborContributionsMilli} 里 MONEY_WAGE 角色的实际 labor（同锚求和）。只计实际出工，
   * 被拒/未执行 unit 没有回执 ⇒ 不虚报 matched。
   */
  private static void observeAndAdjustWageAnchors(SettlementDayContext ctx, boolean marketDay) {
    if (!marketDay || ctx.expectedProductionPlan == null) {
      return;
    }
    Map<WageAnchorKey, ProductionWageAnchor> anchors = ctx.dayView.productionWageAnchors();
    Map<WageAnchorKey, Long> targets;
    Map<WageAnchorKey, Long> matched;
    Map<WageAnchorKey, ProductionWageAnchor> adjusted;
    try {
      targets = aggregateWageTargets(ctx, anchors);
      if (targets.isEmpty()) {
        return;
      }
      matched = aggregateMatchedLabor(ctx);
      adjusted =
          WageAnchorBook.applyObservations(
              anchors,
              targets,
              matched,
              ctx.day,
              true,
              ProductionWageBook.WagePricingPolicy.defaults());
    } catch (ArithmeticException overflow) {
      throw anchorContractFault(
          "wage-anchor-overflow",
          "target/matched/streak 累加或调价溢出: " + overflow.getMessage(),
          overflow);
    }
    // ★ 唯一锚账：写回 session sheet 的 productionWageAnchors，不落第二份锚账。
    LinkedHashMap<WageAnchorKey, ProductionWageAnchor> sheetAnchors =
        ctx.session.sheet().productionWageAnchors();
    sheetAnchors.clear();
    sheetAnchors.putAll(adjusted);
  }

  /** M3 WageDemand 按 anchor 聚合 target；同锚多 unit 求和，并核对锚存在与币种一致。 */
  private static Map<WageAnchorKey, Long> aggregateWageTargets(
      SettlementDayContext ctx, Map<WageAnchorKey, ProductionWageAnchor> anchors) {
    Map<WageAnchorKey, Long> targets = new LinkedHashMap<>();
    for (WageDemand demand : ctx.expectedProductionPlan.wageDemands()) {
      long target = demand.targetLaborMilliPerDay();
      if (target <= 0L) {
        continue;
      }
      WageAnchorKey key = demand.anchorKey();
      ProductionWageAnchor anchor = anchors.get(key);
      if (anchor == null) {
        throw anchorContractFault("wage-anchor-missing", key.toString());
      }
      if (!anchor.currency().equals(demand.currency())) {
        throw anchorContractFault(
            "wage-anchor-currency-mismatch",
            key + " anchor=" + anchor.currency().value() + " demand=" + demand.currency().value());
      }
      targets.merge(key, target, Math::addExact);
    }
    return targets;
  }

  /** M8 实际 labor 按 anchor 聚合 matched；只计 MONEY_WAGE 角色，同锚多 unit/worker 求和。 */
  private static Map<WageAnchorKey, Long> aggregateMatchedLabor(SettlementDayContext ctx) {
    Map<WageAnchorKey, Long> matched = new LinkedHashMap<>();
    for (ProductionReceipt receipt : ctx.productionDay.receipts()) {
      ProductionProcess process = ctx.wageSettlementUnits.get(receipt.unit());
      if (process == null) {
        // 没有当刻生产实例的回执不参与锚观察（旧非名册路径可能没有实例；不伪造 matched）。
        continue;
      }
      IndustryDefinitionView industry =
          ctx.dayView
              .definitions()
              .industry(receipt.plan())
              .orElseThrow(
                  () ->
                      anchorContractFault(
                          "wage-anchor-observation-industry-missing",
                          receipt.unit().value() + "|" + receipt.plan().value()));
      for (Map.Entry<ProductionParticipantKey, Long> contribution :
          receipt.laborContributionsMilli().entrySet()) {
        ProductionParticipantKey participant = contribution.getKey();
        ProductionRoleDefinition role = roleOf(industry, participant.role());
        if (role == null) {
          throw anchorContractFault(
              "wage-anchor-observation-role-missing",
              receipt.unit().value() + "|" + participant.role().value());
        }
        if (!(role.payment() instanceof RolePayment.MoneyWage)) {
          continue;
        }
        Optional<WageAnchorKey> key =
            WageQuoteBridge.wageAnchorKeyOf(ctx.dayView, process, role.id());
        if (key.isEmpty()) {
          continue; // 定不出市场区 ⇒ M3 也不会生成该锚 target；不凭猜测聚合
        }
        matched.merge(key.get(), contribution.getValue(), Math::addExact);
      }
    }
    return matched;
  }

  private static ProductionRoleDefinition roleOf(
      IndustryDefinitionView industry, ProductionRoleId roleId) {
    for (ProductionRoleDefinition role : industry.roles()) {
      if (role.id().equals(roleId)) {
        return role;
      }
    }
    return null;
  }

  private static IllegalStateException anchorContractFault(String word, String detail) {
    return anchorContractFault(word, detail, null);
  }

  private static IllegalStateException anchorContractFault(
      String word, String detail, Throwable cause) {
    EventLog.channel(EconomyLog.settlement())
        .error(
            LogEvent.of(
                "WAGE_ANCHOR_CONTRACT_FAULT",
                EconomyLogSource.ECONOMY_SETTLEMENT,
                "failure",
                word,
                "detail",
                detail == null ? "-" : detail,
                "cause",
                cause == null ? "-" : cause.getClass().getSimpleName()));
    return new IllegalStateException(word + ": " + detail, cause);
  }

}

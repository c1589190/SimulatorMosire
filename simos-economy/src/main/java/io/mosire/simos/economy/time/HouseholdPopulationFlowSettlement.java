package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★★ <b>D-032 C4 / S7-C：向下流动的 population-only 唯一执行原语。</b>
 *
 * <p>★ <b>父运行序列锚点</b>：{@code
 * docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md}
 * §8.3/§8.4/§11/§13。 {@link HouseholdFlowBook} 在 {@link HouseholdTransferSettlement#apply}
 * 之外只允许走本入口，把“债务增量触发的 OWNER→PROLETARIAN 向下流动”表达成<b>纯人口迁移</b>。
 *
 * <pre>
 * applyPopulationOnly(...)
 *   ① 校验 source/target 经济行与账户存在；population ∈ (0, sourcePopulationBefore)（source 至少保留 1 人）
 *   ② 仅缩 source 的 PRODUCTION 劳动承诺：after = floor(before × (sourcePopulationBefore − population) / sourcePopulationBefore)
 *      非 PRODUCTION 承诺逐值不动；不向 target 新建任何承诺
 *   ③ 只追加一条瞬态 EconomyPopulationTransfer(source,target,population,targetHex,Optional.empty(),
 *      targetIsNew,reason)；targetMode 为空表示目标壳无 mode/position
 *   ④ 绝不移动 money / goods / debt / LAND / 非 LAND 资产 / 经验；绝不创建 target 库存/债/承诺；
 *      绝不直接写 HouseholdEconomy.population/laborMilli
 * </pre>
 *
 * <p>★★ <b>为什么不调用完整 {@link HouseholdTransferSettlement#apply}</b>：完整 apply 会按人口比例搬钱/货/债/经验，
 * 这正是用户本轮明确否决的“不净身出户”。本类只复用其 package-private 的 PRODUCTION 承诺 shrink 算法（单一拼写点）， 不复制任何转移公式。
 *
 * <p>★ <b>失败语义</b>：任何具名校验失败都抛 {@link IllegalStateException}；调用方丢弃整次 advance，不存在半状态提交。
 */
final class HouseholdPopulationFlowSettlement {

  private static final org.slf4j.Logger LOG = EconomyLog.migration();

  private HouseholdPopulationFlowSettlement() {}

  /**
   * ★ C4 唯一 population-only 执行入口。
   *
   * @param session 推进会话（只写瞬态人口 outbox；不写 householdEconomies.population/laborMilli）
   * @param accounts 账户会话（只校验 source/target 账户存在，不搬钱/货）
   * @param day 世界日；不得为负
   * @param ordinal 本批内调用序号；不得为负（进日志）
   * @param source 源家户稳定身份；非 null，且不得 == target
   * @param target 目标家户稳定身份；非 null
   * @param population 本次迁出人口；必须 &gt; 0 且严格小于 {@code sourcePopulationBefore}（source 至少保留 1 人）
   * @param targetHex 目标家户所在格；非 null，且必须等于 target 经济行当刻 hex
   * @param targetIsNew true = 本批新建的 0 人口壳，首次迁入；Social 侧据此 CREATE_HOUSEHOLD
   * @param reason 迁移原因；非 null
   * @param sourcePopulationBefore 本笔之前源户人口；必须 &gt; population
   * @param targetPopulationBefore 本笔之前目标户人口（只进日志/校验；人口权威在 Social）
   */
  static void applyPopulationOnly(
      EconomySession session,
      AccountSession accounts,
      long day,
      int ordinal,
      HouseholdId source,
      HouseholdId target,
      long population,
      HexCoord targetHex,
      boolean targetIsNew,
      String reason,
      long sourcePopulationBefore,
      long targetPopulationBefore) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(targetHex, "targetHex");
    Objects.requireNonNull(reason, "reason");
    if (day < 0L) {
      throw fail("household-population-flow-day-negative", "day=" + day);
    }
    if (ordinal < 0) {
      throw fail("household-population-flow-ordinal-negative", "ordinal=" + ordinal);
    }
    if (source.equals(target)) {
      throw fail("household-population-flow-source-equals-target", "household=" + source.value());
    }
    if (population <= 0L) {
      throw fail("household-population-flow-population-nonpositive", "population=" + population);
    }
    if (sourcePopulationBefore <= 0L) {
      throw fail(
          "household-population-flow-source-population-nonpositive",
          "sourcePopulationBefore=" + sourcePopulationBefore);
    }
    if (population >= sourcePopulationBefore) {
      throw fail(
          "household-population-flow-would-empty-source",
          "source="
              + source.value()
              + " population="
              + population
              + " sourcePopulationBefore="
              + sourcePopulationBefore);
    }
    if (targetPopulationBefore < 0L) {
      throw fail(
          "household-population-flow-target-population-negative",
          "targetPopulationBefore=" + targetPopulationBefore);
    }

    EconomyStateBuilder sheet = session.sheet();
    var households = sheet.householdEconomies();
    HouseholdEconomy sourceRow = households.get(source);
    if (sourceRow == null) {
      throw fail("household-population-flow-source-missing", "source=" + source.value());
    }
    HouseholdEconomy targetRow = households.get(target);
    if (targetRow == null) {
      throw fail("household-population-flow-target-missing", "target=" + target.value());
    }
    if (!targetHex.equals(targetRow.view().hex())) {
      throw fail(
          "household-population-flow-target-hex-mismatch",
          "target="
              + target.value()
              + " targetHex="
              + targetHex
              + " rowHex="
              + targetRow.view().hex());
    }
    if (accounts.accountOrNull(source) == null) {
      throw fail("household-population-flow-source-account-missing", "source=" + source.value());
    }
    if (accounts.accountOrNull(target) == null) {
      throw fail("household-population-flow-target-account-missing", "target=" + target.value());
    }

    // ② 只缩 source 的 PRODUCTION 劳动承诺；非 PRODUCTION 原额保留，target 不新建任何承诺。
    HouseholdTransferSettlement.LaborCommitmentShrink laborShrink =
        HouseholdTransferSettlement.shrinkSourceProductionCommitments(
            sheet, source, population, sourcePopulationBefore, false, day);

    // ③ 只追加人口 outbox；targetMode 空 = 目标壳无 mode/position。
    session.recordPopulationTransfer(
        new EconomyPopulationTransfer(
            source, target, population, targetHex, Optional.empty(), targetIsNew, reason));

    logApplied(
        day,
        ordinal,
        source,
        target,
        targetHex,
        population,
        targetIsNew,
        reason,
        sourcePopulationBefore,
        targetPopulationBefore,
        laborShrink);
  }

  private static void logApplied(
      long day,
      int ordinal,
      HouseholdId source,
      HouseholdId target,
      HexCoord targetHex,
      long population,
      boolean targetIsNew,
      String reason,
      long sourcePopulationBefore,
      long targetPopulationBefore,
      HouseholdTransferSettlement.LaborCommitmentShrink laborShrink) {
    if (!LOG.isInfoEnabled()) {
      return;
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "HOUSEHOLD_POPULATION_FLOW_APPLIED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "ordinal",
                ordinal,
                "source",
                source.value(),
                "target",
                target.value(),
                "hex",
                targetHex.toString(),
                "population",
                population,
                "sourcePopulationBefore",
                sourcePopulationBefore,
                "sourcePopulationAfter",
                Math.subtractExact(sourcePopulationBefore, population),
                "targetPopulationBefore",
                targetPopulationBefore,
                "targetPopulationAfter",
                Math.addExact(targetPopulationBefore, population),
                "targetIsNew",
                targetIsNew,
                "targetMode",
                "none",
                "laborCommitmentBeforeMilli",
                laborShrink.beforeTotal(),
                "laborCommitmentAfterMilli",
                laborShrink.afterTotal(),
                "movedMoney",
                "none",
                "movedGoods",
                "none",
                "movedDebt",
                "none",
                "movedLand",
                "none",
                "movedNonLandAssets",
                "none",
                "movedExperience",
                "none",
                "reason",
                reason));
  }

  private static IllegalStateException fail(String reason, String detail) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "HOUSEHOLD_POPULATION_FLOW_FAILED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "reason",
                reason,
                "detail",
                detail));
    return new IllegalStateException(reason + ": " + detail);
  }
}

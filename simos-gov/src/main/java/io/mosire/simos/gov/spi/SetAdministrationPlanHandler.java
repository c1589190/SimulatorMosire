package io.mosire.simos.gov.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovLog;
import io.mosire.simos.gov.GovLogSource;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.change.GovChangeSet;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code gov.SetAdministrationPlan}（Z2，gov 首个 command handler）：整体设置一个 GOV 的行政编制计划</b> （{@link
 * GovAdministrationPlan}，设计书 §4.1）。
 *
 * <pre>{@code
 * {"unitId":"gov-1",
 *  "securityPlannedLaborMilli":3200000,
 *  "paperworkPlannedLaborMilli":1600000,
 *  "postTiers":[
 *    {"tierId":"tier-1","securityWeightPerMille":1000,"paperworkWeightPerMille":0},
 *    {"tierId":"tier-2","securityWeightPerMille":0,"paperworkWeightPerMille":1000},
 *    {"tierId":"tier-3","securityWeightPerMille":500,"paperworkWeightPerMille":500}],
 *  "securitySupplyStaticModifierPerMille":1000,
 *  "paperworkSupplyStaticModifierPerMille":1000,
 *  "securityDemandStaticModifierPerMille":1000,
 *  "paperworkDemandStaticModifierPerMille":1000,
 *  "supernumerarySqrtCoefficient":1}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code unitId} 必填；其余字段可缺省（计划量 0、默认 3 档、修正 1000‰、{@code k=1}，见 {@link
 * GovPayloads}）。同类型重复设置 = <b>整体替换</b>；载荷与既有计划逐值相同 ⇒ 空变更集（幂等 no-op，不落 revision）。
 *
 * <p>★ <b>守卫与拒因</b>：{@code unitId} 必须已存在且带 {@link GovernmentFormation}（GOV 编制单位）——否则具名 {@code
 * Rejected}，零 revision；坏 JSON/坏字段/负值/档数不为 3/档 id 重复等全部由 {@link GovPayloads} 与 {@link
 * GovAdministrationPlan} 构造期给出。
 *
 * <p>★ <b>GM-only（Z2 冻结）</b>：实现 {@link GmOnlyCommand} ⇒ 排除出令白名单 / {@code RegisterEffect} / 决策人目录；
 * GM 的 {@code simos.command.submit} 可用。Z3 接决策人工具 + 审批链时再按需打开（不得提前放进决策人路径）。★ 不实现 {@code
 * CommandTargets}：当前不在决策令路径，目标声明与工具面一并归 Z3（照 Z1a 的 {@code EconomyUpsertIndustryHandler} 先例）。
 *
 * <p>★ <b>日志（AGENTS §一.9）</b>：成功 INFO（{@code GOV_SET_ADMINISTRATION_PLAN_APPLIED}）、业务/载荷拒绝 INFO
 * （{@code ..._REJECTED}，经 {@code logReason} 脱敏）；切片装配/状态一致性故障 ERROR（{@code ..._CONTRACT_VIOLATION}）
 * 且不降级、原样抛出。无 WARN 降级。
 */
public final class SetAdministrationPlanHandler implements CommandHandler, GmOnlyCommand {

  /** 命令类型（唯一拼写点：Shell 注册、catalog 与 GM 提交都从这里取/对齐）。 */
  public static final String TYPE = "gov.SetAdministrationPlan";

  private static final Logger LOG = GovLog.command();

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GovSnapshot snapshot;
    try {
      snapshot = GovSnapshots.of(state);
    } catch (RuntimeException e) {
      logContract("state-assembly", e);
      throw contractFailure(e);
    }
    String unitForLog = null;
    try {
      JsonNode payload = GovPayloads.parse(payloadJson);
      UnitId unitId = GovPayloads.requireUnitId(payload);
      unitForLog = unitId.value();
      requireGovernmentUnit(state, unitId);
      GovAdministrationPlan plan = GovPayloads.administrationPlan(payload);
      boolean existed = snapshot.state().administrationPlans().containsKey(unitId);
      GovState next = snapshot.state().withAdministrationPlan(unitId, plan);
      GovChangeSet changeSet = GovChangeSet.between(snapshot.state(), next);
      logApplied(unitId, plan, existed, changeSet);
      return new HandlerOutcome.Applied(changeSet);
    } catch (IllegalStateException e) {
      logContract("handle", e);
      throw contractFailure(e);
    } catch (IllegalArgumentException e) {
      logRejected(unitForLog, e);
      return new HandlerOutcome.Rejected(e.getMessage());
    } catch (RuntimeException e) {
      logContract("handle", e);
      throw contractFailure(e);
    }
  }

  /**
   * 契约故障的 fail-closed 出口：<b>抛新实例</b>（不写 {@code throw e}——SpotBugs 的 {@code
   * THROWS_METHOD_THROWS_RUNTIMEEXCEPTION} 只放行“抛新实例”，与 {@code SqliteStore} 的门禁口径一致），原异常挂 cause 不丢。
   */
  private static IllegalStateException contractFailure(RuntimeException failure) {
    if (failure instanceof IllegalStateException illegalState) {
      return new IllegalStateException(illegalState.getMessage(), illegalState);
    }
    return new IllegalStateException(
        "gov 命令状态组装/处理契约故障: " + failure.getClass().getSimpleName(), failure);
  }

  /**
   * GOV 编制单位守卫：{@code unitId} 必须在 unit 切片里存在，且 {@code Unit.module} 是 {@link GovernmentFormation}。
   *
   * <p>★ 单位不存在/不是 GOV ⇒ {@link IllegalArgumentException}（业务拒绝 ⇒ INFO + {@code Rejected}）；unit 切片缺失或
   * 类型不符 ⇒ {@link IllegalStateException}（装配故障 ⇒ ERROR + 原样抛）。
   */
  private static void requireGovernmentUnit(SimulationState state, UnitId unitId) {
    Snapshot snapshot =
        state
            .module("unit")
            .orElseThrow(
                () -> new IllegalStateException("state 里没有 unit 切片（装配故障：gov 计划要判 GOV 单位）"));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalStateException(
          "state 的 unit 切片不是 UnitSnapshot: " + snapshot.getClass().getName());
    }
    Unit unit = unitSnapshot.state().units().get(unitId);
    if (unit == null) {
      throw new IllegalArgumentException("GOV 单位不存在: " + unitId);
    }
    if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
      throw new IllegalArgumentException("unit 不是 GOV 编制单位（缺 GovernmentFormation）: " + unitId);
    }
  }

  private static void logApplied(
      UnitId unitId, GovAdministrationPlan plan, boolean existed, GovChangeSet changeSet) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOV_SET_ADMINISTRATION_PLAN_APPLIED",
                GovLogSource.GOV_COMMAND,
                "unit",
                unitId.value(),
                "mode",
                changeSet.isEmpty() ? "noop" : (existed ? "replaced" : "created"),
                "securityPlannedLaborMilli",
                plan.securityPlannedLaborMilli(),
                "paperworkPlannedLaborMilli",
                plan.paperworkPlannedLaborMilli(),
                "postTiers",
                plan.postTiers().size(),
                "securitySupplyStaticModifierPerMille",
                plan.securitySupplyStaticModifierPerMille(),
                "paperworkSupplyStaticModifierPerMille",
                plan.paperworkSupplyStaticModifierPerMille(),
                "securityDemandStaticModifierPerMille",
                plan.securityDemandStaticModifierPerMille(),
                "paperworkDemandStaticModifierPerMille",
                plan.paperworkDemandStaticModifierPerMille(),
                "k",
                plan.supernumerarySqrtCoefficient(),
                "changed",
                !changeSet.isEmpty()));
  }

  private static void logRejected(String unitForLog, IllegalArgumentException e) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOV_SET_ADMINISTRATION_PLAN_REJECTED",
                GovLogSource.GOV_COMMAND,
                "reason",
                GovPayloads.logReason(e.getMessage()),
                "unit",
                unitForLog == null ? "-" : unitForLog));
  }

  private static void logContract(String stage, RuntimeException failure) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "GOV_SET_ADMINISTRATION_PLAN_CONTRACT_VIOLATION",
                GovLogSource.GOV_COMMAND,
                "stage",
                stage,
                "failure",
                failure.getClass().getSimpleName(),
                "reason",
                GovPayloads.logReason(failure.getMessage())));
  }
}

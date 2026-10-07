package io.mosire.simos.gov.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.gov.GovBudgetPolicy;
import io.mosire.simos.gov.GovBudgetPolicyEditMode;
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
 * ★★ <b>{@code gov.SetBudgetPolicy}（Z2，gov 首个 handler 批）：整体设置一个 GOV 的国库预算政策</b> （{@link
 * GovBudgetPolicy}，设计书 §4.1 / §9）。
 *
 * <pre>{@code
 * {"unitId":"gov-1",
 *  "orderedCategories":[
 *    {"category":"ADMIN_STIPEND","minPerCycle":0,"capPerCycle":100000},
 *    {"category":"MILITARY_STIPEND","minPerCycle":0,"capPerCycle":50000},
 *  {"category":"ADMIN_SALARY","minPerCycle":0,"capPerCycle":30000}],
 *  *  "officialSalaryRule":{"grainMilliPerCommittedHour":10,"silverMilliPerCommittedHour":5},
 *  "remittancePerMilleToSuperior":500,
 *  "mode":"PATCH"}
 * }</pre>
 *
 * <p>★ <b>载荷语义（Z7e-3 双模，控制方 2026-10-23 裁定 A+B："AB同时应用吧"）</b>：{@code unitId} 必填；{@code mode} 缺省 =
 * {@link GovBudgetPolicyEditMode#PATCH}（缺省字段<b>保留现值</b>：只改 {@code remittancePerMilleToSuperior}
 * 不会顺手 清空类别表/工资规则；清空类别表要显式 {@code "orderedCategories":[]}），{@code mode:"REPLACE"} = 旧整表替换（类别表缺省 =
 * 空、工资规则缺省 = 0/0、上缴比例缺省 = 0）。{@code remittancePerMilleToSuperior} 0..1000‰ 由 {@link
 * GovBudgetPolicy} 构造期判。类别词表是 {@code GovBudgetCategory} 的常量名，未知类别具名拒。载荷与既有政策逐值相同 ⇒ 空变更集（幂等 no-op，不落
 * revision）。
 *
 * <p>★ <b>守卫与拒因</b>：{@code unitId} 必须已存在且带 {@link GovernmentFormation}（GOV 编制单位）——否则具名 {@code
 * Rejected}，零 revision；坏 JSON/坏字段/负值/min &gt; cap/类别重复等全部由 {@link GovPayloads} 与 {@link
 * GovBudgetPolicy} 构造期给出。
 *
 * <p>★ <b>GM-only（Z2 冻结）</b>：实现 {@link GmOnlyCommand} ⇒ 排除出令白名单 / {@code RegisterEffect} / 决策人目录；
 * GM 的 {@code simos.command.submit} 可用。Z3 接决策人工具 + 审批链时再按需打开（不得提前放进决策人路径）。★ 不实现 {@code
 * CommandTargets}：当前不在决策令路径，目标声明与工具面一并归 Z3（照 Z1a 先例）。
 *
 * <p>★ <b>日志（AGENTS §一.9）</b>：成功 INFO（{@code GOV_SET_BUDGET_POLICY_APPLIED}）、业务/载荷拒绝 INFO （{@code
 * ..._REJECTED}，经 {@code logReason} 脱敏）；切片装配/状态一致性故障 ERROR（{@code ..._CONTRACT_VIOLATION}）
 * 且不降级、原样抛出。无 WARN 降级。
 */
public final class SetBudgetPolicyHandler implements CommandHandler, GmOnlyCommand {

  /** 命令类型（唯一拼写点：Shell 注册、catalog 与 GM 提交都从这里取/对齐）。 */
  public static final String TYPE = "gov.SetBudgetPolicy";

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
      GovBudgetPolicy current = snapshot.state().budgetPolicy(unitId).orElse(null);
      GovBudgetPolicyEditMode requestedMode = GovPayloads.editMode(payload);
      GovBudgetPolicy policy = GovPayloads.budgetPolicy(payload, current);
      boolean existed = snapshot.state().budgetPolicies().containsKey(unitId);
      GovState next = snapshot.state().withBudgetPolicy(unitId, policy);
      GovChangeSet changeSet = GovChangeSet.between(snapshot.state(), next);
      logApplied(unitId, policy, existed, requestedMode, changeSet);
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
                () -> new IllegalStateException("state 里没有 unit 切片（装配故障：gov 政策要判 GOV 单位）"));
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
      UnitId unitId,
      GovBudgetPolicy policy,
      boolean existed,
      GovBudgetPolicyEditMode requestedMode,
      GovChangeSet changeSet) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOV_SET_BUDGET_POLICY_APPLIED",
                GovLogSource.GOV_COMMAND,
                "unit",
                unitId.value(),
                "mode",
                changeSet.isEmpty()
                    ? "noop"
                    : (!existed
                        ? "created"
                        : (requestedMode == GovBudgetPolicyEditMode.PATCH
                            ? "patched"
                            : "replaced")),
                "requestMode",
                requestedMode.name(),
                "categories",
                policy.orderedCategories().size(),
                "grainMilliPerCommittedHour",
                policy.officialSalaryRule().grainMilliPerCommittedHour(),
                "silverMilliPerCommittedHour",
                policy.officialSalaryRule().silverMilliPerCommittedHour(),
                "remittancePerMilleToSuperior",
                policy.remittancePerMilleToSuperior(),
                "changed",
                !changeSet.isEmpty()));
  }

  private static void logRejected(String unitForLog, IllegalArgumentException e) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOV_SET_BUDGET_POLICY_REJECTED",
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
                "GOV_SET_BUDGET_POLICY_CONTRACT_VIOLATION",
                GovLogSource.GOV_COMMAND,
                "stage",
                stage,
                "failure",
                failure.getClass().getSimpleName(),
                "reason",
                GovPayloads.logReason(failure.getMessage())));
  }
}

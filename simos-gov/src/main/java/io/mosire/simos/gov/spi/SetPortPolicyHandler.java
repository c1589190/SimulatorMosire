package io.mosire.simos.gov.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.gov.GovLog;
import io.mosire.simos.gov.GovLogSource;
import io.mosire.simos.gov.GovPortPolicy;
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
 * ★★ <b>P-T1a：{@code gov.SetPortPolicy} —— 整体设置一个 GOV 的口岸管制政策（四元组规则）</b>
 * （{@link GovPortPolicy}，2026-10-09 口岸设计书 §4.2/§4.3；2026-10-10 追加裁定 3 §12 + 冻结口径 T-5；G9"口岸/禁运 =
 * 法律规定层"）。
 *
 * <pre>{@code
 * {"unitId":"gov-1",
 *  "commodityRules":{
 *    "grain":{"entryRestrictionPerMille":1000,"exitRestrictionPerMille":250,
 *             "entryTax":{"mode":"per_unit_milli","amount":5},
 *             "exitTax":{"mode":"ad_valorem_per_mille","amount":100}}},
 *  "currencyRules":{"silver":{"entryRestrictionPerMille":1000}}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code unitId} 必填；两张表可缺省（缺省/<b>显式空对象</b> = 空表 = 该类<b>不限制、不收税</b>，I-P1
 * 用户「肯定0啊」）。<b>每类四个数</b>（入口限制‰ / 出口限制‰ / 入口税 / 出口税）各自可缺省（缺省 = 0 / 不收税）；
 * 税从量与从价都支持（{@code mode} 各自指定，见 {@link GovPayloads#portPolicy}）。
 * 同类型重复设置 = <b>整表替换</b>；与既有政策逐值相同 ⇒ 空变更集（幂等 no-op，不落 revision）。
 *
 * <p>★ <b>守卫与拒因</b>：{@code unitId} 必须已存在且带 {@link GovernmentFormation}（GOV 编制单位）——否则具名 {@code
 * Rejected}，零 revision；坏 JSON / 键空白 / 非整数 / 负强度 / 负税 / 计量方式非法 / 规则里拼错字段名 / id 词法非法全部由 {@link
 * GovPayloads} 与 {@link GovPortPolicy} 构造期给出（N1 负向判据：<b>非法政策 fail-closed 具名拒，不静默忽略</b>）。
 *
 * <p>★ <b>GM-only（R2 冻结）</b>：实现 {@link GmOnlyCommand} ⇒ 排除出令白名单 / {@code RegisterEffect} / 决策人目录；GM
 * 的 {@code simos.command.submit} 可用。设计书 §9 的开放点 O4（"谁有权设限制、审批链"）<b>留给 GOV 优化</b>，本批只做机制与算法 ⇒ 与
 * {@code SetAdministrationPlanHandler} 同口径先挂 GM 面。
 *
 * <p>★ <b>日志（AGENTS §一.9）</b>：成功 INFO（{@code GOV_SET_PORT_POLICY_APPLIED}）、业务/载荷拒绝 INFO（{@code
 * ..._REJECTED}，经 {@code logReason} 脱敏）；切片装配/状态一致性故障 ERROR（{@code
 * ..._CONTRACT_VIOLATION}）且不降级、原样抛出。
 *
 * <p>★ <b>输入来源</b>：handler 只走 {@code Command → ChangeSet → Revision}（铁律 2）；本类不碰任何瞬态注入面。
 */
public final class SetPortPolicyHandler implements CommandHandler, GmOnlyCommand {

  /** 命令类型（唯一拼写点：Shell 注册、catalog 与 GM 提交都从这里取/对齐）。 */
  public static final String TYPE = "gov.SetPortPolicy";

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
      GovPortPolicy policy = GovPayloads.portPolicy(payload);
      boolean existed = snapshot.state().portPolicies().containsKey(unitId);
      GovState next = snapshot.state().withPortPolicy(unitId, policy);
      GovChangeSet changeSet = GovChangeSet.between(snapshot.state(), next);
      logApplied(unitId, policy, existed, changeSet);
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
   * 契约故障的 fail-closed 出口：<b>抛新实例</b>（与 {@code SetAdministrationPlanHandler} / {@code SqliteStore}
   * 的门禁口径一致）， 原异常挂 cause 不丢。
   */
  private static IllegalStateException contractFailure(RuntimeException failure) {
    if (failure instanceof IllegalStateException illegalState) {
      return new IllegalStateException(illegalState.getMessage(), illegalState);
    }
    return new IllegalStateException(
        "gov 口岸政策命令状态组装/处理契约故障: " + failure.getClass().getSimpleName(), failure);
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
                () -> new IllegalStateException("state 里没有 unit 切片（装配故障：gov 口岸政策要判 GOV 单位）"));
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
      UnitId unitId, GovPortPolicy policy, boolean existed, GovChangeSet changeSet) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOV_SET_PORT_POLICY_APPLIED",
                GovLogSource.GOV_PORT,
                "unit",
                unitId.value(),
                "mode",
                changeSet.isEmpty() ? "noop" : (existed ? "replaced" : "created"),
                "commodityClasses",
                policy.commodityRules().size(),
                "currencyClasses",
                policy.currencyRules().size(),
                "definedClasses",
                policy.definedClassCount(),
                // ★ P-T1a：真有作用面的两类计数分开报 —— 设了限制的会让跨区候选被节流；
                //   设了税的在本批只落形状（P-T1b 才真收），日志里看得见"配置被记下了"。
                "restrictedClasses",
                policy.restrictedClassCount(),
                "taxedClasses",
                policy.taxedClassCount(),
                "changed",
                !changeSet.isEmpty()));
  }

  private static void logRejected(String unitForLog, IllegalArgumentException e) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOV_SET_PORT_POLICY_REJECTED",
                GovLogSource.GOV_PORT,
                "reason",
                GovernmentPayloadReason.of(e),
                "unit",
                unitForLog == null ? "-" : unitForLog));
  }

  private static void logContract(String stage, RuntimeException failure) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "GOV_SET_PORT_POLICY_CONTRACT_VIOLATION",
                GovLogSource.GOV_PORT,
                "stage",
                stage,
                "failure",
                failure.getClass().getSimpleName(),
                "reason",
                GovernmentPayloadReason.of(failure)));
  }

  /** 拒绝/故障理由的脱敏唯一拼写点（委托 {@link GovPayloads#logReason}，本类不另写截断规则）。 */
  private static final class GovernmentPayloadReason {

    private GovernmentPayloadReason() {}

    static String of(Throwable failure) {
      if (failure instanceof JsonProcessingException json) {
        return GovPayloads.logReason(json.getOriginalMessage());
      }
      return GovPayloads.logReason(
          failure == null || failure.getMessage() == null ? "unknown" : failure.getMessage());
    }
  }
}

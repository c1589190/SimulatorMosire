package io.mosire.simos.economy.spi;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
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
 * ★★ <b>{@code economy.SetGovServiceCommitment}（Z3a，GM-only）：写/改/清一条 GOV 行政岗位承诺</b> （{@link
 * LaborCommitmentKind#GOV_SERVICE}；spec §3/§4.2/§9/§10 C7/§19）。
 *
 * <p>★★ <b>载荷（冻结）</b>：
 *
 * <pre>{@code
 * {"govUnitId":"u-central",        // 必填非空白；家户 = hh-gov-<govUnitId>
 *  "householdId":"hh-unit-...",     // 必填；出劳动的家户（economy classes + Social 家户表都要有）
 *  "laborMilli":16000,              // 必填；≥ 0；0 = release（删该 (household, activity) 的 GOV_SERVICE 行）
 *  "activity":"unit-office@...",    // 可选；缺省按 operator=hh-gov + industry base kind=office 唯一解析
 *  "reason":"gov:..."}              // 必填非空白（审计；不进状态）
 * }</pre>
 *
 * <p>★★ <b>写语义</b>：{@code laborMilli > 0} ⇒ upsert 确定性 id {@code
 * gov-service:<activity>:<household>} （同量重放 = 空变更集 no-op）；{@code laborMilli = 0} ⇒ release 该 id 的
 * GOV_SERVICE 行（不存在 = 幂等 no-op）。 同 (household, activity) 已有 PRODUCTION 承诺 ⇒ upsert 具名冲突拒（不静默改写
 * kind）；写入后 Σ全部承诺 ≤ {@code HouseholdEconomy.laborMilli} 由 {@link EconomyGovServiceCommitments}
 * 预检（不给构造期 IAE 留业务语义）。
 *
 * <p>★ <b>唯一语义落点</b>：{@link EconomyGovServiceCommitments#project(EconomyData, SocialData,
 * String)}；本类只做 状态组装/日志/异常折返（照 Z1c {@code EconomyUpsertGovUnitHandler} 先例）。
 *
 * <p>★ <b>模块边界（§18 裁定 A 同源）</b>：economy enforcer 禁 unit ⇒ 这里查经济侧 GOV 登记信封 + classes + Social
 * 家户表；app 组合根/工具（Z3c）再做 unit + {@code GovernmentFormation} 预检。本命令 GM 裸 {@code simos.command.submit}
 * 路径只过经济侧信封（已裁定接受的边界）。★ <b>GM-only</b>：实现 {@link GmOnlyCommand} ⇒
 * 排除出令白名单/RegisterEffect/决策人目录；决策人版与审批链归 Z3c。★ 不实现 {@code CommandTargets}。
 *
 * <p>★ <b>日志（AGENTS §一.9）</b>：成功 INFO（{@code GOV_SERVICE_COMMITMENT_SET}）、业务/载荷拒绝 INFO（{@code
 * GOV_SERVICE_COMMITMENT_REJECTED}，理由经 {@code logReason} 脱敏）；切片装配/写出后跨表一致性故障 ERROR（{@code
 * GOV_SERVICE_COMMITMENT_CONTRACT_VIOLATION}）且不降级、抛新实例（保留 cause）；无 WARN 降级。来源 = {@link
 * EconomyLogSource#ECONOMY_COMMAND}。
 */
public final class EconomySetGovServiceCommitmentHandler implements CommandHandler, GmOnlyCommand {

  /** 命令类型（唯一拼写点：Shell 注册、catalog 提示、Z3c 窄工具都从这里取/对齐）。 */
  public static final String TYPE = "economy.SetGovServiceCommitment";

  private static final Logger LOG = EconomyLog.command();

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base;
    SocialData social;
    try {
      base = EconomySnapshots.of(state).data();
      social = requireSocial(state);
    } catch (RuntimeException e) {
      // ★ 装配故障不是载荷错：ERROR 不降级，抛新实例（不写 `throw e` —— SpotBugs
      //   THROWS_METHOD_THROWS_RUNTIMEEXCEPTION 只放行"抛新实例"，与 Z1a/Z1c 门禁口径一致）。
      logContract("state-assembly", e);
      throw contractFailure(e);
    }
    try {
      EconomyGovServiceCommitments.Projection projection =
          EconomyGovServiceCommitments.project(base, social, payloadJson);
      logApplied(projection);
      return new HandlerOutcome.Applied(projection.changeSet());
    } catch (IllegalStateException e) {
      // ★ 写出后的跨表契约故障/GOV_SERVICE 行坏 id：ERROR 不降级；抛新实例保留原 message/cause。
      logContract("project", e);
      throw contractFailure(e);
    } catch (IllegalArgumentException e) {
      // ★ 载荷形状 + 业务世界规则（GOV 未登记/非 office/家户不存在/PRODUCTION 冲突/Σ 越预算/0 人口）都折 INFO +
      //   Rejected。
      logRejected(e);
      return new HandlerOutcome.Rejected(e.getMessage());
    } catch (RuntimeException e) {
      logContract("handle", e);
      throw contractFailure(e);
    }
  }

  /** Social 切片装配（本 handler 判 GOV 家户与承诺家户是否在 Social 家户表）。 */
  private static SocialData requireSocial(SimulationState state) {
    Snapshot snapshot =
        state
            .module("social")
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "state 里没有 social 切片（装配故障：GOV_SERVICE 承诺要判 hh-gov 与官吏户）"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalStateException(
          "state 的 social 切片不是 SocialSnapshot: " + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }

  /** 成功 INFO：谁（gov/unit/household）、动作（created/updated/release/noop）、动作量、kind、是否落 revision。 */
  private static void logApplied(EconomyGovServiceCommitments.Projection projection) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOV_SERVICE_COMMITMENT_SET",
                EconomyLogSource.ECONOMY_COMMAND,
                "govUnit",
                projection.govUnitId(),
                "unit",
                projection.unitId().value(),
                "household",
                projection.household().value(),
                "commitment",
                projection.commitmentId().value(),
                "kind",
                LaborCommitmentKind.GOV_SERVICE.name(),
                "laborMilli",
                projection.laborMilli(),
                "action",
                projection.action(),
                "changed",
                !projection.noop()));
  }

  /** 拒绝 INFO（业务/载荷）：理由过脱敏，不进载荷明文。 */
  private static void logRejected(RuntimeException rejection) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOV_SERVICE_COMMITMENT_REJECTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "reason",
                EconomyCommandPayloads.logReason(rejection.getMessage())));
  }

  /** 契约/一致性故障 ERROR（事件名与来源固定；reason 过脱敏）。 */
  private static void logContract(String stage, RuntimeException failure) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "GOV_SERVICE_COMMITMENT_CONTRACT_VIOLATION",
                EconomyLogSource.ECONOMY_COMMAND,
                "stage",
                stage,
                "failure",
                failure.getClass().getSimpleName(),
                "reason",
                EconomyCommandPayloads.logReason(failure.getMessage())));
  }

  /** 契约故障 fail-closed 出口：抛<b>新实例</b>（不写 {@code throw e}），原 message/cause 不丢。 */
  private static IllegalStateException contractFailure(RuntimeException failure) {
    if (failure instanceof IllegalStateException illegalState) {
      return new IllegalStateException(illegalState.getMessage(), illegalState);
    }
    return new IllegalStateException(
        "economy.SetGovServiceCommitment 状态组装/处理契约故障: " + failure.getClass().getSimpleName(),
        failure);
  }
}

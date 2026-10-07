package io.mosire.simos.economy.spi;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
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
 * ★★ <b>{@code economy.UpsertGovUnit}（Z1c，GM-only）：为 GOV 单位创建/补齐行政服务生产 unit</b>（spec §4.2/§11）。
 *
 * <p>★★ <b>载荷（冻结）</b>：
 *
 * <pre>{@code
 * {"govUnitId":"u-central",          // 必填；家户 = hh-gov-<govUnitId>
 *  "industryId":"office@0_0",        // 必填；必须已存在且 base kind = office（office / office_v2…）
 *  "assets":{"TOOL":1},              // 必填；至少覆盖 recipe capacityPerUnit 的 1 单位规模
 *  "modeKey":"gov_service",          // 可选；缺省 gov_service（稳定键）
 *  "reason":"gm:..."}                // 必填非空白（审计；不进状态）
 * }</pre>
 *
 * <p>★★ <b>一次写三张既有表</b>（{@link EconomyGovUnitUpserts} 是唯一语义落点；本 handler 与 GM 工具共用同一纯函数）： {@code
 * units} 新增 {@code ProductionProcess}（operator={@code HOUSEHOLD:hh-gov-<id>}，unitId={@code
 * ProductionUnitId.idOf(industryId, operator)}，progress=0、cycle 空）；{@code assetShares} 按 recipe
 * {@code capacityPerUnit} 逐 kind 建/补 {@code OWNED} 份额（owner=operator）；{@code relations} 落 V1 空规则
 * {@code ProductionRules}（{@code residualOwner=operator} 的既有等价路径）。{@code operatorConditions} /
 * {@code ProductionEnterprise} 本区不写（见 {@link EconomyGovUnitUpserts} 类注）。
 *
 * <p>★★ <b>幂等 / 拒因</b>：同一载荷重放（unit/关系逐值一致、逐 kind 可用资产 = 载荷量）⇒ 空变更集、不落 revision； 已存在
 * unit/关系/份额与载荷字段冲突（modeKey、资产超量、既有工资规则…）⇒ 具名 {@code Rejected}，绝不静默覆盖。
 *
 * <p>★ <b>GOV 信封与模块边界（控制方 2026-10-23 裁定 A）</b>：economy enforcer 禁依赖 simos-unit ⇒ 本 handler 编译期看不见
 * {@code Unit}/{@code GovernmentFormation}。这里查经济侧登记证据（{@code governments[gov-unit-id].treasury ==
 * hh-gov actor}）+ {@code classes} 的 HouseholdEconomy 行 + Social 家户表；app 的 GM 工具在 preview/apply
 * 两条路径另做 unit 切片预检（unit 存在 + {@code module()} 是 GovernmentFormation）。GM 裸 {@code
 * simos.command.submit} 绕过 unit 切片检查是已裁定接受的边界 （详见 Z1c 台账）。
 *
 * <p>★ <b>GM-only</b>：实现 {@link GmOnlyCommand} ⇒ 排除出令白名单 / {@code RegisterEffect} / 决策人目录；GM 的
 * {@code simos.command.submit} 与窄工具 {@code simos.economy.upsertGovUnit} 照常可用。★ <b>不实现 {@code
 * CommandTargets}</b>（Z3 接决策人工具/审批链时再按需打开）。
 *
 * <p>★ <b>日志（AGENTS §一.9）</b>：成功 INFO（{@code GOV_UNIT_UPSERTED}）、业务/载荷拒绝 INFO（{@code
 * GOV_UNIT_UPSERT_REJECTED}，理由经 {@code logReason} 脱敏）；切片装配/写出后跨表一致性故障 ERROR（{@code
 * GOV_UNIT_UPSERT_CONTRACT_VIOLATION}）且不降级、抛新实例（保留 cause）；无 WARN 降级。事件来源 = {@link
 * EconomyLogSource#ECONOMY_COMMAND}。
 */
public final class EconomyUpsertGovUnitHandler implements CommandHandler, GmOnlyCommand {

  /** 命令类型（唯一拼写点：Shell 注册、GM 工具、catalog 提示都从这里取/对齐）。 */
  public static final String TYPE = "economy.UpsertGovUnit";

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
      //   THROWS_METHOD_THROWS_RUNTIMEEXCEPTION 只放行"抛新实例"，与 Z1a handler / SqliteStore 门禁口径一致）。
      logContract("state-assembly", e);
      throw contractFailure(e);
    }
    try {
      EconomyGovUnitUpserts.Projection projection =
          EconomyGovUnitUpserts.project(base, social, payloadJson);
      logApplied(projection);
      return new HandlerOutcome.Applied(projection.changeSet());
    } catch (IllegalStateException e) {
      // ★ 写出后的跨表一致性契约故障：ERROR 不降级；抛新实例保留原 message/cause。
      logContract("project", e);
      throw contractFailure(e);
    } catch (IllegalArgumentException e) {
      // ★ 载荷形状/坏 id/业务世界规则（GOV 未登记、非 office、assets 不足/超出、字段冲突）都折 INFO + Rejected。
      logRejected(e);
      return new HandlerOutcome.Rejected(e.getMessage());
    } catch (RuntimeException e) {
      logContract("handle", e);
      throw contractFailure(e);
    }
  }

  /** Social 切片装配（本 handler 判 GOV 家户是否在 Social 家户表；缺切片/类型不符 = 装配故障，ERROR 不降级）。 */
  private static SocialData requireSocial(SimulationState state) {
    Snapshot snapshot =
        state
            .module("social")
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "state 里没有 social 切片（装配故障：GOV 生产 unit 要判 hh-gov 家户）"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalStateException(
          "state 的 social 切片不是 SocialSnapshot: " + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }

  /** 成功 INFO：谁（govUnit/operator/unitId）、哪条 office 产业、modeKey、三表各写了什么、变更集是否为空。 */
  private static void logApplied(EconomyGovUnitUpserts.Projection projection) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOV_UNIT_UPSERTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "govUnit",
                projection.govUnitId(),
                "unit",
                projection.unitId().value(),
                "operator",
                projection.operator().kind() + "|" + projection.operator().id(),
                "industry",
                projection.industryId().value(),
                "modeKey",
                projection.modeKey(),
                "unitCreated",
                projection.unitCreated(),
                "relationCreated",
                projection.relationCreated(),
                "sharesAdded",
                projection.sharesCreated().size(),
                "changed",
                !projection.noop()));
  }

  /** 拒绝 INFO（业务/载荷）：理由过脱敏，不进载荷明文。 */
  private static void logRejected(RuntimeException rejection) {
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "GOV_UNIT_UPSERT_REJECTED",
                EconomyLogSource.ECONOMY_COMMAND,
                "reason",
                EconomyCommandPayloads.logReason(rejection.getMessage())));
  }

  /** 契约/一致性故障 ERROR（事件名与来源固定；reason 过脱敏）。 */
  private static void logContract(String stage, RuntimeException failure) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "GOV_UNIT_UPSERT_CONTRACT_VIOLATION",
                EconomyLogSource.ECONOMY_COMMAND,
                "stage",
                stage,
                "failure",
                failure.getClass().getSimpleName(),
                "reason",
                EconomyCommandPayloads.logReason(failure.getMessage())));
  }

  /** 契约故障的 fail-closed 出口：抛<b>新实例</b>（不写 {@code throw e}，SpotBugs 门禁口径），原 message/cause 不丢。 */
  private static IllegalStateException contractFailure(RuntimeException failure) {
    if (failure instanceof IllegalStateException illegalState) {
      return new IllegalStateException(illegalState.getMessage(), illegalState);
    }
    return new IllegalStateException(
        "economy.UpsertGovUnit 状态组装/处理契约故障: " + failure.getClass().getSimpleName(), failure);
  }
}

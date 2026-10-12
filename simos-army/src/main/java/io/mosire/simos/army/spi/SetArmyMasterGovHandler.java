package io.mosire.simos.army.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.army.Army;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmyLog;
import io.mosire.simos.army.ArmyLogSource;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * {@code army.SetArmyMasterGov} 命令的处理器：给已存在的 Army **改派 / 解除认领 GOV**（阶段 12 的后续赋值操作缺口； 2026-10-12 批次
 * 3d-1 自 {@code simos-sd} 整条迁入 {@code simos-army}）。
 *
 * <pre>{@code
 * {"armyId":"a1","masterGovUnitId":"g1"}   // 改派
 * {"armyId":"a1"}                          // 解除认领（键缺席）
 * {"armyId":"a1","masterGovUnitId":null}   // 解除认领（显式 null）
 * {"armyId":"a1","masterGovUnitId":""}     // 解除认领（空串）
 * }</pre>
 *
 * <p>★★ <b>本批的搬迁</b>：命令类型 {@code sd.SetArmyMasterGov} → {@code army.SetArmyMasterGov}（同 {@link
 * CreateArmyHandler} 的同一批、同一条理由）。handler 只换包与命名空间前缀，<b>载荷、判据、拒因文案逐字未改</b>。
 *
 * <p>★ <b>载荷语义</b>：{@code masterGovUnitId} 缺席 / {@code null} / 空串（含空白串）= 解除认领；给了非空白值必须存在、 且带 {@link
 * GovernmentFormation}（与 {@code army.CreateArmy} / {@code unit.SetArmyFormation} 同口径，认主子只认
 * GOV）。armyId 不存在 ⇒ 具名拒。
 *
 * <p>★ <b>只改一个字段</b>：成功时只换 {@link Army#masterGovUnitId()}，{@code id}/{@code rootUnit}/{@code name}
 * 原样保留；变更经 {@link ArmyChangeSet#between} 派生（铁律 2 + 5）。本命令<b>不碰 unit 切片</b>——{@code
 * ArmyFormation.masterGov} 的双边同步是 {@code simos.army.assignGov} 组合工具的职责（本命令只做 army 侧的单一事实落点， 供 GM
 * 直接提交 / 组合工具复用）。
 *
 * <p>★ <b>GM-only</b>：不打 {@code DmUsableCommand} ⇒ handler 照常注册、GM 的 {@code simos.command.submit}
 * 照常可用，但排除出决策令白名单 / 注册效果 / 决策人目录（与迁移前逐字同一条边界；迁移前它靠 {@code sd.} 前缀被排除）。
 *
 * <p>★ <b>日志面换家（如实记）</b>：迁入前用 {@code SdLog.nation()} / {@code SdLogSource.SD_NATION}，现改走 {@link
 * ArmyLog#armies()} / {@link ArmyLogSource#ARMY_REGISTRY}；事件名 {@code
 * SD_SET_ARMY_MASTER_GOV_APPLIED} → {@code ARMY_SET_MASTER_GOV_APPLIED}，字段可读同义。
 */
public final class SetArmyMasterGovHandler implements CommandHandler {

  private static final Logger LOG = ArmyLog.armies();

  /** 命令类型（信封上的 {@code type}）。 */
  public static final String TYPE = "army.SetArmyMasterGov";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ArmySnapshot snapshot = ArmySnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    String armyForLog = null;
    String masterForLog = null;
    try {
      JsonNode payload = ArmyPayloads.parse(payloadJson);
      ArmyId id = ArmyId.parse(ArmyPayloads.requireText(payload, "armyId"));
      armyForLog = id.value();
      // ★ 与迁入前逐字同口径：先取"字符串或 null"（空白串是**合法**的一份"解除认领"载荷），再 filter 掉空白。
      Optional<UnitId> masterGovUnitId =
          ArmyPayloads.optionalTextAllowingBlank(payload, "masterGovUnitId")
              .filter(text -> !text.isBlank())
              .map(UnitId::parse);
      masterForLog = masterGovUnitId.map(UnitId::value).orElse(null);
      Army existing = snapshot.data().armies().get(id);
      if (existing == null) {
        return rejected("军队不存在: " + id, "army", armyForLog);
      }
      if (masterGovUnitId.isPresent()) {
        Unit masterGov = ArmySnapshots.units(state).units().get(masterGovUnitId.get());
        if (masterGov == null) {
          return rejected(
              "masterGovUnitId 不存在: " + masterGovUnitId.get(),
              "army",
              armyForLog,
              "masterGov",
              masterForLog);
        }
        if (!(masterGov.module().orElse(null) instanceof GovernmentFormation)) {
          return rejected(
              "masterGovUnitId 不是 GOV 单位: " + masterGovUnitId.get(),
              "army",
              armyForLog,
              "masterGov",
              masterForLog);
        }
      }
      ArmyData next =
          snapshot
              .data()
              .withArmy(new Army(id, masterGovUnitId, existing.rootUnit(), existing.name()));
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ARMY_SET_MASTER_GOV_APPLIED",
                  ArmyLogSource.ARMY_REGISTRY,
                  "army",
                  id.value(),
                  "fromMasterGov",
                  existing.masterGovUnitId().map(UnitId::value).orElse("-"),
                  "toMasterGov",
                  masterGovUnitId.map(UnitId::value).orElse("-"),
                  "armies",
                  next.armies().size()));
      return new HandlerOutcome.Applied(ArmyChangeSet.between(snapshot.data(), next));
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage(), "army", armyForLog, "masterGov", masterForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * ArmyPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    ArmyPayloads.logRejected(
        LOG, ArmyLogSource.ARMY_REGISTRY, "ARMY_SET_MASTER_GOV_REJECTED", reason, idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}

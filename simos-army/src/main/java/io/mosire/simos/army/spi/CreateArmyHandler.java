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
 * {@code army.CreateArmy} 命令的处理器（阶段 12：Army 去 {@code NationId}、改认 {@code
 * masterGovUnitId}；2026-10-12 批次 3d-1 自 {@code simos-sd} 整条迁入 {@code simos-army}）。
 *
 * <pre>{@code
 * {"armyId":"a1","masterGovUnitId":"g1","rootUnitId":"u-1","name":"第一军"}
 * }</pre>
 *
 * <p>★★ <b>本批的搬迁（逐字照抄 3c 搬外交四条的形制）</b>：命令类型 {@code sd.CreateArmy} → {@code army.CreateArmy}（类型前缀决定
 * 变更集施加到哪个命名空间的 codec ⇒ 改名是硬约束）；handler 与它写的那张表（{@code armies}）、以及值类型（{@link Army} / {@link
 * ArmyId}）<b>一起</b>搬进 {@code simos-army}。用户原话：「<b>战斗模块，包括阶段之类的，归 Army 模块管</b>」＋「<b>sd
 * 整个弃用！</b>」；军队身份的用户口径：「<b>单个Army单位的本质不是被Army模块打上自定义数据集的Unit吗</b>」。
 *
 * <p>★ <b>载荷语义</b>：{@code masterGovUnitId} 可缺省/null（未认主子）；给了必须存在、且带 {@link
 * GovernmentFormation}（认主子只认 GOV，与 {@code unit.SetArmyFormation} 同口径）。{@code rootUnitId} 是否存在经
 * {@link ArmySnapshots#unitExists} 只读 unit 切片（铁律 3）。
 *
 * <p>★ <b>旧 {@code nationId} 键具名拒并指路</b>（阶段 12 二选一，选拒绝不选静默忽略）：字段已从 {@link Army}
 * 删除，继续兼容读会让人以为"军队还记着国家"。拒因直接指向 {@code masterGovUnitId} 与 {@code unit.SetArmyFormation}——既
 * fail-closed，又不把 {@code nationId} 硬映射成 GOV。
 *
 * <p>★★ <b>不迁入 {@code DmUsableCommand}</b>（反默认落地）：本命令**仅 GM** —— 迁入前它也不在 DM 两面（{@code sd.}
 * 前缀在迁移前就被排除，见台账 §10.1 的"142 = 66 + 28 + 48"精确三分）；本批一个字不改这条边界。
 *
 * <p>★ <b>日志面换家（如实记）</b>：迁入前用 {@code SdLog.nation()} / {@code SdLogSource.SD_NATION}（{@code
 * sd-nation}），现改走 {@link ArmyLog#armies()} / {@link ArmyLogSource#ARMY_REGISTRY}（{@code
 * army-registry}）—— 军队身份不再住 sd，来源表随之换家。事件名与字段保持可读同义（{@code SD_ARMY_CREATED} → {@code
 * ARMY_CREATED}）。
 */
public final class CreateArmyHandler implements CommandHandler {

  private static final Logger LOG = ArmyLog.armies();

  /** 命令类型（信封上的 {@code type}）。 */
  public static final String TYPE = "army.CreateArmy";

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
    String rootForLog = null;
    try {
      JsonNode payload = ArmyPayloads.parse(payloadJson);
      if (payload.has("nationId")) {
        return rejected(
            "army.CreateArmy 不再接受 nationId：Army 已去 NationId，改认 masterGovUnitId"
                + "（可选，缺省 = 未认主子）。国家归属请用 gov.CreateNation/Affiliation.Nation；"
                + "认领 GOV 请用 unit.SetArmyFormation。",
            "army",
            "-");
      }
      ArmyId id = ArmyId.parse(ArmyPayloads.requireText(payload, "armyId"));
      armyForLog = id.value();
      Optional<UnitId> masterGovUnitId =
          ArmyPayloads.optionalTextAllowingBlank(payload, "masterGovUnitId").map(UnitId::parse);
      masterForLog = masterGovUnitId.map(UnitId::value).orElse(null);
      UnitId rootUnit = UnitId.parse(ArmyPayloads.requireText(payload, "rootUnitId"));
      rootForLog = rootUnit.value();
      String name = ArmyPayloads.requireText(payload, "name");
      if (snapshot.data().armies().containsKey(id)) {
        return rejected("军队已存在: " + id, "army", armyForLog);
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
      if (!ArmySnapshots.unitExists(state, rootUnit)) {
        return rejected("rootUnitId 不存在: " + rootUnit, "army", armyForLog, "rootUnit", rootForLog);
      }
      ArmyData next = snapshot.data().withArmy(new Army(id, masterGovUnitId, rootUnit, name));
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ARMY_CREATED",
                  ArmyLogSource.ARMY_REGISTRY,
                  "id",
                  id.value(),
                  "masterGov",
                  masterGovUnitId.map(UnitId::value).orElse("-"),
                  "rootUnit",
                  rootUnit.value(),
                  "name",
                  name,
                  "armies",
                  next.armies().size()));
      return new HandlerOutcome.Applied(ArmyChangeSet.between(snapshot.data(), next));
    } catch (IllegalArgumentException e) {
      return rejected(
          e.getMessage(), "army", armyForLog, "masterGov", masterForLog, "rootUnit", rootForLog);
    }
  }

  /**
   * 具名拒绝的唯一发射点（用户 2026-10-23：被拒绝一律 INFO，必须明显记录）：{@code reason} + 关键 id（取不到 {@code -}）。 理由先过 {@link
   * ArmyPayloads#logReason}，载荷原文不进日志；返回 {@code Rejected} 保持原有控制流。
   */
  private static HandlerOutcome rejected(String reason, Object... idKeyValues) {
    ArmyPayloads.logRejected(
        LOG, ArmyLogSource.ARMY_REGISTRY, "ARMY_CREATE_REJECTED", reason, idKeyValues);
    return new HandlerOutcome.Rejected(reason);
  }
}

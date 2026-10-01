package io.mosire.simos.army.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code army.RecordCombat} 命令的处理器（阶段 D1 / 用户设计 D-012，2026-10-02）：写一条单 tick 单场交战记录。
 *
 * <pre>{@code
 * {"id":"c-1","tick":12,"hex":{"q":3,"r":4},"participants":["u-1","u-2"],
 *  "text":"……自然语言过程与结局……","losses":{"u-1:人员":30,"u-2:步枪":5}}
 * }</pre>
 *
 * <p>★★ <b>标 {@link GmOnlyCommand}</b>（用户 D-012 的落点）：裁定战果是 GM 的活，**决策人不得凭空写交战记录**——标了它，本命令仍可由 GM 的
 * {@code simos.command.submit} 直接提交（handler 照常注册、照进 catalog），但排除出令白名单 / {@code sd.RegisterEffect} /
 * 决策人目录三条路径。
 *
 * <p>★ <b>tick 口径</b>（与 {@code sd.PutInfo} 同族）：缺省 = **世界当前 tick**；显式给 ⇒ 用它；**未来 tick
 * 拒**，过去合法（补记历史）。 命令的**效果**仍落在当下（世界不随时间字段改动）。
 *
 * <p>★ <b>同 id 已存在 ⇒ 具名拒</b>（最保守选择）：交战记录是**不可变历史**，不像 {@code ActorData.withActor} 那样后写覆盖——覆盖会悄悄
 * 改写已发生的事。要更正请用新 id 记一条更正的记录（清理/修订命令另批）。
 *
 * <p>★ <b>参与者不查存在性</b>：见 {@link CombatRecord} 的注（记录写的是历史，单位可能已被解散）。★ 损失量非负由记录构造期判。
 */
public final class RecordCombatHandler implements CommandHandler, GmOnlyCommand {

  /** 命令类型（信封上的 {@code type}）。 */
  public static final String TYPE = "army.RecordCombat";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ArmySnapshot snapshot = ArmySnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = ArmyPayloads.parse(payloadJson);
      CombatRecordId id = CombatRecordId.parse(ArmyPayloads.requireText(payload, "id"));
      if (snapshot.data().combats().containsKey(id)) {
        return new HandlerOutcome.Rejected("交战记录 id 已存在（交战记录是不可变历史，不覆盖）: " + id.value());
      }
      long worldTick = state.meta().timestamp().tick();
      // ★ tick 缺省 = 世界当前 tick（与 sd.PutInfo 同族口径）；显式给 ⇒ 用它，但不得记在未来。
      long tick = ArmyPayloads.optionalLong(payload, "tick").orElse(worldTick);
      if (tick > worldTick) {
        return new HandlerOutcome.Rejected(
            "交战记录不得记在未来：载荷 tick " + tick + " > 世界 tick " + worldTick);
      }
      HexCoord hex = ArmyPayloads.requireHex(payload, "hex");
      List<UnitId> participants = ArmyPayloads.requireUnitIdList(payload, "participants");
      String text = ArmyPayloads.requireText(payload, "text");
      Map<String, Long> losses = ArmyPayloads.optionalLosses(payload, "losses");
      CombatRecord record = new CombatRecord(id, tick, hex, participants, text, losses);
      ArmyData next = snapshot.data().withCombat(record);
      return new HandlerOutcome.Applied(ArmyChangeSet.between(snapshot.data(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

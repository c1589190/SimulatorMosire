package io.mosire.simos.army.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyLog;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.CombatStageId;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * {@code army.RecordCombat} 命令的处理器（阶段 D1 落地、阶段 D4 升级 / 用户设计 D-009 补裁 + D-010 + D-012，2026-10-02）：
 * <b>写一条单 tick 单场交战记录（含初始阶段）</b>。
 *
 * <pre>{@code
 * {"id":"c-1","kind":"野战","tick":12,"hex":{"q":3,"r":4},"participants":["u-1","u-2"],
 *  "text":"……自然语言过程……",
 *  "initialStage?":{"id":"s1","name":"接触","participants":["u-1","u-2"],"text":"……",
 *                    "outcomes":[{"id":"o1","label":"胜","weight":60,
 *                                 "losses":[{"unit":"u-1","manpower":[{"type":"士兵","amount":-30}],
 *                                            "equipment":[{"type":"步枪","amount":-5}]}]}]}}
 * }</pre>
 *
 * <p>★★ <b>{@code kind} = 自定义交战状态（自由文本）</b>：D-009 明文"轰城也是特殊交战状态、不另开攻城命令"，故 {@code kind="轰城"} 与
 * {@code kind="野战"} 走的是**同一条命令**。
 *
 * <p>★★ <b>{@code initialStage} 缺省口径</b>：不给 ⇒ handler 合成初始阶段 {@code id="start"}、{@code
 * name="初始阶段"}、{@code participants} = 记录级 participants、{@code text} = 记录级 text、{@code outcomes} =
 * 空表（概率表随后用 {@code army.AppendCombatStage} 追加）。给了 ⇒ 用给的（其中 {@code participants} 仍可省略、缺省沿用记录级
 * participants；阶段载荷不得携带 {@code selectedOutcomeId}/{@code rollSeed}）。
 *
 * <p>★★ <b>标 {@link GmOnlyCommand}</b>（用户 D-012 的落点）：裁定战果是 GM 的活，**决策人不得凭空写交战记录**——标了它，本命令仍可由 GM 的
 * {@code simos.command.submit} 直接提交（handler 照常注册、照进 catalog），但排除出令白名单 / {@code sd.RegisterEffect} /
 * 决策人目录三条路径。
 *
 * <p>★ <b>tick 口径</b>（与 {@code sd.PutInfo} 同族）：缺省 = **世界当前 tick**；显式给 ⇒ 用它；**未来 tick
 * 拒**，过去合法（补记历史）。 命令的**效果**仍落在当下（世界不随时间字段改动）。
 *
 * <p>★ <b>同 id 已存在 ⇒ 具名拒</b>：记录 id 是**一次性身份**；记录内容随后的演进走 {@code army.AppendCombatStage} / {@code
 * army.ResolveCombatStage}（同 id 整条替换、落新 revision，旧 revision 历史不丢）。
 *
 * <p>★ <b>参与者不查存在性</b>：见 {@link CombatRecord} 的注（记录写的是历史，单位可能已被解散）。
 *
 * <p>★ <b>不背旧档</b>（D-011/R4）：旧 D1 载荷的 {@code losses:{自然语义键:数量}} 字段**显式拒**，不留兼容层、也不静默丢。
 */
public final class RecordCombatHandler implements CommandHandler, GmOnlyCommand {

  private static final Logger LOG = ArmyLog.combat();

  /** 命令类型（信封上的 {@code type}）。 */
  public static final String TYPE = "army.RecordCombat";

  /** 未显式给初始阶段时的阶段 id（固定口径；追加阶段不得复用它——记录构造期判重）。 */
  public static final String INITIAL_STAGE_ID = "start";

  /** 未显式给初始阶段时的阶段名（固定口径）。 */
  public static final String INITIAL_STAGE_NAME = "初始阶段";

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
      if (payload.has("losses")) {
        // ★ D-011/R4：旧 D1 载荷形状显式拒（不静默丢、不留双轨）。
        return new HandlerOutcome.Rejected(
            "旧字段 losses 已删除（D-011/R4 不背兼容）：损失写进阶段 outcome 的 losses[...]"
                + "（CombatUnitLoss：unit + 有符号 manpower/equipment 增量）");
      }
      CombatRecordId id = CombatRecordId.parse(ArmyPayloads.requireText(payload, "id"));
      if (snapshot.data().combats().containsKey(id)) {
        return new HandlerOutcome.Rejected("交战记录 id 已存在（记录 id 是一次性身份，不覆盖）: " + id.value());
      }
      long worldTick = state.meta().timestamp().tick();
      // ★ tick 缺省 = 世界当前 tick（与 sd.PutInfo 同族口径）；显式给 ⇒ 用它，但不得记在未来。
      long tick = ArmyPayloads.optionalLong(payload, "tick").orElse(worldTick);
      if (tick > worldTick) {
        return new HandlerOutcome.Rejected(
            "交战记录不得记在未来：载荷 tick " + tick + " > 世界 tick " + worldTick);
      }
      String kind = ArmyPayloads.requireText(payload, "kind");
      HexCoord hex = ArmyPayloads.requireHex(payload, "hex");
      List<UnitId> participants = ArmyPayloads.requireUnitIdList(payload, "participants");
      String text = ArmyPayloads.requireText(payload, "text");
      CombatStage initialStage = ArmyPayloads.optionalStage(payload, "initialStage", participants);
      if (initialStage == null) {
        initialStage =
            new CombatStage(
                new CombatStageId(INITIAL_STAGE_ID),
                INITIAL_STAGE_NAME,
                participants,
                text,
                List.of(),
                Optional.empty(),
                Optional.empty());
      }
      CombatRecord record =
          new CombatRecord(id, kind, tick, hex, participants, text, List.of(initialStage));
      ArmyData next = snapshot.data().withCombat(record);
      LOG.info(
          "event=ARMY_COMBAT_RECORDED id={} kind={} tick={} hex={} participants={} stages={}",
          id.value(),
          kind,
          tick,
          hex,
          participants.size(),
          record.stages().size());
      return new HandlerOutcome.Applied(ArmyChangeSet.between(snapshot.data(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

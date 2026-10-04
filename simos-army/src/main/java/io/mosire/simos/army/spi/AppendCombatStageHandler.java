package io.mosire.simos.army.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmyLog;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatRecord;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.CombatStage;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * {@code army.AppendCombatStage} 命令的处理器（阶段 D4 / 用户设计 D-009 补裁 +
 * D-010，2026-10-02）：**给已存在的交战记录追加一个阶段**。
 *
 * <pre>{@code
 * {"combatId":"c-1","stage":{"id":"s2","name":"续战","participants":["u-1"],
 *  "text":"……自然语言过程……",
 *  "outcomes":[{"id":"o2a","label":"击溃","weight":70},
 *              {"id":"o2b","label":"僵持","weight":30,
 *               "losses":[{"unit":"u-1","manpower":[{"type":"士兵","amount":-10}],"equipment":[]}]}]}}
 * }</pre>
 *
 * <p>★★ <b>这是"阶段可追加"口径的落地</b>（{@link CombatRecord} 的不可变注释）：同 id 的记录被整条替换、落**新 revision**；旧 revision
 * 里只有原阶段。 阶段 id 已存在 ⇒ 具名拒（阶段按 id 不可变，不覆盖）。
 *
 * <p>★ <b>阶段字段缺省</b>：{@code participants} 省略 ⇒ 沿用记录级 participants；{@code outcomes} 省略 ⇒ 空表；{@code
 * selectedOutcomeId}/{@code rollSeed} 不得出现在载荷里（判定只走 {@code army.ResolveCombatStage}）。
 *
 * <p>★ <b>标 {@link GmOnlyCommand}</b>：与 {@link RecordCombatHandler} 同一条边界——战果编排是 GM
 * 的活，决策人不得凭空追加交战阶段。
 */
public final class AppendCombatStageHandler implements CommandHandler, GmOnlyCommand {

  private static final Logger LOG = ArmyLog.combat();

  /** 命令类型（信封上的 {@code type}）。 */
  public static final String TYPE = "army.AppendCombatStage";

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
      CombatRecordId combatId = CombatRecordId.parse(ArmyPayloads.requireText(payload, "combatId"));
      CombatRecord record = snapshot.data().combats().get(combatId);
      if (record == null) {
        return new HandlerOutcome.Rejected("交战记录不存在: " + combatId.value());
      }
      CombatStage stage = ArmyPayloads.requireStage(payload, "stage", record.participants());
      CombatRecord next = record.withAppendedStage(stage);
      ArmyData nextData = snapshot.data().withCombat(next);
      LOG.info(
          "event=ARMY_COMBAT_STAGE_APPENDED combat={} stage={} outcomes={} stages={}",
          combatId.value(),
          stage.id().value(),
          stage.outcomes().size(),
          next.stages().size());
      return new HandlerOutcome.Applied(ArmyChangeSet.between(snapshot.data(), nextData));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

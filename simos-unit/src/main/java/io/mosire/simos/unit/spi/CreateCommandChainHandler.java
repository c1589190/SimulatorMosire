package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.CommandChain;
import io.mosire.simos.unit.CommandChainId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitLog;
import io.mosire.simos.unit.UnitLogSource;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * {@code unit.CreateCommandChain} 命令的处理器（T5 / spec §五.2）：{@code chainId, name, commander,
 * members[]}。
 *
 * <p>★ 四个字段**全必填**（建链没有"部分指定"的语义）：`members` 走 {@link UnitPayloads#requireTextArray} （形状与类型），成员 id 走
 * {@link UnitId#parse}。
 *
 * <p>★ 三条拒绝理由分属两层、**消息刻意不同**（"是哪一层拒的"必须判得出来）：payload 层管形状（字段缺失/类型不对/ 元素不是非空字符串）；域层管语义——链 id 已在 /
 * 成员或 commander 不存在（`…不存在: u-ghost`）；而 `commander ∉ members` 由 {@link CommandChain}
 * **构造期**给出（`commander 必须是 members 之一: u-x`）， {@code UnitOperations.createChain}
 * **不重复实现**（它收到的只能是合法值对象）——三处都有各自的拒绝用例。
 *
 * <p>★ 链**无时刻分量**：本命令不追加任何段，故不读 {@code state.meta().timestamp()}（与 `UpdateCommandChain` 同制， 与带时刻的
 * `SplitFormation`/`MergeFormation` 一族相反）。多属**不是**拒绝理由（spec §一.2 / P11）。
 */
public final class CreateCommandChainHandler implements CommandHandler {

  @Override
  public String type() {
    return "unit.CreateCommandChain";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    String chainForLog = null;
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      CommandChainId chainId = CommandChainId.parse(UnitPayloads.requireText(payload, "chainId"));
      chainForLog = chainId.value();
      String name = UnitPayloads.requireText(payload, "name");
      UnitId commander = UnitId.parse(UnitPayloads.requireText(payload, "commander"));
      Set<UnitId> members = new LinkedHashSet<>();
      for (String text : UnitPayloads.requireTextArray(payload, "members")) {
        members.add(UnitId.parse(text));
      }
      CommandChain chain = new CommandChain(chainId, name, commander, members);
      UnitState next = UnitOperations.createChain(snapshot.state(), chain);
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_CREATE_COMMAND_CHAIN_APPLIED",
                  UnitLogSource.UNIT_COMMAND,
                  "chain",
                  chainId.value(),
                  "commander",
                  commander.value(),
                  "members",
                  members.size()));
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_CREATE_COMMAND_CHAIN_REJECTED",
                  UnitLogSource.UNIT_COMMAND,
                  "reason",
                  UnitPayloads.logReason(e.getMessage()),
                  "chain",
                  chainForLog == null ? "-" : chainForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

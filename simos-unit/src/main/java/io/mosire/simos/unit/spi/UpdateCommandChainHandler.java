package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit.UpdateCommandChain} 命令的处理器（T5 / spec §五.2）：{@code chainId, name?, commander?,
 * members?}。
 *
 * <p>★ **只有 {@code chainId} 必填**：三个可选字段**缺失 / null ⇒ 该字段不动**（**不是清空**）——`name` 走 {@link
 * UnitPayloads#optionalText}、`commander` 走 {@link UnitPayloads#optionalId}、`members` 走 {@link
 * UnitPayloads#optionalTextArray}（T5 新增的那一个）。三缺省全给等于**空转**（合法：返回零变更的 Applied）。
 *
 * <p>★ **有效值的合法性**由 {@code UnitOperations.updateChain} 一处判：新 commander 必须落在**有效 members** 内
 * （`members` 给了就是新的那组、没给就是链上原有的那组），新成员必须都能解析到单位；形状/类型仍归本类前面那些助手。 与 `CreateCommandChainHandler`
 * 同制：域层与载荷层**引用单位失败时的消息刻意不同**，好判是哪一层拒的。
 *
 * <p>★ 链**无时刻分量**：不追加段、不读 {@code state.meta().timestamp()}。
 */
public final class UpdateCommandChainHandler implements CommandHandler {

  @Override
  public String type() {
    return "unit.UpdateCommandChain";
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
      Optional<String> name = UnitPayloads.optionalText(payload, "name");
      Optional<UnitId> commander = UnitPayloads.optionalId(payload, "commander");
      Optional<List<UnitId>> members = Optional.empty();
      Optional<List<String>> rawMembers = UnitPayloads.optionalTextArray(payload, "members");
      if (rawMembers.isPresent()) {
        List<UnitId> parsed = new ArrayList<>();
        for (String text : rawMembers.get()) {
          parsed.add(UnitId.parse(text));
        }
        members = Optional.of(parsed);
      }
      UnitState next =
          UnitOperations.updateChain(snapshot.state(), chainId, name, commander, members);
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_UPDATE_COMMAND_CHAIN_APPLIED",
                  UnitLogSource.UNIT_COMMAND,
                  "chain",
                  chainId.value(),
                  "commander",
                  commander.map(UnitId::value).orElse("-"),
                  "members",
                  members.map(List::size).orElse(0)));
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      EventLog.channel(UnitLog.command())
          .info(
              LogEvent.of(
                  "UNIT_UPDATE_COMMAND_CHAIN_REJECTED",
                  UnitLogSource.UNIT_COMMAND,
                  "reason",
                  UnitPayloads.logReason(e.getMessage()),
                  "chain",
                  chainForLog == null ? "-" : chainForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

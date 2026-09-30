package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit.SetArmyFormation} 命令的处理器（阶段 10a，控制方修订：编制字段在 {@code Unit.module}，故这是 unit 域命令）。
 *
 * <pre>{@code
 * {"unitId":"army-1","masterGov":"gov-central","role":"garrison"}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code masterGov} 可缺省（未认主子）；{@code role} 必填、非空白（词表后置，自由短名）。
 *
 * <p>★ <b>拒因</b>（由 {@link UnitOperations#setArmyFormation} / {@link ArmyFormation} 给出）：单位不存在；单位已带
 * {@code GovFormation}（一单位至多一个标签，不静默替换）；{@code masterGov} 不存在 / 不是 GOV；{@code role} 空白。同类型重复设置 =
 * 整体替换（文档见操作面）。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：按载荷点名的 unitId 判。本命令只写 unit 命名空间。
 */
public final class SetArmyFormationHandler implements CommandHandler, CommandTargets {

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "unitId"));
  }

  @Override
  public String type() {
    return "unit.SetArmyFormation";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "unitId"));
      Optional<UnitId> masterGov = UnitPayloads.optionalId(payload, "masterGov");
      String role = UnitPayloads.requireText(payload, "role");
      ArmyFormation formation = new ArmyFormation(masterGov, role);
      UnitState next = UnitOperations.setArmyFormation(snapshot.state(), id, formation);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

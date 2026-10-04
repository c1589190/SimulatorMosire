package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * ★★ {@code unit.SetVisionRadius}（P1.2 / A6）：设置单位视野半径（六角圈数，{@code ≥ 0}；0 = 只看自身格）。
 *
 * <pre>{@code
 * {"id":"大蜀-army","visionRadius":3}
 * }</pre>
 *
 * <p>★ 字段 {@link io.mosire.simos.unit.Unit#visionRadius()} 早已存在，本命令补齐<b>写路径</b>；当前唯一读者是 app 侧
 * {@code ArmyScope}（军队决策人的可见范围按军队位置 + 本半径现算），下一轮现算即生效、无缓存失效。
 *
 * <p>★ <b>非 GmOnly</b>：与既有 unit 命令同待遇（可嵌入决策令）；{@link CommandTargets} 声明被改单位。
 */
public final class SetVisionRadiusHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "unit.SetVisionRadius";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      int visionRadius = UnitPayloads.requireInt(payload, "visionRadius");
      var next = UnitOperations.setVisionRadius(snapshot.state(), id, visionRadius);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

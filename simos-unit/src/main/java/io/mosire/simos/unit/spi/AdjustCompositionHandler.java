package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.AdjustComposition} 命令的处理器（阶段 D3a，2026-10-02 / D-009 补裁）： {@code id,
 * equipment[{type,amount(有符号)}], equipment[{type,amount(有符号)}]}。
 *
 * <pre>{@code
 * {"id":"u-1","equipment":[{"type":"重骑兵","amount":30},{"type":"轻步兵","amount":-12}]}
 * }</pre>
 *
 * <p>★ <b>GM 调试直改原语</b>（用户 D-009 补裁：「为了确保调试，单独的 Unit 人力/装备变动自然也必须被 GM 工具组支持」）： 正增量可新建
 * type（追加在表尾）、负增量要求 type 已存在且 {@code |Δ| ≤ 当前值}；一条命令原子地改两张表。
 *
 * <p>★ <b>GmOnly（控制器 2026-10-02 裁定，收紧）</b>：它是**GM 调试直改原语**，实现 {@link GmOnlyCommand} ⇒ 不进决策令白名单 /
 * {@code RegisterEffect} 可入队白名单 / 决策人工具目录，只有 GM 的 {@code simos.command.submit} 与窄工具 {@code
 * simos.unit.adjust-composition}（只在 GM 桶）能用。 ★ 依据：四线调查 A 线 A7 实测"{@code unit.SetStrength} 未标 GmOnly
 * 且可嵌令 ⇒ 决策人能在自己视野内凭空增兵"； 正常的整表复写仍走 {@code unit.SetComposition}（与旧 {@code SetStrength} 同待遇，不由本条收权）。
 *
 * <p>★ 目标声明（{@link CommandTargets}）：载荷点名的**那一个单位**。
 */
public final class AdjustCompositionHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（信封上的 {@code type}，也是 catalog / 窄工具引用的唯一拼写点）。 */
  public static final String TYPE = "unit.AdjustComposition";

  /** ★ 目标资源（{@link CommandTargets}）：本命令点名的**那一个单位**。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state);
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      UnitPayloads.rejectRetiredManpower(payload);
      List<CompositionDelta> equipment = UnitPayloads.requireCompositionDelta(payload, "equipment");
      UnitState next = UnitOperations.adjustComposition(snapshot.state(), id, equipment);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

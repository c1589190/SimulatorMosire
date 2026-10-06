package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.ops.EdgeOperations;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Set;

/**
 * {@code map.SetEdge} 命令的处理器（M8 spec §二）：**编辑河流/道路等连通性标注**，{@code replace}/{@code merge}
 * **必须显式**（Q2）。
 *
 * <pre>{@code
 * CommandEnvelope{type:"map.SetEdge", payloadJson:{"kind":"river","edges":["0_0|1_0"],"mode":"merge"}}
 *   → 本类自己反序列化 payload、调 EdgeOperations.setEdge（规则在 simos-map）
 *   → 返回 HandlerOutcome.Applied(MapChangeSet)
 * }</pre>
 *
 * <p>★ **payload 形态是本模块的私事**（C26）：Core 只转交 {@code payloadJson} 字节串（R11）。本类认 {@code
 * {"kind":字符串,"edges":["q_r|q_r"…],"mode":"replace"|"merge"}}，字段缺失或形态不符 ⇒ {@code Rejected}。
 *
 * <p>★ **{@code mode} 没有默认值**：缺字段 ⇒ {@code requireText} 当场拒；非法值由 {@link EdgeOperations} 拒。这是 spec
 * §二 / Q2 的硬约束——静默兜底会让"想 merge 却整份覆盖"这种最贵的教训重演。
 *
 * <p>★ **域规则违反以 {@code Rejected} 面世**：{@link EdgeOperations} 的 {@link IllegalArgumentException}
 * （kind 不在词表、mode 非法、空边集、端点不在图上）在本边界折成拒绝理由。
 */
public final class SetEdgeHandler implements CommandHandler {

  @Override
  public String type() {
    return "map.SetEdge";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap map = MapSnapshots.of(state).map(); // 装配故障当场炸，不走拒绝路径
    String kindForLog = null;
    String modeForLog = null;
    Integer edgesForLog = null;
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      String kind = MapPayloads.requireText(payload, "kind");
      kindForLog = kind;
      Set<EdgeRef> edges = MapPayloads.requireEdgeRefs(payload, "edges");
      edgesForLog = edges.size();
      String mode = MapPayloads.requireText(payload, "mode");
      modeForLog = mode;
      var applied = EdgeOperations.setEdge(map, kind, edges, mode);
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_SET_EDGE_APPLIED",
                  MapLogSource.MAP_EDIT,
                  "kind",
                  kind,
                  "mode",
                  mode,
                  "edges",
                  edges.size()));
      return new HandlerOutcome.Applied(applied);
    } catch (IllegalArgumentException e) {
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_SET_EDGE_REJECTED",
                  MapLogSource.MAP_EDIT,
                  "reason",
                  MapPayloads.logReason(e.getMessage()),
                  "kind",
                  kindForLog == null ? "-" : kindForLog,
                  "mode",
                  modeForLog == null ? "-" : modeForLog,
                  "edges",
                  edgesForLog == null ? "-" : edgesForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

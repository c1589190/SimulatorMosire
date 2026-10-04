package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.RegionOperations;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code map.ReassignHexes}（P1.2 区划语义命令）：把指定 hex 从若干源区域划给目标区域。
 *
 * <pre>{@code
 * {"toRegionId":"A","fromRegionIds":["B","C"],"hexes":[{"q":0,"r":0},{"q":1,"r":0}]}
 * }</pre>
 *
 * <p>★★ <b>语义</b>：对每个 hex，从所有 {@code fromRegionIds} 里移除、往 {@code toRegionId} 加入；每个 hex 必须至少
 * 属于一个源区域；任一源被划空 ⇒ 拒（请改用 Merge/Split/Delete 显式处理空区域）。重叠是 M8-U1 允许的正常状态， 本命令只做显式集合运算，不猜"真正归属"。
 *
 * <p>★★ <b>模块边界</b>：只改 map 自己的 {@code regions}；下游 jurisdiction / 城市 region / 税率 / 编制重算由 app 组合根
 * {@code submitBatch} 协调（见 {@code MergeRegionsHandler}）。
 *
 * <p>★ <b>GM-only</b>。
 */
public final class ReassignHexesHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "map.ReassignHexes";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = MapPayloads.parse(payloadJson);
    RegionId target = MapPayloads.requireRegionId(payload, "toRegionId");
    List<RegionId> sources = MapPayloads.requireRegionIds(payload, "fromRegionIds");
    Set<HexCoord> hexes = MapPayloads.requireHexes(payload, "hexes");
    Set<String> paths = new LinkedHashSet<>();
    paths.add(ResourcePaths.region(mapId, target.value()));
    for (RegionId source : sources) {
      paths.add(ResourcePaths.region(mapId, source.value()));
    }
    for (HexCoord hex : hexes) {
      paths.add(ResourcePaths.hex(mapId, hex.q(), hex.r()));
    }
    return List.copyOf(paths);
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap base = MapSnapshots.of(state).map();
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      RegionId target = MapPayloads.requireRegionId(payload, "toRegionId");
      Set<RegionId> sources =
          new LinkedHashSet<>(MapPayloads.requireRegionIds(payload, "fromRegionIds"));
      Set<HexCoord> hexes = MapPayloads.requireHexes(payload, "hexes");
      return new HandlerOutcome.Applied(
          RegionOperations.reassignHexes(base, target, sources, hexes));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

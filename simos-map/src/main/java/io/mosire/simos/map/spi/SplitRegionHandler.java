package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.ops.RegionOperations;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code map.SplitRegion}（P1.2 区划语义命令）：把一个区域拆成若干新区域。
 *
 * <pre>{@code
 * // keepSource=false：parts 必须恰好覆盖源区域，源被删除
 * {"sourceRegionId":"A","keepSource":false,
 *  "parts":[{"regionId":"A1","name":"甲一","hexes":[{"q":0,"r":0}]},
 *           {"regionId":"A2","name":"甲二","hexes":[{"q":1,"r":0}]}]}
 *
 * // keepSource=true：parts 是源的真子集，源保留剩余格（身份/名称/元数据不变）
 * {"sourceRegionId":"A","keepSource":true,
 *  "parts":[{"regionId":"A1","name":"甲一","hexes":[{"q":0,"r":0}]}]}
 * }</pre>
 *
 * <p>★★ <b>模块边界</b>：只改 map 自己的 {@code regions}；下游 jurisdiction / 城市归属 / 税率 / 编制由 app 组合根 {@code
 * submitBatch} 协调（见 {@code MergeRegionsHandler}）。
 *
 * <p>★ <b>GM-only</b>。
 */
public final class SplitRegionHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "map.SplitRegion";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    List<RegionOperations.RegionPart> parts = parseParts(payloadJson);
    RegionId source = MapPayloads.requireRegionId(MapPayloads.parse(payloadJson), "sourceRegionId");
    Set<String> paths = new LinkedHashSet<>();
    paths.add(ResourcePaths.region(mapId, source.value()));
    for (RegionOperations.RegionPart part : parts) {
      paths.add(ResourcePaths.region(mapId, part.id().value()));
      for (HexCoord hex : part.hexes()) {
        paths.add(ResourcePaths.hex(mapId, hex.q(), hex.r()));
      }
    }
    return List.copyOf(paths);
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap base = MapSnapshots.of(state).map();
    String sourceForLog = null;
    Boolean keepSourceForLog = null;
    Integer partsForLog = null;
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      RegionId source = MapPayloads.requireRegionId(payload, "sourceRegionId");
      sourceForLog = source.value();
      boolean keepSource = MapPayloads.optionalBoolean(payload, "keepSource", false);
      keepSourceForLog = keepSource;
      List<RegionOperations.RegionPart> parts = parseParts(payload);
      partsForLog = parts.size();
      var applied = RegionOperations.splitRegion(base, source, parts, keepSource);
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_SPLIT_REGION_APPLIED",
                  MapLogSource.MAP_EDIT,
                  "source",
                  source.value(),
                  "parts",
                  parts.size(),
                  "keepSource",
                  keepSource));
      return new HandlerOutcome.Applied(applied);
    } catch (IllegalArgumentException e) {
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_SPLIT_REGION_REJECTED",
                  MapLogSource.MAP_EDIT,
                  "reason",
                  MapPayloads.logReason(e.getMessage()),
                  "source",
                  sourceForLog == null ? "-" : sourceForLog,
                  "parts",
                  partsForLog == null ? "-" : partsForLog,
                  "keepSource",
                  keepSourceForLog == null ? "-" : keepSourceForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** {@code parts[]}：每项 {@code {regionId,name,hexes,meta?}}（meta 缺省 = 空元数据）。 */
  private static List<RegionOperations.RegionPart> parseParts(String payloadJson) {
    return parseParts(MapPayloads.parse(payloadJson));
  }

  private static List<RegionOperations.RegionPart> parseParts(JsonNode payload) {
    JsonNode value = payload.get("parts");
    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException(
          "字段 parts 必须是 [{regionId,name,hexes,meta?}…] 数组: " + payload);
    }
    if (value.isEmpty()) {
      throw new IllegalArgumentException("字段 parts 不得为空数组");
    }
    List<RegionOperations.RegionPart> parts = new ArrayList<>(value.size());
    for (JsonNode part : value) {
      if (!part.isObject()) {
        throw new IllegalArgumentException("字段 parts 的元素必须是对象: " + part);
      }
      RegionId id = MapPayloads.requireRegionId(part, "regionId");
      String name = MapPayloads.requireText(part, "name");
      Set<HexCoord> hexes = MapPayloads.requireHexes(part, "hexes");
      RegionMeta meta = MapPayloads.optionalMeta(part, "meta");
      parts.add(
          new RegionOperations.RegionPart(
              id, name, hexes, meta == null ? RegionMeta.empty() : meta));
    }
    return List.copyOf(parts);
  }
}

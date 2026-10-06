package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapLog;
import io.mosire.simos.map.MapLogSource;
import io.mosire.simos.map.ops.RegionOperations;
import io.mosire.simos.map.region.RegionId;
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

/**
 * ★★ {@code map.MergeRegions}（P1.2 区划语义命令）：把若干区域并入目标区域，目标保留身份/名称/元数据，源区域删除。
 *
 * <pre>{@code
 * {"targetRegionId":"A","sourceRegionIds":["B","C"]}
 * }</pre>
 *
 * <p>★★ <b>模块边界</b>：本 handler 只改 map 自己的 {@code regions}（返回 {@code MapChangeSet}）；jurisdiction / 城市
 * region / 税率 / 编制等跟随重算<b>不在本命令</b>——需要时由 app 组合根用 {@code CommandBus.submitBatch} 把本命令与 {@code
 * unit.SetJurisdiction} / {@code social.UpdateCity} / {@code unit.SetTaxRate} 等同批提交（铁律 3/4）。
 *
 * <p>★ <b>GM-only</b>：区划重整是行政原语，不开放给决策令直调。
 */
public final class MergeRegionsHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "map.MergeRegions";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = MapPayloads.parse(payloadJson);
    List<RegionId> sources = MapPayloads.requireRegionIds(payload, "sourceRegionIds");
    RegionId target = MapPayloads.requireRegionId(payload, "targetRegionId");
    List<String> paths = new ArrayList<>(sources.size() + 1);
    paths.add(ResourcePaths.region(mapId, target.value()));
    for (RegionId source : sources) {
      paths.add(ResourcePaths.region(mapId, source.value()));
    }
    return List.copyOf(new LinkedHashSet<>(paths));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap base = MapSnapshots.of(state).map();
    String targetForLog = null;
    int sourcesForLog = -1;
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      RegionId target = MapPayloads.requireRegionId(payload, "targetRegionId");
      targetForLog = target.value();
      var sources = new LinkedHashSet<>(MapPayloads.requireRegionIds(payload, "sourceRegionIds"));
      sourcesForLog = sources.size();
      var applied = RegionOperations.mergeRegions(base, target, sources);
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_MERGE_REGIONS_APPLIED",
                  MapLogSource.MAP_EDIT,
                  "target",
                  target.value(),
                  "sources",
                  sources.size()));
      return new HandlerOutcome.Applied(applied);
    } catch (IllegalArgumentException e) {
      EventLog.channel(MapLog.edit())
          .info(
              LogEvent.of(
                  "MAP_MERGE_REGIONS_REJECTED",
                  MapLogSource.MAP_EDIT,
                  "reason",
                  MapPayloads.logReason(e.getMessage()),
                  "target",
                  targetForLog == null ? "-" : targetForLog,
                  "sources",
                  sourcesForLog < 0 ? "-" : sourcesForLog));
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

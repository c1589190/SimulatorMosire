package io.mosire.simos.map.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code map.RenameRegion} 命令的处理器（R4，行政区划修复计划 §1.4）：GM 改已有区域的显示名（**不动内容与边界**）。
 *
 * <pre>{@code
 * {"regionId":"r1","name":"新的区域名"}
 * }</pre>
 *
 * <p>★ <b>GM-only</b>（{@link GmOnlyCommand}）：决策人只能提出改名诉求，实际执行由 GM 走 {@code simos.command.submit} 或
 * GM 窄工具；该标记会自动把它排除出令白名单 / {@code RegisterEffect} / 决策人 catalog，GM 的提交面与窄工具照常可用。
 *
 * <p>★ <b>为什么单开一条命令而不复用 {@code map.UpdateRegion}</b>：{@code map.UpdateRegion} 的 {@code name}
 * 不在载荷里（它改 hexes/meta），且 GM 改名的语义是"只动 name"——复用会把"改名"与"改内容"混成一条既 能改边界又能改名的命令。{@link
 * Region#withName} 已存在（改名不动内容 ⇒ 边界可原样复用），本类不另造 Region。
 *
 * <p>★ <b>目标必须已存在</b>（改名不是 upsert）：{@code regionId} 查无 ⇒ {@code Rejected}（具名，零 revision）。
 *
 * <p>★ <b>目标资源</b>（{@link CommandTargets}）：被改名区域那一条 {@code map:<mapId>/region/<regionId>}。 载荷坏 /
 * 目标不存在 ⇒ 拒绝，不留 revision。
 */
public final class RenameRegionHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型字面量（同时是窄工具名；两处同源在各自类的 {@code NAME}）。 */
  public static final String TYPE = "map.RenameRegion";

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = MapPayloads.parse(payloadJson);
    return List.of(
        ResourcePaths.region(mapId, MapPayloads.requireRegionId(payload, "regionId").value()));
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    GameMap map = MapSnapshots.of(state).map();
    try {
      JsonNode payload = MapPayloads.parse(payloadJson);
      RegionId id = MapPayloads.requireRegionId(payload, "regionId");
      String name = MapPayloads.requireText(payload, "name");
      if (name.isBlank()) {
        throw new IllegalArgumentException("字段 name 不得为空白: " + name);
      }
      Region existing = map.regions().get(id);
      if (existing == null) {
        return new HandlerOutcome.Rejected("区域不存在: " + id.value());
      }
      Map<RegionId, Region> next = new LinkedHashMap<>(map.regions());
      next.put(id, existing.withName(name));
      // ★ 唯一变更集路径：从"改名前整图"与"只换 regions 组件后整图"派生。
      return new HandlerOutcome.Applied(MapChangeSet.between(map, map.withRegions(next)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

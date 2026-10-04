package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code social.ClearRegion}（P1b1，2026-10-01 后端 + MCP 稳定化计划）：<b>GM-only 区域社会数据清空命令</b>——
 * 只清**数值与关联记录**，不动任何地图/单位/GOV/决策人结构。
 *
 * <pre>{@code
 * {"regionId":"701"}
 * }</pre>
 *
 * <p>★★ <b>清空边界（目标 Region 的 hex 集为唯一范围）</b>：
 *
 * <ul>
 *   <li>{@code SocialData.populations}：键落在目标 hex 集的序列整条删除；
 *   <li>{@code SocialData.groups}：{@code residence} 落在目标 hex 集的批次整条删除；
 *   <li>{@code SocialData.cities}：{@code SocialCity.at} 落在目标 hex 集，或 {@code city.region == 目标
 *       Region} 的城市整条删除（两个判据并列，任一命中即删）；
 *   <li>其余 social 组件与其它 Region 的记录<b>一字不动</b>。
 * </ul>
 *
 * <p>★★ <b>为什么先删 groups 再删 populations</b>：{@code SocialData} 构造期有一条跨组件校验—— 每个批次的 {@code residence}
 * 必须落在**已有 populations 序列**的格上。若先删序列，中间态会立刻违约； 先删批次再删序列则每个中间态都合法。 两条删除合起来仍是"一次命令、一条
 * revision、整条原子"（变更集在 {@code handle} 出口一次性交出）。
 *
 * <p>★ <b>Region 必须先在 {@code map.regions()} 里存在</b>：缺 map 切片/切片类型不对是装配故障（{@link
 * IllegalStateException} 当场炸，不走拒绝路径）；payload 里的 regionId 在 map 里查不到才是 {@link
 * HandlerOutcome.Rejected}（零 revision）。
 *
 * <p>★★ <b>GM-only</b>：实现 {@link GmOnlyCommand} —— 仍注册到 Core、仍进 {@code commandTargets}，GM 的 {@code
 * simos.command.submit} 可提交；但组合根构造 {@code DirectiveWhitelist}/{@code RegisterEffect}
 * 白名单/决策人工具目录时排除它。理由同其余 GM-only 原语：这是"清空"这种破坏性动作，不能由决策人间接触发。
 *
 * <p>★ <b>{@link CommandTargets} 的诚实边界</b>：{@code targetPaths(mapId, payloadJson)} 的签名拿不到 state，
 * 而逐格 social 资源路径必须读目标 Region 的 hex 集才能展开；本命令 GM-only、不进入决策令/裁决目标检查 ⇒ 返回空列表（"没有可声明的目标"，
 * fail-closed）。真正要动的格由 {@code handle} 从只读 state 里取。
 */
public final class ClearRegionHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点：catalog 提示、Shell 注册与组合工具都从这里取/对齐）。 */
  public static final String TYPE = "social.ClearRegion";

  @Override
  public String type() {
    return TYPE;
  }

  /**
   * ★ 本命令没有可签名的目标（见类注）：载荷只有 regionId，逐格资源要读 state 才能展开；本命令 GM-only、不进入决策令与裁决目标检查。 仍解析载荷形状，坏载荷照旧以
   * {@link IllegalArgumentException} 出面（供调用方折成该条命令的拒因）。
   */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    requireRegionId(SocialPayloads.parse(payloadJson));
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      String regionId = requireRegionId(SocialPayloads.parse(payloadJson));
      Region region = requireRegion(state, regionId);
      Set<HexCoord> hexes = region.hexes();
      // ★ 顺序：先删批次（residence 命中），再删序列（键命中）——中间态满足 SocialData 的跨组件校验（见类注）。
      Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>(base.groups());
      groups.values().removeIf(group -> hexes.contains(group.residence()));
      Map<HexCoord, PopulationSeries> populations = new LinkedHashMap<>(base.populations());
      populations.keySet().removeIf(hexes::contains);
      Map<CityId, SocialCity> cities = new LinkedHashMap<>(base.cities());
      cities
          .values()
          .removeIf(
              city ->
                  hexes.contains(city.at())
                      || city.region().filter(region.id()::equals).isPresent());
      SocialData next = base.withGroups(groups).withPopulations(populations).withCities(cities);
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      // ★ 域构造期守卫（若清空边界写错）也在这里折成具名拒绝，不穿成整条推进失败。
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** payload 的 regionId：必填、非空文本（空白由本层具名拒，不落到 RegionId 的构造器里）。 */
  private static String requireRegionId(JsonNode payload) {
    String regionId = SocialPayloads.requireText(payload, "regionId");
    if (regionId.isBlank()) {
      throw new IllegalArgumentException("字段 regionId 不得为空白");
    }
    return regionId;
  }

  /**
   * 从只读 state 的 map 切片里取真档 Region：缺切片/切片类型不对 = 装配故障（{@link IllegalStateException} 当场炸）； regionId
   * 查不到 = 命令载荷错误（{@link IllegalArgumentException}，由 {@link #handle} 折成 Rejected）。
   */
  private static Region requireRegion(SimulationState state, String regionId) {
    Snapshot mapModule =
        state.module("map").orElseThrow(() -> new IllegalStateException("state 里没有 map 切片（装配故障）"));
    if (!(mapModule instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalStateException(
          "state 的 map 切片不是 MapSnapshot: " + mapModule.getClass().getName());
    }
    Region region = mapSnapshot.map().regions().get(new RegionId(regionId));
    if (region == null) {
      throw new IllegalArgumentException("当前 map 里没有这个 region: " + regionId);
    }
    return region;
  }
}

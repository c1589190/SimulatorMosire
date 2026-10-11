package io.mosire.simos.map.tools.read;

import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.List;
import java.util.Map;

/**
 * 地图读工具共用的三件小事：**查询坐标**（载荷 → 值参）、**map 切片取用**、**canonical hex 地址**。
 *
 * <p>★ <b>为什么坐标是值参而不是宿主的查询对象</b>：宿主（app）的 {@code QueryTarget} 是 app 类型，进不了 {@code simos-map}（依赖方向固定
 * {@code util → map → …}）。工具只把载荷里的 {@code branch}/{@code revision} 解析成 util 类型，交给注入的 {@link
 * ToolReadPort}——head 解析、创世等待、读缓存都在宿主实现里（口径逐条不变）。
 *
 * <p>★ <b>为什么 {@code gameMap} 有两个文案</b>：这不是新设计，是**保留既有拒绝文案**（I-G1：工具结果的文案逐字不变）。 今天 {@code
 * MapHexTool}/{@code MapOverlapsTool}/{@code MapOverviewTool}/{@code MapRenderTool} 走 app 的 {@code
 * ToolSupport.gameMap}（短句），而 {@code MapBlockTool}/{@code MapRegionTool} 走 {@code ApiViews.gameMap}
 * （多一句"不是没有候选"）。两条路都只在**装配故障**（状态里没有 map 切片）时才发出——照抄两份文案，是为了让搬迁 一个字节都不改，而不是承认两份文案是对的。
 */
final class MapToolReads {

  private MapToolReads() {}

  /** 载荷里的分支名（缺省 {@link ToolResources#DEFAULT_BRANCH}，与宿主同口径）。 */
  static BranchId branch(Map<String, Object> args) {
    return new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
  }

  /** 载荷里的版本号；缺省（{@code null}）= 该分支 head（{@link ToolReadPort#stateAt} 的同一条约定）。 */
  static RevisionId revision(Map<String, Object> args) {
    Long revision = ToolSchema.optionalLong(args, "revision");
    return revision == null ? null : new RevisionId(revision);
  }

  /** 按载荷坐标取状态（{@code branch}/{@code revision} 的解析与断言都在这里）。 */
  static SimulationState state(ToolReadPort reads, Map<String, Object> args) {
    return reads.stateAt(branch(args), revision(args));
  }

  /**
   * canonical {@code map:<mapId>:hex.<q>_<r>}——**只能用 Address AST 造**（facet 只服务 canonical 主体，
   * 且查询门面不改写转交的地址）。
   *
   * <p>★ 坐标串取 {@link HexCoord#toString()}（不是手拼 {@code q + "_" + r}）：坐标串的**唯一实现**在 {@code
   * HexCoord}——map 模块自带的 L6 护栏就钉着这一条（"多了就是第二份坐标串实现"），值逐字相同。
   */
  static String canonicalHex(String mapId, int q, int r) {
    Address address =
        new Address(
            List.of(
                new Namespace("map"),
                Entity.of(mapId),
                Entity.of("hex", new HexCoord(q, r).toString())));
    return address.canonical();
  }

  /** 状态里的 map 切片（{@code ToolSupport.gameMap} 的逐字口径）。 */
  static GameMap gameMap(SimulationState state) {
    Snapshot snapshot =
        state.module("map").orElseThrow(() -> new IllegalArgumentException("状态里没有 map 模块切片——装配故障"));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalArgumentException("map 模块切片不是 MapSnapshot：" + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }

  /** 状态里的 map 切片（{@code ApiViews.gameMap} 的逐字口径——多一句"不是没有候选"）。 */
  static GameMap gameMapForView(SimulationState state) {
    Snapshot snapshot =
        state
            .module("map")
            .orElseThrow(() -> new IllegalArgumentException("状态里没有 map 模块切片——装配故障，不是\"没有候选\""));
    if (!(snapshot instanceof MapSnapshot mapSnapshot)) {
      throw new IllegalArgumentException("map 模块切片不是 MapSnapshot：" + snapshot.getClass().getName());
    }
    return mapSnapshot.map();
  }
}

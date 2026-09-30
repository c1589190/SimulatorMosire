package io.mosire.simos.app.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.codec.ActorCodec;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.gov.codec.GovCodec;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.pathway.EdgeRef;
import io.mosire.simos.map.pathway.EdgeTags;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * {@link RichWorld} 的逐值护栏（T11）：复刻世界的形状与**可断言的数字**（59223 hex / 252 区域 / 240 河流边 / 地形直方图 /
 * 多对多从属）都钉成字面量，并证明它经**真引擎**（{@code bootstrapGenesis} + {@code replay}）往返一致。
 *
 * <p>★ 预期值都是**当场从源档算过的字面量**（地形合并：`plains = lowland 16933 + plains 11315 + swamp 99 = 28347`），不是
 * "再调一遍领域 API 对拍"。
 */
class RichWorldTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final RevisionId R1 = new RevisionId(1);

  /** 与 {@code HexDirection.ALL} 同序（E,SE,SW,W,NW,NE）；只用于断言边两端相邻。 */
  private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {-1, 1}, {-1, 0}, {0, -1}, {1, -1}};

  @Test
  void richWorldIsDeterministicAndCarriesTheFourSlices() {
    SimulationState first = RichWorld.state("Map1");
    SimulationState second = RichWorld.state("Map1");

    assertThat(first).as("确定性：同参数逐字段相等").isEqualTo(second);
    assertThat(first.meta().ref()).isEqualTo(new StateRef(MAIN, R1));
    assertThat(first.meta().timestamp().tick()).as("资源信封的 tick").isZero();
    assertThat(first.modules().keySet())
        .containsExactlyInAnyOrder("map", "social", "unit", "sd", "economy", "actor", "gov");
    assertThat(first.info()).as("C26：绝不录 Info").isEqualTo(InMemoryInfoSystem.empty());
  }

  @Test
  void hexCountAndTerrainHistogramMatchTheLossyMerge() {
    GameMap map = map();
    assertThat(map.hexes()).as("v17levant 源档 hex 数").hasSize(59223);

    Map<String, Long> histogram = new TreeMap<>();
    for (String terrain : map.terrainIndex().values()) {
      histogram.merge(terrain, 1L, Long::sum);
    }
    assertThat(histogram)
        .as("water→ocean；lowland(16933)+plains(11315)+swamp(99) lossy 合并入 plains ⇒ 28347")
        .containsOnly(
            Map.entry("ocean", 14927L),
            Map.entry("plains", 28347L),
            Map.entry("desert", 746L),
            Map.entry("low_hills", 14107L),
            Map.entry("mountains", 1096L));
    assertThat(histogram.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(59223L);
    assertThat(map.terrainTypes().keySet())
        .as(
            "在用的 5 类（ocean/plains/desert/low_hills/mountains）+ 平缓高原 plateau（★ 2026-09-24 用户裁定："
                + "世界词表恒带 plateau，否则调色板里画不出高原；高原山地不纳入世界词表）")
        .containsExactly("ocean", "plains", "desert", "low_hills", "mountains", "plateau");
  }

  @Test
  void provincesAre252AllNation() {
    Map<RegionId, Region> regions = map().regions();
    assertThat(regions).as("★ U4：截至 n0008 回合的最全区域（n0000 只有 98）").hasSize(252);

    long nations = regions.values().stream().filter(r -> "Nation".equals(r.meta().tag())).count();
    long kingdoms = regions.values().stream().filter(r -> "王国".equals(r.meta().tag())).count();
    assertThat(nations).as("n0001 起『王国』被覆盖 ⇒ 最终 252 个全是 Nation").isEqualTo(252);
    assertThat(kingdoms).isZero();
  }

  @Test
  void riverEdgesAre240AdjacentAndAllRiver() {
    GameMap map = map();
    Map<EdgeRef, EdgeTags> edges = map.edges();
    assertThat(edges).as("edgeTags 480 个有向条目 / 2 = 240 条无向边（★ 不是 248——248 是『非空格数』）").hasSize(240);

    for (Map.Entry<EdgeRef, EdgeTags> entry : edges.entrySet()) {
      EdgeRef edge = entry.getKey();
      assertThat(map.hexes()).containsKey(edge.a());
      assertThat(map.hexes()).containsKey(edge.b());
      assertThat(isNeighbor(edge.a(), edge.b())).as("边两端必须相邻: %s", edge).isTrue();
      assertThat(entry.getValue().byPathway().keySet())
          .as("每条边只带 river 组（v17levant 无道路）")
          .containsExactly("river");
    }
  }

  @Test
  void aHexCanBelongToTwoRegions() {
    GameMap map = map();
    List<RegionId> owners = map.regionIndex().regionOf(new HexCoord(-105, 67));

    assertThat(owners)
        .as("M8-U1：从属是多对多；此格同属 2 个区域")
        .containsExactly(new RegionId("区域14"), new RegionId("石冠诸部"));
  }

  @Test
  void carriesRegionsThatN0000Lacked() {
    Map<RegionId, Region> regions = map().regions();

    // ★ U4 的干净名单：n0000 基础图**没有**、只有 n0001~n0007 的增量才带进来的实名区域。
    assertThat(regions.keySet())
        .as("★ U4「最全」：这些实名区域在 n0000 里不存在")
        .contains(
            new RegionId("瓦伦狄乌斯专制国"),
            new RegionId("霜脊伯国"),
            new RegionId("大汉都护府政权"),
            new RegionId("艾达王国"),
            new RegionId("蒙特卡西诺修道院领"));
  }

  @Test
  void bootstrapsAndReplaysThroughTheRealEngine() throws IOException {
    Path store = Files.createTempDirectory("richworld-e2e");
    try (CoreSimos core = new CoreSimos(new CoreConfig(store, 100, SimosObjectMapper.create()))) {
      core.register(new MapCodec());
      core.register(new SocialCodec());
      core.register(new UnitCodec());
      core.register(new SdCodec());
      core.register(new EconomyCodec());
      // ★ S1 阶段 2：RichWorld.state() 带 actor 切片 ⇒ codec 表必须随之长（否则 bootstrapGenesis 当场抛）。
      core.register(new ActorCodec());
      core.register(new GovCodec());

      SimulationState genesis = RichWorld.state("Map1");
      core.bootstrapGenesis(genesis);

      assertThat(core.branches()).containsExactly(MAIN);
      assertThat(core.head(MAIN)).contains(R1);
      assertThat(core.replay(new StateRef(MAIN, R1)))
          .as("真引擎往返：bootstrapGenesis 编码 → Replay 解码 == 资源解出的状态")
          .isEqualTo(genesis);
    }
  }

  @Test
  void bootstrapRefusesToOverwriteANonEmptyStore() throws IOException {
    Path store = Files.createTempDirectory("richworld-nooverwrite");
    try (CoreSimos core = new CoreSimos(new CoreConfig(store, 100, SimosObjectMapper.create()))) {
      core.register(new MapCodec());
      core.register(new SocialCodec());
      core.register(new UnitCodec());
      core.register(new SdCodec());
      core.register(new EconomyCodec());
      // ★ S1 阶段 2：RichWorld.state() 带 actor 切片 ⇒ codec 表必须随之长（否则 bootstrapGenesis 当场抛）。
      core.register(new ActorCodec());
      core.register(new GovCodec());

      core.bootstrapGenesis(RichWorld.state("Map1"));
      assertThatThrownBy(() -> core.bootstrapGenesis(RichWorld.state("Map1")))
          .as("C25：非空库第二次创世被拒，绝不覆盖已有世界")
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("非空");
    }
  }

  @Test
  void blankMapIdIsRejected() {
    assertThatThrownBy(() -> RichWorld.state(" "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("空白");
  }

  private static GameMap map() {
    SimulationState state = RichWorld.state("Map1");
    SdSnapshot sd = (SdSnapshot) state.module("sd").orElseThrow();
    assertThat(sd.state()).as("补的空 sd 切片").isEqualTo(SdState.empty());
    return ((MapSnapshot) state.module("map").orElseThrow()).map();
  }

  private static boolean isNeighbor(HexCoord a, HexCoord b) {
    for (int[] d : DIRECTIONS) {
      if (a.q() + d[0] == b.q() && a.r() + d[1] == b.r()) {
        return true;
      }
    }
    return false;
  }
}

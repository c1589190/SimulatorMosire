package io.mosire.simos.map.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.BlockId;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexGrid;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ★ M9 T6 §3.12 的**旧档兼容**（线格式回退）：P1 之前地形逐格存在 {@code HexCell.terrain} 里、没有 {@code terrainBlocks}
 * 段；旧存档必须**能读回来且块正确**，不许静默失败。
 *
 * <p>做法：用**新** codec 编出一份新形状快照，再把它**改写成旧形状**（把地形塞回逐格、删掉 terrainBlocks）—— 这样信封/ref/timestamp
 * 的字节与真实旧档一致，只有 map 段是旧的（这正是不变量在验的那一段）。
 */
class MapCodecLegacyTest {

  private static final MapCodec CODEC = new MapCodec();

  private static final HexCoord H00 = new HexCoord(0, 0);

  private static final HexCoord H10 = new HexCoord(1, 0);

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  @Test
  void oldShapeSnapshotIsMigratedToAuthoritativeBlocks() throws Exception {
    GameMap original = twoHexMap();
    MapSnapshot snapshot = new MapSnapshot(REF, SimosTimestamp.of(0), original);
    String oldShape =
        toOldShape(CODEC.encodeSnapshot(snapshot), Map.of("0_0", "plains", "1_0", "ocean"));

    MapSnapshot back = (MapSnapshot) CODEC.decodeSnapshot(oldShape);

    assertThat(back.map()).as("迁回的新形状整图等于原图（块由旧逐格地形重切）").isEqualTo(original);
    assertThat(back.map().hexes().get(H00).height()).as("旧档高度活着").isEqualTo(0.35);
    assertThat(back.map().hexes().get(H10).height()).isEqualTo(0.1);
    assertThat(back.map().terrainAt(H00)).as("逐格地形迁进权威块后可查").isEqualTo("plains");
    assertThat(back.map().terrainAt(H10)).isEqualTo("ocean");
    assertThat(back.map().terrainBlocks()).as("两个异地形格 ⇒ 两块").hasSize(2);
    assertThat(back.map().hexes().values())
        .as("迁后 hexes 只承载高度（不再有 terrain 字段可读）")
        .allSatisfy(cell -> assertThat(cell).isInstanceOf(HexCell.class));
  }

  /** ★ 带洞块的 JSON 往返：{@code terrainBlocks} 的边界（外环 + 洞环）逐字节活着。 */
  @Test
  void holeBearingBlocksRoundTripThroughJson() {
    HexCoord center = new HexCoord(0, 0);
    Map<HexCoord, String> terrain = new LinkedHashMap<>();
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    for (HexCoord hex : HexGrid.withinRadius(center, 1)) {
      terrain.put(hex, hex.equals(center) ? "ocean" : "plains");
      hexes.put(hex, new HexCell(0.5));
    }
    GameMap flower =
        new GameMap(
            hexes,
            TerrainBlocks.split(terrain),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            GenerationSpec.defaults(0L));
    MapSnapshot snapshot = new MapSnapshot(REF, SimosTimestamp.of(0), flower);

    MapSnapshot back = (MapSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back.map()).as("整图往返相等（含带洞块）").isEqualTo(flower);
    assertThat(back.map().terrainBlocks().get(BlockId.parse("plains@-1_0")).boundary().rings())
        .as("洞环在 JSON 往返后仍在")
        .hasSize(2);
  }

  /** 旧形状的 **变更集** 无法迁移（块切分依赖 base 全图）⇒ 显式抛、给出重导入指引（不静默、不半迁）。 */
  @Test
  void oldShapeChangeSetIsRejectedWithGuidance() {
    String oldChangeSet =
        "{\"hexes\":{\"@class\":\"upsert\",\"entries\":{\"0_0\":{\"terrain\":\"plains\",\"height\":0.35}}}}";

    assertThatThrownBy(() -> CODEC.decodeChangeSet(oldChangeSet))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("旧形状 map 变更集")
        .hasMessageContaining("gsimap_import.py");
  }

  /**
   * 把新形状的 {@code MapSnapshot} JSON 改写成旧形状：{@code hexes} 每个值加回 {@code terrain}、删掉 {@code
   * terrainBlocks}。
   *
   * <p>★ 只动 map 段，信封（ref/timestamp/info）字节原样保留——否则测的就不是"旧 map 段"这一件事。
   */
  private static String toOldShape(String newShapeJson, Map<String, String> terrainByHexKey)
      throws Exception {
    ObjectMapper treeMapper = new ObjectMapper();
    ObjectNode root = (ObjectNode) treeMapper.readTree(newShapeJson);
    ObjectNode map = (ObjectNode) root.get("map");
    ObjectNode hexes = (ObjectNode) map.get("hexes");
    for (Map.Entry<String, String> entry : terrainByHexKey.entrySet()) {
      ((ObjectNode) hexes.get(entry.getKey())).put("terrain", entry.getValue());
    }
    map.remove("terrainBlocks");
    return root.toString();
  }

  private static GameMap twoHexMap() {
    return new GameMap(
        Map.of(H00, new HexCell(0.35), H10, new HexCell(0.1)),
        TerrainBlocks.split(Map.of(H00, "plains", H10, "ocean")),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }
}

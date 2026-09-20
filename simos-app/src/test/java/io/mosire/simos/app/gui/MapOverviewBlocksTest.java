package io.mosire.simos.app.gui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.block.BlockId;
import io.mosire.simos.map.block.TerrainBlock;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.hex.HexVertex;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * M9 T13：overview 的**块多边形**视图（服务端发块）。
 *
 * <p>钉住四件事：① 逐格数组整个移除（地形只由块承载）；② 块按 {@link BlockId} 全序、坐标量化 ⇒ 同一状态两次响应**逐字节相同**； ③ 带洞块发**多条环**（m1
 * 的护栏：只发外环会填实洞）；④ 块 hexCount 之和 == 全图（分割不变式在视图层仍成立）。
 */
class MapOverviewBlocksTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void overviewEmitsDeterministicBlockPolygonsAndNoPerHexTerrain() throws Exception {
    GameMap map = holeMap();
    String firstJson = JSON.writeValueAsString(ApiViews.mapOverview("M", map));
    String secondJson = JSON.writeValueAsString(ApiViews.mapOverview("M", map));

    assertThat(firstJson).as("同一状态两次 overview 逐字节相同").isEqualTo(secondJson);
    assertThat(firstJson).as("逐格数组整个移除").doesNotContain("\"hexes\"");
    assertThat(firstJson).as("逐格 height 不再发").doesNotContain("\"height\"");
    assertThat(firstJson)
        .as("M9 T11：顶点走整数标签，不是 {\"x\":…,\"y\":…} 坐标对象（省流 ~2/3）")
        .doesNotContain("\"x\":")
        .doesNotContain("\"y\":");

    JsonNode blocks = JSON.readTree(firstJson).get("blocks");
    assertThat(blocks).as("中心 desert + 外圈 plains ⇒ 2 块").hasSize(2);

    JsonNode plains = blockByTerrain(blocks, "plains");
    JsonNode desert = blockByTerrain(blocks, "desert");
    assertThat(plains.get("hexCount").asInt()).isEqualTo(6);
    assertThat(desert.get("hexCount").asInt()).isEqualTo(1);
    assertThat(plains.get("boundaries")).as("外圈块带一个洞环 ⇒ 2 条环（m1 的护栏）").hasSize(2);
    assertThat(desert.get("boundaries")).as("单格块 1 条环").hasSize(1);

    assertRingsClosedAndIntegerLabeled(plains.get("boundaries"));
    assertRingsClosedAndIntegerLabeled(desert.get("boundaries"));

    JsonNode desertRing = desert.get("boundaries").get(0);
    assertThat(labelsOf(desertRing))
        .as("顶点恰是 HexVertex.at(hex,corner) 的整数标签")
        .isEqualTo(labelsOfHex(new HexCoord(0, 0)));

    int sum = 0;
    for (JsonNode block : blocks) {
      sum += block.get("hexCount").asInt();
    }
    assertThat(sum).as("块 hexCount 之和 == 全图格数").isEqualTo(map.hexes().size());
    assertThatCode(() -> TerrainBlocks.requirePartition(map.hexes(), map.terrainBlocks()))
        .as("分割不变式在视图层仍成立")
        .doesNotThrowAnyException();
  }

  @Test
  void blockOrderIsCanonicalRegardlessOfStateInsertionOrder() throws Exception {
    GameMap base = holeMap();
    List<Map.Entry<BlockId, TerrainBlock>> entries =
        new ArrayList<>(base.terrainBlocks().entrySet());
    Collections.reverse(entries);
    Map<BlockId, TerrainBlock> reversed = new LinkedHashMap<>();
    for (Map.Entry<BlockId, TerrainBlock> entry : entries) {
      reversed.put(entry.getKey(), entry.getValue());
    }
    GameMap reversedMap =
        new GameMap(
            base.hexes(),
            reversed,
            base.regions(),
            base.cities(),
            base.terrainTypes(),
            base.pathways(),
            base.pathwayGroups(),
            base.edges(),
            base.spec());

    JsonNode blocks =
        JSON.readTree(JSON.writeValueAsString(ApiViews.mapOverview("M", reversedMap)))
            .get("blocks");
    String firstId = blocks.get(0).get("id").asText();
    String secondId = blocks.get(1).get("id").asText();
    assertThat(firstId).as("块按 BlockId 全序发（不靠状态插入序）").isLessThan(secondId);
  }

  private static void assertRingsClosedAndIntegerLabeled(JsonNode boundaries) {
    assertThat(boundaries.isArray()).isTrue();
    assertThat(boundaries).isNotEmpty();
    for (JsonNode ring : boundaries) {
      assertThat(ring.size() % 2).as("整数标签环是 [u,w] 偶长：%s", ring.size()).isZero();
      assertThat((ring.size() - 2) / 2).as("环是多边形：去闭合点后顶点数 > 2").isGreaterThan(2);
      for (JsonNode value : ring) {
        assertThat(value.isInt()).as("顶点标签是整数：%s", value).isTrue();
      }
      assertThat(ring.get(ring.size() - 2).asInt())
          .as("环首尾同点（u 闭合）")
          .isEqualTo(ring.get(0).asInt());
      assertThat(ring.get(ring.size() - 1).asInt())
          .as("环首尾同点（w 闭合）")
          .isEqualTo(ring.get(1).asInt());
    }
  }

  /** 一条整数标签环 ⇒ 去重后的 "u,w" 标签集（顺序无关，用于与 {@link HexVertex#at} 对拍）。 */
  private static Set<String> labelsOf(JsonNode ring) {
    Set<String> labels = new LinkedHashSet<>();
    for (int i = 0; i + 1 < ring.size(); i += 2) {
      labels.add(ring.get(i).asInt() + "," + ring.get(i + 1).asInt());
    }
    return labels;
  }

  /** 某格的六个角顶点标签集。 */
  private static Set<String> labelsOfHex(HexCoord hex) {
    Set<String> labels = new LinkedHashSet<>();
    for (int corner = 0; corner < 6; corner++) {
      HexVertex vertex = HexVertex.at(hex, corner);
      labels.add(vertex.u() + "," + vertex.w());
    }
    return labels;
  }

  private static JsonNode blockByTerrain(JsonNode blocks, String terrain) {
    for (JsonNode block : blocks) {
      if (terrain.equals(block.get("terrain").asText())) {
        return block;
      }
    }
    throw new AssertionError("overview 里没有地形块 " + terrain);
  }

  /** 中心 desert、六邻 plains 的带洞图：plains 块的外轮廓包着 desert 飞地（一个洞环）。 */
  private static GameMap holeMap() {
    TerrainType desert = TerrainCatalog.of("desert");
    TerrainType plains = TerrainCatalog.of("plains");
    HexCoord center = new HexCoord(0, 0);
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    Map<HexCoord, String> terrainByHex = new LinkedHashMap<>();
    hexes.put(center, new HexCell(0.5));
    terrainByHex.put(center, desert.key());
    for (HexCoord neighbor : center.neighbors()) {
      hexes.put(neighbor, new HexCell(0.25));
      terrainByHex.put(neighbor, plains.key());
    }
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(desert.key(), desert);
    terrainTypes.put(plains.key(), plains);
    return new GameMap(
        hexes,
        TerrainBlocks.split(terrainByHex),
        Map.of(),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }
}

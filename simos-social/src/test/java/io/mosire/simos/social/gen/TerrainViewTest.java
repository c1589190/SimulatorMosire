package io.mosire.simos.social.gen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link TerrainView#of(GameMap)} 的真档护栏（第 10 条）：把三国格数 / 河边格数 / 沿海格数钉成**字面量**。
 *
 * <p>★ **放在 simos-social 而不是 simos-app**：social 模块**不能依赖 simos-app**（enforcer 拦），而这条用例考的是 {@code
 * TerrainView} 这个 social 侧的口子；真档资源经 {@link RealNations} 的相对路径读入、走 {@code MapCodec} 真读路径解码。
 *
 * <p>★ 三个数字是**当场从真档算出来的**（与用户给的一致）：德意志 430（河边 23 / 沿海 62）、奥斯特马克 138（0 / 10）、霍赫兰 231（0 / 0）； 全图河边格
 * 248、其中 230 格 riverEdgesAt ≥ 2。
 */
class TerrainViewTest {

  @Test
  void realNationsHexRiverAndCoastalCountsMatchTheFrozenFacts() {
    GameMap map = RealNations.map();
    TerrainView terrain = TerrainView.of(map);

    assertThat(counts(terrain, RealNations.DEUTSCHES_REICH))
        .as("德意志第二帝国：430 格 / 23 格沿河 / 62 格沿海")
        .containsExactly(430, 23, 62);
    assertThat(counts(terrain, RealNations.OSTERMARK))
        .as("奥斯特马克侯国：138 格 / 0 格沿河 / 10 格沿海")
        .containsExactly(138, 0, 10);
    assertThat(counts(terrain, RealNations.HOCHLAND))
        .as("霍赫兰伯国：231 格 / 0 格沿河 / 0 格沿海")
        .containsExactly(231, 0, 0);
  }

  @Test
  void navigableRiverHeuristicSweepsMostRiverHexes() {
    // ★ 诚实留痕：248 个河边格里 230 个 riverEdgesAt >= 2 ⇒ "可通航"启发式把绝大多数河边格判成干流。
    GameMap map = RealNations.map();
    TerrainView terrain = TerrainView.of(map);
    long riverHexes = 0;
    long navigable = 0;
    for (HexCoord hex : map.hexes().keySet()) {
      int edges = terrain.riverEdgesAt(hex);
      if (edges > 0) {
        riverHexes++;
      }
      if (edges >= 2) {
        navigable++;
      }
    }
    assertThat(riverHexes).as("全图河边格").isEqualTo(248L);
    assertThat(navigable).as("riverEdgesAt >= 2 的格（判为可通航）").isEqualTo(230L);
  }

  @Test
  void offMapHexIsRejectedInsteadOfSilentlyDefaulted() {
    TerrainView terrain = RealNations.terrainView();
    HexCoord offMap = new HexCoord(100_000, 100_000);
    assertThatThrownBy(() -> terrain.terrainKey(offMap))
        .as("越界格是调用方的错，抛而不静默")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("图里没有这个 hex");
    assertThatThrownBy(() -> terrain.moveCost(offMap)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> terrain.riverEdgesAt(offMap))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> terrain.coastal(offMap)).isInstanceOf(IllegalArgumentException.class);
  }

  /** {@code {格数, 沿河格数, 沿海格数}}。 */
  private static int[] counts(TerrainView terrain, String regionName) {
    Region region = RealNations.map().regions().get(new RegionId(regionName));
    Set<HexCoord> hexes = region.hexes();
    int river = 0;
    int coastal = 0;
    for (HexCoord hex : hexes) {
      if (terrain.riverEdgesAt(hex) > 0) {
        river++;
      }
      if (terrain.coastal(hex)) {
        coastal++;
      }
    }
    return new int[] {hexes.size(), river, coastal};
  }
}

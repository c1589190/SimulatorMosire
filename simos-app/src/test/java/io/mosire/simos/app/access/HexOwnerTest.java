package io.mosire.simos.app.access;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import org.junit.jupiter.api.Test;

/**
 * {@link HexOwner} 的用例（判据 **J6**）：hex → 归属国家。
 *
 * <p>★ **多对多是常态，不是边界情况**（M8-U1 用户裁定：「hex 只是地形块，应当兼容多种从属」）：一个格可以同时落在 若干个区域里，其中若干个带 {@code nation:}
 * tag ⇒ 归属是一个**集合**。写成"取第一个"或"假定唯一"是本类最可能的 缺陷形态，故有一条专门的用例钉它。
 *
 * <p>★ **期望值是字面量**（{@code "FRA"} / {@code "GER"}），不拿被测实现或它的同源工具生成。
 */
class HexOwnerTest {

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H03 = new HexCoord(0, 3);

  /** 单归属：落在唯一一个带 nation tag 的区域里 ⇒ 那个国家。 */
  @Test
  void aHexInsideOneNationRegionBelongsToThatNation() {
    GameMap map = ScopeFixtures.mapOf(ScopeFixtures.nationRegion("701", "FRA", H11, H12));

    assertThat(HexOwner.nationsOf(map, H11)).containsExactly("FRA");
    assertThat(HexOwner.nationsOf(map, H12)).containsExactly("FRA");
  }

  /** ★★ **多归属**：同一个格落在两个国家的区域里 ⇒ **两个都在**（M8-U1；写成"取第一个"在这里当场红）。 */
  @Test
  void aHexInsideTwoNationRegionsBelongsToBothNations() {
    GameMap map =
        ScopeFixtures.mapOf(
            ScopeFixtures.nationRegion("701", "FRA", H11, H12),
            ScopeFixtures.nationRegion("201", "GER", H12, H03));

    assertThat(HexOwner.nationsOf(map, H12))
        .as("★ 一个 hex 可同时属于多个国家区域（M8-U1 多对多）——不许假定唯一")
        .containsExactlyInAnyOrder("FRA", "GER");
    assertThat(HexOwner.nationsOf(map, H11)).as("只落在 FRA 那一块里的格仍只有一个归属").containsExactly("FRA");
  }

  /** 非国家 tag 与**为 null 的 tag** 都不贡献归属（{@code RegionMeta.tag} 可空，空元数据是合法状态）。 */
  @Test
  void tagsThatAreNotNationsContributeNoNation() {
    GameMap map =
        ScopeFixtures.mapOf(
            ScopeFixtures.nationRegion("701", "FRA", H11),
            ScopeFixtures.taggedRegion("801", "supply", H11),
            ScopeFixtures.taggedRegion("802", null, H11));

    assertThat(HexOwner.nationsOf(map, H11))
        .as("只有 nation: 前缀的 tag 算归属；supply / null 都不算")
        .containsExactly("FRA");
  }

  /** 无归属的格（不属于任何区域）⇒ **空集**，不是异常、也不是"回落到某个默认国家"。 */
  @Test
  void aHexWithoutRegionHasNoNation() {
    GameMap map = ScopeFixtures.mapOf(ScopeFixtures.nationRegion("701", "FRA", H11));

    assertThat(HexOwner.nationsOf(map, H03)).isEmpty();
  }

  /** 一个区域都没有的地图 ⇒ 空集（fail-closed 的退化形态）。 */
  @Test
  void aMapWithoutRegionsYieldsNoNation() {
    assertThat(HexOwner.nationsOf(ScopeFixtures.mapOf(), H11)).isEmpty();
  }

  /**
   * ★ 两个国家的 id 有前缀关系时**不许混淆**：{@code FRA} 与 {@code FRAX} 是两个国家。
   *
   * <p>把"本国"写成 {@code tag.startsWith("nation:FRA")} 会让 {@code nation:FRAX} 被读成 FRA——这条用例正是那个
   * 缺陷的靶子（与 {@code NationScope} 的"逐字相等"同一条口径）。
   */
  @Test
  void aNationIdThatIsAPrefixOfAnotherIsNotConfused() {
    GameMap map =
        ScopeFixtures.mapOf(
            ScopeFixtures.nationRegion("701", "FRA", H11),
            ScopeFixtures.nationRegion("801", "FRAX", H11));

    assertThat(HexOwner.nationsOf(map, H11)).containsExactlyInAnyOrder("FRA", "FRAX");
  }

  /** 结果是**有序**的（视图层直接落进 JSON ⇒ 无序集会让同一状态产出不同字节）。 */
  @Test
  void theResultIsSortedSoTheViewIsDeterministic() {
    GameMap map =
        ScopeFixtures.mapOf(
            ScopeFixtures.nationRegion("701", "FRA", H11),
            ScopeFixtures.nationRegion("801", "GER", H11),
            ScopeFixtures.nationRegion("802", "BEL", H11));

    assertThat(HexOwner.nationsOf(map, H11)).containsExactly("BEL", "FRA", "GER");
  }
}

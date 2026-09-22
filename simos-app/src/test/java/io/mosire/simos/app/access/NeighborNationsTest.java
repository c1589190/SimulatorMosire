package io.mosire.simos.app.access;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.id.NationId;
import org.junit.jupiter.api.Test;

/**
 * {@link NeighborNations} 的用例（判据 **J5**）：本国 → 邻国集合。
 *
 * <p>★ 口径（spec §3.4）：「与本国任一区域**六角邻接**的、属于**其他国家**的区域 ⇒ 取那些区域的 {@code nation:} tag」。
 * 三条容易写歪的地方各有一条用例：
 *
 * <ol>
 *   <li>**本国不在结果里**（"邻国"不含自己）；
 *   <li>**邻接是逐 hex 的六邻**，不是"距离近"或"包围盒相交"——隔一环的国家不算邻国；
 *   <li>**只有 {@code nation:} tag 算国家**：{@code supply} / {@code null} 这些别的 tag 都不进结果。
 * </ol>
 *
 * <p>★ 期望值是字面量；夹具用 {@link ScopeFixtures} 的半径 2 世界（中心 {@code (1,1)}，19 格）。
 */
class NeighborNationsTest {

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);
  private static final HexCoord H21 = new HexCoord(2, 1);
  private static final HexCoord H01 = new HexCoord(0, 1);
  private static final HexCoord H02 = new HexCoord(0, 2);
  private static final HexCoord H31 = new HexCoord(3, 1);

  /**
   * ★★ 主干：FRA 的 {@code (1,2)} 与 GER 的 {@code (1,3)} 六角相邻 ⇒ GER 是邻国；BEL 在 {@code (3,1)}
   * （隔了一环）不是；{@code supply} 与 {@code null} tag 的区域虽然贴着 FRA 也不算国家；FRA **自己的第二块区域**不算邻国。
   */
  @Test
  void neighboursAreTheOtherNationsWhoseRegionsShareAnEdgeWithOurs() {
    GameMap map =
        ScopeFixtures.mapOf(
            ScopeFixtures.nationRegion("701", "FRA", H11, H12),
            ScopeFixtures.nationRegion("702", "FRA", H01), // 本国第二块：贴着 701，但**本国不算邻国**
            ScopeFixtures.nationRegion("201", "GER", H13), // 与 (1,2) 六角相邻 ⇒ 邻国
            ScopeFixtures.nationRegion("301", "BEL", H31), // 隔一环 ⇒ 不是邻国
            ScopeFixtures.taggedRegion("801", "supply", H02), // 贴着本国，但不是国家 tag
            ScopeFixtures.taggedRegion("802", null, H21)); // 同上，tag 为空

    assertThat(NeighborNations.of(map, new NationId("FRA")))
        .as("只剩 GER：本国、隔环的国家、非国家 tag 的相邻区域都不进结果")
        .containsExactly("GER");
  }

  /** ★ 邻接是**互相**的：同一条边界从 GER 那侧看出去也成立。 */
  @Test
  void adjacencyIsMutual() {
    GameMap map =
        ScopeFixtures.mapOf(
            ScopeFixtures.nationRegion("701", "FRA", H11, H12),
            ScopeFixtures.nationRegion("201", "GER", H13));

    assertThat(NeighborNations.of(map, new NationId("GER"))).containsExactly("FRA");
  }

  /** 同一个国家有多块区域分别邻接 ⇒ **去重后只出现一次**。 */
  @Test
  void theSameNationIsReportedOnceEvenWhenTwoOfItsRegionsTouch() {
    GameMap map =
        ScopeFixtures.mapOf(
            ScopeFixtures.nationRegion("701", "FRA", H11),
            ScopeFixtures.nationRegion("201", "GER", H12), // 贴着 (1,1)
            ScopeFixtures.nationRegion("202", "GER", H21), // 也贴着 (1,1)
            ScopeFixtures.nationRegion("203", "GER", H01)); // 也贴着 (1,1)

    assertThat(NeighborNations.of(map, new NationId("FRA"))).containsExactly("GER");
  }

  /**
   * ★ 国家 id 有**前缀关系**时不许混淆：{@code nation:FRAX} 不是 {@code nation:FRA}。
   *
   * <p>把"本国"写成 {@code ownTag.startsWith("nation:FRA")} 会把 FRAX 读成本国 ⇒ 它从结果里**消失**（而不是多出来）——
   * 这条用例正是那个方向的靶子。
   */
  @Test
  void aNationIdThatIsAPrefixOfAnotherIsNotSwallowedAsOurOwn() {
    GameMap map =
        ScopeFixtures.mapOf(
            ScopeFixtures.nationRegion("701", "FRA", H11),
            ScopeFixtures.nationRegion("801", "FRAX", H12));

    assertThat(NeighborNations.of(map, new NationId("FRA"))).containsExactly("FRAX");
  }

  /** 本国一块区域都没有（还没圈地 / 区域被删）⇒ **空集**，不是异常。 */
  @Test
  void aNationWithoutRegionsHasNoNeighbours() {
    GameMap map =
        ScopeFixtures.mapOf(
            ScopeFixtures.nationRegion("701", "FRA", H11),
            ScopeFixtures.nationRegion("201", "GER", H12));

    assertThat(NeighborNations.of(map, new NationId("NOPE"))).isEmpty();
  }

  /** 结果是**有序**的（视图层直接落 JSON ⇒ 无序集会让同一状态产出不同字节）。 */
  @Test
  void theResultIsSortedSoTheViewIsDeterministic() {
    GameMap map =
        ScopeFixtures.mapOf(
            ScopeFixtures.nationRegion("701", "FRA", H11),
            ScopeFixtures.nationRegion("201", "GER", H12),
            ScopeFixtures.nationRegion("202", "BEL", H21),
            ScopeFixtures.nationRegion("203", "AUT", H01));

    assertThat(NeighborNations.of(map, new NationId("FRA"))).containsExactly("AUT", "BEL", "GER");
  }
}

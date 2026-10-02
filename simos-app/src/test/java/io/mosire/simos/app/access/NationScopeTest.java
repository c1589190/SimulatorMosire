package io.mosire.simos.app.access;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import org.junit.jupiter.api.Test;

/**
 * Task 3 判据（spec §3.2 / J4）：**国家决策人**的可见范围 = 本国区域（{@code region.meta().tag() == "nation:<id>"}）。
 *
 * <p>★ 三条硬要求，每一条都有对立面用例：① 只收**自己**那个国家（不是"扫到谁算谁"）；② 无匹配 ⇒ **显式 deny-all**（不是空图 = "不表态"，那会静默回落成放行）；③
 * tag 必须**逐字相等**（不是前缀/子串匹配）。
 */
class NationScopeTest {

  private static final SimulationState STATE =
      ScopeFixtures.state(
          ScopeFixtures.mapOf(
              ScopeFixtures.nationRegion("701", "FRA", new HexCoord(1, 1)),
              ScopeFixtures.nationRegion("201", "GER", new HexCoord(1, 2)),
              // ★ 反例夹具：FRAX 不是 FRA（前缀匹配会把它收进来）；tag 为 null 的区域是合法数据
              ScopeFixtures.nationRegion("702", "FRAX", new HexCoord(2, 1)),
              ScopeFixtures.taggedRegion("900", null, new HexCoord(2, 2))),
          ScopeFixtures.units(),
          ScopeFixtures.sdWithArmy("a1", "FRA", "u-1"));

  private static ResourceScopeMap scopesFor(String nationId) {
    return DecisionScopeFunctions.defaults()
        .scopesFor(ScopeFixtures.nationDm("dm-" + nationId, nationId), STATE, ScopeFixtures.MAP_ID);
  }

  @Test
  void nationScopeCoversItsOwnRegionsAndNothingElse() {
    ResourceScopeMap scopes = scopesFor("FRA");

    assertThat(ScopeFixtures.prefixes(scopes))
        .as("FRA 的范围只有 701（GER 的 201 与别的 tag 都不在内）")
        .containsExactly("demo/region/701");
  }

  @Test
  void theOtherNationSeesTheOtherRegion() {
    assertThat(ScopeFixtures.prefixes(scopesFor("GER"))).containsExactly("demo/region/201");
  }

  @Test
  void scopeAllowsItsOwnRegionAndRefusesTheOtherNation() {
    ResourceScope mapScope = scopesFor("FRA").declaredScope("map");

    assertThat(mapScope.unrestricted()).as("受限范围不得退化成 unlimited()").isFalse();
    assertThat(mapScope.allows("demo/region/701")).isTrue();
    assertThat(mapScope.allows("demo/region/201")).as("GER 的区域够不着").isFalse();
    assertThat(mapScope.allows("demo/hex/1_1")).as("区级前缀不放大到任意 hex").isFalse();
  }

  @Test
  void theTagMustMatchExactlyNotAsAPrefix() {
    assertThat(ScopeFixtures.prefixes(scopesFor("FRA")))
        .as("正向锚点：自己那条必须在（否则'不含 702'在空范围下恒真）")
        .contains("demo/region/701")
        .as("'nation:FRAX' 的国家不是 FRA —— 前缀匹配会把 702 误收进来")
        .doesNotContain("demo/region/702");
  }

  @Test
  void aNullTagRegionIsNotAnyonesTerritory() {
    assertThat(ScopeFixtures.prefixes(scopesFor("FRA")))
        .as("正向锚点：自己那条必须在")
        .contains("demo/region/701")
        .doesNotContain("demo/region/900");
  }

  /** ★ spec §5.2 第 3 条："够不着"必须显式写 {@code none()}；空图是"本层不表态" ⇒ 会回落成放行。 */
  @Test
  void noMatchingRegionIsAnExplicitDenyAllNotAnEmptyMap() {
    ResourceScopeMap scopes = scopesFor("XXX");

    assertThat(scopes.namespaces())
        .as("★ 三个命名空间都要**表过态**（T10 起 unit/social 也配了前缀：不表态 ⇒ 回落到工具缺省 ⇒ 静默全放行）")
        .containsExactlyInAnyOrder("map", "social", "unit", "actor");
    assertThat(scopes.declaredScope("map")).isEqualTo(ResourceScope.none());
    assertThat(scopes.declaredScope("map").allows("demo/region/701")).isFalse();
    assertThat(scopes.declaredScope("unit")).as("没圈地就没单位").isEqualTo(ResourceScope.none());
    assertThat(scopes.declaredScope("social")).as("没圈地就没人口").isEqualTo(ResourceScope.none());
  }

  /**
   * ★ **T10 的细粒度化**（spec §3.2）：{@code unit} 命名空间 = **按单位位置落在本国区域内算**， {@code social} 命名空间 =
   * **本国区域内的 hex 前缀**（人口按 hex 取）。
   *
   * <p>★ 判别力靠**三类都在场**：本国单位（进）、别国单位（不进）、**不知道在哪**的单位（不进）—— 只放"本国单位"一条会把"全放行"与"正确"都判成绿。
   */
  @Test
  void theUnitNamespaceCoversOnlyUnitsStandingInsideItsOwnRegions() {
    SimulationState state =
        ScopeFixtures.state(
            ScopeFixtures.mapOf(
                ScopeFixtures.nationRegion("701", "FRA", new HexCoord(1, 1)),
                ScopeFixtures.nationRegion("201", "GER", new HexCoord(1, 2))),
            ScopeFixtures.units(
                ScopeFixtures.unit("u-1", new HexCoord(1, 1)),
                ScopeFixtures.unit("u-2", new HexCoord(1, 2)),
                ScopeFixtures.positionlessUnit("u-3")),
            ScopeFixtures.sdWithArmy("a1", "FRA", "u-1"));

    ResourceScopeMap scopes =
        DecisionScopeFunctions.defaults()
            .scopesFor(ScopeFixtures.nationDm("dm-FRA", "FRA"), state, ScopeFixtures.MAP_ID);

    ResourceScope unitScope = scopes.declaredScope("unit");
    assertThat(unitScope.unrestricted()).as("受限范围不得退化成 unlimited()").isFalse();
    assertThat(unitScope.allows("u-1")).as("站在本国区域 701 里").isTrue();
    assertThat(unitScope.allows("u-2")).as("站在 GER 的 201 里").isFalse();
    assertThat(unitScope.allows("u-3")).as("不知道在哪 ⇒ 看不见（fail-closed）").isFalse();
  }

  /** ★ {@code social} 命名空间：本国区域内的 hex ⇒ 前缀 {@code <q>_<r>}（spec §3.3 的 social 路径语法）。 */
  @Test
  void theSocialNamespaceCoversTheHexesOfItsOwnRegionsOnly() {
    SimulationState state =
        ScopeFixtures.state(
            ScopeFixtures.mapOf(
                ScopeFixtures.nationRegion("701", "FRA", new HexCoord(1, 1), new HexCoord(2, 1)),
                ScopeFixtures.nationRegion("201", "GER", new HexCoord(1, 2))),
            ScopeFixtures.units(),
            ScopeFixtures.sdWithArmy("a1", "FRA", "u-1"));

    ResourceScopeMap scopes =
        DecisionScopeFunctions.defaults()
            .scopesFor(ScopeFixtures.nationDm("dm-FRA", "FRA"), state, ScopeFixtures.MAP_ID);

    ResourceScope socialScope = scopes.declaredScope("social");
    assertThat(socialScope.unrestricted()).isFalse();
    assertThat(socialScope.allows("1_1")).as("701 覆盖的格").isTrue();
    assertThat(socialScope.allows("2_1")).as("701 覆盖的格").isTrue();
    assertThat(socialScope.allows("1_2")).as("GER 的 201 覆盖的格").isFalse();
  }

  @Test
  void aNationWhoseRegionsAllDisappearedSeesNothing() {
    SimulationState bare =
        ScopeFixtures.state(
            ScopeFixtures.mapOf(ScopeFixtures.nationRegion("201", "GER", new HexCoord(1, 2))),
            ScopeFixtures.units(),
            ScopeFixtures.sdWithArmy("a1", "FRA", "u-1"));

    ResourceScopeMap scopes =
        DecisionScopeFunctions.defaults()
            .scopesFor(ScopeFixtures.nationDm("dm-fra", "FRA"), bare, ScopeFixtures.MAP_ID);

    assertThat(scopes.declaredScope("map")).isEqualTo(ResourceScope.none());
  }
}

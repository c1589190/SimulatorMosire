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

    assertThat(scopes.namespaces()).as("表过态（不是空图）").containsExactly("map");
    assertThat(scopes.declaredScope("map")).isEqualTo(ResourceScope.none());
    assertThat(scopes.declaredScope("map").allows("demo/region/701")).isFalse();
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

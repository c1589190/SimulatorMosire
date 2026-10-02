package io.mosire.simos.app.access;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import org.junit.jupiter.api.Test;

/**
 * Task 4 判据（spec §3.2 / J4）：**军队决策人**的可见范围 = 军队当前位置 + 视野半径的六角球。
 *
 * <p>★ 四条硬要求：① 半径语义逐格精确（R=1 ⇒ 自身 + 6 邻，不多不少）；② 位置**走 {@code
 * UnitState.effectivePosition}**（与既有同口径）而不是"单位自己的 position 字段"——编队里根单位自身无位置时两者**分叉**； ③
 * **纯半径**，不看地形（用户裁定⑥）；④ 世界状态变则范围变（J4）。
 */
class ArmyScopeTest {

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H22 = new HexCoord(2, 2);
  private static final HexCoord H33 = new HexCoord(3, 3);

  private static SimulationState stateWith(Unit root, Unit... others) {
    GameMap map = ScopeFixtures.mapOf(ScopeFixtures.nationRegion("701", "FRA", H11));
    return ScopeFixtures.state(
        map,
        ScopeFixtures.units(root, others),
        ScopeFixtures.sdWithArmy("a1", "FRA", root.id().value()));
  }

  private static ResourceScopeMap scopesOf(SimulationState state) {
    return DecisionScopeFunctions.defaults()
        .scopesFor(ScopeFixtures.armyDm("dm-a1", "a1"), state, ScopeFixtures.MAP_ID);
  }

  @Test
  void radiusOneSeesItselfPlusTheSixNeighbours() {
    ResourceScopeMap scopes = scopesOf(stateWith(ScopeFixtures.unitWithVision("u-1", H11, 1)));

    assertThat(ScopeFixtures.prefixes(scopes))
        .as("(1,1) 起半径 1 的六角球 = 7 格")
        .containsExactlyInAnyOrder(
            "demo/hex/1_1",
            "demo/hex/2_1",
            "demo/hex/2_0",
            "demo/hex/1_0",
            "demo/hex/0_1",
            "demo/hex/0_2",
            "demo/hex/1_2");
  }

  @Test
  void hexesOutsideTheRadiusAreNotAllowed() {
    ResourceScope mapScope =
        scopesOf(stateWith(ScopeFixtures.unitWithVision("u-1", H11, 1))).declaredScope("map");

    assertThat(mapScope.unrestricted()).isFalse();
    assertThat(mapScope.allows("demo/hex/1_1")).isTrue();
    assertThat(mapScope.allows("demo/hex/2_1")).as("距离 1 ⇒ 在圈内").isTrue();
    assertThat(mapScope.allows("demo/hex/2_2")).as("距离 2 ⇒ 圈外").isFalse();
    assertThat(mapScope.allows("demo/hex/3_3")).as("距离 4 ⇒ 圈外").isFalse();
    assertThat(mapScope.allows("demo/region/701")).as("军队范围是逐格给的，区域级资源不在其中").isFalse();
  }

  @Test
  void radiusZeroSeesOnlyItsOwnHex() {
    ResourceScopeMap scopes = scopesOf(stateWith(ScopeFixtures.unitWithVision("u-1", H11, 0)));

    assertThat(ScopeFixtures.prefixes(scopes))
        .as("R=0 ⇒ 只看自身格（半径是输入，不是写死的 1 或 2）")
        .containsExactly("demo/hex/1_1");
  }

  @Test
  void radiusTwoGrowsTheBallToNineteenHexes() {
    ResourceScopeMap scopes = scopesOf(stateWith(ScopeFixtures.unitWithVision("u-1", H11, 2)));

    assertThat(ScopeFixtures.prefixes(scopes))
        .as("R=2 ⇒ 3R²+3R+1 = 19 格")
        .hasSize(19)
        .contains("demo/hex/2_2", "demo/hex/3_0", "demo/hex/3_1")
        .doesNotContain("demo/hex/3_2", "demo/hex/3_3");
  }

  /** ★ J4：同一决策人、同一世界**除军队位置外**，范围必须随**现算**变化（缓存/落盘即失效）。 */
  @Test
  void movingTheArmyChangesTheVisibleSet() {
    DecisionMaker dm = ScopeFixtures.armyDm("dm-a1", "a1");
    DecisionScopeFunctions functions = DecisionScopeFunctions.defaults();

    ResourceScopeMap before =
        functions.scopesFor(
            dm, stateWith(ScopeFixtures.unitWithVision("u-1", H11, 1)), ScopeFixtures.MAP_ID);
    ResourceScopeMap after =
        functions.scopesFor(
            dm, stateWith(ScopeFixtures.unitWithVision("u-1", H22, 1)), ScopeFixtures.MAP_ID);

    assertThat(ScopeFixtures.prefixes(before))
        .as("(1,1) 时看得见原格、看不见 (2,2)")
        .contains("demo/hex/1_1")
        .doesNotContain("demo/hex/2_2");
    assertThat(ScopeFixtures.prefixes(after))
        .as("(2,2) 时看得见新格、看不见原格")
        .contains("demo/hex/2_2")
        .doesNotContain("demo/hex/1_1");
    assertThat(ScopeFixtures.prefixes(after)).isNotEqualTo(ScopeFixtures.prefixes(before));
  }

  /**
   * ★★ 判据（**编制 v2 取代了"位置继承"**）：军队根单位**没有自己的位置** ⇒ 它不在图上 ⇒ 视野**空**（fail-closed）。
   *
   * <p>★ 旧语义下这里会"向父取位"给出 7 格；v2 起位置是各单位自己的 ⇒ 根单位没有位置就是看不见任何格。
   */
  @Test
  void anArmyWhoseRootUnitHasNoPositionSeesNothing() {
    Unit top = ScopeFixtures.withVision(ScopeFixtures.unit("u-top", H11), 3);
    Unit child = ScopeFixtures.attachedChild("u-child", "u-top");
    assertThat(child.position().valueAt(ScopeFixtures.T0)).as("夹具前提：根单位自身没有位置").isEmpty();

    assertThat(ScopeFixtures.prefixes(scopesOf(stateWith(child, top))))
        .as("v2：位置不是继承来的 ⇒ 没有位置就是一片都看不见（fail-closed，不是「看得见父的格」）")
        .isEmpty();
  }

  /** ★ 判据：军队根单位**自带位置** ⇒ 视野以它为中心，且半径取**根单位**自己的（同格另一单位半径更大也不影响）。 */
  @Test
  void armyVisionComesFromTheRootUnitsOwnPositionAndRadius() {
    Unit root = ScopeFixtures.unitWithVision("u-child", H11, 1);
    Unit biggerNeighbour = ScopeFixtures.unitWithVision("u-top", H11, 3);

    assertThat(ScopeFixtures.prefixes(scopesOf(stateWith(root, biggerNeighbour))))
        .as("位置取根自己的 (1,1)、半径取根单位的 1（同格那个半径 3 的**不影响**）")
        .hasSize(7)
        .contains("demo/hex/1_1", "demo/hex/2_1")
        .doesNotContain("demo/hex/3_3");
  }

  @Test
  void anUnknownArmyIsAnExplicitDenyAll() {
    SimulationState state =
        ScopeFixtures.state(
            ScopeFixtures.mapOf(ScopeFixtures.nationRegion("701", "FRA", H11)),
            ScopeFixtures.units(ScopeFixtures.unitWithVision("u-1", H11, 1)),
            ScopeFixtures.sdWithArmy("a1", "FRA", "u-1"));

    ResourceScopeMap scopes =
        DecisionScopeFunctions.defaults()
            .scopesFor(ScopeFixtures.armyDm("dm-ghost", "ghost"), state, ScopeFixtures.MAP_ID);

    assertThat(scopes.namespaces())
        .as("★ 三个命名空间都要**表过态**（T10 起 unit/social 也配了前缀：不表态 ⇒ 回落到工具缺省 ⇒ 静默全放行）")
        .containsExactlyInAnyOrder("map", "social", "unit", "actor");
    assertThat(scopes.declaredScope("map")).isEqualTo(ResourceScope.none());
    assertThat(scopes.declaredScope("unit")).isEqualTo(ResourceScope.none());
    assertThat(scopes.declaredScope("social")).isEqualTo(ResourceScope.none());
  }

  /**
   * ★ **T10 的细粒度化**：{@code unit} = 圈内的单位（与 {@code map} **同一套格**口径：位置落在圈内即可见）； {@code social} = 圈内格的
   * hex 前缀（人口按 hex 取）。
   *
   * <p>★ 判别力：圈内（u-1 自身、u-2 邻格）进、圈外（u-3 距离 2）不进——单一"全放行"实现会把后者也放进来。
   */
  @Test
  void theUnitAndSocialNamespacesCoverTheVisionCircle() {
    UnitState units =
        ScopeFixtures.units(
            ScopeFixtures.unitWithVision("u-1", H11, 1),
            ScopeFixtures.unitWithVision("u-2", new HexCoord(1, 2), 1),
            ScopeFixtures.unitWithVision("u-3", H33, 1));
    SimulationState state =
        ScopeFixtures.state(
            ScopeFixtures.mapOf(ScopeFixtures.nationRegion("701", "FRA", H11)),
            units,
            ScopeFixtures.sdWithArmy("a1", "FRA", "u-1"));

    ResourceScopeMap scopes = scopesOf(state);

    ResourceScope unitScope = scopes.declaredScope("unit");
    assertThat(unitScope.unrestricted()).isFalse();
    assertThat(unitScope.allows("u-1")).as("军队自己（圈心）").isTrue();
    assertThat(unitScope.allows("u-2")).as("圈内邻格上的单位").isTrue();
    assertThat(unitScope.allows("u-3")).as("距离 4 ⇒ 圈外").isFalse();

    ResourceScope socialScope = scopes.declaredScope("social");
    assertThat(socialScope.allows("1_1")).as("圈心格").isTrue();
    assertThat(socialScope.allows("1_2")).as("邻格").isTrue();
    assertThat(socialScope.allows("3_3")).as("圈外格").isFalse();
  }

  @Test
  void anArmyOnAPositionlessUnitSeesNothing() {
    ResourceScopeMap scopes = scopesOf(stateWith(ScopeFixtures.positionlessUnit("u-nowhere")));

    assertThat(scopes.declaredScope("map")).isEqualTo(ResourceScope.none());
  }

  /** 归属 → 军队 → **单位根**这条链必须真的走通：不同军队指向不同单位 ⇒ 范围不同（且互不重叠）。 */
  @Test
  void twoArmiesPointingAtDifferentUnitsSeeDifferentHexes() {
    UnitState units =
        ScopeFixtures.units(
            ScopeFixtures.unitWithVision("u-1", H11, 1),
            ScopeFixtures.unitWithVision("u-2", H22, 1));
    GameMap map = ScopeFixtures.mapOf(ScopeFixtures.nationRegion("701", "FRA", H11));
    DecisionScopeFunctions functions = DecisionScopeFunctions.defaults();

    SimulationState first =
        ScopeFixtures.state(map, units, ScopeFixtures.sdWithArmy("a1", "FRA", "u-1"));
    SimulationState second =
        ScopeFixtures.state(map, units, ScopeFixtures.sdWithArmy("a2", "FRA", "u-2"));

    assertThat(
            ScopeFixtures.prefixes(
                functions.scopesFor(
                    ScopeFixtures.armyDm("dm-a1", "a1"), first, ScopeFixtures.MAP_ID)))
        .contains("demo/hex/1_1")
        .doesNotContain("demo/hex/2_2");
    assertThat(
            ScopeFixtures.prefixes(
                functions.scopesFor(
                    ScopeFixtures.armyDm("dm-a2", "a2"), second, ScopeFixtures.MAP_ID)))
        .contains("demo/hex/2_2")
        .doesNotContain("demo/hex/1_1");
  }
}

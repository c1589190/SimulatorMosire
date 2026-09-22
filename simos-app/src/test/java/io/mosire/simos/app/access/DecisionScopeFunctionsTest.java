package io.mosire.simos.app.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.util.state.SimulationState;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Task 3 判据（spec §3.1）：注册表**按 {@code Affiliation} 的运行时类型**分派，**未注册的类型响亮失败**。
 *
 * <p>★ 后一条是本阶段要消灭的形态本身：权限函数"算不出来"时**静默给全量** = 把失败伪装成放行。 所以这里断言的是**抛异常**，不是"返回空范围"、更不是"返回
 * unlimited"。
 */
class DecisionScopeFunctionsTest {

  private static final SimulationState STATE =
      ScopeFixtures.state(
          ScopeFixtures.mapOf(ScopeFixtures.nationRegion("701", "FRA", new HexCoord(1, 1))),
          ScopeFixtures.units(ScopeFixtures.unitWithVision("u-1", new HexCoord(1, 1), 1)),
          ScopeFixtures.sdWithArmy("a1", "FRA", "u-1"));

  @Test
  void dispatchIsByTheRuntimeTypeOfTheAffiliation() {
    DecisionScopeFunctions functions = DecisionScopeFunctions.defaults();

    ResourceScope nationScope =
        functions
            .scopesFor(ScopeFixtures.nationDm("dm-fra", "FRA"), STATE, ScopeFixtures.MAP_ID)
            .declaredScope("map");
    ResourceScope armyScope =
        functions
            .scopesFor(ScopeFixtures.armyDm("dm-a1", "a1"), STATE, ScopeFixtures.MAP_ID)
            .declaredScope("map");

    assertThat(nationScope.allows("demo/region/701")).as("国家归属 ⇒ 区域级前缀").isTrue();
    assertThat(nationScope.allows("demo/hex/1_1")).as("国家范围不是逐格给的").isFalse();
    assertThat(armyScope.allows("demo/hex/1_1")).as("军队归属 ⇒ 逐格前缀").isTrue();
    assertThat(armyScope.allows("demo/region/701")).as("军队范围不是区域级给的").isFalse();
  }

  @Test
  void anUnregisteredAffiliationTypeFailsLoudly() {
    DecisionScopeFunctions onlyNations =
        new DecisionScopeFunctions(Map.of(Affiliation.Nation.class, NationScope.INSTANCE));

    assertThatThrownBy(
            () ->
                onlyNations.scopesFor(
                    ScopeFixtures.armyDm("dm-a1", "a1"), STATE, ScopeFixtures.MAP_ID))
        .as("未注册 ⇒ 抛，绝不静默给全量")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Army");
  }

  @Test
  void anEmptyRegistryFailsLoudlyForEveryAffiliation() {
    DecisionScopeFunctions none = new DecisionScopeFunctions(Map.of());

    assertThatThrownBy(
            () ->
                none.scopesFor(
                    ScopeFixtures.nationDm("dm-fra", "FRA"), STATE, ScopeFixtures.MAP_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Nation");
  }

  /** ★ spec §3.1 的"可扩展"：新增一类范围 = 新增一个实现 + 注册一行，**不改调用点**。 */
  @Test
  void aCustomFunctionCanBeRegisteredWithoutTouchingTheCallSite() {
    DecisionScopeFunction custom =
        (dm, state, mapId) -> ResourceScopeMap.of("map", ResourceScope.of("demo/region/custom"));
    DecisionScopeFunctions functions =
        new DecisionScopeFunctions(
            Map.of(Affiliation.Nation.class, custom, Affiliation.Army.class, ArmyScope.INSTANCE));

    assertThat(
            ScopeFixtures.prefixes(
                functions.scopesFor(
                    ScopeFixtures.nationDm("dm-fra", "FRA"), STATE, ScopeFixtures.MAP_ID)))
        .as("注册表用的是注册表里的那份，不是硬编码的 instanceof")
        .containsExactly("demo/region/custom");
  }

  /** 分派读的是 {@code affiliation}：同一个决策人换成别的归属 ⇒ 换一份范围（范围函数不看别的字段）。 */
  @Test
  void theScopeFollowsTheAffiliationOnly() {
    DecisionScopeFunctions functions = DecisionScopeFunctions.defaults();

    assertThat(
            ScopeFixtures.prefixes(
                functions.scopesFor(
                    ScopeFixtures.nationDm("dm-x", "FRA"), STATE, ScopeFixtures.MAP_ID)))
        .isNotEqualTo(
            ScopeFixtures.prefixes(
                functions.scopesFor(
                    ScopeFixtures.armyDm("dm-x", "a1"), STATE, ScopeFixtures.MAP_ID)));
  }

  @Test
  void theMapIdIsPartOfEveryPrefix() {
    DecisionScopeFunctions functions = DecisionScopeFunctions.defaults();

    ResourceScope scope =
        functions
            .scopesFor(ScopeFixtures.armyDm("dm-a1", "a1"), STATE, "other-map")
            .declaredScope("map");

    assertThat(scope.allows("other-map/hex/1_1")).isTrue();
    assertThat(scope.allows("demo/hex/1_1")).as("别的 mapId 的资源够不着").isFalse();
  }

  /** 夹具自证：范围里的 hex 前缀确实来自那个单位（防"夹具没搭好、断言恒真"）。 */
  @Test
  void theFixtureUnitIsTheOneTheScopeIsBuiltFrom() {
    Unit unit = ScopeFixtures.unitWithVision("u-1", new HexCoord(1, 1), 1);

    assertThat(unit.visionRadius()).isEqualTo(1);
    assertThat(unit.position().valueAt(ScopeFixtures.T0)).contains(new HexCoord(1, 1));
  }
}

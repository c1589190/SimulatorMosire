package io.mosire.simos.app.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.army.ArmyAffiliations;
import io.mosire.simos.gov.nation.NationAffiliations;
import io.mosire.simos.map.hex.HexCoord;
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

  /**
   * ★★ <b>先建决策人、再建世界</b>（2026-10-13 收尾测试第 2 层）：新契约下归属住在{@code gov}/{@code army} 的表里、按 dmId
   * 索引 ⇒ {@link ScopeFixtures#state} 必须<b>拿到 dm</b> 才能把那一行写进去。求值顺序由 {@code static final} 的声明序保证。
   */
  private static final ScopeFixtures.Affiliated DM_FRA = ScopeFixtures.nationDm("dm-fra", "FRA");

  private static final ScopeFixtures.Affiliated DM_ARMY = ScopeFixtures.armyDm("dm-a1", "a1");

  private static final SimulationState STATE =
      ScopeFixtures.state(
          ScopeFixtures.mapOf(ScopeFixtures.nationRegion("701", "FRA", new HexCoord(1, 1))),
          ScopeFixtures.units(ScopeFixtures.unitWithVision("u-1", new HexCoord(1, 1), 1)),
          ScopeFixtures.sdWithArmy("a1", "FRA", "u-1"),
          DM_FRA,
          DM_ARMY);

  @Test
  void dispatchIsByTheRuntimeTypeOfTheAffiliation() {
    DecisionScopeFunctions functions = DecisionScopeFunctions.defaults();

    ResourceScope nationScope =
        functions
            .scopesFor(DM_FRA.maker(), STATE, ScopeFixtures.MAP_ID)
            .declaredScope("map");
    ResourceScope armyScope =
        functions
            .scopesFor(DM_ARMY.maker(), STATE, ScopeFixtures.MAP_ID)
            .declaredScope("map");

    assertThat(nationScope.allows("demo/region/701")).as("国家归属 ⇒ 区域级前缀").isTrue();
    assertThat(nationScope.allows("demo/hex/1_1")).as("国家范围不是逐格给的").isFalse();
    assertThat(armyScope.allows("demo/hex/1_1")).as("军队归属 ⇒ 逐格前缀").isTrue();
    assertThat(armyScope.allows("demo/region/701")).as("军队范围不是区域级给的").isFalse();
  }

  @Test
  void anUnregisteredAffiliationTypeFailsLoudly() {
    DecisionScopeFunctions onlyNations =
        new DecisionScopeFunctions(Map.of(NationAffiliations.KIND, NationScope.INSTANCE));

    assertThatThrownBy(
            () ->
                onlyNations.scopesFor(
                    DM_ARMY.maker(), STATE, ScopeFixtures.MAP_ID))
        .as("未注册 ⇒ 抛，绝不静默给全量")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Army");
  }

  /**
   * ★ spec §3.1 的"可扩展"：新增一类范围 = 新增一个实现 + 注册一行，**不改调用点**。
   */
  @Test
  void anEmptyRegistryFailsLoudlyAtConstruction() {
    // ★★ 2026-10-13 收尾测试第 2 层：生产契约把"空注册表"的响亮失败**前移到构造期**
    //   （DecisionScopeFunctions:68：「空表 = 任何决策人都算不出范围（装配故障，绝不静默）」）。
    //   本条的判据一字未变 —— 仍是「空注册表 ⇒ 响亮失败，绝不静默给全量/静默 deny-all」，只是它现在
    //   在更早、更不可能被绕过的那一点上生效（原先的写法会先撞构造期异常 ⇒ 断言根本走不到 scopesFor）。
    assertThatThrownBy(() -> new DecisionScopeFunctions(Map.of()))
        .as("空注册表 = 装配故障 ⇒ 当场抛（不是"谁都算不出范围"这种静默半死状态）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为空");
  }

  /** ★ spec §3.1 的"可扩展"：新增一类范围 = 新增一个实现 + 注册一行，**不改调用点**。 */
  @Test
  void aCustomFunctionCanBeRegisteredWithoutTouchingTheCallSite() {
    DecisionScopeFunction custom =
        (dm, state, mapId) -> ResourceScopeMap.of("map", ResourceScope.of("demo/region/custom"));
    DecisionScopeFunctions functions =
        new DecisionScopeFunctions(
            Map.of(NationAffiliations.KIND, custom, ArmyAffiliations.KIND, ArmyScope.INSTANCE));

    assertThat(
            ScopeFixtures.prefixes(
                functions.scopesFor(DM_FRA.maker(), STATE, ScopeFixtures.MAP_ID)))
        .as("注册表用的是注册表里的那份，不是硬编码的 instanceof")
        .containsExactly("demo/region/custom");
  }

  /**
   * ★ 分派读的是<b>世界表里那一行归属</b>：<b>同一个决策人 id</b> 在两张不同的表里 ⇒ 换一份范围（范围函数不看别的字段）。
   *
   * <p>★★ <b>2026-10-13 收尾测试第 2 层</b>：旧写法把"同一个决策人换归属"表达成"同一个 {@code DecisionMaker} 值配两种
   * {@code Affiliation}"—— 那个字段已删（乙收尾）。新契约下归属住在世界的表里、按 dmId 索引 ⇒ 同一 dm id 只能靠
   * <b>两张不同的世界</b>来表达（同一张世界里它就是多轴并集，语义不同）。★ 判据强度不变：两个 {@code DecisionMaker} 值
   * <b>逐字段相同</b>（同 id、同工具集、同 limit、同世代），世界除归属行外也逐字段相同 ⇒ 范围不同只可能来自归属。
   */
  @Test
  void theScopeFollowsTheAffiliationOnly() {
    DecisionScopeFunctions functions = DecisionScopeFunctions.defaults();
    ScopeFixtures.Affiliated asNation = ScopeFixtures.nationDm("dm-x", "FRA");
    ScopeFixtures.Affiliated asArmy = ScopeFixtures.armyDm("dm-x", "a1");
    GameMap map = ScopeFixtures.mapOf(ScopeFixtures.nationRegion("701", "FRA", new HexCoord(1, 1)));
    UnitState units = ScopeFixtures.units(ScopeFixtures.unitWithVision("u-1", new HexCoord(1, 1), 1));
    WorldSlices slices = ScopeFixtures.sdWithArmy("a1", "FRA", "u-1");

    SimulationState nationWorld = ScopeFixtures.state(map, units, slices, asNation);
    SimulationState armyWorld = ScopeFixtures.state(map, units, slices, asArmy);

    assertThat(asNation.maker()).as("★ 两个决策人值逐字段相同（只差世界里的归属行）").isEqualTo(asArmy.maker());
    assertThat(ScopeFixtures.prefixes(functions.scopesFor(asNation.maker(), nationWorld, ScopeFixtures.MAP_ID)))
        .as("正向锚点：国家归属确实算出了区域前缀（否则下面的 isNotEqualTo 在'两边都空'时也成立）")
        .containsExactly("demo/region/701");
    assertThat(ScopeFixtures.prefixes(functions.scopesFor(asArmy.maker(), armyWorld, ScopeFixtures.MAP_ID)))
        .as("正向锚点：军队归属确实算出了逐格前缀")
        .contains("demo/hex/1_1")
        .isNotEqualTo(
            ScopeFixtures.prefixes(
                functions.scopesFor(asNation.maker(), nationWorld, ScopeFixtures.MAP_ID)));
  }

  @Test
  void theMapIdIsPartOfEveryPrefix() {
    DecisionScopeFunctions functions = DecisionScopeFunctions.defaults();

    ResourceScope scope =
        functions
            .scopesFor(DM_ARMY.maker(), STATE, "other-map")
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

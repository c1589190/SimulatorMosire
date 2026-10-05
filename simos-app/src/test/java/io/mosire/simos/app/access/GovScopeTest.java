package io.mosire.simos.app.access;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.Army;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.GovLevel;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitModule;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ **政府决策人（{@code Affiliation.Gov}）的直辖范围**（阶段 10a，用户裁定 4）：own + 本级 {@code Unit.jurisdiction} 的每个
 * Region 的**逐格 hex 前缀** + {@code map:<mapId>/region/<rid>} 前缀 + 本单位前缀。
 *
 * <p>★ 本文件把上一轮修过的缺口钉死：只给 region 前缀不够——直接对 {@code map:<mapId>/hex/<q>_<r>} 的判定/命令目标声明必须**逐格**
 * 授权（{@link #directScopeCoversEveryHexOfEveryJurisdictionRegionPerCell}）。
 *
 * <p>★ 三条边界逐条有对立面用例：
 *
 * <ol>
 *   <li>{@code jurisdiction} 空 ⇒ 只 own + 所在格（同格其他单位**不**自动进入辖内）；
 *   <li>无有效位置 ⇒ map/social 显式 {@code none()}、unit 只留自己（fail-closed，不是"看全图"）；
 *   <li>单位不存在 / 存在但不是 GOV（无编制或 ArmyFormation）⇒ 三命名空间 deny-all。
 * </ol>
 *
 * <p>★ {@code unit} 命名空间 = 自己 + **位置落在授权 hex 上**的单位；区外单位一律不可达。
 */
class GovScopeTest {

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);
  private static final HexCoord H20 = new HexCoord(2, 0);
  private static final HexCoord H21 = new HexCoord(2, 1);
  private static final HexCoord H22 = new HexCoord(2, 2);

  private static final RegionId NORTH = new RegionId("r-north");
  private static final RegionId EAST = new RegionId("r-east");

  /** 不在 map.regions() 里的区域（"区域查无 ⇒ 跳过该 Region"的靶子）。 */
  private static final RegionId GHOST = new RegionId("r-ghost");

  // ── 直辖 = own + 管辖 Region 的逐格 hex + region 前缀 ─────────────

  @Test
  void directScopeCoversEveryHexOfEveryJurisdictionRegionPerCell() {
    UnitState units =
        ScopeFixtures.units(
            govUnit("g-1", H20, Optional.of(jurisdiction(NORTH, EAST))),
            plainUnit("u-in", H12),
            plainUnit("u-out", H13));
    SimulationState state = world(units);

    ResourceScopeMap scopes = scopesFor("g-1", state);

    assertThat(ScopeFixtures.prefixes(scopes))
        .as("★ own(H20) + r-north 的逐格(H11,H12) + r-east 的逐格(H21,H22) + 两条 region 前缀")
        .containsExactlyInAnyOrder(
            "demo/hex/1_1",
            "demo/hex/1_2",
            "demo/hex/2_1",
            "demo/hex/2_2",
            "demo/hex/2_0",
            "demo/region/r-north",
            "demo/region/r-east");
    ResourceScope mapScope = scopes.declaredScope("map");
    assertThat(mapScope.unrestricted()).as("受限范围不得退化成 unlimited()").isFalse();
    assertThat(mapScope.allows("demo/hex/1_1")).as("r-north 的第一格").isTrue();
    assertThat(mapScope.allows("demo/hex/1_2")).as("r-north 的第二格").isTrue();
    assertThat(mapScope.allows("demo/hex/2_1")).as("r-east 的第一格").isTrue();
    assertThat(mapScope.allows("demo/hex/2_2")).as("r-east 的第二格").isTrue();
    assertThat(mapScope.allows("demo/hex/2_0")).as("自己的所在格（即使不在任何管辖 Region 内）").isTrue();
    assertThat(mapScope.allows("demo/region/r-north")).as("region 前缀（区域读口）").isTrue();
    assertThat(mapScope.allows("demo/region/r-east")).isTrue();
    assertThat(mapScope.allows("demo/hex/1_3")).as("不在 own、也不在管辖 Region 的格不可达").isFalse();
    assertThat(mapScope.allows("demo/region/r-ghost")).as("没管辖的区域不可达").isFalse();

    ResourceScope socialScope = scopes.declaredScope("social");
    assertThat(socialScope.allows("1_1")).as("social 与 map 同一批 hex").isTrue();
    assertThat(socialScope.allows("1_2")).isTrue();
    assertThat(socialScope.allows("2_1")).isTrue();
    assertThat(socialScope.allows("2_2")).isTrue();
    assertThat(socialScope.allows("2_0")).as("own 所在格").isTrue();
    assertThat(socialScope.allows("1_3")).as("区外格不可达").isFalse();

    ResourceScope unitScope = scopes.declaredScope("unit");
    assertThat(unitScope.allows("g-1")).as("本单位恒在自己的范围里").isTrue();
    assertThat(unitScope.allows("u-in")).as("位置落在授权 hex（H12）上的单位").isTrue();
    assertThat(unitScope.allows("u-out")).as("位置在授权 hex 之外的单位不可达").isFalse();
  }

  // ── 边界一：空 jurisdiction ⇒ 只 own + 所在格 ────────────────────

  @Test
  void emptyJurisdictionGrantsOnlyOwnHexAndItself() {
    for (Optional<Jurisdiction> empty :
        List.of(Optional.<Jurisdiction>empty(), Optional.of(jurisdiction()))) {
      UnitState units =
          ScopeFixtures.units(
              govUnit("g-1", H11, empty),
              // ★ 同格的其他单位：空管辖时**不**因"集中办公"自动进入辖内。
              plainUnit("u-same-hex", H11));
      SimulationState state = world(units);

      ResourceScopeMap scopes = scopesFor("g-1", state);

      assertThat(ScopeFixtures.prefixes(scopes))
          .as("空 jurisdiction（%s）⇒ 只有 own 所在格", empty)
          .containsExactly("demo/hex/1_1");
      assertThat(scopes.declaredScope("social").allows("1_1")).isTrue();
      assertThat(scopes.declaredScope("social").allows("1_2")).isFalse();
      ResourceScope unitScope = scopes.declaredScope("unit");
      assertThat(unitScope.allows("g-1")).as("自己").isTrue();
      assertThat(unitScope.allows("u-same-hex")).as("空管辖只授自己：同格他人不自动进辖内").isFalse();
      assertThat(unitScope.allows("u-ghost")).isFalse();
    }
  }

  // ── 边界二：无有效位置 ⇒ map/social none、unit 只自己 ──────────────

  @Test
  void aGovWithoutPositionGetsMapAndSocialNoneButKeepsItsOwnUnitPrefix() {
    UnitState units =
        ScopeFixtures.units(
            govUnit("g-1", Optional.empty(), Optional.of(jurisdiction(NORTH)), Optional.empty()),
            plainUnit("u-2", H11));
    SimulationState state = world(units);

    ResourceScopeMap scopes = scopesFor("g-1", state);

    assertThat(scopes.namespaces())
        .as("三命名空间都要表态（只配 map 会让 unit/social 回落工具缺省）")
        .containsExactlyInAnyOrder("map", "social", "unit", "actor");
    assertThat(scopes.declaredScope("map"))
        .as("不知道自己在哪 ⇒ map 显式 none()，不是看全图")
        .isEqualTo(ResourceScope.none());
    assertThat(scopes.declaredScope("social")).isEqualTo(ResourceScope.none());
    ResourceScope unitScope = scopes.declaredScope("unit");
    assertThat(unitScope.unrestricted()).isFalse();
    assertThat(unitScope.allows("g-1")).as("最小身份面：自己").isTrue();
    assertThat(unitScope.allows("u-2")).as("没有位置 ⇒ 同世界别的单位也不可达").isFalse();
  }

  // ── 边界三：单位不存在 / 不是 GOV ⇒ 三命名空间 deny-all ────────────

  @Test
  void missingOrNonGovUnitsAreDenyAllInAllThreeNamespaces() {
    Army army =
        new Army(
            new io.mosire.simos.sd.id.ArmyId("a-1"), Optional.empty(), new UnitId("g-army"), "第一军");
    UnitState units =
        ScopeFixtures.units(
            plainUnit("g-plain", H11),
            withModule(
                "g-army", H11, Optional.of(new ArmyFormation(Optional.empty(), "garrison"))));
    SimulationState state =
        ScopeFixtures.state(map(), units, SdState.empty().withArmies(Map.of(army.id(), army)));

    for (String govId : List.of("g-ghost", "g-plain", "g-army")) {
      ResourceScopeMap scopes = scopesFor(govId, state);
      assertThat(scopes.namespaces())
          .as("%s：三命名空间都要表态", govId)
          .containsExactlyInAnyOrder("map", "social", "unit", "actor");
      assertThat(scopes.declaredScope("map"))
          .as("%s：map deny-all", govId)
          .isEqualTo(ResourceScope.none());
      assertThat(scopes.declaredScope("social"))
          .as("%s：social deny-all", govId)
          .isEqualTo(ResourceScope.none());
      assertThat(scopes.declaredScope("unit"))
          .as("%s：unit deny-all（Army 编制不是 GOV）", govId)
          .isEqualTo(ResourceScope.none());
      assertThat(scopes.declaredScope("unit").allows("g-plain")).isFalse();
      assertThat(scopes.declaredScope("map").allows("demo/hex/1_1")).isFalse();
    }
  }

  // ── 区外 hex / 单位不可达；区域查无跳过而非整体 deny-all ───────────

  @Test
  void hexesAndUnitsOutsideTheDirectJurisdictionAreNotReachable() {
    UnitState units =
        ScopeFixtures.units(
            govUnit("g-1", H11, Optional.of(jurisdiction(EAST))),
            plainUnit("u-in", H22),
            plainUnit("u-out", H13));
    SimulationState state = world(units);

    ResourceScopeMap scopes = scopesFor("g-1", state);

    assertThat(ScopeFixtures.prefixes(scopes))
        .as("own H11 + 管辖 r-east 的 H21/H22 + region 前缀；不含别处")
        .containsExactlyInAnyOrder(
            "demo/hex/1_1", "demo/hex/2_1", "demo/hex/2_2", "demo/region/r-east");
    ResourceScope mapScope = scopes.declaredScope("map");
    assertThat(mapScope.allows("demo/hex/1_3")).as("区外 hex 不可达").isFalse();
    assertThat(mapScope.allows("demo/hex/1_2")).as("北境（未纳入管辖）不可达").isFalse();
    assertThat(mapScope.allows("demo/region/r-north")).as("未管辖的 region 前缀也不可达").isFalse();
    assertThat(scopes.declaredScope("social").allows("1_3")).isFalse();
    ResourceScope unitScope = scopes.declaredScope("unit");
    assertThat(unitScope.allows("u-in")).as("辖区内的单位可达").isTrue();
    assertThat(unitScope.allows("u-out")).as("辖区外的单位不可达").isFalse();
  }

  /** ★ 类注口径：管辖里指着一个 map 已不存在的 RegionId ⇒ 跳过该 Region，不整体 deny-all。 */
  @Test
  void anUnknownJurisdictionRegionIsSkippedInsteadOfDenyingEverything() {
    UnitState units =
        ScopeFixtures.units(govUnit("g-1", H11, Optional.of(jurisdiction(GHOST, NORTH))));
    SimulationState state = world(units);

    ResourceScopeMap scopes = scopesFor("g-1", state);

    assertThat(ScopeFixtures.prefixes(scopes))
        .as("悬空 Region 被跳过，剩下的自己 + r-north 与区前缀照常授权")
        .containsExactlyInAnyOrder("demo/hex/1_1", "demo/hex/1_2", "demo/region/r-north")
        .doesNotContain("demo/region/r-ghost");
    assertThat(scopes.declaredScope("map").allows("demo/hex/1_2")).isTrue();
    assertThat(scopes.declaredScope("map").allows("demo/hex/1_1")).isTrue();
    assertThat(scopes.declaredScope("map").allows("demo/region/r-ghost")).isFalse();
  }

  // ── 装置 / 夹具 ────────────────────────────────────────────────

  private static SimulationState world(UnitState units) {
    return ScopeFixtures.state(map(), units, SdState.empty());
  }

  private static ResourceScopeMap scopesFor(String govUnitId, SimulationState state) {
    DecisionMaker dm =
        new DecisionMaker(
            new DecisionMakerId("dm-" + govUnitId),
            new Affiliation.Gov(new UnitId(govUnitId)),
            Set.of(),
            AccessLimit.empty(),
            1);
    return DecisionScopeFunctions.defaults().scopesFor(dm, state, ScopeFixtures.MAP_ID);
  }

  /** r-north = {H11,H12}、r-east = {H21,H22}；两个区域都没有国家 tag（GovScope 不看 tag）。 */
  private static GameMap map() {
    return ScopeFixtures.mapOf(
        ScopeFixtures.taggedRegion("r-north", null, H11, H12),
        ScopeFixtures.taggedRegion("r-east", null, H21, H22));
  }

  private static Jurisdiction jurisdiction(RegionId... regions) {
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    for (RegionId region : regions) {
      rates.put(region, 0L);
    }
    return new Jurisdiction(rates, 0L, 0L, 0L, 0);
  }

  private static Unit govUnit(String id, HexCoord position, Optional<Jurisdiction> jurisdiction) {
    return govUnit(id, Optional.of(position), jurisdiction, Optional.empty());
  }

  private static Unit govUnit(
      String id,
      Optional<HexCoord> position,
      Optional<Jurisdiction> jurisdiction,
      Optional<GovLevel> level) {
    return withModule(
        id,
        position,
        jurisdiction,
        Optional.of(
            new GovFormation(
                Map.of(),
                OfficePolicy.defaults(),
                Optional.empty(),
                level.orElse(GovLevel.CENTRAL))));
  }

  private static Unit plainUnit(String id, HexCoord position) {
    return withModule(id, position, Optional.<UnitModule>empty());
  }

  private static Unit withModule(String id, HexCoord position, Optional<UnitModule> module) {
    return withModule(id, Optional.of(position), Optional.empty(), module);
  }

  private static Unit withModule(
      String id,
      Optional<HexCoord> position,
      Optional<Jurisdiction> jurisdiction,
      Optional<UnitModule> module) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(ScopeFixtures.T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(ScopeFixtures.T0, position)), List.of(), null),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(ScopeFixtures.T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(ScopeFixtures.T0, Optional.<RelativeOffset>empty())),
            List.of(),
            null),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        jurisdiction,
        module);
  }

  /** 防御性自检：夹具的 region 前缀真的与 map 对得上（拼错 id 时用例会全红而不是恒真）。 */
  @Test
  void fixtureRegionsExistInTheMap() {
    GameMap map = map();
    assertThat(map.regions()).containsOnlyKeys(NORTH, EAST);
    assertThat(new ArrayList<>(map.regions().get(NORTH).hexes()))
        .as("r-north 的格集合是夹具字面量")
        .containsExactlyInAnyOrder(H11, H12);
    assertThat(new ArrayList<>(map.regions().get(EAST).hexes()))
        .containsExactlyInAnyOrder(H21, H22);
  }
}

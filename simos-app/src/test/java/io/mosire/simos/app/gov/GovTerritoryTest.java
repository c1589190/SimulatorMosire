package io.mosire.simos.app.gov;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.simos.app.access.DecisionScopeFunctions;
import io.mosire.simos.app.access.ScopeFixtures;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ **{@code GovTerritory} 的名义全境**（阶段 10a，用户裁定 4）：沿 {@code superiorGov} 向下收集整棵 GOV 子树的 {@code
 * Unit.jurisdiction} Region 并集，**含根自身**。
 *
 * <p>★ 判据：单层/两层并集 & 保序不可变、环兜底不死循环、空 jurisdiction 的 GOV 跳过但子树继续、查无/非 GOV 起点为空。
 *
 * <p>★★ **授权隔离**（用户裁定 4 的硬线）：本视图**绝不进权限判定**。本文件用行为 + 结构两面钉死——① 中央 GOV 的直辖 {@code GovScope} 在它自己的
 * jurisdiction 为空时**不因**下级有辖区而放宽；② {@code GovTerritory} 的公开方法签名里不出现任何授权类型 （{@code
 * ResourceScope}/{@code ResourceScopeMap}/{@code DecisionMaker}/{@code SimulationState}）。
 */
class GovTerritoryTest {

  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);

  private static final UnitId G1 = new UnitId("g-1");
  private static final UnitId G2 = new UnitId("g-2");
  private static final UnitId G3 = new UnitId("g-3");
  private static final UnitId G4 = new UnitId("g-4");

  private static final RegionId R_A = new RegionId("r-a");
  private static final RegionId R_B = new RegionId("r-b");
  private static final RegionId R_C = new RegionId("r-c");
  private static final RegionId R_D = new RegionId("r-d");
  private static final RegionId R_E = new RegionId("r-e");
  private static final RegionId R_G = new RegionId("r-g");

  // ── 并集 / 含根 / 保序 / 不可变 ─────────────────────────────────

  @Test
  void singleLevelRootIncludesItsOwnJurisdiction() {
    UnitState units = stateOf(gov(G1, Optional.empty(), GovernmentLevel.CENTRAL, R_A));

    Set<RegionId> nominal = GovTerritory.nominalRegions(units, G1);

    assertThat(new ArrayList<>(nominal)).as("单层：根自己就是名义全境（含根自身）").containsExactly(R_A);
  }

  @Test
  void twoLevelsUnionKeepsFirstSeenOrderAndIsImmutable() {
    // g-1 → {g-2, g-4}，g-2 → g-3；units 插入序决定同层子节点的 BFS 序。
    UnitState units =
        stateOf(
            gov(G1, Optional.empty(), GovernmentLevel.CENTRAL, R_B, R_A),
            gov(G2, Optional.of(G1), GovernmentLevel.PROVINCE, R_A, R_C),
            gov(G3, Optional.of(G2), GovernmentLevel.PROVINCE, R_D),
            gov(G4, Optional.of(G1), GovernmentLevel.PROVINCE, R_E));

    Set<RegionId> nominal = GovTerritory.nominalRegions(units, G1);

    assertThat(new ArrayList<>(nominal))
        .as("BFS 首现序：根 r-b,r-a → 直接下级 g-2 的 r-c → g-4 的 r-e → 再下级 g-3 的 r-d（r-a 重复只计一次）")
        .containsExactly(R_B, R_A, R_C, R_E, R_D);
    assertThatThrownBy(() -> nominal.add(R_G))
        .as("返回集合不可变")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  // ── 空 jurisdiction / 环 / 查无起点 ─────────────────────────────

  @Test
  void anEmptyJurisdictionGovIsSkippedButItsSubtreeIsStillTraversed() {
    UnitState units =
        stateOf(
            gov(G1, Optional.empty(), GovernmentLevel.CENTRAL, new RegionId[0]),
            gov(G2, Optional.of(G1), GovernmentLevel.PROVINCE, new RegionId[0]),
            gov(G3, Optional.of(G2), GovernmentLevel.PROVINCE, R_G));

    assertThat(new ArrayList<>(GovTerritory.nominalRegions(units, G1)))
        .as("空 jurisdiction 的 GOV 自己不加，但子树继续向下（g-3 的 r-g 必须收到）")
        .containsExactly(R_G);
    assertThat(GovTerritory.nominalRegions(units, G2)).containsExactly(R_G);
  }

  @Test
  void aHandBuiltCycleDoesNotHangAndStillReturnsTheUnion() {
    // UnitState 不校验 superiorGov 环（unit.SetGovSuperior 才拒）；手工拼出的坏状态不能让显示端死循环。
    UnitState units =
        stateOf(
            gov(G1, Optional.of(G2), GovernmentLevel.CENTRAL, R_A),
            gov(G2, Optional.of(G1), GovernmentLevel.PROVINCE, R_B));

    assertThat(new ArrayList<>(GovTerritory.nominalRegions(units, G1)))
        .as("环由访问集兜底：两个 GOV 各出现一次即停")
        .containsExactly(R_A, R_B);
  }

  @Test
  void unknownOrNonGovRootReturnsEmptySet() {
    UnitState units =
        stateOf(plain("u-plain"), gov(G1, Optional.empty(), GovernmentLevel.CENTRAL, R_A));

    assertThat(GovTerritory.nominalRegions(units, new UnitId("ghost"))).isEmpty();
    assertThat(GovTerritory.nominalRegions(units, new UnitId("u-plain")))
        .as("起点没有 GovernmentFormation ⇒ 没有名义全境可谈（不是把它的 jurisdiction 当根）")
        .isEmpty();
  }

  // ── 授权隔离：行为 + 结构 ───────────────────────────────────────

  @Test
  void territoryIsVisibleButNeverWidensTheGovScope() {
    GameMap map = ScopeFixtures.mapOf(ScopeFixtures.taggedRegion("r-child", null, H12, H13));
    UnitState units =
        stateOf(
            govAt(G1, H11, Optional.empty(), GovernmentLevel.CENTRAL, new RegionId[0]),
            govAt(
                G2,
                H12,
                Optional.of(G1),
                GovernmentLevel.PROVINCE,
                new RegionId[] {new RegionId("r-child")}));
    SimulationState state = ScopeFixtures.state(map, units, SdState.empty());

    // 名义全境：中央的 GOV 子树包含下级的 r-child。
    assertThat(GovTerritory.nominalRegions(units, G1))
        .as("名义全境确实聚合到了下级辖区")
        .containsExactly(new RegionId("r-child"));

    // 直辖：中央自己 jurisdiction 为空 ⇒ 只 own(H11)，绝不因名义全境放宽。
    DecisionMaker dm =
        new DecisionMaker(
            new DecisionMakerId("dm-g1"),
            new Affiliation.Gov(G1),
            Set.of(),
            AccessLimit.empty(),
            1);
    ResourceScopeMap scopes =
        DecisionScopeFunctions.defaults().scopesFor(dm, state, ScopeFixtures.MAP_ID);

    assertThat(scopes.declaredScope("map").allows("demo/hex/1_1")).as("own 的格照常可见").isTrue();
    assertThat(scopes.declaredScope("map").allows("demo/region/r-child"))
        .as("★ GovTerritory 的结果不参与授权：下级的 region 前缀看不见")
        .isFalse();
    assertThat(scopes.declaredScope("map").allows("demo/hex/1_2"))
        .as("★ 下级辖区的逐格 hex 也看不见")
        .isFalse();
    assertThat(scopes.declaredScope("social").allows("1_2")).isFalse();
    assertThat(scopes.declaredScope("unit").allows(G2.value()))
        .as("★ 下级 GOV 单位本身也不在中央的直辖里")
        .isFalse();
    assertThat(scopes.declaredScope("unit").allows(G1.value())).isTrue();
    assertThat(scopes.declaredScope("map")).isNotEqualTo(ResourceScope.none());
  }

  /** ★ **结构断言**：{@code GovTerritory} 的公开签名里**不出现任何授权类型**——它连"能算权限"的形状都不具备，杜绝后来者顺手接线。 */
  @Test
  void govTerritoryApiDoesNotMentionAnyAuthorizationType() {
    List<Method> methods =
        java.util.Arrays.stream(GovTerritory.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .toList();
    assertThat(methods).as("名义全境只应有一个公开方法（nominalRegions）").hasSize(1);
    Method only = methods.get(0);
    assertThat(only.getReturnType()).as("返回的只是 RegionId 集合，不是范围对象").isEqualTo(Set.class);
    assertThat(only.getParameterTypes())
        .as("输入只有 unit 切片与根 id；没有 DecisionMaker/SimulationState/ResourceScopeMap")
        .containsExactlyInAnyOrder(UnitState.class, UnitId.class);
  }

  // ── 夹具 ───────────────────────────────────────────────────────

  private static UnitState stateOf(Unit... units) {
    Map<UnitId, Unit> byId = new LinkedHashMap<>();
    for (Unit unit : units) {
      byId.put(unit.id(), unit);
    }
    return new UnitState(byId);
  }

  private static Unit gov(
      UnitId id, Optional<UnitId> superior, GovernmentLevel level, RegionId... regions) {
    return govAt(id, H11, superior, level, regions);
  }

  private static Unit govAt(
      UnitId id,
      HexCoord position,
      Optional<UnitId> superior,
      GovernmentLevel level,
      RegionId... regions) {
    return unit(
        id,
        position,
        Optional.of(
            new GovernmentFormation(Map.of(), Map.of(), OfficePolicy.defaults(), superior, level)),
        Optional.of(jurisdiction(regions)),
        List.of(GovernmentHouseholds.of(id.value())));
  }

  private static Unit plain(String id) {
    return unit(new UnitId(id), H11, Optional.<UnitModule>empty(), Optional.<Jurisdiction>empty());
  }

  private static Unit unit(
      UnitId id,
      HexCoord position,
      Optional<UnitModule> module,
      Optional<Jurisdiction> jurisdiction) {
    return unit(id, position, module, jurisdiction, List.of());
  }

  private static Unit unit(
      UnitId id,
      HexCoord position,
      Optional<UnitModule> module,
      Optional<Jurisdiction> jurisdiction,
      List<HouseholdId> households) {
    return new Unit(
        id,
        "单位 " + id.value(),
        new SegmentedSeries<>(
            List.of(new Segment<>(ScopeFixtures.T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(ScopeFixtures.T0, Optional.of(position))), List.of(), null),
        List.of(),
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
        module,
        Map.of(),
        households);
  }

  private static Jurisdiction jurisdiction(RegionId... regions) {
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    for (RegionId region : regions) {
      rates.put(region, 0L);
    }
    return new Jurisdiction(rates, 0L, 0L, 0L, 0);
  }
}

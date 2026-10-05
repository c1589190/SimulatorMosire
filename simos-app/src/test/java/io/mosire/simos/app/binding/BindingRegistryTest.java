package io.mosire.simos.app.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.agentlib.permission.AgentPermissionSet;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.resolve.MapResolver;
import io.mosire.simos.map.spi.MapAgentAttachPolicy;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.resolve.UnitResolver;
import io.mosire.simos.unit.spi.UnitAgentAttachPolicy;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.resolve.ResolveContext;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.spi.AgentAttachPolicy;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link BindingRegistry} 的验收（M5 T10 Step 3；spec §6 + 护栏 R8）。
 *
 * <p>覆盖：允许主体（map Region / unit）可绑、**无策略的命名空间拒绝**、**策略拒绝**（真 map 策略 + 替身策略）、 重复 {@code
 * (agentId,target)} 拒绝、canonical 化（Human 形地址绑定并**存储 canonical `SubjectId`**、且**问策略时给的是 canonical
 * 地址**）、 {@code list}/{@code bySubject}/{@code byAgent} 过滤、解绑后可重绑。
 *
 * <p>★ 夹具世界只有 map + unit 两切片——恰好够两条策略；{@link ResolverRegistry} 注册 Map/Unit 两个 resolver。
 */
class BindingRegistryTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final UnitId U1 = new UnitId("u-1");
  private static final RegionId R1 = new RegionId("r1");
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  private static final SubjectId UNIT_ID = new SubjectId("unit", "u-1");
  private static final SubjectId REGION_ID = new SubjectId("map.region", "r1");

  private static final AgentId AGENT_A = new AgentId("agent:a-1");
  private static final AgentId AGENT_B = new AgentId("agent:b-1");
  private static final DecisionScope SCOPE = new DecisionScope("theater:north");
  private static final AgentPermissionSet PERMS = AgentPermissionSet.system();

  // ── 允许的主体 ──────────────────────────────────────────────────────

  @Test
  void bindsAnAllowedMapRegionAndStoresTheCanonicalSubject() {
    BindingRegistry registry = fullRegistry();

    AgentBinding binding = bind(registry, AGENT_A, "map:Map1:region.r1");

    assertThat(binding.target()).as("Region 的稳定身份是 map.region:r1").isEqualTo(REGION_ID);
    assertThat(registry.list()).containsExactly(binding);
  }

  @Test
  void bindsAnAllowedUnit() {
    BindingRegistry registry = fullRegistry();

    AgentBinding binding = bind(registry, AGENT_A, "unit:u-1");

    assertThat(binding.target()).isEqualTo(UNIT_ID);
    assertThat(registry.bySubject(UNIT_ID)).containsExactly(binding);
  }

  // ── canonical 化 ───────────────────────────────────────────────────

  @Test
  void canonicalizesAHumanFormAddressBeforeStoringTheSubject() {
    BindingRegistry registry = fullRegistry();

    AgentBinding binding = bind(registry, AGENT_A, "unit:\"第一连\"");

    assertThat(binding.target()).as("Human 形的链式地址应绑定并存储 canonical SubjectId").isEqualTo(UNIT_ID);
    assertThat(registry.bySubject(UNIT_ID)).containsExactly(binding);
  }

  @Test
  void asksThePolicyWithTheCanonicalAddressNotTheHumanForm() {
    RecordingPolicy recorder = new RecordingPolicy();
    BindingRegistry registry = new BindingRegistry(resolvers(), List.of(recorder));

    registry.bind(
        AGENT_A, Address.parse("unit:\"第一连\""), SCOPE, BindingMode.AUTO_APPLY, PERMS, ctx());

    assertThat(recorder.subjects)
        .as("R8：canonical 化后再问策略——策略收到的是 unit:u-1，不是 unit:\"第一连\"")
        .containsExactly(Address.parse("unit:u-1"));
  }

  // ── 拒绝：无策略 / 策略说不行 ───────────────────────────────────────

  @Test
  void refusesANamespaceWithoutAPolicy() {
    BindingRegistry registry =
        new BindingRegistry(resolvers(), List.of(new MapAgentAttachPolicy()));

    assertThatThrownBy(() -> bind(registry, AGENT_A, "unit:u-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unit")
        .hasMessageContaining("没有注册 AgentAttachPolicy");
    assertThat(registry.list()).as("拒绝不留半条记录").isEmpty();
  }

  @Test
  void refusesWhenThePolicyRefuses() {
    BindingRegistry registry =
        new BindingRegistry(resolvers(), List.of(new RefusingPolicy("unit")));

    assertThatThrownBy(() -> bind(registry, AGENT_A, "unit:u-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("策略拒绝");
    assertThat(registry.list()).isEmpty();
  }

  @Test
  void refusesWhenTheRealMapPolicyRejectsANonRegion() {
    BindingRegistry registry = fullRegistry();

    assertThatThrownBy(() -> bind(registry, AGENT_A, "map:Map1:hex.1_1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("策略拒绝");
  }

  @Test
  void refusesAnUnresolvableSubjectAndAnUnregisteredNamespace() {
    BindingRegistry registry = fullRegistry();

    assertThatThrownBy(() -> bind(registry, AGENT_A, "unit:u-ghost"))
        .as("合法但没有候选 ⇒ 拒绝")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有候选");
    assertThatThrownBy(() -> bind(registry, AGENT_A, "agent:x-1"))
        .as("没有 resolver 的命名空间 ⇒ 注册表抛，不兜底")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有注册命名空间");
  }

  // ── 重复绑定 ───────────────────────────────────────────────────────

  @Test
  void refusesDuplicateAgentTargetButAllowsTheSameTargetForAnotherAgent() {
    BindingRegistry registry = fullRegistry();
    bind(registry, AGENT_A, "unit:u-1");

    assertThatThrownBy(() -> bind(registry, AGENT_A, "unit:u-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("重复绑定");
    assertThat(registry.list()).as("重复被拒后仍是原来那一条").hasSize(1);

    AgentBinding forB = bind(registry, AGENT_B, "unit:u-1");
    assertThat(registry.list()).hasSize(2);
    assertThat(registry.byAgent(AGENT_B)).containsExactly(forB);
  }

  // ── 过滤 ───────────────────────────────────────────────────────────

  @Test
  void filtersBySubjectAndByAgentInRegistrationOrder() {
    BindingRegistry registry = fullRegistry();
    AgentBinding aUnit = bind(registry, AGENT_A, "unit:u-1");
    AgentBinding aRegion = bind(registry, AGENT_A, "map:Map1:region.r1");
    AgentBinding bUnit = bind(registry, AGENT_B, "unit:u-1");

    assertThat(registry.list()).containsExactly(aUnit, aRegion, bUnit);
    assertThat(registry.byAgent(AGENT_A)).containsExactly(aUnit, aRegion);
    assertThat(registry.byAgent(AGENT_B)).containsExactly(bUnit);
    assertThat(registry.bySubject(UNIT_ID)).containsExactly(aUnit, bUnit);
    assertThat(registry.bySubject(REGION_ID)).containsExactly(aRegion);
    assertThat(registry.bySubject(new SubjectId("unit", "nope"))).isEmpty();
  }

  // ── 解绑 ───────────────────────────────────────────────────────────

  @Test
  void unbindRemovesTheBindingAndAllowsRebindingTheSamePair() {
    BindingRegistry registry = fullRegistry();
    AgentBinding binding = bind(registry, AGENT_A, "unit:u-1");

    assertThat(registry.unbind(binding.id())).isTrue();
    assertThat(registry.unbind(binding.id())).as("第二次解绑 ⇒ false，不抛").isFalse();
    assertThat(registry.list()).isEmpty();
    assertThat(registry.bySubject(UNIT_ID)).isEmpty();

    AgentBinding again = bind(registry, AGENT_A, "unit:u-1");
    assertThat(again.id()).as("重绑生成新 id").isNotEqualTo(binding.id());
  }

  // ── 记录形状 ───────────────────────────────────────────────────────

  @Test
  void recordShapesRejectBlanksAndMalformedAgentIds() {
    assertThatThrownBy(() -> new BindingId(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AgentId("external-mcp"))
        .as("AgentId 必须是 agent:<id> 形态（C21）")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AgentId("agent:")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DecisionScope("")).isInstanceOf(IllegalArgumentException.class);
  }

  // ────────────────────────────── 夹具 ──────────────────────────────

  private static AgentBinding bind(BindingRegistry registry, AgentId agentId, String address) {
    return registry.bind(
        agentId, Address.parse(address), SCOPE, BindingMode.AUTO_APPLY, PERMS, ctx());
  }

  private static BindingRegistry fullRegistry() {
    return new BindingRegistry(
        resolvers(), List.of(new MapAgentAttachPolicy(), new UnitAgentAttachPolicy()));
  }

  private static ResolverRegistry resolvers() {
    ResolverRegistry resolvers = new ResolverRegistry();
    resolvers.register(new MapResolver());
    resolvers.register(new UnitResolver());
    return resolvers;
  }

  private static ResolveContext ctx() {
    return new ResolveContext(state(), T0);
  }

  private static SimulationState state() {
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(U1, unit())));
    return new SimulationState(
        new StateMeta(REF, T0),
        Map.of(
            "map", new MapSnapshot(REF, T0, map()),
            "unit", new UnitSnapshot(REF, T0, units),
            "sd", new SdSnapshot(REF, T0, SdState.empty())),
        InMemoryInfoSystem.empty());
  }

  private static GameMap map() {
    TerrainType flat = new TerrainType("flat", "flat", "#336699", 0.0, 1.0, 0, 0, 0, 25, "夹具地形");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    Region region = Region.of(R1, "北方行省", Set.of(H11), RegionMeta.empty());
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(flat.key(), flat);
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), flat.key()),
        Map.of(R1, region),
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static Unit unit() {
    return new Unit(
        U1,
        "第一连",
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(H11))), List.of(), null),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty());
  }

  /** 记录型替身：记下收到的 subject，返回 true——用来证明"问策略时给的是 canonical 地址"。 */
  private static final class RecordingPolicy implements AgentAttachPolicy {

    private final List<Address> subjects = new ArrayList<>();

    @Override
    public String namespace() {
      return "unit";
    }

    @Override
    public boolean canAttach(Address subject, ResolveContext ctx) {
      subjects.add(subject);
      return true;
    }
  }

  /** 替身策略：对注册的命名空间一律拒绝——用来隔离"策略拒绝"这条路径。 */
  private static final class RefusingPolicy implements AgentAttachPolicy {

    private final String namespace;

    RefusingPolicy(String namespace) {
      this.namespace = namespace;
    }

    @Override
    public String namespace() {
      return namespace;
    }

    @Override
    public boolean canAttach(Address subject, ResolveContext ctx) {
      return false;
    }
  }
}

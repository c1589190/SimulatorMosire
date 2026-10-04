package io.mosire.simos.app.testing;

import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.terrain.TerrainCatalog;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.spi.SetHouseholdLocationHandler;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.GovLevel;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.unit.spi.SetUnitHouseholdsHandler;
import io.mosire.simos.util.facet.FacetRegistry;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.resolve.ResolverRegistry;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★ S3a GM 工具/读口用例的共享小世界（app 侧测试夹具）：一张 3 格走廊图 + unit/social/sd 切片，创世 revision = 1。
 *
 * <p>形态照 {@code SimosToolsTest.seedGenesis}，但不走 Shell/落盘：直接用 {@link CoreSimos#bootstrapGenesis} 建世界，
 * 让 GM 组合工具（{@code UnitAssignHouseholdTool}/{@code UnitDetachHouseholdTool}）在真 core 上提交命令批、真 revision 可数。
 */
public final class UnitHouseholdWorldFixture {

  public static final BranchId MAIN = new BranchId("main");
  public static final SimosTimestamp T0 = SimosTimestamp.of(0);
  public static final HexCoord H11 = new HexCoord(1, 1);
  public static final HexCoord H12 = new HexCoord(1, 2);
  public static final HexCoord H13 = new HexCoord(1, 3);
  public static final UnitId GOV = new UnitId("u-gov");
  public static final UnitId OTHER = new UnitId("u-other");
  public static final String MAP_ID = "Map1";

  private UnitHouseholdWorldFixture() {}

  /** 建真 core（map/unit/social/sd 四切片 + 已注册 codec），创世坐标 = {@code (main, 1)}。 */
  public static CoreSimos core(Path storeDir, UnitState units, SocialData social) {
    CoreSimos core = new CoreSimos(new CoreConfig(storeDir, 100, SimosObjectMapper.create()));
    core.register(new MapCodec());
    core.register(new UnitCodec());
    core.register(new SocialCodec());
    core.register(new SdCodec());
    // ★ S3a 组合工具同批提交的两条命令（只注册本夹具真的会用到的 handler；不是全量 Shell 注册面）。
    core.register(new SetHouseholdLocationHandler());
    core.register(new SetUnitHouseholdsHandler());
    StateRef ref = ref(1);
    core.bootstrapGenesis(
        new SimulationState(
            new StateMeta(ref, T0),
            Map.of(
                "map", new MapSnapshot(ref, T0, map()),
                "unit", new UnitSnapshot(ref, T0, units),
                "social", new SocialSnapshot(ref, T0, social),
                "sd", new SdSnapshot(ref, T0, SdState.empty())),
            InMemoryInfoSystem.empty()));
    return core;
  }

  public static QueryService query(CoreSimos core) {
    return new QueryService(core, new ResolverRegistry(), new FacetRegistry());
  }

  public static long head(CoreSimos core) {
    return core.head(MAIN).orElseThrow().value();
  }

  public static SimulationState replay(CoreSimos core, long revision) {
    return core.replay(ref(revision));
  }

  public static StateRef ref(long revision) {
    return new StateRef(MAIN, new RevisionId(revision));
  }

  public static SocialData socialSlice(SimulationState state) {
    return ((SocialSnapshot) state.module("social").orElseThrow()).data();
  }

  public static UnitState unitSlice(SimulationState state) {
    return ((UnitSnapshot) state.module("unit").orElseThrow()).state();
  }

  public static Unit unit(SimulationState state, UnitId id) {
    return unitSlice(state).units().get(id);
  }

  /** 一个 GOV 单位：顶层 {@code households} 与 {@link GovFormation#households()} 都可显式给（两账并存）。 */
  public static Unit govUnit(
      List<HouseholdId> unitHouseholds,
      List<HouseholdId> govHouseholds,
      Optional<UnitId> superiorGov) {
    return new Unit(
        GOV,
        "官府",
        parentSeries(),
        positionSeries(H11),
        List.of(new CompositionEntry("衙役", 12)),
        List.of(),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        attachedSeries(),
        offsetSeries(),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        Optional.empty(),
        Optional.of(
            new GovFormation(
                Map.of(StaffRole.SCRIBE, 2L),
                govHouseholds,
                OfficePolicy.defaults(),
                superiorGov,
                GovLevel.CENTRAL)),
        Map.of(),
        unitHouseholds);
  }

  /** 一个无编制的普通单位（用于"目标 unit id 与 household id 撞名"的批回滚样本）。 */
  public static Unit plainUnit(UnitId id) {
    return new Unit(
        id,
        "普通单位 " + id.value(),
        parentSeries(),
        positionSeries(H12),
        List.of(new CompositionEntry("步兵", 10)),
        List.of(),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        attachedSeries(),
        offsetSeries(),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        Optional.empty(),
        Optional.empty(),
        Map.of(),
        List.of());
  }

  /** 3 格走廊图（(1,1)~(1,3)），detach 的 hex 校验需要它真的在图里。 */
  public static GameMap map() {
    TerrainType desert = TerrainCatalog.of("desert");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), desert.key()),
        Map.of(),
        Map.of(),
        Map.of(desert.key(), desert),
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  private static SegmentedSeries<Optional<UnitId>> parentSeries() {
    return new SegmentedSeries<>(
        List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null);
  }

  private static SegmentedSeries<Optional<HexCoord>> positionSeries(HexCoord at) {
    return new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(at))), List.of(), null);
  }

  private static SegmentedSeries<Boolean> attachedSeries() {
    return new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null);
  }

  private static SegmentedSeries<Optional<io.mosire.simos.unit.RelativeOffset>> offsetSeries() {
    return new SegmentedSeries<>(
        List.of(new Segment<>(T0, Optional.<io.mosire.simos.unit.RelativeOffset>empty())),
        List.of(),
        null);
  }
}

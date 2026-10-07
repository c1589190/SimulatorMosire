package io.mosire.simos.unit.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.CommandChain;
import io.mosire.simos.unit.CommandChainId;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentLevel;
import io.mosire.simos.unit.GovernmentPostOfHousehold;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitModule;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
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
 * R-3-unit：unit 模块的 JSON 往返守卫（M4 Task 3 Step 4，与 {@code MapCodecTest}/{@code SocialCodecTest} 同制）。
 *
 * <p>★ 覆盖：{@code SegmentedSeries<Optional<…>>}（嵌套泛型里的 {@code Optional}——probe 的重灾区）、{@code
 * Optional<Movement>} 两侧向（在途/驻止）、自定义键 {@code UnitId}、密封接口 {@code FieldDelta} 四变体。往返只断 {@code
 * equals}（裁定 12）。
 */
class UnitCodecTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private static final HexCoord H11 = new HexCoord(1, 1);

  private static final HexCoord H12 = new HexCoord(1, 2);

  /** S3b 的领导/军职家户（`GovernmentFormation.governmentPostsOfHousehold` 的键）。 */
  private static final HouseholdId HH_A = HouseholdId.parse("hh-a");

  private static final HouseholdId HH_B = HouseholdId.parse("hh-b");

  /** Z3d 的外部岗位家户（`GovernmentFormation.externalPosts` 的键；不要求在 Unit.households 里）。 */
  private static final HouseholdId HH_EXT_A = HouseholdId.parse("hh-ext-a");

  private static final HouseholdId HH_EXT_B = HouseholdId.parse("hh-ext-b");

  private static final UnitCodec CODEC = new UnitCodec();

  @Test
  void namespaceIsUnit() {
    assertThat(CODEC.namespace()).isEqualTo("unit");
  }

  /** 非平凡快照往返：驻止单位（movement 为 empty）+ 带历注。 */
  @Test
  void snapshotRoundTripsWithLabeledTimestamp() {
    UnitSnapshot snapshot =
        snapshotOf(stateOf(oneUnit("u-1", H11, false)), SimosTimestamp.of(10, "弘光元年"));
    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
  }

  /** 在途单位：{@code Optional<Movement>} 的 present 侧 + {@code Route} 的 {@code List<HexCoord>} 组件。 */
  @Test
  void snapshotRoundTripsWithUnitInTransit() {
    UnitSnapshot snapshot = snapshotOf(stateOf(oneUnit("u-1", H11, true)), SimosTimestamp.of(11));
    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
  }

  /**
   * ★★ **人力/装备表的线格式往返（D-006 / R1）**：多条目、非平凡顺序（哈希序会打乱 3 键）也必须逐条、逐位回读。
   *
   * <p>判别力：任何一个拷贝点/编解码把两张表丢掉一条、合成总数或换成哈希序 ⇒ 本用例当场红（"不丢失"变异自证的靶子）。
   */
  @Test
  void snapshotRoundTripsOrderedCompositionTable() {
    UnitSnapshot snapshot =
        snapshotOf(stateOf(withComposition(oneUnit("u-1", H11, false))), SimosTimestamp.of(13));
    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);

    Unit unit = back.state().units().get(new UnitId("u-1"));
    assertThat(unit.equipment())
        .as("装备表逐条带回且顺序不变")
        .containsExactly(
            new CompositionEntry("火炮", 12),
            new CompositionEntry("步枪", 340),
            new CompositionEntry("弹药车", 6));
  }

  /** 变更集也带得动装备表：{@code between} ⇒ 编码 ⇒ 解码 ⇒ {@code apply} 逐值重建目标，一条不丢。 */
  @Test
  void changeSetRoundTripsACompositionChange() {
    UnitState base = stateOf(oneUnit("u-1", H11, false));
    UnitState target = stateOf(withComposition(oneUnit("u-1", H11, false)));
    UnitChangeSet encoded =
        (UnitChangeSet)
            CODEC.decodeChangeSet(CODEC.encodeChangeSet(UnitChangeSet.between(base, target)));
    UnitState applied = UnitChangeSet.apply(encoded, base);

    assertThat(applied).as("整表变更也必须过线并逐条重建（铁律 5）").isEqualTo(target);
    assertThat(applied.units().get(new UnitId("u-1")).equipment())
        .containsExactly(
            new CompositionEntry("火炮", 12),
            new CompositionEntry("步枪", 340),
            new CompositionEntry("弹药车", 6));
  }

  /**
   * ★ **视野半径（权限阶段 Task 1 / spec §4.1）的线格式往返**：非缺省值必须逐字段重建出来。
   *
   * <p>★ **为什么这条比内存往返强**（判据强度的说明，不是冗余）：{@code UnitChangeSet} 是 {@code FieldDelta<Unit>} 的
   * **实体粒度**形态——差异里存的**就是 {@code Unit} 对象本身**，内存往返只是把同一个对象递回来 ⇒ 它**证不了** Unit 自己的字段没丢。只有过线（Jackson
   * 逐分量写 + 逐分量读）才是真正的逐字段重建。
   */
  @Test
  void snapshotRoundTripsANonDefaultVisionRadius() {
    UnitSnapshot snapshot =
        snapshotOf(stateOf(oneUnit("u-1", H11, false, 3)), SimosTimestamp.of(12));
    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
    assertThat(back.state().units().get(new UnitId("u-1")).visionRadius())
        .as("线格式必须真的带着 3 回来（不是掉回缺省 1）")
        .isEqualTo(3);
  }

  /** 变更集也带得动视野半径：{@code between} ⇒ 编码 ⇒ 解码 ⇒ {@code apply} 逐字段重建出目标。 */
  @Test
  void changeSetRoundTripsAVisionRadiusChange() {
    UnitState base = stateOf(oneUnit("u-1", H11, false, Unit.DEFAULT_VISION_RADIUS));
    UnitState target = stateOf(oneUnit("u-1", H11, false, 3));
    UnitChangeSet encoded =
        (UnitChangeSet)
            CODEC.decodeChangeSet(CODEC.encodeChangeSet(UnitChangeSet.between(base, target)));
    assertThat(UnitChangeSet.apply(encoded, base)).as("只改视野半径也必须能过线并重建（铁律 5）").isEqualTo(target);
  }

  /**
   * ★★ **非空管辖（辖区阶段 5）的线格式往返**：区域→税率逐对、三个 cap、行政能力都必须过线，**map 迭代序**也逐值保留 （税率的序是展示/结算的稳定序，不能被哈希序替换）。
   */
  @Test
  void snapshotRoundTripsANonEmptyJurisdiction() {
    RegionId r9 = new RegionId("r-9");
    RegionId r1 = new RegionId("r-1");
    RegionId r5 = new RegionId("r-5");
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    rates.put(r9, 900L);
    rates.put(r1, 100L);
    rates.put(r5, 500L);
    Jurisdiction jurisdiction = new Jurisdiction(rates, 11L, 22L, 33L, 250);

    UnitSnapshot snapshot =
        snapshotOf(
            stateOf(oneUnitWithJurisdiction("u-1", H11, Optional.of(jurisdiction))),
            SimosTimestamp.of(12));
    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back).as("整份快照往返相等").isEqualTo(snapshot);
    Jurisdiction decoded = back.state().units().get(new UnitId("u-1")).jurisdiction().orElseThrow();
    assertThat(decoded).as("record 五个组件逐值相等").isEqualTo(jurisdiction);
    assertThat(new ArrayList<>(decoded.taxRatePerMilleByRegion().keySet()))
        .as("★ 含 map 序：插入序不能被哈希序替换")
        .containsExactly(r9, r1, r5);
    assertThat(decoded.taxRatePerMilleByRegion())
        .containsExactly(Map.entry(r9, 900L), Map.entry(r1, 100L), Map.entry(r5, 500L));
    assertThat(decoded.levyGrainCapPerCommand()).isEqualTo(11L);
    assertThat(decoded.levyMoneyCapPerCommand()).isEqualTo(22L);
    assertThat(decoded.levyManpowerCapPerCommand()).isEqualTo(33L);
    assertThat(decoded.administrationPerMille()).isEqualTo(250);
  }

  /** ★ empty Optional 过线仍是 empty（不是 `{"present":…}`、也不是 null 指针）；键仍在线格式里。 */
  @Test
  void snapshotRoundTripsAnEmptyJurisdictionOptional() {
    UnitSnapshot snapshot =
        snapshotOf(
            stateOf(oneUnitWithJurisdiction("u-1", H11, Optional.empty())), SimosTimestamp.of(12));
    String json = CODEC.encodeSnapshot(snapshot);
    assertThat(json).as("empty Optional 写成 null（键不消失）").contains("\"jurisdiction\":null");

    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(json);

    assertThat(back).isEqualTo(snapshot);
    assertThat(back.state().units().get(new UnitId("u-1")).jurisdiction())
        .as("empty ⇒ 往返仍 empty")
        .isEmpty();
  }

  /** ★ 空 map 的管辖是"present 但无区域"（撤销全部管辖的表示），**不是** Optional.empty——两者必须分得开。 */
  @Test
  void snapshotRoundTripsAPresentJurisdictionWithEmptyRegionMap() {
    Jurisdiction jurisdiction = new Jurisdiction(new LinkedHashMap<>(), 4L, 5L, 6L, 700);
    UnitSnapshot snapshot =
        snapshotOf(
            stateOf(oneUnitWithJurisdiction("u-1", H11, Optional.of(jurisdiction))),
            SimosTimestamp.of(12));

    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back).isEqualTo(snapshot);
    assertThat(back.state().units().get(new UnitId("u-1")).jurisdiction())
        .as("present+空 map 不能被读回成 Optional.empty")
        .contains(jurisdiction);
  }

  /**
   * ★★ **旧档缺 {@code jurisdiction} 键**（辖区阶段 5 之前的快照）：读回后必须是 **empty**，其余字段逐值活着。
   *
   * <p>做法照 {@code MapCodecLegacyTest}：用**新** codec 编一份新形状快照，再把 {@code state.units.u-1} 里的 {@code
   * jurisdiction} 键删掉——信封与其余 14 个分量都是真实字节，只有这一处是旧形状。若构造期不把缺参归一成 empty， "整个世界打不开"（旧档兼容的落点）。
   */
  @Test
  void legacySnapshotWithoutJurisdictionKeyDecodesToEmptyJurisdiction() throws Exception {
    Jurisdiction jurisdiction = new Jurisdiction(Map.of(new RegionId("r-1"), 100L), 1L, 2L, 3L, 4);
    UnitSnapshot snapshot =
        snapshotOf(
            stateOf(oneUnitWithJurisdiction("u-1", H11, Optional.of(jurisdiction))),
            SimosTimestamp.of(12));

    ObjectMapper treeMapper = new ObjectMapper();
    ObjectNode root = (ObjectNode) treeMapper.readTree(CODEC.encodeSnapshot(snapshot));
    ObjectNode unitNode = (ObjectNode) root.get("state").get("units").get("u-1");
    assertThat(unitNode.has("jurisdiction")).as("前置：新形状确实写了该键（否则删键用例是恒真）").isTrue();
    unitNode.remove("jurisdiction");

    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(root.toString());

    Unit unit = back.state().units().get(new UnitId("u-1"));
    assertThat(unit.jurisdiction()).as("旧档缺键 ⇒ 空管辖（不是 null、不抛）").isEmpty();
    assertThat(unit.name()).as("其余字段照常读回").isEqualTo("单位 u-1");
    assertThat(unit.equipment())
        .as("旧档缺 jurisdiction 键也必须逐值带回装备表（不是只剩一个总数）")
        .containsExactly(new CompositionEntry("旗帜", 3));
    assertThat(unit.position().valueAt(T0)).contains(H11);
    assertThat(unit.visionRadius()).isEqualTo(Unit.DEFAULT_VISION_RADIUS);
  }

  /** 变更集也带得动管辖：{@code between} ⇒ 编码 ⇒ 解码 ⇒ {@code apply} 逐值重建目标。 */
  @Test
  void changeSetRoundTripsAJurisdictionChange() {
    UnitState base = stateOf(oneUnitWithJurisdiction("u-1", H11, Optional.empty()));
    Jurisdiction target =
        new Jurisdiction(
            orderedRates(new RegionId("r-9"), 900L, new RegionId("r-1"), 100L), 7L, 8L, 9L, 300);
    UnitState changed = stateOf(oneUnitWithJurisdiction("u-1", H11, Optional.of(target)));

    UnitChangeSet encoded =
        (UnitChangeSet)
            CODEC.decodeChangeSet(CODEC.encodeChangeSet(UnitChangeSet.between(base, changed)));

    assertThat(UnitChangeSet.apply(encoded, base)).as("管辖变更也必须过线并逐值重建（铁律 5）").isEqualTo(changed);
  }

  /**
   * ★★ **非空 GOV 编制的线格式往返**（阶段 9）：{@code staff} 逐键逐值（含**保序**）、{@code policy} 五字段（含 {@code staffCap}
   * 的保序）、{@code superiorGov} 的 Optional、{@code level} 都要过线；且线格式里确实带 {@code "@class":"gov"}
   * 子类型标记（不靠某台 mapper 的 mixin）。
   */
  @Test
  void snapshotRoundTripsANonEmptyGovModule() {
    Map<StaffRole, Long> staff =
        orderedStaff(
            Map.entry(StaffRole.YAMEN, 2L),
            Map.entry(StaffRole.POST, 1L),
            Map.entry(StaffRole.SCRIBE, 5L));
    Map<StaffRole, Long> staffCap =
        orderedStaff(Map.entry(StaffRole.POST, 9L), Map.entry(StaffRole.SCRIBE, 6L));
    GovernmentPostOfHousehold externalA =
        new GovernmentPostOfHousehold(
            HH_EXT_A, StaffRole.POST, GovernmentLevel.CENTRAL, false, "tier-1");
    GovernmentPostOfHousehold externalB =
        new GovernmentPostOfHousehold(
            HH_EXT_B, StaffRole.YAMEN, GovernmentLevel.PROVINCE, true, "");
    GovernmentFormation gov =
        new GovernmentFormation(
            staff,
            orderedPosts(
                Map.entry(
                    HH_B,
                    new GovernmentPostOfHousehold(
                        HH_B, StaffRole.SCRIBE, GovernmentLevel.CENTRAL, true, "tier-2")),
                Map.entry(
                    HH_A,
                    new GovernmentPostOfHousehold(
                        HH_A, StaffRole.YAMEN, GovernmentLevel.PROVINCE, false))),
            new OfficePolicy(111L, 222L, 3L, 7L, staffCap),
            Optional.of(new UnitId("g-9")),
            GovernmentLevel.PROVINCE,
            orderedPosts(Map.entry(HH_EXT_A, externalA), Map.entry(HH_EXT_B, externalB)));
    Jurisdiction jurisdiction =
        new Jurisdiction(orderedRates(new RegionId("r-1"), 100L), 1L, 2L, 3L, 4);
    UnitSnapshot snapshot =
        snapshotOf(
            stateOf(oneUnitWithModule("u-1", H11, Optional.of(jurisdiction), Optional.of(gov), 3)),
            SimosTimestamp.of(12));

    String json = CODEC.encodeSnapshot(snapshot);
    assertThat(json).as("子类型信息钉在类型上：@class=gov").contains("\"@class\":\"gov\"");
    assertThat(json).as("staff 的值逐字在线").contains("\"SCRIBE\":5");
    assertThat(json).as("Z3d 外部岗位用 externalPosts 键落盘").contains("\"externalPosts\"");

    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(json);

    assertThat(back).as("整份快照往返相等").isEqualTo(snapshot);
    Unit unit = back.state().units().get(new UnitId("u-1"));
    GovernmentFormation decoded = (GovernmentFormation) unit.module().orElseThrow();
    assertThat(new ArrayList<>(decoded.staff().keySet()))
        .as("★ staff 保序：插入序 YAMEN→POST→SCRIBE 不能被哈希序替换")
        .containsExactly(StaffRole.YAMEN, StaffRole.POST, StaffRole.SCRIBE);
    assertThat(decoded.staff())
        .containsExactly(
            Map.entry(StaffRole.YAMEN, 2L),
            Map.entry(StaffRole.POST, 1L),
            Map.entry(StaffRole.SCRIBE, 5L));
    assertThat(decoded.policy().grainPerStaffPerTick()).isEqualTo(111L);
    assertThat(decoded.policy().clothPerStaffPerCycle()).isEqualTo(222L);
    assertThat(decoded.policy().moneyPerStaffPerTick()).isEqualTo(3L);
    assertThat(decoded.policy().retirementPerStaff()).isEqualTo(7L);
    assertThat(new ArrayList<>(decoded.policy().staffCap().keySet()))
        .as("staffCap 同样保序")
        .containsExactly(StaffRole.POST, StaffRole.SCRIBE);
    assertThat(decoded.policy().staffCap())
        .containsExactly(Map.entry(StaffRole.POST, 9L), Map.entry(StaffRole.SCRIBE, 6L));
    assertThat(decoded.superiorGov()).as("Optional 的 present 侧逐值在线").contains(new UnitId("g-9"));
    assertThat(decoded.level()).isEqualTo(GovernmentLevel.PROVINCE);
    assertThat(json)
        .as("S3b 的领导家户配置用旧线格式键 householdPosts 落盘")
        .contains("\"householdPosts\"")
        .contains("hh-b");
    assertThat(new ArrayList<>(decoded.governmentPostsOfHousehold().keySet()))
        .as("governmentPostsOfHousehold 保序：插入序 HH_B→HH_A 不能被哈希序替换")
        .containsExactly(HH_B, HH_A);
    assertThat(decoded.governmentPostsOfHousehold())
        .containsExactly(
            Map.entry(
                HH_B,
                new GovernmentPostOfHousehold(
                    HH_B, StaffRole.SCRIBE, GovernmentLevel.CENTRAL, true, "tier-2")),
            Map.entry(
                HH_A,
                new GovernmentPostOfHousehold(
                    HH_A, StaffRole.YAMEN, GovernmentLevel.PROVINCE, false)));
    assertThat(new ArrayList<>(decoded.externalPosts().keySet()))
        .as("★ externalPosts 跨线格式保序：插入序 HH_EXT_A→HH_EXT_B 不能被哈希序替换")
        .containsExactly(HH_EXT_A, HH_EXT_B);
    assertThat(decoded.externalPosts())
        .as("externalPosts 逐值往返（含 tierId 与空串 legacy）")
        .containsExactly(Map.entry(HH_EXT_A, externalA), Map.entry(HH_EXT_B, externalB));
    assertThat(unit.households())
        .as("GOV 单位必须带自己的政府家户，且领导配置家户也在列表里")
        .contains(GovernmentHouseholds.of("u-1"), HH_B, HH_A);
    assertThat(unit.households())
        .as("外部岗位家户保留原单位/位置：不得被编进 Unit.households")
        .doesNotContain(HH_EXT_A, HH_EXT_B);
    assertThat(unit.jurisdiction()).as("module 往返不得顺手吞掉 jurisdiction").contains(jurisdiction);
    assertThat(unit.visionRadius()).as("非缺省视野半径也要活着").isEqualTo(3);
  }

  /**
   * ★ Z3d 旧档兼容：把新形状快照里的 {@code externalPosts} 键删掉 ⇒ 读回**空表**（不是 null、不抛）， 且内部岗位/staff/households
   * 逐值活着。
   */
  @Test
  void legacySnapshotWithoutExternalPostsKeyDecodesToEmptyMap() throws Exception {
    GovernmentPostOfHousehold external =
        new GovernmentPostOfHousehold(
            HH_EXT_A, StaffRole.POST, GovernmentLevel.CENTRAL, false, "tier-1");
    GovernmentFormation gov =
        new GovernmentFormation(
            orderedStaff(Map.entry(StaffRole.SCRIBE, 2L)),
            orderedPosts(
                Map.entry(
                    HH_A,
                    new GovernmentPostOfHousehold(
                        HH_A, StaffRole.YAMEN, GovernmentLevel.PROVINCE, false))),
            OfficePolicy.defaults(),
            Optional.empty(),
            GovernmentLevel.PROVINCE,
            orderedPosts(Map.entry(HH_EXT_A, external)));
    UnitSnapshot snapshot =
        snapshotOf(
            stateOf(oneUnitWithModule("u-1", H11, Optional.empty(), Optional.of(gov))),
            SimosTimestamp.of(18));

    ObjectMapper treeMapper = new ObjectMapper();
    ObjectNode root = (ObjectNode) treeMapper.readTree(CODEC.encodeSnapshot(snapshot));
    ObjectNode moduleNode = (ObjectNode) root.get("state").get("units").get("u-1").get("module");
    assertThat(moduleNode.has("externalPosts")).as("前置：新形状确实写了该键（否则删键用例是恒真）").isTrue();
    moduleNode.remove("externalPosts");

    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(root.toString());
    GovernmentFormation decoded =
        (GovernmentFormation) back.state().units().get(new UnitId("u-1")).module().orElseThrow();

    assertThat(decoded.externalPosts()).as("旧档缺键 ⇒ 空表（不是 null、不抛）").isEmpty();
    assertThat(decoded.hasAnyPosts()).as("内部岗位仍在 ⇒ 旧档不是‘无岗位’").isTrue();
    assertThat(decoded.governmentPostsOfHousehold())
        .containsExactly(
            Map.entry(
                HH_A,
                new GovernmentPostOfHousehold(
                    HH_A, StaffRole.YAMEN, GovernmentLevel.PROVINCE, false)));
    assertThat(decoded.staff()).containsExactly(Map.entry(StaffRole.SCRIBE, 2L));
    assertThat(back.state().units().get(new UnitId("u-1")).households())
        .as("旧档读回不得动 households")
        .containsExactly(GovernmentHouseholds.of("u-1"), HH_A);
  }

  /** ★★ **Army 编制的线格式往返**：{@code "@class":"army"} + present/empty 两侧的 {@code masterGov} + role。 */
  @Test
  void snapshotRoundTripsBothSidesOfArmyMasterGov() {
    Jurisdiction jurisdiction =
        new Jurisdiction(orderedRates(new RegionId("r-1"), 100L), 0L, 0L, 0L, 0);
    ArmyFormation withMaster = new ArmyFormation(Optional.of(new UnitId("g-1")), "garrison");
    UnitSnapshot snapshotWithMaster =
        snapshotOf(
            stateOf(
                oneUnitWithModule("u-1", H11, Optional.of(jurisdiction), Optional.of(withMaster))),
            SimosTimestamp.of(13));
    String jsonWithMaster = CODEC.encodeSnapshot(snapshotWithMaster);
    assertThat(jsonWithMaster).contains("\"@class\":\"army\"");
    assertThat(jsonWithMaster).contains("\"role\":\"garrison\"");
    assertThat(jsonWithMaster).contains("\"masterGov\":{\"value\":\"g-1\"}");

    UnitSnapshot backWithMaster = (UnitSnapshot) CODEC.decodeSnapshot(jsonWithMaster);
    assertThat(backWithMaster).isEqualTo(snapshotWithMaster);
    ArmyFormation decoded =
        (ArmyFormation)
            backWithMaster.state().units().get(new UnitId("u-1")).module().orElseThrow();
    assertThat(decoded.masterGov()).contains(new UnitId("g-1"));
    assertThat(decoded.role()).isEqualTo("garrison");

    ArmyFormation withoutMaster = new ArmyFormation(Optional.empty(), "militia");
    UnitSnapshot snapshotWithoutMaster =
        snapshotOf(
            stateOf(oneUnitWithModule("u-1", H11, Optional.empty(), Optional.of(withoutMaster))),
            SimosTimestamp.of(14));
    UnitSnapshot backWithoutMaster =
        (UnitSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshotWithoutMaster));
    assertThat(backWithoutMaster).isEqualTo(snapshotWithoutMaster);
    assertThat(
            ((ArmyFormation)
                    backWithoutMaster.state().units().get(new UnitId("u-1")).module().orElseThrow())
                .masterGov())
        .as("empty Optional 过线仍是 empty（不是 null 指针、也不是 present）")
        .isEmpty();
  }

  /** ★ empty module 过线仍是 empty，且键在字节里是显式 {@code null}（键不消失）。 */
  @Test
  void snapshotRoundTripsAnEmptyModuleOptional() {
    Jurisdiction jurisdiction =
        new Jurisdiction(orderedRates(new RegionId("r-1"), 100L), 0L, 0L, 0L, 0);
    UnitSnapshot snapshot =
        snapshotOf(
            stateOf(oneUnitWithModule("u-1", H11, Optional.of(jurisdiction), Optional.empty())),
            SimosTimestamp.of(15));

    String json = CODEC.encodeSnapshot(snapshot);
    assertThat(json).as("empty Optional 写成 null（键不消失）").contains("\"module\":null");

    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(json);

    assertThat(back).isEqualTo(snapshot);
    assertThat(back.state().units().get(new UnitId("u-1")).module()).isEmpty();
    assertThat(back.state().units().get(new UnitId("u-1")).jurisdiction())
        .as("empty module 不得把非空管辖带走")
        .contains(jurisdiction);
  }

  /** ★ GOV 的 {@code superiorGov} **empty 侧**也要过线：写 null、读回 empty（不是 present、不是丢键）。 */
  @Test
  void snapshotRoundTripsAGovWithEmptySuperior() {
    GovernmentFormation gov =
        new GovernmentFormation(
            orderedStaff(Map.entry(StaffRole.SCRIBE, 2L)),
            Map.of(),
            OfficePolicy.defaults(),
            Optional.empty(),
            GovernmentLevel.CENTRAL,
            Map.of());
    UnitSnapshot snapshot =
        snapshotOf(
            stateOf(oneUnitWithModule("u-1", H11, Optional.empty(), Optional.of(gov))),
            SimosTimestamp.of(17));

    String json = CODEC.encodeSnapshot(snapshot);
    assertThat(json).as("empty superiorGov 写成 null").contains("\"superiorGov\":null");

    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(json);

    assertThat(back).isEqualTo(snapshot);
    GovernmentFormation decoded =
        (GovernmentFormation) back.state().units().get(new UnitId("u-1")).module().orElseThrow();
    assertThat(decoded.superiorGov()).as("中央 = 无上级，读回仍 empty").isEmpty();
    assertThat(decoded.level()).isEqualTo(GovernmentLevel.CENTRAL);
    assertThat(decoded.staff()).containsExactly(Map.entry(StaffRole.SCRIBE, 2L));
  }

  /**
   * ★★ **旧档缺 {@code module} 键**（阶段 9 之前的快照）：读回必须是 **empty**，且 {@code jurisdiction}、视野半径与其余字段逐值活着
   * ——旧档兼容的落点（构造器把 Jackson 的缺参 null 归一成 empty）。
   */
  @Test
  void legacySnapshotWithoutModuleKeyDecodesToEmptyAndKeepsOtherFields() throws Exception {
    Map<StaffRole, Long> staff = orderedStaff(Map.entry(StaffRole.SCRIBE, 5L));
    GovernmentFormation gov =
        new GovernmentFormation(
            staff,
            Map.of(),
            OfficePolicy.defaults(),
            Optional.empty(),
            GovernmentLevel.CENTRAL,
            Map.of());
    Jurisdiction jurisdiction =
        new Jurisdiction(orderedRates(new RegionId("r-9"), 900L), 11L, 22L, 33L, 250);
    UnitSnapshot snapshot =
        snapshotOf(
            stateOf(oneUnitWithModule("u-1", H11, Optional.of(jurisdiction), Optional.of(gov), 4)),
            SimosTimestamp.of(16));

    ObjectMapper treeMapper = new ObjectMapper();
    ObjectNode root = (ObjectNode) treeMapper.readTree(CODEC.encodeSnapshot(snapshot));
    ObjectNode unitNode = (ObjectNode) root.get("state").get("units").get("u-1");
    assertThat(unitNode.has("module")).as("前置：新形状确实写了该键（否则删键用例是恒真）").isTrue();
    unitNode.remove("module");

    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(root.toString());

    Unit unit = back.state().units().get(new UnitId("u-1"));
    assertThat(unit.module()).as("旧档缺键 ⇒ 空编制（不是 null、不抛）").isEmpty();
    assertThat(unit.jurisdiction()).as("其余字段逐值活着：管辖").contains(jurisdiction);
    assertThat(unit.visionRadius()).as("其余字段逐值活着：视野半径").isEqualTo(4);
    assertThat(unit.name()).isEqualTo("单位 u-1");
    assertThat(unit.equipment())
        .as("旧档缺 module 键也必须逐值带回装备表（不是只剩一个总数）")
        .containsExactly(new CompositionEntry("旗帜", 3));
    assertThat(unit.position().valueAt(T0)).contains(H11);
  }

  /** 变更集也带得动编制（含 Z3d 的 externalPosts）：{@code between} ⇒ 编码 ⇒ 解码 ⇒ {@code apply} 逐值重建目标。 */
  @Test
  void changeSetRoundTripsAGovModuleChange() {
    UnitState base = stateOf(oneUnitWithModule("u-1", H11, Optional.empty(), Optional.empty()));
    GovernmentPostOfHousehold external =
        new GovernmentPostOfHousehold(
            HH_EXT_A, StaffRole.POST, GovernmentLevel.CENTRAL, false, "tier-2");
    GovernmentFormation target =
        new GovernmentFormation(
            orderedStaff(Map.entry(StaffRole.YAMEN, 2L), Map.entry(StaffRole.SCRIBE, 5L)),
            orderedPosts(
                Map.entry(
                    HH_A,
                    new GovernmentPostOfHousehold(
                        HH_A, StaffRole.YAMEN, GovernmentLevel.PROVINCE, false, "tier-1"))),
            new OfficePolicy(7L, 8L, 9L, 10L, orderedStaff(Map.entry(StaffRole.SCRIBE, 40L))),
            Optional.of(new UnitId("g-central")),
            GovernmentLevel.PROVINCE,
            orderedPosts(Map.entry(HH_EXT_A, external)));
    UnitState changed =
        stateOf(oneUnitWithModule("u-1", H11, Optional.empty(), Optional.of(target)));

    UnitChangeSet encoded =
        (UnitChangeSet)
            CODEC.decodeChangeSet(CODEC.encodeChangeSet(UnitChangeSet.between(base, changed)));

    UnitState applied = UnitChangeSet.apply(encoded, base);
    assertThat(applied).as("编制变更也必须过线并逐值重建（铁律 5）").isEqualTo(changed);
    GovernmentFormation decoded =
        (GovernmentFormation) applied.units().get(new UnitId("u-1")).module().orElseThrow();
    assertThat(decoded.externalPosts())
        .as("externalPosts 随变更集逐值过线（不是只在快照路径）")
        .containsExactly(Map.entry(HH_EXT_A, external));
    assertThat(applied.units().get(new UnitId("u-1")).households())
        .as("households 也随变更集过线（GOV 家户 + 领导配置家户；外部户不得入列）")
        .contains(GovernmentHouseholds.of("u-1"), HH_A)
        .doesNotContain(HH_EXT_A);
  }

  /** 变更集往返：四条变体各造一条（Unchanged / Upsert / Remove / Patch），逐条过线。 */
  @Test
  void changeSetRoundTripsWithAllFourDeltaVariants() {
    UnitState one =
        UnitState.empty().withUnits(Map.of(new UnitId("u-1"), oneUnit("u-1", H11, false)));
    UnitState other =
        UnitState.empty().withUnits(Map.of(new UnitId("u-2"), oneUnit("u-2", H12, false)));

    UnitChangeSet unchanged = UnitChangeSet.between(one, one);
    UnitChangeSet upsert = UnitChangeSet.between(UnitState.empty(), one);
    UnitChangeSet remove = UnitChangeSet.between(one, UnitState.empty());
    UnitChangeSet patch = UnitChangeSet.between(one, other);

    // 前置：夹具确实落在了四条变体上
    assertThat(unchanged.units()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(upsert.units()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(remove.units()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(patch.units()).isInstanceOf(FieldDelta.Patch.class);

    assertThat((UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(unchanged)))
        .isEqualTo(unchanged);
    assertThat((UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(upsert)))
        .isEqualTo(upsert);
    assertThat((UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(remove)))
        .isEqualTo(remove);
    assertThat((UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(patch)))
        .isEqualTo(patch);
  }

  /** 值类型的绑定不能在读入侧丢成 Map：Unit 得还是 Unit。 */
  @Test
  void deltaValuesSurviveAsUnitNotAsMaps() {
    UnitChangeSet back =
        (UnitChangeSet)
            CODEC.decodeChangeSet(
                CODEC.encodeChangeSet(
                    UnitChangeSet.between(
                        UnitState.empty(),
                        UnitState.empty()
                            .withUnits(Map.of(new UnitId("u-1"), oneUnit("u-1", H11, false))))));
    FieldDelta.Upsert<Unit> upsert = (FieldDelta.Upsert<Unit>) back.units();
    assertThat(upsert.entries().get("u-1")).isInstanceOf(Unit.class);
  }

  /** 施加（C28）：新快照的 ref/timestamp 来自 newMeta，**不是** base 的；且 base 原样不动。 */
  @Test
  void applyProducesNewSnapshotStampedWithNewMeta() {
    UnitSnapshot base = snapshotOf(stateOf(oneUnit("u-1", H11, false)), SimosTimestamp.of(10));
    UnitState target =
        UnitState.empty().withUnits(Map.of(new UnitId("u-2"), oneUnit("u-2", H12, false)));
    UnitChangeSet changeSet = UnitChangeSet.between(base.state(), target);
    StateMeta newMeta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    UnitSnapshot applied = (UnitSnapshot) CODEC.apply(changeSet, base, newMeta);

    assertThat(applied.ref()).isEqualTo(newMeta.ref());
    assertThat(applied.timestamp()).isEqualTo(newMeta.timestamp());
    assertThat(applied.state()).isEqualTo(UnitChangeSet.apply(changeSet, base.state()));
    assertThat(base.state().units()).containsOnlyKeys(new UnitId("u-1"));
  }

  /**
   * ★ 下转型守卫的自证：喂一个**别的模块的切片**，{@code apply} 与 {@code encodeSnapshot} 都必须当场 {@link
   * IllegalStateException}。
   *
   * <p>这条用例**只在改成 {@code instanceof} 之后**才有判别力——裸 cast 同样会抛（{@code ClassCastException}），
   * 所以断言钉的是**异常类型 + 消息**，不是"抛了就算"。
   */
  @Test
  void applyAndEncodeSnapshotRejectForeignSlice() {
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));
    UnitSnapshot base = snapshotOf(stateOf(oneUnit("u-1", H11, false)), SimosTimestamp.of(10));
    UnitChangeSet changeSet = UnitChangeSet.between(base.state(), base.state());
    StateMeta meta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(() -> CODEC.encodeSnapshot(foreign))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 UnitSnapshot");
    assertThatThrownBy(() -> CODEC.apply(changeSet, foreign, meta))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 UnitSnapshot");
  }

  /** 别的模块的切片：本测试只借它的**类型**，不借语义。 */
  private record ForeignSlice(StateRef ref, SimosTimestamp timestamp) implements Snapshot {

    @Override
    public String namespace() {
      return "map";
    }
  }

  /** ★ T1：含 2 条链 + 同一 unit 多属的快照往返（{@code CommandChainId} 作 Map 键的靶子）。 */
  @Test
  void snapshotRoundTripsCommandChainsWithASharedMember() {
    UnitSnapshot snapshot =
        snapshotOf(
            stateWithChains(oneUnit("u-1", H11, false), oneUnit("u-2", H12, false)),
            SimosTimestamp.of(12));
    UnitSnapshot back = (UnitSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
    assertThat(back.state().commandChains())
        .containsOnlyKeys(new CommandChainId("c-1"), new CommandChainId("c-2"));
    assertThat(back.state().commandChains().get(new CommandChainId("c-1")).members())
        .containsExactlyInAnyOrder(new UnitId("u-1"), new UnitId("u-2"));
  }

  /** 变更集里的 {@code commandChains} 组件也逐值往返。 */
  @Test
  void changeSetRoundTripsCommandChainUpserts() {
    UnitState target = stateWithChains(oneUnit("u-1", H11, false), oneUnit("u-2", H12, false));
    UnitChangeSet cs = UnitChangeSet.between(UnitState.empty(), target);
    assertThat(cs.commandChains()).isInstanceOf(FieldDelta.Upsert.class);

    assertThat((UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(cs))).isEqualTo(cs);
  }

  /**
   * ★★ 判据（spec §八 #19 / T5 的 m3）：**`CommandChainId` 作 Map 键的往返**——链由**命令层的 op** （{@code
   * UnitOperations.createChain}）造出（不是手工 `new UnitState(units, chains)`），两条链共用同一批成员，
   * **快照与变更集各往返一次**。
   *
   * <p>★ m3 的靶子是 `UnitCodec` 里那行 `CommandChainId` 的 Map 键反序列化器（与 `UnitId` 那行相邻）：删掉它，解码落在
   * **类型解析期**（`Cannot find a (Map) Key deserializer for type …`），本用例与 T1 的往返用例各钉一面——这条钉的是 "**op
   * 产出的**状态"那一面。
   */
  @Test
  void commandChainIdsSurviveRoundTripAsMapKeysOnOpProducedStates() {
    Unit u1 = oneUnit("u-1", H11, false);
    Unit u2 = oneUnit("u-2", H12, false);
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(u1.id(), u1);
    units.put(u2.id(), u2);
    UnitState base = UnitState.empty().withUnits(units);
    UnitState target =
        UnitOperations.createChain(
            UnitOperations.createChain(
                base,
                new CommandChain(
                    new CommandChainId("c-1"), "第一链", u1.id(), Set.of(u1.id(), u2.id()))),
            new CommandChain(new CommandChainId("c-2"), "第二链", u2.id(), Set.of(u2.id(), u1.id())));
    assertThat(target.commandChains()).as("前提：两条链真的建上了").hasSize(2);

    UnitSnapshot snapshot = snapshotOf(target, SimosTimestamp.of(13));
    UnitSnapshot snapshotBack = (UnitSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(snapshotBack).isEqualTo(snapshot);
    assertThat(snapshotBack.state().commandChains().keySet().iterator().next())
        .as("键得还是 CommandChainId（不是 String、也不是 Map）")
        .isInstanceOf(CommandChainId.class);
    assertThat(snapshotBack.state().commandChains())
        .containsOnlyKeys(new CommandChainId("c-1"), new CommandChainId("c-2"));
    assertThat(snapshotBack.state().commandChains().get(new CommandChainId("c-2")).commander())
        .as("共享成员那条链的 commander 逐值还在")
        .isEqualTo(u2.id());

    UnitChangeSet cs = UnitChangeSet.between(base, target);
    assertThat(cs.commandChains()).isInstanceOf(FieldDelta.Upsert.class);
    UnitChangeSet changeSetBack = (UnitChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(cs));
    assertThat(changeSetBack).isEqualTo(cs);
    assertThat(UnitChangeSet.apply(changeSetBack, base))
        .as("往返后的变更集照样重建出目标（铁律 5）")
        .isEqualTo(target);
  }

  // ── 夹具 ──

  private static UnitState stateOf(Unit unit) {
    return UnitState.empty().withUnits(Map.of(unit.id(), unit));
  }

  /** 只换装备表（其余组件原样带过）——顺序往返用例的夹具。 */
  private static Unit withComposition(Unit base) {
    return new Unit(
        base.id(),
        base.name(),
        base.parent(),
        base.position(),
        List.of(
            new CompositionEntry("火炮", 12),
            new CompositionEntry("步枪", 340),
            new CompositionEntry("弹药车", 6)),
        base.speed(),
        base.mobilityPerMille(),
        base.movement(),
        base.status(),
        base.attached(),
        base.offset(),
        base.rejoinTarget(),
        base.visionRadius(),
        base.jurisdiction(),
        base.module(),
        base.stateDescriptions());
  }

  private static Unit oneUnit(String id, HexCoord at, boolean inTransit) {
    return oneUnit(id, at, inTransit, Unit.DEFAULT_VISION_RADIUS);
  }

  /** 视野半径逐值给（Task 1 的往返夹具要非缺省的 3）。 */
  private static Unit oneUnit(String id, HexCoord at, boolean inTransit, int visionRadius) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(at))), List.of(), null),
        List.of(new CompositionEntry("旗帜", 3)),
        2,
        1000,
        inTransit
            ? Optional.of(
                new Movement(new Route(List.of(H11, H12), List.of(H11, H12)), T0, 2, 1000))
            : Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty(),
        visionRadius);
  }

  /** 视野半径取缺省、管辖逐值给的 canonical 15 参形态（辖区阶段 5 的往返夹具专用）。 */
  private static Unit oneUnitWithJurisdiction(
      String id, HexCoord at, Optional<Jurisdiction> jurisdiction) {
    return oneUnitWithModule(id, at, jurisdiction, Optional.empty());
  }

  /** 视野半径取缺省的重载（调用点多在 jurisdiction 夹具上）。 */
  private static Unit oneUnitWithModule(
      String id, HexCoord at, Optional<Jurisdiction> jurisdiction, Optional<UnitModule> module) {
    return oneUnitWithModule(id, at, jurisdiction, module, Unit.DEFAULT_VISION_RADIUS);
  }

  /**
   * 视野半径逐值给的 canonical 17 参形态（阶段 9 的编制往返夹具；S3b 起 households 也逐值给）。
   *
   * <p>★ 带 {@link GovernmentFormation} 的单位必须把政府家户 {@code hh-gov-<id>} 编进 households（{@code
   * UnitState} 构造期不变量），领导家户配置的键也必须在列表里；本夹具按这两条自动补齐，好让各往返用例的靶子落在编解码上。
   */
  private static Unit oneUnitWithModule(
      String id,
      HexCoord at,
      Optional<Jurisdiction> jurisdiction,
      Optional<UnitModule> module,
      int visionRadius) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(at))), List.of(), null),
        List.of(new CompositionEntry("旗帜", 3)),
        2,
        1000,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(T0, true)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty(),
        visionRadius,
        jurisdiction,
        module,
        Map.of(),
        householdsFor(id, module));
  }

  /** 单位 households：GOV 编制 ⇒ 政府家户 + 领导配置家户（保序、去重）；其余编制 ⇒ 空表。 */
  private static List<HouseholdId> householdsFor(String id, Optional<UnitModule> module) {
    if (!(module.orElse(null) instanceof GovernmentFormation governmentFormation)) {
      return List.of();
    }
    List<HouseholdId> households = new ArrayList<>();
    households.add(GovernmentHouseholds.of(id));
    for (HouseholdId household : governmentFormation.governmentPostsOfHousehold().keySet()) {
      if (!households.contains(household)) {
        households.add(household);
      }
    }
    return households;
  }

  /** 保序的领导家户配置表（判据同 `staff`：顺序不被哈希序替换）。 */
  @SafeVarargs
  private static Map<HouseholdId, GovernmentPostOfHousehold> orderedPosts(
      Map.Entry<HouseholdId, GovernmentPostOfHousehold>... entries) {
    Map<HouseholdId, GovernmentPostOfHousehold> posts = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, GovernmentPostOfHousehold> entry : entries) {
      posts.put(entry.getKey(), entry.getValue());
    }
    return posts;
  }

  /** 保序的编制表（`staff`/`staffCap` 共用；判据要的是"顺序不被哈希序替换"）。 */
  @SafeVarargs
  private static Map<StaffRole, Long> orderedStaff(Map.Entry<StaffRole, Long>... entries) {
    Map<StaffRole, Long> staff = new LinkedHashMap<>();
    for (Map.Entry<StaffRole, Long> entry : entries) {
      staff.put(entry.getKey(), entry.getValue());
    }
    return staff;
  }

  /** 单键保序税率表（只有一条税率时要测"非空但小"的往返）。 */
  private static Map<RegionId, Long> orderedRates(RegionId only, long rate) {
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    rates.put(only, rate);
    return rates;
  }

  /** 两键的保序税率表（判据要的是"顺序不被哈希序替换"，不是大表）。 */
  private static Map<RegionId, Long> orderedRates(
      RegionId first, long firstRate, RegionId second, long secondRate) {
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    rates.put(first, firstRate);
    rates.put(second, secondRate);
    return rates;
  }

  private static UnitSnapshot snapshotOf(UnitState state, SimosTimestamp timestamp) {
    return new UnitSnapshot(
        new StateRef(new BranchId("main"), new RevisionId(3)), timestamp, state);
  }

  /** 两个单位 + 2 条链、两个单位**同属两条链**（多属）。 */
  private static UnitState stateWithChains(Unit one, Unit two) {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(one.id(), one);
    units.put(two.id(), two);
    Map<CommandChainId, CommandChain> chains = new LinkedHashMap<>();
    chains.put(
        new CommandChainId("c-1"),
        new CommandChain(new CommandChainId("c-1"), "第一链", one.id(), Set.of(one.id(), two.id())));
    chains.put(
        new CommandChainId("c-2"),
        new CommandChain(new CommandChainId("c-2"), "第二链", two.id(), Set.of(one.id(), two.id())));
    return new UnitState(units, chains);
  }
}

package io.mosire.simos.unit.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.generate.GenerationSpec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.map.terrain.TerrainType;
import io.mosire.simos.unit.ArmyFormation;
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.GovLevel;
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
import io.mosire.simos.unit.move.MovementCost;
import io.mosire.simos.unit.move.UnitMoves;
import io.mosire.simos.unit.spi.UnitTimeParticipant;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import io.mosire.simos.util.time.TimeRange;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★★ **阶段 9 编制模块（{@code Unit.module}）的领域操作面**：立编制命令的语义，以及最要紧的那一条 —— **全部生产拷贝点逐处不丢 {@code module}**。
 *
 * <p>★★ 拷贝点的装置照 {@code UnitJurisdictionOperationsTest}/{@code UnitVisionRadiusTest} 的 {@code
 * assertCopied} 形制：{@link #changedComponents} **逐 record 分量对拍** before/after，**不按名字引用** {@code
 * module} ⇒ 某个拷贝点漏传那一刻，{@code "module"} 自己出现在差集里，红点**直接指名丢的是哪个字段** （本仓最贵的教训：{@code MapData} 加字段时
 * {@code MapDiff} 没人提醒要跟上，四个字段静默漂移）。
 *
 * <p>★ 本文件的每个拷贝点夹具都**同时**给非空 {@code module} 与非空 {@code jurisdiction}：丢编制（第 16 分量）或顺手清掉管辖 （第 15
 * 分量）都会当场红。**Gov 与 Army 两种编制都跑**——只测一种的话，"拷贝点只认 GovFormation"这种坏实现会活下来。
 *
 * <p>★ 覆盖的生产写点：{@code UnitOperations} 的 {@code copy} 调用方（reparent / rename / setComposition /
 * applyCasualties / placeAt / planRoute / cancelRoute）、{@code copyFormation} 调用方（attachSubtree /
 * detachUnit / splitFormation / setOffset / reparentSubtree）、{@code
 * withSpeed}（mergeFormation）、{@code withStatus}（setStatus）、{@code
 * withRejoinTarget}（setRejoinTarget）、{@code withJurisdiction}（setJurisdiction / setTaxRate）、{@code
 * withModule}（setGovFormation / setArmyFormation），以及 {@code UnitMoves.evaluate} 的 frozen 视图、{@code
 * UnitTimeParticipant} 的 withPositionAndMovement / withMovement 两处。
 *
 * <p>★ 四条 GOV 编制编辑命令（{@code setGovPolicy}/{@code setGovSuperior}/{@code recruitStaff}/{@code
 * dismissStaff}）：逐条断言**只改目标字段、其余 15 组件不动**（{@link #changedComponents} 恰为 {@code module}）。
 */
class UnitModuleOperationsTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T1 = SimosTimestamp.of(1);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);
  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U2 = new UnitId("u-2");
  private static final UnitId U9 = new UnitId("u-9");
  private static final String MAP_ID = "Map1";
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  private static final RegionId RA = new RegionId("r-a");
  private static final RegionId RB = new RegionId("r-b");
  private static final RegionId RC = new RegionId("r-c");
  private static final RegionId RD = new RegionId("r-d");

  /** 非空 GOV 编制：三键、**故意不按枚举序**（YAMEN→POST→SCRIBE），policy 全非默认。 */
  private static final UnitModule GOV_MODULE =
      new GovFormation(
          orderedStaff(
              Map.entry(StaffRole.YAMEN, 2L),
              Map.entry(StaffRole.POST, 1L),
              Map.entry(StaffRole.SCRIBE, 5L)),
          new OfficePolicy(
              111L,
              222L,
              3L,
              7L,
              orderedStaff(Map.entry(StaffRole.POST, 9L), Map.entry(StaffRole.SCRIBE, 6L))),
          Optional.of(U9),
          GovLevel.PROVINCE);

  /** 非空 Army 编制：认领一个主子 + 非空 role。 */
  private static final UnitModule ARMY_MODULE = new ArmyFormation(Optional.of(U9), "garrison");

  /** 两种编制都跑（Gov 在前；label 进失败信息）。 */
  private static final List<UnitModule> MODULES = List.of(GOV_MODULE, ARMY_MODULE);

  // ── copy：七个调用方 ───────────────────────────────────────────

  @Test
  void renamePreservesModule() {
    for (UnitModule module : MODULES) {
      UnitState base = stateOf(unit(U1, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.rename(base, U1, "新名").units().get(U1),
          op("rename", module),
          "name");
    }
  }

  @Test
  void setCompositionPreservesModule() {
    for (UnitModule module : MODULES) {
      UnitState base = stateOf(unit(U1, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.setComposition(
                  base,
                  U1,
                  List.of(new CompositionEntry("步兵", 70)),
                  List.of(new CompositionEntry("步枪", 40)))
              .units()
              .get(U1),
          op("setComposition", module),
          "manpower",
          "equipment");
    }
  }

  @Test
  void applyCasualtiesPreservesModule() {
    for (UnitModule module : MODULES) {
      UnitState base = stateOf(unit(U1, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.applyCasualties(
                  base,
                  U1,
                  List.of(new CompositionDelta("步兵", -30)),
                  List.of(new CompositionDelta("步枪", -10)))
              .units()
              .get(U1),
          op("applyCasualties", module),
          "manpower",
          "equipment");
    }
  }

  @Test
  void placeAtPreservesModule() {
    for (UnitModule module : MODULES) {
      UnitState base = stateOf(unit(U1, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.placeAt(base, U1, Optional.of(H12), T1).units().get(U1),
          op("placeAt", module),
          "position");
    }
  }

  @Test
  void planRoutePreservesModule() {
    for (UnitModule module : MODULES) {
      UnitState base = stateOf(unit(U1, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.planRoute(base, U1, corridor(), T0).units().get(U1),
          op("planRoute", module),
          "movement");
    }
  }

  @Test
  void cancelRoutePreservesModule() {
    for (UnitModule module : MODULES) {
      // 先真的装上一条路线 ⇒ cancelRoute 的差集里 movement 才会出现（否则判据对"清空"这一支恒真）。
      UnitState planned = UnitOperations.planRoute(stateOf(unit(U1, module)), U1, corridor(), T0);
      assertCopied(
          module,
          planned.units().get(U1),
          UnitOperations.cancelRoute(planned, U1).units().get(U1),
          op("cancelRoute", module),
          "movement");
    }
  }

  @Test
  void reparentPreservesModule() {
    for (UnitModule module : MODULES) {
      UnitState base = stateOf(unit(U1, module), plain(U2, H13, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.reparent(base, U1, Optional.of(U2), T1).units().get(U1),
          op("reparent", module),
          "parent");
    }
  }

  // ── copyFormation：五个调用方 ──────────────────────────────────

  @Test
  void attachSubtreePreservesModule() {
    for (UnitModule module : MODULES) {
      // 编制 v2：attach 只改 parent + attached；两单位必须同格（都在 H11）。
      UnitState base = stateOf(unit(U1, module), plain(U2, H11, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.attachSubtree(base, U1, U2, T1).units().get(U1),
          op("attachSubtree", module),
          "parent",
          "attached");
    }
  }

  @Test
  void detachUnitPreservesModule() {
    for (UnitModule module : MODULES) {
      UnitState base = stateOf(childOfU2(module), plain(U2, H11, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.detachUnit(base, U1, T1).units().get(U1),
          op("detachUnit", module),
          "attached");
    }
  }

  @Test
  void splitFormationPreservesModule() {
    for (UnitModule module : MODULES) {
      // splitFormation 对每个目标复用 detachUnit ⇒ 这里钉的是它的公共入口这一面。
      UnitState base = stateOf(childOfU2(module), plain(U2, H11, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.splitFormation(base, U2, List.of(U1), T1).units().get(U1),
          op("splitFormation", module),
          "attached");
    }
  }

  @Test
  void setOffsetPreservesModule() {
    for (UnitModule module : MODULES) {
      UnitState base = stateOf(unit(U1, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.setOffset(base, U1, Optional.of(new RelativeOffset(1, 0)), T1)
              .units()
              .get(U1),
          op("setOffset", module),
          "offset");
    }
  }

  @Test
  void reparentSubtreePreservesModule() {
    for (UnitModule module : MODULES) {
      UnitState base = stateOf(childOfU2(module), plain(U2, H11, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.reparentSubtree(base, U1, Optional.empty(), T1).units().get(U1),
          op("reparentSubtree", module),
          "parent");
    }
  }

  // ── withSpeed / withStatus / withRejoinTarget / withJurisdiction ──

  /** mergeFormation 的 {@code withSpeed} 只改存活方（父）的 speed；父的编制同样逐值带过。 */
  @Test
  void mergeFormationPreservesModuleOnTheSurvivingParent() {
    for (UnitModule module : MODULES) {
      UnitState base =
          stateOf(
              unit(U1, module, 2),
              unit(
                  U2,
                  Optional.empty(),
                  Optional.of(H11),
                  UnitStatus.MOVING,
                  5,
                  Optional.of(sampleJurisdiction()),
                  module));
      UnitState next = UnitOperations.mergeFormation(base, U1, U2, T1);

      Unit after = next.units().get(U2);
      assertThat(after.speed())
          .as("%s：合体后整支里最慢者（u-1 speed 2）落到存活方", op("mergeFormation", module))
          .isEqualTo(2);
      assertCopied(
          module, base.units().get(U2), after, op("mergeFormation 的 withSpeed", module), "speed");
    }
  }

  @Test
  void setStatusPreservesModule() {
    for (UnitModule module : MODULES) {
      UnitState base = stateOf(unit(U1, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.setStatus(base, U1, UnitStatus.RESTING).units().get(U1),
          op("setStatus", module),
          "status");
    }
  }

  @Test
  void setRejoinTargetPreservesModule() {
    for (UnitModule module : MODULES) {
      UnitState base = stateOf(unit(U1, module), plain(U2, H13, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.setRejoinTarget(base, U1, Optional.of(U2)).units().get(U1),
          op("setRejoinTarget", module),
          "rejoinTarget");
    }
  }

  @Test
  void setJurisdictionPreservesModule() {
    for (UnitModule module : MODULES) {
      UnitState base = stateOf(unit(U1, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.setJurisdiction(
                  base,
                  U1,
                  map(),
                  List.of(RB, RD),
                  Optional.of(50L),
                  Optional.empty(),
                  Optional.empty(),
                  Optional.empty())
              .units()
              .get(U1),
          op("setJurisdiction 的 withJurisdiction", module),
          "jurisdiction");
    }
  }

  @Test
  void setTaxRatePreservesModule() {
    for (UnitModule module : MODULES) {
      UnitState base = stateOf(unit(U1, module));
      assertCopied(
          module,
          base.units().get(U1),
          UnitOperations.setTaxRate(base, U1, RB, 750L).units().get(U1),
          op("setTaxRate 的 withJurisdiction", module),
          "jurisdiction");
    }
  }

  // ── withModule：两条立编制命令 ─────────────────────────────────

  /** {@code withModule} 只换第 16 分量：目标编制落上，管辖/视野/编队等 15 个分量一个不动。 */
  @Test
  void setGovFormationOnlyChangesModule() {
    Unit before = plainWithJurisdiction();
    UnitState base = stateOf(before, govUnit(U9));
    UnitState next = UnitOperations.setGovFormation(base, U1, (GovFormation) GOV_MODULE);

    Unit after = next.units().get(U1);
    assertThat(after.module()).as("目标编制逐值落上").contains(GOV_MODULE);
    assertThat(after.jurisdiction()).as("立编制不得顺手清掉管辖").isEqualTo(before.jurisdiction());
    assertThat(after.visionRadius()).as("立编制不得顺手重置视野半径").isEqualTo(before.visionRadius());
    assertChangedExactly(before, after, "setGovFormation 的 withModule", "module");
  }

  @Test
  void setArmyFormationOnlyChangesModule() {
    Unit before = plainWithJurisdiction();
    UnitState base = stateOf(before, govUnit(U9));
    UnitState next = UnitOperations.setArmyFormation(base, U1, (ArmyFormation) ARMY_MODULE);

    Unit after = next.units().get(U1);
    assertThat(after.module()).as("目标编制逐值落上").contains(ARMY_MODULE);
    assertThat(after.jurisdiction()).as("立编制不得顺手清掉管辖").isEqualTo(before.jurisdiction());
    assertChangedExactly(before, after, "setArmyFormation 的 withModule", "module");
  }

  // ── 四条 GOV 编制编辑命令：只改目标字段，其余 15 组件不动 ─────────

  @Test
  void setGovPolicyOnlyChangesModule() {
    Unit before = unit(U1, GOV_MODULE);
    UnitState base = stateOf(before);
    UnitState next =
        UnitOperations.setGovPolicy(
            base,
            U1,
            Optional.of(999L),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    Unit after = next.units().get(U1);
    GovFormation gov = (GovFormation) after.module().orElseThrow();
    assertThat(gov.policy().grainPerStaffPerTick()).as("给了 ⇒ 覆盖").isEqualTo(999L);
    assertThat(gov.policy().clothPerStaffPerCycle()).as("没给 ⇒ 保持").isEqualTo(222L);
    assertThat(gov.policy().staffCap())
        .as("staffCap 没给 ⇒ 保持原表与原序")
        .containsExactly(Map.entry(StaffRole.POST, 9L), Map.entry(StaffRole.SCRIBE, 6L));
    assertThat(gov.staff()).as("政策编辑不得动 roster").isEqualTo(GOV_MODULE_GOV().staff());
    assertThat(gov.superiorGov()).as("政策编辑不得动上级").contains(U9);
    assertThat(gov.level()).isEqualTo(GovLevel.PROVINCE);
    assertChangedExactly(before, after, "setGovPolicy：只换 module（policy 在 module 内）", "module");
  }

  @Test
  void setGovSuperiorOnlyChangesModule() {
    Unit before = unit(U1, GOV_MODULE);
    UnitState base = stateOf(before);
    UnitState next = UnitOperations.setGovSuperior(base, U1, Optional.empty());

    Unit after = next.units().get(U1);
    GovFormation gov = (GovFormation) after.module().orElseThrow();
    assertThat(gov.superiorGov()).as("空 = 中央（无上级）").isEmpty();
    assertThat(gov.staff()).as("改上级不得动 roster").isEqualTo(GOV_MODULE_GOV().staff());
    assertThat(gov.policy()).as("改上级不得动政策").isEqualTo(GOV_MODULE_GOV().policy());
    assertThat(gov.level()).as("改上级不得动层级").isEqualTo(GovLevel.PROVINCE);
    assertChangedExactly(before, after, "setGovSuperior：只换 module（superior 在 module 内）", "module");
  }

  @Test
  void recruitStaffOnlyChangesModule() {
    Unit before = unit(U1, GOV_MODULE);
    UnitState base = stateOf(before);
    UnitState next = UnitOperations.recruitStaff(base, U1, StaffRole.POST, 3L);

    Unit after = next.units().get(U1);
    GovFormation gov = (GovFormation) after.module().orElseThrow();
    assertThat(gov.staff())
        .as("roster += count；既有键保持原位（YAMEN→POST→SCRIBE）")
        .containsExactly(
            Map.entry(StaffRole.YAMEN, 2L),
            Map.entry(StaffRole.POST, 4L),
            Map.entry(StaffRole.SCRIBE, 5L));
    assertThat(gov.policy()).as("入编不得动政策").isEqualTo(GOV_MODULE_GOV().policy());
    assertThat(gov.superiorGov()).as("入编不得动上级").contains(U9);
    assertThat(gov.level()).isEqualTo(GovLevel.PROVINCE);
    assertChangedExactly(before, after, "recruitStaff：只换 module（staff 在 module 内）", "module");
  }

  @Test
  void dismissStaffKeepsZeroKeyAndOnlyChangesModule() {
    Unit before = unit(U1, GOV_MODULE);
    UnitState base = stateOf(before);
    UnitState next = UnitOperations.dismissStaff(base, U1, StaffRole.YAMEN, 2L);

    Unit after = next.units().get(U1);
    GovFormation gov = (GovFormation) after.module().orElseThrow();
    assertThat(new ArrayList<>(gov.staff().keySet()))
        .as("减到 0 保留键、键序不变")
        .containsExactly(StaffRole.YAMEN, StaffRole.POST, StaffRole.SCRIBE);
    assertThat(gov.staff())
        .containsExactly(
            Map.entry(StaffRole.YAMEN, 0L),
            Map.entry(StaffRole.POST, 1L),
            Map.entry(StaffRole.SCRIBE, 5L));
    assertThat(gov.policy()).as("离编不得动政策").isEqualTo(GOV_MODULE_GOV().policy());
    assertThat(gov.superiorGov()).as("离编不得动上级").contains(U9);
    assertChangedExactly(before, after, "dismissStaff：只换 module（staff 在 module 内）", "module");
  }

  // ── UnitMoves 冻结视图 + UnitTimeParticipant 两处 ──────────────

  @Test
  void frozenViewInUnitMovesPreservesModule() {
    for (UnitModule module : MODULES) {
      Unit inFlight = withMovement(unit(U1, module));
      CapturingCost cost = new CapturingCost();
      UnitMoves.evaluate(inFlight, T1, map(), cost);
      assertThat(cost.seen)
          .as("%s：成本函数必须真的被询价过（否则本用例对 frozen 视图恒真）", op("UnitMoves.evaluate", module))
          .isNotEmpty();
      assertCopied(
          module, inFlight, cost.seen.get(0), op("UnitMoves.evaluate 的 frozen 视图", module));
    }
  }

  @Test
  void tickMaterializationPreservesModule() {
    for (UnitModule module : MODULES) {
      Unit inFlight = withMovement(unit(U1, module));
      UnitState base = stateOf(inFlight);
      UnitState next = applyProposal(base, rangeTo(1), new FixedCost());
      assertCopied(
          module,
          base.units().get(U1),
          next.units().get(U1),
          op("推进物化 withPositionAndMovement", module),
          "position");
    }
  }

  @Test
  void rejoinReplanPreservesModule() {
    for (UnitModule module : MODULES) {
      // 第二趟（withMovement）：U1 无在途行程、位于 H11，回归目标 U2 在 H13 ⇒ 本刻重新装载一条 Movement。
      UnitState base = stateOf(unit(U1, module), plain(U2, H13, module));
      UnitState aimed = UnitOperations.setRejoinTarget(base, U1, Optional.of(U2));
      UnitState next = applyProposal(aimed, rangeTo(1), new FixedCost());
      Unit before = aimed.units().get(U1);
      Unit after = next.units().get(U1);
      assertThat(after.movement())
          .as("%s：回归轨道必须真的装载了行程（否则本用例对 withMovement 恒真）", op("回归重规划", module))
          .isPresent();
      assertCopied(module, before, after, op("回归重规划 withMovement", module), "movement");
    }
  }

  // ── 装置：逐 record 分量对拍 / 断言 ─────────────────────────────

  /**
   * ★★ **本测试的主力装置**：逐 record 分量对拍 {@code before}/{@code after}，返回**值发生变化**的分量名集。
   *
   * <p>**不按名字引用 {@code module}** ⇒ 拷贝点漏传那一刻，{@code "module"} 自己出现在差集里。写死比较对象是错的做法： 那样新增第 17
   * 个分量时本装置不会自动跟上，正是铁律 5 的由来。
   */
  private static Set<String> changedComponents(Unit before, Unit after) {
    Set<String> changed = new LinkedHashSet<>();
    for (RecordComponent rc : Unit.class.getRecordComponents()) {
      Method accessor = rc.getAccessor();
      try {
        if (!Objects.equals(accessor.invoke(before), accessor.invoke(after))) {
          changed.add(rc.getName());
        }
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new IllegalStateException("读不出分量 " + rc.getName(), e);
      }
    }
    return changed;
  }

  private static void assertChangedExactly(Unit before, Unit after, String op, String... expected) {
    assertThat(changedComponents(before, after))
        .as("%s：应恰有这些分量变化（多一个少一个都红）", op)
        .isEqualTo(Set.of(expected));
  }

  /**
   * ★★ 拷贝点的判据：**恰好**这些分量变了，且 {@code module} **逐值原样带过**。
   *
   * <p>前置两条（夹具带非空编制）防的是"夹具不小心用成 empty ⇒ 用例恒真"。逐值相等这条让失败信息直接说"编制丢了"， {@link #changedComponents}
   * 那条则让**新增字段**未来也能被自动抓住。
   */
  private static void assertCopied(
      UnitModule module, Unit before, Unit after, String op, String... expected) {
    assertThat(before.module()).as("%s 的夹具必须带非空 module（否则本用例对丢字段恒真）", op).contains(module);
    assertThat(after.module()).as("%s：module 必须逐值原样带过（丢失 ⇒ 当场红）", op).isEqualTo(before.module());
    assertChangedExactly(before, after, op, expected);
  }

  private static String op(String name, UnitModule module) {
    return name + "[" + module.getClass().getSimpleName() + "]";
  }

  /** GOV_MODULE 的类型化视图（保持 record 相等语义，避免每处强转）。 */
  private static GovFormation GOV_MODULE_GOV() {
    return (GovFormation) GOV_MODULE;
  }

  // ── 夹具 ───────────────────────────────────────────────────────

  private static Jurisdiction sampleJurisdiction() {
    return new Jurisdiction(
        rates(Map.entry(RA, 120L), Map.entry(RB, 340L), Map.entry(RC, 560L)), 5L, 7L, 9L, 250);
  }

  @SafeVarargs
  private static Map<RegionId, Long> rates(Map.Entry<RegionId, Long>... entries) {
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    for (Map.Entry<RegionId, Long> entry : entries) {
      rates.put(entry.getKey(), entry.getValue());
    }
    return rates;
  }

  @SafeVarargs
  private static Map<StaffRole, Long> orderedStaff(Map.Entry<StaffRole, Long>... entries) {
    Map<StaffRole, Long> staff = new LinkedHashMap<>();
    for (Map.Entry<StaffRole, Long> entry : entries) {
      staff.put(entry.getKey(), entry.getValue());
    }
    return staff;
  }

  private static UnitState stateOf(Unit... units) {
    Map<UnitId, Unit> byId = new LinkedHashMap<>();
    for (Unit unit : units) {
      byId.put(unit.id(), unit);
    }
    return new UnitState(byId);
  }

  private static Unit unit(UnitId id, UnitModule module) {
    return unit(id, module, 2);
  }

  /** 一个中央 GOV 单位（给 {@code superiorGov}/{@code masterGov} 一个真实存在的 GOV 目标）。 */
  private static Unit govUnit(UnitId id) {
    return unit(
        id,
        new GovFormation(Map.of(), OfficePolicy.defaults(), Optional.empty(), GovLevel.CENTRAL),
        2);
  }

  private static Unit unit(UnitId id, UnitModule module, int speed) {
    return unit(
        id,
        Optional.empty(),
        Optional.of(H11),
        UnitStatus.MOVING,
        speed,
        Optional.of(sampleJurisdiction()),
        module);
  }

  private static Unit unit(
      UnitId id,
      Optional<UnitId> parent,
      Optional<HexCoord> position,
      UnitStatus status,
      int speed,
      Optional<Jurisdiction> jurisdiction,
      UnitModule module) {
    return new Unit(
        id,
        "单位 " + id.value(),
        parentSeries(parent),
        positionSeries(position),
        List.of(new CompositionEntry("步兵", 100)),
        List.of(new CompositionEntry("步枪", 50)),
        speed,
        500,
        Optional.empty(),
        status,
        attachedSeries(true),
        noOffset(),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        jurisdiction,
        Optional.ofNullable(module));
  }

  /** 无编制的 canonical 16 参形态（jurisdiction/视野非默认，给 withModule 的"只改 module"用例防顺手清字段）。 */
  private static Unit plainWithJurisdiction() {
    return new Unit(
        U1,
        "第一连",
        parentSeries(Optional.empty()),
        positionSeries(Optional.of(H11)),
        List.of(new CompositionEntry("步兵", 100)),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        attachedSeries(true),
        noOffset(),
        Optional.empty(),
        3,
        Optional.of(sampleJurisdiction()),
        Optional.empty());
  }

  /** U1 挂在 U2 之下（detach / split / reparentSubtree 的输入）。 */
  private static Unit childOfU2(UnitModule module) {
    return unit(
        U1,
        Optional.of(U2),
        Optional.of(H11),
        UnitStatus.MOVING,
        2,
        Optional.of(sampleJurisdiction()),
        module);
  }

  /** 编队操作里"另一个单位"（父或子树外的旁观者）。 */
  private static Unit plain(UnitId id, HexCoord position, UnitModule module) {
    return unit(
        id,
        Optional.empty(),
        Optional.of(position),
        UnitStatus.MOVING,
        2,
        Optional.of(sampleJurisdiction()),
        module);
  }

  private static SegmentedSeries<Optional<UnitId>> parentSeries(Optional<UnitId> parent) {
    return new SegmentedSeries<>(List.of(new Segment<>(T0, parent)), List.of(), null);
  }

  private static SegmentedSeries<Optional<HexCoord>> positionSeries(Optional<HexCoord> position) {
    return new SegmentedSeries<>(List.of(new Segment<>(T0, position)), List.of(), null);
  }

  private static SegmentedSeries<Boolean> attachedSeries(boolean attached) {
    return new SegmentedSeries<>(List.of(new Segment<>(T0, attached)), List.of(), null);
  }

  private static SegmentedSeries<Optional<RelativeOffset>> noOffset() {
    return new SegmentedSeries<>(
        List.of(new Segment<>(T0, Optional.<RelativeOffset>empty())), List.of(), null);
  }

  private static Unit withMovement(Unit unit) {
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.manpower(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        Optional.of(new Movement(corridor(), T0, unit.effectiveSpeed(), unit.mobilityPerMille())),
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget(),
        unit.visionRadius(),
        unit.jurisdiction(),
        unit.module());
  }

  private static Route corridor() {
    return new Route(List.of(H11, H13), List.of(H11, H12, H13));
  }

  private static TimeRange rangeTo(long tick) {
    return new TimeRange(T0, Optional.of(T0.plus(tick)));
  }

  private static UnitState applyProposal(UnitState base, TimeRange range, MovementCost cost) {
    TimeProposal proposal = new UnitTimeParticipant(cost, MAP_ID).simulate(worldOf(base), range);
    return UnitChangeSet.apply((UnitChangeSet) proposal.changeSet(), base);
  }

  private static SimulationState worldOf(UnitState unitState) {
    return new SimulationState(
        new StateMeta(REF, T0),
        Map.of(
            "unit", new UnitSnapshot(REF, T0, unitState),
            "map", new MapSnapshot(REF, T0, map())),
        InMemoryInfoSystem.empty());
  }

  /** 三格走廊 + 四个区域（{@code r-a} 到 {@code r-d}）。 */
  private static GameMap map() {
    TerrainType flat = new TerrainType("flat", "flat", "#336699", 0.0, 1.0, 0, 0, 0, 25, "夹具地形");
    Map<HexCoord, HexCell> hexes = new LinkedHashMap<>();
    hexes.put(H11, new HexCell(0.5));
    hexes.put(H12, new HexCell(0.5));
    hexes.put(H13, new HexCell(0.5));
    Map<String, TerrainType> terrainTypes = new LinkedHashMap<>();
    terrainTypes.put(flat.key(), flat);
    Map<RegionId, Region> regions = new LinkedHashMap<>();
    regions.put(RA, Region.of(RA, "甲区", Set.of(H11), RegionMeta.empty()));
    regions.put(RB, Region.of(RB, "乙区", Set.of(H12), RegionMeta.empty()));
    regions.put(RC, Region.of(RC, "丙区", Set.of(H13), RegionMeta.empty()));
    regions.put(RD, Region.of(RD, "丁区", Set.of(H11, H12), RegionMeta.empty()));
    return new GameMap(
        hexes,
        TerrainBlocks.uniform(hexes.keySet(), flat.key()),
        regions,
        Map.of(),
        terrainTypes,
        Map.of(),
        Map.of(),
        Map.of(),
        GenerationSpec.defaults(0L));
  }

  /** 记下被询价的单位（{@code UnitMoves.evaluate} 把 frozen 视图交给成本函数，这是取到它的唯一入口）。 */
  private static final class CapturingCost implements MovementCost {

    private final List<Unit> seen = new ArrayList<>();

    @Override
    public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
      seen.add(unit);
      return OptionalLong.of(48000);
    }

    @Override
    public long minStepCostMillis(Unit unit, GameMap map) {
      return 1;
    }
  }

  /** 每段固定 48000：日制下 speed 2 的一天预算 = 48000（1 tick = 1 天）⇒ 走得动一格、且仍在途。 */
  private static final class FixedCost implements MovementCost {

    @Override
    public OptionalLong costMillis(HexCoord from, HexCoord to, Unit unit, GameMap map) {
      return OptionalLong.of(48000);
    }

    @Override
    public long minStepCostMillis(Unit unit, GameMap map) {
      return 1;
    }
  }
}

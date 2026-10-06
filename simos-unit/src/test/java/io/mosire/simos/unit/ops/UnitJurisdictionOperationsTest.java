package io.mosire.simos.unit.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import io.mosire.simos.unit.CompositionDelta;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.Movement;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Route;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
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
 * ★★ **管辖（辖区阶段 5，2026-09-30）的领域操作面**：{@link UnitOperations#setJurisdiction} / {@link
 * UnitOperations#setTaxRate} 的语义，以及最要紧的那一条 —— **全部生产拷贝点逐处不丢 {@code jurisdiction}**。
 *
 * <p>★★ 拷贝点的装置照 {@code UnitVisionRadiusTest} 的 {@code assertCopied} 形制：{@link #changedComponents}
 * **逐 record 分量对拍** before/after，**不按名字引用** {@code jurisdiction} ⇒ 某个拷贝点漏传那一刻，{@code
 * "jurisdiction"} 自己出现在差集里，红点**直接指名丢的是哪个字段**（本仓最贵的教训：{@code MapData} 加字段时 {@code MapDiff}
 * 没人提醒要跟上，四个字段静默漂移）。本文件的每个拷贝点夹具都要求**非空管辖**，否则用例对"丢字段"是恒真的。
 *
 * <p>★ 覆盖的生产写点：{@code UnitOperations} 的七个 {@code copy} 调用方（reparent / rename / setComposition /
 * applyCasualties / placeAt / planRoute / cancelRoute）、四个 {@code copyFormation} 调用方（attachSubtree /
 * detachUnit / setOffset / reparentSubtree；splitFormation 经 detachUnit）、{@code
 * withStatus}（setStatus）、{@code withRejoinTarget}（setRejoinTarget）、{@code
 * withSpeed}（mergeFormation）、{@code withJurisdiction} （setJurisdiction / setTaxRate 的目标本身），以及
 * {@code UnitMoves.evaluate} 的 frozen 视图、{@code UnitTimeParticipant} 的 withPositionAndMovement /
 * withMovement。
 */
class UnitJurisdictionOperationsTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T1 = SimosTimestamp.of(1);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H13 = new HexCoord(1, 3);
  private static final UnitId U1 = new UnitId("u-1");
  private static final UnitId U2 = new UnitId("u-2");
  private static final String MAP_ID = "Map1";
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  private static final RegionId RA = new RegionId("r-a");
  private static final RegionId RB = new RegionId("r-b");
  private static final RegionId RC = new RegionId("r-c");
  private static final RegionId RD = new RegionId("r-d");

  /** 地图 {@code regions()} 里**没有**的区域（具名拒的靶子）。 */
  private static final RegionId GHOST_REGION = new RegionId("r-ghost");

  // ── setJurisdiction：整体替换 / 政策字段 upsert / 空数组撤销 ──────

  @Test
  void setJurisdictionReplacesTheRegionSetKeepingKeptRatesAndStartingNewRegionsAtZero() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));

    UnitState next =
        UnitOperations.setJurisdiction(
            base,
            U1,
            map(),
            List.of(RB, RD),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    Unit before = base.units().get(U1);
    Unit after = next.units().get(U1);
    Jurisdiction jurisdiction = after.jurisdiction().orElseThrow();
    assertThat(new ArrayList<>(jurisdiction.taxRatePerMilleByRegion().keySet()))
        .as("区域集合整体替换，保留区域保持原序")
        .containsExactly(RB, RD);
    assertThat(jurisdiction.taxRatePerMilleByRegion())
        .as("保留的 r-b 税率保留；新纳入的 r-d 从 0 起")
        .containsEntry(RB, 340L)
        .containsEntry(RD, 0L)
        .doesNotContainKey(RA)
        .doesNotContainKey(RC);
    assertThat(jurisdiction.levyGrainCapPerCommand()).as("未给的政策字段保持原值").isEqualTo(5L);
    assertThat(jurisdiction.levyMoneyCapPerCommand()).isEqualTo(7L);
    assertThat(jurisdiction.levyManpowerCapPerCommand()).isEqualTo(9L);
    assertThat(jurisdiction.administrationPerMille()).isEqualTo(250);
    assertChangedExactly(before, after, "setJurisdiction 整体替换", "jurisdiction");
    assertThat(before.jurisdiction().orElseThrow().taxRatePerMilleByRegion())
        .as("纯函数：base 一个字段都不动")
        .containsOnlyKeys(RA, RB, RC);
  }

  @Test
  void setJurisdictionUpdatesOnlyTheProvidedPolicyFields() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));

    UnitState next =
        UnitOperations.setJurisdiction(
            base,
            U1,
            map(),
            List.of(RA, RB, RC),
            Optional.of(77L),
            Optional.empty(),
            Optional.empty(),
            Optional.of(999));

    Jurisdiction jurisdiction = next.units().get(U1).jurisdiction().orElseThrow();
    assertThat(new ArrayList<>(jurisdiction.taxRatePerMilleByRegion().keySet()))
        .as("区域集合不变 ⇒ 税率表逐键逐值保留")
        .containsExactly(RA, RB, RC);
    assertThat(jurisdiction.taxRatePerMilleByRegion())
        .containsExactly(Map.entry(RA, 120L), Map.entry(RB, 340L), Map.entry(RC, 560L));
    assertThat(jurisdiction.levyGrainCapPerCommand()).as("给了 ⇒ 覆盖").isEqualTo(77L);
    assertThat(jurisdiction.levyMoneyCapPerCommand()).as("没给 ⇒ 保持").isEqualTo(7L);
    assertThat(jurisdiction.levyManpowerCapPerCommand()).as("没给 ⇒ 保持").isEqualTo(9L);
    assertThat(jurisdiction.administrationPerMille()).as("给了 ⇒ 覆盖").isEqualTo(999);
  }

  @Test
  void setJurisdictionOnAUnitWithoutJurisdictionStartsEveryUnspecifiedFieldAtZero() {
    UnitState base = stateOf(unitWithoutJurisdiction(U1, H11));

    UnitState next =
        UnitOperations.setJurisdiction(
            base,
            U1,
            map(),
            List.of(RA),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    Jurisdiction jurisdiction = next.units().get(U1).jurisdiction().orElseThrow();
    assertThat(jurisdiction.taxRatePerMilleByRegion())
        .as("原本无管辖 ⇒ 新区域税率从 0 起")
        .containsOnlyKeys(RA)
        .containsEntry(RA, 0L);
    assertThat(jurisdiction.levyGrainCapPerCommand()).as("原本无管辖 ⇒ 未给也按 0").isZero();
    assertThat(jurisdiction.levyMoneyCapPerCommand()).isZero();
    assertThat(jurisdiction.levyManpowerCapPerCommand()).isZero();
    assertThat(jurisdiction.administrationPerMille()).isZero();
  }

  /**
   * ★ 空数组合法 = **撤销全部管辖**：结果仍是 {@code Optional.of}（present），税率表空；政策字段照常按"给了覆盖、没给保持" 的规则走——**不把整个
   * Optional 丢掉**（空 map = 无管辖是既定表示）。
   */
  @Test
  void setJurisdictionWithEmptyRegionsRevokesAllButKeepsJurisdictionPresent() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));

    UnitState next =
        UnitOperations.setJurisdiction(
            base,
            U1,
            map(),
            List.of(),
            Optional.of(50L),
            Optional.empty(),
            Optional.empty(),
            Optional.of(800));

    Unit unit = next.units().get(U1);
    assertThat(unit.jurisdiction()).as("撤销全部管辖 ≠ 把 Optional 丢掉").isPresent();
    Jurisdiction jurisdiction = unit.jurisdiction().orElseThrow();
    assertThat(jurisdiction.taxRatePerMilleByRegion()).as("空 map = 无管辖").isEmpty();
    assertThat(jurisdiction.levyGrainCapPerCommand()).as("撤销时给的政策字段照常更新").isEqualTo(50L);
    assertThat(jurisdiction.levyMoneyCapPerCommand()).as("没给 ⇒ 保持").isEqualTo(7L);
    assertThat(jurisdiction.levyManpowerCapPerCommand()).as("没给 ⇒ 保持").isEqualTo(9L);
    assertThat(jurisdiction.administrationPerMille()).isEqualTo(800);
  }

  /** ★ 重复 id 的输入：**首现为准**（键位取第一次的位置，值两边一致、只应落一个键）。 */
  @Test
  void setJurisdictionWithDuplicateRegionIdsKeepsTheFirstOccurrence() {
    Map<RegionId, Long> rates = new LinkedHashMap<>();
    rates.put(RA, 120L);
    rates.put(RB, 340L);
    UnitState base =
        stateOf(unit(U1, Optional.empty(), Optional.of(H11), new Jurisdiction(rates, 0, 0, 0, 0)));

    UnitState next =
        UnitOperations.setJurisdiction(
            base,
            U1,
            map(),
            List.of(RB, RA, RB),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());

    Jurisdiction jurisdiction = next.units().get(U1).jurisdiction().orElseThrow();
    assertThat(new ArrayList<>(jurisdiction.taxRatePerMilleByRegion().keySet()))
        .as("r-b 首现在前 ⇒ 键位取首现；重复不给第二个位置")
        .containsExactly(RB, RA);
    assertThat(jurisdiction.taxRatePerMilleByRegion())
        .containsEntry(RB, 340L)
        .containsEntry(RA, 120L);
  }

  @Test
  void setJurisdictionRejectsAnUnknownRegionAndLeavesStateUntouched() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));

    assertThatThrownBy(
            () ->
                UnitOperations.setJurisdiction(
                    base,
                    U1,
                    map(),
                    List.of(RA, GHOST_REGION),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()))
        .as("坏的 regionId 不得被静默丢掉（否则变成'少管一个区域'）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("区域不存在")
        .hasMessageContaining("r-ghost");

    assertThat(base.units().get(U1).jurisdiction().orElseThrow().taxRatePerMilleByRegion())
        .as("拒绝 ⇒ 原状态一字不动")
        .containsOnlyKeys(RA, RB, RC);
  }

  @Test
  void setJurisdictionRejectsAnUnknownUnit() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));

    assertThatThrownBy(
            () ->
                UnitOperations.setJurisdiction(
                    base,
                    U2,
                    map(),
                    List.of(RA),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位不存在")
        .hasMessageContaining("u-2");
  }

  @Test
  void setJurisdictionRejectsANullRegionElement() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));
    List<RegionId> regions = new ArrayList<>();
    regions.add(RA);
    regions.add(null);

    assertThatThrownBy(
            () ->
                UnitOperations.setJurisdiction(
                    base,
                    U1,
                    map(),
                    regions,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("regions 的元素不得为 null");
  }

  // ── setTaxRate：upsert / 边界 / 具名拒 ─────────────────────────

  @Test
  void setTaxRateUpsertsOneRegionKeepingTheOrderAndTheOtherPolicyFields() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));

    UnitState next = UnitOperations.setTaxRate(base, U1, RB, 750L);

    Unit before = base.units().get(U1);
    Unit after = next.units().get(U1);
    Jurisdiction jurisdiction = after.jurisdiction().orElseThrow();
    assertThat(new ArrayList<>(jurisdiction.taxRatePerMilleByRegion().keySet()))
        .as("既有键只换值 ⇒ 键位不动")
        .containsExactly(RA, RB, RC);
    assertThat(jurisdiction.taxRatePerMilleByRegion())
        .containsExactly(Map.entry(RA, 120L), Map.entry(RB, 750L), Map.entry(RC, 560L));
    assertThat(jurisdiction.levyGrainCapPerCommand()).isEqualTo(5L);
    assertThat(jurisdiction.levyMoneyCapPerCommand()).isEqualTo(7L);
    assertThat(jurisdiction.levyManpowerCapPerCommand()).isEqualTo(9L);
    assertThat(jurisdiction.administrationPerMille()).isEqualTo(250);
    assertChangedExactly(before, after, "setTaxRate upsert", "jurisdiction");
    assertThat(before.jurisdiction().orElseThrow().taxRatePerMilleByRegion())
        .as("纯函数：base 的 r-b 仍是 340")
        .containsEntry(RB, 340L);
  }

  @Test
  void setTaxRateAcceptsBoundaryRatesZeroAndThousand() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));

    assertThat(
            UnitOperations.setTaxRate(base, U1, RA, 0L)
                .units()
                .get(U1)
                .jurisdiction()
                .orElseThrow()
                .taxRatePerMilleByRegion())
        .containsEntry(RA, 0L);
    assertThat(
            UnitOperations.setTaxRate(base, U1, RA, 1000L)
                .units()
                .get(U1)
                .jurisdiction()
                .orElseThrow()
                .taxRatePerMilleByRegion())
        .containsEntry(RA, 1000L);
  }

  @Test
  void setTaxRateRejectsAUnitWithoutJurisdiction() {
    UnitState base = stateOf(unitWithoutJurisdiction(U1, H11));

    assertThatThrownBy(() -> UnitOperations.setTaxRate(base, U1, RA, 100L))
        .as("无管辖不是'税率 0'，必须指路 SetJurisdiction")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("没有 jurisdiction")
        .hasMessageContaining("unit.SetJurisdiction");
  }

  @Test
  void setTaxRateRejectsARegionOutsideTheJurisdiction() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));

    assertThatThrownBy(() -> UnitOperations.setTaxRate(base, U1, RD, 100L))
        .as("不能给未纳入管辖的区域设税率（先 SetJurisdiction）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("r-d")
        .hasMessageContaining("不在单位")
        .hasMessageContaining("unit.SetJurisdiction");
  }

  @Test
  void setTaxRateRejectsRatesOutsideRangeWithoutClamping() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));

    assertThatThrownBy(() -> UnitOperations.setTaxRate(base, U1, RA, -1L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ratePerMille")
        .hasMessageContaining("[0,1000]");
    assertThatThrownBy(() -> UnitOperations.setTaxRate(base, U1, RA, 1001L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ratePerMille")
        .hasMessageContaining("[0,1000]");

    assertThat(base.units().get(U1).jurisdiction().orElseThrow().taxRatePerMilleByRegion())
        .as("越界被拒 ⇒ 不钳制、原值不动")
        .containsEntry(RA, 120L);
  }

  @Test
  void setTaxRateRejectsAnUnknownUnit() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));

    assertThatThrownBy(() -> UnitOperations.setTaxRate(base, U2, RA, 100L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("单位不存在")
        .hasMessageContaining("u-2");
  }

  // ── Unit 构造形态：canonical 给原值、兼容构造器给 empty ──────────

  @Test
  void canonicalConstructorKeepsAnExplicitJurisdiction() {
    Jurisdiction jurisdiction = sampleJurisdiction();

    assertThat(unit(U1, Optional.empty(), Optional.of(H11), jurisdiction).jurisdiction())
        .as("15 参 canonical 形态必须逐值保留显式管辖")
        .contains(jurisdiction);
  }

  @Test
  void canonicalConstructorNormalizesNullJurisdictionToEmpty() {
    Unit unit =
        new Unit(
            U1,
            "第一连",
            parentSeries(Optional.empty()),
            positionSeries(Optional.of(H11)),
            List.of(new CompositionEntry("步枪", 50)),
            2,
            500,
            Optional.empty(),
            UnitStatus.MOVING,
            attachedSeries(true),
            noOffset(),
            Optional.empty(),
            Unit.DEFAULT_VISION_RADIUS,
            null);

    assertThat(unit.jurisdiction()).as("旧档缺参给 null ⇒ 归一成 empty（不抛、不留下 null）").isEmpty();
  }

  @Test
  void compatibilityConstructorsLeaveJurisdictionEmpty() {
    assertThat(nineParameterUnit().jurisdiction()).as("9 参兼容形态没有管辖来源 ⇒ empty").isEmpty();
    assertThat(thirteenParameterUnit().jurisdiction()).as("13 参兼容形态同理").isEmpty();
    assertThat(fourteenParameterUnit().jurisdiction()).as("14 参兼容形态同理").isEmpty();
  }

  // ── ★★ 全部生产拷贝点：逐处不丢 jurisdiction ───────────────────

  @Test
  void renamePreservesJurisdiction() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));
    assertCopied(
        base.units().get(U1),
        UnitOperations.rename(base, U1, "新名").units().get(U1),
        "rename",
        "name");
  }

  @Test
  void setCompositionPreservesJurisdiction() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));
    assertCopied(
        base.units().get(U1),
        UnitOperations.setComposition(base, U1, List.of(new CompositionEntry("步枪", 40)))
            .units()
            .get(U1),
        "setComposition",
        "equipment");
  }

  @Test
  void applyCasualtiesPreservesJurisdiction() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));
    assertCopied(
        base.units().get(U1),
        UnitOperations.applyCasualties(base, U1, List.of(new CompositionDelta("步枪", -10)))
            .units()
            .get(U1),
        "applyCasualties",
        "equipment");
  }

  @Test
  void placeAtPreservesJurisdiction() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));
    assertCopied(
        base.units().get(U1),
        UnitOperations.placeAt(base, U1, Optional.of(H12), T1).units().get(U1),
        "placeAt",
        "position");
  }

  @Test
  void planRoutePreservesJurisdiction() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));
    assertCopied(
        base.units().get(U1),
        UnitOperations.planRoute(base, U1, corridor(), T0).units().get(U1),
        "planRoute",
        "movement");
  }

  @Test
  void cancelRoutePreservesJurisdiction() {
    // 先真的装上一条路线 ⇒ cancelRoute 的差集里 movement 才会出现（否则判据对"清空"这一支恒真）。
    UnitState planned =
        UnitOperations.planRoute(
            stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction())),
            U1,
            corridor(),
            T0);
    assertCopied(
        planned.units().get(U1),
        UnitOperations.cancelRoute(planned, U1).units().get(U1),
        "cancelRoute",
        "movement");
  }

  @Test
  void reparentPreservesJurisdiction() {
    UnitState base =
        stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()), plain(U2, H13));
    assertCopied(
        base.units().get(U1),
        UnitOperations.reparent(base, U1, Optional.of(U2), T1).units().get(U1),
        "reparent",
        "parent");
  }

  @Test
  void setStatusPreservesJurisdiction() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));
    assertCopied(
        base.units().get(U1),
        UnitOperations.setStatus(base, U1, UnitStatus.RESTING).units().get(U1),
        "setStatus",
        "status");
  }

  @Test
  void setRejoinTargetPreservesJurisdiction() {
    UnitState base =
        stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()), plain(U2, H13));
    assertCopied(
        base.units().get(U1),
        UnitOperations.setRejoinTarget(base, U1, Optional.of(U2)).units().get(U1),
        "setRejoinTarget",
        "rejoinTarget");
  }

  @Test
  void attachSubtreePreservesJurisdiction() {
    // 编制 v2：attach 只改 parent + attached；两单位必须同格（都在 H11）。
    UnitState base =
        stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()), plain(U2, H11));
    assertCopied(
        base.units().get(U1),
        UnitOperations.attachSubtree(base, U1, U2, T1).units().get(U1),
        "attachSubtree",
        "parent",
        "attached");
  }

  @Test
  void detachUnitPreservesJurisdiction() {
    UnitState base = stateOf(childOfU2(sampleJurisdiction()), plain(U2, H11));
    assertCopied(
        base.units().get(U1),
        UnitOperations.detachUnit(base, U1, T1).units().get(U1),
        "detachUnit",
        "attached");
  }

  @Test
  void splitFormationPreservesJurisdiction() {
    // splitFormation 对每个目标复用 detachUnit ⇒ 这里钉的是它的公共入口这一面。
    UnitState base = stateOf(childOfU2(sampleJurisdiction()), plain(U2, H11));
    assertCopied(
        base.units().get(U1),
        UnitOperations.splitFormation(base, U2, List.of(U1), T1).units().get(U1),
        "splitFormation",
        "attached");
  }

  @Test
  void setOffsetPreservesJurisdiction() {
    UnitState base = stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));
    assertCopied(
        base.units().get(U1),
        UnitOperations.setOffset(base, U1, Optional.of(new RelativeOffset(1, 0)), T1)
            .units()
            .get(U1),
        "setOffset",
        "offset");
  }

  @Test
  void reparentSubtreePreservesJurisdiction() {
    UnitState base = stateOf(childOfU2(sampleJurisdiction()), plain(U2, H11));
    assertCopied(
        base.units().get(U1),
        UnitOperations.reparentSubtree(base, U1, Optional.empty(), T1).units().get(U1),
        "reparentSubtree",
        "parent");
  }

  /** mergeFormation 的 {@code withSpeed} 只改存活方（父）的 speed；父的管辖同样逐值带过。 */
  @Test
  void mergeFormationPreservesJurisdictionOnTheSurvivingParent() {
    UnitState base =
        stateOf(
            unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()),
            unit(
                U2,
                Optional.empty(),
                Optional.of(H11),
                UnitStatus.MOVING,
                5,
                sampleJurisdiction()));

    UnitState next = UnitOperations.mergeFormation(base, U1, U2, T1);

    Unit after = next.units().get(U2);
    assertThat(after.speed()).as("合体后整支里最慢者（u-1 speed 2）落到存活方").isEqualTo(2);
    assertCopied(base.units().get(U2), after, "mergeFormation 的 withSpeed", "speed");
  }

  @Test
  void frozenViewInUnitMovesPreservesJurisdiction() {
    Unit inFlight =
        withMovement(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));
    CapturingCost cost = new CapturingCost();
    UnitMoves.evaluate(inFlight, T1, map(), cost);
    assertThat(cost.seen).as("成本函数必须真的被询价过（否则本用例对 frozen 视图恒真）").isNotEmpty();
    assertCopied(inFlight, cost.seen.get(0), "UnitMoves.evaluate 的 frozen 视图");
  }

  @Test
  void tickMaterializationPreservesJurisdiction() {
    Unit inFlight =
        withMovement(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()));
    UnitState base = stateOf(inFlight);
    UnitState next = applyProposal(base, rangeTo(1), new FixedCost());
    assertCopied(
        base.units().get(U1), next.units().get(U1), "推进物化 withPositionAndMovement", "position");
  }

  @Test
  void rejoinReplanPreservesJurisdiction() {
    // 第二趟（withMovement）：U1 无在途行程、位于 H11，回归目标 U2 在 H13 ⇒ 本刻重新装载一条 Movement。
    UnitState base =
        stateOf(unit(U1, Optional.empty(), Optional.of(H11), sampleJurisdiction()), plain(U2, H13));
    UnitState aimed = UnitOperations.setRejoinTarget(base, U1, Optional.of(U2));
    UnitState next = applyProposal(aimed, rangeTo(1), new FixedCost());
    Unit before = aimed.units().get(U1);
    Unit after = next.units().get(U1);
    assertThat(after.movement()).as("回归轨道必须真的装载了行程（否则本用例对 withMovement 恒真）").isPresent();
    assertCopied(before, after, "回归重规划 withMovement", "movement");
  }

  // ── 装置：逐 record 分量对拍 / 断言 ─────────────────────────────

  /**
   * ★★ **本测试的主力装置**：逐 record 分量对拍 {@code before}/{@code after}，返回**值发生变化**的分量名集。
   *
   * <p>**不按名字引用 {@code jurisdiction}** ⇒ 拷贝点漏传那一刻，{@code "jurisdiction"} 自己出现在差集里。写死比较对象是错的做法：
   * 那样新增第 16 个分量时本装置不会自动跟上，正是铁律 5 的由来（{@code MapDiff} 手工维护、四个字段漂移出去）。
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

  /** 领域操作的判据：**恰好**这些分量变了（不含 jurisdiction 的语义见下一个助手）。 */
  private static void assertChangedExactly(Unit before, Unit after, String op, String... expected) {
    assertThat(changedComponents(before, after))
        .as("%s：应恰有这些分量变化（多一个少一个都红）", op)
        .isEqualTo(Set.of(expected));
  }

  /**
   * ★★ 拷贝点的判据：**恰好**这些分量变了，且 {@code jurisdiction} **逐值原样带过**。
   *
   * <p>前置两条（夹具带非空管辖）防的是"夹具不小心用成 empty ⇒ 用例恒真"。逐值相等这条让失败信息直接说"管辖丢了"， {@link #changedComponents}
   * 那条则让**新增字段**未来也能被自动抓住。
   */
  private static void assertCopied(Unit before, Unit after, String op, String... expected) {
    assertThat(before.jurisdiction()).as("%s 的夹具必须带非空 jurisdiction（否则本用例对丢字段恒真）", op).isPresent();
    assertThat(before.jurisdiction().orElseThrow().taxRatePerMilleByRegion())
        .as("%s 的夹具管辖区域必须非空", op)
        .isNotEmpty();
    assertThat(after.jurisdiction())
        .as("%s：jurisdiction 必须逐值原样带过（丢失 ⇒ 当场红）", op)
        .isEqualTo(before.jurisdiction());
    assertChangedExactly(before, after, op, expected);
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

  private static Unit unit(
      UnitId id, Optional<UnitId> parent, Optional<HexCoord> position, Jurisdiction jurisdiction) {
    return unit(id, parent, position, UnitStatus.MOVING, 2, Optional.of(jurisdiction));
  }

  private static Unit unitWithoutJurisdiction(UnitId id, HexCoord position) {
    return unit(
        id, Optional.empty(), Optional.of(position), UnitStatus.MOVING, 2, Optional.empty());
  }

  private static Unit unit(
      UnitId id,
      Optional<UnitId> parent,
      Optional<HexCoord> position,
      UnitStatus status,
      int speed,
      Jurisdiction jurisdiction) {
    return unit(id, parent, position, status, speed, Optional.of(jurisdiction));
  }

  private static Unit unit(
      UnitId id,
      Optional<UnitId> parent,
      Optional<HexCoord> position,
      UnitStatus status,
      int speed,
      Optional<Jurisdiction> jurisdiction) {
    return new Unit(
        id,
        "单位 " + id.value(),
        parentSeries(parent),
        positionSeries(position),
        List.of(new CompositionEntry("步枪", 50)),
        speed,
        500,
        Optional.empty(),
        status,
        attachedSeries(true),
        noOffset(),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        jurisdiction);
  }

  /** U1 挂在 U2 之下（detach / split / reparentSubtree 的输入）。 */
  private static Unit childOfU2(Jurisdiction jurisdiction) {
    return unit(U1, Optional.of(U2), Optional.of(H11), jurisdiction);
  }

  /** 编队操作里"另一个单位"（父或子树外的旁观者）。 */
  private static Unit plain(UnitId id, HexCoord position) {
    return unit(id, Optional.empty(), Optional.of(position), sampleJurisdiction());
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

  private static Unit nineParameterUnit() {
    return new Unit(
        U1,
        "第一连",
        parentSeries(Optional.empty()),
        positionSeries(Optional.of(H11)),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty());
  }

  private static Unit thirteenParameterUnit() {
    return new Unit(
        U1,
        "第一连",
        parentSeries(Optional.empty()),
        positionSeries(Optional.of(H11)),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        attachedSeries(true),
        noOffset(),
        Optional.empty());
  }

  private static Unit fourteenParameterUnit() {
    return new Unit(
        U1,
        "第一连",
        parentSeries(Optional.empty()),
        positionSeries(Optional.of(H11)),
        List.of(new CompositionEntry("步枪", 50)),
        2,
        500,
        Optional.empty(),
        UnitStatus.MOVING,
        attachedSeries(true),
        noOffset(),
        Optional.empty(),
        2);
  }

  private static Unit withMovement(Unit unit) {
    return new Unit(
        unit.id(),
        unit.name(),
        unit.parent(),
        unit.position(),
        unit.equipment(),
        unit.speed(),
        unit.mobilityPerMille(),
        Optional.of(new Movement(corridor(), T0, unit.effectiveSpeed(), unit.mobilityPerMille())),
        unit.status(),
        unit.attached(),
        unit.offset(),
        unit.rejoinTarget(),
        unit.visionRadius(),
        unit.jurisdiction());
  }

  private static UnitState stateOf(Unit... units) {
    Map<UnitId, Unit> byId = new LinkedHashMap<>();
    for (Unit unit : units) {
      byId.put(unit.id(), unit);
    }
    return new UnitState(byId);
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

  /** 三格走廊 + 四个区域（{@code r-a} 到 {@code r-d}；{@code r-ghost} 刻意不在表里）。 */
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

  /** 每段固定 48000：日制下 speed 2 的**一天预算** = 48000（1 tick = 1 天）⇒ 走得动一格、且仍在途（movement 不被清）。 */
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

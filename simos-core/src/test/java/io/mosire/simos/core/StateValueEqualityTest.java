package io.mosire.simos.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.HexCell;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.block.TerrainBlocks;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.codec.SocialCodec;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * **R4 的夹具前提，本身即护栏**（台账 §辛，2026-09-18 当场探针转正）：{@code Snapshot} / {@link SimulationState}
 * 到底有没有**值相等**语义？
 *
 * <p>★ **为什么这条不是"顺手加的测试"**：Task 8 的 R4 判据是「从最近 checkpoint 重放」与「从创世重放」的结果**对拍**——对拍写的是 {@code
 * assertThat(a).isEqualTo(b)}。若这些类型只有**引用**相等，对拍就**恒假**（一旦两侧是不同对象）或**恒真**（若是同一个对象），
 * 该护栏就是装饰，而**绿**会让它看起来比装饰还好。⇒ 这条先独立证明"对拍这个动作本身有判别力"，才轮到 R4 用它。
 *
 * <p>★ **它为什么住在 simos-core 的测试里**（而不是 util）：被测的三条快照分别住 map/social/unit 三个模块，util 一个都够不着（铁律 3 +
 * {@code bannedDependencies}）——**只有 core 的 test scope 同时看得见三者**。
 *
 * <p>三类断言逐组重复，每组三问（缺一不可）：
 *
 * <ol>
 *   <li>**值相等**：同一条快照 {@code encode → decode} 后的**另一个对象**与原对象 {@code equals}；
 *   <li>★ **R4 的真实形状**：两个**值相等但不同对象**的 base，各自 {@code apply} **同一**变更集，两个结果必须 {@code
 *       equals}——{@code apply} 若**别名**了 base 的某个子对象，而两侧子对象在**其它路**上恰好身份相等，
 *       就只有这一问能现形（既有往返用例用的是**同一个** base，覆盖不到）；
 *   <li>**判别力**：施加变更后的状态必须**不**等于施加前的（否则 {@code equals} 恒真，前两问全无意义）。
 * </ol>
 *
 * <p>★ **本护栏已覆盖到的边界，如实记下**：夹具的 {@code GameMap} 只填了 {@code hexes} 一个字段（{@code regions} / {@code
 * pathways} / {@code edges} / 河流等一概为空）⇒ 本条证明的是"**这些字段为空时**值相等成立"。字段更满时是否有 绕过 {@code equals}
 * 的别名路径，**没有被本护栏证伪，也没有被证实**——不写进结论。
 */
class StateValueEqualityTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final SimosTimestamp T11 = SimosTimestamp.of(11);
  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H12 = new HexCoord(1, 2);
  private static final HexCoord H55 = new HexCoord(5, 5);

  private static final MapCodec MAP = new MapCodec();
  private static final SocialCodec SOCIAL = new SocialCodec();
  private static final UnitCodec UNIT = new UnitCodec();

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(3));
  private static final StateMeta META = new StateMeta(REF, T11);

  @Test
  void everySnapshotHasValueEqualityNotJustIdentity() {
    GameMap mapBase = mapWithHex(H55, 0.35);
    SocialData socialBase = onePopulation(H00);
    UnitState unitBase =
        UnitState.empty().withUnits(Map.of(new UnitId("u-1"), oneUnit("u-1", H11)));

    MapSnapshot mapSnap = new MapSnapshot(REF, T11, mapBase);
    MapSnapshot mapSnapCopy = (MapSnapshot) MAP.decodeSnapshot(MAP.encodeSnapshot(mapSnap));
    MapSnapshot mapAppliedA =
        (MapSnapshot)
            MAP.apply(
                MapChangeSet.between(mapBase, mapBase.withHexes(Map.of(H55, new HexCell(0.9)))),
                mapSnap,
                META);
    MapSnapshot mapAppliedB =
        (MapSnapshot)
            MAP.apply(
                MapChangeSet.between(mapBase, mapBase.withHexes(Map.of(H55, new HexCell(0.9)))),
                mapSnapCopy,
                META);
    assertSnapshotValueSemantics("map", mapSnap, mapSnapCopy, mapAppliedA, mapAppliedB);

    SocialSnapshot socialSnap = new SocialSnapshot(REF, T11, socialBase);
    SocialSnapshot socialSnapCopy =
        (SocialSnapshot) SOCIAL.decodeSnapshot(SOCIAL.encodeSnapshot(socialSnap));
    SocialSnapshot socialAppliedA =
        (SocialSnapshot)
            SOCIAL.apply(SocialChangeSet.between(socialBase, onePopulation(H11)), socialSnap, META);
    SocialSnapshot socialAppliedB =
        (SocialSnapshot)
            SOCIAL.apply(
                SocialChangeSet.between(socialBase, onePopulation(H11)), socialSnapCopy, META);
    assertSnapshotValueSemantics(
        "social", socialSnap, socialSnapCopy, socialAppliedA, socialAppliedB);

    UnitState unitTarget =
        UnitState.empty().withUnits(Map.of(new UnitId("u-2"), oneUnit("u-2", H12)));
    UnitSnapshot unitSnap = new UnitSnapshot(REF, T11, unitBase);
    UnitSnapshot unitSnapCopy = (UnitSnapshot) UNIT.decodeSnapshot(UNIT.encodeSnapshot(unitSnap));
    UnitSnapshot unitAppliedA =
        (UnitSnapshot) UNIT.apply(UnitChangeSet.between(unitBase, unitTarget), unitSnap, META);
    UnitSnapshot unitAppliedB =
        (UnitSnapshot) UNIT.apply(UnitChangeSet.between(unitBase, unitTarget), unitSnapCopy, META);
    assertSnapshotValueSemantics("unit", unitSnap, unitSnapCopy, unitAppliedA, unitAppliedB);
  }

  /**
   * ★ 三个切片**各自独立 decode** 后组装（而不是共用同一批对象再传给两个 {@code SimulationState}）—— 共用的话 {@code equals}
   * 会因为**同一批子对象**而走捷径，测不出"整体是否按值比较"。
   */
  @Test
  void simulationStateComparesByValueAcrossIndependentDecodes() {
    GameMap mapBase = mapWithHex(H55, 0.35);
    MapSnapshot mapSnap = new MapSnapshot(REF, T11, mapBase);
    MapSnapshot mapSnapCopy = (MapSnapshot) MAP.decodeSnapshot(MAP.encodeSnapshot(mapSnap));
    SocialSnapshot socialSnap = new SocialSnapshot(REF, T11, onePopulation(H00));
    SocialSnapshot socialSnapCopy =
        (SocialSnapshot) SOCIAL.decodeSnapshot(SOCIAL.encodeSnapshot(socialSnap));
    UnitSnapshot unitSnap = new UnitSnapshot(REF, T11, UnitState.empty());
    UnitSnapshot unitSnapCopy = (UnitSnapshot) UNIT.decodeSnapshot(UNIT.encodeSnapshot(unitSnap));

    SimulationState s1 =
        new SimulationState(
            META,
            Map.of("map", mapSnap, "social", socialSnap, "unit", unitSnap),
            InMemoryInfoSystem.empty());
    SimulationState s2 =
        new SimulationState(
            new StateMeta(REF, T11),
            Map.of("map", mapSnapCopy, "social", socialSnapCopy, "unit", unitSnapCopy),
            InMemoryInfoSystem.empty());

    assertThat(s1.meta())
        .as("[前提] 两个 SimulationState 的 META 必须是**不同对象**，否则下面的相等可由身份传递")
        .isNotSameAs(s2.meta());
    assertThat(s1).as("[1] 三个切片各自独立 decode 后组装的 SimulationState 必须值相等（R4 对拍的前提）").isEqualTo(s2);
  }

  private static void assertSnapshotValueSemantics(
      String name, Snapshot base, Snapshot baseCopy, Snapshot appliedA, Snapshot appliedB) {
    assertThat(baseCopy).as(name + " [前提] decode 出来的必须是另一个对象，否则 [1] 由身份成立，无判别力").isNotSameAs(base);
    assertThat(appliedA)
        .as(name + " [前提] 两路 apply 的结果必须不同对象，否则 [2] 由身份成立，无判别力")
        .isNotSameAs(appliedB);

    assertThat(baseCopy).as(name + " [1] encode→decode 后值相等").isEqualTo(base);
    assertThat(appliedA)
        .as(name + " [2] ★两个值相等的 base 各自 apply 同一变更集，结果必须相等（R4 的真实形状）")
        .isEqualTo(appliedB);
    assertThat(appliedA).as(name + " [3] 判别力：施加变更后的状态必须不等于施加前，否则 equals 恒真").isNotEqualTo(base);
  }

  private static SocialData onePopulation(HexCoord at) {
    return new SocialData(
        Map.of(
            at,
            new PopulationSeries(
                new Segment<>(T0, 10000L),
                new SegmentedSeries<>(List.of(new Segment<>(T0, 0.02)), List.of(), null),
                List.of())),
        Map.of());
  }

  /** 单格、单地形（{@code plains}）的图：hexes 与 terrainBlocks 原子构造（P1 分割不变式）。 */
  private static GameMap mapWithHex(HexCoord at, double height) {
    return new GameMap(
        Map.of(at, new HexCell(height)),
        TerrainBlocks.uniform(java.util.Set.of(at), "plains"),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        io.mosire.simos.map.generate.GenerationSpec.defaults(0L));
  }

  private static Unit oneUnit(String id, HexCoord at) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(at))), List.of(), null),
        500,
        Map.of("旗帜", 3),
        2,
        1000,
        Optional.empty());
  }
}

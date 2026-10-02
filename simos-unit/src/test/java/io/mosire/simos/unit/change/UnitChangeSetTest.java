package io.mosire.simos.unit.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 单位变更集：与 {@code SocialChangeSet} 同形（同键覆盖、新增、删除、又增又删 = Patch、空集）。 */
class UnitChangeSetTest {

  private static final SimosTimestamp T0 = SimosTimestamp.of(0);
  private static final HexCoord H11 = new HexCoord(1, 1);
  private static final HexCoord H22 = new HexCoord(2, 2);

  /** 两个根单位（无父 ⇒ 天然合法树；变更集不校验编制树，但夹具必须是合法的）。 */
  private static Unit unit(String id, int manpowerAmount, HexCoord position) {
    return new Unit(
        new UnitId(id),
        "单位 " + id,
        new SegmentedSeries<>(
            List.of(new Segment<>(T0, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(T0, Optional.of(position))), List.of(), null),
        List.of(new CompositionEntry("步兵", manpowerAmount)),
        List.of(),
        2,
        1000,
        Optional.empty());
  }

  private static Map<UnitId, Unit> twoUnits(int manpowerAmount1, int manpowerAmount2) {
    Map<UnitId, Unit> units = new LinkedHashMap<>();
    units.put(new UnitId("u-1"), unit("u-1", manpowerAmount1, H11));
    units.put(new UnitId("u-2"), unit("u-2", manpowerAmount2, H22));
    return units;
  }

  @Test
  void unchangedStateGivesAnEmptyChangeSet() {
    UnitState base = new UnitState(twoUnits(100, 200));
    assertThat(UnitChangeSet.between(base, base).isEmpty()).isTrue();
    assertThat(UnitChangeSet.between(base, base).units()).isInstanceOf(FieldDelta.Unchanged.class);
  }

  /** ★ 只改一条记录（manpower 表的一条）⇒ 非空。这一条就是 GSimulator"只改了一条边产生空 diff"的逆否形态。 */
  @Test
  void aSingleChangedUnitIsNotAnEmptyChangeSet() {
    UnitState base = new UnitState(twoUnits(100, 200));
    UnitState target = new UnitState(twoUnits(100, 201));

    UnitChangeSet cs = UnitChangeSet.between(base, target);
    assertThat(cs.isEmpty()).isFalse();
    assertThat(UnitChangeSet.apply(cs, base)).isEqualTo(target);
  }

  /**
   * ★ R-7-a 的序观察点（Task 4 的 {@code populationOrderFollowsInsertionOrder} 之 unit 版）：spec §4.1 冻结要点 1
   * 把"units 保序不可变、绝不用 {@code Map.copyOf}"列为冻结，本用例就是它的观测者：6 键、非平凡插入序、{@code containsExactly}
   * 逐位钉键序。{@code Map.copyOf} 走散列槽位序 —— UnitId 是 String 系 record 键，槽位序**依 JVM 盐而定**： 5 键候选实测只有
   * **17/20** 次 JVM 启动打乱（**3/20 保插入序**，红点会漂），不达标 ⇒ 换成 6 键后 **20/20** 全 SHUFFLED（10 种 槽位序）。实测存档
   * task-7-evidence/order-fixture-20jvm-{5keys-rejected,6keys}.txt。
   */
  @Test
  void unitOrderFollowsInsertionOrder() {
    Map<UnitId, Unit> inserted = new LinkedHashMap<>();
    inserted.put(new UnitId("u-1"), unit("u-1", 100, H11));
    inserted.put(new UnitId("corps-2"), unit("corps-2", 200, H22));
    inserted.put(new UnitId("division-33"), unit("division-33", 300, H11));
    inserted.put(new UnitId("brigade-444"), unit("brigade-444", 400, H22));
    inserted.put(new UnitId("regiment-5555"), unit("regiment-5555", 500, H11));
    inserted.put(new UnitId("battalion-66666"), unit("battalion-66666", 600, H22));
    UnitState state = new UnitState(inserted);

    assertThat(state.units().keySet())
        .as("UnitState 必须保插入序（spec §4.1 冻结要点 1；Map.copyOf 的槽位序会当场红）")
        .containsExactly(
            new UnitId("u-1"),
            new UnitId("corps-2"),
            new UnitId("division-33"),
            new UnitId("brigade-444"),
            new UnitId("regiment-5555"),
            new UnitId("battalion-66666"));
  }

  @Test
  void removalWithoutUpsertIsRemoveAndSurvivesApply() {
    UnitState base = new UnitState(twoUnits(100, 200));
    UnitState target = new UnitState(Map.of(new UnitId("u-1"), unit("u-1", 100, H11)));

    UnitChangeSet cs = UnitChangeSet.between(base, target);
    assertThat(cs.units()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(UnitChangeSet.apply(cs, base).units()).containsOnlyKeys(new UnitId("u-1"));
  }

  @Test
  void addAndRemoveTogetherIsAPatch() {
    UnitState base = new UnitState(twoUnits(100, 200));
    UnitState target =
        new UnitState(
            Map.of(
                new UnitId("u-1"),
                unit("u-1", 100, H11),
                new UnitId("u-3"),
                unit("u-3", 300, H22)));

    UnitChangeSet cs = UnitChangeSet.between(base, target);
    assertThat(cs.units()).isInstanceOf(FieldDelta.Patch.class);
    assertThat(UnitChangeSet.apply(cs, base)).as("Patch 两侧都不许丢").isEqualTo(target);
  }

  /**
   * ★ Unchanged ⇒ base 原样（连键序）——R-7-a 的最终形态（与 Task 4 同款取代说明）：{@link UnitState} 构造期**总是**冻结拷贝（保序 不可变
   * + 逐键查 null），{@code apply} 经 {@code new UnitState(…)} 落地后必是新实例，{@code isSameAs}
   * 不可满足。被冻结的语义是"内容与键序 原样"，由 {@code containsExactlyEntriesOf}（**含迭代序**）钉住。 ★ 夹具取**单键**（照 Task 4
   * 同款）：双键 base 的 {@code Map.copyOf} 槽位序依 JVM 盐而定（{"u-1","u-2"} 实测 13/20 保序 / 7/20 打乱），若在这里钉双键序，m3
   * 变异轮会**漂红**；多键的序观察 职责已由 {@link #unitOrderFollowsInsertionOrder}（20/20 打乱的 6
   * 键夹具）承担，本用例只钉"原样"，单键无从重排。
   */
  @Test
  void applyOfUnchangedKeepsTheBaseMapIdentical() {
    UnitState base = new UnitState(Map.of(new UnitId("u-1"), unit("u-1", 100, H11)));
    UnitChangeSet cs = UnitChangeSet.between(base, base);
    assertThat(UnitChangeSet.apply(cs, base).units())
        .as("Unchanged ⇒ base 原样（连键序），不许 rebuild 出重排/重造")
        .containsExactlyEntriesOf(base.units());
  }

  @Test
  void unitStateIsFrozenAndRejectsNulls() {
    Map<UnitId, Unit> mutable = new LinkedHashMap<>();
    mutable.put(new UnitId("u-1"), unit("u-1", 100, H11));
    UnitState state = new UnitState(mutable);
    mutable.put(new UnitId("u-2"), unit("u-2", 200, H22));
    assertThat(state.units()).as("构造期已冻结").containsOnlyKeys(new UnitId("u-1"));
    assertThat(state.units().get(new UnitId("u-1")).manpower())
        .containsExactly(new CompositionEntry("步兵", 100));

    mutable.put(new UnitId("u-1"), null);
    assertThatThrownBy(() -> new UnitState(mutable)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new UnitState(null)).isInstanceOf(IllegalArgumentException.class);
  }
}

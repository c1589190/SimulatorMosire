package io.mosire.simos.social.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 人口变更集：与 {@code MapChangeSet} 同形（同键覆盖、新增、删除、又增又删 = Patch、空集）。 */
class SocialChangeSetTest {

  private static final HexCoord H00 = new HexCoord(0, 0);
  private static final HexCoord H10 = new HexCoord(1, 0);
  private static final HexCoord H20 = new HexCoord(2, 0);

  private static PopulationSeries population(long people) {
    return new PopulationSeries(
        new Segment<>(SimosTimestamp.of(0), people),
        new SegmentedSeries<>(List.of(new Segment<>(SimosTimestamp.of(0), 0.02)), List.of(), null),
        List.of());
  }

  private static SocialData data(Map<HexCoord, PopulationSeries> populations) {
    return new SocialData(populations);
  }

  @Test
  void unchangedDataGivesAnEmptyChangeSet() {
    SocialData base = data(Map.of(H00, population(100)));
    assertThat(SocialChangeSet.between(base, base).isEmpty()).isTrue();
    assertThat(SocialChangeSet.between(base, base).populations())
        .isInstanceOf(FieldDelta.Unchanged.class);
  }

  /** ★ 只改一条记录 ⇒ 非空。这一条就是 GSimulator"只改了一条边产生空 diff"的逆否形态。 */
  @Test
  void aSingleChangedEntryIsNotAnEmptyChangeSet() {
    SocialData base = data(Map.of(H00, population(100), H10, population(200)));
    Map<HexCoord, PopulationSeries> next = new LinkedHashMap<>();
    next.put(H00, population(100));
    next.put(H10, population(201));
    SocialData target = data(next);

    SocialChangeSet cs = SocialChangeSet.between(base, target);
    assertThat(cs.isEmpty()).isFalse();
    assertThat(SocialChangeSet.apply(cs, base)).isEqualTo(target);
  }

  /**
   * ★ 取代说明（对计划 Step 5 m2 的"把夹具加到 4~6 键"）：**键数救不了序不敏感的断言**（R-4-b；实测 m3t4v-2a/2b
   * 两轮全绿），保序必须有**序观察点**。spec §3.1 冻结要点 1 把"保序不可变、绝不用 {@code Map.copyOf}"列为冻结，本用例就是它的观测者：5
   * 键、非平凡插入序、{@code containsExactly} 逐位钉键序。{@code Map.copyOf} 走散列槽位序 —— 本机对这组键实测 **0/20** 次 JVM
   * 启动保插入序 （8 种不同槽位序，见 task-4-report.md §m2 轨迹）。
   */
  @Test
  void populationOrderFollowsInsertionOrder() {
    Map<HexCoord, PopulationSeries> inserted = new LinkedHashMap<>();
    inserted.put(H00, population(100));
    inserted.put(new HexCoord(3, 1), population(200));
    inserted.put(new HexCoord(1, 2), population(300));
    inserted.put(new HexCoord(2, -1), population(400));
    inserted.put(new HexCoord(4, 4), population(500));
    SocialData data = data(inserted);

    assertThat(data.populations().keySet())
        .as("SocialData 必须保插入序（spec §3.1 冻结要点 1；Map.copyOf 的槽位序会当场红）")
        .containsExactly(
            H00, new HexCoord(3, 1), new HexCoord(1, 2), new HexCoord(2, -1), new HexCoord(4, 4));
  }

  @Test
  void removalWithoutUpsertIsRemoveAndSurvivesApply() {
    Map<HexCoord, PopulationSeries> next = new LinkedHashMap<>();
    next.put(H00, population(100));
    SocialData base = data(Map.of(H00, population(100), H10, population(200)));
    SocialData target = data(next);

    SocialChangeSet cs = SocialChangeSet.between(base, target);
    assertThat(cs.populations()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(SocialChangeSet.apply(cs, base).populations()).containsOnlyKeys(H00);
  }

  @Test
  void addAndRemoveTogetherIsAPatch() {
    Map<HexCoord, PopulationSeries> next = new LinkedHashMap<>();
    next.put(H00, population(100));
    next.put(H20, population(300));
    SocialData base = data(Map.of(H00, population(100), H10, population(200)));
    SocialData target = data(next);

    SocialChangeSet cs = SocialChangeSet.between(base, target);
    assertThat(cs.populations()).isInstanceOf(FieldDelta.Patch.class);
    assertThat(SocialChangeSet.apply(cs, base)).as("Patch 两侧都不许丢").isEqualTo(target);
  }

  /**
   * ★ Unchanged ⇒ base 原样（连键序）——spec §3.4 的原文。 取代说明（对计划第 945 行的 {@code isSameAs}）： SocialData
   * 构造期**总是**冻结拷贝（保序不可变 + 逐键查 null，与 M2 的 GameMap 同形制），{@code apply} 经 {@code new SocialData(…)}
   * 落地后必是新实例，{@code isSameAs} 在计划自带的实现下不可满足（M2 也因此没有 这条断言）。被冻结的语义是"内容与键序原样"，由 {@code
   * containsExactlyEntriesOf}（**含迭代序**）钉住。
   */
  @Test
  void applyOfUnchangedKeepsTheBaseMapIdentical() {
    SocialData base = data(Map.of(H00, population(100)));
    SocialChangeSet cs = SocialChangeSet.between(base, base);
    assertThat(SocialChangeSet.apply(cs, base).populations())
        .as("Unchanged ⇒ base 原样（连键序），不许 rebuild 出重排/重造")
        .containsExactlyEntriesOf(base.populations());
  }

  @Test
  void socialDataIsFrozenAndRejectsNulls() {
    Map<HexCoord, PopulationSeries> mutable = new LinkedHashMap<>();
    mutable.put(H00, population(100));
    SocialData data = data(mutable);
    mutable.put(H10, population(200));
    assertThat(data.populations()).as("构造期已冻结").containsOnlyKeys(H00);
    assertThat(data.populations().get(H00).anchor().value()).isEqualTo(100L);

    Map<HexCoord, PopulationSeries> withNull = new LinkedHashMap<>();
    withNull.put(H00, null);
    assertThatThrownBy(() -> data(withNull)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> data(null)).isInstanceOf(IllegalArgumentException.class);
  }
}

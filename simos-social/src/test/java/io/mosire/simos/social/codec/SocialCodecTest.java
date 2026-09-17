package io.mosire.simos.social.codec;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * R-3-social：social 模块的 JSON 往返守卫（M4 Task 3 Step 4，与 {@code MapCodecTest} 同制，夹具是 social 自己的）。
 *
 * <p>★ 覆盖：{@code Optional}（历注两侧向 + {@code SegmentedSeries.addition=null} 的 null 组件往返）、自定义键 {@code
 * HexCoord}、密封接口 {@code FieldDelta} 四变体。往返只断 {@code equals}（裁定 12）。
 */
class SocialCodecTest {

  private static final HexCoord H00 = new HexCoord(0, 0);

  private static final HexCoord H11 = new HexCoord(1, 1);

  private static final SocialCodec CODEC = new SocialCodec();

  @Test
  void namespaceIsSocial() {
    assertThat(CODEC.namespace()).isEqualTo("social");
  }

  /** 非平凡快照往返：带历注 + {@code Map<HexCoord,…>} 自定义键 + 人口序列（growth 的 addition 是 null）。 */
  @Test
  void snapshotRoundTripsWithLabeledTimestamp() {
    SocialSnapshot snapshot = snapshotOf(onePopulation(H00), SimosTimestamp.of(10, "弘光元年"));
    SocialSnapshot back = (SocialSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
  }

  /** Optional 的另一个方向：无历注（empty）也要活着。 */
  @Test
  void snapshotRoundTripsWithUnlabeledTimestamp() {
    SocialSnapshot snapshot = snapshotOf(onePopulation(H00), SimosTimestamp.of(11));
    SocialSnapshot back = (SocialSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
  }

  /** 变更集往返：四条变体各造一条（Unchanged / Upsert / Remove / Patch），逐条过线。 */
  @Test
  void changeSetRoundTripsWithAllFourDeltaVariants() {
    SocialData full = onePopulation(H00);
    SocialData moved = onePopulation(H11);

    SocialChangeSet unchanged = SocialChangeSet.between(full, full);
    SocialChangeSet upsert = SocialChangeSet.between(SocialData.empty(), full);
    SocialChangeSet remove = SocialChangeSet.between(full, SocialData.empty());
    SocialChangeSet patch = SocialChangeSet.between(full, moved);

    // 前置：夹具确实落在了四条变体上
    assertThat(unchanged.populations()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(upsert.populations()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(remove.populations()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(patch.populations()).isInstanceOf(FieldDelta.Patch.class);

    assertThat((SocialChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(unchanged)))
        .isEqualTo(unchanged);
    assertThat((SocialChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(upsert)))
        .isEqualTo(upsert);
    assertThat((SocialChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(remove)))
        .isEqualTo(remove);
    assertThat((SocialChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(patch)))
        .isEqualTo(patch);
  }

  /** 值类型的绑定不能在读入侧丢成 Map：PopulationSeries 得还是 PopulationSeries。 */
  @Test
  void deltaValuesSurviveAsPopulationSeriesNotAsMaps() {
    SocialChangeSet back =
        (SocialChangeSet)
            CODEC.decodeChangeSet(
                CODEC.encodeChangeSet(
                    SocialChangeSet.between(SocialData.empty(), onePopulation(H00))));
    FieldDelta.Upsert<PopulationSeries> upsert =
        (FieldDelta.Upsert<PopulationSeries>) back.populations();
    assertThat(upsert.entries().get("0_0")).isInstanceOf(PopulationSeries.class);
  }

  /** 施加（C28）：新快照的 ref/timestamp 来自 newMeta，**不是** base 的；且 base 原样不动。 */
  @Test
  void applyProducesNewSnapshotStampedWithNewMeta() {
    SocialSnapshot base = snapshotOf(onePopulation(H00), SimosTimestamp.of(10));
    SocialChangeSet changeSet = SocialChangeSet.between(base.data(), onePopulation(H11));
    StateMeta newMeta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    SocialSnapshot applied = (SocialSnapshot) CODEC.apply(changeSet, base, newMeta);

    assertThat(applied.ref()).isEqualTo(newMeta.ref());
    assertThat(applied.timestamp()).isEqualTo(newMeta.timestamp());
    assertThat(applied.data()).isEqualTo(SocialChangeSet.apply(changeSet, base.data()));
    assertThat(base.data()).isEqualTo(onePopulation(H00));
  }

  // ── 夹具 ──

  private static SocialData onePopulation(HexCoord at) {
    Map<HexCoord, PopulationSeries> populations = new LinkedHashMap<>();
    populations.put(
        at,
        new PopulationSeries(
            new Segment<>(SimosTimestamp.of(0), 10000L),
            new SegmentedSeries<>(
                List.of(new Segment<>(SimosTimestamp.of(0), 0.02)), List.of(), null),
            List.of()));
    return new SocialData(populations);
  }

  private static SocialSnapshot snapshotOf(SocialData data, SimosTimestamp timestamp) {
    return new SocialSnapshot(
        new StateRef(new BranchId("main"), new RevisionId(3)), timestamp, data);
  }
}

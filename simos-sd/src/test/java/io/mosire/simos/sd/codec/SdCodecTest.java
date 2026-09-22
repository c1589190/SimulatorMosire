package io.mosire.simos.sd.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.model.Nation;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** sd 模块的 JSON 往返守卫（与 {@code MapCodecTest}/{@code UnitCodecTest} 同制）。 */
class SdCodecTest {

  private static final SdCodec CODEC = new SdCodec();

  @Test
  void namespaceIsSd() {
    assertThat(CODEC.namespace()).isEqualTo("sd");
  }

  @Test
  void snapshotRoundTripsWithAddressValuesAndLabeledTimestamp() {
    SdSnapshot snapshot =
        new SdSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(3)),
            SimosTimestamp.of(10, "弘光元年"),
            SdFixtures.full());
    SdSnapshot back = (SdSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));
    assertThat(back).isEqualTo(snapshot);
  }

  /**
   * ★ M11 判据：绑定的 {@code providerId} 经**真 JSON** 往返一字不丢（铁律 5 的线格式那半）。变异靶子 m2 = 线格式丢 providerId（如裸往返把
   * {@code Optional} 写坏、或加了 {@code @JsonIgnore}）。
   */
  @Test
  void boundProviderSurvivesTheJsonRoundTrip() {
    SdState sd =
        SdFixtures.empty()
            .withDecisionMakers(
                Map.of(SdFixtures.DM1, SdFixtures.boundDecisionMaker(SdFixtures.DM1, "p-json")));
    SdSnapshot snapshot =
        new SdSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(3)), SimosTimestamp.of(10), sd);

    String json = CODEC.encodeSnapshot(snapshot);
    assertThat(json).as("绑定必须真的写进字节").contains("p-json");

    SdSnapshot back = (SdSnapshot) CODEC.decodeSnapshot(json);
    assertThat(back).isEqualTo(snapshot);
    assertThat(back.state().decisionMakers().get(SdFixtures.DM1).providerId()).contains("p-json");
  }

  /**
   * ★★ **老档兼容**（本任务最硬的一条）：**没有** {@code conversationGeneration} 字段的旧字节，必须读成**世代 0**。
   *
   * <p>★ 为什么这是硬要求：现场已经落盘的老检查点/老档里没有这个字段，读不回来 = **整个世界打不开**。世代 0 同时还是"会话 id 与旧格式逐字相同"那一半（老会话因此接得上）。
   *
   * <p>★★ **做法是把字段从真字节里删掉**，不是在测试里手写一份"看起来像老档"的 JSON：手写的那份只能证明"我写的 JSON 我能读" ——真档是**写出来的那台 mapper
   * 写出来的**，两者不是一回事。故：先编码 ⇒ 逐字删字段（并断言命中恰好 1 次，否则说明线格式 漂移了，本用例**当场红**而不是恒真）⇒ 再解码。
   */
  @Test
  void aLegacyArchiveWithoutTheGenerationFieldReadsAsGenerationZero() {
    SdState sd =
        SdFixtures.empty()
            .withDecisionMakers(
                Map.of(
                    SdFixtures.DM1,
                    SdFixtures.decisionMakerAtGeneration(SdFixtures.DM1, 2),
                    SdFixtures.DM2,
                    SdFixtures.decisionMaker(SdFixtures.DM2)));
    SdSnapshot snapshot =
        new SdSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(3)), SimosTimestamp.of(10), sd);

    String json = CODEC.encodeSnapshot(snapshot);
    assertThat(countOf(json, "\"conversationGeneration\":"))
        .as("★ 先证明这个字段真的写进了字节（两个决策人各一个；写成 0 也照写）")
        .isEqualTo(2);
    String legacy = json.replace(",\"conversationGeneration\":2", "");
    assertThat(countOf(legacy, "\"conversationGeneration\":"))
        .as("删掉的恰好是 DM1 那一个（剩 DM2 的那个）；锚点没命中 ⇒ 这里是 2，本用例据此自证而不是恒真")
        .isEqualTo(1);

    SdSnapshot back = (SdSnapshot) CODEC.decodeSnapshot(legacy);
    assertThat(back.state().decisionMakers().get(SdFixtures.DM1).conversationGeneration())
        .as("★ 老字节（没有这个字段）读回来就是世代 0——世界里没有「重置过」这件事")
        .isZero();
    assertThat(back.state().decisionMakers().get(SdFixtures.DM1).id())
        .as("其余字段一个不少")
        .isEqualTo(SdFixtures.DM1);
  }

  @Test
  void changeSetRoundTripsAllFourDeltaVariants() {
    SdState full = SdFixtures.full();
    SdState empty = SdFixtures.empty();

    SdChangeSet unchanged = SdChangeSet.between(full, full);
    SdChangeSet upsert = SdChangeSet.between(empty, full);
    SdChangeSet remove = SdChangeSet.between(full, empty);
    SdChangeSet patch = SdChangeSet.between(full, patchTarget(full));

    assertThat(unchanged.nations()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(upsert.nations()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(remove.nations()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(patch.nations()).isInstanceOf(FieldDelta.Patch.class);

    assertThat((SdChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(unchanged)))
        .isEqualTo(unchanged);
    assertThat((SdChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(upsert)))
        .isEqualTo(upsert);
    assertThat((SdChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(remove)))
        .isEqualTo(remove);
    assertThat((SdChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(patch))).isEqualTo(patch);
  }

  @Test
  void reEncodeIsByteStable() {
    SdSnapshot snapshot =
        new SdSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(3)),
            SimosTimestamp.of(10),
            SdFixtures.full());
    String first = CODEC.encodeSnapshot(snapshot);
    String second = CODEC.encodeSnapshot(CODEC.decodeSnapshot(first));
    assertThat(second).isEqualTo(first);
  }

  @Test
  void applyAndEncodeSnapshotRejectForeignSlice() {
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));
    SdSnapshot base =
        new SdSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(3)),
            SimosTimestamp.of(10),
            SdFixtures.full());
    SdChangeSet changeSet = SdChangeSet.between(base.state(), base.state());
    StateMeta meta =
        new StateMeta(new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(() -> CODEC.encodeSnapshot(foreign))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 SdSnapshot");
    assertThatThrownBy(() -> CODEC.apply(changeSet, foreign, meta))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 SdSnapshot");
  }

  /** 子串出现次数（老档兼容那条用来自证"真删掉了一个字段"）。 */
  private static int countOf(String haystack, String needle) {
    int count = 0;
    int at = haystack.indexOf(needle);
    while (at >= 0) {
      count++;
      at = haystack.indexOf(needle, at + needle.length());
    }
    return count;
  }

  private static SdState patchTarget(SdState full) {
    Map<NationId, Nation> patched = new LinkedHashMap<>(full.nations());
    patched.remove(SdFixtures.N2);
    NationId extra = new NationId("n-extra");
    patched.put(extra, SdFixtures.nation(extra, new RegionId("r-extra")));
    return full.withNations(patched);
  }

  private record ForeignSlice(StateRef ref, SimosTimestamp timestamp) implements Snapshot {

    @Override
    public String namespace() {
      return "map";
    }
  }
}

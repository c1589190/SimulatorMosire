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

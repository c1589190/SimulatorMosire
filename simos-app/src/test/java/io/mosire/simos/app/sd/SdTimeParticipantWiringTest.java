package io.mosire.simos.app.sd;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.codec.SdCodec;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.time.SdTimeParticipant;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.codec.UnitCodec;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.json.SimosObjectMapper;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * C4 的装配判据：**每个 namespace 恰一个 participant**——注册第二个 {@code sd} 参与者 ⇒ 封存期 {@code TimeAdvance} 构造抛。
 */
class SdTimeParticipantWiringTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final SimosTimestamp T = SimosTimestamp.of(0);

  @TempDir Path tempDir;

  @Test
  void secondSdParticipantIsRejectedAtSeal() {
    CoreSimos core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    try {
      core.register(new SdCodec());
      core.register(new UnitCodec());
      core.register(new SdTimeParticipant("Map1"));
      core.register(new SdTimeParticipant("Map1"));
      core.bootstrapGenesis(genesis());
      assertThatThrownBy(() -> core.replay(new StateRef(MAIN, new RevisionId(1))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("参与者 namespace 重复");
    } finally {
      core.close();
    }
  }

  private static SimulationState genesis() {
    StateRef ref = new StateRef(MAIN, new RevisionId(1));
    UnitId u1 = new UnitId("u-1");
    Unit unit =
        new Unit(
            u1,
            "第一连",
            new SegmentedSeries<>(
                List.of(new Segment<>(T, Optional.<UnitId>empty())), List.of(), null),
            new SegmentedSeries<>(
                List.of(new Segment<>(T, Optional.of(new HexCoord(0, 0)))), List.of(), null),
            100,
            Map.of("步枪", 50),
            2,
            500,
            Optional.empty());
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(u1, unit)));
    return new SimulationState(
        new StateMeta(ref, T),
        Map.of(
            "unit", new UnitSnapshot(ref, T, units),
            "sd", new SdSnapshot(ref, T, SdState.empty())),
        InMemoryInfoSystem.empty());
  }
}

package io.mosire.simos.app.ga;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.codec.ArmyCodec;
import io.mosire.simos.core.CoreConfig;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.codec.GaCodec;
import io.mosire.simos.ga.effect.GaTimeParticipant;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.codec.GovCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.CompositionEntry;
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
class TimeParticipantWiringTest {

  private static final BranchId MAIN = new BranchId("main");
  private static final SimosTimestamp T = SimosTimestamp.of(0);

  @TempDir Path tempDir;

  @Test
  void secondSdParticipantIsRejectedAtSeal() {
    CoreSimos core = new CoreSimos(new CoreConfig(tempDir, 100, SimosObjectMapper.create()));
    try {
      core.register(new GaCodec());
      core.register(new UnitCodec());
      // ★ 2026-10-13 夹具切片欠账：世界补 army / gov 空片 ⇒ 信封必须随之注册两个 codec（漏一个 = 创世当场抛）。
      core.register(new ArmyCodec());
      core.register(new GovCodec());
      core.register(new GaTimeParticipant("Map1"));
      core.register(new GaTimeParticipant("Map1"));
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
            List.of(new CompositionEntry("步枪", 50)),
            2,
            500,
            Optional.empty());
    UnitState units = new UnitState(new LinkedHashMap<>(Map.of(u1, unit)));
    return new SimulationState(
        new StateMeta(ref, T),
        Map.of(
            "unit", new UnitSnapshot(ref, T, units),
            "ga", new GaSnapshot(ref, T, GaState.empty()),
            // ★ 2026-10-13 夹具切片欠账：army 也是时间参与者（ArmyTimeParticipant 逐日判"两军相遇"）
            //   ⇒ 引擎契约"状态必须带每个参与者的切片"要求它必须在场（缺席是装配故障，不是"无事"）；本夹具未播种 ⇒ 空片。
            "army", new ArmySnapshot(ref, T, ArmyData.empty()),
            // ★ 2026-10-13 夹具切片欠账：gov 空片也在场（与 army 同批补；空 gov ⇒ 行政未激活，行为不变）。
            "gov", new GovSnapshot(ref, T, GovState.empty())),
        InMemoryInfoSystem.empty());
  }
}

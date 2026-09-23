package io.mosire.simos.social.spi;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.population.PopulationSeries;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;

/** social 命令 handler 用例的共用夹具（真 {@link SimulationState} + social 切片，不打 DB）。 */
final class SocialSpiFixture {

  static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));

  static final SimosTimestamp T0 = SimosTimestamp.of(0);

  private SocialSpiFixture() {}

  /** 装一个只含 social 切片的真状态，时间戳取 {@link #T0}。 */
  static SimulationState state(SocialData data) {
    return state(data, T0);
  }

  /** 装一个只含 social 切片的真状态（时间戳由调用方给，用于测 anchorTick 缺省）。 */
  static SimulationState state(SocialData data, SimosTimestamp timestamp) {
    return new SimulationState(
        new StateMeta(REF, timestamp),
        Map.of("social", new SocialSnapshot(REF, timestamp, data)),
        InMemoryInfoSystem.empty());
  }

  /** 静止人口序列（增长率 0、无事件），anchor 落在 {@link #T0}。 */
  static PopulationSeries still(long population) {
    return new PopulationSeries(
        new Segment<>(T0, population),
        SegmentedSeries.of(List.of(new Segment<>(T0, 0.0)), List.of(), null),
        List.of());
  }

  static HexCoord hex(int q, int r) {
    return new HexCoord(q, r);
  }
}

package io.mosire.simos.map.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.change.MapChangeSet;
import io.mosire.simos.map.codec.MapCodec;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** R4：不可宽松转换坐标/高度，拒绝必须来自真正命令入口。 */
class ImportRasterHandlerTest {
  private static final ImportRasterHandler HANDLER = new ImportRasterHandler();
  private static final String GOOD =
      "{\"mode\":\"merge\",\"cells\":[{\"q\":-2,\"r\":3,\"terrain\":\"mountains\",\"height\":0.723}]}";

  @Test
  void fullPayloadAppliesAndCodecRoundTripReconstructsTheCompleteTarget() {
    assertThat(HANDLER.type()).isEqualTo("map.ImportRaster");
    HandlerOutcome outcome = HANDLER.handle(state(), GOOD);
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    MapChangeSet delta = (MapChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    MapCodec codec = new MapCodec();
    MapChangeSet back = (MapChangeSet) codec.decodeChangeSet(codec.encodeChangeSet(delta));
    GameMap target = MapChangeSet.apply(back, GameMap.empty());
    assertThat(target.hexes().get(new HexCoord(-2, 3)).height()).isEqualTo(0.723);
    assertThat(target.terrainAt(new HexCoord(-2, 3))).isEqualTo("mountains");
    assertThat(MapChangeSet.apply(delta, GameMap.empty())).isEqualTo(target);
    MapSnapshot snapshot =
        new MapSnapshot(state().meta().ref(), state().meta().timestamp(), target);
    assertThat(codec.decodeSnapshot(codec.encodeSnapshot(snapshot))).isEqualTo(snapshot);
  }

  @Test
  void declaresTheExactHexTargetsAndNoDmUsableCommand() {
    assertThat(HANDLER.targetPaths("Map1", GOOD)).containsExactly("Map1/hex/-2_3");
    assertThat(HANDLER).isNotInstanceOf(io.mosire.simos.util.spi.DmUsableCommand.class);
  }

  @Test
  void rejectsNonIntegerAndOverflowCoordinatesWithoutTruncation() {
    for (String q : new String[] {"1.5", "2147483648", "-2147483649", "\"1\"", "null", "true"}) {
      reject(GOOD.replace("\"q\":-2", "\"q\":" + q), "q/r");
    }
  }

  @Test
  void rejectsNonNumericHeightAndOutOfRangeHeight() {
    for (String h : new String[] {"\"0.7\"", "null", "true", "{}", "[]", "-0.1", "1.1", "1e999"}) {
      reject(GOOD.replace("0.723", h), "height");
    }
  }

  @Test
  void malformedPayloadsAndDuplicateCoordinatesAreRejected() {
    reject("not json", "JSON");
    reject("[]", "对象");
    reject("{\"mode\":\"merge\",\"cells\":[]}", "cells");
    reject(GOOD.replace("\"merge\"", "\"replace\""), "mode");
    reject(GOOD.replace("\"mountains\"", "\"forest\""), "地形");
    reject(
        "{\"mode\":\"merge\",\"cells\":[{\"q\":0,\"r\":0,\"terrain\":\"plains\",\"height\":0.4},{\"q\":0,\"r\":0,\"terrain\":\"ocean\",\"height\":0.1}]}",
        "重复");
  }

  private static void reject(String payload, String reason) {
    HandlerOutcome outcome = HANDLER.handle(state(), payload);
    assertThat(outcome).as(payload).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains(reason);
  }

  private static SimulationState state() {
    StateRef ref = new StateRef(new BranchId("main"), new RevisionId(1));
    SimosTimestamp tick = SimosTimestamp.of(0);
    return new SimulationState(
        new StateMeta(ref, tick),
        Map.of("map", new MapSnapshot(ref, tick, GameMap.empty())),
        InMemoryInfoSystem.empty());
  }
}

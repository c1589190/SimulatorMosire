package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code sd.PutInfo} 的正常 / 拒绝路径与追加语义（R14）。 */
class PutInfoHandlerTest {

  private static final PutInfoHandler HANDLER = new PutInfoHandler();

  @Test
  void putInfoAppendsAnEntryUnderTheCanonicalAddress() {
    SdState next =
        applied(
            SdState.empty(),
            "{\"address\":\"map:Map1:region.r1\",\"key\":\"brief\",\"value\":\"hello\",\"note\":\"n\"}");
    List<SdInfoEntry> entries = next.info().get("map:Map1:region.r1");
    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).key()).isEqualTo("brief");
    assertThat(entries.get(0).value()).isEqualTo("hello");
    assertThat(entries.get(0).note()).contains("n");
  }

  @Test
  void putInfoAcceptsScalarNumbersToo() {
    SdState next =
        applied(SdState.empty(), "{\"address\":\"map:Map1\",\"key\":\"strength\",\"value\":7}");
    assertThat(next.info().get("map:Map1").get(0).value()).isEqualTo(7);
  }

  @Test
  void appendsToAnExistingListForTheSameAddress() {
    SdState first =
        applied(SdState.empty(), "{\"address\":\"map:Map1\",\"key\":\"k1\",\"value\":\"v1\"}");
    SdState second = applied(first, "{\"address\":\"map:Map1\",\"key\":\"k2\",\"value\":\"v2\"}");
    assertThat(second.info().get("map:Map1")).hasSize(2);
    assertThat(second.info().get("map:Map1").get(1).key()).isEqualTo("k2");
  }

  @Test
  void rejectsMalformedAddress() {
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(SdState.empty()),
            "{\"address\":\"not an address\",\"key\":\"k\",\"value\":\"v\"}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
  }

  @Test
  void rejectsMissingValue() {
    HandlerOutcome outcome =
        HANDLER.handle(SdWorlds.world(SdState.empty()), "{\"address\":\"map:Map1\",\"key\":\"k\"}");
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("value");
  }

  private static SdState applied(SdState base, String payload) {
    SimulationState world = SdWorlds.world(base);
    HandlerOutcome outcome = HANDLER.handle(world, payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    SdChangeSet cs = (SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return SdChangeSet.apply(cs, base);
  }
}

package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.SdInfoId;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
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

  /** ★ 第 3 波第 1 步：缺省 id 按 (canonical 地址, 追加序号) 合成；缺省 tags = 空集（无主）。 */
  @Test
  void synthesizesIdAndDefaultsToNoOwner() {
    SdState first =
        applied(SdState.empty(), "{\"address\":\"map:Map1\",\"key\":\"k1\",\"value\":\"v1\"}");
    SdState second = applied(first, "{\"address\":\"map:Map1\",\"key\":\"k2\",\"value\":\"v2\"}");

    assertThat(first.info().get("map:Map1").get(0).id()).isEqualTo(new SdInfoId("map:Map1#0"));
    assertThat(first.info().get("map:Map1").get(0).tags())
        .as("无 tags 载荷 ⇒ 无主（空集，不用 Optional 包）")
        .isEmpty();
    assertThat(second.info().get("map:Map1").get(1).id())
        .as("同址第二条序号递增 ⇒ id 不撞")
        .isEqualTo(new SdInfoId("map:Map1#1"));
  }

  /** ★ 决策结果三件套：显式 id 与 tags 载荷可写入，且 tags 是 DecisionMakerId（不是自由字符串）。 */
  @Test
  void acceptsExplicitIdAndDecisionMakerTags() {
    SdState next =
        applied(
            SdState.empty(),
            "{\"address\":\"map:Map1\",\"key\":\"k\",\"value\":\"v\",\"id\":\"res-1\","
                + "\"tags\":[\"dm2\",\"dm1\"]}");

    SdInfoEntry entry = next.info().get("map:Map1").get(0);
    assertThat(entry.id()).isEqualTo(new SdInfoId("res-1"));
    assertThat(entry.tags())
        .as("标签保序、类型是 DecisionMakerId")
        .containsExactly(SdFixtures.DM2, SdFixtures.DM1);
  }

  /** ★ 唯一性：**载荷显式给的 id** 若与既有条目撞车 ⇒ 命令期拒绝（合成 id 本就注入，不会撞）。 */
  @Test
  void rejectsADuplicateExplicitId() {
    SdState first =
        applied(
            SdState.empty(),
            "{\"address\":\"map:Map1\",\"key\":\"k1\",\"value\":\"v1\",\"id\":\"res-1\"}");
    HandlerOutcome outcome =
        HANDLER.handle(
            SdWorlds.world(first),
            "{\"address\":\"map:Map1\",\"key\":\"k2\",\"value\":\"v2\",\"id\":\"res-1\"}");

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("res-1");
  }

  private static SdState applied(SdState base, String payload) {
    SimulationState world = SdWorlds.world(base);
    HandlerOutcome outcome = HANDLER.handle(world, payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    SdChangeSet cs = (SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return SdChangeSet.apply(cs, base);
  }
}

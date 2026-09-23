package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.SdInfoId;
import io.mosire.simos.sd.model.AdjudicationStatus;
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

  // ── 裁决的生效 / 作废（2026-09-23，用户裁定「只有生效裁决和作废裁决」）────────────────

  @Test
  void putInfoCarriesAnAdjudicationStatusAndRejectsAnythingElse() {
    // ★ 缺省 = 空（普通 INFO 条目不参与裁决状态；老档走同一条）——见 AdjudicationStatus 的类注。
    SdState plain = applied(SdState.empty(), "{\"address\":\"map:Map1\",\"key\":\"k\",\"value\":\"v\"}");
    assertThat(plain.info().get("map:Map1").get(0).adjudicationStatus()).isEmpty();

    SdState effective =
        applied(
            SdState.empty(),
            "{\"address\":\"sd:adjudication.1\",\"key\":\"result\",\"value\":\"{}\","
                + "\"adjudicationStatus\":\"EFFECTIVE\"}");
    assertThat(effective.info().get("sd:adjudication.1").get(0).adjudicationStatus())
        .contains(AdjudicationStatus.EFFECTIVE);

    // ★ 状态**不收自由字符串**（拼写漂移会让读面与不变量都不可断言）⇒ 非法值当场拒。
    HandlerOutcome bad =
        HANDLER.handle(
            SdWorlds.world(SdState.empty()),
            "{\"address\":\"sd:adjudication.1\",\"key\":\"result\",\"value\":\"{}\","
                + "\"adjudicationStatus\":\"MAYBE\"}");
    assertThat(bad).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) bad).reason()).contains("EFFECTIVE|VOIDED");
  }

  @Test
  void theSecondEntryAtTheSameAddressGetsTheNextOrdinalId() {
    // ★ 这条**是"作废之后能重裁"的使能条件**：id 由地址 + 该地址下的条目数合成（不是写死 #0），
    //   否则作废把 #0 翻成 VOIDED 留在原地之后，重裁再写 #0 会撞 id 被拒。
    SdState first =
        applied(SdState.empty(), "{\"address\":\"sd:adjudication.1\",\"key\":\"result\",\"value\":\"{}\"}");
    SdState second =
        applied(first, "{\"address\":\"sd:adjudication.1\",\"key\":\"result\",\"value\":\"{}\"}");

    assertThat(second.info().get("sd:adjudication.1").stream().map(e -> e.id().value()))
        .containsExactly("sd:adjudication.1#0", "sd:adjudication.1#1");
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

  /**
   * ★ 第 3 波第 2 步：{@code tick} 是**可选载荷**。
   *
   * <p>缺省 = **世界当前 tick**（**向后兼容**，旧调用一字不变）；**过去合法**（"补记/滞后"的令必须能把决策结果归到 自己的 tick
   * 上，否则那些令永远没有结果）；**未来 ⇒ 拒**（与 {@code IssueDirectiveHandler}「令不得记在未来」同口径）。
   */
  @Test
  void optionalTickDefaultsToTheWorldTickAndRejectsTheFuture() {
    long worldTick = 5L;
    SdState base = SdState.empty();

    assertThat(
            entryAt(base, worldTick, "{\"address\":\"map:Map1\",\"key\":\"k1\",\"value\":\"v\"}")
                .tick())
        .as("缺省 ⇒ 世界当前 tick")
        .isEqualTo(worldTick);
    assertThat(
            entryAt(
                    base,
                    worldTick,
                    "{\"address\":\"map:Map1\",\"key\":\"k2\",\"value\":\"v\",\"tick\":3}")
                .tick())
        .as("★ 过去合法（补记）")
        .isEqualTo(3L);

    HandlerOutcome future =
        HANDLER.handle(
            SdWorlds.world(base, worldTick),
            "{\"address\":\"map:Map1\",\"key\":\"k3\",\"value\":\"v\",\"tick\":6}");
    assertThat(future).as("记在未来 ⇒ 拒").isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) future).reason()).contains("未来");
  }

  /** 在指定世界 tick 下写一条并在该地址下取回第一条。 */
  private static SdInfoEntry entryAt(SdState base, long worldTick, String payload) {
    HandlerOutcome outcome = HANDLER.handle(SdWorlds.world(base, worldTick), payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    SdChangeSet cs = (SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return SdChangeSet.apply(cs, base).info().get("map:Map1").get(0);
  }

  private static SdState applied(SdState base, String payload) {
    SimulationState world = SdWorlds.world(base);
    HandlerOutcome outcome = HANDLER.handle(world, payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    SdChangeSet cs = (SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    return SdChangeSet.apply(cs, base);
  }
}

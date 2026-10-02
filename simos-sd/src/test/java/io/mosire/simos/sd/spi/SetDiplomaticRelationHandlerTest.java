package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.model.DiplomaticRelation;
import io.mosire.simos.sd.model.DiplomaticRelationKey;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code sd.SetDiplomaticRelation} 命令边界（T2a / D-003 + D-005 + R6）：有向边 upsert、两端必须存在、from≠to、tick 缺省
 * 当前 / 未来拒 / 过去合法、kind 缺省清空、text 非空白。
 *
 * <p>★ 夹具用 {@code SdFixtures.full()} 或"只有两个 Nation 的空状态"：两条命令的变更集都必须**只触目标组件**。
 */
class SetDiplomaticRelationHandlerTest {

  private static final SetDiplomaticRelationHandler HANDLER = new SetDiplomaticRelationHandler();

  private static final DiplomaticRelationKey EDGE = SdFixtures.DR12;

  @Test
  void typeIsSdSetDiplomaticRelationAndItIsNotGmOnly() {
    assertThat(HANDLER.type()).isEqualTo("sd.SetDiplomaticRelation");
    assertThat(HANDLER)
        .as("★ 身份约束在决策人窄工具层（signature），命令本身非 GmOnly ⇒ DM 窄写过滤时不会被误摘")
        .isNotInstanceOf(GmOnlyCommand.class);
  }

  @Test
  void upsertsAnEdgeWithKindTextAndAnExplicitPastTick() {
    SdState next =
        applied(
            nationsOnly(),
            5L,
            "{\"from\":\"n1\",\"to\":\"n2\",\"kind\":\"称臣纳贡\",\"text\":\"N1 向 N2 称臣纳贡\",\"tick\":3}");

    DiplomaticRelation relation = next.diplomaticRelations().get(EDGE);
    assertThat(relation).isNotNull();
    assertThat(relation.kind()).contains("称臣纳贡");
    assertThat(relation.text()).isEqualTo("N1 向 N2 称臣纳贡");
    assertThat(relation.updatedTick()).as("过去合法（补记）").isEqualTo(3L);
    assertThat(next.diplomaticRelations()).as("新增一条边").hasSize(1);
  }

  @Test
  void tickDefaultsToTheWorldTickAndTheFutureIsRejected() {
    SdState next =
        applied(nationsOnly(), 5L, "{\"from\":\"n1\",\"to\":\"n2\",\"text\":\"缺省 tick\"}");
    assertThat(next.diplomaticRelations().get(EDGE).updatedTick())
        .as("缺省 = 世界当前 tick")
        .isEqualTo(5L);

    HandlerOutcome future =
        handle(nationsOnly(), 5L, "{\"from\":\"n1\",\"to\":\"n2\",\"text\":\"未来\",\"tick\":6}");
    assertThat(future).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) future).reason())
        .contains("未来")
        .contains("6")
        .contains("5");
  }

  @Test
  void rejectsEndpointsThatDoNotExistWithNames() {
    HandlerOutcome missingTo =
        handle(nationsOnly(), 5L, "{\"from\":\"n1\",\"to\":\"n-missing\",\"text\":\"边\"}");
    HandlerOutcome missingFrom =
        handle(nationsOnly(), 5L, "{\"from\":\"n-missing\",\"to\":\"n2\",\"text\":\"边\"}");

    assertThat(missingTo).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) missingTo).reason())
        .contains("to")
        .contains("n-missing")
        .contains("不存在");
    assertThat(missingFrom).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) missingFrom).reason())
        .contains("from")
        .contains("n-missing")
        .contains("不存在");
  }

  @Test
  void rejectsASelfEdge() {
    HandlerOutcome self =
        handle(nationsOnly(), 5L, "{\"from\":\"n1\",\"to\":\"n1\",\"text\":\"自环\"}");

    assertThat(self).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) self).reason())
        .as("D-003 的边是 Nation 对 Nation：自己对自己没有语义")
        .contains("不得相同")
        .contains("n1");
  }

  @Test
  void rejectsBlankText() {
    HandlerOutcome blank =
        handle(nationsOnly(), 5L, "{\"from\":\"n1\",\"to\":\"n2\",\"text\":\"   \"}");

    assertThat(blank).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) blank).reason()).contains("text");
  }

  /** ★ upsert 不是 append：同 (from,to) 再调是**更新那一条**，不新增第二条。 */
  @Test
  void aSecondCallOnTheSameEdgeUpdatesInPlaceInsteadOfAppending() {
    SdState first =
        applied(
            nationsOnly(),
            5L,
            "{\"from\":\"n1\",\"to\":\"n2\",\"kind\":\"称臣纳贡\",\"text\":\"第一版\"}");
    SdState second = applied(first, 5L, "{\"from\":\"n1\",\"to\":\"n2\",\"text\":\"谈判状态已更新：停战\"}");

    assertThat(second.diplomaticRelations()).as("同一条边，不是第二条").hasSize(1);
    DiplomaticRelation relation = second.diplomaticRelations().get(EDGE);
    assertThat(relation.text()).as("后写覆盖前写（D-005：谈判状态就记在这条边上）").isEqualTo("谈判状态已更新：停战");
    assertThat(relation.kind()).as("第二次没给 kind ⇒ 清空").isEmpty();
  }

  @Test
  void directionIsPartOfTheEdgeIdentity() {
    SdState forward =
        applied(nationsOnly(), 5L, "{\"from\":\"n1\",\"to\":\"n2\",\"text\":\"N1→N2\"}");
    SdState both = applied(forward, 5L, "{\"from\":\"n2\",\"to\":\"n1\",\"text\":\"N2→N1\"}");

    assertThat(both.diplomaticRelations())
        .as("有向边：A→B 与 B→A 是两条不同的边")
        .hasSize(2)
        .containsOnlyKeys(
            new DiplomaticRelationKey(SdFixtures.N1, SdFixtures.N2),
            new DiplomaticRelationKey(SdFixtures.N2, SdFixtures.N1));
    assertThat(both.diplomaticRelations().get(EDGE).text()).as("反向写入不动正向边").isEqualTo("N1→N2");
  }

  /** ★ kind 的缺省/空白清空口径：显式设过之后，再调用时省略或给空白都回到"无 kind"。 */
  @Test
  void omittedOrBlankKindClearsTheKind() {
    SdState withKind =
        applied(
            nationsOnly(), 5L, "{\"from\":\"n1\",\"to\":\"n2\",\"kind\":\"称臣纳贡\",\"text\":\"v1\"}");
    assertThat(withKind.diplomaticRelations().get(EDGE).kind()).contains("称臣纳贡");

    SdState omitted = applied(withKind, 5L, "{\"from\":\"n1\",\"to\":\"n2\",\"text\":\"v2\"}");
    assertThat(omitted.diplomaticRelations().get(EDGE).kind()).as("省略 kind ⇒ 清空").isEmpty();

    SdState blank =
        applied(withKind, 5L, "{\"from\":\"n1\",\"to\":\"n2\",\"kind\":\"   \",\"text\":\"v3\"}");
    assertThat(blank.diplomaticRelations().get(EDGE).kind()).as("空白 kind 与没写无区别").isEmpty();
    assertThat(blank.diplomaticRelations().get(EDGE).text())
        .as("text 是这条边的本体，仍被更新")
        .isEqualTo("v3");
  }

  @Test
  void changeSetTouchesOnlyDiplomaticRelations() {
    SdState base = SdFixtures.full();
    HandlerOutcome outcome =
        handle(base, 10L, "{\"from\":\"n1\",\"to\":\"n2\",\"text\":\"只动关系表\"}");

    SdChangeSet changeSet = (SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet();
    assertThat(changeSet.diplomaticRelations().changed()).as("目标组件必须进变更集").isTrue();
    assertThat(
            List.of(
                changeSet.nations().changed(),
                changeSet.armies().changed(),
                changeSet.combats().changed(),
                changeSet.combatStates().changed(),
                changeSet.decisionMakers().changed(),
                changeSet.directives().changed(),
                changeSet.effects().changed(),
                changeSet.verdicts().changed(),
                changeSet.lossRecords().changed(),
                changeSet.info().changed(),
                changeSet.diplomaticEvents().changed()))
        .as("其余 11 个组件一个都不许动")
        .containsOnly(false);
    assertThat(SdChangeSet.apply(changeSet, base).diplomaticEvents())
        .as("外交事件表逐字不变")
        .isEqualTo(base.diplomaticEvents());
  }

  @Test
  void malformedPayloadsAreRejectedInsteadOfThrown() {
    assertThat(handle(nationsOnly(), 5L, "not json")).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(handle(nationsOnly(), 5L, "{\"to\":\"n2\",\"text\":\"边\"}"))
        .as("缺 from ⇒ 拒绝")
        .isInstanceOf(HandlerOutcome.Rejected.class);
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static SdState nationsOnly() {
    return SdFixtures.empty()
        .withNations(
            Map.of(
                SdFixtures.N1, SdFixtures.nation(SdFixtures.N1, SdFixtures.R1),
                SdFixtures.N2, SdFixtures.nation(SdFixtures.N2, SdFixtures.R2)));
  }

  private static HandlerOutcome handle(SdState base, long worldTick, String payload) {
    return HANDLER.handle(SdWorlds.world(base, worldTick), payload);
  }

  private static SdState applied(SdState base, long worldTick, String payload) {
    HandlerOutcome outcome = handle(base, worldTick, payload);
    assertThat(outcome).as("期望 Applied，实际: %s", outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return SdChangeSet.apply((SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
  }
}

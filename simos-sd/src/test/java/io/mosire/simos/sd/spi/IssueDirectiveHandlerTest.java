package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.model.DirectiveStatus;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.spi.HandlerOutcome;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** D1 的 handler 级判据：R4 唯一性（命令期）、命令白名单（禁自指/通用写）、执行原文落 INFO。 */
class IssueDirectiveHandlerTest {

  private static final Set<String> REGISTERED =
      Set.of(
          "unit.RenameUnit",
          "unit.PlanRoute",
          "sd.PutInfo",
          "sd.IssueDirective",
          SdCommandNames.SIMOS_COMMAND_SUBMIT);

  private static final DirectiveWhitelist WHITELIST = new DirectiveWhitelist(REGISTERED);

  /** 本类里"另一 tick 出令"那几条用的世界 tick：**必须 ≥ 载荷里最大的 tick**（4），否则会先撞上"令不得记在未来"。 */
  private static final long WORLD_TICK = 4;

  private final IssueDirectiveHandler handler = new IssueDirectiveHandler(WHITELIST);

  @Test
  void whitelistDropsSdSelfReferenceAndGenericSubmit() {
    assertThat(WHITELIST.allows("unit.RenameUnit")).isTrue();
    assertThat(WHITELIST.allows("sd.PutInfo")).as("sd 自指不得在决策里").isFalse();
    assertThat(WHITELIST.allows(SdCommandNames.SIMOS_COMMAND_SUBMIT)).as("通用写不得在决策里").isFalse();
    assertThat(WHITELIST.allowedTypes())
        .containsExactlyInAnyOrder("unit.RenameUnit", "unit.PlanRoute");
  }

  @Test
  void issuesDirectiveAndWritesIntentInfo() {
    SdState base = withDecisionMaker(SdState.empty());
    HandlerOutcome outcome =
        handle(base, payload("d1", "dm1", 0, "[{\"type\":\"unit.RenameUnit\"}]"));

    SdState after = applied(base, outcome);
    Directive directive = after.directives().get(new DirectiveId("d1"));
    assertThat(directive.decisionMakerId()).isEqualTo(SdFixtures.DM1);
    assertThat(directive.tick()).isEqualTo(0);
    assertThat(directive.status()).isEqualTo(DirectiveStatus.ISSUED);
    assertThat(directive.commands()).hasSize(1);
    assertThat(directive.commands().get(0).type()).isEqualTo("unit.RenameUnit");
    assertThat(directive.intentInfoKey()).isEqualTo(IssueDirectiveHandler.INTENT_INFO_KEY);
    assertThat(after.info().get("sd:directive.d1")).hasSize(1);
    assertThat(after.info().get("sd:directive.d1").get(0).value()).isEqualTo("向北推进");
    assertThat(after.info().get("sd:directive.d1").get(0).sourceDirective())
        .contains(new DirectiveId("d1"));
  }

  @Test
  void rejectsSecondDirectiveForSameMakerAndTick() {
    SdState base = withDecisionMaker(SdState.empty());
    SdState afterFirst = applied(base, handleAt(base, WORLD_TICK, payload("d1", "dm1", 3, "[]")));

    HandlerOutcome second = handleAt(afterFirst, WORLD_TICK, payload("d2", "dm1", 3, "[]"));

    assertThat(rejected(second)).contains("R4 违反").contains("dm1").contains("3").contains("d1");
    assertThat(afterFirst.directives()).as("第一条未被第二条覆盖").hasSize(1);
  }

  @Test
  void allowsSecondDirectiveForSameMakerOnAnotherTick() {
    SdState base = withDecisionMaker(SdState.empty());
    SdState afterFirst = applied(base, handleAt(base, WORLD_TICK, payload("d1", "dm1", 3, "[]")));

    SdState afterSecond =
        applied(afterFirst, handleAt(afterFirst, WORLD_TICK, payload("d2", "dm1", 4, "[]")));

    assertThat(afterSecond.directives()).hasSize(2);
  }

  /**
   * ★★ **令不得记在未来**（2026-09-23，用户实测撞到的真缺陷）：载荷 {@code tick} 由模型自己填，填到世界**还没走到**的 时刻会让记录里的"上次出令在 tick
   * N"晚于世界 ⇒ {@code ticksSinceLast} 算出负数 ⇒ {@code due} 永久算错，而那条记录 **改不回来**（时间线只追加）。
   *
   * <p>★ **判别力在三处**：① 世界 tick 4、载荷 5 ⇒ **拒**且理由点名两个数；② 同一载荷在**世界还没走到**（tick 0）时也拒
   * ——但那是同一条守卫的另一种触发，不另算一条；③ **过去的 tick 照样过**（下一条用例：世界 4、载荷 3 ⇒ 合法）——否则 这条守卫就成了"必须恰好等于世界
   * tick"，那会把补记/滞后一拍的正当调用方一并堵死。
   */
  @Test
  void rejectsDirectiveRecordedInTheFuture() {
    SdState base = withDecisionMaker(SdState.empty());

    HandlerOutcome outcome = handleAt(base, 4, payload("d1", "dm1", 5, "[]"));

    assertThat(rejected(outcome)).contains("不得记在未来").contains("5").contains("4");
    assertThat(base.directives()).as("被拒 ⇒ 一条都没落").isEmpty();
  }

  /** 反面：**过去**的 tick 是合法的（补记 / 滞后一拍），本守卫只堵"未来"。 */
  @Test
  void acceptsDirectiveRecordedInThePast() {
    SdState base = withDecisionMaker(SdState.empty());

    SdState after = applied(base, handleAt(base, 4, payload("d1", "dm1", 3, "[]")));

    assertThat(after.directives().get(new DirectiveId("d1")).tick()).isEqualTo(3);
  }

  @Test
  void rejectsUnknownDecisionMaker() {
    HandlerOutcome outcome = handle(SdState.empty(), payload("d1", "ghost", 0, "[]"));
    assertThat(rejected(outcome)).contains("决策人不存在").contains("ghost");
  }

  @Test
  void rejectsCommandOutsideWhitelist() {
    SdState base = withDecisionMaker(SdState.empty());
    HandlerOutcome outcome =
        handle(base, payload("d1", "dm1", 0, "[{\"type\":\"unit.SetStrength\"}]"));
    assertThat(rejected(outcome)).contains("不在白名单").contains("unit.SetStrength");
  }

  @Test
  void rejectsSdSelfReferenceCommand() {
    SdState base = withDecisionMaker(SdState.empty());
    HandlerOutcome outcome = handle(base, payload("d1", "dm1", 0, "[{\"type\":\"sd.PutInfo\"}]"));
    assertThat(rejected(outcome)).contains("自指").contains("sd.PutInfo");
  }

  @Test
  void rejectsDanglingEffectReference() {
    SdState base = withDecisionMaker(SdState.empty());
    String json =
        "{\"directiveId\":\"d1\",\"decisionMakerId\":\"dm1\",\"tick\":0,"
            + "\"intentInfo\":\"向北推进\",\"commands\":[],\"effects\":[\"ghost\"]}";
    HandlerOutcome outcome = handle(base, json);
    assertThat(rejected(outcome)).contains("效果不存在").contains("ghost");
  }

  @Test
  void acceptsRegisteredEffectReference() {
    SdState base =
        withDecisionMaker(SdState.empty())
            .withEffects(Map.of(SdFixtures.E1, SdFixtures.effect(SdFixtures.E1)));
    String json =
        "{\"directiveId\":\"d1\",\"decisionMakerId\":\"dm1\",\"tick\":0,"
            + "\"intentInfo\":\"向北推进\",\"commands\":[],\"effects\":[\""
            + SdFixtures.E1.value()
            + "\"]}";
    SdState after = applied(base, handle(base, json));
    assertThat(after.directives().get(new DirectiveId("d1")).effects())
        .containsExactly(new EffectId("e1"));
  }

  @Test
  void rejectsMalformedTargetAddress() {
    SdState base = withDecisionMaker(SdState.empty());
    String json =
        "{\"directiveId\":\"d1\",\"decisionMakerId\":\"dm1\",\"tick\":0,\"target\":\"no-colon\","
            + "\"intentInfo\":\"向北推进\",\"commands\":[]}";
    HandlerOutcome outcome = handle(base, json);
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
  }

  private static SdState withDecisionMaker(SdState base) {
    return base.withDecisionMakers(
        Map.of(SdFixtures.DM1, SdFixtures.decisionMaker(SdFixtures.DM1)));
  }

  private HandlerOutcome handle(SdState base, String payloadJson) {
    return handler.handle(SdWorlds.world(base), payloadJson);
  }

  /**
   * **世界 tick 显式给定**的形态（2026-09-23）：载荷里的 {@code tick} 一旦大于世界 tick 就会被拒 ⇒ 凡是要测 "某个 tick
   * 上的合法出令"，夹具的世界就必须先走到那儿（否则测到的是"In the future"那条，而断言可能照样过）。
   */
  private HandlerOutcome handleAt(SdState base, long worldTick, String payloadJson) {
    return handler.handle(SdWorlds.world(base, worldTick), payloadJson);
  }

  private static SdState applied(SdState base, HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return SdChangeSet.apply((SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
  }

  private static String rejected(HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }

  private static String payload(String id, String dm, long tick, String commandsJson) {
    return "{"
        + "\"directiveId\":\""
        + id
        + "\",\"decisionMakerId\":\""
        + dm
        + "\",\"tick\":"
        + tick
        + ",\"intentInfo\":\"向北推进\",\"commands\":"
        + commandsJson
        + "}";
  }
}

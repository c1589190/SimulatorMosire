package io.mosire.simos.sd.spi;

import static org.assertj.core.api.Assertions.assertThat;

import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.model.AccessLimit;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DisclosurePolicy;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.sd.testing.SdFixtures;
import io.mosire.simos.sd.testing.SdWorlds;
import io.mosire.simos.util.spi.HandlerOutcome;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * T9 判据（spec §4.2，取代 {@code sd.SetViewScope}）：GM 配权写进 {@code DecisionMaker.accessLimit}（可回放的数据）； dm
 * 不存在 / 载荷非法 ⇒ 拒绝。
 *
 * <p>★ **语义变了，用例跟着变、不变恒真**：旧用例断言的是"可见集合被写进去了"（{@code visibleRegions} 等）。新语义下命令写的是 **额外限制**（前缀图 +
 * 字段级剔除 + 披露档），故断言逐条换成它；"配权不动其他字段"那几条**照旧有效**（providerId / allowedTools / cadence 不被动）。
 */
class SetDecisionMakerAccessHandlerTest {

  private final SetDecisionMakerAccessHandler handler = new SetDecisionMakerAccessHandler();

  @Test
  void writesAccessLimitOntoTheDecisionMaker() {
    SdState base = withDecisionMaker(SdState.empty());
    HandlerOutcome outcome = handle(base, payload("dm1"));

    SdState after = applied(base, outcome);
    DecisionMaker maker = after.decisionMakers().get(SdFixtures.DM1);
    assertThat(maker.accessLimit().prefixesByNamespace())
        .as("前缀图逐值写入")
        .containsOnlyKeys("map", "unit");
    assertThat(maker.accessLimit().prefixesByNamespace().get("map"))
        .containsExactly("Map1/region/r1");
    assertThat(maker.accessLimit().prefixesByNamespace().get("unit")).containsExactly("u-1");
    assertThat(maker.accessLimit().adjudicationDisclosure())
        .isEqualTo(DisclosurePolicy.PERCEPTION_ONLY);
    assertThat(maker.accessLimit().redactedFields()).containsExactly("position");
    assertThat(maker.allowedTools())
        .as("本次载荷没给 allowedTools ⇒ 逐字保留（缺省 = 不改动）")
        .isEqualTo(SdFixtures.decisionMaker(SdFixtures.DM1).allowedTools());
    assertThat(maker.decisionCadenceTicks()).isEqualTo(1);
  }

  /** 本命令能改白名单（旧 {@code SetViewScope} 不能）——"配权"的完整含义。 */
  @Test
  void replacesAllowedToolsWhenGiven() {
    SdState base = withDecisionMaker(SdState.empty());
    SdState after =
        applied(
            base,
            handle(base, "{\"decisionMakerId\":\"dm1\",\"allowedTools\":[\"sd.IssueDirective\"]}"));
    assertThat(after.decisionMakers().get(SdFixtures.DM1).allowedTools())
        .containsExactly("sd.IssueDirective");
  }

  @Test
  void rejectsGenericWriteInAllowedTools() {
    SdState base = withDecisionMaker(SdState.empty());
    HandlerOutcome outcome =
        handle(base, "{\"decisionMakerId\":\"dm1\",\"allowedTools\":[\"simos.command.submit\"]}");
    assertThat(rejected(outcome)).contains("N9").contains("simos.command.submit");
  }

  /**
   * ★★ **"缺省 = 不改动"与"显式给 = 整份替换"必须分得开**（本命令的载荷契约）。
   *
   * <p>方向一：只改白名单 ⇒ {@code accessLimit} **逐字保留**（读成"清空"就会把 GM 刚配好的限制静默抹掉）。 方向二：显式 {@code
   * "accessLimit":{}} ⇒ **清空**（= 退回"无额外限制"；不给这个写法，GM 就永远退不回去）。
   *
   * <p>两条**都不会报错**，只有同时断言才分得开——只测方向一的话，把空对象也读成"不改动"的实现在本用例下**照样绿**。
   */
  @Test
  void absentMeansKeepAndExplicitEmptyMeansClear() {
    SdState base = withDecisionMaker(SdState.empty());
    SdState configured = applied(base, handle(base, payload("dm1")));
    assertThat(configured.decisionMakers().get(SdFixtures.DM1).accessLimit().prefixesByNamespace())
        .isNotEmpty();

    SdState onlyTools =
        applied(
            configured,
            handle(
                configured,
                "{\"decisionMakerId\":\"dm1\",\"allowedTools\":[\"sd.SubmitVerdict\"]}"));
    assertThat(onlyTools.decisionMakers().get(SdFixtures.DM1).accessLimit().prefixesByNamespace())
        .as("缺省 = 不改动（不是清空）")
        .isEqualTo(
            configured.decisionMakers().get(SdFixtures.DM1).accessLimit().prefixesByNamespace());

    SdState cleared =
        applied(onlyTools, handle(onlyTools, "{\"decisionMakerId\":\"dm1\",\"accessLimit\":{}}"));
    AccessLimit limit = cleared.decisionMakers().get(SdFixtures.DM1).accessLimit();
    assertThat(limit.prefixesByNamespace()).as("显式空对象 = 清空").isEmpty();
    assertThat(limit.adjudicationDisclosure())
        .as("披露档也没被这条清空指令动到（它是另一个键）")
        .isEqualTo(DisclosurePolicy.PERCEPTION_ONLY);
  }

  @Test
  void rejectsUnknownDecisionMaker() {
    HandlerOutcome outcome = handle(SdState.empty(), payload("ghost"));
    assertThat(rejected(outcome)).contains("决策人不存在").contains("ghost");
  }

  @Test
  void rejectsMalformedAccessLimit() {
    SdState base = withDecisionMaker(SdState.empty());
    String json = "{\"decisionMakerId\":\"dm1\",\"accessLimit\":\"FULL\"}";
    HandlerOutcome outcome = handler.handle(SdWorlds.world(base), json);
    assertThat(rejected(outcome)).contains("accessLimit");
  }

  @Test
  void rejectsNonArrayPrefixes() {
    SdState base = withDecisionMaker(SdState.empty());
    String json = "{\"decisionMakerId\":\"dm1\",\"accessLimit\":{\"map\":\"Map1/region/r1\"}}";
    HandlerOutcome outcome = handler.handle(SdWorlds.world(base), json);
    assertThat(rejected(outcome)).contains("accessLimit");
  }

  @Test
  void rejectsIllegalDisclosurePolicy() {
    SdState base = withDecisionMaker(SdState.empty());
    String json = "{\"decisionMakerId\":\"dm1\",\"adjudicationDisclosure\":\"MAYBE\"}";
    HandlerOutcome outcome = handler.handle(SdWorlds.world(base), json);
    assertThat(rejected(outcome)).contains("adjudicationDisclosure");
  }

  private static SdState withDecisionMaker(SdState base) {
    return base.withDecisionMakers(
        Map.of(SdFixtures.DM1, SdFixtures.decisionMaker(SdFixtures.DM1)));
  }

  private HandlerOutcome handle(SdState base, String payloadJson) {
    return handler.handle(SdWorlds.world(base), payloadJson);
  }

  private static SdState applied(SdState base, HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Applied.class);
    return SdChangeSet.apply((SdChangeSet) ((HandlerOutcome.Applied) outcome).changeSet(), base);
  }

  private static String rejected(HandlerOutcome outcome) {
    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    return ((HandlerOutcome.Rejected) outcome).reason();
  }

  private static String payload(String dm) {
    return "{"
        + "\"decisionMakerId\":\""
        + dm
        + "\",\"accessLimit\":{\"map\":[\"Map1/region/r1\"],\"unit\":[\"u-1\"]},"
        + "\"adjudicationDisclosure\":\"PERCEPTION_ONLY\",\"redactedFields\":[\"position\"]}";
  }

  /**
   * 命令名**逐字**钉死（spec §4.2 的名字）：它同时出现在 app 侧的窄工具 {@code NAME} 与 catalog 提示里，且必须是**字面量** ——app
   * 侧两条派生式同源判据按源码字面量扫描；这里把 sd 这一侧也钉住，改了名不会静默漂移。
   */
  @Test
  void commandTypeIsTheSpecifiedLiteral() {
    assertThat(handler.type()).isEqualTo("sd.SetDecisionMakerAccess");
  }

  /** 空数组是**有效值**：该命名空间"够不着"（与"整个键缺席 = 不表态"是两回事）。 */
  @Test
  void anExplicitlyEmptyPrefixListIsKeptAsAnEmptyListNotDropped() {
    SdState base = withDecisionMaker(SdState.empty());
    SdState after =
        applied(base, handle(base, "{\"decisionMakerId\":\"dm1\",\"accessLimit\":{\"map\":[]}}"));
    assertThat(after.decisionMakers().get(SdFixtures.DM1).accessLimit().prefixesByNamespace())
        .containsOnlyKeys("map");
    assertThat(
            after
                .decisionMakers()
                .get(SdFixtures.DM1)
                .accessLimit()
                .prefixesByNamespace()
                .get("map"))
        .isEqualTo(Set.of());
  }
}

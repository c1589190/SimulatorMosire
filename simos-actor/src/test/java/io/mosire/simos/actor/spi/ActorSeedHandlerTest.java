package io.mosire.simos.actor.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code actor.Seed} 命令边界（照 {@code EconomySeedHandlerTest}）：首播打标 + 已激活后**按格追加** + 重复格拒绝 +
 * 载荷坏形状/坏数值折成 {@code Rejected} + 目标路径逐格。
 *
 * <p>★ 夹具是**真 {@link SimulationState} + 只有 actor 切片**（不打 DB），形态照 social 侧 {@code
 * SetPopulationHandlerTest}。
 */
class ActorSeedHandlerTest {

  private static final ActorSeedHandler HANDLER = new ActorSeedHandler();
  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(1));
  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final ActorRef ESTATE_FARM = new ActorRef(ActorKind.ESTATE, "farm@0_0");
  private static final ActorRef HOUSEHOLD = new ActorRef(ActorKind.HOUSEHOLD, "house@0_0");

  /** 一格的最小合法 entry：两个主体 + 一本账。 */
  private static final String ENTRY_0 =
      "{\"q\":0,\"r\":0,"
          + "\"actors\":[{\"kind\":\"ESTATE\",\"id\":\"farm@0_0\",\"label\":\"农业庄园\"},"
          + "{\"kind\":\"HOUSEHOLD\",\"id\":\"house@0_0\",\"label\":\"农户\"}],"
          + "\"goods\":[{\"owner\":{\"kind\":\"HOUSEHOLD\",\"id\":\"house@0_0\"},"
          + "\"location\":{\"q\":0,\"r\":0},\"balances\":{\"grain\":2241000,\"fiber\":0}}]}";

  /** 同一 entry 搬到 {@code 1_0}（坐标、位置、两个主体 id 一并改）——验证"已激活后按格追加"。 */
  private static final String ENTRY_1 =
      ENTRY_0.replace("\"q\":0,\"r\":0", "\"q\":1,\"r\":0").replace("0_0", "1_0");

  private static final String PAYLOAD = payload(ENTRY_0);

  private static final String LATER_NATION_PAYLOAD = payload(ENTRY_1);

  @Test
  void typeIsActorSeed() {
    assertThat(HANDLER.type()).isEqualTo("actor.Seed");
  }

  /** ★ 首播：整份落盘并**打标**（`activatedDay` = 世界当前 tick），变更集施加回 base 后逐表对得上。 */
  @Test
  void firstSeedMarksActivationAndSeedsEveryTable() {
    ActorData after = apply(PAYLOAD, ActorData.empty(), T7);

    ActorMeta meta = after.meta().orElseThrow();
    assertThat(meta.mapId()).isEqualTo("Map1");
    assertThat(meta.rulesVersion()).isEqualTo("actor-v1");
    assertThat(meta.activatedDay()).as("激活日 = 世界当前 tick").isEqualTo(7L);
    assertThat(after.actors()).containsOnlyKeys(ESTATE_FARM, HOUSEHOLD);
    assertThat(after.accounts()).as("库存表").hasSize(1);
  }

  /** ★ 已激活后**按格追加**：第二国落在别的格 ⇒ 两批都在，且 meta **不覆盖**（保留首次的激活日）。 */
  @Test
  void appendsALaterNationsHexAndKeepsTheFirstMeta() {
    ActorData first = apply(PAYLOAD, ActorData.empty(), T7);

    ActorData both = apply(LATER_NATION_PAYLOAD, first, SimosTimestamp.of(9));

    assertThat(both.actors()).as("两批各 2 个主体").hasSize(4);
    assertThat(both.accounts()).as("两批各 1 本账").hasSize(2);
    assertThat(both.meta().orElseThrow().activatedDay()).as("meta 不覆盖：保留首次播种的激活日").isEqualTo(7L);
  }

  /** ★ 已激活后**重复格** ⇒ 拒，且拒因**点名该格坐标**（不静默覆盖既有 actor 状态）。 */
  @Test
  void rejectsDuplicateHexAndNamesIt() {
    ActorData first = apply(PAYLOAD, ActorData.empty(), T7);

    HandlerOutcome outcome = HANDLER.handle(state(first, T7), PAYLOAD);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).as("拒因点名重复的那一格坐标").contains("0_0");
  }

  /**
   * ★★ **整份原子拒绝**：载荷里只要**有一格**已被占用，整份都不落盘（含那些还没被占用的格）。
   *
   * <p>判别力：若改成"逐格跳过已占用的"，一条命令就会**部分生效** —— 而 Core 的口径是"一条命令 = 一条 revision"，
   * 部分生效的变更集与"全部拒绝"是两种完全不同的语义。
   */
  @Test
  void rejectsTheWholePayloadWhenAnyHexIsAlreadyOccupied() {
    ActorData first = apply(PAYLOAD, ActorData.empty(), T7);
    String twoEntries = payload(ENTRY_0 + "," + ENTRY_1);

    HandlerOutcome outcome = HANDLER.handle(state(first, T7), twoEntries);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("点名的是那一格**已被占用**的（1_0 是新的、不该被点名）")
        .contains("0_0")
        .doesNotContain("1_0");
  }

  /** ★ 载荷坏形状（不是 JSON / 不是对象）⇒ 折成**拒绝**，不抛到命令边界之外。 */
  @Test
  void rejectsMalformedPayload() {
    assertThat(HANDLER.handle(state(ActorData.empty(), T7), "not json"))
        .isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(HANDLER.handle(state(ActorData.empty(), T7), "[]"))
        .isInstanceOf(HandlerOutcome.Rejected.class);
  }

  /**
   * ★★ **悬空 owner 在命令边界是拒绝（不是抛）**：库存指向一个载荷与现有状态里都没有的主体。
   *
   * <p>判别力：这条判据若不存在，一份拼错 owner 的载荷会被**静默收下**，那本账从此查不到、也永远不报错。
   *
   * <p>★ <b>2026-09-27 裁定 S3</b>：夹具的 {@code "id":"house@0_0"}} 只命中库存行的 owner ⇒ 判据与断言一字未改，只是名字
   * 从"产权"改成"库存"（产权随该裁定整块退役）。
   */
  @Test
  void rejectsAGoodsRowWhoseOwnerIsNotDeclared() {
    String payload = PAYLOAD.replace("\"id\":\"house@0_0\"}", "\"id\":\"house@9_9\"}");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);

    HandlerOutcome outcome = HANDLER.handle(state(ActorData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason())
        .as("拒因点名那个悬空的主体与所在格")
        .contains("house@9_9")
        .contains("0_0");
  }

  /**
   * ★ 数值语义（负余额）由领域类型判，经 handler 的 catch 折成 {@code Rejected}。
   *
   * <p>★ <b>2026-09-27 裁定 S3</b>：夹具原打在产权行的 {@code quantity} 上（随产权退役），现改打**库存行**的余额 —— 判据
   * （领域类型的构造期守卫生成的 IAE 被折成 Rejected）与断言一字未改。
   */
  @Test
  void rejectsANegativeBalanceAsARejection() {
    String payload = PAYLOAD.replace("\"grain\":2241000", "\"grain\":-2241000");
    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);

    HandlerOutcome outcome = HANDLER.handle(state(ActorData.empty(), T7), payload);

    assertThat(outcome).isInstanceOf(HandlerOutcome.Rejected.class);
    assertThat(((HandlerOutcome.Rejected) outcome).reason()).contains("不得为负");
  }

  /** 目标资源：载荷里**每一个**格各一条（GM 代执行时的越权判据），路径与共享层那条助手逐字同形。 */
  @Test
  void targetPathsAreOnePerEntryHex() {
    String twoEntries = payload("{\"q\":1,\"r\":2},{\"q\":-3,\"r\":4}");

    assertThat(HANDLER.targetPaths("Map1", twoEntries))
        .containsExactly(ResourcePaths.actor(1, 2), ResourcePaths.actor(-3, 4));
  }

  /** 目标声明遇到坏载荷 ⇒ 抛（{@code CommandTargets} 的契约：判不出目标就不得放行），不静默返回空表。 */
  @Test
  void targetPathsThrowOnABadPayload() {
    assertThatThrownBy(() -> HANDLER.targetPaths("Map1", "not json"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  // ── 夹具 ────────────────────────────────────────────────────────────────────────────

  private static String payload(String entriesJson) {
    return "{\"mapId\":\"Map1\",\"rulesVersion\":\"actor-v1\",\"entries\":[" + entriesJson + "]}";
  }

  private static ActorData apply(String payload, ActorData base, SimosTimestamp at) {
    HandlerOutcome.Applied applied =
        (HandlerOutcome.Applied) HANDLER.handle(state(base, at), payload);
    return ActorChangeSet.apply((ActorChangeSet) applied.changeSet(), base);
  }

  private static SimulationState state(ActorData data, SimosTimestamp timestamp) {
    return new SimulationState(
        new StateMeta(REF, timestamp),
        Map.of("actor", new ActorSnapshot(REF, timestamp, data)),
        InMemoryInfoSystem.empty());
  }
}

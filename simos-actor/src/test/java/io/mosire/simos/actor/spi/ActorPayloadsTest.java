package io.mosire.simos.actor.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code actor.Seed} 载荷的形状与拒因（本类只测**载荷层**：{@link ActorPayloads} 的解析与 materialize）。
 *
 * <p>★ 分工照 {@code EconomyPayloads}：**形状/类型/词表在本层**判（坏载荷一律以 {@link IllegalArgumentException}
 * 面世，带可读中文原因）；**数值语义**（余额 ≥ 0）交给领域类型（{@link GoodsAccount}）的构造期守卫 ——**不重复实现，一处真相**。
 *
 * <p>★ 库存行的引用判据（P2-A §13.3 起）：账户主体只有家户 ⇒ 载荷是 {@code {"household":"hh-…","balances":{…}}}。
 * 家户由该行自声明，故"悬空 owner"那条旧判据随之退役。
 *
 * <p>★ <b>2026-09-27 裁定 S3</b>：产权（{@code holdings} 行 / {@code AssetHolding}）整块退役 ⇒ 本类里产权那一组用例随之删除。
 * ★ <b>P2-A §13.3</b>：{@code owner}/{@code location} 两段随"账户键无格"整体退役 ⇒ "库存的 location 必须等于所在格"那条判据
 * 不再是账户载荷的一部分（账户位置由 {@code Household.location} 派生），改钉"缺 {@code household} 字段 ⇒ 拒"。
 */
class ActorPayloadsTest {

  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final ActorRef ORGANIZATION_FARM = new ActorRef(ActorKind.ORGANIZATION, "farm@0_0");

  private static final ActorRef HOUSEHOLD_ACTOR = new ActorRef(ActorKind.HOUSEHOLD, "house@0_0");

  private static final HouseholdId HOUSEHOLD = new HouseholdId("hh-house-0_0");

  /** 最小合法载荷：一格，两个主体 + 一本家户账。 */
  private static final String PAYLOAD =
      "{\"mapId\":\"Map1\",\"rulesVersion\":\"actor-v1\",\"entries\":[{\"q\":0,\"r\":0,"
          + "\"actors\":[{\"kind\":\"ORGANIZATION\",\"id\":\"farm@0_0\",\"label\":\"农业组织者\"},"
          + "{\"kind\":\"HOUSEHOLD\",\"id\":\"house@0_0\",\"label\":\"农户\"}],"
          + "\"goods\":[{\"household\":\"hh-house-0_0\","
          + "\"balances\":{\"grain\":2241000,\"fiber\":0}}]}]}";

  /** 会计行的完整字面（**替换类用例的锚点**：它唯一，替换不中就是测了空气）。 */
  private static final String GOODS_ROW =
      "{\"household\":\"hh-house-0_0\",\"balances\":{\"grain\":2241000,\"fiber\":0}}";

  // ── 正例：逐值 materialize ──────────────────────────────────────────────────────────

  /** ★ 正例：三件**逐值**落盘（元信息 / 主体 / 库存），且 `activatedDay` = 世界当前 tick。 */
  @Test
  void materializesEveryFieldValueForValue() {
    ActorData data = ActorPayloads.toData(ActorPayloads.parse(PAYLOAD), Set.of(), Set.of(), T7);

    ActorMeta meta = data.meta().orElseThrow();
    assertThat(meta.mapId()).isEqualTo("Map1");
    assertThat(meta.rulesVersion()).isEqualTo("actor-v1");
    assertThat(meta.activatedDay()).as("激活日 = 世界当前 tick").isEqualTo(7L);

    assertThat(data.actors()).containsOnlyKeys(ORGANIZATION_FARM, HOUSEHOLD_ACTOR);
    Actor organization = data.actors().get(ORGANIZATION_FARM);
    assertThat(organization.ref()).as("键从值派生：键 == Actor.ref()").isEqualTo(ORGANIZATION_FARM);
    assertThat(organization.label()).isEqualTo("农业组织者");

    GoodsAccountKey accountKey = new GoodsAccountKey(HOUSEHOLD);
    assertThat(data.accounts()).containsOnlyKeys(accountKey);
    GoodsAccount account = data.accounts().get(accountKey);
    assertThat(account.key()).as("键从值派生：键 == GoodsAccount.key()").isEqualTo(accountKey);
    assertThat(account.key().household()).as("账户主体 = 载荷里声明的家户").isEqualTo(HOUSEHOLD);
    assertThat(account.balances())
        .as("余额逐值；**0 保留**（存量不是空表）")
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(new CommodityId("grain"), 2_241_000L, new CommodityId("fiber"), 0L));
  }

  /**
   * ★★ **主体声明在别的格、库存落在这一格 ⇒ 合法**（载荷的格序**不是**依赖序；两趟走）。
   *
   * <p>★ <b>P2-A 迁移</b>：库存行不再引用 actor，而是自声明家户 ⇒ 本用例改为钉"多 entry 的 actors 与 goods 各自
   * 都落盘、互不要求同一条 entry"（两趟走仍在：actors 先全收齐再建表）。
   */
  @Test
  void acceptsActorsAndHouseholdAccountsSpreadAcrossEntries() {
    String twoEntries =
        "{\"mapId\":\"Map1\",\"rulesVersion\":\"actor-v1\",\"entries\":["
            + "{\"q\":0,\"r\":0,\"goods\":[{\"household\":\"hh-other-1_0\","
            + "\"balances\":{\"grain\":1}}]},"
            + "{\"q\":1,\"r\":0,\"actors\":[{\"kind\":\"ORGANIZATION\",\"id\":\"farm@1_0\","
            + "\"label\":\"庄园\"}]}"
            + "]}";

    ActorData data = ActorPayloads.toData(ActorPayloads.parse(twoEntries), Set.of(), Set.of(), T7);

    assertThat(data.accounts()).as("库存照旧落盘").hasSize(1);
    assertThat(data.accounts().keySet().iterator().next().household())
        .isEqualTo(new HouseholdId("hh-other-1_0"));
    assertThat(data.actors()).as("主体在后一条 entry 里声明，但同属一份载荷").hasSize(1);
  }

  /**
   * ★ 逐格路径：{@code <q>_<r>}，与 {@link ResourcePaths#actor(int, int)} **逐字同形**（含负坐标）。
   *
   * <p>断言写成"与那条助手相等"而不是写死字面量：围栏两侧（命令的目标声明 / app 的资源断言）必须拼出同一串， 而"同一串"的判据只能是**同一个来源**。
   */
  @Test
  void entryHexKeysAreOnePerEntryHex() {
    String twoEntries =
        "{\"mapId\":\"Map1\",\"rulesVersion\":\"v\",\"entries\":["
            + "{\"q\":1,\"r\":2},{\"q\":-3,\"r\":4}]}";

    assertThat(ActorPayloads.entryHexKeys(ActorPayloads.parse(twoEntries)))
        .containsExactly(ResourcePaths.actor(1, 2), ResourcePaths.actor(-3, 4));
  }

  // ── 拒因：形状 / 类型 ────────────────────────────────────────────────────────────────

  @Test
  void rejectsAPayloadThatIsNotJsonOrNotAnObject() {
    assertThatThrownBy(() -> ActorPayloads.parse("not json"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不是合法 JSON");
    assertThatThrownBy(() -> ActorPayloads.parse("[]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须是 JSON 对象");
  }

  @Test
  void rejectsEmptyOrMissingEntries() {
    assertThatThrownBy(
            () ->
                ActorPayloads.toData(
                    ActorPayloads.parse(
                        "{\"mapId\":\"Map1\",\"rulesVersion\":\"v\",\"entries\":[]}"),
                    Set.of(),
                    Set.of(),
                    T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("entries 不得为空");
    assertThatThrownBy(
            () ->
                ActorPayloads.toData(
                    ActorPayloads.parse("{\"mapId\":\"Map1\",\"rulesVersion\":\"v\"}"),
                    Set.of(),
                    Set.of(),
                    T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("entries 必须是数组");
  }

  /** 缺 {@code mapId} / {@code rulesVersion} ⇒ 拒（{@code ActorMeta} 的字段不得空白）。 */
  @Test
  void rejectsABlankMapIdOrRulesVersion() {
    assertThatThrownBy(
            () ->
                ActorPayloads.toData(
                    ActorPayloads.parse(
                        "{\"mapId\":\" \",\"rulesVersion\":\"v\",\"entries\":[{\"q\":0,\"r\":0}]}"),
                    Set.of(),
                    Set.of(),
                    T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mapId");
    assertThatThrownBy(
            () ->
                ActorPayloads.toData(
                    ActorPayloads.parse("{\"mapId\":\"Map1\",\"entries\":[{\"q\":0,\"r\":0}]}"),
                    Set.of(),
                    Set.of(),
                    T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rulesVersion");
  }

  /**
   * 主体行的 {@code id} 不得空白 ⇒ **载荷层**先拒（形状判在领域类型之前，同 {@code EconomyPayloads} 的 {@code requireText}
   * 口径）；{@link ActorRef} 自己的空白守卫是**直接构造**那条路的兜底，由它自己的单测把守。
   */
  @Test
  void rejectsAnActorWithABlankId() {
    String payload =
        PAYLOAD.replace(
            "\"kind\":\"HOUSEHOLD\",\"id\":\"house@0_0\",\"label\":\"农户\"",
            "\"kind\":\"HOUSEHOLD\",\"id\":\" \",\"label\":\"农户\"");

    assertThat(payload).as("替换必须真的发生（否则测的是别处那个 id）").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(
            () -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("字段 id");
  }

  /**
   * 主体行的 {@code label} 不得空白 ⇒ 同上：载荷层先拒；{@link Actor} 的构造期守卫是直接构造那条路的兜底 （{@code ActorInvariantsTest}
   * 把守）。
   */
  @Test
  void rejectsAnActorWithABlankLabel() {
    String payload = PAYLOAD.replace("\"label\":\"农户\"", "\"label\":\" \"");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(
            () -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("字段 label");
  }

  // ── 拒因：词表外的枚举值（fail-closed）──────────────────────────────────────────────

  /** ★★ 词表外的**主体种类** ⇒ 当场抛，消息里列出合法值。 */
  @Test
  void rejectsAnActorKindOutsideTheVocabulary() {
    String payload = PAYLOAD.replace("\"kind\":\"ORGANIZATION\"", "\"kind\":\"MANOR\"");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(
            () -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MANOR")
        .hasMessageContaining("ORGANIZATION");
  }

  // ── 拒因：数值语义（交给领域类型的构造期守卫）──────────────────────────────────────

  /** ★ 负库存余额 ⇒ 拒（{@link GoodsAccount} 的构造期守卫）。 */
  @Test
  void rejectsANegativeGoodsBalance() {
    String payload = PAYLOAD.replace("\"grain\":2241000", "\"grain\":-2241000");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(
            () -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为负");
  }

  // ── 拒因：同一份载荷内的重复 ────────────────────────────────────────────────────────

  /** 同一份载荷里同一主体声明两次 ⇒ 拒（照 economy 的"同一份载荷里产业 id 重复"口径）。 */
  @Test
  void rejectsADuplicateActorInOnePayload() {
    String payload =
        PAYLOAD.replace(
            "{\"kind\":\"HOUSEHOLD\",\"id\":\"house@0_0\",\"label\":\"农户\"}]",
            "{\"kind\":\"HOUSEHOLD\",\"id\":\"house@0_0\",\"label\":\"农户\"},"
                + "{\"kind\":\"HOUSEHOLD\",\"id\":\"house@0_0\",\"label\":\"农户二\"}]");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(
            () -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("主体重复");
  }

  /** 同一份载荷里同一本账声明两次 ⇒ 拒。 */
  @Test
  void rejectsADuplicateAccountInOnePayload() {
    String payload = PAYLOAD.replace(GOODS_ROW, GOODS_ROW + "," + GOODS_ROW);

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(
            () -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("库存重复");
  }

  // ── 拒因：★ 账户行的家户引用 ────────────────────────────────────────────────────────

  /**
   * ★★ <b>库存行没有 {@code household} ⇒ 拒</b>（P2-A 后账户主体只有家户；"谁的账"必须写得出来）。
   *
   * <p>判别力：若缺字段被当成"默认某个家户"，这本账就会落到一个**调用方没说过的**主体名下 —— 静默记账到错误的人头上。
   */
  @Test
  void rejectsAGoodsRowWithoutAHouseholdField() {
    String payload =
        PAYLOAD.replace(
            "{\"household\":\"hh-house-0_0\",", "{"); // 去掉 household 键，balances 留着

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(
            () -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("字段 household");
  }

  /** ★ 对照：{@code household} 是空白串 ⇒ 同样拒（家户 id 不得空白）。 */
  @Test
  void rejectsAGoodsRowWithABlankHouseholdId() {
    String payload = PAYLOAD.replace("hh-house-0_0", "   ");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(
            () -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("household");
  }
}

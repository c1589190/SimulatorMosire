package io.mosire.simos.actor.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetClassKey;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.actor.model.AssetHolding;
import io.mosire.simos.actor.model.AssetHoldingKey;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@code actor.Seed} 载荷的形状与拒因（本类只测**载荷层**：{@link ActorPayloads} 的解析与 materialize）。
 *
 * <p>★ 分工照 {@code EconomyPayloads}：**形状/类型/词表在本层**判（坏载荷一律以 {@link IllegalArgumentException}
 * 面世，带可读中文原因）；**数值语义**（数量/余额 ≥ 0）交给领域类型（{@link AssetHolding} / {@link GoodsAccount}）的构造期守卫
 * ——**不重复实现，一处真相**。
 *
 * <p>★ 另有两条**本切片特有**的判据（{@code EconomyData} 的 {@code requireSlotExists} 那一族）：
 *
 * <ul>
 *   <li>产权/库存的 {@code owner} 必须是**已声明的主体**（载荷里的 {@code actors} ∪ 现有状态里的主体）——悬空引用当场拒；
 *   <li>产权/库存的 {@code location} 必须**等于所在格**——命令声明的目标（格）必须覆盖它真正动到的资源，否则权限围栏判的不是同一件事。
 * </ul>
 */
class ActorPayloadsTest {

  private static final SimosTimestamp T7 = SimosTimestamp.of(7);

  private static final ActorRef ESTATE_FARM = new ActorRef(ActorKind.ESTATE, "farm@0_0");
  private static final ActorRef HOUSEHOLD = new ActorRef(ActorKind.HOUSEHOLD, "house@0_0");

  /** 最小合法载荷：一格，两个主体 + 一条产权 + 一本账。 */
  private static final String PAYLOAD =
      "{\"mapId\":\"Map1\",\"rulesVersion\":\"actor-v1\",\"entries\":[{\"q\":0,\"r\":0,"
          + "\"actors\":[{\"kind\":\"ESTATE\",\"id\":\"farm@0_0\",\"label\":\"农业庄园\"},"
          + "{\"kind\":\"HOUSEHOLD\",\"id\":\"house@0_0\",\"label\":\"农户\"}],"
          + "\"holdings\":[{\"owner\":{\"kind\":\"ESTATE\",\"id\":\"farm@0_0\"},"
          + "\"location\":{\"q\":0,\"r\":0},"
          + "\"assetKey\":{\"kind\":\"LAND\",\"qualities\":{\"quality\":\"B\",\"arable\":\"true\"}},"
          + "\"quantity\":10000}],"
          + "\"goods\":[{\"owner\":{\"kind\":\"HOUSEHOLD\",\"id\":\"house@0_0\"},"
          + "\"location\":{\"q\":0,\"r\":0},\"balances\":{\"grain\":2241000,\"fiber\":0}}]}]}";

  /** 产权行的完整字面（**替换类用例的锚点**：它唯一，替换不中就是测了空气）。 */
  private static final String HOLDING_ROW =
      "{\"owner\":{\"kind\":\"ESTATE\",\"id\":\"farm@0_0\"},"
          + "\"location\":{\"q\":0,\"r\":0},"
          + "\"assetKey\":{\"kind\":\"LAND\",\"qualities\":{\"quality\":\"B\",\"arable\":\"true\"}},"
          + "\"quantity\":10000}";

  /** 会计行的完整字面（同上）。 */
  private static final String GOODS_ROW =
      "{\"owner\":{\"kind\":\"HOUSEHOLD\",\"id\":\"house@0_0\"},"
          + "\"location\":{\"q\":0,\"r\":0},\"balances\":{\"grain\":2241000,\"fiber\":0}}";

  // ── 正例：逐值 materialize ──────────────────────────────────────────────────────────

  /** ★ 正例：四张表**逐值**落盘（元信息 / 主体 / 产权 / 库存），且 `activatedDay` = 世界当前 tick。 */
  @Test
  void materializesEveryFieldValueForValue() {
    ActorData data = ActorPayloads.toData(ActorPayloads.parse(PAYLOAD), Set.of(), T7);

    ActorMeta meta = data.meta().orElseThrow();
    assertThat(meta.mapId()).isEqualTo("Map1");
    assertThat(meta.rulesVersion()).isEqualTo("actor-v1");
    assertThat(meta.activatedDay()).as("激活日 = 世界当前 tick").isEqualTo(7L);

    assertThat(data.actors()).containsOnlyKeys(ESTATE_FARM, HOUSEHOLD);
    Actor estate = data.actors().get(ESTATE_FARM);
    assertThat(estate.ref()).as("键从值派生：键 == Actor.ref()").isEqualTo(ESTATE_FARM);
    assertThat(estate.label()).isEqualTo("农业庄园");

    AssetHoldingKey landKey =
        new AssetHoldingKey(
            ESTATE_FARM,
            new HexCoord(0, 0),
            AssetClassKey.land(Map.of("quality", "B", "arable", "true")));
    assertThat(data.holdings()).containsOnlyKeys(landKey);
    AssetHolding holding = data.holdings().get(landKey);
    assertThat(holding.key()).as("键从值派生：键 == AssetHolding.key()").isEqualTo(landKey);
    assertThat(holding.quantity()).isEqualTo(10_000L);
    assertThat(landKey.assetKey().qualities())
        .as("qualities 由 AssetClassKey 构造期规范化（按键排序）")
        .containsExactlyInAnyOrderEntriesOf(Map.of("quality", "B", "arable", "true"));

    GoodsAccountKey accountKey = new GoodsAccountKey(HOUSEHOLD, new HexCoord(0, 0));
    assertThat(data.accounts()).containsOnlyKeys(accountKey);
    GoodsAccount account = data.accounts().get(accountKey);
    assertThat(account.key()).as("键从值派生：键 == GoodsAccount.key()").isEqualTo(accountKey);
    assertThat(account.balances())
        .as("余额逐值；**0 保留**（存量不是空表）")
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(new CommodityId("grain"), 2_241_000L, new CommodityId("fiber"), 0L));
  }

  /**
   * ★★ **先落主体、后落产权是合法写序**：{@code owner} 不在**本载荷**里、但已在**现有状态**里 ⇒ 收下。
   *
   * <p>这条判据的口径就是 {@link ActorData} 类注写的那样："表与表之间没有引用完整性约束……存在性由**装配期与命令面**校验" ——
   * 命令面判的是"<b>到这个命令为止</b>该主体存不存在"，而不是"必须与产权同批到达"。
   */
  @Test
  void acceptsAnOwnerThatAlreadyExistsInTheState() {
    ActorData data =
        ActorPayloads.toData(ActorPayloads.parse(PAYLOAD), Set.of(ESTATE_FARM, HOUSEHOLD), T7);

    assertThat(data.holdings()).as("现有状态里已有该主体 ⇒ 收下").hasSize(1);
    assertThat(data.actors()).as("主体表照旧只装载荷声明的那两条").hasSize(2);
  }

  /**
   * ★★ **主体声明在别的格、产权落在这一格 ⇒ 合法**（载荷的格序**不是**依赖序）。
   *
   * <p>判别力：这条用例钉的是"**两趟走**"（先收齐全部主体，再建两张带 location 的表）—— 一趟走（边读边建）会把这份载荷 判成"悬空
   * owner"，而它其实完全合法：产权只要求"到这个命令为止该主体存在"，不要求"与产权同格、同一条 entry"。
   */
  @Test
  void acceptsAnOwnerDeclaredInAnotherEntry() {
    String twoEntries =
        "{\"mapId\":\"Map1\",\"rulesVersion\":\"actor-v1\",\"entries\":["
            + "{\"q\":0,\"r\":0,\"holdings\":[{\"owner\":{\"kind\":\"ESTATE\",\"id\":\"farm@1_0\"},"
            + "\"location\":{\"q\":0,\"r\":0},"
            + "\"assetKey\":{\"kind\":\"LAND\",\"qualities\":{}},\"quantity\":1}]},"
            + "{\"q\":1,\"r\":0,\"actors\":[{\"kind\":\"ESTATE\",\"id\":\"farm@1_0\",\"label\":\"庄园\"}]}"
            + "]}";

    ActorData data = ActorPayloads.toData(ActorPayloads.parse(twoEntries), Set.of(), T7);

    assertThat(data.holdings()).as("产权照旧落盘").hasSize(1);
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
                    T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("entries 不得为空");
    assertThatThrownBy(
            () ->
                ActorPayloads.toData(
                    ActorPayloads.parse("{\"mapId\":\"Map1\",\"rulesVersion\":\"v\"}"),
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
                    T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mapId");
    assertThatThrownBy(
            () ->
                ActorPayloads.toData(
                    ActorPayloads.parse("{\"mapId\":\"Map1\",\"entries\":[{\"q\":0,\"r\":0}]}"),
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
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
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
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("字段 label");
  }

  /** 产权行缺 {@code owner} 对象 ⇒ 拒（形状判在本层，不落到 NPE）。 */
  @Test
  void rejectsAHoldingWithoutAnOwnerObject() {
    String payload = PAYLOAD.replace("\"owner\":{\"kind\":\"ESTATE\",\"id\":\"farm@0_0\"},", "");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("owner");
  }

  // ── 拒因：词表外的枚举值（fail-closed）──────────────────────────────────────────────

  /** ★★ 词表外的**主体种类** ⇒ 当场抛，消息里列出合法值。 */
  @Test
  void rejectsAnActorKindOutsideTheVocabulary() {
    String payload = PAYLOAD.replace("\"kind\":\"ESTATE\"", "\"kind\":\"MANOR\"");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("MANOR")
        .hasMessageContaining("ESTATE");
  }

  /** ★★ 词表外的**资产粗类型** ⇒ 当场抛，消息里列出合法值（{@code AssetKind} 是封闭词表）。 */
  @Test
  void rejectsAnAssetKindOutsideTheVocabulary() {
    String payload = PAYLOAD.replace("\"kind\":\"LAND\"", "\"kind\":\"PLOW\"");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PLOW")
        .hasMessageContaining("LAND");
  }

  /** 产权行的 {@code assetKey} 必须是对象（形状判在本层）。 */
  @Test
  void rejectsAHoldingWhoseAssetKeyIsNotAnObject() {
    String payload =
        PAYLOAD.replace(
            "\"assetKey\":{\"kind\":\"LAND\",\"qualities\":{\"quality\":\"B\",\"arable\":\"true\"}}",
            "\"assetKey\":\"LAND|quality=B\"");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("assetKey");
  }

  // ── 拒因：数值语义（交给领域类型的构造期守卫）──────────────────────────────────────

  /** ★ 负产权余额 ⇒ 拒（{@link AssetHolding} 的构造期守卫，经载荷层原样穿出）。 */
  @Test
  void rejectsANegativeHoldingQuantity() {
    String payload = PAYLOAD.replace("\"quantity\":10000", "\"quantity\":-10000");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不得为负");
  }

  /** ★ 负库存余额 ⇒ 拒（{@link GoodsAccount} 的构造期守卫）。 */
  @Test
  void rejectsANegativeGoodsBalance() {
    String payload = PAYLOAD.replace("\"grain\":2241000", "\"grain\":-2241000");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
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
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("主体重复");
  }

  /** 同一份载荷里同一份产权声明两次 ⇒ 拒（否则后写静默覆盖前写，两份余额只剩一份）。 */
  @Test
  void rejectsADuplicateHoldingInOnePayload() {
    String payload = PAYLOAD.replace(HOLDING_ROW, HOLDING_ROW + "," + HOLDING_ROW);

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("产权重复");
  }

  /** 同一份载荷里同一本账声明两次 ⇒ 拒。 */
  @Test
  void rejectsADuplicateAccountInOnePayload() {
    String payload = PAYLOAD.replace(GOODS_ROW, GOODS_ROW + "," + GOODS_ROW);

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("库存重复");
  }

  // ── 拒因：★ 本切片特有的两条引用判据 ────────────────────────────────────────────────

  /**
   * ★★ **悬空 owner ⇒ 拒**：产权指向一个**既不在载荷里、也不在现有状态里**的主体。
   *
   * <p>判别力：一个拼错的 owner（{@code house@9_9} 而非 {@code house@0_0}）若被收下，那份产权就是**静默的幽灵** —— 按 owner
   * 查它查不到、也没有任何一层会报错（同 "拼错产业 id 会让当日劳动静默变 0" 那一族）。故命令面当场拒，并点名 owner 与所在格。
   */
  @Test
  void rejectsAHoldingWhoseOwnerIsDeclaredNowhere() {
    String payload = PAYLOAD.replace("\"id\":\"house@0_0\"}", "\"id\":\"house@9_9\"}");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("house@9_9")
        .hasMessageContaining("0_0");
  }

  /** ★★ 同一条判据对**会计行**同样成立（两张表都有 owner，判据只写一处）。 */
  @Test
  void rejectsAGoodsRowWhoseOwnerIsDeclaredNowhere() {
    String payload = PAYLOAD.replace("\"id\":\"farm@0_0\"}", "\"id\":\"farm@9_9\"}");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("farm@9_9");
  }

  /**
   * ★★ **产权的地格必须等于所在格 ⇒ 否则拒**：命令声明的目标（格）必须覆盖它真正动到的资源。
   *
   * <p>判别力：{@code actor.Seed} 的目标路径是 {@code entries[]} 的**格**（GM 代执行时逐条判越权）。若载荷能在 {@code 0_0} 那一条里写
   * {@code 1_0} 的产权，权限围栏判的就是**另一件事** —— 一条被授权的命令改到了没被授权的格。
   */
  @Test
  void rejectsAHoldingWhoseLocationIsNotTheEntryHex() {
    String payload =
        PAYLOAD.replace(
            "\"owner\":{\"kind\":\"ESTATE\",\"id\":\"farm@0_0\"},"
                + "\"location\":{\"q\":0,\"r\":0}",
            "\"owner\":{\"kind\":\"ESTATE\",\"id\":\"farm@0_0\"},"
                + "\"location\":{\"q\":1,\"r\":0}");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("location")
        .hasMessageContaining("0_0");
  }

  /** ★★ 同一条判据对**会计行**同样成立。 */
  @Test
  void rejectsAGoodsRowWhoseLocationIsNotTheEntryHex() {
    String payload =
        PAYLOAD.replace(
            "\"owner\":{\"kind\":\"HOUSEHOLD\",\"id\":\"house@0_0\"},"
                + "\"location\":{\"q\":0,\"r\":0}",
            "\"owner\":{\"kind\":\"HOUSEHOLD\",\"id\":\"house@0_0\"},"
                + "\"location\":{\"q\":-1,\"r\":0}");

    assertThat(payload).as("替换必须真的发生").isNotEqualTo(PAYLOAD);
    assertThatThrownBy(() -> ActorPayloads.toData(ActorPayloads.parse(payload), Set.of(), T7))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("location")
        .hasMessageContaining("0_0");
  }
}

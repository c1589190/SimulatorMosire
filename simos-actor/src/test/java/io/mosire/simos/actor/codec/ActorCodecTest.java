package io.mosire.simos.actor.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.info.InMemoryInfoSystem;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * actor 模块的 JSON 往返守卫（照 {@code EconomyCodecTest} / {@code LedgerCodecTest} 同制，夹具是 actor 自己的）。
 *
 * <p>★ 覆盖：{@code Optional<ActorMeta>} 两侧向（未激活 / 已激活）、**两张表各自的键类型**（{@code ActorRef}/ {@code
 * GoodsAccountKey}，以及嵌套在 {@code GoodsAccount.balances} 里的 {@code CommodityId}）、值的类型绑定（{@code
 * Actor}/{@code GoodsAccount} 不许退化成 {@code Map}）、 {@code FieldDelta} 四变体、单值组件的投影往返、**字节级**往返（含"派生判断
 * {@code empty} 不进线格式"的观察点）、 外来切片的两条拒绝、坏键与缺 {@code data} 的响亮失败，以及旧档缺键的兼容。
 *
 * <p>★★ <b>{@code namespace()} 那一条不写成"字面量等于字符串"就完事</b>：{@code SimulationState} 构造期校验"modules 的键 ==
 * {@code snapshot.namespace()}"，故本测试**真的构造一次 {@code SimulationState}**（并配一条反例证明那条校验是活的）——
 * 把本类的字面量与装配期校验**钉在一起**， 而不是把字面量抄一遍。
 */
class ActorCodecTest {

  /** 产业型主体（spec §三：{@code ESTATE} 是农业生产的制度身份之一）。 */
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "farm@0_0");

  /** ★ 人口批次型主体：{@code id} 里**自带两个冒号**（批次的 id 就是这个形状）—— 键的规范串逆靠它取得判别力。 */
  private static final ActorRef HOUSEHOLD =
      new ActorRef(ActorKind.HOUSEHOLD, "legacy-rural:0_0:MALE:1");

  private static final ActorMeta META = new ActorMeta("levant", 7L, "rules-r1");

  private static final HexCoord HEX = new HexCoord(0, 0);

  private static final HexCoord OTHER_HEX = new HexCoord(1, 0);

  private static final CommodityId GRAIN = new CommodityId("grain");

  private static final CommodityId CLOTH = new CommodityId("cloth");

  /** ★ 币种夹具：**三种**，各代表一种"钱在不在"的形状 —— 有余额 / 余额恰为 0 / 完全没有这种钱。 */
  private static final CurrencyId SILVER = new CurrencyId("silver");

  private static final CurrencyId COPPER = new CurrencyId("copper");

  private static final CurrencyId GOLD = new CurrencyId("gold");

  private static final StateRef REF = new StateRef(new BranchId("main"), new RevisionId(3));

  private static final SimosTimestamp TS = SimosTimestamp.of(10, "弘光元年");

  private static final StateMeta NEW_META =
      new StateMeta(new StateRef(new BranchId("main"), new RevisionId(42)), SimosTimestamp.of(11));

  private static final ActorCodec CODEC = new ActorCodec();

  // ── namespace：与 ActorSnapshot.namespace() 同字面，且过得了装配期校验 ────────────────────

  @Test
  void namespaceIsActor() {
    assertThat(CODEC.namespace()).isEqualTo("actor");
  }

  /**
   * ★★ <b>本类的字面量不是"再抄一遍字符串"，而是必须过 {@code SimulationState} 的装配期校验</b>（{@code modules} 的键必须等于 {@code
   * snapshot.namespace()}）。
   *
   * <p>★ <b>为什么要真的构造一次</b>：那三条同字面（{@code ActorSnapshot.namespace()} / 本类的 {@code namespace()} /
   * Task 9 的 {@code ToolSupport.ACTOR_NAMESPACE}）里，**只有装配期这一处有牙**。故这里不写 {@code
   * assertThat(CODEC.namespace()).isEqualTo(snapshot.namespace())} 了事—— 那只是把同一个字面量比对两遍。
   *
   * <p>★ <b>反例是必须的</b>：若装配期校验哪天被摘掉，正例仍会绿。故同一条用例里钉一个"键写歪 ⇒ 当场抛"，证明校验是活的。
   */
  @Test
  void namespaceSatisfiesTheAssemblyTimeCheck() {
    ActorSnapshot snapshot = snapshotOf(fullData(), TS);

    assertThatCode(() -> assembled(CODEC.namespace(), snapshot))
        .as("★ 本类的 namespace() 必须就是装配时用的那个键 —— 装配期校验过不去 = 整个世界起不来")
        .doesNotThrowAnyException();
    assertThat(CODEC.namespace())
        .as("与切片那一处同字面（第三条在 Task 9 的 ToolSupport）")
        .isEqualTo(snapshot.namespace());

    assertThatThrownBy(() -> assembled("actor-typo", snapshot))
        .as("反例：键写歪就必须当场抛 —— 否则上一条的绿是白给的")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("modules 的键必须等于该快照的 namespace()");
  }

  private static SimulationState assembled(String key, Snapshot snapshot) {
    return new SimulationState(
        new StateMeta(REF, TS), Map.of(key, snapshot), InMemoryInfoSystem.empty());
  }

  // ── 正例：两张表都非空 ────────────────────────────────────────────────────────────

  /** 非平凡快照往返：带历注 + 两张表都非空 + 两层自定义键 + 两张表的每条记录每个字段都是非平凡值。 */
  @Test
  void snapshotRoundTripsWithAllTablesNonEmpty() {
    ActorSnapshot snapshot = snapshotOf(fullData(), TS);

    ActorSnapshot back = (ActorSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back).isEqualTo(snapshot);
    assertThat(back.ref()).as("ref 与 timestamp 也必须过线").isEqualTo(snapshot.ref());
    assertThat(back.timestamp()).isEqualTo(snapshot.timestamp());
  }

  /** Optional 的另一个方向：无历注（empty）也要活着。 */
  @Test
  void snapshotRoundTripsWithUnlabeledTimestamp() {
    ActorSnapshot snapshot = snapshotOf(fullData(), SimosTimestamp.of(11));

    ActorSnapshot back = (ActorSnapshot) CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshot));

    assertThat(back.timestamp()).isEqualTo(snapshot.timestamp());
    assertThat(back).isEqualTo(snapshot);
  }

  /**
   * ★★ <b>逐字段</b>：两张表的每一条、每一条的每个字段各断言一次（task-6 的"逐字段"口径，不是一条 {@code equals} 结账）。
   *
   * <p>★ <b>两张表都非空</b>是本条的前提，也是**键（反）序列化器被真正走到**的前提：空表会让注册项**永远不被执行** —— 那是"注册了却测不到"的假覆盖（{@code
   * EconomyCodecTest} 对 R2 两张表记的正是这一条）。
   */
  @Test
  void everyFieldOfAFullActorDataSurvivesTheWire() {
    ActorData full = fullData();

    ActorData back = dataOf(CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshotOf(full, TS))));

    // ① meta：三个组件
    assertThat(back.meta()).as("已激活 ⇒ 必须回来（不是空 Optional）").isPresent();
    ActorMeta meta = back.meta().orElseThrow();
    assertThat(meta.mapId()).isEqualTo("levant");
    assertThat(meta.activatedDay()).isEqualTo(7L);
    assertThat(meta.rulesVersion()).isEqualTo("rules-r1");

    // ② actors：键（ActorRef 的键反序列化器）+ 每条的两个组件
    assertThat(back.actors()).containsOnlyKeys(ESTATE, HOUSEHOLD);
    assertThat(back.actors().get(ESTATE).ref()).as("值不许退化成 Map").isEqualTo(ESTATE);
    assertThat(back.actors().get(ESTATE).label()).isEqualTo("庄园");
    assertThat(back.actors().get(HOUSEHOLD).ref()).isEqualTo(HOUSEHOLD);
    assertThat(back.actors().get(HOUSEHOLD).label()).isEqualTo("佃农家户");

    // ③ accounts：键（两段）+ 余额表（键是 CommodityId —— 那是**嵌套**的一层自定义键）
    GoodsAccountKey estateAccount = new GoodsAccountKey(ESTATE, HEX);
    GoodsAccountKey householdAccount = new GoodsAccountKey(HOUSEHOLD, OTHER_HEX);
    assertThat(back.accounts()).containsOnlyKeys(estateAccount, householdAccount);
    assertThat(back.accounts().get(estateAccount).key().owner()).isEqualTo(ESTATE);
    assertThat(back.accounts().get(estateAccount).key().location()).isEqualTo(HEX);
    assertThat(back.accounts().get(estateAccount).balances())
        .as("★ 余额表的键必须还原成 CommodityId（不是 String）")
        .isEqualTo(Map.of(GRAIN, 100L, CLOTH, 5L));
    assertThat(back.accounts().get(householdAccount).balances())
        .as("两本账各自独立，不许串")
        .isEqualTo(Map.of(GRAIN, 7L));

    assertThat(back).as("整体相等只是最后一条").isEqualTo(full);
  }

  // ── ★★ Review Focus ⑤：空 ActorData 的往返是"逐字段"，不是"任意空也相等" ──────────────

  /**
   * ★★ <b>Review Focus ⑤</b>：未激活（空 {@code ActorData}）经 codec 往返后**逐字段相同**。
   *
   * <p>★★ <b>为什么不能只写一条 {@code assertThat(back).isEqualTo(ActorData.empty())}</b>：{@code ActorData}
   * 的紧凑构造器把 {@code null} 归一成空表 / 未激活（旧档兼容，fail-closed）⇒ **"解码出来全是 null"与"解码出来是空表"在 {@code equals}
   * 下不可区分**，那条断言对 codec 一句话都没说（它证的是构造器会归一）。 故三个组件**各断言一次**、且各自要求 <b>非 null</b>（{@code isNotNull()}
   * 在前：{@code null} 过不了 {@code isEmpty()}，但把这件事写出来才读得懂）。
   *
   * <p>★★ <b>空态在线上有两种写法，两种都要逐字段</b>：我们写出去的是显式的 {@code "meta":null} + 两张 {@code {}}；旧档则三个键<b>全缺席</b>
   * （{@code "data":{}}）。★ <b>「全缺席」那一路正是紧凑构造器的 fail-closed 归一被走到的地方</b>—— Jackson 对缺席的 {@code Map}
   * 组件传 {@code null}（而对缺席的 {@code Optional} 传 {@code Optional.empty()}，故 {@code meta}
   * 那一条归一其实够不着），所以它同时钉住"缺 ⇒ 空表"。
   *
   * <p>★★ <b>空态还必须是"非吸收态"</b>（"不是任意空也相等"的正面表述）：若编码/解码把一切都塌成空，本条的前两段仍会绿 —— 故最后一段拿两个**非空**近邻 （只激活 /
   * 只落一条主体）验它们回来**不与空相等**。
   */
  @Test
  void emptyActorDataRoundTripsFieldByField() {
    ActorData empty = ActorData.empty();

    ActorData back = dataOf(CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshotOf(empty, TS))));

    assertThat(back.meta()).as("meta：未激活 ⇒ 空 Optional（不是 null，也不是 present）").isNotNull().isEmpty();
    assertThat(back.actors()).as("actors：空表（不是 null）").isNotNull().isEmpty();
    assertThat(back.accounts()).as("accounts：空表（不是 null）").isNotNull().isEmpty();
    assertThat(back).as("整体相等只是最后一条").isEqualTo(empty);

    // ★ 另一种空态写法：三个键**全缺席**（旧档）。逐字段同样要回来，且不许是 null。
    ActorData fromAbsentKeys =
        dataOf(
            CODEC.decodeSnapshot(
                "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":3}},"
                    + "\"timestamp\":{\"tick\":10,\"calendarLabel\":null},\"data\":{}}"));

    assertThat(fromAbsentKeys.meta()).as("缺键 → 空 Optional").isNotNull().isEmpty();
    assertThat(fromAbsentKeys.actors())
        .as("缺键 → 空表（**不是 null** —— 这一条才真的走到构造器的归一）")
        .isNotNull()
        .isEmpty();
    assertThat(fromAbsentKeys.accounts()).as("缺键 → 空表").isNotNull().isEmpty();
    assertThat(fromAbsentKeys).as("两种空态写法必须落到**同一个** ActorData").isEqualTo(empty);

    // ★ 空态不是吸收态：两个非空近邻各自回来，且都与空**不**相等。
    ActorData activated = empty.withMeta(Optional.of(META));
    ActorData oneActor = empty.withActor(new Actor(ESTATE, "庄园"));
    ActorData activatedBack =
        dataOf(CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshotOf(activated, TS))));
    ActorData oneActorBack =
        dataOf(CODEC.decodeSnapshot(CODEC.encodeSnapshot(snapshotOf(oneActor, TS))));

    assertThat(activatedBack).as("只激活 ⇒ 不与空相等（否则上一条的三条断言是白给的）").isNotEqualTo(empty);
    assertThat(activatedBack.meta()).isPresent();
    assertThat(activatedBack.actors()).as("激活不捎带造出主体").isEmpty();
    assertThat(oneActorBack).isNotEqualTo(empty);
    assertThat(oneActorBack.actors()).containsOnlyKeys(ESTATE);
    assertThat(oneActorBack.meta()).as("落一条主体不捎带激活").isEmpty();
  }

  /** ★ 空快照的**线格式**钉在这里：{@code meta} 写 {@code null}、两张表写 {@code {}}（与"缺键"是同一档，读侧都收成空）。 */
  @Test
  void theEmptySnapshotHasAFrozenWireShape() {
    String json = CODEC.encodeSnapshot(snapshotOf(ActorData.empty(), SimosTimestamp.of(10)));

    assertThat(json)
        .as("★ 冻结串：空态就是这三个键，一个不多一个不少（派生判断不在其中）")
        .isEqualTo(
            "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":3}},"
                + "\"timestamp\":{\"tick\":10,\"calendarLabel\":null},"
                + "\"data\":{\"meta\":null,\"actors\":{},\"accounts\":{}}}");
  }

  // ── 变更集：四条变体 + 值类型的绑定 ────────────────────────────────────────────────

  /** 变更集往返：四条变体各造一条，逐条过线。 */
  @Test
  void changeSetRoundTripsWithAllFourDeltaVariants() {
    ActorData full = fullData();
    // ★ patch 那一档必须**既删又改**（只换同键的值是 Upsert，不是 Patch）。
    ActorData base =
        ActorData.empty()
            .withActor(new Actor(ESTATE, "庄园"))
            .withActor(new Actor(HOUSEHOLD, "佃农家户"));
    ActorData moved = ActorData.empty().withActor(new Actor(ESTATE, "东庄"));

    ActorChangeSet unchanged = ActorChangeSet.between(full, full);
    ActorChangeSet upsert = ActorChangeSet.between(ActorData.empty(), full);
    ActorChangeSet remove = ActorChangeSet.between(full, ActorData.empty());
    ActorChangeSet patch = ActorChangeSet.between(base, moved);

    // 前置：夹具确实落在了四条变体上
    assertThat(unchanged.accounts()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(upsert.accounts()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(remove.accounts()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(patch.actors()).isInstanceOf(FieldDelta.Patch.class);

    assertThat((ActorChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(unchanged)))
        .isEqualTo(unchanged);
    assertThat((ActorChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(upsert)))
        .isEqualTo(upsert);
    assertThat((ActorChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(remove)))
        .isEqualTo(remove);
    assertThat((ActorChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(patch)))
        .isEqualTo(patch);
  }

  /**
   * ★ 值类型的绑定不能在读入侧丢成 {@code Map}：{@code Actor} / {@code GoodsAccount} 得还是它们自己，
   * 且**它们的键**得还原成各自的自定义键类型（{@code ActorRef} / {@code GoodsAccountKey} 以及嵌套的 {@code CommodityId}）——
   * 键退化成 {@code String} 的话本仓 {@code FieldDelta.rebuild} 的键解析器就白写了。
   */
  @Test
  void deltaValuesSurviveAsTypedRowsWithParsedKeys() {
    ActorChangeSet back =
        (ActorChangeSet)
            CODEC.decodeChangeSet(
                CODEC.encodeChangeSet(ActorChangeSet.between(ActorData.empty(), fullData())));

    FieldDelta.Upsert<Actor> actors = upsert(back.actors());
    assertThat(actors.entries()).containsOnlyKeys(ESTATE.toString(), HOUSEHOLD.toString());
    Actor estate = actors.entries().get(ESTATE.toString());
    assertThat(estate).isInstanceOf(Actor.class);
    assertThat(estate.ref()).isEqualTo(ESTATE);
    assertThat(estate.label()).isEqualTo("庄园");

    FieldDelta.Upsert<GoodsAccount> accounts = upsert(back.accounts());
    GoodsAccountKey householdAccount = new GoodsAccountKey(HOUSEHOLD, OTHER_HEX);
    GoodsAccount account = accounts.entries().get(householdAccount.toString());
    assertThat(account).isInstanceOf(GoodsAccount.class);
    assertThat(account.key()).isEqualTo(householdAccount);
    assertThat(account.balances())
        .as("★ 嵌套那一层的键也得是 CommodityId，且 0 不许被归一掉")
        .isEqualTo(Map.of(GRAIN, 7L));

    assertThat(back).isEqualTo(ActorChangeSet.between(ActorData.empty(), fullData()));
  }

  /** 单值组件的投影往返：未激活→已激活 = {@code Upsert}、已激活→未激活 = {@code Remove}、值→另一个值 = {@code Upsert}。 */
  @Test
  void metaDeltaRoundTripsInAllThreeShapes() {
    ActorData unactivated = ActorData.empty();
    ActorData activated = unactivated.withMeta(Optional.of(META));
    ActorData revised = activated.withMeta(Optional.of(new ActorMeta("levant", 9L, "rules-r2")));

    ActorChangeSet act = ActorChangeSet.between(unactivated, activated);
    ActorChangeSet deact = ActorChangeSet.between(activated, unactivated);
    ActorChangeSet replace = ActorChangeSet.between(activated, revised);

    assertThat(act.meta()).isInstanceOf(FieldDelta.Upsert.class);
    assertThat(deact.meta()).isInstanceOf(FieldDelta.Remove.class);
    assertThat(replace.meta()).isInstanceOf(FieldDelta.Upsert.class);

    assertThat((ActorChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(act))).isEqualTo(act);
    assertThat((ActorChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(deact)))
        .isEqualTo(deact);
    assertThat((ActorChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(replace)))
        .isEqualTo(replace);
  }

  // ── 确定性：写出来的字节是内容的纯函数 ─────────────────────────────────────────────

  /**
   * ★★ <b>确定性</b>：同一份 {@code ActorData} 编码两次**逐字节相同**；且 <b>编码 → 解码 → 再编码</b>两次也逐字节相同。
   *
   * <p>★ <b>后一半才是真判据</b>："同一个对象编码两次"对任何纯函数都成立；能抓住的是**解码把有序容器换掉** —— 若读入侧把 {@code LinkedHashMap} 换成
   * {@code Map.copyOf}/乱序表，"内容相等而迭代序漂移"就会让第二次编码的字节抖起来。
   *
   * <p>★ <b>本仓的读口约定</b>：状态里的插入序**不是内容的纯函数**（{@code Map.copyOf} 的迭代序就与插入序无关）⇒ 跟着它走字节会抖，故两张表一律 {@code
   * LinkedHashMap} + {@code Collections.unmodifiableMap}。本用例把这条约定**钉在字节上**。
   */
  @Test
  void encodingIsByteLevelStable() {
    ActorData full = fullData();
    ActorSnapshot snapshot = snapshotOf(full, TS);

    String once = CODEC.encodeSnapshot(snapshot);
    assertThat(CODEC.encodeSnapshot(snapshot)).as("★ 同一份数据编码两次：逐字节相同").isEqualTo(once);

    String twice = CODEC.encodeSnapshot(CODEC.decodeSnapshot(once));
    assertThat(twice).as("★ 编码 → 解码 → 再编码：逐字节相同（容器不许在解码时换序）").isEqualTo(once);

    ActorChangeSet changeSet = ActorChangeSet.between(ActorData.empty(), full);
    String changeOnce = CODEC.encodeChangeSet(changeSet);
    assertThat(CODEC.encodeChangeSet(changeSet)).as("变更集也必须逐字节稳定").isEqualTo(changeOnce);
    assertThat(CODEC.encodeChangeSet(CODEC.decodeChangeSet(changeOnce))).isEqualTo(changeOnce);
  }

  /**
   * ★ <b>派生判断 {@code empty} 不进线格式</b>：Jackson 会把 {@code isEmpty()} 当成属性 {@code "empty"}
   * 写进字节，而严格读入随即炸掉 ——{@code SimulationState} 的共享基座为此把它摘了出去，本用例把这条约定**在 actor 的字节上观察一次**。
   */
  @Test
  void derivedPredicatesDoNotReachTheWire() {
    String changeSetJson =
        CODEC.encodeChangeSet(ActorChangeSet.between(ActorData.empty(), fullData()));
    String snapshotJson = CODEC.encodeSnapshot(snapshotOf(fullData(), TS));

    assertThat(changeSetJson).as("派生判断 empty 不得进线格式").doesNotContain("\"empty\"");
    assertThat(snapshotJson).as("快照里也没有派生判断").doesNotContain("\"empty\"");
    assertThat(ActorChangeSet.between(ActorData.empty(), fullData()).isEmpty())
        .as("对照：派生判断本身还活着（只是没进字节）")
        .isFalse();
  }

  /**
   * ★ <b>键的线格式就是各键类型的裸 {@code toString()}</b>（裁定 R-48-f / R-aa 的存在理由）。
   *
   * <p>★ 冻结串在这里，正是因为"键的（反）序列化走 {@code toString()}/{@code parse} 配对"是**落盘契约**： 谁改了某个键类型的 {@code
   * toString()}，这条当场红，而不是等到读旧档时才发现键对不上。
   */
  @Test
  void mapKeysGoToWireAsTheirBareToString() {
    String json = CODEC.encodeSnapshot(snapshotOf(fullData(), TS));

    assertThat(json).as("ActorRef 作键").contains("\"" + ESTATE + "\"");
    assertThat(json)
        .as("GoodsAccountKey 作键（两段）")
        .contains("\"HOUSEHOLD:legacy-rural:0_0:MALE:1|1_0\"");
  }

  /**
   * ★ 嵌套那一层的键：{@code GoodsAccount.balances} 的键是 {@code CommodityId}，写成裸值，且 0 保留、插入序保留。
   *
   * <p>★ <b>夹具刻意用 {@code LinkedHashMap} 而不是 {@code Map.of}</b>：{@code Map.of} 的迭代序**不是内容的纯函数**
   * （{@code ImmutableCollections} 的 SALT 每次 JVM 启动都不同）⇒ 拿它当夹具，这条冻结串会**跨运行抖动**， 那是夹具自己的病、不是被测量的规则。
   */
  @Test
  void nestedCommodityKeysGoToWireAsBareValues() {
    Map<CommodityId, Long> balances = new LinkedHashMap<>();
    balances.put(GRAIN, 100L);
    balances.put(CLOTH, 0L);
    ActorData data =
        ActorData.empty().withAccount(new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), balances));

    String json = CODEC.encodeSnapshot(snapshotOf(data, TS));

    assertThat(json)
        .as("★ 冻结串：键是裸值、**0 保留**（库存是存量）、插入序保留（grain 在 cloth 之前）")
        .contains("\"balances\":{\"grain\":100,\"cloth\":0}");
  }

  /**
   * ★★ <b>钱（{@code GoodsAccount.money}）的序列化往返</b>：一本带<b>多币种</b>余额的账经真 {@code ActorCodec} 出去再回来，
   * <b>逐值</b>断言商品表与货币表都一字不差。
   *
   * <p>★★ <b>它为什么必须存在</b>（2026-09-27，M1.0 的由来，本仓第 5 例幻影判别力）：{@code GoodsAccount} 的类注曾声称"钱有没有被序列化丢 由
   * {@code ActorCodec} 的往返用例守着"——而<b>那条用例当时并不存在</b>（{@code simos-actor/src/test} 对 {@code money()}
   * 零断言），且 {@code ActorCodec} 给 {@code CommodityId} 注册了键反序列化器却<b>没给</b> {@code CurrencyId} 注册（而
   * {@code money} 的键正是它）。⇒ 钱的落盘能力在这条用例出现之前，本模块内<b>没有任何判别力</b>。
   *
   * <p>★★ <b>夹具的形状就是判别力</b>——三个币种各代表一种"钱在不在"：
   *
   * <ul>
   *   <li>{@code silver} = 1,200：<b>有余额</b>（正常那一档）；
   *   <li>{@code copper} = <b>0</b>：余额恰为 0 但**这种钱存在** —— 0 是存量（"这个人手里还有 0 毫铜钱"），
   *       <b>不许</b>被归一成"没有这个键"；
   *   <li>{@code gold}：<b>根本没有这种钱</b> —— 它<b>不是</b>夹具的一部分，读回来也不许出现（没有 = 键缺席）。
   * </ul>
   *
   * ⇒ 任何"把货币表压成单值 / 把 0 归一掉 / 把缺的币种补成 0"的写法，本条都会红。
   *
   * <p>★ <b>同时钉住线格式</b>：键是各币种的裸 {@code toString()}（值本身），0 保留、插入序保留 —— 币种键与商品键走的是<b>同一条</b> "裸值 +
   * {@code parse} 配对"的规矩（裁定 R-48-f / R-aa）。
   */
  @Test
  void moneyRoundTripsThroughTheWireWithZeroKeptAndAbsentDistinct() {
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    money.put(SILVER, 1_200L);
    money.put(COPPER, 0L);
    GoodsAccountKey account = new GoodsAccountKey(HOUSEHOLD, OTHER_HEX);
    ActorData data =
        ActorData.empty().withAccount(new GoodsAccount(account, Map.of(GRAIN, 7L), money));

    String json = CODEC.encodeSnapshot(snapshotOf(data, TS));
    ActorData back = dataOf(CODEC.decodeSnapshot(json));

    assertThat(json)
        .as("★ 线格式：键是裸币种名、**0 保留**、插入序保留（币种与商品同制）")
        .contains("\"money\":{\"silver\":1200,\"copper\":0}");

    // ① 货币表：逐币种逐值（这是"钱真的过线了"的判据 —— 是本用例存在的理由）
    Map<CurrencyId, Long> moneyBack = back.accounts().get(account).money();
    assertThat(moneyBack).as("★ 两个币种都得回来").containsOnlyKeys(SILVER, COPPER);
    assertThat(moneyBack).containsEntry(SILVER, 1_200L);
    assertThat(moneyBack)
        .as("★★ 余额为 0 的币种**保留**（0 是存量）—— 归一掉它就与「没有这种钱」分不开了")
        .containsEntry(COPPER, 0L);
    assertThat(moneyBack).as("★★ 完全没有的币种**不得**被补出来（缺席 ≠ 0）").doesNotContainKey(GOLD);
    assertThat(moneyBack).as("逐值相等：1,200 毫银 + 0 毫铜钱").isEqualTo(Map.of(SILVER, 1_200L, COPPER, 0L));

    // ② 商品表：同一次往返里两表互不顶替（钱不许把货挤掉，货也不许把钱挤掉）
    assertThat(back.accounts().get(account).balances())
        .as("★ 货币表非空时，商品表照样逐值回来")
        .isEqualTo(Map.of(GRAIN, 7L));

    // ③ 字节稳定：带着钱再编码一次，逐字节相同（解码不许在货币表上换容器/换序）
    assertThat(CODEC.encodeSnapshot(CODEC.decodeSnapshot(json)))
        .as("★ 解码 → 再编码：逐字节相同")
        .isEqualTo(json);

    assertThat(back).as("整体相等只是最后一条").isEqualTo(data);
  }

  // ── 施加（C28）与 diff（铁律 5） ──────────────────────────────────────────────────

  /** 施加（C28）：新快照的 ref/timestamp 来自 newMeta，**不是** base 的；且 base 原样不动。 */
  @Test
  void applyProducesNewSnapshotStampedWithNewMeta() {
    ActorSnapshot base = snapshotOf(ActorData.empty(), SimosTimestamp.of(10));

    ActorSnapshot applied =
        (ActorSnapshot)
            CODEC.apply(ActorChangeSet.between(ActorData.empty(), fullData()), base, NEW_META);

    assertThat(applied.ref()).isEqualTo(NEW_META.ref());
    assertThat(applied.timestamp()).isEqualTo(NEW_META.timestamp());
    assertThat(applied.data().actors()).containsOnlyKeys(ESTATE, HOUSEHOLD);
    assertThat(applied.data().accounts())
        .containsOnlyKeys(
            new GoodsAccountKey(ESTATE, HEX), new GoodsAccountKey(HOUSEHOLD, OTHER_HEX));
    assertThat(base.data()).as("base 不得被就地改").isEqualTo(ActorData.empty());
  }

  /** 施加到别的模块的切片上 ⇒ 当场炸，不给出一份错快照。 */
  @Test
  void applyRejectsForeignSlice() {
    ActorSnapshot base = snapshotOf(ActorData.empty(), SimosTimestamp.of(10));
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(
            () ->
                CODEC.apply(
                    ActorChangeSet.between(ActorData.empty(), ActorData.empty()),
                    foreign,
                    new StateMeta(
                        new StateRef(new BranchId("main"), new RevisionId(1)),
                        SimosTimestamp.of(1))))
        .as("★ 先验后转：错误信息要读得出「这是装配给错了切片」")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 ActorSnapshot");

    // base 本身合法，只为让"foreign"这一侧成为唯一失败点
    assertThat(base.namespace()).isEqualTo("actor");
  }

  /** {@code ActorCodec} 同时实现 {@link io.mosire.simos.util.spi.ModuleDiffer}：语义委托 {@code between}。 */
  @Test
  void diffDerivesTheChangeSetFromTwoFullSlices() {
    ActorSnapshot base = snapshotOf(ActorData.empty(), SimosTimestamp.of(10));
    ActorSnapshot target = snapshotOf(fullData(), SimosTimestamp.of(10));

    assertThat(CODEC.diff(base, target))
        .isEqualTo(ActorChangeSet.between(base.data(), target.data()));
    assertThat(CODEC.diff(base, base))
        .as("全相等 ⇒ 各组件 Unchanged（仍是一份合法变更集）")
        .isEqualTo(ActorChangeSet.between(base.data(), base.data()));
    assertThat(((ActorChangeSet) CODEC.diff(base, base)).isEmpty()).as("全相等 ⇒ 这份变更集是空的").isTrue();
  }

  /** diff 的两侧切片都必须属于本模块：转错切片 ⇒ 当场炸，不给出一份错变更集。 */
  @Test
  void diffRejectsForeignSlice() {
    ActorSnapshot actor = snapshotOf(fullData(), SimosTimestamp.of(10));
    Snapshot foreign =
        new ForeignSlice(
            new StateRef(new BranchId("main"), new RevisionId(9)), SimosTimestamp.of(20));

    assertThatThrownBy(() -> CODEC.diff(foreign, actor))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 ActorSnapshot");
    assertThatThrownBy(() -> CODEC.diff(actor, foreign))
        .as("两侧都要验")
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("不是 ActorSnapshot");
  }

  // ── 非法态：宁抛不静默 ───────────────────────────────────────────────────────────

  /**
   * ★★ <b>坏键的快照字节读入即抛</b>：{@code ActorData} 的两张表是**真 {@code Map} 带类型化键** ⇒ 键反序列化器（各类型的 {@code
   * parse}，宁抛不静默）在**解码期**就被走到，失败被 {@code readJson} 包成 {@link IllegalStateException}。
   *
   * <p>★ <b>"静默造一个半截的键"比"当场炸"难查得多</b>：若谁把键反序列化器写成"读进 String 就算了"，坏字节会变成一份 看起来合法、实则指向不存在主体 /
   * 不存在地格的档。
   *
   * <p>★ <b>三档各打一路</b>：{@code actors}（{@code ActorRef}）、 {@code accounts}（{@code
   * GoodsAccountKey}），以及<b>嵌套那一层</b>的 {@code CommodityId}。
   */
  @Test
  void aSnapshotWithAMalformedKeyFailsLoudly() {
    String head =
        "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":3}},"
            + "\"timestamp\":{\"tick\":10,\"calendarLabel\":null},\"data\":";

    String badActorKey =
        head
            + "{\"actors\":{\"NO_SEPARATOR\":"
            + "{\"ref\":{\"kind\":\"ESTATE\",\"id\":\"farm@0_0\"},\"label\":\"庄园\"}}}}";
    String badAccountKey =
        head
            + "{\"accounts\":{\"|0_0\":"
            + "{\"key\":{\"owner\":{\"kind\":\"ESTATE\",\"id\":\"farm@0_0\"},"
            + "\"location\":{\"q\":0,\"r\":0}},\"balances\":{}}}}}";
    String badCommodityKey =
        head
            + "{\"accounts\":{\"ESTATE:farm@0_0|0_0\":"
            + "{\"key\":{\"owner\":{\"kind\":\"ESTATE\",\"id\":\"farm@0_0\"},"
            + "\"location\":{\"q\":0,\"r\":0}},\"balances\":{\"\":100}}}}}";

    // ★ 每一档都点名**它自己那句**：断言的是上游各 parse 的拒绝，而不是"反正抛了点什么" ——
    //   "静默造一个半截的键"（吞掉异常、退回一个默认键）过不了这一条。
    String[][] cases = {
      {badActorKey, "非法 actor 规范串"},
      {badAccountKey, "非法库存键"},
      {badCommodityKey, "CommodityId 不得为空白"},
    };
    for (String[] each : cases) {
      assertThatThrownBy(() -> CODEC.decodeSnapshot(each[0]))
          .as("坏键必须当场炸（快照的键是类型化的，读入期就走各 parse）")
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("actor 侧 JSON 解码失败")
          .hasStackTraceContaining(each[1]);
    }
  }

  /**
   * ★★ <b>对照：变更集里的坏键读得进来，但{@code apply} 那一刻必炸</b> —— 这条把 {@code FieldDelta} 的键模型如实钉住： {@code
   * entries} 是 {@code Map<String, T>}，**键在读入时还是字符串**（正因如此键的逆住在 {@code rebuild}），故 {@code
   * decodeChangeSet} 不抛；坏键由各键类型的 {@code parse} 在 {@code apply} 时挡下。
   *
   * <p>★ <b>本用例测的是"codec 不洗白坏键"</b>：字节进 → 对象出 → 施加，坏键必须在**最后那一步**响亮地失败，绝不静默产出一份 指向不存在主体 /
   * 不存在地格的变更集。
   */
  @Test
  void aChangeSetWithAMalformedKeyIsRejectedWhenItIsApplied() {
    ActorChangeSet badActors =
        new ActorChangeSet(
            null, new FieldDelta.Upsert<>(Map.of("NO_SEPARATOR", new Actor(ESTATE, "庄园"))), null);
    ActorChangeSet badAccounts =
        new ActorChangeSet(
            null,
            null,
            new FieldDelta.Upsert<>(
                Map.of(
                    "|0_0",
                    new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), Map.of(GRAIN, 1L)))));

    for (Map.Entry<ActorChangeSet, String> each :
        List.of(Map.entry(badActors, "非法 actor 规范串"), Map.entry(badAccounts, "非法库存键"))) {
      ActorChangeSet handMade = each.getKey();
      ActorChangeSet back = (ActorChangeSet) CODEC.decodeChangeSet(CODEC.encodeChangeSet(handMade));

      assertThat(back).as("坏键读得进来（FieldDelta 的键是 String）——这是事实，不是漏写").isEqualTo(handMade);
      assertThatThrownBy(() -> CODEC.apply(back, snapshotOf(ActorData.empty(), TS), NEW_META))
          .as("坏键必须在 apply 那一刻由上游的 parse 挡下")
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(each.getValue());
    }
  }

  /** ★ 对照：上面那些"红"不许来自别处 —— 同样的路径，键合法时一个都不抛。 */
  @Test
  void aChangeSetWithWellFormedKeysIsAccepted() {
    ActorChangeSet handMade =
        new ActorChangeSet(
            null,
            new FieldDelta.Upsert<>(Map.of(ESTATE.toString(), new Actor(ESTATE, "庄园"))),
            null);

    String json = CODEC.encodeChangeSet(handMade);

    assertThatCode(() -> CODEC.decodeChangeSet(json)).doesNotThrowAnyException();
    assertThat((ActorChangeSet) CODEC.decodeChangeSet(json)).isEqualTo(handMade);
  }

  /**
   * ★ <b>缺 {@code data} 的快照读入即抛</b>（{@code ActorSnapshot} 的紧凑构造器要求 {@code data} 非 null） —— 与下面"缺表键
   * ⇒ 空表"那条**方向不同**：缺**组件**是旧档（收成空），缺**整个 data** 不是旧档（是坏字节）。
   */
  @Test
  void aSnapshotWithoutDataFailsLoudly() {
    String noData =
        "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":3}},"
            + "\"timestamp\":{\"tick\":10,\"calendarLabel\":null}}";
    String nullData =
        "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":3}},"
            + "\"timestamp\":{\"tick\":10,\"calendarLabel\":null},\"data\":null}";

    for (String bad : new String[] {noData, nullData}) {
      assertThatThrownBy(() -> CODEC.decodeSnapshot(bad))
          .as("缺 / 空 data 都必须当场炸")
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("actor 侧 JSON 解码失败");
    }
  }

  /** ★ 多出来的字段是漂移信号（铁律 5），严格读入**不吞**（{@code FAIL_ON_UNKNOWN_PROPERTIES} 保持默认）。 */
  @Test
  void anUnknownFieldIsNotSwallowed() {
    String drifted =
        "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":3}},"
            + "\"timestamp\":{\"tick\":10,\"calendarLabel\":null},"
            + "\"data\":{\"meta\":null,\"actors\":{},\"accounts\":{}},"
            + "\"surprise\":1}";

    assertThatThrownBy(() -> CODEC.decodeSnapshot(drifted))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("actor 侧 JSON 解码失败");
  }

  // ── 旧档兼容 ─────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>旧档兼容：缺键的快照必须读得回来</b>（§11 的口径，照 {@code EconomyCodecTest}）。
   *
   * <p>字节刻意只留 {@code actors}：{@code meta}/{@code accounts} 两个键**缺席**。若让构造器对 null
   * 抛，等于"这个世界打不开"。缺省方向是 fail-closed：缺 ⇒ 未激活 / 空表。
   */
  @Test
  void legacySnapshotWithoutActorKeysDecodesToUnactivatedEmptyTables() {
    String legacy =
        "{\"ref\":{\"branch\":{\"value\":\"main\"},\"revision\":{\"value\":1}},"
            + "\"timestamp\":{\"tick\":0,\"calendarLabel\":null},"
            + "\"data\":{\"actors\":{}}}";

    ActorSnapshot back = (ActorSnapshot) CODEC.decodeSnapshot(legacy);

    assertThat(back.data().actors()).isEmpty();
    assertThat(back.data().accounts()).as("旧档没提库存 ⇒ 空表，不抛").isEmpty();
    assertThat(back.data().meta()).as("旧档没提元信息 ⇒ 未激活，不抛").isEmpty();
  }

  /**
   * ★★ <b>旧档兼容：缺键的变更集必须读得回来</b>。缺省 = {@link FieldDelta.Unchanged}（"一字未动"）——读成 null 的话 {@code
   * isEmpty()} 与 {@code apply} 都会 NPE。
   */
  @Test
  void legacyChangeSetWithoutActorKeysDecodesToUnchangedComponents() {
    String legacy = "{\"actors\":{\"@class\":\"unchanged\"}}";

    ActorChangeSet back = (ActorChangeSet) CODEC.decodeChangeSet(legacy);

    assertThat(back.meta()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.accounts()).isInstanceOf(FieldDelta.Unchanged.class);
    assertThat(back.isEmpty()).as("三个组件都未变 ⇒ 这份旧变更集是空的").isTrue();
    assertThat(ActorChangeSet.apply(back, ActorData.empty())).isEqualTo(ActorData.empty());
  }

  // ── 夹具 ─────────────────────────────────────────────────────────────────────────

  /** 别的模块的切片：本测试只借它的**类型**，不借语义。 */
  private record ForeignSlice(StateRef ref, SimosTimestamp timestamp) implements Snapshot {

    @Override
    public String namespace() {
      return "unit";
    }
  }

  /**
   * 非平凡数据：**两张表都非空**、两层自定义键、每条记录的每个字段都取非平凡值。
   *
   * <p>★ 余额表刻意建成 {@code LinkedHashMap}：{@code Map.of} 的迭代序不是内容的纯函数（{@code ImmutableCollections} 的
   * SALT 每次 JVM 启动都不同）⇒ 拿它当夹具，字节级用例会**跨运行抖动**。
   */
  private static ActorData fullData() {
    Map<CommodityId, Long> estateBalances = new LinkedHashMap<>();
    estateBalances.put(GRAIN, 100L);
    estateBalances.put(CLOTH, 5L);
    Map<CommodityId, Long> householdBalances = new LinkedHashMap<>();
    householdBalances.put(GRAIN, 7L);
    return ActorData.empty()
        .withMeta(Optional.of(META))
        .withActor(new Actor(ESTATE, "庄园"))
        .withActor(new Actor(HOUSEHOLD, "佃农家户"))
        .withAccount(new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), estateBalances))
        .withAccount(
            new GoodsAccount(new GoodsAccountKey(HOUSEHOLD, OTHER_HEX), householdBalances));
  }

  private static ActorSnapshot snapshotOf(ActorData data, SimosTimestamp timestamp) {
    return new ActorSnapshot(REF, timestamp, data);
  }

  private static ActorData dataOf(Snapshot snapshot) {
    return ((ActorSnapshot) snapshot).data();
  }

  @SuppressWarnings("unchecked")
  private static <T> FieldDelta.Upsert<T> upsert(FieldDelta<T> delta) {
    assertThat(delta).isInstanceOf(FieldDelta.Upsert.class);
    return (FieldDelta.Upsert<T>) delta;
  }
}

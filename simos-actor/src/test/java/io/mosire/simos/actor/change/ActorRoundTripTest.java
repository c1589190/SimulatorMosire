package io.mosire.simos.actor.change;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorMeta;
import io.mosire.simos.actor.ActorSnapshot;
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
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.FieldDelta;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * ★ 铁律 5 的机械化落地（照 {@code EconomyRoundTripTest} / {@code LedgerRoundTripTest}）：反射枚举 {@link
 * ActorData} 的 record 组件，逐组件造差异，三条断言 —— 新增状态组件若忘了进变更集，本测试自动红。
 *
 * <p>★ <b>它也是本任务的"变异自证"落点</b>：把 {@code ActorChangeSet.between} 里任一分量改成恒 {@code Unchanged}，
 * "该组件参与"那条断言当场红。
 *
 * <p>★ <b>结构断言（Review Focus ①）也在本类</b>：{@link Actor} 的组件必须恰是 {@code ref} 与 {@code label} —— spec §三
 * L283 的禁令（不许 {@code ActorRow { Money money; List<Debt> debts; }}）在结构上可判，故用反射钉死。
 *
 * <p>★ <b>往返不是"两个空对象也相等"</b>：{@link #roundTripRebuildsEveryFieldOfANonTrivialTarget()} **逐字段**断言
 * 重建结果（meta 三个组件 + 每条主体 的键 / ref / label + 每条持有的键 / owner / hex / assetClass / quantity + 每本账的键 /
 * owner / hex 与其余额的每个商品）， 整体 {@code equals} 只是最后一条。
 */
class ActorRoundTripTest {

  /** 产业型主体（spec §三：{@code ESTATE} 是农业生产的制度身份之一）。 */
  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "farm@0_0");

  /** ★ 人口批次型主体：{@code id} 里**自带两个冒号**（批次的 id 就是这个形状）—— 键的规范串逆靠它取得判别力。 */
  private static final ActorRef HOUSEHOLD = new ActorRef(ActorKind.HOUSEHOLD, "rural:0_0:MALE:1");

  private static final ActorMeta META = new ActorMeta("levant", 7L, "rules-r1");

  /** ★ Task 5 的产权夹具：{@code ESTATE} 在 {@code HEX} 持 B 等地。 */
  private static final HexCoord HEX = new HexCoord(0, 0);

  private static final HexCoord OTHER_HEX = new HexCoord(1, 0);

  /** ★ 两个资产类**只差 quality 一段** ⇒ 任何"把 qualities 抹平"的键都会把两条压成一条。 */
  private static final AssetClassKey ARABLE_B =
      AssetClassKey.land(Map.of("arable", "true", "quality", "B"));

  private static final AssetClassKey ARABLE_C =
      AssetClassKey.land(Map.of("arable", "true", "quality", "C"));

  /** ★ Task 6 的库存夹具：粮与布 —— 两个商品键不同 ⇒ 任何"把 balances 压成单值"的写法都会丢掉一条。 */
  private static final CommodityId GRAIN = new CommodityId("grain");

  private static final CommodityId CLOTH = new CommodityId("cloth");

  /** ★ 唯一的豁免集合：本切片的 {@code ActorData} 没有"不进变更集"的组件 ⇒ 必须是空集，且被单独钉死。 */
  private static final Set<String> EXCLUDED_FROM_CHANGE_SET = Set.of();

  // ── 铁律 5：反射枚举 ────────────────────────────────────────────────────────────────

  @Test
  void everyActorDataComponentParticipatesInTheChangeSet() {
    for (RecordComponent rc : ActorData.class.getRecordComponents()) {
      if (EXCLUDED_FROM_CHANGE_SET.contains(rc.getName())) {
        continue;
      }
      String name = rc.getName();
      ActorData base = ActorData.empty();
      ActorData target = mutate(base, name);
      ActorChangeSet cs = ActorChangeSet.between(base, target);

      assertThat(cs.isEmpty()).as("组件 %s 必须进变更集（漏了它 ⇒ 变更集整个为空）", name).isFalse();
      assertThat(changedOf(cs, name)).as("组件 %s 必须被 between 报成非 Unchanged", name).isTrue();
      assertThat(ActorChangeSet.apply(cs, base)).as("组件 %s 的往返", name).isEqualTo(target);
    }
  }

  @Test
  void theExclusionListIsEmpty() {
    assertThat(EXCLUDED_FROM_CHANGE_SET).as("ActorData 没有豁免项；要加名字必须在 diff 里现形").isEmpty();
  }

  @Test
  void changeSetHasExactlyFourComponents() {
    assertThat(ActorChangeSet.class.getRecordComponents()).hasSize(4);
    assertThat(componentNames(ActorChangeSet.class))
        .as("变更集的每个组件都必须在 ActorData 里有同名的 record 组件")
        .isSubsetOf(componentNames(ActorData.class));
    assertThat(componentNames(ActorData.class))
        .as("反向也成立 ⇒ 两边组件集相同")
        .isSubsetOf(componentNames(ActorChangeSet.class));
  }

  @Test
  void snapshotNamespaceIsActor() {
    ActorSnapshot snapshot =
        new ActorSnapshot(
            new StateRef(new BranchId("main"), new RevisionId(1)),
            SimosTimestamp.of(5),
            ActorData.empty());
    assertThat(snapshot.namespace())
        .as("★ SimulationState 构造期校验 modules 的键 == snapshot.namespace() ⇒ 这个字面量是装配契约")
        .isEqualTo("actor");
  }

  // ── Review Focus ①：结构断言 ───────────────────────────────────────────────────────

  /**
   * ★★ <b>{@link Actor} 的组件必须恰是 {@code ref} 与 {@code label}</b>（brief Step 5）。
   *
   * <p>★ <b>用 {@code containsExactlyInAnyOrder} 而不是 {@code contains}</b>：这条断言的价值全在<b>否定性</b> ——
   * spec §三 L283 的禁令点名 {@code ActorRow { Money money; List<Debt> debts; }} 这种形状，多一个组件就必须当场红。 {@code
   * contains} 挡不住"塞回来的那个"，等于没测。
   */
  @Test
  void actorHasExactlyRefAndLabel() {
    assertThat(componentNames(Actor.class))
        .as("★★ spec §三 L283 的禁令：Actor 只许有 ref 与 label 两个组件（资产/库存/货币/债务都不在本体里）")
        .containsExactlyInAnyOrder("ref", "label");
  }

  // ── 往返：逐字段 ──────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>逐字段往返</b>（brief Step 3 的原文）：断言 {@code apply(between(base, target), base)} <b>逐字段</b> 重建出
   * {@code target}。
   *
   * <p>★ 为什么不能只写一条 {@code assertThat(rebuilt).isEqualTo(target)}：{@code base} 与 {@code target}
   * 都为"空"时它也成立 —— <b>那样一条测试的判别力为零</b>（本仓反复踩过的"判别力假货"）。故这里 target 非平凡、 且每个组件的每个字段各断言一次。
   */
  @Test
  void roundTripRebuildsEveryFieldOfANonTrivialTarget() {
    ActorData base = ActorData.empty();
    ActorData target =
        base.withMeta(Optional.of(META))
            .withActor(new Actor(ESTATE, "庄园"))
            .withActor(new Actor(HOUSEHOLD, "佃农家户"))
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 10_000L))
            .withHolding(new AssetHolding(new AssetHoldingKey(HOUSEHOLD, OTHER_HEX, ARABLE_C), 3L))
            .withAccount(
                new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), Map.of(GRAIN, 100L, CLOTH, 0L)))
            .withAccount(
                new GoodsAccount(new GoodsAccountKey(HOUSEHOLD, OTHER_HEX), Map.of(GRAIN, 7L)));

    ActorChangeSet cs = ActorChangeSet.between(base, target);
    ActorData rebuilt = ActorChangeSet.apply(cs, base);

    // ① 四个组件都真的进了变更集（否则"相等"可能只是因为两边都没动）
    assertThat(cs.meta().changed()).as("meta 参与").isTrue();
    assertThat(cs.actors().changed()).as("actors 参与").isTrue();
    assertThat(cs.holdings().changed()).as("holdings 参与").isTrue();
    assertThat(cs.accounts().changed()).as("accounts 参与").isTrue();
    assertThat(cs.meta()).as("空 → 有值 = Upsert").isInstanceOf(FieldDelta.Upsert.class);
    assertThat(cs.actors()).as("从空表加两条 = Upsert").isInstanceOf(FieldDelta.Upsert.class);
    assertThat(cs.holdings()).as("从空表加两条 = Upsert").isInstanceOf(FieldDelta.Upsert.class);
    assertThat(cs.accounts()).as("从空表加两本 = Upsert").isInstanceOf(FieldDelta.Upsert.class);

    // ② 逐字段：meta 的三个组件
    assertThat(rebuilt.meta()).as("meta 必须被重建出来（不是空 Optional）").isPresent();
    ActorMeta meta = rebuilt.meta().orElseThrow();
    assertThat(meta.mapId()).isEqualTo(META.mapId());
    assertThat(meta.activatedDay()).isEqualTo(META.activatedDay());
    assertThat(meta.rulesVersion()).isEqualTo(META.rulesVersion());

    // ③ 逐字段：actors 的键 + 每条的两个组件（先整体比对两条与它们的序，再逐字段点开）
    assertThat(rebuilt.actors()).containsExactlyEntriesOf(target.actors());
    assertThat(rebuilt.actors().get(ESTATE).ref()).isEqualTo(ESTATE);
    assertThat(rebuilt.actors().get(ESTATE).label()).isEqualTo("庄园");
    assertThat(rebuilt.actors().get(HOUSEHOLD).ref()).isEqualTo(HOUSEHOLD);
    assertThat(rebuilt.actors().get(HOUSEHOLD).label()).isEqualTo("佃农家户");

    // ④ 逐字段：holdings 的键（三段）+ 值（两个组件）
    assertThat(rebuilt.holdings()).containsExactlyEntriesOf(target.holdings());
    AssetHoldingKey estateHolding = new AssetHoldingKey(ESTATE, HEX, ARABLE_B);
    assertThat(rebuilt.holdings().get(estateHolding).key().owner()).isEqualTo(ESTATE);
    assertThat(rebuilt.holdings().get(estateHolding).key().location()).isEqualTo(HEX);
    assertThat(rebuilt.holdings().get(estateHolding).key().assetKey()).isEqualTo(ARABLE_B);
    assertThat(rebuilt.holdings().get(estateHolding).quantity()).isEqualTo(10_000L);
    AssetHoldingKey householdHolding = new AssetHoldingKey(HOUSEHOLD, OTHER_HEX, ARABLE_C);
    assertThat(rebuilt.holdings().get(householdHolding).key().owner()).isEqualTo(HOUSEHOLD);
    assertThat(rebuilt.holdings().get(householdHolding).key().location()).isEqualTo(OTHER_HEX);
    assertThat(rebuilt.holdings().get(householdHolding).key().assetKey()).isEqualTo(ARABLE_C);
    assertThat(rebuilt.holdings().get(householdHolding).quantity()).isEqualTo(3L);

    // ④b 逐字段：accounts 的键（两段）+ 每本账的余额表（逐个商品，含一条 0）
    assertThat(rebuilt.accounts()).containsExactlyEntriesOf(target.accounts());
    GoodsAccountKey estateAccount = new GoodsAccountKey(ESTATE, HEX);
    assertThat(rebuilt.accounts().get(estateAccount).key().owner()).isEqualTo(ESTATE);
    assertThat(rebuilt.accounts().get(estateAccount).key().location()).isEqualTo(HEX);
    assertThat(rebuilt.accounts().get(estateAccount).balances())
        .as("★ 逐商品断言；其中一条是 0 ⇒ 0 在往返里也不许被归一掉（库存是存量）")
        .containsOnlyKeys(GRAIN, CLOTH)
        .containsEntry(GRAIN, 100L)
        .containsEntry(CLOTH, 0L);
    GoodsAccountKey householdAccount = new GoodsAccountKey(HOUSEHOLD, OTHER_HEX);
    assertThat(rebuilt.accounts().get(householdAccount).key().owner()).isEqualTo(HOUSEHOLD);
    assertThat(rebuilt.accounts().get(householdAccount).key().location()).isEqualTo(OTHER_HEX);
    assertThat(rebuilt.accounts().get(householdAccount).balances())
        .as("另一本账的余额不许被前一本来回串（两本账各自独立）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 7L));

    // ⑤ 最后才是整体相等（它不能是唯一一条：空对象也满足它）
    assertThat(rebuilt).isEqualTo(target);
  }

  /**
   * ★ 往返的另一半：<b>删除与覆盖</b>。
   *
   * <p>★ 只测"从空表往里加"会漏掉 {@link FieldDelta} 四条变体里的 {@code Remove} 与 {@code Patch} —— 上一条用例只走到 {@code
   * Upsert}。这一条刻意让两侧<b>又删又改</b>（四个组件各一路：{@code meta} 走 {@code Unchanged}、{@code actors} / {@code
   * holdings} / {@code accounts} 各走一次 {@code Patch}）。
   */
  @Test
  void roundTripAlsoRemovesAndOverwrites() {
    ActorData base =
        ActorData.empty()
            .withMeta(Optional.of(META))
            .withActor(new Actor(ESTATE, "庄园"))
            .withActor(new Actor(HOUSEHOLD, "佃农家户"))
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 10_000L))
            .withHolding(new AssetHolding(new AssetHoldingKey(HOUSEHOLD, HEX, ARABLE_B), 1_000L))
            .withAccount(new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), Map.of(GRAIN, 100L)))
            .withAccount(new GoodsAccount(new GoodsAccountKey(HOUSEHOLD, HEX), Map.of(GRAIN, 7L)));
    // target：撤掉家户这个主体与它的产权/库存、给庄园改名、把庄园的持有量减到 7,500、把粮覆盖成 40
    //   ⇒ 三个表上"既删又改"
    ActorData target =
        ActorData.empty()
            .withMeta(Optional.of(META))
            .withActor(new Actor(ESTATE, "东庄"))
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 7_500L))
            .withAccount(new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), Map.of(GRAIN, 40L)));

    ActorChangeSet cs = ActorChangeSet.between(base, target);
    assertThat(cs.actors()).as("既删又改 ⇒ Patch（两侧都不许丢）").isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.holdings())
        .as("产权也是既删又改 ⇒ Patch（数量是**覆盖**：调用方给的是「该余额是多少」）")
        .isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.accounts())
        .as("库存同样是既删又改 ⇒ Patch（销掉家户那本 + 覆盖庄园那本）")
        .isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.meta())
        .as("两侧 meta 相同 ⇒ Unchanged（不是空对象）")
        .isInstanceOf(FieldDelta.Unchanged.class);

    ActorData rebuilt = ActorChangeSet.apply(cs, base);
    assertThat(rebuilt.actors()).containsExactlyEntriesOf(target.actors());
    assertThat(rebuilt.actors().get(ESTATE).label()).isEqualTo("东庄");
    assertThat(rebuilt.actors()).as("被撤掉的主体不许留在重建结果里").doesNotContainKey(HOUSEHOLD);
    assertThat(rebuilt.holdings()).containsExactlyEntriesOf(target.holdings());
    assertThat(rebuilt.holdings().get(new AssetHoldingKey(ESTATE, HEX, ARABLE_B)).quantity())
        .as("覆盖后的余额（不是 10_000 与 7_500 相加，也不是留在 10_000）")
        .isEqualTo(7_500L);
    assertThat(rebuilt.holdings())
        .as("被撤掉的产权不许留在重建结果里")
        .doesNotContainKey(new AssetHoldingKey(HOUSEHOLD, HEX, ARABLE_B));
    assertThat(rebuilt.accounts()).containsExactlyEntriesOf(target.accounts());
    assertThat(rebuilt.accounts().get(new GoodsAccountKey(ESTATE, HEX)).balances())
        .as("覆盖后的库存（不是 100 与 40 相加，也不是留在 100）")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 40L));
    assertThat(rebuilt.accounts())
        .as("被销账的那本不许留在重建结果里")
        .doesNotContainKey(new GoodsAccountKey(HOUSEHOLD, HEX));
    assertThat(rebuilt).isEqualTo(target);
  }

  /**
   * ★★ <b>{@code accounts} 那一路的两个方向各一条</b>（brief：新 delta 的 {@code Remove} / 同键换值）：{@code Remove} =
   * 这个人不在这一格了（整本账销掉）；同键换值 = 账还在、余额被<b>覆盖</b>。
   *
   * <p>★ <b>覆盖那一条刻意把新值写成 0</b>（不是删键）：于是它同时钉住两件事 —— 余额是"<b>该余额是多少</b>"（不是与旧值相加）， 且 <b>0
   * 不许被归一成空表</b>（库存是存量，spec §2.5 L166）。若谁把 0 当作"没有"而丢掉该商品键，这条当场红。
   */
  @Test
  void accountDeltaRoundTripsBothRemoveAndOverwrite() {
    GoodsAccountKey estateAtHex = new GoodsAccountKey(ESTATE, HEX);
    GoodsAccountKey householdAtHex = new GoodsAccountKey(HOUSEHOLD, HEX);
    ActorData base =
        ActorData.empty()
            .withAccount(new GoodsAccount(estateAtHex, Map.of(GRAIN, 100L, CLOTH, 5L)))
            .withAccount(new GoodsAccount(householdAtHex, Map.of(GRAIN, 7L)));
    ActorData target =
        ActorData.empty().withAccount(new GoodsAccount(estateAtHex, Map.of(GRAIN, 0L)));

    ActorChangeSet cs = ActorChangeSet.between(base, target);

    assertThat(cs.accounts()).as("既删又改 ⇒ Patch（两侧都不许丢）").isInstanceOf(FieldDelta.Patch.class);
    assertThat(cs.isEmpty()).as("这一档必须被报成「有变化」，否则它根本不会进 apply").isFalse();

    ActorData rebuilt = ActorChangeSet.apply(cs, base);
    assertThat(rebuilt.accounts()).as("被销账的那本不许留在重建结果里").doesNotContainKey(householdAtHex);
    assertThat(rebuilt.accounts().get(estateAtHex).balances())
        .as("★ 同键换值 = 覆盖（0 就是 0）：不是与 100 相加，也不许把 0 归一成空表")
        .containsExactlyInAnyOrderEntriesOf(Map.of(GRAIN, 0L));
    assertThat(rebuilt.accounts().get(estateAtHex).key())
        .as("换的是余额，不是键 —— 重建后仍归在 (ESTATE, HEX) 名下")
        .isEqualTo(estateAtHex);
    assertThat(rebuilt).isEqualTo(target);
  }

  // ── 单值组件的两个方向：有值 → 空 / 有值 → 另一个值（M-1 补覆盖） ──────────────────────

  /**
   * ★★ <b>{@code meta} 投影的"有值 → 空"方向</b>（Task 4 评审的 Minor M-1）：单值组件走的是 {@code Optional} ↔
   * <b>单键表</b>的投影，而投影**方向不对称** —— 这一档是 {@link FieldDelta.Remove}，不是 {@code Unchanged}、 也不是"空表的
   * Upsert"（{@code Unchanged} 与"变为空"是两件事，见 {@code FieldDelta} 类注释）。
   *
   * <p>★ 为什么必须单独一条：Task 4 的往返只跑到"空 → 有值"，撤活（有值 → 空）一次都没跑过 —— 而 {@code metaOf} 收到空表时返回 {@code
   * Optional.empty()} 正是这一档的唯一判据。
   */
  @Test
  void metaProjectionRoundTripsWhenItBecomesEmpty() {
    ActorData base = ActorData.empty().withMeta(Optional.of(META));
    ActorData target = ActorData.empty();

    ActorChangeSet cs = ActorChangeSet.between(base, target);

    assertThat(cs.meta()).as("有值 → 空 = Remove").isInstanceOf(FieldDelta.Remove.class);
    assertThat(cs.isEmpty()).as("这一档必须被报成「有变化」，否则撤活根本不会进 apply").isFalse();
    assertThat(cs.meta().changed()).isTrue();
    assertThat(ActorChangeSet.apply(cs, base).meta()).as("撤活必须真的落到重建结果里").isEmpty();
    assertThat(ActorChangeSet.apply(cs, base)).isEqualTo(target);
  }

  /**
   * ★★ <b>{@code meta} 投影的"有值 → 另一个值"方向</b>（同上，M-1 的第二半）：键还是那个键（投影表的单键 {@code "meta"}），换的是值 ⇒
   * {@link FieldDelta.Upsert} 里"该键已在 base 中"的那条支路。
   *
   * <p>★ 为什么必须单独一条：它只比"空 → 有值"多一件事 —— <b>键已在 base</b>。若谁把它按"{@code Optional} 在不在"短路（在 →
   * 就当没变），这条当场红，而"空 → 有值"那条照绿。
   */
  @Test
  void metaProjectionRoundTripsWhenItIsReplacedByAnotherValue() {
    ActorMeta other = new ActorMeta("levant", 9L, "rules-r2");
    ActorData base = ActorData.empty().withMeta(Optional.of(META));
    ActorData target = ActorData.empty().withMeta(Optional.of(other));

    ActorChangeSet cs = ActorChangeSet.between(base, target);
    ActorData rebuilt = ActorChangeSet.apply(cs, base);

    assertThat(cs.meta()).as("有值 → 另一个值 = Upsert（同一个键、换了值）").isInstanceOf(FieldDelta.Upsert.class);
    assertThat(rebuilt.meta()).as("换的是值，不是又加一行").contains(other);
    assertThat(rebuilt.meta().orElseThrow().activatedDay()).isEqualTo(9L);
    assertThat(rebuilt.meta().orElseThrow().rulesVersion()).isEqualTo("rules-r2");
    assertThat(rebuilt).isEqualTo(target);
  }

  // ── 键：规范串的逆（裁定 R4 的落点） ────────────────────────────────────────────────

  /**
   * ★★ <b>键的规范串逆</b>：{@code FieldDelta} 的键是 {@code toString()} 的产物（{@code "<KIND>:<id>"}），
   * 而它的逆住在**上游** {@link ActorRef#parseCanonical(String)}（与 {@code toString()} 同处一个文件、 共用同一个分隔符常量）——
   * 本切片只委托，**不知道分隔符是什么、也不判断按第几个切**。
   *
   * <p>★ <b>判别力全在夹具的 id 上</b>：{@link #HOUSEHOLD} 的 id 自带两个冒号 ⇒ 上游若把切法改成按
   * <b>最后一个</b>冒号切、或按全部冒号切，本切片的往返**当场红**。★ 前置断言把这个前提也钉住： 夹具改简单了，这条用例会自己响。
   */
  @Test
  void roundTripParsesActorKeysWhoseIdContainsColons() {
    ActorData target = ActorData.empty().withActor(new Actor(HOUSEHOLD, "佃农家户"));

    assertThat(HOUSEHOLD.toString())
        .as("前置：夹具的规范串确实自带两个冒号（否则这条用例没有判别力）")
        .isEqualTo("HOUSEHOLD:rural:0_0:MALE:1");

    ActorRef rebuiltKey =
        ActorChangeSet.apply(ActorChangeSet.between(ActorData.empty(), target), ActorData.empty())
            .actors()
            .keySet()
            .iterator()
            .next();

    assertThat(rebuiltKey.kind()).as("分隔符之前那段是种类").isEqualTo(ActorKind.HOUSEHOLD);
    assertThat(rebuiltKey.id()).as("id 里的冒号必须原样回到 id 那一侧").isEqualTo("rural:0_0:MALE:1");
    assertThat(rebuiltKey).isEqualTo(HOUSEHOLD);
  }

  /**
   * ★ 格式不对的键<b>宁抛不静默</b>（口径照 {@code ClassKey#parse}；实现上是上游 {@link ActorRef#parseCanonical(String)}
   * 的拒绝）。
   *
   * <p>★ 这条只在"变更集不是 {@code between} 产出的"时才可达（手搓 {@link FieldDelta.Upsert}，例如将来的 codec 读进一条坏字节）——
   * 正是那种输入最需要一句<b>指名道姓</b>的抛，而不是 {@code StringIndexOutOfBoundsException}。
   *
   * <p>★★ <b>断言的是上游那句话（"规范串"）而不是切片自己的词</b>：这本身就是"拼写点在上游"的<b>可执行证据</b>——
   * 若哪天有人把逆又抄回本切片，消息文案一变，这条断言当场红。
   *
   * <p>★ 三档坏输入各打一条分支：<b>没有分隔符</b>（空串，{@code indexOf} 返回 −1）、<b>分隔符在首</b>（切出来种类为空）、 <b>分隔符在尾</b>（切出来
   * id 为空）。
   */
  @Test
  void aKeyThatIsNotAWellFormedActorRefIsRejected() {
    for (String bad : new String[] {"", "NO_SEPARATOR", ":farm@0_0", "ESTATE:"}) {
      ActorChangeSet handMade =
          new ActorChangeSet(
              null, new FieldDelta.Upsert<>(Map.of(bad, new Actor(ESTATE, "庄园"))), null, null);

      assertThatThrownBy(() -> ActorChangeSet.apply(handMade, ActorData.empty()))
          .as("坏键「%s」必须抛，且消息来自上游的规范串校验", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("规范串");
    }
  }

  /**
   * ★ 上一条的对照：<b>键合法时一个都不抛</b>。
   *
   * <p>★ 为什么要有这条：否则上一条的"红"可能来自 {@code apply} 的别处（例如 meta 那一路），读不出是哪一条规则失守。
   */
  @Test
  void aWellFormedKeyIsAccepted() {
    ActorChangeSet handMade =
        new ActorChangeSet(
            null,
            new FieldDelta.Upsert<>(Map.of(ESTATE.toString(), new Actor(ESTATE, "庄园"))),
            null,
            null);

    assertThatCode(() -> ActorChangeSet.apply(handMade, ActorData.empty()))
        .doesNotThrowAnyException();
    assertThat(ActorChangeSet.apply(handMade, ActorData.empty()).actors()).containsOnlyKeys(ESTATE);
  }

  // ── 写入口：键从值派生 ────────────────────────────────────────────────────────────

  /**
   * ★★ <b>{@code withActor} 的键从值派生</b>（控制方裁定："键从值派生"只许有一个拼写点）。
   *
   * <p>★ 同一个 {@code ref} 写两次 ⇒ <b>后写覆盖前写</b>（调用方给的是"这个主体是谁"，不是"追加一条"）。
   */
  @Test
  void withActorDerivesTheKeyFromTheValue() {
    ActorData once = ActorData.empty().withActor(new Actor(ESTATE, "庄园"));
    assertThat(once.actors()).containsExactly(Map.entry(ESTATE, new Actor(ESTATE, "庄园")));
    assertThat(once.actors().keySet().iterator().next())
        .as("键恰是 value.ref()（不是另一条独立入参，故调用方拼不出不一致的键）")
        .isEqualTo(once.actors().values().iterator().next().ref());

    ActorData twice = once.withActor(new Actor(ESTATE, "东庄"));
    assertThat(twice.actors()).as("同一个 ref 写两次 ⇒ 只有一条").hasSize(1);
    assertThat(twice.actors().get(ESTATE).label()).isEqualTo("东庄");
  }

  /**
   * ★★ <b>{@code withHolding} 的键从值派生</b>（同 {@code withActor}，"键从值派生"只许有一个拼写点）。
   *
   * <p>★ 这里的判别力<b>不在"写了两次只剩一条"</b>（那是 {@code AssetHoldingTest} 的 {@code
   * writingTheSameKeyTwiceOverwrites}），而在：<b>键恰是 {@code value.key()} 的三个字段本身</b> —— wither
   * 不接受另一个独立的键入参，故"键与值各说各话"在类型上就造不出来（绕过 wither 直接塞表的那条路由构造器挡）。
   */
  @Test
  void withHoldingDerivesTheKeyFromTheValue() {
    AssetHolding row = new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 10_000L);
    ActorData once = ActorData.empty().withHolding(row);

    assertThat(once.holdings()).containsExactly(Map.entry(row.key(), row));
    AssetHoldingKey key = once.holdings().keySet().iterator().next();
    assertThat(key.owner()).as("键的第一段 = value.key().owner()").isEqualTo(row.key().owner());
    assertThat(key.location()).as("键的第二段 = value.key().location()").isEqualTo(row.key().location());
    assertThat(key.assetKey()).as("键的第三段 = value.key().assetKey()").isEqualTo(row.key().assetKey());

    AssetHolding overwritten = new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 7_500L);
    assertThat(once.withHolding(overwritten).holdings()).as("同一个键写两次 ⇒ 只有一条").hasSize(1);
    assertThat(once.withHolding(overwritten).holdings().get(key).quantity()).isEqualTo(7_500L);
  }

  /**
   * ★★ <b>{@code withAccount} 的键从值派生</b>（同 {@code withActor} / {@code
   * withHolding}，"键从值派生"只许有一个拼写点）。
   *
   * <p>★ 判别力同 {@code withHolding} 那条：wither <b>不接受另一个独立的键入参</b>，故"键与值各说各话"在类型上就造不出来 （绕过 wither
   * 直接塞表的那条路由构造器挡）。
   */
  @Test
  void withAccountDerivesTheKeyFromTheValue() {
    GoodsAccount row = new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), Map.of(GRAIN, 100L));
    ActorData once = ActorData.empty().withAccount(row);

    assertThat(once.accounts()).containsExactly(Map.entry(row.key(), row));
    GoodsAccountKey key = once.accounts().keySet().iterator().next();
    assertThat(key.owner()).as("键的第一段 = value.key().owner()").isEqualTo(row.key().owner());
    assertThat(key.location()).as("键的第二段 = value.key().location()").isEqualTo(row.key().location());

    GoodsAccount overwritten =
        new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), Map.of(GRAIN, 40L));
    assertThat(once.withAccount(overwritten).accounts()).as("同一个键写两次 ⇒ 只有一本").hasSize(1);
    assertThat(once.withAccount(overwritten).accounts().get(key).balances())
        .containsEntry(GRAIN, 40L);
  }

  // ── 产权的键：规范串与它的逆（裁定 R-48-f 的落点） ────────────────────────────────

  /**
   * ★★ <b>{@link AssetHoldingKey} 的"裸 {@code toString()} + 单参 {@code parse}"这一对</b>（裁定 R-48-f，照
   * {@link ActorRef#parseCanonical(String)} 的先例）：本仓 {@code FieldDelta} 的键模型假定"各 key 类型自带 裸 {@code
   * toString()} + {@code static parse}"，缺了它，切片就被迫自己写规范串的逆 —— 同一个格式就有了两处拼写点。
   *
   * <p>★ <b>冻结串</b>（照 map 线 {@code EdgeRef} 的 {@code toStringMatchesFrozenLiteral} 先例）：格式一旦定下就是
   * <b>落盘契约</b>（变更集的 key），改它必须有人当场拍板，故用字面量钉死。
   *
   * <p>★★ <b>前置断言在数接缝：4 个</b>（所有者后 1 + 地格后 1 + 资产类段内部 2）。若谁把夹具换成"只有一个 qualities"的资产类（{@code
   * LAND|arable=true}），"按头两个接缝切"与"按段数恰为 3 切"就<b>不可区分</b>了 （见 {@link
   * #roundTripParsesHoldingKeysWhoseAssetClassHasSeveralSegments}），这条前置断言会自己响。
   */
  @Test
  void holdingKeyToStringIsAFrozenCanonicalLiteralAndItsOwnInverse() {
    AssetHoldingKey key = new AssetHoldingKey(ESTATE, HEX, ARABLE_B);

    assertThat(key.toString())
        .as("★ 冻结串：{@code <owner>|<location>|<assetKey>}")
        .isEqualTo("ESTATE:farm@0_0|0_0|LAND|arable=true|quality=B");
    assertThat(key.toString().chars().filter(c -> c == '|').count())
        .as("前置：夹具的规范串有 4 个接缝 —— 资产类段自身占掉 2 个（判别力来自这里）")
        .isEqualTo(4);

    AssetHoldingKey back = AssetHoldingKey.parse(key.toString());
    assertThat(back.owner()).as("第一段回到 ActorRef").isEqualTo(ESTATE);
    assertThat(back.location()).as("第二段回到 HexCoord").isEqualTo(HEX);
    assertThat(back.assetKey()).as("第三段（含它自己的接缝）原样回到 AssetClassKey").isEqualTo(ARABLE_B);
    assertThat(back).isEqualTo(key);
  }

  /**
   * ★★ <b>往返：{@code apply} 真的用 {@code AssetHoldingKey.parse} 把新键还原回来</b>（R-48-f 的用处所在）。
   *
   * <p>★ <b>判别力全在资产类段的接缝数上</b>：{@link #ARABLE_B} 的规范串自带两个 {@code |} ⇒ 谁把逆改成 <b>按最后一个接缝切</b>（{@code
   * lastIndexOf}）、或按"段数恰为 3"切，这条<b>当场红</b>。★ 只有 "按头两个接缝切"能把三段还原 —— 因为资产类段自身的接缝必须整段留给它。
   */
  @Test
  void roundTripParsesHoldingKeysWhoseAssetClassHasSeveralSegments() {
    ActorData target =
        ActorData.empty()
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 10_000L));

    ActorData rebuilt =
        ActorChangeSet.apply(ActorChangeSet.between(ActorData.empty(), target), ActorData.empty());

    AssetHoldingKey rebuiltKey = rebuilt.holdings().keySet().iterator().next();
    assertThat(rebuiltKey.owner()).isEqualTo(ESTATE);
    assertThat(rebuiltKey.location()).isEqualTo(HEX);
    assertThat(rebuiltKey.assetKey()).as("qualities 一个都不许丢").isEqualTo(ARABLE_B);
    assertThat(rebuiltKey)
        .as("还原出来的键必须与 target 的键相等")
        .isEqualTo(target.holdings().keySet().iterator().next());
    assertThat(rebuilt).isEqualTo(target);
  }

  /**
   * ★ 产权键的坏输入<b>宁抛不静默</b>（口径照 {@code ClassKey#parse} / {@code EdgeRef#parse}）。
   *
   * <p>★ 四档各打一条分支：<b>没有接缝</b>（{@code indexOf} 返回 −1）、<b>接缝在首</b>（切出来所有者为空）、
   * <b>只有两个接缝但地格段为空</b>、<b>接缝在尾</b>（切出来资产类段为空）。
   *
   * <p>★ <b>抛只可能来自 holdings 那一路</b>：手搓的变更集里 {@code meta} / {@code actors} 都是 {@code Unchanged} ⇒
   * {@code rebuild} 拿到 {@code Unchanged} 直接返回 base、连解析器都不会被调用。
   */
  @Test
  void aHoldingKeyThatIsNotWellFormedIsRejected() {
    AssetHolding row = new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 1L);
    for (String bad :
        new String[] {
          "", "NO_SEPARATOR", "|0_0|LAND", "ESTATE:farm@0_0||LAND", "ESTATE:farm@0_0|0_0|"
        }) {
      ActorChangeSet handMade =
          new ActorChangeSet(null, null, new FieldDelta.Upsert<>(Map.of(bad, row)), null);

      assertThatThrownBy(() -> ActorChangeSet.apply(handMade, ActorData.empty()))
          .as("坏键「%s」必须抛，且是**本类**那句（点名了「产权键」的形状不合格）", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("产权键");
    }

    // ★ 接缝都对、但某一段的内容非法 ⇒ 由**上游的严格解析**挡下，而不是本类自己复述一遍它的格式：
    //   判据是消息里那个上游的词 —— 本类要是自己重写一份 ActorKind 词表 / 坐标解析，这两句断言当场红。
    assertThatThrownBy(() -> AssetHoldingKey.parse("NOSUCHKIND:x|0_0|LAND"))
        .as("种类的词表校验住在上游 ActorRef.parseCanonical / ActorKind.parse")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ActorKind");
    assertThatThrownBy(() -> AssetHoldingKey.parse("ESTATE:farm@0_0|XY|LAND"))
        .as("坐标的解析住在上游 HexCoord.parse")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("坐标串");
  }

  /** ★ 上一条的对照：<b>键合法时一个都不抛</b>（否则上一条的"红"可能来自 {@code apply} 的别处，读不出是哪条规则失守）。 */
  @Test
  void aWellFormedHoldingKeyIsAccepted() {
    AssetHolding row = new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 1L);
    ActorChangeSet handMade =
        new ActorChangeSet(
            null, null, new FieldDelta.Upsert<>(Map.of(row.key().toString(), row)), null);

    assertThatCode(() -> ActorChangeSet.apply(handMade, ActorData.empty()))
        .doesNotThrowAnyException();
    assertThat(ActorChangeSet.apply(handMade, ActorData.empty()).holdings())
        .containsOnlyKeys(row.key());
  }

  // ── 库存的键：规范串与它的逆（同 R-48-f 的配对，Task 6） ──────────────────────────

  /**
   * ★★ <b>{@link GoodsAccountKey} 的"裸 {@code toString()} + 单参 {@code parse}"这一对</b>（裁定 R-48-f 在
   * Task 6 的兑现，同 {@link AssetHoldingKey} 的先例）：{@code FieldDelta} 的键模型假定"各 key 类型自带裸 {@code
   * toString()} + {@code static parse}"，缺了它，本切片就被迫自己写规范串的逆 —— 同一个格式就有了两处拼写点。
   *
   * <p>★ <b>冻结串</b>（照 map 线 {@code EdgeRef} / {@link AssetHoldingKey} 的先例）：格式一旦定下就是<b>落盘契约</b>
   * （变更集的 key），改它必须有人当场拍板，故用字面量钉死。
   *
   * <p>★ <b>判别力来自夹具的 owner 自带两个冒号</b>（{@code HOUSEHOLD:rural:0_0:MALE:1}）：谁把逆写成"按某个冒号切"
   * 或"只取冒号之后那段"，这条当场红。★ 前置断言把"恰好 1 个接缝"也钉住 —— 夹具改复杂了这条会自己响。
   */
  @Test
  void accountKeyToStringIsAFrozenCanonicalLiteralAndItsOwnInverse() {
    GoodsAccountKey key = new GoodsAccountKey(HOUSEHOLD, OTHER_HEX);

    assertThat(key.toString())
        .as("★ 冻结串：{@code <owner>|<location>}")
        .isEqualTo("HOUSEHOLD:rural:0_0:MALE:1|1_0");
    assertThat(key.toString().chars().filter(c -> c == '|').count())
        .as("前置：夹具的规范串**恰 1 个接缝**（两段都不含分隔符 —— 见类注里那条已声明前提）")
        .isEqualTo(1);

    GoodsAccountKey back = GoodsAccountKey.parse(key.toString());
    assertThat(back.owner()).as("第一段回到 ActorRef").isEqualTo(HOUSEHOLD);
    assertThat(back.location()).as("第二段回到 HexCoord").isEqualTo(OTHER_HEX);
    assertThat(back).isEqualTo(key);
  }

  /**
   * ★★ <b>往返：{@code apply} 真的用 {@code GoodsAccountKey.parse} 把新键还原回来</b>（R-48-f 的用处所在）。
   *
   * <p>★ <b>判别力在夹具的形状上</b>：owner 的规范串自带两个冒号、地格是 {@code 1_0} ⇒ 逆若写成"按某个冒号切" 或"按 {@code _} 切"，这条当场红。★
   * <b>但"按最后一个接缝切"这档在这条上**不会**红</b>：合法的规范串恰一个接缝，首个与末个恒同位置 ⇒
   * 本类所有往返用例对它都不敏感（实测：把切法改成末个接缝，这些用例全绿）。那一档的判别力只在 {@link
   * #aGoodsAccountKeyWhoseOwnerIdContainsTheSeparatorFailsLoudly()} 上。
   */
  @Test
  void roundTripParsesAccountKeys() {
    ActorData target =
        ActorData.empty()
            .withAccount(
                new GoodsAccount(new GoodsAccountKey(HOUSEHOLD, OTHER_HEX), Map.of(GRAIN, 7L)));

    ActorData rebuilt =
        ActorChangeSet.apply(ActorChangeSet.between(ActorData.empty(), target), ActorData.empty());

    GoodsAccountKey rebuiltKey = rebuilt.accounts().keySet().iterator().next();
    assertThat(rebuiltKey.owner()).isEqualTo(HOUSEHOLD);
    assertThat(rebuiltKey.location()).isEqualTo(OTHER_HEX);
    assertThat(rebuiltKey)
        .as("还原出来的键必须与 target 的键相等")
        .isEqualTo(target.accounts().keySet().iterator().next());
    assertThat(rebuilt).isEqualTo(target);
  }

  /**
   * ★ 库存键的坏输入<b>宁抛不静默</b>（口径照 {@code ClassKey#parse} / {@link AssetHoldingKey#parse}）。
   *
   * <p>★ 四档各打一条分支：<b>空串</b>、<b>没有接缝</b>（{@code indexOf} 返回 −1）、<b>接缝在首</b>（切出来所有者为空）、
   * <b>接缝在尾</b>（切出来地格段为空）。
   *
   * <p>★ <b>抛只可能来自 accounts 那一路</b>：手搓的变更集里其余三个组件都是 {@code Unchanged} ⇒ {@code rebuild} 拿到 {@code
   * Unchanged} 直接返回 base、连解析器都不会被调用。
   */
  @Test
  void aGoodsAccountKeyThatIsNotWellFormedIsRejected() {
    GoodsAccount account = new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), Map.of(GRAIN, 1L));
    for (String bad : new String[] {"", "NO_SEPARATOR", "|0_0", "ESTATE:farm@0_0|"}) {
      ActorChangeSet handMade =
          new ActorChangeSet(null, null, null, new FieldDelta.Upsert<>(Map.of(bad, account)));

      assertThatThrownBy(() -> ActorChangeSet.apply(handMade, ActorData.empty()))
          .as("坏键「%s」必须抛，且是**本类**那句（点名了「库存键」的形状不合格）", bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("库存键");
    }

    // ★ 接缝都对、但某一段的内容非法 ⇒ 由**上游的严格解析**挡下，而不是本类自己复述一遍它的格式：
    //   判据是消息里那个上游的词 —— 本类要是自己重写一份 ActorKind 词表 / 坐标解析，这两句断言当场红。
    assertThatThrownBy(() -> GoodsAccountKey.parse("NOSUCHKIND:x|0_0"))
        .as("种类的词表校验住在上游 ActorRef.parseCanonical / ActorKind.parse")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ActorKind");
    assertThatThrownBy(() -> GoodsAccountKey.parse("ESTATE:farm@0_0|XY"))
        .as("坐标的解析住在上游 HexCoord.parse")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("坐标串");
  }

  /**
   * ★★ <b>已声明前提的"方向"：违反它必须响亮地失败，绝不静默切出一个错的键</b>（类注里那条"两段都不含 {@code |}"）。
   *
   * <p>★ <b>为什么必须单独一条</b>：{@code "ESTATE:a|b|0_0"} 有两种读法 —— {@code owner="ESTATE:a"} + {@code
   * location="b|0_0"}（非法地格），或 {@code owner="ESTATE:a|b"} + {@code location="0_0"}。★
   * <b>后者正是"按最后一个接缝切" 的实现会静默造出来的那个键</b>（{@code ActorRef.parseCanonical("ESTATE:a|b")} 会照收）⇒
   * 这条用例是"按<b>第一个</b> 接缝切"与"按最后一个接缝切"之间的判别力所在：本类的切法把 {@code b|0_0} 交给 {@code HexCoord.parse}，当场抛。
   *
   * <p>★★ <b>本条是"按第一个接缝切"这条规则的全部判别力</b> —— 合法的规范串恰有一个接缝， 首个/末个恒同位置 ⇒
   * <b>删掉本条</b>、把切法改成末个接缝，<b>其余全部用例仍全绿</b> ⇒ <b>规则静默失效</b>（裁定 R-ag：Task 6 的变异体 M-F 实测确认）。
   */
  @Test
  void aGoodsAccountKeyWhoseOwnerIdContainsTheSeparatorFailsLoudly() {
    assertThatThrownBy(() -> GoodsAccountKey.parse("ESTATE:a|b|0_0"))
        .as(
            "按第一个接缝切 ⇒ 余下那段「b|0_0」喂不进 HexCoord.parse（NumberFormatException 也是 IllegalArgumentException）")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 上一条的对照：<b>键合法时一个都不抛</b>（否则上一条的"红"可能来自 {@code apply} 的别处，读不出是哪条规则失守）。 */
  @Test
  void aWellFormedGoodsAccountKeyIsAccepted() {
    GoodsAccount account = new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), Map.of(GRAIN, 1L));
    ActorChangeSet handMade =
        new ActorChangeSet(
            null, null, null, new FieldDelta.Upsert<>(Map.of(account.key().toString(), account)));

    assertThatCode(() -> ActorChangeSet.apply(handMade, ActorData.empty()))
        .doesNotThrowAnyException();
    assertThat(ActorChangeSet.apply(handMade, ActorData.empty()).accounts())
        .containsOnlyKeys(account.key());
  }

  // ── 旧档兼容 ─────────────────────────────────────────────────────────────────────

  /**
   * ★ 缺键（旧档）⇒ 该组件 {@code Unchanged}，**此处不抛**（照 {@code LedgerChangeSet} / {@code EconomyChangeSet}
   * 的口径）。
   */
  @Test
  void aChangeSetFromAnOldArchiveTreatsMissingComponentsAsUnchanged() {
    ActorChangeSet fromOldArchive = new ActorChangeSet(null, null, null, null);

    assertThat(fromOldArchive.isEmpty()).as("旧档没提该组件 ⇒ 就是没动它").isTrue();
    assertThat(ActorChangeSet.apply(fromOldArchive, ActorData.empty()))
        .as("一字未动的往返必须恒等")
        .isEqualTo(ActorData.empty());
  }

  // ── 夹具与反射小工具 ──────────────────────────────────────────────────────────────

  private static ActorData mutate(ActorData base, String name) {
    return switch (name) {
      case "meta" -> base.withMeta(Optional.of(META));
      case "actors" -> base.withActor(new Actor(ESTATE, "庄园"));
      case "holdings" ->
          base.withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 10_000L));
      case "accounts" ->
          base.withAccount(new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), Map.of(GRAIN, 100L)));
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static boolean changedOf(ActorChangeSet cs, String name) {
    return switch (name) {
      case "meta" -> cs.meta().changed();
      case "actors" -> cs.actors().changed();
      case "holdings" -> cs.holdings().changed();
      case "accounts" -> cs.accounts().changed();
      default -> throw new IllegalStateException("未登记的组件: " + name);
    };
  }

  private static Set<String> componentNames(Class<? extends Record> type) {
    Set<String> names = new LinkedHashSet<>();
    for (RecordComponent rc : type.getRecordComponents()) {
      names.add(rc.getName());
    }
    return names;
  }
}

package io.mosire.simos.actor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.Actor;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * actor 状态树的<b>构造期不变量</b>（照 {@code EconomyInvariantsTest} / {@code LedgerModelInvariantsTest} 的形制：
 * 一条规则一条断言）。
 *
 * <p>★ 每条都断言<b>消息里的字段名</b>：消息不点名的话，将来某条规则被误删，测试只会在"抛了"上转绿，读不出是哪一条失守。
 *
 * <p>★★ <b>为什么这些规则必须各有一条断言</b>（Task 3 评审的 Important 教训）：B 段曾出现"7 条校验里有 3 条零测试、 零变异自证 ——
 * 删掉照样全绿"。<b>零覆盖的分支就是装饰</b>，故本类逐条钉住。
 *
 * <p>★ <b>2026-09-27 裁定 S3</b>：产权表（{@code holdings}）整块退役 ⇒ 本类里 holdings 那一组五条（缺键 / 跨表同键 / 键值非 null
 * / 保序不可变 / 防御性拷贝）随之删除 —— 它们测的就是被退役的组件本身。
 */
class ActorInvariantsTest {

  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "farm@0_0");
  private static final ActorRef HOUSEHOLD = new ActorRef(ActorKind.HOUSEHOLD, "rural:0_0:MALE:1");
  private static final ActorRef GOV = new ActorRef(ActorKind.GOVERNMENT, "gov-1");
  private static final ActorRef WORKSHOP = new ActorRef(ActorKind.WORKSHOP, "craft@0_0");
  private static final ActorMeta META = new ActorMeta("levant", 7L, "rules-r1");

  /** ★ 库存夹具的地格。 */
  private static final HexCoord HEX = new HexCoord(0, 0);

  /** ★ Task 6 的库存夹具：粮（{@code GoodsAccount} 的余额表按商品聚合）。 */
  private static final CommodityId GRAIN = new CommodityId("grain");

  // ── Actor（身份本体） ─────────────────────────────────────────────────────────────

  /** ★ 身份引用是<b>身份</b>（铁律 1）：没有 ref 就没有身份，当场抛。 */
  @Test
  void rejectsNullRef() {
    assertThatThrownBy(() -> new Actor(null, "庄园"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ref");
  }

  /** ★ 名字不是身份，但也不能是"没有名字"：空白 label 当场抛（照 {@code ActorRef.id} 拒空白的口径）。 */
  @Test
  void rejectsBlankLabel() {
    assertThatThrownBy(() -> new Actor(ESTATE, "   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("label");
  }

  // ── ActorMeta（激活元信息） ────────────────────────────────────────────────────────

  @Test
  void rejectsBlankMapId() {
    assertThatThrownBy(() -> new ActorMeta("", 0L, "rules-r1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("mapId");
  }

  @Test
  void rejectsNegativeActivatedDay() {
    assertThatThrownBy(() -> new ActorMeta("levant", -1L, "rules-r1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("activatedDay");
  }

  @Test
  void rejectsBlankRulesVersion() {
    assertThatThrownBy(() -> new ActorMeta("levant", 0L, " "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rulesVersion");
  }

  // ── ActorData（状态树） ───────────────────────────────────────────────────────────

  /** ★ 缺键 = 未激活（fail-closed，见 {@code ActorData} 类注释）：{@code meta} 绑成 {@code null} 不抛，收成空。 */
  @Test
  void treatsAMissingMetaKeyAsNotActivated() {
    ActorData data = new ActorData(null, Map.of(), Map.of());

    assertThat(data.meta()).as("空 Optional = 未激活（不是 NPE）").isEmpty();
    assertThat(data).isEqualTo(ActorData.empty());
  }

  /** ★ 缺键 = 空表（同上）。 */
  @Test
  void treatsAMissingActorsKeyAsAnEmptyTable() {
    ActorData data = new ActorData(Optional.of(META), null, Map.of());

    assertThat(data.actors()).as("缺键 ⇒ 没有主体（不是 NPE）").isEmpty();
    assertThat(data.meta()).as("未缺的那一路不许被顺手清掉").isPresent();
  }

  /**
   * ★★ <b>跨表同键不变式</b>（照 {@code EconomyData} 的"键必须与行内 key 一致"）：{@code actors} 的键必须等于 {@code
   * Actor.ref()}。
   *
   * <p>★ <b>它是 {@code withActor} 那个"唯一拼写点"的兜底</b>：绕过 wither 直接塞表（codec 读入、夹具、将来的 handler）
   * 时，也造不出"键与值各说各话"的状态 —— 否则同一份身份就有两处可能不一致的记录。
   */
  @Test
  void rejectsAKeyThatDisagreesWithTheValueRef() {
    Map<ActorRef, Actor> mismatched = new LinkedHashMap<>();
    mismatched.put(HOUSEHOLD, new Actor(ESTATE, "庄园"));

    assertThatThrownBy(() -> new ActorData(Optional.empty(), mismatched, Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("键必须与 Actor.ref 一致");
  }

  /** ★ 键与值都不得为 {@code null}（{@code null} 是"没有"而不是"空主体"）。 */
  @Test
  void rejectsNullKeysAndValuesInActors() {
    assertThatThrownBy(
            () ->
                new ActorData(
                    Optional.empty(),
                    Collections.singletonMap(null, new Actor(ESTATE, "庄园")),
                    Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("都不得为 null");
    assertThatThrownBy(
            () -> new ActorData(Optional.empty(), Collections.singletonMap(ESTATE, null), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("都不得为 null");
  }

  /**
   * ★ <b>保序不可变</b>（冻在字段赋值处；<b>绝不用 {@code Map.copyOf}</b> —— 迭代序不是内容的纯函数， 字节级往返会因此不成立）。
   *
   * <p>★ <b>夹具用 4 个键</b>：3 键时 {@code ImmutableCollections} 的迭代序有可观概率恰好落回插入序（假绿）， 4~6 键才量得准（{@code
   * AGENT.md} §三 的实测口径）。
   */
  @Test
  void actorsKeepsInsertionOrderAndIsFrozen() {
    Map<ActorRef, Actor> input = new LinkedHashMap<>();
    input.put(WORKSHOP, new Actor(WORKSHOP, "作坊"));
    input.put(GOV, new Actor(GOV, "官府"));
    input.put(ESTATE, new Actor(ESTATE, "庄园"));
    input.put(HOUSEHOLD, new Actor(HOUSEHOLD, "佃农家户"));

    ActorData data = new ActorData(Optional.of(META), input, Map.of());

    assertThat(data.actors().keySet())
        .as("插入序即迭代序（字节级往返的前提）")
        .containsExactly(WORKSHOP, GOV, ESTATE, HOUSEHOLD);
    assertThatThrownBy(() -> data.actors().clear())
        .as("冻在字段赋值处")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** ★ <b>防御性拷贝</b>：建完之后改调用方那张表，状态树里的数不许跟着变。 */
  @Test
  void actorsIsCopiedNotAliased() {
    Map<ActorRef, Actor> mutable = new LinkedHashMap<>();
    mutable.put(ESTATE, new Actor(ESTATE, "庄园"));
    ActorData data = new ActorData(Optional.of(META), mutable, Map.of());

    mutable.put(HOUSEHOLD, new Actor(HOUSEHOLD, "佃农家户"));
    mutable.remove(ESTATE);

    assertThat(data.actors())
        .as("建完之后改原 Map，状态树不受影响")
        .containsExactly(Map.entry(ESTATE, new Actor(ESTATE, "庄园")));
  }

  // ── accounts（本切片唯一的那本账：与 actors 同款五条，逐条对应） ──────────────────────

  /** ★ 缺键 = 空表（同上）。 */
  @Test
  void treatsAMissingAccountsKeyAsAnEmptyTable() {
    ActorData data = new ActorData(Optional.of(META), Map.of(), null);

    assertThat(data.accounts()).as("缺键 ⇒ 没有库存（不是 NPE）").isEmpty();
    assertThat(data.meta()).as("未缺的那一路不许被顺手清掉").isPresent();
  }

  /**
   * ★★ <b>跨表同键不变式（库存那一路）</b>：{@code accounts} 的键必须等于 {@link GoodsAccount#key()}。
   *
   * <p>★ 同 {@code actors} 那条：它是 {@code withAccount} 那个"唯一拼写点"的<b>兜底</b>——绕过 wither 直接塞表（codec
   * 读入、夹具、将来的 handler）时，也造不出"键与值各说各话"的库存。
   */
  @Test
  void rejectsAnAccountKeyThatDisagreesWithTheValueKey() {
    Map<GoodsAccountKey, GoodsAccount> mismatched = new LinkedHashMap<>();
    mismatched.put(new GoodsAccountKey(HOUSEHOLD, HEX), account(ESTATE, HEX, 1L));

    assertThatThrownBy(() -> new ActorData(Optional.empty(), Map.of(), mismatched))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("键必须与 GoodsAccount.key 一致");
  }

  /** ★ 键与值都不得为 {@code null}（同 {@code actors} 那条：{@code null} 是"没有"，不是"空账"）。 */
  @Test
  void rejectsNullKeysAndValuesInAccounts() {
    assertThatThrownBy(
            () ->
                new ActorData(
                    Optional.empty(),
                    Map.of(),
                    Collections.singletonMap(null, account(ESTATE, HEX, 1L))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("都不得为 null");
    assertThatThrownBy(
            () ->
                new ActorData(
                    Optional.empty(),
                    Map.of(),
                    Collections.singletonMap(account(ESTATE, HEX, 1L).key(), null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("都不得为 null");
  }

  /**
   * ★ <b>保序不可变</b>（同 {@code actors} 那条）：插入序即迭代序 —— <b>字节级往返的前提</b>。
   *
   * <p>★ 4 个键：靠 {@code owner} 那一维区分（{@code location} 相同）⇒ 与 {@code actors} 那条同款、量得准。
   */
  @Test
  void accountsKeepsInsertionOrderAndIsFrozen() {
    Map<GoodsAccountKey, GoodsAccount> input = new LinkedHashMap<>();
    for (ActorRef owner : List.of(WORKSHOP, GOV, ESTATE, HOUSEHOLD)) {
      GoodsAccount row = account(owner, HEX, 1L);
      input.put(row.key(), row);
    }

    ActorData data = new ActorData(Optional.of(META), Map.of(), input);

    assertThat(data.accounts().keySet())
        .as("插入序即迭代序（字节级往返的前提）")
        .containsExactly(
            new GoodsAccountKey(WORKSHOP, HEX),
            new GoodsAccountKey(GOV, HEX),
            new GoodsAccountKey(ESTATE, HEX),
            new GoodsAccountKey(HOUSEHOLD, HEX));
    assertThatThrownBy(() -> data.accounts().clear())
        .as("冻在字段赋值处")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** ★ <b>防御性拷贝</b>（同 {@code actors} 那条）：建完之后改调用方那张表，状态树里的数不许跟着变。 */
  @Test
  void accountsIsCopiedNotAliased() {
    Map<GoodsAccountKey, GoodsAccount> mutable = new LinkedHashMap<>();
    GoodsAccount kept = account(ESTATE, HEX, 100L);
    mutable.put(kept.key(), kept);
    ActorData data = new ActorData(Optional.of(META), Map.of(), mutable);

    mutable.put(account(HOUSEHOLD, HEX, 1L).key(), account(HOUSEHOLD, HEX, 1L));
    mutable.remove(kept.key());

    assertThat(data.accounts())
        .as("建完之后改原 Map，状态树不受影响")
        .containsExactly(Map.entry(kept.key(), kept));
  }

  /** ★ 库存夹具：{@code (owner, location)} 两段 + 一个商品余额（粮）。 */
  private static GoodsAccount account(ActorRef owner, HexCoord location, long grain) {
    return new GoodsAccount(new GoodsAccountKey(owner, location), Map.of(GRAIN, grain));
  }
}

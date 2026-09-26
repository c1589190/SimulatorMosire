package io.mosire.simos.actor.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetClassKey;
import io.mosire.simos.map.hex.HexCoord;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>I2.2 前半句的逐值判据</b>（spec §三 L283）：{@code AssetHolding} 按 <b>(owner, hex, assetClass)</b> 聚合
 * —— 三段里任一不同就是<b>另一份</b>产权（不合并、不覆盖），三段全同才是同一份（后写覆盖前写）。
 *
 * <p>★ <b>判别力全在"三段只差一段"这个夹具形状上</b>：第一条用例的四条持有两两之间<b>恰差一段</b>，故任何一个"少了一段" 的聚合键（少了 hex / 少了
 * assetClass / 少了 owner）都会当场把 4 条压成更少 ⇒ 那条用例红。
 *
 * <p>★ <b>写入口是 {@code ActorData.withHolding} 而不是裸 {@code Map}</b>（brief Step 1 的注）：若只把表暴露成 {@code
 * Map}，"聚合键是 (owner, hex, assetClass)"就退化成 {@code java.util.Map} 自己的语义 ——
 * 测试会恒真、判别力为零。故"键从值派生"只有一个拼写点，测试才咬得住。
 *
 * <p>★ <b>"可表达性"这一条只验形状</b>（裁定 R3）：{@code Industry.operator} 属阶段 3，本阶段验的是"多一个 actor
 * 不会给任何人新增一条产权、也不会改动任何一条持有的键"。
 */
class AssetHoldingTest {

  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "farm@0_0");
  private static final ActorRef HOUSEHOLD = new ActorRef(ActorKind.HOUSEHOLD, "household@0_0");
  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final HexCoord OTHER = new HexCoord(1, 0);
  private static final AssetClassKey ARABLE_B =
      AssetClassKey.land(Map.of("arable", "true", "quality", "B"));
  private static final AssetClassKey ARABLE_C =
      AssetClassKey.land(Map.of("arable", "true", "quality", "C"));

  /** ★★ I2.2 前半句：聚合键是 (owner, hex, assetClass) —— 三者任一不同就是**另一份**产权。 */
  @Test
  void theAggregationKeyIsOwnerHexAndAssetClass() {
    ActorData all =
        empty()
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 10_000L))
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, OTHER, ARABLE_B), 1L))
            .withHolding(new AssetHolding(new AssetHoldingKey(HOUSEHOLD, HEX, ARABLE_B), 2L))
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_C), 3L));

    assertThat(all.holdings()).as("★ 原有一条 + 三条**只差一段**的 ⇒ 共 4 条（任一段不同即另一份产权）").hasSize(4);
    assertThat(all.holdings().get(new AssetHoldingKey(ESTATE, HEX, ARABLE_B)).quantity())
        .as("★ 原成本 10,000 必须还在 —— 没被那三条只差一段的覆盖掉")
        .isEqualTo(10_000L);
  }

  /** ★ 同一个键写两次 ⇒ **后写覆盖前写**（调用方给的是"该余额是多少"，不是"加多少"）。 */
  @Test
  void writingTheSameKeyTwiceOverwrites() {
    ActorData data =
        empty()
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 10_000L))
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 7_500L));

    assertThat(data.holdings()).as("★ 同一个 (owner, hex, assetClass) 只有一条").hasSize(1);
    assertThat(data.holdings().values().iterator().next().quantity()).isEqualTo(7_500L);
  }

  /** ★ 同一 owner 在两个 hex 各持一份 ⇒ **两条，不合并**（hex 是键的一部分）。 */
  @Test
  void theSameOwnerAtTwoHexesHasTwoHoldings() {
    ActorData data =
        empty()
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 1L))
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, OTHER, ARABLE_B), 2L));

    assertThat(data.holdings()).as("★ 跨格**不合并** —— 否则「某人在全境有多少地」与「这块地归谁」就混成一件事了").hasSize(2);
  }

  /** ★ 同一个 hex 上，owner A 与 owner B 各持一份 ⇒ **两条**（这正是佃制的形状）。 */
  @Test
  void twoOwnersAtTheSameHexAreTwoHoldings() {
    ActorData data =
        empty()
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 9_000L))
            .withHolding(new AssetHolding(new AssetHoldingKey(HOUSEHOLD, HEX, ARABLE_B), 1_000L));

    assertThat(data.holdings()).as("★ 同一格同一类地，两个 owner 各持一份").hasSize(2);
  }

  /**
   * ★★★ **I2.2 后半句的"可表达性"**（裁定 R3）：owner ≠ operator 是**两条互不牵连的记录**。
   *
   * <p>★ 本阶段 {@code Industry.operator} 还不存在（属阶段 3），所以这条**只能**验"形状"： **把另一个 actor
   * 建出来这件事，不会给任何人新增一条产权，也不会改动任何一条持有的键。**
   */
  @Test
  void ownershipAndOperationAreTwoIndependentFacts() {
    ActorData data =
        empty()
            .withActor(new Actor(ESTATE, "庄园"))
            .withActor(new Actor(HOUSEHOLD, "佃农家户"))
            .withHolding(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 10_000L));

    assertThat(data.actors()).as("地主与佃农家户是两个 actor").hasSize(2);
    assertThat(data.holdings().keySet())
        .as("★ 产权只在 ESTATE 名下 —— 多一个 actor **不会**改变任何一条持有的键")
        .containsExactly(new AssetHoldingKey(ESTATE, HEX, ARABLE_B));
    assertThat(data.holdings().keySet())
        .as(
            "★★ 反向：把 HOUSEHOLD 建成 actor 之后，它名下**一条产权都没有**"
                + "（「谁经营」与「谁拥有」在本模型里是两件事 —— 没有任何字段把二者绑起来）")
        .noneMatch(key -> key.owner().equals(HOUSEHOLD));
  }

  /** ★ 负数量即抛（数量是余额，不是增量）；**0 是合法余额**。 */
  @Test
  void rejectsNegativeQuantity() {
    assertThatThrownBy(() -> new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), -1L))
        .as("负余额必须当场抛")
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new AssetHolding(new AssetHoldingKey(ESTATE, HEX, ARABLE_B), 0L))
        .as("★ 0 合法：有这份产权，只是数量为 0（与「没有这份产权」是两件事）")
        .isNotNull();
  }

  /** ★ 往返用例的起点：**未激活 + 三张空表**（组件个数按"当下真能编译"的四件写：meta + actors + holdings + accounts）。 */
  private static ActorData empty() {
    return new ActorData(Optional.empty(), Map.of(), Map.of(), Map.of());
  }
}

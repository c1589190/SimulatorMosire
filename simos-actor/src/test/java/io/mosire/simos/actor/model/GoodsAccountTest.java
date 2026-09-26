package io.mosire.simos.actor.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>{@code GoodsAccount} 的逐值判据</b>（spec §2.5 L166"库存是存量"、裁定 R6）：商品余额按 <b>(owner, hex)</b> 聚合 ——
 * 同一个 owner 在两格是<b>两本账</b>；<b>0 余额保留</b>（不是删键）；负余额当场抛；余额表是<b>防御性拷贝</b>。
 *
 * <p>★ <b>判别力全在"同一 owner 两格"与"0 余额"这两个夹具形状上</b>：前者的两本账若被合并（键少了 {@code location} 一段）就只剩 1
 * 条；后者若被归一成空表，{@link GoodsAccount#balances()} 里那条 0 就消失。
 */
class GoodsAccountTest {

  private static final ActorRef ESTATE = new ActorRef(ActorKind.ESTATE, "farm@0_0");
  private static final HexCoord HEX = new HexCoord(0, 0);
  private static final HexCoord OTHER = new HexCoord(1, 0);
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");

  /** ★ 键是 (owner, hex)：同一个 owner 在两格的库存是**两本账**，不合并。 */
  @Test
  void theAccountKeyIsOwnerAndHex() {
    ActorData data =
        empty()
            .withAccount(new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), Map.of(GRAIN, 100L)))
            .withAccount(new GoodsAccount(new GoodsAccountKey(ESTATE, OTHER), Map.of(GRAIN, 7L)));

    assertThat(data.accounts()).as("★ 跨格**不合并** —— 否则「全境有多少粮」与「这一格有多少粮」就混成一件事了").hasSize(2);
    assertThat(data.accounts().get(new GoodsAccountKey(ESTATE, HEX)).balances().get(GRAIN))
        .isEqualTo(100L);
  }

  /**
   * ★★ **库存是存量**（spec §2.5 L166）：0 余额**保留**（不是删键）。
   *
   * <p>★ 否则「这一格这个人手里还有 0 斤粮」与「这个人根本不在这格」就**分不开**了 —— 而这两件事在阶段 4（产出落 operator）与阶段 6（消费从 receipt
   * 来）含义完全不同。
   */
  @Test
  void zeroBalanceIsKeptNotDropped() {
    GoodsAccount account = new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), Map.of(GRAIN, 0L));

    assertThat(account.balances()).as("★ 0 不被归一成空表").containsEntry(GRAIN, 0L);
    assertThat(empty().withAccount(account).accounts())
        .as("★ 整本 0 余额的账照样在表里（键在 = 这个人在这格有账）")
        .hasSize(1);
  }

  /** ★ 负余额即抛 —— 库存是**存量**，不是可以透支的信用。 */
  @Test
  void rejectsNegativeBalance() {
    assertThatThrownBy(() -> new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), Map.of(GRAIN, -1L)))
        .as("负余额必须当场抛")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 余额表是**防御性拷贝**（外部 Map 之后被改，不许影响已建的账）。 */
  @Test
  void balancesAreCopiedNotAliased() {
    Map<CommodityId, Long> mutable = new HashMap<>(Map.of(GRAIN, 5L, CLOTH, 9L));
    GoodsAccount account = new GoodsAccount(new GoodsAccountKey(ESTATE, HEX), mutable);
    mutable.put(GRAIN, 999L);

    assertThat(account.balances()).as("★ 建完之后改原 Map，账里的数不许跟着变").containsEntry(GRAIN, 5L);
    assertThatThrownBy(() -> account.balances().put(GRAIN, 1L))
        .as("★ 且账自己的表不可变")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** ★ 往返用例的起点：**未激活 + 三张空表**（组件个数按"当下真能编译"的四件写：meta + actors + holdings + accounts）。 */
  private static ActorData empty() {
    return new ActorData(Optional.empty(), Map.of(), Map.of(), Map.of());
  }
}

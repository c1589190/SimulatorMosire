package io.mosire.simos.ledger.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.AccountId;
import io.mosire.simos.economy.api.id.ClaimId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.TransferId;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * 账本模型的**构造期不变量**（增量 2 spec §三 的不变量清单逐条）：`reserved ≤ goods`（逐商品）、 `reservedMoney ≤ money`、`amount ≥
 * 0`，以及同源的几条下界（goods/money 不得为负、`dueDay ≥ 0`）。
 *
 * <p>每条都断言**消息里的字段名**：消息不点名的话，将来某条不变量被误删，测试只会在"抛了"上转绿， 读不出是哪一条失守。
 */
class LedgerModelInvariantsTest {

  private static final AccountId A1 = new AccountId("acc-1");
  private static final ActorRef LOT = new ActorRef(ActorKind.PEOPLE_LOT, "lot-1");
  private static final ActorRef GOV = new ActorRef(ActorKind.GOVERNMENT, "gov-1");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");

  /** ★ spec §三：`reserved ≤ goods`（逐商品）—— 预留多过库存，当场抛。 */
  @Test
  void rejectsReservedExceedingGoods() {
    assertThatThrownBy(() -> new Account(A1, LOT, Map.of(GRAIN, 10L), Map.of(GRAIN, 11L), 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserved")
        .hasMessageContaining("goods");
  }

  /** 同一条不变量的边界：goods 里**没有**这个商品 ⇒ 库存按 0 算，任何正预留都算超。 */
  @Test
  void rejectsReservedForCommodityAbsentFromGoods() {
    assertThatThrownBy(() -> new Account(A1, LOT, Map.of(CLOTH, 10L), Map.of(GRAIN, 1L), 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reserved")
        .hasMessageContaining("goods");
  }

  /** ★ spec §三：`reservedMoney ≤ money`。 */
  @Test
  void rejectsReservedMoneyExceedingMoney() {
    assertThatThrownBy(() -> new Account(A1, LOT, Map.of(), Map.of(), 100L, 101L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reservedMoney")
        .hasMessageContaining("money");
  }

  /** ★ spec §三：`amount ≥ 0`。 */
  @Test
  void rejectsNegativeClaimAmount() {
    assertThatThrownBy(
            () ->
                new Claim(
                    new ClaimId("cl-1"),
                    ClaimKind.LOAN,
                    LOT,
                    GOV,
                    Optional.empty(),
                    -1L,
                    30L,
                    OptionalLong.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("amount");
  }

  /** spec §三：`dueDay ≥ 0`。 */
  @Test
  void rejectsNegativeDueDay() {
    assertThatThrownBy(
            () ->
                new Claim(
                    new ClaimId("cl-1"),
                    ClaimKind.TAX,
                    LOT,
                    GOV,
                    Optional.empty(),
                    1L,
                    -1L,
                    OptionalLong.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dueDay");
  }

  /** spec §三：`goods.collectValues ≥ 0`（库存与转移腿各自判一遍）。 */
  @Test
  void rejectsNegativeGoodsOnAccountAndTransfer() {
    assertThatThrownBy(() -> new Account(A1, LOT, Map.of(GRAIN, -1L), Map.of(), 0L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("goods");
    assertThatThrownBy(
            () ->
                new Transfer(
                    new TransferId("tr-1"), 1L, LOT, GOV, Map.of(GRAIN, -1L), 0L, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("goods");
  }

  /** spec §三：`money ≥ 0`（账户与转移腿各自判一遍）。 */
  @Test
  void rejectsNegativeMoneyOnAccountAndTransfer() {
    assertThatThrownBy(() -> new Account(A1, LOT, Map.of(), Map.of(), -1L, 0L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("money");
    assertThatThrownBy(
            () ->
                new Transfer(new TransferId("tr-1"), 1L, LOT, GOV, Map.of(), -1L, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("money");
  }

  /** 两个 `Optional*` 组件不得为 null：缺席有自己的表达（照 {@code SocialCity.region}）。 */
  @Test
  void rejectsNullOptionalComponents() {
    assertThatThrownBy(
            () ->
                new Claim(
                    new ClaimId("cl-1"),
                    ClaimKind.LOAN,
                    LOT,
                    GOV,
                    null,
                    1L,
                    1L,
                    OptionalLong.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("commodity");
    assertThatThrownBy(() -> new Transfer(new TransferId("tr-1"), 1L, LOT, GOV, Map.of(), 0L, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("settles");
    assertThatThrownBy(() -> new EconomyMeta("m1", 0L, null, "v1", Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("lastClosedDay");
  }
}

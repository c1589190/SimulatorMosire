package io.mosire.simos.actor.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>{@code GoodsAccount} 的逐值判据</b>（spec §2.5 L166"库存是存量"、裁定 R6；P2-A §13.3 起账户主体统一为家户）：
 * 商品余额按 <b>{@link HouseholdId} 家户身份</b>聚合 —— <b>一个家户一本账</b>；<b>0 余额保留</b>（不是删键）；负余额当场抛；
 * 余额表是<b>防御性拷贝</b>。
 *
 * <p>★★ <b>P2-A 迁移（如实记）</b>：旧判据是"同一个 owner 在两格是两本账"；账户键去掉 {@code HexCoord} 之后，"跨格不合并"这件事
 * 在模型里不再存在（家户搬家账自动跟走） ⇒ 本类改为钉"<b>两个家户 = 两本账</b>"。判别力仍然来自键有一段真身份（家户）：键若被归一成单本账/丢掉家户段，
 * 第一条只剩 1 条。
 *
 * <p>★ <b>0 余额保留</b>这条判据不受形状变化影响，一字不动保留。
 */
class GoodsAccountTest {

  private static final HouseholdId PEASANT = new HouseholdId("hh-0_0-rural-poor_peasant");
  private static final HouseholdId LANDLORD = new HouseholdId("hh-1_0-rural-landlord");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");

  /** ★ 键是家户身份：两个家户的库存是**两本账**，不合并。 */
  @Test
  void theAccountKeyIsTheHousehold() {
    ActorData data =
        empty()
            .withAccount(new GoodsAccount(new GoodsAccountKey(PEASANT), Map.of(GRAIN, 100L)))
            .withAccount(new GoodsAccount(new GoodsAccountKey(LANDLORD), Map.of(GRAIN, 7L)));

    assertThat(data.accounts()).as("★ 两个家户不合并 —— 否则「全村有多少粮」与「这一户有多少粮」就混成一件事了").hasSize(2);
    assertThat(data.accounts().get(new GoodsAccountKey(PEASANT)).balances().get(GRAIN))
        .isEqualTo(100L);
  }

  /**
   * ★★ **库存是存量**（spec §2.5 L166）：0 余额**保留**（不是删键）。
   *
   * <p>★ 否则「这个家户手里还有 0 斤粮」与「这个家户根本没有这本账」就**分不开**了 —— 而这两件事在产出落账与消费扣账里含义完全不同。
   */
  @Test
  void zeroBalanceIsKeptNotDropped() {
    GoodsAccount account = new GoodsAccount(new GoodsAccountKey(PEASANT), Map.of(GRAIN, 0L));

    assertThat(account.balances()).as("★ 0 不被归一成空表").containsEntry(GRAIN, 0L);
    assertThat(empty().withAccount(account).accounts())
        .as("★ 整本 0 余额的账照样在表里（键在 = 这个家户有账）")
        .hasSize(1);
  }

  /** ★ 负余额即抛 —— 库存是**存量**，不是可以透支的信用。 */
  @Test
  void rejectsNegativeBalance() {
    assertThatThrownBy(() -> new GoodsAccount(new GoodsAccountKey(PEASANT), Map.of(GRAIN, -1L)))
        .as("负余额必须当场抛")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 余额表是**防御性拷贝**（外部 Map 之后被改，不许影响已建的账）。 */
  @Test
  void balancesAreCopiedNotAliased() {
    Map<CommodityId, Long> mutable = new HashMap<>(Map.of(GRAIN, 5L, CLOTH, 9L));
    GoodsAccount account = new GoodsAccount(new GoodsAccountKey(PEASANT), mutable);
    mutable.put(GRAIN, 999L);

    assertThat(account.balances()).as("★ 建完之后改原 Map，账里的数不许跟着变").containsEntry(GRAIN, 5L);
    assertThatThrownBy(() -> account.balances().put(GRAIN, 1L))
        .as("★ 且账自己的表不可变")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** ★ 键往返（铁律 5 的键要求）：{@code parse(toString())} 必须还原家户身份。 */
  @Test
  void keyRoundTripsThroughCanonicalText() {
    GoodsAccountKey key = new GoodsAccountKey(PEASANT);

    assertThat(key.toString()).isEqualTo(PEASANT.value());
    assertThat(GoodsAccountKey.parse(key.toString())).isEqualTo(key);
    assertThatThrownBy(() -> GoodsAccountKey.parse("  "))
        .as("空白不是身份")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 往返用例的起点：**未激活 + 两张空表**（组件个数按"当下真能编译"的三件写：meta + actors + accounts）。 */
  private static ActorData empty() {
    return new ActorData(Optional.empty(), Map.of(), Map.of());
  }
}

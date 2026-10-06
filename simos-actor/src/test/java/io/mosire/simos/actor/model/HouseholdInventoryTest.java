package io.mosire.simos.actor.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * ★★ <b>{@code HouseholdInventory} 的逐值判据</b>（spec §2.5 L166"库存是存量"、裁定 R6、H4/K15、M1.2；P2-A §13.3
 * 起账户主体统一为家户）：商品 / 货币余额按 <b>{@link HouseholdId} 家户身份</b>聚合 —— <b>一个家户一本账</b>； <b>0
 * 余额保留</b>（不是删键）；负余额当场抛；余额表是<b>防御性拷贝</b>；两张冻结表<b>逐键同款</b>（{@code 0 ≤ 冻结 ≤ 余额}，缺键 = 0，旧档缺表 ⇒ 空表）。
 *
 * <p>★★ <b>文件与类名迁移（如实记）</b>：旧文件 {@code GoodsAccountTest} 测的是已退役的 {@code GoodsAccount} / {@code
 * GoodsAccountKey}（{@code (owner, hex)} 聚合键）。当前模型是 {@code HouseholdInventory} / {@code
 * HouseholdAccountKey}（键 = 家户身份），<b>本类整流迁移到当前类型</b>：旧判据（两本账不合并 / 0 保留 / 负余额拒 / 防御性拷贝 /
 * 键往返）一字保留，并按当前构造期守卫补上货币余额与两张冻结表的判据。
 *
 * <p>★★ <b>P2-A 迁移（如实记）</b>：旧判据是"同一个 owner 在两格是两本账"；账户键去掉 {@code HexCoord} 之后，"跨格不合并"这件事
 * 在模型里不再存在（家户搬家账自动跟走） ⇒ 本类钉"<b>两个家户 = 两本账</b>"。判别力仍然来自键有一段真身份（家户）：键若被归一成单本账/丢掉家户段， 第一条只剩 1 条。
 *
 * <p>★ <b>0 余额保留</b>这条判据不受形状变化影响，一字不动保留。
 */
class HouseholdInventoryTest {

  private static final HouseholdId PEASANT = new HouseholdId("hh-0_0-rural-poor_peasant");
  private static final HouseholdId LANDLORD = new HouseholdId("hh-1_0-rural-landlord");
  private static final CommodityId GRAIN = new CommodityId("grain");
  private static final CommodityId CLOTH = new CommodityId("cloth");
  private static final CurrencyId SILVER = new CurrencyId("silver");

  /** ★ 键是家户身份：两个家户的库存是**两本账**，不合并。 */
  @Test
  void theAccountKeyIsTheHousehold() {
    ActorData data =
        empty()
            .withInventory(
                new HouseholdInventory(new HouseholdAccountKey(PEASANT), Map.of(GRAIN, 100L)))
            .withInventory(
                new HouseholdInventory(new HouseholdAccountKey(LANDLORD), Map.of(GRAIN, 7L)));

    assertThat(data.accounts()).as("★ 两个家户不合并 —— 否则「全村有多少粮」与「这一户有多少粮」就混成一件事了").hasSize(2);
    assertThat(data.accounts().get(new HouseholdAccountKey(PEASANT)).balances().get(GRAIN))
        .isEqualTo(100L);
  }

  /**
   * ★★ **库存是存量**（spec §2.5 L166）：0 余额**保留**（不是删键）。
   *
   * <p>★ 否则「这个家户手里还有 0 斤粮」与「这个家户根本没有这本账」就**分不开**了 —— 而这两件事在产出落账与消费扣账里含义完全不同。
   */
  @Test
  void zeroBalanceIsKeptNotDropped() {
    HouseholdInventory account =
        new HouseholdInventory(new HouseholdAccountKey(PEASANT), Map.of(GRAIN, 0L));

    assertThat(account.balances()).as("★ 0 不被归一成空表").containsEntry(GRAIN, 0L);
    assertThat(empty().withInventory(account).accounts())
        .as("★ 整本 0 余额的账照样在表里（键在 = 这个家户有账）")
        .hasSize(1);
  }

  /** ★ 负商品余额即抛 —— 库存是**存量**，不是可以透支的信用。 */
  @Test
  void rejectsNegativeBalance() {
    assertThatThrownBy(
            () -> new HouseholdInventory(new HouseholdAccountKey(PEASANT), Map.of(GRAIN, -1L)))
        .as("负余额必须当场抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("余额是存量、不得为负");
  }

  /** ★ 负货币余额同样即抛（货币与商品的口径逐条同款；发行/回笼不在本类型）。 */
  @Test
  void rejectsNegativeMoneyBalance() {
    assertThatThrownBy(
            () ->
                new HouseholdInventory(
                    new HouseholdAccountKey(PEASANT),
                    Map.of(),
                    Map.of(SILVER, -1L),
                    Map.of(),
                    Map.of()))
        .as("负货币余额必须当场抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("货币余额是存量、不得为负");
  }

  /** ★ 余额表是**防御性拷贝**（外部 Map 之后被改，不许影响已建的账）。 */
  @Test
  void balancesAreCopiedNotAliased() {
    Map<CommodityId, Long> mutable = new HashMap<>(Map.of(GRAIN, 5L, CLOTH, 9L));
    HouseholdInventory account = new HouseholdInventory(new HouseholdAccountKey(PEASANT), mutable);
    mutable.put(GRAIN, 999L);

    assertThat(account.balances()).as("★ 建完之后改原 Map，账里的数不许跟着变").containsEntry(GRAIN, 5L);
    assertThatThrownBy(() -> account.balances().put(GRAIN, 1L))
        .as("★ 且账自己的表不可变")
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** ★ 货币与两张冻结表**逐条同款**：防御性拷贝 + 冻结在字段赋值处（含冻结额）。 */
  @Test
  void moneyAndFrozenTablesAreCopiedNotAliased() {
    Map<CurrencyId, Long> money = new HashMap<>(Map.of(SILVER, 100L));
    Map<CommodityId, Long> frozenBalances = new HashMap<>(Map.of(GRAIN, 30L));
    Map<CurrencyId, Long> frozenMoney = new HashMap<>(Map.of(SILVER, 20L));
    HouseholdInventory account =
        new HouseholdInventory(
            new HouseholdAccountKey(PEASANT),
            Map.of(GRAIN, 50L),
            money,
            frozenBalances,
            frozenMoney);

    money.put(SILVER, 999L);
    frozenBalances.put(GRAIN, 1L);
    frozenMoney.put(SILVER, 2L);

    assertThat(account.money()).as("★ 建完之后改原 Map，货币余额不许跟着变").containsEntry(SILVER, 100L);
    assertThat(account.frozenBalances()).containsEntry(GRAIN, 30L);
    assertThat(account.frozenMoney()).containsEntry(SILVER, 20L);
    assertThatThrownBy(() -> account.money().put(SILVER, 1L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> account.frozenBalances().put(GRAIN, 1L))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> account.frozenMoney().put(SILVER, 1L))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  /** ★★ 冻结守卫：{@code 0 ≤ 冻结 ≤ 余额}，逐键判 —— 冻结超过余额（含余额表里没有该键）当场抛。 */
  @Test
  void rejectsFrozenAboveBalance() {
    assertThatThrownBy(
            () ->
                new HouseholdInventory(
                    new HouseholdAccountKey(PEASANT),
                    Map.of(GRAIN, 50L),
                    Map.of(),
                    Map.of(GRAIN, 51L),
                    Map.of()))
        .as("冻结 51 > 余额 50 必须抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("冻结额不得超过余额");

    assertThatThrownBy(
            () ->
                new HouseholdInventory(
                    new HouseholdAccountKey(PEASANT),
                    Map.of(GRAIN, 50L),
                    Map.of(),
                    Map.of(CLOTH, 1L),
                    Map.of()))
        .as("★ 缺键 = 0：冻结一个余额表里根本没有的商品 ⇒ 同样抛（占用了不存在的东西）")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("冻结额不得超过余额");
  }

  /** ★★ 货币冻结守卫：与商品**逐条同款**（两张表各判一遍，口径一致）。 */
  @Test
  void rejectsFrozenMoneyAboveMoneyBalance() {
    assertThatThrownBy(
            () ->
                new HouseholdInventory(
                    new HouseholdAccountKey(PEASANT),
                    Map.of(),
                    Map.of(SILVER, 50L),
                    Map.of(),
                    Map.of(SILVER, 51L)))
        .as("货币冻结 51 > 货币余额 50 必须抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("货币冻结额不得超过余额");

    assertThatThrownBy(
            () ->
                new HouseholdInventory(
                    new HouseholdAccountKey(PEASANT),
                    Map.of(),
                    Map.of(SILVER, 50L),
                    Map.of(),
                    Map.of(new CurrencyId("copper"), 1L)))
        .as("★ 缺键 = 0：冻结一个货币表里根本没有的币种 ⇒ 同样抛")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("货币冻结额不得超过余额");
  }

  /**
   * ★ 旧档兼容：M1.2 之前落盘的账没有两张冻结表，Jackson 会绑成 {@code null} ⇒ 收成空表、<b>此处不抛</b>。
   *
   * <p>方向是 fail-closed：旧档没提冻结，就是**没有冻结**。而余额表 {@code null} 仍是坏数据、照样抛（它是这本账的本体）。
   */
  @Test
  void missingFrozenTablesAreTreatedAsEmpty() {
    HouseholdInventory account =
        new HouseholdInventory(
            new HouseholdAccountKey(PEASANT), Map.of(GRAIN, 5L), Map.of(), null, null);

    assertThat(account.frozenBalances()).as("缺表 ⇒ 空表（不是异常，也不是 null）").isEmpty();
    assertThat(account.frozenMoney()).as("缺表 ⇒ 空表（不是异常，也不是 null）").isEmpty();
    assertThatThrownBy(() -> new HouseholdInventory(new HouseholdAccountKey(PEASANT), null))
        .as("余额表是本体：null 仍是坏数据")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("balances 不得为 null");
  }

  /** ★ 键往返（铁律 5 的键要求）：{@code parse(toString())} 必须还原家户身份。 */
  @Test
  void keyRoundTripsThroughCanonicalText() {
    HouseholdAccountKey key = new HouseholdAccountKey(PEASANT);

    assertThat(key.toString()).isEqualTo(PEASANT.value());
    assertThat(HouseholdAccountKey.parse(key.toString())).isEqualTo(key);
    assertThatThrownBy(() -> HouseholdAccountKey.parse("  "))
        .as("空白不是身份")
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** ★ 往返用例的起点：**未激活 + 两张空表**（组件个数按"当下真能编译"的三件写：meta + actors + accounts）。 */
  private static ActorData empty() {
    return new ActorData(Optional.empty(), Map.of(), Map.of());
  }

  /** ★ 保留 {@link LinkedHashMap} 夹具的形状检查：键序即迭代序（字节级往返的前提）。 */
  @Test
  void balancesKeepInsertionOrder() {
    Map<CommodityId, Long> balances = new LinkedHashMap<>();
    balances.put(GRAIN, 1L);
    balances.put(CLOTH, 2L);
    HouseholdInventory account = new HouseholdInventory(new HouseholdAccountKey(PEASANT), balances);

    assertThat(account.balances().keySet()).as("插入序即迭代序").containsExactly(GRAIN, CLOTH);
  }
}

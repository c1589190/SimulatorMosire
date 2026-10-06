package io.mosire.simos.economy.api.stock;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>「从家户库存里扣走一笔」的唯一契约</b>（2026-10-09 用户裁定：经济模块提供通用扣除接口，传入「家户 + 扣除的库存 + 扣除理由」即可扣除；
 * 税收、行政俸禄、军队俸禄都只给政策和 reason，不再自己写扣账机制）。
 *
 * <p>★★ <b>方向由"扣除"这件事本身表达，不用负号</b>：{@code goods}/{@code money} 逐值 <b>&gt; 0</b> —— "−120
 * 粮"这种写法会让"方向"与"数量"揉进一个符号里（同 {@code Transfer} 的禁令）。有符号净增量仍归 {@code actor.AdjustAccounts} 那个
 * <b>裸账目原语</b>，不是本契约。
 *
 * <p>★★ <b>两条语义，二选一，必须显式</b>：
 *
 * <ul>
 *   <li>{@code toHousehold} <b>缺席</b> ⇒ <b>明确 sink</b>：这笔库存被制度性消耗/支付给"无可信对端"的一方（今天的行政俸禄就是这一档）。 ★
 *       <b>不许</b>把 sink 当成"忘了写收款方"：调用方要显式用 {@link #sink} 构造，日志里也记 {@code to=<sink>}；
 *   <li>{@code toHousehold} <b>给出</b> ⇒ <b>原子转移</b>：扣减与收款在同一批里一次落账（税：家户 → 政府家户）。
 * </ul>
 *
 * <p>★★ <b>不变量（构造期一律当场抛，fail-closed）</b>：
 *
 * <ol>
 *   <li>{@code household} / {@code reason} / {@code toHousehold}（Optional 本身）非 null；
 *   <li>{@code goods} / {@code money} 非 null（没有那一腿就给<b>空 map</b>，不许 null）；键与值非 null、且逐值 <b>&gt;
 *       0</b>（扣除量是正数； {@code 0} 不是一条发生额 —— 请删键）；
 *   <li>{@code goods} 与 {@code money} <b>至少一个非空</b>（两个都空的"扣除"没有任何动作）；
 *   <li>{@code toHousehold} 非空时不得等于 {@code household}（自己转给自己不是发生额，是坏数据）；
 *   <li>{@code detail} 可缺省（{@code null} ⇒ {@code ""}）；它只进审计与日志，<b>不参与任何判定</b>，也<b>不</b>是第二条 reason。
 * </ol>
 *
 * <p>★★ <b>为什么{@code toHousehold}用 {@link Optional} 而不是"可空字段"</b>：sink
 * 与"忘了填"在账上完全同形，而它们是完全不同的两件事（本仓最忌的 "静默丢字段"）。类型层面把"有收款方"与"没有收款方"分开 ⇒ 调用方必须表态。
 *
 * <p>★ <b>两张表都保序不可变</b>（{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，<b>绝不用 {@code
 * Map.copyOf}</b> —— 它的迭代序不是内容的纯函数）；冻结写在字段赋值处。
 *
 * <p>★ <b>它只描述"扣什么"，不描述"扣不扣得动"</b>：家户/账户是否存在、余额是否够、是否侵占冻结，由持账的一方 （actor 切片或 {@code
 * AccountSession}）在落账前判 —— 契约层不重复实现任何余额语义（铁律 3）。
 *
 * @param household 被扣的家户（账户主体；不得为 null）
 * @param goods 扣走的商品（毫单位；值 &gt; 0；没有这一腿给空 map）
 * @param money 扣走的货币（最小币值；值 &gt; 0；没有这一腿给空 map）
 * @param reason 这笔扣除的制度原因（{@link DeductionReason} 封闭词表）
 * @param detail 审计自由文本（可缺省；不进任何判定）
 * @param toHousehold 收款家户；<b>缺席 = 明确 sink</b>，给出 = 原子转移
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "compact constructor 已做防御性拷贝并冻结；SpotBugs 不跨辅助方法识别")
public record HouseholdStockDeduction(
    HouseholdId household,
    Map<CommodityId, Long> goods,
    Map<CurrencyId, Long> money,
    DeductionReason reason,
    String detail,
    Optional<HouseholdId> toHousehold) {

  public HouseholdStockDeduction {
    if (household == null) {
      throw new IllegalArgumentException("HouseholdStockDeduction.household 不得为 null");
    }
    if (reason == null) {
      throw new IllegalArgumentException(
          "HouseholdStockDeduction.reason 不得为 null（这笔扣除是什么制度造成的必须显式）");
    }
    if (toHousehold == null) {
      throw new IllegalArgumentException(
          "HouseholdStockDeduction.toHousehold 不得为 null（没有收款方请给 Optional.empty()）");
    }
    goods = positiveGoods(goods);
    money = positiveMoney(money);
    if (goods.isEmpty() && money.isEmpty()) {
      throw new IllegalArgumentException(
          "HouseholdStockDeduction 的 goods/money 至少一个必须非空（household=" + household + "）");
    }
    if (detail == null) {
      detail = "";
    }
    if (toHousehold.isPresent() && toHousehold.get().equals(household)) {
      throw new IllegalArgumentException(
          "HouseholdStockDeduction 的收款家户不得等于被扣家户（自转不是一条发生额）: " + household);
    }
  }

  /** ★ sink 构造（明确"没有收款方"）：行政俸禄这种"付出即消失"的制度性支出口。 */
  public static HouseholdStockDeduction sink(
      HouseholdId household,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money,
      DeductionReason reason,
      String detail) {
    return new HouseholdStockDeduction(household, goods, money, reason, detail, Optional.empty());
  }

  /** ★ 原子转移构造：扣减 + 收款在同一批里落账（税：家户 → 政府家户）。 */
  public static HouseholdStockDeduction transfer(
      HouseholdId household,
      HouseholdId toHousehold,
      Map<CommodityId, Long> goods,
      Map<CurrencyId, Long> money,
      DeductionReason reason,
      String detail) {
    Objects.requireNonNull(toHousehold, "toHousehold（原子转移必须显式给收款方）");
    return new HouseholdStockDeduction(
        household, goods, money, reason, detail, Optional.of(toHousehold));
  }

  /** {@code true} = 明确 sink（没有收款方）；日志与审计据此显式记 {@code to=<sink>}。 */
  public boolean isSink() {
    return toHousehold.isEmpty();
  }

  private static Map<CommodityId, Long> positiveGoods(Map<CommodityId, Long> goods) {
    if (goods == null) {
      throw new IllegalArgumentException("HouseholdStockDeduction.goods 不得为 null（没有商品腿请给空 map）");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : goods.entrySet()) {
      requirePositive(entry.getKey(), entry.getValue(), "商品");
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只看得到这里）
  }

  private static Map<CurrencyId, Long> positiveMoney(Map<CurrencyId, Long> money) {
    if (money == null) {
      throw new IllegalArgumentException("HouseholdStockDeduction.money 不得为 null（没有货币腿请给空 map）");
    }
    Map<CurrencyId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : money.entrySet()) {
      requirePositive(entry.getKey(), entry.getValue(), "货币");
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }

  private static void requirePositive(Object asset, Long amount, String dimension) {
    if (asset == null || amount == null) {
      throw new IllegalArgumentException(
          "HouseholdStockDeduction 的" + dimension + "表的键与值都不得为 null: " + asset);
    }
    if (amount <= 0L) {
      throw new IllegalArgumentException(
          "HouseholdStockDeduction 的"
              + dimension
              + "扣除量必须 > 0（0 不是一条发生额，请删键）: "
              + asset
              + "="
              + amount);
    }
  }
}

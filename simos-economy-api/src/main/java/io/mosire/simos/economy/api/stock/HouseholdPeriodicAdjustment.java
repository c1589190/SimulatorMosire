package io.mosire.simos.economy.api.stock;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * ★★ <b>一条「周期家户库存扣增」规则</b>（P4a；2026-10-14 用户确认路线 C+PARTIAL）：规则只描述<b>每个周期请求从哪个家户扣多少</b>，
 * 本身不落账户；是否到期、能扣多少由 app 的无状态执行器按绝对世界日现算。
 *
 * <pre>
 * HouseholdPeriodicAdjustment(
 *     id,                 // 稳定身份；同时是 EconomyData.periodicAdjustments 的键
 *     payer,              // 必填：被扣方
 *     payee,              // 空 = sink；给出 = 原子转移给收款家户
 *     goodsPerCycle,      // 可空；逐值 &gt; 0，键 = 商品 id
 *     moneyPerCycle,      // 可空；逐值 &gt; 0，键 = 币种 id
 *     reason,             // 封闭词表（P4 用 MILITARY_SALARY）
 *     periodDays,         // &gt; 0
 *     phaseDay,           // [0, periodDays)
 *     startsOnDay,        // &gt;= 0
 *     expiresOnDay,       // 空 = 永久；给了 &gt;= startsOnDay
 *     policySource)       // 非空白审计串（如 army:&lt;unitId&gt; / gm:&lt;id&gt;）
 * </pre>
 *
 * <p>★★ <b>无 lastPaidTick</b>：到期判据只依赖规则字段与绝对世界日 ⇒ 同一份状态在 1×N 与 N×1 两条推进路径下必然
 * 同日到期（执行位置见 {@code app.PopulationEconomyTimeParticipant}，判据见 executor）。
 *
 * <p>★★ <b>构造期不变量（坏数据 fail-closed）</b>：
 *
 * <ol>
 *   <li>{@code id}/{@code payer}/{@code payee}/{@code reason}/{@code policySource} 非 null；
 *   <li>{@code payee} 给出时不得等于 {@code payer}（自转不是一条发生额）；
 *   <li>{@code goodsPerCycle}/{@code moneyPerCycle} 的键与值非 null、逐值 &gt; 0；两张表至少一腿非空；
 *   <li>{@code periodDays &gt; 0}、{@code phaseDay ∈ [0, periodDays)}、{@code startsOnDay ≥ 0}；
 *   <li>{@code expiresOnDay} 空 = 永久；非空必须 {@code ≥ startsOnDay}。
 * </ol>
 *
 * <p>★ <b>两张表都保序不可变</b>（{@code LinkedHashMap} + {@code Collections.unmodifiableMap}，绝不用 {@code
 * Map.copyOf}——它的迭代序不是内容的纯函数）；冻结写在字段赋值处。
 */
public record HouseholdPeriodicAdjustment(
    PeriodicHouseholdAdjustmentId id,
    HouseholdId payer,
    Optional<HouseholdId> payee,
    Map<CommodityId, Long> goodsPerCycle,
    Map<CurrencyId, Long> moneyPerCycle,
    DeductionReason reason,
    long periodDays,
    long phaseDay,
    long startsOnDay,
    OptionalLong expiresOnDay,
    String policySource) {

  public HouseholdPeriodicAdjustment {
    if (id == null) {
      throw new IllegalArgumentException("HouseholdPeriodicAdjustment.id 不得为 null");
    }
    if (payer == null) {
      throw new IllegalArgumentException("HouseholdPeriodicAdjustment.payer 不得为 null");
    }
    if (payee == null) {
      throw new IllegalArgumentException(
          "HouseholdPeriodicAdjustment.payee 不得为 null（没有收款方请给 Optional.empty()）");
    }
    if (payee.isPresent() && payee.get().equals(payer)) {
      throw new IllegalArgumentException(
          "HouseholdPeriodicAdjustment 的 payer/payee 不得相同（自转不是一条发生额）: " + payer);
    }
    goodsPerCycle = positiveGoods(goodsPerCycle);
    moneyPerCycle = positiveMoney(moneyPerCycle);
    if (goodsPerCycle.isEmpty() && moneyPerCycle.isEmpty()) {
      throw new IllegalArgumentException(
          "HouseholdPeriodicAdjustment 的 goodsPerCycle/moneyPerCycle 至少一腿非空: " + id);
    }
    if (reason == null) {
      throw new IllegalArgumentException("HouseholdPeriodicAdjustment.reason 不得为 null");
    }
    if (periodDays <= 0L) {
      throw new IllegalArgumentException(
          "HouseholdPeriodicAdjustment.periodDays 必须 > 0: " + periodDays);
    }
    if (phaseDay < 0L || phaseDay >= periodDays) {
      throw new IllegalArgumentException(
          "HouseholdPeriodicAdjustment.phaseDay 必须 ∈ [0, periodDays): phaseDay="
              + phaseDay
              + "，periodDays="
              + periodDays);
    }
    if (startsOnDay < 0L) {
      throw new IllegalArgumentException(
          "HouseholdPeriodicAdjustment.startsOnDay 不得为负: " + startsOnDay);
    }
    // ★ 旧档/手写 JSON 缺键时 Jackson 可能把 OptionalLong 位置绑成 null ⇒ 统一收成 empty（同 DebtTerms 的口径）。
    if (expiresOnDay == null) {
      expiresOnDay = OptionalLong.empty();
    }
    if (expiresOnDay.isPresent() && expiresOnDay.getAsLong() < startsOnDay) {
      throw new IllegalArgumentException(
          "HouseholdPeriodicAdjustment.expiresOnDay 必须 ≥ startsOnDay: expiresOnDay="
              + expiresOnDay.getAsLong()
              + "，startsOnDay="
              + startsOnDay);
    }
    if (policySource == null || policySource.isBlank()) {
      throw new IllegalArgumentException("HouseholdPeriodicAdjustment.policySource 不得为空白");
    }
  }

  /**
   * {@code true} = 明确 sink（没有收款方）；执行日志将记 {@code to=<sink>}。
   *
   * <p>★ 方法名刻意<b>不用 {@code is} 前缀</b>：Jackson 会把 {@code isXxx()} 当派生布尔属性写进线格式，而
   * Timeline 的第四台 mapper 装不上本模块的 mixin；用 {@code sink()} 则所有 mapper 都只序列化 record 组件，
   * 不会多出未知字段。
   */
  public boolean sink() {
    return payee.isEmpty();
  }

  private static Map<CommodityId, Long> positiveGoods(Map<CommodityId, Long> goods) {
    if (goods == null) {
      throw new IllegalArgumentException(
          "HouseholdPeriodicAdjustment.goodsPerCycle 不得为 null（没有商品腿请给空 map）");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : goods.entrySet()) {
      requirePositive(entry.getKey(), entry.getValue(), "商品");
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  private static Map<CurrencyId, Long> positiveMoney(Map<CurrencyId, Long> money) {
    if (money == null) {
      throw new IllegalArgumentException(
          "HouseholdPeriodicAdjustment.moneyPerCycle 不得为 null（没有货币腿请给空 map）");
    }
    Map<CurrencyId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : money.entrySet()) {
      requirePositive(entry.getKey(), entry.getValue(), "货币");
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  private static void requirePositive(Object asset, Long amount, String dimension) {
    if (asset == null || amount == null) {
      throw new IllegalArgumentException(
          "HouseholdPeriodicAdjustment 的" + dimension + "表的键与值都不得为 null: " + asset);
    }
    if (amount <= 0L) {
      throw new IllegalArgumentException(
          "HouseholdPeriodicAdjustment 的"
              + dimension
              + "每周期请求量必须 > 0（0 不是一条规则腿，请删键）: "
              + asset
              + "="
              + amount);
    }
  }
}

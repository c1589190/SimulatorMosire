package io.mosire.simos.gov;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * ★★ <b>R2：一个 GOV 的口岸管制政策（逐政府 × 逐商品 / 逐币种的限制强度 s，‰）</b>（2026-10-09 口岸设计书 §4.2/§4.3/§5 I-P1）。
 *
 * <pre>
 * GovPortPolicy(commodityRestrictionPerMille: Map&lt;CommodityId, Long&gt;,
 *               currencyRestrictionPerMille:  Map&lt;CurrencyId,  Long&gt;)
 * </pre>
 *
 * <p>★★ <b>为什么政策载体落成 gov 的状态组件（而不是"注入式政策表"）</b>——三条理由，按重要性排：
 *
 * <ol>
 *   <li><b>铁律 2 只允许一条写入口</b>：{@code Command → ChangeSet → Revision}。落成 {@link GovState} 的组件后，写侧自然得到
 *       "命令 handler → {@link io.mosire.simos.gov.change.GovChangeSet} → revision"的完整链路、旧档兼容（缺键 ⇒ 空表
 *       = 无政策）与 往返不变式；注入式政策表则<b>没有持久化</b>，一次存档/回放/分支切换就会丢掉"谁对什么设了多严"，重放语义无法成立 （本仓在 F/F2/D2/R1
 *       各批都按"未设 ⇒ 逐值不变"的<b>状态</b>口径收口，不引入第二套瞬态权威）。
 *   <li><b>它是"法律规定"而不是"行为"</b>：设计书 §4.5 把口岸/禁运放在<b>法律规定层</b>（GOV 决策人 → 经济模块的市场区机制）；
 *       法律规定必须可持久、可审计、可改（{@code gov.SetPortPolicy}），与 I-P6 禁止的"政府经济行为的政策层"（补贴/官营）不是一回事。
 *   <li><b>逐政府维护</b>（设计书 §1.3-2 用户原话："每个政府都可以维护自己控制的口岸的口岸政策"）：身份就是 {@link GovState#portPolicies()}
 *       的<b>键</b>（{@code UnitId}），内容不内嵌第二份身份（照 {@link GovAdministrationPlan} 的形制）。
 * </ol>
 *
 * <p>★★ <b>缺键 = 不限制（s = 0）</b>（I-P1；用户 2026-10-09「肯定0啊」）：
 *
 * <ul>
 *   <li>{@link GovState#portPolicyOrDefault(io.mosire.simos.unit.UnitId)} 对没有该 GOV 的行返回 {@link
 *       #empty()}；
 *   <li>{@link #restrictionOfCommodity} / {@link #restrictionOfCurrency} 对表里没有的类返回 {@code 0}；
 *   <li>★ <b>显式 0 与未设逐值同义</b>（都读作"不限制"）——OR 规则只读<b>有效值</b>，不做"设置过/没设置过"的区分。
 * </ul>
 *
 * <p>★ <b>量纲与不封顶</b>：两个表的值都是 per-mille（‰）。{@code 1000} = "管制强度拉满"（在总效率里把该接触面完全关掉）；<b>没有上界</b> （用户
 * 2026-10-23「都不封顶」）。负值是<b>非法政策</b> ⇒ 构造期具名拒（N1 负向判据）。
 *
 * <p>★ <b>保序不可变</b>：两张表都 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap}
 * 冻结，<b>不用</b> {@code Map.copyOf}（迭代序不是内容的纯函数，字节级往返会因此不成立）。
 *
 * <p>★ <b>未知商品/币种不在这里判</b>：gov 模块编译期看不见经济词表（模块边界：gov 只许依赖 economy-api 的 ID 契约）。"这个类是不是世界上
 * 存在的商品/币种"的判据在组合根（{@code PortRegimeBridge}），那里对未知类<b>fail-closed 具名拒</b>（N1），不静默忽略。
 *
 * @param commodityRestrictionPerMille 商品 → 限制强度（‰；≥ 0，不封顶；缺键 = 0 = 不限制；保序不可变）
 * @param currencyRestrictionPerMille 币种 → 限制强度（‰；≥ 0，不封顶；缺键 = 0 = 不限制；保序不可变）
 */
public record GovPortPolicy(
    Map<CommodityId, Long> commodityRestrictionPerMille,
    Map<CurrencyId, Long> currencyRestrictionPerMille) {

  /**
   * ★★ <b>返回防御性副本</b>（修 SpotBugs {@code EI_EXPOSE_REP}）：record 的自动访问器会把内部表直接交出去。 ★ <b>逐层 {@code
   * LinkedHashMap} + {@code unmodifiableMap}</b>（不用 {@code Map.copyOf}）： 迭代序必须是内容的纯函数（字节级往返依赖它）。
   */
  @Override
  public Map<CommodityId, Long> commodityRestrictionPerMille() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(commodityRestrictionPerMille));
  }

  /** ★★ 同上（防御性副本；见 {@link #commodityRestrictionPerMille()} 的注）。 */
  @Override
  public Map<CurrencyId, Long> currencyRestrictionPerMille() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(currencyRestrictionPerMille));
  }

  /** 不限制（‰）：缺键与显式 0 都读作它。 */
  public static final long RESTRICTION_NONE_PER_MILLE = 0L;

  /** 管制强度拉满（‰）：在总效率里把该接触面完全关掉（{@code openness = 1000 − s×e÷1000}）。 */
  public static final long RESTRICTION_FULL_PER_MILLE = 1000L;

  public GovPortPolicy {
    commodityRestrictionPerMille =
        freezeCommodities(commodityRestrictionPerMille, "commodityRestrictionPerMille");
    currencyRestrictionPerMille =
        freezeCurrencies(currencyRestrictionPerMille, "currencyRestrictionPerMille");
  }

  /** 空政策 = 什么都不限制（缺键 GOV 的默认；旧档缺该组件键时的读法）。 */
  public static GovPortPolicy empty() {
    return new GovPortPolicy(Map.of(), Map.of());
  }

  /** 该 GOV 对某商品的限制强度（‰）；缺键 ⇒ {@link #RESTRICTION_NONE_PER_MILLE} = 0 = 不限制。 */
  public long restrictionOfCommodity(CommodityId commodity) {
    Objects.requireNonNull(commodity, "commodity");
    Long value = commodityRestrictionPerMille.get(commodity);
    return value == null ? RESTRICTION_NONE_PER_MILLE : value;
  }

  /** 该 GOV 对某币种的限制强度（‰）；缺键 ⇒ 0 = 不限制。 */
  public long restrictionOfCurrency(CurrencyId currency) {
    Objects.requireNonNull(currency, "currency");
    Long value = currencyRestrictionPerMille.get(currency);
    return value == null ? RESTRICTION_NONE_PER_MILLE : value;
  }

  /**
   * 某商品的限制强度（‰）—— <b>只有显式设过才给值</b>（{@code OptionalLong.empty()} = 未设 ⇒ 调用方按 0 处理）。
   *
   * <p>它与 {@link #restrictionOfCommodity} 的差别只在"要不要区分未设/显式 0"；OR 规则用不到这个区分（两者都放行）， 但读数/日志要能说清
   * "这条是我设的 0 还是我没设"。
   */
  public OptionalLong explicitCommodityRestriction(CommodityId commodity) {
    Objects.requireNonNull(commodity, "commodity");
    Long value = commodityRestrictionPerMille.get(commodity);
    return value == null ? OptionalLong.empty() : OptionalLong.of(value);
  }

  /** 某币种的限制强度（‰）—— 只有显式设过才给值（口径同 {@link #explicitCommodityRestriction}）。 */
  public OptionalLong explicitCurrencyRestriction(CurrencyId currency) {
    Objects.requireNonNull(currency, "currency");
    Long value = currencyRestrictionPerMille.get(currency);
    return value == null ? OptionalLong.empty() : OptionalLong.of(value);
  }

  /** 是否一条限制都没设（两表全空 ⇒ {@code true}）。 */
  public boolean isEmpty() {
    return commodityRestrictionPerMille.isEmpty() && currencyRestrictionPerMille.isEmpty();
  }

  /** 设过的类的条数（商品 + 币种；只进日志/读数）。 */
  public int definedClassCount() {
    return commodityRestrictionPerMille.size() + currencyRestrictionPerMille.size();
  }

  private static Map<CommodityId, Long> freezeCommodities(
      Map<CommodityId, Long> table, String field) {
    if (table == null) {
      throw new IllegalArgumentException("GovPortPolicy." + field + " 不得为 null（不限制用 Map.of()）");
    }
    Map<CommodityId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : table.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("GovPortPolicy." + field + " 的键与值都不得为 null");
      }
      requireNonNegative(entry.getValue(), field + "[" + entry.getKey().value() + "]");
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  private static Map<CurrencyId, Long> freezeCurrencies(Map<CurrencyId, Long> table, String field) {
    if (table == null) {
      throw new IllegalArgumentException("GovPortPolicy." + field + " 不得为 null（不限制用 Map.of()）");
    }
    Map<CurrencyId, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> entry : table.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("GovPortPolicy." + field + " 的键与值都不得为 null");
      }
      requireNonNegative(entry.getValue(), field + "[" + entry.getKey().value() + "]");
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  /** 非法政策（负强度）⇒ 具名拒（N1）；上界不设（用户 2026-10-23「都不封顶」）。 */
  private static void requireNonNegative(long value, String field) {
    if (value < 0L) {
      throw new IllegalArgumentException("口岸限制强度不得为负（非法政策）: " + field + " = " + value);
    }
  }
}

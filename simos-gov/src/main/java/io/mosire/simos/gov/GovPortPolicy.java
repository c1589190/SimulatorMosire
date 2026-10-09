package io.mosire.simos.gov;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.PortDirection;
import io.mosire.simos.economy.api.market.PortRule;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>R2/P-T1a：一个 GOV 的口岸管制政策（逐政府 × 逐商品 / 逐币种的<b>四元组</b>规则）</b> （2026-10-09 口岸设计书 §4.2/§4.3/§5
 * I-P1；2026-10-10 追加裁定 3 §12 + 冻结口径 T-5）。
 *
 * <pre>
 * GovPortPolicy(commodityRules: Map&lt;CommodityId, PortRule&gt;,
 *               currencyRules:  Map&lt;CurrencyId,  PortRule&gt;)
 *
 * PortRule = 入口限制‰ / 出口限制‰ / 入口税 / 出口税        （见 economy-api 的 {@link PortRule}）
 * </pre>
 *
 * <p>★★ <b>本批（P-T1a）改了什么、为什么必须改</b>：本类型此前是"每类一个限制"（{@code commodityRestrictionPerMille} / {@code
 * currencyRestrictionPerMille}，各一张 {@code id → ‰} 表）—— 它<b>表达不出方向</b>，也<b>装不下税</b>。用户 2026-10-10
 * 连着两条裁定把它推翻：①「出入都设规则拦，分别按口岸效率算…… 都需要两边都过才能跨区」；② 冻结口径 T-5「政策形状 = 每类四个数：入口限制‰ / 出口限制‰ / 入口税 / 出口税」；
 * ③「规则可以灵活，从量从价都行」。⇒ 形状直接重建为"id → {@link PortRule}"（<b>不做旧键兼容</b>： 用户
 * 2026-10-10「旧设计和数据类型直接重建不用留」／AGENTS §一.11）。
 *
 * <p>★★ <b>为什么政策载体落成 gov 的状态组件（而不是"注入式政策表"）</b>——三条理由，按重要性排：
 *
 * <ol>
 *   <li><b>铁律 2 只允许一条写入口</b>：{@code Command → ChangeSet → Revision}。落成 {@link GovState} 的组件后，写侧自然得到
 *       "命令 handler → {@link io.mosire.simos.gov.change.GovChangeSet} → revision"的完整链路、缺键 ⇒ 空表（=
 *       无政策） 与往返不变式；注入式政策表则<b>没有持久化</b>，一次存档/回放/分支切换就会丢掉"谁对什么设了多严"，重放语义无法成立。
 *   <li><b>它是"法律规定"而不是"行为"</b>：设计书 §4.5 把口岸/禁运放在<b>法律规定层</b>（GOV 决策人 → 经济模块的市场区机制）；
 *       法律规定必须可持久、可审计、可改（{@code gov.SetPortPolicy}），与 I-P6 禁止的"政府经济行为的政策层"（补贴/官营）不是一回事。
 *   <li><b>逐政府维护</b>（设计书 §1.3-2 用户原话："每个政府都可以维护自己控制的口岸的口岸政策"）：身份就是 {@link GovState#portPolicies()}
 *       的<b>键</b>（{@code UnitId}），内容不内嵌第二份身份。
 * </ol>
 *
 * <p>★★ <b>缺键 = 不限制 + 不收税</b>（I-P1；用户 2026-10-09「肯定0啊」）：
 *
 * <ul>
 *   <li>{@link GovState#portPolicyOrDefault(io.mosire.simos.unit.UnitId)} 对没有该 GOV 的行返回 {@link
 *       #empty()}；
 *   <li>{@link #ruleOfCommodity} / {@link #ruleOfCurrency} 对表里没有的类返回 {@link
 *       PortRule#unrestricted()}；
 *   <li>★ <b>显式 0 与未设逐值同义</b>（都读作"不限制/不收税"）——OR 规则与税都只读<b>有效值</b>，不做"设置过/没设置过"的区分。
 * </ul>
 *
 * <p>★ <b>保序不可变</b>：两张表都 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap}
 * 冻结，<b>不用</b> {@code Map.copyOf}（迭代序不是内容的纯函数，字节级往返会因此不成立）。
 *
 * <p>★ <b>未知商品/币种不在这里判</b>：gov 模块编译期看不见经济词表（模块边界：gov 只许依赖 economy-api 的 ID 契约）。"这个类是不是世界上
 * 存在的商品/币种"的判据在组合根（{@code GovPortPolicyGuard} 写前拒 + {@code PortRegimeBridge} 折算期
 * fail-closed），不静默忽略。
 *
 * @param commodityRules 商品 → 四元组规则（缺键 = 不限制/不收税；保序不可变）
 * @param currencyRules 币种 → 四元组规则（缺键 = 不限制/不收税；保序不可变）
 */
public record GovPortPolicy(
    Map<CommodityId, PortRule> commodityRules, Map<CurrencyId, PortRule> currencyRules) {

  /**
   * ★★ <b>返回防御性副本</b>（修 SpotBugs {@code EI_EXPOSE_REP}）：record 的自动访问器会把内部表直接交出去。 ★ <b>逐层 {@code
   * LinkedHashMap} + {@code unmodifiableMap}</b>（不用 {@code Map.copyOf}）： 迭代序必须是内容的纯函数（字节级往返依赖它）。
   */
  @Override
  public Map<CommodityId, PortRule> commodityRules() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(commodityRules));
  }

  /** ★★ 同上（防御性副本；见 {@link #commodityRules()} 的注）。 */
  @Override
  public Map<CurrencyId, PortRule> currencyRules() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(currencyRules));
  }

  public GovPortPolicy {
    commodityRules = freezeCommodities(commodityRules, "commodityRules");
    currencyRules = freezeCurrencies(currencyRules, "currencyRules");
  }

  /** 空政策 = 什么都不限制、什么都不收（缺键 GOV 的默认；缺该组件键时的读法）。 */
  public static GovPortPolicy empty() {
    return new GovPortPolicy(Map.of(), Map.of());
  }

  /** 该 GOV 对某商品的规则；缺键 ⇒ {@link PortRule#unrestricted()}（不限制、不收税）。 */
  public PortRule ruleOfCommodity(CommodityId commodity) {
    Objects.requireNonNull(commodity, "commodity");
    PortRule rule = commodityRules.get(commodity);
    return rule == null ? PortRule.unrestricted() : rule;
  }

  /** 该 GOV 对某币种的规则；缺键 ⇒ {@link PortRule#unrestricted()}。 */
  public PortRule ruleOfCurrency(CurrencyId currency) {
    Objects.requireNonNull(currency, "currency");
    PortRule rule = currencyRules.get(currency);
    return rule == null ? PortRule.unrestricted() : rule;
  }

  /**
   * 某商品的规则 —— <b>只有显式设过才给值</b>（{@code Optional.empty()} = 未设 ⇒ 调用方按 {@link PortRule#unrestricted()}
   * 处理）。
   *
   * <p>它与 {@link #ruleOfCommodity} 的差别只在"要不要区分未设/显式空规则"；OR 规则与税都用不到这个区分（两者都放行/都不收），
   * 但读数/日志要能说清"这条是我设的全 0 还是我没设"。
   */
  public Optional<PortRule> explicitCommodityRule(CommodityId commodity) {
    Objects.requireNonNull(commodity, "commodity");
    return Optional.ofNullable(commodityRules.get(commodity));
  }

  /** 某币种的规则 —— 只有显式设过才给值（口径同 {@link #explicitCommodityRule}）。 */
  public Optional<PortRule> explicitCurrencyRule(CurrencyId currency) {
    Objects.requireNonNull(currency, "currency");
    return Optional.ofNullable(currencyRules.get(currency));
  }

  /** 某类某方向的限制强度（‰）的便利入口（商品/币种各自走对应表；缺键 ⇒ 0 = 不限制）。 */
  public long restrictionOf(PortDirection direction, CommodityId commodity) {
    return ruleOfCommodity(commodity).restriction(direction);
  }

  /** 某币种某方向的限制强度（‰）的便利入口。 */
  public long restrictionOf(PortDirection direction, CurrencyId currency) {
    return ruleOfCurrency(currency).restriction(direction);
  }

  /**
   * 是否一条规则都没设（两表全空 ⇒ {@code true}）。
   *
   * <p>★ <b>名字刻意不用 {@code isEmpty()}</b>：本类型是<b>持久状态</b>（{@code GovState.portPolicies} ⇒ 快照/变更集
   * JSON）， getter 形态的方法名会被 Jackson 内省成属性 {@code "empty"} 写进线格式，而读侧 {@code
   * FAIL_ON_UNKNOWN_PROPERTIES} 严格 ⇒ 写出来的档自己读不回（手工往返实测复现）。旧形状的 {@code isEmpty()} 是同一颗地雷，本批顺手拆掉。
   */
  public boolean noRules() {
    return commodityRules.isEmpty() && currencyRules.isEmpty();
  }

  /** 设过的类的条数（商品 + 币种；只进日志/读数）。 */
  public int definedClassCount() {
    return commodityRules.size() + currencyRules.size();
  }

  /** 真的会拦货/拦钱的类数（任一侧限制 ≠ 0；只进日志/读数）。 */
  public int restrictedClassCount() {
    int count = 0;
    for (PortRule rule : commodityRules.values()) {
      if (rule.hasRestriction()) {
        count++;
      }
    }
    for (PortRule rule : currencyRules.values()) {
      if (rule.hasRestriction()) {
        count++;
      }
    }
    return count;
  }

  /**
   * 设了<b>真会收</b>的税的类数（只进日志/读数）。
   *
   * <p>★ <b>本批（P-T1a）它只进日志</b>：过境税真收款是 P-T1b（见 {@link PortRule} 的类注）；本计数存在的意义是"GM
   * 设了税，日志里看得见它被记下了"，不让配置静默消失。
   */
  public int taxedClassCount() {
    int count = 0;
    for (PortRule rule : commodityRules.values()) {
      if (rule.hasEffectiveTax()) {
        count++;
      }
    }
    for (PortRule rule : currencyRules.values()) {
      if (rule.hasEffectiveTax()) {
        count++;
      }
    }
    return count;
  }

  private static Map<CommodityId, PortRule> freezeCommodities(
      Map<CommodityId, PortRule> table, String field) {
    if (table == null) {
      throw new IllegalArgumentException("GovPortPolicy." + field + " 不得为 null（什么都不设给 Map.of()）");
    }
    Map<CommodityId, PortRule> copy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, PortRule> entry : table.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "GovPortPolicy." + field + " 的键与值都不得为 null（不设规则给 PortRule.unrestricted()）");
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  private static Map<CurrencyId, PortRule> freezeCurrencies(
      Map<CurrencyId, PortRule> table, String field) {
    if (table == null) {
      throw new IllegalArgumentException("GovPortPolicy." + field + " 不得为 null（什么都不设给 Map.of()）");
    }
    Map<CurrencyId, PortRule> copy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, PortRule> entry : table.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "GovPortPolicy." + field + " 的键与值都不得为 null（不设规则给 PortRule.unrestricted()）");
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }
}

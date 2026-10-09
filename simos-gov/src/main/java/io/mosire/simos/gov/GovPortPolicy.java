package io.mosire.simos.gov;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.market.MarketOrderKind;
import io.mosire.simos.economy.api.market.PortDirection;
import io.mosire.simos.economy.api.market.PortRule;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>R2/P-T1a/P-T1e：一个 GOV 的口岸管制政策（逐政府 × 逐商品 / 逐（币种 × 挂单类型）的<b>四元组</b>规则）</b> （2026-10-09 口岸设计书
 * §4.2/§4.3/§5 I-P1；2026-10-10 追加裁定 3 §12 + 冻结口径 T-5 + 追加裁定 5 §14）。
 *
 * <pre>
 * GovPortPolicy(commodityRules: Map&lt;CommodityId, PortRule&gt;,
 *               currencyRules:  Map&lt;CurrencyId, Map&lt;MarketOrderKind, PortRule&gt;&gt;,
 *               marketControl:  boolean)                    ← ★ P-T1d："政府要求管控市场"
 *
 * PortRule = 入口限制‰ / 出口限制‰ / 入口税 / 出口税        （见 economy-api 的 {@link PortRule}）
 * </pre>
 *
 * <p>★★ <b>本批（P-T1e）改了什么、为什么必须改</b>：币种表此前是"币种 → 四元组"（P-T1a）—— 它<b>装不下挂单类型</b>，
 * 于是用户给的规则示例「禁止本市场区货币被<b>外国借贷</b>（借贷走的也是市场挂单）」<b>表达不出来</b> （设计书 §14.3-4：过滤的键不能只有币种，还要能带挂单类型）。⇒
 * 币种表加一层 {@link MarketOrderKind} 键（"币种 → 类型 → 四元组"）；商品表<b>不动</b>（商品没有"兑换/借贷"这条类型维，只有货↔钱的买卖）。
 * <b>不做旧键兼容</b>（用户 2026-10-10「旧设计和数据类型直接重建不用留」／AGENTS §一.11）。
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
 *   <li>{@link #ruleOfCommodity} / {@link #ruleOfCurrency(CurrencyId, MarketOrderKind)} 对表里没有的类/类型
 *       返回 {@link PortRule#unrestricted()}；
 *   <li>★ <b>显式 0 与未设逐值同义</b>（都读作"不限制/不收税"）——OR 规则与税都只读<b>有效值</b>，不做"设置过/没设置过"的区分。
 * </ul>
 *
 * <p>★ <b>保序不可变</b>：两张表（含币种表的内层）都 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code
 * Collections.unmodifiableMap} 冻结，<b>不用</b> {@code Map.copyOf}（迭代序不是内容的纯函数，字节级往返会因此不成立）。
 *
 * <p>★ <b>未知商品/币种不在这里判</b>：gov 模块编译期看不见经济词表（模块边界：gov 只许依赖 economy-api 的 ID 契约）。"这个类是不是世界上
 * 存在的商品/币种"的判据在组合根（{@code GovPortPolicyGuard} 写前拒 + {@code PortRegimeBridge} 折算期
 * fail-closed），不静默忽略。★ <b>挂单类型</b>相反：它是 {@code economy-api} 的受控词表（gov 编译期看得见）， 故在载荷解析处具名拒（{@link
 * MarketOrderKind#parse}），不必等组合根。
 *
 * <p>★★ <b>P-T1d：第三个字段 {@code marketControl}（"政府要求管控市场"）</b>（2026-10-10 口岸设计书 §16.3/§17；用户原话
 * 「如果政府要求管控市场，视为政府强制把自己的账户在市场交易里强制到最开始卖、最开始买」）：它是<b>开关</b>（不是强度）—— 打开后该政府的国库户（{@code
 * hh-gov-&lt;govUnitId&gt;}）挂单在<b>行政力池余量内</b>强制置顶（最先卖/最先买），每超越一户消耗一份行政力，见底硬停。
 *
 * <ul>
 *   <li><b>为什么放在本类而不是新开一条政策/命令</b>：本类就是"政府对本市场区的法律规定层"的既有载体（设计书 §4.5 G9）， {@code gov.SetPortPolicy}
 *       也已经是 {@code GmOnly} 的既有命令面 ⇒ 不新增权限面、不新增命令类型（派单冻结口径 §1）；
 *   <li><b>缺省 false = 缺省语义中性</b>（I-C2）：没要求管控的世界既没有置顶也没有行政力消耗 ⇒ 逐值不变；
 *   <li><b>它不是"规则表"，但也是"设过的政策"</b>：{@link #noRules()} 因此把本开关算进去（开了管控的政策不是"空政策"）。
 * </ul>
 *
 * @param commodityRules 商品 → 四元组规则（缺键 = 不限制/不收税；保序不可变）
 * @param currencyRules 币种 → 挂单类型 → 四元组规则（缺键 = 不限制/不收税；内外两层都保序不可变）
 * @param marketControl ★ P-T1d：政府是否<b>要求管控市场</b>（{@code true} = 该政府国库户的挂单按行政力池余量置顶；缺省 {@code false}
 *     = 不要求，逐值不变）
 */
public record GovPortPolicy(
    Map<CommodityId, PortRule> commodityRules,
    Map<CurrencyId, Map<MarketOrderKind, PortRule>> currencyRules,
    boolean marketControl) {

  /**
   * ★★ <b>返回防御性副本</b>（修 SpotBugs {@code EI_EXPOSE_REP}）：record 的自动访问器会把内部表直接交出去。 ★ <b>逐层 {@code
   * LinkedHashMap} + {@code unmodifiableMap}</b>（不用 {@code Map.copyOf}）： 迭代序必须是内容的纯函数（字节级往返依赖它）。
   */
  @Override
  public Map<CommodityId, PortRule> commodityRules() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(commodityRules));
  }

  /** ★★ 同上（防御性副本，<b>内外两层都拷</b>；见 {@link #commodityRules()} 的注）。 */
  @Override
  public Map<CurrencyId, Map<MarketOrderKind, PortRule>> currencyRules() {
    Map<CurrencyId, Map<MarketOrderKind, PortRule>> copy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Map<MarketOrderKind, PortRule>> entry : currencyRules.entrySet()) {
      copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
    }
    return Collections.unmodifiableMap(copy);
  }

  public GovPortPolicy {
    commodityRules = freezeCommodities(commodityRules, "commodityRules");
    currencyRules = freezeCurrencies(currencyRules, "currencyRules");
  }

  /** 空政策 = 什么都不限制、什么都不收、不要求管控（缺键 GOV 的默认；缺该组件键时的读法）。 */
  public static GovPortPolicy empty() {
    return new GovPortPolicy(Map.of(), Map.of(), false);
  }

  /**
   * ★★ <b>P-T1d：该政府要不要管控市场</b>（= {@link #marketControl}；唯一读法）。
   *
   * <p>★ 名字刻意不用 getter 形态：本类型是<b>持久状态</b>（{@code GovState.portPolicies} ⇒ 快照/变更集 JSON）， getter
   * 形态的方法名会被 Jackson 内省成属性写进线格式（见 {@link #noRules()} 的注）⇒ 谓词一律用非 getter 名。
   */
  public boolean controlsMarket() {
    return marketControl;
  }

  /** 该 GOV 对某商品的规则；缺键 ⇒ {@link PortRule#unrestricted()}（不限制、不收税）。 */
  public PortRule ruleOfCommodity(CommodityId commodity) {
    Objects.requireNonNull(commodity, "commodity");
    PortRule rule = commodityRules.get(commodity);
    return rule == null ? PortRule.unrestricted() : rule;
  }

  /**
   * 该 GOV 对某币种<b>某一类挂单</b>的规则；缺币种键/缺类型键 ⇒ {@link PortRule#unrestricted()}。
   *
   * <p>★ <b>为什么入参是币种 + 类型</b>：见 {@link MarketOrderKind} 的类注（"禁止本市场区货币被外国借贷"这条规则 只有带上类型才表达得出来）。
   */
  public PortRule ruleOfCurrency(CurrencyId currency, MarketOrderKind orderKind) {
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(orderKind, "orderKind");
    Map<MarketOrderKind, PortRule> byKind = currencyRules.get(currency);
    if (byKind == null) {
      return PortRule.unrestricted();
    }
    PortRule rule = byKind.get(orderKind);
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

  /** 某币种某类挂单的规则 —— 只有显式设过才给值（口径同 {@link #explicitCommodityRule}）。 */
  public Optional<PortRule> explicitCurrencyRule(CurrencyId currency, MarketOrderKind orderKind) {
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(orderKind, "orderKind");
    Map<MarketOrderKind, PortRule> byKind = currencyRules.get(currency);
    return byKind == null ? Optional.empty() : Optional.ofNullable(byKind.get(orderKind));
  }

  /** 某类某方向的限制强度（‰）的便利入口（商品 / 币种各自走对应表；缺键 ⇒ 0 = 不限制）。 */
  public long restrictionOf(PortDirection direction, CommodityId commodity) {
    return ruleOfCommodity(commodity).restriction(direction);
  }

  /** 某币种某类挂单某方向的限制强度（‰）的便利入口（缺键 ⇒ 0 = 不限制）。 */
  public long restrictionOf(
      PortDirection direction, CurrencyId currency, MarketOrderKind orderKind) {
    return ruleOfCurrency(currency, orderKind).restriction(direction);
  }

  /**
   * 某币种<b>设过规则</b>的挂单类型（保序 = {@link MarketOrderKind#all()} 的声明序；只作读数/日志）。
   *
   * <p>★ <b>顺序为什么按词表声明序而不是表插入序</b>：注入值/读数的遍历序必须是内容的纯函数（I7）；本表是给"逐类型折算"用的， 折算结果与遍历序无关，但日志顺序要稳定 ⇒
   * 用词表序（与"政策里怎么写"无关）。
   */
  public Set<MarketOrderKind> currencyKindsOf(CurrencyId currency) {
    Objects.requireNonNull(currency, "currency");
    Map<MarketOrderKind, PortRule> byKind = currencyRules.get(currency);
    Set<MarketOrderKind> kinds = new LinkedHashSet<>();
    if (byKind == null) {
      return Collections.unmodifiableSet(kinds);
    }
    for (MarketOrderKind kind : MarketOrderKind.all()) {
      if (byKind.containsKey(kind)) {
        kinds.add(kind);
      }
    }
    return Collections.unmodifiableSet(kinds);
  }

  /**
   * 是否一条规则都没设、也没要求管控（两表全空 <b>且</b> {@link #marketControl} = false ⇒ {@code true}）。
   *
   * <p>★ <b>P-T1d 起把管控开关算进来</b>：一个"只开管控、不设限制/税"的政策是<b>有内容的政策</b>（它会产生置顶与行政力消耗）， 不能再被读成"空"。
   *
   * <p>★ <b>名字刻意不用 {@code isEmpty()}</b>：本类型是<b>持久状态</b>（{@code GovState.portPolicies} ⇒ 快照/变更集
   * JSON）， getter 形态的方法名会被 Jackson 内省成属性 {@code "empty"} 写进线格式，而读侧 {@code
   * FAIL_ON_UNKNOWN_PROPERTIES} 严格 ⇒ 写出来的档自己读不回（手工往返实测复现）。旧形状的 {@code isEmpty()} 是同一颗地雷，本批顺手拆掉。
   */
  public boolean noRules() {
    return commodityRules.isEmpty() && currencyRules.isEmpty() && !marketControl;
  }

  /** 币种表里设过规则的（币种 × 类型）条数（只进日志/读数）。 */
  public int currencyKindCount() {
    int count = 0;
    for (Map<MarketOrderKind, PortRule> byKind : currencyRules.values()) {
      count += byKind.size();
    }
    return count;
  }

  /** 设过的类的条数（商品 + （币种 × 挂单类型）；只进日志/读数）。 */
  public int definedClassCount() {
    return commodityRules.size() + currencyKindCount();
  }

  /** 真的会拦货/拦钱的条数（任一侧限制 ≠ 0；只进日志/读数）。 */
  public int restrictedClassCount() {
    int count = 0;
    for (PortRule rule : commodityRules.values()) {
      if (rule.hasRestriction()) {
        count++;
      }
    }
    for (Map<MarketOrderKind, PortRule> byKind : currencyRules.values()) {
      for (PortRule rule : byKind.values()) {
        if (rule.hasRestriction()) {
          count++;
        }
      }
    }
    return count;
  }

  /**
   * 设了<b>真会收</b>的税/手续费规则的条数（只进日志/读数；商品 + （币种 × 挂单类型））。
   *
   * <p>★★ <b>币种那一份在本批只进日志</b>：币种手续费（用户"异种货币自然按手续费/规则来算"）今天<b>没有收款面</b>
   * （钱腿恒铸买方支付币、货↔钱成交不按"兑换手续费"抽成）⇒ 只落形状、读数与具名 INFO，<b>一个数都不搬</b>（不是静默丢弃）。 商品那一份是 P-T1b 的过境税，已真收。
   */
  public int taxedClassCount() {
    int count = 0;
    for (PortRule rule : commodityRules.values()) {
      if (rule.hasEffectiveTax()) {
        count++;
      }
    }
    for (Map<MarketOrderKind, PortRule> byKind : currencyRules.values()) {
      for (PortRule rule : byKind.values()) {
        if (rule.hasEffectiveTax()) {
          count++;
        }
      }
    }
    return count;
  }

  /** 设了<b>币种手续费</b>（真会收）的（币种 × 类型）条数（只进日志/读数；见 {@link #taxedClassCount()} 的注）。 */
  public int currencyFeeKindCount() {
    int count = 0;
    for (Map<MarketOrderKind, PortRule> byKind : currencyRules.values()) {
      for (PortRule rule : byKind.values()) {
        if (rule.hasEffectiveTax()) {
          count++;
        }
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

  /** 币种表（两层）的冻结与校验：币种键、类型键、内层表、规则值都不许为 null（fail-closed 具名拒）。 */
  private static Map<CurrencyId, Map<MarketOrderKind, PortRule>> freezeCurrencies(
      Map<CurrencyId, Map<MarketOrderKind, PortRule>> table, String field) {
    if (table == null) {
      throw new IllegalArgumentException("GovPortPolicy." + field + " 不得为 null（什么都不设给 Map.of()）");
    }
    Map<CurrencyId, Map<MarketOrderKind, PortRule>> copy = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Map<MarketOrderKind, PortRule>> entry : table.entrySet()) {
      if (entry.getKey() == null) {
        throw new IllegalArgumentException("GovPortPolicy." + field + " 的币种键不得为 null");
      }
      Map<MarketOrderKind, PortRule> byKind = entry.getValue();
      if (byKind == null) {
        throw new IllegalArgumentException(
            "GovPortPolicy." + field + "[" + entry.getKey().value() + "] 不得为 null（不设规则给 Map.of()）");
      }
      Map<MarketOrderKind, PortRule> inner = new LinkedHashMap<>();
      for (Map.Entry<MarketOrderKind, PortRule> item : byKind.entrySet()) {
        if (item.getKey() == null || item.getValue() == null) {
          throw new IllegalArgumentException(
              "GovPortPolicy."
                  + field
                  + "["
                  + entry.getKey().value()
                  + "] 的挂单类型键与规则值都不得为 null（不设规则给 PortRule.unrestricted()）");
        }
        inner.put(item.getKey(), item.getValue());
      }
      copy.put(entry.getKey(), Collections.unmodifiableMap(inner));
    }
    return Collections.unmodifiableMap(copy);
  }
}

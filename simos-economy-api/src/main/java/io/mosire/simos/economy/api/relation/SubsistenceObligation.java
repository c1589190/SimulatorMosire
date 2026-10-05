package io.mosire.simos.economy.api.relation;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ★★ <b>M1.7：实物给养义务（具名）</b> —— 一个生产经营者（{@link #provider()}）在一个周期里，<b>向谁</b>（{@link
 * #recipient()}）、<b>按什么劳动量</b>（{@link #laborMilli()} × {@link
 * #perLaborMilli()}）、<b>应交付多少</b>（{@link #dueAmount()}）实物。
 *
 * <p>★★ <b>它为什么存在</b>：改前"给养"只是某一条通用规则（{@code RuleType.FIXED_IN_KIND_PER_LABOR}）的一种用法 ——
 * 谁也答不出"这个主体对哪些家户、还欠多少给养"：受方集合要从 {@code rules} + 劳动账现算，欠额只进**当日**的 {@code RuleSettlement} 读数（ledger
 * 当日丢弃）。本类型把这件事<b>具名</b>：它是<b>纯派生</b>的（{@link #of(ProductionRules, Map)} 由既有 {@code
 * relation.rules()} + 本周期实际劳动量算出）， <b>不给 {@code ProductionRules} 加组件、不给 {@code EconomyData}
 * 加状态</b>（零契约改动、零 codec/往返牵动）。
 *
 * <p>★★ <b>口径与实付同源</b>：{@link #dueAmount()} 逐字等于 {@code ProductionSettlement} 公式表里 {@code
 * FIXED_IN_KIND_PER_LABOR} 那一档的应付量（{@code ⌊本受方劳动 ÷ 1000⌋ × fixedAmount}），而且两边调的是<b>同一个函数</b> {@link
 * #perLaborDue(long, long)}（实付侧见 {@code ProductionSettlement.perLabor}）。 ★
 * <b>它是"本周期应付"，不是跨周期债务</b>： 实付上限仍由 R6（本周期可用产出）在结算里咬合，付不出的部分<b>只进读数、不落债权</b>（"记欠"开关未定，M1.5
 * 已裁不做跨周期债务）。
 *
 * <p>★★ <b>M1.7 判据②的落点：经营者保留算式从这里接</b>（唯一入口 = {@link #retentionOf(ProductionRules, Map,
 * Map)}）。用户的裁定是"<b>经营者不能因为关联了某批人口，就再替这批人口扣一次完整口粮</b>" —— 本类型把这条做成<b>结构性事实</b>：
 *
 * <ul>
 *   <li>保留额<b>只由已具名的实物给养义务派生</b>（{@code Σ dueAmount}），派生入口<b>只收 relation + 劳动量表</b>，
 *       <b>不接受任何人口参数</b> ⇒ "按关联人口再算一份口粮"在类型上就无从表达；
 *   <li>{@link #retentionOf(ProductionRules, Map, Map)} 还逐商品把策略请求<b>钉在承诺额以内</b>（{@code min(请求,
 *       承诺)}） ⇒ 即使 M2 的保留策略把"生活保留"算大了，也<b>不可能超过它承诺的给养</b>（判据②：Σ经营者保留 ≤ 它承诺的）。
 * </ul>
 *
 * <p>★ <b>M2 的接缝</b>：M2 的订单/保留算式是 {@code 可售库存 = max(0, 持有 − 已冻结 − 必要生产投入 − 生活保留)}； 其中经营者的"生活保留"必须走
 * {@link #retentionOf(ProductionRules, Map, Map)} —— 类注见该方法的 Javadoc。
 *
 * <p>★ <b>受方的两档与结算同源</b>：{@code ToHousehold} 的劳动量查本周期劳动量表，{@code ToActor} 在本阶段没有劳动账 ⇒ 0 （口径与 {@code
 * ProductionSettlement.laborOf} 逐字相同，且本类型的 {@link #laborOf(Payee, Map)} 就是它的唯一拼写点）。 ★ <b>未归一化的
 * {@code ToCohort}</b> 没有家户身份可查 ⇒ <b>当场抛</b>（M5：与结算侧 {@code requireCohortRows} 的 fail-closed
 * 同向）；不得读成 0 —— "算不出"与"劳动量是 0"在给养义务里会给出完全不同的应付额。
 *
 * <p>★ <b>本类型不做的事</b>：不落账、不产生 {@code Debt}/{@code Claim}、不改任何守恒式；"欠了多少"也<b>不跨周期累计</b> （那是 M1.5
 * 明确不做的跨周期债务）。实数计算一律毫单位、整数、向下取整。
 *
 * @param activity 这条义务出自哪个生产活动（身份 = 生产单元 unit；铁律 1，不另造 id）
 * @param provider <b>谁承担</b>（= 该关系的经营者 {@code ProductionRules.operator()}；不得为 null）
 * @param recipient <b>向谁</b>（{@code Payee}：家户 cohort 或 actor，恰其一；不得为 null）
 * @param laborMilli <b>按什么量</b>：本受方本周期（周期口径，非日口径）的实际劳动量（千分劳动）；不得为负
 * @param rule 这条义务的<b>来源规则</b>（{@code FIXED_IN_KIND_PER_LABOR}；每周期多少与商品都由它给；不得为 null） ★
 *     带上它是为了可追溯：读口/用例能把一条义务逐值对回关系表里的那一条，而不是看一个孤立数字。
 */
public record SubsistenceObligation(
    ProductionUnitId activity,
    ActorRef provider,
    Payee recipient,
    long laborMilli,
    CompensationRule rule) {

  /** 千分率的分母（与 {@code ProductionSettlement} 同值：劳动量口径是千分劳动）。 */
  private static final long PER_MILLE = 1000L;

  public SubsistenceObligation {
    if (activity == null) {
      throw new IllegalArgumentException("SubsistenceObligation.activity 不得为 null");
    }
    if (provider == null) {
      throw new IllegalArgumentException("SubsistenceObligation.provider 不得为 null（谁承担给养）");
    }
    if (recipient == null) {
      throw new IllegalArgumentException("SubsistenceObligation.recipient 不得为 null（向谁给养）");
    }
    if (rule == null) {
      throw new IllegalArgumentException("SubsistenceObligation.rule 不得为 null（来源规则）");
    }
    if (rule.type() != RuleType.FIXED_IN_KIND_PER_LABOR) {
      throw new IllegalArgumentException(
          "只有实物给养那一档（" + RuleType.FIXED_IN_KIND_PER_LABOR + "）才具名成这一类型，这一条是: " + rule.type());
    }
    if (rule.commodity().isEmpty()) {
      throw new IllegalArgumentException("实物给养义务必须有商品（CompensationRule 的守卫应已判死）: " + rule);
    }
    if (!rule.recipient().equals(recipient)) {
      throw new IllegalArgumentException(
          "SubsistenceObligation.recipient 必须与来源规则的受方逐值相同（同一件事不许两处拼写）："
              + recipient
              + " vs "
              + rule.recipient());
    }
    if (laborMilli < 0L) {
      throw new IllegalArgumentException("SubsistenceObligation.laborMilli 不得为负: " + laborMilli);
    }
  }

  /** <b>给养的商品</b>（派生自来源规则；本类型不存第二份，故不可能与规则漂开）。 */
  public CommodityId commodity() {
    return rule.commodity().orElseThrow();
  }

  /** <b>每 1000 千分劳动给多少</b>（派生自来源规则的 {@code fixedAmount}；本类型不存第二份）。 */
  public long perLaborMilli() {
    return rule.fixedAmount();
  }

  /**
   * ★★ <b>本周期应付</b>（毫商品）：{@code ⌊本受方劳动 ÷ 1000⌋ × 每千分劳动给养}。
   *
   * <p>★ 它<b>不是实付</b>：实付上限还受 R6（本周期可用产出）咬合（见 {@code ProductionSettlement}），欠额只进读数。
   */
  public long dueAmount() {
    return perLaborDue(laborMilli, perLaborMilli());
  }

  /**
   * ★★ <b>实物给养义务量的唯一算法</b>（毫商品）：{@code ⌊laborMilli ÷ 1000⌋ × perLaborMilli}（整数、逐步向下取整）。
   *
   * <p>★★ <b>为什么它是 static 而不是只藏在结算里</b>：M1.7 要求"义务量算法与实付逐字一致或就是它" ⇒ 结算侧 （{@code
   * ProductionSettlement.perLabor}）与本类型都调这一个函数，公式<b>不可能漂开</b>。
   *
   * @param laborMilli 本受方的劳动量（千分劳动；不得为负）
   * @param perLaborMilli 每 1000 千分劳动给多少（毫商品；不得为负）
   * @throws IllegalArgumentException 任一为负（负数不是一种数量）
   */
  public static long perLaborDue(long laborMilli, long perLaborMilli) {
    if (laborMilli < 0L) {
      throw new IllegalArgumentException("perLaborDue 的 laborMilli 不得为负: " + laborMilli);
    }
    if (perLaborMilli < 0L) {
      throw new IllegalArgumentException("perLaborDue 的 perLaborMilli 不得为负: " + perLaborMilli);
    }
    return laborMilli / PER_MILLE * perLaborMilli;
  }

  /**
   * ★★ <b>受方本周期劳动量（唯一拼写点）</b>：家户查本周期劳动量表；{@code ToActor} 本阶段没有劳动账 ⇒ 0； {@code ToCohort} 是旧档变体（S1
   * 起不再由运行期生产）且**没有家户身份可查** ⇒ 当场抛（M5）。
   *
   * <p>★★ <b>为什么 {@code ToCohort} 不能再归零</b>：结算侧 {@code requireCohortRows} 对同一状态按视图反查家户、
   * 找不到/有歧义就抛；这里若返回 0，读口会显示一条"应付 0"的给养义务，而结算会在同一天抛 —— 两处口径分叉， 且分叉的方向正是本仓最忌的"静默付 0"。⇒ 统一为具名 {@link
   * IllegalStateException}，让归一化缺口在第一次派生时现形。
   */
  public static long laborOf(Payee recipient, Map<HouseholdId, Long> laborOfHousehold) {
    if (recipient == null) {
      throw new IllegalArgumentException("laborOf 的 recipient 不得为 null");
    }
    if (laborOfHousehold == null) {
      throw new IllegalArgumentException("laborOf 的 laborOfHousehold 不得为 null（没有劳动请给空 map）");
    }
    return switch (recipient) {
      case Payee.ToHousehold toHousehold ->
          laborOfHousehold.getOrDefault(toHousehold.household(), 0L);
      // ★ M5：旧档变体（S1 迁移前）没有可查的家户身份 ⇒ fail-closed（与结算侧 requireCohortRows 同向），不读成 0。
      case Payee.ToCohort toCohort ->
          throw new IllegalStateException(
              "未归一化的 Payee.ToCohort 没有家户身份，无法查本周期劳动量（拒绝把算不出读成 0）：cohort="
                  + toCohort.cohort()
                  + "。请先在 EconomyData 构造期把一对一视图归一为 Payee.ToHousehold；视图有歧义时结算与读口都拒绝猜");
      // ★ 本阶段没有 actor 劳动账（与 ProductionSettlement 的类注同款）：归零，不猜。
      case Payee.ToActor ignored -> 0L;
    };
  }

  /**
   * ★★ <b>纯派生</b>：由一条既有关系 + 本周期劳动量表，展开出它全部的实物给养义务。
   *
   * <p>★ <b>只认公式表登记的那一档</b>（{@code FIXED_IN_KIND_PER_LABOR × NET_AFTER_INPUTS + LABOR_AMOUNT}）：
   * 组合没登记 ⇒ <b>当场抛</b>（与结算的 E11 同口径，不静默给一个数）。
   *
   * <p>★ 次序 = 付款次序（{@code priority} 升序、同值按表序稳定）⇒ 与实付的次序同源、可逐条对回。
   *
   * @param relation 生产关系；不得为 null
   * @param laborOfHousehold 本周期各家户的劳动量（键值非空、逐值非负；缺键 ⇒ 0 劳动 ⇒ 应付 0）；不得为 null
   * @return 该关系的全部实物给养义务（保序、不可变；没有 ⇒ 空表）
   */
  public static List<SubsistenceObligation> of(
      ProductionRules relation, Map<HouseholdId, Long> laborOfHousehold) {
    if (relation == null) {
      throw new IllegalArgumentException("SubsistenceObligation.of 的 relation 不得为 null");
    }
    if (laborOfHousehold == null) {
      throw new IllegalArgumentException(
          "SubsistenceObligation.of 的 laborOfHousehold 不得为 null（没有劳动请给空 map）");
    }
    for (Map.Entry<HouseholdId, Long> entry : laborOfHousehold.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("laborOfHousehold 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "laborOfHousehold 的劳动量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
    }
    List<CompensationRule> ordered = new ArrayList<>(relation.rules());
    ordered.sort(Comparator.comparingInt(CompensationRule::priority)); // ★ 稳定排序 ⇒ 同 priority 保表序
    List<SubsistenceObligation> obligations = new ArrayList<>();
    for (CompensationRule rule : ordered) {
      if (rule.type() != RuleType.FIXED_IN_KIND_PER_LABOR) {
        continue;
      }
      if (rule.pool() != Pool.NET_AFTER_INPUTS || rule.weight() != Weight.LABOR_AMOUNT) {
        throw new IllegalArgumentException(
            "实物给养那一档在公式表里只登记了一种组合（FIXED_IN_KIND_PER_LABOR × NET_AFTER_INPUTS +"
                + " LABOR_AMOUNT），本规则不是：pool="
                + rule.pool()
                + " weight="
                + rule.weight()
                + "；规则="
                + rule);
      }
      obligations.add(
          new SubsistenceObligation(
              relation.activity(),
              relation.operator(),
              rule.recipient(),
              laborOf(rule.recipient(), laborOfHousehold),
              rule));
    }
    return List.copyOf(obligations); // ★ 保序不可变（List.copyOf 保迭代序）
  }

  /**
   * ★★ <b>它承诺的给养</b>（逐商品求和）：{@code 商品 → Σ dueAmount}。
   *
   * <p>★ 保序不可变（键序 = 义务表里首次出现的次序）；读口要字典序请在视图层排。
   */
  public static Map<CommodityId, Long> promisedByCommodity(
      List<SubsistenceObligation> obligations) {
    if (obligations == null) {
      throw new IllegalArgumentException("promisedByCommodity 的 obligations 不得为 null");
    }
    Map<CommodityId, Long> promised = new LinkedHashMap<>();
    for (SubsistenceObligation obligation : obligations) {
      if (obligation == null) {
        throw new IllegalArgumentException("promisedByCommodity 的 obligations 不得含 null 项");
      }
      promised.merge(obligation.commodity(), obligation.dueAmount(), Long::sum);
    }
    return Collections.unmodifiableMap(promised);
  }

  /**
   * ★★ <b>M2 的订单/保留算式从这里接（唯一入口）：经营者为实物给养要保留的库存</b>（逐商品）。
   *
   * <pre>
   * 承诺额 promised[c] = Σ 本关系全部实物给养义务的 dueAmount（逐商品）
   * 保留额 retained[c] = min(策略请求 requested[c], promised[c])     // c 只在"有承诺"的商品上取值
   * ⇒ 恒有 Σ retained ≤ Σ promised（判据②）；且本方法**不收任何人口参数** ⇒ "按关联人口再扣一份口粮"结构上不可表达
   * </pre>
   *
   * <p>★★ <b>为什么要有 requested 这道口</b>：M2 的"生活保留"是策略量（可能把关联人口的整份口粮算进来）——
   * 本入口把它<b>钉在承诺额以内</b>，于是用户的裁定"经营者只保留它确实承担的那份给养"成为<b>可执行的守卫</b>， 而不是类注里的一句承诺。
   *
   * <p>★ <b>没有承诺的商品一律不进结果</b>（请求了也不保留）："保留"只能是为已具名的给养义务保留，不许借"生活保留"之名囤别的东西。
   *
   * <p>★ <b>本阶段没有生产调用方</b>（经营者还没进市场）；M2.1/M2.2 的订单与保留算式落地时接这里（见类注）。
   *
   * @param relation 生产关系；不得为 null
   * @param laborOfHousehold 本周期各家户的劳动量（见 {@link #of(ProductionRules, Map)}）；不得为 null
   * @param requestedRetention 策略想保留的量（逐商品；不得为 null，没有请求给空 map）——逐值不得为负
   * @return 逐商品的保留额（保序不可变；只在有承诺的商品上有键）
   */
  public static Map<CommodityId, Long> retentionOf(
      ProductionRules relation,
      Map<HouseholdId, Long> laborOfHousehold,
      Map<CommodityId, Long> requestedRetention) {
    if (requestedRetention == null) {
      throw new IllegalArgumentException("retentionOf 的 requestedRetention 不得为 null（没有请求请给空 map）");
    }
    for (Map.Entry<CommodityId, Long> entry : requestedRetention.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("requestedRetention 的键与值都不得为 null: " + entry.getKey());
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "requestedRetention 的量不得为负：" + entry.getKey() + " = " + entry.getValue());
      }
    }
    Map<CommodityId, Long> promised = promisedByCommodity(of(relation, laborOfHousehold));
    Map<CommodityId, Long> retained = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : promised.entrySet()) {
      long requested = requestedRetention.getOrDefault(entry.getKey(), 0L);
      retained.put(entry.getKey(), Math.min(requested, entry.getValue()));
    }
    return Collections.unmodifiableMap(retained);
  }
}

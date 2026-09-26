package io.mosire.simos.economy.api.relation;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.IndustryId;
import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>一次生产活动的结算规则</b>（spec §2.4 的 {@code ProductionRelation}）：关账以后，产出如何在<b>经营者</b>、
 * <b>劳动提供者</b>、<b>资产所有者</b>之间分掉（裁定 E4 的单列表形态）。
 *
 * <p>★★ <b>它不另造 id</b>：身份 = 它结算的那个 {@link #activity()}（铁律 1 —— id 是身份，不在切片里另造同义 ID）。★ 关系表因此是 {@code
 * EconomyData} 的<b>第 8 个组件</b>、键 = {@code IndustryId}，并与 {@code Industry.operator} 有<b>跨表守卫</b>
 * （两处拼写必须一致；守卫住 {@code EconomyData}，不在这里 —— 本类型看得见自己，看不见那张产业表）。
 *
 * <p>★★ <b>单列表 + {@code priority}（裁定 E4，取代 spec §2.4 的 labor/asset 两张表）</b>：
 *
 * <ul>
 *   <li>「这一条规则是给劳动者的还是给资产所有者的」<b>已经由</b> {@code recipient}（sealed：actor | cohort）<b>与</b> {@code
 *       basis}（{@code LABOR_AMOUNT} / {@code ASSET_QUANTITY}）<b>表达</b> ⇒ 再切两张表就是<b>第二拼写点</b>：
 *       同一批规则能被分成两处、次序也被切成两段；
 *   <li>★ 更要紧的是：<b>两张表之间没有次序</b> ⇒「先给养、后算地租（地租按剩下的算）」这种<b>真实制度</b>无从表达。一张表 + {@code priority}
 *       把它变成<b>数据</b>（改次序 = 改一个整数，不改代码）；
 *   <li>★ 于是「租给 {@code (hex, landlord)} cohort」就是<b>再一条规则</b>：{@code recipient = ToCohort(new
 *       CohortKey(hexOf(activity), SocialClassId.LANDLORD))}。★ <b>必须显式给</b> —— 地主既不是劳动者（不在劳动账里），
 *       也不是 actor（经营者），不显式给，<b>地主 cohort 的粮源会凭空消失</b>。
 * </ul>
 *
 * <p>★★ <b>构造期守卫</b>：{@code activity} / {@code operator} / {@code residualOwner} 非 null；{@code
 * rules} 非 null、<b>逐项非 null</b>、<b>保序不可变</b>（冻结写在赋值处，外部之后改那张表不影响已建的关系）。★ <b>空表合法</b>：一条规则都没有 ⇒
 * 产出全部归 {@link #residualOwner()}（自留是<b>缺省</b>，不是坏数据）。
 *
 * <p>★ <b>本阶段（S1 阶段 4+5）的过渡口径如实记</b>：{@code AssetHolding} <b>未在真档种入</b> ⇒ {@code ASSET_QUANTITY}
 * 这一档只有夹具覆盖（未达成项，落点见台账）。★ 另：{@code SELF_RETENTION} 属<b>实物</b>档 ⇒ 它也要带 {@code commodity} （{@link
 * CompensationRule} 的二选一守卫）；若某一档只想要「余额归 residualOwner」，把它写成<b>不出现这条规则</b>即可（空表 ⇒ 全归 residualOwner）。
 *
 * @param activity 这条关系结算的那个活动（身份 = 它，不另造 id）
 * @param operator 经营主体（必须与 {@code Industry.operator} 一致，跨表守卫在 {@code EconomyData}）
 * @param rules 补偿规则（**一张表、保序、不可变**；空表 = 全部自留）
 * @param residualOwner 余额归谁（一般是 {@code operator}；不产生任何条目）
 */
public record ProductionRelation(
    IndustryId activity, ActorRef operator, List<CompensationRule> rules, ActorRef residualOwner) {

  public ProductionRelation {
    if (activity == null) {
      throw new IllegalArgumentException("ProductionRelation.activity 不得为 null");
    }
    if (operator == null) {
      throw new IllegalArgumentException("ProductionRelation.operator 不得为 null");
    }
    if (rules == null) {
      throw new IllegalArgumentException("ProductionRelation.rules 不得为 null（无规则请给空表）");
    }
    if (residualOwner == null) {
      throw new IllegalArgumentException("ProductionRelation.residualOwner 不得为 null");
    }
    List<CompensationRule> rulesCopy = new ArrayList<>(rules.size());
    for (CompensationRule rule : rules) {
      if (rule == null) {
        throw new IllegalArgumentException(
            "ProductionRelation.rules 不得含 null 项（第 " + rulesCopy.size() + " 项）");
      }
      rulesCopy.add(rule);
    }
    rules = List.copyOf(rulesCopy); // ★ 冻在赋值处（含防御性拷贝，且保序）
  }
}

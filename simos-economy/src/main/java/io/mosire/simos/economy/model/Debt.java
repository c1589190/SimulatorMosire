package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import java.util.Optional;

/**
 * 债务（新经济设计 §3.3 的形状 + v2 spec §7.2 的**语义**）：**一周期内一对债权债务人之间**的往来一条（本金/利率/到期/标的）。
 *
 * <p>★★ **语义变更（v2 spec §7.2；V6 落地）**：本类**不再**是"一笔借据"，而是"**同一周期内同一对债权债务人、同一商品的往来**"： 同一周期内该对主体**多次借入
 * ⇒ 累加到同一条**（{@code principal} 递增），**新周期开新条**。理由见 {@code EconomySettlement.debtIdOf}（id 由 {@code
 * (周期, 债务人, 债权人, 商品)} 确定性算出）—— 效果是债务条数上界从 {@code O(天数 × 格子)} 降到 {@code O(周期数 × 格子 × 债权人对数)}（799 格 ×
 * 4 槽位、缺口持续时，旧口径一年留下约 117 万条）。
 *
 * <p>★ **实物债与货币债分开**：{@code commodity} 有值 ⇒ 实物债（借粮，{@code principal} 按最小计量单位）； 空 ⇒ 货币债（{@code
 * principal} 按最小币值）。二者量纲都是整数，**禁 {@code double} 决定钱/粮**（§7）。
 *
 * <p>★★ **一条 spec 内部张力，取舍写在结算侧**（{@code EconomySettlement.chargeInterest} 的注释有完整理由）：v1 spec §3.3
 * 末条说「债务**只能**由 借入/赊购 产生」，而 §四 周期结算第 6 步允许"计息（写新应付款或**并入本金**）"。本批取 §四 —— 计息**并入本金**（复利）：§3.3
 * 防的是"凭空造负债"，而利息是挂在一条**已由借入产生**的债务上的**合同义务**。 ⇒ {@code principal} 因此**可以**在没有任何新借入的一天变大（周期末计息那一笔）。
 *
 * <p>★★ **{@code dueCycle} 保留，但当前没有任何代码读它**（V6 明列的"留位"）：偿还/到期执行/违约处置都是"判" （"有粮才还"要 GM 可调预设），属
 * V7+。它记的是"这条债**是哪一周期借的** + 1"（同周期内多次借入 ⇒ 该值相同）。 本仓禁"看起来在记、其实永远不被读"的**静默**字段，故在此写明；{@code
 * defaulted} 同理（写死 {@code false}， 等违约处置落地才可能为真）。
 *
 * <p>★ **不变量（构造期判，§6.4）**：{@code principal ≥ 0}（本金非负）、{@code ratePerMillePerCycle ≥ 0}、 {@code
 * dueCycle ≥ 0}；{@code commodity} 不得为 null（货币债用 {@code Optional.empty()}）。
 *
 * @param id 稳定身份（由 {@code (周期, 债务人, 债权人, 商品)} 确定性算出、不含 {@code "."}、跨周期不同）
 * @param debtor 债务人（**家户** = {@link HouseholdId}；S1 起债务两端是稳定身份，不再随视图迁移改键）
 * @param creditor 债权人（同上）
 * @param commodity 实物债的商品；**货币债 = {@code Optional.empty()}**
 * @param principal 本金（余额；**周期末计息并入** ⇒ 不只是"借入之和"）；不得为负
 * @param ratePerMillePerCycle 每周期利率（千分数）；不得为负
 * @param dueCycle 借入周期 + 1；不得为负；**当前不被读**（见上）
 * @param defaulted 是否已违约（供阶层流动判据用，§八 R6）；**当前恒 {@code false}**
 */
public record Debt(
    DebtId id,
    HouseholdId debtor,
    HouseholdId creditor,
    Optional<CommodityId> commodity,
    long principal,
    int ratePerMillePerCycle,
    long dueCycle,
    boolean defaulted) {

  public Debt {
    if (id == null) {
      throw new IllegalArgumentException("Debt.id 不得为 null");
    }
    if (debtor == null) {
      throw new IllegalArgumentException("Debt.debtor 不得为 null");
    }
    if (creditor == null) {
      throw new IllegalArgumentException("Debt.creditor 不得为 null");
    }
    if (commodity == null) {
      throw new IllegalArgumentException("Debt.commodity 不得为 null（货币债用 Optional.empty()）");
    }
    if (principal < 0) {
      throw new IllegalArgumentException("Debt.principal 不得为负: " + principal);
    }
    if (ratePerMillePerCycle < 0) {
      throw new IllegalArgumentException("Debt.ratePerMillePerCycle 不得为负: " + ratePerMillePerCycle);
    }
    if (dueCycle < 0) {
      throw new IllegalArgumentException("Debt.dueCycle 不得为负: " + dueCycle);
    }
  }
}

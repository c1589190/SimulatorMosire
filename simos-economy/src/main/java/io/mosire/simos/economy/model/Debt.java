package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import java.util.Optional;

/**
 * 债务（新经济设计 §3.3 逐字）：**阶层 → 阶层**的聚合债权一条（本金/利率/到期/标的）。
 *
 * <p>★ **实物债与货币债分开**：{@code commodity} 有值 ⇒ 实物债（借粮，{@code principal} 按最小计量单位）； 空 ⇒ 货币债（{@code
 * principal} 按最小币值）。二者量纲都是整数，**禁 {@code double} 决定钱/粮**（§7）。
 *
 * <p>★ **债务只能由借入/赊购产生**（§3.3 末条）：本类只记"谁欠谁多少"，钱的搬运不在这里；计息/偿债/违约判定是 R5 的结算逻辑。
 *
 * <p>★ **不变量（构造期判，§6.4）**：{@code principal ≥ 0}（本金非负）、{@code ratePerMillePerCycle ≥ 0}、 {@code
 * dueCycle ≥ 0}；{@code commodity} 不得为 null（货币债用 {@code Optional.empty()}）。
 *
 * @param id 稳定身份
 * @param debtor 债务人（阶层行）
 * @param creditor 债权人（阶层行）
 * @param commodity 实物债的商品；**货币债 = {@code Optional.empty()}**
 * @param principal 本金（余额）；不得为负
 * @param ratePerMillePerCycle 每周期利率（千分数）；不得为负
 * @param dueCycle 到期周期序号；不得为负
 * @param defaulted 是否已违约（供阶层流动判据用，§八 R6）
 */
public record Debt(
    DebtId id,
    ClassKey debtor,
    ClassKey creditor,
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

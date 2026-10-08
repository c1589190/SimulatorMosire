package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★ <b>「家户 → 其债引用 id」的复合键</b>（2026-10-09 选项 A：把债务引用从 {@link HouseholdEconomy} 行内拆成独立表）。
 *
 * <p>★★ <b>要修的病</b>：{@code FieldDelta.diff} 是<b>行级</b>的（{@code simos-util} 的 {@code
 * FieldDelta.java:158}：值不等就把<b>整行值</b>放进 Upsert）⇒ 单条 {@code HouseholdEconomy} 里那 3.7KB 的 {@code
 * debts} 列表（约 50 条 {@code DebtContractId}，实测 0/203 户每天变化）被 {@code cycleNaturalNeedMilli} 这类 6
 * 字节的日变动<b>每天整行重写一遍</b>，占变更集的 ~62%。拆成"一户一债一对"的行之后，每天只落盘<b>真正变化的引用对</b>。
 *
 * <p>★★ <b>权威方向不变（单向，不许反过来）</b>：一条合同是否存在、债务人/债权人是谁、本金多少，只由 {@code debtContracts}
 * 表回答；本表的每一行只是"该家户是该合同的债务人"这一条<b>派生索引</b>，由 {@code DebtReferenceReconciler} 在 {@code EconomyData}
 * 构造期按合同表重建。故本表<b>不是</b>第二本债务账。
 *
 * <p>★ <b>规范串</b>（{@link #toString()} / {@link #parse(String)} 互逆，{@code FieldDelta} 的键就靠这一对）：
 * {@code <household>@<contract>}。分隔符取 {@code '@'} 的理由：两个 id 的规范串都是不透明值，而<b>实际拼写</b>里 家户 id 只含
 * {@code [A-Za-z0-9_|.\-]}（{@code hh-<q>_<r>-<居住>-<阶层>} 与 legacy 的 {@code q_r|居住|阶层}）、合同 id 只含
 * {@code [0-9a-f\-]}（{@code debtc-<hex>-<hex>-<hex>-<hex>}， 见 {@link DebtContractId#idOf}）⇒ {@code
 * '@'} 不会出现在任一侧。{@link #parse} 对"没有分隔符/多于一个分隔符"的串 <b>fail-closed 具名抛</b>（不猜、不截断）。
 *
 * @param household 债务人（家户稳定身份；铁律 1：ID 是身份）
 * @param contract 债务合同 id（{@code debtContracts} 表的键；本行只声明"该户是它的债务人"）
 */
public record HouseholdDebtReference(HouseholdId household, DebtContractId contract) {

  /** 复合键的规范分隔符（见类注：两个 id 的实际拼写都不含它）。 */
  public static final char SEPARATOR = '@';

  public HouseholdDebtReference {
    Objects.requireNonNull(household, "HouseholdDebtReference.household 不得为 null");
    Objects.requireNonNull(contract, "HouseholdDebtReference.contract 不得为 null");
  }

  /** 规范串 = {@code <household>@<contract>}（与 {@link #parse} 互为逆，{@code FieldDelta} 的键就是它）。 */
  @Override
  public String toString() {
    return household.value() + SEPARATOR + contract.value();
  }

  /**
   * 规范串 ⇒ 复合键（{@link #toString()} 的逆）。
   *
   * @throws IllegalArgumentException 文本为空、没有分隔符、或多于一个分隔符（不猜格式）
   */
  public static HouseholdDebtReference parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("HouseholdDebtReference 的规范串不得为空白");
    }
    int at = text.indexOf(SEPARATOR);
    if (at <= 0 || at != text.lastIndexOf(SEPARATOR) || at == text.length() - 1) {
      throw new IllegalArgumentException(
          "HouseholdDebtReference 的规范串必须是 <household>@<contract>（恰一个分隔符，两侧非空）: " + text);
    }
    return new HouseholdDebtReference(
        HouseholdId.parse(text.substring(0, at)), DebtContractId.parse(text.substring(at + 1)));
  }
}

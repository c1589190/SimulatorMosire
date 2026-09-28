package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * ★★ <b>债务的「双向可查」—— 从债务表纯派生出的两个方向索引</b>（M1.5；★ 不复活 {@code Claim}、零契约改动）。
 *
 * <p>★★ <b>为什么是纯派生、不是新状态组件</b>：{@link Debt} 一条记录同时带 {@code debtor} 与 {@code creditor}
 * ——"一笔债两视图同源"在<b>数据上已经成立</b>，缺的只是债权人方向的索引（{@code ClassRow.debts} 只记债务人）。 加一个 {@code EconomyData}
 * 组件会牵动铁律 5 的变更集 / codec / 往返用例，而本次要的只是"查得到" ⇒ 用一个纯函数从既有债务表现算。★ 索引**每次查询现算**（债务表本就不大：条数上界 = 周期数 ×
 * 格子 × 债权人对数）， 且"派生"保证了它与真源<b>不可能漂移</b>（存一份索引 = 同一事实的第二处拼写）。
 *
 * <p>★★ <b>两个方向都从同一条记录取数</b>：{@link #byDebtor} 与 {@link #byCreditor} 只是把同一条 {@link Debt} 挂到不同的主体上 ⇒
 * 一笔债两个方向读到的本金 / 利率 / {@code dueCycle} <b>逐值相同</b>（调用方拿 {@link DebtId} 回 {@code
 * EconomyData.debts()} 查同一条记录，本类不另存任何字段）。
 *
 * <p>★ <b>顺序确定性</b>：先按 {@link DebtId#value()} 字典序排列，再建索引 ⇒ 同一张债务表两次查询逐值、逐序相同 （不沿用 Map 的插入序 ——
 * 那是"谁先算出来"的函数）。列表与外层表都<b>冻在赋值处</b>：读口拿到的是快照， 改不动真源，也改不动别人的查询结果。
 *
 * <p>★ <b>与 {@code ClassRow.debts} 的关系</b>：行里那份引用仍然是<b>债务人方向</b>的权威清单（放贷时写它、 偿还时按它走）；本类的 {@link
 * #byDebtor} 是它的只读派生视图，{@link #byCreditor} 是此前完全缺失的那一半。 两边都不写状态。
 */
public final class DebtIndex {

  private DebtIndex() {}

  /** 债务人侧索引：债务人 → 它欠的 {@link DebtId}（按 id 字典序）。 */
  public static Map<HouseholdId, List<DebtId>> byDebtor(Map<DebtId, Debt> debts) {
    return index(debts, Debt::debtor);
  }

  /** ★★ 债权人侧索引：债权人 → 它应收的 {@link DebtId}（按 id 字典序）—— M1.5 新增的那一半。 */
  public static Map<HouseholdId, List<DebtId>> byCreditor(Map<DebtId, Debt> debts) {
    return index(debts, Debt::creditor);
  }

  /** 建索引的**唯一实现**：两个方向只差一个"把这条债挂给哪一端"的函数 ⇒ 同一段遍历/排序/冻结只写一遍 （本仓的"唯一拼写点"纪律）。 */
  private static Map<HouseholdId, List<DebtId>> index(
      Map<DebtId, Debt> debts, Function<Debt, HouseholdId> side) {
    Objects.requireNonNull(debts, "debts");
    Objects.requireNonNull(side, "side");
    List<Debt> sorted = new ArrayList<>(debts.values());
    sorted.sort(Comparator.comparing(debt -> debt.id().value()));
    Map<HouseholdId, List<DebtId>> index = new LinkedHashMap<>();
    for (Debt debt : sorted) {
      index.computeIfAbsent(side.apply(debt), ignored -> new ArrayList<>()).add(debt.id());
    }
    Map<HouseholdId, List<DebtId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, List<DebtId>> entry : index.entrySet()) {
      frozen.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen); // ★ 冻在赋值处
  }
}

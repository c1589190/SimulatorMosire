package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.DebtContractId;
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
 * ★★ <b>债务的「双向可查」—— 从债务合同表纯派生出的两个方向索引</b>（M1.5；E4a 换成 {@link DebtContract}）。
 *
 * <p>★★ <b>为什么是纯派生、不是新状态组件</b>：{@link DebtContract} 一条记录同时带 {@code debtor} 与 {@code creditor}
 * ——“一笔债两视图同源”在<b>数据上已经成立</b>，缺的只是债权人方向的索引 （{@code ClassRow.debts} 只记债务人）。索引每次查询从合同表现算，不另存第二份真相。
 *
 * <p>★★ <b>两个方向都从同一条记录取数</b>：{@link #byDebtor} 与 {@link #byCreditor} 只是把同一条 {@link DebtContract}
 * 挂到不同的主体上 ⇒ 一笔债两个方向读到的本金 / 利率 / {@code dueCycle} / 状态 逐值相同（调用方拿 {@link DebtContractId} 回 {@code
 * EconomyData.debtContracts()} 查同一条记录）。
 *
 * <p>★ <b>顺序确定性</b>：先按 {@link DebtContractId#value()} 字典序排列，再建索引 ⇒ 同一张合同表两次查询 逐值、逐序相同（不沿用 Map
 * 的插入序）。列表与外层表都冻在赋值处：读口拿到的是快照。
 *
 * <p>★ <b>与 {@code ClassRow.debts} 的关系</b>：行里那份引用仍然是<b>债务人方向</b>的权威清单（放贷时写它、 偿还时按它走）；本类的 {@link
 * #byDebtor} 是它的只读派生视图，{@link #byCreditor} 是此前完全缺失的那一半。 两边都不写状态。
 */
public final class DebtIndex {

  private DebtIndex() {}

  /** 债务人侧索引：债务人 → 它欠的 {@link DebtContractId}（按 id 字典序）。 */
  public static Map<HouseholdId, List<DebtContractId>> byDebtor(
      Map<DebtContractId, DebtContract> debtContracts) {
    return index(debtContracts, DebtContract::debtor);
  }

  /** ★★ 债权人侧索引：债权人 → 它应收的 {@link DebtContractId}（按 id 字典序）—— M1.5 新增的那一半。 */
  public static Map<HouseholdId, List<DebtContractId>> byCreditor(
      Map<DebtContractId, DebtContract> debtContracts) {
    return index(debtContracts, DebtContract::creditor);
  }

  /** 建索引的**唯一实现**：两个方向只差一个“把这条合同挂给哪一端”的函数。 */
  private static Map<HouseholdId, List<DebtContractId>> index(
      Map<DebtContractId, DebtContract> debtContracts, Function<DebtContract, HouseholdId> side) {
    Objects.requireNonNull(debtContracts, "debtContracts");
    Objects.requireNonNull(side, "side");
    List<DebtContract> sorted = new ArrayList<>(debtContracts.values());
    sorted.sort(Comparator.comparing(contract -> contract.id().value()));
    Map<HouseholdId, List<DebtContractId>> index = new LinkedHashMap<>();
    for (DebtContract contract : sorted) {
      index.computeIfAbsent(side.apply(contract), ignored -> new ArrayList<>()).add(contract.id());
    }
    Map<HouseholdId, List<DebtContractId>> frozen = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, List<DebtContractId>> entry : index.entrySet()) {
      frozen.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen); // ★ 冻在赋值处
  }
}

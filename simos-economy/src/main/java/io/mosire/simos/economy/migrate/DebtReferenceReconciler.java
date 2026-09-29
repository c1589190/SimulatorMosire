package io.mosire.simos.economy.migrate;

import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.DebtContract;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>孤儿债对账（R4-B.3b；R3 决策单 §0.3；E4a 换成 {@link DebtContract}）</b>：以 {@code debtContracts}
 * 表为<b>唯一权威</b>重建每条 {@link ClassRow#debts()} 引用。
 *
 * <p>★★ <b>要修的病</b>：tick120 旧档存在“债权人有记录、债务人 {@code ClassRow.debts} 无引用”的孤儿债（c1）。
 * 残缺的引用会让债务相关的阶层判断读到不完整状态，故在 {@code EconomyData} 构造期一次性对账清掉。
 *
 * <p>★★ <b>权威方向（单向，不许反过来）</b>：一条合同是否存在、债务人/债权人是谁、本金多少，只由合同表回答； {@code ClassRow.debts}
 * 只是“债务人侧便于逐行读债”的<b>派生索引</b>。故本对账：
 *
 * <ol>
 *   <li>按 {@code debtor} 分组，组内按 {@link DebtContractId} 的规范串<b>升序</b>（同一份状态每次得到同一个清单）；
 *   <li>对每个家户行重建引用清单：该行作为 debtor 的合同全部写入，且只写这些 —— 表外的多余/重复/陈旧引用一律丢弃；
 *   <li>合同的 debtor/creditor 在 {@code classes} 里不存在 ⇒ <b>fail-closed 具名抛</b>（不静默核销、不转第三人、不删合同）；
 *   <li>只重建引用：合同表本身、{@code principal}、{@code status} 一字不动（本金守恒是结构性的）。
 * </ol>
 *
 * <p>★ <b>幂等</b>：一致时返回入参 {@code classes} 的<b>同一实例</b>（no-op）；不一致时返回按入参键序重建的新 map。 故重复调用、重放同一
 * revision 都不会产生第二份状态，也不会每次构造都写出无谓的 classes 增量。
 *
 * <p>★ <b>唯一实现</b>：调用点只在 {@code EconomyData} 紧凑构造器里（旧档迁移器之后、跨表守卫之前）。 这样旧档、直接构造、命令三条读入路径走的是同一段对账。
 */
public final class DebtReferenceReconciler {

  private DebtReferenceReconciler() {}

  /**
   * 以 {@code debtContracts} 表为权威重建 {@code classes} 每行的 {@code debts} 引用。
   *
   * @param debtContracts 债务合同表（键 = 稳定 id；已由调用方保证非 null；本方法不修改它）
   * @param classes 家户行表（键 = 稳定身份；已由调用方保证键/值非 null、键 == 行内 id）
   * @return 一致时 = 入参 {@code classes} 本身；否则 = 按原键序重建的新 {@link LinkedHashMap}
   * @throws IllegalArgumentException debtor 或 creditor 的家户行不存在（具名 fail-closed）
   */
  public static Map<HouseholdId, ClassRow> reconcile(
      Map<DebtContractId, DebtContract> debtContracts, Map<HouseholdId, ClassRow> classes) {
    Objects.requireNonNull(debtContracts, "debtContracts");
    Objects.requireNonNull(classes, "classes");
    // 按债务人分组；组内规范串升序 ⇒ 同一份合同表给出唯一顺序。
    Map<HouseholdId, List<DebtContractId>> byDebtor = new LinkedHashMap<>();
    for (Map.Entry<DebtContractId, DebtContract> entry : debtContracts.entrySet()) {
      DebtContract contract = entry.getValue();
      if (!classes.containsKey(contract.debtor())) {
        throw new IllegalArgumentException(
            "债务引用对账失败：合同 "
                + entry.getKey()
                + " 的债务人 "
                + contract.debtor()
                + " 在 classes 里不存在"
                + "（fail-closed：不静默核销、不转第三人、不删合同）");
      }
      if (!classes.containsKey(contract.creditor())) {
        throw new IllegalArgumentException(
            "债务引用对账失败：合同 "
                + entry.getKey()
                + " 的债权人 "
                + contract.creditor()
                + " 在 classes 里不存在"
                + "（fail-closed：不静默核销、不转第三人、不删合同）");
      }
      byDebtor.computeIfAbsent(contract.debtor(), ignored -> new ArrayList<>()).add(entry.getKey());
    }
    for (List<DebtContractId> refs : byDebtor.values()) {
      refs.sort(Comparator.comparing(DebtContractId::value));
    }
    // 逐行重建；全部一致时返回原实例（幂等 no-op 的判据）。
    Map<HouseholdId, ClassRow> rebuilt = new LinkedHashMap<>();
    boolean changed = false;
    for (Map.Entry<HouseholdId, ClassRow> entry : classes.entrySet()) {
      ClassRow row = entry.getValue();
      List<DebtContractId> expected = byDebtor.getOrDefault(entry.getKey(), List.of());
      if (row.debts().equals(expected)) {
        rebuilt.put(entry.getKey(), row);
        continue;
      }
      changed = true;
      rebuilt.put(
          entry.getKey(),
          new ClassRow(
              row.id(),
              row.view(),
              row.population(),
              row.laborMilli(),
              row.participationPerMille(),
              row.money(),
              new ArrayList<>(expected),
              row.naturalNeeds(),
              row.effectiveDemand(),
              row.cycleNaturalNeedMilli()));
    }
    return changed ? rebuilt : classes;
  }
}

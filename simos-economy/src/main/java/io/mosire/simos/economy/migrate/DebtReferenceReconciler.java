package io.mosire.simos.economy.migrate;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdDebtReference;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★ <b>孤儿债对账（R4-B.3b；R3 决策单 §0.3；E4a 换成 {@link DebtContract}；2026-10-09 选项 A 改为重建独立引用表）</b>： 以
 * {@code debtContracts} 表为<b>唯一权威</b>重建 {@code EconomyData.householdDebtRefs}（键 = {@link
 * HouseholdDebtReference}，即"该家户是该合同的债务人"这一条派生索引）。
 *
 * <p>★★ <b>要修的病</b>：tick120 旧档存在“债权人有记录、债务人行无引用”的孤儿债（c1）。残缺的引用会让债务相关的阶层判断读到不完整状态，故在 {@code
 * EconomyData} 构造期一次性对账清掉。
 *
 * <p>★★ <b>权威方向（单向，不许反过来）</b>：一条合同是否存在、债务人/债权人是谁、本金多少，只由合同表回答；引用表只是“债务人侧便于逐行读债”的 <b>派生索引</b>。故本对账：
 *
 * <ol>
 *   <li>按 {@code debtor} 分组，组内按 {@link DebtContractId} 的规范串<b>升序</b>（同一份状态每次得到同一个清单）；
 *   <li>对每个家户行重建引用清单：该行作为 debtor 的合同全部写入，且只写这些 —— 表外的多余/重复/陈旧引用一律丢弃；
 *   <li>合同的 debtor/creditor 在 {@code classes} 里不存在 ⇒ <b>fail-closed 具名抛</b>（不静默核销、不转第三人、不删合同）；
 *   <li>只重建引用：合同表本身、{@code principal}、{@code status} 一字不动（本金守恒是结构性的）。
 * </ol>
 *
 * <p>★★ <b>2026-10-09 选项 A 改了什么</b>：引用从 {@code HouseholdEconomy.debts}（行内 3.7KB / 户）搬成这张独立表（键 =
 * {@code 家户@合同}）—— 目的是让"每天只变 6 字节的行"不再拖着整份引用列表落盘（行级 {@code FieldDelta.diff} 的代价）。对账的
 * <b>结论逐字不变</b>：旧档读回时引用表由同一份合同表重建，逐值等于改前逐行重建的结果。
 *
 * <p>★ <b>幂等</b>：一致时返回入参引用表的<b>同一实例</b>（no-op）；不一致时返回按 {@code classes} 键序重建的新 {@link
 * LinkedHashMap}。故重复调用、重放同一 revision 都不会产生第二份状态，也不会每次构造都写出无谓的增量。
 *
 * <p>★ <b>唯一实现</b>：调用点只在 {@code EconomyData} 紧凑构造器里（旧档迁移器之后、跨表守卫之前）。 这样旧档、直接构造、命令三条读入路径走的是同一段对账。
 */
public final class DebtReferenceReconciler {

  /** 引用表增删的对账日志（{@code .debt} 门面 + system 来源；构造期无 day 上下文）。 */
  private static final Logger DEBT_REF = EconomyLog.debt();

  private DebtReferenceReconciler() {}

  /**
   * 以 {@code debtContracts} 表为权威重建引用表。
   *
   * @param debtContracts 债务合同表（键 = 稳定 id；已由调用方保证非 null；本方法不修改它）
   * @param householdEconomies 家户行表（键 = 稳定身份；已由调用方保证键/值非 null、键 == 行内 id）
   * @param householdDebtRefs 当前引用表（键 = {@link HouseholdDebtReference}，值 = 标记位；不得为 null）
   * @return 一致时 = 入参 {@code householdDebtRefs} 本身；否则 = 按 {@code classes} 键序重建的新 {@link
   *     LinkedHashMap}
   * @throws IllegalArgumentException debtor 或 creditor 的家户行不存在（具名 fail-closed）
   */
  public static Map<HouseholdDebtReference, Boolean> reconcile(
      Map<DebtContractId, DebtContract> debtContracts,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdDebtReference, Boolean> householdDebtRefs) {
    Objects.requireNonNull(debtContracts, "debtContracts");
    Objects.requireNonNull(householdEconomies, "classes");
    Objects.requireNonNull(householdDebtRefs, "householdDebtRefs");
    // 按债务人分组；组内规范串升序 ⇒ 同一份合同表给出唯一顺序。
    Map<HouseholdId, List<DebtContractId>> byDebtor = new LinkedHashMap<>();
    for (Map.Entry<DebtContractId, DebtContract> entry : debtContracts.entrySet()) {
      DebtContract contract = entry.getValue();
      if (!householdEconomies.containsKey(contract.debtor())) {
        throw new IllegalArgumentException(
            "债务引用对账失败：合同 "
                + entry.getKey()
                + " 的债务人 "
                + contract.debtor()
                + " 在 classes 里不存在"
                + "（fail-closed：不静默核销、不转第三人、不删合同）");
      }
      if (!householdEconomies.containsKey(contract.creditor())) {
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
    // 逐户重建（键序 = classes 的键序；一户之内 = 合同 id 升序）⇒ 同一份状态恒得同一份表。
    Map<HouseholdDebtReference, Boolean> rebuilt = new LinkedHashMap<>();
    for (HouseholdId household : householdEconomies.keySet()) {
      for (DebtContractId contractId : byDebtor.getOrDefault(household, List.of())) {
        rebuilt.put(new HouseholdDebtReference(household, contractId), Boolean.TRUE);
      }
    }
    if (rebuilt.equals(householdDebtRefs)) {
      return householdDebtRefs; // 幂等 no-op：一致 ⇒ 同一实例
    }
    logRebuild(householdDebtRefs, rebuilt);
    return rebuilt;
  }

  /**
   * ★★ <b>跨表守卫（构造期兜底断言）</b>：引用表里每一条引用都必须指向<b>已存在</b>的合同，且合同的债务人就是该键的家户、该家户行存在。
   *
   * <p>★ 它跑在 {@link #reconcile} <b>之后</b>（守卫的语义与 E4a 之前那条"ClassRow.debts 引用了不存在的债务合同"逐条相同：引用表是派生索引，
   * 对账负责修、守卫负责兜底断言）。★ 它同时是<b>可独立调用</b>的：拆表不允许把关卡拆松，故把这段判据收成一个具名方法， 让"引用悬空 ⇒ 具名抛"这件事可以被单独验证。
   *
   * @param debtContracts 债务合同表（权威）
   * @param householdEconomies 家户行表
   * @param householdDebtRefs 引用表
   * @throws IllegalArgumentException 悬空引用 / 债务人错挂 / 家户行不存在（具名 fail-closed）
   */
  public static void requireReferencesResolvable(
      Map<DebtContractId, DebtContract> debtContracts,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdDebtReference, Boolean> householdDebtRefs) {
    Objects.requireNonNull(debtContracts, "debtContracts");
    Objects.requireNonNull(householdEconomies, "classes");
    Objects.requireNonNull(householdDebtRefs, "householdDebtRefs");
    for (Map.Entry<HouseholdDebtReference, Boolean> entry : householdDebtRefs.entrySet()) {
      HouseholdDebtReference ref = entry.getKey();
      DebtContract contract = debtContracts.get(ref.contract());
      if (contract == null) {
        throw new IllegalArgumentException(
            "householdDebtRefs 引用了不存在的债务合同（v2 spec §八.2）："
                + ref.household()
                + " → "
                + ref.contract()
                + "（fail-closed：不静默核销、不放行悬空引用）");
      }
      if (!ref.household().equals(contract.debtor())) {
        throw new IllegalArgumentException(
            "householdDebtRefs 的引用挂错了债务人（v2 spec §八.2）：键里的家户="
                + ref.household()
                + "，合同的债务人="
                + contract.debtor()
                + "，合同="
                + ref.contract());
      }
      if (!householdEconomies.containsKey(ref.household())) {
        throw new IllegalArgumentException(
            "householdDebtRefs 引用了不存在的家户行（v2 spec §八.2）：" + ref.household());
      }
    }
  }

  /**
   * 引用表增删的对账日志（§一.9：新状态写口至少一条"发生了什么 + 具名计数"）。
   *
   * <p>★ 级别 INFO：它每个 revision 边界最多一条（对账是构造期一次性动作），且只在<b>引用真的变了</b>时发射 —— 不是逐笔噪声。
   * 逐条明细（哪一户加了哪条）不在这里：那是 TRACE 面，且 {@code DebtContractBook} 已逐笔记了 DEBT_UPSERT / DEBT_REDUCE。
   */
  private static void logRebuild(
      Map<HouseholdDebtReference, Boolean> before, Map<HouseholdDebtReference, Boolean> after) {
    Set<HouseholdId> touched = new LinkedHashSet<>();
    int added = 0;
    for (HouseholdDebtReference ref : after.keySet()) {
      if (!before.containsKey(ref)) {
        added++;
        touched.add(ref.household());
      }
    }
    int removed = 0;
    for (HouseholdDebtReference ref : before.keySet()) {
      if (!after.containsKey(ref)) {
        removed++;
        touched.add(ref.household());
      }
    }
    EventLog.channel(DEBT_REF)
        .info(
            LogEvent.of(
                "DEBT_REFERENCES_REBUILT",
                EconomyLogSource.ECONOMY_DEBT_REFERENCE,
                "households",
                touched.size(),
                "added",
                added,
                "removed",
                removed,
                "total",
                after.size()));
  }
}

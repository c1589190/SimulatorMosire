package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.EconomySettlement.CREDITOR_EXTINCT_DEBT_WRITE_OFF_REASON;
import static io.mosire.simos.economy.time.EconomySettlement.DEATH_DEBT_WRITE_OFF_REASON;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DebtIndex;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 由 EconomySettlement 按职责机械抽取；包内实现，不构成跨模块 API。 */
final class PopulationDebtWriteOffSettlement {

  /**
   * ★★★ <b>W8：户绝 ⇒ <b>双向</b>销账（用户裁定「穷人的话，死了账一并消除」）</b>。
   *
   * <pre>
   * ① 债务人侧：复用 P5 既有语义（{@link #writeOffDebtsForDeaths}，population = 死亡前人口、deaths = 社会报来的死亡）
   *    —— 本分支只在"写后人口 == 0"时进入 ⇒ deaths ≥ populationBefore ⇒ survivors = populationBefore − deaths ≤ 0
   *    ⇒ 逐笔**全额减免**（forgive 到 0 ⇒ 状态 FORGIVEN），写口仍是 {@link DebtContractBook#forgive}。
   * ② 债权人侧：该户作为**债权人**的全部应收一并减免（同一条写口，原因 {@link #CREDITOR_EXTINCT_DEBT_WRITE_OFF_REASON}）。
   * </pre>
   *
   * <p>★★ <b>为什么②是控制方口径、可被用户推翻</b>：用户原话只说了"死了账一并消除"，没说方向。本批按双向实现， 理由是户绝后<b>没有任何主体</b>能主张或承担这笔账 ——
   * 留着它就会出现调查报告 §2.5 那种读数："一个 0 人口的家户 仍是 1.04×10¹² silver 的债权人，而活着的债务人还在为它复利"。★ 两个方向用**不同**的 reason
   * 常量命名 ⇒ 用户日后 只推翻一侧时，审计/读口能把它们分开。★ 见实现账本的"偏离记录"。
   *
   * <p>★★ <b>只动本金与状态</b>：不碰账户、库存、发行审计、转移流水（调查报告 §2.8 已证 {@code forgive} 的语义如此） ⇒ 货币守恒读数逐值不变；变的是
   * {@code debtContracts} 本金合计、{@code DebtStock.totalPrincipal} 与会话瞬态 {@code debtWriteOffs}。
   *
   * <p>★ <b>{@code debtWriteOffs} 的键仍是债务人</b>（那是 {@link EconomySession#debtWriteOffs()} 的既有契约： "键 =
   * 本金被删掉的债务人"）。债权人侧消除的受益人是**债务人**（它的本金掉了）⇒ 记在债务人名下，方向由本方法的 具名 INFO 的 {@code
   * creditorPrincipalByUnit} 字段区分，不靠累加器的键。
   *
   * @param session 经济会话（就地更新合同表工作副本与瞬态删债累加器）
   * @param household 户绝的家户（= 债务人侧与债权人侧的两个身份）
   * @param populationBefore 户绝前人口（&gt; 0）
   * @param deaths 社会报来的死亡数（&gt; 0；本分支保证 ≥ populationBefore）
   * @param day 世界日（具名 INFO 的 day 字段）
   */
  static void writeOffExtinctHouseholdDebts(
      EconomySession session, HouseholdId household, long populationBefore, long deaths, long day) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(household, "household");
    LinkedHashMap<DebtContractId, DebtContract> debts = session.sheet().debtContracts();
    LinkedHashMap<HouseholdId, Long> debtWriteOffs = session.debtWriteOffs();
    // ★ 先量后删：本分支保证 survivors ≤ 0 ⇒ 逐笔全额减免，故"删前本金"逐值等于"被减免额"（不重写 P5 的算式）。
    Map<HouseholdId, List<DebtContractId>> debtsByDebtor =
        debts.isEmpty() ? Map.of() : DebtIndex.byDebtor(debts);
    LinkedHashMap<String, Long> debtorPrincipalByUnit = new LinkedHashMap<>();
    long debtorContracts = 0L;
    for (DebtContractId id : debtsByDebtor.getOrDefault(household, List.of())) {
      DebtContract debt = debts.get(id);
      if (debt == null || !debt.debtor().equals(household) || debt.principal() <= 0L) {
        continue;
      }
      debtorPrincipalByUnit.merge(debt.unit().key(), debt.principal(), Math::addExact);
      debtorContracts++;
    }
    // ① 债务人侧（复用 P5：population=死亡前人口、deaths=社会报来的死亡 ⇒ survivors=0 ⇒ 全额减免）。
    writeOffDebtsForDeaths(
        debts,
        debtsByDebtor.getOrDefault(household, List.of()),
        household,
        populationBefore,
        deaths,
        debtWriteOffs);
    // ② 债权人侧（控制方口径）：户绝后没有主体能主张这笔应收。
    LinkedHashMap<String, Long> creditorPrincipalByUnit = new LinkedHashMap<>();
    long creditorContracts = 0L;
    if (!debts.isEmpty()) {
      for (Map.Entry<HouseholdId, List<DebtContractId>> side :
          DebtIndex.byCreditor(debts).entrySet()) {
        if (!side.getKey().equals(household)) {
          continue;
        }
        for (DebtContractId id : side.getValue()) {
          DebtContract debt = debts.get(id);
          if (debt == null || !debt.creditor().equals(household)) {
            continue;
          }
          long principal = debt.principal();
          if (principal <= 0L) {
            continue; // 含自持合同（debtor == creditor == 本户）：① 已把它全额减免 ⇒ 这里本金已是 0，不重复计
          }
          DebtContractBook.forgive(debts, id, principal, CREDITOR_EXTINCT_DEBT_WRITE_OFF_REASON);
          debtWriteOffs.merge(debt.debtor(), principal, Math::addExact);
          creditorPrincipalByUnit.merge(debt.unit().key(), principal, Math::addExact);
          creditorContracts++;
        }
      }
    }
    EventLog.channel(EconomyLog.population())
        .info(
            LogEvent.of(
                "HOUSEHOLD_EXTINCT_DEBT_WRITTEN_OFF",
                EconomyLogSource.ECONOMY_POPULATION_WRITE,
                "day",
                day,
                "household",
                household.value(),
                "populationBefore",
                populationBefore,
                "deaths",
                deaths,
                "cause",
                "death",
                "debtorSideContracts",
                debtorContracts,
                "debtorPrincipalByUnit",
                debtorPrincipalByUnit,
                "creditorSideContracts",
                creditorContracts,
                "creditorPrincipalByUnit",
                creditorPrincipalByUnit,
                "why",
                "w8:extinct-household-debt-and-receivable-eliminated (social-authoritative deaths>0)"));
  }

  /**
   * ★★ <b>W8：某户人口归零但<b>不是死亡</b>（迁移/搬走）⇒ 具名记"债务保留"</b>——这是"迁移不销账"那条判据的 可观测面（与 {@link
   * #writeOffExtinctHouseholdDebts} 的 INFO 成对；调查报告 D6：不加分辨就会把阶层/户籍流动读成户绝）。
   *
   * <p>★ 债务按比例随人走（{@code LotMigrationBook.moveDebts} / {@code ModeMigrationSettlement.moveDebt}），
   * 留在空户名下的那部分**不减记**、但也不再计息（W8 ③：{@code chargeInterest} 跳过 population ≤ 0 的行）。
   *
   * <p>★ <b>day 字段只在知道世界日时写</b>（D4 纪律：绝不拿假日凑数）—— 迁移走二参重载时调用方给的是 {@link #NO_WORLD_DAY}，此时本事件**不带
   * day**（与 {@code HOUSEHOLD_VITAL_WRITEBACK} 的守卫同一口径）。
   */
  static void logEmptiedHouseholdDebtRetained(
      EconomySession session, HouseholdId household, long populationBefore, long day) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(household, "household");
    LinkedHashMap<DebtContractId, DebtContract> debts = session.sheet().debtContracts();
    LinkedHashMap<String, Long> retainedPrincipalByUnit = new LinkedHashMap<>();
    long contracts = 0L;
    for (DebtContract debt : debts.values()) {
      if (!debt.debtor().equals(household) || debt.principal() <= 0L) {
        continue;
      }
      retainedPrincipalByUnit.merge(debt.unit().key(), debt.principal(), Math::addExact);
      contracts++;
    }
    List<Object> fields = new ArrayList<>();
    if (day >= 0L) {
      fields.add("day");
      fields.add(day);
    }
    fields.add("household");
    fields.add(household.value());
    fields.add("populationBefore");
    fields.add(populationBefore);
    fields.add("cause");
    fields.add("relocation-not-death");
    fields.add("contracts");
    fields.add(contracts);
    fields.add("retainedPrincipalByUnit");
    fields.add(retainedPrincipalByUnit);
    fields.add("why");
    fields.add("w8:empty-household-without-deaths-keeps-debt (migration-carries-its-share)");
    EventLog.channel(EconomyLog.population())
        .info(
            LogEvent.of(
                "HOUSEHOLD_EMPTIED_DEBT_RETAINED",
                EconomyLogSource.ECONOMY_POPULATION_WRITE,
                fields.toArray()));
  }

  /**
   * ★★ <b>W3-fix：校验"社会报来的逐户生死原值表"</b>（键必须落在经济家户行上；值非 null、&gt; 0）——坏项在写任何一行之前具名拒 （ERROR 日志 + 异常，与逐户
   * delta 表同一条 fail-closed 口径）。
   *
   * <p>★ <b>为什么键必须在行上</b>：本步只往**已存在的家户行**落流水（不凭空建行）—— 报来一个没有行的家户 = Social/Economy
   * 投影不一致的装配故障，静默跳过会让"社会说死了人、经济流水里没有"这种半落账状态留下来。
   *
   * @param counts 逐户出生或死亡原值表（只含 &gt; 0 的家户）；不得为 null
   * @param field 表名（只进日志/异常文本，便于具名定位）
   * @param householdEconomies 经济家户行工作表
   */
  static void requireReportedVitalCounts(
      Map<HouseholdId, Long> counts,
      String field,
      LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies) {
    for (Map.Entry<HouseholdId, Long> entry : counts.entrySet()) {
      HouseholdId household = entry.getKey();
      Long count = entry.getValue();
      String reason = null;
      if (household == null) {
        reason = "null-household-key";
      } else if (count == null) {
        reason = "null-count";
      } else if (count < 0L) {
        reason = "negative-count";
      } else if (count == 0L) {
        reason = "zero-count";
      } else if (!householdEconomies.containsKey(household)) {
        reason = "unknown-household-row";
      }
      if (reason == null) {
        continue;
      }
      EventLog.channel(EconomyLog.population())
          .error(
              LogEvent.of(
                  "HOUSEHOLD_VITAL_WRITEBACK_REJECTED",
                  EconomyLogSource.ECONOMY_POPULATION_WRITE,
                  "reason",
                  reason,
                  "field",
                  field,
                  "household",
                  household == null ? "-" : household));
      throw new IllegalArgumentException(
          "报来的逐户生死原值表不合法（拒绝落账）: field="
              + field
              + " reason="
              + reason
              + " household="
              + household
              + " count="
              + count);
    }
  }

  /**
   * ★★ <b>P5：死亡按人口比例删债（逐笔整数口径）</b>——只缩减「{@code debtor ==} 本行」的全部活跃合同。
   *
   * <pre>
   * survivors   = population − deaths                     // population = 本行死亡前人口
   * newPrincipal = survivors == 0 ? 0 : ⌊principal × survivors ÷ population⌋   // 逐笔向下取整
   * forgiven     = principal − newPrincipal                // 0 本金合同不产生"空减免"
   * </pre>
   *
   * <p>★★ <b>为什么逐笔算、不先合计再摊</b>：合同是各自独立的连续欠账，逐笔 floor 是探针口径的逐值投影 （探针 {@code household.debt = debt ×
   * afterPop ÷ beforePop} 也是整户一笔、直接乘除）。同一家户多笔合同用 <b>同一个</b> survivors/population ⇒ 比例一致；合计删债 = Σ
   * 逐笔差值（由调用方逐笔累加，不再二次取整）。
   *
   * <p>★★ <b>只走唯一写口</b>：{@link DebtContractBook#forgive} 负责本金下溢守卫、状态迁移（减到 0 ⇒ {@code
   * FORGIVEN}）与具名原因；本方法<b>不碰</b>账户、库存、流水或任何发行审计 —— 死亡删债不是还款、也不是发行。
   *
   * <p>★ <b>确定性</b>：{@code debtorContracts} 来自只读索引（按 id 规范串排序，见 {@link DebtIndex#byDebtor}），
   * 且逐笔只依赖自己的本金；因此同一份输入无论分区/迭代序都给出同一组删债额。
   *
   * @param debts 合同表工作副本（就地更新；唯一写口）
   * @param debtorContracts 该债务人的合同 id 清单（只读派生；合同表仍是权威）
   * @param debtor 本行家户（＝债务人）
   * @param population 本行死亡前人口（&gt; 0）
   * @param deaths 本行摊到的死亡数（**社会报来的死亡**按人口权重摊到本行的那一份；≤ population；来自 {@link #allocate}）——
   *     它是"经济侧为**已死的人**做债务善后"的输入，不是"经济侧决定死多少人"（判死在 Social）
   * @param debtWriteOffs 会话瞬态删债累加器（就地更新；键 = 债务人）
   */
  static void writeOffDebtsForDeaths(
      Map<DebtContractId, DebtContract> debts,
      List<DebtContractId> debtorContracts,
      HouseholdId debtor,
      long population,
      long deaths,
      LinkedHashMap<HouseholdId, Long> debtWriteOffs) {
    Objects.requireNonNull(debts, "debts");
    Objects.requireNonNull(debtor, "debtor");
    Objects.requireNonNull(debtWriteOffs, "debtWriteOffs");
    if (deaths <= 0L || debtorContracts.isEmpty()) {
      return;
    }
    if (population <= 0L) {
      throw new IllegalStateException(
          "死亡删债的死亡前人口必须 > 0：家户=" + debtor + " 人口=" + population + " 死亡=" + deaths);
    }
    long survivors =
        population - deaths; // 调用方保证 deaths ≤ population（allocate 按人口权重切，见 allocate 的注释）
    for (DebtContractId id : debtorContracts) {
      DebtContract debt = debts.get(id);
      if (debt == null || !debt.debtor().equals(debtor)) {
        continue; // 索引是只读派生；合同表是权威。取不到/债务人漂开都不在这里"顺手修"
      }
      long principal = debt.principal();
      if (principal <= 0L) {
        continue; // 已结清/已减免/无本金：没有可删的活跃本金
      }
      long after =
          survivors <= 0L ? 0L : proportionalPrincipalAfterDeaths(principal, survivors, population);
      long forgiven = principal - after;
      if (forgiven <= 0L) {
        continue; // 人口太小、floor 后本金未变：不写 0 减免（forgive 的 amount 必须 > 0）
      }
      DebtContractBook.forgive(debts, id, forgiven, DEATH_DEBT_WRITE_OFF_REASON);
      debtWriteOffs.merge(debtor, forgiven, Math::addExact);
    }
  }

  /**
   * ★ P5：{@code ⌊principal × survivors ÷ population⌋} 的**不溢出**逐笔整数算法（结果与探针的直接乘除逐值相同）。
   *
   * <pre>
   * whole = principal ÷ population；remainder = principal mod population
   * result = whole × survivors + ⌊remainder × survivors ÷ population⌋
   * </pre>
   *
   * <p>{@code whole × survivors ≤ principal}（因 {@code survivors ≤ population}）⇒ 主项不溢出；尾项 {@code
   * remainder × survivors} 用 {@link Math#multiplyExact} 在极端值上 fail-closed（不静默绕回）。
   */
  static long proportionalPrincipalAfterDeaths(long principal, long survivors, long population) {
    if (survivors <= 0L) {
      return 0L;
    }
    if (survivors >= population) {
      return principal;
    }
    long whole = principal / population;
    long remainder = principal % population;
    long tail = Math.multiplyExact(remainder, survivors) / population;
    return Math.addExact(Math.multiplyExact(whole, survivors), tail);
  }
}

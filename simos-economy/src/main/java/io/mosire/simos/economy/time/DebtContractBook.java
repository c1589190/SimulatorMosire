package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.DebtContract;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.Predicate;

/**
 * ★★ <b>E4c：债务合同的唯一写口</b>（理想架构 §2.7；计划 §4「债务只走 upsertDebt」）。本类之外，生产代码 <b>不得再对债务工作表调用 {@code
 * put}/{@code putAll}</b> —— 身份拼写、本金增减、状态迁移、下溢/溢出守卫全部收在这一处。
 *
 * <pre>
 * 身份   id = DebtContractId.idOf(debtor, creditor, unit, terms)
 *       同一四元组跨周期恒同一条；不同 unit / terms 必然不同条（编码单射由 DebtContractId 保证）
 * upsert 本金增加：借入 / 欠租欠薪资本化 / 利息并入（利息走 compoundInterest，多写 lastInterestDay）
 * reduce 本金减少：偿还 / 减免（部分）；剩余为 0 且未违约 ⇒ SETTLED
 * forgive 本金减少：全额到 0 ⇒ FORGIVEN；部分 ⇒ 状态原样保留（原因在返回值里具名）
 * absorb 并行分区结果的落回口：按分区终值替换快照，不重新计算本金
 * </pre>
 *
 * <p>★★ <b>再借语义（显式）</b>：同一四元组在 {@link DebtStatus#SETTLED}/{@link DebtStatus#FORGIVEN} 之后再次借入 ⇒ 本金在
 * 0 上加回并把状态<b>重新激活为 {@link DebtStatus#NORMAL}</b>（这是一笔新的连续欠账，{@code openedDay} 仍是首借日、{@code
 * lastInterestDay} 原样保留）。{@link DebtStatus#DEFAULTED}/{@link DebtStatus#DELINQUENT}
 * 的条在追加借入时<b>状态不变</b>：追加本金不洗白违约/逾期；要洗白必须由明确的状态写口做。
 *
 * <p>★★ <b>到期周期 ({@code dueCycle}) 的覆盖规则（显式）</b>：调用方传入非空 ⇒ <b>覆盖</b>合同当前 {@code
 * dueCycle}（借粮路径的最新一笔到期）；传入空 ⇒ <b>保留</b>原值（条款自身写死了期限的合同不被滚动字段改掉）。
 *
 * <p>★★ <b>它不搬任何粮/钱、不碰任何库存</b>：所有方法只写传入的债务工作表（以及 {@link #withDebtReference}
 * 返回的家户行副本）。利息/资本化/减免都只是本金与状态的变化；真实的粮/钱换手仍只走 {@code 旧结算引擎（R3a 已删除）.applyTransfer}。
 *
 * <p>★★ <b>与派生读物的关系</b>：{@link io.mosire.simos.economy.model.DebtIndex} 是纯派生（只在读时从合同表现算）； {@link
 * io.mosire.simos.economy.migrate.DebtReferenceReconciler} 只在 {@code EconomyData} 构造期按合同表重建 {@code
 * HouseholdEconomy.debts}。结算会话内每建一条新合同，调用方用 {@link #withDebtReference} 把债务人行的派生引用补上； 持久化时仍以合同表为唯一权威。
 */
public final class DebtContractBook {

  /** 逐笔债务变动日志（debt 分类；TRACE 用于对账，DEBUG 用于状态/核销）。 */
  private static final org.slf4j.Logger DEBT = EconomyLog.debt();

  private DebtContractBook() {}

  /**
   * ★★ <b>本金增加写口</b>：同一 {@code (debtor, creditor, unit, terms)} 命中同一条并累加本金；不存在则建条。
   *
   * <pre>
   * 新条：principal = amount，openedDay = day，lastInterestDay = empty，dueCycle = 传入值（可空），status = NORMAL
   * 旧条：principal = addExact(旧本金, amount)
   *       SETTLED / FORGIVEN ⇒ status = NORMAL（显式再借激活；见类注）
   *       NORMAL / DELINQUENT / DEFAULTED ⇒ status 不变（追加本金不洗白违约）
   *       dueCycle = 传入非空 ? 覆盖 : 保留
   *       openedDay / lastInterestDay 保留
   * </pre>
   *
   * @param contracts 债务工作表（就地更新；键 = 值内 id）；不得为 null
   * @param debtor 债务人；不得为 null
   * @param creditor 债权人；不得为 null
   * @param unit 计量单位；不得为 null
   * @param terms 条款（参与身份）；不得为 null
   * @param amount 增加的本金；必须 {@code > 0}（0/负增量不是一种本金变化）
   * @param day 本次借入/资本化发生日（只在新条时进 {@code openedDay}）；不得为负
   * @param dueCycle 合同当前约定到期周期；空 = 保留旧条原值
   * @return 更新后的合同（也是写回 {contracts} 的那一条）
   */
  public static DebtContract upsert(
      Map<DebtContractId, DebtContract> contracts,
      HouseholdId debtor,
      HouseholdId creditor,
      DebtUnit unit,
      DebtTerms terms,
      long amount,
      long day,
      OptionalLong dueCycle) {
    Objects.requireNonNull(contracts, "contracts 不得为 null");
    Objects.requireNonNull(debtor, "debtor 不得为 null");
    Objects.requireNonNull(creditor, "creditor 不得为 null");
    Objects.requireNonNull(unit, "unit 不得为 null");
    Objects.requireNonNull(terms, "terms 不得为 null");
    if (amount <= 0L) {
      throw new IllegalArgumentException("DebtContractBook.upsert 的本金增量必须 > 0: " + amount);
    }
    if (day < 0L) {
      throw new IllegalArgumentException("DebtContractBook.upsert 的 day 不得为负: " + day);
    }
    if (dueCycle == null) {
      dueCycle = OptionalLong.empty();
    }
    DebtContractId id = DebtContractId.idOf(debtor, creditor, unit, terms);
    DebtContract existing = contracts.get(id);
    DebtContract next;
    if (existing == null) {
      next =
          new DebtContract(
              id,
              debtor,
              creditor,
              unit,
              terms,
              amount,
              day,
              OptionalLong.empty(),
              dueCycle,
              DebtStatus.NORMAL);
    } else {
      requireSameIdentity(id, existing);
      long principal = Math.addExact(existing.principal(), amount);
      DebtStatus status =
          existing.status() == DebtStatus.SETTLED || existing.status() == DebtStatus.FORGIVEN
              ? DebtStatus.NORMAL
              : existing.status();
      next =
          new DebtContract(
              existing.id(),
              existing.debtor(),
              existing.creditor(),
              existing.unit(),
              existing.terms(),
              principal,
              existing.openedDay(),
              existing.lastInterestDay(),
              dueCycle.isPresent() ? dueCycle : existing.dueCycle(),
              status);
    }
    contracts.put(id, next);
    if (DEBT.isTraceEnabled()) {
      DEBT.trace(
          "event=DEBT_UPSERT new={} id={} debtor={} creditor={} unit={} amount={} principal={} ratePerMille={} dueCycle={} status={}",
          existing == null,
          id.value(),
          debtor.value(),
          creditor.value(),
          unit.key(),
          amount,
          next.principal(),
          terms.interestRatePerMillePerCycle(),
          next.dueCycle(),
          next.status());
    }
    return next;
  }

  /**
   * ★★ <b>本金减少写口（偿还/部分减免）</b>：
   *
   * <pre>
   * remaining = principal − amount                 // amount > principal ⇒ 抛（下溢守卫，不静默归零）
   * remaining == 0 且当前状态非 DEFAULTED ⇒ SETTLED // 与旧粮债偿还路径逐值相同：违约标记不被静默洗白
   * remaining == 0 且当前状态是 DEFAULTED ⇒ 保持 DEFAULTED（要转 SETTLED 走 markStatus）
   * remaining > 0 ⇒ 状态原样保留
   * </pre>
   *
   * @param contracts 债务工作表（就地更新）；不得为 null
   * @param id 合同稳定身份；必须已在表中
   * @param amount 减少的本金；{@code ≥ 0}（0 = no-op）；不得超过当前本金
   * @return 更新后的合同（{@code amount == 0} 时 = 原合同）
   */
  public static DebtContract reduce(
      Map<DebtContractId, DebtContract> contracts, DebtContractId id, long amount) {
    Objects.requireNonNull(contracts, "contracts 不得为 null");
    Objects.requireNonNull(id, "id 不得为 null");
    if (amount < 0L) {
      throw new IllegalArgumentException("DebtContractBook.reduce 的 amount 不得为负: " + amount);
    }
    DebtContract debt = requireContract(contracts, id);
    if (amount == 0L) {
      return debt;
    }
    if (amount > debt.principal()) {
      throw new IllegalArgumentException(
          "DebtContractBook.reduce 不得超过本金（下溢守卫）：合同="
              + id
              + " 本金="
              + debt.principal()
              + " 减本="
              + amount);
    }
    long remaining = debt.principal() - amount;
    DebtStatus status = debt.status();
    if (remaining == 0L && status != DebtStatus.DEFAULTED && status != DebtStatus.FORGIVEN) {
      status = DebtStatus.SETTLED;
    }
    DebtContract next =
        new DebtContract(
            debt.id(),
            debt.debtor(),
            debt.creditor(),
            debt.unit(),
            debt.terms(),
            remaining,
            debt.openedDay(),
            debt.lastInterestDay(),
            debt.dueCycle(),
            status);
    contracts.put(id, next);
    if (DEBT.isTraceEnabled()) {
      DEBT.trace(
          "event=DEBT_REDUCE id={} debtor={} creditor={} unit={} amount={} remaining={} status={}",
          id.value(),
          debt.debtor().value(),
          debt.creditor().value(),
          debt.unit().key(),
          amount,
          remaining,
          status);
    }
    return next;
  }

  /**
   * ★★ <b>利息并入本金</b>（{@code chargeInterest} 的唯一写点）：本金 {@code addExact(+charged)}、 {@code
   * lastInterestDay = day}；{@code dueCycle} 与其余字段不变。它<b>不是</b> {@link #upsert}（不做四元组身份拼写，
   * 也不因"再借"而重置 {@code dueCycle}/{@code openedDay}）。
   *
   * <p>★★ <b>状态语义（写清）</b>：本金因利息重新变为正数 ⇒ {@link DebtStatus#SETTLED}/{@link DebtStatus#FORGIVEN}
   * 必须重新激活为 {@link DebtStatus#NORMAL}（否则读口出现"已结清却本金 > 0"的自相矛盾；同日先还清、后按当日起始本金计息， 正好会走到这一步）。{@link
   * DebtStatus#DEFAULTED}/{@link DebtStatus#DELINQUENT} 保持原状态（计息不洗白违约/逾期）。
   *
   * @param contracts 债务工作表（就地更新）；不得为 null
   * @param id 合同稳定身份；必须已在表中
   * @param charged 并入的利息；{@code ≥ 0}（0 = no-op）；加法溢出 ⇒ 抛
   * @param day 本次真的并入利息的日；不得为负
   * @return 更新后的合同（{@code charged == 0} 时 = 原合同）
   */
  public static DebtContract compoundInterest(
      Map<DebtContractId, DebtContract> contracts, DebtContractId id, long charged, long day) {
    Objects.requireNonNull(contracts, "contracts 不得为 null");
    Objects.requireNonNull(id, "id 不得为 null");
    if (charged < 0L) {
      throw new IllegalArgumentException(
          "DebtContractBook.compoundInterest 的 charged 不得为负: " + charged);
    }
    if (day < 0L) {
      throw new IllegalArgumentException("DebtContractBook.compoundInterest 的 day 不得为负: " + day);
    }
    DebtContract debt = requireContract(contracts, id);
    if (charged == 0L) {
      return debt;
    }
    long principal = Math.addExact(debt.principal(), charged);
    DebtStatus status =
        debt.status() == DebtStatus.SETTLED || debt.status() == DebtStatus.FORGIVEN
            ? DebtStatus.NORMAL
            : debt.status();
    DebtContract next =
        new DebtContract(
            debt.id(),
            debt.debtor(),
            debt.creditor(),
            debt.unit(),
            debt.terms(),
            principal,
            debt.openedDay(),
            OptionalLong.of(day),
            debt.dueCycle(),
            status);
    contracts.put(id, next);
    if (DEBT.isTraceEnabled()) {
      DEBT.trace(
          "event=DEBT_INTEREST id={} debtor={} creditor={} unit={} charged={} principal={} day={} status={}",
          id.value(),
          debt.debtor().value(),
          debt.creditor().value(),
          debt.unit().key(),
          charged,
          principal,
          day,
          status);
    }
    return next;
  }

  /**
   * ★★ <b>只换状态</b>（退出处置的 DEFAULTED / SETTLED 写点；本金与其余字段一字不动）。
   *
   * <p>★ 两条结构守卫：{@link DebtStatus#SETTLED} 与 {@link DebtStatus#FORGIVEN} 只允许在本金为 0 时写
   * ——「本金还在却已结清/已减免」会让读口给出自相矛盾的结论。
   *
   * @param contracts 债务工作表（就地更新）；不得为 null
   * @param id 合同稳定身份；必须已在表中
   * @param status 新状态；不得为 null
   * @return 更新后的合同
   */
  public static DebtContract markStatus(
      Map<DebtContractId, DebtContract> contracts, DebtContractId id, DebtStatus status) {
    Objects.requireNonNull(contracts, "contracts 不得为 null");
    Objects.requireNonNull(id, "id 不得为 null");
    Objects.requireNonNull(status, "status 不得为 null");
    DebtContract debt = requireContract(contracts, id);
    if ((status == DebtStatus.SETTLED || status == DebtStatus.FORGIVEN) && debt.principal() > 0L) {
      throw new IllegalArgumentException(
          "DebtContractBook.markStatus 不允许本金 > 0 的合同标成 "
              + status
              + "（先 reduce/forgive 到 0）：合同="
              + id
              + " 本金="
              + debt.principal());
    }
    if (debt.status() == status) {
      return debt;
    }
    DebtContract next =
        new DebtContract(
            debt.id(),
            debt.debtor(),
            debt.creditor(),
            debt.unit(),
            debt.terms(),
            debt.principal(),
            debt.openedDay(),
            debt.lastInterestDay(),
            debt.dueCycle(),
            status);
    contracts.put(id, next);
    if (DEBT.isDebugEnabled()) {
      DEBT.debug(
          "event=DEBT_STATUS id={} debtor={} creditor={} unit={} principal={} from={} to={}",
          id.value(),
          debt.debtor().value(),
          debt.creditor().value(),
          debt.unit().key(),
          debt.principal(),
          debt.status(),
          status);
    }
    return next;
  }

  /**
   * ★★ <b>减免（部分或全额）</b>：本金 −{@code amount}；减到 0 ⇒ {@link DebtStatus#FORGIVEN}。
   *
   * <p>★★ <b>部分减免的状态语义（写清）</b>：部分减免<b>不</b>把合同标成 FORGIVEN（它还没被免除完）， 原状态（NORMAL / DELINQUENT /
   * DEFAULTED）原样保留；剩余本金继续按合同条款计息/偿还。 减免的<b>金额与原因</b>由返回的 {@link Forgiveness} 具名给出；E4c
   * 不新增持久审计组件（E5/E6 的清算/GM 工具 复用本写口时，把返回值记进各自的审计通道）。
   *
   * @param contracts 债务工作表（就地更新）；不得为 null
   * @param id 合同稳定身份；必须已在表中
   * @param amount 减免金额；{@code > 0} 且 ≤ 当前本金
   * @param reason 减免原因；不得空白
   * @return 减免结果（含减免前/后本金、状态与原因）
   */
  public static Forgiveness forgive(
      Map<DebtContractId, DebtContract> contracts, DebtContractId id, long amount, String reason) {
    Objects.requireNonNull(contracts, "contracts 不得为 null");
    Objects.requireNonNull(id, "id 不得为 null");
    if (amount <= 0L) {
      throw new IllegalArgumentException("DebtContractBook.forgive 的 amount 必须 > 0: " + amount);
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("DebtContractBook.forgive 的 reason 不得空白");
    }
    DebtContract debt = requireContract(contracts, id);
    if (amount > debt.principal()) {
      throw new IllegalArgumentException(
          "DebtContractBook.forgive 不得超过本金（下溢守卫）：合同="
              + id
              + " 本金="
              + debt.principal()
              + " 减免="
              + amount);
    }
    long remaining = debt.principal() - amount;
    DebtStatus status = remaining == 0L ? DebtStatus.FORGIVEN : debt.status();
    DebtContract next =
        new DebtContract(
            debt.id(),
            debt.debtor(),
            debt.creditor(),
            debt.unit(),
            debt.terms(),
            remaining,
            debt.openedDay(),
            debt.lastInterestDay(),
            debt.dueCycle(),
            status);
    contracts.put(id, next);
    if (DEBT.isDebugEnabled()) {
      DEBT.debug(
          "event=DEBT_FORGIVE id={} debtor={} creditor={} unit={} amount={} remaining={} from={} to={} reason={}",
          id.value(),
          debt.debtor().value(),
          debt.creditor().value(),
          debt.unit().key(),
          amount,
          remaining,
          debt.status(),
          status,
          reason);
    }
    return new Forgiveness(
        id, debt.principal(), amount, remaining, debt.status(), status, remaining == 0L, reason);
  }

  /** ★ 全额减免（{@link #forgive} 的 {@code amount = principal} 便捷形态；本金为 0 ⇒ 抛，不制造空减免）。 */
  public static Forgiveness forgiveAll(
      Map<DebtContractId, DebtContract> contracts, DebtContractId id, String reason) {
    DebtContract debt = requireContract(contracts, id);
    return forgive(contracts, id, debt.principal(), reason);
  }

  /**
   * ★ 按四元组定位的减免形态（{@code id} 仍由 {@link DebtContractId#idOf} 唯一拼写）。
   *
   * @param contracts 债务工作表（就地更新）；不得为 null
   * @param debtor 债务人；不得为 null
   * @param creditor 债权人；不得为 null
   * @param unit 计量单位；不得为 null
   * @param terms 条款；不得为 null
   * @param amount 减免金额；{@code > 0} 且 ≤ 当前本金
   * @param reason 减免原因；不得空白
   */
  public static Forgiveness forgive(
      Map<DebtContractId, DebtContract> contracts,
      HouseholdId debtor,
      HouseholdId creditor,
      DebtUnit unit,
      DebtTerms terms,
      long amount,
      String reason) {
    Objects.requireNonNull(debtor, "debtor 不得为 null");
    Objects.requireNonNull(creditor, "creditor 不得为 null");
    Objects.requireNonNull(unit, "unit 不得为 null");
    Objects.requireNonNull(terms, "terms 不得为 null");
    return forgive(contracts, DebtContractId.idOf(debtor, creditor, unit, terms), amount, reason);
  }

  /**
   * ★★ <b>并行分区结果的唯一落回口</b>（包内可见：只服务 {@code 旧结算引擎（R3a 已删除）} 的并行借粮分区）。
   *
   * <p>★ 它<b>不是</b>本金递增语义：不做 addExact、不重置状态；只负责「分区 worker 的终值 → 协调器」这一步， 所以每个分区只允许碰自己债务人集合内的合同（同
   * key 不会由两个分区同时写）。值与原值逐字相同 ⇒ 不产生写入。 键/值身份守卫照 {@link #requireSameIdentity} 走。
   *
   * @param target 协调器债务工作表（就地更新）；不得为 null
   * @param partitionResult 分区 worker 的终值表；不得为 null
   */
  static void absorb(
      Map<DebtContractId, DebtContract> target, Map<DebtContractId, DebtContract> partitionResult) {
    Objects.requireNonNull(target, "target 不得为 null");
    Objects.requireNonNull(partitionResult, "partitionResult 不得为 null");
    for (Map.Entry<DebtContractId, DebtContract> entry : partitionResult.entrySet()) {
      DebtContractId id = entry.getKey();
      DebtContract value = entry.getValue();
      if (id == null || value == null) {
        throw new IllegalArgumentException("partitionResult 的键与值都不得为 null: " + id);
      }
      requireSameIdentity(id, value);
      DebtContract existing = target.get(id);
      if (existing == null || !existing.equals(value)) {
        target.put(id, value);
      }
    }
  }

  /**
   * ★ 为并行分区复制一份「只含指定债务人合同」的工作表快照（包内可见：复制口的写入仍在本类内；worker 之后用 {@link #absorb} 落回）。
   *
   * @param contracts 权威债务表（只读）；不得为 null
   * @param debtorFilter 该分区的债务人归属判据；不得为 null
   */
  static LinkedHashMap<DebtContractId, DebtContract> subset(
      Map<DebtContractId, DebtContract> contracts, Predicate<DebtContract> debtorFilter) {
    Objects.requireNonNull(contracts, "contracts 不得为 null");
    Objects.requireNonNull(debtorFilter, "debtorFilter 不得为 null");
    LinkedHashMap<DebtContractId, DebtContract> copy = new LinkedHashMap<>();
    for (Map.Entry<DebtContractId, DebtContract> entry : contracts.entrySet()) {
      if (debtorFilter.test(entry.getValue())) {
        copy.put(entry.getKey(), entry.getValue());
      }
    }
    return copy;
  }

  /**
   * ★ 把一条合同的派生引用补进债务人行（幂等；已含 ⇒ 返回原行）。{@code HouseholdEconomy.debts} 的权威重建仍在 {@code
   * DebtReferenceReconciler}（{@code EconomyData} 构造期），本方法只保证结算会话内同一行的引用不落后于合同表。
   */
  public static HouseholdEconomy withDebtReference(HouseholdEconomy householdEconomy, DebtContractId debtId) {
    Objects.requireNonNull(householdEconomy, "row 不得为 null");
    Objects.requireNonNull(debtId, "debtId 不得为 null");
    if (householdEconomy.debts().contains(debtId)) {
      return householdEconomy;
    }
    List<DebtContractId> debts = new ArrayList<>(householdEconomy.debts());
    debts.add(debtId);
    return new HouseholdEconomy(
        householdEconomy.id(),
        householdEconomy.view(),
        householdEconomy.population(),
        householdEconomy.laborMilli(),
        householdEconomy.participationPerMille(),
        householdEconomy.money(),
        debts,
        householdEconomy.naturalNeeds(),
        householdEconomy.effectiveDemand(),
        householdEconomy.cycleNaturalNeedMilli());
  }

  /** 取合同；缺席 ⇒ 具名抛（不允许"减少一条不存在的合同"这种静默 no-op）。 */
  private static DebtContract requireContract(
      Map<DebtContractId, DebtContract> contracts, DebtContractId id) {
    DebtContract debt = contracts.get(id);
    if (debt == null) {
      throw new IllegalArgumentException("债务合同不存在（写口 fail-closed）: " + id);
    }
    requireSameIdentity(id, debt);
    return debt;
  }

  /** 键 == 值内 id、且 id 确实是四元组的确定性派生。 */
  private static void requireSameIdentity(DebtContractId id, DebtContract contract) {
    if (!id.equals(contract.id())) {
      throw new IllegalArgumentException(
          "债务工作表的键必须与 DebtContract.id 一致：键=" + id + "，行内 id=" + contract.id());
    }
    if (!contract.idMatchesIdentity()) {
      throw new IllegalArgumentException(
          "DebtContract.id 必须由 (debtor, creditor, unit, terms) 确定性派生："
              + id
              + " ≠ "
              + DebtContractId.idOf(
                  contract.debtor(), contract.creditor(), contract.unit(), contract.terms()));
    }
  }

  /**
   * ★★ <b>减免结果</b>（E4c 的具名返回值；不新增持久状态组件）：金额、前后本金、前后状态、是否全额、原因。
   *
   * <p>★ {@code partial == true} 时 {@code statusAfter} 与原状态相同（FORGIVEN 只在本金到 0 时写，见 {@link
   * #forgive}）；调用方据此把"部分减免"与"结清式减免"分开记账。
   */
  public record Forgiveness(
      DebtContractId id,
      long principalBefore,
      long forgivenAmount,
      long principalAfter,
      DebtStatus statusBefore,
      DebtStatus statusAfter,
      boolean full,
      String reason) {

    public Forgiveness {
      Objects.requireNonNull(id, "Forgiveness.id 不得为 null");
      Objects.requireNonNull(statusBefore, "Forgiveness.statusBefore 不得为 null");
      Objects.requireNonNull(statusAfter, "Forgiveness.statusAfter 不得为 null");
      if (principalBefore < 0L || forgivenAmount <= 0L || principalAfter < 0L) {
        throw new IllegalArgumentException(
            "Forgiveness 的本金/减免额非法: before="
                + principalBefore
                + " forgiven="
                + forgivenAmount
                + " after="
                + principalAfter);
      }
      if (principalBefore - forgivenAmount != principalAfter) {
        throw new IllegalArgumentException(
            "Forgiveness 本金不守恒: "
                + principalBefore
                + " − "
                + forgivenAmount
                + " ≠ "
                + principalAfter);
      }
      if (full != (principalAfter == 0L)) {
        throw new IllegalArgumentException(
            "Forgiveness.full 必须与 principalAfter == 0 一致: full="
                + full
                + " after="
                + principalAfter);
      }
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("Forgiveness.reason 不得空白");
      }
    }
  }
}

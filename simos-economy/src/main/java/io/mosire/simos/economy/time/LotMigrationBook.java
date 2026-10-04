package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.population.LotMigration;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DebtIndex;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>P8 经济侧迁移写口</b>：把一份 {@link LotMigration} 计划落到 {@link EconomyData} 的工作副本上（人口 / 劳动 / 债务）。<b>不改
 * {@link EconomyData} 的组件集合，也不改 {@code LotChange} 的语义</b>—— 迁移不是出生/死亡，本类只做“换居住地”那一半。
 *
 * <pre>
 * 逐笔 LotMigration：
 *   ① 源选择：{@code (from, 源居住类型)} 的全部家户行，按行人口用最大余数法切出 count 人
 *      （正好 count 人，不会把任何一行抽成负）。
 *   ② 家户行迁移：源行人口 −take、源行劳动 −⌊源行劳动 × take ÷ 源行原人口⌋；
 *      目标行（同阶层 + 目标居住类型 + toResidence）人口 +take、劳动 +同一份劳动。
 *   ③ 目标行不存在 ⇒ 用 HouseholdIds.ofSeed 的规范身份新建（人口/劳动 0，参与率取第一个源行的值）；
 *      已存在 ⇒ 复用（合并进同一行，不另造第二本账）。
 *   ④ 债务：逐合同 {@code ⌊本金 × take ÷ 源行原人口⌋} —— 逐合同 floor、余数留在源合同；
 *      目标侧同 debtor 合同的新建/合并<b>只走</b> {@link DebtContractBook#upsert}，源侧减少只走
 *      {@link DebtContractBook#reduce}（两者都是唯一写口）。
 * </pre>
 *
 * <p>★★ <b>五条 fail-closed 守卫（绝不许静默丢人/丢债）</b>：
 *
 * <ol>
 *   <li>{@code from} + 源居住类型的家户行为空 ⇒ 具名抛（说不出这些人住哪一本账，不猜）；
 *   <li>源行合计人口 &lt; 迁移人数 ⇒ 具名抛；
 *   <li>逐行切分超过该行人口（含同一次调用的前几笔迁走后的实时值）⇒ 具名抛；
 *   <li>目标家户/行无法解析（视图冲突、id 碰撞、目标行缺失）⇒ 具名抛；
 *   <li>全部迁移完成后逐笔复核人数守恒（{@code Σ take == migration.count()}），不等 ⇒ 具名抛。
 * </ol>
 *
 * <p>★ <b>为什么按“源居住区的行人口”摊而不是按 lot 精确拆</b>：{@code ClassRow} 只有 {@code (格, 居住类型, 阶层)}
 * 三维，<b>没有性别/年龄维</b> —— 一笔 lot 级迁移在 economy 侧只能按该居住区各行的行人口比例摊（“这批人具体属于哪个家户” 的精确关系在 {@code
 * Membership} 里，P9 对账时用）。本类不假装能精确到 lot↔家户，也不因此静默少搬人。
 *
 * <p>★★ <b>本类不负责的接线（P8 明确留给 P9，不许假装已经做完）</b>：
 *
 * <ul>
 *   <li><b>social 侧</b>：{@code PopulationGroup} 的拆批/换 residence/合并目标批次（本类只写 economy）；
 *   <li><b>成员份额</b>：{@code Membership} 本类<b>一字不改</b> —— 源/目标 lot 的人数变化由 app 协调器在 P9 用 {@code
 *       MembershipWriteback.reconcile} 按行人口权重重建/削平（那正是它既有的职责：逐 lot Σcount == 社会人数）；
 *   <li><b>劳动配额与供给</b>：{@code LaborAllocation}/{@code LaborSupply} 不随本类改变（源批次的 {@code
 *       grossLaborMilli} 与配额由 social 侧迁移后的批次重发/缩编）；P9 必须在同一 revision 里接上，否则 “行劳动减了、批次配额没减”会让 {@code
 *       Σ allocated ≤ available} 与行/批次两侧漂开；
 *   <li><b>货币/商品</b>：迁移只带人、劳动与债务；{@code ClassRow.money} 留在源行（本记录没有“第二份钱账”）， P9 若决定财富随行必须另立显式契约；
 *   <li><b>naturalNeeds/effectiveDemand/cycleNaturalNeedMilli</b>：不随行；目标行由日结算的 {@code
 *       withDailyNeed} 在下一次结算时按新人口重算（源行余留的一日需求同样是下一次结算会覆盖的量）。
 * </ul>
 *
 * <p>★ <b>确定性</b>：全部遍历按 {@code HouseholdId} 规范串升序、切分走 {@link ProportionalSplit}
 * （最大余数法、同余按稳定序号）；同一份状态两次调用逐值相同。
 */
public final class LotMigrationBook {

  private LotMigrationBook() {}

  /** ★ 纯函数形态：{@code base} 一字不改；空计划 ⇒ 返回入参同一实例。 */
  public static EconomyData apply(EconomyData base, List<LotMigration> migrations, long day) {
    Objects.requireNonNull(base, "LotMigrationBook.apply 的 base 不得为 null");
    Objects.requireNonNull(migrations, "LotMigrationBook.apply 的 migrations 不得为 null");
    if (migrations.isEmpty()) {
      return base;
    }
    EconomySession session = new EconomySession(base);
    applyInto(session, migrations, day);
    return session.build();
  }

  /**
   * ★★ <b>会话形态</b>（供日/月结算在工作副本上就地调用）：只改 {@link EconomySession#sheet()} 的家户行与债务表工作副本； 全量守卫由 revision
   * 边界（{@link EconomySession#build()}）照跑一次。
   *
   * @param day 迁移执行日（债务新合同的 {@code openedDay}；不得为负）
   */
  public static void applyInto(EconomySession session, List<LotMigration> migrations, long day) {
    Objects.requireNonNull(session, "LotMigrationBook.applyInto 的 session 不得为 null");
    Objects.requireNonNull(migrations, "LotMigrationBook.applyInto 的 migrations 不得为 null");
    if (day < 0L) {
      throw new IllegalArgumentException("LotMigrationBook.applyInto 的 day 不得为负: " + day);
    }
    if (migrations.isEmpty()) {
      return;
    }
    LinkedHashMap<HouseholdId, ClassRow> rows = session.sheet().rows();
    LinkedHashMap<DebtContractId, DebtContract> debts = session.sheet().debtContracts();
    Map<CohortKey, HouseholdId> householdByView = indexHouseholdsByView(rows);
    Map<HouseholdId, List<DebtContractId>> debtsByDebtor = mutableDebtIndex(debts);
    for (int i = 0; i < migrations.size(); i++) {
      LotMigration migration = migrations.get(i);
      if (migration == null) {
        throw new IllegalArgumentException(
            "LotMigrationBook.applyInto 的 migrations[" + i + "] 不得为 null");
      }
      applyOne(rows, debts, householdByView, debtsByDebtor, migration, day);
    }
  }

  // ── 单笔迁移 ────────────────────────────────────────────────────────────────────────────────

  private static void applyOne(
      LinkedHashMap<HouseholdId, ClassRow> rows,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      Map<CohortKey, HouseholdId> householdByView,
      Map<HouseholdId, List<DebtContractId>> debtsByDebtor,
      LotMigration migration,
      long day) {
    ResidenceKind sourceResidence = ResidenceKind.ofLot(migration.sourceLot());
    ResidenceKind targetResidence = ResidenceKind.ofLot(migration.targetLot());
    List<ClassRow> sourceRows = rowsAt(rows, migration.from(), sourceResidence);
    if (sourceRows.isEmpty()) {
      throw new IllegalStateException(
          "迁移源格在 economy 侧没有对应家户行（拒绝静默丢人）："
              + migration.sourceLot()
              + " from="
              + migration.from()
              + " residence="
              + sourceResidence);
    }
    long totalPopulation = 0L;
    long[] weights = new long[sourceRows.size()];
    for (int i = 0; i < sourceRows.size(); i++) {
      weights[i] = sourceRows.get(i).population();
      totalPopulation = Math.addExact(totalPopulation, weights[i]);
    }
    if (totalPopulation <= 0L || migration.count() > totalPopulation) {
      throw new IllegalStateException(
          "迁移源行的可迁人口不足（拒绝把行抽成负）：迁移=" + migration + " 源行人口=" + totalPopulation);
    }
    long[] parts = ProportionalSplit.byDenominator(migration.count(), weights, totalPopulation);
    long moved = 0L;
    for (int i = 0; i < sourceRows.size(); i++) {
      long take = parts[i];
      if (take <= 0L) {
        continue;
      }
      ClassRow sourceRow = sourceRows.get(i);
      if (take > sourceRow.population()) {
        throw new IllegalStateException(
            "迁移源行切分超过行人口（拒绝把行抽成负）：row="
                + sourceRow.id()
                + " take="
                + take
                + " population="
                + sourceRow.population());
      }
      HouseholdId targetHousehold =
          resolveTargetHousehold(
              rows,
              householdByView,
              migration,
              targetResidence,
              sourceRow.view().stratum(),
              sourceRow.participationPerMille());
      movePopulationAndLabor(
          rows, debts, debtsByDebtor, sourceRow.id(), targetHousehold, take, day, migration);
      moved = Math.addExact(moved, take);
    }
    if (moved != migration.count()) {
      throw new IllegalStateException("迁移人数未守恒（拒绝静默丢人）：迁移=" + migration + " 实际切出=" + moved);
    }
  }

  /** ★ 源行人口/劳动减、目标行人口/劳动增（同家户 ⇒ 净 0，直接返回）。 */
  private static void movePopulationAndLabor(
      LinkedHashMap<HouseholdId, ClassRow> rows,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      Map<HouseholdId, List<DebtContractId>> debtsByDebtor,
      HouseholdId sourceHousehold,
      HouseholdId targetHousehold,
      long count,
      long day,
      LotMigration migration) {
    if (count <= 0L) {
      return;
    }
    ClassRow source = rows.get(sourceHousehold);
    if (source == null) {
      throw new IllegalStateException(
          "迁移源家户行不存在（拒绝静默丢人）：" + migration + " source=" + sourceHousehold);
    }
    long sourcePopulation = source.population();
    if (sourcePopulation <= 0L || count > sourcePopulation) {
      throw new IllegalStateException(
          "迁移源行人口不足（拒绝把行抽成负）：迁移="
              + migration
              + " source="
              + sourceHousehold
              + " population="
              + sourcePopulation
              + " count="
              + count);
    }
    if (sourceHousehold.equals(targetHousehold)) {
      // 同一本账内部的迁移：人口/劳动净 0、债务仍归同一债务人 ⇒ 没有经济侧状态要改。
      return;
    }
    ClassRow target = rows.get(targetHousehold);
    if (target == null) {
      throw new IllegalStateException(
          "迁移目标家户行不存在（拒绝静默丢人）：" + migration + " target=" + targetHousehold);
    }
    long movedLabor = laborShareFloor(source.laborMilli(), count, sourcePopulation);
    long sourcePopulationAfter = sourcePopulation - count;
    long sourceLaborAfter = source.laborMilli() - movedLabor;
    long targetPopulationAfter = Math.addExact(target.population(), count);
    long targetLaborAfter = Math.addExact(target.laborMilli(), movedLabor);
    rows.put(
        sourceHousehold, source.withPopulationAndLabor(sourcePopulationAfter, sourceLaborAfter));
    rows.put(
        targetHousehold, target.withPopulationAndLabor(targetPopulationAfter, targetLaborAfter));
    moveDebts(
        rows, debts, debtsByDebtor, sourceHousehold, targetHousehold, sourcePopulation, count, day);
  }

  /**
   * ★★ <b>债务随行</b>：逐合同 {@code ⌊本金 × count ÷ 源行原人口⌋}（floor；剩余本金留在源合同）。
   *
   * <p>源侧减少只走 {@link DebtContractBook#reduce}；目标侧新建/合并只走 {@link DebtContractBook#upsert} （同 debtor
   * 四元组自会合并）。{@code movedPrincipal == 0} 的合同不建目标条、不写 0 减免。
   */
  private static void moveDebts(
      LinkedHashMap<HouseholdId, ClassRow> rows,
      LinkedHashMap<DebtContractId, DebtContract> debts,
      Map<HouseholdId, List<DebtContractId>> debtsByDebtor,
      HouseholdId sourceHousehold,
      HouseholdId targetHousehold,
      long sourcePopulation,
      long count,
      long day) {
    if (sourceHousehold.equals(targetHousehold) || sourcePopulation <= 0L || count <= 0L) {
      return;
    }
    List<DebtContractId> sourceContractIds =
        new ArrayList<>(debtsByDebtor.getOrDefault(sourceHousehold, List.of()));
    for (DebtContractId contractId : sourceContractIds) {
      DebtContract contract = debts.get(contractId);
      if (contract == null || !contract.debtor().equals(sourceHousehold)) {
        continue; // 索引是只读派生；合同表才是权威。
      }
      long principal = contract.principal();
      if (principal <= 0L) {
        continue; // 已结清/已减免：没有可随行的活跃本金。
      }
      long movedPrincipal = proportionalFloor(principal, count, sourcePopulation);
      if (movedPrincipal <= 0L) {
        continue; // floor 到 0 ⇒ 整笔留在源行（不制造 0 本金目标条）。
      }
      DebtContractBook.reduce(debts, contractId, movedPrincipal);
      DebtContractBook.upsert(
          debts,
          targetHousehold,
          contract.creditor(),
          contract.unit(),
          contract.terms(),
          movedPrincipal,
          day,
          contract.dueCycle());
      DebtContractId targetContractId =
          DebtContractId.idOf(
              targetHousehold, contract.creditor(), contract.unit(), contract.terms());
      ClassRow targetRow = rows.get(targetHousehold);
      if (targetRow == null) {
        throw new IllegalStateException(
            "债务随行的目标家户行不存在（拒绝静默丢债）：" + targetHousehold + " ← " + sourceHousehold);
      }
      // ★ 会话内也把目标行的派生引用补上（权威仍是合同表；build() 的 DebtReferenceReconciler 会再对一次）。
      rows.put(targetHousehold, DebtContractBook.withDebtReference(targetRow, targetContractId));
      List<DebtContractId> targetContracts =
          debtsByDebtor.computeIfAbsent(targetHousehold, ignored -> new ArrayList<>());
      if (!targetContracts.contains(targetContractId)) {
        targetContracts.add(targetContractId);
        targetContracts.sort(Comparator.comparing(DebtContractId::value));
      }
    }
  }

  // ── 家户行解析 ──────────────────────────────────────────────────────────────────────────────

  /**
   * ★★ <b>目标家户行解析</b>：优先复用“同格 + 同居住类型 + 同阶层”的既有行（多行时取 id 规范串最小者，确定性）； 不存在 ⇒ 用 {@link
   * HouseholdIds#ofSeed} 的规范身份新建（人口/劳动 0，参与率取第一个源行的值，其余字段合法零值）。
   *
   * <p>新建行<b>不</b>造第二份钱/需求/债务账；它们由日结算/债务写口在后续按规范路径产生。
   */
  private static HouseholdId resolveTargetHousehold(
      LinkedHashMap<HouseholdId, ClassRow> rows,
      Map<CohortKey, HouseholdId> householdByView,
      LotMigration migration,
      ResidenceKind targetResidence,
      SocialClassId stratum,
      int participationPerMille) {
    CohortKey targetView = new CohortKey(migration.toResidence(), targetResidence, stratum);
    HouseholdId existing = householdByView.get(targetView);
    if (existing != null) {
      if (rows.get(existing) == null) {
        throw new IllegalStateException(
            "目标行索引与家户表不一致（拒绝静默丢人）：视图=" + targetView + " 指向 " + existing);
      }
      return existing;
    }
    HouseholdId created = HouseholdIds.ofSeed(migration.toResidence(), targetResidence, stratum);
    if (rows.containsKey(created)) {
      throw new IllegalStateException(
          "目标家户 id 已被不同视图占用（拒绝覆盖）：id="
              + created
              + " 既有视图="
              + rows.get(created).view()
              + " 期望视图="
              + targetView);
    }
    ClassRow createdRow =
        new ClassRow(
            created,
            targetView,
            0L,
            0L,
            participationPerMille,
            0L,
            List.of(),
            Map.of(),
            Map.of(),
            0L);
    rows.put(created, createdRow);
    householdByView.put(targetView, created);
    return created;
  }

  // ── 纯派生小工具 ─────────────────────────────────────────────────────────────────────────────

  /** 视图 → 家户行；同一视图多行时取 id 规范串最小者（确定性；正常状态应唯一）。 */
  private static Map<CohortKey, HouseholdId> indexHouseholdsByView(
      Map<HouseholdId, ClassRow> rows) {
    Map<CohortKey, HouseholdId> index = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : rows.entrySet()) {
      HouseholdId current = index.get(entry.getValue().view());
      if (current == null || entry.getKey().value().compareTo(current.value()) < 0) {
        index.put(entry.getValue().view(), entry.getKey());
      }
    }
    return index;
  }

  /** (格, 居住类型) 的源行（按 id 规范串升序，确定性）。 */
  private static List<ClassRow> rowsAt(
      Map<HouseholdId, ClassRow> rows, HexCoord hex, ResidenceKind residence) {
    List<ClassRow> out = new ArrayList<>();
    for (ClassRow row : rows.values()) {
      if (row.view().hex().equals(hex) && row.view().residence() == residence) {
        out.add(row);
      }
    }
    out.sort(Comparator.comparing(row -> row.id().value()));
    return out;
  }

  /** 可变的债务人索引（{@link DebtIndex} 的派生是只读的；迁移会新增目标合同，故逐层复制成可变表）。 */
  private static Map<HouseholdId, List<DebtContractId>> mutableDebtIndex(
      Map<DebtContractId, DebtContract> debts) {
    Map<HouseholdId, List<DebtContractId>> index = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, List<DebtContractId>> entry :
        DebtIndex.byDebtor(debts).entrySet()) {
      index.put(entry.getKey(), new ArrayList<>(entry.getValue()));
    }
    return index;
  }

  /** {@code ⌊laborMilli × count ÷ population⌋}（人口 &gt; 0；乘法溢出 fail-closed）。 */
  private static long laborShareFloor(long laborMilli, long count, long population) {
    if (population <= 0L) {
      throw new IllegalStateException("劳动按比例取整的人口必须 > 0: " + population);
    }
    try {
      return Math.multiplyExact(laborMilli, count) / population;
    } catch (ArithmeticException overflow) {
      throw new ArithmeticException(
          "迁移劳动按比例取整溢出（fail-closed）：labor="
              + laborMilli
              + " count="
              + count
              + " population="
              + population);
    }
  }

  /** {@code ⌊amount × part ÷ whole⌋} 的安全形态（主项不溢出；尾项溢出 fail-closed）。 */
  private static long proportionalFloor(long amount, long part, long whole) {
    if (whole <= 0L) {
      throw new IllegalStateException("债务按比例取整的分母必须 > 0: " + whole);
    }
    if (part <= 0L) {
      return 0L;
    }
    long quotient = amount / whole;
    long remainder = amount % whole;
    try {
      return Math.addExact(
          Math.multiplyExact(quotient, part), Math.multiplyExact(remainder, part) / whole);
    } catch (ArithmeticException overflow) {
      throw new ArithmeticException(
          "债务随行按比例取整溢出（fail-closed）：amount=" + amount + " part=" + part + " whole=" + whole);
    }
  }
}

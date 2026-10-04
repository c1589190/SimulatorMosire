package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassPosition;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.ClassStanding;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.Market;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionOrganization;
import io.mosire.simos.economy.model.ProductionUnit;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * ★★ <b>P10.2 / P10.5 家户生产方式迁移计划器（架构 §5.2 的 ⑧）</b>：用 {@link OrganizationProfitBook} 的<b>真实</b>
 * 单位劳动净收益做权重，输出确定性的"人/钱/债转移到目标 mode 家户"的计划。
 *
 * <pre>
 * 目标权重 weight = max(0, target.netPerLaborScaled − current.netPerLaborScaled)
 *                （同一把百万分之一单位的高精度尺，避免 net/labor 整数截断为 0；真读数，不是外生 π）
 * 目标排序       weight 降序 → 距离升序（同格 0、邻格 1）→ (hex, mode) 规范串升序 → 家户 id 升序
 * 基线速度        MIGRATION_PER_MILLE = 10‰/周期（可迁人口 = max(1, ⌊人口×10÷1000⌋)）
 * A 规则        expectedNet(current) < 0 且 cash + sellableInventory − nextCycleInputNeed < 0
 *                · 存在 netPerLaborScaled 严格更高的可行目标 ⇒ transferSpeed = 1000‰，把人口转出
 *                · 当前户已是最优（或没有更高可行目标）⇒ 不转移、继续生产，债务/欠款走现有资本化
 *                · 无法维持生产且没有任何能承载的可行目标 ⇒ 转 DISPLACED（同样 1000‰）
 * 新建可行性      同一 plan() 共享"闲置资产预留账本"（IndustryId × AssetKind）；
 *                available = Σ 闲置(quantity>0 ∧ operator==owner ∧ id∉既有组织 assetSources) − 已预留，
 *                任一 required 不足则整笔不预留。
 *                后一个新建户/后一遍 allocate 只看前面计划消费后的剩余，与执行期逐笔拆份额一致。
 *                ★ 闲置判据的唯一拼写点 = {@link #isIdleShare(AssetShare, Set)}（policy/settlement 共用）。
 * </pre>
 *
 * <p>★★ <b>D-022 硬约束</b>：计划里只有 {@code MigrationMove}（源户 → 目标户）；{@code targetMode} 只属于目标家户，
 * 绝不产生"把源户 mode/standing/organization/unit.modeKey 原地改成 targetMode"的计划项。
 *
 * <p>★★ <b>确定性</b>：无随机、无时钟、无 UUID；全部遍历按稳定 id / (q,r) 排序；同输入同计划。
 */
public final class ModeMigrationPolicy {

  private ModeMigrationPolicy() {}

  /** 基线迁移速度（‰/周期；架构 §10）。 */
  public static final long MIGRATION_PER_MILLE = 10L;

  /** A 规则触发后的转移速度（‰；架构 §10）。 */
  public static final long A_RULE_TRANSFER_SPEED_PER_MILLE = 1000L;

  /** 单个家户承载上限（架构 §10：新家户 200 人/户；合并目标同样按它判剩余容量）。 */
  public static final long MAX_HOUSEHOLD_POPULATION = 200L;

  /** 一条迁移计划（不可变、保序：按源家户 id、再按目标排序）。 */
  public record MigrationPlan(List<MigrationMove> moves) {

    public MigrationPlan {
      Objects.requireNonNull(moves, "moves");
      List<MigrationMove> copy = new ArrayList<>(moves.size());
      for (MigrationMove move : moves) {
        copy.add(Objects.requireNonNull(move, "MigrationMove"));
      }
      moves = Collections.unmodifiableList(copy);
    }

    public static MigrationPlan empty() {
      return new MigrationPlan(List.of());
    }

    public boolean isEmpty() {
      return moves.isEmpty();
    }
  }

  /**
   * 一条迁移：源户人口/货币/债务按比例转到目标家户。
   *
   * <p>★ {@code target} 已有家户（合并）或计划新建家户 id（新建）；{@code target != source} 由构造期守卫判死；
   * {@code targetMode} 是<b>目标家户</b>的 mode，源户 mode 在整条执行路径上一字不改。
   *
   * <p>★★ <b>D-023：货币随迁按全部币种</b> —— 权威口径是 {@link #moneyByCurrency()}（逐币种按人口比例
   * floor；源户迁空时该币种余数随最后一笔走；<b>不做 FX</b>）。{@link #moneyMilli()} 只保留为旧读口（计划的主币种
   * 份额），执行器以 {@code moneyByCurrency} 为准；空 map = 旧/手工计划 ⇒ 执行器就地按人口比例补算全部币种。
   */
  public record MigrationMove(
      HouseholdId source,
      HouseholdId target,
      HexCoord targetHex,
      ProductionModeId targetMode,
      long transferSpeedPerMille,
      long population,
      long moneyMilli,
      long debtMilli,
      String reason,
      Map<CurrencyId, Long> moneyByCurrency) {

    /** 迁移原因词表（规范串）。 */
    public static final String REASON_PROFIT_WEIGHTED = "PROFIT_WEIGHTED";

    public static final String REASON_A_RULE_MAX_SPEED = "A_RULE_MAX_SPEED";
    public static final String REASON_DISPLACED_ABSORBED = "DISPLACED_ABSORBED";

    /** 旧读口的 9 参构造（全币种 map 为空 ⇒ 执行器就地按人口比例补算，不静默丢任何币种）。 */
    public MigrationMove(
        HouseholdId source,
        HouseholdId target,
        HexCoord targetHex,
        ProductionModeId targetMode,
        long transferSpeedPerMille,
        long population,
        long moneyMilli,
        long debtMilli,
        String reason) {
      this(
          source,
          target,
          targetHex,
          targetMode,
          transferSpeedPerMille,
          population,
          moneyMilli,
          debtMilli,
          reason,
          Map.of());
    }

    public MigrationMove {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(targetHex, "targetHex");
      Objects.requireNonNull(targetMode, "targetMode");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(moneyByCurrency, "moneyByCurrency");
      if (source.equals(target)) {
        throw new IllegalArgumentException("MigrationMove.target 不得 == source（D-022：不原地改源户）: " + source);
      }
      if (population <= 0L) {
        throw new IllegalArgumentException("MigrationMove.population 必须 > 0: " + population);
      }
      if (moneyMilli < 0L || debtMilli < 0L) {
        throw new IllegalArgumentException(
            "MigrationMove 的 moneyMilli/debtMilli 不得为负: " + moneyMilli + "/" + debtMilli);
      }
      if (transferSpeedPerMille != MIGRATION_PER_MILLE
          && transferSpeedPerMille != A_RULE_TRANSFER_SPEED_PER_MILLE) {
        throw new IllegalArgumentException(
            "MigrationMove.transferSpeedPerMille 只允许 "
                + MIGRATION_PER_MILLE
                + " 或 "
                + A_RULE_TRANSFER_SPEED_PER_MILLE
                + ": "
                + transferSpeedPerMille);
      }
      LinkedHashMap<CurrencyId, Long> moneyCopy = new LinkedHashMap<>();
      for (Map.Entry<CurrencyId, Long> entry : moneyByCurrency.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "MigrationMove.moneyByCurrency 的键与值都不得为 null: " + entry.getKey());
        }
        if (entry.getValue() < 0L) {
          throw new IllegalArgumentException(
              "MigrationMove.moneyByCurrency 的份额不得为负: " + entry.getKey() + " = " + entry.getValue());
        }
        if (entry.getValue() > 0L) {
          moneyCopy.put(entry.getKey(), entry.getValue());
        }
      }
      moneyByCurrency = Collections.unmodifiableMap(moneyCopy); // ★ 冻在赋值处
    }
  }

  /** 当前户的预期读数（A 规则两条件与"能否维持生产"都读它）。 */
  public record ExpectedProfit(
      long expectedNetMilli,
      long expectedLaborMilli,
      long nextCycleInputNeedMilli,
      long liquidityMilli,
      boolean canSustainProduction,
      String reason) {

    public ExpectedProfit {
      Objects.requireNonNull(reason, "reason");
      if (expectedLaborMilli < 0L || nextCycleInputNeedMilli < 0L || liquidityMilli < 0L) {
        throw new IllegalArgumentException("ExpectedProfit 的劳动/投入/流动性读数不得为负");
      }
    }

    public long expectedNetPerLaborMilli() {
      return expectedNetMilli / Math.max(1L, expectedLaborMilli);
    }
  }

  /** 计划入口：只读全部入参，返回确定性计划。 */
  public static MigrationPlan plan(
      EconomyData base,
      Map<ProductionOrganizationId, ProductionOrganization> organizations,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<HouseholdId, ClassRow> rows,
      Map<HouseholdId, ClassStanding> standings,
      Map<AssetShareId, AssetShare> assetShares,
      Map<ProductionUnitId, ProductionRelation> relations,
      Map<LaborAllocationId, LaborAllocation> allocations,
      Map<HexCoord, Market> markets,
      Map<DebtContractId, DebtContract> debts,
      AccountSession accounts,
      OrganizationProfitBook.Book book,
      long day) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(organizations, "organizations");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(standings, "standings");
    Objects.requireNonNull(assetShares, "assetShares");
    Objects.requireNonNull(allocations, "allocations");
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(debts, "debts");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(book, "book");
    Objects.requireNonNull(relations, "relations");
    // ★★ P10.6：计划期共享的"闲置资产预留账本"。同一 plan() 的所有源户、所有目标新建户、allocate 的两遍
    //    都读写同一份账本 ⇒ 后一个新建户看到的是前面计划已消费后的剩余闲置资产，与执行期逐笔拆份额一致。
    Map<IndustryId, Map<AssetKind, Long>> reservedIdle = new LinkedHashMap<>();
    // ★★ P10.7 / P10.8：既有 ProductionOrganization.assetSources 正在使用的份额不是"闲置"——执行期把它拆/移走后，
    //    组织引用会指向已删除的份额 id，EconomyData 当即以「生产组织使用的资产份额必须已存在」拒绝。
    //    claimed 必须读调用方传入的当天工作副本 organizations（可能已被当天自动组织阶段加入新组织），
    //    不能用当天开始时的不可变 base；只在这里构建一次，与预留账本一起透传；谓词与执行期共用
    //    {@link #isIdleShare}，不新增持久组件。
    Set<AssetShareId> claimedByOrganizations = claimedAssetShares(organizations);
    if (base.modes().isEmpty()) {
      return MigrationPlan.empty(); // class-first 世界：逐字 no-op
    }

    Map<HouseholdId, HouseholdMode> modeByHousehold = new LinkedHashMap<>();
    for (HouseholdId household : sortedHouseholds(rows)) {
      ClassRow row = rows.get(household);
      ClassStanding standing = standings.get(household);
      if (row == null || row.population() <= 0L || standing == null) {
        continue;
      }
      ClassPosition position = base.classPositions().get(standing.currentPositionId());
      if (position == null) {
        continue;
      }
      modeByHousehold.put(
          household, new HouseholdMode(household, row.view().hex(), position.modeId(), position.id()));
    }
    if (modeByHousehold.isEmpty()) {
      return MigrationPlan.empty();
    }

    List<HexCoord> hexes = globalHexes(rows, units, markets);
    List<ProductionMode> modes = sortedModes(base);
    Map<ProductionModeId, List<ClassPosition>> producingPositions =
        producingPositionsByMode(base, modes);

    LinkedHashMap<HouseholdId, List<MoveDraft>> draftsBySource = new LinkedHashMap<>();
    for (HouseholdId source : sortedHouseholds(rows)) {
      HouseholdMode current = modeByHousehold.get(source);
      if (current == null) {
        continue;
      }
      ClassRow sourceRow = rows.get(source);
      List<MoveDraft> drafts =
          planForSource(
              base,
              source,
              sourceRow,
              current,
              hexes,
              modes,
              producingPositions,
              modeByHousehold,
              organizations,
              units,
              rows,
              assetShares,
              markets,
              accounts,
              book,
              reservedIdle,
              claimedByOrganizations);
      if (!drafts.isEmpty()) {
        draftsBySource.put(source, drafts);
      }
    }
    if (draftsBySource.isEmpty()) {
      return MigrationPlan.empty();
    }

    List<MigrationMove> moves = new ArrayList<>();
    for (HouseholdId source : sortedHouseholds(rows)) {
      List<MoveDraft> drafts = draftsBySource.get(source);
      if (drafts == null) {
        continue;
      }
      long sourcePopulation = rows.get(source).population();
      long[] populations = new long[drafts.size()];
      for (int i = 0; i < drafts.size(); i++) {
        populations[i] = drafts.get(i).population;
      }
      boolean emptiesSource = sum(populations) == sourcePopulation;
      List<Map<CurrencyId, Long>> moneySharesByCurrency =
          splitMoneyByCurrency(accounts, populations, sourcePopulation, source, emptiesSource);
      // ★ 旧读口 moneyMilli 仍报"主币种"份额；权威全币种表在 moneyByCurrency（D-023）。
      CurrencyId legacyCurrency =
          currentCurrency(currentOf(modeByHousehold, source), accounts, markets);
      long[] debtShares = splitDebt(populations, sourcePopulation, source, debts, emptiesSource);
      for (int i = 0; i < drafts.size(); i++) {
        MoveDraft draft = drafts.get(i);
        Map<CurrencyId, Long> moneyByCurrency = moneySharesByCurrency.get(i);
        long legacyMoneyMilli =
            legacyCurrency == null ? 0L : moneyByCurrency.getOrDefault(legacyCurrency, 0L);
        moves.add(
            new MigrationMove(
                source,
                draft.target,
                draft.targetHex,
                draft.targetMode,
                draft.transferSpeedPerMille,
                draft.population,
                legacyMoneyMilli,
                debtShares[i],
                draft.reason,
                moneyByCurrency));
      }
    }
    return new MigrationPlan(moves);
  }

  // ── 逐源户计划 ─────────────────────────────────────────────────────────────────────────────

  private static List<MoveDraft> planForSource(
      EconomyData base,
      HouseholdId source,
      ClassRow sourceRow,
      HouseholdMode current,
      List<HexCoord> hexes,
      List<ProductionMode> modes,
      Map<ProductionModeId, List<ClassPosition>> producingPositions,
      Map<HouseholdId, HouseholdMode> modeByHousehold,
      Map<ProductionOrganizationId, ProductionOrganization> organizations,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<HouseholdId, ClassRow> rows,
      Map<AssetShareId, AssetShare> assetShares,
      Map<HexCoord, Market> markets,
      AccountSession accounts,
      OrganizationProfitBook.Book book,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByOrganizations) {
    // ★★ P10.5：当前收益用高精度同一把尺（百万分之一单位）；无读数/labor<=0 时读口本身返回 0。
    long currentPerLabor = book.netPerLaborScaled(current.modeId, current.hex);
    List<Target> targets =
        buildTargets(
            base,
            source,
            sourceRow,
            current,
            currentPerLabor,
            hexes,
            modes,
            producingPositions,
            modeByHousehold,
            rows,
            book);
    ExpectedProfit expected =
        expectedProfit(
            base, source, sourceRow, current, organizations, units, assetShares, markets, accounts, book);
    boolean aRule =
        expected.expectedNetMilli() < 0L
            && expected.liquidityMilli() < expected.nextCycleInputNeedMilli();

    if (aRule) {
      List<Target> higher = targets.stream().filter(target -> target.weight > 0L).toList();
      if (!higher.isEmpty()) {
        return allocate(
            source,
            sourceRow.population(),
            sourceRow.population(),
            A_RULE_TRANSFER_SPEED_PER_MILLE,
            MigrationMove.REASON_A_RULE_MAX_SPEED,
            higher,
            base,
            sourceRow,
            assetShares,
            reservedIdle,
            claimedByOrganizations);
      }
      if (!expected.canSustainProduction()) {
        List<Target> displaced =
            buildDisplacedTargets(source, current, hexes, modes, producingPositions, modeByHousehold, rows);
        if (!displaced.isEmpty()) {
          return allocate(
              source,
              sourceRow.population(),
              sourceRow.population(),
              A_RULE_TRANSFER_SPEED_PER_MILLE,
              MigrationMove.REASON_DISPLACED_ABSORBED,
              displaced,
              base,
              sourceRow,
              assetShares,
              reservedIdle,
              claimedByOrganizations);
        }
      }
      // 当前户已是最优（或没有可承载目标）：不转移，债务按现有路径继续增长。
      return List.of();
    }

    List<Target> positive = targets.stream().filter(target -> target.weight > 0L).toList();
    if (positive.isEmpty()) {
      return List.of();
    }
    long moveable =
        Math.min(
            sourceRow.population(),
            Math.max(1L, sourceRow.population() * MIGRATION_PER_MILLE / 1000L));
    if (moveable <= 0L) {
      return List.of();
    }
    return allocate(
        source,
        sourceRow.population(),
        moveable,
        MIGRATION_PER_MILLE,
        MigrationMove.REASON_PROFIT_WEIGHTED,
        positive,
        base,
        sourceRow,
        assetShares,
        reservedIdle,
        claimedByOrganizations);
  }

  /** 目标候选（已有家户 + 可新建；同 (hex, mode) 一条，已有家户优先）。 */
  private static List<Target> buildTargets(
      EconomyData base,
      HouseholdId source,
      ClassRow sourceRow,
      HouseholdMode current,
      long currentPerLabor,
      List<HexCoord> hexes,
      List<ProductionMode> modes,
      Map<ProductionModeId, List<ClassPosition>> producingPositions,
      Map<HouseholdId, HouseholdMode> modeByHousehold,
      Map<HouseholdId, ClassRow> rows,
      OrganizationProfitBook.Book book) {
    List<Target> targets = new ArrayList<>();
    for (HexCoord hex : hexes) {
      int distance = sourceRow.view().hex().distanceTo(hex);
      if (distance > 1) {
        continue;
      }
      for (ProductionMode mode : modes) {
        if (DefaultProductionModes.DISPLACED.equals(mode.id())) {
          continue; // displaced 只在 A 规则"无路可走"支里出现
        }
        if (mode.id().equals(current.modeId) && hex.equals(current.hex)) {
          continue;
        }
        List<ClassPosition> positions = producingPositions.get(mode.id());
        if (positions == null || positions.isEmpty() || !hasIndustryTemplate(base, hex)) {
          continue;
        }
        if (!book.hasReading(mode.id(), hex) || book.labor(mode.id(), hex) <= 0L) {
          continue; // 没有真实利润读数/没有真实劳动投入的 mode/hex 不作为正收益目标（不凭空造目标）
        }
        long weight =
            Math.max(
                0L,
                Math.subtractExact(book.netPerLaborScaled(mode.id(), hex), currentPerLabor));
        if (weight <= 0L) {
          continue;
        }
        HouseholdId existing =
            bestExistingTarget(source, hex, mode, modeByHousehold, positions, rows);
        long room =
            existing == null
                ? 0L
                : Math.max(0L, MAX_HOUSEHOLD_POPULATION - rows.get(existing).population());
        targets.add(
            new Target(
                hex,
                mode.id(),
                existing,
                weight,
                distance,
                true,
                room,
                canonicalKey(hex, mode.id())));
      }
    }
    targets.sort(
        Comparator.comparingLong((Target target) -> target.weight)
            .reversed()
            .thenComparingInt(target -> target.distance)
            .thenComparing(target -> target.canonical)
            .thenComparing(target -> target.existing == null ? "" : target.existing.value()));
    return targets;
  }

  /** A 规则专用：DISPLACED 目标（已有流民户或同一/邻格新建；不要求产业/资产）。 */
  private static List<Target> buildDisplacedTargets(
      HouseholdId source,
      HouseholdMode current,
      List<HexCoord> hexes,
      List<ProductionMode> modes,
      Map<ProductionModeId, List<ClassPosition>> producingPositions,
      Map<HouseholdId, HouseholdMode> modeByHousehold,
      Map<HouseholdId, ClassRow> rows) {
    ProductionMode displaced =
        modes.stream()
            .filter(mode -> DefaultProductionModes.DISPLACED.equals(mode.id()))
            .findFirst()
            .orElse(null);
    if (displaced == null) {
      return List.of();
    }
    List<Target> targets = new ArrayList<>();
    for (HexCoord hex : hexes) {
      int distance = current.hex.distanceTo(hex);
      if (distance > 1) {
        continue;
      }
      List<ClassPosition> positions = producingPositions.get(displaced.id());
      if (positions == null || positions.isEmpty()) {
        continue;
      }
      HouseholdId existing =
          bestExistingTarget(source, hex, displaced, modeByHousehold, positions, rows);
      long room =
          existing == null
              ? 0L
              : Math.max(0L, MAX_HOUSEHOLD_POPULATION - rows.get(existing).population());
      if (existing == null) {
        targets.add(
            new Target(
                hex,
                displaced.id(),
                null,
                0L,
                distance,
                true,
                MAX_HOUSEHOLD_POPULATION,
                canonicalKey(hex, displaced.id())));
      } else if (room > 0L) {
        targets.add(
            new Target(
                hex,
                displaced.id(),
                existing,
                0L,
                distance,
                false,
                room,
                canonicalKey(hex, displaced.id())));
      }
    }
    targets.sort(
        Comparator.comparingInt((Target target) -> target.distance)
            .thenComparing(target -> target.canonical)
            .thenComparing(target -> target.existing == null ? "" : target.existing.value()));
    return targets;
  }

  /**
   * 分配可迁人口：按目标排序贪心填充（已有户剩余容量 → 同目标可新建容量 → 下一个目标）。
   *
   * <p>★ 新建目标的资产可行性在这里用"本次实际要建的人口"复核（不足 ⇒ 该目标的新建容量记 0）。溢出部分留在源户，
   * 不丢人、也不凭空造承载。★★ P10.6：预留账本由调用方（同一 plan 顶层）传入，两遍循环共用、不重置。
   */
  private static List<MoveDraft> allocate(
      HouseholdId source,
      long sourcePopulation,
      long moveable,
      long speedPerMille,
      String reason,
      List<Target> targets,
      EconomyData base,
      ClassRow sourceRow,
      Map<AssetShareId, AssetShare> assetShares,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByOrganizations) {
    List<MoveDraft> drafts = new ArrayList<>();
    if (targets.isEmpty()) {
      return drafts;
    }
    long total = Math.min(moveable, sourcePopulation);
    if (total <= 0L) {
      return drafts;
    }
    // ★ 权重分配：先按 weight 比例切出各目标的名义额度，再按目标序在额度内落地；某目标承载不足时
    //   余额顺延给后面的目标（不丢人、也不偏离"按权重转出"）。
    long[] weights = new long[targets.size()];
    long weightSum = 0L;
    for (int i = 0; i < targets.size(); i++) {
      weights[i] = Math.max(0L, targets.get(i).weight);
      weightSum = Math.addExact(weightSum, weights[i]);
    }
    long[] wants =
        io.mosire.simos.util.economy.ProportionalSplit.byDenominator(total, weights, weightSum);
    long remaining = total;
    int[] newOrdinal = {0};
    for (int i = 0; i < targets.size() && remaining > 0L; i++) {
      long placed =
          placeInto(
              targets.get(i),
              Math.min(remaining, wants[i]),
              source,
              speedPerMille,
              reason,
              base,
              sourceRow,
              assetShares,
              drafts,
              newOrdinal,
              reservedIdle,
              claimedByOrganizations);
      remaining -= placed;
    }
    // 第二遍：把前面目标没承载下的余量按目标序顺延（仍受真实容量/资产约束）。
    for (int i = 0; i < targets.size() && remaining > 0L; i++) {
      long placed =
          placeInto(
              targets.get(i),
              remaining,
              source,
              speedPerMille,
              reason,
              base,
              sourceRow,
              assetShares,
              drafts,
              newOrdinal,
              reservedIdle,
              claimedByOrganizations);
      remaining -= placed;
    }
    return drafts;
  }

  /**
   * 往一个目标里放人口（已有户剩余容量 → 该 (hex, mode) 至多新建一户）；返回实际放入人口。
   *
   * <p>★★ P10.6：新建部分的"资产可行性"是<b>检查并预留</b>（{@code reserveIdleAssetsFor}）；预留成功才产出 MoveDraft，
   * 账本由同一 {@code plan()} 的所有源户/所有目标共享。
   */
  private static long placeInto(
      Target target,
      long allow,
      HouseholdId source,
      long speedPerMille,
      String reason,
      EconomyData base,
      ClassRow sourceRow,
      Map<AssetShareId, AssetShare> assetShares,
      List<MoveDraft> drafts,
      int[] newOrdinal,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByOrganizations) {
    long placed = 0L;
    if (allow <= 0L) {
      return 0L;
    }
    if (target.existing != null && target.existingRoom > 0L) {
      long take = Math.min(allow, target.existingRoom);
      drafts.add(new MoveDraft(target.existing, target.hex, target.mode, speedPerMille, take, reason));
      placed += take;
    }
    if (placed >= allow || !target.newFeasible) {
      return placed;
    }
    long want = Math.min(allow - placed, MAX_HOUSEHOLD_POPULATION);
    long room =
        perMoveNewRoom(
            target.hex,
            target.mode,
            want,
            base,
            sourceRow,
            assetShares,
            reservedIdle,
            claimedByOrganizations);
    long take = Math.min(want, room);
    if (take > 0L) {
      drafts.add(
          new MoveDraft(
              newHouseholdId(source, target.hex, target.mode, newOrdinal[0]++),
              target.hex,
              target.mode,
              speedPerMille,
              take,
              reason));
      placed += take;
    }
    return placed;
  }

  /**
   * 新建户可承载人口：由本次 take 的劳动反推规模，再看该 hex 的产业模板里有没有"闲置/可租赁资产够且预留成功"的；
   * 第一个预留成功的产业即返回 take（预留已计入共享账本）。没有产业可预留 ⇒ 0。
   */
  private static long perMoveNewRoom(
      HexCoord hex,
      ProductionModeId mode,
      long take,
      EconomyData base,
      ClassRow sourceRow,
      Map<AssetShareId, AssetShare> assetShares,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByOrganizations) {
    if (DefaultProductionModes.DISPLACED.equals(mode)) {
      return take;
    }
    // 以源户的"劳动/人口"比例折算这批人带来的劳动（与执行期 laborTake 同源；不按人口 1:1 猜劳动）。
    long laborEstimate =
        sourceRow.population() <= 0L
            ? take
            : Math.multiplyExact(sourceRow.laborMilli(), take) / sourceRow.population();
    List<IndustryId> industries = new ArrayList<>(IndustryHexKeys.at(base.industries(), hex.q(), hex.r()));
    industries.sort(Comparator.comparing(IndustryId::value));
    for (IndustryId industryId : industries) {
      Industry industry = base.industries().get(industryId);
      if (industry != null
          && reserveIdleAssetsFor(
              industry, laborEstimate, assetShares, reservedIdle, claimedByOrganizations)) {
        return take;
      }
    }
    return 0L;
  }

  /**
   * "检查并预留"：按 laborMilli 推出的规模算每个 required 的 need，闲置资产够（Σ 闲置 − 已预留 ≥ need）则把
   * need 累加进共享 {@code reservedIdle} 并返回 true；任一 required 不足 ⇒ false，<b>且不做任何部分预留</b>。
   *
   * <p>★ 口径与执行期 {@code ModeMigrationSettlement.planAssetMoves} 对齐：同一份"闲置"定义
   * （{@link #isIdleShare}：{@code quantity > 0 && operator == owner && id ∉ claimedByOrganizations}）与同一个
   * {@code scale} 公式；按 {@link IndustryId} 预留 ⇒ 同一产业的多个 mode 共享同一个闲置分母，不会各看各的快照。
   * 执行侧逐笔拆完 share 后，各产业/种类的剩余量与本账本一致。
   */
  private static boolean reserveIdleAssetsFor(
      Industry industry,
      long laborMilli,
      Map<AssetShareId, AssetShare> assetShares,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByOrganizations) {
    long scale =
        industry.recipe().laborPerUnit() <= 0L
            ? 1L
            : Math.max(1L, laborMilli / industry.recipe().laborPerUnit());
    Map<AssetKind, Long> reservedForIndustry =
        reservedIdle.getOrDefault(industry.id(), Map.of());
    Map<AssetKind, Long> needs = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> required : industry.recipe().capacityPerUnit().entrySet()) {
      long need = Math.multiplyExact(scale, required.getValue());
      long available = 0L;
      for (AssetShare share : assetShares.values()) {
        if (!share.industry().equals(industry.id())
            || share.asset() != required.getKey()
            || !isIdleShare(share, claimedByOrganizations)) {
          continue;
        }
        available = Math.addExact(available, share.quantity());
      }
      long alreadyReserved = reservedForIndustry.getOrDefault(required.getKey(), 0L);
      if (Math.subtractExact(available, alreadyReserved) < need) {
        return false; // 全有或全不：先查完全部 required，确认全部可行后才写账本。
      }
      needs.put(required.getKey(), need);
    }
    Map<AssetKind, Long> bucket =
        reservedIdle.computeIfAbsent(industry.id(), key -> new LinkedHashMap<>());
    for (Map.Entry<AssetKind, Long> need : needs.entrySet()) {
      bucket.merge(need.getKey(), need.getValue(), Math::addExact);
    }
    return true;
  }

  /** 同 (hex, mode) 的已有目标家户：剩余容量最大者，tie 用 id 升序（确定性）。 */
  private static HouseholdId bestExistingTarget(
      HouseholdId source,
      HexCoord hex,
      ProductionMode mode,
      Map<HouseholdId, HouseholdMode> modeByHousehold,
      List<ClassPosition> producingPositions,
      Map<HouseholdId, ClassRow> rows) {
    Set<ClassPositionId> positionIds = new LinkedHashSet<>();
    for (ClassPosition position : producingPositions) {
      positionIds.add(position.id());
    }
    HouseholdId best = null;
    long bestRoom = 0L;
    for (HouseholdId household : sortedHouseholds(rows)) {
      if (household.equals(source)) {
        continue;
      }
      HouseholdMode candidate = modeByHousehold.get(household);
      if (candidate == null
          || !candidate.modeId.equals(mode.id())
          || !candidate.hex.equals(hex)
          || !positionIds.contains(candidate.positionId)) {
        continue;
      }
      long room = Math.max(0L, MAX_HOUSEHOLD_POPULATION - rows.get(household).population());
      if (room > bestRoom
          || (room == bestRoom
              && room > 0L
              && (best == null || household.value().compareTo(best.value()) < 0))) {
        best = household;
        bestRoom = room;
      }
    }
    return best;
  }

  // ── 预期收益（A 规则）────────────────────────────────────────────────────────────────────

  private static ExpectedProfit expectedProfit(
      EconomyData base,
      HouseholdId household,
      ClassRow row,
      HouseholdMode current,
      Map<ProductionOrganizationId, ProductionOrganization> organizations,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<AssetShareId, AssetShare> assetShares,
      Map<HexCoord, Market> markets,
      AccountSession accounts,
      OrganizationProfitBook.Book book) {
    ActorRef actor = HouseholdActors.of(household);
    ProductionOrganization organization = organizationOf(actor, organizations);
    ProductionUnit unit =
        organization != null && organization.unitId().isPresent()
            ? units.get(organization.unitId().get())
            : null;
    Industry industry = unit == null ? null : base.industries().get(unit.industry());
    Market market = markets.get(current.hex);
    Map<CommodityId, Long> stock = accounts.householdGoods().getOrDefault(household, Map.of());
    Map<CurrencyId, Long> wallet = accounts.householdMoney().getOrDefault(household, Map.of());

    boolean canSustain = true;
    long scale = 0L;
    if (organization == null || unit == null || industry == null) {
      canSustain = false;
    } else {
      long capacityScale = ProductionUnitBook.capacityScaleOf(unit, industry, assetShares);
      long laborPerUnit = industry.recipe().laborPerUnit();
      long laborScale =
          laborPerUnit <= 0L
              ? Long.MAX_VALUE
              : row.participationAdjustedLaborMilli() / laborPerUnit;
      scale = Math.max(0L, Math.min(capacityScale, laborScale));
      if (capacityScale < 1L || scale <= 0L) {
        canSustain = false;
      }
      for (Map.Entry<CommodityId, Long> input : industry.recipe().inputPerUnit().entrySet()) {
        if (input.getValue() > 0L && stock.getOrDefault(input.getKey(), 0L) <= 0L) {
          canSustain = false;
        }
      }
    }

    long expectedNet;
    long nextNeed;
    long expectedLabor;
    String reason;
    boolean priced =
        market != null
            && industry != null
            && industry.recipe().outputPerUnit().keySet().stream()
                .anyMatch(commodity -> market.priceOf(commodity) > 0L);
    if (priced) {
      long gross = 0L;
      for (Map.Entry<CommodityId, Long> output : industry.recipe().outputPerUnit().entrySet()) {
        gross =
            Math.addExact(
                gross,
                Math.multiplyExact(
                    Math.multiplyExact(output.getValue(), scale), market.priceOf(output.getKey())));
      }
      long inputCost = 0L;
      for (Map.Entry<CommodityId, Long> input : industry.recipe().inputPerUnit().entrySet()) {
        inputCost =
            Math.addExact(
                inputCost,
                Math.multiplyExact(
                        Math.multiplyExact(input.getValue(), scale), market.priceOf(input.getKey()))
                    / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
      }
      expectedNet = gross - inputCost;
      nextNeed = inputCost;
      expectedLabor =
          industry.recipe().laborPerUnit() <= 0L
              ? row.participationAdjustedLaborMilli()
              : Math.multiplyExact(scale, industry.recipe().laborPerUnit());
      reason = "localRefPrice";
    } else {
      // 缺价：退回本期真实净收益（仍不是未来价，也不是外生 π）。
      // ★★ P10.5：用原始 Σnet / Σlabor 重建，不经 netPerLabor × labor 的二次截断；无读数时读口返回 0。
      long net = book.net(current.modeId, current.hex);
      long labor = book.labor(current.modeId, current.hex);
      expectedNet = net;
      expectedLabor = labor;
      nextNeed = 0L;
      reason = "noLocalRefPrice:fallbackRealized";
    }

    long cash = market == null ? 0L : wallet.getOrDefault(market.numeraire(), 0L);
    long sellable = 0L;
    if (market != null) {
      for (Map.Entry<CommodityId, Long> good : stock.entrySet()) {
        long price = market.priceOf(good.getKey());
        if (price > 0L) {
          sellable =
              Math.addExact(
                  sellable,
                  good.getValue() * price / EconomyVocabulary.MILLI_PER_COMMODITY_UNIT);
        }
      }
    }
    return new ExpectedProfit(
        expectedNet, expectedLabor, nextNeed, Math.addExact(cash, sellable), canSustain, reason);
  }

  // ── 钱/债的比例切分（逐笔 floor；源户人口归零时余数随最后一笔走）────────────────────────────

  private static HouseholdMode currentOf(
      Map<HouseholdId, HouseholdMode> modeByHousehold, HouseholdId source) {
    HouseholdMode mode = modeByHousehold.get(source);
    if (mode == null) {
      throw new IllegalStateException("计划的源家户没有 mode 索引（坏输入）: " + source);
    }
    return mode;
  }

  private static CurrencyId currentCurrency(
      HouseholdMode current, AccountSession accounts, Map<HexCoord, Market> markets) {
    Market market = markets.get(current.hex);
    if (market != null) {
      return market.numeraire();
    }
    Map<CurrencyId, Long> money = accounts.householdMoney().getOrDefault(current.household, Map.of());
    CurrencyId smallest = null;
    for (CurrencyId currency : money.keySet()) {
      if (smallest == null || currency.value().compareTo(smallest.value()) < 0) {
        smallest = currency;
      }
    }
    return smallest;
  }

  /**
   * ★★ <b>P11.1 / D-023：按人口比例切源户的“全部币种”余额</b>（逐币种独立、逐笔 floor、<b>不做 FX</b>）。
   *
   * <pre>
   * 逐币种 c（CurrencyId.value 升序）：remaining = 当前余额
   *   第 i 笔（i = 0..n-1）：denom = max(1, sourcePopulation − movedBefore)
   *     share_i = (源户迁空 && i == n−1) ? remaining : ⌊remaining × populations[i] ÷ denom⌋
   *     remaining -= share_i；movedBefore += populations[i]
   * 源户不迁空 ⇒ 每个币种的 floor 余数留在源户；迁空 ⇒ 余数随最后一笔走。
   * </pre>
   *
   * <p>★ 返回的第 i 个 map 就是第 i 笔 move 的 {@code moneyByCurrency}；不同币种并列存在，不互相折算。
   */
  private static List<Map<CurrencyId, Long>> splitMoneyByCurrency(
      AccountSession accounts,
      long[] populations,
      long sourcePopulation,
      HouseholdId source,
      boolean emptiesSource) {
    List<Map<CurrencyId, Long>> shares = new ArrayList<>(populations.length);
    for (int i = 0; i < populations.length; i++) {
      shares.add(new LinkedHashMap<>());
    }
    Map<CurrencyId, Long> balances = accounts.householdMoney().getOrDefault(source, Map.of());
    List<CurrencyId> currencies = new ArrayList<>(balances.keySet());
    currencies.sort(Comparator.comparing(CurrencyId::value));
    for (CurrencyId currency : currencies) {
      long remainingBalance = balances.getOrDefault(currency, 0L);
      if (remainingBalance <= 0L) {
        continue;
      }
      long movedBefore = 0L;
      for (int i = 0; i < populations.length; i++) {
        long denom = Math.max(1L, sourcePopulation - movedBefore);
        long share =
            emptiesSource && i == populations.length - 1
                ? remainingBalance
                : Math.multiplyExact(remainingBalance, populations[i]) / denom;
        if (share < 0L || share > remainingBalance) {
          throw new IllegalStateException(
              "货币切分出现非法份额（坏数据）: source="
                  + source
                  + " currency="
                  + currency
                  + " share="
                  + share
                  + " remaining="
                  + remainingBalance);
        }
        remainingBalance -= share;
        movedBefore = Math.addExact(movedBefore, populations[i]);
        if (share > 0L) {
          shares.get(i).put(currency, share);
        }
      }
      if (remainingBalance < 0L) {
        throw new IllegalStateException("货币切分出现负余额（坏数据）: source=" + source);
      }
    }
    return shares;
  }

  private static long[] splitDebt(
      long[] populations,
      long sourcePopulation,
      HouseholdId source,
      Map<DebtContractId, DebtContract> debts,
      boolean emptiesSource) {
    long[] shares = new long[populations.length];
    List<DebtContract> contracts = new ArrayList<>();
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(source) && contract.principal() > 0L) {
        contracts.add(contract);
      }
    }
    contracts.sort(Comparator.comparing(contract -> contract.id().value()));
    long[] remainingPrincipal = new long[contracts.size()];
    for (int i = 0; i < contracts.size(); i++) {
      remainingPrincipal[i] = contracts.get(i).principal();
    }
    long movedBefore = 0L;
    for (int moveIndex = 0; moveIndex < populations.length; moveIndex++) {
      long denom = Math.max(1L, sourcePopulation - movedBefore);
      long total = 0L;
      for (int contractIndex = 0; contractIndex < contracts.size(); contractIndex++) {
        long take =
            emptiesSource && moveIndex == populations.length - 1
                ? remainingPrincipal[contractIndex]
                : Math.multiplyExact(remainingPrincipal[contractIndex], populations[moveIndex]) / denom;
        take = Math.min(take, remainingPrincipal[contractIndex]);
        remainingPrincipal[contractIndex] -= take;
        total = Math.addExact(total, take);
      }
      shares[moveIndex] = total;
      movedBefore = Math.addExact(movedBefore, populations[moveIndex]);
    }
    return shares;
  }

  // ── 小工具 ────────────────────────────────────────────────────────────────────────────────

  private static ProductionOrganization organizationOf(
      ActorRef actor, Map<ProductionOrganizationId, ProductionOrganization> organizations) {
    ProductionOrganization best = null;
    List<ProductionOrganizationId> ids = new ArrayList<>(organizations.keySet());
    ids.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId id : ids) {
      ProductionOrganization organization = organizations.get(id);
      if (organization != null && organization.organizer().equals(actor)) {
        if (best == null) {
          best = organization;
        }
      }
    }
    return best;
  }

  private static Map<CurrencyId, Long> accountsHouseholdMoney(
      AccountSession accounts, HouseholdId household) {
    return accounts.householdMoney().getOrDefault(household, Map.of());
  }

  private static boolean hasIndustryTemplate(EconomyData base, HexCoord hex) {
    return !IndustryHexKeys.at(base.industries(), hex.q(), hex.r()).isEmpty();
  }

  private static Map<ProductionModeId, List<ClassPosition>> producingPositionsByMode(
      EconomyData base, List<ProductionMode> modes) {
    Map<ProductionModeId, List<ClassPosition>> result = new LinkedHashMap<>();
    for (ProductionMode mode : modes) {
      ClassStructure structure = base.classStructures().get(mode.classStructureId());
      if (structure == null) {
        result.put(mode.id(), List.of());
        continue;
      }
      List<ClassPosition> positions = new ArrayList<>();
      for (ClassPosition position : structure.positions().values()) {
        if (shouldProduce(position)) {
          positions.add(position);
        }
      }
      positions.sort(Comparator.comparing(position -> position.id().value()));
      result.put(mode.id(), positions);
    }
    return result;
  }

  private static boolean shouldProduce(ClassPosition position) {
    return position.laborRole() != ClassPosition.LaborRole.NONE
        && position.surplusRole() != ClassPosition.SurplusRole.DEPENDENT;
  }

  // ── 闲置资产判据的唯一拼写点（policy / settlement 共用；纯谓词，不新增持久组件）─────────────

  /**
   * ★★ <b>P10.7 闲置/可租赁资产判据的唯一拼写点</b>（计划期 {@code reserveIdleAssetsFor} 与执行期 {@code
   * ModeMigrationSettlement.planAssetMoves} 共用；两侧不得各拼一遍）：
   *
   * <ol>
   *   <li>{@code quantity() > 0}；
   *   <li>{@code operator().equals(owner())}（自有自营；{@code operator != owner} 已被人实际使用）；
   *   <li>{@code id()} <b>不在</b>既有 {@code ProductionOrganization.assetSources} 的并集 {@code
   *       claimedByOrganizations} 里 —— 既有商号/组织正在使用的份额不是闲置：执行期若把它整条拆走，源份额行
   *       会被删除，组织引用会指向不存在的份额 id，{@code EconomyData} 当即以「生产组织使用的资产份额必须已存在」
   *       fail-closed。
   * </ol>
   */
  static boolean isIdleShare(AssetShare share, Set<AssetShareId> claimedByOrganizations) {
    Objects.requireNonNull(share, "share");
    Objects.requireNonNull(claimedByOrganizations, "claimedByOrganizations");
    return share.quantity() > 0L
        && share.operator().equals(share.owner())
        && !claimedByOrganizations.contains(share.id());
  }

  /**
   * ★★ P10.7 / P10.8：<b>既有组织正在使用的资产份额 id 并集</b>（只读派生，不新增持久组件）。计划期在
   * {@link #plan} 顶层从传入的 {@code organizations} 工作副本构建一次并透传；执行期在 {@link
   * ModeMigrationSettlement#createOrganizationAndUnit} 从同一份当天工作副本再构建一次。集合只用于
   * {@link #isIdleShare} 的包含判断，不参与排序，故迭代序不影响计划确定性。
   */
  static Set<AssetShareId> claimedAssetShares(
      Map<ProductionOrganizationId, ProductionOrganization> organizations) {
    Objects.requireNonNull(organizations, "organizations");
    Set<AssetShareId> claimed = new LinkedHashSet<>();
    for (ProductionOrganization organization : organizations.values()) {
      if (organization != null) {
        claimed.addAll(organization.assetSources());
      }
    }
    return claimed;
  }

  private static List<ProductionMode> sortedModes(EconomyData base) {
    List<ProductionMode> modes = new ArrayList<>(base.modes().values());
    modes.sort(Comparator.comparing(mode -> mode.id().value()));
    return modes;
  }

  private static List<HouseholdId> sortedHouseholds(Map<HouseholdId, ClassRow> rows) {
    List<HouseholdId> keys = new ArrayList<>(rows.keySet());
    keys.sort(Comparator.comparing(HouseholdId::value));
    return keys;
  }

  private static List<HexCoord> globalHexes(
      Map<HouseholdId, ClassRow> rows,
      Map<ProductionUnitId, ProductionUnit> units,
      Map<HexCoord, Market> markets) {
    TreeSet<HexCoord> hexes =
        new TreeSet<>(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    for (ClassRow row : rows.values()) {
      hexes.add(row.view().hex());
    }
    hexes.addAll(markets.keySet());
    for (ProductionUnit unit : units.values()) {
      Optional<String> key = IndustryHexKeys.hexKeyOf(unit.industry());
      if (key.isPresent()) {
        int separator = key.get().lastIndexOf('_');
        try {
          hexes.add(
              new HexCoord(
                  Integer.parseInt(key.get().substring(0, separator)),
                  Integer.parseInt(key.get().substring(separator + 1))));
        } catch (RuntimeException ignored) {
          // 不猜坐标
        }
      }
    }
    return new ArrayList<>(hexes);
  }

  private static String canonicalKey(HexCoord hex, ProductionModeId modeId) {
    return modeId.value() + "@" + hex.q() + "_" + hex.r();
  }

  private static HouseholdId newHouseholdId(
      HouseholdId source, HexCoord hex, ProductionModeId modeId, int ordinal) {
    // 迁移新建户 id 的唯一拼写点：确定性、无随机、不含 '.'；ordinal 只服务"同源同目标要建多户"。
    return HouseholdId.parse(
        "hh-mig-"
            + hex.q()
            + "_"
            + hex.r()
            + "-"
            + modeId.value()
            + "-"
            + source.value()
            + "-"
            + ordinal);
  }

  private static long sum(long[] values) {
    long total = 0L;
    for (long value : values) {
      total = Math.addExact(total, value);
    }
    return total;
  }

  /** 内部家户 mode/hex/position 索引。 */
  private record HouseholdMode(
      HouseholdId household, HexCoord hex, ProductionModeId modeId, ClassPositionId positionId) {
    HouseholdMode {
      Objects.requireNonNull(household, "household");
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(modeId, "modeId");
      Objects.requireNonNull(positionId, "positionId");
    }
  }

  /** 目标候选（existingRoom 为已有户剩余容量；newFeasible 为同 (hex, mode) 是否能新建）。 */
  private record Target(
      HexCoord hex,
      ProductionModeId mode,
      HouseholdId existing,
      long weight,
      int distance,
      boolean newFeasible,
      long existingRoom,
      String canonical) {
    Target {
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(canonical, "canonical");
    }
  }

  /** 施工坞（人口切分与目标确认完成后才冻成 {@link MigrationMove}）。 */
  private static final class MoveDraft {
    final HouseholdId target;
    final HexCoord targetHex;
    final ProductionModeId targetMode;
    final long transferSpeedPerMille;
    final long population;
    final String reason;

    MoveDraft(
        HouseholdId target,
        HexCoord targetHex,
        ProductionModeId targetMode,
        long transferSpeedPerMille,
        long population,
        String reason) {
      this.target = target;
      this.targetHex = targetHex;
      this.targetMode = targetMode;
      this.transferSpeedPerMille = transferSpeedPerMille;
      this.population = population;
      this.reason = reason;
    }
  }
}

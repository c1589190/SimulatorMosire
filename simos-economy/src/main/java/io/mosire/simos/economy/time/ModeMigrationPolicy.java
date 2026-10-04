package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
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
 * ★★ <b>P10.2 家户生产方式迁移计划器（架构 §5.2 的 ⑧）</b>：用 {@link OrganizationProfitBook} 的<b>真实</b>
 * 单位劳动净收益做权重，输出确定性的"人/钱/债转移到目标 mode 家户"的计划。
 *
 * <pre>
 * 目标权重 weight = max(0, target.netPerLabor − current.netPerLabor)          （真读数，不是外生 π）
 * 目标排序       weight 降序 → 距离升序（同格 0、邻格 1）→ (hex, mode) 规范串升序 → 家户 id 升序
 * 基线速度        MIGRATION_PER_MILLE = 10‰/周期（可迁人口 = max(1, ⌊人口×10÷1000⌋)）
 * A 规则        expectedNet(current) < 0 且 cash + sellableInventory − nextCycleInputNeed < 0
 *                · 存在 netPerLabor 严格更高的可行目标 ⇒ transferSpeed = 1000‰，把人口转出
 *                · 当前户已是最优（或没有更高可行目标）⇒ 不转移、继续生产，债务/欠款走现有资本化
 *                · 无法维持生产且没有任何能承载的可行目标 ⇒ 转 DISPLACED（同样 1000‰）
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
      String reason) {

    /** 迁移原因词表（规范串）。 */
    public static final String REASON_PROFIT_WEIGHTED = "PROFIT_WEIGHTED";

    public static final String REASON_A_RULE_MAX_SPEED = "A_RULE_MAX_SPEED";
    public static final String REASON_DISPLACED_ABSORBED = "DISPLACED_ABSORBED";

    public MigrationMove {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(targetHex, "targetHex");
      Objects.requireNonNull(targetMode, "targetMode");
      Objects.requireNonNull(reason, "reason");
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
              book);
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
      CurrencyId currency = currentCurrency(currentOf(modeByHousehold, source), accounts, markets);
      long[] moneyShares =
          splitMoney(accounts, populations, sourcePopulation, currency, source, emptiesSource);
      long[] debtShares = splitDebt(populations, sourcePopulation, source, debts, emptiesSource);
      for (int i = 0; i < drafts.size(); i++) {
        MoveDraft draft = drafts.get(i);
        moves.add(
            new MigrationMove(
                source,
                draft.target,
                draft.targetHex,
                draft.targetMode,
                draft.transferSpeedPerMille,
                draft.population,
                moneyShares[i],
                debtShares[i],
                draft.reason));
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
      OrganizationProfitBook.Book book) {
    long currentPerLabor =
        book.hasReading(current.modeId, current.hex)
            ? book.netPerLabor(current.modeId, current.hex)
            : 0L;
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
            assetShares);
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
              assetShares);
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
        assetShares);
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
        long weight = Math.max(0L, book.netPerLabor(mode.id(), hex) - currentPerLabor);
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
   * 不丢人、也不凭空造承载。
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
      Map<AssetShareId, AssetShare> assetShares) {
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
              newOrdinal);
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
              newOrdinal);
      remaining -= placed;
    }
    return drafts;
  }

  /** 往一个目标里放人口（已有户剩余容量 → 该 (hex, mode) 至多新建一户）；返回实际放入人口。 */
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
      int[] newOrdinal) {
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
    long room = perMoveNewRoom(target.hex, target.mode, want, base, sourceRow, assetShares);
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

  /** 新建户可承载人口：由本次 take 的劳动反推规模，再看该 hex 同产业闲置/可租赁资产够不够。不足 ⇒ 0。 */
  private static long perMoveNewRoom(
      HexCoord hex,
      ProductionModeId mode,
      long take,
      EconomyData base,
      ClassRow sourceRow,
      Map<AssetShareId, AssetShare> assetShares) {
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
      if (industry != null && hasIdleAssetsFor(industry, laborEstimate, assetShares)) {
        return take;
      }
    }
    return 0L;
  }

  /** 这个 (hex 产业, asset) 上，按 take 人口推出的劳动规模，闲置/可租赁资产是否够。 */
  private static boolean hasIdleAssetsFor(
      Industry industry, long laborMilli, Map<AssetShareId, AssetShare> assetShares) {
    long scale =
        industry.recipe().laborPerUnit() <= 0L
            ? 1L
            : Math.max(1L, laborMilli / industry.recipe().laborPerUnit());
    for (Map.Entry<io.mosire.simos.actor.api.asset.AssetKind, Long> required :
        industry.recipe().capacityPerUnit().entrySet()) {
      long need = Math.multiplyExact(scale, required.getValue());
      long available = 0L;
      for (AssetShare share : assetShares.values()) {
        if (!share.industry().equals(industry.id())
            || share.asset() != required.getKey()
            || !share.operator().equals(share.owner())) {
          continue;
        }
        available = Math.addExact(available, share.quantity());
      }
      if (available < need) {
        return false;
      }
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
      long net =
          book.hasReading(current.modeId, current.hex)
              ? book.netPerLabor(current.modeId, current.hex)
              : 0L;
      long labor =
          book.hasReading(current.modeId, current.hex)
              ? book.labor(current.modeId, current.hex)
              : Math.max(1L, row.participationAdjustedLaborMilli());
      expectedNet = net * Math.max(1L, labor);
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

  private static long[] splitMoney(
      AccountSession accounts,
      long[] populations,
      long sourcePopulation,
      CurrencyId currency,
      HouseholdId source,
      boolean emptiesSource) {
    long[] shares = new long[populations.length];
    if (currency == null) {
      return shares;
    }
    long remainingBalance =
        accounts.householdMoney().getOrDefault(source, Map.of()).getOrDefault(currency, 0L);
    if (remainingBalance <= 0L) {
      return shares;
    }
    long movedBefore = 0L;
    for (int i = 0; i < populations.length; i++) {
      long denom = Math.max(1L, sourcePopulation - movedBefore);
      shares[i] =
          emptiesSource && i == populations.length - 1
              ? remainingBalance
              : Math.multiplyExact(remainingBalance, populations[i]) / denom;
      remainingBalance -= shares[i];
      movedBefore = Math.addExact(movedBefore, populations[i]);
    }
    if (remainingBalance < 0L) {
      throw new IllegalStateException("货币切分出现负余额（坏数据）: source=" + source);
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

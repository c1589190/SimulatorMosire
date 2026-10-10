package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdClassMembership;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketTables;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
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
 * ★★ <b>P10.2 / P10.5 家户生产方式迁移计划器（架构 §5.2 的 ⑧；D-024 起换权重来源）</b>：用 {@link ExpectedProfitBook} 对（家户 ×
 * 候选 mode × hex × 市场）现算的<b>预期</b>单位劳动净收益做权重，输出确定性的 "人/钱/债转移到目标 mode 家户"的计划。
 *
 * <p>★★ <b>D-024 的两处替换</b>：① 候选门槛不再要求该 (mode, hex) 已有 {@link EnterpriseProfitBook} 真实读数
 * （`hasReading`/`labor>0` 门槛已删）；② A 规则不再读旧 {@code output × 价 − 投入} 的局部公式，改读同一个 {@link
 * ExpectedProfitBook.Prospect}。{@link EnterpriseProfitBook} 仍由 {@code collect} 产出、供读数对账，只是 不再是候选门槛。
 *
 * <pre>
 * 目标权重 weight = max(0, target.netPerLaborScaled − current.netPerLaborScaled)
 *                （同一把百万分之一单位的高精度尺；两侧都来自 ExpectedProfitBook）
 * 目标排序       weight 降序 → 距离升序（同格 0、邻格 1）→ (hex, mode) 规范串升序 → 家户 id 升序
 * 基线速度        MIGRATION_PER_MILLE = 10‰/周期（可迁人口 = max(1, ⌊人口×10÷1000⌋)）
 * A 规则        expectedNet(current) < 0 且 cash + sellableInventory − nextCycleInputNeed < 0
 *                · 存在 netPerLaborScaled 严格更高的可行目标 ⇒ transferSpeed = 1000‰，把人口转出
 *                · 当前户已是最优（或没有更高可行目标）⇒ 不转移、继续生产，债务/欠款走现有资本化
 *                · 无法维持生产且没有任何能承载的可行目标 ⇒ 转 DISPLACED（同样 1000‰）
 * 新建可行性      同一 plan() 共享"闲置资产预留账本"（IndustryId × AssetKind）；
 *                available = Σ 闲置(quantity>0 ∧ operator==owner ∧ id∉claimed) − 已预留，
 *                claimed = 既有组织 assetSources ∪ 在产 unit 所占份额（同 industry、同 operator、quantity>0），
 *                任一 required 不足则整笔不预留。
 *                后一个新建户/后一遍 allocate 只看前面计划消费后的剩余，与执行期逐笔拆份额一致。
 *                ★ 闲置判据的唯一拼写点 = {@link #isIdleShare(OwnershipStake, Set)}（policy/settlement 共用）：
 *                  只有 owner==operator 且不被任何组织/在产 unit 使用的份额才算闲置（D-024 修复 1b）。
 * </pre>
 *
 * <p>★★ <b>D-022 硬约束</b>：计划里只有 {@code MigrationMove}（源户 → 目标户）；{@code targetMode} 只属于目标家户， 绝不产生"把源户
 * mode/standing/enterprise/unit.modeKey 原地改成 targetMode"的计划项。
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

  /** 需求簿视界的兜底（没有任何产业模板时）：与真实运行时世界的 120 天周期同值。 */
  private static final long DEFAULT_DEMAND_HORIZON_DAYS = 120L;

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
   * <p>★ {@code target} 已有家户（合并）或计划新建家户 id（新建）；{@code target != source} 由构造期守卫判死； {@code
   * targetMode} 是<b>目标家户</b>的 mode，源户 mode 在整条执行路径上一字不改。
   *
   * <p>★★ <b>D-023：货币随迁按全部币种</b> —— 权威口径是 {@link #moneyByCurrency()}（逐币种按人口比例
   * floor；源户迁空时该币种余数随最后一笔走；<b>不做 FX</b>）。{@link #moneyMilli()} 只保留为旧读口（计划的主币种 份额），执行器以 {@code
   * moneyByCurrency} 为准；空 map = 旧/手工计划 ⇒ 执行器就地按人口比例补算全部币种。
   *
   * <p>★★ <b>M-D 追加 {@code primaryPositionId}：主业落点</b>（设计书 §13 I-A/I-D、I-3）—— 该笔 move
   * 的**生产方式排序表**里， 与 {@code (targetMode, targetHex)} 相符的那一项的**位置 id**；它就是新建目标家户 {@code
   * currentPositionId} 的落点 （"排序表第 1 项 ⇒ 主业"，落点仍走既有模式变迁路径 {@code ModeMigrationSettlement}）。★ {@code
   * null} = 没有排序表落点 （旧调用方/夹具）⇒ 执行器退回既有的 {@code pickTargetPosition}（按 relationToMeans+surplusRole
   * 匹配、再位置 id 升序）—— **逐值不变**。
   *
   * @param primaryPositionId 排序表第 1 项的落点位置（可 {@code null} = 按既有口径选位置）
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
      Map<CurrencyId, Long> moneyByCurrency,
      ClassPositionId primaryPositionId) {

    /** 迁移原因词表（规范串）。 */
    public static final String REASON_PROFIT_WEIGHTED = "PROFIT_WEIGHTED";

    public static final String REASON_A_RULE_MAX_SPEED = "A_RULE_MAX_SPEED";
    public static final String REASON_DISPLACED_ABSORBED = "DISPLACED_ABSORBED";

    /** 旧读口的 10 参构造（没有排序表落点 ⇒ {@code primaryPositionId = null}，执行器按既有口径选位置）。 */
    public MigrationMove(
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
          moneyByCurrency,
          null);
    }

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
          Map.of(),
          null);
    }

    public MigrationMove {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(targetHex, "targetHex");
      Objects.requireNonNull(targetMode, "targetMode");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(moneyByCurrency, "moneyByCurrency");
      if (source.equals(target)) {
        throw new IllegalArgumentException(
            "MigrationMove.target 不得 == source（D-022：不原地改源户）: " + source);
      }
      if (population <= 0L) {
        throw new IllegalArgumentException("MigrationMove.population 必须 > 0: " + population);
      }
      if (moneyMilli < 0L || debtMilli < 0L) {
        throw new IllegalArgumentException(
            "MigrationMove 的 moneyMilli/debtMilli 不得为负: " + moneyMilli + "/" + debtMilli);
      }
      // ★★ W4（2026-10-11）：速度不再只允许两个具名值 —— 基线速度改成"债务/资产"的函数
      //   （用户第二批第 1 条：「直接根据债务/资产规模估算向其他阶层流动的速度」），A 规则仍是 1000‰ 的上界。
      //   口径 = ‰/周期，合法区间 [1, 1000]；0 表示"不迁移"（由调用方在产生 move 之前就挡掉）。
      if (transferSpeedPerMille < 1L || transferSpeedPerMille > A_RULE_TRANSFER_SPEED_PER_MILLE) {
        throw new IllegalArgumentException(
            "MigrationMove.transferSpeedPerMille 必须在 [1, "
                + A_RULE_TRANSFER_SPEED_PER_MILLE
                + "]（W4：基线 = max("
                + MIGRATION_PER_MILLE
                + ", f(债务/资产))，A 规则 = "
                + A_RULE_TRANSFER_SPEED_PER_MILLE
                + "）: "
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
              "MigrationMove.moneyByCurrency 的份额不得为负: "
                  + entry.getKey()
                  + " = "
                  + entry.getValue());
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
      EconomyDayView base,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<HouseholdId, HouseholdClassMembership> classMemberships,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<LaborAllocationId, HouseholdLaborCommitment> laborCommitments,
      Map<MarketZoneId, MarketTable> markets,
      Map<DebtContractId, DebtContract> debts,
      AccountSession accounts,
      EnterpriseProfitBook.Book book,
      long day,
      MarketTopology topology,
      List<MarketReport> marketReports) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(enterprises, "organizations");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(householdEconomies, "rows");
    Objects.requireNonNull(classMemberships, "standings");
    Objects.requireNonNull(assetShares, "assetShares");
    Objects.requireNonNull(laborCommitments, "allocations");
    Objects.requireNonNull(markets, "markets");
    Objects.requireNonNull(debts, "debts");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(book, "book");
    Objects.requireNonNull(relations, "relations");
    Objects.requireNonNull(topology, "topology");
    Objects.requireNonNull(marketReports, "marketReports（没有报告给空表）");
    // ★★ P10.6：计划期共享的"闲置资产预留账本"。同一 plan() 的所有源户、所有目标新建户、allocate 的两遍
    //    都读写同一份账本 ⇒ 后一个新建户看到的是前面计划已消费后的剩余闲置资产，与执行期逐笔拆份额一致。
    Map<IndustryId, Map<AssetKind, Long>> reservedIdle = new LinkedHashMap<>();
    // ★★ P10.7 / P10.8：既有 ProductionEnterprise.assetSources 正在使用的份额不是"闲置"——执行期把它拆/移走后，
    //    组织引用会指向已删除的份额 id，EconomyData 当即以「生产组织使用的资产份额必须已存在」拒绝。
    //    claimed 必须读调用方传入的当天工作副本 enterprises + units + assetShares（可能已被当天自动组织阶段加入
    //    新组织/新 unit），不能用当天开始时的不可变 base；只在这里构建一次，与预留账本一起透传；谓词与执行期共用
    //    {@link #isIdleShare}，不新增持久组件。
    //    ★★ D-024 修复 1：同一份集合也作为唯一入参透传给所有 {@link ExpectedProfitBook#prospect} 调用
    //    （当前户读数 + 候选），prospect 内部不得再从 base.productionOrganizations() 重算（那是过期快照）。
    //    ★★ D-024 修复 1b：claimed 的"单位占用"格 = 与在产 unit 同 industry、同 operator 且 quantity>0 的份额。
    //    seeder 直接以 RegimeOperators.defaultOperator(...)（ESTATE 等）建立的 farm/weave/craft/trade 主 unit
    // 没有
    //    对应 ProductionEnterprise，只按组织引用格会把它们的土地/作坊/船畜误判成"闲置可租"。
    //    单参兼容重载只算组织引用格，生产路径一律走三参版本。
    Set<AssetShareId> claimedByEnterprises =
        claimedOwnershipStakes(enterprises, units, assetShares);
    if (base.modes().isEmpty()) {
      return MigrationPlan.empty(); // 旧档（无 modes）：逐字 no-op
    }

    // ★★ D-024 / 设计 §3.3：需求簿只构建一次，透传给所有源户/候选评估（horizonDays = 世界产业 cycleDays 的统一值；
    //   多个不同值时取最大者——本轮真实世界都是 120）。
    long horizonDays = demandHorizonDays(base, units);
    MarketDemandBook.Book demandBook =
        MarketDemandBook.build(
            marketReports,
            topology,
            householdEconomies,
            units,
            base.industries(),
            assetShares,
            base.shipments(),
            accounts,
            markets,
            day,
            horizonDays);

    Map<HouseholdId, HouseholdMode> modeByHousehold = new LinkedHashMap<>();
    for (HouseholdId household : sortedHouseholds(householdEconomies)) {
      HouseholdEconomy householdEconomy = householdEconomies.get(household);
      HouseholdClassMembership classMembership = classMemberships.get(household);
      if (householdEconomy == null
          || householdEconomy.population() <= 0L
          || classMembership == null) {
        continue;
      }
      ProductionRole position = base.classPositions().get(classMembership.currentPositionId());
      if (position == null) {
        continue;
      }
      modeByHousehold.put(
          household,
          new HouseholdMode(
              household, householdEconomy.view().hex(), position.modeId(), position.id()));
    }
    if (modeByHousehold.isEmpty()) {
      return MigrationPlan.empty();
    }

    List<HexCoord> hexes = globalHexes(householdEconomies, units, markets, base.marketZones());
    List<ProductionMode> modes = sortedModes(base);
    Map<ProductionModeId, List<ProductionRole>> producingPositions =
        producingPositionsByMode(base, modes);

    // ★★ W4（2026-10-11，S-1）：**基线速度改按"债务/资产"**（用户第二批第 1 条：「直接根据债务/资产规模估算
    //   向其他阶层流动的速度」）。口径与 {@link ClassSlideSettlement} / 市场轮结算序**共用同一份快照**
    //   （{@link SettlementOrderBook}：资产 = 货币 + 商品库存折价；负债 = Σ 未偿本金的可定价共同价值）
    //   ⇒ "排序用的发达程度"与"滑落速度用的债务/资产"不可能漂成两把尺。
    //   ★ 速度 = max(基线 MIGRATION_PER_MILLE, f(债务/资产))：f 见 {@link
    // ClassSlideSettlement#speedPerMille}。
    //     取 max（而不是纯 f）是**本批的 fail-closed 取舍**：纯 f 会让"无债户"速度恒 0 ⇒ 生产方式迁移整条机制
    //     对绝大多数户停摆（那是另一项设计决定，不在 W4 授权范围内）。偏离记录见实现账本。
    SettlementOrderBook ordering =
        SettlementOrderBook.snapshot(
            day, topology, markets, householdEconomies, debts, accounts, base);
    LinkedHashMap<HouseholdId, List<MoveDraft>> draftsBySource = new LinkedHashMap<>();
    for (HouseholdId source : sortedHouseholds(householdEconomies)) {
      HouseholdMode current = modeByHousehold.get(source);
      if (current == null) {
        continue;
      }
      HouseholdEconomy sourceHouseholdEconomy = householdEconomies.get(source);
      List<MoveDraft> drafts =
          planForSource(
              base,
              source,
              sourceHouseholdEconomy,
              current,
              hexes,
              modes,
              producingPositions,
              modeByHousehold,
              enterprises,
              units,
              householdEconomies,
              assetShares,
              relations,
              markets,
              accounts,
              reservedIdle,
              claimedByEnterprises,
              demandBook,
              topology,
              day,
              slideSpeedPerMille(ordering, source));
      if (!drafts.isEmpty()) {
        draftsBySource.put(source, drafts);
      }
    }
    if (draftsBySource.isEmpty()) {
      return MigrationPlan.empty();
    }

    List<MigrationMove> moves = new ArrayList<>();
    for (HouseholdId source : sortedHouseholds(householdEconomies)) {
      List<MoveDraft> drafts = draftsBySource.get(source);
      if (drafts == null) {
        continue;
      }
      long sourcePopulation = householdEconomies.get(source).population();
      long[] populations = new long[drafts.size()];
      for (int i = 0; i < drafts.size(); i++) {
        populations[i] = drafts.get(i).population;
      }
      boolean emptiesSource = sum(populations) == sourcePopulation;
      List<Map<CurrencyId, Long>> moneySharesByCurrency =
          splitMoneyByCurrency(accounts, populations, sourcePopulation, source, emptiesSource);
      // ★ 旧读口 moneyMilli 仍报"主币种"份额；权威全币种表在 moneyByCurrency（D-023）。
      HouseholdMode legacyCurrent = currentOf(modeByHousehold, source);
      CurrencyId legacyCurrency =
          currentCurrency(
              legacyCurrent,
              accounts,
              markets,
              MarketZoneBook.zoneOfHex(base, legacyCurrent.hex).orElse(null));
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
                moneyByCurrency,
                draft.primaryPositionId));
      }
    }
    return new MigrationPlan(moves);
  }

  // ── 逐源户计划 ─────────────────────────────────────────────────────────────────────────────

  private static List<MoveDraft> planForSource(
      EconomyDayView base,
      HouseholdId source,
      HouseholdEconomy sourceHouseholdEconomy,
      HouseholdMode current,
      List<HexCoord> hexes,
      List<ProductionMode> modes,
      Map<ProductionModeId, List<ProductionRole>> producingPositions,
      Map<HouseholdId, HouseholdMode> modeByHousehold,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<MarketZoneId, MarketTable> markets,
      AccountSession accounts,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByEnterprises,
      MarketDemandBook.Book demandBook,
      MarketTopology topology,
      long day,
      long w4SlideSpeedPerMille) {
    // ★★ D-024：当前户读数改由同一个预期利润计算器现算（真实利润账退位为事后对账）。
    Industry currentIndustry = currentIndustryOf(base, source, current, units, enterprises);
    ExpectedProfitBook.Prospect currentProspect =
        ExpectedProfitBook.prospect(
            base,
            source,
            current.modeId,
            current.hex,
            currentIndustry,
            marketOf(markets, topology, current.hex),
            demandBook,
            assetShares,
            claimedByEnterprises,
            accounts,
            units,
            relations,
            topology,
            day);
    long currentPerLabor = currentProspect.netPerLaborScaled();
    // ★★ M-D：被排序输入挡下的跑商候选（具名；进排序表的 excluded 与 DEBUG 日志）。
    List<PrimaryModeRanking.Excluded> excluded = new ArrayList<>();
    List<Target> targets =
        buildTargets(
            base,
            source,
            sourceHouseholdEconomy,
            current,
            currentPerLabor,
            excluded,
            hexes,
            modes,
            producingPositions,
            modeByHousehold,
            householdEconomies,
            units,
            assetShares,
            claimedByEnterprises,
            relations,
            markets,
            accounts,
            demandBook,
            topology,
            day);
    // ★★ M-D（设计书 §13 I-A/I-B）：**生产方式排序表** —— 当前项（主业）+ 全部成立候选，
    //   排序输入 = 市场议价权（本格运力占比‰，G-1 同一拼写点）+ 库存（货币余额 + 商品库存折价 + 生产资料份额）；
    //   破平 = 位置 id 升序（I-3）。★ 纯读数：本表不写任何状态，落点仍走既有模式变迁路径。
    PrimaryModeRanking.Table table =
        rankingTable(
            base, source, current, currentProspect, targets, excluded, accounts, markets, topology);
    PrimaryModeRanking.logTable(day, table);
    // ★★ M-D（I-C）：排序表第 1 项 = **加速流向的那一项目标** —— 只把该目标提到候选序最前
    //   （权重、承载约束、门槛一字不改；"副业足够赚钱"就体现在它排到了第 1 项）。
    List<Target> ranked = primaryFirst(targets, table);
    ExpectedProfit expected =
        expectedProfit(
            currentProspect, liquidityMilli(accounts, markets, topology, source, current.hex));
    boolean aRule =
        expected.expectedNetMilli() < 0L
            && expected.liquidityMilli() < expected.nextCycleInputNeedMilli();

    List<MoveDraft> drafts;
    if (aRule) {
      List<Target> higher = ranked.stream().filter(target -> target.weight > 0L).toList();
      if (!higher.isEmpty()) {
        drafts =
            allocate(
                source,
                sourceHouseholdEconomy.population(),
                sourceHouseholdEconomy.population(),
                A_RULE_TRANSFER_SPEED_PER_MILLE,
                MigrationMove.REASON_A_RULE_MAX_SPEED,
                higher,
                base,
                sourceHouseholdEconomy,
                assetShares,
                reservedIdle,
                claimedByEnterprises);
      } else if (!expected.canSustainProduction()) {
        List<Target> displaced =
            buildDisplacedTargets(
                source,
                current,
                hexes,
                modes,
                producingPositions,
                modeByHousehold,
                householdEconomies);
        drafts =
            displaced.isEmpty()
                ? List.of()
                : allocate(
                    source,
                    sourceHouseholdEconomy.population(),
                    sourceHouseholdEconomy.population(),
                    A_RULE_TRANSFER_SPEED_PER_MILLE,
                    MigrationMove.REASON_DISPLACED_ABSORBED,
                    displaced,
                    base,
                    sourceHouseholdEconomy,
                    assetShares,
                    reservedIdle,
                    claimedByEnterprises);
      } else {
        // 当前户已是最优（或没有可承载目标）：不转移，债务按现有路径继续增长。
        drafts = List.of();
      }
    } else {
      List<Target> positive = ranked.stream().filter(target -> target.weight > 0L).toList();
      // ★★ W4：基线速度 = max(MIGRATION_PER_MILLE, f(债务/资产))（见 plan() 里的口径注与偏离记录）。
      long baselineSpeedPerMille = Math.max(MIGRATION_PER_MILLE, w4SlideSpeedPerMille);
      long moveable =
          Math.min(
              sourceHouseholdEconomy.population(),
              Math.max(1L, sourceHouseholdEconomy.population() * baselineSpeedPerMille / 1000L));
      drafts =
          positive.isEmpty() || moveable <= 0L
              ? List.of()
              : allocate(
                  source,
                  sourceHouseholdEconomy.population(),
                  moveable,
                  baselineSpeedPerMille,
                  MigrationMove.REASON_PROFIT_WEIGHTED,
                  positive,
                  base,
                  sourceHouseholdEconomy,
                  assetShares,
                  reservedIdle,
                  claimedByEnterprises);
    }
    // ★★ M-D（冻结项 7）：INFO = **主业变更**（谁、从哪到哪、为什么）—— 只在真的产生了迁移、且排序表第 1 项
    //   换了生产方式时打点；表本身与判据在 DEBUG（{@link PrimaryModeRanking#logTable}）。
    maybeLogPrimaryChange(day, source, current, currentPerLabor, table, drafts);
    return drafts;
  }

  // ── M-D：生产方式排序表（§13 I-A..I-E；唯一拼写点在本节）───────────────────────────────────

  /**
   * ★★ <b>把"当前项 + 全部成立候选"排成一张表</b>（I-A/I-B）—— <b>纯读数</b>，不写任何状态。
   *
   * <p>★ 当前项用该户**真实的 {@code currentPositionId}**（"从哪"要准确），收益率用同一个 {@link
   * ExpectedProfitBook.Prospect}（与候选同一把尺，A 规则与排序表不可能漂开）。DISPLACED 一类没有位置的候选不进表。
   */
  private static PrimaryModeRanking.Table rankingTable(
      EconomyDayView base,
      HouseholdId source,
      HouseholdMode current,
      ExpectedProfitBook.Prospect currentProspect,
      List<Target> targets,
      List<PrimaryModeRanking.Excluded> excluded,
      AccountSession accounts,
      Map<MarketZoneId, MarketTable> markets,
      MarketTopology topology) {
    List<PrimaryModeRanking.Row> rows = new ArrayList<>();
    String currentGate =
        DefaultProductionModes.MERCHANT.equals(current.modeId)
            ? PrimaryModeRanking.merchantGateReason(
                base, source, current.hex, accounts.householdGoods())
            : null;
    if (currentGate == null) {
      rows.add(
          new PrimaryModeRanking.Row(
              current.positionId,
              current.modeId,
              current.hex,
              currentProspect.netPerLaborScaled(),
              bargainingPowerPerMille(base, source, current.modeId, current.hex, accounts),
              PrimaryModeRanking.inventoryMilli(accounts, markets, topology, source, current.hex),
              currentProspect.reason()));
    } else {
      // ★ 当前主业就是跑商、但**工具不够一趟**（§12 H-D/H-5）：库存是排序输入之一 ⇒ 它进不了"第 1 项"
      //   （跑商在决策层不成立；它在市场轮的既有 M-C 门槛另有具名 tool-short）。★ 只影响**本表的读数**，
      //   A 规则读的 expectedNet 一字不改（冻结项 5）。
      excluded.add(new PrimaryModeRanking.Excluded(current.modeId, current.hex, currentGate));
    }
    for (Target target : targets) {
      if (target.positionId == null) {
        continue; // 没有位置的目标（DISPLACED）：排序表只排"生产方式位置"，它不进表
      }
      rows.add(
          new PrimaryModeRanking.Row(
              target.positionId,
              target.mode,
              target.hex,
              target.yieldPerLaborScaled,
              bargainingPowerPerMille(base, source, target.mode, target.hex, accounts),
              PrimaryModeRanking.inventoryMilli(accounts, markets, topology, source, target.hex),
              target.prospectReason));
    }
    return PrimaryModeRanking.rank(source, rows, excluded);
  }

  /**
   * ★★ <b>排序输入①：市场议价权（占比‰）</b>—— 只有跑商行非零（议价权是**运力**概念；§11.4 G-1 的唯一口径， 经 {@link
   * MerchantCapacityPool#sharePerMilleAsProviderAt}）。
   */
  private static long bargainingPowerPerMille(
      EconomyDayView base,
      HouseholdId source,
      ProductionModeId mode,
      HexCoord hex,
      AccountSession accounts) {
    if (!DefaultProductionModes.MERCHANT.equals(mode)) {
      return 0L;
    }
    return MerchantCapacityPool.sharePerMilleAsProviderAt(
        base, source, hex, accounts.householdGoods());
  }

  /**
   * ★★ <b>I-C：把排序表第 1 项对应的目标提到候选序最前</b>（"副业足够赚钱 ⇒ 既有 A 规则加速流向它"）。
   *
   * <p>★ 只改**次序**：权重比例、承载约束、新建可行性、门槛一律不动 ⇒ 与改前逐值可比。 ★ 第 1 项就是当前项（主业没变）或候选里没有它的 (mode, hex) ⇒
   * 原序返回（一字不改）。
   */
  private static List<Target> primaryFirst(List<Target> targets, PrimaryModeRanking.Table table) {
    PrimaryModeRanking.Row primary = table.primary();
    if (primary == null || targets.size() <= 1) {
      return targets;
    }
    Target first = null;
    List<Target> rest = new ArrayList<>(targets.size());
    for (Target target : targets) {
      if (first == null
          && target.mode.equals(primary.modeId())
          && target.hex.equals(primary.hex())) {
        first = target;
      } else {
        rest.add(target);
      }
    }
    if (first == null) {
      return targets;
    }
    List<Target> ordered = new ArrayList<>(targets.size());
    ordered.add(first);
    ordered.addAll(rest);
    return ordered;
  }

  /** ★★ <b>INFO：主业变更</b>（谁、从哪到哪、为什么）—— 排序表第 1 项的生产方式与该户当前主业不同、且本轮确实产生了 迁移时才打点（否则只是读数，进 DEBUG）。 */
  private static void maybeLogPrimaryChange(
      long day,
      HouseholdId source,
      HouseholdMode current,
      long currentYield,
      PrimaryModeRanking.Table table,
      List<MoveDraft> drafts) {
    PrimaryModeRanking.Row primary = table.primary();
    if (primary == null || drafts.isEmpty() || primary.modeId().equals(current.modeId)) {
      return;
    }
    // "从哪"的收益率取**该户当前项那一次的 prospect 读数**（与本方法无关的 A 规则同源），不依赖当前行是否在表里。
    PrimaryModeRanking.logPrimaryChange(
        day, source, current.positionId, current.modeId, primary, currentYield);
  }

  /**
   * 目标候选（已有家户 + 可新建；同 (hex, mode) 一条，已有家户优先）。
   *
   * <p>★★ <b>D-024</b>：删除"该 (mode, hex) 必须有 {@link EnterpriseProfitBook} 真实读数"的门槛， 对每个 距离≤1 的 hex ×
   * 每个 mode 调 {@link ExpectedProfitBook#prospect} 现算；不可行/规模 0 ⇒ 跳过。 权重仍是同一把百万分之一尺：{@code max(0,
   * target.netPerLaborScaled − current.netPerLaborScaled)}。
   *
   * <p>★★ <b>D-024 修复 1 / 1b</b>：{@code claimedByEnterprises} 由 {@link #plan} 从当天工作副本 {@code
   * enterprises + units + assetShares} 算出后传入，并原样透传给每个候选 {@code prospect(...)}；本方法不从 {@code base} 重算
   * claimed，避免周期开始时的过期快照把既有组织/在产 unit 正在使用的份额（含 ESTATE 土地）当 "闲置可租"。
   */
  private static List<Target> buildTargets(
      EconomyDayView base,
      HouseholdId source,
      HouseholdEconomy sourceHouseholdEconomy,
      HouseholdMode current,
      long currentPerLabor,
      List<PrimaryModeRanking.Excluded> excluded,
      List<HexCoord> hexes,
      List<ProductionMode> modes,
      Map<ProductionModeId, List<ProductionRole>> producingPositions,
      Map<HouseholdId, HouseholdMode> modeByHousehold,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares,
      Set<AssetShareId> claimedByEnterprises,
      Map<ProductionUnitId, ProductionRules> relations,
      Map<MarketZoneId, MarketTable> markets,
      AccountSession accounts,
      MarketDemandBook.Book demandBook,
      MarketTopology topology,
      long day) {
    List<Target> targets = new ArrayList<>();
    for (HexCoord hex : hexes) {
      int distance = sourceHouseholdEconomy.view().hex().distanceTo(hex);
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
        List<ProductionRole> positions = producingPositions.get(mode.id());
        if (positions == null || positions.isEmpty()) {
          continue;
        }
        // 允许条件：该 hex 有产业模板；merchant 额外允许"有商号运力"（无 trade 模板仍会在 prospect 里具名失败）。
        boolean hasIndustry = hasIndustryTemplate(base, hex);
        boolean merchantCapacity =
            DefaultProductionModes.MERCHANT.equals(mode.id()) && hasMerchantCapacityAt(base, hex);
        if (!hasIndustry && !merchantCapacity) {
          continue;
        }
        ExpectedProfitBook.Prospect prospect =
            ExpectedProfitBook.prospect(
                base,
                source,
                mode.id(),
                hex,
                null,
                marketOf(markets, topology, hex),
                demandBook,
                assetShares,
                claimedByEnterprises,
                accounts,
                units,
                relations,
                topology,
                day);
        if (!prospect.feasible() || prospect.feasibleScale() <= 0L) {
          continue; // 不可行/规模 0：不凭空造目标（理由在 prospect 里具名）
        }
        // ★★ M-D：排序输入在**跑商候选**上的门槛（Q-18 库存 = tool 不够一趟；Q-19 议价权占比‰ = 0）。
        //   不成立 ⇒ 该候选不进排序表、也不成目标（缺工具的"预期收益" = 0，§12 H-D/H-5 在决策层的同一条门槛）；
        //   被挡下的行**具名**进排序表的 excluded（DEBUG 打印，不静默）。
        if (DefaultProductionModes.MERCHANT.equals(mode.id())) {
          String gate =
              PrimaryModeRanking.merchantGateReason(base, source, hex, accounts.householdGoods());
          if (gate != null) {
            excluded.add(new PrimaryModeRanking.Excluded(mode.id(), hex, gate));
            continue;
          }
        }
        long weight =
            Math.max(0L, Math.subtractExact(prospect.netPerLaborScaled(), currentPerLabor));
        HouseholdId existing =
            bestExistingTarget(source, hex, mode, modeByHousehold, positions, householdEconomies);
        // ★★★ W2-fix7 / D1①（控制方裁定 v3.10 §7）：**目标生产方式的实物腿商品拿不到 ⇒ 该户不迁入这个 mode**。
        //   判据（详见 inKindLegUnavailableReason）：目标产业的产出表里没有这个商品 **且** 该户没有可用库存
        //   **且** 目标格的市场也不交易它 ⇒ 迁过去只会得到一条永远付不出的租腿（改前是 day 540 的 E14 400，
        //   世界当场卡死）。★ 只判**新建**目标：并入既有户不生成新关系（既有关系早就在跑）。
        //   ★ 被挡下的目标进排序表的 excluded（不静默）+ 一条具名 INFO。
        if (existing == null) {
          String refusal =
              inKindLegUnavailableReason(base, source, mode.id(), hex, accounts, markets, topology);
          if (refusal != null) {
            excluded.add(new PrimaryModeRanking.Excluded(mode.id(), hex, refusal));
            if (weight > 0L) {
              // ★ 只在"它本来是活候选"时报 INFO（weight = 0 的目标本来就不会被选中，报它只是噪声）。
              EventLog.channel(EconomyLog.migration())
                  .info(
                      LogEvent.of(
                          "MIGRATION_IN_KIND_LEG_UNAVAILABLE_REFUSED",
                          EconomyLogSource.ECONOMY_MIGRATION,
                          "day",
                          day,
                          "household",
                          source.value(),
                          "targetHex",
                          hex.q() + "," + hex.r(),
                          "targetMode",
                          mode.id().value(),
                          "reason",
                          refusal));
            }
            continue;
          }
        }
        long room =
            existing == null
                ? 0L
                : Math.max(
                    0L, MAX_HOUSEHOLD_POPULATION - householdEconomies.get(existing).population());
        targets.add(
            new Target(
                hex,
                mode.id(),
                existing,
                weight,
                distance,
                true,
                room,
                canonicalKey(hex, mode.id()),
                prospect.positionId(),
                prospect.netPerLaborScaled(),
                prospect.reason()));
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

  /**
   * ★★★ <b>W2-fix7 / D1①（控制方裁定 v3.10 §7 第 ① 条）：一个"新建目标"能不能迁 —— 实物腿商品的可得性判据</b>。
   *
   * <pre>
   * 目标产业 I   = industriesForMode(base, hex, mode) 的第一个（与 ExpectedProfitBook.resolveIndustry 同源）
   * 模板制度 R   = ModeMigrationSettlement.migrationTemplateRegime(mode, I, base)（唯一映射点，不另写一份）
   * 实物腿商品 C = RegimeRelations.inKindCommodities(R)（模板里所有 commodity 非空的腿，去重保序）
   * 逐 c ∈ C（只要有一条**拿不到**就拒这个目标）：
   *   ① c ∈ I.outputPerUnit()                ⇒ 拿得到（本 unit 自己就产它）
   *   ② 该户（承担这条租腿的那个家户）手头有 c ⇒ 拿得到（家户库存）
   *   ③ 目标格所属市场的价目表里有 c 这一行    ⇒ 拿得到（能买；含 0 价 = 免费交易，债务式清算下"没钱也能买"）
   *   三者都不成立 ⇒ 返回具名理由（调用方：不迁入这个 mode、进 excluded、INFO 一条），世界继续跑
   * </pre>
   *
   * <p>★★ <b>"该户"= 被决策的这个家户（迁移源户）</b>：判据读的是它手头的库存（{@code accounts.householdGoods()}）。 ★
   * <b>一处如实记的边界</b>：本仓的迁移 ①–⑥ 只搬人/钱/债/资产/劳动配额，<b>不搬实物库存</b>（既有事实，本批不改）⇒
   * 源户"有库存"放行的迁移，在**新建**目标户那一侧头一轮仍然可能欠租 —— 那一轮由三级支付链的 ③ 具名缺口 + 债权 资本化承接（不是死结，也不是静默 0）。★
   * 并入既有户不走本判据（{@code existing != null} 时调用方直接跳过）： 那种 move <b>不生成新关系</b>，既有关系的合法性早由 E14 把守。
   *
   * <p>★★ <b>为什么"市场有这一行"就等于可得</b>：S-6/§2.1 的口径是"表里有这一行（含 0）= 可交易；没有这一行 = 不交易"， 且债务式清算下"没钱也能买（记债）"⇒
   * 市场交易该商品 = 这户有拿到它的通道。★ 反向（市场不交易、产出表也没有、 自己也没库存）才是真死路 —— 那正是要挡的形态。
   *
   * <p>★ <b>为什么按 mode→模板→商品，而不是按"已生成的关系"</b>：决策发生在关系生成之前；商品集合只由 {@link RegimeRelations}
   * 的表决定（受方/格/居住类型不改变商品）⇒ 模板就是权威。
   *
   * @return 具名拒绝理由；null = 可以迁
   */
  private static String inKindLegUnavailableReason(
      EconomyDayView base,
      HouseholdId source,
      ProductionModeId mode,
      HexCoord hex,
      AccountSession accounts,
      Map<MarketZoneId, MarketTable> markets,
      MarketTopology topology) {
    IndustryId industryId = null;
    Industry industry = null;
    for (IndustryId candidate : industriesForMode(base, hex, mode)) {
      Industry resolved = base.industries().get(candidate);
      if (resolved != null) {
        industryId = candidate;
        industry = resolved;
        break;
      }
    }
    if (industryId == null || industry == null) {
      return null; // 没有产业模板：本方法不判（该目标的可行性由 prospect / merchant 门槛具名把关）
    }
    Optional<RegimeId> regime =
        ModeMigrationSettlement.migrationTemplateRegime(mode, industryId, base);
    if (regime.isEmpty()) {
      return null; // 模板制度未登记（如 merchant）⇒ 关系走具名回退空规则，没有实物腿可判
    }
    return inKindLegUnavailableReasonOf(
        industryId,
        industry,
        RegimeRelations.inKindCommodities(regime.get()),
        accounts.householdGoods().getOrDefault(source, Map.of()),
        marketOf(markets, topology, hex),
        regime.get());
  }

  /**
   * ★★ <b>{@link #inKindLegUnavailableReason} 的判据本体（包内可见 ⇒ 可被逐值探针直接钉住）</b>： 三条"拿得到"的通道逐商品判，
   * 只要有一条商品三条都不成立 ⇒ 返回具名理由。
   *
   * @param industry 目标产业（产出表 = 唯一的"自己能产"权威）
   * @param inKindCommodities 目标模板的实物腿商品（{@link RegimeRelations#inKindCommodities}）
   * @param householdStock 该户手头库存（逐商品；缺键 = 0）
   * @param market 目标格所属市场的价目表（null = 该格没有市场 ⇒ 市场那一档不成立）
   * @return 具名拒绝理由；null = 可以迁
   */
  static String inKindLegUnavailableReasonOf(
      IndustryId industryId,
      Industry industry,
      List<CommodityId> inKindCommodities,
      Map<CommodityId, Long> householdStock,
      MarketTable market,
      RegimeId regime) {
    StringBuilder refused = new StringBuilder();
    for (CommodityId commodity : inKindCommodities) {
      if (industry.outputPerUnit().containsKey(commodity)) {
        continue; // ① 本产业能产
      }
      if (householdStock.getOrDefault(commodity, 0L) > 0L) {
        continue; // ② 该户手头有库存
      }
      if (market != null && market.hasPrice(commodity)) {
        continue; // ③ 目标格的市场交易它（含 0 价 = 免费交易）
      }
      if (refused.length() > 0) {
        refused.append('|');
      }
      refused
          .append(commodity.value())
          .append("(industry=")
          .append(industryId.value())
          .append(" outputs=")
          .append(industry.outputPerUnit().keySet())
          .append(",stock=0,market=")
          .append(market == null ? "none" : market.hasPrice(commodity))
          .append(')');
    }
    if (refused.length() == 0) {
      return null;
    }
    return "in-kind-leg-unavailable:" + refused + ":regime=" + regime.value();
  }

  /** A 规则专用：DISPLACED 目标（已有流民户或同一/邻格新建；不要求产业/资产）。 */
  private static List<Target> buildDisplacedTargets(
      HouseholdId source,
      HouseholdMode current,
      List<HexCoord> hexes,
      List<ProductionMode> modes,
      Map<ProductionModeId, List<ProductionRole>> producingPositions,
      Map<HouseholdId, HouseholdMode> modeByHousehold,
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
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
      List<ProductionRole> positions = producingPositions.get(displaced.id());
      if (positions == null || positions.isEmpty()) {
        continue;
      }
      HouseholdId existing =
          bestExistingTarget(
              source, hex, displaced, modeByHousehold, positions, householdEconomies);
      long room =
          existing == null
              ? 0L
              : Math.max(
                  0L, MAX_HOUSEHOLD_POPULATION - householdEconomies.get(existing).population());
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
                canonicalKey(hex, displaced.id()),
                null,
                0L,
                "DISPLACED"));
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
                canonicalKey(hex, displaced.id()),
                null,
                0L,
                "DISPLACED"));
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
   * <p>★ 新建目标的资产可行性在这里用"本次实际要建的人口"复核（不足 ⇒ 该目标的新建容量记 0）。溢出部分留在源户， 不丢人、也不凭空造承载。★★ P10.6：预留账本由调用方（同一
   * plan 顶层）传入，两遍循环共用、不重置。
   */
  private static List<MoveDraft> allocate(
      HouseholdId source,
      long sourcePopulation,
      long moveable,
      long speedPerMille,
      String reason,
      List<Target> targets,
      EconomyDayView base,
      HouseholdEconomy sourceHouseholdEconomy,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByEnterprises) {
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
              sourceHouseholdEconomy,
              assetShares,
              drafts,
              newOrdinal,
              reservedIdle,
              claimedByEnterprises);
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
              sourceHouseholdEconomy,
              assetShares,
              drafts,
              newOrdinal,
              reservedIdle,
              claimedByEnterprises);
      remaining -= placed;
    }
    return drafts;
  }

  /**
   * 往一个目标里放人口（已有户剩余容量 → 该 (hex, mode) 至多新建一户）；返回实际放入人口。
   *
   * <p>★★ P10.6：新建部分的"资产可行性"是<b>检查并预留</b>（{@code reserveIdleAssetsFor}）；预留成功才产出 MoveDraft， 账本由同一
   * {@code plan()} 的所有源户/所有目标共享。
   */
  private static long placeInto(
      Target target,
      long allow,
      HouseholdId source,
      long speedPerMille,
      String reason,
      EconomyDayView base,
      HouseholdEconomy sourceHouseholdEconomy,
      Map<AssetShareId, OwnershipStake> assetShares,
      List<MoveDraft> drafts,
      int[] newOrdinal,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByEnterprises) {
    long placed = 0L;
    if (allow <= 0L) {
      return 0L;
    }
    if (target.existing != null && target.existingRoom > 0L) {
      long take = Math.min(allow, target.existingRoom);
      drafts.add(
          new MoveDraft(
              target.existing,
              target.hex,
              target.mode,
              speedPerMille,
              take,
              reason,
              target.positionId));
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
            sourceHouseholdEconomy,
            assetShares,
            reservedIdle,
            claimedByEnterprises);
    long take = Math.min(want, room);
    if (take > 0L) {
      drafts.add(
          new MoveDraft(
              newHouseholdId(source, target.hex, target.mode, newOrdinal[0]++),
              target.hex,
              target.mode,
              speedPerMille,
              take,
              reason,
              target.positionId));
      placed += take;
    }
    return placed;
  }

  /**
   * 新建户可承载人口：由本次 take 的劳动反推规模，再看该 hex 的产业模板里有没有"闲置/可租赁资产够且预留成功"的； 第一个预留成功的产业即返回
   * take（预留已计入共享账本）。没有产业可预留 ⇒ 0。
   */
  private static long perMoveNewRoom(
      HexCoord hex,
      ProductionModeId mode,
      long take,
      EconomyDayView base,
      HouseholdEconomy sourceHouseholdEconomy,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByEnterprises) {
    if (DefaultProductionModes.DISPLACED.equals(mode)) {
      return take;
    }
    // 以源户的"劳动/人口"比例折算这批人带来的劳动（与执行期 laborTake 同源；不按人口 1:1 猜劳动）。
    long laborEstimate =
        sourceHouseholdEconomy.population() <= 0L
            ? take
            : Math.multiplyExact(sourceHouseholdEconomy.laborMilli(), take)
                / sourceHouseholdEconomy.population();
    // ★ 与执行期（ModeMigrationSettlement.createEnterpriseAndProcess）同一份"该 mode 能用哪些产业模板"：
    //   按 mode 的默认 regime 优先；merchant 找不到 trade 模板 ⇒ 空（绝不把农场模板当商号）。
    for (IndustryId industryId : industriesForMode(base, hex, mode)) {
      Industry industry = base.industries().get(industryId);
      if (industry != null
          && reserveIdleAssetsFor(
              industry, laborEstimate, assetShares, reservedIdle, claimedByEnterprises)) {
        return take;
      }
    }
    return 0L;
  }

  /**
   * "检查并预留"：按 laborMilli 推出的规模算每个 required 的 need，闲置资产够（Σ 闲置 − 已预留 ≥ need）则把 need 累加进共享 {@code
   * reservedIdle} 并返回 true；任一 required 不足 ⇒ false，<b>且不做任何部分预留</b>。
   *
   * <p>★ 口径与执行期 {@code ModeMigrationSettlement.planAssetMoves} 对齐：同一份"闲置"定义 （{@link
   * #isIdleShare}：{@code quantity > 0 && operator == owner && id ∉ claimedByEnterprises}）与同一个
   * {@code scale} 公式；按 {@link IndustryId} 预留 ⇒ 同一产业的多个 mode 共享同一个闲置分母，不会各看各的快照。 执行侧逐笔拆完 share
   * 后，各产业/种类的剩余量与本账本一致。
   */
  private static boolean reserveIdleAssetsFor(
      Industry industry,
      long laborMilli,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<IndustryId, Map<AssetKind, Long>> reservedIdle,
      Set<AssetShareId> claimedByEnterprises) {
    long scale =
        industry.recipe().laborPerUnit() <= 0L
            ? 1L
            : Math.max(1L, laborMilli / industry.recipe().laborPerUnit());
    Map<AssetKind, Long> reservedForIndustry = reservedIdle.getOrDefault(industry.id(), Map.of());
    Map<AssetKind, Long> needs = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> required : industry.recipe().capacityPerUnit().entrySet()) {
      long need = Math.multiplyExact(scale, required.getValue());
      long available = 0L;
      for (OwnershipStake share : assetShares.values()) {
        if (!share.industry().equals(industry.id())
            || share.asset() != required.getKey()
            || !isIdleShare(share, claimedByEnterprises)) {
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
      List<ProductionRole> producingPositions,
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    Set<ClassPositionId> positionIds = new LinkedHashSet<>();
    for (ProductionRole position : producingPositions) {
      positionIds.add(position.id());
    }
    HouseholdId best = null;
    long bestRoom = 0L;
    for (HouseholdId household : sortedHouseholds(householdEconomies)) {
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
      long room =
          Math.max(0L, MAX_HOUSEHOLD_POPULATION - householdEconomies.get(household).population());
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

  // ── 预期收益（A 规则，D-024：与候选评估共用同一个计算器）──────────────────────────────────

  /**
   * ★★ <b>D-024</b>：A 规则与"能否维持生产"改读 {@link ExpectedProfitBook.Prospect} —— 不再保留第二套 {@code output ×
   * 价 − 投入} 的旧公式（那会与候选门槛漂开）。{@code nextCycleInputNeed} = 同一个 prospect 的 {@code
   * inputCostMilli}；{@code canSustainProduction} = prospect 可行且规模 ≥ 1。
   */
  private static ExpectedProfit expectedProfit(
      ExpectedProfitBook.Prospect currentProspect, long liquidityMilli) {
    return new ExpectedProfit(
        currentProspect.netMilli(),
        currentProspect.laborNeedMilli(),
        currentProspect.inputCostMilli(),
        liquidityMilli,
        currentProspect.feasible() && currentProspect.feasibleScale() > 0L,
        currentProspect.reason());
  }

  /**
   * 当前户实际在产的产业模板（组织/unit 优先；找不到 ⇒ null，让 {@link ExpectedProfitBook} 按 mode 默认 regime 挑）。
   * 这样"当前户读数"用真实在产的配方，而不是"该格第一个模板"。
   */
  private static Industry currentIndustryOf(
      EconomyDayView base,
      HouseholdId household,
      HouseholdMode current,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    ActorRef actor = HouseholdActors.of(household);
    for (ProductionOrganizationId organizationId : sortedEnterpriseIds(enterprises)) {
      ProductionEnterprise enterprise = enterprises.get(organizationId);
      if (enterprise == null
          || !enterprise.organizer().equals(actor)
          || !current.modeId.equals(enterprise.modeId())
          || enterprise.unitId().isEmpty()) {
        continue;
      }
      ProductionProcess unit = units.get(enterprise.unitId().get());
      if (unit != null) {
        Industry industry = base.industries().get(unit.industry());
        if (industry != null) {
          return industry;
        }
      }
    }
    // 兜底：本户名下的 unit（旧档/手工夹具可能没有对应组织行），按 unit id 升序取第一个同格模板。
    List<ProductionUnitId> unitIds = new ArrayList<>(units.keySet());
    unitIds.sort(Comparator.comparing(ProductionUnitId::value));
    for (ProductionUnitId unitId : unitIds) {
      ProductionProcess unit = units.get(unitId);
      if (unit == null || !unit.operator().equals(actor)) {
        continue;
      }
      HexCoord unitHex = unitHexOrNull(unit);
      if (unitHex != null && unitHex.equals(current.hex)) {
        Industry industry = base.industries().get(unit.industry());
        if (industry != null) {
          return industry;
        }
      }
    }
    return null;
  }

  /**
   * 流动性（毫计价货币）= 本格计价币现金 + 库存按参考价折算（与旧 A 规则同一口径）。
   *
   * <p>★★ <b>M-D：算式的唯一拼写点搬进 {@link PrimaryModeRanking#inventoryMilli}</b>（"库存"是排序表与 A 规则共用的
   * 同一个输入，Q-18）——本方法只做委托，**不保留第二份算式**（含 2026-10-09 的溢出饱和修复）。
   */
  private static long liquidityMilli(
      AccountSession accounts,
      Map<MarketZoneId, MarketTable> markets,
      MarketTopology topology,
      HouseholdId household,
      HexCoord hex) {
    return PrimaryModeRanking.inventoryMilli(accounts, markets, topology, household, hex);
  }

  /**
   * ★★ <b>W4：该户的"债务/资产"滑落速度（‰/周期）</b> —— 与 {@link ClassSlideSettlement} 同一条算式、同一份快照。 快照里没有该户（人口 0 /
   * 无行）⇒ 0（= 不加速，退回基线常量）。
   */
  private static long slideSpeedPerMille(SettlementOrderBook ordering, HouseholdId household) {
    Long assets = ordering.householdAssetsOf(household);
    Long debt = ordering.householdDebtOf(household);
    if (assets == null || debt == null) {
      return 0L;
    }
    return ClassSlideSettlement.speedPerMille(debt, assets);
  }

  /** 本格价表：优先本格市场；无则退到该格所在区的集散节点市场（区内同价）；都没有 ⇒ null。 */
  private static MarketTable marketOf(
      Map<MarketZoneId, MarketTable> markets, MarketTopology topology, HexCoord hex) {
    return PrimaryModeRanking.marketOf(markets, topology, hex);
  }

  /**
   * ★★ <b>M-A1：merchant 允许条件用 —— 该格有没有"选了跑商且有运力"的家户</b>（运力 0 不算）。
   *
   * <p>运力是派生量（{@link MerchantCapacity}）：成员判据 = {@code classStandings} 的 {@code
   * effectivePositionIds()} 含 merchant 生产方式的位置；本查询走纯状态口径（工具维取 0 = 运力下界，见 {@link
   * MerchantCapacityPool#hasCapacityAt(EconomyData, HexCoord)}）。旧"查商号行"的实现已随 {@code merchantFirms}
   * 退役。
   */
  private static boolean hasMerchantCapacityAt(EconomyDayView base, HexCoord hex) {
    return MerchantCapacityPool.hasCapacityAt(base, hex);
  }

  /**
   * 需求簿视界：<b>世界现有 unit 所属产业 cycleDays 的最大者</b>（多个不同值时取最大并在此具名说明；本轮真实世界 所有产业都是 120）。没有 unit（或 unit
   * 模板缺失）时退回"全部产业的最大者"，再没有 ⇒ 120。
   */
  private static long demandHorizonDays(
      EconomyDayView base, Map<ProductionUnitId, ProductionProcess> units) {
    long horizon = 0L;
    for (ProductionProcess unit : units.values()) {
      Industry industry = base.industries().get(unit.industry());
      if (industry != null) {
        horizon = Math.max(horizon, industry.cycleDays());
      }
    }
    if (horizon > 0L) {
      return horizon;
    }
    for (Industry industry : base.industries().values()) {
      if (industry != null) {
        horizon = Math.max(horizon, industry.cycleDays());
      }
    }
    return horizon <= 0L ? DEFAULT_DEMAND_HORIZON_DAYS : horizon;
  }

  /**
   * ★★ <b>该 mode 在该格能用哪些产业模板</b>（策略/执行/预期利润三处共用，避免各写一份 mode→regime 映射）：
   *
   * <ol>
   *   <li>按 mode 的默认 regime（{@link RegimeOperators#defaultRegimeForMode}）筛；有匹配 ⇒ 只回匹配的（按 id 升序）；
   *   <li>没有匹配：merchant ⇒ <b>空</b>（绝不把农场模板当商号）；其余 mode/未登记制度 ⇒ 该格全部模板（保持既有回退）。
   * </ol>
   */
  static List<IndustryId> industriesForMode(
      EconomyDayView base, HexCoord hex, ProductionModeId modeId) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(modeId, "modeId");
    List<IndustryId> candidates =
        new ArrayList<>(IndustryHexKeys.at(base.industries(), hex.q(), hex.r()));
    candidates.sort(Comparator.comparing(IndustryId::value));
    Optional<String> wanted = RegimeOperators.defaultRegimeForMode(modeId);
    if (wanted.isEmpty()) {
      return List.copyOf(candidates);
    }
    List<IndustryId> matching = new ArrayList<>();
    for (IndustryId industryId : candidates) {
      Industry industry = base.industries().get(industryId);
      if (industry != null && wanted.get().equals(industry.regime().value())) {
        matching.add(industryId);
      }
    }
    if (!matching.isEmpty()) {
      return List.copyOf(matching);
    }
    if (DefaultProductionModes.MERCHANT.equals(modeId)) {
      return List.of();
    }
    return List.copyOf(candidates);
  }

  private static HexCoord unitHexOrNull(ProductionProcess unit) {
    return IndustryHexKeys.hexKeyOf(unit.industry()).map(HexCoord::parse).orElse(null);
  }

  private static List<ProductionOrganizationId> sortedEnterpriseIds(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    List<ProductionOrganizationId> ids = new ArrayList<>(enterprises.keySet());
    ids.sort(Comparator.comparing(ProductionOrganizationId::value));
    return ids;
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
      HouseholdMode current,
      AccountSession accounts,
      Map<MarketZoneId, MarketTable> markets,
      MarketZone zone) {
    // ★ W1：本格价表 = 本格所属市场的那一张（价表每区一张；区内任何格同一张）。
    MarketTable market = zone == null ? null : markets.get(zone.zoneId());
    if (market != null) {
      return market.numeraire();
    }
    Map<CurrencyId, Long> money =
        accounts.householdMoney().getOrDefault(current.household, Map.of());
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
                : Math.multiplyExact(remainingPrincipal[contractIndex], populations[moveIndex])
                    / denom;
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

  private static boolean hasIndustryTemplate(EconomyDayView base, HexCoord hex) {
    return !IndustryHexKeys.at(base.industries(), hex.q(), hex.r()).isEmpty();
  }

  private static Map<ProductionModeId, List<ProductionRole>> producingPositionsByMode(
      EconomyDayView base, List<ProductionMode> modes) {
    Map<ProductionModeId, List<ProductionRole>> result = new LinkedHashMap<>();
    for (ProductionMode mode : modes) {
      ClassStructure structure = base.classStructures().get(mode.classStructureId());
      if (structure == null) {
        result.put(mode.id(), List.of());
        continue;
      }
      List<ProductionRole> positions = new ArrayList<>();
      for (ProductionRole position : structure.positions().values()) {
        if (shouldProduce(position)) {
          positions.add(position);
        }
      }
      positions.sort(Comparator.comparing(position -> position.id().value()));
      result.put(mode.id(), positions);
    }
    return result;
  }

  private static boolean shouldProduce(ProductionRole position) {
    return position.laborRole() != ProductionRole.LaborRole.NONE
        && position.surplusRole() != ProductionRole.SurplusRole.DEPENDENT;
  }

  // ── 闲置资产判据的唯一拼写点（policy / settlement 共用；纯谓词，不新增持久组件）─────────────

  /**
   * ★★ <b>P10.7 闲置/可租赁资产判据的唯一拼写点</b>（计划期 {@code reserveIdleAssetsFor} 与执行期 {@code
   * ModeMigrationSettlement.planAssetMoves} 共用；两侧不得各拼一遍）：
   *
   * <ol>
   *   <li>{@code quantity() > 0}；
   *   <li>{@code operator().equals(owner())}（自有自营；{@code operator != owner} 已被人实际使用）；
   *   <li>{@code id()} <b>不在</b> claimed 集合里 —— claimed = 既有 {@code
   *       ProductionEnterprise.assetSources} 的并集 ∪ 在产 unit 占用的份额（同 industry、同 operator、{@code
   *       quantity>0}，见 {@link #claimedOwnershipStakes(Map, Map, Map)}）。既有商号/组织/在产 unit
   *       正在使用的份额不是闲置： 执行期若把它整条拆走，源份额行会被删除，组织引用会指向不存在的份额 id，{@code EconomyData} 当即以
   *       「生产组织使用的资产份额必须已存在」fail-closed；在产 unit 被抽走资产则会让产能凭空消失。
   * </ol>
   *
   * <p>★ 结论一句话：<b>只有 {@code owner==operator} 且不被任何组织/在产 unit 使用的份额才算闲置</b>（与 P10.7 的原始 意图一致；"unit
   * 占用"是 D-024 修复 1b 新增的显式一格）。
   */
  static boolean isIdleShare(OwnershipStake share, Set<AssetShareId> claimedByEnterprises) {
    Objects.requireNonNull(share, "share");
    Objects.requireNonNull(claimedByEnterprises, "claimedByOrganizations");
    return share.quantity() > 0L
        && share.operator().equals(share.owner())
        && !claimedByEnterprises.contains(share.id());
  }

  /**
   * ★★ <b>兼容重载：只算"被既有组织引用"这一格</b>，等价于 {@code claimedOwnershipStakes(enterprises, Map.of(),
   * Map.of())}。测试/旧调用点可继续用；生产路径 （{@link #plan} 与 {@code
   * ModeMigrationSettlement.createEnterpriseAndProcess}）一律走三参版本，把当天工作副本 {@code units}/{@code
   * assetShares} 一并传入。两参/三参共用下面同一个谓词，不存在第二套拼写。★ Z7d-2：三参版本公开给 app 组合根的逃亡去向排序复用（不复制谓词）。
   */
  static Set<AssetShareId> claimedOwnershipStakes(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    return claimedOwnershipStakes(enterprises, Map.of(), Map.of());
  }

  /**
   * ★★ P10.7 / P10.8 / D-024 修复 1b：<b>"已被占用、不是闲置"的资产份额 id 并集</b>（只读派生，不新增持久组件）。
   *
   * <p>两格显式占用：
   *
   * <ol>
   *   <li>既有 {@link ProductionEnterprise#assetSources()} 引用的份额（组织正在用）；
   *   <li>传入的 {@code units} 里每个在产 unit 所占的份额：满足 {@link #usedByUnit(OwnershipStake,
   *       ProductionProcess)} —— 同 {@code industry}、同 {@code operator}、{@code quantity>0}。seeder
   *       直接以 {@code RegimeOperators.defaultOperator(...)}（ESTATE 等）建立的 {@code
   *       farm/weave/craft/trade} 主 unit 没有对应的 {@code ProductionEnterprise}，只靠第 ① 格会漏掉它们，让城市户把
   *       ESTATE 的土地/作坊/船畜当 "闲置可租"抢占（D-024 第二轮根因 A）。
   * </ol>
   *
   * <p>计划期在 {@link #plan} 顶层从当天工作副本构建一次并透传；执行期在 {@link
   * ModeMigrationSettlement#createEnterpriseAndProcess} 从同一份当天工作副本再构建一次。集合只用于 {@link #isIdleShare}
   * 的包含判断，不参与排序，故迭代序不影响计划确定性。
   */
  public static Set<AssetShareId> claimedOwnershipStakes(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares) {
    Objects.requireNonNull(enterprises, "organizations");
    Objects.requireNonNull(units, "units");
    Objects.requireNonNull(assetShares, "assetShares");
    Set<AssetShareId> claimed = new LinkedHashSet<>();
    for (ProductionEnterprise enterprise : enterprises.values()) {
      if (enterprise != null) {
        claimed.addAll(enterprise.assetSources());
      }
    }
    for (ProductionProcess unit : units.values()) {
      if (unit == null) {
        continue;
      }
      for (OwnershipStake share : assetShares.values()) {
        if (usedByUnit(share, unit)) {
          claimed.add(share.id());
        }
      }
    }
    return claimed;
  }

  /**
   * "在产 unit 占用份额"的唯一谓词：{@code share.industry == unit.industry ∧ share.operator == unit.operator ∧
   * share.quantity > 0}。产业 id 自带格键 ⇒ 不需要另判 hex；不判 {@code modeKey}（同产业下一条在产 unit 就是一种 占用，mode
   * 只决定生产方式，不决定实物占用）。{@link #claimedOwnershipStakes(Map, Map, Map)} 的两格都经本方法/同一 循环拼装，别处不得再写一份。
   */
  private static boolean usedByUnit(OwnershipStake share, ProductionProcess unit) {
    Objects.requireNonNull(share, "share");
    Objects.requireNonNull(unit, "unit");
    return share.quantity() > 0L
        && share.industry().equals(unit.industry())
        && share.operator().equals(unit.operator());
  }

  private static List<ProductionMode> sortedModes(EconomyDayView base) {
    List<ProductionMode> modes = new ArrayList<>(base.modes().values());
    modes.sort(Comparator.comparing(mode -> mode.id().value()));
    return modes;
  }

  private static List<HouseholdId> sortedHouseholds(
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    List<HouseholdId> keys = new ArrayList<>(householdEconomies.keySet());
    keys.sort(Comparator.comparing(HouseholdId::value));
    return keys;
  }

  private static List<HexCoord> globalHexes(
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<ProductionUnitId, ProductionProcess> units,
      Map<MarketZoneId, MarketTable> markets,
      Map<MarketZoneId, MarketZone> marketZones) {
    TreeSet<HexCoord> hexes =
        new TreeSet<>(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    for (HouseholdEconomy householdEconomy : householdEconomies.values()) {
      hexes.add(householdEconomy.view().hex());
    }
    hexes.addAll(
        MarketTables.allMarketHexes(markets, marketZones)); // ★ W1：有市场的格（持久 = 有价表的区成员格；派生 = 价表成员格）
    for (ProductionProcess unit : units.values()) {
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

  /**
   * 目标候选（existingRoom 为已有户剩余容量；newFeasible 为同 (hex, mode) 是否能新建）。
   *
   * <p>★★ <b>M-D 追加 {@code positionId} / {@code yieldPerLaborScaled}</b>：目标那一项的**位置 id**（= 该 (hex,
   * mode) 的 {@code ExpectedProfitBook.Prospect.positionId()}，排序表第 1
   * 项的落点候选）与该行的**收益率读数**（同尺，进排序表与日志）。 ★ {@code positionId} 可 {@code null}（DISPLACED 目标等没有 prospect
   * 的场合）。
   */
  private record Target(
      HexCoord hex,
      ProductionModeId mode,
      HouseholdId existing,
      long weight,
      int distance,
      boolean newFeasible,
      long existingRoom,
      String canonical,
      ClassPositionId positionId,
      long yieldPerLaborScaled,
      String prospectReason) {
    Target {
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(canonical, "canonical");
    }
  }

  /**
   * 施工坞（人口切分与目标确认完成后才冻成 {@link MigrationMove}）。
   *
   * <p>★ M-D：{@code primaryPositionId} = 该目标那一项在**生产方式排序表**里的位置（= {@code current} 的落点）； 可 {@code
   * null}（DISPLACED 等没有 prospect 的目标）。
   */
  private static final class MoveDraft {
    final HouseholdId target;
    final HexCoord targetHex;
    final ProductionModeId targetMode;
    final long transferSpeedPerMille;
    final long population;
    final String reason;
    final ClassPositionId primaryPositionId;

    MoveDraft(
        HouseholdId target,
        HexCoord targetHex,
        ProductionModeId targetMode,
        long transferSpeedPerMille,
        long population,
        String reason,
        ClassPositionId primaryPositionId) {
      this.target = target;
      this.targetHex = targetHex;
      this.targetMode = targetMode;
      this.transferSpeedPerMille = transferSpeedPerMille;
      this.population = population;
      this.reason = reason;
      this.primaryPositionId = primaryPositionId;
    }
  }
}

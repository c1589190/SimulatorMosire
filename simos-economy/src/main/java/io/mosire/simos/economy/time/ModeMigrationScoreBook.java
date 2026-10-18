package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.ModeMigrationCandidateBook.allocate;
import static io.mosire.simos.economy.time.ModeMigrationCandidateBook.buildDisplacedTargets;
import static io.mosire.simos.economy.time.ModeMigrationCandidateBook.buildTargets;
import static io.mosire.simos.economy.time.ModeMigrationPolicy.A_RULE_TRANSFER_SPEED_PER_MILLE;
import static io.mosire.simos.economy.time.ModeMigrationPolicy.MIGRATION_SPEED_CAP_PER_MILLE;
import static io.mosire.simos.economy.time.ModeMigrationPolicy.PROFIT_GAP_SPEED_SLOPE_PER_MILLE;
import static io.mosire.simos.economy.time.ModeMigrationRules.industriesForMode;
import static io.mosire.simos.economy.time.ModeMigrationRules.marketOf;
import static io.mosire.simos.economy.time.ModeMigrationRules.sortedEnterpriseIds;
import static io.mosire.simos.economy.time.ModeMigrationRules.unitHexOrNull;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.time.ModeMigrationCandidateBook.CandidateTally;
import io.mosire.simos.economy.time.ModeMigrationCandidateBook.MoveDraft;
import io.mosire.simos.economy.time.ModeMigrationCandidateBook.Target;
import io.mosire.simos.economy.time.ModeMigrationPolicy.ExpectedProfit;
import io.mosire.simos.economy.time.ModeMigrationPolicy.MigrationMove;
import io.mosire.simos.economy.time.ModeMigrationRules.HouseholdMode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** P0.5-E1 机械拆分自 {@code ModeMigrationPolicy}；职责见类名与各方法注释。 */
final class ModeMigrationScoreBook {

  private ModeMigrationScoreBook() {}

  static List<MoveDraft> planForSource(
      EconomyDayView base,
      HouseholdId source,
      HouseholdEconomy sourceHouseholdEconomy,
      HouseholdMode current,
      List<HexCoord> hexes,
      List<ProductionModeDefinitionView> modes,
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
      CandidateTally tally,
      ProductionRuntime productionRuntime,
      AssetOccupancyBudget assetBudget) {
    // ★★ D-024：当前户读数改由同一个预期利润计算器现算（真实利润账退位为事后对账）。
    IndustryDefinitionView currentIndustry =
        currentIndustryOf(base, source, current, units, enterprises);
    ExpectedProfitBook.Prospect currentProspect =
        ExpectedProfitBook.prospect(
            base,
            source,
            current.modeId(),
            current.hex(),
            currentIndustry,
            marketOf(markets, topology, current.hex()),
            demandBook,
            assetShares,
            claimedByEnterprises,
            accounts,
            units,
            relations,
            topology,
            day,
            // ★★ §15.12 B（R1）：当前户的物理量（产出/所需劳动/所需投入）也走同一份试算 ——
            //   A 规则与"能否维持生产"从此看见熟练度/GM 覆盖/解锁条件。
            null,
            productionRuntime,
            assetBudget);
    long currentPerLabor = currentProspect.netPerLaborScaled();
    // ★★★ W9-fix（控制方裁定 v3.32 §7 第 1 条）：速度算式的**正参考基准**（分母下限）之一 ——
    //   **当前生产方式的"收益规模基准"**（1 单位规模劳动的毛收入 / 口粮成本，见 {@link #profitScaleBasisScaled}）。
    //   ★ 它绝不能由 `max(1, |收益_当前|)` 顶替：那正是 W9 首版"收益 ≈ 0 ⇒ 分母退化 1 ⇒ 打满 1000‰"的根因。
    long currentProfitBasisScaled = profitScaleBasisScaled(currentProspect);
    // ★★ M-D：被排序输入挡下的跑商候选（具名；进排序表的 excluded 与 DEBUG 日志）。
    List<PrimaryModeRanking.Excluded> excluded = new ArrayList<>();
    List<Target> targets =
        buildTargets(
            base,
            source,
            sourceHouseholdEconomy,
            current,
            currentPerLabor,
            currentProfitBasisScaled,
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
            day,
            tally,
            productionRuntime,
            assetBudget);
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
            currentProspect, liquidityMilli(accounts, markets, topology, source, current.hex()));
    boolean aRule =
        expected.expectedNetMilli() < 0L
            && expected.liquidityMilli() < expected.nextCycleInputNeedMilli();

    List<MoveDraft> drafts;
    if (aRule) {
      List<Target> higher = ranked.stream().filter(target -> target.weight() > 0L).toList();
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
      // ★★★ W9（2026-10-11，用户裁定 v3.30 §7 第 2 条）：**转业速度 = 预期收益差驱动**。
      //   「只要其他生产方式比目前生产方式预期收益更高，就产生转业速度，差越大越快」⇒
      //     ① 活候选 = weight > 0 **且** speed > 0（收益差 ≤ 0 ⇒ speed 0 ⇒ 该目标不是去处，直接不进候选）；
      //     ② 可迁人口 = 该户**最快的那一档速度**折算（max(1, ⌊人口 × 最快速度 ÷ 1000⌋)）——
      //        它是"这一轮最多能走多少人"的上界，落到哪个目标仍由 allocate 按 weight 比例分；
      //     ③ 每一笔 move 带**该目标自己的速度**（allocate 的 speedOverride = 0 ⇒ 用 Target.speedPerMille）：
      //        "差越大越快"因此是逐目标的，不是整户一个数。
      // ★★★ W9-fix（2026-10-11，控制方裁定 v3.32 §7 第 2 条）：**转业速率上限**（政策预设
      //   {@link #MIGRATION_SPEED_CAP_PER_MILLE}）—— 本户本轮可转人口再被"每市场轮 ≤ 人口 × 上限‰ ÷ 1000"夹一次。
      //   ★ 与 A 规则**分开**：A 规则两支（上面 aRule 的两支）显式传 1000‰ 且 moveable = 全户人口，不走这里。
      //   ★ 上限是**独立的人口闸**：它不改 {@code Target.speedPerMille}（"差越大越快"的读数保持逐值可读），
      //     只把"这一轮最多走多少人"夹住 —— 两者在 MIGRATION_MOVE 里两栏同时可读。
      long bestSpeedPerMille = 0L;
      for (Target target : ranked) {
        if (target.weight() > 0L) {
          bestSpeedPerMille = Math.max(bestSpeedPerMille, target.speedPerMille());
        }
      }
      long moveable =
          Math.min(
              moveablePopulation(sourceHouseholdEconomy.population(), bestSpeedPerMille),
              moveablePopulation(
                  sourceHouseholdEconomy.population(), MIGRATION_SPEED_CAP_PER_MILLE));
      List<Target> positive =
          ranked.stream()
              .filter(target -> target.weight() > 0L && target.speedPerMille() > 0L)
              .toList();
      drafts =
          positive.isEmpty() || moveable <= 0L
              ? List.of()
              : allocate(
                  source,
                  sourceHouseholdEconomy.population(),
                  moveable,
                  // ★ 0 = "用每个目标自己的收益差速度"（A 规则两支仍显式传 1000‰ 覆盖）。
                  0L,
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
  static PrimaryModeRanking.Table rankingTable(
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
        DefaultProductionModes.MERCHANT.equals(current.modeId())
            ? PrimaryModeRanking.merchantGateReason(
                base, source, current.hex(), accounts.householdGoods())
            : null;
    if (currentGate == null) {
      rows.add(
          new PrimaryModeRanking.Row(
              current.positionId(),
              current.modeId(),
              current.hex(),
              currentProspect.netPerLaborScaled(),
              bargainingPowerPerMille(base, source, current.modeId(), current.hex(), accounts),
              PrimaryModeRanking.inventoryMilli(accounts, markets, topology, source, current.hex()),
              currentProspect.reason()));
    } else {
      // ★ 当前主业就是跑商、但**工具不够一趟**（§12 H-D/H-5）：库存是排序输入之一 ⇒ 它进不了"第 1 项"
      //   （跑商在决策层不成立；它在市场轮的既有 M-C 门槛另有具名 tool-short）。★ 只影响**本表的读数**，
      //   A 规则读的 expectedNet 一字不改（冻结项 5）。
      excluded.add(new PrimaryModeRanking.Excluded(current.modeId(), current.hex(), currentGate));
    }
    for (Target target : targets) {
      if (target.positionId() == null) {
        continue; // 没有位置的目标（DISPLACED）：排序表只排"生产方式位置"，它不进表
      }
      rows.add(
          new PrimaryModeRanking.Row(
              target.positionId(),
              target.mode(),
              target.hex(),
              target.yieldPerLaborScaled(),
              bargainingPowerPerMille(base, source, target.mode(), target.hex(), accounts),
              PrimaryModeRanking.inventoryMilli(accounts, markets, topology, source, target.hex()),
              target.prospectReason()));
    }
    return PrimaryModeRanking.rank(source, rows, excluded);
  }

  /**
   * ★★ <b>排序输入①：市场议价权（占比‰）</b>—— 只有跑商行非零（议价权是**运力**概念；§11.4 G-1 的唯一口径， 经 {@link
   * MerchantCapacityPool#sharePerMilleAsProviderAt}）。
   */
  static long bargainingPowerPerMille(
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
  static List<Target> primaryFirst(List<Target> targets, PrimaryModeRanking.Table table) {
    PrimaryModeRanking.Row primary = table.primary();
    if (primary == null || targets.size() <= 1) {
      return targets;
    }
    Target first = null;
    List<Target> rest = new ArrayList<>(targets.size());
    for (Target target : targets) {
      if (first == null
          && target.mode().equals(primary.modeId())
          && target.hex().equals(primary.hex())) {
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
  static void maybeLogPrimaryChange(
      long day,
      HouseholdId source,
      HouseholdMode current,
      long currentYield,
      PrimaryModeRanking.Table table,
      List<MoveDraft> drafts) {
    PrimaryModeRanking.Row primary = table.primary();
    if (primary == null || drafts.isEmpty() || primary.modeId().equals(current.modeId())) {
      return;
    }
    // "从哪"的收益率取**该户当前项那一次的 prospect 读数**（与本方法无关的 A 规则同源），不依赖当前行是否在表里。
    PrimaryModeRanking.logPrimaryChange(
        day, source, current.positionId(), current.modeId(), primary, currentYield);
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
  static String inKindLegUnavailableReason(
      EconomyDayView base,
      HouseholdId source,
      ProductionModeId mode,
      HexCoord hex,
      AccountSession accounts,
      Map<MarketZoneId, MarketTable> markets,
      MarketTopology topology) {
    IndustryId industryId = null;
    IndustryDefinitionView industry = null;
    for (IndustryId candidate : industriesForMode(base, hex, mode)) {
      IndustryDefinitionView resolved = base.industries().get(candidate);
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
      IndustryDefinitionView industry,
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

  /**
   * ★★ <b>D-024</b>：A 规则与"能否维持生产"改读 {@link ExpectedProfitBook.Prospect} —— 不再保留第二套 {@code output ×
   * 价 − 投入} 的旧公式（那会与候选门槛漂开）。{@code nextCycleInputNeed} = 同一个 prospect 的 {@code
   * inputCostMilli}；{@code canSustainProduction} = prospect 可行且规模 ≥ 1。
   */
  static ExpectedProfit expectedProfit(
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
  static IndustryDefinitionView currentIndustryOf(
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
          || !current.modeId().equals(enterprise.modeId())
          || enterprise.unitId().isEmpty()) {
        continue;
      }
      ProductionProcess unit = units.get(enterprise.unitId().get());
      if (unit != null) {
        IndustryDefinitionView industry = base.industries().get(unit.industry());
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
      if (unitHex != null && unitHex.equals(current.hex())) {
        IndustryDefinitionView industry = base.industries().get(unit.industry());
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
  static long liquidityMilli(
      AccountSession accounts,
      Map<MarketZoneId, MarketTable> markets,
      MarketTopology topology,
      HouseholdId household,
      HexCoord hex) {
    return PrimaryModeRanking.inventoryMilli(accounts, markets, topology, household, hex);
  }

  /**
   * ★★★ <b>W9（2026-10-11，用户裁定 v3.30 §7 第 2 条）：转业速度的唯一算式 —— 预期收益差驱动</b>。
   *
   * <pre>
   * gap        = 收益_其他(每单位劳动净收益, 缩放整型) − 收益_当前(同尺)
   * gap ≤ 0    ⇒ 0     （"其他生产方式不比现在赚钱" ⇒ **不产生转业速度** = 不转业）
   * gap &gt; 0    ⇒ clamp(⌊SLOPE × gap ÷ 分母⌋, 1, 1000)
   *
   * 分母（**具名**，W9-fix 口径）= max(|收益_当前|, |收益_其他|, 收益规模基准)
   *   · |收益_当前| / |收益_其他| ⇒ 相对差两侧都算（"比现在多赚几成"），并保证 分母 ≥ |gap| ÷ 2
   *   · 收益规模基准 = 两侧生产方式"1 单位规模劳动的毛收入 / 口粮成本"取大（{@link #profitScaleBasisScaled}）
   *     ⇒ **正参考基准**：收益 ≈ 0 的户（流民/不可行户）不会拿到无穷大的相对差
   *   · 基准 = 0（两侧都没有规模/没有市场 ⇒ 夹具世界）⇒ 分母退化为 max(|收益_当前|, |收益_其他|)：
   *     它在 gap &gt; 0 时**恒 &gt; 0**（两者不会同时为 0），且速度 ≤ 2×SLOPE = 200‰ ⇒ 仍被
   *     {@link #MIGRATION_SPEED_CAP_PER_MILLE} 的人口闸夹住（不是 W9 首版的 1000‰ 爆炸）。
   * </pre>
   *
   * <p>★★★ <b>W9-fix 修的是什么（控制方裁定 v3.32 §7 第 1 条）</b>：W9 首版分母 = {@code max(1, |收益_当前|)} —— "收益_当前 ≈
   * 0"时它退化为 1，把"无收益"变成"无穷大相对差" ⇒ 打满 1000‰（真世界实测 sw 68/91、tp 62/93 笔， 单笔最多 199 人）⇒ 一轮搬空家户/整格 ⇒
   * three-powers **21 个零成交轮**（硬牙 {@code 零成交轮次 = 0} 被打破）。 现在分母**永远含一个正的收益规模基准**（或至少含 |收益_其他|），"收益
   * 0"不再等于"无穷大相对差"。
   *
   * <p>★ <b>量纲</b>：两侧都是 {@link ExpectedProfitBook.Prospect#netPerLaborScaled()}（每单位劳动的净收益， 缩放到整型）⇒
   * 比值无量纲，乘 SLOPE 得 ‰/轮。⇒ 速度与人口无关，人口只决定"这一轮最多走多少人" （{@link #moveablePopulation}）。
   *
   * @param otherNetPerLaborScaled 候选目标的每单位劳动净收益
   * @param currentNetPerLaborScaled 当前生产方式的同一读数
   * @return 转业速度（‰/轮）；0 = 不转业
   */
  static long profitGapSpeedPerMille(long otherNetPerLaborScaled, long currentNetPerLaborScaled) {
    // ★ 没有"收益规模基准"入参的调用点（旧夹具/薄封装）⇒ 基准 = 0；生产路径一律走下面的三参形态。
    return profitGapSpeedPerMille(otherNetPerLaborScaled, currentNetPerLaborScaled, 0L);
  }

  /**
   * ★★★ <b>W9-fix：转业速度算式（含正参考基准）</b> —— 生产路径的唯一入口（{@link #profitGapSpeedPerMille(long, long)}
   * 是本方法的"基准 = 0"薄封装）。
   *
   * @param profitScaleBasisScaled 两侧生产方式的收益规模基准取大后的值（≥ 0；见 {@link #profitScaleBasisScaled}）
   */
  static long profitGapSpeedPerMille(
      long otherNetPerLaborScaled, long currentNetPerLaborScaled, long profitScaleBasisScaled) {
    long gap;
    try {
      gap = Math.subtractExact(otherNetPerLaborScaled, currentNetPerLaborScaled);
    } catch (ArithmeticException overflow) {
      gap = otherNetPerLaborScaled > currentNetPerLaborScaled ? Long.MAX_VALUE : Long.MIN_VALUE;
    }
    if (gap <= 0L) {
      return 0L;
    }
    // ★ 斜率 = 0 的语义（与 α=0 "GM 把价格钉死" 同制）：**收益差不再产生任何转业速度** ⇒ 转业停摆。
    //   没有这一条，下面的"至少 1‰"地板会把"GM 关掉转业"这一合法状态变成"每轮仍走 1‰"。
    if (PROFIT_GAP_SPEED_SLOPE_PER_MILLE <= 0L) {
      return 0L;
    }
    // ★★★ W9-fix：分母 = max(|收益_当前|, |收益_其他|, 收益规模基准) —— **三个都是非负量**，且 gap > 0 时前两项
    //   不会同时为 0 ⇒ 分母恒 > 0（不需要 `max(1, ·)` 那种"不许除零"的补丁，那个补丁正是 W9 首版的病根）。
    long denominator =
        Math.max(
            Math.max(absOrMax(currentNetPerLaborScaled), absOrMax(otherNetPerLaborScaled)),
            Math.max(0L, profitScaleBasisScaled));
    if (denominator <= 0L) {
      return 0L; // 理论不可达（gap > 0 ⇒ 有一侧非 0）；fail-closed 不除零。
    }
    // 先除后乘 + 余数补算：逐值等于 ⌊SLOPE × gap ÷ denominator⌋，但不会在极端值上溢出。
    long whole = gap / denominator * PROFIT_GAP_SPEED_SLOPE_PER_MILLE;
    long residual = gap % denominator * PROFIT_GAP_SPEED_SLOPE_PER_MILLE / denominator;
    long speed;
    try {
      speed = Math.addExact(whole, residual);
    } catch (ArithmeticException overflow) {
      speed = Long.MAX_VALUE;
    }
    if (speed <= 0L) {
      speed = 1L; // gap > 0 且斜率 > 0 ⇒ 至少 1‰（"只要更高就有速度"，不打成 0）
    }
    return Math.min(A_RULE_TRANSFER_SPEED_PER_MILLE, speed);
  }

  /**
   * ★★★ <b>W9-fix（2026-10-11，控制方裁定 v3.32 §7 第 1 条）：一个生产方式的"收益规模基准"（缩放整型）</b> ——
   * 速度算式的**正参考基准**（分母下限）。
   *
   * <pre>
   * 基准(p) = max( 每单位劳动的毛收入   = perLaborScaled(p.revenueMilli,   p.laborNeedMilli),
   *               每单位劳动的口粮/劳动成本 = perLaborScaled(p.laborCostMilli, p.laborNeedMilli) )
   * p.laborNeedMilli ≤ 0（不可行/规模 0）⇒ 0
   * </pre>
   *
   * <p>★ <b>为什么是这两个量（回代码找的正量，不是拍的魔法数）</b>：收益差是"**相对什么**的差" —— 相对的是**这个生产方式 本身的收益规模**：1
   * 单位规模劳动能带来多少产值（{@code revenueMilli}）、至少要吃掉多少口粮（{@code laborCostMilli}）。两者都是 {@link
   * ExpectedProfitBook.Prospect} 现成的读数，且与收益差**同一把尺** （{@link ExpectedProfitBook#perLaborScaled}）⇒
   * 比值无量纲、不会漂开。取 max ⇒ 只要这个生产方式还在产东西、或还要养人， 基准就 &gt; 0。
   *
   * <p>★★ <b>它替掉的是什么</b>：W9 首版分母 {@code max(1, |收益_当前|)} 在"收益 ≈ 0"时退化为 1（把"无收益"读成 "无穷大相对差"）⇒ 打满
   * 1000‰ ⇒ 一次把整户/整格搬空。见 {@link #profitGapSpeedPerMille} 的类注。
   */
  static long profitScaleBasisScaled(ExpectedProfitBook.Prospect prospect) {
    Objects.requireNonNull(prospect, "prospect");
    long laborNeed = prospect.laborNeedMilli();
    if (laborNeed <= 0L) {
      return 0L; // 没有规模（不可行户/流民户）⇒ 没有基准
    }
    return Math.max(
        ExpectedProfitBook.perLaborScaled(prospect.revenueMilli(), laborNeed),
        ExpectedProfitBook.perLaborScaled(prospect.laborCostMilli(), laborNeed));
  }

  /**
   * ★★★ <b>W9-fix：可迁人口（"这一轮这一户最多走多少人"）—— 唯一拼写点</b>。
   *
   * <pre>
   * moveable = max(1, ⌊人口 × 速度‰ ÷ 1000⌋)，再夹到 [0, 人口]
   * 人口 ≤ 0 ∨ 速度 ≤ 0 ⇒ 0（没有可迁的人 / 速度 0 = 不转业）
   * </pre>
   *
   * <p>★ <b>"至少 1 人"地板（J3，逐值等于 W9）</b>：没有它，人口 &lt; 1000/速度 的户在任何速度下都走不动 （"只要更高就有速度"会被整数截断吃掉）。
   *
   * <p>★ <b>乘法溢出 ⇒ 返回整个人口（fail-closed，不静默回绕）</b>：速度 ≤ 1000 ⇒ "溢出的份额"必然 ≥ 100%， 夹到全户是唯一正确的一侧。
   *
   * <p>★ <b>两个调用点</b>：① 收益差速度（{@code bestSpeedPerMille}）；② {@link #MIGRATION_SPEED_CAP_PER_MILLE}
   * 的速率上限。取两者的小者 = "差越大越快，但每轮不超过上限"。
   */
  static long moveablePopulation(long population, long speedPerMille) {
    if (population <= 0L || speedPerMille <= 0L) {
      return 0L;
    }
    long scaled;
    try {
      scaled = Math.multiplyExact(population, speedPerMille) / A_RULE_TRANSFER_SPEED_PER_MILLE;
    } catch (ArithmeticException overflow) {
      return population;
    }
    return Math.min(population, Math.max(1L, scaled));
  }

  /** {@code |value|}，{@code Long.MIN_VALUE} 取饱和（denominator 必须是非负量，不许回绕成负数）。 */
  static long absOrMax(long value) {
    return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
  }
}

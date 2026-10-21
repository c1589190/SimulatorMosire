package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.ModeMigrationAssetPolicy.carriedCapitalByKind;
import static io.mosire.simos.economy.time.ModeMigrationAssetPolicy.carriedCarrierAssetsForMove;
import static io.mosire.simos.economy.time.ModeMigrationAssetPolicy.laborCarriedByMoveUpperBound;
import static io.mosire.simos.economy.time.ModeMigrationAssetPolicy.reserveIdleAssetsFor;
import static io.mosire.simos.economy.time.ModeMigrationAssetPolicy.supportedTierIndex;
import static io.mosire.simos.economy.time.ModeMigrationAssetPolicy.tierPosition;
import static io.mosire.simos.economy.time.ModeMigrationPolicy.A_RULE_TRANSFER_SPEED_PER_MILLE;
import static io.mosire.simos.economy.time.ModeMigrationRules.canonicalKey;
import static io.mosire.simos.economy.time.ModeMigrationRules.hasIndustryTemplate;
import static io.mosire.simos.economy.time.ModeMigrationRules.hasMerchantCapacityAt;
import static io.mosire.simos.economy.time.ModeMigrationRules.industriesForMode;
import static io.mosire.simos.economy.time.ModeMigrationRules.marketOf;
import static io.mosire.simos.economy.time.ModeMigrationRules.newHouseholdId;
import static io.mosire.simos.economy.time.ModeMigrationRules.sortedHouseholds;
import static io.mosire.simos.economy.time.ModeMigrationScoreBook.inKindLegUnavailableReason;
import static io.mosire.simos.economy.time.ModeMigrationScoreBook.profitGapSpeedPerMille;
import static io.mosire.simos.economy.time.ModeMigrationScoreBook.profitScaleBasisScaled;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.time.ModeMigrationRules.HouseholdMode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRuntime;
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

/** P0.5-E1 机械拆分自 {@code ModeMigrationPolicy}；职责见类名与各方法注释。 */
final class ModeMigrationCandidateBook {

  private ModeMigrationCandidateBook() {}

  private static final org.slf4j.Logger LOG = EconomyLog.migration();

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
  static List<Target> buildTargets(
      EconomyDayView base,
      HouseholdId source,
      HouseholdEconomy sourceHouseholdEconomy,
      HouseholdMode current,
      long currentPerLabor,
      long currentProfitBasisScaled,
      List<PrimaryModeRanking.Excluded> excluded,
      List<HexCoord> hexes,
      List<ProductionModeDefinitionView> modes,
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
      long day,
      CandidateTally tally,
      ProductionRuntime productionRuntime,
      AssetOccupancyBudget assetBudget) {
    List<Target> targets = new ArrayList<>();
    for (HexCoord hex : hexes) {
      int distance = sourceHouseholdEconomy.view().hex().distanceTo(hex);
      if (distance > 1) {
        continue;
      }
      for (ProductionModeDefinitionView mode : modes) {
        if (DefaultProductionModes.DISPLACED.equals(mode.id())) {
          continue; // displaced 只在 A 规则"无路可走"支里出现
        }
        if (mode.id().equals(current.modeId()) && hex.equals(current.hex())) {
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
        // ★★★ W7（2026-10-11）：候选评估的两个具名落点（§一.9）—— ① "评估过"（逐格计数）；
        //   ② "为什么没成"（prospect 的具名理由 / 门槛 / 实物腿），轮级一行汇总（见 CandidateTally.logRound）。
        boolean merchantCandidate = DefaultProductionModes.MERCHANT.equals(mode.id());
        tally.candidate(hex, merchantCandidate);
        // ★★★ W9：并入既有户这一支也要目标格真能承载这种生产方式（W7 判据，保留）。
        boolean hostIndustryAvailable =
            !DefaultProductionModes.MERCHANT.equals(mode.id())
                || !industriesForMode(base, hex, mode.id()).isEmpty();
        HouseholdId existing =
            bestExistingTarget(
                source,
                hex,
                mode,
                modeByHousehold,
                positions,
                householdEconomies,
                hostIndustryAvailable);
        // ★★★ W9（用户裁定 v3.29/v3.30 §7 第 2/4 条）：**档位 = 这户带来的库存能支撑的最高档**。
        //   ① 档位按**整户**能带走的作业资本算（carriedCapitalByKind：自有 + 可移动 + 未被占用 + 工具商品库存）；
        //   ② 目标产业模板 = 该 (mode, hex) 解析出来的那一个（农场/作坊/商号各自的 slots() 与 capacityPerUnit）；
        //   ③ 只有**新建**目标才落档位（并入既有户时，目标户的档位是它自己的 —— 不是这笔能决定的）。
        IndustryDefinitionView tierIndustry =
            industryForTier(base, hex, mode.id(), merchantCandidate);
        int tierIndex =
            tierIndustry == null
                ? 0
                : supportedTierIndex(
                    tierIndustry,
                    sourceHouseholdEconomy.participationAdjustedLaborMilli(),
                    carriedCapitalByKind(
                        source,
                        assetShares,
                        claimedByEnterprises,
                        accounts.householdGoods().getOrDefault(source, Map.of())));
        List<ProductionRole> tierPositions = producingPositions.get(mode.id());
        ClassPositionId tierPositionId =
            existing == null && tierIndex > 0 && tierPositions != null
                ? tierPosition(tierPositions, tierIndex)
                : null;
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
                day,
                tierPositionId,
                // ★★ §15.12 B（R1）：候选的物理量同样取自 production 的试算（熟练度/GM 覆盖/解锁条件进选择链）。
                productionRuntime,
                assetBudget);
        // ★★★ W9（用户裁定 v3.30 §7 第 1 条「没有库存就要么去当前生产方式的最低档…为啥要拦着？」）：
        //   **降档重试** —— 默认位置因为"这户拿不出作业资本/劳动"而不可行时，再按**最低档的位置**试一次
        //   （只有"新建目标"才试；并入既有户不生成新关系、也不新建 unit，位置不是这笔能决定的）。
        //   ★ 方向：它**只会新增候选**（每个候选仍由同一个 ExpectedProfitBook 判可行）⇒ 不静默放行任何不可行目标；
        //     真的落到最低档时，落点随 `tierPositionId` 进计划（档位仍是 0 = 兜底档，槽位照抄源户，见 D1）。
        if ((!prospect.feasible() || prospect.feasibleScale() <= 0L)
            && existing == null
            && tierPositionId == null
            && tierPositions != null
            && tierPositions.size() > 1) {
          ClassPositionId lowestPosition = tierPosition(tierPositions, 0);
          if (lowestPosition != null && !lowestPosition.equals(prospect.positionId())) {
            ExpectedProfitBook.Prospect lowerProspect =
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
                    day,
                    lowestPosition,
                    productionRuntime,
                    assetBudget);
            if (lowerProspect.feasible() && lowerProspect.feasibleScale() > 0L) {
              prospect = lowerProspect;
              tierPositionId = lowestPosition;
            }
          }
        }
        if (!prospect.feasible() || prospect.feasibleScale() <= 0L) {
          tally.failure(
              hex,
              mode.id(),
              prospect.reason(),
              merchantCandidate,
              source,
              merchantCandidate
                  ? MerchantCapacityPool.providerCapacityMilli(
                      base, source, hex, accounts.householdGoods())
                  : 0L,
              sourceHouseholdEconomy,
              accounts.householdGoods());
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
            tally.failure(
                hex,
                mode.id(),
                gate,
                true,
                source,
                MerchantCapacityPool.providerCapacityMilli(
                    base, source, hex, accounts.householdGoods()),
                sourceHouseholdEconomy,
                accounts.householdGoods());
            continue;
          }
        }
        long weight =
            Math.max(0L, Math.subtractExact(prospect.netPerLaborScaled(), currentPerLabor));
        // ★★★ W9（用户裁定 v3.30 §7 第 2 条）：**速度 = 预期收益差驱动**（差 ≤ 0 ⇒ 0 ⇒ 该目标不是去处）。
        //   ★★★ W9-fix（控制方裁定 v3.32 §7 第 1 条）：分母 = max(|收益_当前|, |收益_其他|, **收益规模基准**)，
        //     基准 = 两侧生产方式的"1 单位规模劳动的毛收入 / 口粮成本"取大（见 {@link #profitScaleBasisScaled}）
        //     —— 它保证"收益 ≈ 0 的户"不会拿到无穷大的相对差（那正是 21 个零成交轮的根因）。
        long speedPerMille =
            profitGapSpeedPerMille(
                prospect.netPerLaborScaled(),
                currentPerLabor,
                Math.max(currentProfitBasisScaled, profitScaleBasisScaled(prospect)));
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
            tally.failure(
                hex,
                mode.id(),
                refusal,
                merchantCandidate,
                source,
                merchantCandidate
                    ? MerchantCapacityPool.providerCapacityMilli(
                        base, source, hex, accounts.householdGoods())
                    : 0L,
                sourceHouseholdEconomy,
                accounts.householdGoods());
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
        // ★ R4：已有同 key 目标户不再受旧单户人口上限约束 —— 视为可接纳任意人口；
        //   新建目标仍由 perMoveNewRoom 的真实资产/劳动可行性决定。
        long room = existing == null ? 0L : Long.MAX_VALUE;
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
                // ★ W9：新建目标 + 支撑到高档 ⇒ 落点是**那一档的位置**；否则用 prospect 自己的落点（逐值等于改前）。
                tierPositionId != null ? tierPositionId : prospect.positionId(),
                prospect.netPerLaborScaled(),
                prospect.reason(),
                speedPerMille,
                tierIndex));
      }
    }
    targets.sort(
        Comparator.comparingLong((Target target) -> target.weight())
            .reversed()
            .thenComparingInt(target -> target.distance())
            .thenComparing(target -> target.canonical())
            .thenComparing(target -> target.existing() == null ? "" : target.existing().value()));
    return targets;
  }

  /**
   * ★★ <b>W9：档位判据要读哪一个产业模板</b> —— 与 {@code ExpectedProfitBook.resolveIndustry} / {@code
   * ModeMigrationSettlement.industriesForMode} 同源（按 mode 的默认 regime 在该格挑，id 升序第一个）。
   *
   * <p>★ merchant 在**非城市格**没有 {@code trade} 模板 ⇒ 用按需模板 {@link
   * MerchantTradeTemplate#industry(HexCoord)} （与决策层预估、执行期真的写进工作副本的那一份**同一个拼写点**）。都没有 ⇒ {@code
   * null}（调用方按最低档兜底）。
   */
  static IndustryDefinitionView industryForTier(
      EconomyDayView base, HexCoord hex, ProductionModeId modeId, boolean merchant) {
    for (IndustryId industryId : industriesForMode(base, hex, modeId)) {
      IndustryDefinitionView industry = base.industries().get(industryId);
      if (industry != null) {
        return industry;
      }
    }
    return merchant ? MerchantTradeTemplate.industry(hex) : null;
  }

  /** A 规则专用：DISPLACED 目标（已有流民户或同一/邻格新建；不要求产业/资产）。 */
  static List<Target> buildDisplacedTargets(
      HouseholdId source,
      HouseholdMode current,
      List<HexCoord> hexes,
      List<ProductionModeDefinitionView> modes,
      Map<ProductionModeId, List<ProductionRole>> producingPositions,
      Map<HouseholdId, HouseholdMode> modeByHousehold,
      Map<HouseholdId, HouseholdEconomy> householdEconomies) {
    ProductionModeDefinitionView displaced =
        modes.stream()
            .filter(mode -> DefaultProductionModes.DISPLACED.equals(mode.id()))
            .findFirst()
            .orElse(null);
    if (displaced == null) {
      return List.of();
    }
    List<Target> targets = new ArrayList<>();
    for (HexCoord hex : hexes) {
      int distance = current.hex().distanceTo(hex);
      if (distance > 1) {
        continue;
      }
      List<ProductionRole> positions = producingPositions.get(displaced.id());
      if (positions == null || positions.isEmpty()) {
        continue;
      }
      HouseholdId existing =
          bestExistingTarget(
              source, hex, displaced, modeByHousehold, positions, householdEconomies, true);
      // ★ R4：已有 DISPLACED 目标户无人口上限；新建目标容量只是占位（真实承载力仍由 allocate 决定）。
      long room = existing == null ? 0L : Long.MAX_VALUE;
      if (existing == null) {
        targets.add(
            new Target(
                hex,
                displaced.id(),
                null,
                0L,
                distance,
                true,
                Long.MAX_VALUE,
                canonicalKey(hex, displaced.id()),
                null,
                0L,
                "DISPLACED",
                0L,
                0));
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
                "DISPLACED",
                0L,
                0));
      }
    }
    targets.sort(
        Comparator.comparingInt((Target target) -> target.distance())
            .thenComparing(target -> target.canonical())
            .thenComparing(target -> target.existing() == null ? "" : target.existing().value()));
    return targets;
  }

  /**
   * 分配可迁人口：按目标排序贪心填充（已有户无人口上限 → 同目标可新建容量 → 下一个目标）。
   *
   * <p>★ 新建目标的资产可行性在这里用"本次实际要建的人口"复核（不足 ⇒ 该目标的新建容量记 0）。溢出部分留在源户， 不丢人、也不凭空造承载。★★ P10.6：预留账本由调用方（同一
   * plan 顶层）传入，两遍循环共用、不重置。
   */
  static List<MoveDraft> allocate(
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
      weights[i] = Math.max(0L, targets.get(i).weight());
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
   * 往一个目标里放人口（已有户无人口上限 → 该 (hex, mode) 至多新建一户）；返回实际放入人口。
   *
   * <p>★★ P10.6：新建部分的"资产可行性"是<b>检查并预留</b>（{@code reserveIdleAssetsFor}）；预留成功才产出 MoveDraft， 账本由同一
   * {@code plan()} 的所有源户/所有目标共享。
   */
  static long placeInto(
      Target target,
      long allow,
      HouseholdId source,
      long speedOverridePerMille,
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
    // ★★★ W9（用户裁定 v3.30 §7 第 2 条）：速度**逐目标**取 —— 覆盖值 &gt; 0（A 规则两支传 1000‰）用它，
    //   否则用该目标自己的收益差速度（{@link #profitGapSpeedPerMille}）。速度 ≤ 0 ⇒ 这个目标不是去处（不落地）。
    long speedPerMille =
        speedOverridePerMille > 0L ? speedOverridePerMille : target.speedPerMille();
    if (speedPerMille <= 0L) {
      return 0L;
    }
    if (target.existing() != null && target.existingRoom() > 0L) {
      long take = Math.min(allow, target.existingRoom());
      drafts.add(
          new MoveDraft(
              target.existing(),
              target.hex(),
              target.mode(),
              speedPerMille,
              take,
              reason,
              target.positionId(),
              target.tierIndex()));
      placed += take;
    }
    if (placed >= allow || !target.newFeasible()) {
      return placed;
    }
    // ★ R4：新建目标不再按旧单户上限夹紧；want = 本轮剩余可迁人口，承载由 perMoveNewRoom 复核。
    long want = allow - placed;
    long room =
        perMoveNewRoom(
            target.hex(),
            target.mode(),
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
              newHouseholdId(source, target.hex(), target.mode(), newOrdinal[0]++),
              target.hex(),
              target.mode(),
              speedPerMille,
              take,
              reason,
              target.positionId(),
              target.tierIndex()));
      placed += take;
    }
    return placed;
  }

  /**
   * 新建户可承载人口：由本次 take 的劳动反推规模，再看该 hex 的产业模板里有没有"闲置/可租赁资产够且预留成功"的； 第一个预留成功的产业即返回
   * take（预留已计入共享账本）。没有产业可预留 ⇒ 0。
   */
  static long perMoveNewRoom(
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
    // ★★★ W9-fix2：口径 = {@link #laborCarriedByMoveUpperBound}（唯一拼写点，向上取整 = 按较大者预留）。
    long laborEstimate =
        laborCarriedByMoveUpperBound(
            sourceHouseholdEconomy.laborMilli(), sourceHouseholdEconomy.population(), take);
    // ★ 与执行期（ModeMigrationSettlement.createEnterpriseAndProcess）同一份"该 mode 能用哪些产业模板"：
    //   按 mode 的默认 regime 优先；merchant 找不到 trade 模板 ⇒ 空（绝不把农场模板当商号）。
    List<IndustryId> templates = industriesForMode(base, hex, mode);
    // ★★★ W7（2026-10-11）：**非城市格的跑商** —— 本格没有 merchant 制度的产业模板时，走"按需创建"那一支：
    //   估算用的模板 = {@link MerchantTradeTemplate#industry(HexCoord)}（与 {@code ExpectedProfitBook}
    // 的可行性预估、
    //   与迁移执行真的写进工作副本的那一份**同一个拼写点**）；可用的**作业资本 = 工具** = **该户自备/随迁携带**的
    //   那一份（D-023 §4 可移动资产随迁；判据与执行期 {@code migrateAssetsForMove} 的按人口比例取整逐式对齐）。
    //   ★★ W7-fix（2026-10-11，用户裁定 v3.28 §7）：判据从 **CATTLE** 换成 **工具**（= 模板的
    //     {@code capacityPerUnit} 那一维）—— 牲口不再是跑商的可行性门槛，也不再是运力的来源。
    //     没有作业资本（工具）的户 ⇒ 这里算不出可用量 ⇒ 返回 0 ⇒ 不就这一格建目标
    //     （"新建户凑不齐作业资本就建不出商号"仍是诚实的约束，且与执行期 {@code planAssetMoves} 同一口径）。
    if (templates.isEmpty() && DefaultProductionModes.MERCHANT.equals(mode)) {
      IndustryDefinitionView onDemand = MerchantTradeTemplate.industry(hex);
      Map<AssetKind, Long> carried =
          carriedCarrierAssetsForMove(sourceHouseholdEconomy, take, assetShares);
      // ★★★ W9-fix2（2026-10-11，本批修掉的具名不一致）：规模口径**必须与执行期同一份算式** ——
      //   {@code createEnterpriseAndProcess} 对**新建**目标户算
      //   {@code scale = requiredWorkCapitalScale(模板, 目标行.laborMilli)}，而那里的"目标行"是
      //   **带本笔随迁投影的工作行视图**（{@code 0 + 本笔迁入劳动}），**不是**落盘行。
      //   ⇒ 改前在这里传 {@code laborPerUnit}（把 P0 的落盘 labor = 0 当执行期读法 ⇒ scale 恒 1）是**错的**：
      //     计划按 1 份预留、执行按 ⌊laborTake ÷ laborPerUnit⌋ 份索取 ⇒ N 倍缺口 ⇒ 执行期守卫当场抛
      //     （three-powers day=270：labor=8857 ⇒ scale=8，计划只留了 1 份；HTTP 500）。
      //   ⇒ 现在传 {@link #laborCarriedByMoveUpperBound}（= 执行期会读到的那一份劳动，按较大者预留），
      //     与执行期共用 {@link #requiredWorkCapitalScale} 这一个拼写点。
      //   ★ 产业规模**随后由资产派生**（ProductionProcessBook 读 OwnershipStake）：一户带 N 毫工具过去
      //     就得到 N 单位规模的商号（上界）；真正的规模由劳动与投入满足率取最紧那一路 ——
      //     "从 1 单位起步、随作业资本长大"，这正是"不凭空造资产"的字面结果。
      //     ★ 但"能建起来"的**门槛**是劳动反解出来的那一份（本文上面那条），不是 1 —— 门槛低于执行期索取
      //       就是"计划包含了一个执行期建不起来的目标"，那是 fail-closed 守卫该拦的（守卫不放宽）。
      // ★★★ W9（2026-10-11，用户裁定 v3.29 §7 第 3 条「没有生产工具不会买吗」）：**没带来作业资本 ⇒ 走"买/租"通道**
      //   —— 与执行期同一判据：允许用**同格其它产业**的闲置同种资产作作业资本（本格市场交易工具是决策层
      //   {@code ExpectedProfitBook} 那一侧的可买判据，执行期 gate 再复核一次）。带得来 ⇒ 逐值等于改前。
      boolean buyOrLease = carried.isEmpty();
      return reserveIdleAssetsFor(
              onDemand,
              laborEstimate,
              assetShares,
              reservedIdle,
              claimedByEnterprises,
              carried,
              buyOrLease,
              hex)
          ? take
          : 0L;
    }
    for (IndustryId industryId : templates) {
      IndustryDefinitionView industry = base.industries().get(industryId);
      if (industry != null
          && reserveIdleAssetsFor(
              industry,
              laborEstimate,
              assetShares,
              reservedIdle,
              claimedByEnterprises,
              Map.of(),
              false,
              null)) {
        return take;
      }
    }
    return 0L;
  }

  /**
   * 同 (hex, mode) 的已有目标家户：★ R4 起**不再按剩余容量选**（不存在单户人口上限），改为稳定排序 （HouseholdId 升序）取第一个同 key 目标。
   *
   * <p>★ 返回非 null 只表示"存在可并入的同 key 户"；"能装多少人"由调用方按本轮可迁人口直接放入，不再夹紧。
   */
  static HouseholdId bestExistingTarget(
      HouseholdId source,
      HexCoord hex,
      ProductionModeDefinitionView mode,
      Map<HouseholdId, HouseholdMode> modeByHousehold,
      List<ProductionRole> producingPositions,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      boolean hostIndustryAvailable) {
    if (!hostIndustryAvailable) {
      // ★★★ W7：本格**没有**该 mode 的产业模板 ⇒ 不得并入既有户（并入 = 一户没有生产 unit 的空壳；
      //   见调用点的具名理由）。返回 null 让上层走"新建户"那一支（新建会按需建产业 + 由该户自带的资产建 unit）。
      return null;
    }
    Set<ClassPositionId> positionIds = new LinkedHashSet<>();
    for (ProductionRole position : producingPositions) {
      positionIds.add(position.id());
    }
    // sortedHouseholds = HouseholdId.value 升序；第一个命中的同 key 户就是稳定选择。
    for (HouseholdId household : sortedHouseholds(householdEconomies)) {
      if (household.equals(source)) {
        continue;
      }
      HouseholdMode candidate = modeByHousehold.get(household);
      if (candidate == null
          || !candidate.modeId().equals(mode.id())
          || !candidate.hex().equals(hex)
          || !positionIds.contains(candidate.positionId())) {
        continue;
      }
      return household;
    }
    return null;
  }

  // ── 预期收益（A 规则，D-024：与候选评估共用同一个计算器）──────────────────────────────────

  /**
   * 目标候选（★ R4 起 existingRoom 对已有户恒为 Long.MAX_VALUE = 无人口上限；newFeasible 为同 (hex, mode) 是否能新建）。
   *
   * <p>★★ <b>M-D 追加 {@code positionId} / {@code yieldPerLaborScaled}</b>：目标那一项的**位置 id**（= 该 (hex,
   * mode) 的 {@code ExpectedProfitBook.Prospect.positionId()}，排序表第 1
   * 项的落点候选）与该行的**收益率读数**（同尺，进排序表与日志）。 ★ {@code positionId} 可 {@code null}（DISPLACED 目标等没有 prospect
   * 的场合）。
   *
   * <p>★★★ <b>W9 追加 {@code speedPerMille} / {@code tierIndex}</b>：这一项的**转业速度**（收益差驱动，见 {@link
   * #profitGapSpeedPerMille}）与**档位**（{@link #supportedTierIndex}；0 = 兜底档）。★ 速度 0 ⇒ 该项不进活候选 （{@code
   * weight > 0 && speed > 0} 才是去处）；档位进 {@code MigrationMove}，决定新建目标户落在哪一档。
   */
  record Target(
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
      String prospectReason,
      long speedPerMille,
      int tierIndex) {
    Target {
      Objects.requireNonNull(hex, "hex");
      Objects.requireNonNull(mode, "mode");
      Objects.requireNonNull(canonical, "canonical");
      if (speedPerMille < 0L || speedPerMille > A_RULE_TRANSFER_SPEED_PER_MILLE) {
        throw new IllegalArgumentException(
            "Target.speedPerMille 必须在 [0, "
                + A_RULE_TRANSFER_SPEED_PER_MILLE
                + "]（0 = 收益差不为正 ⇒ 不转业）: "
                + speedPerMille);
      }
      if (tierIndex < 0) {
        throw new IllegalArgumentException("Target.tierIndex 不得为负: " + tierIndex);
      }
    }
  }

  /**
   * 施工坞（人口切分与目标确认完成后才冻成 {@link MigrationMove}）。
   *
   * <p>★ M-D：{@code primaryPositionId} = 该目标那一项在**生产方式排序表**里的位置（= {@code current} 的落点）； 可 {@code
   * null}（DISPLACED 等没有 prospect 的目标）。
   */
  static final class MoveDraft {
    final HouseholdId target;
    final HexCoord targetHex;
    final ProductionModeId targetMode;
    final long transferSpeedPerMille;
    final long population;
    final String reason;
    final ClassPositionId primaryPositionId;

    /** ★★ W9：落点档位（0 = 兜底档/贫农；见 {@link #supportedTierIndex}）。 */
    final int tierIndex;

    MoveDraft(
        HouseholdId target,
        HexCoord targetHex,
        ProductionModeId targetMode,
        long transferSpeedPerMille,
        long population,
        String reason,
        ClassPositionId primaryPositionId,
        int tierIndex) {
      this.target = target;
      this.targetHex = targetHex;
      this.targetMode = targetMode;
      this.transferSpeedPerMille = transferSpeedPerMille;
      this.population = population;
      this.reason = reason;
      this.primaryPositionId = primaryPositionId;
      this.tierIndex = tierIndex;
    }
  }

  /**
   * ★★★ <b>W7（2026-10-11）：一轮"生产方式候选"评估的**具名账**（瞬态；只进日志，不是状态组件）</b>。
   *
   * <p>★★ <b>它回答的问题是</b>：「**为什么这一轮没有新跑商？**」—— 用户派单 ⑤ 的原话： 「{@code NO_MERCHANT_CAPACITY:*} 一族（含
   * {@code no-trade-industry} / {@code no-capacity}）在**决策层静默 continue 处** 发具名 DEBUG（或 INFO，按 §一.9
   * 的级别约定），字段含 hex/mode/reason/关键量级；★ 同时把"为什么这一轮没有新跑商" 在轮级 DEBUG 里可读（含每格候选数与首个失败理由）」。
   *
   * <p>★★ <b>W7-fix（2026-10-11）：{@code NO_MERCHANT_CAPACITY:*} 一族的成员换了</b> —— 用户裁定 v3.28 §7
   * 取消牲口要求后， 该族现在是「{@code no-labor}」（没有劳动投入）/「{@code no-tool-input}」（手上没有工具）/ 「{@code
   * no-capacity}」（服务成市格上取较大者后仍为 0 的兜底）；{@code no-trade-industry} 与 {@code no-carrier-asset}
   * 两条**已不存在**（前者被"按需创建模板"取代，后者被用户裁定删除）。★ 本类的机制一字未改。
   *
   * <pre>
   * 轮级一行（DEBUG，事件 MODE_MIGRATION_CANDIDATES）：
   *   candidateRows          本轮评估过的 (家户 × mode × hex) 候选数
   *   merchantCandidates     其中 merchant 候选数
   *   byHex                  逐格候选数（键序 = hex 规范序）
   *   merchantFirstFailure   逐格"首个具名失败理由"（★ W7 前这条链是**静默 continue**，日志里一片空白）
   *   failuresByReason       逐理由计数（NO_MERCHANT_CAPACITY:no-labor / no-tool-input / no-capacity /
   *                          no-deliverable-service-capacity / no-bargaining-power …）
   *   moves / movedPopulation 本轮真的落地的迁移笔数与人口（0 ⇒ 一行也不少 —— "没有"本身就是要读的事实）
   * 逐候选一行（DEBUG，事件 MODE_MIGRATION_PROSPECT_INFEASIBLE，**只对 NO_MERCHANT_CAPACITY:* 一族发**）：
   *   household / mode / hex / reason / merchantCandidate
   * </pre>
   *
   * <p>★ <b>级别取舍（具名记下）</b>：§一.9 把"业务拒绝"定为 INFO，但这里**不逐候选发 INFO** —— 非城市格每天每户都会 产生同样的拒绝（调查报告实测：sw 17
   * 格 × 每户每天），逐行 INFO 会把真正的"这一轮发生了什么"淹掉。⇒ **轮级一行**（"这一轮有几个候选、为什么没成"）承担 INFO 的可读性职责，逐候选明细留在 DEBUG。
   * 这条取舍与"日志失败不得影响结算"同级：本类只读、不写任何状态、不改任何判据。
   */
  static final class CandidateTally {

    private final Map<HexCoord, Long> candidatesByHex = new LinkedHashMap<>();
    private final Map<HexCoord, Long> merchantCandidatesByHex = new LinkedHashMap<>();
    private final Map<HexCoord, String> merchantFirstFailureByHex = new LinkedHashMap<>();
    private final Map<String, Long> failuresByReason = new LinkedHashMap<>();
    private long candidateRows = 0L;
    private long merchantCandidates = 0L;

    /** ★★ W9：候选阶段跳过的"壳户"（人口 0 / 无阶层归属的那一族）。 */
    private long shellSkippedNoPopulation = 0L;

    /** ★★ W9：候选阶段跳过的"没有劳动可投"的户（人口 &gt; 0 但参与率折算后劳动 0）。 */
    private long shellSkippedNoLabor = 0L;

    /**
     * ★★ <b>W9（用户裁定 v3.30 §7，用户派单 ⑥）：记一次"壳户在候选阶段被跳过"</b> —— {@code noPopulation = true} ⇒ 人口
     * 0/无归属；{@code false} ⇒ 人口 &gt; 0 但没有劳动可投。
     *
     * <p>★ 只计数、**不逐户打点**：W7e 真世界实测这两族每轮上千条（{@code no-labor} 8,844(sw)/17,610(tp)），
     * 逐户日志会把"这一轮到底发生了什么"淹掉；轮级一行（{@link #logRound}）承担可读性职责。
     */
    void shellSkipped(boolean noPopulation) {
      if (noPopulation) {
        shellSkippedNoPopulation++;
      } else {
        shellSkippedNoLabor++;
      }
    }

    /** 记一次"这个 (家户, mode, hex) 候选被评估过"。 */
    void candidate(HexCoord hex, boolean merchant) {
      candidateRows++;
      candidatesByHex.merge(hex, 1L, Math::addExact);
      if (merchant) {
        merchantCandidates++;
        merchantCandidatesByHex.merge(hex, 1L, Math::addExact);
      }
    }

    /**
     * 记一次"这个候选没成"（具名理由）。★ 逐候选 DEBUG **只对 {@code NO_MERCHANT_CAPACITY:*} 一族发** ——
     * 那正是用户点名的、改前**一行都不打**的那一族；其余理由（门槛/实物腿/其它 mode 的不可行）只进轮级计数。
     *
     * <p>★★ <b>W7-fix（2026-10-11）：量级字段补上"劳动 + 工具"两项</b> —— 用户裁定 v3.28 §7 之后该族的新成员 （{@code no-labor}
     * / {@code no-tool-input}）说的正是这两项；只报 {@code deliverableCapacityMilli} 会让"可交付运力明明 &gt; 0
     * 却不可行"在同一行里读不出原因（读数面必须自解释）。
     */
    void failure(
        HexCoord hex,
        ProductionModeId modeId,
        String reason,
        boolean merchant,
        HouseholdId household,
        long deliverableCapacityMilli,
        HouseholdEconomy row,
        Map<HouseholdId, Map<CommodityId, Long>> goods) {
      String named = reason == null ? "UNSPECIFIED" : reason;
      failuresByReason.merge(named, 1L, Math::addExact);
      if (!merchant) {
        return;
      }
      merchantFirstFailureByHex.putIfAbsent(hex, named);
      if (named.startsWith("NO_MERCHANT_CAPACITY:") && LOG.isDebugEnabled()) {
        // ★ 具名行（字段按用户派单 ⑤：hex / mode / reason / **关键量级**）—— 量级 = 该户的
        //   **可交付运力**（{@link MerchantCapacityPool#providerCapacityMilli}，与结算层同一把尺），
        //   外加 W7-fix 的两个可行性操作数（劳动投入 / 手上的工具）。
        //   ★★ W7-fix3（2026-10-11）：工具存量先在**对象口径**取出来（见下面那条注释）—— 改前它是
        //   `goods == null ? 0L : <Long>`：三目里 long 与 Long 混用会**拆箱**，随即又为 varargs **装箱**
        //   （SpotBugs BX_UNBOXING_IMMEDIATELY_REBOXED）。★ 只改取值形态，不碰字段名/值/级别/发射条件。
        Long toolMilli = 0L;
        if (goods != null) {
          toolMilli =
              goods
                  .getOrDefault(household, Map.of())
                  .getOrDefault(MerchantTradeTemplate.TOOL_COMMODITY, 0L);
        }
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "MODE_MIGRATION_PROSPECT_INFEASIBLE",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "household",
                    household.value(),
                    "modeId",
                    modeId.value(),
                    "hex",
                    hex,
                    "reason",
                    named,
                    "deliverableCapacityMilli",
                    deliverableCapacityMilli,
                    "laborMilli",
                    row == null ? 0L : row.participationAdjustedLaborMilli(),
                    "toolMilli",
                    toolMilli));
      }
    }

    /** 轮级汇总（DEBUG，一行）—— **无论本轮有没有迁移都发**（"没有"是要读的事实，不是噪声）。 */
    void logRound(long day, long moves, long movedPopulation) {
      if (!LOG.isDebugEnabled()) {
        return;
      }
      Map<HexCoord, Long> candidatesView = new LinkedHashMap<>(candidatesByHex);
      Map<HexCoord, Long> merchantView = new LinkedHashMap<>(merchantCandidatesByHex);
      Map<HexCoord, String> firstFailureView = new LinkedHashMap<>(merchantFirstFailureByHex);
      Map<String, Long> reasonsView = new LinkedHashMap<>(failuresByReason);
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "MODE_MIGRATION_CANDIDATES",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "candidateRows",
                  candidateRows,
                  "merchantCandidates",
                  merchantCandidates,
                  // ★★ W9：壳户在候选阶段被跳过的具名计数（人口 0 / 无劳动两族）——"没有"也是要读的事实。
                  "skippedShellNoPopulation",
                  shellSkippedNoPopulation,
                  "skippedShellNoLabor",
                  shellSkippedNoLabor,
                  "byHex",
                  candidatesView,
                  "merchantCandidatesByHex",
                  merchantView,
                  "merchantFirstFailureByHex",
                  firstFailureView,
                  "failuresByReason",
                  reasonsView,
                  "moves",
                  moves,
                  "movedPopulation",
                  movedPopulation));
    }
  }

  /** 本轮计划迁出的人口合计（只作轮级读数）。 */
  static long totalDraftPopulation(Map<HouseholdId, List<MoveDraft>> draftsBySource) {
    long total = 0L;
    for (List<MoveDraft> drafts : draftsBySource.values()) {
      for (MoveDraft draft : drafts) {
        total = Math.addExact(total, draft.population);
      }
    }
    return total;
  }
}

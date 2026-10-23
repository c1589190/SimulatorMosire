package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.land.LandLedgerState;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ★★★ <b>D-032 C3/C8 / S7-B：PROLETARIAN 市场买入 LAND/运输马匹后的向上跃迁。</b>
 *
 * <p>★ <b>父运行序列锚点</b>：{@code
 * docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md} §3.2
 * S7-B、§8.1/§8.2。 位置 = 统一市场轮把 LAND 产权写回工作副本之后、S7-C 向下流动之前。
 *
 * <pre>
 * 输入：
 *   LAND     = ctx.marketRound.landAcquiredQuantityThisRound()（只含市场 LAND 成交）
 *   transport_horse = 本轮市场报告中已实际交付的 fill（TransportHorseAcquisitionBook 唯一读口）
 * 过滤：ctx.ownershipClassAtMarketTradeStart[buyer] == PROLETARIAN
 * 容量：LAND = LandUpwardMobility.landAbsorbableMigrants（C3 唯一算式）
 *       马匹 = LandUpwardMobility.horseAbsorbableMigrants（C8 唯一算式）
 *       total = min(P-1, speedCap, landCapacity + horseCapacity)
 * 拆分：landCapacity:horseCapacity 最大余数；LAND 先 tie-break
 * 顺序：先 LAND（LandBook + 人口/钱货债/经验）→ 后马匹（普通库存 transport_horse + 人口/钱货债/经验）
 * </pre>
 *
 * <p>★★ <b>本类不做</b>：市场撮合、S7-M2 债偿、C7 lane/锚价、C9 世界播种/读口。
 */
final class PostLandTradeClassCheck {

  /** RC3 outbox 的固定原因标签。 */
  static final String POST_LAND_CLASS_TRANSITION_REASON = "POST_LAND_CLASS_TRANSITION";

  /** 毫商品 / 整商品单位（与 MarketTable 价格量纲一致）。 */
  private static final long MILLI_PER_COMMODITY_UNIT = 1000L;

  /** 千分亩 / 整亩（与 LAND 数量量纲一致）。 */
  private static final long MILLI_MU_PER_MU = 1000L;

  private static final org.slf4j.Logger LOG = EconomyLog.migration();

  private PostLandTradeClassCheck() {}

  /** 单笔 buyer→target 的 LAND/人口结果。 */
  record TargetMove(
      HouseholdId buyer, HexCoord hex, HouseholdId target, long landTransferred, long migrants) {

    TargetMove {
      Objects.requireNonNull(buyer, "TargetMove.buyer");
      Objects.requireNonNull(hex, "TargetMove.hex");
      Objects.requireNonNull(target, "TargetMove.target");
      if (landTransferred <= 0L) {
        throw new IllegalArgumentException("TargetMove.landTransferred 必须 > 0");
      }
      if (migrants < 0L) {
        throw new IllegalArgumentException("TargetMove.migrants 不得为负");
      }
    }
  }

  /** S7-B LAND 半边的只读交账读数（C3 既有形状保持不变）。 */
  record PostLandResult(
      int buyers,
      int hexes,
      int transfers,
      long migrants,
      long landTransferred,
      List<TargetMove> targets) {

    PostLandResult {
      if (buyers < 0 || hexes < 0 || transfers < 0 || migrants < 0L || landTransferred < 0L) {
        throw new IllegalArgumentException("PostLandResult 的计数/数量不得为负");
      }
      targets = List.copyOf(targets);
    }

    static PostLandResult empty() {
      return new PostLandResult(0, 0, 0, 0L, 0L, List.of());
    }
  }

  /** ★ C8：单笔 buyer→horse OWNER target 的只读交账读数。 */
  record HorseMove(
      HouseholdId buyer,
      HexCoord targetHex,
      HouseholdId target,
      long acquiredHorseMilli,
      long horseTransferredMilli,
      long migrants) {

    HorseMove {
      Objects.requireNonNull(buyer, "HorseMove.buyer");
      Objects.requireNonNull(targetHex, "HorseMove.targetHex");
      Objects.requireNonNull(target, "HorseMove.target");
      if (acquiredHorseMilli <= 0L || horseTransferredMilli <= 0L) {
        throw new IllegalArgumentException("HorseMove 的马匹数量必须 > 0");
      }
      if (migrants < 0L) {
        throw new IllegalArgumentException("HorseMove.migrants 不得为负");
      }
    }
  }

  /** ★ C8：一轮 S7-B 的完整交账读数（LAND 半边 + 马匹半边）。 */
  record UpwardMobilityResult(
      PostLandResult landResult,
      int horseBuyers,
      int horseTransfers,
      long horseAcquiredMilli,
      long horseTransferredMilli,
      long horseMigrants,
      long totalMigrants,
      List<HorseMove> horseMoves) {

    UpwardMobilityResult {
      Objects.requireNonNull(landResult, "UpwardMobilityResult.landResult");
      if (horseBuyers < 0
          || horseTransfers < 0
          || horseAcquiredMilli < 0L
          || horseTransferredMilli < 0L
          || horseMigrants < 0L
          || totalMigrants < 0L) {
        throw new IllegalArgumentException("UpwardMobilityResult 的计数/数量不得为负");
      }
      horseMoves = List.copyOf(horseMoves);
    }

    static UpwardMobilityResult empty() {
      return new UpwardMobilityResult(PostLandResult.empty(), 0, 0, 0L, 0L, 0L, 0L, List.of());
    }
  }

  private record PlannedMove(HexCoord hex, long acquiredLand, long migrants) {}

  private record HorsePlannedMove(long acquiredHorseMilli, long migrants) {}

  private record BuyerPlan(
      HouseholdId buyer,
      long population,
      List<PlannedMove> moves,
      HorsePlannedMove horse,
      HexCoord buyerCurrentHex) {}

  /**
   * C3 兼容入口：只处理本轮 LAND 市场取得。
   *
   * <p>C8 的真实生产路径必须调用 {@link #settle(SettlementDayContext, long, MarketReport)}，由后者把已交付马匹取得 与 LAND
   * 合并后走同一套最大余数拆分。
   */
  static PostLandResult settle(SettlementDayContext ctx, long day) {
    return settleInternal(ctx, day, Map.of()).landResult();
  }

  /**
   * ★★★ C8 生产入口：LAND + 已交付运输马匹的混合向上跃迁。
   *
   * <p>马匹取得只从 {@code report} 的即时已交付 fill 读取；跨区在途 shipment 不进入本轮。
   */
  static UpwardMobilityResult settle(SettlementDayContext ctx, long day, MarketReport report) {
    Objects.requireNonNull(ctx, "PostLandTradeClassCheck.settle 的 ctx 不得为 null");
    Objects.requireNonNull(report, "PostLandTradeClassCheck.settle 的 report 不得为 null");
    if (day != ctx.day) {
      throw new IllegalArgumentException(
          "PostLandTradeClassCheck.settle 的 day 必须等于 ctx.day: day=" + day + " ctx.day=" + ctx.day);
    }
    if (report.day() != day) {
      throw fail("post-upward-market-report-stale", "day=" + day + " reportDay=" + report.day());
    }
    Map<HouseholdId, TransportHorseAcquisitionBook.Acquisition> acquisitions =
        TransportHorseAcquisitionBook.deliveredMarketFills(
            report, ctx.ownershipClassAtMarketTradeStart);
    return settleInternal(ctx, day, acquisitions);
  }

  private static UpwardMobilityResult settleInternal(
      SettlementDayContext ctx,
      long day,
      Map<HouseholdId, TransportHorseAcquisitionBook.Acquisition> horseAcquisitions) {
    Objects.requireNonNull(ctx, "PostLandTradeClassCheck.settleInternal 的 ctx 不得为 null");
    Objects.requireNonNull(
        horseAcquisitions, "PostLandTradeClassCheck.settleInternal 的 horseAcquisitions 不得为 null");
    if (day != ctx.day) {
      throw new IllegalArgumentException(
          "PostLandTradeClassCheck.settle 的 day 必须等于 ctx.day: day=" + day + " ctx.day=" + ctx.day);
    }
    Map<LandHoldingKey, Long> acquiredLand =
        ctx.marketRound == null ? Map.of() : ctx.marketRound.landAcquiredQuantityThisRound();
    if (acquiredLand.isEmpty() && horseAcquisitions.isEmpty()) {
      return UpwardMobilityResult.empty();
    }

    LandLedgerState land =
        LandLedgerState.from(ctx.session.sheet().landArableSupply(), landHoldings(ctx));
    List<BuyerPlan> plans = planBuyers(ctx, land, acquiredLand, horseAcquisitions);
    if (plans.isEmpty()) {
      return UpwardMobilityResult.empty();
    }
    return execute(ctx, land, plans);
  }

  private static List<BuyerPlan> planBuyers(
      SettlementDayContext ctx,
      LandLedgerState land,
      Map<LandHoldingKey, Long> acquiredLand,
      Map<HouseholdId, TransportHorseAcquisitionBook.Acquisition> horseAcquisitions) {
    TreeMap<HouseholdId, TreeMap<HexCoord, Long>> landGrouped =
        new TreeMap<>(Comparator.comparing(HouseholdId::value));
    for (Map.Entry<LandHoldingKey, Long> entry : acquiredLand.entrySet()) {
      LandHoldingKey key = Objects.requireNonNull(entry.getKey(), "市场 LAND 取得的 key 不得为 null");
      Long quantity = Objects.requireNonNull(entry.getValue(), "市场 LAND 取得数量不得为 null");
      if (quantity <= 0L) {
        throw fail(
            "post-land-market-land-invalid",
            "buyer=" + key.owner().value() + " hex=" + key.hex() + " quantity=" + quantity);
      }
      landGrouped.computeIfAbsent(key.owner(), ignored -> new TreeMap<>()).put(key.hex(), quantity);
    }
    TreeSet<HouseholdId> buyers = new TreeSet<>(Comparator.comparing(HouseholdId::value));
    buyers.addAll(landGrouped.keySet());
    buyers.addAll(horseAcquisitions.keySet());

    List<BuyerPlan> plans = new ArrayList<>();
    for (HouseholdId buyer : buyers) {
      TreeMap<HexCoord, Long> acquiredByHex = landGrouped.getOrDefault(buyer, new TreeMap<>());
      TransportHorseAcquisitionBook.Acquisition horseAcquisition = horseAcquisitions.get(buyer);
      boolean hasLand = !acquiredByHex.isEmpty();
      boolean hasHorse = horseAcquisition != null;

      OwnershipClass buyerClass = ctx.ownershipClassAtMarketTradeStart.get(buyer);
      if (buyerClass == null) {
        throw fail(
            (hasLand ? "post-land" : "post-upward") + "-ownership-snapshot-missing",
            "buyer=" + buyer.value() + " day=" + ctx.day);
      }
      if (buyerClass != OwnershipClass.PROLETARIAN) {
        logSkipped(buyer, buyerClass);
        continue;
      }

      HouseholdEconomy sourceRow = ctx.householdEconomies.get(buyer);
      if (sourceRow == null) {
        throw fail(
            (hasLand ? "post-land" : "post-upward") + "-buyer-missing", "buyer=" + buyer.value());
      }
      long population = sourceRow.population();
      if (population <= 0L) {
        throw fail(
            (hasLand ? "post-land" : "post-upward") + "-buyer-no-population",
            "buyer=" + buyer.value() + " population=" + population);
      }

      long acquiredLandTotal = 0L;
      for (long quantity : acquiredByHex.values()) {
        acquiredLandTotal = Math.addExact(acquiredLandTotal, quantity);
      }

      long acquiredHorseMilli = 0L;
      if (hasHorse) {
        if (!CaravanTransportTemplate.TRANSPORT_HORSE.equals(horseAcquisition.commodity())) {
          throw fail(
              "post-upward-horse-commodity-mismatch",
              "buyer=" + buyer.value() + " commodity=" + horseAcquisition.commodity().value());
        }
        if (horseAcquisition.marketRoundId() != ctx.day) {
          throw fail(
              "post-upward-horse-market-round-mismatch",
              "buyer="
                  + buyer.value()
                  + " expectedRound="
                  + ctx.day
                  + " actualRound="
                  + horseAcquisition.marketRoundId());
        }
        acquiredHorseMilli = horseAcquisition.quantityMilli();
      }

      // ★ C3 唯一 LAND 吸收算式；C8 只消费，不复制 minLandPerPerson/wealth/speed 规则。
      long landCapacity =
          acquiredLandTotal > 0L
              ? LandUpwardMobility.landAbsorbableMigrants(
                  ctx, buyer, sourceRow.view().hex(), acquiredLandTotal, population)
              : 0L;
      // ★ C8 唯一马匹吸收算式；只作人口权重，不扣马。
      long horseCapacity =
          acquiredHorseMilli > 0L
              ? LandUpwardMobility.horseAbsorbableMigrants(acquiredHorseMilli, population)
              : 0L;
      long capacitySum = Math.addExact(landCapacity, horseCapacity);
      long totalMigrants =
          Math.min(
              Math.min(
                  Math.subtractExact(population, 1L),
                  LandUpwardMobility.migrationSpeedCap(population)),
              capacitySum);
      long[] shares =
          UpwardMobilityAllocation.largestRemainder(
              totalMigrants, new long[] {landCapacity, horseCapacity}, Comparator.naturalOrder());
      long landShare = shares[0];
      long horseShare = shares[1];

      Map<HexCoord, Long> migrantsByHex =
          acquiredLandTotal > 0L
              ? largestRemainderSplit(landShare, acquiredByHex, acquiredLandTotal)
              : Map.of();

      List<PlannedMove> moves = new ArrayList<>();
      for (Map.Entry<HexCoord, Long> acquired : acquiredByHex.entrySet()) {
        HexCoord hex = acquired.getKey();
        long quantity = acquired.getValue();
        long holding = land.holdingOf(hex, buyer);
        if (holding < quantity) {
          throw fail(
              "post-land-insufficient-source-land",
              "buyer="
                  + buyer.value()
                  + " hex="
                  + hex
                  + " required="
                  + quantity
                  + " holding="
                  + holding);
        }
        moves.add(new PlannedMove(hex, quantity, migrantsByHex.getOrDefault(hex, 0L)));
      }

      HexCoord buyerCurrentHex = null;
      HorsePlannedMove horsePlannedMove = null;
      if (hasHorse) {
        buyerCurrentHex = ctx.homeHexAtMarketTradeStart.get(buyer);
        if (buyerCurrentHex == null) {
          throw fail(
              "post-upward-home-hex-snapshot-missing",
              "buyer=" + buyer.value() + " day=" + ctx.day);
        }
        horsePlannedMove = new HorsePlannedMove(acquiredHorseMilli, horseShare);
      }
      if (!moves.isEmpty() || horsePlannedMove != null) {
        plans.add(
            new BuyerPlan(
                buyer, population, List.copyOf(moves), horsePlannedMove, buyerCurrentHex));
      }
      if (LOG.isDebugEnabled()) {
        EventLog.channel(LOG)
            .debug(
                LogEvent.of(
                    "POST_UPWARD_CAPACITY",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    ctx.day,
                    "buyer",
                    buyer.value(),
                    "population",
                    population,
                    "landCapacity",
                    landCapacity,
                    "horseCapacity",
                    horseCapacity,
                    "totalMigrants",
                    totalMigrants,
                    "landShare",
                    landShare,
                    "horseShare",
                    horseShare,
                    "acquiredLandMilliMu",
                    acquiredLandTotal,
                    "acquiredHorseMilli",
                    acquiredHorseMilli,
                    "reason",
                    "largest-remainder-split-land-first-tie-break"));
      }
    }
    return List.copyOf(plans);
  }

  private static UpwardMobilityResult execute(
      SettlementDayContext ctx, LandLedgerState initialLand, List<BuyerPlan> plans) {
    long totalLandBefore = totalLand(initialLand);
    LandLedgerState land = initialLand;
    LandUpwardMobility.RunState state = new LandUpwardMobility.RunState();
    List<TargetMove> targets = new ArrayList<>();
    List<HorseMove> horseMoves = new ArrayList<>();
    int landBuyers = 0;
    int landHexes = 0;
    int landTransfers = 0;
    int horseBuyers = 0;
    int horseTransfers = 0;
    int ordinal = 0;
    long landMigrants = 0L;
    long horseMigrants = 0L;
    long horseAcquired = 0L;
    long horseTransferred = 0L;
    long totalLandTransferred = 0L;

    for (BuyerPlan plan : plans) {
      if (!plan.moves().isEmpty()) {
        landBuyers++;
      }
      long sourcePopulationRemaining = plan.population();
      long plannedPopulationAfter = plan.population();
      for (PlannedMove move : plan.moves()) {
        LandUpwardMobility.StepResult step =
            LandUpwardMobility.applyLandStep(
                ctx,
                land,
                state,
                new LandUpwardMobility.StepRequest(
                    plan.buyer(),
                    move.hex(),
                    move.acquiredLand(),
                    move.migrants(),
                    sourcePopulationRemaining,
                    POST_LAND_CLASS_TRANSITION_REASON),
                ordinal,
                Set.of(CaravanTransportTemplate.TRANSPORT_HORSE));
        land = step.land();
        sourcePopulationRemaining = step.sourcePopulationAfter();
        plannedPopulationAfter = Math.subtractExact(plannedPopulationAfter, move.migrants());
        landHexes = Math.addExact(landHexes, 1);
        totalLandTransferred = Math.addExact(totalLandTransferred, step.landTransferred());
        if (step.populationTransferred()) {
          ordinal = Math.addExact(ordinal, 1);
          landTransfers = Math.addExact(landTransfers, 1);
          landMigrants = Math.addExact(landMigrants, step.migrants());
        }
        targets.add(
            new TargetMove(
                plan.buyer(),
                move.hex(),
                step.target().household(),
                step.landTransferred(),
                step.migrants()));
      }

      if (plan.horse() != null) {
        horseBuyers++;
        LandUpwardMobility.HorseStepResult horseStep =
            LandUpwardMobility.applyHorseStep(
                ctx,
                state,
                new LandUpwardMobility.HorseStepRequest(
                    plan.buyer(),
                    plan.buyerCurrentHex(),
                    plan.horse().acquiredHorseMilli(),
                    plan.horse().migrants(),
                    sourcePopulationRemaining,
                    POST_LAND_CLASS_TRANSITION_REASON),
                ordinal);
        sourcePopulationRemaining = horseStep.sourcePopulationAfter();
        plannedPopulationAfter =
            Math.subtractExact(plannedPopulationAfter, plan.horse().migrants());
        horseAcquired = Math.addExact(horseAcquired, horseStep.acquiredHorseMilli());
        horseTransferred = Math.addExact(horseTransferred, horseStep.horseTransferredMilli());
        if (horseStep.populationTransferred()) {
          ordinal = Math.addExact(ordinal, 1);
          horseTransfers = Math.addExact(horseTransfers, 1);
          horseMigrants = Math.addExact(horseMigrants, horseStep.migrants());
        }
        horseMoves.add(
            new HorseMove(
                plan.buyer(),
                plan.buyerCurrentHex(),
                horseStep.target().household(),
                horseStep.acquiredHorseMilli(),
                horseStep.horseTransferredMilli(),
                horseStep.migrants()));
      }
      if (sourcePopulationRemaining != plannedPopulationAfter) {
        throw fail(
            "post-upward-source-population-not-conserved",
            "buyer="
                + plan.buyer().value()
                + " remaining="
                + sourcePopulationRemaining
                + " expected="
                + plannedPopulationAfter);
      }
    }

    long totalLandAfter = totalLand(land);
    if (totalLandAfter != totalLandBefore) {
      throw fail(
          "post-land-land-total-not-conserved",
          "before=" + totalLandBefore + " after=" + totalLandAfter);
    }
    PostLandResult landResult =
        new PostLandResult(
            landBuyers, landHexes, landTransfers, landMigrants, totalLandTransferred, targets);
    if (LOG.isInfoEnabled()) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "POST_LAND_CLASS_CHECK_ROUND",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  ctx.day,
                  "buyers",
                  landBuyers,
                  "hexes",
                  landHexes,
                  "transfers",
                  landTransfers,
                  "migrants",
                  landMigrants,
                  "landTransferred",
                  totalLandTransferred,
                  "landConserved",
                  totalLandAfter == totalLandBefore));
      if (horseBuyers > 0) {
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "POST_UPWARD_MOBILITY_ROUND",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    ctx.day,
                    "buyers",
                    plans.size(),
                    "horseBuyers",
                    horseBuyers,
                    "horseTransfers",
                    horseTransfers,
                    "landMigrants",
                    landMigrants,
                    "horseMigrants",
                    horseMigrants,
                    "totalMigrants",
                    Math.addExact(landMigrants, horseMigrants),
                    "horseAcquiredMilli",
                    horseAcquired,
                    "horseTransferredMilli",
                    horseTransferred,
                    "reason",
                    "land-first-then-horse-same-source-decrementing"));
      }
    }
    return new UpwardMobilityResult(
        landResult,
        horseBuyers,
        horseTransfers,
        horseAcquired,
        horseTransferred,
        horseMigrants,
        Math.addExact(landMigrants, horseMigrants),
        horseMoves);
  }

  /** 按 acquired LAND 权重做最大余数法拆分 migrants（稳定 hex 序；共用 C8 的唯一拆分实现）。 */
  private static Map<HexCoord, Long> largestRemainderSplit(
      long migrants, Map<HexCoord, Long> acquiredByHex, long acquiredTotal) {
    TreeMap<HexCoord, Long> split = new TreeMap<>();
    for (HexCoord hex : acquiredByHex.keySet()) {
      split.put(hex, 0L);
    }
    if (migrants == 0L) {
      return split;
    }
    if (acquiredTotal <= 0L) {
      throw fail("post-land-market-land-invalid", "acquiredTotal=" + acquiredTotal);
    }
    List<Map.Entry<HexCoord, Long>> ordered = new ArrayList<>(acquiredByHex.entrySet());
    ordered.sort(Map.Entry.comparingByKey());
    long[] weights = new long[ordered.size()];
    for (int index = 0; index < ordered.size(); index++) {
      weights[index] = ordered.get(index).getValue();
    }
    long[] shares =
        UpwardMobilityAllocation.largestRemainder(migrants, weights, Comparator.naturalOrder());
    for (int index = 0; index < ordered.size(); index++) {
      split.put(ordered.get(index).getKey(), shares[index]);
    }
    long sum = 0L;
    for (long share : split.values()) {
      sum = Math.addExact(sum, share);
    }
    if (sum != migrants) {
      throw fail(
          "post-land-migrant-split-not-conserved", "expected=" + migrants + " actual=" + sum);
    }
    return split;
  }

  /**
   * ★★★ <b>RC8d：S7-B / S7-C 与 M3 无地买盘共用的 {@code minLandPerPersonMilliMu} 唯一口径。</b>
   *
   * <pre>
   * netGrainPerMuMilli = outputPerUnit[GRAIN] × 1000 − inputPerUnit[GRAIN]
   * requiredGrainPerPersonPerCycleMilli = ceil(naturalNeeds[GRAIN] × cycleDays / population)
   * minLandPerPersonMilliMu = ceil(requiredGrainPerPersonPerCycleMilli × 1000 / netGrainPerMuMilli)
   * </pre>
   *
   * <p>★ 所有输入都是当刻事实：{@code naturalNeeds[GRAIN]} 由 Social 注入；配方与周期来自该 hex 的 LAND 型产业定义。 缺 grain 配方、缺
   * cycleDays、need ≤ 0、population ≤ 0 或净产分母 ≤ 0 ⇒ fail-closed；绝不回退旧的 2667 假装成功。
   */
  static long minLandPerPersonMilliMu(SettlementDayContext ctx, HouseholdId buyer, HexCoord hex) {
    Objects.requireNonNull(ctx, "minLandPerPersonMilliMu 的 ctx 不得为 null");
    Objects.requireNonNull(buyer, "minLandPerPersonMilliMu 的 buyer 不得为 null");
    Objects.requireNonNull(hex, "minLandPerPersonMilliMu 的 hex 不得为 null");
    HouseholdEconomy row = ctx.householdEconomies.get(buyer);
    if (row == null) {
      throw fail("post-land-buyer-missing", "buyer=" + buyer.value());
    }
    long population = row.population();
    if (population <= 0L) {
      throw fail(
          "post-land-buyer-no-population", "buyer=" + buyer.value() + " population=" + population);
    }
    long grainNaturalNeed = row.naturalNeeds().getOrDefault(EconomySettlement.GRAIN, 0L);
    if (grainNaturalNeed <= 0L) {
      throw fail(
          "post-land-grain-natural-need-missing",
          "buyer="
              + buyer.value()
              + " hex="
              + hex
              + " population="
              + population
              + " naturalNeedsGrain="
              + grainNaturalNeed);
    }

    IndustryDefinitionView industry = grainLandIndustryAt(ctx, hex);
    if (industry == null) {
      throw fail(
          "post-land-grain-industry-definition-missing",
          "hex=" + hex + " buyer=" + buyer.value() + " reason=no-land-based-industry-at-hex");
    }
    Long outputPerUnit = industry.recipe().outputPerUnit().get(EconomySettlement.GRAIN);
    Long inputPerUnit = industry.recipe().inputPerUnit().get(EconomySettlement.GRAIN);
    if (outputPerUnit == null || outputPerUnit <= 0L) {
      throw fail(
          "post-land-grain-output-definition-missing",
          "hex=" + hex + " industry=" + industry.id().value() + " outputPerUnit=" + outputPerUnit);
    }
    if (inputPerUnit == null) {
      throw fail(
          "post-land-grain-input-definition-missing",
          "hex=" + hex + " industry=" + industry.id().value() + " inputPerUnit=missing");
    }
    long cycleDays = industry.cycleDays();
    if (cycleDays <= 0L) {
      throw fail(
          "post-land-cycle-days-invalid",
          "hex=" + hex + " industry=" + industry.id().value() + " cycleDays=" + cycleDays);
    }

    long netGrainPerMuMilli;
    try {
      netGrainPerMuMilli =
          Math.subtractExact(
              Math.multiplyExact(outputPerUnit, MILLI_PER_COMMODITY_UNIT), inputPerUnit);
    } catch (ArithmeticException overflow) {
      throw fail(
          "post-land-net-grain-overflow",
          "hex=" + hex + " outputPerUnit=" + outputPerUnit + " inputPerUnit=" + inputPerUnit);
    }
    if (netGrainPerMuMilli <= 0L) {
      throw fail(
          "post-land-net-grain-nonpositive",
          "hex="
              + hex
              + " industry="
              + industry.id().value()
              + " netGrainPerMuMilli="
              + netGrainPerMuMilli);
    }

    BigInteger requiredPerPersonPerCycle =
        ceilDivide(
            BigInteger.valueOf(grainNaturalNeed).multiply(BigInteger.valueOf(cycleDays)),
            BigInteger.valueOf(population));
    if (requiredPerPersonPerCycle.signum() <= 0) {
      throw fail(
          "post-land-required-grain-nonpositive",
          "buyer=" + buyer.value() + " hex=" + hex + " required=" + requiredPerPersonPerCycle);
    }
    long requiredGrainPerPersonPerCycleMilli = requiredPerPersonPerCycle.longValueExact();
    long minLand =
        ceilDivide(
                BigInteger.valueOf(requiredGrainPerPersonPerCycleMilli)
                    .multiply(BigInteger.valueOf(MILLI_MU_PER_MU)),
                BigInteger.valueOf(netGrainPerMuMilli))
            .longValueExact();
    if (minLand <= 0L) {
      throw fail(
          "post-land-min-land-nonpositive",
          "buyer=" + buyer.value() + " hex=" + hex + " minLandPerPersonMilliMu=" + minLand);
    }
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "POST_LAND_MIN_LAND_PER_PERSON",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  ctx.day,
                  "buyer",
                  buyer.value(),
                  "hex",
                  hex.toString(),
                  "industry",
                  industry.id().value(),
                  "population",
                  population,
                  "naturalNeedsGrainMilli",
                  grainNaturalNeed,
                  "outputPerUnitGrain",
                  outputPerUnit,
                  "inputPerUnitGrainMilli",
                  inputPerUnit,
                  "cycleDays",
                  cycleDays,
                  "netGrainPerMuMilli",
                  netGrainPerMuMilli,
                  "requiredGrainPerPersonPerCycleMilli",
                  requiredGrainPerPersonPerCycleMilli,
                  "minLandPerPersonMilliMu",
                  minLand));
    }
    return minLand;
  }

  /**
   * ★ RC8d：该 hex 的 LAND 型 grain 产业定义；同 hex 多个候选时按 IndustryId 稳定序优先含 grain 产出的那个。
   *
   * <p>返回 null = 该 hex 没有 LAND 型产业（调用方 fail-closed）；有 LAND 型产业但缺 grain 产出时返回该产业，由调用方按 「缺 grain
   * 配方」具名拒绝。
   */
  static IndustryDefinitionView grainLandIndustryAt(SettlementDayContext ctx, HexCoord hex) {
    if (ctx == null || ctx.hexToIndustries == null || hex == null || ctx.industries == null) {
      return null;
    }
    List<IndustryId> industryIds =
        new ArrayList<>(
            ctx.hexToIndustries.getOrDefault(IndustryHexKeys.hexKey(hex.q(), hex.r()), List.of()));
    industryIds.sort(Comparator.comparing(IndustryId::value));
    IndustryDefinitionView firstLandBased = null;
    for (IndustryId industryId : industryIds) {
      IndustryDefinitionView industry = ctx.industries.get(industryId);
      if (industry == null || !ProductionPlanBridge.isLandBased(industry)) {
        continue;
      }
      if (firstLandBased == null) {
        firstLandBased = industry;
      }
      if (industry.recipe().outputPerUnit().containsKey(EconomySettlement.GRAIN)) {
        return industry;
      }
    }
    return firstLandBased;
  }

  /** 非负整数的 ceil(a / b)，b &gt; 0；用 BigInteger 防止乘法/加法溢出。 */
  private static BigInteger ceilDivide(BigInteger numerator, BigInteger denominator) {
    if (numerator.signum() < 0 || denominator.signum() <= 0) {
      throw new IllegalArgumentException(
          "ceilDivide 要求 numerator ≥ 0 且 denominator > 0: numerator="
              + numerator
              + " denominator="
              + denominator);
    }
    BigInteger[] quotientAndRemainder = numerator.divideAndRemainder(denominator);
    return quotientAndRemainder[1].signum() == 0
        ? quotientAndRemainder[0]
        : quotientAndRemainder[0].add(BigInteger.ONE);
  }

  private static Map<LandHoldingKey, LandHolding> landHoldings(SettlementDayContext ctx) {
    return ctx.session.sheet().landHoldings();
  }

  private static long totalLand(LandLedgerState land) {
    long total = 0L;
    for (LandHolding holding : land.holdingsByKey().values()) {
      total = Math.addExact(total, holding.quantityMilliMu());
    }
    return total;
  }

  private static void logSkipped(HouseholdId buyer, OwnershipClass ownershipClass) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "POST_LAND_CLASS_CHECK_SKIPPED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "buyer",
                buyer.value(),
                "ownershipClass",
                ownershipClass == null ? "missing" : ownershipClass.name(),
                "reason",
                "not-proletarian-at-market-trade-start"));
  }

  private static IllegalStateException fail(String reason, String detail) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "POST_LAND_CLASS_CHECK_FAILED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "reason",
                reason,
                "detail",
                detail));
    return new IllegalStateException(reason + ": " + detail);
  }
}

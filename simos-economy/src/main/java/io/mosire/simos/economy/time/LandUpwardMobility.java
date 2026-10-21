package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.culture.HouseholdCultureKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.api.id.TransferId;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.api.land.LandTransfer;
import io.mosire.simos.economy.api.transfer.Transfer;
import io.mosire.simos.economy.api.transfer.TransferReason;
import io.mosire.simos.economy.land.LandLedgerState;
import io.mosire.simos.economy.model.CaravanTransportTemplate;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★★ <b>D-032 C3 / S7-B：LAND 向上跃迁的唯一可复用 primitive（C8 复用入口）。</b>
 *
 * <p>★ <b>父运行序列锚点</b>：{@code
 * docs/superpowers/specs/2026-10-21-d032-carrier-household-transport-capacity-design.md} §3.2 S7-B
 * 与 §8.1/§8.2。 本类只承担“目标选择 → LAND 转移 → 人口/钱货债/经验迁移”这一条 LAND 腿；市场撮合、M2 债偿、马匹分支均不在本类。
 *
 * <pre>
 * resolveOwnerTarget(ctx, buyer, hex)
 *   同 hex 的当刻 OWNER 候选（排除 buyer，不看 productionMode/currentPosition/classStanding）
 *     排序 = population desc → 本 hex LAND desc → HouseholdId.value asc
 *     同 cultureKey 优先；没有同 culture 时取确定性 fallback（0 人口 OWNER 也允许）
 *   完全没有 OWNER ⇒ 建确定性 0 人口 OWNER 壳（空账户、无 mode/position/classStanding）
 *
 * landAbsorbableMigrants(ctx, buyer, sourceHex, acquiredLand, population)
 *   minLandPerPerson = PostLandTradeClassCheck.minLandPerPersonMilliMu（唯一口径）
 *   migrants = min(acquiredLand / minLandPerPerson, maxBySpeed, maxByWealth, population - 1)
 *
 * applyLandStep(ctx, land, state, request, ordinal)
 *   LAND 只走 LandLedgerState/LandBook；转移后 buyer 在该 hex LAND=0 且 market marker 清除；
 *   migrants &gt; 0 才调 HouseholdTransferSettlement.apply（targetMode = Optional.empty()）；
 *   targetIsNew 只对“本轮本类新建、且尚未在 outbox 宣告过”的壳为 true。
 * </pre>
 *
 * <p>★★ <b>C8 复用义务</b>：马匹分支不得复制本类的 LAND 目标选择/吸收人数/转移算式；C8 只调用 {@link #resolveOwnerTarget}、{@link
 * #landAbsorbableMigrants}、{@link #applyLandStep}，并在此基础上增加马匹分支、 LAND/马匹最大余数混拆与两目标顺序。
 *
 * <p>★★ <b>边界</b>：不写商品/assetShares 第二本 LAND 账；不写 householdEconomies.population/laborMilli；不新建持久组件；
 * 不伪造 mode/position。
 */
final class LandUpwardMobility {

  private static final org.slf4j.Logger LOG = EconomyLog.migration();

  /** 毫商品 / 整商品单位（与 MarketTable 价格量纲一致）。 */
  private static final long MILLI_PER_COMMODITY_UNIT = 1000L;

  /** 千分亩 / 整亩（与 LAND 数量量纲一致）。 */
  private static final long MILLI_MU_PER_MU = 1000L;

  /** 每市场轮最多迁移的人口比例（‰）。沿用 C3 前既有口径。 */
  static final long MAX_MIGRATION_PER_MILLE = 100L;

  /**
   * ★★ C8：每名车夫占用的运输马匹数量（毫商品）。1 匹马 = 1,000 毫；本批按“一马一车夫”作为唯一工程口径。
   *
   * <p>它只服务向上跃迁的马匹吸收人数，不扣马、不建第二本马账；运输生产规模仍按 {@link
   * CaravanTransportTemplate#HORSE_MILLI_PER_SCALE_UNIT} 的同量纲读取。
   */
  static final long HORSE_MILLI_PER_DRIVER = CaravanTransportTemplate.HORSE_MILLI_PER_SCALE_UNIT;

  private LandUpwardMobility() {}

  /** 每市场轮人口速度上限（现有 `max(1, P × 100‰)` 口径的唯一实现）。 */
  static long migrationSpeedCap(long population) {
    if (population <= 0L) {
      throw new IllegalArgumentException("migrationSpeedCap.population 必须 > 0");
    }
    return Math.max(1L, Math.multiplyExact(population, MAX_MIGRATION_PER_MILLE) / 1000L);
  }

  /** 目标选择的结果：target 身份 + 是否本轮新建壳 + 是否同 culture。 */
  record OwnerTarget(
      HouseholdId household,
      boolean createdShell,
      boolean cultureMatched,
      HouseholdCultureKey cultureKey) {

    OwnerTarget {
      Objects.requireNonNull(household, "OwnerTarget.household");
      Objects.requireNonNull(cultureKey, "OwnerTarget.cultureKey");
    }
  }

  /** 一笔 LAND 跃迁步骤请求（不可变）。 */
  record StepRequest(
      HouseholdId buyer,
      HexCoord hex,
      long acquiredLandMilliMu,
      long migrants,
      long sourcePopulationBefore,
      String reason) {

    StepRequest {
      Objects.requireNonNull(buyer, "StepRequest.buyer");
      Objects.requireNonNull(hex, "StepRequest.hex");
      Objects.requireNonNull(reason, "StepRequest.reason");
      if (acquiredLandMilliMu <= 0L) {
        throw new IllegalArgumentException("StepRequest.acquiredLandMilliMu 必须 > 0");
      }
      if (migrants < 0L) {
        throw new IllegalArgumentException("StepRequest.migrants 不得为负");
      }
      if (sourcePopulationBefore <= 0L) {
        throw new IllegalArgumentException("StepRequest.sourcePopulationBefore 必须 > 0");
      }
      if (migrants > sourcePopulationBefore) {
        throw new IllegalArgumentException(
            "StepRequest.migrants 不得超过 sourcePopulationBefore: migrants="
                + migrants
                + " sourcePopulationBefore="
                + sourcePopulationBefore);
      }
    }
  }

  /** 一笔 LAND 跃迁步骤结果。 */
  record StepResult(
      LandLedgerState land,
      OwnerTarget target,
      long landTransferred,
      long migrants,
      long sourcePopulationAfter,
      boolean populationTransferred) {

    StepResult {
      Objects.requireNonNull(land, "StepResult.land");
      Objects.requireNonNull(target, "StepResult.target");
    }
  }

  /** ★ C8：一笔运输马匹跃迁步骤请求。 */
  record HorseStepRequest(
      HouseholdId buyer,
      HexCoord buyerCurrentHex,
      long acquiredHorseMilli,
      long migrants,
      long sourcePopulationBefore,
      String reason) {

    HorseStepRequest {
      Objects.requireNonNull(buyer, "HorseStepRequest.buyer");
      Objects.requireNonNull(buyerCurrentHex, "HorseStepRequest.buyerCurrentHex");
      Objects.requireNonNull(reason, "HorseStepRequest.reason");
      if (acquiredHorseMilli <= 0L) {
        throw new IllegalArgumentException("HorseStepRequest.acquiredHorseMilli 必须 > 0");
      }
      if (migrants < 0L) {
        throw new IllegalArgumentException("HorseStepRequest.migrants 不得为负");
      }
      if (sourcePopulationBefore <= 0L) {
        throw new IllegalArgumentException("HorseStepRequest.sourcePopulationBefore 必须 > 0");
      }
      if (migrants > sourcePopulationBefore) {
        throw new IllegalArgumentException(
            "HorseStepRequest.migrants 不得超过 sourcePopulationBefore: migrants="
                + migrants
                + " sourcePopulationBefore="
                + sourcePopulationBefore);
      }
    }
  }

  /** ★ C8：一笔运输马匹跃迁步骤结果。 */
  record HorseStepResult(
      OwnerTarget target,
      long acquiredHorseMilli,
      long horseTransferredMilli,
      long migrants,
      long sourcePopulationAfter,
      boolean populationTransferred) {

    HorseStepResult {
      Objects.requireNonNull(target, "HorseStepResult.target");
      if (acquiredHorseMilli <= 0L || horseTransferredMilli <= 0L) {
        throw new IllegalArgumentException("HorseStepResult 的马匹数量必须 > 0");
      }
      if (migrants < 0L) {
        throw new IllegalArgumentException("HorseStepResult.migrants 不得为负");
      }
    }
  }

  /**
   * ★★ 一次 S7-B 运行内的跨步骤投影（瞬态）。
   *
   * <p>同一源户多笔、或多个买方同轮指向同一 target 时，target 的“迁移前人口”读本投影而不是初值；新建壳的 outbox CREATE_HOUSEHOLD
   * 只在第一次真正迁入时宣告一次。
   */
  static final class RunState {

    private final Map<HouseholdId, Long> projectedTargetPopulation = new LinkedHashMap<>();
    private final Set<HouseholdId> createdShells = new LinkedHashSet<>();
    private final Set<HouseholdId> announcedShells = new LinkedHashSet<>();

    long targetPopulationBefore(SettlementDayContext ctx, HouseholdId target) {
      Long projected = projectedTargetPopulation.get(target);
      if (projected != null) {
        return projected;
      }
      HouseholdEconomy row = ctx.householdEconomies.get(target);
      if (row == null) {
        throw fail("post-land-target-missing", "target=" + target.value());
      }
      return row.population();
    }

    boolean targetIsNew(HouseholdId target) {
      return createdShells.contains(target) && !announcedShells.contains(target);
    }

    void recordCreatedShell(HouseholdId target) {
      createdShells.add(target);
    }

    void recordPopulationTransfer(HouseholdId target, long targetPopulationBefore, long migrants) {
      projectedTargetPopulation.put(target, Math.addExact(targetPopulationBefore, migrants));
      announcedShells.add(target);
    }
  }

  /**
   * 选同 hex 的 OWNER target；没有 OWNER 时建确定性 0 人口 OWNER 壳。
   *
   * <p>候选判据 = {@link OwnershipClassBook#classOf(SettlementDayContext, HouseholdId)} 当刻资产派生 OWNER +
   * 同 hex + 非 buyer。不要求 productionMode/currentPosition/classStanding。
   */
  static OwnerTarget resolveOwnerTarget(
      SettlementDayContext ctx, HouseholdId buyer, HexCoord hex, RunState state) {
    Objects.requireNonNull(ctx, "resolveOwnerTarget.ctx");
    Objects.requireNonNull(buyer, "resolveOwnerTarget.buyer");
    Objects.requireNonNull(hex, "resolveOwnerTarget.hex");
    Objects.requireNonNull(state, "resolveOwnerTarget.state");

    HouseholdCultureKey buyerCulture = cultureKeyOf(ctx, buyer);
    List<Candidate> candidates = new ArrayList<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> entry : ctx.householdEconomies.entrySet()) {
      HouseholdId candidate = entry.getKey();
      HouseholdEconomy row = entry.getValue();
      if (row == null || candidate.equals(buyer) || !hex.equals(row.view().hex())) {
        continue;
      }
      if (OwnershipClassBook.classOf(ctx, candidate) != OwnershipClass.OWNER) {
        continue;
      }
      candidates.add(
          new Candidate(
              candidate,
              row.population(),
              landAtHex(ctx, hex, candidate),
              cultureKeyOf(ctx, candidate)));
    }

    if (candidates.isEmpty()) {
      return createOwnerShell(ctx, buyer, hex, state);
    }

    List<Candidate> sameCulture = new ArrayList<>();
    for (Candidate candidate : candidates) {
      if (candidate.cultureKey().equals(buyerCulture)) {
        sameCulture.add(candidate);
      }
    }
    List<Candidate> pool = sameCulture.isEmpty() ? candidates : sameCulture;
    pool.sort(TARGET_SURVIVOR_ORDER);
    Candidate chosen = pool.get(0);
    if (!sameCulture.isEmpty()) {
      return new OwnerTarget(chosen.household(), false, true, chosen.cultureKey());
    }
    if (LOG.isInfoEnabled()) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "POST_LAND_CULTURE_MISMATCH_DIRECT_MERGE",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  ctx.day,
                  "buyer",
                  buyer.value(),
                  "target",
                  chosen.household().value(),
                  "hex",
                  hex.toString(),
                  "buyerCultureKey",
                  buyerCulture,
                  "targetCultureKey",
                  chosen.cultureKey(),
                  "reason",
                  "no-same-culture-owner-target-select-deterministic-first"));
    }
    return new OwnerTarget(chosen.household(), false, false, chosen.cultureKey());
  }

  /**
   * ★ C3/C8 唯一 LAND 吸收人数算式（C8 计算 landCapacity 时只能调本方法）。
   *
   * <pre>
   * minLandPerPerson = PostLandTradeClassCheck.minLandPerPersonMilliMu(ctx, buyer, sourceHex)
   * maxByLand   = acquiredLandMilliMu / minLandPerPerson
   * maxBySpeed  = max(1, population × 100 / 1000)   // 现有口径：至少 1 人/轮
   * maxByWealth = floor(landlessWealthMilli / ceil(minLandPerPerson × landPrice / 1000))
   * migrants    = min(maxByLand, maxBySpeed, maxByWealth, population - 1)
   * </pre>
   */
  static long landAbsorbableMigrants(
      SettlementDayContext ctx,
      HouseholdId buyer,
      HexCoord sourceHex,
      long acquiredLandMilliMu,
      long population) {
    Objects.requireNonNull(ctx, "landAbsorbableMigrants.ctx");
    Objects.requireNonNull(buyer, "landAbsorbableMigrants.buyer");
    Objects.requireNonNull(sourceHex, "landAbsorbableMigrants.sourceHex");
    if (acquiredLandMilliMu < 0L) {
      throw new IllegalArgumentException("landAbsorbableMigrants.acquiredLandMilliMu 不得为负");
    }
    if (population <= 0L) {
      throw new IllegalArgumentException("landAbsorbableMigrants.population 必须 > 0");
    }
    long minLandPerPerson = PostLandTradeClassCheck.minLandPerPersonMilliMu(ctx, buyer, sourceHex);
    long maxByLand = acquiredLandMilliMu / minLandPerPerson;
    long maxBySpeed = migrationSpeedCap(population);
    MarketTable localMarket = MarketIndex.of(ctx.markets, ctx.base.marketZones()).at(sourceHex);
    long maxByWealth = maxByWealth(ctx, buyer, minLandPerPerson, localMarket);
    return Math.min(Math.min(maxByLand, maxBySpeed), Math.min(maxByWealth, population - 1L));
  }

  /**
   * ★ C8：马匹侧吸收人数唯一算式。
   *
   * <pre>
   * horseCapacity = min(floor(acquiredHorseMilli / HORSE_MILLI_PER_DRIVER),
   *                    migrationSpeedCap(population), population - 1)
   * </pre>
   *
   * <p>马匹不设财富门（任务书 §3.3 的公式）；只作为吸收人数权重，不扣马、不折旧。
   */
  static long horseAbsorbableMigrants(long acquiredHorseMilli, long population) {
    if (acquiredHorseMilli < 0L) {
      throw new IllegalArgumentException("horseAbsorbableMigrants.acquiredHorseMilli 不得为负");
    }
    if (population <= 0L) {
      throw new IllegalArgumentException("horseAbsorbableMigrants.population 必须 > 0");
    }
    long maxByHorse = acquiredHorseMilli / HORSE_MILLI_PER_DRIVER;
    return Math.min(maxByHorse, Math.min(migrationSpeedCap(population), population - 1L));
  }

  /**
   * ★★★ <b>C8：运输马匹向上跃迁的唯一入口</b>：resolve buyer 当前 hex 的 OWNER target → 可选人口/钱货债/经验迁移 → 从 buyer
   * 的普通库存全量转出 {@code transport_horse}。
   *
   * <p>★ 先做库存充足预检，再做任何写；目标复用 {@link #resolveOwnerTarget}（同 hex/culture/0 人口 OWNER 壳）。 马匹只走 {@code
   * householdGoods}，不建 AssetKind.HORSE/OwnershipStake、不扣、不折旧。
   */
  static HorseStepResult applyHorseStep(
      SettlementDayContext ctx, RunState state, HorseStepRequest request, int ordinal) {
    Objects.requireNonNull(ctx, "applyHorseStep.ctx");
    Objects.requireNonNull(state, "applyHorseStep.state");
    Objects.requireNonNull(request, "applyHorseStep.request");
    if (ordinal < 0) {
      throw new IllegalArgumentException("applyHorseStep.ordinal 不得为负");
    }
    HouseholdId buyer = request.buyer();
    HexCoord targetHex = request.buyerCurrentHex();
    long horseBefore = horseInventoryOf(ctx, buyer);
    if (horseBefore < request.acquiredHorseMilli()) {
      throw fail(
          "post-upward-horse-insufficient-source-inventory",
          "buyer="
              + buyer.value()
              + " required="
              + request.acquiredHorseMilli()
              + " holding="
              + horseBefore);
    }
    if (ctx.accounts.accountOrNull(buyer) == null) {
      throw fail("post-upward-horse-source-account-missing", "buyer=" + buyer.value());
    }

    OwnerTarget target = resolveOwnerTarget(ctx, buyer, targetHex, state);
    if (buyer.equals(target.household())) {
      throw fail("post-upward-horse-self-target", "buyer=" + buyer.value());
    }
    if (ctx.accounts.accountOrNull(target.household()) == null) {
      throw fail(
          "post-upward-horse-target-account-missing",
          "buyer=" + buyer.value() + " target=" + target.household().value());
    }

    long sourcePopulationAfter = request.sourcePopulationBefore();
    boolean populationTransferred = false;
    if (request.migrants() > 0L) {
      HouseholdEconomy targetRow = ctx.householdEconomies.get(target.household());
      if (targetRow == null) {
        throw fail(
            "post-upward-horse-target-missing",
            "buyer=" + buyer.value() + " target=" + target.household().value());
      }
      long targetPopulationBefore = state.targetPopulationBefore(ctx, target.household());
      boolean targetIsNew = state.targetIsNew(target.household());
      HouseholdTransferSettlement.apply(
          ctx.session,
          ctx.accounts,
          ctx.ledger,
          ctx.day,
          ordinal,
          new HouseholdTransferSettlement.HouseholdTransferRequest(
              buyer,
              target.household(),
              request.migrants(),
              targetHex,
              Optional.empty(),
              request.reason(),
              targetIsNew),
          request.sourcePopulationBefore(),
          targetPopulationBefore,
          Set.of(CaravanTransportTemplate.TRANSPORT_HORSE));
      state.recordPopulationTransfer(
          target.household(), targetPopulationBefore, request.migrants());
      sourcePopulationAfter =
          Math.subtractExact(request.sourcePopulationBefore(), request.migrants());
      populationTransferred = true;
    }

    long horseTransferred =
        transferAllTransportHorses(ctx, buyer, target.household(), targetHex, ordinal);
    if (horseInventoryOf(ctx, buyer) != 0L) {
      throw fail(
          "post-upward-horse-source-residual",
          "buyer=" + buyer.value() + " residual=" + horseInventoryOf(ctx, buyer));
    }
    logHorseStep(ctx, request, target, horseTransferred, sourcePopulationAfter);
    return new HorseStepResult(
        target,
        request.acquiredHorseMilli(),
        horseTransferred,
        request.migrants(),
        sourcePopulationAfter,
        populationTransferred);
  }

  /** C8：全量转移 buyer 的普通库存 {@code transport_horse}（唯一写口 = EconomySettlement.applyTransfer）。 */
  private static long transferAllTransportHorses(
      SettlementDayContext ctx,
      HouseholdId buyer,
      HouseholdId target,
      HexCoord targetHex,
      int ordinal) {
    long quantity = horseInventoryOf(ctx, buyer);
    if (quantity <= 0L) {
      throw fail(
          "post-upward-horse-transfer-empty",
          "buyer=" + buyer.value() + " target=" + target.value());
    }
    long targetBefore = horseInventoryOf(ctx, target);
    Transfer transfer =
        ctx.ledger == null
            ? new Transfer(
                new TransferId("tr-" + ctx.day + "-upward-horse-" + ordinal),
                ctx.day,
                HouseholdActors.of(buyer),
                HouseholdActors.of(target),
                targetHex,
                Map.of(CaravanTransportTemplate.TRANSPORT_HORSE, quantity),
                Map.of(),
                TransferReason.MIGRATION_CARRY,
                Optional.empty())
            : ctx.ledger.mint(
                HouseholdActors.of(buyer),
                HouseholdActors.of(target),
                targetHex,
                Map.of(CaravanTransportTemplate.TRANSPORT_HORSE, quantity),
                Map.of(),
                TransferReason.MIGRATION_CARRY);
    EconomySettlement.applyTransfer(
        ctx.accounts.householdGoods(),
        ctx.accounts.householdMoney(),
        ctx.accounts.householdFrozenGoods(),
        ctx.accounts.householdFrozenMoney(),
        ctx.accounts.householdOfActor(),
        transfer);
    long targetAfter = horseInventoryOf(ctx, target);
    long expectedTarget = Math.addExact(targetBefore, quantity);
    if (targetAfter != expectedTarget) {
      throw fail(
          "post-upward-horse-target-not-conserved",
          "target="
              + target.value()
              + " before="
              + targetBefore
              + " expected="
              + expectedTarget
              + " after="
              + targetAfter);
    }
    return quantity;
  }

  private static long horseInventoryOf(SettlementDayContext ctx, HouseholdId household) {
    return ctx.accounts
        .householdGoods()
        .getOrDefault(household, Map.of())
        .getOrDefault(CaravanTransportTemplate.TRANSPORT_HORSE, 0L);
  }

  private static void logHorseStep(
      SettlementDayContext ctx,
      HorseStepRequest request,
      OwnerTarget target,
      long horseTransferred,
      long sourcePopulationAfter) {
    if (!LOG.isInfoEnabled()) {
      return;
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "POST_UPWARD_HORSE_TRANSFER",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                ctx.day,
                "buyer",
                request.buyer().value(),
                "targetHex",
                request.buyerCurrentHex().toString(),
                "target",
                target.household().value(),
                "targetCreatedShell",
                target.createdShell(),
                "cultureMatched",
                target.cultureMatched(),
                "migrants",
                request.migrants(),
                "acquiredHorseMilli",
                request.acquiredHorseMilli(),
                "horseTransferredMilli",
                horseTransferred,
                "sourcePopulationAfter",
                sourcePopulationAfter,
                "transfer",
                "ordinary-household-goods-transport_horse-only"));
  }

  /**
   * ★★★ <b>C3/C8 的唯一 LAND 步骤入口</b>：resolve target → LandBook 全量转移 → 可选人口/钱货债/经验迁移。
   *
   * <p>调用方只负责按吸收能力给出本步 migrants；本方法不重算 LAND 算式（算式在 {@link #landAbsorbableMigrants}）。LAND 转移后 buyer
   * 在该 hex 的持有必须归零，market marker 必须清除。
   */
  static StepResult applyLandStep(
      SettlementDayContext ctx,
      LandLedgerState land,
      RunState state,
      StepRequest request,
      int ordinal) {
    return applyLandStep(ctx, land, state, request, ordinal, Set.of());
  }

  /** ★ C8：LAND 步可显式排除 {@code transport_horse} 随人口比例迁移；马匹由紧随其后的马匹步全量转移。 */
  static StepResult applyLandStep(
      SettlementDayContext ctx,
      LandLedgerState land,
      RunState state,
      StepRequest request,
      int ordinal,
      Set<CommodityId> goodsExcludedFromPopulationCarry) {
    Objects.requireNonNull(ctx, "applyLandStep.ctx");
    Objects.requireNonNull(land, "applyLandStep.land");
    Objects.requireNonNull(state, "applyLandStep.state");
    Objects.requireNonNull(request, "applyLandStep.request");
    Objects.requireNonNull(
        goodsExcludedFromPopulationCarry, "applyLandStep.goodsExcludedFromPopulationCarry");
    if (ordinal < 0) {
      throw new IllegalArgumentException("applyLandStep.ordinal 不得为负");
    }
    HouseholdId buyer = request.buyer();
    HexCoord hex = request.hex();

    // 先做不写状态的预检：源持地不足时不得先建壳/先碰 marker。
    long holding = land.holdingOf(hex, buyer);
    if (holding < request.acquiredLandMilliMu()) {
      throw fail(
          "post-land-insufficient-source-land",
          "buyer="
              + buyer.value()
              + " hex="
              + hex
              + " required="
              + request.acquiredLandMilliMu()
              + " holding="
              + holding);
    }

    OwnerTarget target = resolveOwnerTarget(ctx, buyer, hex, state);
    if (buyer.equals(target.household())) {
      throw fail("post-land-self-target", "buyer=" + buyer.value());
    }

    LandHoldingKey markerKey = new LandHoldingKey(hex, buyer);
    LandLedgerState next =
        land.transfer(
            new LandTransfer(hex, buyer, target.household(), request.acquiredLandMilliMu()));
    writeLandHoldings(ctx, next);
    long residual = next.holdingOf(hex, buyer);
    if (residual != 0L) {
      throw fail(
          "post-land-landless-residual-land",
          "buyer=" + buyer.value() + " hex=" + hex + " residual=" + residual);
    }
    ctx.marketRound.clearMarketLandAcquired(markerKey);

    long sourcePopulationAfter = request.sourcePopulationBefore();
    boolean populationTransferred = false;
    if (request.migrants() > 0L) {
      HouseholdEconomy targetRow = ctx.householdEconomies.get(target.household());
      if (targetRow == null) {
        throw fail(
            "post-land-target-missing",
            "buyer=" + buyer.value() + " target=" + target.household().value());
      }
      long targetPopulationBefore = state.targetPopulationBefore(ctx, target.household());
      boolean targetIsNew = state.targetIsNew(target.household());
      HouseholdTransferSettlement.apply(
          ctx.session,
          ctx.accounts,
          ctx.ledger,
          ctx.day,
          ordinal,
          new HouseholdTransferSettlement.HouseholdTransferRequest(
              buyer,
              target.household(),
              request.migrants(),
              hex,
              Optional.empty(),
              request.reason(),
              targetIsNew),
          request.sourcePopulationBefore(),
          targetPopulationBefore,
          goodsExcludedFromPopulationCarry);
      state.recordPopulationTransfer(
          target.household(), targetPopulationBefore, request.migrants());
      sourcePopulationAfter =
          Math.subtractExact(request.sourcePopulationBefore(), request.migrants());
      populationTransferred = true;
    }

    logStep(ctx, request, target, sourcePopulationAfter);
    return new StepResult(
        next,
        target,
        request.acquiredLandMilliMu(),
        request.migrants(),
        sourcePopulationAfter,
        populationTransferred);
  }

  // ── 内部 ────────────────────────────────────────────────────────────────────────────────

  private record Candidate(
      HouseholdId household, long population, long landAtHex, HouseholdCultureKey cultureKey) {}

  /** 与 R1 {@code HouseholdMergeBook} 的 survivor 规则同序：人口最多 → 该 hex LAND 最多 → HouseholdId 最小。 */
  private static final Comparator<Candidate> TARGET_SURVIVOR_ORDER =
      Comparator.comparingLong(Candidate::population)
          .reversed()
          .thenComparing(Comparator.comparingLong(Candidate::landAtHex).reversed())
          .thenComparing(candidate -> candidate.household().value());

  private static OwnerTarget createOwnerShell(
      SettlementDayContext ctx, HouseholdId buyer, HexCoord hex, RunState state) {
    HouseholdEconomy buyerRow = ctx.householdEconomies.get(buyer);
    if (buyerRow == null) {
      throw fail("post-land-buyer-missing", "buyer=" + buyer.value());
    }
    ResidenceKind residence = buyerRow.view().residence();
    HouseholdId target = allocateOwnerShellId(ctx, hex, residence);
    HouseholdEconomy shell =
        new HouseholdEconomy(
            target,
            new CohortKey(hex, residence, SocialClassId.POOR_PEASANT),
            0L,
            0L,
            0,
            0L,
            Map.of(),
            Map.of(),
            0L);
    if (ctx.householdEconomies.putIfAbsent(target, shell) != null) {
      throw fail(
          "post-land-owner-shell-id-collision",
          "buyer=" + buyer.value() + " target=" + target.value());
    }
    if (ctx.accounts.accountOrNull(target) != null) {
      throw fail(
          "post-land-owner-shell-account-collision",
          "buyer=" + buyer.value() + " target=" + target.value());
    }
    ctx.accounts.registerHousehold(target, hex, Map.of(), Map.of(), Map.of(), Map.of());
    state.recordCreatedShell(target);
    // 新壳没有 culture 记录：不向 culture lookup 猜一个；同 culture 优先在后续轮由 Social 建档后自然生效。
    HouseholdCultureKey cultureKey = HouseholdCultureKey.missing();
    if (LOG.isInfoEnabled()) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "POST_LAND_OWNER_SHELL_CREATED",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  ctx.day,
                  "buyer",
                  buyer.value(),
                  "target",
                  target.value(),
                  "hex",
                  hex.toString(),
                  "residence",
                  residence.value(),
                  "population",
                  0L,
                  "mode",
                  "none",
                  "position",
                  "none",
                  "classStanding",
                  "none",
                  "land",
                  "pending-asset-derived-owner"));
    }
    return new OwnerTarget(target, true, false, cultureKey);
  }

  /** 稳定 id：同 (hex, residence, 新 OWNER 壳) 的最小未占用 n；账户已占而经济行不存在 ⇒ fail-closed。 */
  private static HouseholdId allocateOwnerShellId(
      SettlementDayContext ctx, HexCoord hex, ResidenceKind residence) {
    for (int n = 1; n < Integer.MAX_VALUE; n++) {
      HouseholdId candidate =
          HouseholdIds.ofSeedRole(hex, residence, SocialClassId.POOR_PEASANT, "owner-shell-" + n);
      if (ctx.householdEconomies.containsKey(candidate)) {
        continue;
      }
      if (ctx.accounts.accountOrNull(candidate) != null) {
        throw fail(
            "post-land-owner-shell-id-account-collision",
            "hex=" + hex + " residence=" + residence.value() + " candidate=" + candidate.value());
      }
      return candidate;
    }
    throw fail("post-land-owner-shell-id-exhausted", "hex=" + hex);
  }

  private static long landAtHex(SettlementDayContext ctx, HexCoord hex, HouseholdId household) {
    LandHolding holding = landHoldings(ctx).get(new LandHoldingKey(hex, household));
    return holding == null ? 0L : holding.quantityMilliMu();
  }

  private static Map<LandHoldingKey, LandHolding> landHoldings(SettlementDayContext ctx) {
    return ctx.session.sheet().landHoldings();
  }

  private static void writeLandHoldings(SettlementDayContext ctx, LandLedgerState land) {
    LinkedHashMap<LandHoldingKey, LandHolding> working = ctx.session.sheet().landHoldings();
    working.clear();
    working.putAll(land.holdingsByKey());
    // 本轮 MarketRound 的工作副本也同步，避免 Stage 后续 / 读数看到 S7-B 前的临时 PROLETARIAN 持地。
    ctx.marketRound.setLandHoldings(land.holdingsByKey());
  }

  private static HouseholdCultureKey cultureKeyOf(SettlementDayContext ctx, HouseholdId household) {
    Optional<HouseholdCultureKey> found = ctx.session.cultureKeyOf(household);
    if (found == null) {
      throw fail("post-land-culture-lookup-null", "household=" + household.value());
    }
    return found.orElse(HouseholdCultureKey.missing());
  }

  /**
   * ★ 现有 maxByWealth 口径：landPrice empty / 0 是两种不同的“不设财富门”；只有正价格才用 {@code floor(landlessWealth /
   * ceil(minLandPerPerson × landPrice / 1000))}。LAND 不计入（PROLETARIAN 无 LAND）。
   */
  private static long maxByWealth(
      SettlementDayContext ctx,
      HouseholdId buyer,
      long minLandPerPersonMilliMu,
      MarketTable localMarket) {
    if (localMarket == null || !localMarket.hasLandPrice()) {
      logMaxByWealth(ctx.day, buyer, "empty", -1L, 0L, 0L, Long.MAX_VALUE);
      return Long.MAX_VALUE;
    }
    long landPricePerMu = localMarket.landPrice().getAsLong();
    if (landPricePerMu == 0L) {
      logMaxByWealth(ctx.day, buyer, "zero", landPricePerMu, 0L, 0L, Long.MAX_VALUE);
      return Long.MAX_VALUE;
    }
    long entryCostPerPersonMilli =
        ceilDivide(
                BigInteger.valueOf(minLandPerPersonMilliMu)
                    .multiply(BigInteger.valueOf(landPricePerMu)),
                BigInteger.valueOf(MILLI_MU_PER_MU))
            .longValueExact();
    entryCostPerPersonMilli = Math.max(1L, entryCostPerPersonMilli);
    long wealthMilli = landlessWealthMilli(ctx, buyer, localMarket);
    long wealthGate = wealthMilli / entryCostPerPersonMilli;
    logMaxByWealth(
        ctx.day,
        buyer,
        "positive",
        landPricePerMu,
        wealthMilli,
        entryCostPerPersonMilli,
        wealthGate);
    return wealthGate;
  }

  /** 当刻账户财富（毫计价币）：本市场计价币现金 + 已定价商品按本地市场价的 floor 估值。 */
  private static long landlessWealthMilli(
      SettlementDayContext ctx, HouseholdId buyer, MarketTable localMarket) {
    long wealth =
        ctx.accounts
            .householdMoney()
            .getOrDefault(buyer, Map.of())
            .getOrDefault(localMarket.numeraire(), 0L);
    for (Map.Entry<CommodityId, Long> goods :
        ctx.accounts.householdGoods().getOrDefault(buyer, Map.of()).entrySet()) {
      if (!localMarket.hasPrice(goods.getKey())) {
        continue; // 未定价商品不臆造价值；0 价是明确免费，估值自然为 0。
      }
      wealth =
          Math.addExact(
              wealth,
              Math.multiplyExact(goods.getValue(), localMarket.priceOf(goods.getKey()))
                  / MILLI_PER_COMMODITY_UNIT);
    }
    return wealth;
  }

  private static void logMaxByWealth(
      long day,
      HouseholdId buyer,
      String landPriceState,
      long landPricePerMu,
      long landlessWealthMilli,
      long entryCostPerPersonMilli,
      long maxByWealth) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "POST_LAND_MAX_BY_WEALTH",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "buyer",
                buyer.value(),
                "landPriceState",
                landPriceState,
                "landPricePerMu",
                landPricePerMu,
                "landlessWealthMilli",
                landlessWealthMilli,
                "entryCostPerPersonMilli",
                entryCostPerPersonMilli,
                "maxByWealth",
                maxByWealth,
                "reason",
                "empty-or-zero-land-price-has-no-wealth-gate; positive-price-uses-account-wealth"));
  }

  private static void logStep(
      SettlementDayContext ctx,
      StepRequest request,
      OwnerTarget target,
      long sourcePopulationAfter) {
    if (!LOG.isInfoEnabled()) {
      return;
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "POST_LAND_CLASS_CHECK",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                ctx.day,
                "buyer",
                request.buyer().value(),
                "hex",
                request.hex().toString(),
                "target",
                target.household().value(),
                "targetCreatedShell",
                target.createdShell(),
                "cultureMatched",
                target.cultureMatched(),
                "migrants",
                request.migrants(),
                "landTransferred",
                request.acquiredLandMilliMu(),
                // 市场轮只把 (hex,buyer)→数量 交给 S7-B，不重算/猜测成交价款。
                "notional",
                "n/a",
                "notionalReason",
                "market-round-land-fill-value-not-exposed-at-s7b",
                "sourcePopulationAfter",
                sourcePopulationAfter));
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

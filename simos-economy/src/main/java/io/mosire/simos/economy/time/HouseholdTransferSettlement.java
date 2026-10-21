package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.MigrationDebtMove.carryGoodsForMove;
import static io.mosire.simos.economy.time.MigrationDebtMove.moveAllCurrenciesByPopulation;
import static io.mosire.simos.economy.time.MigrationDebtMove.moveDebt;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.ProductionExperienceMoveOutcome;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★★ <b>RC3（D-032 v4 R4/R5）：可复用的家户部分迁移原语</b>。
 *
 * <p>★ <b>父目标运行序列锚点</b>：{@code
 * docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md} 的
 * S7-B（PostLandTradeClassCheck：LANDLESS 买地后 LAND + 按比例人口转给目标有地户）。C3 的 LAND 向上腿仍走本类； ★ <b>S7-C 向下流动自
 * D-032 C4 起不再走本类</b>——用户裁定“只迁人口、净身出户”， 唯一执行入口是 {@link
 * HouseholdPopulationFlowSettlement#applyPopulationOnly}（只迁人口 + 缩 source PRODUCTION 承诺）。
 *
 * <p>本原语只承担 LAND 向上路径的"人口 + 钱/货/债/经验/劳动承诺"部分，不决定谁触发、不搬 LAND。
 *
 * <pre>
 * 调用方（RC6/RC7）预先建立目标经济行 / class standing；
 * HouseholdTransferSettlement.apply(...)：
 *   ① 校验 source != target、population ∈ (0, sourcePopulationBefore]、源/目标经济行与账户存在
 *   ② money     逐币种 ⌊源户当刻余额 × population / sourcePopulationBefore⌋；搬空 ⇒ 余额全走；余数留源
 *   ③ goods     逐商品 ⌊(余额 − 冻结) × population / sourcePopulationBefore⌋；搬空 ⇒ 可用量全走；
 *               HAUL 服务不搬；写路径 = EconomySettlement.applyTransfer 唯一写口
 *   ④ debt      先算 take = ⌊源户当刻总债务 × population / sourcePopulationBefore⌋（搬空 ⇒ 全部）；
 *               再逐合同按剩余本金分摊（Σ 恒等于 take）；目标=债权人 ⇒ 自债净额，不落自持合同
 *   ⑤ experience 直调 production 契约口 mergeExperience(source, population, target, targetPopulationBefore)
 *   ⑥ labor     源户 PRODUCTION 承诺按**剩余人口比例**保留（`(sourcePopulationBefore − population) / sourcePopulationBefore`），
 *               搬空清除；不向目标户新建 production 承诺（target 的当前劳动由下一 S3 的 effective roster 刷新）
 *   ⑦ outbox    成功后追加 EconomyPopulationTransfer（瞬态；不进 EconomyData/ChangeSet/Codec）
 * </pre>
 *
 * <p>★★ <b>权威边界（不得破坏）</b>：
 *
 * <ul>
 *   <li>不写 {@code HouseholdEconomy.population}/{@code laborMilli}：人口权威在 Social；App 在 step 后按 outbox
 *       回写。
 *   <li>不碰 LAND（{@code earthholdings}）：RC6/RC7 在调用者侧显式转移/清零。
 *   <li>不新增持久组件；outbox 是 {@link EconomySession} 的瞬态列表。
 *   <li>所有账户写入复用既有唯一写口（商品 = {@code applyTransfer}；债务 = {@link DebtContractBook}；经验 = {@link
 *       ProductionRuntime#mergeExperience}）。
 * </ul>
 *
 * <p>★★ <b>顺序迁移（同一 source 多笔）</b>：每一步都读<b>活账</b>（当刻余额/库存/债务），不是对初值重复切分 ⇒
 * 第二笔按第一笔后的剩余切；源户搬空的那一笔把该币种/商品/债务的剩余全部带走。
 */
public final class HouseholdTransferSettlement {

  private HouseholdTransferSettlement() {}

  private static final org.slf4j.Logger LOG = EconomyLog.migration();

  /** 一笔家户部分迁移请求（不可变）。 */
  public record HouseholdTransferRequest(
      HouseholdId source,
      HouseholdId target,
      long population,
      HexCoord targetHex,
      Optional<ProductionModeId> targetMode,
      String reason,
      boolean targetIsNew) {

    public HouseholdTransferRequest {
      Objects.requireNonNull(source, "HouseholdTransferRequest.source");
      Objects.requireNonNull(target, "HouseholdTransferRequest.target");
      Objects.requireNonNull(targetHex, "HouseholdTransferRequest.targetHex");
      Objects.requireNonNull(
          targetMode, "HouseholdTransferRequest.targetMode（无 mode 用 Optional.empty()，不得传 null）");
      Objects.requireNonNull(reason, "HouseholdTransferRequest.reason");
      if (source.equals(target)) {
        throw new IllegalArgumentException(
            "HouseholdTransferRequest.source 不得 == target（拒绝原地迁移）: " + source);
      }
      if (population <= 0L) {
        throw new IllegalArgumentException(
            "HouseholdTransferRequest.population 必须 > 0: " + population);
      }
    }
  }

  /**
   * 执行一笔家户部分迁移。
   *
   * @param session 推进会话（读 worker 表，写 outbox；不写 householdEconomies.population/laborMilli）
   * @param accounts 账户会话（钱/货活账）
   * @param ledger 当天瞬态审计累加器；可为 {@code null}（仅少审计读数，不改变守恒）
   * @param day 世界日；不得为负
   * @param ordinal 本批内本原语调用序号（商品转移 id 的兜底段）；不得为负
   * @param request 迁移请求
   * @param sourcePopulationBefore 本笔之前源户人口（活账投影；必须 &gt;= population）
   * @param targetPopulationBefore 本笔之前目标户人口（经验合并权重；搬入新建户时通常 0）
   */
  public static void apply(
      EconomySession session,
      AccountSession accounts,
      ProductionLedger.Accumulator ledger,
      long day,
      int ordinal,
      HouseholdTransferRequest request,
      long sourcePopulationBefore,
      long targetPopulationBefore) {
    apply(
        session,
        accounts,
        ledger,
        day,
        ordinal,
        request,
        sourcePopulationBefore,
        targetPopulationBefore,
        Set.of());
  }

  /**
   * ★★ D-032 C8：可显式排除某些商品不随本笔人口比例迁移的重载。
   *
   * <p>向上跃迁的 LAND 步排除 {@code transport_horse}，马匹步再全量转马；旧入口传空集 ⇒ 原行为逐值不变。
   */
  public static void apply(
      EconomySession session,
      AccountSession accounts,
      ProductionLedger.Accumulator ledger,
      long day,
      int ordinal,
      HouseholdTransferRequest request,
      long sourcePopulationBefore,
      long targetPopulationBefore,
      Set<CommodityId> goodsExcludedFromPopulationCarry) {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(accounts, "accounts");
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(goodsExcludedFromPopulationCarry, "goodsExcludedFromPopulationCarry");
    if (day < 0L) {
      throw new IllegalArgumentException("HouseholdTransferSettlement.apply 的 day 不得为负: " + day);
    }
    if (ordinal < 0) {
      throw new IllegalArgumentException(
          "HouseholdTransferSettlement.apply 的 ordinal 不得为负: " + ordinal);
    }
    if (sourcePopulationBefore <= 0L) {
      throw new IllegalArgumentException(
          "HouseholdTransferSettlement.apply 的 sourcePopulationBefore 必须 > 0: "
              + sourcePopulationBefore);
    }
    if (request.population() > sourcePopulationBefore) {
      throw new IllegalArgumentException(
          "HouseholdTransferSettlement.apply 的人口超过源户当刻人口（拒绝抽成负人口）: population="
              + request.population()
              + " sourcePopulationBefore="
              + sourcePopulationBefore);
    }
    if (targetPopulationBefore < 0L) {
      throw new IllegalArgumentException(
          "HouseholdTransferSettlement.apply 的 targetPopulationBefore 不得为负: "
              + targetPopulationBefore);
    }

    HouseholdId source = request.source();
    HouseholdId target = request.target();
    boolean empties = request.population() == sourcePopulationBefore;

    EconomyStateBuilder sheet = session.sheet();
    LinkedHashMap<HouseholdId, HouseholdEconomy> householdEconomies = sheet.householdEconomies();
    if (!householdEconomies.containsKey(source)) {
      throw new IllegalStateException(
          "household-transfer-source-missing: 源经济行不存在: " + source.value());
    }
    if (!householdEconomies.containsKey(target)) {
      throw new IllegalStateException(
          "household-transfer-target-missing: 目标经济行不存在（调用者须预先建立）: " + target.value());
    }
    if (accounts.accountOrNull(source) == null) {
      throw new IllegalStateException(
          "household-transfer-source-account-missing: 源家户账户不存在: " + source.value());
    }
    if (accounts.accountOrNull(target) == null) {
      throw new IllegalStateException(
          "household-transfer-target-account-missing: 目标家户账户不存在: " + target.value());
    }
    ProductionRuntime runtime = session.productionRuntime();
    if (runtime == null) {
      throw new IllegalStateException(
          "household-transfer-production-runtime-missing: 经验合并需要 production 契约口: "
              + source.value()
              + " → "
              + target.value());
    }

    // ★ 先做"会写之前"的全部具名预检：源户若在本笔搬空且仍挂非 PRODUCTION 承诺，首版不搬也不缩，
    //   不能静默留下 0 人口却有政府岗位承诺的半状态。
    refuseNonProductionCommitmentsOnEmpty(sheet, source, empties, day);

    Map<CurrencyId, Long> moneyBefore = snapshotMoney(accounts, source);
    Map<CommodityId, Long> goodsBefore = snapshotGoods(accounts, source);

    // ① 钱：逐币种按当刻余额比例 floor；搬空 ⇒ 该币种全走。写口 = MigrationDebtMove.moveMoney（既有迁移同款）。
    moveAllCurrenciesByPopulation(
        accounts, source, target, sourcePopulationBefore, request.population(), empties);

    // ② 货：逐商品按可用库存比例 floor；搬空 ⇒ 可用量全走；HAUL 不搬；写路径 = applyTransfer。
    carryGoodsForMove(
        accounts,
        source,
        target,
        request.population(),
        sourcePopulationBefore,
        empties,
        request.targetHex(),
        day,
        ledger,
        ordinal,
        goodsExcludedFromPopulationCarry);

    // ③ 债：先按当刻总债务算 take，再逐合同分摊（目标=债权人 ⇒ 自债净额）。
    LinkedHashMap<DebtContractId, DebtContract> debts = sheet.debtContracts();
    long debtTake =
        plannedDebtTake(debts, source, request.population(), sourcePopulationBefore, empties);
    if (debtTake > 0L) {
      moveDebt(request.source(), request.target(), debtTake, debts, day, ledger);
    }

    // ④ 经验：唯一权威在 production；economy 只给人口事实。
    ProductionExperienceMoveOutcome experience =
        runtime.mergeExperience(source, request.population(), target, targetPopulationBefore);

    // ⑤ 劳动承诺：只缩源户 PRODUCTION（不向目标新建）；搬空清除。GOV_SERVICE 首版不搬不缩。
    LaborCommitmentShrink laborShrink =
        shrinkSourceProductionCommitments(
            sheet, source, request.population(), sourcePopulationBefore, empties, day);

    // ⑥ 成功后追加瞬态 outbox（人口事实），App 在 step 后翻译成 Social 工单。
    session.recordPopulationTransfer(
        new EconomyPopulationTransfer(
            source,
            target,
            request.population(),
            request.targetHex(),
            request.targetMode(),
            request.targetIsNew(),
            request.reason()));

    Map<CurrencyId, Long> moneyMoved =
        positiveDelta(
            moneyBefore, snapshotMoney(accounts, source), Comparator.comparing(CurrencyId::value));
    Map<CommodityId, Long> goodsMoved =
        positiveDelta(
            goodsBefore, snapshotGoods(accounts, source), Comparator.comparing(CommodityId::value));
    logApplied(
        day,
        ordinal,
        request,
        sourcePopulationBefore,
        targetPopulationBefore,
        moneyMoved,
        goodsMoved,
        debtTake,
        experience,
        laborShrink);
  }

  // ── 债务 ────────────────────────────────────────────────────────────────────────────────

  /** 源户作为债务人的正本金合计（当刻活账）。 */
  private static long sourceDebtPrincipal(
      Map<DebtContractId, DebtContract> debts, HouseholdId source) {
    long total = 0L;
    for (DebtContract contract : debts.values()) {
      if (contract.debtor().equals(source) && contract.principal() > 0L) {
        total = Math.addExact(total, contract.principal());
      }
    }
    return total;
  }

  /** 本笔应随迁的债务本金：源户搬空 ⇒ 全部；否则 floor(总债务 × 人口 / 源人口)。 */
  private static long plannedDebtTake(
      Map<DebtContractId, DebtContract> debts,
      HouseholdId source,
      long population,
      long sourcePopulationBefore,
      boolean empties) {
    long total = sourceDebtPrincipal(debts, source);
    if (total <= 0L) {
      return 0L;
    }
    if (empties) {
      return total;
    }
    return Math.multiplyExact(total, population) / sourcePopulationBefore;
  }

  // ── 劳动承诺 ─────────────────────────────────────────────────────────────────────────────

  /**
   * 一次源户劳动承诺缩减的读数（瞬态，只进日志）。
   *
   * <p>★ package-private 供 {@link HouseholdPopulationFlowSettlement} 复用同一 shrink 算法；
   * 不得在本类之外另抄一份比例算式。
   */
  record LaborCommitmentShrink(
      long beforeTotal,
      long afterTotal,
      int shrunk,
      int removed,
      List<LaborAllocationId> retainedNonProduction) {}

  /** 源户搬空时，非 PRODUCTION 承诺（GOV_SERVICE）不可缩、不可搬 ⇒ 具名拒绝，绝不静默留下 "0 人口 + 政府岗位承诺" 的半状态。 */
  private static void refuseNonProductionCommitmentsOnEmpty(
      EconomyStateBuilder sheet, HouseholdId source, boolean empties, long day) {
    if (!empties) {
      return;
    }
    List<LaborAllocationId> retained = new ArrayList<>();
    for (HouseholdLaborCommitment commitment : sheet.laborCommitments().values()) {
      if (commitment.household().equals(source)
          && commitment.kind() != LaborCommitmentKind.PRODUCTION) {
        retained.add(commitment.id());
      }
    }
    if (retained.isEmpty()) {
      return;
    }
    retained.sort(Comparator.comparing(LaborAllocationId::value));
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "HOUSEHOLD_TRANSFER_NON_PRODUCTION_COMMITMENT_REFUSED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "source",
                source.value(),
                "commitments",
                retained,
                "reason",
                "non-production-commitment-cannot-vanish-with-source"));
    throw new IllegalStateException(
        "household-transfer-non-production-commitment-cannot-vanish: source="
            + source.value()
            + " commitments="
            + retained);
  }

  /**
   * 源户 PRODUCTION 劳动承诺按**剩余人口比例**保留（搬走人口不带走承诺）；搬空 ⇒ 全部移除；非 PRODUCTION 保留原额（首版不搬不缩）。
   *
   * <p>★ 不变量：本方法只做<b>单调递减</b>（每条 after ≤ before、总 after ≤ 总 before），且不在目标户新建任何 commitment ⇒ 不引入新的
   * {@code Σ commitments(household) > laborMilli} 破裂面。
   */
  static LaborCommitmentShrink shrinkSourceProductionCommitments(
      EconomyStateBuilder sheet,
      HouseholdId source,
      long population,
      long sourcePopulationBefore,
      boolean empties,
      long day) {
    LinkedHashMap<LaborAllocationId, HouseholdLaborCommitment> laborCommitments =
        sheet.laborCommitments();
    List<LaborAllocationId> sourceIds = new ArrayList<>();
    for (HouseholdLaborCommitment commitment : laborCommitments.values()) {
      if (commitment.household().equals(source)) {
        sourceIds.add(commitment.id());
      }
    }
    sourceIds.sort(Comparator.comparing(LaborAllocationId::value));

    long beforeTotal = 0L;
    long afterTotal = 0L;
    int shrunk = 0;
    int removed = 0;
    List<LaborAllocationId> retainedNonProduction = new ArrayList<>();
    for (LaborAllocationId id : sourceIds) {
      HouseholdLaborCommitment commitment = laborCommitments.get(id);
      if (commitment == null) {
        continue;
      }
      long before = commitment.laborMilli();
      beforeTotal = Math.addExact(beforeTotal, before);
      if (commitment.kind() != LaborCommitmentKind.PRODUCTION) {
        // ★ C7：GOV_SERVICE 首版不搬、不缩；只保留原额并具名读数。
        retainedNonProduction.add(id);
        afterTotal = Math.addExact(afterTotal, before);
        continue;
      }
      // ★★★ RC8b：部分迁移后 source **保留剩余人口比例**的生产承诺，不是只留迁走比例。
      //   160 迁 16 ⇒ 保留 144/160 = 90%（84,093 → 75,684），而不是 10%（8,408）。
      long remaining = sourcePopulationBefore - population;
      long after = empties ? 0L : Math.multiplyExact(before, remaining) / sourcePopulationBefore;
      after = Math.min(after, before);
      if (after <= 0L) {
        laborCommitments.remove(id);
        removed++;
        continue;
      }
      if (after < before) {
        laborCommitments.put(id, LaborQueueWritebackBook.withLaborMilli(commitment, after));
        shrunk++;
      }
      afterTotal = Math.addExact(afterTotal, after);
    }
    if (afterTotal > beforeTotal) {
      throw new IllegalStateException(
          "household-transfer-labor-commitment-increased: source="
              + source.value()
              + " before="
              + beforeTotal
              + " after="
              + afterTotal);
    }
    // ★ 构造期不变量的本原语侧守卫：只缩不增，且不得让源户承诺超过其当刻 laborMilli。
    //   目标户不新建任何承诺 ⇒ 不引入新的超预算面。
    long sourceLaborMilli = sheet.householdEconomies().get(source).laborMilli();
    if (afterTotal > sourceLaborMilli) {
      throw new IllegalStateException(
          "household-transfer-labor-commitment-exceeds-labor-budget: source="
              + source.value()
              + " commitmentsAfter="
              + afterTotal
              + " laborMilli="
              + sourceLaborMilli);
    }
    if (EconomyLog.migration().isTraceEnabled()) {
      EventLog.channel(LOG)
          .trace(
              LogEvent.of(
                  "HOUSEHOLD_TRANSFER_LABOR_COMMITMENTS_SCALED",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "day",
                  day,
                  "source",
                  source.value(),
                  "beforeTotalMilli",
                  beforeTotal,
                  "afterTotalMilli",
                  afterTotal,
                  "shrunk",
                  shrunk,
                  "removed",
                  removed,
                  "retainedNonProduction",
                  retainedNonProduction));
    }
    return new LaborCommitmentShrink(
        beforeTotal, afterTotal, shrunk, removed, retainedNonProduction);
  }

  // ── 日志与快照 ───────────────────────────────────────────────────────────────────────────

  private static Map<CurrencyId, Long> snapshotMoney(
      AccountSession accounts, HouseholdId household) {
    return new LinkedHashMap<>(accounts.householdMoney().getOrDefault(household, Map.of()));
  }

  private static Map<CommodityId, Long> snapshotGoods(
      AccountSession accounts, HouseholdId household) {
    return new LinkedHashMap<>(accounts.householdGoods().getOrDefault(household, Map.of()));
  }

  /** 源户余额/库存的减少量 = 本笔实际搬走的量（只取正差；键按规范序）。这是日志读数，不参与写路径。 */
  private static <K> Map<K, Long> positiveDelta(
      Map<K, Long> before, Map<K, Long> after, Comparator<? super K> order) {
    Map<K, Long> moved = new LinkedHashMap<>();
    List<K> keys = new ArrayList<>(before.keySet());
    keys.sort(order);
    for (K key : keys) {
      long delta = before.getOrDefault(key, 0L) - after.getOrDefault(key, 0L);
      if (delta > 0L) {
        moved.put(key, delta);
      }
    }
    return moved;
  }

  private static void logApplied(
      long day,
      int ordinal,
      HouseholdTransferRequest request,
      long sourcePopulationBefore,
      long targetPopulationBefore,
      Map<CurrencyId, Long> moneyMoved,
      Map<CommodityId, Long> goodsMoved,
      long debtTake,
      ProductionExperienceMoveOutcome experience,
      LaborCommitmentShrink laborShrink) {
    if (EconomyLog.migration().isTraceEnabled()) {
      for (Map.Entry<CurrencyId, Long> leg : moneyMoved.entrySet()) {
        EventLog.channel(LOG)
            .trace(
                LogEvent.of(
                    "HOUSEHOLD_TRANSFER_MONEY_LEG",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "ordinal",
                    ordinal,
                    "source",
                    request.source().value(),
                    "target",
                    request.target().value(),
                    "currency",
                    leg.getKey().value(),
                    "amount",
                    leg.getValue()));
      }
      for (Map.Entry<CommodityId, Long> leg : goodsMoved.entrySet()) {
        EventLog.channel(LOG)
            .trace(
                LogEvent.of(
                    "HOUSEHOLD_TRANSFER_GOODS_LEG",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "ordinal",
                    ordinal,
                    "source",
                    request.source().value(),
                    "target",
                    request.target().value(),
                    "commodity",
                    leg.getKey().value(),
                    "quantity",
                    leg.getValue()));
      }
    }
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "HOUSEHOLD_TRANSFER_APPLIED",
                EconomyLogSource.ECONOMY_MIGRATION,
                "day",
                day,
                "ordinal",
                ordinal,
                "source",
                request.source().value(),
                "target",
                request.target().value(),
                "population",
                request.population(),
                "sourcePopulationBefore",
                sourcePopulationBefore,
                "targetPopulationBefore",
                targetPopulationBefore,
                "targetHex",
                request.targetHex().q() + "," + request.targetHex().r(),
                "targetMode",
                request.targetMode().map(ProductionModeId::value).orElse("none"),
                "targetIsNew",
                request.targetIsNew(),
                "reason",
                request.reason(),
                "money",
                moneyMoved,
                "goods",
                goodsMoved,
                "debt",
                debtTake,
                "experienceMoved",
                experience.movedPeople(),
                "experienceChanged",
                experience.changed(),
                "experienceTracksMerged",
                experience.tracksMerged(),
                "experienceUnlocksAdded",
                experience.unlocksAdded(),
                "laborBeforeMilli",
                laborShrink.beforeTotal(),
                "laborAfterMilli",
                laborShrink.afterTotal(),
                "laborCommitmentsShrunk",
                laborShrink.shrunk(),
                "laborCommitmentsRemoved",
                laborShrink.removed(),
                "laborNonProductionRetained",
                laborShrink.retainedNonProduction()));
  }
}

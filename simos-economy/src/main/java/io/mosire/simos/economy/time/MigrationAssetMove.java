package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.ModeMigrationSettlement.buildMigrationRelation;
import static io.mosire.simos.economy.time.ModeMigrationSettlement.enterpriseOf;
import static io.mosire.simos.economy.time.ModeMigrationSettlement.requireInstanceLocation;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.DefaultProductionModes;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.MarketTable;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.model.MerchantTradeTemplate;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.time.ModeMigrationSettlement.MigrationRelationPlan;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
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
import java.util.Optional;
import java.util.Set;

/** P0.5-E1 机械拆分自 {@code ModeMigrationSettlement}；职责见类名与各方法注释。 */
final class MigrationAssetMove {

  private MigrationAssetMove() {}

  /** 迁移执行日志（与门面同一 migration 分类；logger 名仍唯一由 EconomyLog 拼写）。 */
  private static final org.slf4j.Logger LOG = EconomyLog.migration();

  static List<OwnershipStakeBook.Move> planAssetMoves(
      IndustryId industryId,
      IndustryDefinitionView industry,
      ActorRef targetActor,
      long scale,
      Map<AssetShareId, OwnershipStake> assetShares,
      Set<AssetShareId> claimedByEnterprises,
      Map<AssetKind, Long> availableByKind,
      boolean crossIndustryIdleInHex,
      HexCoord hex) {
    Map<AssetKind, Long> coveredByKind = availableByKind == null ? Map.of() : availableByKind;
    String hexKey = hex == null ? null : IndustryHexKeys.hexKey(hex.q(), hex.r());
    List<OwnershipStake> idle = new ArrayList<>();
    for (OwnershipStake share : assetShares.values()) {
      if (!ModeMigrationPolicy.isIdleShare(share, claimedByEnterprises)) {
        continue;
      }
      if (share.industry().equals(industryId)) {
        idle.add(share);
        continue;
      }
      if (crossIndustryIdleInHex
          && hexKey != null
          && IndustryHexKeys.hexKeyOf(share.industry()).filter(hexKey::equals).isPresent()) {
        idle.add(share);
      }
    }
    idle.sort(Comparator.comparing(share -> share.id().value()));
    List<OwnershipStakeBook.Move> moves = new ArrayList<>();
    for (Map.Entry<AssetKind, Long> required : industry.recipe().capacityPerUnit().entrySet()) {
      long requiredQuantity = Math.multiplyExact(scale, required.getValue());
      long need =
          Math.max(0L, requiredQuantity - coveredByKind.getOrDefault(required.getKey(), 0L));
      for (OwnershipStake share : idle) {
        if (need <= 0L) {
          break;
        }
        if (share.asset() != required.getKey()) {
          continue;
        }
        long take = Math.min(need, share.quantity());
        if (take <= 0L) {
          continue;
        }
        moves.add(
            new OwnershipStakeBook.Move(
                share.id(), take, share.owner(), targetActor, AssetRightKind.TENANCY));
        need -= take;
      }
      if (need > 0L) {
        return null;
      }
    }
    return moves;
  }

  static ProductionUnitId createEnterpriseAndProcess(
      io.mosire.simos.production.contract.ProductionRuntime runtime,
      ModeMigrationPolicy.MigrationMove move,
      Map<IndustryId, IndustryDefinitionView> industries,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      HouseholdEconomy targetHouseholdEconomy,
      ClassPositionId positionId,
      LinkedHashMap<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, ProductionProcess> units,
      LinkedHashMap<ProductionUnitId, ProductionRules> relations,
      LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> enterprises,
      EconomyDayView base,
      List<AssetShareId> migratedShareIds,
      Map<IndustryId, Map<AssetKind, Long>> migratedCoverage,
      long day,
      ProductionLedger.Accumulator auditLedger) {
    ActorRef targetActor = HouseholdActors.of(move.target());
    // ★★ D-024：按目标 mode 的默认 regime 选产业模板（与计划期 ModeMigrationPolicy.industriesForMode 同源）——
    //   merchant 目标只在 trade 模板里找；找不到 ⇒ 空 ⇒ 具名失败（绝不把农场模板当商号）。
    List<IndustryId> industryCandidates =
        ModeMigrationPolicy.industriesForMode(industries, move.targetHex(), move.targetMode());
    if (industryCandidates.isEmpty()) {
      throw new IllegalStateException("新建目标 hex 没有产业模板（拒绝凭空造生产）: " + move.targetHex());
    }
    IndustryId industryId = null;
    IndustryDefinitionView industry = null;
    List<OwnershipStakeBook.Move> assetMoves = new ArrayList<>();
    // ★★ P10.7 / P10.8 / D-024 修复 1b：既有组织 assetSources 正在使用的份额不是闲置；seeder 直接以 ESTATE 等
    //    operator 建立的 farm/weave/craft/trade 主 unit 没有对应组织，故 claimed 还必须并入"在产 unit 占用的份额"
    //    （同 industry、同 operator、quantity>0）。这里与 ModeMigrationPolicy.plan() 顶层共用同一个三参拼装点，
    //    并读同一份当天工作副本 enterprises/units/assetShares（含当天自动组织阶段新加、以及本一次 apply 前几笔
    //    新建的组织/unit），保证"计划可新建 ⇔ 执行可拆到"；随迁新份额也从闲置池里排除（它们已归目标户）。
    Set<AssetShareId> claimedByEnterprises =
        new LinkedHashSet<>(
            ModeMigrationPolicy.claimedOwnershipStakes(enterprises, units, assetShares));
    claimedByEnterprises.addAll(migratedShareIds);
    // ★★★ W9-fix2：失败时把"每个候选按哪个规模要多少"带上（本次事故的诊断成本全在缺的这一行信息上：
    //   现场只有 labor=8857，看不出执行期要的是 scale=8 ⇒ 与计划期预留 scale=1 的具名不一致）。
    StringBuilder triedCandidates = new StringBuilder();
    for (IndustryId candidate : industryCandidates) {
      IndustryDefinitionView candidateIndustry = industries.get(candidate);
      if (candidateIndustry == null) {
        continue;
      }
      // ★★★ W9-fix2（2026-10-11）：规模算式的**唯一拼写点** = {@link
      // ModeMigrationPolicy#requiredWorkCapitalScale}（计划期预留与执行期索取共用；P0 语义在那里写一次）。
      //   ★ 这里的 labor 是**带本笔随迁投影的工作行视图**的劳动（新建户 = 0 + 本笔迁入劳动；落盘行仍是 0，
      //     由 App 按 outbox 回写）—— 逐字读落盘行会得到 scale = 1，那正是 W9-fix2 改前计划侧的旧假设。
      long scale =
          ModeMigrationPolicy.requiredWorkCapitalScale(
              candidateIndustry, targetHouseholdEconomy.laborMilli());
      if (triedCandidates.length() > 0) {
        triedCandidates.append(' ');
      }
      triedCandidates
          .append(candidate.value())
          .append(":scale=")
          .append(scale)
          .append(",need=")
          .append(candidateIndustry.recipe().capacityPerUnit());
      List<OwnershipStakeBook.Move> planned =
          planAssetMoves(
              candidate,
              candidateIndustry,
              targetActor,
              scale,
              assetShares,
              claimedByEnterprises,
              migratedCoverage.getOrDefault(candidate, Map.of()),
              // ★★★ W9（用户裁定 v3.29 §7 第 3 条「没有生产工具不会买吗」）：**这一笔没带来作业资本**时，
              //   允许租**同格**其它产业的闲置同种资产（工具可买/可租）—— 否则"按需新建的 trade 产业"名下
              //   永远没有任何份额，只能整笔否决（W7 记的 E3 遗留门槛）。★ 只在"随迁覆盖为空"时为真：
              //   带来了作业资本就照旧只在本产业内找闲置（逐值等于改前）。
              DefaultProductionModes.MERCHANT.equals(move.targetMode())
                  && migratedCoverage.getOrDefault(candidate, Map.of()).isEmpty(),
              move.targetHex());
      if (planned != null) {
        industryId = candidate;
        industry = candidateIndustry;
        assetMoves = planned;
        break;
      }
    }
    if (industryId == null || industry == null) {
      throw new IllegalStateException(
          "新建目标户的租赁资产不足（计划不该包含此目标；已按 id 序遍历该 hex 全部产业模板）: hex="
              + move.targetHex()
              + " labor="
              + targetHouseholdEconomy.laborMilli()
              + " tried=["
              + triedCandidates
              + "]");
    }
    List<AssetShareId> createdShares = new ArrayList<>();
    if (!assetMoves.isEmpty()) {
      createdShares.addAll(OwnershipStakeBook.apply(assetShares, industries, assetMoves));
    }
    // ★★ GAP-2：组织 assetSources 只收 operator == organizer（targetActor）的份额。随迁份额由
    //    migrateAssetsForMove 按 targetActor 重建（同/跨 hex 都一样）；租赁份额由上方 planAssetMoves
    //    按 targetActor 新建。这里再做最后一次逐项核对：新建租赁份额对不上是内部错误，当场抛；
    //    随迁份额对不上（理论上不会发生）不得进 assetSources，也不放进 unit 的可用资产依赖里。
    List<AssetShareId> assetSources =
        new ArrayList<>(migratedShareIds.size() + createdShares.size());
    for (AssetShareId migratedShareId : migratedShareIds) {
      OwnershipStake migratedShare = assetShares.get(migratedShareId);
      if (migratedShare != null && migratedShare.operator().equals(targetActor)) {
        assetSources.add(migratedShareId);
      }
    }
    for (AssetShareId createdShare : createdShares) {
      OwnershipStake share = assetShares.get(createdShare);
      if (share == null || !share.operator().equals(targetActor)) {
        throw new IllegalStateException(
            "新建目标户的租赁份额 operator 必须等于 organizer(targetActor): share="
                + createdShare
                + " operator="
                + (share == null ? "<份额不存在>" : share.operator()));
      }
      assetSources.add(createdShare);
    }
    ProductionUnitId unitId = ProductionUnitId.idOf(industryId, targetActor);
    if (units.containsKey(unitId)) {
      throw new IllegalStateException("新建目标 unit id 已存在（拒绝覆盖）: " + unitId);
    }
    // ★★★ D-032 B3（§13.8 裁定二第 1/3/4 条）：地点是语义边界 —— 实例必须有真实地点，解不出即**契约故障
    //   ERROR 不降级**（跨切片不一致），不写 UNSPECIFIED 占位。★ industryId 取自
    //   `IndustryHexKeys.at(..., move.targetHex())`（自带格段）⇒ 正常路径必命中。
    io.mosire.simos.production.contract.ProductionLocation instanceLocation =
        requireInstanceLocation(industryId);
    // ★★ B2b：实例新建直调契约口（`createInstance`）。被拒 = 跨切片不一致 ⇒ ERROR 不降级（I15 / §13.2 ①④）。
    io.mosire.simos.production.contract.ProductionInstanceOutcome created =
        runtime.createInstance(
            new io.mosire.simos.production.contract.ProductionCreationRequest(
                industryId,
                unitId,
                instanceLocation,
                targetActor,
                "mode:" + move.targetMode().value()));
    if (created
        instanceof
        io.mosire.simos.production.contract.ProductionInstanceOutcome.Rejected rejected) {
      EventLog.channel(LOG)
          .error(
              LogEvent.of(
                  "MODE_MIGRATION_UNIT_CREATE_REJECTED",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "unit",
                  unitId.value(),
                  "plan",
                  industryId.value(),
                  "day",
                  day,
                  "reason",
                  rejected.reason()));
      throw new IllegalStateException(
          "迁移搬迁新建实例被 production 拒绝（跨切片不一致，不降级）: unit="
              + unitId.value()
              + " plan="
              + industryId.value()
              + " reason="
              + rejected.reason());
    }
    // ★★ D-023 第 5 项：新家户必须用目标产业 regime + 目标 mode 的**完整关系模板**（不再空规则全归 operator）。
    MigrationRelationPlan relationPlan =
        buildMigrationRelation(
            move,
            unitId,
            industryId,
            targetActor,
            householdEconomies,
            targetHouseholdEconomy,
            base,
            day,
            auditLedger,
            industries);
    ProductionRules relation = relationPlan.relation();
    relations.put(unitId, relation);
    ProductionOrganizationId organizationId =
        ProductionOrganizationId.idOf(
            move.targetMode(),
            positionId,
            move.target(),
            IndustryHexKeys.hexKey(move.targetHex().q(), move.targetHex().r()));
    ProductionEnterprise enterprise =
        new ProductionEnterprise(
            organizationId,
            move.targetMode(),
            positionId,
            Optional.of(unitId),
            targetActor,
            List.of(move.target()),
            assetSources,
            List.of(relation.inputSupplier()),
            new Payee.ToActor(relation.residualOwner()),
            Optional.of(relationPlan.templateRef()),
            ProductionEnterprise.Status.ACTIVE,
            "");
    enterprises.put(organizationId, enterprise);
    return unitId;
  }

  /** ★ Z3b/C7：迁移计划要搬动 GOV_SERVICE 承诺家户 ⇒ 具名 ERROR（先落证据）再 fail-closed。 */
  record AssetMigrationOutcome(
      List<AssetShareId> createdIds, Map<IndustryId, Map<AssetKind, Long>> coverage) {

    AssetMigrationOutcome {
      createdIds = List.copyOf(createdIds);
      Map<IndustryId, Map<AssetKind, Long>> frozen = new LinkedHashMap<>();
      for (Map.Entry<IndustryId, Map<AssetKind, Long>> entry : coverage.entrySet()) {
        frozen.put(
            entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
      }
      coverage = Collections.unmodifiableMap(frozen);
    }

    static AssetMigrationOutcome empty() {
      return new AssetMigrationOutcome(List.of(), Map.of());
    }
  }

  static AssetMigrationOutcome migrateAssetsForMove(
      ActorRef sourceActor,
      HexCoord sourceHex,
      ModeMigrationPolicy.MigrationMove move,
      long populationBefore,
      long popTake,
      boolean empties,
      List<AssetShareId> sourceOwnedAssetIds,
      LinkedHashMap<AssetShareId, OwnershipStake> assetShares,
      EconomyDayView base,
      Set<AssetShareId> foreignUsedShareIds,
      Set<AssetShareId> movedWholeShareIds,
      Map<AssetShareId, String> residualReasons,
      Map<IndustryId, IndustryDefinitionView> industries) {
    boolean sameHex = move.targetHex().equals(sourceHex);
    ActorRef targetActor = HouseholdActors.of(move.target());
    List<OwnershipStakeBook.Move> sameHexMoves = new ArrayList<>();
    List<OwnershipStakeBook.RebuildMove> rebuildMoves = new ArrayList<>();
    for (AssetShareId shareId : sourceOwnedAssetIds) {
      OwnershipStake share = assetShares.get(shareId);
      if (share == null || share.quantity() <= 0L || !share.owner().equals(sourceActor)) {
        continue; // 已在前面 move 整条随迁 / 已非源户所有
      }
      // 已出租给他组织实际使用的份额：改 operator 会拆掉那个组织的资产引用 ⇒ 本批留原户并具名（不越权搬）。
      if (foreignUsedShareIds.contains(share.id())) {
        residualReasons.putIfAbsent(share.id(), "leased-to-foreign-org:" + share.asset().name());
        continue;
      }
      long take =
          empties
              ? share.quantity()
              : Math.multiplyExact(share.quantity(), popTake) / Math.max(1L, populationBefore);
      take = Math.min(take, share.quantity());
      if (take <= 0L) {
        continue;
      }
      AssetKind asset = share.asset();
      AssetRightKind kind = transferRightKind(share);
      ActorRef newOperator = transferOperator(share, targetActor);
      if (sameHex) {
        sameHexMoves.add(
            new OwnershipStakeBook.Move(share.id(), take, targetActor, newOperator, kind));
      } else {
        if (!isMobileAsset(asset)) {
          residualReasons.putIfAbsent(share.id(), "immobile-cross-hex:" + asset.name());
          continue;
        }
        Optional<IndustryId> hostIndustry = hostIndustryFor(industries, move.targetHex(), asset);
        if (hostIndustry.isEmpty()) {
          residualReasons.putIfAbsent(share.id(), "no-host-industry:" + asset.name());
          continue;
        }
        rebuildMoves.add(
            new OwnershipStakeBook.RebuildMove(
                share.id(), take, hostIndustry.get(), targetActor, newOperator, kind));
      }
      if (take == share.quantity()) {
        movedWholeShareIds.add(share.id());
      }
    }
    List<AssetShareId> createdIds = new ArrayList<>();
    if (!sameHexMoves.isEmpty()) {
      createdIds.addAll(OwnershipStakeBook.apply(assetShares, industries, sameHexMoves));
    }
    if (!rebuildMoves.isEmpty()) {
      createdIds.addAll(OwnershipStakeBook.rebuild(assetShares, industries, rebuildMoves));
    }
    if (createdIds.isEmpty()) {
      return AssetMigrationOutcome.empty();
    }
    // ★ 2026-10-09：同 tuple 目标行会合并，`createdIds` 可能重复指向同一行 ⇒ 覆盖量按**本次实际移动量**
    //   逐 Move 记账，不读合并行的总数量（否则会把目标户原有资产算进"随迁覆盖"）。
    Map<IndustryId, Map<AssetKind, Long>> coverage = new LinkedHashMap<>();
    int coverageIndex = 0;
    for (OwnershipStakeBook.Move assetMove : sameHexMoves) {
      coverageIndex =
          accumulateCoverage(
              coverage, assetShares, createdIds, coverageIndex, assetMove.quantity());
    }
    for (OwnershipStakeBook.RebuildMove rebuildMove : rebuildMoves) {
      coverageIndex =
          accumulateCoverage(
              coverage, assetShares, createdIds, coverageIndex, rebuildMove.quantity());
    }
    return new AssetMigrationOutcome(createdIds, coverage);
  }

  /** 把一条随迁移动量记进覆盖率（目标行必须真实存在；重复目标行按 Move 逐笔记，不重复读行内总量）。 */
  static int accumulateCoverage(
      Map<IndustryId, Map<AssetKind, Long>> coverage,
      Map<AssetShareId, OwnershipStake> assetShares,
      List<AssetShareId> createdIds,
      int index,
      long quantity) {
    OwnershipStake created = assetShares.get(createdIds.get(index));
    if (created == null) {
      throw new IllegalStateException("资产随迁目标份额在表里不存在（拒绝静默丢资产）: " + createdIds.get(index));
    }
    coverage
        .computeIfAbsent(created.industry(), ignored -> new LinkedHashMap<>())
        .merge(created.asset(), quantity, Math::addExact);
    return index + 1;
  }

  /**
   * 可移动资产判据（D-023：SMALL_TOOL/LARGE_TOOL/SHIP/CATTLE/MACHINE 随人走；LAND/WORKSHOP 不跨 hex）。
   *
   * <p>★ <b>W9（2026-10-11）：包内可见</b> —— {@code
   * ModeMigrationPolicy.carriedCapitalByKind}（档位判据）要读同一条判据； 只改可见性，判据本体一字未动（转业档位不得有第二份"什么带得走"的拼写）。
   */
  static boolean isMobileAsset(AssetKind asset) {
    return switch (asset) {
      case SMALL_TOOL, LARGE_TOOL, SHIP, CATTLE, MACHINE -> true;
      case LAND, WORKSHOP -> false;
    };
  }

  /** 随迁新份额的权利性质：共有保持共有；其余一律随目标户自营（owner == operator ⇒ OWNED）。 */
  static AssetRightKind transferRightKind(OwnershipStake share) {
    return share.kind() == AssetRightKind.COMMUNAL ? AssetRightKind.COMMUNAL : AssetRightKind.OWNED;
  }

  /**
   * 随迁后的 operator = 目标户（D-023 §5.3 的“改 owner/operator 到目标户”）。★ 已被别的组织实际使用的 出租份额不在这里处理：上游 {@code
   * foreignUsedOwnershipStakeIds} 已把它按“留原户并具名”跳过，避免拆掉那个组织的引用。
   */
  static ActorRef transferOperator(OwnershipStake share, ActorRef targetActor) {
    if (share == null || targetActor == null) {
      throw new IllegalArgumentException("transferOperator 的 share/targetActor 不得为 null");
    }
    return targetActor;
  }

  /** 目标 hex 能承载该 AssetKind 的第一个产业（{@link IndustryHexKeys#at} 已按 id 升序）；无 ⇒ 空。 */
  static Optional<IndustryId> hostIndustryFor(
      Map<IndustryId, IndustryDefinitionView> industries, HexCoord targetHex, AssetKind asset) {
    for (IndustryId candidate : IndustryHexKeys.at(industries, targetHex.q(), targetHex.r())) {
      IndustryDefinitionView industry = industries.get(candidate);
      if (industry != null && industry.capacityPerUnit().containsKey(asset)) {
        return Optional.of(candidate);
      }
    }
    return Optional.empty();
  }

  /**
   * ★★★ <b>W7（2026-10-11）/ W7-fix（2026-10-11）：把"目标格要按需建商号、但这一笔**带不来作业资本**"的迁移笔**具名跳过**</b>。
   *
   * <pre>
   * 触发条件（两条同时成立）：
   *   ① mode = merchant 且目标格本来没有 merchant 制度的产业模板（= 本批要按需建 trade@hex）
   *   ② 这一笔**实际能随迁携带**的作业资本（★ W7-fix 起 = 工具；改前 = 牲口）≤ 0
   * 判据（与 {@code migrateAssetsForMove} 的取整逐式对齐）：
   *   范围 = owner == 源户 actor ∧ asset ∈ {@link MerchantTradeTemplate#capacityKinds()} ∧ quantity &gt; 0
   *          ∧ 未被别的组织实际使用
   *   取整 = 整户迁空 ⇒ 全部；否则 ⇒ ⌊数量 × 本笔人口 ÷ 该源户当时的投影人口⌋
   *   ★ "当时" = 同一源户前面的迁移笔已扣掉它们搬走的人口与份额（顺序 = 计划序 ⇒ 确定性）
   * 处置 = INFO 具名跳过（不建商号、不搬人、不搬资产）；其余笔一字不动
   * </pre>
   *
   * <p>★ <b>为什么必须有这道闸</b>：{@code createEnterpriseAndProcess} 在"新建户凑不齐目标产业的租赁资产"时
   * 是**当场抛**的（fail-closed）—— 而"按需建的 trade@hex 下唯一能有的作业资本就是这一笔背过来的那一份"。 决策层（{@code
   * ExpectedProfitBook} + {@code perMoveNewRoom}）已经按同一条口径挡过一轮，这里是执行期兜底： 挡住 = 一笔迁移不发生（可读、可重试），挡不住 =
   * 整个世界卡死。
   *
   * <p>★★ <b>W7-fix：判据从牲口换成工具</b>（用户裁定 v3.28 §7「能不能先把牲口要求取消掉啊？…算作工具需求得了」）—— 与模板 {@code
   * capacityPerUnit = {SMALL_TOOL: TOOL_PER_UNIT}} 同源；工具是**可移动资产**（{@code isMobileAsset} 含
   * SMALL_TOOL）， 随迁携带那一条路照旧成立。★ 理由字面量随之改名（{@code no-carrier-asset-… → no-tool-input-…}，§一.11 不留旧名）。
   */
  static List<ModeMigrationPolicy.MigrationMove> filterOnDemandMerchantMoves(
      List<ModeMigrationPolicy.MigrationMove> moves,
      Set<HexCoord> onDemandTradeHexes,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      EconomyDayView base,
      long day) {
    if (onDemandTradeHexes.isEmpty()) {
      return moves;
    }
    // 逐源户模拟（键序 = 计划里的首次出现序；同一源户的多笔按计划序扣减 ⇒ 与执行期同序）。
    Map<HouseholdId, Long> projectedPopulation = new LinkedHashMap<>();
    Map<HouseholdId, Long> carriedCarrierAssetsLeft = new LinkedHashMap<>();
    List<ModeMigrationPolicy.MigrationMove> accepted = new ArrayList<>();
    for (ModeMigrationPolicy.MigrationMove move : moves) {
      boolean needsOnDemandIndustry =
          DefaultProductionModes.MERCHANT.equals(move.targetMode())
              && onDemandTradeHexes.contains(move.targetHex());
      HouseholdEconomy row = householdEconomies.get(move.source());
      if (!needsOnDemandIndustry || row == null) {
        accepted.add(move);
        continue;
      }
      long population =
          projectedPopulation.computeIfAbsent(move.source(), ignored -> row.population());
      long carrierAssetLeft =
          carriedCarrierAssetsLeft.computeIfAbsent(
              move.source(),
              ignored -> carriedCarrierAssets(row, assetShares, enterprises, move.source()));
      boolean empties = population <= 0L || move.population() >= population;
      long carry =
          empties
              ? carrierAssetLeft
              : Math.multiplyExact(carrierAssetLeft, move.population()) / Math.max(1L, population);
      if (carry <= 0L) {
        // ★★★ W9（2026-10-11，用户裁定 v3.29 §7 第 3 条「没有生产工具不会买吗？」）：先问"**买不买得到**"。
        //   判据 = 目标格的市场交易工具这个商品（买 / 赊购通道）∧ 本格有**闲置**的同种作业资本（可租）。
        //   成立 ⇒ 放行（具名 INFO：走购买/租佃通道；真的租到由 createEnterpriseAndProcess 的放宽分支完成）。
        MarketTable market = marketForHex(base, move.targetHex());
        boolean toolTraded =
            market != null && market.hasPrice(MerchantTradeTemplate.TOOL_COMMODITY);
        long idleToolMilli = idleToolInHex(assetShares, enterprises, units, move.targetHex());
        if (toolTraded && idleToolMilli > 0L) {
          EventLog.channel(LOG)
              .info(
                  LogEvent.of(
                      "ONDEMAND_TRADE_MIGRATION_TOOL_PURCHASE",
                      EconomyLogSource.ECONOMY_MIGRATION,
                      "day",
                      day,
                      "source",
                      move.source().value(),
                      "target",
                      move.target().value(),
                      "targetHex",
                      move.targetHex(),
                      "targetMode",
                      move.targetMode().value(),
                      "population",
                      move.population(),
                      "carriedToolInput",
                      carry,
                      "toolUnitPriceMilli",
                      market.priceOf(MerchantTradeTemplate.TOOL_COMMODITY),
                      "idleToolMilliInHex",
                      idleToolMilli,
                      "reason",
                      "no-tool-input-bought-or-leased-at-market"));
          projectedPopulation.put(move.source(), Math.max(0L, population - move.population()));
          accepted.add(move);
          continue;
        }
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "ONDEMAND_TRADE_MIGRATION_SKIPPED",
                    EconomyLogSource.ECONOMY_MIGRATION,
                    "day",
                    day,
                    "source",
                    move.source().value(),
                    "target",
                    move.target().value(),
                    "targetHex",
                    move.targetHex(),
                    "targetMode",
                    move.targetMode().value(),
                    "population",
                    move.population(),
                    // ★ W7-fix：字段名随作业资本那一维改名（改前 = carriedCattle）。
                    "carriedToolInput",
                    carry,
                    // ★ W9：把"到底卡在哪一条"补上（市场有没有这一行 / 本格有没有闲着的工具）。
                    "toolTradedAtMarket",
                    toolTraded,
                    "idleToolMilliInHex",
                    idleToolMilli,
                    "reason",
                    "no-tool-input-to-host-on-demand-trade-industry"));
        continue;
      }
      projectedPopulation.put(move.source(), Math.max(0L, population - move.population()));
      carriedCarrierAssetsLeft.put(move.source(), Math.max(0L, carrierAssetLeft - carry));
      accepted.add(move);
    }
    return List.copyOf(accepted);
  }

  /**
   * ★★ <b>W9：目标格所属市场的价表（执行期可用、不依赖 topology）</b> —— 与 {@code PrimaryModeRanking.marketOf(markets,
   * topology, hex)} 是**同一张表**（区内一张，键 = 市场区 id）：执行期手里只有 {@link EconomyDayView}，故用 {@link
   * MarketZoneBook#zoneOfHex(EconomyDayView, HexCoord)} 求区。没有区 / 没有这一行 ⇒ null（"这格没有市场"是合法状态，不是故障）。
   */
  static MarketTable marketForHex(EconomyDayView base, HexCoord hex) {
    return MarketZoneBook.zoneOfHex(base, hex)
        .map(zone -> base.marketTables().get(zone.zoneId()))
        .orElse(null);
  }

  /**
   * ★★ <b>W9：本格里"闲置的"作业资本有多少（{@link MerchantTradeTemplate#capacityKinds()} 那一维，毫）</b> ——
   * 它是"这户就算没带工具，也买/租得到"的**物理前提**（W2 起没钱也能买 ⇒ 唯一还要问的是"有没有闲着的"）。
   *
   * <p>★ <b>同一把尺</b>：闲置判据 = {@link ModeMigrationPolicy#isIdleShare}（没有任何组织/在产 unit 在用）， 与 {@code
   * planAssetMoves}、与"同格跨产业放宽"那一条完全同源；只数**同一格**的份额（跨格搬工具要运力，本批不引入）。
   */
  static long idleToolInHex(
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionProcess> units,
      HexCoord hex) {
    Set<AssetShareId> claimed =
        ModeMigrationPolicy.claimedOwnershipStakes(enterprises, units, assetShares);
    String hexKey = IndustryHexKeys.hexKey(hex.q(), hex.r());
    List<AssetKind> capacityKinds = MerchantTradeTemplate.capacityKinds();
    long total = 0L;
    for (OwnershipStake share : assetShares.values()) {
      if (share == null
          || !capacityKinds.contains(share.asset())
          || share.quantity() <= 0L
          || !ModeMigrationPolicy.isIdleShare(share, claimed)) {
        continue;
      }
      if (IndustryHexKeys.hexKeyOf(share.industry()).filter(hexKey::equals).isEmpty()) {
        continue;
      }
      total = Math.addExact(total, share.quantity());
    }
    return total;
  }

  /**
   * ★ W7 / W7-fix：一个源户"这一批迁移能带走的**作业资本**总量" —— 判据与 {@code migrateAssetsForMove} 同源 （owner == 源户 ∧
   * 未被别的组织实际使用）。★ 只统计**可移动**资产（{@code isMobileAsset}；{@code SMALL_TOOL} 在其中）。
   *
   * <p>★★ W7-fix（用户裁定 v3.28 §7）：那一维从 {@code CATTLE} 换成 {@link
   * MerchantTradeTemplate#capacityKinds()}（= 模板的 {@code capacityPerUnit} 键集，唯一拼写点） ——
   * 牲口不再是跑商的可行性门槛，也不再是运力的来源。
   */
  static long carriedCarrierAssets(
      HouseholdEconomy row,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      HouseholdId source) {
    ActorRef sourceActor = HouseholdActors.of(source);
    Set<AssetShareId> foreignUsed = foreignUsedOwnershipStakeIds(enterprises, sourceActor);
    List<AssetKind> capacityKinds = MerchantTradeTemplate.capacityKinds();
    long total = 0L;
    for (OwnershipStake share : assetShares.values()) {
      if (share == null
          || !capacityKinds.contains(share.asset())
          || share.quantity() <= 0L
          || !share.owner().equals(sourceActor)
          || foreignUsed.contains(share.id())) {
        continue;
      }
      total = Math.addExact(total, share.quantity());
    }
    return total;
  }

  /** 被 organizer != 源户的组织在 assetSources 里实际使用的份额 id 并集（这些份额本批不搬，留原户并具名）。 */
  static Set<AssetShareId> foreignUsedOwnershipStakeIds(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises, ActorRef sourceActor) {
    Set<AssetShareId> used = new LinkedHashSet<>();
    for (ProductionEnterprise enterprise : enterprises.values()) {
      if (enterprise != null && !enterprise.organizer().equals(sourceActor)) {
        used.addAll(enterprise.assetSources());
      }
    }
    return used;
  }

  /** 整条随迁后旧 id 已删：把源户自己的组织 assetSources 里的这些 id 摘掉（部分随迁 id 仍在、数量已减，不动）。 */
  static void removeSourceEnterpriseAssetSources(
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises,
      ActorRef sourceActor,
      Set<AssetShareId> removedShareIds) {
    if (removedShareIds.isEmpty()) {
      return;
    }
    List<ProductionOrganizationId> enterpriseIds = new ArrayList<>(enterprises.keySet());
    enterpriseIds.sort(Comparator.comparing(ProductionOrganizationId::value));
    for (ProductionOrganizationId organizationId : enterpriseIds) {
      ProductionEnterprise enterprise = enterprises.get(organizationId);
      if (enterprise == null || !enterprise.organizer().equals(sourceActor)) {
        continue;
      }
      List<AssetShareId> updated = new ArrayList<>(enterprise.assetSources());
      if (!updated.removeIf(removedShareIds::contains)) {
        continue;
      }
      enterprises.put(
          organizationId,
          new ProductionEnterprise(
              enterprise.id(),
              enterprise.modeId(),
              enterprise.classPositionId(),
              enterprise.unitId(),
              enterprise.organizer(),
              enterprise.laborSources(),
              updated,
              enterprise.inputSources(),
              enterprise.outputOwnership(),
              enterprise.relationTemplateRef(),
              enterprise.status(),
              enterprise.statusReason()));
    }
  }

  /**
   * ★★ D-023：本一次 apply 内新建的目标户，其组织 assetSources 必须指向随迁后的新份额 id（含后续源并入同一新户）。 只收 operator ==
   * organizer 的份额（EconomyData 的「组织使用的份额必须由 organizer 经营」守卫）。
   */
  static void attachMigratedSharesToNewTargetEnterprises(
      Set<HouseholdId> createdTargets,
      Map<HouseholdId, List<AssetShareId>> migratedShareIdsByTarget,
      Map<HouseholdId, HouseholdEconomy> householdEconomies,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionOrganizationId, ProductionEnterprise> enterprises) {
    if (migratedShareIdsByTarget.isEmpty()) {
      return;
    }
    List<HouseholdId> targets = new ArrayList<>(migratedShareIdsByTarget.keySet());
    targets.sort(Comparator.comparing(HouseholdId::value));
    for (HouseholdId target : targets) {
      if (!createdTargets.contains(target)) {
        continue; // 已有目标户不在此路（其组织不是本批新建；随迁份额仍按 operator 计产能）
      }
      ProductionEnterprise enterprise = enterpriseOf(target, householdEconomies, enterprises);
      if (enterprise == null) {
        continue; // DISPLACED 新建户不建组织
      }
      List<AssetShareId> updated = new ArrayList<>(enterprise.assetSources());
      for (AssetShareId shareId : migratedShareIdsByTarget.get(target)) {
        OwnershipStake share = assetShares.get(shareId);
        if (share == null || !share.operator().equals(enterprise.organizer())) {
          continue;
        }
        if (!updated.contains(shareId)) {
          updated.add(shareId);
        }
      }
      if (updated.size() == enterprise.assetSources().size()) {
        continue;
      }
      enterprises.put(
          enterprise.id(),
          new ProductionEnterprise(
              enterprise.id(),
              enterprise.modeId(),
              enterprise.classPositionId(),
              enterprise.unitId(),
              enterprise.organizer(),
              enterprise.laborSources(),
              updated,
              enterprise.inputSources(),
              enterprise.outputOwnership(),
              enterprise.relationTemplateRef(),
              enterprise.status(),
              enterprise.statusReason()));
    }
  }

  /** ★ D-023 留原户资产的具名读数（瞬态 ProductionLedger；不新增持久组件、改数量为 0）。 */
  static void recordResidualAssetAudits(
      ProductionLedger.Accumulator auditLedger,
      long day,
      HouseholdId source,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<AssetShareId, String> residualReasons) {
    if (auditLedger == null || residualReasons.isEmpty()) {
      return;
    }
    List<AssetShareId> shareIds = new ArrayList<>(residualReasons.keySet());
    shareIds.sort(Comparator.comparing(AssetShareId::value));
    for (AssetShareId shareId : shareIds) {
      OwnershipStake share = assetShares.get(shareId);
      if (share == null || share.quantity() <= 0L) {
        continue; // 后来又被随迁走了：不需要“留原户”读数
      }
      auditLedger.addLiquidationAudit(
          new ProductionLedger.LiquidationAudit(
              day,
              "migration-retained-asset",
              Optional.of(source),
              Optional.empty(),
              Optional.of(shareId),
              Optional.empty(),
              share.quantity(),
              0L,
              0L,
              0L,
              residualReasons.get(shareId),
              Map.of("assetKindOrdinal", (long) share.asset().ordinal())));
    }
  }

  // ── D-023 第 5 项：新家户的完整生产关系模板 ─────────────────────────────────────────────────

  /**
   * ★★ <b>D-023 第 5 项：按目标 mode + 目标产业 regime 生成完整 {@link ProductionRules}</b>。
   *
   * <pre>
   * tenancy_fixed_kind / tenancy_share / tenancy_cash → RegimeRelations 的 tenant 档（租佃模板）
   * wage_farm                                          → feudal 档（雇农/庄园模板）
   * handicraft_workshop                                → handicraft 档（手工业模板）
   * family_farm                                        → household 档（家户自用模板）
   * merchant                                           → RegimeRelations 未登记 merchant 档 ⇒ 具名回退空规则
   * displaced                                          → 不建组织（P11.1 旁路，本方法不会被调用）
   * 其余 mode                                          → 目标产业 regime（仅当已登记）；否则具名回退
   * </pre>
   *
   * <p>★ 规则里的受方/工资/租率全部来自 {@link RegimeRelations#defaultRelation}；组织 inputSources/outputOwnership
   * 也取自同一关系。回退空规则时把原因同时写进持久 {@code relationTemplateRef} 与当天瞬态 ledger 审计，绝不静默。
   *
   * <p>★★ <b>GAP-3 同源收口</b>：模板里的 {@code ToCohort(view)} 受方必须像 E2 自动组织一样在**落盘前**用当前 rows 归一成 {@link
   * Payee.ToHousehold}（唯一视图）或退回最小自留关系；否则本关系会在**当天** harvest 时被 {@code requireCohortRows}
   * 的"视图不再是唯一身份"守卫拒绝。归一逻辑不另写一份，直接复用 {@link EconomyEnterpriseSettlement} 的唯一拼写点。
   */
}

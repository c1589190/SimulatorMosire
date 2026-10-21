package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionParticipant;
import io.mosire.simos.economy.model.ProductionRoster;
import io.mosire.simos.economy.model.ProductionRosters;
import io.mosire.simos.economy.model.TransportCapacityBatch;
import io.mosire.simos.economy.model.TransportCapacityBatchKey;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★★ <b>D-032 RC4-C：家户合并的 economy 结构引用重绑</b>。
 *
 * <p>父序列锚点：{@code
 * docs/superpowers/specs/2026-10-19-d032-standard-agriculture-architecture-design-v2.md} §1 的 <b>S2
 * 进入/迁移/组织</b>。合户本身由 RC4-D 在 S2 内调用；本类只承担其中"source 户的结构引用改为 survivor 户"这一段，不搬 LAND、不搬钱货债/人口/经验，也不改
 * production 实例 operator。
 *
 * <pre>
 * 固定顺序（全部在局部工作副本完成，最后一次性写回 sheet）：
 *   1. 非 LAND OwnershipStake 的 owner/operator 转移 + AssetShareId 映射
 *   2. ProductionRules operator/inputSupplier/residualOwner/rules[].recipient
 *   3. ProductionRoster mergeHousehold + rightsStakes 按 assetIdMapping 重键
 *   4. ProductionEnterprise organizer/laborSources/assetSources/inputSources/outputOwnership
 *   5. 全部成功后才写回 sheet 的四个工作副本
 * </pre>
 *
 * <p>★★ <b>身份边界</b>：unit id、enterprise id、relation key、roster key 与 plan 均不重派生；只改这些行内对 source
 * 家户/旧资产份额的引用。{@code asset == LAND} 且 owner/operator 命中 source ⇒ 具名 fail-closed，LAND 只能走
 * LandBook（RC4-D）。
 *
 * <p>★★ <b>原子性</b>：本类先复制 sheet 的工作副本，在副本上完成全部校验与重绑；任何异常都不会写 sheet。调用方仍须按 session 的既有失败语义丢弃整次
 * advance。
 */
public final class HouseholdMergeRebindBook {

  private static final LogChannel LOG = EventLog.channel(EconomyLog.migration());

  private HouseholdMergeRebindBook() {}

  /**
   * 一次结构重绑的只读结果。
   *
   * @param assetIdMapping 旧资产份额 id → 新资产份额 id；只含实际发生身份变化的行，按旧 id 稳定序
   * @param assetRowsRebound 发生整条转移/合并的 assetShares 行数
   * @param enterprisesRebound 内容实际发生变化的 ProductionEnterprise 行数
   * @param relationsRebound 内容实际发生变化的 ProductionRules 行数
   * @param rosterParticipantsMerged 原 household == source 的参与项数（participant 行，不是去重家户数）
   * @param changed 任一结构引用是否发生变化；false 表示没有写回 sheet
   */
  public record RebindOutcome(
      Map<AssetShareId, AssetShareId> assetIdMapping,
      int assetRowsRebound,
      int enterprisesRebound,
      int relationsRebound,
      int rosterParticipantsMerged,
      boolean changed) {

    public RebindOutcome {
      Objects.requireNonNull(assetIdMapping, "RebindOutcome.assetIdMapping 不得为 null");
      if (assetRowsRebound < 0
          || enterprisesRebound < 0
          || relationsRebound < 0
          || rosterParticipantsMerged < 0) {
        throw new IllegalArgumentException("RebindOutcome 的计数不得为负");
      }
      Map<AssetShareId, AssetShareId> copy = new LinkedHashMap<>();
      for (Map.Entry<AssetShareId, AssetShareId> entry : assetIdMapping.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("RebindOutcome.assetIdMapping 不得含 null id");
        }
        copy.put(entry.getKey(), entry.getValue());
      }
      assetIdMapping = java.util.Collections.unmodifiableMap(copy);
    }
  }

  /** 一条待执行的整行资产转移计划（不改 quantity/industry/asset/kind/location）。 */
  private record AssetTransfer(
      AssetShareId id,
      long quantity,
      ActorRef targetOwner,
      ActorRef targetOperator,
      io.mosire.simos.economy.api.asset.AssetRightKind kind) {}

  /**
   * 把 {@code source} 的全部 economy 结构引用重绑到 {@code survivor}。
   *
   * @param sheet 当刻经济工作表；只在其工作副本上写，成功前不触碰
   * @param industries production 当刻产业定义；源份额产业缺失 ⇒ 具名 fail-closed
   * @param source 被合并户
   * @param survivor 合并后保留户
   * @return 本次重绑结果；source 已无引用时 {@code changed == false}
   */
  public static RebindOutcome rebind(
      EconomyStateBuilder sheet,
      Map<IndustryId, IndustryDefinitionView> industries,
      HouseholdId source,
      HouseholdId survivor) {
    Objects.requireNonNull(sheet, "HouseholdMergeRebindBook.rebind 的 sheet 不得为 null");
    Objects.requireNonNull(industries, "HouseholdMergeRebindBook.rebind 的 industries 不得为 null");
    Objects.requireNonNull(source, "HouseholdMergeRebindBook.rebind 的 source 不得为 null");
    Objects.requireNonNull(survivor, "HouseholdMergeRebindBook.rebind 的 survivor 不得为 null");
    if (source.equals(survivor)) {
      return emptyOutcome();
    }

    ActorRef sourceActor = HouseholdActors.of(source);
    ActorRef survivorActor = HouseholdActors.of(survivor);

    // ── 1) 资产份额：先在稳定序上生成完整转移计划，再走 OwnershipStakeBook 的唯一写口 ───────
    LinkedHashMap<AssetShareId, OwnershipStake> stagedShares =
        new LinkedHashMap<>(sheet.assetShares());
    Map<AssetShareId, AssetShareId> assetIdMapping =
        transferSourceStakes(stagedShares, industries, sourceActor, survivorActor);

    // ── 2) 生产关系：operator / inputSupplier / residualOwner / rules[].recipient ───────────
    LinkedHashMap<ProductionUnitId, ProductionRules> stagedRelations =
        new LinkedHashMap<>(sheet.relations());
    int relationsRebound =
        remapRelations(stagedRelations, source, survivor, sourceActor, survivorActor);

    // ── 3) 名册：mergeHousehold 后把 rightsStakes 从旧 asset id 换成新 asset id ────────────
    LinkedHashMap<ProductionUnitId, ProductionRoster> stagedRosters =
        new LinkedHashMap<>(sheet.productionRosters());
    int rosterParticipantsMerged = countSourceParticipants(stagedRosters, source);
    Map<ProductionUnitId, ProductionRoster> mergedRosters =
        ProductionRosters.mergeHousehold(stagedRosters, source, survivor);
    LinkedHashMap<ProductionUnitId, ProductionRoster> remappedRosters =
        remapRosterRights(mergedRosters, assetIdMapping, stagedShares);

    // ── 4) 企业：organizer / laborSources / assetSources / inputSources / outputOwnership ──
    LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> stagedEnterprises =
        new LinkedHashMap<>(sheet.productionOrganizations());
    int enterprisesRebound =
        remapEnterprises(
            stagedEnterprises,
            remappedRosters,
            assetIdMapping,
            stagedShares,
            source,
            survivor,
            sourceActor,
            survivorActor);

    boolean changed =
        !assetIdMapping.isEmpty()
            || relationsRebound > 0
            || rosterParticipantsMerged > 0
            || enterprisesRebound > 0
            || !sameRosters(stagedRosters, remappedRosters);
    if (!changed) {
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            LogEvent.of(
                "HOUSEHOLD_STRUCTURAL_REBIND_NOOP",
                EconomyLogSource.ECONOMY_MIGRATION,
                "source",
                source.value(),
                "survivor",
                survivor.value()));
      }
      return emptyOutcome();
    }

    // ── 5) 全部成功后才写回 sheet 工作副本；任何前面的失败都不会产生半状态 ─────────────────
    replace(sheet.assetShares(), stagedShares);
    replace(sheet.relations(), stagedRelations);
    replace(sheet.productionRosters(), remappedRosters);
    replace(sheet.productionOrganizations(), stagedEnterprises);

    RebindOutcome outcome =
        new RebindOutcome(
            assetIdMapping,
            assetIdMapping.size(),
            enterprisesRebound,
            relationsRebound,
            rosterParticipantsMerged,
            true);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          LogEvent.of(
              "HOUSEHOLD_STRUCTURAL_REBIND_APPLIED",
              EconomyLogSource.ECONOMY_MIGRATION,
              "source",
              source.value(),
              "survivor",
              survivor.value(),
              "assetRows",
              outcome.assetRowsRebound(),
              "enterprises",
              outcome.enterprisesRebound(),
              "relations",
              outcome.relationsRebound(),
              "rosters",
              outcome.rosterParticipantsMerged()));
    }
    return outcome;
  }

  /**
   * ★ <b>C6 唯一扩展点：家户级 post-state rebind。</b>
   *
   * <p>C2 尚无 {@code TransportCapacityBatch}，因此本方法是显式空实现。C6 增加批次持久组件后，<b>必须且只能</b> 在本方法内把 {@code
   * source} 的当前 marketRoundId 批次按 {@code survivorHomeHex} 重键到 {@code survivor}；同一 {@code
   * (marketRoundId, homeHex)} 的多条批次相加 {@code capacityWorkMilli}，不同 homeHex 保留各自的键。C2 不得预造 batch
   * 类型、字段、Codec 或占位值。
   *
   * @param sheet 当刻经济工作表
   * @param source 被合并户
   * @param survivor 合并后保留户
   * @param survivorHomeHex survivor 所在 hex（C6 批次重键/容量相加的 homeHex）
   * @return 因本扩展点实际重绑的行数；C2 固定为 0
   */
  public static int rebindHouseholdOwnedPostState(
      EconomyStateBuilder sheet,
      HouseholdId source,
      HouseholdId survivor,
      HexCoord survivorHomeHex) {
    Objects.requireNonNull(
        sheet, "HouseholdMergeRebindBook.rebindHouseholdOwnedPostState 的 sheet 不得为 null");
    Objects.requireNonNull(
        source, "HouseholdMergeRebindBook.rebindHouseholdOwnedPostState 的 source 不得为 null");
    Objects.requireNonNull(
        survivor, "HouseholdMergeRebindBook.rebindHouseholdOwnedPostState 的 survivor 不得为 null");
    Objects.requireNonNull(
        survivorHomeHex,
        "HouseholdMergeRebindBook.rebindHouseholdOwnedPostState 的 survivorHomeHex 不得为 null");
    if (source.equals(survivor)) {
      return 0;
    }
    // ★★★ D-032 C6：把 source 的全部 TransportCapacityBatch 重键到 survivor。
    //   · 同 (marketRoundId, homeHex) ⇒ capacityWorkMilli 相加；
    //   · 不同 homeHex ⇒ 保留各自键（批次是 hex 本地事实，不得跨 hex 汇总后异地使用）；
    //   · 不丢 source 批次；市场区漂开 = 契约故障，整次 merge 由上游丢弃。
    LinkedHashMap<TransportCapacityBatchKey, TransportCapacityBatch> batches =
        sheet.transportCapacityBatches();
    List<Map.Entry<TransportCapacityBatchKey, TransportCapacityBatch>> sourceEntries =
        new ArrayList<>();
    for (Map.Entry<TransportCapacityBatchKey, TransportCapacityBatch> entry : batches.entrySet()) {
      if (entry.getKey().household().equals(source)) {
        sourceEntries.add(Map.entry(entry.getKey(), entry.getValue()));
      }
    }
    if (sourceEntries.isEmpty()) {
      return 0;
    }
    sourceEntries.sort(Comparator.comparing(entry -> entry.getKey().toString()));
    LinkedHashMap<TransportCapacityBatchKey, TransportCapacityBatch> staged =
        new LinkedHashMap<>(batches);
    for (Map.Entry<TransportCapacityBatchKey, TransportCapacityBatch> entry : sourceEntries) {
      TransportCapacityBatchKey oldKey = entry.getKey();
      TransportCapacityBatch oldBatch = entry.getValue();
      staged.remove(oldKey);
      TransportCapacityBatchKey newKey =
          new TransportCapacityBatchKey(survivor, oldKey.homeHex(), oldKey.marketRoundId());
      TransportCapacityBatch existing = staged.get(newKey);
      long nextCapacity = oldBatch.capacityWorkMilli();
      if (existing != null) {
        if (!existing.marketZone().equals(oldBatch.marketZone())) {
          throw new IllegalStateException(
              "household-merge-transport-capacity-market-zone-mismatch: source="
                  + source.value()
                  + " survivor="
                  + survivor.value()
                  + " key="
                  + newKey
                  + " existingZone="
                  + existing.marketZone().value()
                  + " movedZone="
                  + oldBatch.marketZone().value());
        }
        nextCapacity = Math.addExact(existing.capacityWorkMilli(), oldBatch.capacityWorkMilli());
      }
      staged.put(newKey, new TransportCapacityBatch(oldBatch.marketZone(), nextCapacity));
    }
    batches.clear();
    batches.putAll(staged);
    if (LOG.isInfoEnabled()) {
      LOG.info(
          LogEvent.of(
              "HOUSEHOLD_TRANSPORT_CAPACITY_REBOUND",
              EconomyLogSource.ECONOMY_MIGRATION,
              "source",
              source.value(),
              "survivor",
              survivor.value(),
              "survivorHomeHex",
              survivorHomeHex.toString(),
              "sourceBatches",
              sourceEntries.size(),
              "resultBatches",
              batches.size()));
    }
    return sourceEntries.size();
  }

  /** 实体转移阶段：稳定序预检 → 逐行走 OwnershipStakeBook.transfer → 返回旧→新 id 映射。 */
  private static Map<AssetShareId, AssetShareId> transferSourceStakes(
      LinkedHashMap<AssetShareId, OwnershipStake> shares,
      Map<IndustryId, IndustryDefinitionView> industries,
      ActorRef sourceActor,
      ActorRef survivorActor) {
    List<OwnershipStake> ordered = new ArrayList<>(shares.values());
    ordered.sort(Comparator.comparing(stake -> stake.id().value()));

    List<AssetTransfer> transfers = new ArrayList<>();
    for (OwnershipStake stake : ordered) {
      if (!stake.id().equals(shares.get(stake.id()).id())) {
        throw new IllegalArgumentException(
            "household-merge-asset-key-id-mismatch: 键="
                + stake.id()
                + "，行内 id="
                + shares.get(stake.id()).id());
      }
      boolean ownerHit = stake.owner().equals(sourceActor);
      boolean operatorHit = stake.operator().equals(sourceActor);
      if (!ownerHit && !operatorHit) {
        continue;
      }
      if (stake.asset() == AssetKind.LAND) {
        throw new IllegalArgumentException(
            "household-merge-land-in-assetshares: LAND 只能走 LandBook（RC4-D）: share="
                + stake.id().value()
                + " owner="
                + stake.owner()
                + " operator="
                + stake.operator());
      }
      if (!industries.containsKey(stake.industry())) {
        throw new IllegalArgumentException(
            "household-merge-asset-industry-missing: share="
                + stake.id().value()
                + " industry="
                + stake.industry().value());
      }
      if (stake.quantity() <= 0L) {
        throw new IllegalArgumentException(
            "household-merge-zero-quantity-stake: OwnershipStakeBook.transfer 只接受正数量: share="
                + stake.id().value()
                + " quantity="
                + stake.quantity());
      }
      transfers.add(
          new AssetTransfer(
              stake.id(),
              stake.quantity(),
              ownerHit ? survivorActor : stake.owner(),
              operatorHit ? survivorActor : stake.operator(),
              stake.kind()));
    }

    Map<AssetShareId, AssetShareId> mapping = new LinkedHashMap<>();
    for (AssetTransfer transfer : transfers) {
      AssetShareId newId =
          OwnershipStakeBook.transfer(
              shares,
              industries,
              transfer.id(),
              transfer.quantity(),
              transfer.targetOwner(),
              transfer.targetOperator(),
              transfer.kind());
      if (newId.equals(transfer.id())) {
        throw new IllegalStateException(
            "household-merge-asset-id-not-changed: 主体已改变但份额 id 未变: " + transfer.id().value());
      }
      AssetShareId previous = mapping.put(transfer.id(), newId);
      if (previous != null && !previous.equals(newId)) {
        throw new IllegalStateException(
            "household-merge-asset-mapping-conflict: share="
                + transfer.id().value()
                + " first="
                + previous.value()
                + " second="
                + newId.value());
      }
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            LogEvent.of(
                "HOUSEHOLD_STRUCTURAL_REBIND_ASSET",
                EconomyLogSource.ECONOMY_MIGRATION,
                "oldShare",
                transfer.id().value(),
                "newShare",
                newId.value()));
      }
    }
    return mapping;
  }

  /** 生产关系阶段：unit key 不变，只重指 operator/Payee/residualOwner。 */
  private static int remapRelations(
      LinkedHashMap<ProductionUnitId, ProductionRules> relations,
      HouseholdId source,
      HouseholdId survivor,
      ActorRef sourceActor,
      ActorRef survivorActor) {
    List<Map.Entry<ProductionUnitId, ProductionRules>> ordered =
        new ArrayList<>(relations.entrySet());
    ordered.sort(Comparator.comparing(entry -> entry.getKey().value()));
    int changed = 0;
    for (Map.Entry<ProductionUnitId, ProductionRules> entry : ordered) {
      ProductionRules relation = entry.getValue();
      ActorRef operator = remapActor(relation.operator(), sourceActor, survivorActor);
      Payee inputSupplier =
          remapPayee(relation.inputSupplier(), source, survivor, sourceActor, survivorActor);
      List<CompensationRule> rules = new ArrayList<>(relation.rules().size());
      for (CompensationRule rule : relation.rules()) {
        rules.add(
            new CompensationRule(
                rule.type(),
                remapPayee(rule.recipient(), source, survivor, sourceActor, survivorActor),
                rule.pool(),
                rule.weight(),
                rule.ratePerMille(),
                rule.fixedAmount(),
                rule.commodity(),
                rule.currency(),
                rule.priority()));
      }
      ActorRef residualOwner = remapActor(relation.residualOwner(), sourceActor, survivorActor);
      ProductionRules rebound =
          operator.equals(relation.operator())
                  && inputSupplier.equals(relation.inputSupplier())
                  && residualOwner.equals(relation.residualOwner())
                  && sameRules(rules, relation.rules())
              ? relation
              : new ProductionRules(
                  relation.activity(),
                  operator,
                  inputSupplier,
                  rules,
                  residualOwner,
                  relation.laborSource());
      if (!rebound.equals(relation)) {
        changed++;
        if (LOG.isDebugEnabled()) {
          LOG.debug(
              LogEvent.of(
                  "HOUSEHOLD_STRUCTURAL_REBIND_RELATION",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "unit",
                  entry.getKey().value(),
                  "operator",
                  rebound.operator(),
                  "residualOwner",
                  rebound.residualOwner()));
        }
      }
      relations.put(entry.getKey(), rebound);
    }
    return changed;
  }

  /** 名册权利阶段：保留 mergeHousehold 的 unit/plan/participant 语义，只替换旧 asset id。 */
  private static LinkedHashMap<ProductionUnitId, ProductionRoster> remapRosterRights(
      Map<ProductionUnitId, ProductionRoster> rosters,
      Map<AssetShareId, AssetShareId> assetIdMapping,
      Map<AssetShareId, OwnershipStake> stagedShares) {
    LinkedHashMap<ProductionUnitId, ProductionRoster> result = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, ProductionRoster> entry : rosters.entrySet()) {
      ProductionRoster roster = entry.getValue();
      List<ProductionParticipant> participants = new ArrayList<>(roster.participants().size());
      boolean rosterChanged = false;
      for (ProductionParticipant participant : roster.participants()) {
        List<AssetShareId> rights = new ArrayList<>(participant.rightsStakes().size());
        Set<AssetShareId> seen = new LinkedHashSet<>();
        for (AssetShareId oldStake : participant.rightsStakes()) {
          AssetShareId mapped = assetIdMapping.getOrDefault(oldStake, oldStake);
          if (!stagedShares.containsKey(mapped)) {
            throw new IllegalArgumentException(
                "household-merge-roster-rights-stake-dangling: unit="
                    + roster.unit().value()
                    + " household="
                    + participant.household().value()
                    + " oldShare="
                    + oldStake.value()
                    + " mappedShare="
                    + mapped.value());
          }
          if (seen.add(mapped)) {
            rights.add(mapped);
          }
        }
        if (!rights.equals(participant.rightsStakes())) {
          rosterChanged = true;
        }
        participants.add(
            new ProductionParticipant(
                participant.household(),
                participant.role(),
                participant.organizer(),
                participant.plannedLaborMilliPerDay(),
                rights,
                participant.configuredWeight()));
      }
      ProductionRoster rebound =
          rosterChanged ? new ProductionRoster(roster.unit(), roster.plan(), participants) : roster;
      result.put(entry.getKey(), rebound);
    }
    return result;
  }

  /** 企业阶段：roster 是 laborSources 的派生权威，资产名册已重键后再同步企业引用。 */
  private static int remapEnterprises(
      LinkedHashMap<ProductionOrganizationId, ProductionEnterprise> enterprises,
      Map<ProductionUnitId, ProductionRoster> remappedRosters,
      Map<AssetShareId, AssetShareId> assetIdMapping,
      Map<AssetShareId, OwnershipStake> stagedShares,
      HouseholdId source,
      HouseholdId survivor,
      ActorRef sourceActor,
      ActorRef survivorActor) {
    List<Map.Entry<ProductionOrganizationId, ProductionEnterprise>> ordered =
        new ArrayList<>(enterprises.entrySet());
    ordered.sort(Comparator.comparing(entry -> entry.getKey().value()));
    int changed = 0;
    for (Map.Entry<ProductionOrganizationId, ProductionEnterprise> entry : ordered) {
      ProductionEnterprise enterprise = entry.getValue();
      ActorRef organizer = remapActor(enterprise.organizer(), sourceActor, survivorActor);
      List<HouseholdId> laborSources = remapHouseholds(enterprise.laborSources(), source, survivor);
      if (enterprise.unitId().isPresent()) {
        ProductionRoster roster = remappedRosters.get(enterprise.unitId().get());
        if (roster != null) {
          List<HouseholdId> derived = roster.households();
          if (!new LinkedHashSet<>(laborSources).equals(new LinkedHashSet<>(derived))) {
            throw new IllegalArgumentException(
                "household-merge-enterprise-roster-mismatch: org="
                    + entry.getKey().value()
                    + " unit="
                    + enterprise.unitId().get().value()
                    + " enterpriseHouseholds="
                    + laborSources
                    + " rosterHouseholds="
                    + derived);
          }
          // roster.households() 是规范稳定序；用它保证同步点逐值一致。
          laborSources = derived;
        }
      }

      List<AssetShareId> assetSources = new ArrayList<>(enterprise.assetSources().size());
      Set<AssetShareId> seenAssets = new LinkedHashSet<>();
      for (AssetShareId oldShare : enterprise.assetSources()) {
        AssetShareId mapped = assetIdMapping.getOrDefault(oldShare, oldShare);
        if (!stagedShares.containsKey(mapped)) {
          throw new IllegalArgumentException(
              "household-merge-enterprise-asset-source-dangling: org="
                  + entry.getKey().value()
                  + " oldShare="
                  + oldShare.value()
                  + " mappedShare="
                  + mapped.value());
        }
        if (seenAssets.add(mapped)) {
          assetSources.add(mapped);
        }
      }

      List<Payee> inputSources = new ArrayList<>(enterprise.inputSources().size());
      for (Payee payee : enterprise.inputSources()) {
        inputSources.add(remapPayee(payee, source, survivor, sourceActor, survivorActor));
      }
      Payee outputOwnership =
          remapPayee(enterprise.outputOwnership(), source, survivor, sourceActor, survivorActor);

      ProductionEnterprise rebound =
          organizer.equals(enterprise.organizer())
                  && laborSources.equals(enterprise.laborSources())
                  && assetSources.equals(enterprise.assetSources())
                  && inputSources.equals(enterprise.inputSources())
                  && outputOwnership.equals(enterprise.outputOwnership())
              ? enterprise
              : new ProductionEnterprise(
                  enterprise.id(),
                  enterprise.modeId(),
                  enterprise.classPositionId(),
                  enterprise.unitId(),
                  organizer,
                  laborSources,
                  assetSources,
                  inputSources,
                  outputOwnership,
                  enterprise.relationTemplateRef(),
                  enterprise.status(),
                  enterprise.statusReason());
      if (!rebound.equals(enterprise)) {
        changed++;
        if (LOG.isDebugEnabled()) {
          LOG.debug(
              LogEvent.of(
                  "HOUSEHOLD_STRUCTURAL_REBIND_ENTERPRISE",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "organization",
                  entry.getKey().value(),
                  "unit",
                  enterprise.unitId().map(ProductionUnitId::value).orElse(""),
                  "laborSources",
                  rebound.laborSources().size(),
                  "assetSources",
                  rebound.assetSources().size()));
        }
      }
      enterprises.put(entry.getKey(), rebound);
    }
    return changed;
  }

  /** 家户列表映射：source→survivor、保序去重；不重派生任何 id。 */
  private static List<HouseholdId> remapHouseholds(
      List<HouseholdId> households, HouseholdId source, HouseholdId survivor) {
    List<HouseholdId> result = new ArrayList<>(households.size());
    Set<HouseholdId> seen = new LinkedHashSet<>();
    for (HouseholdId household : households) {
      HouseholdId mapped = household.equals(source) ? survivor : household;
      if (seen.add(mapped)) {
        result.add(mapped);
      }
    }
    return result;
  }

  /** ActorRef 只映射本户 actor 的唯一拼写；其他主体逐值保留。 */
  private static ActorRef remapActor(ActorRef actor, ActorRef sourceActor, ActorRef survivorActor) {
    return actor.equals(sourceActor) ? survivorActor : actor;
  }

  /** Payee 三个变体都必须穷尽处理；ToCohort 只映射可逆的 legacy source 视图。 */
  private static Payee remapPayee(
      Payee payee,
      HouseholdId source,
      HouseholdId survivor,
      ActorRef sourceActor,
      ActorRef survivorActor) {
    return switch (payee) {
      case Payee.ToActor toActor ->
          toActor.actor().equals(sourceActor) ? new Payee.ToActor(survivorActor) : toActor;
      case Payee.ToHousehold toHousehold ->
          toHousehold.household().equals(source) ? new Payee.ToHousehold(survivor) : toHousehold;
      case Payee.ToCohort toCohort ->
          HouseholdIds.legacyView(source).map(view -> view.equals(toCohort.cohort())).orElse(false)
              ? new Payee.ToHousehold(survivor)
              : toCohort;
    };
  }

  private static boolean sameRules(List<CompensationRule> left, List<CompensationRule> right) {
    if (left.size() != right.size()) {
      return false;
    }
    for (int index = 0; index < left.size(); index++) {
      if (!left.get(index).equals(right.get(index))) {
        return false;
      }
    }
    return true;
  }

  private static boolean sameRosters(
      Map<ProductionUnitId, ProductionRoster> left, Map<ProductionUnitId, ProductionRoster> right) {
    if (!left.keySet().equals(right.keySet())) {
      return false;
    }
    for (Map.Entry<ProductionUnitId, ProductionRoster> entry : left.entrySet()) {
      if (!entry.getValue().equals(right.get(entry.getKey()))) {
        return false;
      }
    }
    return true;
  }

  private static int countSourceParticipants(
      Map<ProductionUnitId, ProductionRoster> rosters, HouseholdId source) {
    int count = 0;
    for (ProductionRoster roster : rosters.values()) {
      count = Math.addExact(count, roster.participantsOf(source).size());
    }
    return count;
  }

  private static <K, V> void replace(LinkedHashMap<K, V> target, Map<K, V> values) {
    target.clear();
    target.putAll(values);
  }

  private static RebindOutcome emptyOutcome() {
    return new RebindOutcome(Map.of(), 0, 0, 0, 0, false);
  }
}

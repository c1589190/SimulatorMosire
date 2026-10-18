package io.mosire.simos.economy.time;

import static io.mosire.simos.economy.time.AssetOccupancyBook.GRANT_ORDER;
import static io.mosire.simos.economy.time.AssetOccupancyBook.KIND_ORDER;
import static io.mosire.simos.economy.time.AssetOccupancyBook.LOG;
import static io.mosire.simos.economy.time.AssetOccupancyBook.SHARE_ORDER;
import static io.mosire.simos.economy.time.AssetOccupancyBook.UNIT_ORDER;
import static io.mosire.simos.economy.time.AssetOccupancyReadout.activeGrants;
import static io.mosire.simos.economy.time.AssetOccupancyReadout.addExact;
import static io.mosire.simos.economy.time.AssetOccupancyReadout.contractFault;
import static io.mosire.simos.economy.time.AssetOccupancyReadout.debug;
import static io.mosire.simos.economy.time.AssetOccupancyReadout.logBudget;
import static io.mosire.simos.economy.time.AssetOccupancyReadout.multiplyExact;
import static io.mosire.simos.economy.time.AssetOccupancyReadout.subtractExact;
import static io.mosire.simos.economy.time.AssetOccupancyReadout.validatedShares;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.asset.AssetRightKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.AssetOccupancyStatus;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 资产占用拆分 helper（由 AssetOccupancyBook 抽取；行为不变）。 */
final class AssetOccupancyAllocator {

  private AssetOccupancyAllocator() {}

  /**
   * 带 acceptedRightKinds 的执行入口（D4 §5.3 第 1 条）：map 缺键 = 调用方未提供权益过滤（接受全部合法份额）；空集合 = 该 unit 不可产。
   *
   * @param acceptedRightKindsByUnit unit → 方案接受的权益性质集合；只影响供给匹配，不改变所有权
   */
  public static AssetOccupancyBudget allocate(
      Collection<AssetOccupancyRequest> requests,
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> existingGrants,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, Set<AssetRightKind>> acceptedRightKindsByUnit,
      Map<ProductionUnitId, ProductionProcess> existingUnits,
      long currentTick) {
    return allocate(
        requests,
        existingGrants,
        assetShares,
        acceptedRightKindsByUnit,
        existingUnits,
        currentTick,
        ProductionLandFilter.none());
  }

  /**
   * ★★★ <b>D-032 P3b：带“本轮新取得土地排除集”的执行入口</b>。
   *
   * <p>★ {@code excludedShareIds} 只影响 <b>scope 供给匹配 / 资产量计算</b>：被排除的 LAND 份额不进入 {@code
   * scopeShares}/{@code kindShares}、也不把它的 ACTIVE grant 计为本轮 committed；但 {@code assetShares} 表
   * <b>原样保留</b>（{@link AssetOccupancyReadout#activeGrants} 仍能解析 grant 引用的份额，不会误报 {@code
   * asset-occupancy-share-missing}）。
   *
   * <p>★ 空集（{@link ProductionLandFilter#none()}）⇒ 与 P2 逐值相同：不跳过任何份额、不改变任何算式。
   *
   * @param excludedShareIds 本轮 M2/M4 取得的新土地块（空集 = 不排除）
   */
  public static AssetOccupancyBudget allocate(
      Collection<AssetOccupancyRequest> requests,
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> existingGrants,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, Set<AssetRightKind>> acceptedRightKindsByUnit,
      Map<ProductionUnitId, ProductionProcess> existingUnits,
      long currentTick,
      Set<AssetShareId> excludedShareIds) {
    Objects.requireNonNull(requests, "requests 不得为 null（无请求用空集合）");
    Objects.requireNonNull(existingGrants, "existingGrants 不得为 null（无占用用空表）");
    Objects.requireNonNull(acceptedRightKindsByUnit, "acceptedRightKindsByUnit 不得为 null（不过滤用空表）");
    Objects.requireNonNull(existingUnits, "existingUnits 不得为 null（无实例用空表）");
    Set<AssetShareId> excluded = ProductionLandFilter.normalize(excludedShareIds);
    if (currentTick < 0L) {
      contractFault("asset-occupancy-invalid-tick", "currentTick 不得为负: " + currentTick, null);
    }

    Map<AssetShareId, OwnershipStake> shares = validatedShares(assetShares);
    List<AssetOccupancyGrant> grantsToRelease = new ArrayList<>();
    Map<AssetOccupancyGrantId, AssetOccupancyGrant> activeGrants =
        activeGrants(existingGrants, shares, currentTick, grantsToRelease);

    List<AssetOccupancyRequest> requestList = new ArrayList<>(requests);
    for (AssetOccupancyRequest request : requestList) {
      Objects.requireNonNull(request, "requests 不得含 null");
    }
    requestList.sort(
        Comparator.comparing((AssetOccupancyRequest request) -> request.unitId().value())
            .thenComparing(request -> request.industry().value())
            .thenComparing(request -> request.operator().kind().name())
            .thenComparing(request -> request.operator().id())
            .thenComparing(request -> request.location().value())
            .thenComparingLong(AssetOccupancyRequest::fromTick)
            .thenComparingLong(AssetOccupancyRequest::toTickExclusive));

    validateContinuations(requestList, existingGrants);
    Map<RequestGroupKey, List<AssetOccupancyRequest>> groups = groupRequests(requestList);

    Map<ProductionUnitId, Map<AssetKind, Long>> allocations = new LinkedHashMap<>();
    Map<ProductionUnitId, Map<AssetKind, Long>> committedReadout = new LinkedHashMap<>();
    Map<AssetOccupancyScope, Map<AssetKind, Long>> available = new LinkedHashMap<>();
    Map<AssetOccupancyScope, Map<AssetKind, Long>> occupied = new LinkedHashMap<>();
    Map<AssetOccupancyScope, Map<AssetKind, Long>> unallocated = new LinkedHashMap<>();
    Map<ProductionUnitId, String> rejections = new LinkedHashMap<>();
    List<AssetOccupancyGrant> grantsToWrite = new ArrayList<>();
    Set<AssetOccupancyGrantId> existingGrantIds = new LinkedHashSet<>(existingGrants.keySet());

    for (Map.Entry<RequestGroupKey, List<AssetOccupancyRequest>> groupEntry : groups.entrySet()) {
      RequestGroupKey group = groupEntry.getKey();
      List<AssetOccupancyRequest> groupRequests = groupEntry.getValue();

      Map<ProductionUnitId, AssetOccupancyRequest> requestsByUnit = new LinkedHashMap<>();
      for (AssetOccupancyRequest request : groupRequests) {
        if (requestsByUnit.putIfAbsent(request.unitId(), request) != null) {
          contractFault(
              "asset-occupancy-request-duplicate",
              "同一 unit 对同一作用域给出了两条请求: " + request.unitId().value(),
              request.unitId());
        }
      }

      List<OwnershipStake> scopeShares = new ArrayList<>();
      int excludedLandShares = 0;
      for (OwnershipStake share : shares.values()) {
        if (share.industry().equals(group.industry())
            && share.operator().equals(group.operator())
            && share.location().equals(group.location())
            && share.quantity() > 0L) {
          // ★★★ P3b：本轮新取得土地不进入供给匹配；份额表本身仍完整（见方法注）。
          if (!ProductionLandFilter.landAllowed(share, excluded)) {
            excludedLandShares++;
            continue;
          }
          scopeShares.add(share);
        }
      }
      scopeShares.sort(Comparator.comparing(OwnershipStake::id, SHARE_ORDER));
      if (excludedLandShares > 0) {
        // ★ 业务事实（不是故障）：本轮新土地被排除在生产供给之外。INFO 一条，逐 scope 具名。
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "D032_P3_LAND_FILTERED",
                    EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                    "industry",
                    group.industry().value(),
                    "operator",
                    group.operator().toString(),
                    "location",
                    group.location().value(),
                    "excludedLandShares",
                    excludedLandShares,
                    "why",
                    "land-acquired-this-round-not-usable-until-next-market-round"));
      }
      if (excludedLandShares > 0 && scopeShares.isEmpty() && !groupRequests.isEmpty()) {
        // ★★★ P3b fail-closed：该 scope 的供给**只剩本轮新土地** ⇒ 本组请求一律具名拒绝，不得静默执行空 allocation。
        //   若不在这里短路，空请求表（hasNoRequestTable）的请求会因为 kinds 集合为空而绕过 processKind 的拒绝。
        AssetOccupancyScope rejectScope =
            new AssetOccupancyScope(
                group.industry(),
                group.operator(),
                group.location(),
                AssetKind.LAND,
                group.fromTick(),
                group.toTickExclusive());
        rejectAll(
            groupRequests,
            rejectScope,
            "asset-location-scope-missing:"
                + group.location().value()
                + ":land-acquired-this-round-excluded",
            rejections);
        continue;
      }

      List<AssetOccupancyGrant> scopeActive = new ArrayList<>();
      for (AssetOccupancyGrant grant : activeGrants.values()) {
        OwnershipStake share = shares.get(grant.shareId());
        if (share.industry().equals(group.industry())
            && share.operator().equals(group.operator())
            && share.location().equals(group.location())
            && grant.overlaps(group.fromTick(), group.toTickExclusive())
            // ★★★ P3b：被排除的新土地上的 ACTIVE grant 不计入本轮 committed（供给已剔除，否则守恒式会为负）。
            && ProductionLandFilter.landAllowed(share, excluded)) {
          scopeActive.add(grant);
        }
      }
      scopeActive.sort(GRANT_ORDER);

      Set<AssetKind> kinds = new LinkedHashSet<>();
      for (AssetOccupancyRequest request : groupRequests) {
        kinds.addAll(request.requestedQuantity().keySet());
        if (request.hasNoRequestTable()
            || request.hasExplicitZeroScale()
            || request.declaresPositiveScaleWithoutQuantities()) {
          // 无请求表/显式零规模/缺请求拒绝需要看见该 scope 的全部资产种类；候选缺请求不得因为
          // 没有 requestedQuantity 的 kind 键就静默跳过。
          for (OwnershipStake share : scopeShares) {
            kinds.add(share.asset());
          }
        }
      }
      for (AssetOccupancyGrant grant : scopeActive) {
        kinds.add(shares.get(grant.shareId()).asset());
      }
      List<AssetKind> kindOrder = new ArrayList<>(kinds);
      kindOrder.sort(KIND_ORDER);

      for (AssetKind kind : kindOrder) {
        processKind(
            group,
            groupRequests,
            scopeShares,
            scopeActive,
            kind,
            acceptedRightKindsByUnit,
            allocations,
            committedReadout,
            available,
            occupied,
            unallocated,
            rejections,
            grantsToWrite,
            existingGrantIds,
            shares,
            existingUnits.keySet());
      }
    }

    AssetOccupancyBudget budget =
        new AssetOccupancyBudget(
            allocations,
            committedReadout,
            available,
            occupied,
            unallocated,
            rejections,
            grantsToWrite,
            grantsToRelease);
    logBudget(budget);
    return budget;
  }

  static void processKind(
      RequestGroupKey group,
      List<AssetOccupancyRequest> groupRequests,
      List<OwnershipStake> allScopeShares,
      List<AssetOccupancyGrant> allScopeActive,
      AssetKind kind,
      Map<ProductionUnitId, Set<AssetRightKind>> acceptedRightKindsByUnit,
      Map<ProductionUnitId, Map<AssetKind, Long>> allocations,
      Map<ProductionUnitId, Map<AssetKind, Long>> committedReadout,
      Map<AssetOccupancyScope, Map<AssetKind, Long>> available,
      Map<AssetOccupancyScope, Map<AssetKind, Long>> occupied,
      Map<AssetOccupancyScope, Map<AssetKind, Long>> unallocated,
      Map<ProductionUnitId, String> rejections,
      List<AssetOccupancyGrant> grantsToWrite,
      Set<AssetOccupancyGrantId> existingGrantIds,
      Map<AssetShareId, OwnershipStake> shares,
      Set<ProductionUnitId> existingUnitIds) {

    AssetOccupancyScope scope =
        new AssetOccupancyScope(
            group.industry(),
            group.operator(),
            group.location(),
            kind,
            group.fromTick(),
            group.toTickExclusive());

    List<AssetOccupancyRequest> kindRequests = new ArrayList<>();
    for (AssetOccupancyRequest request : groupRequests) {
      if (requestTouchesKind(request, kind)) {
        kindRequests.add(request);
      }
    }

    List<AssetOccupancyGrant> kindActive = new ArrayList<>();
    for (AssetOccupancyGrant grant : allScopeActive) {
      OwnershipStake share = shares.get(grant.shareId());
      if (share.asset() == kind) {
        kindActive.add(grant);
      }
    }
    kindActive.sort(GRANT_ORDER);

    Set<ProductionUnitId> evidenceUnits = new LinkedHashSet<>();
    for (AssetOccupancyRequest request : kindRequests) {
      evidenceUnits.add(request.unitId());
    }
    for (AssetOccupancyGrant grant : kindActive) {
      evidenceUnits.add(grant.unitId());
    }
    List<ProductionUnitId> evidenceOrder = new ArrayList<>(evidenceUnits);
    evidenceOrder.sort(UNIT_ORDER);
    Set<AssetRightKind> acceptedKinds =
        acceptedKindsFor(group, evidenceOrder, acceptedRightKindsByUnit);
    if (acceptedKinds != null && acceptedKinds.isEmpty()) {
      rejectAll(
          kindRequests, scope, "asset-right-kind-missing:" + group.industry().value(), rejections);
      return;
    }
    kindActive.removeIf(
        grant -> {
          OwnershipStake share = shares.get(grant.shareId());
          return acceptedKinds != null && !acceptedKinds.contains(share.kind());
        });

    Set<ProductionUnitId> kindUnits = new LinkedHashSet<>();
    for (AssetOccupancyRequest request : kindRequests) {
      kindUnits.add(request.unitId());
    }
    for (AssetOccupancyGrant grant : kindActive) {
      kindUnits.add(grant.unitId());
    }
    if (kindUnits.isEmpty()) {
      return;
    }
    List<ProductionUnitId> unitOrder = new ArrayList<>(kindUnits);
    unitOrder.sort(UNIT_ORDER);
    boolean singleUnitScope = unitOrder.size() == 1;

    List<OwnershipStake> kindShares = new ArrayList<>();
    for (OwnershipStake share : allScopeShares) {
      if (share.asset() == kind
          && (acceptedKinds == null || acceptedKinds.contains(share.kind()))) {
        kindShares.add(share);
      }
    }
    kindShares.sort(Comparator.comparing(OwnershipStake::id, SHARE_ORDER));

    if (kindShares.isEmpty()) {
      rejectAll(
          kindRequests,
          scope,
          "asset-location-scope-missing:" + group.location().value(),
          rejections);
      return;
    }

    long supply = 0L;
    Map<AssetShareId, Long> committedByShare = new LinkedHashMap<>();
    for (OwnershipStake share : kindShares) {
      supply = addExact(supply, share.quantity(), "supply", scope, null);
      committedByShare.put(share.id(), 0L);
    }
    Map<ProductionUnitId, Long> committedByUnit = new LinkedHashMap<>();
    long committedTotal = 0L;
    for (AssetOccupancyGrant grant : kindActive) {
      committedTotal =
          addExact(committedTotal, grant.quantity(), "committed", scope, grant.unitId());
      committedByShare.merge(grant.shareId(), grant.quantity(), Math::addExact);
      committedByUnit.merge(grant.unitId(), grant.quantity(), Math::addExact);
    }
    for (Map.Entry<AssetShareId, Long> entry : committedByShare.entrySet()) {
      OwnershipStake share = shares.get(entry.getKey());
      if (entry.getValue() > share.quantity()) {
        contractFault(
            "asset-occupancy-overcommit",
            "份额 "
                + entry.getKey().value()
                + " 的 ACTIVE 占用 "
                + entry.getValue()
                + " 超过 quantity "
                + share.quantity(),
            null);
      }
    }
    long remainingSupply = subtractExact(supply, committedTotal, "remainingSupply", scope, null);

    available.put(scope, Map.of(kind, supply));
    occupied.put(scope, Map.of(kind, committedTotal));
    for (Map.Entry<ProductionUnitId, Long> entry : committedByUnit.entrySet()) {
      committedReadout.computeIfAbsent(entry.getKey(), ignored -> new LinkedHashMap<>());
      committedReadout.get(entry.getKey()).put(kind, entry.getValue());
    }
    for (ProductionUnitId unit : unitOrder) {
      committedReadout.computeIfAbsent(unit, ignored -> new LinkedHashMap<>());
    }

    if (singleUnitScope) {
      ProductionUnitId unit = unitOrder.get(0);
      AssetOccupancyRequest request = requestFor(kindRequests, unit);
      long existing = committedByUnit.getOrDefault(unit, 0L);
      // 续占证据（previousGrantId 已由 validateContinuations 校验为同 unit 相邻上一段）也可证明
      // "这是已存在实例"，从而允许无请求表的整池兼容；候选/新实例没有这条证据。
      boolean hasExistingFact =
          existing > 0L
              || existingUnitIds.contains(unit)
              || (request != null && request.previousGrantId().isPresent());
      boolean missingRequest =
          request != null
              && (request.declaresPositiveScaleWithoutQuantities()
                  || (request.hasNoRequestTable() && !hasExistingFact));
      if (missingRequest) {
        reject(unit, scope, "asset-occupancy-request-missing:" + unit.value(), rejections);
        putUnallocated(unallocated, scope, kind, remainingSupply);
        return;
      }
      if (request == null && !hasExistingFact) {
        // 只有 ACTIVE 事实或显式请求才能进入单实例路径；本 scope 没有事实 ⇒ 不伪造整池授予。
        putUnallocated(unallocated, scope, kind, remainingSupply);
        return;
      }
      if (request != null && request.hasExplicitZeroScale()) {
        // D4 §5.2：显式 requestedScale=0 = 本 tick 不请求资产，不是缺请求，也不授予整池。
        putAllocation(allocations, unit, kind, existing);
        putUnallocated(unallocated, scope, kind, remainingSupply);
        debug(
            "ASSET_OCCUPANCY_SCOPE_BUDGET",
            scope,
            "units",
            1,
            "supply",
            supply,
            "committed",
            committedTotal,
            "demand",
            0,
            "newGrant",
            0,
            "unallocated",
            remainingSupply,
            "mode",
            "explicit-zero-scale");
        return;
      }

      long newQuantity = remainingSupply;
      long granted =
          grantAcrossShares(
              scope,
              unit,
              newQuantity,
              kindShares,
              committedByShare,
              occupiedSharesOf(kindActive, unit),
              existingGrantIds,
              grantsToWrite);
      if (granted < newQuantity) {
        // 同 unit/share 已有重叠 ACTIVE 时不能再建第二个重叠 grant；差量留在 unallocated。
        AssetShareId occupiedShare = firstOccupiedShareOf(kindActive, unit);
        if (occupiedShare != null) {
          reject(
              unit,
              scope,
              "asset-occupancy-overlap:" + occupiedShare.value() + ":" + unit.value(),
              rejections);
        }
        debug(
            "ASSET_OCCUPANCY_SINGLE_UNIT_PARTIAL",
            scope,
            "unit",
            unit.value(),
            "demand",
            newQuantity,
            "granted",
            granted,
            "unallocated",
            remainingSupply - granted);
      }
      long totalAllocated = addExact(existing, granted, "singleUnitAllocation", scope, unit);
      putAllocation(allocations, unit, kind, totalAllocated);
      putUnallocated(
          unallocated,
          scope,
          kind,
          subtractExact(remainingSupply, granted, "unallocated", scope, unit));
      debug(
          "ASSET_OCCUPANCY_SCOPE_BUDGET",
          scope,
          "units",
          1,
          "supply",
          supply,
          "committed",
          committedTotal,
          "demand",
          newQuantity,
          "newGrant",
          granted,
          "unallocated",
          remainingSupply - granted,
          "mode",
          "single-unit-whole-pool");
      return;
    }

    Map<ProductionUnitId, Long> demands = new LinkedHashMap<>();
    long totalDemand = 0L;
    for (ProductionUnitId unitId : unitOrder) {
      AssetOccupancyRequest request = requestFor(kindRequests, unitId);
      if (request == null) {
        continue;
      }
      if (request.declaresPositiveScaleWithoutQuantities() || request.hasNoRequestTable()) {
        reject(unitId, scope, "asset-occupancy-request-missing:" + unitId.value(), rejections);
        continue;
      }
      long requested = request.requestedQuantityOf(kind);
      long existing = committedByUnit.getOrDefault(unitId, 0L);
      long demand = Math.max(0L, subtractExact(requested, existing, "demand", scope, unitId));
      if (demand > 0L) {
        demands.put(unitId, demand);
        totalDemand = addExact(totalDemand, demand, "totalDemand", scope, unitId);
      }
    }

    Map<ProductionUnitId, Long> newGrants = new LinkedHashMap<>();
    if (totalDemand > 0L && remainingSupply > 0L) {
      if (remainingSupply >= totalDemand) {
        for (Map.Entry<ProductionUnitId, Long> entry : demands.entrySet()) {
          newGrants.put(entry.getKey(), entry.getValue());
        }
      } else {
        Map<ProductionUnitId, Long> base = new LinkedHashMap<>();
        Map<ProductionUnitId, Long> remainders = new LinkedHashMap<>();
        long assignedBase = 0L;
        for (Map.Entry<ProductionUnitId, Long> entry : demands.entrySet()) {
          long numerator =
              multiplyExact(
                  remainingSupply,
                  entry.getValue(),
                  "proportionalNumerator",
                  scope,
                  entry.getKey());
          long baseQuantity = Math.floorDiv(numerator, totalDemand);
          long remainder =
              subtractExact(
                  numerator,
                  multiplyExact(
                      baseQuantity, totalDemand, "proportionalBaseProduct", scope, entry.getKey()),
                  "proportionalRemainder",
                  scope,
                  entry.getKey());
          base.put(entry.getKey(), baseQuantity);
          remainders.put(entry.getKey(), remainder);
          assignedBase =
              addExact(assignedBase, baseQuantity, "assignedBase", scope, entry.getKey());
        }
        long left = subtractExact(remainingSupply, assignedBase, "proportionalLeft", scope, null);
        List<ProductionUnitId> ranked = new ArrayList<>(demands.keySet());
        ranked.sort(
            Comparator.comparing((ProductionUnitId unit) -> remainders.get(unit))
                .reversed()
                .thenComparing(ProductionUnitId::value));
        for (ProductionUnitId unit : ranked) {
          long quantity = base.get(unit);
          if (left > 0L) {
            quantity = addExact(quantity, 1L, "proportionalExtra", scope, unit);
            left--;
          }
          if (quantity > demands.get(unit)) {
            contractFault(
                "asset-occupancy-proportional-cap",
                "比例分配超过 unit 需求: unit=" + unit.value() + " quantity=" + quantity,
                unit);
          }
          newGrants.put(unit, quantity);
        }
        debug(
            "ASSET_OCCUPANCY_PROPORTIONAL_REMAINDER",
            scope,
            "supply",
            supply,
            "committed",
            committedTotal,
            "remainingSupply",
            remainingSupply,
            "demand",
            totalDemand,
            "left",
            left,
            "remainders",
            remainders);
      }
    }

    long totalNewActual = 0L;
    for (Map.Entry<ProductionUnitId, Long> entry : demands.entrySet()) {
      ProductionUnitId unit = entry.getKey();
      long planned = newGrants.getOrDefault(unit, 0L);
      long granted =
          grantAcrossShares(
              scope,
              unit,
              planned,
              kindShares,
              committedByShare,
              occupiedSharesOf(kindActive, unit),
              existingGrantIds,
              grantsToWrite);
      totalNewActual = addExact(totalNewActual, granted, "totalNewActual", scope, unit);
      long existing = committedByUnit.getOrDefault(unit, 0L);
      long totalAllocated = addExact(existing, granted, "multiUnitAllocation", scope, unit);
      putAllocation(allocations, unit, kind, totalAllocated);
      if (granted < planned) {
        AssetShareId occupiedShare = firstOccupiedShareOf(kindActive, unit);
        if (occupiedShare != null) {
          reject(
              unit,
              scope,
              "asset-occupancy-overlap:" + occupiedShare.value() + ":" + unit.value(),
              rejections);
        }
        debug(
            "ASSET_OCCUPANCY_PARTIAL_GRANT",
            scope,
            "unit",
            unit.value(),
            "planned",
            planned,
            "granted",
            granted,
            "why",
            "share-already-overlaps-same-unit-interval");
      }
      if (totalAllocated <= 0L) {
        reject(unit, scope, "asset-short:" + kind.name(), rejections);
      }
    }
    // 有 ACTIVE 但没有请求的 unit 也必须有 committed 读数（保持先认已占）。
    for (ProductionUnitId unit : unitOrder) {
      if (!demands.containsKey(unit)) {
        long existing = committedByUnit.getOrDefault(unit, 0L);
        if (existing > 0L) {
          putAllocation(allocations, unit, kind, existing);
        }
      }
    }

    long remaining =
        subtractExact(
            subtractExact(supply, committedTotal, "unallocatedBase", scope, null),
            totalNewActual,
            "unallocatedAfterGrants",
            scope,
            null);
    putUnallocated(unallocated, scope, kind, remaining);
    debug(
        "ASSET_OCCUPANCY_SCOPE_BUDGET",
        scope,
        "units",
        unitOrder.size(),
        "supply",
        supply,
        "committed",
        committedTotal,
        "demand",
        totalDemand,
        "newGrant",
        totalNewActual,
        "unallocated",
        remaining,
        "mode",
        "multi-unit-proportional");
  }

  static long grantAcrossShares(
      AssetOccupancyScope scope,
      ProductionUnitId unit,
      long quantity,
      List<OwnershipStake> kindShares,
      Map<AssetShareId, Long> committedByShare,
      Set<AssetShareId> occupiedSharesForUnit,
      Set<AssetOccupancyGrantId> existingGrantIds,
      List<AssetOccupancyGrant> grantsToWrite) {
    if (quantity <= 0L) {
      return 0L;
    }
    long remaining = quantity;
    for (OwnershipStake share : kindShares) {
      if (remaining <= 0L) {
        break;
      }
      if (occupiedSharesForUnit.contains(share.id())
          || hasOverlappingGrantForUnit(grantsToWrite, unit, share.id(), scope)) {
        continue;
      }
      long alreadyCommitted = committedByShare.getOrDefault(share.id(), 0L);
      long availableOnShare = Math.max(0L, share.quantity() - alreadyCommitted);
      if (availableOnShare <= 0L) {
        continue;
      }
      // ★★★ W5-F：逐 grant 硬上限 = **这一条** share 的 quantity（不是只保证同 share 总和）。
      //   请求量大于可用份额时只取 availableOnShare，剩余留在 remaining ⇒ 由调用方记 unallocated /
      //   asset-short:<kind>。显式再夹一次 share.quantity，避免未来任何 committed 读口漂移造出非法 grant。
      long take = Math.min(remaining, Math.min(availableOnShare, share.quantity()));
      if (take <= 0L || take > share.quantity()) {
        contractFault(
            "asset-occupancy-quantity-exceeds-share",
            "份额 "
                + share.id().value()
                + " 的本次授予量 "
                + take
                + " 超过 share.quantity "
                + share.quantity(),
            unit);
      }
      AssetOccupancyGrantId id = AssetOccupancyGrantId.idOf(unit, share.id(), scope.fromTick());
      if (existingGrantIds.contains(id)) {
        contractFault(
            "asset-occupancy-grant-id-collision",
            "确定性 grant id 已存在（同一 unit/share/fromTick 不得重复授予）: " + id.value(),
            unit);
      }
      existingGrantIds.add(id);
      AssetOccupancyGrant grant =
          new AssetOccupancyGrant(
              id,
              share.id(),
              unit,
              take,
              scope.fromTick(),
              scope.toTickExclusive(),
              AssetOccupancyStatus.ACTIVE,
              -1L);
      grantsToWrite.add(grant);
      committedByShare.merge(share.id(), take, Math::addExact);
      remaining -= take;
      EventLog.channel(LOG)
          .trace(
              LogEvent.of(
                  "ASSET_OCCUPANCY_SHARE_SPLIT",
                  EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                  "grant",
                  id.value(),
                  "unit",
                  unit.value(),
                  "share",
                  share.id().value(),
                  "location",
                  share.location().value(),
                  "kind",
                  share.asset().name(),
                  "quantity",
                  take,
                  "shareRemainingAfter",
                  share.quantity() - committedByShare.get(share.id())));
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ASSET_OCCUPANCY_GRANT_CREATED",
                  EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                  "grant",
                  id.value(),
                  "unit",
                  unit.value(),
                  "share",
                  share.id().value(),
                  "location",
                  share.location().value(),
                  "kind",
                  share.asset().name(),
                  "quantity",
                  take,
                  "fromTick",
                  scope.fromTick(),
                  "toTickExclusive",
                  scope.toTickExclusive()));
    }
    return quantity - remaining;
  }

  static AssetShareId firstOccupiedShareOf(
      List<AssetOccupancyGrant> grants, ProductionUnitId unit) {
    for (AssetOccupancyGrant grant : grants) {
      if (grant.unitId().equals(unit)) {
        return grant.shareId();
      }
    }
    return null;
  }

  static Set<AssetShareId> occupiedSharesOf(
      List<AssetOccupancyGrant> grants, ProductionUnitId unit) {
    Set<AssetShareId> shares = new LinkedHashSet<>();
    for (AssetOccupancyGrant grant : grants) {
      if (grant.unitId().equals(unit)) {
        shares.add(grant.shareId());
      }
    }
    return shares;
  }

  static boolean hasOverlappingGrantForUnit(
      List<AssetOccupancyGrant> grantsToWrite,
      ProductionUnitId unit,
      AssetShareId share,
      AssetOccupancyScope scope) {
    for (AssetOccupancyGrant grant : grantsToWrite) {
      if (grant.unitId().equals(unit)
          && grant.shareId().equals(share)
          && grant.overlaps(scope.fromTick(), scope.toTickExclusive())) {
        return true;
      }
    }
    // 本批之前的 ACTIVE 由 committedByShare 计入；但同 unit 同 share 重叠时不能再建第二个 grant。
    // 调用方无此列表，故由 grantAcrossShares 的调用前 committed 状态无法区分；见调用方 filter。
    return false;
  }

  static void validateContinuations(
      List<AssetOccupancyRequest> requests,
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> existingGrants) {
    for (AssetOccupancyRequest request : requests) {
      if (request.previousGrantId().isEmpty()) {
        continue;
      }
      AssetOccupancyGrant previous = existingGrants.get(request.previousGrantId().get());
      if (previous == null
          || !previous.unitId().equals(request.unitId())
          || previous.toTickExclusive() != request.fromTick()) {
        contractFault(
            "asset-occupancy-continuation-mismatch",
            "previousGrantId 不是同 unit 的相邻上一段: unit="
                + request.unitId().value()
                + " previous="
                + request.previousGrantId().get().value(),
            request.unitId());
      }
    }
  }

  static Map<RequestGroupKey, List<AssetOccupancyRequest>> groupRequests(
      List<AssetOccupancyRequest> requests) {
    Map<RequestGroupKey, List<AssetOccupancyRequest>> groups = new LinkedHashMap<>();
    for (AssetOccupancyRequest request : requests) {
      RequestGroupKey key =
          new RequestGroupKey(
              request.industry(),
              request.operator(),
              request.location(),
              request.fromTick(),
              request.toTickExclusive());
      groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(request);
    }
    List<RequestGroupKey> ordered = new ArrayList<>(groups.keySet());
    ordered.sort(RequestGroupKey.STABLE_ORDER);
    Map<RequestGroupKey, List<AssetOccupancyRequest>> sorted = new LinkedHashMap<>();
    for (RequestGroupKey key : ordered) {
      sorted.put(key, groups.get(key));
    }
    return sorted;
  }

  static boolean requestTouchesKind(AssetOccupancyRequest request, AssetKind kind) {
    return request.hasExplicitQuantity(kind)
        || request.hasNoRequestTable()
        || request.hasExplicitZeroScale()
        || request.declaresPositiveScaleWithoutQuantities();
  }

  static Set<AssetRightKind> acceptedKindsFor(
      RequestGroupKey group,
      List<ProductionUnitId> unitOrder,
      Map<ProductionUnitId, Set<AssetRightKind>> acceptedRightKindsByUnit) {
    for (ProductionUnitId unit : unitOrder) {
      Set<AssetRightKind> accepted = acceptedRightKindsByUnit.get(unit);
      if (accepted != null) {
        return accepted;
      }
    }
    return null;
  }

  static AssetOccupancyRequest requestFor(
      List<AssetOccupancyRequest> requests, ProductionUnitId unit) {
    for (AssetOccupancyRequest request : requests) {
      if (request.unitId().equals(unit)) {
        return request;
      }
    }
    return null;
  }

  static void putAllocation(
      Map<ProductionUnitId, Map<AssetKind, Long>> allocations,
      ProductionUnitId unit,
      AssetKind kind,
      long quantity) {
    if (quantity <= 0L) {
      return;
    }
    allocations.computeIfAbsent(unit, ignored -> new LinkedHashMap<>()).put(kind, quantity);
  }

  static void putUnallocated(
      Map<AssetOccupancyScope, Map<AssetKind, Long>> unallocated,
      AssetOccupancyScope scope,
      AssetKind kind,
      long quantity) {
    if (quantity < 0L) {
      contractFault(
          "asset-occupancy-negative-unallocated",
          "unallocated 不得为负: " + scope + " " + kind + " = " + quantity,
          null);
    }
    unallocated.computeIfAbsent(scope, ignored -> new LinkedHashMap<>()).put(kind, quantity);
  }

  static void rejectAll(
      List<AssetOccupancyRequest> requests,
      AssetOccupancyScope scope,
      String reason,
      Map<ProductionUnitId, String> rejections) {
    for (AssetOccupancyRequest request : requests) {
      reject(request.unitId(), scope, reason, rejections);
    }
  }

  static void reject(
      ProductionUnitId unit,
      AssetOccupancyScope scope,
      String reason,
      Map<ProductionUnitId, String> rejections) {
    String previous = rejections.putIfAbsent(unit, reason);
    if (previous == null) {
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "ASSET_OCCUPANCY_REJECTED",
                  EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                  "unit",
                  unit.value(),
                  "industry",
                  scope.industry().value(),
                  "operator",
                  scope.operator().toString(),
                  "location",
                  scope.location().value(),
                  "kind",
                  scope.assetKind().name(),
                  "fromTick",
                  scope.fromTick(),
                  "toTickExclusive",
                  scope.toTickExclusive(),
                  "reason",
                  reason));
    }
  }

  record RequestGroupKey(
      IndustryId industry,
      io.mosire.simos.actor.api.actor.ActorRef operator,
      io.mosire.simos.production.contract.ProductionLocation location,
      long fromTick,
      long toTickExclusive) {

    static final Comparator<RequestGroupKey> STABLE_ORDER =
        Comparator.comparing((RequestGroupKey key) -> key.industry().value())
            .thenComparing(key -> key.operator().kind().name())
            .thenComparing(key -> key.operator().id())
            .thenComparing(key -> key.location().value())
            .thenComparingLong(RequestGroupKey::fromTick)
            .thenComparingLong(RequestGroupKey::toTickExclusive);
  }
}

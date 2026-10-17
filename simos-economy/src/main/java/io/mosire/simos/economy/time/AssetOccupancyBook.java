package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyLog;
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
import org.slf4j.Logger;

/**
 * ★★ <b>D-032 D4 统一资产预算分配器</b>（D4 §5）：一次 revision 内的<b>纯算法</b>，只产出 {@code grantsToWrite}/{@code
 * grantsToRelease}/allocation/unallocated/rejection，<b>不直接写 EconomyData</b>。
 *
 * <p>★★ <b>核心判据</b>：
 *
 * <ul>
 *   <li>scope = {@code (industry, operator, location, assetKind,
 *       [from,to))}；只匹配同产业/同经营者/同地点/同资产种的实物份额，绝不跨格借用；
 *   <li>先认已有 ACTIVE grant（与目标区间重叠），它们计入 committed，后到请求不能抢走；
 *   <li>单 unit scope 拿该 kind 的整个 supply（保持单实例逐值不变）；无请求表只在"该 unit 已有 ACTIVE 事实"时走此兼容路径， 候选/新实例不得默认 1
 *       或默认整池；
 *   <li>多 unit 按请求量比例分配，整数最大余数（{@code r_i desc, unitId asc}），所有加乘使用 exact 算术；
 *   <li>按 share id 升序拆 grant；每份 share 的 ACTIVE + 本批新授予 ≤ share.quantity；不修改 {@code
 *       OwnershipStake.quantity}；
 *   <li>释放/到期把 grant 置 RELEASED 并回填 unallocated；同 share 同 unit 同时段不产生重叠 ACTIVE（不同 unit 可在同一 share
 *       上合法重叠，但同一时刻的合计不得超过 share.quantity）。
 * </ul>
 *
 * <p>★ <b>确定性</b>：所有集合按 D4 §5.4 的稳定序处理；无随机、无时钟、无 UUID、无 HashMap 迭代序参与决策。
 *
 * <p>★ <b>日志</b>：INFO 创建/释放/回填/跨格拒绝，DEBUG 每 scope supply/committed/demand/unallocated/余数，TRACE 逐
 * share 拆行； 业务拒绝 INFO，契约故障 ERROR 后抛出（不降级）。
 */
public final class AssetOccupancyBook {

  private static final Logger LOG = EconomyLog.assetOccupancy();

  private static final Comparator<AssetKind> KIND_ORDER = Comparator.comparing(AssetKind::name);

  private static final Comparator<AssetShareId> SHARE_ORDER =
      Comparator.comparing(AssetShareId::value);

  private static final Comparator<ProductionUnitId> UNIT_ORDER =
      Comparator.comparing(ProductionUnitId::value);

  private static final Comparator<AssetOccupancyGrant> GRANT_ORDER =
      Comparator.comparing((AssetOccupancyGrant grant) -> grant.unitId().value())
          .thenComparing(grant -> grant.shareId().value())
          .thenComparingLong(AssetOccupancyGrant::fromTick)
          .thenComparingLong(AssetOccupancyGrant::toTickExclusive)
          .thenComparing(grant -> grant.id().value());

  private AssetOccupancyBook() {}

  /**
   * 执行一次统一资产预算（不带 acceptedRightKinds：调用方必须已在 scope 构造前按 plan 过滤；空 scope 由本方法给出具名拒绝）。
   *
   * @param requests 本 revision 的显式请求；同一 {@code (unit, industry, operator, location, from, to)} 不得重复
   * @param existingGrants 第 36 组件的权威表（只读）
   * @param assetShares 实物份额表（只读；quantity 是唯一数量真相）
   * @param currentTick 当前世界日；用于到期释放（{@code toTickExclusive <= currentTick}）
   * @return 只有 grantsToWrite/grantsToRelease 是需要提交的持久增量
   */
  public static AssetOccupancyBudget allocate(
      Collection<AssetOccupancyRequest> requests,
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> existingGrants,
      Map<AssetShareId, OwnershipStake> assetShares,
      long currentTick) {
    return allocate(requests, existingGrants, assetShares, Map.of(), Map.of(), currentTick);
  }

  /** 兼容既有调用面；不支持“无请求的已存在单实例整池”证据（见带 existingUnits 的重载）。 */
  public static AssetOccupancyBudget allocate(
      Collection<AssetOccupancyRequest> requests,
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> existingGrants,
      Map<AssetShareId, OwnershipStake> assetShares,
      Map<ProductionUnitId, Set<AssetRightKind>> acceptedRightKindsByUnit,
      long currentTick) {
    return allocate(
        requests, existingGrants, assetShares, acceptedRightKindsByUnit, Map.of(), currentTick);
  }

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
    Objects.requireNonNull(requests, "requests 不得为 null（无请求用空集合）");
    Objects.requireNonNull(existingGrants, "existingGrants 不得为 null（无占用用空表）");
    Objects.requireNonNull(acceptedRightKindsByUnit, "acceptedRightKindsByUnit 不得为 null（不过滤用空表）");
    Objects.requireNonNull(existingUnits, "existingUnits 不得为 null（无实例用空表）");
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
      for (OwnershipStake share : shares.values()) {
        if (share.industry().equals(group.industry())
            && share.operator().equals(group.operator())
            && share.location().equals(group.location())
            && share.quantity() > 0L) {
          scopeShares.add(share);
        }
      }
      scopeShares.sort(Comparator.comparing(OwnershipStake::id, SHARE_ORDER));

      List<AssetOccupancyGrant> scopeActive = new ArrayList<>();
      for (AssetOccupancyGrant grant : activeGrants.values()) {
        OwnershipStake share = shares.get(grant.shareId());
        if (share.industry().equals(group.industry())
            && share.operator().equals(group.operator())
            && share.location().equals(group.location())
            && grant.overlaps(group.fromTick(), group.toTickExclusive())) {
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

  /**
   * 单条释放：ACTIVE → RELEASED，数量原样保留供审计/回填；不修改份额数量。
   *
   * @param grant 当前 ACTIVE 授予
   * @param releasedAtTick 当前 tick（不得为负）
   * @return RELEASED 终态行
   */
  public static AssetOccupancyGrant release(AssetOccupancyGrant grant, long releasedAtTick) {
    Objects.requireNonNull(grant, "grant 不得为 null");
    if (grant.status() != AssetOccupancyStatus.ACTIVE) {
      contractFault(
          "asset-occupancy-release-not-active",
          "只能释放 ACTIVE grant: " + grant.id().value(),
          grant.unitId());
    }
    if (releasedAtTick < 0L) {
      contractFault(
          "asset-occupancy-invalid-release-tick",
          "releasedAtTick 不得为负: " + releasedAtTick,
          grant.unitId());
    }
    AssetOccupancyGrant released =
        new AssetOccupancyGrant(
            grant.id(),
            grant.shareId(),
            grant.unitId(),
            grant.quantity(),
            grant.fromTick(),
            grant.toTickExclusive(),
            AssetOccupancyStatus.RELEASED,
            releasedAtTick);
    EventLog.channel(LOG)
        .info(
            LogEvent.of(
                "ASSET_OCCUPANCY_GRANT_RELEASED",
                EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                "grant",
                released.id().value(),
                "unit",
                released.unitId().value(),
                "share",
                released.shareId().value(),
                "quantity",
                released.quantity(),
                "fromTick",
                released.fromTick(),
                "toTickExclusive",
                released.toTickExclusive(),
                "releasedAtTick",
                released.releasedAtTick(),
                "why",
                "released-and-returned-to-unallocated"));
    return released;
  }

  private static void processKind(
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

  private static long grantAcrossShares(
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
      long availableOnShare = share.quantity() - alreadyCommitted;
      if (availableOnShare <= 0L) {
        continue;
      }
      long take = Math.min(remaining, availableOnShare);
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

  private static AssetShareId firstOccupiedShareOf(
      List<AssetOccupancyGrant> grants, ProductionUnitId unit) {
    for (AssetOccupancyGrant grant : grants) {
      if (grant.unitId().equals(unit)) {
        return grant.shareId();
      }
    }
    return null;
  }

  private static Set<AssetShareId> occupiedSharesOf(
      List<AssetOccupancyGrant> grants, ProductionUnitId unit) {
    Set<AssetShareId> shares = new LinkedHashSet<>();
    for (AssetOccupancyGrant grant : grants) {
      if (grant.unitId().equals(unit)) {
        shares.add(grant.shareId());
      }
    }
    return shares;
  }

  private static boolean hasOverlappingGrantForUnit(
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

  private static Map<AssetOccupancyGrantId, AssetOccupancyGrant> activeGrants(
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> existingGrants,
      Map<AssetShareId, OwnershipStake> shares,
      long currentTick,
      List<AssetOccupancyGrant> grantsToRelease) {
    Map<AssetOccupancyGrantId, AssetOccupancyGrant> active = new LinkedHashMap<>();
    List<AssetOccupancyGrant> ordered = new ArrayList<>();
    for (Map.Entry<AssetOccupancyGrantId, AssetOccupancyGrant> entry : existingGrants.entrySet()) {
      AssetOccupancyGrantId key = entry.getKey();
      AssetOccupancyGrant grant = entry.getValue();
      if (key == null || grant == null) {
        contractFault("asset-occupancy-null-grant", "assetOccupancies 的键/值不得为 null", null);
      }
      if (!key.equals(grant.id())) {
        contractFault(
            "asset-occupancy-key-mismatch", "grant 键必须等于行内 id: " + key.value(), grant.unitId());
      }
      OwnershipStake share = shares.get(grant.shareId());
      if (share == null) {
        contractFault(
            "asset-occupancy-share-missing",
            "grant 指名的 share 不存在: " + grant.id().value() + " → " + grant.shareId().value(),
            grant.unitId());
      }
      if (grant.quantity() > share.quantity()) {
        contractFault(
            "asset-occupancy-quantity-exceeds-share",
            "grant 数量超过 share.quantity: " + grant.id().value(),
            grant.unitId());
      }
      ordered.add(grant);
      if (grant.status() == AssetOccupancyStatus.ACTIVE) {
        if (grant.toTickExclusive() <= currentTick) {
          grantsToRelease.add(release(grant, currentTick));
        } else {
          active.put(grant.id(), grant);
        }
      }
    }
    ordered.sort(GRANT_ORDER);
    for (int i = 0; i < ordered.size(); i++) {
      AssetOccupancyGrant left = ordered.get(i);
      if (left.status() != AssetOccupancyStatus.ACTIVE) {
        continue;
      }
      for (int j = i + 1; j < ordered.size(); j++) {
        AssetOccupancyGrant right = ordered.get(j);
        if (right.status() != AssetOccupancyStatus.ACTIVE) {
          continue;
        }
        if (left.shareId().equals(right.shareId())
            && left.unitId().equals(right.unitId())
            && left.overlaps(right.fromTick(), right.toTickExclusive())) {
          contractFault(
              "asset-occupancy-overlap:" + left.shareId().value() + ":" + left.unitId().value(),
              "同一 share 同一 unit 同时段存在两条 ACTIVE grant: "
                  + left.id().value()
                  + " / "
                  + right.id().value(),
              left.unitId());
        }
      }
    }
    validateActiveShareQuantities(active, shares);
    return active;
  }

  /**
   * 同一 share 上不同 unit 的 ACTIVE grant 可以重叠，但任意时刻的合计数量不得超过 share.quantity。
   *
   * <p>只在 grant 起点处取并集：ACTIVE 合计只在某个 grant 起点单调增加，随后只会在终点减少，因此逐个起点检查即可覆盖所有 时刻的最大并发占用。
   */
  private static void validateActiveShareQuantities(
      Map<AssetOccupancyGrantId, AssetOccupancyGrant> active,
      Map<AssetShareId, OwnershipStake> shares) {
    Map<AssetShareId, List<AssetOccupancyGrant>> byShare = new LinkedHashMap<>();
    for (AssetOccupancyGrant grant : active.values()) {
      byShare.computeIfAbsent(grant.shareId(), ignored -> new ArrayList<>()).add(grant);
    }
    for (Map.Entry<AssetShareId, List<AssetOccupancyGrant>> entry : byShare.entrySet()) {
      OwnershipStake share = shares.get(entry.getKey());
      List<AssetOccupancyGrant> grants = entry.getValue();
      List<Long> moments = new ArrayList<>();
      for (AssetOccupancyGrant grant : grants) {
        moments.add(grant.fromTick());
      }
      moments.sort(Long::compare);
      for (long moment : moments) {
        long sum = 0L;
        for (AssetOccupancyGrant grant : grants) {
          if (grant.fromTick() <= moment && moment < grant.toTickExclusive()) {
            sum = addExact(sum, grant.quantity(), "activeShareQuantity", null, grant.unitId());
          }
        }
        if (sum > share.quantity()) {
          contractFault(
              "asset-occupancy-overcommit",
              "份额 "
                  + share.id().value()
                  + " 在 tick "
                  + moment
                  + " 的 ACTIVE 合计 "
                  + sum
                  + " 超过 quantity "
                  + share.quantity(),
              null);
        }
      }
    }
  }

  private static Map<AssetShareId, OwnershipStake> validatedShares(
      Map<AssetShareId, OwnershipStake> assetShares) {
    Objects.requireNonNull(assetShares, "assetShares 不得为 null（无份额用空表）");
    Map<AssetShareId, OwnershipStake> shares = new LinkedHashMap<>();
    for (Map.Entry<AssetShareId, OwnershipStake> entry : assetShares.entrySet()) {
      AssetShareId key = entry.getKey();
      OwnershipStake share = entry.getValue();
      if (key == null || share == null || !key.equals(share.id())) {
        contractFault(
            "asset-occupancy-share-key-mismatch",
            "assetShares 的键必须等于行内 id: " + key + " / " + (share == null ? null : share.id()),
            null);
      }
      OwnershipStake.requireSpecifiedLocation(share.location());
      shares.put(key, share);
    }
    return shares;
  }

  private static void validateContinuations(
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

  private static Map<RequestGroupKey, List<AssetOccupancyRequest>> groupRequests(
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

  private static boolean requestTouchesKind(AssetOccupancyRequest request, AssetKind kind) {
    return request.hasExplicitQuantity(kind)
        || request.hasNoRequestTable()
        || request.hasExplicitZeroScale()
        || request.declaresPositiveScaleWithoutQuantities();
  }

  private static Set<AssetRightKind> acceptedKindsFor(
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

  private static AssetOccupancyRequest requestFor(
      List<AssetOccupancyRequest> requests, ProductionUnitId unit) {
    for (AssetOccupancyRequest request : requests) {
      if (request.unitId().equals(unit)) {
        return request;
      }
    }
    return null;
  }

  private static void putAllocation(
      Map<ProductionUnitId, Map<AssetKind, Long>> allocations,
      ProductionUnitId unit,
      AssetKind kind,
      long quantity) {
    if (quantity <= 0L) {
      return;
    }
    allocations.computeIfAbsent(unit, ignored -> new LinkedHashMap<>()).put(kind, quantity);
  }

  private static void putUnallocated(
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

  private static void rejectAll(
      List<AssetOccupancyRequest> requests,
      AssetOccupancyScope scope,
      String reason,
      Map<ProductionUnitId, String> rejections) {
    for (AssetOccupancyRequest request : requests) {
      reject(request.unitId(), scope, reason, rejections);
    }
  }

  private static void reject(
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

  private static void logBudget(AssetOccupancyBudget budget) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    EventLog.channel(LOG)
        .debug(
            LogEvent.of(
                "ASSET_OCCUPANCY_BUDGET",
                EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                "scopes",
                budget.available().size(),
                "grantsToWrite",
                budget.grantsToWrite().size(),
                "grantsToRelease",
                budget.grantsToRelease().size(),
                "rejections",
                budget.rejections().size()));
  }

  private static void debug(String event, AssetOccupancyScope scope, Object... keyValues) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    Object[] fields = new Object[keyValues.length + 6];
    fields[0] = "industry";
    fields[1] = scope.industry().value();
    fields[2] = "location";
    fields[3] = scope.location().value();
    fields[4] = "kind";
    fields[5] = scope.assetKind().name();
    System.arraycopy(keyValues, 0, fields, 6, keyValues.length);
    EventLog.channel(LOG)
        .debug(LogEvent.of(event, EconomyLogSource.ECONOMY_ASSET_OCCUPANCY, fields));
  }

  private static long addExact(
      long left, long right, String operation, AssetOccupancyScope scope, ProductionUnitId unit) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      contractFault(
          "asset-occupancy-quantity-overflow",
          operation + " 溢出: " + left + " + " + right + " scope=" + scope + " unit=" + unit,
          unit);
      return 0L;
    }
  }

  private static long subtractExact(
      long left, long right, String operation, AssetOccupancyScope scope, ProductionUnitId unit) {
    try {
      return Math.subtractExact(left, right);
    } catch (ArithmeticException overflow) {
      contractFault(
          "asset-occupancy-quantity-overflow",
          operation + " 溢出: " + left + " - " + right + " scope=" + scope + " unit=" + unit,
          unit);
      return 0L;
    }
  }

  private static long multiplyExact(
      long left, long right, String operation, AssetOccupancyScope scope, ProductionUnitId unit) {
    try {
      return Math.multiplyExact(left, right);
    } catch (ArithmeticException overflow) {
      contractFault(
          "asset-occupancy-quantity-overflow",
          operation + " 溢出: " + left + " * " + right + " scope=" + scope + " unit=" + unit,
          unit);
      return 0L;
    }
  }

  private static void contractFault(String word, String detail, ProductionUnitId unit) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "ASSET_OCCUPANCY_CONTRACT_FAULT",
                EconomyLogSource.ECONOMY_ASSET_OCCUPANCY,
                "failure",
                word,
                "unit",
                unit == null ? "-" : unit.value(),
                "detail",
                detail));
    throw new IllegalStateException(word + ": " + detail);
  }

  private record RequestGroupKey(
      IndustryId industry,
      io.mosire.simos.actor.api.actor.ActorRef operator,
      io.mosire.simos.production.contract.ProductionLocation location,
      long fromTick,
      long toTickExclusive) {

    private static final Comparator<RequestGroupKey> STABLE_ORDER =
        Comparator.comparing((RequestGroupKey key) -> key.industry().value())
            .thenComparing(key -> key.operator().kind().name())
            .thenComparing(key -> key.operator().id())
            .thenComparing(key -> key.location().value())
            .thenComparingLong(RequestGroupKey::fromTick)
            .thenComparingLong(RequestGroupKey::toTickExclusive);
  }
}

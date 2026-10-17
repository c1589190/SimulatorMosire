package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.AssetOccupancyGrant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>D-032 D4 统一资产预算的纯派生结果</b>（D4 §5.1）：一次 {@link AssetOccupancyBook#allocate} 的完整读数。
 *
 * <p>★★ <b>不进 EconomyData、不进 ChangeSet、不跨 revision 缓存</b>；只有 {@code grantsToWrite} / {@code
 * grantsToRelease} 经 economy ChangeSet 落状态。它是预计、排队、执行和读口的同源事实。
 *
 * <p>★ <b>整数守恒</b>（D4 §5.5）：逐 scope 逐 kind 有 {@code committed + newGrant + unallocated ==
 * available}；本类型的 map 在构造时按内容纯函数序冻结，绝不使用 {@link Map#copyOf}。
 *
 * @param allocations unit → 资产种类 → 本次分配后的总量（committed + 本批新授予）
 * @param committed unit → 资产种类 → 本次分配前已 ACTIVE 的数量
 * @param available scope → 资产种类 → 匹配份额 quantity 之和
 * @param occupied scope → 资产种类 → 目标区间内已 ACTIVE 的数量（不含本批新授予）
 * @param unallocated scope → 资产种类 → 本次分配后仍未分配的数量
 * @param rejections unit → 稳定业务拒绝词
 * @param grantsToWrite 本批新增的 ACTIVE 授予（确定性排序）
 * @param grantsToRelease 本批释放的授予（RELEASED 终态，确定性排序）
 */
public record AssetOccupancyBudget(
    Map<ProductionUnitId, Map<AssetKind, Long>> allocations,
    Map<ProductionUnitId, Map<AssetKind, Long>> committed,
    Map<AssetOccupancyScope, Map<AssetKind, Long>> available,
    Map<AssetOccupancyScope, Map<AssetKind, Long>> occupied,
    Map<AssetOccupancyScope, Map<AssetKind, Long>> unallocated,
    Map<ProductionUnitId, String> rejections,
    List<AssetOccupancyGrant> grantsToWrite,
    List<AssetOccupancyGrant> grantsToRelease) {

  private static final Comparator<AssetOccupancyGrant> GRANT_ORDER =
      Comparator.comparing((AssetOccupancyGrant grant) -> grant.unitId().value())
          .thenComparing(grant -> grant.shareId().value())
          .thenComparingLong(AssetOccupancyGrant::fromTick)
          .thenComparingLong(AssetOccupancyGrant::toTickExclusive)
          .thenComparing(grant -> grant.id().value());

  public AssetOccupancyBudget {
    allocations =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(freezeUnitQuantities("allocations", allocations)));
    committed =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(freezeUnitQuantities("committed", committed)));
    available =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(freezeScopeQuantities("available", available)));
    occupied =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(freezeScopeQuantities("occupied", occupied)));
    unallocated =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(freezeScopeQuantities("unallocated", unallocated)));
    rejections = Collections.unmodifiableMap(new LinkedHashMap<>(freezeRejections(rejections)));
    grantsToWrite =
        Collections.unmodifiableList(new ArrayList<>(freezeGrants("grantsToWrite", grantsToWrite)));
    grantsToRelease =
        Collections.unmodifiableList(
            new ArrayList<>(freezeGrants("grantsToRelease", grantsToRelease)));
  }

  /** 某 unit 的分配表；没有条目 ⇒ 空表（权威空 = 合法 0）。 */
  public Map<AssetKind, Long> allocationOf(ProductionUnitId unit) {
    Objects.requireNonNull(unit, "unit 不得为 null");
    return allocations.getOrDefault(unit, Map.of());
  }

  /** 某 unit 的既有已占表；没有条目 ⇒ 空表。 */
  public Map<AssetKind, Long> committedOf(ProductionUnitId unit) {
    Objects.requireNonNull(unit, "unit 不得为 null");
    return committed.getOrDefault(unit, Map.of());
  }

  /** 某 scope 的未分配表；没有条目 ⇒ 空表。 */
  public Map<AssetKind, Long> unallocatedOf(AssetOccupancyScope scope) {
    Objects.requireNonNull(scope, "scope 不得为 null");
    return unallocated.getOrDefault(scope, Map.of());
  }

  /** 某 unit 的具名业务拒绝；无拒绝 ⇒ empty。 */
  public Optional<String> rejectionOf(ProductionUnitId unit) {
    Objects.requireNonNull(unit, "unit 不得为 null");
    return Optional.ofNullable(rejections.get(unit));
  }

  /** 只读派生行：逐 unit × 资产种类的 committed/allocation 总量（稳定序）。 */
  public List<AssetOccupancyAllocation> allocationRows() {
    List<AssetOccupancyAllocation> rows = new ArrayList<>();
    for (Map.Entry<ProductionUnitId, Map<AssetKind, Long>> unitEntry : allocations.entrySet()) {
      ProductionUnitId unit = unitEntry.getKey();
      Map<AssetKind, Long> committedByKind = committed.getOrDefault(unit, Map.of());
      for (Map.Entry<AssetKind, Long> kindEntry : unitEntry.getValue().entrySet()) {
        AssetKind kind = kindEntry.getKey();
        rows.add(
            new AssetOccupancyAllocation(
                unit, kind, committedByKind.getOrDefault(kind, 0L), kindEntry.getValue()));
      }
    }
    rows.sort(
        Comparator.comparing((AssetOccupancyAllocation row) -> row.unitId().value())
            .thenComparing(row -> row.assetKind().name()));
    return Collections.unmodifiableList(rows);
  }

  /** 只读派生行：逐 unit 的具名拒绝（稳定序）。 */
  public List<AssetOccupancyRejection> rejectionRows() {
    List<AssetOccupancyRejection> rows = new ArrayList<>();
    for (Map.Entry<ProductionUnitId, String> entry : rejections.entrySet()) {
      rows.add(new AssetOccupancyRejection(entry.getKey(), entry.getValue()));
    }
    rows.sort(Comparator.comparing(row -> row.unitId().value()));
    return Collections.unmodifiableList(rows);
  }

  private static Map<ProductionUnitId, Map<AssetKind, Long>> freezeUnitQuantities(
      String field, Map<ProductionUnitId, Map<AssetKind, Long>> source) {
    Objects.requireNonNull(source, "AssetOccupancyBudget." + field + " 不得为 null（无条目用空表）");
    List<Map.Entry<ProductionUnitId, Map<AssetKind, Long>>> entries =
        new ArrayList<>(source.entrySet());
    entries.sort(Comparator.comparing(entry -> entry.getKey().value()));
    Map<ProductionUnitId, Map<AssetKind, Long>> frozen = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, Map<AssetKind, Long>> entry : entries) {
      ProductionUnitId unit = entry.getKey();
      if (unit == null) {
        throw new IllegalArgumentException("AssetOccupancyBudget." + field + " 的键不得为 null");
      }
      frozen.put(unit, freezeKindQuantities(field + "." + unit.value(), entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  private static Map<AssetOccupancyScope, Map<AssetKind, Long>> freezeScopeQuantities(
      String field, Map<AssetOccupancyScope, Map<AssetKind, Long>> source) {
    Objects.requireNonNull(source, "AssetOccupancyBudget." + field + " 不得为 null（无条目用空表）");
    List<Map.Entry<AssetOccupancyScope, Map<AssetKind, Long>>> entries =
        new ArrayList<>(source.entrySet());
    entries.sort(Map.Entry.comparingByKey(AssetOccupancyScope.STABLE_ORDER));
    Map<AssetOccupancyScope, Map<AssetKind, Long>> frozen = new LinkedHashMap<>();
    for (Map.Entry<AssetOccupancyScope, Map<AssetKind, Long>> entry : entries) {
      AssetOccupancyScope scope = entry.getKey();
      if (scope == null) {
        throw new IllegalArgumentException("AssetOccupancyBudget." + field + " 的键不得为 null");
      }
      frozen.put(scope, freezeKindQuantities(field + "." + scope, entry.getValue()));
    }
    return Collections.unmodifiableMap(frozen);
  }

  private static Map<AssetKind, Long> freezeKindQuantities(
      String field, Map<AssetKind, Long> source) {
    Objects.requireNonNull(source, "AssetOccupancyBudget." + field + " 不得为 null（无数量用空表）");
    List<Map.Entry<AssetKind, Long>> entries = new ArrayList<>(source.entrySet());
    entries.sort(Map.Entry.comparingByKey(Comparator.comparing(AssetKind::name)));
    Map<AssetKind, Long> frozen = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : entries) {
      AssetKind kind = entry.getKey();
      Long quantity = entry.getValue();
      if (kind == null || quantity == null) {
        throw new IllegalArgumentException(
            "AssetOccupancyBudget." + field + " 的键与值都不得为 null: " + kind + " = " + quantity);
      }
      if (quantity < 0L) {
        throw new IllegalArgumentException(
            "AssetOccupancyBudget." + field + " 不得为负: " + kind + " = " + quantity);
      }
      frozen.put(kind, quantity);
    }
    return Collections.unmodifiableMap(frozen);
  }

  private static Map<ProductionUnitId, String> freezeRejections(
      Map<ProductionUnitId, String> source) {
    Objects.requireNonNull(source, "AssetOccupancyBudget.rejections 不得为 null（无拒绝用空表）");
    List<Map.Entry<ProductionUnitId, String>> entries = new ArrayList<>(source.entrySet());
    entries.sort(Comparator.comparing(entry -> entry.getKey().value()));
    Map<ProductionUnitId, String> frozen = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, String> entry : entries) {
      ProductionUnitId unit = entry.getKey();
      String reason = entry.getValue();
      if (unit == null || reason == null || reason.isBlank()) {
        throw new IllegalArgumentException(
            "AssetOccupancyBudget.rejections 的键/值必须非空: " + unit + " = " + reason);
      }
      frozen.put(unit, reason);
    }
    return Collections.unmodifiableMap(frozen);
  }

  private static List<AssetOccupancyGrant> freezeGrants(
      String field, List<AssetOccupancyGrant> source) {
    Objects.requireNonNull(source, "AssetOccupancyBudget." + field + " 不得为 null（无行用空表）");
    List<AssetOccupancyGrant> copy = new ArrayList<>();
    for (AssetOccupancyGrant grant : source) {
      if (grant == null) {
        throw new IllegalArgumentException("AssetOccupancyBudget." + field + " 不得含 null");
      }
      copy.add(grant);
    }
    copy.sort(GRANT_ORDER);
    return Collections.unmodifiableList(copy);
  }
}

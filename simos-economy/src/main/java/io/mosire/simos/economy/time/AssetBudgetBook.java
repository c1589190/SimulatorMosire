package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.production.contract.ProductionProcess;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * ★★ <b>R2：一次 revision 内的资产预算纯派生</b>（D-032 R2 最小修复）。
 *
 * <p>本类只回答一个问题：{@code (industry, operator)} 资产作用域内<b>哪些 unit 可以看见多少资产</b>。它不写状态、不产生第二份实物账， 也不改
 * {@link OwnershipStake} 的 owner/operator/quantity/kind。
 *
 * <p><b>最小 fail-closed 口径</b>：
 *
 * <pre>
 * scope 内恰有 1 个 unit  ⇒ 该 unit 得到整个 scope 的资产表（单实例逐值不变）
 * scope 内 &gt;1 个 unit     ⇒ 每个 unit 的分配表为空；scope 汇总进入 unallocated（只读）；
 *                            每个 unit 得到具名拒绝 asset-scope-shared:awaiting-D4
 * 没有任何 unit 的 scope   ⇒ 汇总进入 unallocated（退出/迁移仍可按份额 id 处理实物账）
 * </pre>
 *
 * <p>这不是完整 D4（实例/地点占用、占用期、释放）的实现，只是阻止同一份资产被同 scope 的多个实例整池复制。完整多实例分配仍待 D4。
 *
 * <p><b>确定性</b>：unit 按 id 升序、scope 按 {@code (industry.value, operator.kind, operator.id)} 升序、资产种类按
 * {@link AssetKind#name()} 升序。全程无 HashMap 迭代序、无随机/时钟/UUID。
 */
public final class AssetBudgetBook {

  /** 共享资产作用域的固定业务拒绝词（R2 最小修复；不得静默降级成 0 成功）。 */
  public static final String SHARED_SCOPE_REJECTION = "asset-scope-shared:awaiting-D4";

  private static final Comparator<AssetScope> SCOPE_ORDER =
      Comparator.comparing((AssetScope scope) -> scope.industry().value())
          .thenComparing(scope -> scope.operator().kind().name())
          .thenComparing(scope -> scope.operator().id());

  private static final Comparator<AssetKind> ASSET_ORDER = Comparator.comparing(AssetKind::name);

  private AssetBudgetBook() {}

  /** 资产的归属作用域：同一产业 + 同一经营者。 */
  public record AssetScope(IndustryId industry, ActorRef operator) {
    public AssetScope {
      Objects.requireNonNull(industry, "AssetScope.industry 不得为 null");
      Objects.requireNonNull(operator, "AssetScope.operator 不得为 null");
    }
  }

  /**
   * 一次派生的资产预算。
   *
   * @param allocations unit → (资产种类 → 数量)；无份额的 unit 也有空表
   * @param unallocated 未被分配的作用域 → 汇总资产；共享 scope 与没有 unit 的 scope 在这里可见
   * @param rejections unit → 具名拒绝；只有共享 scope 的 unit 会出现
   */
  public record AssetBudget(
      Map<ProductionUnitId, Map<AssetKind, Long>> allocations,
      Map<AssetScope, Map<AssetKind, Long>> unallocated,
      Map<ProductionUnitId, String> rejections) {

    public AssetBudget {
      allocations = freezeAllocations(allocations);
      unallocated = freezeUnallocated(unallocated);
      rejections = freezeRejections(rejections);
    }

    /** 某个 unit 的可用资产分配；没有条目 ⇒ 空表（与旧“无份额 ⇒ 空表”同侧）。 */
    public Map<AssetKind, Long> allocationOf(ProductionUnitId unit) {
      Objects.requireNonNull(unit, "unit 不得为 null");
      return allocations.getOrDefault(unit, Map.of());
    }

    /** 某个 unit 的具名业务拒绝；无拒绝 ⇒ empty。 */
    public java.util.Optional<String> rejectionOf(ProductionUnitId unit) {
      Objects.requireNonNull(unit, "unit 不得为 null");
      return java.util.Optional.ofNullable(rejections.get(unit));
    }

    /** 某个作用域的未分配资产；不可变空表表示“该 scope 没有未分配量”。 */
    public Map<AssetKind, Long> unallocatedOf(AssetScope scope) {
      Objects.requireNonNull(scope, "scope 不得为 null");
      return unallocated.getOrDefault(scope, Map.of());
    }
  }

  /**
   * 从 {@code units + assetShares} 纯派生一次资产预算。
   *
   * @param units 生产实例的只读投影（键是稳定 id；值内的 id 必须与键一致）
   * @param assetShares 实物资产总账（唯一 quantity 真相；本方法只读）
   */
  public static AssetBudget allocate(
      Map<ProductionUnitId, ProductionProcess> units,
      Map<AssetShareId, OwnershipStake> assetShares) {
    Objects.requireNonNull(units, "units 不得为 null");
    Objects.requireNonNull(assetShares, "assetShares 不得为 null");

    Map<AssetScope, List<ProductionUnitId>> unitsByScope = new TreeMap<>(SCOPE_ORDER);
    for (ProductionProcess unit : units.values()) {
      Objects.requireNonNull(unit, "units 的值不得为 null");
      AssetScope scope = new AssetScope(unit.industry(), unit.operator());
      unitsByScope.computeIfAbsent(scope, ignored -> new ArrayList<>()).add(unit.id());
    }
    for (List<ProductionUnitId> scopedUnits : unitsByScope.values()) {
      scopedUnits.sort(Comparator.comparing(ProductionUnitId::value));
    }

    Map<AssetScope, Map<AssetKind, Long>> totalsByScope = new TreeMap<>(SCOPE_ORDER);
    for (OwnershipStake share : assetShares.values()) {
      Objects.requireNonNull(share, "assetShares 的值不得为 null");
      AssetScope scope = new AssetScope(share.industry(), share.operator());
      Map<AssetKind, Long> totals =
          totalsByScope.computeIfAbsent(scope, ignored -> new LinkedHashMap<>());
      totals.merge(share.asset(), share.quantity(), Math::addExact);
    }

    Set<AssetScope> allScopes = new TreeSet<>(SCOPE_ORDER);
    allScopes.addAll(unitsByScope.keySet());
    allScopes.addAll(totalsByScope.keySet());

    Map<ProductionUnitId, Map<AssetKind, Long>> allocations = new LinkedHashMap<>();
    Map<AssetScope, Map<AssetKind, Long>> unallocated = new LinkedHashMap<>();
    Map<ProductionUnitId, String> rejections = new LinkedHashMap<>();
    for (AssetScope scope : allScopes) {
      List<ProductionUnitId> scopedUnits = unitsByScope.getOrDefault(scope, List.of());
      Map<AssetKind, Long> totals = assetTable(totalsByScope.getOrDefault(scope, Map.of()));
      if (scopedUnits.isEmpty()) {
        unallocated.put(scope, totals);
      } else if (scopedUnits.size() == 1) {
        allocations.put(scopedUnits.get(0), totals);
      } else {
        for (ProductionUnitId unitId : scopedUnits) {
          allocations.put(unitId, Map.of());
          rejections.put(unitId, SHARED_SCOPE_REJECTION);
        }
        unallocated.put(scope, totals);
      }
    }
    return new AssetBudget(allocations, unallocated, rejections);
  }

  /**
   * ★★ 防御性判定：当前 {@code units} 中同一 {@code (industry, operator)} 是否已有多个 unit。
   *
   * <p>正常日结算必须读 {@link AssetBudgetBook.AssetBudget}；本方法只给“索引快照后又被坏路径塞入第二个 unit”的执行前守卫使用，
   * 避免陈旧索引把整池资产漏给其中一个 unit。
   */
  public static boolean hasMultipleUnitsInScope(
      Map<ProductionUnitId, ProductionProcess> units, IndustryId industry, ActorRef operator) {
    Objects.requireNonNull(units, "units 不得为 null");
    Objects.requireNonNull(industry, "industry 不得为 null");
    Objects.requireNonNull(operator, "operator 不得为 null");
    int matched = 0;
    for (ProductionProcess unit : units.values()) {
      if (unit.industry().equals(industry) && unit.operator().equals(operator)) {
        matched++;
        if (matched > 1) {
          return true;
        }
      }
    }
    return false;
  }

  /** 该 {@code (industry, operator)} 在本修订内是否已被至少一个 unit 占用。 */
  public static boolean hasUnitInScope(
      Map<ProductionUnitId, ProductionProcess> units, IndustryId industry, ActorRef operator) {
    Objects.requireNonNull(units, "units 不得为 null");
    Objects.requireNonNull(industry, "industry 不得为 null");
    Objects.requireNonNull(operator, "operator 不得为 null");
    for (ProductionProcess unit : units.values()) {
      if (unit.industry().equals(industry) && unit.operator().equals(operator)) {
        return true;
      }
    }
    return false;
  }

  private static Map<AssetKind, Long> assetTable(Map<AssetKind, Long> source) {
    Map<AssetKind, Long> sorted = new TreeMap<>(ASSET_ORDER);
    for (Map.Entry<AssetKind, Long> entry : source.entrySet()) {
      AssetKind kind = Objects.requireNonNull(entry.getKey(), "资产种类不得为 null");
      Long quantity = Objects.requireNonNull(entry.getValue(), "资产数量不得为 null");
      if (quantity < 0L) {
        throw new IllegalArgumentException("资产数量不得为负: " + kind + "=" + quantity);
      }
      sorted.put(kind, quantity);
    }
    return Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
  }

  private static Map<ProductionUnitId, Map<AssetKind, Long>> freezeAllocations(
      Map<ProductionUnitId, Map<AssetKind, Long>> source) {
    Objects.requireNonNull(source, "allocations 不得为 null");
    Map<ProductionUnitId, Map<AssetKind, Long>> frozen = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, Map<AssetKind, Long>> entry : source.entrySet()) {
      ProductionUnitId unit = Objects.requireNonNull(entry.getKey(), "allocations 的键不得为 null");
      frozen.put(
          unit, assetTable(Objects.requireNonNull(entry.getValue(), "allocations 的值不得为 null")));
    }
    return Collections.unmodifiableMap(frozen);
  }

  private static Map<AssetScope, Map<AssetKind, Long>> freezeUnallocated(
      Map<AssetScope, Map<AssetKind, Long>> source) {
    Objects.requireNonNull(source, "unallocated 不得为 null");
    Map<AssetScope, Map<AssetKind, Long>> frozen = new LinkedHashMap<>();
    for (Map.Entry<AssetScope, Map<AssetKind, Long>> entry : source.entrySet()) {
      AssetScope scope = Objects.requireNonNull(entry.getKey(), "unallocated 的键不得为 null");
      frozen.put(
          scope, assetTable(Objects.requireNonNull(entry.getValue(), "unallocated 的值不得为 null")));
    }
    return Collections.unmodifiableMap(frozen);
  }

  private static Map<ProductionUnitId, String> freezeRejections(
      Map<ProductionUnitId, String> source) {
    Objects.requireNonNull(source, "rejections 不得为 null");
    Map<ProductionUnitId, String> frozen = new LinkedHashMap<>();
    for (Map.Entry<ProductionUnitId, String> entry : source.entrySet()) {
      ProductionUnitId unit = Objects.requireNonNull(entry.getKey(), "rejections 的键不得为 null");
      String reason = Objects.requireNonNull(entry.getValue(), "rejections 的值不得为 null");
      if (reason.isBlank()) {
        throw new IllegalArgumentException("rejections 的值不得为空白");
      }
      frozen.put(unit, reason);
    }
    return Collections.unmodifiableMap(frozen);
  }
}

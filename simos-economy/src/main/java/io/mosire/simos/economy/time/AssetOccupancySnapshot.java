package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.util.state.RevisionId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>D-032 D4-C 只读资产占用快照</b>（D4 §7.0）：某个 {@code unit} 在冻结区间上的唯一权威占用读数。
 *
 * <p>★★ <b>语义边界</b>：本类型只是只读值，不写状态、不推导分配。{@link Optional#empty()} 类读数由 lookup 返回，表示接口未装配或权威事实不可用； 本
 * record 的三个 map 为空则只表示<b>确有权威结果且没有对应数量</b>（合法 0）。调用方必须自行校验 revision/unit/location/interval， 不匹配时具名
 * {@code asset-occupancy-snapshot-mismatch}。
 *
 * <p>★ <b>构造期守卫</b>：身份字段与 map、Optional 均不得为 null；tick 非负且 {@code fromTick < toTickExclusive}； 三个数量
 * map 逐项键/值非 null、值非负，并按传入迭代序冻结为不可变表。
 *
 * @param revision 产生本快照的数据版本；只读值，不落状态
 * @param unit 被读取的生产单元
 * @param location 该生产单元的地点
 * @param fromTick 区间起点（含）
 * @param toTickExclusive 区间终点（不含）
 * @param allocation 本次分配量；空表 = 权威零分配
 * @param committed 已承诺量；只读
 * @param unallocated 未分配量；只读
 * @param rejection 拒绝原因（如有）；只读
 */
public record AssetOccupancySnapshot(
    RevisionId revision,
    ProductionUnitId unit,
    ProductionLocation location,
    long fromTick,
    long toTickExclusive,
    Map<AssetKind, Long> allocation,
    Map<AssetKind, Long> committed,
    Map<AssetKind, Long> unallocated,
    Optional<String> rejection) {

  public AssetOccupancySnapshot {
    Objects.requireNonNull(revision, "AssetOccupancySnapshot.revision 不得为 null");
    Objects.requireNonNull(unit, "AssetOccupancySnapshot.unit 不得为 null");
    Objects.requireNonNull(location, "AssetOccupancySnapshot.location 不得为 null");
    if (fromTick < 0L) {
      throw new IllegalArgumentException("AssetOccupancySnapshot.fromTick 不得为负: " + fromTick);
    }
    if (fromTick >= toTickExclusive) {
      throw new IllegalArgumentException(
          "AssetOccupancySnapshot 区间必须满足 fromTick < toTickExclusive: "
              + fromTick
              + " >= "
              + toTickExclusive);
    }
    allocation =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(freezeAssetQuantities("allocation", allocation)));
    committed =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(freezeAssetQuantities("committed", committed)));
    unallocated =
        Collections.unmodifiableMap(
            new LinkedHashMap<>(freezeAssetQuantities("unallocated", unallocated)));
    rejection = Objects.requireNonNull(rejection, "AssetOccupancySnapshot.rejection 不得为 null");
  }

  /** ★ 保序冻结：不使用 {@link Map#copyOf}，因为它的迭代序不是内容的纯函数；空表同样是合法权威结果。 */
  private static Map<AssetKind, Long> freezeAssetQuantities(
      String field, Map<AssetKind, Long> source) {
    Objects.requireNonNull(source, "AssetOccupancySnapshot." + field + " 不得为 null（无分配给空表）");
    LinkedHashMap<AssetKind, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : source.entrySet()) {
      AssetKind kind = entry.getKey();
      Long quantity = entry.getValue();
      if (kind == null || quantity == null) {
        throw new IllegalArgumentException(
            "AssetOccupancySnapshot." + field + " 的键与值都不得为 null: " + kind + " = " + quantity);
      }
      if (quantity < 0L) {
        throw new IllegalArgumentException(
            "AssetOccupancySnapshot." + field + " 不得为负: " + kind + " = " + quantity);
      }
      copy.put(kind, quantity);
    }
    return Collections.unmodifiableMap(copy);
  }
}

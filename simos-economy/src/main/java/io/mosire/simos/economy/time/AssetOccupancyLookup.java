package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.util.state.RevisionId;
import java.util.Optional;

/**
 * ★★ <b>D-032 D4-C 资产占用唯一只读读口</b>（D4 §7.0 / D2 §8.1）：C2 只依赖本接口取得 unit 的冻结占用， 不复制临时接口、不回退旧 {@code
 * 整池份额扫描。
 *
 * <p>★★ <b>不可用与零的区分</b>：{@link Optional#empty()} 只表示接口未装配 / D4-I 未实现 / 权威事实不可用， 调用方必须具名 {@code
 * asset-occupancy-unavailable} 且不得读成 0；返回快照中的空 map 才表示有权威结果且确实没有分配。
 *
 * <p>★ <b>只读</b>：本接口不写状态、不产生变更。返回快照必须由调用方按 {@code expectedRevision}、unit、location 与区间校验；任一不匹配都是
 * {@code asset-occupancy-snapshot-mismatch}。
 */
public interface AssetOccupancyLookup {

  /**
   * 读取某 unit 在给定 revision 与半开区间上的权威占用快照。
   *
   * @param unit 被读取的生产单元
   * @param expectedRevision 调用方要求的权威数据版本
   * @param fromTick 区间起点（含）
   * @param toTickExclusive 区间终点（不含）
   * @return 权威快照；接口未装配或权威事实不可用时为空
   */
  Optional<AssetOccupancySnapshot> occupancyOf(
      ProductionUnitId unit, RevisionId expectedRevision, long fromTick, long toTickExclusive);
}

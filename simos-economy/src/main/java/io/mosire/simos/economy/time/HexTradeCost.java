package io.mosire.simos.economy.time;

import io.mosire.simos.map.hex.HexCoord;
import java.util.Objects;

/**
 * ★★ <b>单 hex 贸易成本</b>（D-027 的第一层：区内跨格物流成本，逐格计量）。它是<b>纯策略</b>：只读一张 {@link
 * MarketTopology}（地形/道路/费率入口都在它里面），自己不持有状态、不落盘、不改价。
 *
 * <p>★★ <b>分层纪律（D-027）</b>：
 *
 * <ul>
 *   <li><b>单 hex 贸易成本</b>（本类）—— 货物在区内从一个 hex 到另一个 hex 的物流/通行成本（地形、道路、距离），
 *       逐格计量；本批第一版只表达为<b>实物损耗</b>（{@link #lossPerMilleBetween}）， {@link #costMilliPerUnit} <b>恒
 *       0</b>（单区内不产生货币运费/CARRIER_FEE；"钱付给谁"是后续批次的事）。
 *   <li><b>区内市场规则</b>（{@link MarketRegulation}）—— 市场区这一层的聚合规则（P-T1c 起只剩区内税费），
 *       按区施加一次。两层不得互相顶替：本类不承担价格/税费职能，{@code MarketRegulation} 也不进这条逐格算式。
 * </ul>
 *
 * <p>★★ <b>公式（唯一拼写点）</b>：
 *
 * <pre>
 * 同格（from == to）：  lossPerMille = 0，costMilliPerUnit = 0
 * 跨格（同一区内）：
 *   lossPerMille = min(MAX_HEX_TRADE_LOSS_PER_MILLE,
 *                      HEX_TRADE_LOSS_PER_MILLE_PER_HEX
 *                      × from.distanceTo(to)
 *                      × max(1, topology.moveCostAt(to)))
 *   costMilliPerUnit = 0
 * </pre>
 *
 * <p>★ {@code topology.moveCostAt(to)} 自身已经把地形代价夹到 {@code >= 1}（不可通行的哨兵值由调用方按 {@link
 * io.mosire.simos.map.terrain.TerrainType#IMPASSABLE_MOVE_COST} 判），因此这里不再重复钳位； 内层 {@code max(1, …)}
 * 的语义由拓扑的唯一拼写点承担。
 *
 * <p>★ <b>坏数据 fail-closed</b>：{@code null} 参数 / {@code from}/{@code to} 不属于本拓扑的成员表 ⇒ 抛 {@link
 * IllegalArgumentException}（"没有归属"不许静默当成 0 成本）。
 */
public record HexTradeCost(MarketTopology topology) {

  /**
   * ★ <b>每 hex 的贸易损耗率（‰）</b>：{@code 2} —— 跨一格最少损耗 2‰（一格的 {@code moveCost} 至少 1）。 具名常量：全仓只有这一处拼写点。
   */
  public static final long HEX_TRADE_LOSS_PER_MILLE_PER_HEX = 2L;

  /** ★ <b>单 hex 贸易损耗率的上限（‰）</b>：{@code 500} —— 任意远/任意难走的两格之间最多损耗一半。 它不是"价格上限"，只是损耗率的天花板。 */
  public static final long MAX_HEX_TRADE_LOSS_PER_MILLE = 500L;

  /** 第一版单区内**不产生货币运费**：{@code costMilliPerUnit} 恒 0。 */
  public static final long HEX_TRADE_COST_MILLI_PER_UNIT = 0L;

  public HexTradeCost {
    Objects.requireNonNull(topology, "topology");
  }

  /**
   * {@code from → to} 的单 hex 贸易损耗率（‰，毫商品/毫商品）。
   *
   * @throws IllegalArgumentException {@code from}/{@code to} 为 null、或不在本拓扑的成员表里（fail-closed）
   */
  public long lossPerMilleBetween(HexCoord from, HexCoord to) {
    if (from == null || to == null) {
      throw new IllegalArgumentException("HexTradeCost 的 from/to 不得为 null");
    }
    if (from.equals(to)) {
      return 0L;
    }
    if (!topology.contains(from) || !topology.contains(to)) {
      throw new IllegalArgumentException(
          "HexTradeCost 只服务本拓扑的成员格（没有归属的格不许静默按 0 成本成交）：from=" + from + " to=" + to);
    }
    long lossPerMille =
        HEX_TRADE_LOSS_PER_MILLE_PER_HEX
            * (long) from.distanceTo(to)
            * Math.max(1, topology.moveCostAt(to));
    return Math.min(MAX_HEX_TRADE_LOSS_PER_MILLE, lossPerMille);
  }

  /**
   * {@code from → to} 的单位货币运费（{@code costMilliPerUnit}）。★ 第一版恒 {@code 0}：单区内即时成交 <b>不产生货币运费</b>，也不铸
   * CARRIER_FEE（成本以实物损耗表达，见 {@link #lossPerMilleBetween}）；货币运费留给后续 批次的承运人设计。
   */
  public long costMilliPerUnit(HexCoord from, HexCoord to) {
    if (from == null || to == null) {
      throw new IllegalArgumentException("HexTradeCost 的 from/to 不得为 null");
    }
    return HEX_TRADE_COST_MILLI_PER_UNIT;
  }
}

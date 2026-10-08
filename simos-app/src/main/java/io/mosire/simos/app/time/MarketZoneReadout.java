package io.mosire.simos.app.time;

import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.time.MarketTopology;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>市场区的只读读数</b>（B1，阶段 2-B）：把"这个世界<strong>此刻</strong>有几个市场区、每个区的法定币是什么" 一次问清 —— 供创世日志（§一.9
 * 的"几个区 / 每区法定币"两条 INFO）、探针与后续 GUI 读口使用。
 *
 * <p>★★ <b>它为什么必须存在</b>：{@link MarketTopologyBook} 是**包内可见**的装配件（组合根内部实现）， 于是"区数"此前**没有任何包外读口** ——
 * 而 B1 的验收判据 G1 恰是"世界起来即有 3 个市场区，每区法定币不同"。 没有读口就只能靠反射或读代码推断，那两者都不是证据。本类只做一件事：把 {@link
 * MarketTopology#regions()} 折成保序、不可变的纯值。
 *
 * <p>★ <b>它不改任何状态、不进 {@code EconomyData}</b>：市场区在这一批**仍是派生件**（铁律 3/4：city 的权威在 social/map，半径是 GM
 * 参数）—— 把区变持久状态是 B2 的事（约束设计书 §4.2 / I22）。故本类的读数 <b>逐次现算</b>，同一份状态必然给出同一份区表（{@link MarketTopology}
 * 的构造性可复现）。
 *
 * <p>★ <b>法定币 = 该区节点（集散城市）那格的 {@code Market.numeraire}</b>：区内逐格价格都按它计（D-027 的同币口径）， 故它是这一维的唯一读数口径。★
 * 本批**没有**"区 → 法定币"的持久字段（那是 B2 的 {@code zoneId/hexes/法定币/发行政府}）。
 *
 * <p>★ <b>区数怎么来的</b>（判据与坑都在 {@link MarketTopologyBook#from(SimulationState)} 的类注里）：同币 ⇒ 恰恰 1 个区；异币
 * ⇒ 每个"锚格有市场"的城市一个节点 + 每个覆盖不到的市场格一个兜底单格区。
 */
public final class MarketZoneReadout {

  private MarketZoneReadout() {}

  /**
   * 一个市场区的只读投影。
   *
   * @param zoneId 区 id（{@link MarketRegion#node()}{@code .nodeId()}；D-027 快路下恒为 {@code
   *     "single-region"}）
   * @param anchor 集散节点格（区内参考价取这一格的市场）
   * @param radiusHex 区半径（hex；{@link MarketTopology#singleRegion} 与兜底单格区恒 0）
   * @param numeraire <b>本区法定币</b>（区内价格都按它计）
   * @param receiveWith 本区卖方接收的货币工具 id（见 {@code MarketTopologyBook#addNode} 的注：该字段全仓零读取者）
   * @param members 成员格（保序不可变；含锚格）
   */
  public record Zone(
      String zoneId,
      HexCoord anchor,
      int radiusHex,
      CurrencyId numeraire,
      InstrumentId receiveWith,
      List<HexCoord> members) {

    public Zone {
      Objects.requireNonNull(zoneId, "zoneId");
      Objects.requireNonNull(anchor, "anchor");
      Objects.requireNonNull(numeraire, "numeraire");
      Objects.requireNonNull(receiveWith, "receiveWith");
      members = List.copyOf(Objects.requireNonNull(members, "members"));
    }

    /** 成员格数。 */
    public int hexCount() {
      return members.size();
    }
  }

  /**
   * 当前世界的全部市场区（保序：{@link MarketTopology#regions()} 的声明序 —— 节点声明序，兜底单格区接在其后）。
   *
   * @throws IllegalStateException 状态里没有 economy 切片（装配故障；照 {@link MarketTopologyBook} 的既有口径）
   */
  public static List<Zone> zones(SimulationState state) {
    Objects.requireNonNull(state, "state");
    MarketTopology topology = MarketTopologyBook.from(state);
    List<Zone> zones = new ArrayList<>(topology.regions().size());
    for (MarketRegion region : topology.regions()) {
      List<HexCoord> members = new ArrayList<>(region.members());
      members.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
      zones.add(
          new Zone(
              region.node().nodeId(),
              region.anchor(),
              region.radiusHex(),
              region.numeraire(),
              region.receiveWith(),
              members));
    }
    return List.copyOf(zones);
  }

  /** 市场区数（= {@link #zones(SimulationState)} 的规模；B1 判据 G1 的"3 个市场区"读的就是它）。 */
  public static int zoneCount(SimulationState state) {
    return zones(state).size();
  }

  /** 某个有市场的格所属的区（该格不属于任何区 ⇒ 空；合法状态：没有市场的格不属于任何区）。 */
  public static Optional<Zone> zoneOf(SimulationState state, HexCoord hex) {
    Objects.requireNonNull(hex, "hex");
    for (Zone zone : zones(state)) {
      if (zone.members().contains(hex)) {
        return Optional.of(zone);
      }
    }
    return Optional.empty();
  }

  /**
   * 逐区一行的人类可读摘要（保序；供日志与探针直读）：{@code "<zoneId>@<anchor>[<n>格]=<币种>"}。
   *
   * <p>★ 只输出稳定 id 与数量，不输出任何载荷/密钥（§一.9 的日志纪律）。
   */
  public static List<String> describe(SimulationState state) {
    List<String> lines = new ArrayList<>();
    for (Zone zone : zones(state)) {
      lines.add(
          zone.zoneId()
              + "@"
              + zone.anchor()
              + "["
              + zone.hexCount()
              + "格]="
              + zone.numeraire().value());
    }
    return List.copyOf(lines);
  }
}

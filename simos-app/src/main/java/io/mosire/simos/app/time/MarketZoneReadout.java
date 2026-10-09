package io.mosire.simos.app.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketRegion;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.time.MarketTopology;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 本批**没有**"区 → 法定币"的持久字段（那是 B2 的 {@code zoneId/hexes/法定币}；★ 2026-10-09 C 批起区里连"发行政府"也没有了）。
 *
 * <p>★ <b>区数怎么来的</b>（判据与坑都在 {@link MarketTopologyBook#from(SimulationState)} 的类注里）：同币 ⇒ 恰恰 1 个区；异币
 * ⇒ 每个"锚格有市场"的城市一个节点 + 每个覆盖不到的市场格一个兜底单格区。
 */
public final class MarketZoneReadout {

  private MarketZoneReadout() {}

  /**
   * 一个市场区的只读投影。
   *
   * @param zoneId 区 id（持久区 = {@code MarketZoneId} 裸值；D-027 快路下恒为 {@code "single-region"}）
   * @param anchor 集散节点格（区内参考价取这一格的市场）
   * @param radiusHex 区半径（hex；{@link MarketTopology#singleRegion} 与兜底单格区恒 0；持久区 = 声明半径）
   * @param numeraire <b>本区法定币</b>（区内价格都按它计）
   * @param receiveWith 本区卖方接收的货币工具 id（见 {@code MarketTopologyBook#addNode} 的注：该字段全仓零读取者）
   * @param members 成员格（保序不可变；含锚格）
   * @param authority {@code "persistent"}（成员格由 {@code EconomyData.marketZones} 给定，I22）或 {@code
   *     "derived"} （空区表 ⇒ "城市 + tier 半径"派生，本批之前的既有形态）
   * @param officialRates 本区**区级**官方汇率覆盖（{@code base|quote} → 报价；派生区 / 无覆盖 ⇒ 空表）
   */
  // ★★ 2026-10-09 C 批：{@code issuingGov} / {@code issuingGovUnit} 两栏退役（用户「法定货币发行者也丢掉」；设计书 §4.1/G1、
  //   §6.3「读数里 issuingGov 两栏消失（预期变化）」）。"谁管这种钱"改由 `Government.issuable` 反查回答
  //   （{@link MarketZoneBook#possibleIssuersOf}，可多值）—— 一个区里可以有多个政府，读数里塞一个"发行者"就是错的。
  public record Zone(
      String zoneId,
      HexCoord anchor,
      int radiusHex,
      CurrencyId numeraire,
      InstrumentId receiveWith,
      List<HexCoord> members,
      String authority,
      Map<String, OfficialRate> officialRates) {

    public Zone {
      Objects.requireNonNull(zoneId, "zoneId");
      Objects.requireNonNull(anchor, "anchor");
      Objects.requireNonNull(numeraire, "numeraire");
      Objects.requireNonNull(receiveWith, "receiveWith");
      members = List.copyOf(Objects.requireNonNull(members, "members"));
      Objects.requireNonNull(authority, "authority");
      officialRates = Map.copyOf(Objects.requireNonNull(officialRates, "officialRates"));
      // ★ officialRates 是**只读读数**（不参与任何等式判定），Map.copyOf 的迭代序不承诺是内容的纯函数也不影响结论；
      //   要保序读的调用方按 key 排序自己排（Zone.officialRates() 的规模是币对数，数量级个位数）。
    }

    /** 成员格数。 */
    public int hexCount() {
      return members.size();
    }

    /** 成员格是否由持久状态给定（I22 的单一权威；{@code false} = 派生区）。 */
    public boolean persistent() {
      return "persistent".equals(authority);
    }
  }

  /**
   * ★★ <b>B2：持久区（{@code EconomyData.marketZones}）的只读投影</b>（不经过拓扑）—— 供命令面/探针/日志读"状态里到底有哪些区"。
   *
   * <p>★ 与 {@link #zones(SimulationState)} 的区别：那个读的是**拓扑**（含派生兜底单格区、成员格经装配），本方法读的是
   * <b>状态</b>（唯一权威本身）。两者在区表非空时逐值一致（拓扑按持久区装配）；区表为空时本方法返回空表而拓扑仍会给出派生区 —— 这正是"空表 = 派生默认值"的形态。
   */
  public static List<Zone> persistentZones(SimulationState state) {
    Objects.requireNonNull(state, "state");
    EconomyData economy = economyOf(state);
    List<Zone> zones = new ArrayList<>(economy.marketZones().size());
    for (MarketZone zone : MarketZoneBook.zones(economy)) {
      List<HexCoord> members = new ArrayList<>(zone.hexes());
      members.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
      zones.add(
          new Zone(
              zone.zoneId().value(),
              zone.anchor(),
              zone.radiusHex(),
              zone.legalTender(),
              MarketTopologyBook.receiveInstrumentOf(economy, zone.legalTender()),
              members,
              "persistent",
              preserveOrder(zone.officialRates())));
    }
    return List.copyOf(zones);
  }

  /** 保序拷贝（读数要可复现：按币对键升序；不用 {@code Map.copyOf}——它的迭代序不是内容的纯函数）。 */
  private static Map<String, OfficialRate> preserveOrder(Map<String, OfficialRate> rates) {
    if (rates == null || rates.isEmpty()) {
      return Map.of();
    }
    List<String> keys = new ArrayList<>(rates.keySet());
    keys.sort(Comparator.naturalOrder());
    Map<String, OfficialRate> copy = new LinkedHashMap<>();
    for (String key : keys) {
      copy.put(key, rates.get(key));
    }
    return java.util.Collections.unmodifiableMap(copy);
  }

  /** economy 切片（缺席/类型不符 ⇒ 抛；与 {@code MarketTopologyBook} 的既有口径同源）。 */
  private static EconomyData economyOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module("economy")
            .orElseThrow(() -> new IllegalStateException("状态里没有 economy 切片（装配故障）"));
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "economy 切片不是 EconomySnapshot: " + snapshot.getClass().getName());
    }
    return economySnapshot.data();
  }

  /**
   * 当前世界的全部市场区（保序：{@link MarketTopology#regions()} 的声明序 —— 节点声明序，兜底单格区接在其后）。
   *
   * @throws IllegalStateException 状态里没有 economy 切片（装配故障；照 {@link MarketTopologyBook} 的既有口径）
   */
  public static List<Zone> zones(SimulationState state) {
    Objects.requireNonNull(state, "state");
    EconomyData economy = economyOf(state);
    Map<MarketZoneId, MarketZone> persistent = economy.marketZones();
    MarketTopology topology = MarketTopologyBook.from(state);
    List<Zone> zones = new ArrayList<>(topology.regions().size());
    for (MarketRegion region : topology.regions()) {
      List<HexCoord> members = new ArrayList<>(region.members());
      members.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
      MarketZone zone = null;
      try {
        zone = persistent.get(new MarketZoneId(region.node().nodeId()));
      } catch (IllegalArgumentException e) {
        zone = null; // 派生区节点 id（"single-region" / "hex:…" / 城市 id）不是 MarketZoneId ⇒ 派生区
      }
      zones.add(
          new Zone(
              region.node().nodeId(),
              region.anchor(),
              region.radiusHex(),
              region.numeraire(),
              region.receiveWith(),
              members,
              zone == null ? "derived" : "persistent",
              zone == null ? Map.of() : preserveOrder(zone.officialRates())));
    }
    return List.copyOf(zones);
  }

  /**
   * ★★ <b>某个市场区上某币对的官方汇率读数</b>（区级覆盖优先、回落该区发行 GOV 的 GOV 级报价；见 {@code
   * MarketZoneBook.officialRateFor}）：读的是**状态**，不是撮合结果（I18：官方汇率是政策价，不是成交价）。
   */
  public static Optional<OfficialRate> officialRateFor(
      SimulationState state, HexCoord hex, CurrencyId base, CurrencyId quote) {
    Objects.requireNonNull(hex, "hex");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(quote, "quote");
    return MarketZoneBook.officialRateFor(economyOf(state), hex, base, quote);
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
   * <p>★ 只输出稳定 id 与数量，不输出任何载荷/密钥（§一.9 的日志纪律）。★★ B2 起本行的**格式不变**（见方法内注释）： 权威来源与发行者/区级汇率走 {@code
   * GovCurrencyLinks.describe} 与 {@link Zone} 的字段。
   */
  public static List<String> describe(SimulationState state) {
    List<String> lines = new ArrayList<>();
    for (Zone zone : zones(state)) {
      // ★★ B2：这一行的**文本格式一字不改**（既有的探针/日志消费它；"旧档不动"的自证也拿它当对照面）——
      //   权威来源（persistent/derived）、发行者、区级汇率条数走 Zone 的字段与持久区描述（GovCurrencyLinks.describe），
      //   不往这行里塞。
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

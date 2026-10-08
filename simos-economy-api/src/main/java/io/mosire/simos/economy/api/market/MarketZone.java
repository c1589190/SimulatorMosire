package io.mosire.simos.economy.api.market;

import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>一个市场区</b>（阶段 2-B2，2026-10-08；约束设计书 §4.2 / §4.3 / 不变量 I22）。
 *
 * <pre>
 * MarketZone(zoneId, anchor, radiusHex, hexes, legalTender, issuingGov, officialRates)
 *   zoneId        区的稳定身份（命令面点名它；改 hexes 不改身份 —— 铁律 1）
 *   anchor        集散节点格（区内参考价取这一格的市场；必须在 hexes 里）
 *   radiusHex     区的<b>声明半径</b>（hex；只用于邻接判据与读数，<b>绝不</b>用它派生成员格）
 *   hexes         成员格（<b>唯一权威</b>：一个 hex 属于哪个区由它给定，I22）
 *   legalTender   本区法定币（区内价格按它计）
 *   issuingGov    本区法定币的发行政府（{@code economy} 侧的 {@link GovernmentId}；
 *                 GOV 单位 id 由 {@code GovernmentIds.unitRefOf} 反查，见 §4.3 的连线）
 *   officialRates 本区官方汇率覆盖（币对键 {@code base|quote}；空 = 本区没有覆盖，仍可回落到 GOV 级报价）
 * </pre>
 *
 * <p>★★ <b>它是持久状态</b>（{@code EconomyData.marketZones}，进 ChangeSet/Codec/往返不变式）：B2 之前市场区是纯派生件 （"城市 +
 * tier 半径"每轮现算），于是"这个 hex 属于哪个区"没有任何可写的对象，划界/退让/覆盖/合并这类治理动作无处落笔。
 * 落成状态之后<b>成员格由本记录给定</b>，派生路径退化为<b>空表时的默认值</b>（单一权威；见 {@code MarketTopologyBook}）。
 *
 * <p>★★ <b>{@code radiusHex} 为什么在、以及它不做什么</b>：成员格是显式的，半径<b>不</b>参与归属计算 —— 它只服务两件事： ① {@code
 * MarketTopology.adjacent} 的"跨区可达"判据（两个集散点的距离 ≤ 两区半径之和 + 间隙）；② 读数与日志里的人可读半径。
 * 把它写清楚是为了堵住"两套逻辑同时说了算"：{@code hexes} 说了算，半径说了不算。★ 想让某个区的成员格变化，只能走 {@code
 * economy.ReassignZoneHexes}（改 hexes），**没有**"改半径顺带改归属"这条路。
 *
 * <p>★★ <b>不变量在这里判死（fail-closed）</b>：
 *
 * <ol>
 *   <li>成员格非空、不含 null，且 {@code hexes} 的迭代序是<b>规范序</b>（{@code (q, r)} 升序）—— 迭代序是内容的纯函数，
 *       逐格结算/读数的顺序才可复现（{@code MarketRegion.members} 的既有口径）；
 *   <li>{@code anchor ∈ hexes}：锚格是"区内参考价"的取价点，不在成员里就不是这个区的锚；
 *   <li>{@code radiusHex ≥ 0}；
 *   <li>官方汇率表的键必须等于值内币对（{@link OfficialRate#key()}）—— 键与值漂开 = 有一处代码在按另一个键查它， 那种失败会在很远的读口才现形（{@code
 *       Government.officialRates} 的同一条守卫）。
 * </ol>
 *
 * <p>★ <b>跨表守卫不在这里</b>（"发行 GOV 必须登记、法定币必须在该 GOV 的 issuable 里、法定币必须在世界词表里、成员格不得同时在两个区"） —— 那几条要同时看
 * {@code governments} / {@code currencies} 与整张区表，落在 {@code EconomyData} 的构造期（那才是"完整状态"
 * 的边界），此处只判本记录自身的形状。
 *
 * @param zoneId 区的稳定身份；不得为 null（键 == 值内 zoneId 由 {@code EconomyData} 判）
 * @param anchor 集散节点格；不得为 null，且必须 ∈ {@code hexes}
 * @param radiusHex 声明半径（hex；≥ 0；不参与成员格派生）
 * @param hexes 成员格（非空、规范序、不可变）
 * @param legalTender 本区法定币；不得为 null
 * @param issuingGov 本区法定币的发行政府（{@code economy} 侧政府身份）；不得为 null
 * @param officialRates 本区官方汇率覆盖（键 = {@link OfficialRate#key()}；空 = 无覆盖）
 */
public record MarketZone(
    MarketZoneId zoneId,
    HexCoord anchor,
    int radiusHex,
    Set<HexCoord> hexes,
    CurrencyId legalTender,
    GovernmentId issuingGov,
    Map<String, OfficialRate> officialRates) {

  public MarketZone {
    Objects.requireNonNull(zoneId, "MarketZone.zoneId 不得为 null");
    Objects.requireNonNull(anchor, "MarketZone.anchor 不得为 null");
    Objects.requireNonNull(legalTender, "MarketZone.legalTender 不得为 null");
    Objects.requireNonNull(issuingGov, "MarketZone.issuingGov 不得为 null");
    if (radiusHex < 0) {
      throw new IllegalArgumentException("MarketZone.radiusHex 不得为负: " + radiusHex);
    }
    if (hexes == null || hexes.isEmpty()) {
      throw new IllegalArgumentException("MarketZone.hexes 不得为空（一个区至少含锚格）: " + zoneId.value());
    }
    List<HexCoord> sorted = new ArrayList<>(hexes.size());
    for (HexCoord hex : hexes) {
      if (hex == null) {
        throw new IllegalArgumentException("MarketZone.hexes 不得含 null: " + zoneId.value());
      }
      sorted.add(hex);
    }
    // ★ 规范序（(q,r) 升序）：迭代序 = 内容的纯函数 ⇒ 逐格结算序与读数序可复现（与 MarketTopology 的
    //   sortedHexes 同一口径）。用 LinkedHashSet 而不是 Set.copyOf：后者的迭代序不承诺是内容的纯函数。
    sorted.sort(HexCoord::compareTo);
    Set<HexCoord> hexCopy = new LinkedHashSet<>(sorted);
    if (!hexCopy.contains(anchor)) {
      throw new IllegalArgumentException(
          "MarketZone.anchor 必须 ∈ hexes（锚格是取价点）: zone="
              + zoneId.value()
              + " anchor="
              + anchor
              + " 成员数="
              + hexCopy.size());
    }
    hexes = Collections.unmodifiableSet(hexCopy); // ★ 冻在赋值处
    if (officialRates == null) {
      officialRates = Map.of();
    }
    Map<String, OfficialRate> ratesCopy = new LinkedHashMap<>();
    for (Map.Entry<String, OfficialRate> entry : officialRates.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "MarketZone.officialRates 的键/值不得为 null: zone=" + zoneId.value());
      }
      OfficialRate rate = entry.getValue();
      if (!entry.getKey().equals(rate.key())) {
        throw new IllegalArgumentException(
            "MarketZone.officialRates 的键必须等于值内币对（"
                + rate.key()
                + "）: zone="
                + zoneId.value()
                + " 键="
                + entry.getKey());
      }
      ratesCopy.put(entry.getKey(), rate);
    }
    officialRates = Collections.unmodifiableMap(ratesCopy); // ★ 冻在赋值处
  }

  /** 成员格数。 */
  public int hexCount() {
    return hexes.size();
  }

  /** 本区对某币对的官方汇率覆盖（缺 ⇒ 空：本区没有覆盖，是否回落由读取方说清）。 */
  public Optional<OfficialRate> officialRate(CurrencyId base, CurrencyId quote) {
    return OfficialRate.find(officialRates, base, quote);
  }

  /**
   * ★★ <b>只换成员格与锚</b>（其余字段原样带过）：划界/退让/覆盖/合并的唯一写入形态。
   *
   * <p>★ 为什么必须有它：手写 {@code new MarketZone(...)} 会在下一次新增组件时静默丢掉那个组件（本仓最贵的那类 bug）。 ★
   * 半径不在此列：成员格变了之后半径仍由命令显式给（{@link #withHexes(Set, HexCoord, int)}）。
   */
  public MarketZone withHexes(Set<HexCoord> nextHexes, HexCoord nextAnchor, int nextRadiusHex) {
    return new MarketZone(
        zoneId, nextAnchor, nextRadiusHex, nextHexes, legalTender, issuingGov, officialRates);
  }

  /**
   * ★★ <b>只换官方汇率覆盖里的一条</b>（法定币、发行者、成员格逐值不变）。
   *
   * <p>★ 它是"本区官方汇率"的唯一写入形态 —— 调用方拿不到"顺手把法定币也改了"的口子（{@code Government.withOfficialRate} 的同款）。
   */
  public MarketZone withOfficialRate(OfficialRate rate) {
    Objects.requireNonNull(rate, "rate");
    LinkedHashMap<String, OfficialRate> rates = new LinkedHashMap<>(officialRates);
    rates.put(rate.key(), rate);
    return new MarketZone(zoneId, anchor, radiusHex, hexes, legalTender, issuingGov, rates);
  }

  /**
   * ★★ <b>只换整张区级官方汇率表</b>（成员格、法定币、发行者逐值不变）。
   *
   * <p>★ 合并两区（{@code economy.MergeMarketZones}）要的正是"整张表取并集"这一个动作；逐条 {@link #withOfficialRate} 拼
   * 也可以，但那样"并集"这件事就散落在命令层（两处拼写 = 两处会漂）。冲突（同币对不同价）由**调用方**在合并之前判掉， 本方法只做形状守卫（键 == 值内币对）。
   */
  public MarketZone withOfficialRates(Map<String, OfficialRate> nextRates) {
    return new MarketZone(zoneId, anchor, radiusHex, hexes, legalTender, issuingGov, nextRates);
  }

  /** 本区一行人类可读摘要（日志/探针用；只输出稳定 id 与数量，§一.9 的日志纪律）。 */
  public String describe() {
    return zoneId.value()
        + "@"
        + anchor
        + "["
        + hexes.size()
        + "格,r="
        + radiusHex
        + "]="
        + legalTender.value()
        + "/"
        + issuingGov.value()
        + (officialRates.isEmpty() ? "" : " rates=" + officialRates.size());
  }
}

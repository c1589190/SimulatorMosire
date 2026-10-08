package io.mosire.simos.economy.model;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.fx.OfficialRate;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.map.hex.HexCoord;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>市场区与"发行政府 ↔ GOV 连线"的只读查询面</b>（阶段 2-B2，2026-10-08；约束设计书 §4.2/§4.3；不变量 I22）。
 *
 * <p>★★ <b>它解决什么问题</b>：{@code EconomyData.marketZones} 是一张 {@code Map<MarketZoneId,
 * MarketZone>}，而调用方真正要问的是 ① "这个 hex 属于哪个区"（结算/读数/命令守卫都要）、② "某个 GOV 发行哪些币" / "某种币由哪个 GOV 发行"（§4.3
 * 的连线，用户 2026-10-08 裁定"一个 GOV 拥有发行货币的权利"）、③ "本区官方汇率是多少，没有区级覆盖时回落到谁"。把这三件事的拼写点收在这里，
 * 别处不许再遍历表自己拼（同一件事两处拼写 ⇒ 两处会漂）。
 *
 * <p>★★ <b>权威口径（I22）</b>：一个 hex 属于哪个区<b>只看</b> {@code marketZones} 的成员格；本类<b>不</b>做任何"半径推算"或
 * "最近锚格"的兜底 —— 那是派生路径（空表时的默认值，见 {@code MarketTopologyBook}）的事。空区表 ⇒ 本类全部查询给出"没有区" 的答案，而不是自己造一个。
 *
 * <p>★ <b>保序口径</b>：{@link #zones(EconomyData)} 与各 {@code Set} 结果都按稳定 id 升序（{@link Comparator}
 * 的规范序）， 不依赖 {@code LinkedHashMap} 的写入史 —— 读数与日志要可复现。
 *
 * <p>★ <b>不写状态</b>：本类全是纯函数，不落盘、不进变更集、不改任何切片（{@code IndustryHexKeys} 的同款形制）。
 */
public final class MarketZoneBook {

  private MarketZoneBook() {}

  /**
   * 全部市场区（<b>规范序</b>：{@link MarketZoneId} 的裸值升序）。
   *
   * <p>★ 排序而不按表插入序：插入序是"写入史"的函数，读写两边会因为同一份内容的不同历史给出不同读数序（{@code MarketTopology.regions()} 是声明序 ⇒
   * 逐区读数必须同样确定）。
   */
  public static List<MarketZone> zones(EconomyData data) {
    Objects.requireNonNull(data, "data");
    return zones(data.marketZones());
  }

  /**
   * ★★ <b>B4（2026-10-08）：区表（裸 {@code Map}）取规范序</b> —— 给"手里只有区表、没有整份 {@code EconomyData}"
   * 的调用方（外汇窗口装配）用，口径与 {@link #zones(EconomyData)} <b>逐字相同</b>（唯一排序拼写点）。
   */
  public static List<MarketZone> zones(Map<MarketZoneId, MarketZone> zoneTable) {
    Objects.requireNonNull(zoneTable, "zoneTable");
    if (zoneTable.isEmpty()) {
      return List.of();
    }
    List<MarketZone> sorted = new ArrayList<>(zoneTable.values());
    sorted.sort(Comparator.comparing(zone -> zone.zoneId().value()));
    return List.copyOf(sorted);
  }

  /** 区 id 集合（规范序）。 */
  public static Set<MarketZoneId> zoneIds(EconomyData data) {
    Set<MarketZoneId> ids = new LinkedHashSet<>();
    for (MarketZone zone : zones(data)) {
      ids.add(zone.zoneId());
    }
    return java.util.Collections.unmodifiableSet(ids);
  }

  /** 某个 hex 所属的区（成员格 = 唯一权威；不属于任何区 ⇒ 空 —— "没有区"是合法状态，不是故障）。 */
  public static Optional<MarketZone> zoneOfHex(EconomyData data, HexCoord hex) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(hex, "hex");
    for (MarketZone zone : zones(data)) {
      if (zone.hexes().contains(hex)) {
        return Optional.of(zone);
      }
    }
    return Optional.empty();
  }

  /** 区 id 取区（查无 ⇒ 空）。 */
  public static Optional<MarketZone> zone(EconomyData data, MarketZoneId zoneId) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(zoneId, "zoneId");
    return Optional.ofNullable(data.marketZones().get(zoneId));
  }

  /**
   * ★★ <b>§4.3 的连线：这个区的发行政府对应哪个 GOV 单位</b>（{@code gov-unit-<govUnitId>} 的反查）。
   *
   * <p>★ 区挂的是<b>世界级政府身份</b>（{@code gov-unit-…}），而"GOV 单位"住在 unit 切片 ⇒ 连线靠 {@link
   * GovernmentIds#unitRefOf} 把身份翻译成单位 id。世界级政府（{@code world-silver} 一类，不是任何 GOV 单位）⇒ 空 （不猜，{@code
   * GovernmentIds} 的既有口径）。
   */
  public static Optional<String> issuingGovUnitOf(MarketZone zone) {
    Objects.requireNonNull(zone, "zone");
    return GovernmentIds.unitRefOf(zone.issuingGov());
  }

  /**
   * ★★ <b>"某种币由哪些 GOV 单位发行"</b>（§4.3 的查询面）：扫 {@code governments} 的 {@code issuable}，把命中的政府身份翻译成 GOV
   * 单位 id（规范序；世界级政府 / 未登记的政府不计入）。
   *
   * <p>★ 一个币种落在多个 GOV 的 {@code issuable} 里是**状态里的事实**（本仓"一币一发行人"由 9 处守卫在写入侧拒），本方法 照实列出全部而不替调用方挑一个
   * —— 挑一个就等于把"谁发的"这件事藏进一个任意的 choice。
   */
  public static Set<String> govUnitIdsIssuing(EconomyData data, CurrencyId currency) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(currency, "currency");
    List<String> units = new ArrayList<>();
    for (Government government : data.governments().values()) {
      if (government.issuable().contains(currency)) {
        GovernmentIds.unitRefOf(government.id()).ifPresent(units::add);
      }
    }
    units.sort(Comparator.naturalOrder());
    return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(units));
  }

  /**
   * ★★ <b>"某个 GOV 单位发行哪些币"</b>（§4.3 的查询面）：{@code gov-unit-<govUnitId>} 查政府表 ⇒ 它的 {@code issuable}
   * （规范序）。政府未登记 ⇒ 空集（"这个单位不是发行人"是合法答案）。
   *
   * @throws IllegalArgumentException govUnitId 为空白 / 含分段符 / 已带 {@code gov-unit-} 前缀（{@link
   *     GovernmentIds#ofUnit} 的口径）
   */
  public static Set<CurrencyId> currenciesIssuedBy(EconomyData data, String govUnitId) {
    Objects.requireNonNull(data, "data");
    GovernmentId governmentId = GovernmentIds.ofUnit(govUnitId);
    Government government = data.governments().get(governmentId);
    if (government == null) {
      return Set.of();
    }
    List<CurrencyId> currencies = new ArrayList<>(government.issuable());
    currencies.sort(Comparator.comparing(CurrencyId::value));
    return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(currencies));
  }

  /**
   * ★★ <b>某个 hex 上的官方汇率（区级优先，回落到该区发行政府的 GOV 级报价）</b>（§4.3："A 阶段挂在 GOV 上的官方汇率在此迁到 市场区"）。
   *
   * <pre>
   * hex ∈ 某区 且该区有该币对覆盖  ⇒ 区级报价
   * hex ∈ 某区 但没有该币对覆盖    ⇒ 该区发行政府的 GOV 级报价（缺 ⇒ 空）
   * hex ∉ 任何区                  ⇒ 空（说不出"谁在这里报价"）
   * </pre>
   *
   * <p>★★ <b>不回落成"随便哪个 GOV 的价"</b>：外汇窗口的激活条件是"某个币对<b>有</b>官方汇率"（{@code FxRoundInput} 的 fail-closed
   * 口径），把"没有任何 GOV 报价"伪装成一条报价正是 I18 禁掉的那类事（政策价冒充市场价）。
   *
   * <p>★★ <b>B4（2026-10-08）更新</b>：本方法的口径（区级优先、按币对回落）自 B4 起<b>同时是外汇窗口的装配口径</b> （{@link
   * #effectiveRateOf} / {@link #effectiveRatesOf}，供 {@code FxRoundInput.of(..., marketZones)} 使用）——
   * B2 落地的区级覆盖当时只进"状态 + 命令面 + 读数 + 本方法"，于是区级汇率<b>不产生窗口</b>（three-powers 的实测缺陷），B4 补上这条接线。★
   * <b>仍未收窄的</b>：窗口是<b>世界级</b>的（{@code FxSettlement} 的币对簿不对区隔离，"哪个区的人跟哪个窗口成交"是待裁定的开放点）， 把匹配域限定在本区会改
   * A2 的撮合语义与 F2/F3/F4 的读数，留给阶段 3（口岸/管制）。
   */
  public static Optional<OfficialRate> officialRateFor(
      EconomyData data, HexCoord hex, CurrencyId base, CurrencyId quote) {
    Objects.requireNonNull(data, "data");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(quote, "quote");
    Optional<MarketZone> zone = zoneOfHex(data, hex);
    if (zone.isEmpty()) {
      return Optional.empty();
    }
    Optional<OfficialRate> zoneRate = zone.get().officialRate(base, quote);
    if (zoneRate.isPresent()) {
      return zoneRate;
    }
    Government government = data.governments().get(zone.get().issuingGov());
    return government == null ? Optional.empty() : government.officialRate(base, quote);
  }

  /** 逐区一行人类可读摘要（保序；供日志与探针直读）。 */
  public static List<String> describe(EconomyData data) {
    List<String> lines = new ArrayList<>();
    for (MarketZone zone : zones(data)) {
      lines.add(zone.describe());
    }
    return List.copyOf(lines);
  }

  // ── B4（2026-10-08）：一个 GOV 的"生效报价"（区级优先、按币对回落）────────────────────────

  /**
   * ★★ <b>B4：覆盖某 GOV 某币对的<b>全部</b>区</b>（规范序；空 = 没有区级覆盖 ⇒ 回落到 GOV 级）。
   *
   * <p>★ 两个用途：① 取生效价 = 第一个（{@link #zoneCovering}）；② 把"冲突时被覆盖的那几条报价"<b>具名</b>列出来 （装订点的 {@code
   * FX_ZONE_RATE_CONFLICT}，诊断"我设的价为什么没用"）—— 一条被静默丢掉的政府报价正是本仓最忌讳的那类事。
   */
  public static List<MarketZone> zonesCovering(
      Map<MarketZoneId, MarketZone> marketZones,
      GovernmentId governmentId,
      CurrencyId base,
      CurrencyId quote) {
    Objects.requireNonNull(marketZones, "marketZones");
    Objects.requireNonNull(governmentId, "governmentId");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(quote, "quote");
    List<MarketZone> covering = new ArrayList<>();
    for (MarketZone zone : zones(marketZones)) {
      if (zone.issuingGov().equals(governmentId) && zone.officialRate(base, quote).isPresent()) {
        covering.add(zone);
      }
    }
    return List.copyOf(covering);
  }

  /**
   * ★★ <b>B4：某个 GOV 的某个币对由哪个区级覆盖给出</b>（规范序<b>第一个</b>覆盖它的本 GOV 所辖区；没有覆盖 ⇒ 空）。
   *
   * <p>★ <b>为什么"第一个"要具名</b>：一个 GOV 下辖两个区、两区对同一币对给了不同报价时，"用哪条"必须有一个可复现的口径 （{@link #zones(Map)}
   * 的规范序），否则窗口价会随区表的写入史漂开。★ 冲突本身不由本方法吞掉：装订点 （{@code EconomySettlement#logFxWindows}）把被覆盖的那几条按 DEBUG
   * 具名记下来（见 {@link #zonesCovering}）。
   */
  public static Optional<MarketZone> zoneCovering(
      Map<MarketZoneId, MarketZone> marketZones,
      GovernmentId governmentId,
      CurrencyId base,
      CurrencyId quote) {
    List<MarketZone> covering = zonesCovering(marketZones, governmentId, base, quote);
    return covering.isEmpty() ? Optional.empty() : Optional.of(covering.get(0));
  }

  /**
   * ★★ <b>B4：一个 GOV 对某个币对的生效报价</b>（{@code FxRoundInput} 的窗口价唯一来源）：
   *
   * <pre>
   * 本 GOV 所辖区里有区级覆盖 ⇒ 该区级报价（规范序第一个覆盖者，见 {@link #zoneCovering}）
   * 没有区级覆盖             ⇒ 本 GOV 的 GOV 级报价（回落；缺 ⇒ 空）
   * </pre>
   *
   * <p>★ 回落<b>按币对</b>判（不是"这个区有区级表就整体回落"）：区级覆盖的语义是"覆盖某几个币对"，某区只覆盖了 A/B 时，该区发行 GOV 的 C/D 报价仍然有效 —— 与
   * {@link #officialRateFor} 的逐 hex/逐币对口径一致。
   */
  public static Optional<OfficialRate> effectiveRateOf(
      Government government,
      Map<MarketZoneId, MarketZone> marketZones,
      CurrencyId base,
      CurrencyId quote) {
    Objects.requireNonNull(government, "government");
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(quote, "quote");
    Optional<MarketZone> covering =
        marketZones == null
            ? Optional.empty()
            : zoneCovering(marketZones, government.id(), base, quote);
    if (covering.isPresent()) {
      Optional<OfficialRate> zoneRate = covering.get().officialRate(base, quote);
      if (zoneRate.isPresent()) {
        return zoneRate;
      }
    }
    return government.officialRate(base, quote);
  }

  /**
   * ★★ <b>B4：一个 GOV 的整张生效报价表</b>（键 = {@code OfficialRate.key()}；<b>币对升序</b>， 与 A2a 的窗口序同口径 ⇒
   * 窗口序不依赖区表/报价表的写入史）。空表 = 这个 GOV 没有任何生效报价（没有外汇窗口）。
   *
   * <p>币对的全集 = "本 GOV 所辖区的区级覆盖" ∪ "本 GOV 的 GOV 级报价"；每条取值一律经 {@link #effectiveRateOf} （优先级只有一处拼写）。★
   * 区级覆盖在这里只是"投到发行 GOV 的窗口上"，不复制窗口（一个 GOV × 一个币对恰一条）。
   */
  public static Map<String, OfficialRate> effectiveRatesOf(
      Government government, Map<MarketZoneId, MarketZone> marketZones) {
    Objects.requireNonNull(government, "government");
    Map<String, OfficialRate> candidates = new LinkedHashMap<>();
    if (marketZones != null) {
      for (MarketZone zone : zones(marketZones)) {
        if (!zone.issuingGov().equals(government.id())) {
          continue;
        }
        for (OfficialRate rate : sortedRates(zone.officialRates())) {
          candidates.putIfAbsent(rate.key(), rate);
        }
      }
    }
    for (OfficialRate rate : sortedRates(government.officialRates())) {
      candidates.putIfAbsent(rate.key(), rate);
    }
    List<OfficialRate> ordered = new ArrayList<>(candidates.values());
    ordered.sort(RATE_ORDER);
    Map<String, OfficialRate> effective = new LinkedHashMap<>();
    for (OfficialRate candidate : ordered) {
      OfficialRate rate =
          effectiveRateOf(government, marketZones, candidate.base(), candidate.quote())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "生效报价装配故障：候选币对 " + candidate.key() + " 查不到生效值（内部不一致）"));
      effective.put(rate.key(), rate);
    }
    return java.util.Collections.unmodifiableMap(effective);
  }

  /** 币对的确定序：(base, quote) 升序（与 {@code FxRoundInput} 的窗口序同口径）。 */
  private static final Comparator<OfficialRate> RATE_ORDER =
      Comparator.comparing((OfficialRate r) -> r.base().value())
          .thenComparing(r -> r.quote().value());

  /** 一张报价表按币对升序（键 == 值内币对由各表自身的构造期守卫保证）。 */
  private static List<OfficialRate> sortedRates(Map<String, OfficialRate> rates) {
    List<OfficialRate> sorted = new ArrayList<>(rates.values());
    sorted.sort(RATE_ORDER);
    return sorted;
  }
}

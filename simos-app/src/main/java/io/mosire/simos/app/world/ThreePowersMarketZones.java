package io.mosire.simos.app.world;

import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.GovernmentIds;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.codec.EconomyCodec;
import io.mosire.simos.economy.model.Government;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.economy.spi.EconomyDefineMarketZoneHandler;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>B3（阶段 2-B 第三批，2026-10-08）：把 {@code three-powers} 的 3 个市场区落成持久状态</b>（约束设计书 §4.1/§4.2、不变量
 * <b>I22</b>；命令面用 B2 已交付的 {@code economy.DefineMarketZone}）。
 *
 * <p>★★ <b>它解决哪件事（B2 复核发现的缺口）</b>：B2 交付了<b>机制</b>（{@code EconomyData.marketZones} + 三条 GM 命令 +
 * Codec 往返 + {@code MarketTopologyBook} 持久区优先），但 {@code three-powers} 的<b>创世没有写区表</b> ⇒ 该世界仍走"城市 +
 * tier 半径"的<b>派生</b>路径、{@code marketZones} 恒为空 ⇒ I22（市场区单一权威）在这个世界上<b>没有真正生效</b>。本类补上它：
 * 创世期逐行政区落一条持久区，成员格 = 该行政区（= 该 GOV 辖区）的全部格。
 *
 * <p>★★ <b>命令序落点（不可换）</b>：{@link #define} 由 {@code ThreePowersGovBootstrap.apply} 在 <b>全部 GOV 的
 * per-GOV 命令序跑完之后、{@code actor.AdjustAccounts}（国库注资）之前</b>调用。依赖是 {@code economy.DefineMarketZone}
 * 自己的判据（{@code EconomyDefineMarketZoneHandler#handle}；逐条 file:line 见 B3 账本 §1）：
 *
 * <pre>
 * ① 法定币 ∈ 世界词表         ⇒ 逐 GOV 的 economy.DefineCurrency 必须已跑（铜/金）
 * ② 锚格有市场行 / 成员格的计价币 = 法定币 ⇒ economy.Seed（本世界第一条经济命令，远早于此处）
 * ③ 该格不属于别的区          ⇒ 本批三条命令各定义一区，区两两不相交
 * </pre>
 *
 * ★★ 2026-10-09 C 批更新：原依赖 ②③（"发行 GOV 已登记"、"该 GOV 的 issuable 含该币"）随 {@code MarketZone.issuingGov}
 * 一并退役（用户「法定货币发行者也丢掉」；设计书 §4.1/G1、§4.3/G4：立区不管"谁发得出"）。⇒ 现在<b>最早可行点</b>是"最后一个 GOV 的 {@code
 * economy.DefineCurrency} 之后"（币种进词表）；实际落点仍取<b>整个 per-GOV 循环之后</b>（同时满足"所有 {@code
 * unit.SetJurisdiction} 之后"），并把区表自检放在国库注资之前 ⇒ <b>坏区表在铸币/审计发生之前就中止创世</b>。
 *
 * <p>★★ <b>五条创世期自检（全部 fail-closed，任一不成立 ⇒ 具名抛，绝不落半套）</b>（{@link #requireZones}，与 B1 的 I23 自检 {@code
 * ThreePowersGovBootstrap#requireDisjointJurisdictions} 同风格）：
 *
 * <ol>
 *   <li>{@code zone-count}：状态里的区数 = 本次创世声明的区数（3）；
 *   <li>{@code zone-hexes-vs-region}：每区的 {@code hexes} <b>恰等于</b>对应行政区的 hex 集（逐 hex 相等，不是比个数）；
 *   <li>{@code zone-tender-distinct}：三区法定币两两不同（"每区法定币不同"是 G1 的一半）；
 *   <li>{@code zone-issuing-gov}：本区的 GOV 已登记为政府、<b>且</b>它的 {@code issuable} 确实含该区法定币
 *       （"谁发行的"不许在两处漂开）—— ★ C 批起这条改读<b>政府表</b>（区里已无 {@code issuingGov}；见 {@link #requireZones} ④）。
 *   <li>{@code zone-union-vs-jurisdiction} / {@code zone-union-vs-map}：三区并集 = 各辖区并集 <b>且</b> = 全图
 *       hex 集， 逐格归属恰一个区（I23 的市场区侧）。
 * </ol>
 *
 * <p>★ 另加两条同族的具名检查（都在 {@link #requireZones} 内）：锚格 = 声明锚格（{@code zone-anchor-mismatch}）、成员格逐格
 * <b>有市场行</b>（{@code zone-hex-without-market}）—— 后者保证"持久区成员集"与派生路径（只取有市场的格）逐格重合，于是
 * "换成持久权威"这件事<b>不改任何读数</b>。
 *
 * <p>★★ <b>半径为什么是 8、以及它不做什么</b>：三座城都是 {@code City} 档 ⇒ 派生路径给的半径就是 8（{@code
 * MarketTopologyBook.TIER_RADIUS_HEX} 的 City 档）。持久区沿用同一值 ⇒ 半径读数与 {@code MarketTopology#adjacent}
 * 的跨区可达判据逐值不变。★ 半径<b>不</b>参与成员格派生（成员格来自行政区 hex 集，I22 的单一权威）。
 *
 * <p>★ <b>不做</b>（与任务书"明确不做"一致）：不给三区设<b>官方汇率</b>（留给控制方验收时用 GM 命令设，便于观察"设汇率前外汇面 沉默"）；不把 FX
 * 窗口按区收窄；口岸/关税；跨区套利的新机制。★ 也<b>不</b>碰 {@code SmallWorld}/{@code corridor}：它们的区表保持 为空 ⇒
 * 逐字走派生路径（旧世界逐值不变）。
 */
final class ThreePowersMarketZones {

  /**
   * 持久区的<b>声明半径</b>（hex）：三座城都是 {@code City} 档 ⇒ 派生路径的半径就是 8。
   *
   * <p>★ 它只影响 {@code MarketTopology.adjacent} 的"跨区可达"判据与读数，<b>绝不</b>参与成员格派生（见类注）。
   */
  static final int ZONE_RADIUS_HEX = 8;

  /** 创世 INFO（逐区一条）：区 id / 格数 / 法定币 / 锚格（★ C 批起不再记"发行 GOV"——区里已无该栏）。 */
  static final String EVENT_ZONE_PERSISTED = "THREE_POWERS_GENESIS_MARKET_ZONE_PERSISTED";

  /** 创世 INFO（一条汇总）：几个区、覆盖几格、权威 = persistent、设计要求自检全过。 */
  static final String EVENT_ZONES_PERSISTED = "THREE_POWERS_GENESIS_MARKET_ZONES_PERSISTED";

  /** 创世 ERROR（自检失败）：具名检查 id + 失败的区/格（契约/跨切片一致性故障 = ERROR 不降级）。 */
  static final String EVENT_SELF_CHECK_FAILED =
      "THREE_POWERS_GENESIS_MARKET_ZONE_SELF_CHECK_FAILED";

  /**
   * 设计书/任务书要求的<b>自检族</b>条数（①区数 ②hexes = 行政区 ③法定币两两不同 ④发行 GOV 已登记且确实发行该币 ⑤并集 = 全图且互斥）。
   *
   * <p>★ 落成代码后这 5 条族共展开为 <b>12 个具名检查 id</b>（族内每条判据各自具名，日志与探针据此定位；完整清单见 B3 账本 §3）： 例如 ④ 由 {@code
   * zone-issuing-gov} 覆盖三种失败（未登记 / 发行者不符 / {@code issuable} 不含该币）， ⑤ 由 {@code zone-hex-disjoint} +
   * {@code zone-union-vs-jurisdiction} + {@code zone-union-vs-map} 覆盖。
   */
  static final int REQUIRED_SELF_CHECKS = 5;

  /** 命令类型（唯一拼写点取 handler 自己的 {@code TYPE}；本类只做创世调度，不另拼字符串）。 */
  private static final String DEFINE_TYPE = EconomyDefineMarketZoneHandler.TYPE;

  private ThreePowersMarketZones() {}

  /**
   * 一个待落地的市场区事实（由 {@link ThreePowersGovBootstrap.GovSpec} 派生；本类只读这五个字段）。
   *
   * <p>★ 为什么把"区"单独抽成一条记录而不是直接吃 {@code GovSpec}：区落地的判据只用这五件事（区身份 / 锚格 / 辖区 / 发行 GOV / 法定币），而 {@code
   * GovSpec} 还带着国库、官吏来源等与区无关的事实 —— 把依赖面收窄到五件事，创世自检与 负向对照（故意给错法定币 / 给错辖区）才有唯一的口子。
   *
   * @param zoneId 区稳定身份（= 本区城市 id；与派生路径的节点 id 同字面 ⇒ 换权威不改读数）
   * @param anchor 集散节点格（= 该 GOV 的座位格 = 该区城市格；必须 ∈ 辖区）
   * @param jurisdictionRegionId 该 GOV 的行政区 id（区成员格 = 它的 hex 集）
   * @param govUnitId 本区 GOV 单位 id（{@code GovernmentIds.ofUnit} 反查政府身份）—— ★ C 批起它<b>不再进区记录</b>
   *     （{@code MarketZone.issuingGov} 已退役），只服务创世自检 ④ 与日志/载荷的"这个世界里区 = 谁的辖区"这一句陈述
   * @param legalTender 本区法定币（★ C 批起区记录里它是区自己的事实；本世界里仍取该 GOV 发行的币）
   */
  record ZoneSpec(
      String zoneId,
      HexCoord anchor,
      String jurisdictionRegionId,
      String govUnitId,
      CurrencyId legalTender) {

    ZoneSpec {
      requireNonBlank(zoneId, "zoneId");
      Objects.requireNonNull(anchor, "anchor");
      requireNonBlank(jurisdictionRegionId, "jurisdictionRegionId");
      requireNonBlank(govUnitId, "govUnitId");
      Objects.requireNonNull(legalTender, "legalTender");
    }
  }

  /**
   * 从 GOV spec 表派生区事实表（本 GOV 的区 id / 座位 / 辖区 / 单位 id + <b>法定币 = 该 GOV 发行的币</b>）。
   *
   * <p>★ 法定币不在这里另立一份拼写：它取自 spec 的"本 GOV 会发行什么"（{@code declaredIssuable} ∪ {@code newCurrency}），
   * 恰一种才说得出来；说不出 / 多于一种 ⇒ <b>具名抛</b>（"一区一币"是这个世界形态的一部分，不许猜）。
   *
   * @throws IllegalStateException 区 id 重复 / 某 GOV 说不出唯一法定币（装配故障）
   */
  static List<ZoneSpec> zoneSpecsOf(List<ThreePowersGovBootstrap.GovSpec> specs) {
    Objects.requireNonNull(specs, "specs");
    List<ZoneSpec> zones = new ArrayList<>(specs.size());
    Set<String> zoneIds = new LinkedHashSet<>();
    for (ThreePowersGovBootstrap.GovSpec spec : specs) {
      if (!zoneIds.add(spec.marketZoneId())) {
        throw new IllegalStateException(
            "两个 GOV 的市场区 id 重复（区 id = 身份，铁律 1）: " + spec.marketZoneId() + "（已见 " + zoneIds + "）");
      }
      zones.add(
          new ZoneSpec(
              spec.marketZoneId(),
              spec.seat(),
              spec.jurisdictionRegionId(),
              spec.govId(),
              legalTenderOf(spec)));
    }
    return List.copyOf(zones);
  }

  /**
   * 逐区发一条 {@code economy.DefineMarketZone}（真 handler + 真 codec，与创世路径的其它命令同一口），随后跑七条具名自检。
   *
   * @param state 已完成 map/social/unit/economy/actor/gov 切片与全部 GOV 行政链的状态
   * @param applier 创世命令执行口（{@code ThreePowersWorld::applyCommand}；被拒 ⇒ 当场抛）
   * @param map 真地图（只读行政区 hex 集，用于成员格与自检）
   * @param zones 区事实表（顺序即定义序 = 声明序）
   * @return 追加 3 条持久区后的状态
   * @throws IllegalStateException 任一条命令被具名拒、或任一自检不成立（创世整次失败，不落半截世界）
   */
  static SimulationState define(
      SimulationState state,
      ThreePowersGovBootstrap.CommandApplier applier,
      GameMap map,
      List<ZoneSpec> zones) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(applier, "applier");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(zones, "zones");
    if (zones.isEmpty()) {
      throw new IllegalArgumentException("zones 不得为空（没有区就没有持久区表）");
    }
    SimulationState next = state;
    for (ZoneSpec zone : zones) {
      next =
          applier.apply(
              next,
              DEFINE_TYPE,
              new EconomyDefineMarketZoneHandler(),
              new EconomyCodec(),
              payload(zone, map));
    }
    requireZones(next, map, zones);
    logZones(next, zones);
    return next;
  }

  /**
   * ★★ <b>创世期自检</b>：五条设计判据（①区数 ②hexes = 行政区 ③法定币两两不同 ④发行 GOV 已登记且确实发行该币 ⑤并集 = 全图且互斥） 展开为 12 个具名检查
   * id；全部 fail-closed，任一不成立 ⇒ 先记一条 ERROR（具名）再具名抛。
   *
   * <p>★ 检查顺序 = "先问区表长什么样（①/②），再问谁发行（④），最后问这张表铺满了吗（③/⑤）" —— 与 {@code
   * EconomyDefineMarketZoneHandler} 的判据顺序同一口径（先问"你立的是什么区"，再问"这些格腾出来了吗"），失败信息因此指向 最早出错的那件事。
   *
   * @throws IllegalStateException 任一检查不成立（消息以 {@code [<check-id>]} 具名）
   */
  static void requireZones(SimulationState state, GameMap map, List<ZoneSpec> zones) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(zones, "zones");
    EconomyData economy = requireEconomy(state);

    // ① 区数 = 声明数（多一个少一个都说明有别的写入者或漏发命令）
    if (economy.marketZones().size() != zones.size()) {
      throw fail(
          "zone-count",
          "状态里的市场区数 "
              + economy.marketZones().size()
              + " ≠ 创世声明的 "
              + zones.size()
              + "（区表="
              + zoneIdsOf(economy)
              + "）");
    }

    List<MarketZone> ordered = new ArrayList<>(zones.size());
    Set<CurrencyId> tenders = new LinkedHashSet<>();
    for (ZoneSpec spec : zones) {
      MarketZone zone =
          MarketZoneBook.zone(economy, new MarketZoneId(spec.zoneId()))
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "three-powers 创世市场区自检失败 [zone-missing]: 区 "
                              + spec.zoneId()
                              + " 没有落进状态（命令被静默丢弃？）"));
      ordered.add(zone);
      // ①b 锚格 = 声明锚格（座位格 = 城市格）
      if (!zone.anchor().equals(spec.anchor())) {
        throw fail(
            "zone-anchor-mismatch",
            "区 " + spec.zoneId() + " 的锚格 " + zone.anchor() + " ≠ 声明锚格 " + spec.anchor());
      }
      // ② hexes 恰等于该行政区的 hex 集（逐 hex，不是比个数）
      Region region = map.regions().get(new RegionId(spec.jurisdictionRegionId()));
      if (region == null) {
        throw fail(
            "zone-region-missing",
            "区 " + spec.zoneId() + " 的行政区 " + spec.jurisdictionRegionId() + " 不在当前地图里");
      }
      Set<HexCoord> missing = new LinkedHashSet<>(region.hexes());
      missing.removeAll(zone.hexes());
      Set<HexCoord> extra = new LinkedHashSet<>(zone.hexes());
      extra.removeAll(region.hexes());
      if (!missing.isEmpty() || !extra.isEmpty()) {
        throw fail(
            "zone-hexes-vs-region",
            "区 "
                + spec.zoneId()
                + " 的成员格 ≠ 行政区 "
                + spec.jurisdictionRegionId()
                + " 的 hex 集：少 "
                + missing.size()
                + " 格"
                + head(missing)
                + "、多 "
                + extra.size()
                + " 格"
                + head(extra));
      }
      // ②b 成员格逐格有市场行（派生路径只取有市场的格 ⇒ 这条保证两条路径的成员集逐格重合）
      for (HexCoord hex : zone.hexes()) {
        if (!economy.markets().containsKey(hex)) {
          throw fail(
              "zone-hex-without-market",
              "区 " + spec.zoneId() + " 的成员格 " + hex + " 没有市场行（派生路径不会把它算作成员 ⇒ 两条路径会漂开）");
        }
      }
      // ③ 本区法定币 = 声明值（命令载荷没被改写）
      if (!zone.legalTender().equals(spec.legalTender())) {
        throw fail(
            "zone-tender-mismatch",
            "区 "
                + spec.zoneId()
                + " 的法定币 "
                + zone.legalTender().value()
                + " ≠ 声明 "
                + spec.legalTender().value());
      }
      // ④ 本区法定币由本区 GOV 声明发行（"谁发行的"不许在两处漂开）——
      //   ★★ 2026-10-09 C 批：这条改读**政府表**（`government.issuable()`），不再读 `zone.issuingGov()`（该组件已退役，
      //   用户「法定货币发行者也丢掉」）。区里没有发行者，但**这个世界**声明的事实仍然可以是"本区 GOV 发行本区的钱"，
      //   自检因此一字不松（它验的是世界形态，不是区的准入规则；立区早已不看"谁发得出"，见设计书 §4.3/G4）。
      GovernmentId govId = GovernmentIds.ofUnit(spec.govUnitId());
      Government government = economy.governments().get(govId);
      if (government == null) {
        throw fail(
            "zone-issuing-gov", "区 " + spec.zoneId() + " 的发行 GOV " + govId.value() + " 未登记为政府");
      }
      if (!government.issuable().contains(spec.legalTender())) {
        throw fail(
            "zone-issuing-gov",
            "区 "
                + spec.zoneId()
                + " 的法定币 "
                + spec.legalTender().value()
                + " 不在发行 GOV "
                + govId.value()
                + " 的 issuable 里（issuable="
                + government.issuable()
                + "）");
      }
      tenders.add(spec.legalTender());
    }

    // ⑤a 三区法定币两两不同（"每区法定币不同"是 G1 的一半）
    if (tenders.size() != zones.size()) {
      throw fail(
          "zone-tender-distinct",
          "三区法定币不是两两不同：声明 " + zones.size() + " 个区但只有 " + tenders.size() + " 种法定币（" + tenders + "）");
    }

    // ⑤b 逐格归属恰一个区；并集 = 各辖区并集 且 = 全图 hex 集（I23 的市场区侧）
    Map<HexCoord, String> owner = new LinkedHashMap<>();
    Set<HexCoord> zoneUnion = new LinkedHashSet<>();
    for (MarketZone zone : ordered) {
      for (HexCoord hex : zone.hexes()) {
        zoneUnion.add(hex);
        String previous = owner.putIfAbsent(hex, zone.zoneId().value());
        if (previous != null) {
          throw fail(
              "zone-hex-disjoint",
              "格 " + hex + " 同时属于市场区 " + previous + " 与 " + zone.zoneId().value() + "（I22）");
        }
      }
    }
    Set<HexCoord> jurisdictionUnion = new LinkedHashSet<>();
    for (ZoneSpec spec : zones) {
      Region region = map.regions().get(new RegionId(spec.jurisdictionRegionId()));
      if (region != null) {
        jurisdictionUnion.addAll(region.hexes());
      }
    }
    if (!zoneUnion.equals(jurisdictionUnion)) {
      Set<HexCoord> onlyZones = new LinkedHashSet<>(zoneUnion);
      onlyZones.removeAll(jurisdictionUnion);
      Set<HexCoord> onlyRegions = new LinkedHashSet<>(jurisdictionUnion);
      onlyRegions.removeAll(zoneUnion);
      throw fail(
          "zone-union-vs-jurisdiction",
          "三区并集 ≠ 各辖区并集：仅在区里 "
              + onlyZones.size()
              + " 格"
              + head(onlyZones)
              + "、仅在辖区里 "
              + onlyRegions.size()
              + " 格"
              + head(onlyRegions));
    }
    if (!zoneUnion.equals(map.hexes().keySet())) {
      Set<HexCoord> uncovered = new LinkedHashSet<>(map.hexes().keySet());
      uncovered.removeAll(zoneUnion);
      throw fail(
          "zone-union-vs-map",
          "三区并集 = "
              + zoneUnion.size()
              + " 格 ≠ 全图 "
              + map.hexes().size()
              + " 格（未覆盖 "
              + uncovered.size()
              + " 格"
              + head(uncovered)
              + "；three-powers 的世界形态是 3 个辖区铺满全图）");
    }
    EventLog.channel(AppLog.shell())
        .debug(
            LogEvent.of(
                "THREE_POWERS_GENESIS_MARKET_ZONE_SELF_CHECK_OK",
                AppLogSource.SHELL_LIFECYCLE,
                "world",
                ThreePowersWorld.MAP_ID,
                "zones",
                zones.size(),
                "tenders",
                tenders.size(),
                "hexes",
                zoneUnion.size(),
                "requiredSelfChecks",
                REQUIRED_SELF_CHECKS));
  }

  // ── 载荷与日志 ────────────────────────────────────────────────────────────────────────

  /** {@code economy.DefineMarketZone} 载荷（字段形状以 handler 的类注为准；成员格 (q,r) 升序）。 */
  private static String payload(ZoneSpec zone, GameMap map) {
    Region region = map.regions().get(new RegionId(zone.jurisdictionRegionId()));
    if (region == null) {
      throw new IllegalStateException(
          "three-powers 创世市场区装配故障：行政区 "
              + zone.jurisdictionRegionId()
              + " 不在当前地图里（GOV "
              + zone.govUnitId()
              + " 的辖区）");
    }
    List<HexCoord> hexes = new ArrayList<>(region.hexes());
    hexes.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    List<Map<String, Object>> rows = new ArrayList<>(hexes.size());
    for (HexCoord hex : hexes) {
      rows.add(ToolSupport.hexCoord(hex));
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("zoneId", zone.zoneId());
    payload.put("anchor", ToolSupport.hexCoord(zone.anchor()));
    payload.put("hexes", rows);
    payload.put("legalTender", zone.legalTender().value());
    payload.put("radiusHex", ZONE_RADIUS_HEX);
    payload.put(
        "reason",
        "three-powers 创世：为 "
            + zone.govUnitId()
            + " 的辖区落持久市场区 "
            + zone.zoneId()
            + "（I22 单一权威；成员格 = 该行政区全部格）");
    return ToolSupport.json(payload);
  }

  /** 逐区一条 INFO（区 id / 格数 / 法定币 / 锚格）+ 一条汇总 INFO（§一.9：新状态写口至少一条 INFO）。 */
  private static void logZones(SimulationState state, List<ZoneSpec> zones) {
    EconomyData economy = requireEconomy(state);
    int hexes = 0;
    for (ZoneSpec spec : zones) {
      MarketZone zone =
          MarketZoneBook.zone(economy, new MarketZoneId(spec.zoneId()))
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "three-powers 创世日志装配故障：区 " + spec.zoneId() + " 不在状态里"));
      hexes += zone.hexCount();
      EventLog.channel(AppLog.shell())
          .info(
              LogEvent.of(
                  EVENT_ZONE_PERSISTED,
                  AppLogSource.SHELL_LIFECYCLE,
                  "world",
                  ThreePowersWorld.MAP_ID,
                  "zone",
                  zone.zoneId().value(),
                  "anchor",
                  zone.anchor().toString(),
                  "hexCount",
                  zone.hexCount(),
                  "legalTender",
                  zone.legalTender().value(),
                  "radiusHex",
                  zone.radiusHex()));
    }
    EventLog.channel(AppLog.shell())
        .info(
            LogEvent.of(
                EVENT_ZONES_PERSISTED,
                AppLogSource.SHELL_LIFECYCLE,
                "world",
                ThreePowersWorld.MAP_ID,
                "zones",
                zones.size(),
                "hexes",
                hexes,
                "authority",
                "persistent",
                "requiredSelfChecks",
                REQUIRED_SELF_CHECKS));
  }

  /** 自检失败：先记一条 ERROR（具名检查 id + 原因）再返回待抛异常（契约/跨切片一致性故障 = ERROR 不降级）。 */
  private static IllegalStateException fail(String check, String message) {
    EventLog.channel(AppLog.shell())
        .error(
            LogEvent.of(
                EVENT_SELF_CHECK_FAILED,
                AppLogSource.SHELL_LIFECYCLE,
                "world",
                ThreePowersWorld.MAP_ID,
                "check",
                check,
                "message",
                message));
    return new IllegalStateException("three-powers 创世市场区自检失败 [" + check + "]: " + message);
  }

  /** 本 GOV 的法定币：{@code declaredIssuable} ∪ {新定义币}；恰一种才说得出来（说不出 / 多于一种 ⇒ 具名抛）。 */
  private static CurrencyId legalTenderOf(ThreePowersGovBootstrap.GovSpec spec) {
    Set<CurrencyId> issued = new LinkedHashSet<>(spec.declaredIssuable());
    spec.newCurrency().ifPresent(currency -> issued.add(new CurrencyId(currency.currencyId())));
    if (issued.isEmpty()) {
      throw new IllegalStateException(
          "GOV "
              + spec.govId()
              + " 说不出本区的市场区法定币：它既没有 declaredIssuable、也没有 newCurrency"
              + "（区法定币 = 该 GOV 发行的币，创世 spec 必须写清）");
    }
    if (issued.size() > 1) {
      throw new IllegalStateException(
          "GOV "
              + spec.govId()
              + " 发行多种币 "
              + issued
              + "，说不出哪一种是本区法定币（一区一币是 three-powers 的世界形态；要一 GOV 多币需先裁定）");
    }
    return issued.iterator().next();
  }

  /** 区 id 清单（规范序；失败信息用，避免把整张区表刷进日志）。 */
  private static List<String> zoneIdsOf(EconomyData economy) {
    List<String> ids = new ArrayList<>();
    for (MarketZoneId zoneId : MarketZoneBook.zoneIds(economy)) {
      ids.add(zoneId.value());
    }
    return ids;
  }

  /** 格集的稳定可读首几项（失败信息里只列前 3 个，避免把整张区表刷进日志）。 */
  private static String head(Set<HexCoord> hexes) {
    if (hexes.isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder("（前几个: ");
    int shown = 0;
    for (HexCoord hex : hexes) {
      if (shown > 0) {
        text.append(',');
      }
      text.append(hex);
      if (++shown == 3) {
        break;
      }
    }
    return text.append('）').toString();
  }

  private static EconomyData requireEconomy(SimulationState state) {
    return state
        .module("economy")
        .filter(EconomySnapshot.class::isInstance)
        .map(EconomySnapshot.class::cast)
        .map(EconomySnapshot::data)
        .orElseThrow(
            () -> new IllegalStateException("ThreePowersMarketZones 需要 economy 切片（创世装配故障）"));
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 不得为空白");
    }
  }
}

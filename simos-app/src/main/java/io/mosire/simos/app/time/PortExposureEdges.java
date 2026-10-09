package io.mosire.simos.app.time;

import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.market.MarketZone;
import io.mosire.simos.economy.model.MarketZoneBook;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
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
 * ★★ <b>R2：暴露边纯函数（2026-10-09 口岸设计书 §4.1/G1 + 上位文档 §10-2 裁定"逐格暴露边"）</b>。
 *
 * <pre>
 * 接触面 k = 一条"本区格 ↔ 外区格"的相邻边
 * 暴露边条数 = 逐格逐方向判"邻居不在本区"的条数（★ 只在 Z 侧计，见下）
 * </pre>
 *
 * <p>★★ <b>口岸是默认存在的、不需要创建</b>（用户 2026-10-09 原话「口岸是默认创建的，也就是市场区-市场区接壤就是有口岸」）⇒
 * 本类<b>不落盘、不建状态、不加"口岸格"</b>：由 {@code MarketZone.hexes} + {@link HexCoord#neighbors()} 现算
 * （I-P3：暴露边是纯函数、无第二权威）。
 *
 * <p>★★ <b>口径（本批冻结，理由逐条）</b>：
 *
 * <ol>
 *   <li><b>只在 Z 侧逐格计，不除以 2</b>：聚合是<b>按区</b>问"这个区有多少条暴露边、分别归哪个政府管"，扫的只有 Z 的成员格 ⇒ 每条"Z ↔ 外面"的边在 Z
 *       的计数里<b>恰好出现一次</b>，不存在重复。<b>除以 2 反而会错两处</b>：① 把 Z 的暴露边条数砍半； ② 地图边缘的边（邻居格根本不存在）只可能被一个区计到，除 2
 *       会把它算成半条。上位文档 §10-2 的"共享边两侧各计一次 ⇒ 除以 2 或只在规范侧计"针对的是"全图总暴露边"那种跨区口径；本批不做那个口径，故取<b>只在规范侧（Z
 *       侧）计</b>这一支。
 *   <li><b>邻居必须"存在"（在地图里）才计</b>：地图外的邻居格不存在 ⇒ 那片区域没有任何市场区/商品/人，"对它设关"没有对象。
 *       若把它计进来，"一个覆盖全图的区"会凭空获得暴露边与管制面（幽灵权威，违反 I-P3 与 I-P8）。★ 邻居格存在但
 *       <b>不属于任何市场区</b>的边<b>照计</b>（三不管地带是真实存在的来货方向）。
 *   <li><b>归属 = Z 侧那格的管辖政府</b>：用户原话「要先考察当前市场区各个管辖政府的口岸效率情况……分别乘上口岸效率带来的管控力度」 （设计书 §1.4-3）⇒
 *       一段边界归"管着这块地"的政府。三不管（无任何政府辖区）的暴露边归 {@link #UNGOVERNED_OWNER_KEY} 一个具名集合 —— 它没有政府设限 ⇒ 那一处的
 *       {@code s = 0}。
 * </ol>
 *
 * <p>★ <b>为什么落组合根（app）</b>：算它要同时看 map 的 Region→hexes、unit 的 jurisdiction 与 economy 的区表；{@code
 * simos-gov} 的 enforcer 禁 {@code simos-economy}（设计书 F3），`simos-economy` 也不认识 unit/gov ⇒ 只有组合根能看见三边。
 *
 * <p>★ <b>确定性</b>：区按 {@link MarketZoneBook#zones(EconomyData)} 的规范序、成员格按 (q,r) 升序、归属键按字符串升序 ⇒
 * 同一状态的输出逐值相同（可回放）。
 */
public final class PortExposureEdges {

  /** 三不管（不属于任何政府辖区）的暴露边归属键（唯一拼写点；具名，不静默丢边）。 */
  public static final String UNGOVERNED_OWNER_KEY = "ungoverned";

  /** 契约故障日志通道（组合根日循环同一 logger）。 */
  private static final LogChannel LOG = EventLog.channel(AppLog.time());

  private PortExposureEdges() {}

  /**
   * 一个接触面：某市场区里"归某个 owner 管"的那批暴露边（{@code exposedEdgeCount} = 设计书 §4.3 的权重 {@code w_k}）。
   *
   * @param zoneId 市场区身份裸值
   * @param ownerKey 归属键（政府 {@code UnitId} 裸值 / {@link #UNGOVERNED_OWNER_KEY}）
   * @param exposedEdgeCount 该归属下的暴露边条数（≥ 1；0 条不产生接触面）
   */
  public record Contact(String zoneId, String ownerKey, long exposedEdgeCount) {

    public Contact {
      Objects.requireNonNull(zoneId, "zoneId");
      Objects.requireNonNull(ownerKey, "ownerKey");
      if (exposedEdgeCount <= 0L) {
        throw new IllegalArgumentException("Contact.exposedEdgeCount 必须 > 0: " + exposedEdgeCount);
      }
    }
  }

  /**
   * ★★ <b>全部市场区 × 全部归属的接触面</b>（保序：区按规范序、归属键按字符串升序）。
   *
   * @param economy 经济切片（区表 = 成员格的唯一权威，I22）；不得为 null
   * @param map 地图（{@code hexes} 判"邻居格存在"、{@code regions} 把辖区 Region 展开成格）；不得为 null
   * @param units 单位切片（{@code jurisdiction} 给出"哪块地归哪个政府"）；不得为 null
   */
  public static List<Contact> contacts(EconomyData economy, GameMap map, UnitState units) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(map, "map");
    Objects.requireNonNull(units, "units");
    Map<HexCoord, String> ownerByHex = ownerByHex(map, units);
    List<Contact> contacts = new ArrayList<>();
    for (MarketZone zone : MarketZoneBook.zones(economy)) {
      String zoneId = zone.zoneId().value();
      Map<String, Long> byOwner = new LinkedHashMap<>();
      for (HexCoord hex : sortedMembers(zone)) {
        String owner = ownerByHex.getOrDefault(hex, UNGOVERNED_OWNER_KEY); // 三不管 = 没有政府设限
        for (HexCoord neighbour : hex.neighbors()) {
          if (!map.hexes().containsKey(neighbour)) {
            continue; // ★ 邻居格不存在（地图外）⇒ 不计（见类注口径 2）
          }
          if (zone.hexes().contains(neighbour)) {
            continue; // 区内邻居 ⇒ 不是暴露边
          }
          byOwner.merge(owner, 1L, Math::addExact);
        }
      }
      List<String> owners = new ArrayList<>(byOwner.keySet());
      owners.sort(Comparator.naturalOrder());
      for (String owner : owners) {
        contacts.add(new Contact(zoneId, owner, byOwner.get(owner)));
      }
    }
    LOG.debug(
        LogEvent.of(
            "PORT_EXPOSURE_EDGES_COMPUTED",
            AppLogSource.DAILY_LOOP,
            "zones",
            MarketZoneBook.zones(economy).size(),
            "contacts",
            contacts.size(),
            "edges",
            totalEdges(contacts),
            "ungovernedEdges",
            edgesOfOwner(contacts, UNGOVERNED_OWNER_KEY)));
    return List.copyOf(contacts);
  }

  /** 某区某归属的暴露边条数（查无 ⇒ 0；探针/读数用）。 */
  public static long edgeCount(List<Contact> contacts, String zoneId, String ownerKey) {
    Objects.requireNonNull(contacts, "contacts");
    long total = 0L;
    for (Contact contact : contacts) {
      if (contact.zoneId().equals(zoneId) && contact.ownerKey().equals(ownerKey)) {
        total = Math.addExact(total, contact.exposedEdgeCount());
      }
    }
    return total;
  }

  /** 某区的暴露边条数合计（逐方向计；探针 T1 的"条数与手算一致"读它）。 */
  public static long edgeCountOfZone(List<Contact> contacts, String zoneId) {
    Objects.requireNonNull(contacts, "contacts");
    long total = 0L;
    for (Contact contact : contacts) {
      if (contact.zoneId().equals(zoneId)) {
        total = Math.addExact(total, contact.exposedEdgeCount());
      }
    }
    return total;
  }

  /** 全部接触面的暴露边合计（只进读数/日志）。 */
  public static long totalEdges(List<Contact> contacts) {
    Objects.requireNonNull(contacts, "contacts");
    long total = 0L;
    for (Contact contact : contacts) {
      total = Math.addExact(total, contact.exposedEdgeCount());
    }
    return total;
  }

  /** 全部区里归某归属的暴露边合计（只进读数/日志）。 */
  public static long edgesOfOwner(List<Contact> contacts, String ownerKey) {
    Objects.requireNonNull(contacts, "contacts");
    long total = 0L;
    for (Contact contact : contacts) {
      if (contact.ownerKey().equals(ownerKey)) {
        total = Math.addExact(total, contact.exposedEdgeCount());
      }
    }
    return total;
  }

  /** 该区相接触的"别的市场区"集合（规范序；"挨着就算"，供读数与邻居判据）。 */
  public static Set<MarketZoneId> neighbourZones(EconomyData economy, GameMap map) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(map, "map");
    Set<MarketZoneId> neighbours = new LinkedHashSet<>();
    for (MarketZone zone : MarketZoneBook.zones(economy)) {
      for (HexCoord hex : sortedMembers(zone)) {
        for (HexCoord neighbour : hex.neighbors()) {
          if (!map.hexes().containsKey(neighbour) || zone.hexes().contains(neighbour)) {
            continue;
          }
          for (MarketZone other : MarketZoneBook.zones(economy)) {
            if (!other.zoneId().equals(zone.zoneId()) && other.hexes().contains(neighbour)) {
              neighbours.add(other.zoneId());
            }
          }
        }
      }
    }
    return java.util.Collections.unmodifiableSet(neighbours);
  }

  /** 格 → 管辖政府裸值（扫每个 GOV 单位的辖区 Region → 展开成 hex；同格两主 = 具名契约 ERROR，fail-closed）。 */
  private static Map<HexCoord, String> ownerByHex(GameMap map, UnitState units) {
    Map<HexCoord, String> owner = new LinkedHashMap<>();
    Set<HexCoord> conflicted = new LinkedHashSet<>();
    List<Unit> ordered = new ArrayList<>(units.units().values());
    ordered.sort(Comparator.comparing(unit -> unit.id().value()));
    for (Unit unit : ordered) {
      Optional<Jurisdiction> jurisdiction = unit.jurisdiction();
      if (jurisdiction.isEmpty()) {
        continue;
      }
      for (RegionId regionId : jurisdiction.get().taxRatePerMilleByRegion().keySet()) {
        Region region = map.regions().get(regionId);
        if (region == null) {
          continue; // 缺 Region 由日循环既有的 missingRegions 具名汇总（本类不重复报）
        }
        for (HexCoord hex : region.hexes()) {
          String previous = owner.putIfAbsent(hex, unit.id().value());
          if (previous != null && !previous.equals(unit.id().value())) {
            conflicted.add(hex);
          }
        }
      }
    }
    if (!conflicted.isEmpty()) {
      HexCoord first = conflicted.iterator().next();
      LOG.error(
          LogEvent.of(
              "PORT_EXPOSURE_OWNERSHIP_CONTRACT",
              AppLogSource.DAILY_LOOP,
              "reason",
              "hex-owned-by-two-governments",
              "hex",
              first.q() + "_" + first.r(),
              "conflicts",
              conflicted.size()));
      throw new IllegalStateException(
          "暴露边归属契约故障：同一格被两个政府认领（重叠辖区不被允许，见 GovJurisdictionGuard）: "
              + first
              + "（共 "
              + conflicted.size()
              + " 格）");
    }
    return owner;
  }

  /** 区成员格的规范序（q, r 升序）——内容的纯函数，不依赖集合迭代序。 */
  private static List<HexCoord> sortedMembers(MarketZone zone) {
    List<HexCoord> members = new ArrayList<>(zone.hexes());
    members.sort(Comparator.comparingInt(HexCoord::q).thenComparingInt(HexCoord::r));
    return members;
  }

  /** 只取一个政府单位的裸值拼法（供 {@link PortRegimeBridge} 对齐键；避免两处手拼）。 */
  static String ownerKeyOf(UnitId unitId) {
    return Objects.requireNonNull(unitId, "unitId").value();
  }
}

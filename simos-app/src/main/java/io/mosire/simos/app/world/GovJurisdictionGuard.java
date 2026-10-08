package io.mosire.simos.app.world;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.MutationGuard;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
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
 * ★★ <b>行政区互斥守卫（I23）</b>：<b>一个 hex 至多属于一个 GOV 的辖区</b> —— 用户 2026-10-08 第 8 轮裁定原文：
 * 「重叠辖区是不被允许的，一个地方只可能有一个行政政府，要么就是重划、变成三不管地带」。
 *
 * <p>★★ <b>它为什么住在组合根（{@code simos-app}），而不在 {@code simos-map} 或 {@code simos-gov}</b>（铁律 3/4）：
 * 判据要<b>同时</b>看两片数据 —— map 的 {@code Region → hexes}（"一个行政区由哪些格组成"）与 unit 的 {@code
 * Unit.jurisdiction()}（"哪个 GOV 管哪些行政区"）。{@code simos-map} <b>永远不知道</b> GOV 存在； {@code simos-gov}
 * 也看不见 map 的区表。⇒ 只有组合根同时看得见两者；这与 {@code sd.RegionDeleteGuard} 必须住在 {@code simos-sd}（它要同看 map 的 tag
 * 与 sd 的 Nation）是同一条推理。
 *
 * <p>★★ <b>它拦哪三条命令</b>（"授辖区"的三条真实入口，逐条给判据）：
 *
 * <ol>
 *   <li>{@code unit.SetJurisdiction}（<b>主入口</b>，M9 用例）：{@code regions[…] = 该 GOV 新的辖区行政区全集} ⇒ 新集里任一
 *       hex 已属**另一个 GOV** 的辖区 ⇒ 拒（具名点名 hex / 对方 GOV / 对方 Region / 本方 GOV）；
 *   <li>{@code map.UpdateRegion}（{@code regionId + hexes[…]}）：该 Region 若正被某 GOV 管辖，则它的成员格**变成**载荷给的
 *       hex 集 ⇒ 新集与**另一个 GOV** 的辖区相交 ⇒ 拒（这条不拦的话，GM 可以绕过 ① 把 A 省的边界推进 B 省）；
 *   <li>{@code map.ReassignHexes}（{@code toRegionId + hexes[…]}）：把点名的格从源区划给目标区 ⇒ 目标区若被某 GOV 管辖，
 *       则这些格在**划入后**同时属于目标区（含 GOV 甲）与它们原来的区（含 GOV 乙，若乙是源）⇒ 相交 ⇒ 拒。
 * </ol>
 *
 * <p>★★ <b>为什么只拦这三条、另外几条明确不拦（如实记，不是漏写）</b>：
 *
 * <ul>
 *   <li>{@code map.CreateRegion}：新区的成员格集**从零开始**（新 Region 不被任何 GOV 管辖）⇒ 造不出 GOV 间重叠；
 *   <li>{@code map.MergeRegions} / {@code map.SplitRegion} / {@code map.DeleteRegion}：源区**被删除** ⇒
 *       覆盖它的 GOV 的辖区引用随即悬空（{@code Region} 查无 ⇒ 该 GOV 的 hex 集少掉那一块）⇒ 那是"<b>辖区悬空</b>"这一另一类问题
 *       （该由"区域删除"的守卫面处理，见 {@code sd.RegionDeleteGuard} 的既有口径），<b>不</b>制造"两个 GOV 覆盖同一 hex"。
 *       本守卫不拦它们，因为拦了会**改变这些命令在既有世界里的合法用法**（如把两省合并成一个大区），而那不在 B1 的裁定范围内；
 *   <li>{@code map.RandomizeRegion}：只随机化**地形**，不动任何区的成员格 ⇒ 与 I23 无关。
 * </ul>
 *
 * <p>★★ <b>只读、只回答可不可以</b>（{@link MutationGuard} 的契约）：本守卫不写任何状态、不产生变更集；授辖区本身仍由各命令 <b>自己的 handler</b>
 * 执行（本守卫只在 handler 之前投一票）。载荷坏 / 切片缺席 / 点名的区查无 ⇒ <b>放行</b> （判不了的不猜：坏载荷由 handler 具名拒，缺切片是装配故障、由
 * handler 的切片读取当场炸出来）。
 *
 * <p>★ <b>日志</b>（§一.9）：拒绝时发一条具名 DEBUG（谁、哪个 hex、撞上谁），事件名 {@value #EVENT_REJECTED}； <b>拒绝本身</b>由
 * {@code CommandBus} 按既有口径落 INFO（{@code COMMAND_REJECTED} + reason）—— 本守卫不重复报。
 *
 * <p>★ <b>确定性</b>：GOV 按 {@link UnitId#value()} 升序遍历、冲突 hex 取 {@code (q,r)} 最小的那个 ⇒ 同一份坏状态永远给出
 * 同一条理由（可复现的拒绝文本）。
 */
public final class GovJurisdictionGuard implements MutationGuard {

  /** {@code unit.SetJurisdiction}（授/撤销辖区：{@code regions[]} = 新的行政区全集）。 */
  public static final String SET_JURISDICTION = "unit.SetJurisdiction";

  /** {@code map.UpdateRegion}（改已有区：给了 {@code hexes[]} ⇒ 成员格整表替换）。 */
  public static final String UPDATE_REGION = "map.UpdateRegion";

  /** {@code map.ReassignHexes}（把点名的格从源区划给目标区）。 */
  public static final String REASSIGN_HEXES = "map.ReassignHexes";

  /** 拒绝事件名（日志面；探针按它取证据）。 */
  public static final String EVENT_REJECTED = "GOV_JURISDICTION_OVERLAP_REJECTED";

  /** 本类唯一的一台 mapper（共享基座出厂配置，不认识任何领域类型 —— 载荷是扁平 JSON）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private static final String UNIT_ID_FIELD = "unitId";

  private static final String REGIONS_FIELD = "regions";

  private static final String REGION_ID_FIELD = "regionId";

  private static final String TO_REGION_ID_FIELD = "toRegionId";

  private static final String HEXES_FIELD = "hexes";

  @Override
  public String name() {
    return "app.gov-jurisdiction-exclusive";
  }

  @Override
  public Optional<String> rejection(SimulationState state, String commandType, String payloadJson) {
    if (!SET_JURISDICTION.equals(commandType)
        && !UPDATE_REGION.equals(commandType)
        && !REASSIGN_HEXES.equals(commandType)) {
      return Optional.empty();
    }
    Objects.requireNonNull(state, "state");
    JsonNode payload = parse(payloadJson);
    if (payload == null) {
      return Optional.empty();
    }
    Optional<GameMap> map = mapOf(state);
    Optional<UnitState> units = unitsOf(state);
    if (map.isEmpty() || units.isEmpty()) {
      return Optional.empty(); // 判不了 ⇒ 放行（缺切片是装配故障，由 handler 当场炸出来）
    }
    if (SET_JURISDICTION.equals(commandType)) {
      return grantRejection(payload, map.get(), units.get());
    }
    // ② / ③：改"某个已有区的成员格" ⇒ 只有该区**正被某 GOV 管辖**时才可能与另一个 GOV 的辖区重叠。
    String regionField = UPDATE_REGION.equals(commandType) ? REGION_ID_FIELD : TO_REGION_ID_FIELD;
    Optional<String> regionId = textOf(payload, regionField);
    Optional<Set<HexCoord>> newHexes = hexesOf(payload, HEXES_FIELD);
    if (regionId.isEmpty() || newHexes.isEmpty()) {
      return Optional.empty(); // meta-only 改动 / 坏载荷 ⇒ 放行
    }
    Optional<String> holder = govHoldingRegion(units.get(), regionId.get());
    if (holder.isEmpty()) {
      return Optional.empty(); // 该区不被任何 GOV 管辖 ⇒ 不构成 GOV 间重叠
    }
    String actor = holder.get();
    Optional<HexCoord> overlap =
        firstOverlap(newHexes.get(), otherGovHexes(units.get(), map.get(), actor));
    if (overlap.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        reject(
            commandType,
            actor,
            regionId.get(),
            overlap.get(),
            conflictOwner(units.get(), map.get(), actor, overlap.get())));
  }

  /** ① 主入口：{@code unit.SetJurisdiction} 的新辖区集里任一 hex 已属另一个 GOV ⇒ 拒。 */
  private Optional<String> grantRejection(JsonNode payload, GameMap map, UnitState units) {
    Optional<String> unitId = textOf(payload, UNIT_ID_FIELD);
    Optional<List<String>> regions = textArrayOf(payload, REGIONS_FIELD);
    if (unitId.isEmpty() || regions.isEmpty()) {
      return Optional.empty();
    }
    // 判据：**只有 GOV 单位**才受本条约束（I23 说的是 GOV 辖区；非 GOV 单位挂辖区是另一回事，
    // 既有世界里 jurisdiction 只在 GOV 单位上，但本守卫不去替领域层收紧它的合法面）。
    Unit unit = units.units().get(UnitId.parse(unitId.get()));
    if (unit == null || !(unit.module().orElse(null) instanceof GovernmentFormation)) {
      return Optional.empty();
    }
    Set<HexCoord> candidate = hexesOfRegions(map, regions.get());
    Optional<HexCoord> overlap = firstOverlap(candidate, otherGovHexes(units, map, unitId.get()));
    if (overlap.isEmpty()) {
      return Optional.empty();
    }
    Set<String> ownRegions = new LinkedHashSet<>(regions.get());
    return Optional.of(
        reject(
            SET_JURISDICTION,
            unitId.get(),
            String.join(",", ownRegions),
            overlap.get(),
            conflictOwner(units, map, unitId.get(), overlap.get())));
  }

  /** 拒绝理由（具名：命令、本方 GOV、点名的区、冲突 hex、对方 GOV/区）。 */
  private static String reject(
      String commandType, String govId, String regionText, HexCoord hex, String owner) {
    String reason =
        "行政区互斥（I23，用户 2026-10-08 第 8 轮裁定「重叠辖区是不被允许的」，命令 "
            + commandType
            + "）：hex "
            + hex
            + " 已属 "
            + owner
            + "，"
            + govId
            + " 不得再授（本次点名区: "
            + regionText
            + "）—— 要改请先重划/退让，或让它成为三不管地带";
    EventLog.channel(AppLog.tool())
        .debug(
            LogEvent.of(
                EVENT_REJECTED,
                AppLogSource.COMMAND_ENTRY,
                "command",
                commandType,
                "gov",
                govId,
                "regions",
                regionText,
                "hex",
                hex.q() + "_" + hex.r(),
                "owner",
                owner));
    return reason;
  }

  /** 冲突 hex 的**归属描述**（"GOV X 的区 R"，按同一遍历序取第一个持有者）——理由文本里必须点名对方，否则 GM 不知道该找谁退让。 */
  private static String conflictOwner(
      UnitState units, GameMap map, String excludingGovId, HexCoord hex) {
    for (Unit unit : sortedGovs(units)) {
      if (unit.id().value().equals(excludingGovId)) {
        continue;
      }
      Optional<String> regionId =
          unit.jurisdiction()
              .flatMap(j -> firstRegionContaining(map, j.taxRatePerMilleByRegion().keySet(), hex));
      if (regionId.isPresent()) {
        return "GOV " + unit.id().value() + " 的辖区 " + regionId.get();
      }
    }
    return "另一个 GOV 的辖区";
  }

  /** {@code excludingGovId} 之外的**全部 GOV 辖区 hex 并集**（只含 map 里真实存在的区）。 */
  private static Set<HexCoord> otherGovHexes(UnitState units, GameMap map, String excludingGovId) {
    Set<HexCoord> hexes = new LinkedHashSet<>();
    for (Unit unit : sortedGovs(units)) {
      if (unit.id().value().equals(excludingGovId)) {
        continue;
      }
      unit.jurisdiction()
          .ifPresent(
              j -> hexes.addAll(hexesOfRegions(map, idsOf(j.taxRatePerMilleByRegion().keySet()))));
    }
    return hexes;
  }

  /** 某个区当前被哪个 GOV 管辖（按 GOV id 升序取第一个；查无 / 区 id 坏 ⇒ 空）。 */
  private static Optional<String> govHoldingRegion(UnitState units, String regionId) {
    RegionId id;
    try {
      id = RegionId.parse(regionId);
    } catch (RuntimeException e) {
      return Optional.empty(); // 坏 regionId 由 handler 具名拒；守卫不替它判形状
    }
    for (Unit unit : sortedGovs(units)) {
      boolean holds =
          unit.jurisdiction().map(j -> j.taxRatePerMilleByRegion().containsKey(id)).orElse(false);
      if (holds) {
        return Optional.of(unit.id().value());
      }
    }
    return Optional.empty();
  }

  /** GOV 单位（带 {@link GovernmentFormation}），按 {@link UnitId#value()} 升序（确定性）。 */
  private static List<Unit> sortedGovs(UnitState units) {
    List<Unit> govs = new ArrayList<>();
    for (Unit unit : units.units().values()) {
      if (unit.module().orElse(null) instanceof GovernmentFormation) {
        govs.add(unit);
      }
    }
    govs.sort(Comparator.comparing(unit -> unit.id().value()));
    return govs;
  }

  /** 这些行政区在**当前地图**里的 hex 并集（区查无 / 区 id 坏 ⇒ 跳过：由 handler 自己具名拒）。 */
  private static Set<HexCoord> hexesOfRegions(GameMap map, List<String> regionIds) {
    Set<HexCoord> hexes = new LinkedHashSet<>();
    for (String regionId : regionIds) {
      Region region = regionOf(map, regionId);
      if (region != null) {
        hexes.addAll(region.hexes());
      }
    }
    return hexes;
  }

  /** 按 id 取区；id 坏 ⇒ {@code null}（守卫不替 handler 判形状）。 */
  private static Region regionOf(GameMap map, String regionId) {
    try {
      return map.regions().get(RegionId.parse(regionId));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 第一个落在 {@code candidates} 里、且出现在 {@code occupied} 里的格（{@code (q,r)} 最小者；无 ⇒ 空）。 */
  private static Optional<HexCoord> firstOverlap(Set<HexCoord> candidates, Set<HexCoord> occupied) {
    HexCoord worst = null;
    for (HexCoord candidate : candidates) {
      if (!occupied.contains(candidate)) {
        continue;
      }
      if (worst == null
          || candidate.q() < worst.q()
          || (candidate.q() == worst.q() && candidate.r() < worst.r())) {
        worst = candidate;
      }
    }
    return Optional.ofNullable(worst);
  }

  /** 某 GOV 的辖区里第一个含 {@code hex} 的区 id（按区 id 升序；无 ⇒ 空）。 */
  private static Optional<String> firstRegionContaining(
      GameMap map, Set<RegionId> regionIds, HexCoord hex) {
    List<String> sorted = new ArrayList<>(idsOf(regionIds));
    sorted.sort(Comparator.naturalOrder());
    for (String regionId : sorted) {
      Region region = regionOf(map, regionId);
      if (region != null && region.hexes().contains(hex)) {
        return Optional.of(regionId);
      }
    }
    return Optional.empty();
  }

  private static List<String> idsOf(Set<RegionId> ids) {
    List<String> out = new ArrayList<>(ids.size());
    for (RegionId id : ids) {
      out.add(id.value());
    }
    return out;
  }

  // ── 载荷读取（坏载荷一律 ⇒ empty ⇒ 放行；不抛、不改语义）────────────────────────────────

  private static JsonNode parse(String payloadJson) {
    try {
      return MAPPER.readTree(payloadJson);
    } catch (JsonProcessingException | RuntimeException e) {
      return null;
    }
  }

  private static Optional<String> textOf(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      return Optional.empty();
    }
    return Optional.of(value.asText());
  }

  private static Optional<List<String>> textArrayOf(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || !value.isArray()) {
      return Optional.empty();
    }
    List<String> out = new ArrayList<>(value.size());
    for (JsonNode element : value) {
      if (!element.isTextual() || element.asText().isBlank()) {
        return Optional.empty();
      }
      out.add(element.asText());
    }
    return Optional.of(out);
  }

  /** {@code [{q,r}…]} 数组 ⇒ 坐标集（**空数组 ⇒ 空集**，与本守卫的语义一致：没有格就没有新覆盖）。 */
  private static Optional<Set<HexCoord>> hexesOf(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isArray()) {
      return Optional.empty();
    }
    Set<HexCoord> hexes = new LinkedHashSet<>();
    for (JsonNode element : value) {
      Optional<HexCoord> hex = hexOf(element);
      if (hex.isEmpty()) {
        return Optional.empty();
      }
      hexes.add(hex.get());
    }
    return Optional.of(hexes);
  }

  private static Optional<HexCoord> hexOf(JsonNode element) {
    if (element == null || !element.isObject()) {
      return Optional.empty();
    }
    JsonNode q = element.get("q");
    JsonNode r = element.get("r");
    if (q == null || r == null || !q.isIntegralNumber() || !r.isIntegralNumber()) {
      return Optional.empty();
    }
    return Optional.of(new HexCoord(q.asInt(), r.asInt()));
  }

  private static Optional<GameMap> mapOf(SimulationState state) {
    Snapshot snapshot = state.module("map").orElse(null);
    if (snapshot instanceof MapSnapshot mapSnapshot) {
      return Optional.of(mapSnapshot.map());
    }
    return Optional.empty();
  }

  private static Optional<UnitState> unitsOf(SimulationState state) {
    Snapshot snapshot = state.module("unit").orElse(null);
    if (snapshot instanceof UnitSnapshot unitSnapshot) {
      return Optional.of(unitSnapshot.state());
    }
    return Optional.empty();
  }

  /** 只读诊断：当前世界的 GOV → 辖区 Region id 表（保序；供探针/日志复用，不写任何状态）。 */
  public static Map<String, Set<String>> govRegions(SimulationState state) {
    Objects.requireNonNull(state, "state");
    Optional<UnitState> units = unitsOf(state);
    if (units.isEmpty()) {
      return Map.of();
    }
    Map<String, Set<String>> out = new LinkedHashMap<>();
    for (Unit unit : sortedGovs(units.get())) {
      Set<String> regions = new LinkedHashSet<>();
      unit.jurisdiction()
          .ifPresent(j -> regions.addAll(idsOf(j.taxRatePerMilleByRegion().keySet())));
      out.put(unit.id().value(), regions);
    }
    return java.util.Collections.unmodifiableMap(out);
  }
}

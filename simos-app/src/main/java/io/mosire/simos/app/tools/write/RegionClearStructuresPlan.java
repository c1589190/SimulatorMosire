package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.gov.ProvinceDivider;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.Directive;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.unit.CommandChain;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
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
 * ★★ {@code simos.region.clearStructures} 的只读 pre-scan（P1b2，2026-10-01）：从 base state
 * 识别"生成器创建的结构"候选， 给出固定批序与全部 warning，<b>一个字节都不写</b>。
 *
 * <p>★★ <b>候选识别规则（本类唯一语义落点）</b>：
 *
 * <ol>
 *   <li><b>候选 GOV 单位</b>：带 {@link GovernmentFormation} 且**当刻有效位置**落在目标 Region hex 集内的单位 + 调用方显式给的 {@code
 *       unitIds}（必须存在）。自动候选只认"位置在目标 Region 内"的 GOV，不因为别的 Region 使用同一个单位就扩大；
 *   <li><b>候选决策人</b>：{@link Affiliation.Gov} 且其 {@code govUnit} 落在上述候选单位集合内的决策人 + 调用方显式给的 {@code
 *       decisionMakerIds}（必须存在；本批选择"必须是 Gov 归属"的严格口径，非 Gov 的显式 id 具名拒，见 {@link
 *       #scanDecisionMakers}）；
 *   <li><b>候选 Region</b>：调用方显式给的 {@code regionIds}（必须存在且 != 目标 Region）+ 自动候选—— 只认 id 形如 {@code
 *       sanitize(目标 regionId) + "__P" + 两位以上数字}（{@link ProvinceDivider} 的建议命名形制）且 {@code hexes} 是目标
 *       Region hex 集的<b>真子集</b>的 Region。其它相交 Region 一律只进 {@link #overlappingRegions()} 与
 *       warning，绝不自动删。
 * </ol>
 *
 * <p>★★ <b>区域重叠 ≠ 省籍</b>：{@code overlappingRegions} 只作信息；是否删除某个相交 Region，要么它满足上面的自动候选规则， 要么调用方在
 * {@code regionIds} 里显式点名。
 *
 * <p>★★ <b>批序固定</b>：{@code sd.DeleteDecisionMaker × N → unit.DisbandUnit × N → map.DeleteRegion × N
 * → sd.PutInfo}；只对有候选项且开关打开的类别下单，全部无命令 ⇒ {@link #hasWork()} 为假（零 revision）。
 *
 * <p>★ <b>不前置预演 domain 拒绝</b>：{@code unit.DisbandUnit} 仍有下属或仍被链引用时仍会被域层拒；pre-scan 只把这两种 "几乎必拒"的形状写进
 * warning（见 {@link #addUnitDisbandWarnings}），不改候选、不替域层判。
 */
final class RegionClearStructuresPlan {

  /** 固定批序第 1 类。 */
  static final String DELETE_DECISION_MAKER_TYPE = "sd.DeleteDecisionMaker";

  /** 固定批序第 2 类。 */
  static final String DISBAND_UNIT_TYPE = "unit.DisbandUnit";

  /** 固定批序第 3 类。 */
  static final String DELETE_REGION_TYPE = "map.DeleteRegion";

  /** 固定批序末尾的行动记录类型（与包内既有组合工具同源，不另抄字面量）。 */
  static final String PUT_INFO_TYPE = RegionSeedPlan.PUT_INFO_TYPE;

  /** 行动记录在 sd INFO 覆盖层里的 key（地址 = 目标 Region canonical）。 */
  static final String INFO_KEY = "regionClearStructures";

  /** preview 的 id 样本长度上限。 */
  private static final int SAMPLE_SIZE = 5;

  private RegionClearStructuresPlan() {}

  /** 一类候选集合：{@code ids} 恒为"实际会下单的顺序"；{@code autoIds}/{@code explicitIds} 只用于 preview 溯源。 */
  record CandidateSet(List<String> ids, List<String> autoIds, List<String> explicitIds) {

    CandidateSet {
      Objects.requireNonNull(ids, "ids");
      Objects.requireNonNull(autoIds, "autoIds");
      Objects.requireNonNull(explicitIds, "explicitIds");
      ids = List.copyOf(ids);
      autoIds = List.copyOf(autoIds);
      explicitIds = List.copyOf(explicitIds);
    }

    int count() {
      return ids.size();
    }

    /** preview 视图：id / 计数 / 样本（自动与显式各自的来源 id 也带上，便于调用方核对 override）。 */
    Map<String, Object> view(boolean enabled) {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("count", ids.size());
      view.put("ids", ids);
      view.put("sample", ids.size() <= SAMPLE_SIZE ? ids : ids.subList(0, SAMPLE_SIZE));
      view.put("autoIds", autoIds);
      view.put("explicitIds", explicitIds);
      view.put("enabled", enabled);
      return view;
    }
  }

  /** 与目标 Region 相交的其它 Region（只读信息；不因相交而自动进入候选）。 */
  record Overlap(String regionId, String name, String tag, int overlapHexCount) {

    Overlap {
      Objects.requireNonNull(regionId, "regionId");
      Objects.requireNonNull(name, "name");
      if (overlapHexCount < 1) {
        throw new IllegalArgumentException("overlapHexCount 必须 ≥1: " + overlapHexCount);
      }
    }

    Map<String, Object> view() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("regionId", regionId);
      view.put("name", name);
      view.put("tag", tag);
      view.put("overlapHexCount", overlapHexCount);
      return view;
    }
  }

  /** 一次可执行清空计划（纯函数产物；{@code commands} 是实际会落的类型序，含末尾的 {@code sd.PutInfo}）。 */
  record Plan(
      Region region,
      long tick,
      CandidateSet units,
      CandidateSet decisionMakers,
      CandidateSet regions,
      List<Overlap> overlappingRegions,
      boolean deleteRegions,
      boolean deleteUnits,
      boolean deleteDecisionMakers,
      List<String> commands,
      List<String> warnings) {

    Plan {
      Objects.requireNonNull(region, "region");
      Objects.requireNonNull(units, "units");
      Objects.requireNonNull(decisionMakers, "decisionMakers");
      Objects.requireNonNull(regions, "regions");
      Objects.requireNonNull(overlappingRegions, "overlappingRegions");
      Objects.requireNonNull(commands, "commands");
      Objects.requireNonNull(warnings, "warnings");
      overlappingRegions = List.copyOf(overlappingRegions);
      commands = List.copyOf(commands);
      warnings = List.copyOf(warnings);
    }

    boolean hasWork() {
      return !commands.isEmpty();
    }

    /** 是否识别出任何候选结构（无论对应开关是否打开）；用于区分"没候选"与"候选都被开关拦住"。 */
    boolean hasCandidates() {
      return units.count() > 0 || decisionMakers.count() > 0 || regions.count() > 0;
    }

    Map<String, Object> candidatesView() {
      Map<String, Object> candidates = new LinkedHashMap<>();
      candidates.put("units", units.view(deleteUnits));
      candidates.put("decisionMakers", decisionMakers.view(deleteDecisionMakers));
      candidates.put("regions", regions.view(deleteRegions));
      return candidates;
    }

    List<Map<String, Object>> overlappingRegionsView() {
      List<Map<String, Object>> rows = new ArrayList<>(overlappingRegions.size());
      for (Overlap overlap : overlappingRegions) {
        rows.add(overlap.view());
      }
      return List.copyOf(rows);
    }

    Map<String, Object> switchesView() {
      Map<String, Object> switches = new LinkedHashMap<>();
      switches.put("deleteDecisionMakers", deleteDecisionMakers);
      switches.put("deleteUnits", deleteUnits);
      switches.put("deleteRegions", deleteRegions);
      return switches;
    }
  }

  /**
   * 只读 pre-scan。
   *
   * @throws IllegalArgumentException 目标 Region 不存在；显式 regionIds 含目标自身 / 不存在；显式 unitIds 不存在；显式
   *     decisionMakerIds 不存在或不是 Gov 归属（strict 口径）
   */
  static Plan scan(
      SimulationState state,
      String regionId,
      List<String> explicitRegionIds,
      List<String> explicitUnitIds,
      List<String> explicitDecisionMakerIds,
      boolean deleteRegions,
      boolean deleteUnits,
      boolean deleteDecisionMakers) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(regionId, "regionId");
    Objects.requireNonNull(explicitRegionIds, "explicitRegionIds");
    Objects.requireNonNull(explicitUnitIds, "explicitUnitIds");
    Objects.requireNonNull(explicitDecisionMakerIds, "explicitDecisionMakerIds");
    GameMap map = ToolSupport.gameMap(state);
    Region region = map.regions().get(new RegionId(regionId));
    if (region == null) {
      throw new IllegalArgumentException("当前 map 里没有这个 region: " + regionId);
    }
    SimosTimestamp at = state.meta().timestamp();
    UnitState units = ToolSupport.unitState(state);
    SdState sd = ToolSupport.sdState(state);
    List<String> warnings = new ArrayList<>();
    CandidateSet regionCandidates = scanRegions(map, region, explicitRegionIds, warnings);
    CandidateSet unitCandidates = scanUnits(units, at, region, explicitUnitIds, warnings);
    CandidateSet decisionMakerCandidates =
        scanDecisionMakers(sd, units, unitCandidates.ids(), explicitDecisionMakerIds, warnings);
    List<Overlap> overlaps = scanOverlaps(map, region);
    addOverlapWarnings(overlaps, regionCandidates.ids(), warnings);
    addSwitchWarnings(
        regionCandidates,
        unitCandidates,
        decisionMakerCandidates,
        deleteRegions,
        deleteUnits,
        deleteDecisionMakers,
        warnings);
    addUnitDisbandWarnings(units, at, unitCandidates.ids(), warnings);
    addDecisionMakerWarnings(sd, decisionMakerCandidates.ids(), warnings);
    List<String> commands =
        buildCommands(
            regionCandidates,
            unitCandidates,
            decisionMakerCandidates,
            deleteRegions,
            deleteUnits,
            deleteDecisionMakers);
    return new Plan(
        region,
        at.tick(),
        unitCandidates,
        decisionMakerCandidates,
        regionCandidates,
        overlaps,
        deleteRegions,
        deleteUnits,
        deleteDecisionMakers,
        commands,
        warnings);
  }

  // ── Region 候选 ────────────────────────────────────────────────────────────────────

  private static CandidateSet scanRegions(
      GameMap map, Region target, List<String> explicitRegionIds, List<String> warnings) {
    String targetId = target.id().value();
    Set<String> explicit = new LinkedHashSet<>(explicitRegionIds);
    if (explicit.contains(targetId)) {
      throw new IllegalArgumentException("regionIds 不得包含目标 Region 自身: " + targetId);
    }
    for (String id : explicit) {
      if (!map.regions().containsKey(new RegionId(id))) {
        throw new IllegalArgumentException("显式 regionId 不存在: " + id);
      }
    }
    List<Region> sorted = new ArrayList<>(map.regions().values());
    sorted.sort(Comparator.comparing(region -> region.id().value()));
    Set<String> all = new LinkedHashSet<>();
    Set<String> auto = new LinkedHashSet<>();
    Set<String> explicitOut = new LinkedHashSet<>();
    for (Region candidate : sorted) {
      String id = candidate.id().value();
      if (id.equals(targetId)) {
        continue;
      }
      boolean isExplicit = explicit.contains(id);
      boolean matchesName = matchesSuggestedProvinceId(targetId, id);
      boolean isAuto = matchesName && isTrueSubset(target.hexes(), candidate.hexes());
      if (isExplicit) {
        explicitOut.add(id);
        all.add(id);
      }
      if (isAuto) {
        auto.add(id);
        all.add(id);
      }
      if (isExplicit && !isAuto) {
        String why = matchesName ? "hexes 不是目标 Region hex 集的真子集" : "id 不符合建议省命名形制";
        warnings.add("显式 regionId " + id + " 未命中自动候选规则（" + why + "）；仍按显式清单删除");
      }
    }
    return new CandidateSet(List.copyOf(all), List.copyOf(auto), List.copyOf(explicitOut));
  }

  /** 建议省 id 形制：{@code sanitize(目标 regionId) + "__P" + 两位以上 ASCII 数字}。 */
  private static boolean matchesSuggestedProvinceId(
      String targetRegionId, String candidateRegionId) {
    String prefix = ProvinceDivider.sanitize(targetRegionId) + "__P";
    if (!candidateRegionId.startsWith(prefix)) {
      return false;
    }
    String suffix = candidateRegionId.substring(prefix.length());
    if (suffix.length() < 2) {
      return false;
    }
    for (int i = 0; i < suffix.length(); i++) {
      char c = suffix.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /** 真子集 = 目标 hex 集包含候选全部 hexes，且候选比目标小（相等不算）。 */
  private static boolean isTrueSubset(Set<HexCoord> targetHexes, Set<HexCoord> candidateHexes) {
    return candidateHexes.size() < targetHexes.size() && targetHexes.containsAll(candidateHexes);
  }

  // ── Unit 候选 ──────────────────────────────────────────────────────────────────────

  private static CandidateSet scanUnits(
      UnitState units,
      SimosTimestamp at,
      Region target,
      List<String> explicitUnitIds,
      List<String> warnings) {
    Set<String> explicit = new LinkedHashSet<>(explicitUnitIds);
    for (String id : explicit) {
      if (!units.units().containsKey(UnitId.parse(id))) {
        throw new IllegalArgumentException("显式 unitId 不存在: " + id);
      }
    }
    List<Unit> sorted = new ArrayList<>(units.units().values());
    sorted.sort(Comparator.comparing(unit -> unit.id().value()));
    Set<String> all = new LinkedHashSet<>();
    Set<String> auto = new LinkedHashSet<>();
    Set<String> explicitOut = new LinkedHashSet<>();
    for (Unit unit : sorted) {
      String id = unit.id().value();
      boolean isGov = unit.module().orElse(null) instanceof GovernmentFormation;
      Optional<HexCoord> position = units.effectivePosition(unit.id(), at);
      boolean inTarget = position.isPresent() && target.hexes().contains(position.get());
      boolean isAuto = isGov && inTarget;
      boolean isExplicit = explicit.contains(id);
      if (isExplicit) {
        explicitOut.add(id);
        all.add(id);
      }
      if (isAuto) {
        auto.add(id);
        all.add(id);
      }
      if (isExplicit && !isAuto) {
        warnings.add("显式 unitId " + id + " 不是目标 Region 内当刻有效的 GovernmentFormation 单位（仍按显式清单删除）");
      }
    }
    // ★ 批内 unit.DisbandUnit 的域层约束是"不得仍有下属"⇒ 先删后代再删祖先，避免同一批里可避免的整批拒。
    List<String> ordered = orderUnitsForDisband(units, all, at);
    return new CandidateSet(ordered, List.copyOf(auto), List.copyOf(explicitOut));
  }

  /** 候选单位按编制深度从深到浅、同深度按 id 升序（确定性；先删后代可让同批的祖先随后能删）。 */
  private static List<String> orderUnitsForDisband(
      UnitState units, Set<String> ids, SimosTimestamp at) {
    List<String> ordered = new ArrayList<>(ids);
    ordered.sort(
        Comparator.comparingInt((String id) -> -depthOf(units, UnitId.parse(id), at))
            .thenComparing(Comparator.naturalOrder()));
    return List.copyOf(ordered);
  }

  /** 沿当刻 parent 链上溯的深度（根 = 0）；父缺失/成环防御性停住，不在 pre-scan 里抛。 */
  private static int depthOf(UnitState units, UnitId id, SimosTimestamp at) {
    int depth = 0;
    Set<UnitId> seen = new LinkedHashSet<>();
    UnitId current = id;
    while (current != null) {
      if (!seen.add(current)) {
        break;
      }
      Unit unit = units.units().get(current);
      if (unit == null) {
        break;
      }
      Optional<UnitId> parent = unit.parent().valueAt(at);
      if (parent.isEmpty()) {
        break;
      }
      depth++;
      current = parent.get();
    }
    return depth;
  }

  // ── DecisionMaker 候选 ─────────────────────────────────────────────────────────────

  private static CandidateSet scanDecisionMakers(
      SdState sd,
      UnitState units,
      List<String> candidateUnitIds,
      List<String> explicitDecisionMakerIds,
      List<String> warnings) {
    Set<String> explicit = new LinkedHashSet<>(explicitDecisionMakerIds);
    for (String id : explicit) {
      DecisionMaker decisionMaker = sd.decisionMakers().get(DecisionMakerId.parse(id));
      if (decisionMaker == null) {
        throw new IllegalArgumentException("显式 decisionMakerId 不存在: " + id);
      }
      if (!(decisionMaker.affiliation() instanceof Affiliation.Gov)) {
        throw new IllegalArgumentException(
            "显式 decisionMakerId "
                + id
                + " 不是 Gov 归属（"
                + decisionMaker.affiliation()
                + "）：clearStructures 只清生成器创建的 GOV 决策人；其它归属请走后续单独命令");
      }
    }
    Set<UnitId> candidateUnits = new LinkedHashSet<>();
    for (String id : candidateUnitIds) {
      candidateUnits.add(UnitId.parse(id));
    }
    List<DecisionMaker> sorted = new ArrayList<>(sd.decisionMakers().values());
    sorted.sort(Comparator.comparing(decisionMaker -> decisionMaker.id().value()));
    Set<String> all = new LinkedHashSet<>();
    Set<String> auto = new LinkedHashSet<>();
    Set<String> explicitOut = new LinkedHashSet<>();
    for (DecisionMaker decisionMaker : sorted) {
      String id = decisionMaker.id().value();
      boolean isAuto =
          decisionMaker.affiliation() instanceof Affiliation.Gov gov
              && candidateUnits.contains(gov.govUnit());
      boolean isExplicit = explicit.contains(id);
      if (isExplicit) {
        explicitOut.add(id);
        all.add(id);
      }
      if (isAuto) {
        auto.add(id);
        all.add(id);
      }
      if (isExplicit && !isAuto && decisionMaker.affiliation() instanceof Affiliation.Gov gov) {
        if (!units.units().containsKey(gov.govUnit())) {
          warnings.add("显式 decisionMakerId " + id + " 的 Gov 归属单位不存在于 unit 切片（仍按显式清单删除）");
        } else {
          warnings.add(
              "显式 decisionMakerId "
                  + id
                  + " 的 Gov 归属单位 "
                  + gov.govUnit().value()
                  + " 不在本次候选单位集里（仍按显式清单删除）");
        }
      }
    }
    return new CandidateSet(List.copyOf(all), List.copyOf(auto), List.copyOf(explicitOut));
  }

  // ── 相交 Region 与 warning ─────────────────────────────────────────────────────────

  private static List<Overlap> scanOverlaps(GameMap map, Region target) {
    List<Region> sorted = new ArrayList<>(map.regions().values());
    sorted.sort(Comparator.comparing(region -> region.id().value()));
    List<Overlap> overlaps = new ArrayList<>();
    for (Region candidate : sorted) {
      if (candidate.id().equals(target.id())) {
        continue;
      }
      int overlap = overlapHexCount(target.hexes(), candidate.hexes());
      if (overlap > 0) {
        overlaps.add(
            new Overlap(candidate.id().value(), candidate.name(), candidate.meta().tag(), overlap));
      }
    }
    return List.copyOf(overlaps);
  }

  private static int overlapHexCount(Set<HexCoord> left, Set<HexCoord> right) {
    int count = 0;
    for (HexCoord hex : right) {
      if (left.contains(hex)) {
        count++;
      }
    }
    return count;
  }

  private static void addOverlapWarnings(
      List<Overlap> overlaps, List<String> candidateRegionIds, List<String> warnings) {
    Set<String> candidates = new LinkedHashSet<>(candidateRegionIds);
    for (Overlap overlap : overlaps) {
      if (!candidates.contains(overlap.regionId())) {
        warnings.add(
            "Region "
                + overlap.regionId()
                + "（"
                + overlap.name()
                + "）与目标 Region 相交 "
                + overlap.overlapHexCount()
                + " 格，但不是建议省命名形制 + 真子集，且不在显式 regionIds：不自动删除；如需删除请显式点名");
      }
    }
  }

  private static void addSwitchWarnings(
      CandidateSet regions,
      CandidateSet units,
      CandidateSet decisionMakers,
      boolean deleteRegions,
      boolean deleteUnits,
      boolean deleteDecisionMakers,
      List<String> warnings) {
    if (!deleteDecisionMakers && decisionMakers.count() > 0) {
      warnings.add("候选决策人 " + decisionMakers.count() + " 个，但 deleteDecisionMakers=false ⇒ 本类不下单");
    }
    if (!deleteUnits && units.count() > 0) {
      warnings.add("候选单位 " + units.count() + " 个，但 deleteUnits=false ⇒ 本类不下单");
    }
    if (!deleteRegions && regions.count() > 0) {
      warnings.add("候选 Region " + regions.count() + " 个，但 deleteRegions=false ⇒ 本类不下单");
    }
  }

  // ── domain 层"几乎必拒"形状的 warning（不做前置预演）──────────────────────────────────

  private static void addUnitDisbandWarnings(
      UnitState units, SimosTimestamp at, List<String> candidateUnitIds, List<String> warnings) {
    if (candidateUnitIds.isEmpty()) {
      return;
    }
    Set<UnitId> candidates = new LinkedHashSet<>();
    for (String id : candidateUnitIds) {
      candidates.add(UnitId.parse(id));
    }
    Map<UnitId, List<String>> subordinateIds = new LinkedHashMap<>();
    for (Unit unit : units.units().values()) {
      Optional<UnitId> parent = unit.parent().valueAt(at);
      if (parent.isPresent() && candidates.contains(parent.get())) {
        subordinateIds
            .computeIfAbsent(parent.get(), key -> new ArrayList<>())
            .add(unit.id().value());
      }
    }
    for (String id : candidateUnitIds) {
      UnitId unitId = UnitId.parse(id);
      List<String> children = new ArrayList<>(subordinateIds.getOrDefault(unitId, List.of()));
      children.sort(String::compareTo);
      List<String> external = new ArrayList<>();
      List<String> internal = new ArrayList<>();
      for (String child : children) {
        if (candidates.contains(UnitId.parse(child))) {
          internal.add(child);
        } else {
          external.add(child);
        }
      }
      if (!external.isEmpty()) {
        warnings.add(
            "候选单位 "
                + id
                + " 在 tick "
                + at.tick()
                + " 仍有 "
                + external.size()
                + " 个不在候选集的下属（"
                + external
                + "）：unit.DisbandUnit 的既有约束会拒，整批将零 revision");
      }
      if (!internal.isEmpty()) {
        warnings.add(
            "候选单位 "
                + id
                + " 在 tick "
                + at.tick()
                + " 有 "
                + internal.size()
                + " 个下属也在候选集（"
                + internal
                + "）：批序已把后代替删除排在本单之前");
      }
      List<String> chains = chainReferences(units, unitId);
      if (!chains.isEmpty()) {
        warnings.add(
            "候选单位 " + id + " 仍挂在命令链 " + chains + "：unit.DisbandUnit 的既有约束会拒，整批将零 revision");
      }
    }
  }

  private static List<String> chainReferences(UnitState units, UnitId id) {
    List<String> hits = new ArrayList<>();
    for (CommandChain chain : units.commandChains().values()) {
      boolean commander = chain.commander().equals(id);
      boolean member = chain.members().contains(id);
      if (commander) {
        hits.add(chain.id().value() + "(commander)");
      } else if (member) {
        hits.add(chain.id().value() + "(member)");
      }
    }
    hits.sort(String::compareTo);
    return hits;
  }

  private static void addDecisionMakerWarnings(
      SdState sd, List<String> candidateDecisionMakerIds, List<String> warnings) {
    for (String id : candidateDecisionMakerIds) {
      DecisionMakerId decisionMakerId = DecisionMakerId.parse(id);
      List<String> directiveIds = new ArrayList<>();
      for (Directive directive : sd.directives().values()) {
        if (directive.decisionMakerId().equals(decisionMakerId)) {
          directiveIds.add(directive.id().value());
        }
      }
      if (!directiveIds.isEmpty()) {
        directiveIds.sort(String::compareTo);
        String listed =
            directiveIds.size() <= SAMPLE_SIZE
                ? directiveIds.toString()
                : directiveIds.subList(0, SAMPLE_SIZE) + " 等";
        warnings.add(
            "候选决策人 "
                + id
                + " 仍有 "
                + directiveIds.size()
                + " 条 Directive 引用（"
                + listed
                + "）：sd.DeleteDecisionMaker 会拒（不级联删历史指令），整批将零 revision");
      }
    }
  }

  // ── 固定批序 ──────────────────────────────────────────────────────────────────────

  private static List<String> buildCommands(
      CandidateSet regions,
      CandidateSet units,
      CandidateSet decisionMakers,
      boolean deleteRegions,
      boolean deleteUnits,
      boolean deleteDecisionMakers) {
    List<String> commands = new ArrayList<>();
    if (deleteDecisionMakers) {
      addRepeated(commands, DELETE_DECISION_MAKER_TYPE, decisionMakers.count());
    }
    if (deleteUnits) {
      addRepeated(commands, DISBAND_UNIT_TYPE, units.count());
    }
    if (deleteRegions) {
      addRepeated(commands, DELETE_REGION_TYPE, regions.count());
    }
    if (!commands.isEmpty()) {
      commands.add(PUT_INFO_TYPE);
    }
    return List.copyOf(commands);
  }

  private static void addRepeated(List<String> commands, String type, int count) {
    for (int i = 0; i < count; i++) {
      commands.add(type);
    }
  }
}

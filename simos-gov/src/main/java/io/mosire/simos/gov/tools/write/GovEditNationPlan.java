package io.mosire.simos.gov.tools.write;

import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.gov.nation.Nation;
import io.mosire.simos.gov.nation.NationId;
import io.mosire.simos.gov.nation.NationTag;
import io.mosire.simos.gov.spi.UpdateNationHandler;
import io.mosire.simos.gov.tools.GovNationViews;
import io.mosire.simos.gov.tools.GovToolReads;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.region.RegionMeta;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Jurisdiction;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code simos.gov.editNation} 的纯计划：读一个 {@link SimulationState}，推导 {@code map}/{@code unit}/{@code
 * gov}/{@code social} 四片的原子命令批；不调用写口、不写存档、不调用模型。 其中 social 只有新 GOV 的 0 人政府家户 CREATE_HOUSEHOLD 工单（消除
 * SetGovFormation 自动挂入的悬空引用）， 不建 economy/actor/ga。
 *
 * <p>★★ <b>领土口径</b>：国家领土 = 精确 {@code nation:<id>} 标签区域的并集。创建时新 homeRegion 承载目标并集；更新时目标并集放进既有
 * homeRegion，其他同标签区域<b>保留 ID、几何与其他元数据，只解除 nation 标签</b>（区域模型禁止空几何，见 2026-11-14 设计修正）。
 *
 * <p>★★ <b>重叠</b>：默认 {@code reject}，具名列出冲突国家/格；{@code transfer} 只从其他国家同标签区域扣目标格，扣后若对方区域变空则整批具名拒绝
 * ——不删除仍被引用的区域，也不改 map 的非空不变量。
 *
 * <p>★★ <b>原子性</b>：所有命令由工具一次 {@link ToolWritePort#submitBatchWithReceipt} 提交；本类只负责"计划不自洽就抛 {@link
 * IllegalArgumentException}"，不捕获域层拒绝。
 */
public final class GovEditNationPlan {

  private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");
  private static final int MAX_HEXES = 1_000_000;

  public static final String CREATE_NATION = "gov.CreateNation";
  public static final String UPDATE_NATION = UpdateNationHandler.TYPE;
  public static final String CREATE_REGION = "map.CreateRegion";
  public static final String UPDATE_REGION = "map.UpdateRegion";
  public static final String RENAME_REGION = "map.RenameRegion";
  public static final String CREATE_UNIT = "unit.CreateUnit";
  public static final String SET_GOV_FORMATION = "unit.SetGovFormation";
  public static final String SET_JURISDICTION = "unit.SetJurisdiction";
  public static final String RENAME_UNIT = "unit.RenameUnit";
  public static final String UPDATE_FORMATION_NODE = "unit.UpdateFormationNode";
  public static final String SUBMIT_HOUSEHOLD_WORK_ORDER = SubmitHouseholdWorkOrderHandler.TYPE;

  private GovEditNationPlan() {}

  /** 政府单位输入（{@code position} 在 update 可空 = 保持原位置）。 */
  public record GovernmentSpec(String unitId, String name, HexCoord position) {}

  /** 一条计划命令（类型 + 载荷 JSON）。 */
  public record Command(String type, String payloadJson) {}

  /** 计划结果：命令批 + 给工具/web 回显的视图数据。 */
  public record Plan(
      String mode,
      String nationId,
      String name,
      String color,
      String homeRegionId,
      int hexCount,
      List<String> regionIds,
      List<String> releasedRegionIds,
      List<String> transferredFromRegionIds,
      String governmentUnitId,
      List<Command> commands) {

    public Plan {
      regionIds = List.copyOf(regionIds);
      releasedRegionIds = List.copyOf(releasedRegionIds);
      transferredFromRegionIds = List.copyOf(transferredFromRegionIds);
      commands = List.copyOf(commands);
    }
  }

  /** 创建/更新共用入口。{@code hexes == null} 表示更新时"未给领土"（保持原值）。 */
  public static Plan plan(
      SimulationState state,
      String mode,
      String nationIdText,
      String name,
      String color,
      List<HexCoord> hexes,
      String territoryMode,
      String overlapPolicy,
      GovernmentSpec government,
      String reason) {
    Objects.requireNonNull(state, "state");
    GovState gov = GovToolReads.govState(state);
    GameMap map = GovToolReads.gameMap(state);
    UnitState units = GovToolReads.unitState(state);
    NationId nationId = NationId.parse(nationIdText);
    if ("create".equals(mode)) {
      return planCreate(
          state, gov, map, units, nationId, name, color, hexes, overlapPolicy, government, reason);
    }
    if ("update".equals(mode)) {
      return planUpdate(
          state,
          gov,
          map,
          units,
          nationId,
          name,
          color,
          hexes,
          territoryMode,
          overlapPolicy,
          government,
          reason);
    }
    throw new IllegalArgumentException("mode 只能是 create|update: " + mode);
  }

  // ── create ──────────────────────────────────────────────────────────

  private static Plan planCreate(
      SimulationState state,
      GovState gov,
      GameMap map,
      UnitState units,
      NationId nationId,
      String name,
      String color,
      List<HexCoord> hexes,
      String overlapPolicy,
      GovernmentSpec government,
      String reason) {
    if (gov.nations().containsKey(nationId)) {
      throw new IllegalArgumentException("国家已存在（create 不能覆盖）: " + nationId);
    }
    String validName = requireName(name);
    String validColor = requireColor(color);
    List<HexCoord> target = requireHexes(hexes, map, "create");
    requireOverlapPolicy(overlapPolicy);
    List<Region> conflicts = conflicts(map, nationId, target);
    Map<RegionId, Set<HexCoord>> transferred = transfer(map, conflicts, target, overlapPolicy);
    List<Region> orphanTagged = GovNationViews.nationRegions(map, nationId);
    RegionId homeRegionId = chooseHomeRegion(map, nationId, orphanTagged);
    Region home = map.regions().get(homeRegionId);

    List<Command> commands = new ArrayList<>();
    if (home == null) {
      commands.add(
          command(
              CREATE_REGION,
              map(
                  "regionId", homeRegionId.value(),
                  "name", validName,
                  "hexes", hexPayload(target),
                  "meta", meta(validColor, NationTag.tagFor(nationId), null, null))));
    } else {
      commands.add(
          command(
              UPDATE_REGION,
              map(
                  "regionId", homeRegionId.value(),
                  "hexes", hexPayload(target),
                  "meta",
                      meta(
                          validColor,
                          NationTag.tagFor(nationId),
                          home.meta().description(),
                          home.meta().annexedBy()))));
    }
    if (home != null && !validName.equals(home.name())) {
      commands.add(command(RENAME_REGION, map("regionId", home.id().value(), "name", validName)));
    }
    for (Region other : orphanTagged) {
      if (other.id().equals(homeRegionId)) {
        continue;
      }
      commands.add(releaseTag(other));
    }
    for (Map.Entry<RegionId, Set<HexCoord>> entry : transferred.entrySet()) {
      Region source = map.regions().get(entry.getKey());
      commands.add(
          command(
              UPDATE_REGION,
              map(
                  "regionId",
                  source.id().value(),
                  "hexes",
                  hexPayload(entry.getValue()),
                  "meta",
                  metaOf(source.meta()))));
    }
    commands.add(
        command(
            CREATE_NATION,
            map(
                "nationId",
                nationId.value(),
                "name",
                validName,
                "homeRegionId",
                homeRegionId.value(),
                "adminBudgetPerTick",
                0)));
    String govUnit =
        addGovernmentCommands(state, units, map, commands, government, homeRegionId, reason);
    return new Plan(
        "create",
        nationId.value(),
        validName,
        validColor,
        homeRegionId.value(),
        target.size(),
        List.of(homeRegionId.value()),
        orphanTagged.stream()
            .filter(region -> !region.id().equals(homeRegionId))
            .map(region -> region.id().value())
            .toList(),
        transferred.keySet().stream().map(RegionId::value).toList(),
        govUnit,
        List.copyOf(commands));
  }

  // ── update ──────────────────────────────────────────────────────────

  private static Plan planUpdate(
      SimulationState state,
      GovState gov,
      GameMap map,
      UnitState units,
      NationId nationId,
      String name,
      String color,
      List<HexCoord> hexes,
      String territoryMode,
      String overlapPolicy,
      GovernmentSpec government,
      String reason) {
    Nation nation = gov.nations().get(nationId);
    if (nation == null) {
      throw new IllegalArgumentException("国家不存在（update 不能新建）: " + nationId);
    }
    String nextName = name == null ? nation.name() : requireName(name);
    Region home = map.regions().get(nation.homeRegion());
    if (home == null) {
      throw new IllegalArgumentException(
          "国家 " + nationId + " 的 homeRegion 不存在: " + nation.homeRegion());
    }
    if (!NationTag.tagFor(nationId).equals(home.meta().tag())) {
      throw new IllegalArgumentException(
          "国家 "
              + nationId
              + " 的 homeRegion "
              + nation.homeRegion()
              + " 的 tag 不是 "
              + NationTag.tagFor(nationId)
              + "（实际 "
              + home.meta().tag()
              + "）");
    }
    String territory = territoryMode == null ? "replace" : territoryMode;
    if (!"replace".equals(territory) && !"add".equals(territory) && !"remove".equals(territory)) {
      throw new IllegalArgumentException("territoryMode 只能是 replace|add|remove: " + territory);
    }
    List<HexCoord> validatedHexes = hexes == null ? null : requireHexes(hexes, map, "update");
    List<HexCoord> current = GovNationViews.union(map, nationId);
    List<HexCoord> target = computeTarget(current, validatedHexes, territory);
    if (target.isEmpty()) {
      throw new IllegalArgumentException("目标国家领土不能为空（区域模型禁止空几何）");
    }
    List<HexCoord> added = minus(target, current);
    requireOverlapPolicy(overlapPolicy);
    List<Region> conflicts = conflicts(map, nationId, added);
    Map<RegionId, Set<HexCoord>> transferred = transfer(map, conflicts, added, overlapPolicy);
    String nextColor = color == null ? home.meta().color() : requireColor(color);
    if (nextColor == null || nextColor.isBlank()) {
      throw new IllegalArgumentException(
          "update 未给 color，且 homeRegion 没有可继承的颜色；请显式给 color:#RRGGBB");
    }

    List<Command> commands = new ArrayList<>();
    commands.add(
        command(
            UPDATE_REGION,
            map(
                "regionId", home.id().value(),
                "hexes", hexPayload(target),
                "meta",
                    meta(
                        nextColor,
                        NationTag.tagFor(nationId),
                        home.meta().description(),
                        home.meta().annexedBy()))));
    List<String> released = new ArrayList<>();
    for (Region region : GovNationViews.nationRegions(map, nationId)) {
      if (region.id().equals(home.id())) {
        continue;
      }
      commands.add(releaseTag(region));
      released.add(region.id().value());
    }
    for (Map.Entry<RegionId, Set<HexCoord>> entry : transferred.entrySet()) {
      Region source = map.regions().get(entry.getKey());
      commands.add(
          command(
              UPDATE_REGION,
              map(
                  "regionId",
                  source.id().value(),
                  "hexes",
                  hexPayload(entry.getValue()),
                  "meta",
                  metaOf(source.meta()))));
    }
    if (!nextName.equals(nation.name())) {
      commands.add(command(UPDATE_NATION, map("nationId", nationId.value(), "name", nextName)));
    }
    if (!nextName.equals(home.name())) {
      // 地图上的国名文字来自 homeRegion.name；改名时同步，避免"Nation.name 与地图文字不一致"。
      commands.add(command(RENAME_REGION, map("regionId", home.id().value(), "name", nextName)));
    }
    String govUnit =
        addGovernmentCommands(state, units, map, commands, government, home.id(), reason);
    return new Plan(
        "update",
        nationId.value(),
        nextName,
        nextColor,
        home.id().value(),
        target.size(),
        List.of(home.id().value()),
        List.copyOf(released),
        transferred.keySet().stream().map(RegionId::value).toList(),
        govUnit,
        List.copyOf(commands));
  }

  // ── government ──────────────────────────────────────────────────────

  /**
   * 新 GOV 的 Social 政府家户：`hh-gov-<govId>`、location UNIT(govId)、0 人、source.module=gov。
   *
   * <p>★★ 必须与 GOV 单位同批、且排在单位之后：{@code unit.SetGovFormation} 会把同一把家户键写进 {@code Unit.households}；没有这条
   * Social 实体就是悬空引用。仅 CREATE_HOUSEHOLD，不建经济/actor/ga。 若同 id 家户已存在：位置/空成员匹配 ⇒ 复用它（不重复建户）；不匹配 ⇒
   * 具名拒绝，绝不覆盖。
   */
  private static void addGovernmentHouseholdCommands(
      SimulationState state,
      List<Command> commands,
      String govUnitId,
      String govName,
      String callerReason) {
    SocialData social = GovToolReads.socialData(state);
    HouseholdId household = GovernmentHouseholds.of(govUnitId);
    Household existing = social.households().get(household);
    if (existing != null) {
      boolean locationOk =
          existing.location() instanceof HouseholdLocation.Unit unit
              && unit.unitId().equals(govUnitId);
      if (!locationOk || !existing.members().isEmpty()) {
        throw new IllegalArgumentException(
            "政府家户 "
                + household
                + " 已存在但与新 GOV 不匹配（location="
                + existing.location()
                + "，members="
                + existing.members().size()
                + "）：拒绝覆盖；请先人工处理该家户");
      }
      return;
    }
    Map<String, Object> step =
        map(
            "op", "CREATE_HOUSEHOLD",
            "household", household.value(),
            "location", Map.of("type", "UNIT", "unitId", govUnitId),
            "profile", Map.of("name", govName + "政府家户"),
            "vitalRates", List.of());
    Map<String, Object> payload =
        map(
            "target", household.value(),
            "reason", "editNation 新建 GOV 政府家户（0 人）：" + callerReason,
            "source", Map.of("module", "gov"),
            "plan", List.of(step));
    commands.add(command(SUBMIT_HOUSEHOLD_WORK_ORDER, payload));
  }

  private static String addGovernmentCommands(
      SimulationState state,
      UnitState units,
      GameMap map,
      List<Command> commands,
      GovernmentSpec government,
      RegionId homeRegionId,
      String callerReason) {
    if (government == null) {
      return null;
    }
    String unitIdText = government.unitId();
    if (unitIdText == null || unitIdText.isBlank()) {
      throw new IllegalArgumentException("government.unitId 不得为空白");
    }
    UnitId unitId = UnitId.parse(unitIdText);
    Unit existing = units.units().get(unitId);
    if (existing == null) {
      HexCoord at = government.position();
      if (at == null) {
        throw new IllegalArgumentException(
            "government.position 在新建 GOV 时必填（中央 GOV 也要有落点）: " + unitId);
      }
      if (!map.hexes().containsKey(at)) {
        throw new IllegalArgumentException("government.position 不在图上: " + at);
      }
      String govName = government.name();
      if (govName == null || govName.isBlank()) {
        govName = unitId.value();
      }
      commands.add(
          command(
              CREATE_UNIT,
              map(
                  "id", unitId.value(),
                  "name", govName,
                  "position", hex(at),
                  "equipment", List.of(),
                  "speed", 1,
                  "mobilityPerMille", 1000,
                  "households", List.of())));
      // 中央 GOV = 空编制（staff 空）+ GovernmentFormation + jurisdiction；不发 economy/actor/ga 命令。
      commands.add(
          command(
              SET_GOV_FORMATION,
              map("unitId", unitId.value(), "level", "CENTRAL", "staff", Map.of())));
      commands.add(
          command(
              SET_JURISDICTION,
              map("unitId", unitId.value(), "regions", List.of(homeRegionId.value()))));
      // ★ 2026-11-14 修正：GOV 单位存在之后同批建它的 Social 政府家户（0 人，位置 UNIT(govId)），
      //   消除 SetGovFormation 自动挂 hh-gov-<id> 却没有 Social 实体的悬空引用。只发 social 工单，
      //   仍不发 economy/actor/ga 命令；既有 GovCreateOfficePlan 的经济链不复用。
      addGovernmentHouseholdCommands(state, commands, unitId.value(), govName, callerReason);
      return unitId.value();
    }
    if (!(existing.module().orElse(null) instanceof GovernmentFormation)) {
      throw new IllegalArgumentException(
          "government.unitId " + unitId + " 已存在但不是 GOV 单位（没有 GovernmentFormation）");
    }
    String govName = government.name();
    if (govName != null && !govName.isBlank() && !govName.equals(existing.name())) {
      commands.add(command(RENAME_UNIT, map("id", unitId.value(), "name", govName)));
    }
    HexCoord at = government.position();
    if (at != null && !map.hexes().containsKey(at)) {
      throw new IllegalArgumentException("government.position 不在图上: " + at);
    }
    if (at != null
        && !at.equals(units.effectivePosition(unitId, state.meta().timestamp()).orElse(null))) {
      commands.add(
          command(
              UPDATE_FORMATION_NODE,
              map("id", unitId.value(), "position", hex(at), "attached", false)));
    }
    Set<RegionId> regions = new LinkedHashSet<>();
    existing
        .jurisdiction()
        .map(Jurisdiction::taxRatePerMilleByRegion)
        .ifPresent(table -> regions.addAll(table.keySet()));
    if (!regions.contains(homeRegionId)) {
      regions.add(homeRegionId);
      commands.add(
          command(
              SET_JURISDICTION,
              map(
                  "unitId", unitId.value(),
                  "regions", regions.stream().map(RegionId::value).toList())));
    }
    return unitId.value();
  }

  // ── helpers ─────────────────────────────────────────────────────────

  private static List<HexCoord> computeTarget(
      List<HexCoord> current, List<HexCoord> hexes, String territoryMode) {
    String mode = territoryMode == null ? "replace" : territoryMode;
    if (hexes == null) {
      return current;
    }
    return switch (mode) {
      case "replace" -> {
        if (hexes.isEmpty()) {
          throw new IllegalArgumentException("territoryMode=replace 时 hexes 不得为空（目标国家不能清空）");
        }
        yield sorted(hexes);
      }
      case "add" -> {
        Set<HexCoord> union = new LinkedHashSet<>(current);
        union.addAll(hexes);
        yield sorted(union);
      }
      case "remove" -> {
        Set<HexCoord> remain = new LinkedHashSet<>(current);
        remain.removeAll(hexes);
        yield sorted(remain);
      }
      default ->
          throw new IllegalArgumentException("territoryMode 只能是 replace|add|remove: " + mode);
    };
  }

  private static List<Region> conflicts(GameMap map, NationId nationId, List<HexCoord> hexes) {
    if (hexes.isEmpty()) {
      return List.of();
    }
    String ownTag = NationTag.tagFor(nationId);
    List<Region> conflicts = new ArrayList<>();
    for (Region region : map.regions().values()) {
      String tag = region.meta().tag();
      if (tag == null || !NationTag.isNationTag(tag) || ownTag.equals(tag)) {
        continue;
      }
      for (HexCoord hex : hexes) {
        if (region.hexes().contains(hex)) {
          conflicts.add(region);
          break;
        }
      }
    }
    conflicts.sort(Comparator.comparing(region -> region.id().value()));
    return List.copyOf(conflicts);
  }

  private static void requireOverlapPolicy(String overlapPolicy) {
    String policy = overlapPolicy == null ? "reject" : overlapPolicy;
    if (!"reject".equals(policy) && !"transfer".equals(policy)) {
      throw new IllegalArgumentException("overlapPolicy 只能是 reject|transfer: " + policy);
    }
  }

  private static Map<RegionId, Set<HexCoord>> transfer(
      GameMap map, List<Region> conflicts, List<HexCoord> hexes, String overlapPolicy) {
    String policy = overlapPolicy == null ? "reject" : overlapPolicy;
    if (!"reject".equals(policy) && !"transfer".equals(policy)) {
      throw new IllegalArgumentException("overlapPolicy 只能是 reject|transfer: " + policy);
    }
    if (conflicts.isEmpty()) {
      return Map.of();
    }
    if ("reject".equals(policy)) {
      StringBuilder detail = new StringBuilder();
      for (Region region : conflicts) {
        if (detail.length() > 0) {
          detail.append("；");
        }
        detail.append(region.meta().tag()).append(" @ ").append(region.id());
      }
      throw new IllegalArgumentException("领土冲突（overlapPolicy=reject）：目标格已属于其他国家区域 " + detail);
    }
    Map<RegionId, Set<HexCoord>> remainingByRegion = new LinkedHashMap<>();
    for (Region region : conflicts) {
      Set<HexCoord> remaining = new LinkedHashSet<>(region.hexes());
      remaining.removeAll(hexes);
      if (remaining.isEmpty()) {
        throw new IllegalArgumentException(
            "overlapPolicy=transfer 会让区域 "
                + region.id()
                + "（"
                + region.meta().tag()
                + "）变空：区域模型禁止空几何，且不得删除仍被引用的区域；请先调整对方区域结构（拆出保留格）后再转移");
      }
      remainingByRegion.put(region.id(), Set.copyOf(remaining));
    }
    return remainingByRegion;
  }

  private static RegionId chooseHomeRegion(
      GameMap map, NationId nationId, List<Region> orphanTagged) {
    if (!orphanTagged.isEmpty()) {
      return orphanTagged.get(0).id();
    }
    RegionId candidate = RegionId.parse("nation-home-" + nationId.value());
    if (map.regions().containsKey(candidate)) {
      throw new IllegalArgumentException(
          "homeRegion id " + candidate + " 已被非本国区域占用；请先改名或换 nationId");
    }
    return candidate;
  }

  private static List<HexCoord> requireHexes(List<HexCoord> hexes, GameMap map, String mode) {
    if (hexes == null || hexes.isEmpty()) {
      throw new IllegalArgumentException("mode=" + mode + " 必须给非空 hexes");
    }
    if (hexes.size() > MAX_HEXES) {
      throw new IllegalArgumentException("hexes 超过上限 " + MAX_HEXES + ": " + hexes.size());
    }
    Set<HexCoord> unique = new LinkedHashSet<>();
    for (HexCoord hex : hexes) {
      if (hex == null) {
        throw new IllegalArgumentException("hexes 不得含 null");
      }
      if (!map.hexes().containsKey(hex)) {
        throw new IllegalArgumentException("hex 不在图上: " + hex);
      }
      if (!unique.add(hex)) {
        throw new IllegalArgumentException("hexes 有重复格: " + hex);
      }
    }
    return sorted(unique);
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    return name;
  }

  private static String requireColor(String color) {
    if (color == null || !COLOR.matcher(color).matches()) {
      throw new IllegalArgumentException("color 必须是 #RRGGBB: " + color);
    }
    return color;
  }

  private static List<HexCoord> minus(List<HexCoord> target, List<HexCoord> current) {
    Set<HexCoord> now = new LinkedHashSet<>(current);
    List<HexCoord> result = new ArrayList<>();
    for (HexCoord hex : target) {
      if (!now.contains(hex)) {
        result.add(hex);
      }
    }
    return List.copyOf(result);
  }

  private static List<HexCoord> sorted(Iterable<HexCoord> hexes) {
    List<HexCoord> copy = new ArrayList<>();
    for (HexCoord hex : hexes) {
      copy.add(hex);
    }
    copy.sort(Comparator.naturalOrder());
    return List.copyOf(copy);
  }

  private static Command releaseTag(Region region) {
    return command(
        UPDATE_REGION,
        map(
            "regionId",
            region.id().value(),
            "meta",
            meta(
                region.meta().color(),
                null,
                region.meta().description(),
                region.meta().annexedBy())));
  }

  private static Command command(String type, Map<String, Object> payload) {
    return new Command(type, ToolResults.json(payload));
  }

  private static Map<String, Object> map(Object... keyValues) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int index = 0; index < keyValues.length; index += 2) {
      out.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
    }
    return out;
  }

  private static Map<String, Object> hex(HexCoord hex) {
    return map("q", hex.q(), "r", hex.r());
  }

  private static List<Map<String, Object>> hexPayload(Iterable<HexCoord> hexes) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (HexCoord hex : hexes) {
      out.add(hex(hex));
    }
    return List.copyOf(out);
  }

  private static Map<String, Object> metaOf(RegionMeta meta) {
    return meta(meta.color(), meta.tag(), meta.description(), meta.annexedBy());
  }

  private static Map<String, Object> meta(
      String color, String tag, String description, String annexedBy) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("color", color);
    out.put("tag", tag);
    out.put("description", description);
    out.put("annexedBy", annexedBy);
    return out;
  }

  /** 计划回显（工具 dryRun / apply 共用）。 */
  public static Map<String, Object> view(Plan plan, boolean dryRun, String reason) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("dryRun", dryRun);
    out.put("mode", plan.mode());
    out.put("nationId", plan.nationId());
    out.put("name", plan.name());
    out.put("color", plan.color());
    out.put("homeRegionId", plan.homeRegionId());
    out.put("hexCount", plan.hexCount());
    out.put("regionIds", plan.regionIds());
    out.put("releasedRegionIds", plan.releasedRegionIds());
    out.put("transferredFromRegionIds", plan.transferredFromRegionIds());
    out.put("governmentUnitId", plan.governmentUnitId());
    out.put("reason", reason);
    List<Map<String, Object>> commands = new ArrayList<>();
    for (Command command : plan.commands()) {
      commands.add(Map.of("type", command.type(), "payload", command.payloadJson()));
    }
    out.put("commands", List.copyOf(commands));
    return out;
  }

  /** 供工具在 apply 后把计划视图补上提交回执。 */
  public static Map<String, Object> applied(
      Plan plan, String reason, Map<String, Object> submission) {
    Map<String, Object> out = new LinkedHashMap<>(view(plan, false, reason));
    out.put("submission", submission);
    return out;
  }
}

package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.move.PathFinder;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.gov.selectExaminees} 的<b>纯推导</b>（阶段 13A 人员流转，GOV/Army 计划 §2.6）： 从一份 {@link
 * SimulationState} 与参数算出 {@link Plan}——<b>不碰 {@link io.mosire.agentlib.tool.ToolContext}、 不碰 {@code
 * CoreSimos}</b>，preview 与 apply 因此共用同一份语义。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：人在 {@code social} 切片、新单位在 {@code unit} 切片、 行动记录在 {@code sd}
 * 切片；单条命令只能落一个命名空间。本工具走 {@link io.mosire.simos.core.CoreSimos#submitBatch}（同 branch + 同
 * expectedRevision ⇒ 一批 = 一条 revision，原子）。
 *
 * <p>★★ <b>来源口径 = 辖区社会批次的跨 Region 瀑布</b>（与 {@code simos.gov.recruit} 逐字同源）：
 *
 * <ol>
 *   <li><b>辖区顺序</b>：{@code Unit.jurisdiction.taxRatePerMilleByRegion} 的 key 插入序（构造期保序冻结）， 逐个 Region
 *       分配直到满额；
 *   <li><b>Region 内口径</b>：只调 {@link RegionAllocations#allocateManpower}（唯一一份 MALE + {@link
 *       io.mosire.simos.social.population.AgeBracket#ADULT}、count 降序 / id 升序瀑布），本类不另写 排序、过滤或扣减；
 *   <li><b>不足 ⇒ 整条拒</b>：先扫全部辖区 Region 得到 available 合计；不足时抛具名 {@link IllegalArgumentException}（带
 *       requested / available / 缺口），<b>不部分抽取、不截断</b>；
 *   <li><b>守恒</b>：Σ来源扣人 == {@code count} == 新单位 manpower 的 amount；三个数字在 Plan 构造期逐值互校。
 * </ol>
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code social.SeedGroups}（逐批整组覆盖，带 {@code ageDays/anchorTick/stress}
 * 保真）→ {@code unit.CreateUnit}（<b>无 module 的纯人员单位</b>： manpower=[{type=role,
 * amount=count}]、equipment=[]、speed=4、mobilityPerMille=800、position=来源 GOV 当刻有效位置）→（给了
 * targetGovUnitId 且不同格时）{@code unit.PlanRoute}（waypoints = A* 逐格路径，首点 = 新单位落点、末点 = 目标 GOV 当刻有效位置）→
 * {@code sd.PutInfo}（地址 = 来源 GOV canonical，key={@code selectExaminees}，value 含来源/目的/角色，note 人可读）。
 *
 * <p>★★ <b>新单位 id 由参数或确定性生成</b>：参数给了 {@code newUnitId} 就用它（已存在 ⇒ 具名拒）；没给就取 {@code
 * "exam-<来源GOV>-<tick>-<count>"}，若该 id 已在状态里则依次追加 {@code -2}/{@code -3}…（同一状态 + 同一参数 ⇒ 同一个
 * id；纯状态函数，不用随机量/墙钟）。
 *
 * <p>★★ <b>纯推导校验（前置不满足 ⇒ 工具折 {@code BAD_REQUEST}、零 revision）</b>：来源单位存在且带 {@link
 * GovFormation}；{@code count ≥ 1}（{@code unit.CreateUnit} 的 manpower amount 是 long，不再有 int 上限）；来源
 * GOV 当刻必须有有效位置（否则新单位没有落点）；{@code targetGovUnitId} 若给必须是存在的 GOV，且可达 （A* 无路 ⇒ 具名拒）；无 jurisdiction /
 * 辖区 Region 在地图里查无 ⇒ 具名拒；辖区 Region hex 重叠导致同一批次被 两个 Region 选中 ⇒ 具名拒（判据与 recruit 同款）。
 *
 * <p>★ <b>确定性 / 保序不可变</b>：不碰墙钟（{@code tick} 是状态 meta 的函数）、不用随机量；来源表沿用 {@link
 * GovRecruitPlan.GroupSource}（唯一一份“批次 + 抽走人数”的形状），以 {@link List#copyOf} 冻住。
 */
final class GovSelectExamineesPlan {

  /** {@code social.SeedGroups} 的命令类型（与 {@code SeedGroupsHandler.type()} 同字面）。 */
  static final String SEED_GROUPS_TYPE = "social.SeedGroups";

  /** {@code unit.CreateUnit} 的命令类型（与 {@code CreateUnitHandler.type()} 同字面）。 */
  static final String CREATE_UNIT_TYPE = "unit.CreateUnit";

  /** {@code unit.PlanRoute} 的命令类型（给了目标 GOV 且不同格时才落）。 */
  static final String PLAN_ROUTE_TYPE = "unit.PlanRoute";

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 新纯人员单位的速度（控制方口径：科举选人赶路比普通行政班子快）。 */
  static final int NEW_UNIT_SPEED = 4;

  /** 新纯人员单位的机动性（控制方口径 800‰）。 */
  static final int NEW_UNIT_MOBILITY_PER_MILLE = 800;

  /** {@code role} 缺省时的记录标签（它只是行动记录里的角色说明，不落任何单位字段——新单位无 module）。 */
  static final String DEFAULT_ROLE = "EXAMINEE";

  private GovSelectExamineesPlan() {}

  /**
   * 纯推导入口（见类注的来源口径与校验清单）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 来源 GOV 单位 id（必须带 {@link GovFormation}）
   * @param count 选送人数（≥ 1，且 ≤ {@code Integer.MAX_VALUE}）
   * @param targetGovUnitId 目的 GOV（可选；给了就要求存在、是 GOV，并规划到它当刻位置的路线）
   * @param roleText 行动记录里的角色标签（可选；缺省 {@value #DEFAULT_ROLE}）
   * @param newUnitId 新单位 id（可选；缺省确定性生成）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  static Plan plan(
      SimulationState state,
      String unitId,
      long count,
      Optional<String> targetGovUnitId,
      Optional<String> roleText,
      Optional<String> newUnitId) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(targetGovUnitId, "targetGovUnitId");
    Objects.requireNonNull(roleText, "roleText");
    Objects.requireNonNull(newUnitId, "newUnitId");
    requireNonBlank(unitId, "unitId");
    if (count < 1L) {
      throw new IllegalArgumentException("选送人数 count 必须 ≥ 1: " + count);
    }
    String role = roleText.orElse(DEFAULT_ROLE);
    requireNonBlank(role, "role");
    UnitState units = ToolSupport.unitState(state);
    SimosTimestamp timestamp = state.meta().timestamp();
    long tick = timestamp.tick();
    Unit source = units.units().get(UnitId.parse(unitId));
    if (source == null) {
      throw new IllegalArgumentException("来源 GOV 单位不存在: " + unitId);
    }
    requireGovFormation(source, unitId);
    Optional<HexCoord> sourceAt = units.effectivePosition(source.id(), timestamp);
    if (sourceAt.isEmpty()) {
      throw new IllegalArgumentException("来源 GOV " + unitId + " 当刻没有有效位置：新单位落点无法确定；先 unit.PlaceAt");
    }
    String newId =
        resolveNewUnitId(
            units, newUnitId, "exam-" + unitId + "-" + tick + "-" + count, "newUnitId");
    Optional<String> targetId = Optional.empty();
    Optional<HexCoord> targetAt = Optional.empty();
    Optional<List<HexCoord>> route = Optional.empty();
    if (targetGovUnitId.isPresent()) {
      String targetText = targetGovUnitId.get();
      requireNonBlank(targetText, "targetGovUnitId");
      Unit target = units.units().get(UnitId.parse(targetText));
      if (target == null) {
        throw new IllegalArgumentException("目的 GOV 单位不存在: " + targetText);
      }
      requireGovFormation(target, targetText);
      Optional<HexCoord> at =
          target.id().equals(source.id())
              ? sourceAt
              : units.effectivePosition(target.id(), timestamp);
      if (at.isEmpty()) {
        throw new IllegalArgumentException(
            "目的 GOV " + targetText + " 当刻没有有效位置：无法规划路线；先 unit.PlaceAt");
      }
      targetId = Optional.of(targetText);
      targetAt = at;
      if (!at.get().equals(sourceAt.get())) {
        Optional<List<HexCoord>> path =
            PathFinder.findPath(
                ToolSupport.gameMap(state),
                sourceAt.get(),
                at.get(),
                pathProbeUnit(timestamp, sourceAt.get()),
                TerrainMovementCost.INSTANCE);
        if (path.isEmpty()) {
          throw new IllegalArgumentException(
              "目的 GOV "
                  + targetText
                  + "（"
                  + hexText(at.get())
                  + "）从来源 "
                  + hexText(sourceAt.get())
                  + " 不可达：A* 找不到路径（先确认地形连通或改指可达的 GOV）");
        }
        route = path;
      }
    }
    var jurisdiction =
        source
            .jurisdiction()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "来源 GOV "
                            + unitId
                            + " 没有 jurisdiction（无管辖区域）：没有可科举来源；先 unit.SetJurisdiction"));
    if (jurisdiction.taxRatePerMilleByRegion().isEmpty()) {
      throw new IllegalArgumentException(
          "来源 GOV " + unitId + " 的 jurisdiction 为空（无管辖区域）：没有可科举来源；先 unit.SetJurisdiction");
    }
    SocialData social = ToolSupport.socialData(state);
    GameMap map = ToolSupport.gameMap(state);
    List<GovRecruitPlan.GroupSource> sources = new ArrayList<>();
    Set<String> seenGroupIds = new LinkedHashSet<>();
    long remaining = count;
    long available = 0L;
    for (RegionId regionId : jurisdiction.taxRatePerMilleByRegion().keySet()) {
      Region region = map.regions().get(regionId);
      if (region == null) {
        throw new IllegalArgumentException(
            "辖区区域在地图里查无: "
                + regionId.value()
                + "（来源 GOV "
                + unitId
                + " 的 jurisdiction 指向了一个已不存在的 Region；先 map.CreateRegion 或调整管辖）");
      }
      long regionAvailable = regionAvailability(social, region, tick);
      available = saturatedAdd(available, regionAvailable);
      if (remaining == 0L || regionAvailable == 0L) {
        continue;
      }
      long take = Math.min(remaining, regionAvailable);
      for (RegionAllocations.GroupSource sourceGroup :
          RegionAllocations.allocateManpower(social, region, tick, take).sources()) {
        GovRecruitPlan.GroupSource item = GovRecruitPlan.GroupSource.from(sourceGroup);
        if (!seenGroupIds.add(item.group().id().value())) {
          throw new IllegalArgumentException(
              "同一社会批次被多个 Region 选中（辖区 Region hex 重叠）: "
                  + item.group().id().value()
                  + "（先修管辖区域，避免同一批人被扣两次）");
        }
        sources.add(item);
      }
      remaining -= take;
    }
    if (remaining != 0L) {
      throw new IllegalArgumentException(
          "科举来源不足：requested="
              + count
              + "，available="
              + available
              + "，缺口="
              + (count > available ? count - available : remaining)
              + "（不部分抽取、不截断；先扩管辖 / 等人口长大或降低 count）");
    }
    return new Plan(
        unitId,
        newId,
        "科举选人 " + unitId,
        sourceAt.get(),
        tick,
        count,
        role,
        targetId,
        targetAt,
        route,
        available,
        sources);
  }

  /**
   * ★ <b>一个辖 Region 的合格人力总量</b>：调 {@link RegionAllocations#allocateManpower}、{@code
   * requested=1}——成功时它返回的 {@code available} 是该 Region 全部合格批次人数之和。其它 IAE（如未来锚点导致
   * 年龄为负的坏数据）必须原样重抛，<b>不静默当 0</b>。
   */
  private static long regionAvailability(SocialData social, Region region, long tick) {
    try {
      return RegionAllocations.allocateManpower(social, region, tick, 1L).available();
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("人力总量不足")) {
        return 0L;
      }
      throw e;
    }
  }

  /**
   * 新单位 id：参数给了就用参数（已存在 ⇒ 具名拒）；否则确定性生成（同状态 + 同参数 ⇒ 同 id），生成 id 已存在时依次 追加后缀。两条路都保证“新 id 不在当前 unit
   * 切片里”。
   *
   * <p>★ <b>人员流转两个建单位的工具（selectExaminees / dispatchTeam）共用这一份</b>：id 生成口径只此一处， 两个工具各自的 {@code
   * fallbackBase} 前缀不同。
   */
  static String resolveNewUnitId(
      UnitState units, Optional<String> explicit, String fallbackBase, String field) {
    if (explicit.isPresent()) {
      String candidate = explicit.get();
      requireNonBlank(candidate, field);
      if (units.units().containsKey(UnitId.parse(candidate))) {
        throw new IllegalArgumentException(field + " 指定的单位 id 已存在（不得复用）: " + candidate);
      }
      return candidate;
    }
    if (!units.units().containsKey(UnitId.parse(fallbackBase))) {
      return fallbackBase;
    }
    for (int suffix = 2; suffix < Integer.MAX_VALUE; suffix++) {
      String candidate = fallbackBase + "-" + suffix;
      if (!units.units().containsKey(UnitId.parse(candidate))) {
        return candidate;
      }
    }
    throw new IllegalArgumentException("确定性生成的新单位 id 后缀已用尽（unit 切片里 " + fallbackBase + "-* 全部被占用）");
  }

  /** 单位必须带 {@link GovFormation}（本工具只做 GOV 侧的人员流转）。 */
  private static GovFormation requireGovFormation(Unit unit, String unitId) {
    if (unit.module().orElse(null) instanceof GovFormation gov) {
      return gov;
    }
    throw new IllegalArgumentException(
        "单位 " + unitId + " 没有 GovFormation：本工具只对 GOV 单位；先 unit.SetGovFormation");
  }

  /**
   * 只给 {@link PathFinder} 用的探针单位：唯一被读的字段是 {@code mobilityPerMille}，取新单位的 800‰，保证
   * 寻路成本与将创建的新单位逐值一致（不拿来源 GOV 的机动性估）。
   */
  private static Unit pathProbeUnit(SimosTimestamp at, HexCoord start) {
    return new Unit(
        new UnitId("__select-examinees-path-probe__"),
        "selectExaminees path probe",
        new SegmentedSeries<>(
            List.of(new Segment<>(at, Optional.<UnitId>empty())), List.of(), null),
        new SegmentedSeries<>(List.of(new Segment<>(at, Optional.of(start))), List.of(), null),
        List.of(),
        List.of(),
        NEW_UNIT_SPEED,
        NEW_UNIT_MOBILITY_PER_MILLE,
        Optional.empty(),
        UnitStatus.MOVING,
        new SegmentedSeries<>(List.of(new Segment<>(at, false)), List.of(), null),
        new SegmentedSeries<>(
            List.of(new Segment<>(at, Optional.<RelativeOffset>empty())), List.of(), null),
        Optional.empty(),
        Unit.DEFAULT_VISION_RADIUS,
        Optional.empty(),
        Optional.empty(),
        // ★ 创建（不是拷贝）：探针单位不承载任何状态链接（阶段 D1 / D-012）。
        Map.<String, String>of());
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本");
    }
  }

  /** 饱和加法（非负 long；只用于 available 合计与拒因展示，不参与逐值扣减）。 */
  private static long saturatedAdd(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  /** 格的可读文本（拒因与行动记录共用；格式不与任何资源路径语法绑定）。 */
  static String hexText(HexCoord at) {
    return "(" + at.q() + "," + at.r() + ")";
  }

  /**
   * 一份科举选人计划（全部字段是状态的纯函数；来源表在构造期冻结）。
   *
   * @param unitId 来源 GOV 单位 id
   * @param newUnitId 新纯人员单位 id（untagged：无 module）
   * @param unitName 新单位名
   * @param at 新单位落点 = 来源 GOV 当刻有效位置
   * @param tick 推导时的世界日
   * @param count 选送人数（= Σ来源扣人 = 新单位 manpower 单条的 amount；type=role）
   * @param role 行动记录里的角色标签
   * @param targetGovUnitId 目的 GOV（可选）
   * @param targetAt 目的 GOV 当刻有效位置（仅给了目标时有值）
   * @param route 新单位到目标 GOV 的逐格 A* 路径（可选；同格 = 空 = 批里无 PlanRoute）
   * @param available 全部辖区 Region 的合格来源合计（不足拒因与视图用）
   * @param sources 逐来源（辖区顺序 + Region 内瀑布序；Σtaken == count）
   */
  record Plan(
      String unitId,
      String newUnitId,
      String unitName,
      HexCoord at,
      long tick,
      long count,
      String role,
      Optional<String> targetGovUnitId,
      Optional<HexCoord> targetAt,
      Optional<List<HexCoord>> route,
      long available,
      List<GovRecruitPlan.GroupSource> sources) {

    Plan {
      requireNonBlank(unitId, "unitId");
      requireNonBlank(newUnitId, "newUnitId");
      requireNonBlank(unitName, "unitName");
      Objects.requireNonNull(at, "at");
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      if (count < 1L || count > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("count 必须在 [1, Integer.MAX_VALUE]: " + count);
      }
      requireNonBlank(role, "role");
      Objects.requireNonNull(targetGovUnitId, "targetGovUnitId");
      Objects.requireNonNull(targetAt, "targetAt");
      Objects.requireNonNull(route, "route");
      if (available < 0L) {
        throw new IllegalArgumentException("available 不得为负: " + available);
      }
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      long total = 0L;
      Set<String> seen = new LinkedHashSet<>();
      for (GovRecruitPlan.GroupSource source : sources) {
        if (!seen.add(source.group().id().value())) {
          throw new IllegalArgumentException("守恒破坏：同一社会批次出现两次: " + source.group().id().value());
        }
        total = saturatedAdd(total, source.taken());
      }
      if (total != count) {
        throw new IllegalArgumentException(
            "守恒破坏：Σ来源扣人=" + total + " != count=" + count + "（批载荷必须逐值对应）");
      }
      if (route.isPresent()) {
        List<HexCoord> path = route.get();
        if (targetAt.isEmpty()) {
          throw new IllegalArgumentException("规划了路线却没有目的 GOV 落点");
        }
        if (path.size() < 2
            || !path.get(0).equals(at)
            || !path.get(path.size() - 1).equals(targetAt.get())) {
          throw new IllegalArgumentException("内部分摊不自洽：路线必须至少两格，且首点=新单位落点、末点=目的 GOV 落点");
        }
      }
    }

    /** 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(4);
      types.add(SEED_GROUPS_TYPE);
      types.add(CREATE_UNIT_TYPE);
      if (route.isPresent()) {
        types.add(PLAN_ROUTE_TYPE);
      }
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /** {@code social.SeedGroups} 载荷：逐批整组覆盖（count=扣后，带保真三件；可为 0）。 */
    String seedGroupsPayloadJson() {
      List<Map<String, Object>> entries = new ArrayList<>(sources.size());
      for (GovRecruitPlan.GroupSource source : sources) {
        PopulationGroup group = source.group();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", group.id().value());
        entry.put("q", group.residence().q());
        entry.put("r", group.residence().r());
        entry.put("sex", group.sex().name());
        entry.put("count", source.countAfter());
        entry.put("ageDays", group.ageAtAnchorDays());
        entry.put("anchorTick", group.anchorTick());
        entry.put("stress", group.physiologicalStress());
        entries.add(entry);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("entries", entries);
      return ToolSupport.json(payload);
    }

    /** {@code unit.CreateUnit} 载荷：untagged 纯人员单位（无 module；handler 对新单位固定 empty）。 */
    String createUnitPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", newUnitId);
      payload.put("name", unitName);
      payload.put("position", ToolSupport.hexCoord(at));
      // ★ D3a：type 取行动记录里的角色标签（缺省 EXAMINEE）——它是这一批人的自然语义身份。
      payload.put(
          "manpower", ToolSupport.compositionView(List.of(new CompositionEntry(role, count))));
      payload.put("equipment", List.of());
      payload.put("speed", NEW_UNIT_SPEED);
      payload.put("mobilityPerMille", NEW_UNIT_MOBILITY_PER_MILLE);
      return ToolSupport.json(payload);
    }

    /** {@code unit.PlanRoute} 载荷（{@link #route} 为真时才可调用）：waypoints = A* 逐格路径本身。 */
    String planRoutePayloadJson() {
      if (route.isEmpty()) {
        throw new IllegalStateException("批不自洽：无路线却要组装 unit.PlanRoute 载荷");
      }
      List<Map<String, Object>> waypoints = new ArrayList<>(route.get().size());
      for (HexCoord coord : route.get()) {
        waypoints.add(ToolSupport.hexCoord(coord));
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", newUnitId);
      payload.put("waypoints", waypoints);
      return ToolSupport.json(payload);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON 字符串；含来源/目的/角色）。 */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("sourceGovUnitId", unitId);
      value.put("newUnitId", newUnitId);
      value.put("targetGovUnitId", targetGovUnitId.orElse(null));
      value.put("role", role);
      value.put("count", count);
      value.put("tick", tick);
      value.put("at", ToolSupport.hexCoord(at));
      value.put("available", available);
      value.put("route", routeView());
      value.put("sources", sourcesView());
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireNonBlank(reason, "reason");
      return "科举选人 "
          + unitId
          + " → "
          + newUnitId
          + "（"
          + count
          + " 人，role="
          + role
          + "，tick "
          + tick
          + "）：落点 "
          + hexText(at)
          + "，目的="
          + targetGovUnitId.orElse("(未指定)")
          + (route.isPresent()
              ? "（路线 " + route.get().size() + " 格，末点 " + hexText(targetAt.get()) + "）"
              : "（同格/未指定目标：无 PlanRoute）")
          + "；来源批次 "
          + sources.size()
          + "；reason="
          + reason;
    }

    /** 逐来源视图（工具结果用；保序）。 */
    List<Map<String, Object>> sourcesView() {
      List<Map<String, Object>> rows = new ArrayList<>(sources.size());
      for (GovRecruitPlan.GroupSource source : sources) {
        PopulationGroup group = source.group();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", group.id().value());
        row.put("q", group.residence().q());
        row.put("r", group.residence().r());
        row.put("before", group.count());
        row.put("taken", source.taken());
        row.put("after", source.countAfter());
        rows.add(row);
      }
      return rows;
    }

    /** 路线视图（格列表；未规划时 null）。 */
    List<Map<String, Object>> routeView() {
      if (route.isEmpty()) {
        return null;
      }
      List<Map<String, Object>> rows = new ArrayList<>(route.get().size());
      for (HexCoord coord : route.get()) {
        rows.add(ToolSupport.hexCoord(coord));
      }
      return rows;
    }
  }
}

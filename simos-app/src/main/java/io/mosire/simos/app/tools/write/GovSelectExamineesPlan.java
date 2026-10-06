package io.mosire.simos.app.tools.write;

import io.mosire.simos.actor.spi.EnsureHouseholdAccountHandler;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.spi.EconomyRegisterHouseholdHandler;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.spi.PutInfoHandler;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.move.PathFinder;
import io.mosire.simos.unit.move.TerrainMovementCost;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import io.mosire.simos.unit.spi.PlanRouteHandler;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.gov.selectExaminees} 的<b>纯推导</b>（阶段 13A 人员流转；P1.5 接完整 Social 工单路径）：从一份 {@link
 * SimulationState} 与参数算出 {@link Plan}——<b>不碰 {@link io.mosire.agentlib.tool.ToolContext}、不碰 {@code
 * CoreSimos}</b>，preview 与 apply 因此共用同一份语义。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：人在 {@code social} 切片、新单位/路线在 {@code unit} 切片、 家户经济行在 {@code
 * economy} 切片、家户账户在 {@code actor} 切片、行动记录在 {@code sd} 切片；单条命令只能落一个命名空间。本工具走 {@link
 * io.mosire.simos.core.CoreSimos#submitBatch}（同 branch + 同 expectedRevision ⇒ 一批 = 一条 revision，原子）。
 *
 * <p>★★ <b>来源口径 = 来源 GOV 辖区的 share-aware 家户份额瀑布</b>（P1.5 起与 {@code simos.gov.recruit} 同源）：
 *
 * <ol>
 *   <li><b>辖区顺序</b>：{@code Unit.jurisdiction.taxRatePerMilleByRegion} 的 key 插入序（构造期保序冻结），逐 Region 把
 *       {@code region.hexes()} 追加成 {@link HouseholdManpowerAllocator#allocateMalesOfAdult} 的 {@code
 *       jurisdictionHexesInOrder}；
 *   <li><b>选人唯一拼写点</b>：只调 {@link HouseholdManpowerAllocator#allocateMalesOfAdult}（MALE + {@link
 *       io.mosire.simos.social.population.AgeBracket#ADULT}；家户 id 升序 / lotId 升序瀑布），本类不另写排序、过滤或扣减；
 *       <b>旧</b> {@link RegionAllocations} 的整批 count 瀑布与 {@code social.SeedGroups} 批不再进入本路径；
 *   <li><b>不足 ⇒ 整条拒</b>：家户份额总量不足时带 requested / available / 缺口具名抛出，<b>不部分抽取、不截断</b>；
 *   <li><b>守恒</b>：Σ share.taken == {@code count} == 新人口家户的成员增量；Plan 构造期逐值互校，批载荷逐值对应。
 * </ol>
 *
 * <p>★★ <b>新单位人口家户 = {@code hh-unit:<newUnitId>}（与 {@code RaiseUnitPlan} 同拼写）</b>：先校验它不在 {@code
 * SocialData.households()}（不得复用既有家户），再作为工单 target；新单位的 {@code unit.households} 恰好是该家户。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code social.SubmitHouseholdWorkOrder}（{@code
 * orderId=gov-select-examinees:<batchId>:<newUnitId>}；{@code plan =
 * CREATE_HOUSEHOLD(hh-unit:<newUnitId>, UNIT(newUnitId), profile=name+"·人口家户") + 逐 share
 * TRANSFER_MEMBERS(from=来源家户, to=新家户, lotId, count=taken)}）→ {@code unit.CreateUnit}（{@code
 * households=[新家户]}、equipment=[]、speed=4、mobilityPerMille=800；<b>无 manpower</b>）→ （目的 GOV
 * 给了且不同格时）{@code unit.PlanRoute}（A* 逐格路径）→ {@code economy.RegisterHousehold}（新家户经济行）→ {@code
 * actor.EnsureHouseholdAccount}（新家户零余额账户，幂等）→ {@code sd.PutInfo}（地址 = 来源 GOV canonical， key={@code
 * selectExaminees}，value 含新单位/人数/家户/来源 shares/route）。
 *
 * <p>★★ <b>目的 GOV route 逻辑保留</b>：来源与目的都是 GOV、当刻都有有效位置；同格 ⇒ 不发 {@code unit.PlanRoute}，不同格 ⇒ A*
 * 可达才发（不可达具名拒）。
 *
 * <p>★★ <b>纯推导校验（前置不满足 ⇒ 工具折 {@code BAD_REQUEST}、零 revision）</b>：来源单位存在且带 {@link
 * GovernmentFormation}；{@code count ≥ 1}；来源 GOV 当刻必须有有效位置；{@code targetGovUnitId} 若给必须是存在的 GOV，且可达；
 * 无 jurisdiction / 辖区 Region 在地图里查无 ⇒ 具名拒；新单位人口家户 id 已被占用 ⇒ 具名拒；来源份额不足 ⇒ 整条具名拒。
 *
 * <p>★ <b>确定性 / 保序不可变</b>：不碰墙钟（{@code tick} 是状态 meta 的函数）、不用随机量；来源表由 {@link
 * HouseholdManpowerAllocator} 的全序瀑布冻住，以 {@link List#copyOf} 冻住。
 */
final class GovSelectExamineesPlan {

  /** {@code social.SubmitHouseholdWorkOrder} 的命令类型（引用 social handler 常量，本类不另抄字面量）。 */
  static final String SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE = SubmitHouseholdWorkOrderHandler.TYPE;

  /** {@code unit.CreateUnit} 的命令类型（引用 unit handler 常量）。 */
  static final String CREATE_UNIT_TYPE = CreateUnitHandler.TYPE;

  /** {@code unit.PlanRoute} 的命令类型（给了目标 GOV 且不同格时才落；引用 unit handler 常量）。 */
  static final String PLAN_ROUTE_TYPE = PlanRouteHandler.TYPE;

  /** {@code economy.RegisterHousehold} 的命令类型（引用 economy handler 常量）。 */
  static final String REGISTER_HOUSEHOLD_TYPE = EconomyRegisterHouseholdHandler.TYPE;

  /** {@code actor.EnsureHouseholdAccount} 的命令类型（引用 actor handler 常量）。 */
  static final String ENSURE_HOUSEHOLD_ACCOUNT_TYPE = EnsureHouseholdAccountHandler.TYPE;

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录；引用 sd handler 常量）。 */
  static final String PUT_INFO_TYPE = PutInfoHandler.TYPE;

  /** 新纯人员单位的速度（控制方口径：科举选人赶路比普通行政班子快）。 */
  static final int NEW_UNIT_SPEED = 4;

  /** 新纯人员单位的机动性（控制方口径 800‰）。 */
  static final int NEW_UNIT_MOBILITY_PER_MILLE = 800;

  /** {@code role} 缺省时的记录标签（它只是行动记录里的角色说明，不落任何单位字段——新单位无 module）。 */
  static final String DEFAULT_ROLE = "EXAMINEE";

  /** 工单来源模块名（进 {@code social.SubmitHouseholdWorkOrder} 载荷与日志）。 */
  private static final String SOURCE_MODULE = "gov";

  private GovSelectExamineesPlan() {}

  /**
   * 纯推导入口（见类注的来源口径与校验清单）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 来源 GOV 单位 id（必须带 {@link GovernmentFormation}）
   * @param count 选送人数（≥ 1）
   * @param targetGovUnitId 目的 GOV（可选；给了就要求存在、是 GOV，并规划到它当刻位置的路线）
   * @param roleText 行动记录里的角色标签（可选；缺省 {@value #DEFAULT_ROLE}）
   * @param newUnitId 新单位 id（可选；缺省确定性生成）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  // ★ 测试/旧路径：全缺省儒略历时钟；生产路径由 CalendarService.clock() 传入。
  static Plan plan(
      SimulationState state,
      String unitId,
      long count,
      Optional<String> targetGovUnitId,
      Optional<String> roleText,
      Optional<String> newUnitId) {
    return plan(
        state, unitId, count, targetGovUnitId, roleText, newUnitId, CalendarClock.julianDefault());
  }

  /**
   * 生产入口：历法时钟由调用方传入（本类的年龄档判定只认这台钟）。
   *
   * @param clock 历法时钟（非空；生产路径 = CalendarService.clock()）
   */
  static Plan plan(
      SimulationState state,
      String unitId,
      long count,
      Optional<String> targetGovUnitId,
      Optional<String> roleText,
      Optional<String> newUnitId,
      CalendarClock clock) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(targetGovUnitId, "targetGovUnitId");
    Objects.requireNonNull(roleText, "roleText");
    Objects.requireNonNull(newUnitId, "newUnitId");
    requireNonBlank(unitId, "unitId");
    if (count < 1L) {
      throw new IllegalArgumentException("选送人数 count 必须 ≥ 1: " + count);
    }
    String role = roleText.orElse(DEFAULT_ROLE);
    requireNonBlank(role, "role");
    targetGovUnitId.ifPresent(text -> requireNonBlank(text, "targetGovUnitId"));
    newUnitId.ifPresent(text -> requireNonBlank(text, "newUnitId"));
    UnitState units = ToolSupport.unitState(state);
    SimosTimestamp timestamp = state.meta().timestamp();
    long tick = timestamp.tick();
    Unit source = units.units().get(UnitId.parse(unitId));
    if (source == null) {
      throw new IllegalArgumentException("来源 GOV 单位不存在: " + unitId);
    }
    requireGovernmentFormation(source, unitId);
    Optional<HexCoord> sourceAt = units.effectivePosition(source.id(), timestamp);
    if (sourceAt.isEmpty()) {
      throw new IllegalArgumentException("来源 GOV " + unitId + " 当刻没有有效位置：新单位落点无法确定；先 unit.PlaceAt");
    }
    String newId =
        resolveNewUnitId(
            units, newUnitId, "exam-" + unitId + "-" + tick + "-" + count, "newUnitId");

    // ── 目的 GOV route 逻辑（P1.5 保留）：同格不发 PlanRoute；不同格必须 A* 可达 ──────────
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
      requireGovernmentFormation(target, targetText);
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

    // ── 来源：来源 GOV 辖区的 Region hex 顺序 → HouseholdManpowerAllocator（MALE + ADULT）──
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
    String householdId = RaiseUnitPlan.householdIdFor(newId);
    if (social.households().containsKey(HouseholdId.parse(householdId))) {
      throw new IllegalArgumentException(
          "P1.5 新单位的人口家户 id 已被占用: " + householdId + "（先清掉同名家户，或换 newUnitId）");
    }
    GameMap map = ToolSupport.gameMap(state);
    List<Set<HexCoord>> jurisdictionHexesInOrder = new ArrayList<>();
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
      jurisdictionHexesInOrder.add(region.hexes());
    }
    HouseholdManpowerAllocator.Allocation allocation;
    try {
      allocation =
          HouseholdManpowerAllocator.allocateMalesOfAdult(
              social, jurisdictionHexesInOrder, count, clock, tick, Set.of());
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("人力总量不足")) {
        throw new IllegalArgumentException("科举来源不足：" + e.getMessage(), e);
      }
      throw e;
    }
    return new Plan(
        unitId,
        newId,
        "科举选人 " + unitId,
        sourceAt.get(),
        residenceAt(social, sourceAt.get()),
        tick,
        count,
        role,
        targetId,
        targetAt,
        route,
        allocation.available(),
        householdId,
        allocation.shares());
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

  /** 单位必须带 {@link GovernmentFormation}（本工具只做 GOV 侧的人员流转）。 */
  private static GovernmentFormation requireGovernmentFormation(Unit unit, String unitId) {
    if (unit.module().orElse(null) instanceof GovernmentFormation governmentFormation) {
      return governmentFormation;
    }
    throw new IllegalArgumentException(
        "单位 " + unitId + " 没有 GovernmentFormation：本工具只对 GOV 单位；先 unit.SetGovFormation");
  }

  /**
   * P1.5：新人口家户 economy 视图的居住类型（与 {@link RaiseUnitPlan} 同口径）——落点是某座 {@code SocialCity} 的 {@code at}
   * ⇒ {@link ResidenceKind#URBAN}，否则 {@link ResidenceKind#RURAL}。两个 P1.5 工具共用这一份，不另写第二份判据。
   */
  static ResidenceKind residenceAt(SocialData social, HexCoord at) {
    for (var city : social.cities().values()) {
      if (city.at().equals(at)) {
        return ResidenceKind.URBAN;
      }
    }
    return ResidenceKind.RURAL;
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
        Map.<String, String>of(),
        // ★ 创建（不是拷贝）：探针单位不容纳任何家户（S3a / 2026-10-09）。
        List.of());
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本");
    }
  }

  /** 饱和加法（非负 long；只用于守恒合计与拒因展示，不参与逐值扣减）。 */
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
   * @param residence P1.5：新人口家户 economy 视图的居住类型（at 是某城 at ⇒ URBAN，否则 RURAL）
   * @param tick 推导时的世界日
   * @param count 选送人数（= Σ来源 share.taken = 新人口家户的成员增量）
   * @param role 行动记录里的角色标签
   * @param targetGovUnitId 目的 GOV（可选）
   * @param targetAt 目的 GOV 当刻有效位置（仅给了目标时有值）
   * @param route 新单位到目标 GOV 的逐格 A* 路径（可选；同格 = 空 = 批里无 PlanRoute）
   * @param available 全部辖区合格来源合计（不足拒因与视图用；成功时 ≥ count）
   * @param householdId 新单位人口家户 id（{@code hh-unit:<newUnitId>}；location = UNIT(newUnitId)）
   * @param sources 逐来源家户份额（辖区顺序 + 家户/lot 全序瀑布序；Σtaken == count）
   */
  record Plan(
      String unitId,
      String newUnitId,
      String unitName,
      HexCoord at,
      ResidenceKind residence,
      long tick,
      long count,
      String role,
      Optional<String> targetGovUnitId,
      Optional<HexCoord> targetAt,
      Optional<List<HexCoord>> route,
      long available,
      String householdId,
      List<HouseholdManpowerAllocator.ManpowerShare> sources) {

    Plan {
      requireNonBlank(unitId, "unitId");
      requireNonBlank(newUnitId, "newUnitId");
      requireNonBlank(unitName, "unitName");
      Objects.requireNonNull(at, "at");
      Objects.requireNonNull(residence, "residence");
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      if (count < 1L) {
        throw new IllegalArgumentException("count 必须 ≥ 1: " + count);
      }
      requireNonBlank(role, "role");
      Objects.requireNonNull(targetGovUnitId, "targetGovUnitId");
      Objects.requireNonNull(targetAt, "targetAt");
      Objects.requireNonNull(route, "route");
      if (available < 0L) {
        throw new IllegalArgumentException("available 不得为负: " + available);
      }
      if (available < count) {
        throw new IllegalArgumentException("内部分摊不自洽：available=" + available + " < count=" + count);
      }
      requireNonBlank(householdId, "householdId");
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      long total = 0L;
      for (HouseholdManpowerAllocator.ManpowerShare share : sources) {
        if (share.householdId().value().equals(householdId)) {
          throw new IllegalArgumentException("内部分摊不自洽：来源家户不得是目标家户 " + householdId + "（自我转移会被域层拒）");
        }
        total = saturatedAdd(total, share.taken());
      }
      if (total != count) {
        throw new IllegalArgumentException(
            "守恒破坏：Σ来源 share.taken=" + total + " != count=" + count + "（批载荷必须逐值对应）");
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

    /**
     * 工单确定性幂等键：{@code gov-select-examinees:<batchId>:<newUnitId>}。{@code batchId} 是本工具 apply 生成的
     * batch UUID（同一 apply 内稳定 ⇒ 同批可复现）。
     */
    String orderId(String batchId) {
      requireNonBlank(batchId, "batchId");
      return "gov-select-examinees:" + batchId + ":" + newUnitId;
    }

    /**
     * P1.5 人口腿的<b>唯一</b>命令载荷（{@code social.SubmitHouseholdWorkOrder}）——第一步 {@code
     * CREATE_HOUSEHOLD}（location = {@code UNIT(newUnitId)}、画像 {@code name+"·人口家户"}、vitalRates
     * 空表），随后逐来源 {@code TRANSFER_MEMBERS(from=share.householdId, to=新家户, lotId, count=taken)}。
     */
    String submitHouseholdWorkOrderPayloadJson(String batchId, String reason) {
      requireNonBlank(reason, "reason");
      List<Map<String, Object>> steps = new ArrayList<>(sources.size() + 1);
      Map<String, Object> create = new LinkedHashMap<>();
      create.put("op", "CREATE_HOUSEHOLD");
      create.put("household", householdId);
      Map<String, Object> location = new LinkedHashMap<>();
      location.put("type", "UNIT");
      location.put("unitId", newUnitId);
      create.put("location", location);
      Map<String, Object> profile = new LinkedHashMap<>();
      profile.put("name", unitName + "·人口家户");
      create.put("profile", profile);
      create.put("vitalRates", List.of());
      steps.add(create);
      for (HouseholdManpowerAllocator.ManpowerShare share : sources) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("op", "TRANSFER_MEMBERS");
        step.put("from", share.householdId().value());
        step.put("to", householdId);
        step.put("lotId", share.lotId().value());
        step.put("count", share.taken());
        steps.add(step);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("orderId", orderId(batchId));
      payload.put("target", householdId);
      payload.put("reason", reason);
      payload.put("source", Map.of("module", SOURCE_MODULE));
      payload.put("plan", steps);
      return ToolSupport.json(payload);
    }

    /**
     * {@code unit.CreateUnit} 载荷：untagged 纯人员单位；人口由 {@code households=[新人口家户]} 承载，<b>不再发已退役的 {@code
     * manpower}</b>。{@code jurisdiction} 不在载荷里——{@code CreateUnitHandler} 对新建单位一律取 {@code
     * Optional.empty()}。
     */
    String createUnitPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", newUnitId);
      payload.put("name", unitName);
      payload.put("position", ToolSupport.hexCoord(at));
      payload.put("households", List.of(householdId));
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

    /**
     * {@code economy.RegisterHousehold} 载荷：落点 = {@code at}，居住类型 = {@link #residence()}，阶层 = {@code
     * landless_laborer}（无资产的中性档），参与率 = 0（由 Social 逐户劳动预算在后续日循环注入）。
     */
    String registerHouseholdPayloadJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("household", householdId);
      payload.put("q", at.q());
      payload.put("r", at.r());
      payload.put("residence", residence.value());
      payload.put("stratum", io.mosire.simos.economy.api.id.SocialClassId.LANDLESS_LABORER.value());
      payload.put("participationPerMille", 0);
      payload.put("reason", reason);
      return ToolSupport.json(payload);
    }

    /** {@code actor.EnsureHouseholdAccount} 载荷：给新人口家户补零余额账户（幂等）。 */
    String ensureHouseholdAccountPayloadJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("household", householdId);
      payload.put("reason", reason);
      return ToolSupport.json(payload);
    }

    /** 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(6);
      types.add(SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE);
      types.add(CREATE_UNIT_TYPE);
      if (route.isPresent()) {
        types.add(PLAN_ROUTE_TYPE);
      }
      types.add(REGISTER_HOUSEHOLD_TYPE);
      types.add(ENSURE_HOUSEHOLD_ACCOUNT_TYPE);
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON 字符串；含来源/目的/角色/家户/来源 shares）。 */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("sourceGovUnitId", unitId);
      value.put("newUnitId", newUnitId);
      value.put("householdId", householdId);
      value.put("targetGovUnitId", targetGovUnitId.orElse(null));
      value.put("role", role);
      value.put("count", count);
      value.put("tick", tick);
      value.put("at", ToolSupport.hexCoord(at));
      value.put("residence", residence.value());
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
          + "，家户 "
          + householdId
          + "，tick "
          + tick
          + "）：落点 "
          + hexText(at)
          + "，目的="
          + targetGovUnitId.orElse("(未指定)")
          + (route.isPresent()
              ? "（路线 " + route.get().size() + " 格，末点 " + hexText(targetAt.get()) + "）"
              : "（同格/未指定目标：无 PlanRoute）")
          + "；来源份额 "
          + sources.size()
          + " 条；reason="
          + reason;
    }

    /** 逐来源视图（工具结果与 {@code sd.PutInfo.value.sources} 共用；保序）。 */
    List<Map<String, Object>> sourcesView() {
      List<Map<String, Object>> rows = new ArrayList<>(sources.size());
      for (HouseholdManpowerAllocator.ManpowerShare share : sources) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("householdId", share.householdId().value());
        row.put("lotId", share.lotId().value());
        row.put("taken", share.taken());
        row.put("hex", share.hex() == null ? null : ToolSupport.hexCoord(share.hex()));
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

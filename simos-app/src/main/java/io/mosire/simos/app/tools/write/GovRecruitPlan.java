package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.spi.AssignGovPostHandler;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.gov.recruit} 的<b>纯推导</b>（阶段 10b-ii，2026-10-01 GOV/Army 计划 §2.2/§2.6；P1.1 改走
 * Social 家户工单）：从一份 {@link SimulationState} 与参数算出 {@link Plan}——<b>不碰 {@link
 * io.mosire.agentlib.tool.ToolContext}、不碰 {@code CoreSimos}</b>，preview 与 apply 因此共用同一份语义（工具只负责读态、把
 * Plan 折成视图、组批、折叠结局）。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：人在 {@code social} 切片、编制在 {@code unit} 切片、行动记录在 {@code sd}
 * 切片；单条命令只能落一个命名空间。本工具走 {@link io.mosire.simos.core.CoreSimos#submitBatch}（同 branch + 同
 * expectedRevision ⇒ 一批 = 一条 revision，原子）。
 *
 * <p>★★ <b>来源口径 = 辖区社会家户份额的跨 Region 瀑布</b>（P1.1）：
 *
 * <ol>
 *   <li><b>辖区顺序</b>：{@code Unit.jurisdiction.taxRatePerMilleByRegion} 的 key 插入序（{@code
 *       Jurisdiction} 构造期用 {@code LinkedHashMap} 保序冻结）；<b>逐 Region 的 hex 集合按这个顺序</b>交给 {@link
 *       HouseholdManpowerAllocator}；
 *   <li><b>选人唯一拼写点</b>：只调 {@link HouseholdManpowerAllocator#allocateMalesOfAdult}（MALE + {@link
 *       io.mosire.simos.social.population.AgeBracket#ADULT}；家户 id 升序 / lotId 升序瀑布），本类不另写排序、过滤或扣减；
 *       <b>旧</b> {@link RegionAllocations} 的整批 count 瀑布不再进入本路径；
 *   <li><b>排除目标政府家户</b>：分配时把 {@code hh-gov-<unitId>} 放进排除集——它是本批的<b>目标</b>，不得再作为来源 （自我转移会在域层被拒）；
 *   <li><b>不足 ⇒ 整条拒</b>：家户份额总量不足时带 requested / available / 缺口具名抛出，<b>不部分抽取、不截断</b>；
 *   <li><b>守恒</b>：Σ share.taken == {@code count} == {@code unit.RecruitStaff} 的 roster 增量；三个数字在
 *       Plan 构造期逐值互校，批载荷逐值对应（同一份 sources 同时喂 {@code social.SubmitHouseholdWorkOrder}、{@code
 *       unit.RecruitStaff} 与 {@code sd.PutInfo}）。
 * </ol>
 *
 * <p>★★ <b>目标政府家户前置（缺一 ⇒ plan 级具名拒，不猜、不新建第二户）</b>：目标恒为 {@link GovernmentHouseholds#of(String)} =
 * {@code hh-gov-<unitId>}；必须<b>同时</b>出现在 {@code Unit.households()}（否则该 GOV 单位的家户关系数据坏）与 {@code
 * social.households()}（否则 Social 里没有可落人的目标家户）。缺任一项都不发批、零 revision。
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code social.SubmitHouseholdWorkOrder}（{@code
 * orderId=gov-recruit:<unitId>:<role>:<tick>:<count>} 确定性幂等键；target = 政府家户；逐来源 {@code
 * TRANSFER_MEMBERS(from=来源家户, to=政府家户, lotId, count=taken)}）→ {@code unit.RecruitStaff}（{@code
 * sources} = 逐来源 {@code {kind:"household", id, lotId, count}}）→ {@code sd.PutInfo}（地址 = 单位
 * canonical，key={@code recruit}，value=JSON <b>字符串</b>，含
 * role/count/来源家户/reason/tick，note=人可读摘要）。三条共享同一 batchId 与同一 branch/expectedRevision ⇒ 一条 revision。
 *
 * <p>★★ <b>纯推导校验（前置不满足 ⇒ 工具折 {@code BAD_REQUEST}、零 revision）</b>：单位存在且带 {@link
 * GovernmentFormation}；{@code count ≥ 1}；{@code role} 词表；{@code staffCap[role]} 若存在且 {@code 现有 +
 * count > cap} ⇒ 具名拒（带现有 / 上限 / 请求，不截断）；现有 + count 溢出 long ⇒ 具名拒；无 jurisdiction / 辖区 Region 在地图里查无
 * ⇒ 具名拒；政府家户不在 {@code Unit.households()} 或 {@code social.households()} ⇒ 具名拒；合格来源不足 ⇒ 整条具名拒。
 *
 * <p>★ <b>确定性 / 保序不可变</b>：不碰墙钟（{@code tick} 是状态 meta 的函数）、不用随机量；来源表按辖区顺序 + 家户全序瀑布序， 用 {@link
 * List#copyOf} 冻结；{@code staffCap} 只读不改。
 *
 * <p>★ <b>遗留形状</b>：{@link GroupSource}（整批 count 形状）仍被 {@link GovSelectExamineesPlan} 引用（该工具在 P1.0 已
 * fail-closed，P1.3 再迁移）；本类的 recruit 路径不再构造/消费它，且不再发出 {@code social.SeedGroups}。
 */
final class GovRecruitPlan {

  /** {@code social.SubmitHouseholdWorkOrder} 的命令类型（与 handler 的 {@code TYPE} 同源）。 */
  static final String SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE = SubmitHouseholdWorkOrderHandler.TYPE;

  /** {@code unit.RecruitStaff} 的命令类型（与 {@code RecruitStaffHandler.type()} 同字面；旧档模式才落）。 */
  static final String RECRUIT_STAFF_TYPE = "unit.RecruitStaff";

  /** {@code unit.AssignGovPost} 的命令类型（Z4 岗位户模式落点；只写 posts，绝不写 staff）。 */
  static final String ASSIGN_GOV_POST_TYPE = AssignGovPostHandler.TYPE;

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  private GovRecruitPlan() {}

  /**
   * 纯推导入口（见类注的来源口径与校验清单；Z4/C1 双模式）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 招募主体（必须是带 {@link GovernmentFormation} 的单位）
   * @param roleText 行政角色词表（SCRIBE|YAMEN|POST）
   * @param count 招募人数（≥ 1）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  // ★ 测试/旧路径：全缺省儒略历时钟；生产路径由 CalendarService.clock() 传入。
  static Plan plan(SimulationState state, String unitId, String roleText, long count) {
    return plan(state, unitId, roleText, count, CalendarClock.julianDefault());
  }

  /**
   * 生产入口：历法时钟由调用方传入（本类的年龄档判定只认这台钟）。
   *
   * @param clock 历法时钟（非空；生产路径 = CalendarService.clock()）
   */
  static Plan plan(
      SimulationState state, String unitId, String roleText, long count, CalendarClock clock) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(clock, "clock");
    if (unitId == null || unitId.isBlank()) {
      throw new IllegalArgumentException("unitId 必须是非空文本");
    }
    StaffRole role = parseRole(roleText);
    if (count < 1L) {
      throw new IllegalArgumentException("招募人数 count 必须 ≥ 1: " + count);
    }
    UnitState units = ToolSupport.unitState(state);
    Unit unit = units.units().get(UnitId.parse(unitId));
    if (unit == null) {
      throw new IllegalArgumentException("GOV 单位不存在: " + unitId);
    }
    GovernmentFormation governmentFormation = requireGovernmentFormation(unit, unitId);

    // ★★ Z4/C1：目标家户选择（岗位户优先；没有岗位户且 posts 为空时维持旧档财政户口径）──────────────
    HouseholdId postHousehold = HouseholdId.parse(RaiseUnitPlan.householdIdFor(unitId));
    boolean postHouseholdMode = unit.households().contains(postHousehold);
    if (!postHouseholdMode && governmentFormation.staffIsHouseholdProjection()) {
      throw new IllegalArgumentException(
          "GOV 单位 "
              + unitId
              + " 的 householdPosts 非空但没有岗位户 "
              + postHousehold.value()
              + "：岗位数据坏（posts 的键不可能 ⊆ Unit.households）；先补齐岗位户（Z3 simos.gov.expandHousehold / "
              + "unit.SetUnitHouseholds）再招募（不猜、不静默降级到财政户）");
    }
    HouseholdId targetHousehold =
        postHouseholdMode ? postHousehold : GovernmentHouseholds.of(unitId);

    // ★ staff 只在旧档模式参与：新世界的 staff 是岗位户人口/承诺投影（C4），由 app 现算，不由本工具直写。
    long staffBefore = -1L;
    long staffAfter = -1L;
    Optional<Long> staffCap = Optional.empty();
    if (!postHouseholdMode) {
      staffBefore = governmentFormation.staff().getOrDefault(role, 0L);
      if (staffBefore > Long.MAX_VALUE - count) {
        throw new IllegalArgumentException(
            "招募后 " + role + " 在编人数溢出 long: 现有 " + staffBefore + " + 请求 " + count);
      }
      staffAfter = staffBefore + count;
      staffCap = Optional.ofNullable(governmentFormation.policy().staffCap().get(role));
      if (staffCap.isPresent() && staffBefore > staffCap.get() - count) {
        throw new IllegalArgumentException(
            "招募 "
                + role
                + " "
                + count
                + " 人会超编制上限: 现有 "
                + staffBefore
                + " + 请求 "
                + count
                + " > staffCap "
                + staffCap.get()
                + "（不截断；先 unit.SetGovPolicy 提上限或减少 count）");
      }
    }
    var jurisdiction =
        unit.jurisdiction()
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "单位 " + unitId + " 没有 jurisdiction（无管辖区域）：没有可招募来源；先 unit.SetJurisdiction"));
    if (jurisdiction.taxRatePerMilleByRegion().isEmpty()) {
      throw new IllegalArgumentException(
          "单位 " + unitId + " 的 jurisdiction 为空（无管辖区域）：没有可招募来源；先 unit.SetJurisdiction");
    }
    SocialData social = ToolSupport.socialData(state);
    if (!unit.households().contains(targetHousehold)) {
      throw new IllegalArgumentException(
          "GOV 单位 "
              + unitId
              + " 的 Unit.households 不含招募目标 "
              + targetHousehold.value()
              + (postHouseholdMode
                  ? "（岗位户）：单位家户关系数据坏；先 unit.SetUnitHouseholds 把它编入（GOV 单位须保留政府家户）"
                  : "（政府家户）：单位家户关系数据坏；先 unit.SetGovFormation / 修数（不猜、不新建第二户）"));
    }
    if (!social.households().containsKey(targetHousehold)) {
      throw new IllegalArgumentException(
          "Social 里不存在招募目标家户 "
              + targetHousehold.value()
              + (postHouseholdMode
                  ? "（岗位户；先由 Z3 simos.gov.expandHousehold / Z5 bootstrap 建户，或对齐 Unit.households）"
                  : "（GOV 单位 " + unitId + " 的旧档政府家户；先补该政府家户再招募）")
              + "（不猜、不新建第二户）");
    }
    GameMap map = ToolSupport.gameMap(state);
    long tick = state.meta().timestamp().tick();
    List<Set<HexCoord>> jurisdictionHexesInOrder = new ArrayList<>();
    for (RegionId regionId : jurisdiction.taxRatePerMilleByRegion().keySet()) {
      Region region = map.regions().get(regionId);
      if (region == null) {
        throw new IllegalArgumentException(
            "辖区区域在地图里查无: "
                + regionId.value()
                + "（单位 "
                + unitId
                + " 的 jurisdiction 指向了一个已不存在的 Region；先 map.CreateRegion 或调整管辖）");
      }
      jurisdictionHexesInOrder.add(region.hexes());
    }
    HouseholdManpowerAllocator.Allocation allocation;
    try {
      // ★ 排除目标家户：本批把它当目标，不得再作为来源（from == to 会被 HouseholdBook 具名拒）。
      //   岗位户模式下同时排除财政户 hh-gov（它已是 UNIT 位置、通常不在辖区 hex 上；显式排除防边界情形）。
      Set<HouseholdId> excluded =
          postHouseholdMode
              ? Set.of(targetHousehold, GovernmentHouseholds.of(unitId))
              : Set.of(targetHousehold);
      allocation =
          HouseholdManpowerAllocator.allocateMalesOfAdult(
              social, jurisdictionHexesInOrder, count, clock, tick, excluded);
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("人力总量不足")) {
        throw new IllegalArgumentException("招募来源不足：" + e.getMessage(), e);
      }
      throw e;
    }
    return new Plan(
        postHouseholdMode,
        unitId,
        role,
        count,
        tick,
        staffBefore,
        staffAfter,
        staffCap,
        allocation.available(),
        targetHousehold,
        allocation.shares());
  }

  /** 角色词表：只认 SCRIBE|YAMEN|POST，别的词给具名拒（不静默当缺省）。 */
  private static StaffRole parseRole(String roleText) {
    if (roleText == null || roleText.isBlank()) {
      throw new IllegalArgumentException("role 必须是非空文本（SCRIBE|YAMEN|POST）");
    }
    try {
      return StaffRole.valueOf(roleText);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("role 不是合法角色（SCRIBE|YAMEN|POST）: " + roleText, e);
    }
  }

  /** 单位必须带 {@link GovernmentFormation}（招募命令的领域前置；消息给出下一步）。 */
  private static GovernmentFormation requireGovernmentFormation(Unit unit, String unitId) {
    if (unit.module().orElse(null) instanceof GovernmentFormation governmentFormation) {
      return governmentFormation;
    }
    throw new IllegalArgumentException(
        "单位 " + unitId + " 没有 GovernmentFormation：招募只对 GOV 单位；先 unit.SetGovFormation");
  }

  /** 饱和加法（非负 long；溢出取 {@link Long#MAX_VALUE}）——只用于守恒合计与拒因展示，不参与逐值扣减。 */
  private static long saturatedAdd(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  /**
   * 一份招募计划（全部字段是状态的纯函数；来源表在构造期冻结）。
   *
   * @param postHouseholdMode true = Z4 新世界岗位户模式（目标 = {@code hh-unit:<unitId>}，落 {@code
   *     unit.AssignGovPost}）； false = 旧档财政户模式（目标 = {@code hh-gov-<unitId>}，保留 {@code
   *     unit.RecruitStaff} 旧行为）
   * @param unitId 招募主体
   * @param role 行政角色
   * @param count 招募人数（= Σ来源 share.taken；旧档另 = roster 增量）
   * @param tick 推导时的世界日（年龄现算、工单幂等键与行动记录用）
   * @param staffBefore 旧档该角色现有在编；岗位户模式 = −1（staff 是投影，C4）
   * @param staffAfter 旧档该角色招募后在编（= staffBefore + count）；岗位户模式 = −1
   * @param staffCap 旧档该角色的编制上限（不存在 = 不设限）；岗位户模式 = empty
   * @param available 全部辖区合格家户份额合计（不足拒因用；成功时也随视图返回）
   * @param targetHouseholdId 招募目标家户（岗位户 {@code hh-unit:<unitId>} 或旧档政府家户 {@code hh-gov-<unitId>}）
   * @param sources 逐来源家户份额（辖区顺序 + 家户全序瀑布序；Σtaken == count）
   */
  record Plan(
      boolean postHouseholdMode,
      String unitId,
      StaffRole role,
      long count,
      long tick,
      long staffBefore,
      long staffAfter,
      Optional<Long> staffCap,
      long available,
      HouseholdId targetHouseholdId,
      List<HouseholdManpowerAllocator.ManpowerShare> sources) {

    Plan {
      if (unitId == null || unitId.isBlank()) {
        throw new IllegalArgumentException("unitId 不得为空白");
      }
      Objects.requireNonNull(role, "role");
      if (count < 1L) {
        throw new IllegalArgumentException("count 必须 ≥ 1: " + count);
      }
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      Objects.requireNonNull(staffCap, "staffCap");
      if (postHouseholdMode) {
        if (staffBefore != -1L || staffAfter != -1L || staffCap.isPresent()) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：岗位户模式不得带 legacy staff 数字（"
                  + staffBefore
                  + "→"
                  + staffAfter
                  + " cap="
                  + staffCap
                  + "）");
        }
      } else {
        if (staffBefore < 0L) {
          throw new IllegalArgumentException("staffBefore 不得为负: " + staffBefore);
        }
        if (staffAfter != staffBefore + count) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：staffAfter="
                  + staffAfter
                  + " != staffBefore+count="
                  + (staffBefore + count));
        }
        staffCap.ifPresent(
            cap -> {
              if (cap < staffAfter) {
                throw new IllegalArgumentException(
                    "内部分摊不自洽：staffCap=" + cap + " < staffAfter=" + staffAfter);
              }
            });
      }
      if (available < 0L) {
        throw new IllegalArgumentException("available 不得为负: " + available);
      }
      Objects.requireNonNull(targetHouseholdId, "targetHouseholdId");
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      long total = 0L;
      for (HouseholdManpowerAllocator.ManpowerShare share : sources) {
        if (share.householdId().equals(targetHouseholdId)) {
          throw new IllegalArgumentException(
              "内部分摊不自洽：来源家户不得是招募目标 " + targetHouseholdId.value() + "（自我转移会被域层拒）");
        }
        total = saturatedAdd(total, share.taken());
      }
      if (total != count) {
        throw new IllegalArgumentException(
            "守恒破坏：Σ来源 share.taken=" + total + " != count=" + count + "（批载荷必须逐值对应）");
      }
      if (available < count) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：available=" + available + " < count=" + count + "（不足应在选人层整条拒）");
      }
    }

    /** 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      return postHouseholdMode
          ? List.of(SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE, ASSIGN_GOV_POST_TYPE, PUT_INFO_TYPE)
          : List.of(SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE, RECRUIT_STAFF_TYPE, PUT_INFO_TYPE);
    }

    /**
     * 工单确定性幂等键：{@code gov-recruit:<unitId>:<role>:<tick>:<count>}（两模式同形；同一 GOV 同一 tick 只有一个模式）。
     * 同一批参数在同一 tick 重放 ⇒ 命中幂等键、整单具名拒，不重复改人口。
     */
    String orderId() {
      return "gov-recruit:" + unitId + ":" + role.name() + ":" + tick + ":" + count;
    }

    /**
     * {@code social.SubmitHouseholdWorkOrder} 载荷：target = 招募目标家户（岗位户或旧档财政户）；逐来源一条 {@code
     * TRANSFER_MEMBERS(from=来源家户, to=目标, lotId, count=taken)}；{@code orderId} = {@link
     * #orderId()}，{@code source.module="gov"}，reason = 工具 reason。
     */
    String submitHouseholdWorkOrderPayloadJson(String reason) {
      requireReason(reason);
      List<Map<String, Object>> steps = new ArrayList<>(sources.size());
      for (HouseholdManpowerAllocator.ManpowerShare share : sources) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("op", "TRANSFER_MEMBERS");
        step.put("from", share.householdId().value());
        step.put("to", targetHouseholdId.value());
        step.put("lotId", share.lotId().value());
        step.put("count", share.taken());
        steps.add(step);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("orderId", orderId());
      payload.put("target", targetHouseholdId.value());
      payload.put("reason", reason);
      payload.put("source", Map.of("module", "gov"));
      payload.put("plan", steps);
      return ToolSupport.json(payload);
    }

    /**
     * {@code unit.AssignGovPost} 载荷（仅岗位户模式合法）：{@code {unitId, household, role}}；只写 {@code
     * householdPosts}，<b>绝不写 staff</b>（C4）。
     */
    String assignGovPostPayloadJson() {
      if (!postHouseholdMode) {
        throw new IllegalStateException("批不自洽：旧档财政户模式不得组装 unit.AssignGovPost 载荷");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", unitId);
      payload.put("household", targetHouseholdId.value());
      payload.put("role", role.name());
      return ToolSupport.json(payload);
    }

    /**
     * {@code unit.RecruitStaff} 载荷（仅旧档财政户模式合法）：{@code {unitId, role, count, sources}}；{@code
     * sources} = 逐来源 {@code {kind:"household", id:householdId, lotId, count:taken}}，与 {@link
     * #sources} 逐值对应（命令本身只入编、 不扣人；扣人在同批 {@code social.SubmitHouseholdWorkOrder}）。
     */
    String recruitStaffPayloadJson() {
      if (postHouseholdMode) {
        throw new IllegalStateException("批不自洽：岗位户模式（staff 是投影）不得组装 unit.RecruitStaff 载荷");
      }
      List<Map<String, Object>> rows = new ArrayList<>(sources.size());
      for (HouseholdManpowerAllocator.ManpowerShare share : sources) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "household");
        row.put("id", share.householdId().value());
        row.put("lotId", share.lotId().value());
        row.put("count", share.taken());
        rows.add(row);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", unitId);
      payload.put("role", role.name());
      payload.put("count", count);
      payload.put("sources", rows);
      return ToolSupport.json(payload);
    }

    /**
     * {@code sd.PutInfo} 的 {@code value}（JSON <b>字符串</b>；含
     * mode/target/role/count/来源家户份额/reason/tick； 旧档另含 staff 前后）。
     */
    String infoValueJson(String reason) {
      requireReason(reason);
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("mode", postHouseholdMode ? "post-household" : "treasury-household-legacy");
      value.put("unitId", unitId);
      value.put("role", role.name());
      value.put("count", count);
      value.put("tick", tick);
      if (!postHouseholdMode) {
        value.put("staffBefore", staffBefore);
        value.put("staffAfter", staffAfter);
      }
      value.put("targetHouseholdId", targetHouseholdId.value());
      if (!postHouseholdMode) {
        // ★ 旧档键名兼容：目标 = hh-gov-<unitId>。
        value.put("governmentHouseholdId", targetHouseholdId.value());
      }
      value.put("available", available);
      value.put("sourceCount", sources.size());
      value.put("sources", sourcesView());
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireReason(reason);
      return "招募 "
          + unitId
          + " 的 "
          + role
          + " "
          + count
          + " 人（tick "
          + tick
          + "，"
          + (postHouseholdMode ? "岗位户" : "旧档财政户")
          + "）：来源家户份额 "
          + sources.size()
          + " 条（辖区顺序瀑布 → "
          + targetHouseholdId.value()
          + "）"
          + (postHouseholdMode
              ? "，落岗位指派（unit.AssignGovPost，不写 staff）"
              : "，在编 "
                  + staffBefore
                  + "→"
                  + staffAfter
                  + (staffCap.isPresent() ? "（上限 " + staffCap.get() + "）" : ""))
          + "；reason="
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
        row.put("hex", ToolSupport.hexCoord(share.hex()));
        rows.add(row);
      }
      return rows;
    }

    private static void requireReason(String reason) {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason 必须是非空文本");
      }
    }
  }

  /**
   * ★ <b>遗留的整批 count 形状</b>：仍被 {@link GovSelectExamineesPlan}（P1.0 fail-closed 的历史批）引用；recruit 新路径
   * 不再使用。等 P1.3 把科举迁移到 {@link HouseholdManpowerAllocator.ManpowerShare} 后可删。
   *
   * <p>一个被动批次：整组覆盖用的原始批次 + 它的来源格（S2：位置来自家户）+ 抽走的人数；{@code countAfter} 可为 0。
   */
  record GroupSource(PopulationGroup group, HexCoord at, long taken) {

    GroupSource {
      Objects.requireNonNull(group, "group");
      Objects.requireNonNull(at, "at");
      if (taken <= 0L) {
        throw new IllegalArgumentException("taken 必须 > 0: " + taken);
      }
      if (taken > group.count()) {
        throw new IllegalArgumentException(
            "taken 不得超过批次人数: taken=" + taken + "，count=" + group.count());
      }
    }

    static GroupSource from(RegionAllocations.GroupSource source) {
      return new GroupSource(source.group(), source.at(), source.taken());
    }

    long countAfter() {
      return group.count() - taken;
    }
  }
}

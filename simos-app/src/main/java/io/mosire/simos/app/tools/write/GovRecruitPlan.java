package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.gov.recruit} 的<b>纯推导</b>（阶段 10b-ii，2026-10-01 GOV/Army 计划 §2.2/§2.6）：从一份 {@link
 * SimulationState} 与参数算出 {@link Plan}——<b>不碰 {@link io.mosire.agentlib.tool.ToolContext}、不碰 {@code
 * CoreSimos}</b>， preview 与 apply 因此共用同一份语义（工具只负责读态、把 Plan 折成视图、组批、折叠结局）。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：人在 {@code social} 切片、编制在 {@code unit} 切片、行动记录在 {@code sd}
 * 切片；单条命令只能落一个命名空间。本工具走 {@link io.mosire.simos.core.CoreSimos#submitBatch}（同 branch + 同
 * expectedRevision ⇒ 一批 = 一条 revision，原子）。
 *
 * <p>★★ <b>来源口径 = 辖区社会批次的跨 Region 瀑布</b>：
 *
 * <ol>
 *   <li><b>辖区顺序</b>：{@code Unit.jurisdiction.taxRatePerMilleByRegion} 的 key 插入序（{@code
 *       Jurisdiction} 构造期用 {@code LinkedHashMap} 保序冻结）；<b>逐个 Region 分配直到满额</b>；
 *   <li><b>Region 内口径与 levy / raiseUnit 逐字同源</b>：<b>只调</b> {@link
 *       RegionAllocations#allocateManpower}（同一份 MALE + {@link
 *       io.mosire.simos.social.population.AgeBracket#ADULT}、count 降序 / id 升序瀑布）， 本类不另写排序、过滤或扣减；
 *   <li><b>不足 ⇒ 整条拒</b>：先扫全部辖区 Region 得到 available 合计；railing 不足时抛具名 {@link
 *       IllegalArgumentException}（带 requested / available / 缺口），<b>不部分抽取、不截断</b>；
 *   <li><b>守恒</b>：Σ来源扣人 == {@code count} == {@code unit.RecruitStaff} 的 roster 增量；三个数字在 Plan
 *       构造期逐值互校，批载荷逐值对应（同一份 sources 同时喂 {@code social.SeedGroups}、{@code unit.RecruitStaff} 与
 *       {@code sd.PutInfo}）。
 * </ol>
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code social.SeedGroups}（逐批整组覆盖，{@code count=扣后} 可为 0，带 {@code
 * ageDays/anchorTick/stress} 保真）→ {@code unit.RecruitStaff}（{@code sources} = 逐来源 {@code
 * {kind:"social_group", id, count}}）→ {@code sd.PutInfo}（地址 = 单位 canonical，key={@code
 * recruit}，value=JSON <b>字符串</b>，含 role/count/来源计数/reason/tick，note=人可读摘要）。三条共享同一 batchId 与同一
 * branch/expectedRevision ⇒ 一条 revision。
 *
 * <p>★★ <b>纯推导校验（前置不满足 ⇒ 工具折 {@code BAD_REQUEST}、零 revision）</b>：单位存在且带 {@link GovernmentFormation}；
 * {@code count ≥ 1}；{@code role} 词表；{@code staffCap[role]} 若存在且 {@code 现有 + count > cap} ⇒ 具名拒（带现有
 * / 上限 / 请求，不截断）；现有 + count 溢出 long ⇒ 具名拒；无 jurisdiction / 辖区 Region 在地图里查无 ⇒ 具名拒。
 *
 * <p>★ <b>确定性 / 保序不可变</b>：不碰墙钟（{@code tick} 是状态 meta 的函数）、不用随机量；来源表按辖区顺序 + Region 内瀑布序， 用 {@link
 * List#copyOf} 冻结；{@code staffCap} 只读不改。
 */
final class GovRecruitPlan {

  /** {@code social.SeedGroups} 的命令类型（与 {@code SeedGroupsHandler.type()} 同字面）。 */
  static final String SEED_GROUPS_TYPE = "social.SeedGroups";

  /** {@code unit.RecruitStaff} 的命令类型（与 {@code RecruitStaffHandler.type()} 同字面）。 */
  static final String RECRUIT_STAFF_TYPE = "unit.RecruitStaff";

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  private GovRecruitPlan() {}

  /**
   * 纯推导入口（见类注的来源口径与校验清单）。
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
    long staffBefore = governmentFormation.staff().getOrDefault(role, 0L);
    if (staffBefore > Long.MAX_VALUE - count) {
      throw new IllegalArgumentException(
          "招募后 " + role + " 在编人数溢出 long: 现有 " + staffBefore + " + 请求 " + count);
    }
    long staffAfter = staffBefore + count;
    Optional<Long> staffCap = Optional.ofNullable(governmentFormation.policy().staffCap().get(role));
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
    long tick = state.meta().timestamp().tick();
    SocialData social = ToolSupport.socialData(state);
    GameMap map = ToolSupport.gameMap(state);
    List<GroupSource> sources = new ArrayList<>();
    long remaining = count;
    long available = 0L;
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
      long regionAvailable = regionAvailability(social, region, tick, clock);
      available = saturatedAdd(available, regionAvailable);
      if (remaining == 0L || regionAvailable == 0L) {
        continue;
      }
      long take = Math.min(remaining, regionAvailable);
      RegionAllocations.ManpowerAllocation allocation =
          RegionAllocations.allocateManpower(social, region, tick, take, clock);
      sources.addAll(allocation.sources().stream().map(GroupSource::from).toList());
      remaining -= take;
    }
    if (remaining != 0L) {
      throw new IllegalArgumentException(
          "招募来源不足：requested="
              + count
              + "，available="
              + available
              + "，缺口="
              + (count > available ? count - available : remaining)
              + "（不部分抽取、不截断；先扩管辖 / 等人口长大或降低 count）");
    }
    return new Plan(
        unitId, role, count, tick, staffBefore, staffAfter, staffCap, available, sources);
  }

  /**
   * ★ <b>一个辖 Region 的合格人力总量</b>：调 {@link RegionAllocations#allocateManpower}、{@code
   * requested=1}——成功时它返回 的 {@code available} 是<b>该 Region 全部</b>合格批次人数之和（"available = 全部合格来源"是那份
   * API 的口径）。
   *
   * <p>★★ {@code requested=1} 且其余入参合法时，唯一的 {@link IllegalArgumentException} 是"人力总量不足"（合格来源 0 人）； 其它
   * IAE（如未来锚点导致年龄为负的坏数据）必须原样重抛，<b>不静默当 0</b>——被吞掉的异常会把"数据坏了"伪装成"这里没人"。
   */
  private static long regionAvailability(
      SocialData social, Region region, long tick, CalendarClock clock) {
    try {
      return RegionAllocations.allocateManpower(social, region, tick, 1L, clock).available();
    } catch (IllegalArgumentException e) {
      if (e.getMessage() != null && e.getMessage().startsWith("人力总量不足")) {
        return 0L;
      }
      throw e;
    }
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

  /** 饱和加法（非负 long；溢出取 {@link Long#MAX_VALUE}）——只用于 available 合计与拒因展示，不参与逐值扣减。 */
  private static long saturatedAdd(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  /**
   * 一份招募计划（全部字段是状态的纯函数；来源表在构造期冻结）。
   *
   * @param unitId 招募主体
   * @param role 行政角色
   * @param count 招募人数（= roster 增量 = Σ来源扣人）
   * @param tick 推导时的世界日（年龄现算与行动记录用）
   * @param staffBefore 该角色现有在编
   * @param staffAfter 该角色招募后在编（= staffBefore + count）
   * @param staffCap 该角色的编制上限（不存在 = 不设限）
   * @param available 全部辖区 Region 的合格来源合计（不足拒因用；成功时也随视图返回）
   * @param sources 逐来源（辖区顺序 + Region 内瀑布序；Σtaken == count）
   */
  record Plan(
      String unitId,
      StaffRole role,
      long count,
      long tick,
      long staffBefore,
      long staffAfter,
      Optional<Long> staffCap,
      long available,
      List<GroupSource> sources) {

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
      if (staffBefore < 0L) {
        throw new IllegalArgumentException("staffBefore 不得为负: " + staffBefore);
      }
      if (staffAfter != staffBefore + count) {
        throw new IllegalArgumentException(
            "内部分摊不自洽：staffAfter=" + staffAfter + " != staffBefore+count=" + (staffBefore + count));
      }
      if (available < 0L) {
        throw new IllegalArgumentException("available 不得为负: " + available);
      }
      Objects.requireNonNull(staffCap, "staffCap");
      staffCap.ifPresent(
          cap -> {
            if (cap < staffAfter) {
              throw new IllegalArgumentException(
                  "内部分摊不自洽：staffCap=" + cap + " < staffAfter=" + staffAfter);
            }
          });
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
      long total = 0L;
      Set<String> seenGroupIds = new LinkedHashSet<>();
      for (GroupSource source : sources) {
        // ★ Region hex 重叠时同一社会批次会被两个 Region 各选中一次：两条 SeedGroups 会互相覆盖（第二腿按原始
        //   count 重算），守恒在批内静默破坏 ⇒ 具名拒（fail-closed，不产出会打架的批）。
        if (!seenGroupIds.add(source.group().id().value())) {
          throw new IllegalArgumentException(
              "同一社会批次被多个 Region 选中（辖区 Region hex 重叠）: "
                  + source.group().id().value()
                  + "（先修管辖区域，避免同一批人被扣两次）");
        }
        total = saturatedAdd(total, source.taken());
      }
      if (total != count) {
        throw new IllegalArgumentException(
            "守恒破坏：Σ来源扣人=" + total + " != count=" + count + "（批载荷必须逐值对应）");
      }
    }

    /** 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      return List.of(SEED_GROUPS_TYPE, RECRUIT_STAFF_TYPE, PUT_INFO_TYPE);
    }

    /**
     * {@code social.SeedGroups} 载荷：每个被动批次一条<b>整组覆盖</b>，必须带原 {@code ageDays}/{@code anchorTick} 与
     * {@code stress} 保真（否则重写会把压力静默清零）；{@code count} 取扣后、可为 0。
     */
    String seedGroupsPayloadJson() {
      List<Map<String, Object>> entries = new ArrayList<>(sources.size());
      for (GroupSource source : sources) {
        PopulationGroup group = source.group();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", group.id().value());
        entry.put("q", source.at().q());
        entry.put("r", source.at().r());
        entry.put("sex", group.sex().name());
        entry.put("count", source.countAfter());
        // ★ 保真三件：锚点年龄 / 锚点 tick / 生理压力——整组覆盖不重新解释这批人。
        entry.put("ageDays", group.ageAtAnchorDays());
        entry.put("anchorTick", group.anchorTick());
        entry.put("stress", group.physiologicalStress());
        entries.add(entry);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("entries", entries);
      return ToolSupport.json(payload);
    }

    /**
     * {@code unit.RecruitStaff} 载荷：{@code {unitId, role, count, sources}}；{@code sources} = 逐来源
     * {@code {kind:"social_group", id, count}}，与 {@link #sources} 逐值对应（命令本身只入编、不扣人；扣人在同批 {@code
     * social.SeedGroups}）。
     */
    String recruitStaffPayloadJson() {
      List<Map<String, Object>> rows = new ArrayList<>(sources.size());
      for (GroupSource source : sources) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", "social_group");
        row.put("id", source.group().id().value());
        row.put("count", source.taken());
        rows.add(row);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", unitId);
      payload.put("role", role.name());
      payload.put("count", count);
      payload.put("sources", rows);
      return ToolSupport.json(payload);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON <b>字符串</b>；含 role/count/来源计数/逐来源/reason/tick）。 */
    String infoValueJson(String reason) {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason 必须是非空文本");
      }
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("unitId", unitId);
      value.put("role", role.name());
      value.put("count", count);
      value.put("tick", tick);
      value.put("staffBefore", staffBefore);
      value.put("staffAfter", staffAfter);
      value.put("available", available);
      value.put("sourceCount", sources.size());
      List<Map<String, Object>> rows = new ArrayList<>(sources.size());
      for (GroupSource source : sources) {
        PopulationGroup group = source.group();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", group.id().value());
        row.put("q", source.at().q());
        row.put("r", source.at().r());
        row.put("before", group.count());
        row.put("taken", source.taken());
        row.put("after", source.countAfter());
        rows.add(row);
      }
      value.put("sources", rows);
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason 必须是非空文本");
      }
      return "招募 "
          + unitId
          + " 的 "
          + role
          + " "
          + count
          + " 人（tick "
          + tick
          + "）：来源批次 "
          + sources.size()
          + "（辖区顺序瀑布），在编 "
          + staffBefore
          + "→"
          + staffAfter
          + (staffCap.isPresent() ? "（上限 " + staffCap.get() + "）" : "")
          + "；reason="
          + reason;
    }

    /** 逐来源视图（工具结果用；保序）。 */
    List<Map<String, Object>> sourcesView() {
      List<Map<String, Object>> rows = new ArrayList<>(sources.size());
      for (GroupSource source : sources) {
        PopulationGroup group = source.group();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", group.id().value());
        row.put("q", source.at().q());
        row.put("r", source.at().r());
        row.put("before", group.count());
        row.put("taken", source.taken());
        row.put("after", source.countAfter());
        rows.add(row);
      }
      return rows;
    }
  }

  /** 一个被动批次：整组覆盖用的原始批次 + 它的来源格（S2：位置来自家户）+ 抽走的人数；{@code countAfter} 可为 0。 */
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

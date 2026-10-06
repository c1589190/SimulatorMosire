package io.mosire.simos.app.tools.write;

import io.mosire.simos.actor.spi.EnsureHouseholdAccountHandler;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.economy.spi.EconomyRegisterHouseholdHandler;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.spi.SubmitHouseholdWorkOrderHandler;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ {@code simos.unit.spawnArmy} 的<b>纯推导</b>（P5，2026-10-01 后端 + MCP 稳定化计划）：从一份 {@link
 * SimulationState} 与参数算出 {@link Plan}——<b>不碰 {@link io.mosire.agentlib.tool.ToolContext}、不碰 {@code
 * CoreSimos}</b>，preview 与 apply 因此共用同一份语义（工具只负责读态、把 Plan 折成视图、组批、折叠结局）。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：新单位在 {@code unit} 切片、Army 归属在 {@code sd} 切片，单条命令只能落一个
 * 命名空间。本工具走 {@link io.mosire.simos.core.CoreSimos#submitBatch}（同 branch + 同 expectedRevision ⇒ 一批 =
 * 一条 revision，原子）。
 *
 * <p>★★ <b>批顺序（固定七步，可复现；{@code role} 缺席时只有六步）</b>：{@code social.SubmitHouseholdWorkOrder}（建人口家户 +
 * 凭空加成年男丁）→ {@code unit.CreateUnit}（{@code households=[人口家户]}， 不携带 {@code manpower}）→（{@code role}
 * 非空才落）{@code unit.SetArmyFormation} → {@code sd.CreateArmy} → {@code economy.RegisterHousehold} →
 * {@code actor.EnsureHouseholdAccount} → {@code sd.PutInfo}。后几条都看得见前一条累积后的候选态，故 {@code
 * sd.CreateArmy} 的 {@code rootUnitId} 就是同批刚创建的 root 单位，{@code sd.CreateArmy} 之前的家户也已由 Social 权威建好。
 *
 * <p>★★ <b>造人走 Social 权威（用户 2026-10-19 裁定）</b>：GM 可以直接建军、可以凭空造人，但人口加口必须经 {@code
 * social.SubmitHouseholdWorkOrder} 的 {@code ADD_MEMBERS} 落进 Social 家户——{@code member} 是 GM 授权下 经
 * Social 家户工单创建的成年男丁数（MALE、20 岁），<b>新单位人口不再走 {@code unit.CreateUnit(manpower=...)} 的旧口径</b>；
 * 新单位的人力表只作视图口径单条 {@code {type:"士兵", amount=member}}，人口权威在家户。
 *
 * <p>★★ <b>GM 特权口径</b>：直接建军<b>不抽地方人口、不抽粮饷</b>（用户 2026-10-01 裁定 6）；允许无粮无钱、无国库，人口由 Social
 * 工单凭空创建；{@code member} 仍须 ≥ 1（P5 计划明文）。{@code raiseUnit} 保持抽取语义，一个字不动。
 *
 * <p>★★ <b>{@code masterGov} 的双边语义</b>：{@code masterGov} 给了 ⇒ 必须存在且带 {@link
 * GovernmentFormation}；同批写入 {@code sd.CreateArmy.masterGovUnitId}。{@code role} 非空 ⇒ 同批再落 {@code
 * unit.SetArmyFormation}，并把同一个 {@code masterGov} 写进它的 {@code masterGov} 字段。{@code role} 为空但 {@code
 * masterGov} 给了 ⇒ <b>只写 sd 侧</b>（unit 侧不落 ArmyFormation，也就不设 {@code
 * ArmyFormation.masterGov}）——preview 由 {@link Plan#armyFormationNote()} 明确说明。
 *
 * <p>★★ <b>纯推导校验（前置不满足 ⇒ 工具折 {@code BAD_REQUEST}、零 revision）</b>：{@code unitId}/{@code name}/
 * {@code armyId} 非空白；{@code member ≥ 1}；{@code speed ≥ 1}；{@code mobilityPerMille ∈
 * [1,1000]}；{@code equipment} 键非空、值 ≥ 0；{@code unitId} 不得与既有单位重复；{@code armyId} 不得与既有 Army
 * 重复；{@code (q,r)} 必须存在于当前 {@link GameMap}；{@code parent} 若给必须存在且当刻有效位置与落点同格；{@code masterGov}
 * 若给必须存在且带 {@link GovernmentFormation}；{@code hh-unit:<unitId>} 家户 id 若已被占用 ⇒ 具名拒（人口家户 id 不得复用）。
 * 批内域层拒（如同一 tick 的第二次改编、{@code ADD_MEMBERS} 批次 id 冲突）由 {@code submitBatch} 整条拒，逐条真拒因折成 {@code
 * REJECTED}。
 *
 * <p>★ <b>确定性 / 保序不可变</b>：不碰墙钟（{@code tick} 是状态 meta 的函数）、不用随机量；{@code equipment} 用 {@link
 * LinkedHashMap} 输入序 + 赋值处 {@code List.copyOf}（<b>不用</b> {@code Map.copyOf}——它不承诺保序）。
 */
final class SpawnArmyPlan {

  /** {@code unit.CreateUnit} 的命令类型（字面量与 {@code CreateUnitHandler.type()} 同源）。 */
  static final String CREATE_UNIT_TYPE = "unit.CreateUnit";

  /** {@code unit.SetArmyFormation} 的命令类型（仅 {@code role} 非空才落）。 */
  static final String SET_ARMY_FORMATION_TYPE = "unit.SetArmyFormation";

  /** {@code sd.CreateArmy} 的命令类型。 */
  static final String CREATE_ARMY_TYPE = "sd.CreateArmy";

  /** 人口腿统一走 Social 家户工单（引用 social handler 常量，本类不另抄字面量）。 */
  static final String SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE = SubmitHouseholdWorkOrderHandler.TYPE;

  /** 新人口家户的 economy 经济行登记命令（引用 economy handler 常量，本类不另抄字面量）。 */
  static final String REGISTER_HOUSEHOLD_TYPE = EconomyRegisterHouseholdHandler.TYPE;

  /** 新人口家户的 actor 零余额账户命令（引用 actor handler 常量，本类不另抄字面量）。 */
  static final String ENSURE_HOUSEHOLD_ACCOUNT_TYPE = EnsureHouseholdAccountHandler.TYPE;

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 新单位速度缺省值（P5 计划：{@code speed} 可选，缺省 3；域层要求 ≥ 1）。 */
  static final int DEFAULT_SPEED = 3;

  /** 新单位机动性缺省值（P5 计划：{@code mobilityPerMille} 可选，缺省 1000；域层要求 [1,1000]）。 */
  static final int DEFAULT_MOBILITY_PER_MILLE = 1000;

  /** 新单位状态缺省值（P5 计划：{@code status} 可选，缺省 {@code RESTING}）。 */
  static final UnitStatus DEFAULT_STATUS = UnitStatus.RESTING;

  /**
   * GM 造人的锚点年龄（天）：{@code 20 * 365 = 7300}，即 {@code ADD_MEMBERS} 凭空加的成年男丁在锚点 tick 的年龄。
   *
   * <p>★ 文档 §2 明文；本批不扩 params 细分（MALE + 成年档默认）。
   */
  static final long POPULATION_AGE_AT_ANCHOR_DAYS = 20L * 365L;

  /**
   * 新单位人力表的 type 字面量（D3a：{@code member} 这个单一人数在命令载荷里已变成有序条目表；本工具取自然语义 {@code "士兵"}）。
   *
   * <p>★ 本工具输入仍是单一 {@code member}（GM 直接建军的人数），故只能落一条同 type 的条目；需要任意多类型人力时走 {@code
   * simos.unit.create} 窄工具或 {@code simos.army.formatUnit}。是否给 type 可配参数**本阶段未裁定**，不发明。
   */
  static final String DEFAULT_MANPOWER_TYPE = "士兵";

  private SpawnArmyPlan() {}

  /**
   * 纯推导入口（见类注的批顺序与校验清单）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 新 root 单位 id（不得与既有单位重复）
   * @param name 新单位名（非空白）
   * @param at 新单位落点（工具层的 {@code q}/{@code r}；必须是当前 GameMap 上的格）
   * @param member 新单位人口家户经 Social 工单凭空创建的成年男丁数（≥ 1；GM 授权下允许凭空加口）
   * @param equipment 新单位装备（可空表；键非空白、值 ≥ 0）
   * @param speed 新单位速度（≥ 1）
   * @param mobilityPerMille 新单位机动性（[1,1000]）
   * @param parent 父单位 id（可选；给了必须存在且当刻有效位置与 {@code at} 同格）
   * @param status 新单位状态（工具层已折；缺省 {@link #DEFAULT_STATUS}）
   * @param armyId Army id（工具层已给缺省；不得与既有 Army 重复）
   * @param role 兵种/职责短名（可选；给了才同批落 {@code unit.SetArmyFormation}）
   * @param masterGov 认领的 GOV 单位 id（可选；给了必须存在且带 {@link GovernmentFormation}）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  static Plan plan(
      SimulationState state,
      String unitId,
      String name,
      HexCoord at,
      int member,
      Map<String, Integer> equipment,
      int speed,
      int mobilityPerMille,
      Optional<String> parent,
      UnitStatus status,
      String armyId,
      Optional<String> role,
      Optional<String> masterGov) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(equipment, "equipment");
    Objects.requireNonNull(parent, "parent");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(masterGov, "masterGov");
    requireNonBlank(unitId, "unitId");
    requireNonBlank(name, "name");
    requireNonBlank(armyId, "armyId");
    if (member < 1) {
      throw new IllegalArgumentException("member 必须 ≥ 1: " + member);
    }
    if (speed < 1) {
      throw new IllegalArgumentException("speed 必须 ≥ 1（unit.CreateUnit 领域约束）: " + speed);
    }
    if (mobilityPerMille < 1 || mobilityPerMille > 1000) {
      throw new IllegalArgumentException(
          "mobilityPerMille 必须在 [1,1000]（unit.CreateUnit 领域约束 + 千分上限）: " + mobilityPerMille);
    }
    requireEquipment(equipment);
    // ★ 可选文本参数若给了，先完成基本非空形状校验（与既有路径同一套 requireNonBlank）。
    parent.ifPresent(value -> requireNonBlank(value, "parent"));
    role.ifPresent(value -> requireNonBlank(value, "role"));
    masterGov.ifPresent(value -> requireNonBlank(value, "masterGov"));

    UnitState units = ToolSupport.unitState(state);
    UnitId id = UnitId.parse(unitId);
    if (units.units().containsKey(id)) {
      throw new IllegalArgumentException("单位 id 已存在（unitId 不得复用）: " + unitId);
    }
    GameMap map = ToolSupport.gameMap(state);
    if (!map.hexes().containsKey(at)) {
      throw new IllegalArgumentException(
          "hex " + hexText(at) + " 不在当前 GameMap 上（先 map.CreateRegion / 地图编辑纳入该格）");
    }
    SimosTimestamp timestamp = state.meta().timestamp();
    parent.ifPresent(parentId -> requireParentAt(units, parentId, at, timestamp));
    ArmyId army = ArmyId.parse(armyId);
    if (ToolSupport.sdState(state).armies().containsKey(army)) {
      throw new IllegalArgumentException("军队 id 已存在（armyId 不得复用）: " + armyId);
    }
    masterGov.ifPresent(govId -> requireGovUnit(units, govId, "masterGov"));

    // ★★ 人口家户 id 固定为 hh-unit:<unitId>（唯一拼写点复用 RaiseUnitPlan.householdIdFor，不另写字符串拼接）；
    //   已被占用 ⇒ 具名拒（人口家户 id 不得复用，否则 CreateUnit 的 households 会指错家户）。
    SocialData social = ToolSupport.socialData(state);
    String householdId = RaiseUnitPlan.householdIdFor(unitId);
    if (social.households().containsKey(HouseholdId.parse(householdId))) {
      throw new IllegalArgumentException(
          "人口家户 id 已被占用: " + householdId + "（spawnArmy 需要为 unitId 建新的人口家户；先清掉同名家户，或换 unitId）");
    }
    // ★ P3 口径：新家户 economy 视图居住类型按落点判——at 是某座 SocialCity 的 at ⇒ urban，否则 rural。
    ResidenceKind residence = GovSelectExamineesPlan.residenceAt(social, at);
    return new Plan(
        unitId,
        name,
        at,
        residence,
        householdId,
        member,
        ToolSupport.compositionEntries(equipment),
        speed,
        mobilityPerMille,
        parent,
        status,
        armyId,
        role,
        masterGov,
        timestamp.tick());
  }

  /** 装备形状校验：可为空表；键非空、值非负（范围已在工具面折成 int）。 */
  private static void requireEquipment(Map<String, Integer> equipment) {
    for (Map.Entry<String, Integer> entry : equipment.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("equipment 的键不得空白");
      }
      if (entry.getValue() == null || entry.getValue() < 0) {
        throw new IllegalArgumentException(
            "equipment 的值必须 ≥ 0: " + entry.getKey() + "=" + entry.getValue());
      }
    }
  }

  /** 父单位必须存在且当刻有效位置与落点同格（{@code unit.CreateUnit} 的硬要求）。 */
  private static void requireParentAt(
      UnitState units, String parentId, HexCoord at, SimosTimestamp timestamp) {
    requireNonBlank(parentId, "parent");
    UnitId parentKey = UnitId.parse(parentId);
    if (!units.units().containsKey(parentKey)) {
      throw new IllegalArgumentException("parent 单位不存在: " + parentId);
    }
    Optional<HexCoord> parentAt = units.effectivePosition(parentKey, timestamp);
    if (parentAt.isEmpty() || !parentAt.get().equals(at)) {
      throw new IllegalArgumentException(
          "parent "
              + parentId
              + " 当刻有效位置 "
              + parentAt.map(SpawnArmyPlan::hexText).orElse("(不可确定)")
              + " 与新单位落点 "
              + hexText(at)
              + " 不同格：unit.CreateUnit 要求同格才能编入同一支");
    }
  }

  /**
   * {@code masterGov} 必须存在且带 {@link GovernmentFormation}（与 {@code sd.CreateArmy}/{@code
   * UnitOperations} 同口径）。
   */
  private static void requireGovUnit(UnitState units, String govId, String field) {
    requireNonBlank(govId, field);
    UnitId gov = UnitId.parse(govId);
    Unit unit = units.units().get(gov);
    if (unit == null) {
      throw new IllegalArgumentException(field + " 指定的 GOV 单位不存在: " + govId);
    }
    if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
      throw new IllegalArgumentException(
          field + " 指定的单位 " + govId + " 没有 GovernmentFormation：不能作为 GOV");
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本");
    }
  }

  /** 格的可读文本（拒因、note 与 info 视图共用；格式不与任何资源路径语法绑定）。 */
  private static String hexText(HexCoord at) {
    return "(" + at.q() + "," + at.r() + ")";
  }

  /**
   * 单位 canonical 地址（{@code unit:<unitId>}）：只经 {@link Address#parse} → {@link Address#canonical()}（与
   * {@code RaiseUnitTool}/{@code GovCreateOfficeTool} 同款），行动记录的唯一拼写点。
   */
  static String unitAddress(String unitId) {
    Objects.requireNonNull(unitId, "unitId");
    return Address.parse(ToolSupport.UNIT_NAMESPACE + ":" + unitId).canonical();
  }

  /**
   * 一份按格建军计划（全部字段是状态与参数的纯函数）。
   *
   * @param unitId root 单位 id
   * @param name 新单位名
   * @param at 新单位落点
   * @param residence P3：新人口家户 economy 视图的居住类型（{@code at} 是某城 {@code at} ⇒ URBAN，否则 RURAL）
   * @param householdId 新单位人口家户 id（固定 {@code hh-unit:<unitId>}；location = UNIT(unitId)）
   * @param member 新单位人口家户经 Social 工单凭空创建的成年男丁数（≥ 1；GM 授权下允许凭空加口）
   * @param equipment 新单位装备（输入 map 按其迭代序转成有序表；缺省空表）
   * @param speed 新单位速度（≥ 1）
   * @param mobilityPerMille 新单位机动性（[1,1000]）
   * @param parent 父单位 id（可选）
   * @param status 新单位状态
   * @param armyId Army id
   * @param role 兵种/职责短名（可选；给了才落 {@code unit.SetArmyFormation}）
   * @param masterGov 认领的 GOV 单位 id（可选）
   * @param tick 推导时的世界日（行动记录与工单幂等键/批次锚点用）
   */
  record Plan(
      String unitId,
      String name,
      HexCoord at,
      ResidenceKind residence,
      String householdId,
      int member,
      List<CompositionEntry> equipment,
      int speed,
      int mobilityPerMille,
      Optional<String> parent,
      UnitStatus status,
      String armyId,
      Optional<String> role,
      Optional<String> masterGov,
      long tick) {

    Plan {
      requireNonBlank(unitId, "unitId");
      requireNonBlank(name, "name");
      requireNonBlank(armyId, "armyId");
      requireNonBlank(householdId, "householdId");
      Objects.requireNonNull(at, "at");
      Objects.requireNonNull(residence, "residence");
      Objects.requireNonNull(parent, "parent");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(role, "role");
      Objects.requireNonNull(masterGov, "masterGov");
      if (member < 1) {
        throw new IllegalArgumentException("member 必须 ≥ 1: " + member);
      }
      if (speed < 1) {
        throw new IllegalArgumentException("speed 必须 ≥ 1: " + speed);
      }
      if (mobilityPerMille < 1 || mobilityPerMille > 1000) {
        throw new IllegalArgumentException("mobilityPerMille 必须在 [1,1000]: " + mobilityPerMille);
      }
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      parent.ifPresent(value -> requireNonBlank(value, "parent"));
      role.ifPresent(value -> requireNonBlank(value, "role"));
      masterGov.ifPresent(value -> requireNonBlank(value, "masterGov"));
      // ★ 装备冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的 Collections.unmodifiableList）；
      //   输入 map 已由 plan() 的 requireEquipment 校过，这里按迭代序转成有序条目表。
      equipment = List.copyOf(Objects.requireNonNull(equipment, "equipment"));
    }

    /**
     * 新单位的人力表<b>视图</b>：单条 {@value #DEFAULT_MANPOWER_TYPE}（= member）；人口权威在 Social 家户 {@code
     * hh-unit:<unitId>}，本视图只供 preview/结果读取，<b>不</b>进任何命令载荷。
     */
    List<CompositionEntry> manpowerEntries() {
      return List.of(new CompositionEntry(DEFAULT_MANPOWER_TYPE, member));
    }

    /** 是否要落 {@code unit.SetArmyFormation}（只有 {@code role} 给了才落）。 */
    boolean hasArmyFormationCommand() {
      return role.isPresent();
    }

    /** 本工具将落的命令类型（批内固定七步顺序，{@code SetArmyFormation} 按 role 缺席；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(7);
      types.add(SUBMIT_HOUSEHOLD_WORK_ORDER_TYPE);
      types.add(CREATE_UNIT_TYPE);
      if (hasArmyFormationCommand()) {
        types.add(SET_ARMY_FORMATION_TYPE);
      }
      types.add(CREATE_ARMY_TYPE);
      types.add(REGISTER_HOUSEHOLD_TYPE);
      types.add(ENSURE_HOUSEHOLD_ACCOUNT_TYPE);
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /**
     * ★★ 人口腿的<b>唯一</b>命令载荷（{@code social.SubmitHouseholdWorkOrder}）——{@code orderId =
     * spawn-army:<batchId>:<unitId>}（{@code batchId} 由 Tool 每次 apply 生成，本类不造随机数），{@code target =
     * hh-unit:<unitId>}，{@code source.module="unit"}；{@code plan} 严格两步：先 {@code
     * CREATE_HOUSEHOLD}（位置 = {@code UNIT(unitId)}、画像 = {@code name+"·人口家户"}、vitalRates 空表），再 {@code
     * ADD_MEMBERS}（GM 造人： MALE、count=member、{@code ageAtAnchorDays=20*365}、{@code
     * anchorTick=tick}、lotId = {@code spawn-army:<unitId>:<tick>}）。
     */
    String submitHouseholdWorkOrderPayloadJson(String batchId, String reason) {
      requireNonBlank(batchId, "batchId");
      requireNonBlank(reason, "reason");
      List<Map<String, Object>> steps = new ArrayList<>(2);
      Map<String, Object> create = new LinkedHashMap<>();
      create.put("op", "CREATE_HOUSEHOLD");
      create.put("household", householdId);
      Map<String, Object> location = new LinkedHashMap<>();
      location.put("type", "UNIT");
      location.put("unitId", unitId);
      create.put("location", location);
      Map<String, Object> profile = new LinkedHashMap<>();
      profile.put("name", name + "·人口家户");
      create.put("profile", profile);
      create.put("vitalRates", List.of());
      steps.add(create);
      Map<String, Object> add = new LinkedHashMap<>();
      add.put("op", "ADD_MEMBERS");
      add.put("household", householdId);
      add.put("lotId", "spawn-army:" + unitId + ":" + tick);
      add.put("sex", "MALE");
      add.put("count", member);
      add.put("ageAtAnchorDays", POPULATION_AGE_AT_ANCHOR_DAYS);
      add.put("anchorTick", tick);
      steps.add(add);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("orderId", "spawn-army:" + batchId + ":" + unitId);
      payload.put("target", householdId);
      payload.put("reason", reason);
      payload.put("source", Map.of("module", "unit"));
      payload.put("plan", steps);
      return ToolSupport.json(payload);
    }

    /**
     * ★★ 新人口家户的 economy 登记载荷（{@code economy.RegisterHousehold}）——落点 = {@code at}，居住类型 = {@link
     * #residence()}，阶层 = {@code landless_laborer}，参与率 = 0（由 Social 逐户劳动预算在后续日循环注入，不在登记时猜）。
     */
    String registerHouseholdPayloadJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("household", householdId);
      payload.put("q", at.q());
      payload.put("r", at.r());
      payload.put("residence", residence.value());
      payload.put("stratum", SocialClassId.LANDLESS_LABORER.value());
      payload.put("participationPerMille", 0);
      payload.put("reason", reason);
      return ToolSupport.json(payload);
    }

    /** ★★ 新人口家户的零余额 actor 账户载荷（{@code actor.EnsureHouseholdAccount}，幂等；账户归 actor 切片）。 */
    String ensureHouseholdAccountPayloadJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("household", householdId);
      payload.put("reason", reason);
      return ToolSupport.json(payload);
    }

    /**
     * {@code unit.CreateUnit} 载荷（字段名逐字照 handler：{@code
     * id/name/position/households/equipment/speed/mobilityPerMille/status/parent?}）；{@code
     * households=[hh-unit:<unitId>]} 承载人口家户，<b>绝不出现 {@code manpower} 键</b>；status 显式写入（缺省 RESTING
     * 不靠 handler 的 MOVING 缺省）。
     */
    String createUnitPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", unitId);
      payload.put("name", name);
      payload.put("position", ToolSupport.hexCoord(at));
      payload.put("households", List.of(householdId));
      payload.put("equipment", ToolSupport.compositionView(equipment));
      payload.put("speed", speed);
      payload.put("mobilityPerMille", mobilityPerMille);
      payload.put("status", status.name());
      parent.ifPresent(parentId -> payload.put("parent", parentId));
      return ToolSupport.json(payload);
    }

    /** {@code unit.SetArmyFormation} 载荷：{@code {unitId, role, masterGov?}}（仅 role 非空时组装）。 */
    String setArmyFormationPayloadJson() {
      if (!hasArmyFormationCommand()) {
        throw new IllegalStateException("批不自洽：role 为空却要组装 unit.SetArmyFormation 载荷");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", unitId);
      payload.put("role", role.orElseThrow());
      masterGov.ifPresent(govId -> payload.put("masterGov", govId));
      return ToolSupport.json(payload);
    }

    /**
     * {@code sd.CreateArmy} 载荷：{@code {armyId, masterGovUnitId?, rootUnitId, name}}——rootUnitId
     * 指向同批刚创建的 单位；masterGov 给了才写 sd 侧主子。
     */
    String createArmyPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("armyId", armyId);
      masterGov.ifPresent(govId -> payload.put("masterGovUnitId", govId));
      payload.put("rootUnitId", unitId);
      payload.put("name", name);
      return ToolSupport.json(payload);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON <b>字符串</b>；字段序固定，人可读审计；含人口家户 id 与人口数）。 */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("unitId", unitId);
      value.put("armyId", armyId);
      value.put("hex", ToolSupport.hexCoord(at));
      value.put("householdId", householdId);
      value.put("population", member);
      value.put("manpower", ToolSupport.compositionView(manpowerEntries()));
      value.put("equipment", ToolSupport.compositionView(equipment));
      value.put("speed", speed);
      value.put("mobility", mobilityPerMille);
      value.put("masterGov", masterGov.orElse(null));
      value.put("role", role.orElse(null));
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /**
     * unit 侧 {@code ArmyFormation} 的说明：role 非空时说明将落命令；role 为空但 masterGov 给了时，明确说明 unit 侧不设 {@code
     * ArmyFormation.masterGov}（只写 sd 侧）。
     */
    String armyFormationNote() {
      if (hasArmyFormationCommand()) {
        return "unit.SetArmyFormation 将落：role="
            + role.orElseThrow()
            + "，masterGov="
            + masterGov.orElse("(未认主子)");
      }
      if (masterGov.isPresent()) {
        return "role 为空：unit 侧不落 unit.SetArmyFormation，unit 侧未设 ArmyFormation.masterGov；"
            + "masterGov 只写入 sd.CreateArmy.masterGovUnitId";
      }
      return "role 为空：unit 侧不落 unit.SetArmyFormation，unit 侧未设 ArmyFormation.masterGov";
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireNonBlank(reason, "reason");
      return "按格直接建军 "
          + unitId
          + "（"
          + name
          + "，tick "
          + tick
          + "）：armyId="
          + armyId
          + "，hex="
          + hexText(at)
          + "，member="
          + member
          + "（人口家户 "
          + householdId
          + "）"
          + "，equipment="
          + equipment
          + "，speed="
          + speed
          + "，mobility="
          + mobilityPerMille
          + "，status="
          + status
          + "，parent="
          + parent.orElse("(无)")
          + "，role="
          + role.orElse("(不落 SetArmyFormation)")
          + "，masterGov="
          + masterGov.orElse("(未给)")
          + "；reason="
          + reason;
    }
  }
}

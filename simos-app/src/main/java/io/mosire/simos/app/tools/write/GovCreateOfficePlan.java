package io.mosire.simos.app.tools.write;

import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.unit.GovFormation;
import io.mosire.simos.unit.GovLevel;
import io.mosire.simos.unit.OfficePolicy;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.gov.createOffice} 的<b>纯推导</b>（阶段 10b-ii，2026-10-01 GOV/Army 计划 §2.2/§2.6）： 从一份
 * {@link SimulationState} 与参数算出 {@link Plan}——<b>不碰 {@link io.mosire.agentlib.tool.ToolContext}、 不碰
 * {@code CoreSimos}</b>，preview 与 apply 因此共用同一份语义（工具只负责读态、把 Plan 折成视图、组批、折叠结局）。
 *
 * <p>★★ <b>它为什么是 app 级组合工具而不是一条命令</b>：新建的 GOV 单位在 {@code unit} 切片、绑定的决策人在 {@code sd}
 * 切片，单条命令只能落一个命名空间。本工具走 {@link io.mosire.simos.core.CoreSimos#submitBatch}（同 branch + 同
 * expectedRevision ⇒ 一批 = 一条 revision，原子）。
 *
 * <p>★★ <b>新单位参数（控制方口径，逐值写死；不在类外再拼一份）</b>：{@code member=0}、{@code equipment={}}、{@code
 * speed=1}、{@code mobilityPerMille=500}、{@code position=(q,r)}、<b>无 parent</b>（顶层，{@code
 * attached=false}）、{@code status} 走 {@code unit.CreateUnit} 的缺省 {@code MOVING}、{@code jurisdiction}
 * 走创建缺省 {@code empty}、{@code module} 由同批 {@code unit.SetGovFormation} 落。{@code q}/{@code r} 是
 * {@link HexCoord} 的合法 int 坐标（工具层已折）。
 *
 * <p>★★ <b>决策人三件（载荷先读 {@code CreateDecisionMakerHandler}/{@code SdPayloads} 定准）</b>：
 *
 * <ul>
 *   <li>{@code sd.CreateDecisionMaker} 的载荷只认 {@code {id, affiliation, allowedTools,
 *       cadence}}；{@code affiliation} = {@code {"kind":"gov","id":unitId}}。创建期它<b>不接受</b> {@code
 *       accessLimit}（传了会被静默忽略） ⇒ {@code accessLimit} 若给，走同批 {@code sd.SetDecisionMakerAccess}；
 *   <li>{@code providerId} 若给 ⇒ 同批 {@code sd.SetDecisionMakerProvider}（{@code {decisionMakerId,
 *       providerId}}）；
 *   <li>缺省：{@code cadence} 缺省 = {@code 1}（域层的合法下界 = 1）；{@code allowedTools} 缺省 = 空集（不替调用方发明能力面）；
 *       {@code accessLimit} 缺省 = 不写（创建期本就是 {@code AccessLimit.empty()} = 不额外收紧）；显式给空对象 {@code {}}
 *       与缺省等价，故不产生 {@code sd.SetDecisionMakerAccess} 命令。
 * </ul>
 *
 * <p>★★ <b>批顺序（固定，可复现）</b>：{@code unit.CreateUnit} → {@code unit.SetGovFormation} → {@code
 * sd.CreateDecisionMaker} →（{@code providerId} 给了才落）{@code sd.SetDecisionMakerProvider} →（{@code
 * accessLimit} 给了非空对象才落）{@code sd.SetDecisionMakerAccess} → {@code sd.PutInfo}（key={@code
 * createOffice}， address=单位 canonical，value=JSON <b>字符串</b>，note=人可读摘要）。四条/六条共享同一 batchId 与同一
 * branch/expectedRevision ⇒ 一条 revision。
 *
 * <p>★★ <b>纯推导校验（前置不满足 ⇒ 工具折 {@code BAD_REQUEST}、零 revision）</b>：{@code unitId}/{@code name}/
 * {@code decisionMakerId} 非空白；{@code unitId} 在 unit 切片里<b>必须不存在</b>；{@code decisionMakerId} 在 sd
 * 切片里 <b>必须不存在</b>；{@code level} 必须是 {@link GovLevel} 词表；{@code superiorGov} 非空 ⇒ 必须存在、带 {@link
 * GovFormation}、且不得等于新 unitId；{@code cadence ≥ 1}。批内域层拒（如一单位一标签、N9 白名单）由 {@code submitBatch}
 * 整条拒，逐条真拒因折成 {@code REJECTED}。
 *
 * <p>★ <b>确定性 / 保序不可变</b>：不碰墙钟（{@code tick} 是状态 meta 的函数）、不用随机量；{@code staff} 用 {@code
 * LinkedHashMap} 拷贝 + 赋值处冻结，{@code allowedTools} 用 {@code LinkedHashSet} 保留调用方给的顺序， {@code
 * accessLimit} 的命名空间与前缀都保留插入序；<b>不用</b> {@code Map.copyOf}（它不承诺保序）。
 */
final class GovCreateOfficePlan {

  /** {@code unit.CreateUnit} 的命令类型（字面量与 {@code CreateUnitHandler.type()} 同源）。 */
  static final String CREATE_UNIT_TYPE = "unit.CreateUnit";

  /** {@code unit.SetGovFormation} 的命令类型。 */
  static final String SET_GOV_FORMATION_TYPE = "unit.SetGovFormation";

  /** {@code sd.CreateDecisionMaker} 的命令类型。 */
  static final String CREATE_DECISION_MAKER_TYPE = "sd.CreateDecisionMaker";

  /** {@code sd.SetDecisionMakerProvider} 的命令类型（仅 providerId 给了才落）。 */
  static final String SET_DECISION_MAKER_PROVIDER_TYPE = "sd.SetDecisionMakerProvider";

  /** {@code sd.SetDecisionMakerAccess} 的命令类型（仅 accessLimit 给了非空对象才落）。 */
  static final String SET_DECISION_MAKER_ACCESS_TYPE = "sd.SetDecisionMakerAccess";

  /** {@code sd.PutInfo} 的命令类型（恒有，行动记录）。 */
  static final String PUT_INFO_TYPE = "sd.PutInfo";

  /** 新 GOV 单位的固定速度（控制方口径：行政班子不是作战单位；speed=1 是域层下界）。 */
  static final int NEW_UNIT_SPEED = 1;

  /** 新 GOV 单位的固定机动性（控制方口径 500‰，域层要求 ≥ 1）。 */
  static final int NEW_UNIT_MOBILITY_PER_MILLE = 500;

  /** 决策人缺省决策周期（天）：域层的合法下界 = 1，缺省不替调用方发明更慢的节奏。 */
  static final long DEFAULT_CADENCE = 1L;

  private GovCreateOfficePlan() {}

  /**
   * 纯推导入口（见类注的校验清单与固定口径）。
   *
   * @param state 读数所在的状态（preview / apply 都取<b>同一坐标</b>的状态）
   * @param unitId 新 GOV 单位 id（不得与既有单位重复）
   * @param name 新单位名（非空白）
   * @param at 新单位落点 = 工具层的 {@code (q,r)}
   * @param level GOV 层级词表（CENTRAL|PROVINCE）
   * @param superiorGov 上级 GOV（可选；非空必须存在、带 GovFormation、不得等于 unitId）
   * @param staff 初始编制（保序；缺省空表由工具层给）
   * @param policy 编制政策（缺省 {@link OfficePolicy#defaults()} 由工具层给）
   * @param decisionMakerId 同批绑定的决策人 id（在 sd 切片里必须不存在）
   * @param providerId LLM provider 引用（可选；给了才落 sd.SetDecisionMakerProvider）
   * @param cadence 决策周期（天；≥ 1）
   * @param allowedTools 决策人窄工具白名单（保序；缺省空集由工具层给）
   * @param accessLimit 额外资源限制（可选；给了非空对象才落 sd.SetDecisionMakerAccess）
   * @throws IllegalArgumentException 任一具名前置不满足（工具折 {@code BAD_REQUEST}）
   */
  static Plan plan(
      SimulationState state,
      String unitId,
      String name,
      HexCoord at,
      GovLevel level,
      Optional<String> superiorGov,
      Map<StaffRole, Long> staff,
      OfficePolicy policy,
      String decisionMakerId,
      Optional<String> providerId,
      long cadence,
      Set<String> allowedTools,
      Optional<Map<String, Set<String>>> accessLimit) {
    Objects.requireNonNull(state, "state");
    requireNonBlank(unitId, "unitId");
    requireNonBlank(name, "name");
    requireNonBlank(decisionMakerId, "decisionMakerId");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(superiorGov, "superiorGov");
    Objects.requireNonNull(staff, "staff");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(providerId, "providerId");
    Objects.requireNonNull(allowedTools, "allowedTools");
    Objects.requireNonNull(accessLimit, "accessLimit");
    if (cadence < 1L) {
      throw new IllegalArgumentException("cadence 必须 ≥ 1（决策周期，单位：天）: " + cadence);
    }
    UnitId id = UnitId.parse(unitId);
    if (ToolSupport.unitState(state).units().containsKey(id)) {
      throw new IllegalArgumentException("单位 id 已存在（createOffice 只建新单位）: " + unitId);
    }
    DecisionMakerId dmId = DecisionMakerId.parse(decisionMakerId);
    if (ToolSupport.sdState(state).decisionMakers().containsKey(dmId)) {
      throw new IllegalArgumentException("决策人 id 已存在（createOffice 只绑新决策人）: " + decisionMakerId);
    }
    superiorGov.ifPresent(
        superior -> {
          if (superior.equals(unitId)) {
            throw new IllegalArgumentException("superiorGov 不得指向新单位自身: " + unitId);
          }
          requireGovUnit(ToolSupport.unitState(state), UnitId.parse(superior), "superiorGov");
        });
    providerId.ifPresent(
        provider -> {
          if (provider.isBlank()) {
            throw new IllegalArgumentException("providerId 不得为空白（要么不给、要么给非空引用）");
          }
        });
    for (String tool : allowedTools) {
      if (tool == null || tool.isBlank()) {
        throw new IllegalArgumentException("allowedTools 不得含空白元素");
      }
    }
    return new Plan(
        unitId,
        name,
        at,
        level,
        superiorGov,
        staff,
        policy,
        decisionMakerId,
        providerId,
        cadence,
        allowedTools,
        accessLimit,
        state.meta().timestamp().tick());
  }

  /** 上级 GOV 必须存在且带 {@link GovFormation}（与 {@code UnitOperations.requireGovUnit} 同口径，纯只读）。 */
  private static void requireGovUnit(
      io.mosire.simos.unit.UnitState units, UnitId govUnitId, String field) {
    Unit unit = units.units().get(govUnitId);
    if (unit == null) {
      throw new IllegalArgumentException(field + " 指定的 GOV 单位不存在: " + govUnitId);
    }
    if (!(unit.module().orElse(null) instanceof GovFormation)) {
      throw new IllegalArgumentException(
          field + " 指定的单位 " + govUnitId + " 没有 GovFormation：不能作为 GOV");
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本");
    }
  }

  /**
   * 一份建 GOV + 绑决策人的计划（全部字段是状态与参数的纯函数；集合都在构造期冻结）。
   *
   * @param unitId 新 GOV 单位 id
   * @param name 新单位名
   * @param at 新单位落点
   * @param level GOV 层级
   * @param superiorGov 上级 GOV（可选）
   * @param staff 初始编制（保序不可变）
   * @param policy 编制政策
   * @param decisionMakerId 同批绑定的决策人 id
   * @param providerId provider 引用（可选）
   * @param cadence 决策周期（≥ 1）
   * @param allowedTools 决策人白名单（保序不可变）
   * @param accessLimit 额外资源限制（可选；<b>空 Optional = 键缺席</b>，有值但空 map = 显式空对象）
   * @param tick 推导时的世界日（行动记录用）
   */
  record Plan(
      String unitId,
      String name,
      HexCoord at,
      GovLevel level,
      Optional<String> superiorGov,
      Map<StaffRole, Long> staff,
      OfficePolicy policy,
      String decisionMakerId,
      Optional<String> providerId,
      long cadence,
      Set<String> allowedTools,
      Optional<Map<String, Set<String>>> accessLimit,
      long tick) {

    Plan {
      requireNonBlank(unitId, "unitId");
      requireNonBlank(name, "name");
      requireNonBlank(decisionMakerId, "decisionMakerId");
      Objects.requireNonNull(at, "at");
      Objects.requireNonNull(level, "level");
      Objects.requireNonNull(superiorGov, "superiorGov");
      Objects.requireNonNull(policy, "policy");
      Objects.requireNonNull(providerId, "providerId");
      if (cadence < 1L) {
        throw new IllegalArgumentException("cadence 必须 ≥ 1: " + cadence);
      }
      if (tick < 0L) {
        throw new IllegalArgumentException("tick 不得为负: " + tick);
      }
      staff = freezeStaff(staff);
      allowedTools = freezeTools(allowedTools);
      accessLimit = freezeLimit(accessLimit);
    }

    /** 是否要落 {@code sd.SetDecisionMakerProvider}（providerId 给了才落）。 */
    boolean hasProviderCommand() {
      return providerId.isPresent();
    }

    /**
     * 是否要落 {@code sd.SetDecisionMakerAccess}：<b>键缺席</b>不落；显式给非空对象才落；显式给空对象 {@code {}} 与创建期缺省（{@code
     * AccessLimit.empty()}）等价，故不落（不产生无状态变化的命令）。
     */
    boolean hasAccessCommand() {
      return accessLimit.filter(limit -> !limit.isEmpty()).isPresent();
    }

    /** 本工具将落的命令类型（批内固定顺序；preview 视图与 apply 组批共用这一处）。 */
    List<String> commandTypes() {
      List<String> types = new ArrayList<>(6);
      types.add(CREATE_UNIT_TYPE);
      types.add(SET_GOV_FORMATION_TYPE);
      types.add(CREATE_DECISION_MAKER_TYPE);
      if (hasProviderCommand()) {
        types.add(SET_DECISION_MAKER_PROVIDER_TYPE);
      }
      if (hasAccessCommand()) {
        types.add(SET_DECISION_MAKER_ACCESS_TYPE);
      }
      types.add(PUT_INFO_TYPE);
      return List.copyOf(types);
    }

    /**
     * {@code unit.CreateUnit} 载荷：新 GOV 单位参数逐值写死（类注口径）；不给 {@code parent}/{@code status}（status 缺省
     * MOVING），{@code jurisdiction} 由 handler 对新建单位固定 {@code empty}，本工具不发明第二个字段。
     */
    String createUnitPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", unitId);
      payload.put("name", name);
      payload.put("position", ToolSupport.hexCoord(at));
      payload.put("member", 0);
      payload.put("equipment", new LinkedHashMap<String, Object>());
      payload.put("speed", NEW_UNIT_SPEED);
      payload.put("mobilityPerMille", NEW_UNIT_MOBILITY_PER_MILLE);
      return ToolSupport.json(payload);
    }

    /**
     * {@code unit.SetGovFormation} 载荷：{@code {unitId, level, superiorGov?, staff, policy}}；{@code
     * staff}/{@code policy} 都显式给全（而不是靠 handler 缺省），让 revision 里的意图可读、可回放。
     */
    String setGovFormationPayloadJson() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("unitId", unitId);
      payload.put("level", level.name());
      superiorGov.ifPresent(superior -> payload.put("superiorGov", superior));
      payload.put("staff", staffView());
      payload.put("policy", policyView());
      return ToolSupport.json(payload);
    }

    /**
     * {@code sd.CreateDecisionMaker} 载荷：{@code {id, affiliation:{kind:"gov",id:unitId},
     * allowedTools, cadence}}—— 逐字段对应 {@code CreateDecisionMakerHandler} 的四个必填输入；{@code
     * accessLimit} <b>不进这里</b>（创建期恒为空、 传了会被静默忽略），要走同批 {@code sd.SetDecisionMakerAccess}。
     */
    String createDecisionMakerPayloadJson() {
      Map<String, Object> affiliation = new LinkedHashMap<>();
      affiliation.put("kind", "gov");
      affiliation.put("id", unitId);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", decisionMakerId);
      payload.put("affiliation", affiliation);
      payload.put("allowedTools", new ArrayList<>(allowedTools));
      payload.put("cadence", cadence);
      return ToolSupport.json(payload);
    }

    /** {@code sd.SetDecisionMakerProvider} 载荷（{@link #hasProviderCommand()} 为真时才可调用）。 */
    String setDecisionMakerProviderPayloadJson() {
      if (!hasProviderCommand()) {
        throw new IllegalStateException("批不自洽：无 providerId 却要组装 sd.SetDecisionMakerProvider 载荷");
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("decisionMakerId", decisionMakerId);
      payload.put("providerId", providerId.get());
      return ToolSupport.json(payload);
    }

    /** {@code sd.SetDecisionMakerAccess} 载荷（{@link #hasAccessCommand()} 为真时才可调用）。 */
    String setDecisionMakerAccessPayloadJson() {
      if (!hasAccessCommand()) {
        throw new IllegalStateException("批不自洽：无 accessLimit 却要组装 sd.SetDecisionMakerAccess 载荷");
      }
      Map<String, Object> limit = new LinkedHashMap<>();
      for (Map.Entry<String, Set<String>> entry : accessLimit.get().entrySet()) {
        limit.put(entry.getKey(), new ArrayList<>(entry.getValue()));
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("decisionMakerId", decisionMakerId);
      payload.put("accessLimit", limit);
      return ToolSupport.json(payload);
    }

    /** {@code sd.PutInfo} 的 {@code value}（JSON <b>字符串</b>；字段序固定，人可读审计）。 */
    String infoValueJson(String reason) {
      requireNonBlank(reason, "reason");
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("unitId", unitId);
      value.put("name", name);
      value.put("q", at.q());
      value.put("r", at.r());
      value.put("level", level.name());
      value.put("superiorGov", superiorGov.orElse(null));
      value.put("staff", staffView());
      value.put("policy", policyView());
      value.put("decisionMakerId", decisionMakerId);
      value.put("providerId", providerId.orElse(null));
      value.put("cadence", cadence);
      value.put("allowedTools", new ArrayList<>(allowedTools));
      Map<String, Object> limit = new LinkedHashMap<>();
      accessLimit.ifPresent(
          given -> {
            for (Map.Entry<String, Set<String>> entry : given.entrySet()) {
              limit.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
          });
      value.put("accessLimit", accessLimit.isPresent() ? limit : null);
      value.put("tick", tick);
      value.put("reason", reason);
      return ToolSupport.json(value);
    }

    /** 人可读行动摘要（工具结果与 {@code sd.PutInfo.note} 共用）。 */
    String infoNote(String reason) {
      requireNonBlank(reason, "reason");
      return "建 GOV "
          + unitId
          + "（"
          + name
          + "，tick "
          + tick
          + "）：level="
          + level
          + "，落点 ("
          + at.q()
          + ","
          + at.r()
          + ")，上级="
          + superiorGov.orElse("(无/中央)")
          + "，编制 "
          + staffSummary()
          + "，决策人="
          + decisionMakerId
          + (providerId.isPresent() ? "（provider=" + providerId.get() + "）" : "")
          + (hasAccessCommand() ? "（含 accessLimit）" : "")
          + "；reason="
          + reason;
    }

    /** 编制的人可读摘要（视图/note 共用；保序）。 */
    String staffSummary() {
      if (staff.isEmpty()) {
        return "{}";
      }
      StringBuilder text = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<StaffRole, Long> entry : staff.entrySet()) {
        if (!first) {
          text.append(", ");
        }
        text.append(entry.getKey().name()).append('=').append(entry.getValue());
        first = false;
      }
      return text.append('}').toString();
    }

    /** staff 的 JSON 视图（角色名 → 人数；保序）。 */
    Map<String, Object> staffView() {
      Map<String, Object> view = new LinkedHashMap<>();
      for (Map.Entry<StaffRole, Long> entry : staff.entrySet()) {
        view.put(entry.getKey().name(), entry.getValue());
      }
      return view;
    }

    /** policy 的 JSON 视图（五个字段全给；{@code staffCap} 保序）。 */
    Map<String, Object> policyView() {
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("grainPerStaffPerTick", policy.grainPerStaffPerTick());
      view.put("clothPerStaffPerCycle", policy.clothPerStaffPerCycle());
      view.put("moneyPerStaffPerTick", policy.moneyPerStaffPerTick());
      view.put("retirementPerStaff", policy.retirementPerStaff());
      Map<String, Object> caps = new LinkedHashMap<>();
      for (Map.Entry<StaffRole, Long> entry : policy.staffCap().entrySet()) {
        caps.put(entry.getKey().name(), entry.getValue());
      }
      view.put("staffCap", caps);
      return view;
    }
  }

  // ── 冻结小件（保序不可变；不用 Map.copyOf / Set.copyOf，它们不承诺保序） ────────────────

  private static Map<StaffRole, Long> freezeStaff(Map<StaffRole, Long> staff) {
    Objects.requireNonNull(staff, "staff");
    Map<StaffRole, Long> copy = new LinkedHashMap<>();
    for (Map.Entry<StaffRole, Long> entry : staff.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("staff 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "staff 的值必须 ≥ 0: " + entry.getKey() + "=" + entry.getValue());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  private static Set<String> freezeTools(Set<String> allowedTools) {
    Objects.requireNonNull(allowedTools, "allowedTools");
    Set<String> copy = new LinkedHashSet<>();
    for (String tool : allowedTools) {
      if (tool == null || tool.isBlank()) {
        throw new IllegalArgumentException("allowedTools 不得含空白");
      }
      copy.add(tool);
    }
    return Collections.unmodifiableSet(copy);
  }

  private static Optional<Map<String, Set<String>>> freezeLimit(
      Optional<Map<String, Set<String>>> accessLimit) {
    Objects.requireNonNull(accessLimit, "accessLimit");
    if (accessLimit.isEmpty()) {
      return Optional.empty();
    }
    Map<String, Set<String>> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> entry : accessLimit.get().entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        throw new IllegalArgumentException("accessLimit 的命名空间不得为空白");
      }
      Set<String> prefixes = new LinkedHashSet<>();
      for (String prefix : entry.getValue()) {
        if (prefix == null || prefix.isBlank()) {
          throw new IllegalArgumentException("accessLimit[" + entry.getKey() + "] 的前缀不得为空白");
        }
        prefixes.add(prefix);
      }
      copy.put(entry.getKey(), Collections.unmodifiableSet(prefixes));
    }
    return Optional.of(Collections.unmodifiableMap(copy));
  }

  /**
   * 单位 canonical 地址（行动记录的唯一拼写点）：只经 {@link Address#parse} → {@link Address#canonical()}， 与 levy /
   * raiseUnit / rejectDirective 同款，不手拼第二套格式。
   */
  static String unitAddress(String unitId) {
    Objects.requireNonNull(unitId, "unitId");
    return Address.parse(ToolSupport.UNIT_NAMESPACE + ":" + unitId).canonical();
  }
}

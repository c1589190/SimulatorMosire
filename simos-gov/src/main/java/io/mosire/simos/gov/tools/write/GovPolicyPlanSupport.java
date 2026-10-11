package io.mosire.simos.gov.tools.write;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.gov.tools.GovToolEconomy;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★★ <b>{@code economy.SetGovernmentPolicy} 的窄工具共用件（追加裁定 12，2026-10-12 §21.9 D-1~D-4）</b>：载荷整形 +
 * 预览投影 + GOV 单位守卫。
 *
 * <p>★★ <b>它替代了什么</b>：旧形状里这三个职责住在 {@code io.mosire.simos.gov.spi.GovAdministrationProjections} （gov
 * 切片内的"纯预览投影"）。政策搬进 economy 后，那段投影<b>必须跟着搬</b>——它要读政府政策（economy）又要判 GOV 单位 （unit），只有组合根同时看得见两片；留在
 * gov 会立刻变成"gov 读 economy"的反向依赖（I-B5）。
 *
 * <p>★★ <b>窄工具的用户载荷为什么仍是"平铺"的</b>：{@code simos.gov.setEstablishment} / {@code
 * simos.gov.setBudgetPolicy} 的既有调用方（含 GUI/演示脚本/既有测试夹具）写的是 <b>平铺</b>载荷（{@code
 * {unitId,securityPlannedLaborMilli,…}}）。本类把平铺载荷<b>逐字</b>嵌进新命令的 {@code policies} 子集 （{@code
 * {"governmentId":"gov-unit-<unitId>","policies":{"administration":{…平铺字段…}}}}）——工具的参数面因此
 * <b>一个字段都没变</b>（权限面与调用方不受影响），变的只是"落到哪条命令、写进哪张表"。
 *
 * <p>★ <b>preview 与 apply 同源</b>：两者都读同一坐标的 base 状态、跑<b>同一个</b> {@link EconomyGovPolicyPayloads}
 * 解析器与 GOV 单位守卫；preview 不写状态、不发"已应用"日志；逐值相同的重放 = noop（不落空 revision）。
 *
 * <p>★★ <b>命令名清单带来的连带</b>：命令类型串的唯一拼写点仍在 {@code economy} 的 handler（{@code
 * EconomySetGovernmentPolicyHandler#TYPE}）；搬进 gov 后本类**不再持有**这个字面量，改由工具经 {@link
 * io.mosire.simos.gov.tools.GovToolCommandTypes#setGovernmentPolicy()} 取（app 适配器转调同一个常量的那一处）。
 */
final class GovPolicyPlanSupport {

  /** 唯一的一台 mapper：共享基座出厂配置；本类只做对象字段覆盖与序列化。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private GovPolicyPlanSupport() {}

  /** 编制计划投影：{@code keyExisted} = 该 GOV 的政府记录存在（= 政策是"设过的"）；{@code noop} = 逐值相同。 */
  record PlanProjection(
      UnitId unitId,
      GovToolEconomy.AdministrationPlan previous,
      GovToolEconomy.AdministrationPlan next,
      boolean keyExisted,
      boolean noop) {

    PlanProjection {
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(previous, "previous");
      Objects.requireNonNull(next, "next");
    }
  }

  /** 预算政策投影：口径同 {@link PlanProjection}；{@code requestedMode} = 载荷声明的编辑模式（PATCH/REPLACE）。 */
  record BudgetProjection(
      UnitId unitId,
      GovToolEconomy.BudgetPolicy previous,
      GovToolEconomy.BudgetPolicy next,
      boolean keyExisted,
      boolean noop,
      String requestedMode) {

    BudgetProjection {
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(previous, "previous");
      Objects.requireNonNull(next, "next");
      Objects.requireNonNull(requestedMode, "requestedMode");
    }
  }

  /**
   * 载荷里声明的 {@code unitId}：缺失/{@code null} ⇒ 空；非文本或空白 ⇒ 具名拒。
   *
   * <p>★ 决策人版工具用它判"载荷是否试图指定别人的 GOV"（越权 ⇒ 具名拒）；GM 版用它做必填校验。
   */
  static String declaredUnitId(String payloadJson) {
    JsonNode payload = parse(payloadJson);
    JsonNode unitId = payload.get("unitId");
    if (unitId == null || unitId.isNull()) {
      return null;
    }
    if (!unitId.isTextual() || unitId.asText().isBlank()) {
      throw new IllegalArgumentException("字段 unitId 必须是非空白字符串");
    }
    return unitId.asText();
  }

  /**
   * 把载荷里的 {@code unitId} <b>整体覆盖</b>成调用方解析出的 GOV 单位 id（其余字段逐字保留），返回覆盖后的平铺载荷 JSON。
   *
   * <p>★ 这是决策人版工具"身份派生覆盖自报"的唯一落点；覆盖后的载荷仍由 {@link EconomyGovPolicyPayloads} 校验， 不存在"覆盖即绕过字段校验"。
   */
  static String withUnitId(String payloadJson, String unitId) {
    if (unitId == null || unitId.isBlank()) {
      throw new IllegalArgumentException("覆盖用的 unitId 不得为空白");
    }
    UnitId.parse(unitId); // 坏 id 在命令边界前折成 BAD_REQUEST（与 handler 同一条拼写）
    return withField(payloadJson, "unitId", unitId);
  }

  /**
   * 把平铺载荷里的 {@code mode} <b>整体覆盖</b>成工具参数指定的编辑模式（其余字段逐字保留）。
   *
   * <p>★ 给出即覆盖（覆盖后仍由 {@link EconomyGovPolicyPayloads} 解析校验，不存在"覆盖即绕过词表"）；不传时载荷逐字透传、 缺省 = PATCH。
   */
  static String withMode(String payloadJson, String modeName) {
    Objects.requireNonNull(modeName, "modeName");
    return withField(payloadJson, "mode", modeName);
  }

  /** 载荷字段覆盖的<b>唯一实现点</b>（{@code withUnitId} / {@code withMode} 共用；深拷贝后覆盖再序列化）。 */
  private static String withField(String payloadJson, String field, String value) {
    JsonNode parsed = parse(payloadJson);
    if (!(parsed instanceof ObjectNode object)) {
      throw new IllegalArgumentException("payload 必须是 JSON 对象");
    }
    ObjectNode copy = object.deepCopy();
    copy.put(field, value);
    return write(copy);
  }

  /**
   * ★★ <b>把平铺载荷嵌成新命令的载荷</b>（逐字搬运，不做字段改名）：{@code {"unitId":…,"mode":?,"其余字段":…}} ⇒ {@code
   * {"governmentId":"gov-unit-<unitId>","policies":{"<subset>":{其余字段+mode}}}}。
   *
   * @param flatPayloadJson 平铺载荷（含 {@code unitId}）
   * @param subset {@code port} / {@code budget} / {@code administration} 之一
   * @param includeMode true = 把平铺的 {@code mode} 搬进子集（只有预算那一路有它）
   */
  static String wrapAsCommand(String flatPayloadJson, String subset, boolean includeMode) {
    JsonNode flat = parse(flatPayloadJson);
    if (!(flat instanceof ObjectNode object)) {
      throw new IllegalArgumentException("payload 必须是 JSON 对象");
    }
    JsonNode unitIdNode = object.get("unitId");
    if (unitIdNode == null || !unitIdNode.isTextual() || unitIdNode.asText().isBlank()) {
      throw new IllegalArgumentException("字段 unitId 必须是非空白字符串（新命令要由它派生 governmentId）");
    }
    UnitId unitId = UnitId.parse(unitIdNode.asText());
    ObjectNode policy = MAPPER.createObjectNode();
    object
        .fields()
        .forEachRemaining(
            entry -> {
              if ("unitId".equals(entry.getKey())) {
                return;
              }
              if ("mode".equals(entry.getKey()) && !includeMode) {
                return;
              }
              policy.set(entry.getKey(), entry.getValue());
            });
    ObjectNode policies = MAPPER.createObjectNode();
    policies.set(subset, policy);
    ObjectNode wrapper = MAPPER.createObjectNode();
    wrapper.put("governmentId", governmentIdOf(unitId));
    wrapper.set("policies", policies);
    return write(wrapper);
  }

  /** {@code UnitId} → 政府身份裸值（唯一换算点：{@code GovernmentIds.ofUnit}；与 handler 同一条拼写）。 */
  static String governmentIdOf(UnitId unitId) {
    return io.mosire.simos.economy.api.id.GovernmentIds.ofUnit(unitId.value()).value();
  }

  /** 编制计划投影（见类注；GOV 单位守卫与 handler 同源）。 */
  static PlanProjection administrationPlanProjection(
      GovToolEconomy economy, SimulationState state, String commandPayloadJson) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(state, "state");
    JsonNode payload = parse(commandPayloadJson);
    UnitId unitId = commandUnitId(payload);
    requireGovernmentUnit(state, unitId);
    GovToolEconomy.AdministrationPlan next =
        economy.parseAdministrationPlan(write(subset(payload, economy.administrationSubsetName())));
    boolean keyExisted = economy.governmentRegistered(state, unitId.value());
    GovToolEconomy.AdministrationPlan previous = economy.administrationPlan(state, unitId.value());
    return new PlanProjection(unitId, previous, next, keyExisted, previous.equals(next));
  }

  /** 预算政策投影（见类注；GOV 单位守卫与 handler 同源；PATCH 的合并基准 = 现值政策）。 */
  static BudgetProjection budgetPolicyProjection(
      GovToolEconomy economy, SimulationState state, String commandPayloadJson) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(state, "state");
    JsonNode payload = parse(commandPayloadJson);
    UnitId unitId = commandUnitId(payload);
    requireGovernmentUnit(state, unitId);
    JsonNode subset = subset(payload, economy.budgetSubsetName());
    String requestedMode = economy.budgetEditMode(write(subset));
    boolean keyExisted = economy.governmentRegistered(state, unitId.value());
    GovToolEconomy.BudgetPolicy previous = economy.budgetPolicy(state, unitId.value());
    GovToolEconomy.BudgetPolicy next =
        economy.mergeBudgetPolicy(state, unitId.value(), write(subset));
    return new BudgetProjection(
        unitId, previous, next, keyExisted, previous.equals(next), requestedMode);
  }

  /** 命令载荷里的 {@code governmentId} → GOV 单位 id（唯一反向换算点；世界级政府 ⇒ 具名拒）。 */
  private static UnitId commandUnitId(JsonNode payload) {
    JsonNode governmentId = payload.get("governmentId");
    if (governmentId == null || !governmentId.isTextual() || governmentId.asText().isBlank()) {
      throw new IllegalArgumentException("字段 governmentId 必须是非空白字符串（由 unitId 派生）");
    }
    String govUnitId =
        io.mosire.simos.economy.api.id.GovernmentIds.unitRefOf(
                new io.mosire.simos.economy.api.id.GovernmentId(governmentId.asText()))
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "governmentId 不是任何 GOV 单位的政府（世界级政府没有编制可设）: " + governmentId.asText()));
    return UnitId.parse(govUnitId);
  }

  /** 子集节点（缺席 ⇒ 具名拒：预览与命令边界同一条口径）。 */
  private static JsonNode subset(JsonNode payload, String name) {
    JsonNode policies = payload.get("policies");
    if (policies == null || !policies.isObject()) {
      throw new IllegalArgumentException("命令载荷缺 policies 对象（工具内部形状，不该出现）");
    }
    JsonNode subset = policies.get(name);
    if (subset == null || !subset.isObject()) {
      throw new IllegalArgumentException("命令载荷缺 policies." + name + " 子集（工具内部形状，不该出现）");
    }
    return subset;
  }

  /**
   * GOV 编制单位守卫（与 handler <b>不同源但同判据</b>）：命令边界只判"政府记录存在"（economy 自己的身份表）， 工具面还要判"这个 id 真的是一个 GOV
   * 编制单位"（unit 切片）——因为工具的参数是 GOV 单位 id，写错一个字母要能当场说清。
   *
   * <p>unit 切片缺/类型不符 ⇒ 装配故障 {@link IllegalStateException}；单位不存在/不是 GOV ⇒ 业务拒 {@link
   * IllegalArgumentException}。
   */
  private static void requireGovernmentUnit(SimulationState state, UnitId unitId) {
    Snapshot snapshot =
        state
            .module("unit")
            .orElseThrow(
                () -> new IllegalStateException("state 里没有 unit 切片（装配故障：政府政策工具要判 GOV 单位）"));
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalStateException(
          "state 的 unit 切片不是 UnitSnapshot: " + snapshot.getClass().getName());
    }
    Unit unit = unitSnapshot.state().units().get(unitId);
    if (unit == null) {
      throw new IllegalArgumentException("GOV 单位不存在: " + unitId);
    }
    if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
      throw new IllegalArgumentException("unit 不是 GOV 编制单位（缺 GovernmentFormation）: " + unitId);
    }
  }

  /** 平铺载荷解析（与命令载荷同一台 mapper）。 */
  private static JsonNode parse(String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    try {
      JsonNode parsed = MAPPER.readTree(payloadJson);
      if (parsed == null || !parsed.isObject()) {
        throw new IllegalArgumentException("payload 必须是 JSON 对象");
      }
      return parsed;
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("payload 不是合法 JSON: " + e.getOriginalMessage(), e);
    }
  }

  private static String write(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("政府政策载荷重写序列化失败: " + e.getOriginalMessage(), e);
    }
  }

  /** 预览视图里"命令长什么样"的一行（type + 新命令载荷，与提交组包同源）。 */
  static Map<String, Object> commandPreview(String commandType, String commandPayloadJson) {
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", commandType);
    command.put("payloadJson", commandPayloadJson);
    return command;
  }
}

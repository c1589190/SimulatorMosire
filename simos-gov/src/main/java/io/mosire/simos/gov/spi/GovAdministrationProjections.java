package io.mosire.simos.gov.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovBudgetPolicy;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.Objects;

/**
 * ★★ {@code gov.SetAdministrationPlan} / {@code gov.SetBudgetPolicy} 的<b>纯预览投影</b>（Z3c-2）：app 工具在
 * {@code preview=true} 时读同一份 base 状态、跑与命令 handler <b>同源</b>的载荷解析与 GOV 单位守卫，但<b>不写状态、不发 "已应用"日志、不落
 * revision</b>。
 *
 * <p>★★ <b>为什么不直接调 handler</b>：handler 的成功路径会发 {@code GOV_SET_*_APPLIED} INFO——预览并没有应用， 发那条日志等于制造
 * "看起来已经写了"的假事实（本仓最反对的形态）。本类因此只复用 {@link GovPayloads} 与 {@code GovState} 的纯函数，不复制第二套字段解析。
 *
 * <p>★ <b>命令边界的语义仍只有一份</b>：本类的投影只决定 preview 视图（before/after/noop）；真正提交仍走同一条 {@code
 * core.submit(CommandEnvelope)} → handler，守卫/幂等/拒绝语义不因工具而改变。
 *
 * <p>★ <b>{@link #withUnitId} 是唯一的重写点</b>：决策人版工具用身份派生的 GOV 覆盖载荷里的 {@code unitId}（GM 版则要求载荷显式带
 * {@code unitId}）；重写后仍交给同一解析器，缺省展开/范围校验一字不动。
 */
public final class GovAdministrationProjections {

  /** 唯一的一台 mapper：共享基座出厂配置；本类只做对象字段覆盖与序列化。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private GovAdministrationProjections() {}

  /** 编制计划投影：{@code keyExisted} = 既有源状态表里是否已有该 GOV 的键；{@code noop} = 键存在且逐值相同。 */
  public record PlanProjection(
      UnitId unitId,
      GovAdministrationPlan previous,
      GovAdministrationPlan next,
      boolean keyExisted,
      boolean noop) {

    public PlanProjection {
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(previous, "previous");
      Objects.requireNonNull(next, "next");
    }
  }

  /** 预算政策投影：口径同 {@link PlanProjection}。 */
  public record BudgetProjection(
      UnitId unitId,
      GovBudgetPolicy previous,
      GovBudgetPolicy next,
      boolean keyExisted,
      boolean noop) {

    public BudgetProjection {
      Objects.requireNonNull(unitId, "unitId");
      Objects.requireNonNull(previous, "previous");
      Objects.requireNonNull(next, "next");
    }
  }

  /**
   * 载荷里声明的 {@code unitId}：缺失/{@code null} ⇒ 空；非文本或空白 ⇒ 具名拒。
   *
   * <p>决策人版工具用它判"载荷是否试图指定别人的 GOV"（越权 ⇒ 具名拒）；GM 版用它做必填校验。
   */
  public static String declaredUnitId(String payloadJson) {
    JsonNode payload = GovPayloads.parse(payloadJson);
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
   * 把载荷里的 {@code unitId} <b>整体覆盖</b>成调用方解析出的 GOV 单位 id（其余字段逐字保留），返回新的载荷 JSON 文本。
   *
   * <p>★ 这是决策人版工具"身份派生覆盖自报"的唯一落点；覆盖后的载荷仍由 {@link GovPayloads} 校验， 不存在"覆盖即绕过字段校验"。
   */
  public static String withUnitId(String payloadJson, String unitId) {
    if (unitId == null || unitId.isBlank()) {
      throw new IllegalArgumentException("覆盖用的 unitId 不得为空白");
    }
    UnitId.parse(unitId); // 坏 id 在命令边界前折成 BAD_REQUEST（与 handler 同一条拼写）
    JsonNode parsed = GovPayloads.parse(payloadJson);
    if (!(parsed instanceof ObjectNode object)) {
      throw new IllegalArgumentException("payload 必须是 JSON 对象");
    }
    ObjectNode copy = object.deepCopy();
    copy.put("unitId", unitId);
    try {
      return MAPPER.writeValueAsString(copy);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("编制载荷重写序列化失败: " + e.getOriginalMessage(), e);
    }
  }

  /** 编制计划投影（见类注；GOV 单位守卫与 handler 同源）。 */
  public static PlanProjection administrationPlan(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    JsonNode payload = GovPayloads.parse(payloadJson);
    UnitId unitId = GovPayloads.requireUnitId(payload);
    requireGovernmentUnit(state, unitId);
    GovState govState = govState(state);
    GovAdministrationPlan next = GovPayloads.administrationPlan(payload);
    boolean keyExisted = govState.administrationPlans().containsKey(unitId);
    GovAdministrationPlan previous =
        keyExisted ? govState.administrationPlans().get(unitId) : GovAdministrationPlan.neutral();
    return new PlanProjection(
        unitId, previous, next, keyExisted, keyExisted && previous.equals(next));
  }

  /** 预算政策投影（见类注；GOV 单位守卫与 handler 同源）。 */
  public static BudgetProjection budgetPolicy(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    JsonNode payload = GovPayloads.parse(payloadJson);
    UnitId unitId = GovPayloads.requireUnitId(payload);
    requireGovernmentUnit(state, unitId);
    GovState govState = govState(state);
    GovBudgetPolicy next = GovPayloads.budgetPolicy(payload);
    boolean keyExisted = govState.budgetPolicies().containsKey(unitId);
    GovBudgetPolicy previous =
        keyExisted ? govState.budgetPolicies().get(unitId) : GovBudgetPolicy.neutral();
    return new BudgetProjection(
        unitId, previous, next, keyExisted, keyExisted && previous.equals(next));
  }

  /** gov 切片提取：缺切片/类型不符 = 装配故障（ERROR 不降级，同 handler）。 */
  private static GovState govState(SimulationState state) {
    return GovSnapshots.of(state).state();
  }

  /**
   * GOV 编制单位守卫（与 {@code SetAdministrationPlanHandler#requireGovernmentUnit} 同一条口径）：unit 切片缺/类型不符 ⇒
   * 装配故障 {@link IllegalStateException}；单位不存在/不是 GOV ⇒ 业务拒 {@link IllegalArgumentException}。
   */
  private static void requireGovernmentUnit(SimulationState state, UnitId unitId) {
    Snapshot snapshot =
        state
            .module("unit")
            .orElseThrow(
                () -> new IllegalStateException("state 里没有 unit 切片（装配故障：gov 计划要判 GOV 单位）"));
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
}

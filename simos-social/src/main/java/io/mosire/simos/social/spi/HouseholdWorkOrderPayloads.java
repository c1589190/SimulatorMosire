package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.workorder.HouseholdWorkOrder;
import io.mosire.simos.social.workorder.HouseholdWorkOrderPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code social.SubmitHouseholdWorkOrder} 的载荷解析（social 模块私事，C26）：把 JSON 形状翻成
 * {@link HouseholdWorkOrder}（{@link HouseholdWorkOrderPlan} 的一组有序操作），形状/类型不对一律以
 * {@link IllegalArgumentException} 具名面世（handler 折 {@code Rejected}）。
 *
 * <pre>{@code
 * {
 *   "orderId"?: "wo-1",
 *   "target": "hh-1",
 *   "reason": "征兵",
 *   "source": {"module":"unit", "commandId"?: "...", "actorId"?: "..."},
 *   "dryRun"?: false,
 *   "plan": [
 *     {"op":"SET_LOCATION", "household":"hh-1", "location":{"type":"HEX","hex":{"q":1,"r":0}}},
 *     {"op":"TRANSFER_MEMBERS", "from":"hh-1", "to":"hh-2", "lotId":"lot-1", "count":3},
 *     {"op":"ADD_MEMBERS", "household":"hh-1", "sex":"MALE", "count":12, "ageAtAnchorDays":0, "anchorTick"?},
 *     {"op":"REMOVE_MEMBERS", "household":"hh-1", "lotId":"lot-1", "count":2},
 *     {"op":"ADJUST_POPULATION", "household":"hh-1", "sex":"FEMALE", "ageBracketId":"15-59", "delta":-2},
 *     {"op":"SET_VITAL_RATES", "household":"hh-1", "vitalRates":[...]},
 *     {"op":"CREATE_HOUSEHOLD", "household":"hh-new", "location":{...}, "profile":{"name":"..."}, "vitalRates"?}
 *   ]
 * }
 * }</pre>
 *
 * <p>★ <b>字段名的容错</b>：操作名认 {@code op} / {@code type} / {@code kind}（大小写不敏感）；
 * 家户 id 认 {@code household} / {@code householdId}，转移认 {@code from}/{@code to} 及其
 * {@code fromHousehold}/{@code toHousehold} 写法，率表认 {@code vitalRates} / {@code rates}。
 * location/profile/vitalRates/sex/count 等复用 {@link SocialPayloads} 的既有解析（与逐操作命令同一份形状）。
 *
 * <p>★ <b>ADD_MEMBERS 的 lotId</b>：给了就用；没给而工单有 {@code orderId} ⇒ 派生
 * {@code work-order:<orderId>:add:<计划序号>}（同载荷确定性）；两者都没有 ⇒ 具名拒（批次身份必须显式给出）。
 *
 * <p>★ <b>dryRun</b>：命令 handler 只有"给出变更集"或"拒绝"两种结局（{@code HandlerOutcome}），没有"只算不写"的
 * 结局；因此 {@code dryRun=true} 在命令面具名拒（不静默假装成功、也不落空 revision）。预览由 app 工具的预览路径承担。
 */
final class HouseholdWorkOrderPayloads {

  private HouseholdWorkOrderPayloads() {}

  /** 解析整张工单；{@code worldTick} 只作 ADD_MEMBERS 缺省锚点（写入计划后成为确定性输入）。 */
  static HouseholdWorkOrder parse(JsonNode payload, long worldTick) {
    if (worldTick < 0L) {
      throw new IllegalArgumentException("worldTick 不得为负: " + worldTick);
    }
    String orderId = SocialPayloads.optionalNonBlankText(payload, "orderId");
    HouseholdId target = SocialPayloads.requireHouseholdId(payload, "target");
    String reason = SocialPayloads.requireReason(payload);
    String source = sourceDescription(payload);
    if (SocialPayloads.optionalBoolean(payload, "dryRun", false)) {
      throw new IllegalArgumentException(
          "dryRun=true 不支持：命令面没有\"只算不写\"的结局（不能假装成功，也不能落一条空 revision）；"
              + "预览请走 app 工具的预览路径，正式受理请省略 dryRun 或传 false");
    }
    JsonNode planNode = payload.get("plan");
    if (planNode == null || planNode.isNull() || !planNode.isArray() || planNode.isEmpty()) {
      throw new IllegalArgumentException(
          "字段 plan 必须是非空操作数组（CREATE_HOUSEHOLD|SET_LOCATION|ADD_MEMBERS|REMOVE_MEMBERS|"
              + "TRANSFER_MEMBERS|ADJUST_POPULATION|SET_VITAL_RATES）: "
              + payload);
    }
    List<HouseholdWorkOrderPlan.Step> steps = new ArrayList<>(planNode.size());
    int index = 0;
    for (JsonNode stepNode : planNode) {
      index++;
      try {
        steps.add(parseStep(stepNode, index, orderId, worldTick));
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("工单 plan 第 " + index + " 步解析失败: " + e.getMessage(), e);
      }
    }
    return new HouseholdWorkOrder(orderId, target, reason, source, new HouseholdWorkOrderPlan(steps));
  }

  private static HouseholdWorkOrderPlan.Step parseStep(
      JsonNode step, int index, String orderId, long worldTick) {
    if (step == null || !step.isObject()) {
      throw new IllegalArgumentException("操作必须是 JSON 对象: " + step);
    }
    return switch (opKind(step)) {
      case "CREATE_HOUSEHOLD" ->
          new HouseholdWorkOrderPlan.CreateHousehold(
              requireHousehold(step),
              SocialPayloads.requireLocation(step, "location"),
              SocialPayloads.requireProfile(step, "profile"),
              requireVitalRates(step));
      case "SET_LOCATION" ->
          new HouseholdWorkOrderPlan.SetLocation(
              requireHousehold(step), SocialPayloads.requireLocation(step, "location"));
      case "ADD_MEMBERS" ->
          new HouseholdWorkOrderPlan.AddMembers(
              requireHousehold(step),
              requireLotId(step, orderId, index),
              SocialPayloads.requireSex(step, "sex"),
              SocialPayloads.requireLong(step, "count"),
              optionalLongDefault(step, "ageAtAnchorDays", 0L),
              optionalLongDefault(step, "anchorTick", worldTick));
      case "REMOVE_MEMBERS" ->
          new HouseholdWorkOrderPlan.RemoveMembers(
              requireHousehold(step),
              PeopleLotId.parse(SocialPayloads.requireText(step, "lotId")),
              SocialPayloads.requireLong(step, "count"));
      case "TRANSFER_MEMBERS" ->
          new HouseholdWorkOrderPlan.TransferMembers(
              requireFrom(step),
              requireTo(step),
              PeopleLotId.parse(SocialPayloads.requireText(step, "lotId")),
              SocialPayloads.requireLong(step, "count"));
      case "ADJUST_POPULATION" ->
          new HouseholdWorkOrderPlan.AdjustPopulation(
              requireHousehold(step),
              SocialPayloads.requireSex(step, "sex"),
              requireAgeBracketId(step),
              SocialPayloads.requireLong(step, "delta"));
      case "SET_VITAL_RATES" ->
          new HouseholdWorkOrderPlan.SetVitalRates(requireHousehold(step), requireVitalRates(step));
      default ->
          throw new IllegalArgumentException(
              "未知操作 op="
                  + opKind(step)
                  + "（支持 CREATE_HOUSEHOLD|SET_LOCATION|ADD_MEMBERS|REMOVE_MEMBERS|TRANSFER_MEMBERS|"
                  + "ADJUST_POPULATION|SET_VITAL_RATES）: "
                  + step);
    };
  }

  /** 操作名：{@code op}（首选）/ {@code type} / {@code kind}；大小写不敏感。 */
  private static String opKind(JsonNode step) {
    String text = SocialPayloads.optionalNonBlankText(step, "op");
    if (text == null) {
      text = SocialPayloads.optionalNonBlankText(step, "type");
    }
    if (text == null) {
      text = SocialPayloads.optionalNonBlankText(step, "kind");
    }
    if (text == null) {
      throw new IllegalArgumentException(
          "操作缺 op（支持 CREATE_HOUSEHOLD|SET_LOCATION|ADD_MEMBERS|REMOVE_MEMBERS|TRANSFER_MEMBERS|"
              + "ADJUST_POPULATION|SET_VITAL_RATES）: "
              + step);
    }
    return text.trim().toUpperCase(Locale.ROOT);
  }

  /** 家户 id：{@code household}（首选）或 {@code householdId}。 */
  private static HouseholdId requireHousehold(JsonNode step) {
    String text = SocialPayloads.optionalNonBlankText(step, "household");
    if (text == null) {
      text = SocialPayloads.optionalNonBlankText(step, "householdId");
    }
    if (text == null) {
      throw new IllegalArgumentException("操作缺 household（家户 id）: " + step);
    }
    return HouseholdId.parse(text);
  }

  private static HouseholdId requireFrom(JsonNode step) {
    String text = SocialPayloads.optionalNonBlankText(step, "from");
    if (text == null) {
      text = SocialPayloads.optionalNonBlankText(step, "fromHousehold");
    }
    if (text == null) {
      throw new IllegalArgumentException("TRANSFER_MEMBERS 缺 from（源家户 id）: " + step);
    }
    return HouseholdId.parse(text);
  }

  private static HouseholdId requireTo(JsonNode step) {
    String text = SocialPayloads.optionalNonBlankText(step, "to");
    if (text == null) {
      text = SocialPayloads.optionalNonBlankText(step, "toHousehold");
    }
    if (text == null) {
      throw new IllegalArgumentException("TRANSFER_MEMBERS 缺 to（目标家户 id）: " + step);
    }
    return HouseholdId.parse(text);
  }

  /**
   * ADD_MEMBERS 的批次身份：显式 {@code lotId} 优先；缺省且工单带 {@code orderId} ⇒ 确定性派生
   * {@code work-order:<orderId>:add:<计划序号>}；两者都没有 ⇒ 具名拒（身份不能由实现随手编）。
   */
  private static PeopleLotId requireLotId(JsonNode step, String orderId, int index) {
    String lotText = SocialPayloads.optionalNonBlankText(step, "lotId");
    if (lotText != null) {
      return PeopleLotId.parse(lotText);
    }
    if (orderId == null) {
      throw new IllegalArgumentException(
          "ADD_MEMBERS 缺 lotId 且工单未给 orderId：批次身份必须显式给出"
              + "（或给 orderId 以确定性派生 work-order:<orderId>:add:<序号>）: "
              + step);
    }
    return PeopleLotId.parse("work-order:" + orderId + ":add:" + index);
  }

  /** 年龄档 id：{@code ageBracketId}（首选）或 {@code bracketId}；空白由域层具名拒。 */
  private static String requireAgeBracketId(JsonNode step) {
    String text = SocialPayloads.optionalText(step, "ageBracketId");
    if (text == null) {
      text = SocialPayloads.optionalText(step, "bracketId");
    }
    if (text == null) {
      throw new IllegalArgumentException(
          "ADJUST_POPULATION 缺 ageBracketId（如 0-14|15-59|60+）: " + step);
    }
    return text;
  }

  private static long optionalLongDefault(JsonNode payload, String field, long defaultValue) {
    Long value = SocialPayloads.optionalLong(payload, field);
    return value == null ? defaultValue : value;
  }

  /**
   * 率表字段：{@code vitalRates}（{@code HouseholdBook.setVitalRates} 的形参名；新建命令的线格式）优先，
   * 也接受既有 {@code social.SetHouseholdVitalRates} 的字段名 {@code rates}；两者都没给 ⇒ 空表（= 清空，与既有命令同口径）。
   */
  private static HouseholdVitalRates requireVitalRates(JsonNode step) {
    if (!step.has("vitalRates") && step.has("rates")) {
      return new HouseholdVitalRates(SocialPayloads.requireVitalRates(step, "rates"));
    }
    return new HouseholdVitalRates(SocialPayloads.requireVitalRates(step, "vitalRates"));
  }

  /**
   * {@code source:{module,commandId?,actorId?}} ⇒ 规范化描述串（进日志与标记事件的 source 字段）。
   *
   * <p>★ 只做形状与非空白校验；来源是调用方自报（handler 看不到调用者身份），不在契约上冒充可信身份。
   */
  private static String sourceDescription(JsonNode payload) {
    JsonNode source = payload.get("source");
    if (source == null || source.isNull() || !source.isObject()) {
      throw new IllegalArgumentException(
          "字段 source 必须是 {module,commandId?,actorId?} 对象: " + payload);
    }
    String module = SocialPayloads.requireText(source, "module");
    if (module.isBlank()) {
      throw new IllegalArgumentException("字段 source.module 不得为空白: " + source);
    }
    String commandId = SocialPayloads.optionalNonBlankText(source, "commandId");
    String actorId = SocialPayloads.optionalNonBlankText(source, "actorId");
    StringBuilder text = new StringBuilder("module=").append(module);
    if (commandId != null) {
      text.append(" commandId=").append(commandId);
    }
    if (actorId != null) {
      text.append(" actorId=").append(actorId);
    }
    return text.toString();
  }
}

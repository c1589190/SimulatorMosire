package io.mosire.simos.production.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionLog;
import io.mosire.simos.production.ProductionLogSource;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code production.Seed}：定义批量播种</b>（D-031 P1 spec §3.3）—— 载荷 {@code industries:[完整产业定义]}、
 * {@code modes:[完整模式定义]}。
 *
 * <p>★ <b>具名校验、保序、同值 no-op</b>：同一 id 与既有定义逐值相同 = 幂等；不同值 = 冲突拒绝（不静默覆盖）。 冲突/版本拒绝零状态变化。
 *
 * <p>★★ <b>批内前向引用</b>（设计 §9.3）：本命令<b>只做本域形状/同值幂等/冲突拒绝</b>；mode→classStructure 这类"可能由同批
 * 后续命令补齐"的跨片引用后置到落库前的统一候选态校验（{@code CandidateStateValidator}），不在此处把合法批次卡死。
 */
public final class ProductionSeedHandler implements CommandHandler {

  public static final String TYPE = "production.Seed";

  private static final Logger LOG = ProductionLog.command();

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ProductionData base = ProductionSnapshots.of(state);
    try {
      JsonNode payload = ProductionPayloads.parse(payloadJson);
      ProductionData target = base;
      Map<IndustryId, IndustryDefinitionView> industries = new LinkedHashMap<>(target.industries());
      for (JsonNode node : array(payload, "industries")) {
        IndustryDefinitionView industry = ProductionDefinitionPayloads.industry(node);
        IndustryDefinitionView existing = industries.get(industry.id());
        if (existing != null && !existing.equals(industry)) {
          return rejected("production.Seed 的产业 id 与既有定义冲突（同值才幂等）: " + industry.id().value());
        }
        industries.put(industry.id(), industry);
      }
      target = target.withIndustries(industries);

      Map<ProductionModeId, ProductionModeDefinitionView> modes =
          new LinkedHashMap<>(target.modes());
      for (JsonNode node : array(payload, "modes")) {
        ProductionDefinitionPayloads.ModePayload mode = ProductionDefinitionPayloads.mode(node);
        int version = mode.version() != null ? mode.version() : 1;
        if (version < 1) {
          return rejected("production.Seed 的 mode version 必须 ≥ 1: " + mode.id().value());
        }
        ProductionModeDefinitionView definition =
            new ProductionModeDefinitionView(
                mode.id(), mode.name(), version, mode.classStructureId());
        ProductionModeDefinitionView existing = modes.get(mode.id());
        if (existing != null && !existing.equals(definition)) {
          return rejected("production.Seed 的 mode id 与既有定义冲突（同值才幂等）: " + mode.id().value());
        }
        modes.put(mode.id(), definition);
      }
      target = target.withModes(modes);

      // ── ★★ B2b（§13.11.4 第 5 条）：**实例段** ──────────────────────────────────────────
      //   `EconomyData.units` 已删 ⇒ 创世/追加播种的生产实例改由本命令承接（与 `industries` 同一套
      //   "同值幂等 / 冲突具名拒" 口径）。节点形状：
      //   `{id, plan, operator:{kind,id}, modeKey, location?, progressDays?, cycleContributions?,
      // cycleInputUsedMilli?}`
      //   ★ 地点缺省 = 从 plan id 的格键派生（`ofPlan`；**解不出 ⇒ 具名拒 location-unknown**，B3 收口）；
      //     显式给则必须与 plan 自带地点一致
      //     （那条一致性由 {@code ProductionRuntimeEngine.requireLocationAgreesWithPlan} 在运行期判，
      //      这里只做构造期形状校验）。
      Map<ProductionUnitId, ProductionProcess> instances = new LinkedHashMap<>(target.instances());
      for (JsonNode node : array(payload, "instances")) {
        ProductionProcess instance = instance(node);
        ProductionProcess existing = instances.get(instance.id());
        if (existing != null && !existing.equals(instance)) {
          return rejected("production.Seed 的实例 id 与既有实例冲突（同值才幂等）: " + instance.id().value());
        }
        instances.put(instance.id(), instance);
      }
      target = target.withInstances(instances);

      ProductionChangeSet changeSet = ProductionChangeSet.between(base, target);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_SEED_APPLIED",
                  ProductionLogSource.PRODUCTION_COMMAND,
                  "industries",
                  target.industries().size(),
                  "modes",
                  target.modes().size(),
                  "instances",
                  target.instances().size(),
                  "changed",
                  !changeSet.isEmpty()));
      return new HandlerOutcome.Applied(changeSet);
    } catch (IllegalArgumentException e) {
      return rejected(e.getMessage());
    }
  }

  /**
   * 一条实例节点 → {@link ProductionProcess}（B2b 的 {@code instances[]} 段）。
   *
   * <p>★ 必填 {@code id/plan/operator/modeKey}；{@code location} 缺省按 plan 的格键派生； {@code progressDays}
   * 缺省 0；周期台账缺省空表（新实例的合法起点）。
   */
  private static ProductionProcess instance(JsonNode node) {
    if (!(node instanceof com.fasterxml.jackson.databind.node.ObjectNode object)) {
      throw new IllegalArgumentException("instances 的每一项必须是对象: " + node);
    }
    ProductionUnitId id = ProductionUnitId.parse(text(object, "id"));
    IndustryId plan = IndustryId.parse(text(object, "plan"));
    String modeKey = text(object, "modeKey");
    JsonNode operatorNode = object.get("operator");
    if (!(operatorNode instanceof com.fasterxml.jackson.databind.node.ObjectNode operatorObject)) {
      throw new IllegalArgumentException("instances[].operator 必须是对象: " + node);
    }
    io.mosire.simos.actor.api.actor.ActorRef operator =
        new io.mosire.simos.actor.api.actor.ActorRef(
            io.mosire.simos.actor.api.actor.ActorKind.valueOf(text(operatorObject, "kind")),
            text(operatorObject, "id"));
    // ★★★ D-032 B3（§13.8 裁定二第 1/3/4 条）：**播种是语义边界** —— 方案串解不出地点时**绝不**写
    //   UNSPECIFIED 占位（那会让一个"地点没落定"的实例静默进入权威候选态）。实例必须有真实格：
    //   载荷显式给 {@code location}，或 {@code plan} 的 id 自带格段（{@code <kind>@<q>_<r>}）。
    //   ⇒ 两者都没有时**具名业务拒绝** {@code location-unknown}（命令层折 Rejected + INFO），
    //     与 {@code production.CreateInstance} 同一条口径。★ §14.12.3 裁定二十九：**构点 fail-closed** ——
    //     载荷**显式**给未落定的占位（`"location":"?"`）同样具名拒 `location-unknown`（判据 DEF-T2a-2；
    //     `parse` 仍可构造出该占位，那是留给读侧防御的，不是允许它进权威状态）。
    JsonNode locationNode = object.get("location");
    io.mosire.simos.production.contract.ProductionLocation location;
    if (locationNode == null || locationNode.isNull()) {
      location =
          io.mosire.simos.production.contract.ProductionLocation.ofPlan(plan)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "location-unknown: production.Seed 的实例既没有 location、plan id 也不带格段"
                              + "（实例必须有真实地点；不写 UNSPECIFIED 占位）: id="
                              + id.value()
                              + " plan="
                              + plan.value()));
    } else {
      location =
          io.mosire.simos.production.contract.ProductionLocation.parse(locationNode.asText());
      if (!location.isSpecified()) {
        // ★★★ DEF-T2a-2（§14.12.3 裁定二十九：**构点必须 fail-closed**）：载荷**显式**给
        //   `"location":"?"` 时不得把占位实例写进权威候选态。parse 仍能构造出 UNSPECIFIED（读侧防御要用它），
        //   但"写进权威状态"这条路必须在这里具名拒 —— 与上面"解不出地点"同一条口径 `location-unknown`。
        //   （读侧那一道保留：EconomyReferenceValidation / ClearRegionDefinitionsHandler。）
        throw new IllegalArgumentException(
            "location-unknown: production.Seed 的实例给了未落定的地点占位 \""
                + location.value()
                + "\"（实例必须有真实地点；不写 UNSPECIFIED 占位）: id="
                + id.value()
                + " plan="
                + plan.value());
      }
    }
    long progressDays =
        object.hasNonNull("progressDays") ? object.get("progressDays").asLong() : 0L;
    return new ProductionProcess(
        id, plan, location, operator, modeKey, progressDays, Map.of(), Map.of());
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空文本: " + node);
    }
    return value.asText();
  }

  private static HandlerOutcome rejected(String reason) {
    ProductionPayloads.logRejected(
        LOG, ProductionLogSource.PRODUCTION_COMMAND, "PRODUCTION_SEED_REJECTED", reason);
    return new HandlerOutcome.Rejected(reason);
  }

  private static Iterable<JsonNode> array(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {
      return java.util.List.of();
    }
    if (!value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是数组: " + payload);
    }
    return value;
  }
}

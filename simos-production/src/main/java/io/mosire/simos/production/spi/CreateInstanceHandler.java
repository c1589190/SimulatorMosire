package io.mosire.simos.production.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionLog;
import io.mosire.simos.production.ProductionLogSource;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.ProductionCreationRequest;
import io.mosire.simos.production.contract.ProductionInstanceOutcome;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code production.CreateInstance}：按需为一个<b>已存在</b>的方案定义立一个生产实例</b> （D-032 §4.3「economy
 * 在分配阶段提出创建请求，production 在自身状态里创建实例并返回」；★★ B2c §13.12.4 裁定十）。
 *
 * <pre>{@code {"plan":"office@0_0","unit":"unit-…","operator":{"kind":"HOUSEHOLD","id":"hh-gov-…"},
 *  "modeKey":"gov_service","location":"0_0"?}}</pre>
 *
 * <p>★★ <b>为什么它是命令面而不是"第二份 createInstance 实现"</b>：本 handler <b>不自己写实例表</b> —— 它把 {@link
 * ProductionData} 交给 {@link ProductionRuntimeEngine}，由<b>同一个</b> {@link
 * io.mosire.simos.production.contract.ProductionRuntime#createInstance} 契约口建档（同一套守卫：unit 冲突、 plan
 * 不存在、方案自带地点与实例地点矛盾），再把引擎派生出的 ChangeSet 原样交出。⇒ 命令面与运行期契约口 <b>一份实现</b>（I1：方案/实例的唯一权威在
 * production，且只有一个拼写点）。
 *
 * <p>★★ <b>裁定十的落点</b>：GM 窄工具 {@code simos.economy.upsertGovUnit} 在<b>同一批</b>里先发本命令（为新 GOV 建 office
 * 实例），再发 {@code economy.UpsertGovUnit}（写 economy 侧的份额/关系）。economy 侧的具名拒 {@code
 * OFFICE_UNIT_NOT_IN_PRODUCTION} <b>保留不动</b>（fail-closed：不许静默建无实例的 office）——
 * 修的是"没有建实例的入口"，不是"放宽拒因"。
 *
 * <p>★ <b>地点不得默认成空</b>：{@code location} 显式给就用它；没给则按方案 id 的格段读 （{@link
 * ProductionLocation#ofPlan}，"把既有生成约定读成地点"是该方法的既有用途）；两者都没有 ⇒ <b>具名拒</b> {@code
 * location-unknown}（绝不写 {@code UNSPECIFIED} 占位）。★ §14.12.3 裁定二十九：显式给**未落定的占位**（{@code
 * "location":"?"}）同样具名拒 {@code location-unknown}（构点 fail-closed，DEF-T2a-2）。
 *
 * <p>★ 业务拒绝（unit 冲突 / 方案不存在 / 地点矛盾）：INFO 具名理由 + 空 ChangeSet；装配故障（缺 production 切片） 照旧当场抛。
 */
public final class CreateInstanceHandler implements CommandHandler {

  /** ★ 字面量的唯一拼写点在 contract 包（跨模块引用面），见 {@code ProductionCommandTypes#CREATE_INSTANCE}。 */
  public static final String TYPE =
      io.mosire.simos.production.contract.ProductionCommandTypes.CREATE_INSTANCE;

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
      IndustryId plan = IndustryId.parse(text(payload, "plan"));
      ProductionUnitId unit = ProductionUnitId.parse(text(payload, "unit"));
      String modeKey = text(payload, "modeKey");
      JsonNode operatorNode = payload.get("operator");
      if (!(operatorNode
          instanceof com.fasterxml.jackson.databind.node.ObjectNode operatorObject)) {
        throw new IllegalArgumentException("字段 operator 必须是对象: " + payload);
      }
      ActorRef operator =
          new ActorRef(ActorKind.valueOf(text(operatorObject, "kind")), text(operatorObject, "id"));
      JsonNode locationNode = payload.get("location");
      Optional<ProductionLocation> location =
          locationNode == null || locationNode.isNull()
              ? ProductionLocation.ofPlan(plan)
              : Optional.of(ProductionLocation.parse(locationNode.asText()));
      if (location.isEmpty() || !location.get().isSpecified()) {
        // ★ 不默认空地点：方案 id 不带格段、载荷也没给地点 ⇒ 具名拒（调用方必须说清在哪一格生产）。
        // ★★★ DEF-T2a-2（§14.12.3 裁定二十九：**构点必须 fail-closed**）：载荷**显式**给未落定的地点占位
        //   （`"location":"?"`）走的是同一条具名拒 —— 占位**不得**被写进权威候选态。`parse` 仍能构造出它
        //   （读侧防御要用），但构造点不认。
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "PRODUCTION_INSTANCE_REJECTED",
                    ProductionLogSource.PRODUCTION_COMMAND,
                    "reason",
                    "location-unknown",
                    "unit",
                    unit.value(),
                    "plan",
                    plan.value()));
        return new HandlerOutcome.Rejected(
            TYPE + " location-unknown: " + locationDetail(plan, location));
      }
      ProductionRuntimeEngine runtime = new ProductionRuntimeEngine(base);
      ProductionInstanceOutcome outcome =
          runtime.createInstance(
              new ProductionCreationRequest(plan, unit, location.get(), operator, modeKey));
      if (outcome instanceof ProductionInstanceOutcome.Rejected rejected) {
        // ★ 业务拒绝 = INFO 具名理由（§8；引擎已记一条，这里再补命令面读数），零状态变化。
        EventLog.channel(LOG)
            .info(
                LogEvent.of(
                    "PRODUCTION_INSTANCE_REJECTED",
                    ProductionLogSource.PRODUCTION_COMMAND,
                    "reason",
                    rejected.reason(),
                    "unit",
                    unit.value(),
                    "plan",
                    plan.value()));
        return new HandlerOutcome.Rejected(
            TYPE + " " + rejected.reason() + ": unit=" + unit.value());
      }
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_INSTANCE_CREATE_APPLIED",
                  ProductionLogSource.PRODUCTION_COMMAND,
                  "unit",
                  unit.value(),
                  "plan",
                  plan.value(),
                  "location",
                  location.get().value(),
                  "operator",
                  operator.toString()));
      return new HandlerOutcome.Applied(runtime.changeSet());
    } catch (IllegalArgumentException e) {
      ProductionPayloads.logRejected(
          LOG,
          ProductionLogSource.PRODUCTION_COMMAND,
          "PRODUCTION_INSTANCE_REJECTED",
          e.getMessage());
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /**
   * ★ {@code location-unknown} 的<b>逐情形 detail</b>（reason 串与后半句不变量不变）：
   *
   * <ul>
   *   <li>载荷<b>没给</b> location 且方案 id 不带格段 ⇒"两处都没有"；
   *   <li>载荷<b>显式给</b>了未落定的占位（{@code "?"}）⇒ 报出该值（DEF-T2a-2：构点 fail-closed）。
   * </ul>
   */
  private static String locationDetail(IndustryId plan, Optional<ProductionLocation> location) {
    String why =
        location.isPresent()
            ? "载荷给的 location 是未落定的地点占位 \"" + location.get().value() + "\""
            : "方案 " + plan.value() + " 的 id 不带格段、载荷也没给 location";
    return why + " ⇒ 实例不知道在哪一格生产（不写 UNSPECIFIED 占位）";
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是非空文本: " + node);
    }
    return value.asText();
  }
}

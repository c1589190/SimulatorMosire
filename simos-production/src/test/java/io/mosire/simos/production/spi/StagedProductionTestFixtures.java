package io.mosire.simos.production.spi;

import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionRuntimeEngine;
import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ParticipantContribution;
import io.mosire.simos.production.contract.ProductionExecutionRequest;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.ProductionStageDefinition;
import io.mosire.simos.production.contract.ProductionStageState;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ★★ <b>D-032 T3 阶段 JSON 验收的测试夹具</b>（test-only；不碰生产代码）。
 *
 * <p>它只做三件事：① 用真实 JSON（经 {@link ProductionPayloads#parse} + {@link
 * ProductionDefinitionPayloads#industry(com.fasterxml.jackson.databind.JsonNode)}）解析方案； ② 按当前
 * {@link ProductionStageState} 与方案定义算出"今天该供应的 start + tick 投入"（与引擎 {@code ProductionRuntimeEngine}
 * 的 {@code startDue}/{@code requiredStageInputs} 同口径）； ③ 组一个真实的 {@link ProductionExecutionRequest}。
 *
 * <p>★ 不绕过 JSON 直接 {@code new ProductionStageDefinition(...)}：阶段数据全部来自文本块 JSON，测试才代表 "测试侧改 JSON →
 * 解析 → 引擎"的真实链路。
 */
final class StagedProductionTestFixtures {

  private StagedProductionTestFixtures() {}

  static final HouseholdId H1 = new HouseholdId("h-stage-1");
  static final ActorRef OPERATOR = new ActorRef(ActorKind.HOUSEHOLD, H1.value());
  static final ProductionUnitId UNIT = new ProductionUnitId("unit-grain-farm");
  static final ProductionRoleId OPERATOR_ROLE = ProductionRoleDefinition.defaultOperator().id();

  static final CommodityId GRAIN = new CommodityId("grain");
  static final CommodityId TRANSPORT_CAPACITY_SERVICE =
      new CommodityId("transport_capacity_service");

  /** JSON → 真实 {@link IndustryDefinitionView}（走生产 spi 的公开解析入口）。 */
  static IndustryDefinitionView parseIndustry(String json) {
    return ProductionDefinitionPayloads.industry(ProductionPayloads.parse(json));
  }

  /** 一个"刚立起来"的实例：进度 0、台账空、stageState 空（引擎下一 tick 自行初始化第一段）。 */
  static ProductionProcess freshInstance(IndustryId planId, String location) {
    return new ProductionProcess(
        UNIT,
        planId,
        new ProductionLocation(location),
        OPERATOR,
        planId.value(),
        0L,
        Map.of(),
        Map.of(),
        Optional.empty());
  }

  /** 空状态 + 一条方案 + 一个实例。 */
  static ProductionData world(IndustryDefinitionView plan, String location) {
    return ProductionData.empty()
        .withIndustry(plan)
        .withInstance(freshInstance(plan.id(), location));
  }

  /** 当前阶段 id（无 stageState ⇒ {@code "<none>"}，含首周期未初始化与 HARVEST 已清空两种）。 */
  static String stageIdOf(ProductionRuntimeEngine engine) {
    return engine
        .units()
        .get(UNIT)
        .stageState()
        .map(ProductionStageState::stageId)
        .orElse("<none>");
  }

  /** 当前阶段（stageState 空 ⇒ 方案第一段；stageState 非法 ⇒ 测试直接失败）。 */
  static ProductionStageDefinition currentStage(
      IndustryDefinitionView plan, ProductionProcess instance) {
    if (plan.stages().isEmpty()) {
      throw new IllegalStateException("方案没有 stages，不能走 staged 供料: " + plan.id().value());
    }
    if (instance.stageState().isEmpty()) {
      return plan.stages().get(0);
    }
    String stageId = instance.stageState().get().stageId();
    for (ProductionStageDefinition stage : plan.stages()) {
      if (stage.stageId().equals(stageId)) {
        return stage;
      }
    }
    throw new IllegalStateException("阶段状态引用了不存在的 stageId: " + stageId);
  }

  /**
   * 本 tick 应供应的 start + tick 投入（{@code perUnit × grantedScale}）。
   *
   * <p>口径与引擎一致：进入阶段第一天（{@code stageProgressDays == 0} 且开始投入表仍空）才追加 {@code startInputPerUnit}；每个
   * tick 都追加 {@code tickInputPerUnit}；同商品相加。0 不进表（请求拒绝 0 值）。
   */
  static Map<CommodityId, Long> dailyInputs(
      IndustryDefinitionView plan, ProductionProcess instance, long grantedScale) {
    ProductionStageDefinition stage = currentStage(plan, instance);
    boolean startDue =
        instance.stageState().isEmpty()
            || (instance.stageState().get().stageProgressDays() == 0L
                && instance.stageState().get().stageStartInputsUsedMilli().isEmpty());
    Map<CommodityId, Long> inputs = new LinkedHashMap<>();
    if (startDue) {
      mergeScaled(inputs, stage.startInputPerUnit(), grantedScale);
    }
    mergeScaled(inputs, stage.tickInputPerUnit(), grantedScale);
    return inputs;
  }

  private static void mergeScaled(
      Map<CommodityId, Long> target, Map<CommodityId, Long> perUnit, long grantedScale) {
    for (Map.Entry<CommodityId, Long> entry : perUnit.entrySet()) {
      long amount = Math.multiplyExact(entry.getValue(), grantedScale);
      if (amount > 0L) {
        target.merge(entry.getKey(), amount, Math::addExact);
      }
    }
  }

  /** 真实执行请求：既有实例 + 给定规模/占用/劳动/本日最终投入。 */
  static ProductionExecutionRequest executionRequest(
      long grantedScale,
      Map<AssetKind, Long> assetOccupancy,
      Map<CommodityId, Long> durableCommodityOccupancy,
      long laborMilli,
      Map<CommodityId, Long> finalInputs) {
    List<ParticipantContribution> contributions =
        laborMilli <= 0L
            ? List.of()
            : List.of(new ParticipantContribution(H1, OPERATOR_ROLE, laborMilli));
    Map<HouseholdId, Long> laborBudget = laborMilli <= 0L ? Map.of() : Map.of(H1, laborMilli);
    return new ProductionExecutionRequest(
        Optional.of(UNIT),
        Optional.empty(),
        grantedScale,
        contributions,
        assetOccupancy,
        durableCommodityOccupancy,
        finalInputs,
        ExternalAttributeFacts.EMPTY,
        Map.of(),
        laborBudget,
        Map.of());
  }
}

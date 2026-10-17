package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.CommodityId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★ <b>纯角色分配函数的输入</b>（D-032 D2 §6.3）。
 *
 * <p>所有表在构造期保序拷贝并冻结。权重表的键必须是非 null 的 {@link ProductionParticipantKey}，值必须 ≥ 0；
 * 角色是否存在、参与者是否重复等跨字段校验由 {@link RoleDistribution#allocate(RoleDistributionRequest)} 统一做。
 *
 * @param definition 方案定义；不得为 null
 * @param participants 参与项；不得为 null、不得含 null
 * @param recognizedLaborMilli 按 {@code (家户, 角色)} 的实际认可劳动；不得为 null
 * @param rightsWeights 按 {@code (家户, 角色)} 的权益权重；不得为 null
 * @param distributablePools 逐商品的<b>可分配净池</b>（已扣 FEED/DEPRECIATION）；不得为 null
 */
public record RoleDistributionRequest(
    IndustryDefinitionView definition,
    List<RoleDistributionParticipant> participants,
    Map<ProductionParticipantKey, Long> recognizedLaborMilli,
    Map<ProductionParticipantKey, Long> rightsWeights,
    Map<CommodityId, Long> distributablePools) {

  public RoleDistributionRequest {
    Objects.requireNonNull(definition, "RoleDistributionRequest.definition 不得为 null");
    if (participants == null) {
      throw new IllegalArgumentException("RoleDistributionRequest.participants 不得为 null");
    }
    List<RoleDistributionParticipant> participantCopy = new ArrayList<>();
    for (RoleDistributionParticipant participant : participants) {
      if (participant == null) {
        throw new IllegalArgumentException("RoleDistributionRequest.participants 不得含 null");
      }
      participantCopy.add(participant);
    }
    participants = Collections.unmodifiableList(participantCopy);

    if (recognizedLaborMilli == null) {
      throw new IllegalArgumentException(
          "RoleDistributionRequest.recognizedLaborMilli 不得为 null（无权重用空表）");
    }
    Map<ProductionParticipantKey, Long> recognizedLaborCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionParticipantKey, Long> entry : recognizedLaborMilli.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "RoleDistributionRequest.recognizedLaborMilli 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "RoleDistributionRequest.recognizedLaborMilli 不得为负: " + entry.getKey());
      }
      recognizedLaborCopy.put(entry.getKey(), entry.getValue());
    }
    recognizedLaborMilli = Collections.unmodifiableMap(recognizedLaborCopy);

    if (rightsWeights == null) {
      throw new IllegalArgumentException("RoleDistributionRequest.rightsWeights 不得为 null（无权重用空表）");
    }
    Map<ProductionParticipantKey, Long> rightsWeightsCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionParticipantKey, Long> entry : rightsWeights.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("RoleDistributionRequest.rightsWeights 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "RoleDistributionRequest.rightsWeights 不得为负: " + entry.getKey());
      }
      rightsWeightsCopy.put(entry.getKey(), entry.getValue());
    }
    rightsWeights = Collections.unmodifiableMap(rightsWeightsCopy);

    if (distributablePools == null) {
      throw new IllegalArgumentException("RoleDistributionRequest.distributablePools 不得为 null");
    }
    Map<CommodityId, Long> poolsCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : distributablePools.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "RoleDistributionRequest.distributablePools 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "RoleDistributionRequest.distributablePools 不得为负: "
                + entry.getKey()
                + " = "
                + entry.getValue());
      }
      poolsCopy.put(entry.getKey(), entry.getValue());
    }
    distributablePools = Collections.unmodifiableMap(poolsCopy);
  }
}

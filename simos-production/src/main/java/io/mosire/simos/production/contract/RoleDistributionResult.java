package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.id.CommodityId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★ <b>纯角色分配函数的输出</b>（D-032 D2 §6.3）：逐参与户逐商品份额、具名未分配量与逐商品池总量。
 *
 * <p>★ 构造器当场校验逐商品守恒：{@code Σshares + unallocated == poolTotal}。任何差额都是契约故障，不能静默留下 “无主池”。份额与未分配表只装
 * {@code > 0} 项。
 *
 * @param shares 按参与键的逐商品份额；不得为 null
 * @param unallocated 逐商品未分配量；不得为 null
 * @param poolTotal 逐商品可分配净池总量；不得为 null
 */
public record RoleDistributionResult(
    Map<ProductionParticipantKey, Map<CommodityId, Long>> shares,
    Map<CommodityId, Long> unallocated,
    Map<CommodityId, Long> poolTotal) {

  public RoleDistributionResult {
    if (shares == null || unallocated == null || poolTotal == null) {
      throw new IllegalArgumentException("RoleDistributionResult 的三张表都不得为 null（空用空表）");
    }
    Map<ProductionParticipantKey, Map<CommodityId, Long>> sharesCopy = new LinkedHashMap<>();
    for (Map.Entry<ProductionParticipantKey, Map<CommodityId, Long>> entry : shares.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("RoleDistributionResult.shares 的键与值都不得为 null");
      }
      Map<CommodityId, Long> lineCopy = new LinkedHashMap<>();
      for (Map.Entry<CommodityId, Long> line : entry.getValue().entrySet()) {
        if (line.getKey() == null || line.getValue() == null) {
          throw new IllegalArgumentException(
              "RoleDistributionResult.shares 内层表的键与值都不得为 null: " + entry.getKey());
        }
        if (line.getValue() <= 0L) {
          throw new IllegalArgumentException(
              "RoleDistributionResult.shares 只装正份额: " + entry.getKey() + "/" + line.getKey());
        }
        lineCopy.put(line.getKey(), line.getValue());
      }
      if (!lineCopy.isEmpty()) {
        sharesCopy.put(entry.getKey(), Collections.unmodifiableMap(lineCopy));
      }
    }
    shares = Collections.unmodifiableMap(sharesCopy);

    Map<CommodityId, Long> unallocatedCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : unallocated.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("RoleDistributionResult.unallocated 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "RoleDistributionResult.unallocated 不得为负: " + entry.getKey());
      }
      if (entry.getValue() > 0L) {
        unallocatedCopy.put(entry.getKey(), entry.getValue());
      }
    }
    unallocated = Collections.unmodifiableMap(unallocatedCopy);

    Map<CommodityId, Long> poolsCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : poolTotal.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("RoleDistributionResult.poolTotal 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "RoleDistributionResult.poolTotal 不得为负: " + entry.getKey());
      }
      poolsCopy.put(entry.getKey(), entry.getValue());
    }
    poolTotal = Collections.unmodifiableMap(poolsCopy);

    for (Map.Entry<ProductionParticipantKey, Map<CommodityId, Long>> entry : shares.entrySet()) {
      for (CommodityId commodity : entry.getValue().keySet()) {
        if (!poolTotal.containsKey(commodity)) {
          throw new IllegalArgumentException(
              "RoleDistributionResult.shares 含 poolTotal 之外的商品: "
                  + entry.getKey()
                  + "/"
                  + commodity);
        }
      }
    }
    for (CommodityId commodity : unallocated.keySet()) {
      if (!poolTotal.containsKey(commodity)) {
        throw new IllegalArgumentException(
            "RoleDistributionResult.unallocated 含 poolTotal 之外的商品: " + commodity);
      }
    }
    for (Map.Entry<CommodityId, Long> pool : poolTotal.entrySet()) {
      long allocated = 0L;
      for (Map<CommodityId, Long> line : shares.values()) {
        allocated = Math.addExact(allocated, line.getOrDefault(pool.getKey(), 0L));
      }
      allocated = Math.addExact(allocated, unallocated.getOrDefault(pool.getKey(), 0L));
      if (allocated != pool.getValue()) {
        throw new IllegalArgumentException(
            "RoleDistributionResult 逐商品守恒失败: "
                + pool.getKey()
                + " shares+unallocated="
                + allocated
                + " != poolTotal="
                + pool.getValue());
      }
    }
  }
}

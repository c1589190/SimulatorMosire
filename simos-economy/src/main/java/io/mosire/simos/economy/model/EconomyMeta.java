package io.mosire.simos.economy.model;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * 经济切片的**激活元信息**（新经济设计 §3.3 逐字）：{@code mapId}、激活日、最后关账的产业周期、规则版本、迁移来源。
 *
 * <p>★★ **"未激活"由承载它的 {@code Optional} 表达，不由本类型表达**（§3.3 + §6.6）：{@code EconomyData.meta} 为空 {@code
 * Optional} ⇒ 日制世界尚未激活经济（人口查询走简化口径，但日推进仍要求切片在场）。 故本类型**没有**"未激活"这一档状态——它一旦存在，就是"已激活"。
 *
 * <p>★ **量纲**：{@code activatedDay} 是**日**（日制底座 1 tick = 1 天，§7）；{@code lastClosedCycle} 是**产业周期序号**
 * （不是天数——农业 120 天一周期，故二者不同量纲）。
 *
 * <p>★ 不变量（构造期判）：{@code mapId}/{@code rulesVersion} 非空白；{@code activatedDay ≥ 0}；两个 {@code
 * Optional*} 组件**不得为 null**（缺席一律用 {@code Optional.empty()}/{@code OptionalLong.empty()}）。
 *
 * @param mapId 本切片所属地图的 ID（**只存不校验**：地图自己另有身份来源）
 * @param activatedDay 激活日（创世 = 0）
 * @param lastClosedCycle 最后关账的产业周期序号；**未关过账 = {@code OptionalLong.empty()}**
 * @param rulesVersion 规则版本（分配函数/税则的版本标签）
 * @param migrationSource 迁移来源（旧档坐标）；**直接创世 = {@code Optional.empty()}**
 */
public record EconomyMeta(
    String mapId,
    long activatedDay,
    OptionalLong lastClosedCycle,
    String rulesVersion,
    Optional<String> migrationSource) {

  /** ★ S1 迁移后的规则版本标签（计划 S1.5）：迁移器把旧档升到本版本；再次加载不得二次迁移。 */
  public static final String RULES_VERSION_PRE_MODERN_V1 = "pre-modern-v1";

  public EconomyMeta {
    if (mapId == null || mapId.isBlank()) {
      throw new IllegalArgumentException("EconomyMeta.mapId 不得为空白");
    }
    if (activatedDay < 0) {
      throw new IllegalArgumentException("EconomyMeta.activatedDay 不得为负: " + activatedDay);
    }
    if (rulesVersion == null || rulesVersion.isBlank()) {
      throw new IllegalArgumentException("EconomyMeta.rulesVersion 不得为空白");
    }
    if (lastClosedCycle == null) {
      throw new IllegalArgumentException(
          "EconomyMeta.lastClosedCycle 不得为 null（没关过账用 OptionalLong.empty()）");
    }
    if (migrationSource == null) {
      throw new IllegalArgumentException(
          "EconomyMeta.migrationSource 不得为 null（直接创世用 Optional.empty()）");
    }
  }
}

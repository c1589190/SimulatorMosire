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

  /**
   * ★★ <b>P10.1：7 hex 全链路运行时的当前规则版本（架构 §6 的唯一拼写点）。</b>
   *
   * <p>新世界的 {@code PRODUCTION_RUNTIME} 种子由 {@code EconomySeeder} 写入本值；检测到版本为空 / 不等本值 / 结构不完整时，
   * 唯一恢复路径是 GM 重置后按新 profile 重播，<b>不做任何旧档迁移兼容</b>（用户 2026-10-06 裁定）。
   */
  public static final String RUNTIME_VERSION_SEVEN_HEX_V1 = "seven-hex-v1";

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

  /**
   * ★★ <b>P10.1 版本门（读侧判据）</b>：本档的 {@code rulesVersion} 是否等于当前 7 hex 运行时版本。
   *
   * <p>它只是判据、不改状态；调用方（激活/载入路径）拿到 false 后应走 GM 重置，不许静默按空表继续 （架构 §6："旧档到不了空表静默路径"）。class-first 旧档的
   * {@code aggregate-v1} 会如实返回 false —— 那正是要拒绝的旧档。
   */
  public boolean isCurrentRuntimeVersion() {
    return RUNTIME_VERSION_SEVEN_HEX_V1.equals(rulesVersion);
  }

  /** 上一条的字符串形态（给"还没有 EconomyMeta"或缺版本位的最小检查口用）：{@code null}/空白/不等 ⇒ false。 */
  public static boolean isCurrentRuntimeVersion(String rulesVersion) {
    return RUNTIME_VERSION_SEVEN_HEX_V1.equals(rulesVersion);
  }

  /**
   * ★★ <b>P10.1 启动检查口</b>：版本为空 / 缺 {@link EconomyMeta} / 不等于当前运行时版本 ⇒ 抛具名异常， 由 GM 重置后按新 profile
   * 重播；<b>不自动迁移</b>。
   *
   * <p>本批只提供检查口与常量（被 {@code EconomySeeder} 的 PRODUCTION_RUNTIME 写入路径引用）；把它接到世界激活/日结算 是后续批次的接线点，不在
   * P10.1 越过边界。
   */
  public static void requireCurrentRuntimeVersion(EconomyMeta meta) {
    if (meta == null) {
      throw new IllegalStateException(
          "旧档/版本不符，需要 GM 重置: 经济元信息缺失（当前版本=" + RUNTIME_VERSION_SEVEN_HEX_V1 + "）");
    }
    if (!meta.isCurrentRuntimeVersion()) {
      throw new IllegalStateException(
          "旧档/版本不符，需要 GM 重置: rulesVersion="
              + meta.rulesVersion()
              + "，当前版本="
              + RUNTIME_VERSION_SEVEN_HEX_V1);
    }
  }

  /**
   * 上一条的字符串形态（给"版本标签"这类还没有 {@link EconomyMeta} 的最小检查口用）；不叫 {@code requireCurrentRuntimeVersion}
   * 是为了避免 {@code null} 字面量在两个重载之间产生编译歧义。
   *
   * @param rulesVersion 版本标签；{@code null}/空白/不等 ⇒ 同一个具名异常
   */
  public static void requireCurrentRuntimeVersionTag(String rulesVersion) {
    if (!isCurrentRuntimeVersion(rulesVersion)) {
      throw new IllegalStateException(
          "旧档/版本不符，需要 GM 重置: rulesVersion="
              + rulesVersion
              + "，当前版本="
              + RUNTIME_VERSION_SEVEN_HEX_V1);
    }
  }
}

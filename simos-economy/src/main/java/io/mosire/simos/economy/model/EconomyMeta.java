package io.mosire.simos.economy.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
    Optional<String> migrationSource,
    // ── ★★★ W10（2026-10-11，用户裁定「凡"行为"一律做成 GM 可调的预设」）第 6 个组件：经济侧政策预设表 ──
    //   键 = EconomyPolicyKey（稳定枚举名），值 = 该键的当前值。
    //   ★ 缺键 = 该键的具名缺省（= 改造前的常量值）⇒ 空表的世界逐值等于改动前（缺省中性，I-P8 同款）。
    //   ★ 宿主选这里（而不是新开第 42 个组件）的理由见实现账本：本组件是世界级单值组件，ChangeSet 走"单键表"投影、
    //     Codec 走既有 Jackson record 绑定 ⇒ 加一个字段不动任何既有 with*/构造点，也不引入"新组件忘了接三件套"的风险。
    Map<EconomyPolicyKey, Long> economyPolicies) {

  /** ★ S1 迁移后的规则版本标签（计划 S1.5）：迁移器把旧档升到本版本；再次加载不得二次迁移。 */
  public static final String RULES_VERSION_PRE_MODERN_V1 = "pre-modern-v1";

  /**
   * ★★ <b>P10.1：seven-hex 运行时的第一代版本标签（历史常量）。</b>
   *
   * <p>它只在旧档/旧载荷的拒绝路径里有意义（读到 V1 ⇒ 旧档）；当前写入值与版本门比较目标是 {@link #RUNTIME_VERSION_SEVEN_HEX_V2}。检测到版本为空
   * / 不等当前版本 / 结构不完整时，唯一恢复路径是 GM 重置后按新 profile 重播，<b>不做任何旧档迁移兼容</b>（用户 2026-10-06 裁定）。
   */
  public static final String RUNTIME_VERSION_SEVEN_HEX_V1 = "seven-hex-v1";

  /**
   * ★★ <b>P11.7 / D-024：seven-hex 运行时的第二代版本标签（架构 §3.6）。</b>
   *
   * <p>新世界的 PRODUCTION_RUNTIME 种子写本值（{@code EconomySeeder}），旧 V1 载荷/存档按设计一律视为旧档。 <b>版本门（{@link
   * #isCurrentRuntimeVersion()} / {@link #requireCurrentRuntimeVersionTag(String)}）的比较目标已随本批切到
   * V2</b>：V1 只是历史常量，不再是"当前版本"。
   */
  public static final String RUNTIME_VERSION_SEVEN_HEX_V2 = "seven-hex-v2";

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
    // ★★★ W10：政策表缺键（旧档 / 未设）⇒ 空表（= 全部走具名缺省）；null 与空表同义，不写第二个真相。
    //   ★ 越域的**已配置**值在构造期 fail-closed（命令边界另有一道同样的判据）—— 越域不是"调得激进"，是坏数据。
    // ★★★ W10-fix2（2026-10-11）：★ 冻在赋值处（与 MarketTable.hexes / RentRule.legs / Government.issuable
    // 同一形制）。
    //   ★ 为什么要有这一道**多余**的包装：{@code EconomyPolicyKey.normalize(...)} 虽已返回 {@code
    //   Collections.unmodifiableMap}（保序），但它在**另一个类**里 —— SpotBugs 的 EI_EXPOSE_REP 不做跨过程分析，
    //   只认它在这一个方法里看得见的 {@code Collections.unmodifiableMap}，于是把生成的 record 访问器报成"暴露内部表示"
    //   （canonical verify 实测 1 条，line 28 = record 头）。包内其余 22 个 record 组件都在 compact 构造器里就地包装，
    //   所以只有本类型被报。
    //   ★ 语义为空操作：normalize 已保序、已冻结 ⇒ 逐键、逐序、逐字节不变（缺省中性 I-P8 不受影响）。
    //   ★ **禁用 Map.copyOf / Set.copyOf**（会破坏保序；AGENTS §〇 铁律 7 / I7）。
    economyPolicies = Collections.unmodifiableMap(EconomyPolicyKey.normalize(economyPolicies));
    for (Map.Entry<EconomyPolicyKey, Long> entry : economyPolicies.entrySet()) {
      if (entry.getValue() == null || !entry.getKey().accepts(entry.getValue())) {
        throw new IllegalArgumentException(
            "EconomyMeta.economyPolicies 的政策值越域："
                + entry.getKey().name()
                + " = "
                + entry.getValue()
                + "（有效域 ["
                + entry.getKey().minValue()
                + ", "
                + entry.getKey().maxValue()
                + "]）");
      }
    }
  }

  /**
   * ★★ <b>W10：旧组件面的便捷构造器</b>（5 参 = 政策表为空）—— 历史调用点（含创世载荷与迁移器）不必为新增组件逐个改动； 语义 = "这个档没有配过任何政策" ⇒ 全部走
   * {@link EconomyPolicyKey#defaultValue()}（缺省中性）。
   */
  public EconomyMeta(
      String mapId,
      long activatedDay,
      OptionalLong lastClosedCycle,
      String rulesVersion,
      Optional<String> migrationSource) {
    this(mapId, activatedDay, lastClosedCycle, rulesVersion, migrationSource, Map.of());
  }

  /**
   * ★★ <b>W10：读一个政策值</b>（缺键 ⇒ 该键的具名缺省 = 现值）。★ 读口的唯一拼写点 —— 各结算类不得自己 {@code
   * economyPolicies().get(...)} 再兜底一遍。
   */
  public long policyOf(EconomyPolicyKey key) {
    Objects.requireNonNull(key, "key");
    Long configured = economyPolicies.get(key);
    return configured == null ? key.defaultValue() : configured;
  }

  /**
   * ★★ <b>W10：写一个政策值</b>（返回新实例；`value == 该键缺省` ⇒ <b>删掉这一行</b>，不留"等于现值的冗余行"）。
   *
   * <p>★ 越域当场抛（命令 handler 先在命令边界具名拒，这里是第二道闸）。
   */
  public EconomyMeta withPolicy(EconomyPolicyKey key, long value) {
    Objects.requireNonNull(key, "key");
    if (!key.accepts(value)) {
      throw new IllegalArgumentException(
          "EconomyMeta.withPolicy 的政策值越域："
              + key.name()
              + " = "
              + value
              + "（有效域 ["
              + key.minValue()
              + ", "
              + key.maxValue()
              + "]）");
    }
    LinkedHashMap<EconomyPolicyKey, Long> next = new LinkedHashMap<>(economyPolicies);
    if (value == key.defaultValue()) {
      next.remove(key);
    } else {
      next.put(key, value);
    }
    return new EconomyMeta(
        mapId, activatedDay, lastClosedCycle, rulesVersion, migrationSource, next);
  }

  /**
   * ★★ <b>P10.1 版本门（读侧判据）</b>：本档的 {@code rulesVersion} 是否等于当前 7 hex 运行时版本 （{@link
   * #RUNTIME_VERSION_SEVEN_HEX_V2}）。
   *
   * <p>它只是判据、不改状态；调用方（激活/载入路径）拿到 false 后应走 GM 重置，不许静默按空表继续 （架构 §6："旧档到不了空表静默路径"）。旧档的 {@code
   * aggregate-v1} 与上一代 {@code seven-hex-v1} 会如实返回 false —— 那正是要拒绝的旧档。
   *
   * <p>★★ <b>线格式契约</b>：这是派生判据，不是状态组件 ⇒ 必须在类型上直接 {@link JsonIgnore}。不能只靠 {@code EconomyCodec} 的
   * mixin：Core 时间线（{@code Timeline.CHANGESET_MAPPER}）与真实 Shell 用的是 {@code
   * SimosObjectMapper.create()} 的裸 mapper，看不见模块私有 mixin；不摘掉本属性会让任何改过 {@code economy.meta} 的
   * revision 在 replay 时因未知键 {@code currentRuntimeVersion} fail-closed。
   */
  @JsonIgnore
  public boolean isCurrentRuntimeVersion() {
    return RUNTIME_VERSION_SEVEN_HEX_V2.equals(rulesVersion);
  }

  /**
   * ★★ <b>A1：当前产业周期序号</b>（{@code ≥ 1}）—— 最后一个已关账周期 + 1；没关过账 ⇒ 1（世界刚激活，正在第 1 个周期里）。
   *
   * <p>★ 它存在的唯一理由是 {@code MoneyIssuanceRecord.period}（"这条发行发生在第几个产业周期"）必须 ≥ 1， 而命令面拿不到日结算上下文里的
   * {@code currentCycle} ⇒ 从权威的 {@code lastClosedCycle} 现算，不另存一份 （两份真相会漂）。
   *
   * <p>★★ <b>线格式契约</b>：这是派生判据、不是状态组件 ⇒ 必须 {@link JsonIgnore}（理由同 {@link
   * #isCurrentRuntimeVersion()}：Core 时间线的裸 mapper 看不见模块 mixin，不摘掉会让改过 {@code economy.meta} 的
   * revision 在 replay 时因未知键 fail-closed）。
   */
  @JsonIgnore
  public long currentCycleNumber() {
    return lastClosedCycle.orElse(0L) + 1L;
  }

  /** 上一条的字符串形态（给"还没有 EconomyMeta"或缺版本位的最小检查口用）：{@code null}/空白/不等 ⇒ false。 */
  public static boolean isCurrentRuntimeVersion(String rulesVersion) {
    return RUNTIME_VERSION_SEVEN_HEX_V2.equals(rulesVersion);
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
          "旧档/版本不符，需要 GM 重置: 经济元信息缺失（当前版本=" + RUNTIME_VERSION_SEVEN_HEX_V2 + "）");
    }
    if (!meta.isCurrentRuntimeVersion()) {
      throw new IllegalStateException(
          "旧档/版本不符，需要 GM 重置: rulesVersion="
              + meta.rulesVersion()
              + "，当前版本="
              + RUNTIME_VERSION_SEVEN_HEX_V2);
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
              + RUNTIME_VERSION_SEVEN_HEX_V2);
    }
  }
}

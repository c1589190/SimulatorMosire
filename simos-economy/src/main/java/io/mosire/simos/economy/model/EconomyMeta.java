package io.mosire.simos.economy.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.relation.LaborSource;
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
    Map<EconomyPolicyKey, Long> economyPolicies,
    // ── ★★ D-032 B2c 第 7 个组件：**按方案声明的"闲置份额来源主体"** ──────────────────────────────
    //   键 = 方案稳定 id（不绑地点的 IndustryId），值 = 该方案可取用其**空闲**份额的主体。
    //   ★ 归属（裁定八）：候选的 assetSource 字段**留 economy** —— "谁提供闲置份额"是经济关系，不是方案配方；
    //     它进 economy 的资产分配（进入算法拆分份额时的来源选择）。
    //   ★ 缺键 = 只认自有份额（= 旧候选缺省 assetSource 的逐值行为）⇒ 空表的世界逐值不变。
    //   ★ 宿主选这里（而不是新开第 41 个 EconomyData 组件）与 W10 的政策表同一条理由：本组件是世界级单值
    //     组件，ChangeSet 走"单键表"投影、Codec 走既有 Jackson record 绑定 ⇒ 不动 35 个 with*/构造点，
    //     也不引入"新组件忘了接三件套"的风险。
    Map<IndustryId, ActorRef> planAssetSources,
    // ── ★★ D-032 B3 第 8 个组件（裁定十二）：**按方案声明的"劳动来源"** ─────────────────────────────
    //   键 = 方案稳定 id（不绑地点的 IndustryId），值 = 该方案被采用时写进 relation 的劳动来源。
    //   ★ 为什么必须有它（而不是"读制度派生"）：B2c 曾把进入路径改成 `RegimeRelations.laborSourceFor(regime)`
    //     —— 那是**行为变更**（非 SELF 制度下与旧候选缺省不同），裁定十二定为"逐值不变 + 该派生另立项"。
    //     旧口径的声明面是候选的 `laborSource` 字段（GM 经 `upsertCandidate` 声明，缺省 SELF）；候选面退役后
    //     声明必须留在 economy（"谁出劳动"是经济关系），否则一项 GM 能力会随旧写面静默消失（与裁定十一同理）。
    //   ★ 缺键 = SELF（= 旧候选缺省）⇒ 空表的世界逐值不变（I14）。
    Map<IndustryId, LaborSource> planLaborSources) {

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
    // ★★ B2c：缺键（旧档 / 未设）⇒ 空表（= 只认自有份额）；null 与空表同义，不写第二个真相。
    //   保序不可变：LinkedHashMap + Collections.unmodifiableMap（禁用 Map.copyOf —— 迭代序不是内容的纯函数）。
    Map<IndustryId, ActorRef> sources = new LinkedHashMap<>();
    if (planAssetSources != null) {
      for (Map.Entry<IndustryId, ActorRef> entry : planAssetSources.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "EconomyMeta.planAssetSources 的键与值都不得为 null: " + entry.getKey());
        }
        sources.put(entry.getKey(), entry.getValue());
      }
    }
    planAssetSources = Collections.unmodifiableMap(sources);
    // ★★ B3（裁定十二）：缺键（旧档 / 未声明）⇒ 空表（= 全部 SELF）；null 与空表同义，不写第二个真相。
    //   ★ 值不得为 null；键不得为 null（越域/坏数据在构造期 fail-closed，不留给读取点猜）。
    Map<IndustryId, LaborSource> laborSources = new LinkedHashMap<>();
    if (planLaborSources != null) {
      for (Map.Entry<IndustryId, LaborSource> entry : planLaborSources.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "EconomyMeta.planLaborSources 的键与值都不得为 null: " + entry.getKey());
        }
        laborSources.put(entry.getKey(), entry.getValue());
      }
    }
    planLaborSources = Collections.unmodifiableMap(laborSources);
  }

  /**
   * ★★ <b>W10：旧组件面的便捷构造器</b>（5 参 = 政策表为空）—— 历史调用点（含创世载荷与迁移器）不必为新增组件逐个改动； 语义 = "这个档没有配过任何政策" ⇒ 全部走
   * {@link EconomyPolicyKey#defaultValue()}（缺省中性）。
   *
   * <p>★ B3：两个声明表（{@code planAssetSources} / {@code planLaborSources}）同样取空 ⇒ 逐值等于"没有任何方案声明" （只认自有份额
   * + SELF）。
   */
  public EconomyMeta(
      String mapId,
      long activatedDay,
      OptionalLong lastClosedCycle,
      String rulesVersion,
      Optional<String> migrationSource) {
    this(
        mapId,
        activatedDay,
        lastClosedCycle,
        rulesVersion,
        migrationSource,
        Map.of(),
        Map.of(),
        Map.of());
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
        mapId,
        activatedDay,
        lastClosedCycle,
        rulesVersion,
        migrationSource,
        next,
        planAssetSources,
        planLaborSources);
  }

  /**
   * ★★ <b>B3（裁定十一）：写/清某方案的"闲置份额来源主体"</b>（返回新实例）—— 声明命令 {@code economy.SetPlanSources} 的唯一写口。
   *
   * <p>★ <b>空 ⇒ 删掉这一行</b>（与 {@link #withPolicy} 同制）：缺键就是"只认自有份额"这条缺省，不留冗余行。 ★ 读口是 {@link
   * #planAssetSources()} 本身（进入算法按方案 id 取，缺键 ⇒ 只认自有份额）。
   */
  public EconomyMeta withPlanAssetSource(IndustryId plan, Optional<ActorRef> source) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(source, "source（没有给 Optional.empty()）");
    LinkedHashMap<IndustryId, ActorRef> next = new LinkedHashMap<>(planAssetSources);
    if (source.isPresent()) {
      next.put(plan, source.get());
    } else {
      next.remove(plan);
    }
    return new EconomyMeta(
        mapId,
        activatedDay,
        lastClosedCycle,
        rulesVersion,
        migrationSource,
        economyPolicies,
        next,
        planLaborSources);
  }

  /**
   * ★★ <b>B3（裁定十二）：写/清某方案的"劳动来源"</b>（返回新实例）。
   *
   * <p>★ <b>写成 {@link LaborSource#SELF}（或缺省）⇒ 删掉这一行</b>：SELF 就是缺省，不留"等于缺省的冗余行" （与政策表的 {@code value
   * == defaultValue ⇒ remove} 同制）。
   */
  public EconomyMeta withPlanLaborSource(IndustryId plan, Optional<LaborSource> source) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(source, "source（没有给 Optional.empty()）");
    LinkedHashMap<IndustryId, LaborSource> next = new LinkedHashMap<>(planLaborSources);
    LaborSource value = source.orElse(LaborSource.SELF);
    if (value == LaborSource.SELF) {
      next.remove(plan);
    } else {
      next.put(plan, value);
    }
    return new EconomyMeta(
        mapId,
        activatedDay,
        lastClosedCycle,
        rulesVersion,
        migrationSource,
        economyPolicies,
        planAssetSources,
        next);
  }

  /**
   * ★★ <b>B3：某方案声明的劳动来源（读口的唯一拼写点）</b> —— 缺键 ⇒ {@link LaborSource#SELF}（= 旧候选缺省， I14 逐值不变）。
   *
   * <p>★ 各消费点（进入生产写 relation）不得自己 {@code planLaborSources().get(...)} 再兜底一遍。
   */
  public LaborSource laborSourceOf(IndustryId plan) {
    return laborSourceOf(planLaborSources, plan);
  }

  /** 上一条的表形态（会话只拿到那张表时用同一份缺省口径；**不得**在别处再写一遍 {@code SELF} 兜底）。 */
  public static LaborSource laborSourceOf(
      Map<IndustryId, LaborSource> declarations, IndustryId plan) {
    Objects.requireNonNull(declarations, "declarations（缺省给空表 = 全部 SELF）");
    Objects.requireNonNull(plan, "plan");
    LaborSource declared = declarations.get(plan);
    return declared == null ? LaborSource.SELF : declared;
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

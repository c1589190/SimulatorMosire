package io.mosire.simos.social.provisioning;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.util.economy.EconomyVocabulary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>社会侧人口/劳动/需求的权威旁表</b>（2026-10-09 家户结构修复计划 §3.4 / §3.5，用户已选方案 B）。
 *
 * <p>它是 {@code SocialData} 的第 6 个组件：<b>不塞进 {@code Household}</b>（避免家户 record 的成员/位置/编码
 * 拷贝点随系数表爆炸），而是按 {@code HouseholdId} 关联——家户覆盖优先、全局默认兜底：
 *
 * <pre>
 * globalDemandDefaults       : (年龄档, 性别, 商品) → DemandCoefficient   // 全局默认，保序
 * globalLaborDefaults        : (年龄档, 性别)       → LaborCoefficient    // 全局默认，保序
 * householdDemandOverrides   : HouseholdId → (年龄档, 性别, 商品) → 系数   // 逐户覆盖，保序
 * householdLaborOverrides    : HouseholdId → (年龄档, 性别)       → 系数   // 逐户覆盖，保序
 * </pre>
 *
 * <p>★★ <b>查找顺序恒为"家户覆盖 &gt; 全局默认"</b>（{@link #findDemand} / {@link #findLabor}）：某个 {@code (年龄档, 性别,
 * 商品)} 没有家户覆盖时**逐键回落**全局默认；两边都没有 ⇒ {@link IllegalArgumentException} 具名拒（不静默给 0——"这一档需求是
 * 0"与"没有这一档的口径"是两件事）。
 *
 * <p>★★ <b>默认值 = 计划 §3.5 的六档表</b>（{@link #defaults()}，全仓唯一拼写点）：
 *
 * <table border="1">
 *   <caption>全局默认（粮 = 毫粮/人/120 天；布 = 毫布/人/历年；劳动 = 毫小时/人/tick）</caption>
 *   <tr><th>年龄档</th><th>性别</th><th>粮</th><th>布</th><th>劳动</th></tr>
 *   <tr><td>0-14</td><td>MALE</td><td>6,000</td><td>600</td><td>4,000</td></tr>
 *   <tr><td>0-14</td><td>FEMALE</td><td>6,000</td><td>600</td><td>4,000</td></tr>
 *   <tr><td>15-59</td><td>MALE</td><td>10,000</td><td>1,000</td><td>16,000</td></tr>
 *   <tr><td>15-59</td><td>FEMALE</td><td>9,000</td><td>1,200</td><td>8,000</td></tr>
 *   <tr><td>60+</td><td>MALE</td><td>7,000</td><td>800</td><td>0</td></tr>
 *   <tr><td>60+</td><td>FEMALE</td><td>7,000</td><td>900</td><td>0</td></tr>
 * </table>
 *
 * <p>★ 粮的 {@code period = PER_CYCLE_DAYS}、{@code cycleDays = 120}（周期残差必须留到逐日差分，不许先化成日均）； 布的 {@code
 * period = PER_CALENDAR_YEAR}、{@code cycleDays = 0}（年长由历法时钟给）；劳动本身无时间分数。 数值是初值、可 GM
 * 调；机制（逐成员求和、家户层一次取整、覆盖回落）才是本表要钉死的东西。
 *
 * <p>★★ <b>保序不可变 + 表内唯一键</b>（构造期判、坏数据当场抛）：四张表都做防御性拷贝并冻结 （{@code LinkedHashMap} / {@code ArrayList}
 * + {@code unmodifiable*}，**不用** {@code Map.copyOf}——迭代序 不是内容的纯函数）；每个列表内 {@code (年龄档, 性别, 商品)} /
 * {@code (年龄档, 性别)} 不得重复， 家户覆盖表的键与值不得为 null。
 *
 * <p>★ <b>{@link #defaults()} 只覆盖粮/布两商品</b>（计划 §3.5 的六档表）；本类不硬编码其它商品， 其它商品要进需求表必须由
 * GM/工单显式写入行。{@link #globalDemandBases()} 是"某商品已知默认口径"的只读视图， 供后续展开与调参读口使用；同一商品在默认表里出现互相矛盾的口径 ⇒ 具名拒。
 *
 * <p>★ 本类型零 Jackson 注解：线格式由 {@code SocialCodec} 负责；{@code HouseholdId} 作 map 键的 反序列化器已在那个 mapper
 * 上注册（与 {@code households} 表的键同一条）。
 *
 * @param globalDemandDefaults 全局需求默认表（{@code (年龄档, 性别, 商品)} 唯一）；不得为 null
 * @param globalLaborDefaults 全局劳动默认表（{@code (年龄档, 性别)} 唯一）；不得为 null
 * @param householdDemandOverrides 逐家户需求覆盖（键不得为 null；每户列表内三维键唯一）；不得为 null
 * @param householdLaborOverrides 逐家户劳动覆盖（键不得为 null；每户列表内二维键唯一）；不得为 null
 */
public record SocialProvisioning(
    List<DemandCoefficient> globalDemandDefaults,
    List<LaborCoefficient> globalLaborDefaults,
    Map<HouseholdId, List<DemandCoefficient>> householdDemandOverrides,
    Map<HouseholdId, List<LaborCoefficient>> householdLaborOverrides) {

  /**
   * 粮的商品 id：字面量的唯一拼写点在 {@link EconomyVocabulary#GRAIN_COMMODITY_ID}（util 层共享词表）， 本类不写第二遍 {@code
   * "grain"}。
   */
  private static final CommodityId GRAIN = new CommodityId(EconomyVocabulary.GRAIN_COMMODITY_ID);

  /** 布的商品 id：同上，字面量唯一拼写点是 {@link EconomyVocabulary#CLOTH_COMMODITY_ID}。 */
  private static final CommodityId CLOTH = new CommodityId(EconomyVocabulary.CLOTH_COMMODITY_ID);

  public SocialProvisioning {
    globalDemandDefaults = freezeDemandList(globalDemandDefaults, "globalDemandDefaults");
    globalLaborDefaults = freezeLaborList(globalLaborDefaults, "globalLaborDefaults");
    householdDemandOverrides =
        freezeDemandOverrides(householdDemandOverrides, "householdDemandOverrides");
    householdLaborOverrides =
        freezeLaborOverrides(householdLaborOverrides, "householdLaborOverrides");
  }

  /**
   * ★★ <b>计划 §3.5 的六档默认表</b>（全仓唯一拼写点；数值是初值、可被 GM 覆盖/改全局）。
   *
   * <p>调用即构造一份新的保序不可变实例（record 本身不可变，调用方拿不到可变别名），并按 INFO 记一条 {@code
   * event=SOCIAL_PROVISIONING_DEFAULTS_SEEDED}（含需求/劳动行数与商品清单）；DEBUG 另记逐行明细。 默认表按"未成年 → 成年 → 老年"、每档
   * MALE → FEMALE、每格粮 → 布的顺序写入；劳动表同序。
   */
  public static SocialProvisioning defaults() {
    long grainCycleDays = EconomyVocabulary.RATION_CYCLE_DAYS;
    List<DemandCoefficient> demands =
        List.of(
            new DemandCoefficient(
                AgeBracket.CHILD,
                Sex.MALE,
                GRAIN,
                6_000L,
                DemandPeriod.PER_CYCLE_DAYS,
                grainCycleDays),
            new DemandCoefficient(
                AgeBracket.CHILD, Sex.MALE, CLOTH, 600L, DemandPeriod.PER_CALENDAR_YEAR, 0L),
            new DemandCoefficient(
                AgeBracket.CHILD,
                Sex.FEMALE,
                GRAIN,
                6_000L,
                DemandPeriod.PER_CYCLE_DAYS,
                grainCycleDays),
            new DemandCoefficient(
                AgeBracket.CHILD, Sex.FEMALE, CLOTH, 600L, DemandPeriod.PER_CALENDAR_YEAR, 0L),
            new DemandCoefficient(
                AgeBracket.ADULT,
                Sex.MALE,
                GRAIN,
                10_000L,
                DemandPeriod.PER_CYCLE_DAYS,
                grainCycleDays),
            new DemandCoefficient(
                AgeBracket.ADULT, Sex.MALE, CLOTH, 1_000L, DemandPeriod.PER_CALENDAR_YEAR, 0L),
            new DemandCoefficient(
                AgeBracket.ADULT,
                Sex.FEMALE,
                GRAIN,
                9_000L,
                DemandPeriod.PER_CYCLE_DAYS,
                grainCycleDays),
            new DemandCoefficient(
                AgeBracket.ADULT, Sex.FEMALE, CLOTH, 1_200L, DemandPeriod.PER_CALENDAR_YEAR, 0L),
            new DemandCoefficient(
                AgeBracket.ELDER,
                Sex.MALE,
                GRAIN,
                7_000L,
                DemandPeriod.PER_CYCLE_DAYS,
                grainCycleDays),
            new DemandCoefficient(
                AgeBracket.ELDER, Sex.MALE, CLOTH, 800L, DemandPeriod.PER_CALENDAR_YEAR, 0L),
            new DemandCoefficient(
                AgeBracket.ELDER,
                Sex.FEMALE,
                GRAIN,
                7_000L,
                DemandPeriod.PER_CYCLE_DAYS,
                grainCycleDays),
            new DemandCoefficient(
                AgeBracket.ELDER, Sex.FEMALE, CLOTH, 900L, DemandPeriod.PER_CALENDAR_YEAR, 0L));
    List<LaborCoefficient> labor =
        List.of(
            new LaborCoefficient(AgeBracket.CHILD, Sex.MALE, 4_000L),
            new LaborCoefficient(AgeBracket.CHILD, Sex.FEMALE, 4_000L),
            new LaborCoefficient(AgeBracket.ADULT, Sex.MALE, 16_000L),
            new LaborCoefficient(AgeBracket.ADULT, Sex.FEMALE, 8_000L),
            new LaborCoefficient(AgeBracket.ELDER, Sex.MALE, 0L),
            new LaborCoefficient(AgeBracket.ELDER, Sex.FEMALE, 0L));
    SocialProvisioning defaults = new SocialProvisioning(demands, labor, Map.of(), Map.of());
    SocialLog.provisioning()
        .info(
            "event=SOCIAL_PROVISIONING_DEFAULTS_SEEDED "
                + SocialLog.kv(
                    "demandRows",
                    demands.size(),
                    "laborRows",
                    labor.size(),
                    "commodities",
                    defaults.globalDemandBases().keySet()));
    if (SocialLog.provisioning().isDebugEnabled()) {
      SocialLog.provisioning()
          .debug("event=SOCIAL_PROVISIONING_DEFAULTS_ROWS defaults={}", defaults);
    }
    return defaults;
  }

  /**
   * ★★ <b>查需求系数</b>：家户覆盖优先、全局默认兜底；两边都没有 ⇒ {@link IllegalArgumentException} 具名拒 （ERROR 日志带 household
   * / 年龄档 / 性别 / 商品）。
   *
   * <p>覆盖的粒度是"键"而不是"整户"：某户只覆盖了粮 ⇒ 它的布仍走全局默认；删除某键的覆盖 ⇒ 该键回落全局默认。
   *
   * @param householdId 家户 id；不得为 null
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   * @param commodity 商品 id；不得为 null
   * @throws IllegalArgumentException 任一所参为 null，或该键在覆盖与默认表里都查不到
   */
  public DemandCoefficient findDemand(
      HouseholdId householdId, AgeBracket ageBracket, Sex sex, CommodityId commodity) {
    return findDemandOptionally(householdId, ageBracket, sex, commodity)
        .orElseThrow(
            () ->
                ProvisioningReject.reject(
                    "找不到需求系数（家户覆盖与全局默认都没有）: household="
                        + householdId
                        + " ageBracket="
                        + ageBracket.key()
                        + " sex="
                        + sex
                        + " commodity="
                        + commodity));
  }

  /**
   * ★ <b>查需求系数的可空形态</b>（与 {@link #findDemand} 同一条查找路径，但查不到返回 {@link Optional#empty()} 而不是具名拒）：
   * 调用方是"读口/预览"这类**允许某键不存在**的场景（例如清除家户覆盖后的 after 视图：该商品可能没有全局默认行）。
   *
   * <p>参数校验仍是硬约束（任一为 null ⇒ 具名拒），"查不到"与"给了 null"是两件事。
   *
   * @param householdId 家户 id；不得为 null
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   * @param commodity 商品 id；不得为 null
   */
  public Optional<DemandCoefficient> findDemandOptionally(
      HouseholdId householdId, AgeBracket ageBracket, Sex sex, CommodityId commodity) {
    requireArg(householdId, "householdId");
    requireArg(ageBracket, "ageBracket");
    requireArg(sex, "sex");
    requireArg(commodity, "commodity");
    DemandCoefficient hit =
        findDemandIn(householdDemandOverrides.get(householdId), ageBracket, sex, commodity);
    if (hit != null) {
      return Optional.of(hit);
    }
    return Optional.ofNullable(findDemandIn(globalDemandDefaults, ageBracket, sex, commodity));
  }

  /**
   * ★ <b>查全局需求默认行</b>（不含任何家户覆盖）：全局表里没有该键 ⇒ 空。供 GM 工具/预览取"清除覆盖后的回落值"。
   *
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   * @param commodity 商品 id；不得为 null
   */
  public Optional<DemandCoefficient> globalDemand(
      AgeBracket ageBracket, Sex sex, CommodityId commodity) {
    requireArg(ageBracket, "ageBracket");
    requireArg(sex, "sex");
    requireArg(commodity, "commodity");
    return Optional.ofNullable(findDemandIn(globalDemandDefaults, ageBracket, sex, commodity));
  }

  /**
   * ★ <b>查某家户自己的需求覆盖行</b>（不回落全局）：该户没有这条覆盖键 ⇒ 空。供清除前的存在性校验与日志读数使用。
   *
   * @param householdId 家户 id；不得为 null
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   * @param commodity 商品 id；不得为 null
   */
  public Optional<DemandCoefficient> householdDemandOverride(
      HouseholdId householdId, AgeBracket ageBracket, Sex sex, CommodityId commodity) {
    requireArg(householdId, "householdId");
    requireArg(ageBracket, "ageBracket");
    requireArg(sex, "sex");
    requireArg(commodity, "commodity");
    return Optional.ofNullable(
        findDemandIn(householdDemandOverrides.get(householdId), ageBracket, sex, commodity));
  }

  /**
   * ★★ <b>查劳动系数</b>：家户覆盖优先、全局默认兜底；两边都没有 ⇒ {@link IllegalArgumentException} 具名拒 （ERROR 日志带 household
   * / 年龄档 / 性别）。语义与 {@link #findDemand} 同制（逐键回落，不做整户覆盖）。
   *
   * @param householdId 家户 id；不得为 null
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   * @throws IllegalArgumentException 任一所参为 null，或该键在覆盖与默认表里都查不到
   */
  public LaborCoefficient findLabor(HouseholdId householdId, AgeBracket ageBracket, Sex sex) {
    return findLaborOptionally(householdId, ageBracket, sex)
        .orElseThrow(
            () ->
                ProvisioningReject.reject(
                    "找不到劳动系数（家户覆盖与全局默认都没有）: household="
                        + householdId
                        + " ageBracket="
                        + ageBracket.key()
                        + " sex="
                        + sex));
  }

  /** ★ 查劳动系数的可空形态（语义同 {@link #findDemandOptionally}）。 */
  public Optional<LaborCoefficient> findLaborOptionally(
      HouseholdId householdId, AgeBracket ageBracket, Sex sex) {
    requireArg(householdId, "householdId");
    requireArg(ageBracket, "ageBracket");
    requireArg(sex, "sex");
    LaborCoefficient hit = findLaborIn(householdLaborOverrides.get(householdId), ageBracket, sex);
    if (hit != null) {
      return Optional.of(hit);
    }
    return Optional.ofNullable(findLaborIn(globalLaborDefaults, ageBracket, sex));
  }

  /** ★ 查全局劳动默认行（不回落任何覆盖）：默认表里没有该键 ⇒ 空。 */
  public Optional<LaborCoefficient> globalLabor(AgeBracket ageBracket, Sex sex) {
    requireArg(ageBracket, "ageBracket");
    requireArg(sex, "sex");
    return Optional.ofNullable(findLaborIn(globalLaborDefaults, ageBracket, sex));
  }

  /** ★ 查某家户自己的劳动覆盖行（不回落全局）：该户没有这条覆盖键 ⇒ 空。 */
  public Optional<LaborCoefficient> householdLaborOverride(
      HouseholdId householdId, AgeBracket ageBracket, Sex sex) {
    requireArg(householdId, "householdId");
    requireArg(ageBracket, "ageBracket");
    requireArg(sex, "sex");
    return Optional.ofNullable(findLaborIn(householdLaborOverrides.get(householdId), ageBracket, sex));
  }

  // ── 不可变 copy-with（GM 命令/窄工具的唯一写口；所有不变量仍由本 record 构造期校验）──────────

  /**
   * ★★ <b>全局需求默认表的 upsert</b>：同键 ⇒ 原位替换（保持表序）、新键 ⇒ 追加表尾；返回新实例。
   *
   * <p>旧档作废、不迁移：本方法只服务"从当前权威表构造下一份权威表"，不做任何旧字段兼容； 坏数据（null、负值、period/cycleDays 不自洽、重复键）
   * 一律由 {@link DemandCoefficient} 与 record 构造期具名拒。
   *
   * @param coefficient 新行；不得为 null
   */
  public SocialProvisioning withGlobalDemand(DemandCoefficient coefficient) {
    requireArg(coefficient, "coefficient");
    return new SocialProvisioning(
        upsertDemand(globalDemandDefaults, coefficient),
        globalLaborDefaults,
        householdDemandOverrides,
        householdLaborOverrides);
  }

  /**
   * ★★ <b>某家户需求覆盖的 upsert</b>：同 {@code (年龄档, 性别, 商品)} ⇒ 原位替换；新键 ⇒ 追加该户列表表尾。 该户原本没有覆盖 ⇒ 新建一张只含这一行的覆盖表；
   * 不触碰全局表与其它户。
   *
   * @param householdId 家户 id；不得为 null
   * @param coefficient 新覆盖行；不得为 null
   */
  public SocialProvisioning withHouseholdDemand(
      HouseholdId householdId, DemandCoefficient coefficient) {
    requireArg(householdId, "householdId");
    requireArg(coefficient, "coefficient");
    Map<HouseholdId, List<DemandCoefficient>> next =
        new LinkedHashMap<>(householdDemandOverrides);
    List<DemandCoefficient> existing = next.get(householdId);
    next.put(
        householdId,
        upsertDemand(existing == null ? List.of() : existing, coefficient));
    return new SocialProvisioning(
        globalDemandDefaults, globalLaborDefaults, next, householdLaborOverrides);
  }

  /**
   * ★★ <b>删除某家户的一条需求覆盖键</b>：删除后该键回落全局默认（全局没有该商品行时即为"该户不再有这条键"）。 该户列表删空 ⇒ 整户覆盖键一并移除（不落空表）。
   * 该户或该键本来不存在 ⇒ 原样返回 {@code this}（幂等；命令层会先判存在性并具名拒，见 {@code SocialProvisioningEdits}）。
   *
   * @param householdId 家户 id；不得为 null
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   * @param commodity 商品 id；不得为 null
   */
  public SocialProvisioning withoutHouseholdDemand(
      HouseholdId householdId, AgeBracket ageBracket, Sex sex, CommodityId commodity) {
    requireArg(householdId, "householdId");
    requireArg(ageBracket, "ageBracket");
    requireArg(sex, "sex");
    requireArg(commodity, "commodity");
    List<DemandCoefficient> existing = householdDemandOverrides.get(householdId);
    if (existing == null) {
      return this;
    }
    List<DemandCoefficient> next = new ArrayList<>(existing.size());
    boolean removed = false;
    for (DemandCoefficient coefficient : existing) {
      if (!removed
          && coefficient.ageBracket() == ageBracket
          && coefficient.sex() == sex
          && coefficient.commodity().equals(commodity)) {
        removed = true;
        continue;
      }
      next.add(coefficient);
    }
    if (!removed) {
      return this;
    }
    Map<HouseholdId, List<DemandCoefficient>> nextOverrides =
        new LinkedHashMap<>(householdDemandOverrides);
    if (next.isEmpty()) {
      nextOverrides.remove(householdId);
    } else {
      nextOverrides.put(householdId, next);
    }
    return new SocialProvisioning(
        globalDemandDefaults, globalLaborDefaults, nextOverrides, householdLaborOverrides);
  }

  /**
   * ★★ <b>全局劳动默认表的 upsert</b>：同 {@code (年龄档, 性别)} ⇒ 原位替换、新键 ⇒ 追加表尾。旧档作废、不迁移。
   *
   * @param coefficient 新行；不得为 null
   */
  public SocialProvisioning withGlobalLabor(LaborCoefficient coefficient) {
    requireArg(coefficient, "coefficient");
    return new SocialProvisioning(
        globalDemandDefaults,
        upsertLabor(globalLaborDefaults, coefficient),
        householdDemandOverrides,
        householdLaborOverrides);
  }

  /** ★★ <b>某家户劳动覆盖的 upsert</b>（同键原位替换、新键追加；不触碰全局表与其它户）。 */
  public SocialProvisioning withHouseholdLabor(
      HouseholdId householdId, LaborCoefficient coefficient) {
    requireArg(householdId, "householdId");
    requireArg(coefficient, "coefficient");
    Map<HouseholdId, List<LaborCoefficient>> next = new LinkedHashMap<>(householdLaborOverrides);
    List<LaborCoefficient> existing = next.get(householdId);
    next.put(householdId, upsertLabor(existing == null ? List.of() : existing, coefficient));
    return new SocialProvisioning(
        globalDemandDefaults, globalLaborDefaults, householdDemandOverrides, next);
  }

  /**
   * ★★ <b>删除某家户的一条劳动覆盖键</b>：删除后回落全局默认；该户列表删空 ⇒ 整户覆盖键一并移除。本来不存在 ⇒ 原样返回 {@code this}。
   *
   * @param householdId 家户 id；不得为 null
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   */
  public SocialProvisioning withoutHouseholdLabor(
      HouseholdId householdId, AgeBracket ageBracket, Sex sex) {
    requireArg(householdId, "householdId");
    requireArg(ageBracket, "ageBracket");
    requireArg(sex, "sex");
    List<LaborCoefficient> existing = householdLaborOverrides.get(householdId);
    if (existing == null) {
      return this;
    }
    List<LaborCoefficient> next = new ArrayList<>(existing.size());
    boolean removed = false;
    for (LaborCoefficient coefficient : existing) {
      if (!removed && coefficient.ageBracket() == ageBracket && coefficient.sex() == sex) {
        removed = true;
        continue;
      }
      next.add(coefficient);
    }
    if (!removed) {
      return this;
    }
    Map<HouseholdId, List<LaborCoefficient>> nextOverrides =
        new LinkedHashMap<>(householdLaborOverrides);
    if (next.isEmpty()) {
      nextOverrides.remove(householdId);
    } else {
      nextOverrides.put(householdId, next);
    }
    return new SocialProvisioning(
        globalDemandDefaults, globalLaborDefaults, householdDemandOverrides, nextOverrides);
  }

  /** 表内 upsert：同键原位替换、新键追加表尾（保持既有表序；重复键由构造期再兜一道）。 */
  private static List<DemandCoefficient> upsertDemand(
      List<DemandCoefficient> source, DemandCoefficient coefficient) {
    List<DemandCoefficient> next = new ArrayList<>(source.size() + 1);
    boolean replaced = false;
    for (DemandCoefficient existing : source) {
      if (existing.key().equals(coefficient.key())) {
        next.add(coefficient);
        replaced = true;
      } else {
        next.add(existing);
      }
    }
    if (!replaced) {
      next.add(coefficient);
    }
    return next;
  }

  /** 表内 upsert：同键原位替换、新键追加表尾（保持既有表序；重复键由构造期再兜一道）。 */
  private static List<LaborCoefficient> upsertLabor(
      List<LaborCoefficient> source, LaborCoefficient coefficient) {
    List<LaborCoefficient> next = new ArrayList<>(source.size() + 1);
    boolean replaced = false;
    for (LaborCoefficient existing : source) {
      if (existing.key().equals(coefficient.key())) {
        next.add(coefficient);
        replaced = true;
      } else {
        next.add(existing);
      }
    }
    if (!replaced) {
      next.add(coefficient);
    }
    return next;
  }

  /**
   * ★★ <b>某商品的已知默认口径</b>（只读查询）：在 {@link #globalDemandDefaults()} 里找该商品，返回其 {@link DemandBasis}；没有 ⇒
   * {@link Optional#empty()}（"这个商品不在默认表里"是合法查询结果，不是坏数据）。
   *
   * <p>同一商品在默认表里若出现互相矛盾的口径（{@code PER_CYCLE_DAYS(120)} 与 {@code PER_CALENDAR_YEAR} 各写一行）⇒
   * 具名拒：逐日展开按"商品一个口径"汇总，矛盾表无法给出唯一解释。
   *
   * @param commodity 商品 id；不得为 null
   * @throws IllegalArgumentException {@code commodity} 为 null，或默认表对同一商品给了多个口径
   */
  public Optional<DemandBasis> globalDemandBasis(CommodityId commodity) {
    requireArg(commodity, "commodity");
    DemandBasis found = null;
    for (DemandCoefficient coefficient : globalDemandDefaults) {
      if (!coefficient.commodity().equals(commodity)) {
        continue;
      }
      DemandBasis basis = coefficient.basis();
      if (found == null) {
        found = basis;
      } else if (!found.equals(basis)) {
        throw ProvisioningReject.reject(
            "全局需求默认表对商品 " + commodity + " 给了多个时间口径: " + found + " vs " + basis);
      }
    }
    return Optional.ofNullable(found);
  }

  /**
   * ★★ <b>默认表的"商品 → 口径"只读视图</b>（保序不可变）：遍历 {@link #globalDemandDefaults()}，每个商品 取一份 {@link
   * DemandBasis}。供后续逐日展开与调参读口使用——调用方因此**不需要硬编码 grain/cloth 的名字， 也不需要自己解释 {@link DemandPeriod}**。
   *
   * <p>同一商品出现矛盾口径 ⇒ 具名拒（理由同 {@link #globalDemandBasis}）。返回的 map 是新的不可变拷贝， 迭代序 = 默认表里商品首次出现的顺序。
   *
   * @throws IllegalArgumentException 默认表对同一商品给了多个口径
   */
  public Map<CommodityId, DemandBasis> globalDemandBases() {
    Map<CommodityId, DemandBasis> bases = new LinkedHashMap<>();
    for (DemandCoefficient coefficient : globalDemandDefaults) {
      CommodityId commodity = coefficient.commodity();
      DemandBasis basis = coefficient.basis();
      DemandBasis existing = bases.get(commodity);
      if (existing == null) {
        bases.put(commodity, basis);
      } else if (!existing.equals(basis)) {
        throw ProvisioningReject.reject(
            "全局需求默认表对商品 " + commodity + " 给了多个时间口径: " + existing + " vs " + basis);
      }
    }
    return Collections.unmodifiableMap(bases);
  }

  // ── 冻结/校验（构造期唯一入口）────────────────────────────────────────────────────────

  /** 需求默认表：保序、冻结、逐项 null 拒、三维键重复拒。 */
  private static List<DemandCoefficient> freezeDemandList(
      List<DemandCoefficient> source, String field) {
    if (source == null) {
      throw ProvisioningReject.reject(field + " 不得为 null（空表用 List.of()）");
    }
    List<DemandCoefficient> copy = new ArrayList<>(source.size());
    Set<DemandCoefficient.DemandKey> keys = new LinkedHashSet<>();
    for (int index = 0; index < source.size(); index++) {
      DemandCoefficient coefficient = source.get(index);
      if (coefficient == null) {
        throw ProvisioningReject.reject(field + " 第 " + index + " 项不得为 null");
      }
      if (!keys.add(coefficient.key())) {
        throw ProvisioningReject.reject(
            field + " 出现重复键 " + coefficient.key() + "（(年龄档, 性别, 商品) 必须唯一）");
      }
      copy.add(coefficient);
    }
    return Collections.unmodifiableList(copy);
  }

  /** 劳动默认表：保序、冻结、逐项 null 拒、二维键重复拒。 */
  private static List<LaborCoefficient> freezeLaborList(
      List<LaborCoefficient> source, String field) {
    if (source == null) {
      throw ProvisioningReject.reject(field + " 不得为 null（空表用 List.of()）");
    }
    List<LaborCoefficient> copy = new ArrayList<>(source.size());
    Set<LaborCoefficient.LaborKey> keys = new LinkedHashSet<>();
    for (int index = 0; index < source.size(); index++) {
      LaborCoefficient coefficient = source.get(index);
      if (coefficient == null) {
        throw ProvisioningReject.reject(field + " 第 " + index + " 项不得为 null");
      }
      if (!keys.add(coefficient.key())) {
        throw ProvisioningReject.reject(field + " 出现重复键 " + coefficient.key() + "（(年龄档, 性别) 必须唯一）");
      }
      copy.add(coefficient);
    }
    return Collections.unmodifiableList(copy);
  }

  /** 逐家户需求覆盖：家户键与列表都不得 null；每户列表内部三维键唯一。 */
  private static Map<HouseholdId, List<DemandCoefficient>> freezeDemandOverrides(
      Map<HouseholdId, List<DemandCoefficient>> source, String field) {
    if (source == null) {
      throw ProvisioningReject.reject(field + " 不得为 null（无覆盖用 Map.of()）");
    }
    Map<HouseholdId, List<DemandCoefficient>> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, List<DemandCoefficient>> entry : source.entrySet()) {
      if (entry.getKey() == null) {
        throw ProvisioningReject.reject(field + " 不得含 null 家户键");
      }
      if (entry.getValue() == null) {
        throw ProvisioningReject.reject(
            field + " 的家户 " + entry.getKey() + " 覆盖列表不得为 null（空覆盖用 List.of()）");
      }
      copy.put(
          entry.getKey(), freezeDemandList(entry.getValue(), field + "[" + entry.getKey() + "]"));
    }
    return Collections.unmodifiableMap(copy);
  }

  /** 逐家户劳动覆盖：家户键与列表都不得 null；每户列表内部二维键唯一。 */
  private static Map<HouseholdId, List<LaborCoefficient>> freezeLaborOverrides(
      Map<HouseholdId, List<LaborCoefficient>> source, String field) {
    if (source == null) {
      throw ProvisioningReject.reject(field + " 不得为 null（无覆盖用 Map.of()）");
    }
    Map<HouseholdId, List<LaborCoefficient>> copy = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, List<LaborCoefficient>> entry : source.entrySet()) {
      if (entry.getKey() == null) {
        throw ProvisioningReject.reject(field + " 不得含 null 家户键");
      }
      if (entry.getValue() == null) {
        throw ProvisioningReject.reject(
            field + " 的家户 " + entry.getKey() + " 覆盖列表不得为 null（空覆盖用 List.of()）");
      }
      copy.put(
          entry.getKey(), freezeLaborList(entry.getValue(), field + "[" + entry.getKey() + "]"));
    }
    return Collections.unmodifiableMap(copy);
  }

  // ── 查找内部（覆盖/默认两条路径共用同一比较语义）────────────────────────────────────────

  /** 在一条需求表里找键；表为 null（该户没有覆盖）或没有该键 ⇒ null。 */
  private static DemandCoefficient findDemandIn(
      List<DemandCoefficient> coefficients, AgeBracket ageBracket, Sex sex, CommodityId commodity) {
    if (coefficients == null) {
      return null;
    }
    for (DemandCoefficient coefficient : coefficients) {
      if (coefficient.ageBracket() == ageBracket
          && coefficient.sex() == sex
          && coefficient.commodity().equals(commodity)) {
        return coefficient;
      }
    }
    return null;
  }

  /** 在一条劳动表里找键；表为 null（该户没有覆盖）或没有该键 ⇒ null。 */
  private static LaborCoefficient findLaborIn(
      List<LaborCoefficient> coefficients, AgeBracket ageBracket, Sex sex) {
    if (coefficients == null) {
      return null;
    }
    for (LaborCoefficient coefficient : coefficients) {
      if (coefficient.ageBracket() == ageBracket && coefficient.sex() == sex) {
        return coefficient;
      }
    }
    return null;
  }

  /** 引用参数的非 null 校验：坏参数据名拒（带日志），不把 null 混进键比较。 */
  private static void requireArg(Object value, String field) {
    if (value == null) {
      throw ProvisioningReject.reject("SocialProvisioning." + field + " 不得为 null");
    }
  }
}

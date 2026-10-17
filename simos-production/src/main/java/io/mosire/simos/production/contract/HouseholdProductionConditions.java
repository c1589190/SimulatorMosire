package io.mosire.simos.production.contract;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>家户生产条件（试算与执行的不可变入参）</b>（D-032 约束设计书 §4.2；用户 U8「单个家户选用某个 生产方式」）。
 *
 * <p>★★ <b>它为什么是"值"而不是"经济的状态对象"</b>：§4.2 原文「两个调用，<b>都不接受 economy 状态对象， 只接受不可变输入值</b>」。economy
 * 自己把"这家户能出多少劳动、手上有哪些资产、能凑出哪些原料、在相关经验条上 是什么水平"整理成本记录后传进来；production 不反向读 {@code EconomyData}（边界裁决
 * §2： 「production 不反向读取 EconomyData，不保留旧 D3 端口或为它造替代回读接口」）。
 *
 * <p>★★ <b>缺键语义都是"没有"，不是"猜一个"</b>：{@code assets}/{@code inputs} 缺键 ⇒ 0（该家户没有这种 资源）；{@code
 * masteryPerMille}/{@code experience} 缺键 ⇒ 0（还没有这门经验）——这两条都是<b>真实世界里的 常见状态</b>，不是配置错误，故按 0 处理而不是
 * fail-closed。★ 与"外部属性缺失"不同：属性的缺失意味着 <b>通路断掉</b>（fail-closed，见 {@link
 * ExternalAttributeFacts}），而"这家户没养过牛"是正常世界状态。
 *
 * <p>★ <b>保序不可变</b>：四张表一律 {@code LinkedHashMap} 拷贝后冻结（迭代序是内容的纯函数）。
 *
 * <p>★★ <b>{@code laborMilli} 是"本周期累计"，不是"每日"</b>：它表示本家户在<b>整个生产周期</b>内可供本方案使用的 劳动，调用方必须按"每日可用劳动 ×
 * 本方案 {@code cycleDays}"填；{@code probe} 内部再按周期天数折回日均（周期累计 ÷ {@code cycleDays}）。★ 不要与 economy 侧
 * {@code HouseholdEconomy.laborMilli()} 的<b>每 tick（日）</b>时间预算 混淆 —— 直接传日值会把规模低估 {@code cycleDays}
 * 倍（D-032 §15.6 / R6 的 C-D 对照）。
 *
 * @param household 家户稳定身份；不得为 null
 * @param laborMilli 本家户可供本方案使用的劳动（千分劳动·日），口径为<b>本周期累计</b>（= 每日可用 × 本方案 {@code cycleDays}；{@code
 *     probe} 内部按周期天数折回日均）。★ 与 {@code HouseholdEconomy.laborMilli()} 的 <b>每日</b>口径区分：不得直接传日值，否则规模被低估
 *     {@code cycleDays} 倍；不得为负
 * @param assets 该家户可用生产资料（按种类）；不得为 null、逐值不得为负
 * @param inputs 该家户可投入的原料（按商品，毫单位）；不得为 null、逐值不得为负
 * @param masteryPerMille 该家户在相关经验条上的<b>熟练度</b>（千分制）；不得为 null、逐值不得为负
 * @param experience 该家户在相关经验条上的<b>原始经验值</b>；不得为 null、逐值不得为负
 */
public record HouseholdProductionConditions(
    HouseholdId household,
    long laborMilli,
    Map<AssetKind, Long> assets,
    Map<CommodityId, Long> inputs,
    Map<ExperienceTrackId, Long> masteryPerMille,
    Map<ExperienceTrackId, Long> experience) {

  public HouseholdProductionConditions {
    Objects.requireNonNull(household, "HouseholdProductionConditions.household 不得为 null");
    if (laborMilli < 0L) {
      throw new IllegalArgumentException(
          "HouseholdProductionConditions.laborMilli 不得为负: " + laborMilli);
    }
    // ★★ B11：四张表**就地**逐字展开冻结（照 MarketTable.hexes / EconomyMeta 政策表 / ProductionData 的形制）。
    //   ★ 为什么必须写在本构造器里、不能抽私有 helper：SpotBugs 的 EI_EXPOSE_REP **不做跨过程分析**，只认它在这
    //     一个方法里看得见的 Collections.unmodifiableMap ⇒ 抽成 helper 会让生成的 record 访问器被报成"暴露内部
    //     表示"（本类 4 条）。★ 语义与抽 helper 时**逐字节相同**：同一保序拷贝、同一组守卫、同一批拒绝消息。
    if (assets == null) {
      throw new IllegalArgumentException("HouseholdProductionConditions.assets 不得为 null（无资源用空表）");
    }
    Map<AssetKind, Long> assetsCopy = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : assets.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("HouseholdProductionConditions.assets 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "HouseholdProductionConditions.assets 不得为负：" + entry.getKey());
      }
      assetsCopy.put(entry.getKey(), entry.getValue());
    }
    assets = Collections.unmodifiableMap(assetsCopy); // ★ 冻在赋值处

    if (inputs == null) {
      throw new IllegalArgumentException("HouseholdProductionConditions.inputs 不得为 null（无资源用空表）");
    }
    Map<CommodityId, Long> inputsCopy = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : inputs.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("HouseholdProductionConditions.inputs 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "HouseholdProductionConditions.inputs 不得为负：" + entry.getKey());
      }
      inputsCopy.put(entry.getKey(), entry.getValue());
    }
    inputs = Collections.unmodifiableMap(inputsCopy); // ★ 冻在赋值处

    if (masteryPerMille == null) {
      throw new IllegalArgumentException(
          "HouseholdProductionConditions.masteryPerMille 不得为 null（无经验用空表）");
    }
    Map<ExperienceTrackId, Long> masteryCopy = new LinkedHashMap<>();
    for (Map.Entry<ExperienceTrackId, Long> entry : masteryPerMille.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "HouseholdProductionConditions.masteryPerMille 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "HouseholdProductionConditions.masteryPerMille 不得为负：" + entry.getKey());
      }
      masteryCopy.put(entry.getKey(), entry.getValue());
    }
    masteryPerMille = Collections.unmodifiableMap(masteryCopy); // ★ 冻在赋值处

    if (experience == null) {
      throw new IllegalArgumentException(
          "HouseholdProductionConditions.experience 不得为 null（无经验用空表）");
    }
    Map<ExperienceTrackId, Long> experienceCopy = new LinkedHashMap<>();
    for (Map.Entry<ExperienceTrackId, Long> entry : experience.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException(
            "HouseholdProductionConditions.experience 的键与值都不得为 null");
      }
      if (entry.getValue() < 0L) {
        throw new IllegalArgumentException(
            "HouseholdProductionConditions.experience 不得为负：" + entry.getKey());
      }
      experienceCopy.put(entry.getKey(), entry.getValue());
    }
    experience = Collections.unmodifiableMap(experienceCopy); // ★ 冻在赋值处
  }

  /** 该种类的可用量；缺键 ⇒ 0（"没有这种资源"，不是配置错误）。 */
  public long assetOf(AssetKind kind) {
    return kind == null ? 0L : assets.getOrDefault(kind, 0L);
  }

  /** 该商品的可用量（毫单位）；缺键 ⇒ 0。 */
  public long inputOf(CommodityId commodity) {
    return commodity == null ? 0L : inputs.getOrDefault(commodity, 0L);
  }

  /** 该经验条上的熟练度（千分制）；缺键 ⇒ 0。 */
  public long masteryOf(ExperienceTrackId track) {
    return track == null ? 0L : masteryPerMille.getOrDefault(track, 0L);
  }

  /** 该经验条上的原始经验值；缺键 ⇒ 0。 */
  public long experienceOf(ExperienceTrackId track) {
    return track == null ? 0L : experience.getOrDefault(track, 0L);
  }
}

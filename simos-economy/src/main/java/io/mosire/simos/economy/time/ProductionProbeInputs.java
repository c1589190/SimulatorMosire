package io.mosire.simos.economy.time;

import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionAvailabilityCondition;
import io.mosire.simos.production.contract.ProductionRuntime;
import io.mosire.simos.production.contract.ProductionTickCondition;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★★ <b>D-032 §15.12 A/B：economy 侧装配 {@code ProductionRuntime.probe} 入参的唯一拼写点</b>（纯函数；只读入参）。
 *
 * <p>★★ <b>为什么单列一个类</b>：§15.12 的 A（劳动分配排序链 {@code LaborQueueBook.offer}）与 B（迁移/候选排序链 {@code
 * ExpectedProfitBook.productionProspect}）都需要同一套"probe 入参"三件：
 *
 * <ol>
 *   <li><b>熟练度/经验</b>：优先读 {@link ProductionRuntime#currentCondition()}（本 tick 冻结口径，R5 / §15.5）， 没有
 *       tick 时退回运行时读口（{@code masteryOf}/{@code experienceOf}）；
 *   <li><b>资产占用</b>：{@code ProductionProbeRequest.assetOccupancy} 的契约是"逐值必须为正（0 不进表）"；
 *   <li><b>原料投入</b>：按方案 {@code inputPerUnit} 的键集从家户库存取（缺账户/缺键 ⇒ 0，与 {@code
 *       EconomyEntrySettlement#probeEntry} 同一口径）。
 * </ol>
 *
 * <p>★ <b>两处各写一份会漂开</b>（本仓"同一个东西两处各写一份"那一族）："读哪几条经验条、要不要看冻结条件"只要有一处 写歪，A 与 B 的预计就会在同一天里对同户算出两个数（I6
 * 的反面）。故判据只在这里。
 *
 * <p>★ <b>确定性</b>：全部按方案的键序/经验条首次出现序（保序 {@link LinkedHashMap}/{@link LinkedHashSet}），无随机、
 * 无时钟；同输入同输出。
 */
final class ProductionProbeInputs {

  private ProductionProbeInputs() {}

  /**
   * ★ 方案点名要读经验的<b>全部</b>经验条：专门条 a ∪ 共用条 b ∪ 可用性条件里的经验门槛（{@link
   * ProductionAvailabilityCondition.MinimumExperience}）。
   *
   * <p>★ <b>为什么还要收可用性条件那一条</b>：{@code probe} 的 {@code unsatisfiedCondition} 用 {@code
   * HouseholdProductionConditions#experienceOf} 读条件的经验门槛；漏收那条经验条 ⇒ 条件恒读 0 ⇒ 明明够门槛的方案 也被判 {@code
   * condition-unsatisfied}。它是"入参装配"的一部分，不是第二套条件判据（判据仍在 production）。
   */
  static Set<ExperienceTrackId> experienceTracksOf(IndustryDefinitionView plan) {
    Objects.requireNonNull(plan, "plan");
    Set<ExperienceTrackId> tracks = new LinkedHashSet<>();
    plan.experience().primaryTrack().ifPresent(tracks::add);
    plan.experience().sharedTrack().ifPresent(tracks::add);
    for (ProductionAvailabilityCondition condition : plan.availability()) {
      if (condition instanceof ProductionAvailabilityCondition.MinimumExperience minimum) {
        tracks.add(minimum.track());
      }
    }
    return tracks;
  }

  /**
   * ★ 逐经验条的<b>熟练度</b>（千分制）：本 tick 生效条件存在 ⇒ 读冻结值（R5 / §15.5 口径），否则读运行时读口。
   *
   * <p>★ 缺键 = 0 由 {@code probe} 侧解释（{@code HouseholdProductionConditions#masteryOf} 缺键 ⇒
   * 0）；这里只填方案点名的 那几条，不补零行（与 {@code EconomyEntrySettlement#probeEntry} 同制）。
   */
  static Map<ExperienceTrackId, Long> masteryOf(
      ProductionRuntime runtime, HouseholdId household, IndustryDefinitionView plan) {
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(household, "household");
    Optional<ProductionTickCondition> frozen = runtime.currentCondition();
    Map<ExperienceTrackId, Long> mastery = new LinkedHashMap<>();
    for (ExperienceTrackId track : experienceTracksOf(plan)) {
      mastery.put(
          track,
          frozen.isPresent()
              ? frozen.get().masteryOf(household, track)
              : runtime.masteryOf(household, track));
    }
    return mastery;
  }

  /** ★ 逐经验条的<b>原始经验值</b>：与 {@link #masteryOf} 同一来源选择（冻结条件优先），理由见其类注。 */
  static Map<ExperienceTrackId, Long> experienceOf(
      ProductionRuntime runtime, HouseholdId household, IndustryDefinitionView plan) {
    Objects.requireNonNull(runtime, "runtime");
    Objects.requireNonNull(household, "household");
    Optional<ProductionTickCondition> frozen = runtime.currentCondition();
    Map<ExperienceTrackId, Long> experience = new LinkedHashMap<>();
    for (ExperienceTrackId track : experienceTracksOf(plan)) {
      experience.put(
          track,
          frozen.isPresent()
              ? frozen.get().experienceOf(household, track)
              : runtime.experienceOf(household, track));
    }
    return experience;
  }

  /**
   * ★ <b>只保留正值项</b>：{@code ProductionProbeRequest.assetOccupancy} 的契约是"逐值必须为正（0 不进表）"， 而份额表/可用资产表允许
   * 0 数量行 ⇒ 进 probe 前必须先滤掉（否则请求构造器当场 IllegalArgumentException）。
   *
   * <p>★ 保序（{@link LinkedHashMap}）、不合并、不改值 —— 过滤不改变任何"多少"的读数。
   */
  static Map<AssetKind, Long> positiveAssets(Map<AssetKind, Long> assets) {
    Objects.requireNonNull(assets, "assets");
    Map<AssetKind, Long> positive = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : assets.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
        continue;
      }
      positive.put(entry.getKey(), entry.getValue());
    }
    return positive;
  }

  /**
   * ★ <b>durable 商品占用读数</b>：按方案 {@code durableCommodityCapacityPerUnit} 的键集从家户库存取正余额。
   *
   * <p>缺账户 / 缺键 = 0（该通道不构成约束）；负库存 fail-closed；键序取方案 durable 表的首次出现序。 与服务键/普通投入不同：本表只作规模锚，不进入 {@link
   * #inputsOf} 的原料表。
   */
  static Map<CommodityId, Long> durableCommodityOccupancyOf(
      Map<CommodityId, Long> householdGoods, IndustryDefinitionView plan) {
    Objects.requireNonNull(plan, "plan");
    Map<CommodityId, Long> goods = householdGoods == null ? Map.of() : householdGoods;
    Map<CommodityId, Long> durableOccupancy = new LinkedHashMap<>();
    for (CommodityId commodity : plan.durableCommodityCapacityPerUnit().keySet()) {
      long quantity = goods.getOrDefault(commodity, 0L);
      if (quantity < 0L) {
        throw new IllegalStateException(
            "durable-commodity-occupancy-negative: plan="
                + plan.id().value()
                + " commodity="
                + commodity.value()
                + " quantity="
                + quantity);
      }
      if (quantity > 0L) {
        durableOccupancy.put(commodity, quantity);
      }
    }
    return durableOccupancy;
  }

  /**
   * ★ 本户可投入的原料（按方案有效配方的 {@code inputPerUnit} 键集）：缺账户/缺键 ⇒ 0；负存量按 0 计（"拿不出来"，不是"负投入"）。
   *
   * <p>★ <b>键集为什么取 {@code plan.recipe().inputPerUnit()}</b>：{@code probe} 的原料通道按有效周期投入逐项读 {@code
   * inputs}。staged 方案的 legacy {@code plan.inputPerUnit()} 为空，只有 {@code recipe()} 会投影出 {@code
   * Σ_stage(startInput + tickInput × durationDays)}；把整张家户库存表搬进来只会让每个候选多拷一份无关商品（性能）， 读数逐值不变。
   */
  static Map<CommodityId, Long> inputsOf(
      Map<CommodityId, Long> householdGoods, IndustryDefinitionView plan) {
    Objects.requireNonNull(plan, "plan");
    Map<CommodityId, Long> goods = householdGoods == null ? Map.of() : householdGoods;
    Map<CommodityId, Long> inputs = new LinkedHashMap<>();
    for (CommodityId commodity : plan.recipe().inputPerUnit().keySet()) {
      inputs.put(commodity, Math.max(0L, goods.getOrDefault(commodity, 0L)));
    }
    return inputs;
  }
}

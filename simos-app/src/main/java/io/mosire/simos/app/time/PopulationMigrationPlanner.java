package io.mosire.simos.app.time;

import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.population.LotMigration;
import io.mosire.simos.economy.model.MigrationPolicy;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.city.SocialCity;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.social.population.PopulationLots;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>P8 迁移纯规划器</b>：读 social 的农村批次与各城读数，产出一份<b>不改任何状态</b>的 {@link LotMigration} 计划。
 *
 * <pre>
 * 输入：SocialData（农村批次 + 城市落点）
 *       List&lt;CityMigrationReading&gt;（每城的交易/商人/利润/粮压/拥挤读数）
 *       MigrationPolicy（权重、阈值、迁出率、源批次保留）
 *       tick（只进 reason，不参与任何“随机/时钟”判断）
 * 输出：List&lt;LotMigration&gt;（城市按 pull 降序、城市内按 PeopleLotId 稳定序）
 * </pre>
 *
 * <p>★★ <b>本类五条硬纪律</b>：
 *
 * <ol>
 *   <li><b>纯</b>：不改 {@code SocialData}、不写盘、不用随机/时钟；同一份入参恒得同一份计划（含列表序）；
 *   <li><b>目标批次走唯一命名口</b>：{@code PopulationLots.urban(cityId, sex, cohort)}；{@code cohort} 由 {@link
 *       PopulationLots#cohortOf(PeopleLotId)} 从源批次取回 ⇒ 本类不拼第二套批次 id；
 *   <li><b>不把单批抽到 0 以下</b>：每批可迁 = {@code max(0, count − minimumSourceLotCount)}，剩余额度按稳定序批批扣；
 *   <li><b>确定性取整</b>：每城配额 = {@code ⌊剩余农村可迁人口 × migrationPerMille ÷ 1000⌋}（整数 floor）；
 *   <li><b>城市优先级</b>：pull 降序；同 pull 按 {@link CityId#value()} 升序（消除 map 迭代序）。
 * </ol>
 *
 * <p>★★ <b>本类不读 {@code EconomyData}</b>：规划只看 social 与外部读数；把计划落到经济侧（{@code HouseholdEconomy}/债务）是
 * {@code LotMigrationBook} 的职责，跨切片“social 拆/合批次 + 经济侧行列 + 成员份额对账”的原子接线留给 P9。
 */
public final class PopulationMigrationPlanner {

  private PopulationMigrationPlanner() {}

  /**
   * ★ 无历史迟滞的规划入口（默认路径）：对每座城用 {@link MigrationPolicy#shouldMigrate(long)} 单阈值判断。
   *
   * <p>★ {@code readings} 为空是<b>合法的默认输入</b> ⇒ 返回空计划（P8 接线位的默认 no-op 就建立在这条上）。
   */
  public static List<LotMigration> plan(
      SocialData social, List<CityMigrationReading> readings, MigrationPolicy policy, long tick) {
    return plan(social, readings, policy, tick, Set.of());
  }

  /**
   * ★★ <b>带迟滞的规划入口</b>：{@code currentlyMigratingCities} 是“上一轮已经在迁出”的城市集合（调用方显式给，本类不存 状态）；对这些城用
   * {@link MigrationPolicy#shouldMigrate(long, boolean)} 的退出阈值，形成防抖。
   *
   * @param currentlyMigratingCities 已处于迁移状态的城市（null ⇒ 抛）；不得含 null
   */
  public static List<LotMigration> plan(
      SocialData social,
      List<CityMigrationReading> readings,
      MigrationPolicy policy,
      long tick,
      Set<CityId> currentlyMigratingCities) {
    Objects.requireNonNull(social, "PopulationMigrationPlanner.plan 的 social 不得为 null");
    Objects.requireNonNull(readings, "PopulationMigrationPlanner.plan 的 readings 不得为 null");
    Objects.requireNonNull(policy, "PopulationMigrationPlanner.plan 的 policy 不得为 null");
    Objects.requireNonNull(
        currentlyMigratingCities,
        "PopulationMigrationPlanner.plan 的 currentlyMigratingCities 不得为 null");
    if (tick < 0L) {
      throw new IllegalArgumentException("PopulationMigrationPlanner.plan 的 tick 不得为负: " + tick);
    }
    LinkedHashSet<CityId> seenCities = new LinkedHashSet<>();
    List<CityPlan> cityPlans = new ArrayList<>();
    for (int i = 0; i < readings.size(); i++) {
      CityMigrationReading reading = readings.get(i);
      if (reading == null) {
        throw new IllegalArgumentException(
            "PopulationMigrationPlanner.plan 的 readings[" + i + "] 不得为 null");
      }
      if (!seenCities.add(reading.cityId())) {
        throw new IllegalArgumentException(
            "PopulationMigrationPlanner.plan 的 readings 不得重复城市: " + reading.cityId());
      }
      SocialCity city = social.cities().get(reading.cityId());
      if (city == null) {
        throw new IllegalArgumentException(
            "迁移读数的城市不在 social.cities 里（fail-closed，不静默跳过）: " + reading.cityId());
      }
      long pull =
          policy.urbanPull(
              reading.perCapitaTradeVolumePerMille(),
              reading.merchantDensityPerMille(),
              reading.workshopProfit(),
              reading.grainPriceOrGap(),
              reading.urbanCrowdingPerMille());
      if (policy.shouldMigrate(pull, currentlyMigratingCities.contains(reading.cityId()))) {
        cityPlans.add(new CityPlan(pull, city));
      }
    }
    // pull 降序；同 pull 按 CityId 规范串升序 ⇒ 列表序是内容的纯函数，不依赖 readings 的输入序。
    cityPlans.sort(
        Comparator.comparingLong(CityPlan::pull)
            .reversed()
            .thenComparing(plan -> plan.city().id().value()));
    if (cityPlans.isEmpty()) {
      return List.of();
    }
    List<PopulationGroup> ruralLots = new ArrayList<>();
    Map<PeopleLotId, Long> availableByLot = new LinkedHashMap<>();
    for (PopulationGroup group : social.groups().values()) {
      if (ResidenceKind.ofLot(group.id()) != ResidenceKind.RURAL) {
        continue;
      }
      ruralLots.add(group);
      long reserved = Math.min(group.count(), policy.minimumSourceLotCount());
      availableByLot.put(group.id(), Math.max(0L, group.count() - reserved));
    }
    ruralLots.sort(Comparator.comparing(group -> group.id().value()));
    List<LotMigration> migrations = new ArrayList<>();
    for (CityPlan cityPlan : cityPlans) {
      long remainingRural = 0L;
      for (long available : availableByLot.values()) {
        remainingRural = Math.addExact(remainingRural, available);
      }
      long quota = policy.migrationQuota(remainingRural);
      if (quota <= 0L) {
        continue;
      }
      long remaining = quota;
      for (PopulationGroup group : ruralLots) {
        if (remaining <= 0L) {
          break;
        }
        long available = availableByLot.get(group.id());
        if (available <= 0L) {
          continue;
        }
        long take = Math.min(remaining, available);
        PeopleLotId targetLot =
            PopulationLots.urban(
                cityPlan.city().id(), group.sex(), PopulationLots.cohortOf(group.id()));
        // ★ S2：源格只能从所属家户取（批次身上没有 residence）；RURAL 批次的家户必然在 HEX 上。
        HexCoord from =
            social
                .hexOfLot(group.id())
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "迁移源批次 " + group.id() + " 的家户不在 HEX 上（S2 的城乡迁移口径只认 hex 家户）"));
        migrations.add(
            new LotMigration(
                group.id(),
                targetLot,
                from,
                cityPlan.city().id(),
                cityPlan.city().at(),
                take,
                "urbanPull="
                    + cityPlan.pull()
                    + ";perMille="
                    + policy.migrationPerMille()
                    + ";tick="
                    + tick));
        availableByLot.put(group.id(), available - take);
        remaining -= take;
      }
    }
    return List.copyOf(migrations);
  }

  /** 一座城的候选（pull + 落点）；只在方法内使用。 */
  private record CityPlan(long pull, SocialCity city) {}
}

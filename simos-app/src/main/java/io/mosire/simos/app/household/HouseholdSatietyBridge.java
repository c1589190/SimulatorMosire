package io.mosire.simos.app.household;

import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>Z7d-1：经济断顿 → Social satietyPerMille 的纯函数回写桥</b>（住在 {@code simos-app}：唯一同时认识 economy 事实与
 * social 状态的组合根）。
 *
 * <p>输入 = 每个家户的"当日粮缺口 / 当日粮自然需求"（由 {@code PopulationEconomyTimeParticipant} 从经济侧 {@code
 * FlowRow.unmetNeed} 的日增量与当日注入需求现算）；输出 = 新的 {@link SocialData} 与逐户 {@link Outcome} 账。规则为 2026-10-23
 * 设计书 §5/§9 的冻结默认值：
 *
 * <pre>
 * 有粮缺口 ⇒ satiety ← max(0, satiety − ⌊150 × min(1000, ⌊缺口×1000÷需求⌋) ÷ 1000⌋)   // 快降
 * 无粮缺口 ⇒ satiety ← min(1000, satiety + 20)                                       // 慢升（下限 0）
 * </pre>
 *
 * <p>★ <b>为什么是纯函数</b>：同一 base + 同一 feed 列表 ⇒ 同一输出（"同日重放同结果"）；同一列表内同一家户重复出现 ⇒
 * 具名拒（不把两次写回叠成一次双倍跌幅）。桥内不读时钟、不读经济、不写日志、不改任何其它 Social 组件。
 *
 * <p>★ <b>边界</b>：feed 指向的家户不在 base 的 {@code households} 里（旧档/装配边界）⇒ 跳过并计数，绝不凭空建户；
 * 布维缺口不在这里处理（它只进经济读口/告警，不降劳动）。
 */
public final class HouseholdSatietyBridge {

  /** satiety 上限（毫；缺键语义 = 1000）。 */
  public static final long MAX_SATIETY_PER_MILLE = SocialData.SATIETY_FULL_PER_MILLE;

  /** 有缺口时每天最快下降的毫数（设计书 §9：−150‰/日 × 断顿比例）。 */
  public static final long FAST_DROP_PER_DAY_PER_MILLE = 150L;

  /** 无缺口时每天恢复的毫数（设计书 §9：+20‰/日）。 */
  public static final long SLOW_RECOVER_PER_DAY = 20L;

  private HouseholdSatietyBridge() {}

  /**
   * 一户一天的断顿事实（最少必要输入）。
   *
   * @param household 家户稳定身份；不得为 null
   * @param grainUnmetMilli 当日粮缺口（毫粮；≥ 0；0 = 吃饱）
   * @param grainNeedMilli 当日粮自然需求（毫粮；≥ 0；= 0 时缺口 &gt; 0 ⇒ 断顿比例按 1000‰ 封顶）
   */
  public record DailyFeed(HouseholdId household, long grainUnmetMilli, long grainNeedMilli) {

    public DailyFeed {
      Objects.requireNonNull(household, "household");
      if (grainUnmetMilli < 0L || grainNeedMilli < 0L) {
        throw new IllegalArgumentException(
            "HouseholdSatietyBridge.DailyFeed 两个数量都必须 ≥ 0: "
                + grainUnmetMilli
                + "/"
                + grainNeedMilli);
      }
    }
  }

  /**
   * 一户一天的折算账（可审计：缺口、需求、断顿比例、变化前后）。
   *
   * @param shortageRatioPerMille 断顿比例（无缺口时 = 0；有缺口时 = min(1000, ⌊缺口×1000÷需求⌋)，需求 0 ⇒ 1000）
   */
  public record Outcome(
      HouseholdId household,
      long grainUnmetMilli,
      long grainNeedMilli,
      long shortageRatioPerMille,
      long satietyBeforePerMille,
      long satietyAfterPerMille) {

    public Outcome {
      Objects.requireNonNull(household, "household");
      if (grainUnmetMilli < 0L
          || grainNeedMilli < 0L
          || shortageRatioPerMille < 0L
          || shortageRatioPerMille > MAX_SATIETY_PER_MILLE
          || satietyBeforePerMille < 0L
          || satietyBeforePerMille > MAX_SATIETY_PER_MILLE
          || satietyAfterPerMille < 0L
          || satietyAfterPerMille > MAX_SATIETY_PER_MILLE) {
        throw new IllegalArgumentException(
            "HouseholdSatietyBridge.Outcome 数量越界: unmet="
                + grainUnmetMilli
                + " need="
                + grainNeedMilli
                + " ratio="
                + shortageRatioPerMille
                + " before="
                + satietyBeforePerMille
                + " after="
                + satietyAfterPerMille);
      }
    }
  }

  /** 一次回写结果：新的 SocialData（无变化时返回 base 同一实例）+ 逐户账 + 跳过的不存在家户数。 */
  public record Report(SocialData data, List<Outcome> outcomes, long skippedUnknownHouseholds) {

    public Report {
      Objects.requireNonNull(data, "data");
      Objects.requireNonNull(outcomes, "outcomes");
      if (skippedUnknownHouseholds < 0L) {
        throw new IllegalArgumentException(
            "HouseholdSatietyBridge.Report.skippedUnknownHouseholds 不得为负: "
                + skippedUnknownHouseholds);
      }
      outcomes = Collections.unmodifiableList(new ArrayList<>(outcomes));
    }
  }

  /**
   * 把一批逐户断顿事实折算进 Social 的 satietyPerMille（整表替换；不存在的家户跳过并计数）。
   *
   * @param base 回写基准（本日生死结算后的 SocialData）；不得为 null
   * @param feeds 逐户事实（顺序无关；同一家户不得重复）；不得为 null、不得含 null
   * @return 新 SocialData（表格逐值不变时返回 base 本身）与可审计账
   * @throws IllegalArgumentException 含 null / 同一家户重复 / 数量为负（{@link DailyFeed} 构造期已判）
   */
  public static Report apply(SocialData base, List<DailyFeed> feeds) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(feeds, "feeds");
    if (feeds.isEmpty()) {
      return new Report(base, List.of(), 0L);
    }
    List<DailyFeed> ordered = new ArrayList<>(feeds.size());
    Set<HouseholdId> seen = new LinkedHashSet<>();
    for (DailyFeed feed : feeds) {
      DailyFeed checked = Objects.requireNonNull(feed, "feed");
      if (!seen.add(checked.household())) {
        throw new IllegalArgumentException(
            "HouseholdSatietyBridge.apply 同一家户重复出现（会把一次断顿叠加成两次跌幅）: " + checked.household());
      }
      ordered.add(checked);
    }
    ordered.sort(Comparator.comparing(feed -> feed.household().value()));
    Map<HouseholdId, Long> existing = base.satietyPerMille();
    LinkedHashMap<HouseholdId, Long> updated = new LinkedHashMap<>(existing);
    List<Outcome> outcomes = new ArrayList<>(ordered.size());
    long skippedUnknownHouseholds = 0L;
    for (DailyFeed feed : ordered) {
      HouseholdId household = feed.household();
      if (!base.households().containsKey(household)) {
        skippedUnknownHouseholds++; // 旧档/装配边界：没有这个 Social 户，绝不凭空建户
        continue;
      }
      long before = base.satietyPerMille(household);
      long shortageRatioPerMille = 0L;
      long after;
      if (feed.grainUnmetMilli() > 0L) {
        // 断顿比例 = min(1000, ⌊缺口×1000÷当日粮需求⌋)；需求为 0 而有缺口 ⇒ 1000‰（封顶）。
        shortageRatioPerMille =
            feed.grainNeedMilli() <= 0L
                ? MAX_SATIETY_PER_MILLE
                : Math.min(
                    MAX_SATIETY_PER_MILLE,
                    Math.floorDiv(
                        Math.multiplyExact(feed.grainUnmetMilli(), MAX_SATIETY_PER_MILLE),
                        feed.grainNeedMilli()));
        long drop =
            Math.floorDiv(
                Math.multiplyExact(FAST_DROP_PER_DAY_PER_MILLE, shortageRatioPerMille),
                MAX_SATIETY_PER_MILLE);
        after = Math.max(0L, before - drop);
      } else {
        after = Math.min(MAX_SATIETY_PER_MILLE, Math.addExact(before, SLOW_RECOVER_PER_DAY));
      }
      if (after != before) {
        updated.put(household, after); // 既有键原位覆盖（保序）；新户追加在表尾
      }
      outcomes.add(
          new Outcome(
              household,
              feed.grainUnmetMilli(),
              feed.grainNeedMilli(),
              shortageRatioPerMille,
              before,
              after));
    }
    SocialData next = updated.equals(existing) ? base : base.withSatietyPerMille(updated);
    return new Report(next, outcomes, skippedUnknownHouseholds);
  }
}

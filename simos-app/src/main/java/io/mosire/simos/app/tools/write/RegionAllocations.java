package io.mosire.simos.app.tools.write;

import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.actor.model.HouseholdInventory;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * ★★ <b>辖区抽取 / 组军共用的两份“瀑布”分摊助手</b>（辖区阶段 6/8）：从 region 里选来源、总量不足整条拒、按全序逐值扣满。
 *
 * <p>★★ <b>为什么必须只有这一份</b>：{@code simos.unit.levyRegion}（阶段 6）与 {@code simos.unit.raiseUnit}（阶段 8）
 * 抽的是同一批家户账与同一批人口批次，口径（可支配 = 余额 − 冻结、只抽 MALE + 成年档、降序瀑布、不足整条拒）必须逐字一致。
 * 两处各写一遍排序与扣减，就会在某个版本里悄悄漂开——本仓最贵的教训形态。故这里集中两份瀑布，{@link LevyRegionPlan} 与 {@link RaiseUnitPlan}
 * 都只准调它，不许再写第二份。
 *
 * <p>★ <b>家户账瀑布</b>（{@link #allocateAccounts}）：region 各 hex 上 {@link ActorKind#HOUSEHOLD} 的账 → 可用量
 * ≤ 0 的 不进来源表 → 按“可用量降序、同量按 {@link io.mosire.simos.actor.model.HouseholdAccountKey#toString()}
 * 升序”逐户扣满。 额度函数由调用方给（粮 / 钱各走 {@link io.mosire.simos.actor.model.AvailableStock} 的对应重载，本类不写减法）。
 *
 * <p>★ <b>人力瀑布</b>（{@link #allocateManpower}）：{@code social.groups()} 里家户位置在 region、{@link
 * Sex#MALE}、 且 {@code AgeBracket.of(clock.system(), clock.dayNumberOfTick(tick), ageDaysAt(tick))}
 * == {@link AgeBracket#ADULT} 的批次（年龄按<b>当前 tick + 历法现算</b>，15/60 整历法年、阈值不在本类另写） → count ≤ 0 的不进来源表
 * → 按“count 降序、id 升序”逐批扣满。
 *
 * <p>★ <b>不足 ⇒ 整条拒</b>：总可用 < requested 时抛具名 {@link IllegalArgumentException}（带 requested /
 * available / 缺口），不做部分抽取、不截断——{@link LevyRegionPlan} 与 {@link RaiseUnitPlan} 因此都在推导期 fail-closed。
 *
 * <p>★ <b>纯函数 / 确定性 / 保序不可变</b>：本类不碰 {@code ToolContext} / {@code CoreSimos} / 墙钟；排序键是内容的全序 （键在 Map
 * 里唯一、批次 id 全局唯一 ⇒ 无并列歧义）；返回的来源表按瀑布序、以 {@link List#copyOf} 冻住，调用方拿不到可变集合。 遍历的是传入状态里保序不可变的 {@code
 * LinkedHashMap}（{@code accounts} / {@code groups}），不依赖任何其它迭代序。
 */
final class RegionAllocations {

  private RegionAllocations() {}

  // ── 家户账瀑布 ───────────────────────────────────────────────────────────────────────

  /**
   * 一个维度的家户账分摊：收集 region 各 hex 上的 {@link ActorKind#HOUSEHOLD} 账 → 过滤可支配 ≤ 0 → 降序排 → 总量不足整条拒 → 逐户扣满。
   *
   * @param actors 该坐标上的 actor 切片（本方法只读；不查存在性、不写账）
   * @param region 来源区域（只取 {@code hexes()} 的格集；区域本身必须已由调用方确认存在）
   * @param label 维度名（{@code 粮}/{@code 钱}），只进拒因与内部不变量消息
   * @param requested 请求量（0 = 本维度整段跳过，返回 {@link AccountAllocation#skipped()}，不扫描来源）
   * @param availableOf 可用量的<b>唯一算法</b>（由调用方传 {@link io.mosire.simos.actor.model.AvailableStock}
   *     的对应重载——本类不写减法）
   * @throws IllegalArgumentException requested 为负、或来源总量不足（整条拒，不部分抽取）
   */
  static AccountAllocation allocateAccounts(
      ActorData actors,
      SocialData social,
      Region region,
      String label,
      long requested,
      ToLongFunction<HouseholdInventory> availableOf) {
    Objects.requireNonNull(actors, "actors");
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(label, "label");
    Objects.requireNonNull(availableOf, "availableOf");
    if (requested < 0L) {
      throw new IllegalArgumentException(label + " requested 不得为负: " + requested);
    }
    if (requested == 0L) {
      return AccountAllocation.skipped();
    }
    List<AccountCandidate> candidates = new ArrayList<>();
    for (Map.Entry<HouseholdAccountKey, HouseholdInventory> entry : actors.accounts().entrySet()) {
      HouseholdAccountKey key = entry.getKey();
      var household = social.households().get(key.household());
      if (household == null
          || !(household.location()
              instanceof io.mosire.simos.social.api.household.HouseholdLocation.Hex at)) {
        continue; // 不在 HEX 上的家户不参与按格瀑布
      }
      if (!region.hexes().contains(at.hex())) {
        continue;
      }
      long available = availableOf.applyAsLong(entry.getValue());
      if (available <= 0L) {
        continue; // 可支配为 0 的账供不出任何量，不进来源表（也不占用瀑布位次）。
      }
      candidates.add(
          new AccountCandidate(
              HouseholdActors.of(key.household()), at.hex(), available, key.toString()));
    }
    // ★ 瀑布全序：可用量降序、同量按账键规范串升序（键在 Map 里唯一 ⇒ 无并列歧义）。
    candidates.sort(
        Comparator.comparingLong((AccountCandidate candidate) -> candidate.available)
            .reversed()
            .thenComparing(candidate -> candidate.sortKey));
    long total = 0L;
    for (AccountCandidate candidate : candidates) {
      total = saturatedAdd(total, candidate.available);
    }
    requireEnough(label, requested, total);
    List<AccountSource> sources = new ArrayList<>();
    long remaining = requested;
    for (AccountCandidate candidate : candidates) {
      if (remaining == 0L) {
        break;
      }
      long take = Math.min(candidate.available, remaining);
      sources.add(new AccountSource(candidate.owner, candidate.at, take));
      remaining -= take;
    }
    if (remaining != 0L) {
      // 总量 ≥ requested 却分不满 = 内部不变量坏了（数字自相矛盾）⇒ 响亮失败，不静默截断。
      throw new IllegalStateException(
          "内部分摊不自洽：" + label + " requested=" + requested + "，仍有 " + remaining + " 未分满");
    }
    return new AccountAllocation(requested, total, List.copyOf(sources));
  }

  // ── 人力瀑布 ─────────────────────────────────────────────────────────────────────────

  /**
   * 人力维度的分摊：MALE + 成年档 + region 落点的批次，按 count 降序 / id 升序逐批抽满。
   *
   * @param social 该坐标上的 social 切片（本方法只读，不写批次）
   * @param region 来源区域（只取 {@code hexes()} 的格集）
   * @param tick 现算年龄用的当前世界日（年龄口径的唯一拼写点在 {@link PopulationGroup#ageDaysAt(long)}）
   * @param requested 请求量（0 = 本维度整段跳过，返回 {@link ManpowerAllocation#skipped()}，不扫描来源）
   * @param clock 历法时钟：tick→JDN 的唯一换算点（15/60 是整历法年）；生产路径由 {@code CalendarService.clock()}
   *     传入，本类不自造默认时钟
   * @throws IllegalArgumentException requested / tick 为负、或合格批次总人数不足（整条拒，不部分抽取）
   */
  static ManpowerAllocation allocateManpower(
      SocialData social, Region region, long tick, long requested, CalendarClock clock) {
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(clock, "clock");
    if (tick < 0L) {
      throw new IllegalArgumentException("tick 不得为负: " + tick);
    }
    if (requested < 0L) {
      throw new IllegalArgumentException("人力 requested 不得为负: " + requested);
    }
    if (requested == 0L) {
      return ManpowerAllocation.skipped();
    }
    long currentDayNumber = clock.dayNumberOfTick(tick);
    List<PopulationGroup> candidates = new ArrayList<>();
    for (PopulationGroup group : social.groups().values()) {
      if (group.sex() != Sex.MALE) {
        continue;
      }
      // ★ 家户架构 §4.2：位置只能从所属家户取（批次身上已没有 residence）；UNIT 家户没有格，不进 hex 辖区瀑布。
      if (!social.hexOfLot(group.id()).filter(region.hexes()::contains).isPresent()) {
        continue;
      }
      if (group.count() <= 0L) {
        continue; // 空批供不出人，不进来源表（count = 0 的批次本批次也不会被改写）。
      }
      // ★ 成年档的唯一拼写点在 AgeBracket：本类不另写 15/60 岁阈值。
      if (AgeBracket.of(clock.system(), currentDayNumber, group.ageDaysAt(tick))
          != AgeBracket.ADULT) {
        continue;
      }
      candidates.add(group);
    }
    candidates.sort(
        Comparator.comparingLong(PopulationGroup::count)
            .reversed()
            .thenComparing(group -> group.id().value()));
    long total = 0L;
    for (PopulationGroup group : candidates) {
      total = saturatedAdd(total, group.count());
    }
    requireEnough("人力", requested, total);
    List<GroupSource> sources = new ArrayList<>();
    long remaining = requested;
    for (PopulationGroup group : candidates) {
      if (remaining == 0L) {
        break;
      }
      long take = Math.min(group.count(), remaining);
      // ★ 入选时已确认有 HEX 位置；这里把来源格随批次冻进 GroupSource（下游载荷不再回头解析 lot id）。
      sources.add(new GroupSource(group, social.hexOfLot(group.id()).orElseThrow(), take));
      remaining -= take;
    }
    if (remaining != 0L) {
      throw new IllegalStateException(
          "内部分摊不自洽：人力 requested=" + requested + "，仍有 " + remaining + " 未分满");
    }
    return new ManpowerAllocation(requested, total, List.copyOf(sources));
  }

  // ── 校验小件 ─────────────────────────────────────────────────────────────────────────

  /** 总量不足 ⇒ 整条拒（带 requested / available / 缺口，不部分抽取、不截断）。 */
  private static void requireEnough(String label, long requested, long available) {
    if (available < requested) {
      throw new IllegalArgumentException(
          label
              + "总量不足：requested="
              + requested
              + "，available="
              + available
              + "，缺口="
              + (requested - available)
              + "（不部分抽取、不截断；先补来源或降低 requested）");
    }
  }

  /** 饱和加法（非负 long；溢出取 {@link Long#MAX_VALUE}）——只用于合计与比较，不参与逐值扣减。 */
  private static long saturatedAdd(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  // ── 结果与来源（全部保序不可变）──────────────────────────────────────────────────────

  /**
   * 一个家户账维度的分摊结果。
   *
   * @param requested 请求量（0 = 本维度整段跳过）
   * @param available 来源总量（requested = 0 时为 0 = 未求值；有请求时 = 全部合格来源的可支配量之和）
   * @param sources 实际扣减的来源（瀑布序；requested = 0 时为空）
   */
  record AccountAllocation(long requested, long available, List<AccountSource> sources) {

    AccountAllocation {
      if (requested < 0L) {
        throw new IllegalArgumentException("requested 不得为负: " + requested);
      }
      if (available < 0L) {
        throw new IllegalArgumentException("available 不得为负: " + available);
      }
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }

    static AccountAllocation skipped() {
      return new AccountAllocation(0L, 0L, List.of());
    }
  }

  /** 一个家户账来源：{@code (owner, 格)} 与从它扣走的数量（&gt; 0）。 */
  record AccountSource(ActorRef owner, HexCoord at, long amount) {

    AccountSource {
      Objects.requireNonNull(owner, "owner");
      Objects.requireNonNull(at, "at");
      if (amount <= 0L) {
        throw new IllegalArgumentException("amount 必须 > 0: " + amount);
      }
    }
  }

  /**
   * 人力一个维度的分摊结果。
   *
   * @param requested 请求量（0 = 本维度整段跳过）
   * @param available 全部合格批次的人数之和（requested = 0 时为 0 = 未求值）
   * @param sources 实际抽人的批次（瀑布序）
   */
  record ManpowerAllocation(long requested, long available, List<GroupSource> sources) {

    ManpowerAllocation {
      if (requested < 0L) {
        throw new IllegalArgumentException("requested 不得为负: " + requested);
      }
      if (available < 0L) {
        throw new IllegalArgumentException("available 不得为负: " + available);
      }
      sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }

    static ManpowerAllocation skipped() {
      return new ManpowerAllocation(0L, 0L, List.of());
    }
  }

  /** 一个被动批次：整组覆盖用的原始批次 + **它的来源格**（S2：位置来自家户，随来源一起冻住）+ 抽走的人数； {@code countAfter} 可为 0（合法空批）。 */
  record GroupSource(PopulationGroup group, HexCoord at, long taken) {

    GroupSource {
      Objects.requireNonNull(group, "group");
      Objects.requireNonNull(at, "at");
      if (taken <= 0L) {
        throw new IllegalArgumentException("taken 必须 > 0: " + taken);
      }
      if (taken > group.count()) {
        throw new IllegalArgumentException(
            "taken 不得超过批次人数: taken=" + taken + "，count=" + group.count());
      }
    }

    long countAfter() {
      return group.count() - taken;
    }
  }

  /** 账候选（排序用）：available 参与瀑布，sortKey 只用于同量并列的稳定定序。 */
  private record AccountCandidate(ActorRef owner, HexCoord at, long available, String sortKey) {}
}

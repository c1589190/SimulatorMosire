package io.mosire.simos.app.tools.write;

import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.household.Household;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.population.PopulationGroup;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>P1.0：share-aware 的辖区分人层</b>（P1.1 征兵 / P1.3 组军共用）。从一份 {@link SocialData} 的
 * <b>家户成员份额</b>里，按辖区优先级与全序瀑布抽出恰好 {@code requested} 人，逐步给出
 * {@code (householdId, lotId, hex, taken)}。
 *
 * <p>★★ <b>纯函数边界</b>：本类不 submit 任何命令、不碰 {@link io.mosire.agentlib.tool.ToolContext} /
 * {@code CoreSimos}、不写任何状态；只读传入的 {@code social} 并返回新造的不可变结果。同一状态 + 同一参数 ⇒ 逐字段相同的结果。
 *
 * <p>★★ <b>候选 = 家户份额，不是整批 count</b>：对每个候选家户，逐个遍历 {@code household.members()} 的
 * {@code (lotId, share)}，只取 {@code share > 0}。{@code share} 是该户对 lot 的份额；同一个 lot 被多个家户按份额持有时，
 * 每个家户各自成为一条独立候选（例如 hh-a share 6、hh-b share 4 会各出一条 {@code ManpowerShare}），<b>禁止</b>用旧
 * 单户持有读法 / 组覆盖读法 / 整批 {@code count} 来选人。{@code hex} 一律取自家户 {@code location()}，不得从批次反查位置。
 *
 * <p>★★ <b>属性过滤</b>：候选的 {@code lotId} 必须能在 {@code social.groups()} 里找到对应的
 * {@link PopulationGroup}（找不到，或该户份额超过 {@code group.count()} ⇒ 状态已破坏 SocialData 不变量，抛具名
 * {@link IllegalStateException}，<b>不静默跳过</b>）；{@code sexFilter} 非空时按 {@code group.sex()} 过滤；
 * {@code ageBracketFilter} 非空时用 {@link AgeBracket#of(io.mosire.simos.calendar.CalendarSystem, long, long)}
 * 当场现算（{@code clock.system()} + {@code clock.dayNumberOfTick(tick)} + {@code group.ageDaysAt(tick)}）后比对，
 * 不在本类另写 15/60 阈值。
 *
 * <p>★★ <b>辖区优先级与重叠</b>：{@code jurisdictionHexesInOrder} 的外层 {@link List} 顺序 = 辖区优先级顺序；内层
 * {@code Set<HexCoord>} 只表达“哪些格属于本辖区”，本类<b>不依赖 Set 的迭代序</b>。构造格 → 优先级索引时按外层顺序
 * {@code putIfAbsent}，因此同一 hex 出现在多个辖区（区域重叠）时取<b>第一个</b>出现的辖区；一个家户只有一处 location，
 * 故不会因重叠被算两次。
 *
 * <p>★★ <b>排序全序</b>：候选按“辖区优先级索引升序 → {@code householdId.value()} 字符串升序 →
 * {@code lotId.value()} 字符串升序”排序；这是内容的全序（家户 id + lot id 在各自的表里唯一 ⇒ 无并列歧义），
 * 与 {@code Set}/{@code Map} 的迭代序无关。
 *
 * <p>★★ <b>瀑布与守恒</b>：先对全部合格候选的 {@code share} 做<b>饱和加法</b>得 {@code available}（防 long 回绕；只用于合计与拒因）；
 * 再按排序顺序逐个 {@code take = min(share, remaining)}。请求量为正时，成功返回的
 * {@code Allocation.shares()} 满足 {@code Σ take == requested}，且每个单户条目 {@code take ≤ 该户对该 lot 的 share}。
 * 某户只被取走份额的一部分时，不会清掉该 lot、不会给 lot 派生新 id、也不改变其它家户的份额。
 *
 * <p>★★ <b>不足整条拒（不部分、不截断）</b>：{@code available < requested} 时抛具名
 * {@link IllegalArgumentException}（消息带 requested / available / 缺口，并说明“不部分抽取、不截断；先扩辖区或降低
 * requested”），绝不返回部分结果。分流后 {@code remaining != 0} 属于内部自相矛盾（总量已足够却分不满）⇒
 * {@link IllegalStateException}。
 *
 * <p>★★ <b>参数与失败语义</b>：{@code requested == 0} 在参数校验后立即返回 {@code new Allocation(0, 0, List.of())}
 * —— 0 = 整段跳过（§2.2 语义），不扫描任何候选；{@code requested < 0} / {@code tick < 0} / 入参为 null /
 * 辖区集合含 null hex / {@code excludedHouseholds} 含 null 元素 ⇒ 具名 {@link IllegalArgumentException}。
 * 因 SocialData 不变量已坏而失败时用 {@link IllegalStateException}（与参数错误区分）。
 *
 * <p>★★ <b>输出保序不可变</b>：{@link Allocation} 与 {@link ManpowerShare} 都在紧凑构造器里校验并以
 * {@link List#copyOf} 冻结；{@code shares()} 的顺序 = 瀑布选取顺序。
 *
 * <p>★ <b>P1.1 / P1.3 用法</b>（本层只选人；落 Social 家户工单由上层 plan 组装）：
 *
 * <pre>
 * HouseholdManpowerAllocator.Allocation allocation =
 *     HouseholdManpowerAllocator.allocateMalesOfAdult(
 *         social, List.of(region.hexes()), count, clock, tick, Set.of());
 * // 或显式过滤：
 * HouseholdManpowerAllocator.allocate(
 *     social, List.of(region.hexes()), count, clock, tick,
 *     Optional.of(Sex.MALE), Optional.of(AgeBracket.ADULT), Set.of(excludedHousehold));
 * </pre>
 */
final class HouseholdManpowerAllocator {

  private HouseholdManpowerAllocator() {}

  /**
   * 从多个辖区里抽人：外层 List 顺序 = 辖区优先级（同一 hex 重叠时取第一个出现的辖区），家户候选与份额口径见类注。
   *
   * @param social 社会状态（只读；家户位置、成员份额、批次属性都从这里取）
   * @param jurisdictionHexesInOrder 辖区的 hex 集合，按<b>辖区优先级顺序</b>排列；内层 Set 只表所属关系，不承诺迭代序
   * @param requested 请求人数（&ge; 0；0 = 整段跳过，返回空 shares）
   * @param clock 历法时钟（年龄档现算唯一拼写点；非空）
   * @param tick 当前世界日（年龄现算的现在；&ge; 0）
   * @param sexFilter 性别过滤（非空 Optional；空 = 不限）
   * @param ageBracketFilter 年龄档过滤（非空 Optional；空 = 不限；档位由 {@link AgeBracket#of} 现算）
   * @param excludedHouseholds 排除的家户 id 集合（非空；可为空集；不得含 null）
   * @return 恰好选满 {@code requested} 人的不可变 {@link Allocation}；{@code requested == 0} 时为空结果
   * @throws IllegalArgumentException 任一入参为 null、requested/tick 为负、辖区集合含 null hex、excluded 含 null，
   *     或合格份额总量不足（整条拒，不部分抽取）
   * @throws IllegalStateException 候选家户的 lot 不在 {@code social.groups()}，或份额超过批次人数（SocialData 不变量已破坏）
   */
  static Allocation allocate(
      SocialData social,
      List<Set<HexCoord>> jurisdictionHexesInOrder,
      long requested,
      CalendarClock clock,
      long tick,
      Optional<Sex> sexFilter,
      Optional<AgeBracket> ageBracketFilter,
      Set<HouseholdId> excludedHouseholds) {
    requireArg(social, "social");
    requireArg(jurisdictionHexesInOrder, "jurisdictionHexesInOrder");
    requireArg(clock, "clock");
    requireArg(sexFilter, "sexFilter");
    requireArg(ageBracketFilter, "ageBracketFilter");
    requireArg(excludedHouseholds, "excludedHouseholds");
    if (requested < 0L) {
      throw new IllegalArgumentException("requested 不得为负: " + requested);
    }
    if (tick < 0L) {
      throw new IllegalArgumentException("tick 不得为负: " + tick);
    }

    // ★ 辖区优先级：外层 List 顺序是权威；Set 只判断成员关系 ⇒ 这里不用 Set 的迭代序决定优先级。
    //   putIfAbsent 让同一 hex 的多个辖区分录都取第一个出现的索引（区域重叠规则）。
    Map<HexCoord, Integer> jurisdictionIndexByHex = new HashMap<>();
    for (int jurisdictionIndex = 0;
        jurisdictionIndex < jurisdictionHexesInOrder.size();
        jurisdictionIndex++) {
      Set<HexCoord> hexes = jurisdictionHexesInOrder.get(jurisdictionIndex);
      if (hexes == null) {
        throw new IllegalArgumentException(
            "jurisdictionHexesInOrder[" + jurisdictionIndex + "] 不得为 null");
      }
      for (HexCoord hex : hexes) {
        if (hex == null) {
          throw new IllegalArgumentException(
              "辖区 " + jurisdictionIndex + " 的 hex 集合含 null hex（辖区集合元素不得为 null）");
        }
        jurisdictionIndexByHex.putIfAbsent(hex, jurisdictionIndex);
      }
    }
    for (HouseholdId excluded : excludedHouseholds) {
      if (excluded == null) {
        throw new IllegalArgumentException("excludedHouseholds 不得含 null 元素");
      }
    }
    // ★ requested = 0：参数校验完成后整段跳过，不扫描 households / groups。
    if (requested == 0L) {
      return new Allocation(0L, 0L, List.of());
    }

    List<Candidate> candidates = new ArrayList<>();
    for (Household household : social.households().values()) {
      if (excludedHouseholds.contains(household.id())) {
        continue;
      }
      if (!(household.location() instanceof HouseholdLocation.Hex at)) {
        continue; // UNIT 家户没有格，不进 hex 辖区瀑布。
      }
      Integer jurisdictionIndex = jurisdictionIndexByHex.get(at.hex());
      if (jurisdictionIndex == null) {
        continue; // 不在任一辖区集合里的家户不是候选。
      }
      // ★ 必须从 household.members() 的份额出发；不得用旧的单户持有 / 组覆盖读法或整批 count 选人。
      for (Map.Entry<PeopleLotId, Long> member : household.members().entrySet()) {
        PeopleLotId lotId = member.getKey();
        long share = member.getValue();
        if (share <= 0L) {
          continue; // 份额为 0 的显式条目供不出人；份额为负是坏数据，但 SocialData 构造期已拒。
        }
        PopulationGroup group = social.groups().get(lotId);
        if (group == null) {
          throw new IllegalStateException(
              "家户 "
                  + household.id()
                  + " 持有批次 "
                  + lotId
                  + " 的份额，但该批次不在 social.groups()（SocialData 不变量已破坏，拒绝静默跳过）");
        }
        if (share > group.count()) {
          throw new IllegalStateException(
              "家户 "
                  + household.id()
                  + " 对批次 "
                  + lotId
                  + " 的份额超过批次人数：share="
                  + share
                  + "，count="
                  + group.count()
                  + "（SocialData 不变量已破坏，拒绝静默跳过）");
        }
        if (sexFilter.isPresent() && group.sex() != sexFilter.get()) {
          continue;
        }
        if (ageBracketFilter.isPresent()
            && AgeBracket.of(clock.system(), clock.dayNumberOfTick(tick), group.ageDaysAt(tick))
                != ageBracketFilter.get()) {
          continue; // 年龄档唯一拼写点在 AgeBracket，不在本类写 15/60。
        }
        candidates.add(
            new Candidate(household.id(), lotId, at.hex(), share, jurisdictionIndex));
      }
    }
    // ★ 全序：辖区序号升序 → 家户 id 字符串升序 → lot id 字符串升序。与任何 Set/Map 迭代序无关。
    candidates.sort(
        Comparator.comparingInt(Candidate::jurisdictionIndex)
            .thenComparing(candidate -> candidate.householdId().value())
            .thenComparing(candidate -> candidate.lotId().value()));

    long available = 0L;
    for (Candidate candidate : candidates) {
      available = saturatedAdd(available, candidate.share());
    }
    if (available < requested) {
      throw new IllegalArgumentException(
          "人力总量不足：requested="
              + requested
              + "，available="
              + available
              + "，缺口="
              + (requested - available)
              + "（不部分抽取、不截断；先扩辖区或降低 requested）");
    }

    List<ManpowerShare> shares = new ArrayList<>(candidates.size());
    long remaining = requested;
    for (Candidate candidate : candidates) {
      if (remaining == 0L) {
        break;
      }
      long take = Math.min(candidate.share(), remaining);
      shares.add(
          new ManpowerShare(candidate.householdId(), candidate.lotId(), candidate.hex(), take));
      remaining -= take;
    }
    if (remaining != 0L) {
      // available ≥ requested 却分不满 = 数字自相矛盾（饱和加法不可能反过来少算）⇒ 响亮失败，不静默截断。
      throw new IllegalStateException(
          "内部分摊不自洽：人力 requested="
              + requested
              + "，available="
              + available
              + "，仍有 "
              + remaining
              + " 未分满");
    }
    return new Allocation(requested, available, shares);
  }

  /**
   * 单辖区便捷入口：把 {@code jurisdictionHexes} 包成 {@code List.of(...)} 后委托
   * {@link #allocate(SocialData, List, long, CalendarClock, long, Optional, Optional, Set)}。
   *
   * @throws IllegalArgumentException {@code jurisdictionHexes} 为 null 或其余参数校验失败（见主入口）
   */
  static Allocation allocate(
      SocialData social,
      Set<HexCoord> jurisdictionHexes,
      long requested,
      CalendarClock clock,
      long tick,
      Optional<Sex> sexFilter,
      Optional<AgeBracket> ageBracketFilter,
      Set<HouseholdId> excludedHouseholds) {
    if (jurisdictionHexes == null) {
      throw new IllegalArgumentException("jurisdictionHexes 不得为 null");
    }
    return allocate(
        social,
        List.of(jurisdictionHexes),
        requested,
        clock,
        tick,
        sexFilter,
        ageBracketFilter,
        excludedHouseholds);
  }

  /**
   * 最常见的 MALE + {@link AgeBracket#ADULT} 口径便捷入口：委托主入口并固定
   * {@code Optional.of(Sex.MALE)} / {@code Optional.of(AgeBracket.ADULT)}。
   *
   * <p>★ P1.1 / P1.3 的推荐用法：
   * {@code allocateMalesOfAdult(social, List.of(region.hexes()), count, clock, tick, Set.of())}。
   *
   * @throws IllegalArgumentException 参数校验失败或合格份额总量不足（见主入口）
   */
  static Allocation allocateMalesOfAdult(
      SocialData social,
      List<Set<HexCoord>> jurisdictionHexesInOrder,
      long requested,
      CalendarClock clock,
      long tick,
      Set<HouseholdId> excludedHouseholds) {
    return allocate(
        social,
        jurisdictionHexesInOrder,
        requested,
        clock,
        tick,
        Optional.of(Sex.MALE),
        Optional.of(AgeBracket.ADULT),
        excludedHouseholds);
  }

  // ── 校验 / 加法小件 ────────────────────────────────────────────────────────────────

  /** null 入参一律具名 {@link IllegalArgumentException}（契约口径，不用 NPE）。 */
  private static void requireArg(Object value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " 不得为 null");
    }
  }

  /** 饱和加法（非负 long；溢出取 {@link Long#MAX_VALUE}）——只用于合计与拒因，不参与逐值扣减。 */
  private static long saturatedAdd(long left, long right) {
    return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
  }

  /** 排序/瀑布用的一个候选：家户对批次份额 + 家户落格 + 辖区优先级索引。 */
  private record Candidate(
      HouseholdId householdId, PeopleLotId lotId, HexCoord hex, long share, int jurisdictionIndex) {}

  // ── 结果（全部保序不可变）──────────────────────────────────────────────────────────

  /**
   * 一次选人结果。
   *
   * @param requested 请求人数（0 = 整段跳过）
   * @param available 全部合格候选的份额之和（饱和加法；requested = 0 时为 0 = 未求值）
   * @param shares 实际抽到的家户份额（瀑布选取顺序；requested = 0 时为空）
   */
  record Allocation(long requested, long available, List<ManpowerShare> shares) {

    Allocation {
      if (requested < 0L) {
        throw new IllegalArgumentException("requested 不得为负: " + requested);
      }
      if (available < 0L) {
        throw new IllegalArgumentException("available 不得为负: " + available);
      }
      shares = List.copyOf(requireNonNullArg(shares, "shares"));
      if (requested == 0L && !shares.isEmpty()) {
        throw new IllegalArgumentException("requested == 0 时 shares 必须为空（0 = 整段跳过）");
      }
      if (requested > 0L) {
        if (available < requested) {
          throw new IllegalArgumentException(
              "Allocation 的 available 不得小于 requested: available="
                  + available
                  + "，requested="
                  + requested);
        }
        long total = 0L;
        for (ManpowerShare share : shares) {
          total = saturatedAdd(total, share.taken());
        }
        if (total != requested) {
          throw new IllegalArgumentException(
              "Allocation 的 Σ taken 必须等于 requested: Σ=" + total + "，requested=" + requested);
        }
      }
    }
  }

  /**
   * 从某个家户对某个 lot 的份额里抽走的人数。
   *
   * @param householdId 来源家户 id
   * @param lotId 来源批次 id（保持原 id，不派生新 id）
   * @param hex 来源格 = 该家户 {@code location()} 的 hex（不得从批次反查位置）
   * @param taken 从该户份额里抽走的人数（&gt; 0）
   */
  record ManpowerShare(HouseholdId householdId, PeopleLotId lotId, HexCoord hex, long taken) {

    ManpowerShare {
      householdId = requireNonNullArg(householdId, "householdId");
      lotId = requireNonNullArg(lotId, "lotId");
      hex = requireNonNullArg(hex, "hex");
      if (taken <= 0L) {
        throw new IllegalArgumentException("taken 必须 > 0: " + taken);
      }
    }
  }

  /** 记录字段校验用的小件（返回原值，便于紧凑构造器赋值）。 */
  private static <T> T requireNonNullArg(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " 不得为 null");
    }
    return value;
  }
}

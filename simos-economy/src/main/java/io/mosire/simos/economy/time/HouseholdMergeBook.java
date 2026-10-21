package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.classes.HouseholdOwnershipLookup;
import io.mosire.simos.economy.api.classes.OwnershipClass;
import io.mosire.simos.economy.api.culture.HouseholdCultureKey;
import io.mosire.simos.economy.api.culture.HouseholdCultureKeyLookup;
import io.mosire.simos.economy.api.land.LandHolding;
import io.mosire.simos.economy.api.land.LandHoldingKey;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★★ <b>D-032 RC4-A：同 key 家户合并的纯只读计划簿</b>。
 *
 * <p>父序列：S7-B {@code PostLandTradeClassCheck}/S7-C 的下游合并判据。本类只<b>产出</b>稳定计划，<b>不执行</b>合户：
 * 不写地图/人口/LAND/账户/roster/enterprise，不构造 session，不产生 ChangeSet。
 *
 * <pre>
 * mergeKey = (hex, cultureKey, ownershipClass)   // D-032 C2 起 productionMode 不入 key
 * survivor = population 降序 → 该 hex LAND 降序 → HouseholdId.value() 升序
 * plan     = 同 key 且 members.size() &gt; 1 的稳定分组
 * </pre>
 *
 * <p>★★ <b>候选过滤</b>：只看 {@code base.classes()} 中 {@code population &gt; 0} 的户；ownershipClass 由调用方
 * 提供的跨切片只读 lookup 派生（不持久、不由 mode 推导）。候选不要求 class standing、currentPosition 或 classNature；{@code
 * cultureLookup} 返回 empty ⇒ 使用 {@link HouseholdCultureKey#missing()} 的显式空 key 并发 INFO {@code
 * CULTURE_KEY_MISSING}，<b>绝不</b>用 SocialClassId/生产方式/库存猜文化。
 *
 * <p>★★ <b>确定性</b>：分组只用 {@link LinkedHashMap} 暂存，输出前按 {@code hex → cultureKey → ownershipClass}
 * 全序排序；组内成员按 survivor 全序排序；不依赖任何输入 Map 的迭代序。
 */
public final class HouseholdMergeBook {

  private static final LogChannel LOG = EventLog.channel(EconomyLog.settlement());

  /** 合并 key：同 hex、同 culture key、同 ownershipClass 才允许进入同一组；生产方式不参与分组。 */
  public record MergeKey(
      HexCoord hex, HouseholdCultureKey cultureKey, OwnershipClass ownershipClass) {

    public MergeKey {
      Objects.requireNonNull(hex, "MergeKey.hex 不得为 null");
      Objects.requireNonNull(cultureKey, "MergeKey.cultureKey 不得为 null（缺失用 missing()）");
      Objects.requireNonNull(ownershipClass, "MergeKey.ownershipClass 不得为 null");
    }
  }

  /** 一个合并候选户在本 hex 的只读读数。 */
  public record Member(HouseholdId household, long population, long landMilliMuAtHex) {

    public Member {
      Objects.requireNonNull(household, "Member.household 不得为 null");
      if (population < 0L) {
        throw new IllegalArgumentException("Member.population 不得为负: " + population);
      }
      if (landMilliMuAtHex < 0L) {
        throw new IllegalArgumentException("Member.landMilliMuAtHex 不得为负: " + landMilliMuAtHex);
      }
    }
  }

  /** 一个待执行的稳定合并计划；members 至少两个，survivor 必须属于 members。 */
  public record MergePlan(MergeKey key, List<Member> members, HouseholdId survivor) {

    public MergePlan {
      Objects.requireNonNull(key, "MergePlan.key 不得为 null");
      Objects.requireNonNull(members, "MergePlan.members 不得为 null");
      Objects.requireNonNull(survivor, "MergePlan.survivor 不得为 null");
      List<Member> copy = new ArrayList<>(members.size());
      for (Member member : members) {
        if (member == null) {
          throw new IllegalArgumentException("MergePlan.members 不得含 null");
        }
        copy.add(member);
      }
      if (copy.size() <= 1) {
        throw new IllegalArgumentException("MergePlan.members 必须多于 1 个（单户不需要合并）: " + copy.size());
      }
      boolean survivorPresent = false;
      for (Member member : copy) {
        if (member.household().equals(survivor)) {
          survivorPresent = true;
          break;
        }
      }
      if (!survivorPresent) {
        throw new IllegalArgumentException("MergePlan.survivor 必须属于 members: " + survivor.value());
      }
      members = List.copyOf(copy);
    }
  }

  /** survivor：人口最多 → 本 hex LAND 最多 → HouseholdId 最小。成员列表也按同一全序稳定排列。 */
  private static final Comparator<Member> MEMBER_ORDER =
      Comparator.comparingLong(Member::population)
          .reversed()
          .thenComparing(Comparator.comparingLong(Member::landMilliMuAtHex).reversed())
          .thenComparing(member -> member.household().value());

  /** 计划输出全序：hex 自然序 → cultureKey 结构串序 → ownershipClass 枚举序。 */
  private static final Comparator<MergeKey> KEY_ORDER =
      Comparator.comparing(MergeKey::hex)
          .thenComparing(MergeKey::cultureKey, HouseholdMergeBook::compareCultureKey)
          .thenComparing(MergeKey::ownershipClass);

  private HouseholdMergeBook() {}

  /**
   * 生成所有 {@code members.size() &gt; 1} 的同 key 合并计划；纯只读、每次返回不可变快照。
   *
   * @param base 当刻经济只读视图；不得为 null
   * @param landHoldings 当刻 LAND 工作副本；不得为 null
   * @param cultureLookup 家户文化 key 只读口；不得为 null（无 culture 切片请传 unavailable）
   * @param ownershipLookup 家户 ownershipClass 只读口（同一份当刻资产）；不得为 null
   * @return 按 {@link MergeKey} 稳定排序的不可变计划列表；没有可合并组 ⇒ 空列表
   */
  public static List<MergePlan> plan(
      EconomyDayView base,
      Map<LandHoldingKey, LandHolding> landHoldings,
      HouseholdCultureKeyLookup cultureLookup,
      HouseholdOwnershipLookup ownershipLookup) {
    Objects.requireNonNull(base, "HouseholdMergeBook.plan 的 base 不得为 null");
    Objects.requireNonNull(landHoldings, "HouseholdMergeBook.plan 的 landHoldings 不得为 null");
    Objects.requireNonNull(
        cultureLookup, "HouseholdMergeBook.plan 的 cultureLookup 不得为 null（缺实现用 unavailable()）");
    Objects.requireNonNull(
        ownershipLookup,
        "HouseholdMergeBook.plan 的 ownershipLookup 不得为 null（production 路径必须传派生读口）");

    Map<MergeKey, List<Member>> grouped = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, HouseholdEconomy> entry : base.classes().entrySet()) {
      HouseholdId household = Objects.requireNonNull(entry.getKey(), "classes 不得含 null 键");
      HouseholdEconomy row = Objects.requireNonNull(entry.getValue(), "classes 不得含 null 值");
      HexCoord hex = row.view().hex();
      if (row.population() <= 0L) {
        logSkip("zero-population", household, hex, null);
        continue;
      }

      OwnershipClass ownershipClass = ownershipLookup.ownershipClassOf(household);
      if (ownershipClass == null) {
        throw new IllegalStateException(
            "HouseholdOwnershipLookup.ownershipClassOf 不得返回 null: household=" + household.value());
      }

      HouseholdCultureKey cultureKey = cultureKeyOf(cultureLookup, household, hex, ownershipClass);
      Member member =
          new Member(household, row.population(), landMilliMuAtHex(landHoldings, hex, household));
      MergeKey key = new MergeKey(hex, cultureKey, ownershipClass);
      grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(member);
    }

    List<MergePlan> plans = new ArrayList<>();
    for (Map.Entry<MergeKey, List<Member>> entry : grouped.entrySet()) {
      if (entry.getValue().size() <= 1) {
        continue;
      }
      List<Member> members = new ArrayList<>(entry.getValue());
      members.sort(MEMBER_ORDER);
      plans.add(new MergePlan(entry.getKey(), members, members.get(0).household()));
    }
    plans.sort(Comparator.comparing(MergePlan::key, KEY_ORDER));
    return List.copyOf(plans);
  }

  private static HouseholdCultureKey cultureKeyOf(
      HouseholdCultureKeyLookup cultureLookup,
      HouseholdId household,
      HexCoord hex,
      OwnershipClass ownershipClass) {
    Optional<HouseholdCultureKey> found = cultureLookup.cultureKeyOf(household);
    if (found == null) {
      throw new IllegalStateException(
          "HouseholdCultureKeyLookup.cultureKeyOf 不得返回 null: household=" + household.value());
    }
    if (found.isPresent()) {
      return found.get();
    }
    HouseholdCultureKey missing = HouseholdCultureKey.missing();
    if (LOG.isInfoEnabled()) {
      LOG.info(
          LogEvent.of(
              "CULTURE_KEY_MISSING",
              EconomyLogSource.ECONOMY_SETTLEMENT,
              "household",
              household.value(),
              "hex",
              hex.toString(),
              "ownershipClass",
              ownershipClass.name(),
              "fallback",
              "explicit-empty-key"));
    }
    return missing;
  }

  private static long landMilliMuAtHex(
      Map<LandHoldingKey, LandHolding> landHoldings, HexCoord hex, HouseholdId household) {
    LandHolding holding = landHoldings.get(new LandHoldingKey(hex, household));
    return holding == null ? 0L : holding.quantityMilliMu();
  }

  private static void logSkip(String reason, HouseholdId household, HexCoord hex, String detail) {
    if (!LOG.isDebugEnabled()) {
      return;
    }
    LOG.debug(
        LogEvent.of(
            "HOUSEHOLD_MERGE_SKIPPED",
            EconomyLogSource.ECONOMY_SETTLEMENT,
            "reason",
            reason,
            "household",
            household.value(),
            "hex",
            hex.toString(),
            "detail",
            detail));
  }

  /** cultureKey 的结构化全序；customs 已由 {@link HouseholdCultureKey} 排序去重。 */
  private static int compareCultureKey(HouseholdCultureKey left, HouseholdCultureKey right) {
    int byCulture = left.cultureId().compareTo(right.cultureId());
    if (byCulture != 0) {
      return byCulture;
    }
    int byReligion = left.religionId().compareTo(right.religionId());
    if (byReligion != 0) {
      return byReligion;
    }
    int byCustoms = compareStringLists(left.customs(), right.customs());
    if (byCustoms != 0) {
      return byCustoms;
    }
    return left.label().compareTo(right.label());
  }

  private static int compareStringLists(List<String> left, List<String> right) {
    int shared = Math.min(left.size(), right.size());
    for (int index = 0; index < shared; index++) {
      int compared = left.get(index).compareTo(right.get(index));
      if (compared != 0) {
        return compared;
      }
    }
    return Integer.compare(left.size(), right.size());
  }
}

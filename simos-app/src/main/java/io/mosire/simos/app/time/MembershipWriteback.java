package io.mosire.simos.app.time;

import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.Membership;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.population.PopulationGroup;
import io.mosire.simos.util.economy.ProportionalSplit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>成员份额 × 社会人口的跨切片对账</b>（S1.4 的后置不变量；app 组合根，唯一同时看得见 social 与 economy 的地方）。
 *
 * <pre>
 * 逐 lot：Σ_{memberships(lot)} count == PopulationGroup.count(lot)      // 本类的硬判据
 * 全球：  Σ Membership.count          == Σ ClassRow.population           // EconomyData 自己的守卫
 * </pre>
 *
 * <p>★★ <b>为什么必须有本类</b>：{@code EconomyData} 看不见 social，只能判"份额之和 == 行人口之和"这条**片区内部**的 守恒；而"每个 lot
 * 的份额之和 == 该批次社会人数"这条**跨切片**守恒只能在这里判。少了它，社会侧少了人而经济侧份额没变 这种漂移在两个切片各自都"自洽"，只在总量上静默消失。
 *
 * <p>★★ <b>三条路径的分工</b>：
 *
 * <ol>
 *   <li><b>tick0 seed</b>：{@code EconomySeeder} 用同一份 {@code PopulationGroup} 列表拆份额，出口自检（见 seeder 的
 *       守恒断言）；
 *   <li><b>月度 LotChange 回写</b>：{@code PopulationDynamics.monthly} 把新生儿放进**新的批次**（年龄 0、当月的 born
 *       lot）⇒ 存量 lot 只减死亡、新生 lot 需要新建份额。{@link #reconcile} 在各家户的**行人口**权重上把新 lot 的人数 摊到该 (格, 居住类型)
 *       的家户，保证逐 lot 相等；
 *   <li><b>旧档迁移</b>：{@code LegacyHouseholdMigration} 只能按行人口反推一份**近似**份额（还可能给没有配额的行造 {@code legacy-}
 *       合成 lot）⇒ 第一次推进前用 {@link #rebuildLegacy} 按 social 的真实批次重建份额；片区总量对不上 ⇒ fail-closed（不静默均摊）。
 * </ol>
 *
 * <p>★ <b>顺序确定性</b>：全部按 {@code PeopleLotId.value()} / {@code HouseholdId.value()} 升序，切分用 {@link
 * ProportionalSplit}（最大余数法，同余数按输入下标 = 排序后的家户序）—— 同一状态重放得到同一份份额。
 */
public final class MembershipWriteback {

  private MembershipWriteback() {}

  /** (格, 居住类型) 的复合键 —— 批次与家户行在这里会合（社会批次的 residence 与行的 view 同口径）。 */
  private record ViewKey(HexCoord hex, ResidenceKind residence) {}

  /**
   * ★★ <b>逐 lot 硬判据</b>：不等 ⇒ 当场抛（fail-closed；调用方不得把差额均摊到别的 lot）。
   *
   * <p>★ 也判"份额指向的家户必须存在"与"份额的 lot 必须在 social 里"—— 前者 {@code EconomyData} 构造期还会再判一次，
   * 后者是跨切片才看得见的错（例如旧档合成 lot 没被重建）。
   */
  public static void requireConsistent(
      Map<MembershipId, Membership> memberships,
      Map<HouseholdId, ClassRow> rows,
      SocialData social) {
    Objects.requireNonNull(memberships, "memberships");
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(social, "social");
    Map<PeopleLotId, Long> byLot = membershipSums(memberships);
    List<String> mismatches = new ArrayList<>();
    for (Map.Entry<PeopleLotId, Long> entry : byLot.entrySet()) {
      PopulationGroup group = social.groups().get(entry.getKey());
      if (group == null) {
        mismatches.add(
            "份额的 lot 不在 social 批次表里: " + entry.getKey() + "（份额=" + entry.getValue() + "）");
      } else if (entry.getValue() != group.count()) {
        mismatches.add(
            "lot="
                + entry.getKey()
                + "：ΣMembership.count="
                + entry.getValue()
                + " ≠ PopulationGroup.count="
                + group.count());
      }
    }
    for (PopulationGroup group : social.groups().values()) {
      long sum = byLot.getOrDefault(group.id(), 0L);
      if (sum != group.count()) {
        mismatches.add(
            "lot="
                + group.id()
                + "：ΣMembership.count="
                + sum
                + " ≠ PopulationGroup.count="
                + group.count());
      }
    }
    for (Membership membership : memberships.values()) {
      if (!rows.containsKey(membership.household())) {
        mismatches.add("份额的家户不存在: " + membership.id() + " → " + membership.household());
      }
    }
    if (!mismatches.isEmpty()) {
      throw new IllegalStateException(
          "Σ Membership.count(lot) 必须等于 PopulationGroup.count(lot)（S1.4 后置不变量）—— "
              + mismatches.size()
              + " 处不符："
              + mismatches.subList(0, Math.min(5, mismatches.size())));
    }
  }

  /** 只读判定（不抛）；{@link #requireConsistent} 的布尔形态，服务"旧档是否需要重建"的分支。 */
  public static boolean isConsistent(
      Map<MembershipId, Membership> memberships,
      Map<HouseholdId, ClassRow> rows,
      SocialData social) {
    try {
      requireConsistent(memberships, rows, social);
      return true;
    } catch (IllegalStateException inconsistent) {
      return false;
    }
  }

  /**
   * ★★ <b>补齐"新增批次"的份额</b>（月度出生 / 旧档里完全缺失的批次）：逐 lot 把差额摊到该 (格, 居住类型) 的家户。
   *
   * <p>★ 规则：
   *
   * <ul>
   *   <li>先做 <b>盈余再分配</b>：{@code Σ已有份额 > group.count} 的 lot（月度出生被 economy 记到了旧 lot 的那些） 按份额权重削到
   *       {@code group.count} —— 这是"把 births 从旧 lot 移到新生 lot"的通道，不是静默丢人；
   *   <li>再做缺口补齐：差额 = {@code group.count − Σ已有份额}；该 lot 已有份额 ⇒ 在**已有份额家户**之间按份额权重补差；
   *   <li>该 lot 没有份额（新生儿批次）⇒ 在 (批次居住格, 批次居住类型) 的**行人口 > 0 的家户**之间按行人口权重新建份额；
   *   <li>候选家户为空 ⇒ <b>抛</b>（"社会有人、经济侧没有一个家户能收"是坏数据，不静默丢人）。
   * </ul>
   *
   * <p>★ 本方法**不改行人口**：月度回写已经按出生/死亡改过行（见 {@code EconomySettlement.applyPopulationChangeInto}）， 这里只把
   * social 侧的人数与经济侧份额对齐。★ 逐 lot 完成后仍调用 {@link #requireConsistent} 收口。
   */
  public static void reconcile(
      Map<HouseholdId, ClassRow> rows,
      Map<MembershipId, Membership> memberships,
      SocialData social) {
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(memberships, "memberships");
    Objects.requireNonNull(social, "social");
    Map<PeopleLotId, Long> byLot = membershipSums(memberships);
    Map<ViewKey, List<HouseholdId>> householdsByView = householdsByView(rows);
    List<PopulationGroup> groups = new ArrayList<>(social.groups().values());
    groups.sort(Comparator.comparing(group -> group.id().value()));
    // ★★ 第一遍（盈余再分配）：economy 的月度回写把 births 也摊进了**旧 lot**，而 social 把新生儿放进新的 born lot
    //   ⇒ 旧 lot 多出 births 份、born lot 少 births 份。这里按份额权重把多出的削掉，再由第二遍补到缺的 lot
    //   （Σ 总量不变，且削/补都由稳定序决定）。
    for (PopulationGroup group : groups) {
      long actual = byLot.getOrDefault(group.id(), 0L);
      long expected = group.count();
      if (actual > expected) {
        reduceLot(memberships, group.id(), actual - expected);
      }
    }
    byLot = membershipSums(memberships);
    // ★★ 第二遍（缺口补齐）：新生 lot 没有份额 ⇒ 在同 (格, 居住类型) 的家户之间按行人口权重新建。
    for (PopulationGroup group : groups) {
      long actual = byLot.getOrDefault(group.id(), 0L);
      long expected = group.count();
      if (actual == expected) {
        continue;
      }
      if (actual > expected) {
        throw new IllegalStateException(
            "批次 "
                + group.id()
                + " 的经济侧份额（"
                + actual
                + "）在社会人数（"
                + expected
                + "）之下削不平（份额权重和为 0 或份额结构被破坏）—— 拒绝静默丢人（S1.4 后置不变量）");
      }
      long need = expected - actual;
      List<Membership> existing = membershipsFor(memberships, group.id());
      long[] weights;
      List<HouseholdId> targets;
      if (!existing.isEmpty()) {
        existing.sort(Comparator.comparing(membership -> membership.household().value()));
        targets = new ArrayList<>(existing.size());
        weights = new long[existing.size()];
        for (int i = 0; i < existing.size(); i++) {
          targets.add(existing.get(i).household());
          weights[i] = existing.get(i).count();
        }
      } else {
        ViewKey view = new ViewKey(group.residence(), ResidenceKind.ofLot(group.id()));
        List<HouseholdId> candidates = householdsByView.get(view);
        if (candidates == null || candidates.isEmpty()) {
          throw new IllegalStateException(
              "批次 "
                  + group.id()
                  + " 有 "
                  + need
                  + " 人没有成员份额，而 (格 "
                  + group.residence()
                  + ", 居住 "
                  + ResidenceKind.ofLot(group.id())
                  + ") 没有任何人口非 0 的家户可收 —— 拒绝静默丢人（S1.4 后置不变量）");
        }
        targets = candidates;
        weights = new long[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
          ClassRow row = rows.get(candidates.get(i));
          weights[i] = row == null ? 0L : row.population();
        }
      }
      long weightSum = 0L;
      for (long weight : weights) {
        weightSum = Math.addExact(weightSum, weight);
      }
      if (weightSum <= 0L && !existing.isEmpty()) {
        // ★ 已有份额但全是 0 份额空壳 ⇒ 退回"按行人口权重"的候选集（不把新生儿塞给一个 0 权重家户）。
        ViewKey view = new ViewKey(group.residence(), ResidenceKind.ofLot(group.id()));
        List<HouseholdId> candidates = householdsByView.get(view);
        if (candidates != null && !candidates.isEmpty()) {
          targets = candidates;
          weights = new long[candidates.size()];
          for (int i = 0; i < candidates.size(); i++) {
            ClassRow row = rows.get(candidates.get(i));
            weights[i] = row == null ? 0L : row.population();
          }
          weightSum = 0L;
          for (long weight : weights) {
            weightSum = Math.addExact(weightSum, weight);
          }
        }
      }
      if (weightSum <= 0L) {
        throw new IllegalStateException(
            "批次 " + group.id() + " 要补 " + need + " 份份额，但候选家户的权重和为 0（拒绝均摊到无从判断的家户）");
      }
      long[] parts = ProportionalSplit.byDenominator(need, weights, weightSum);
      for (int i = 0; i < targets.size(); i++) {
        if (parts[i] <= 0L) {
          continue;
        }
        HouseholdId household = targets.get(i);
        MembershipId id = Membership.idOf(group.id(), household);
        Membership previous = memberships.get(id);
        long next = Math.addExact(previous == null ? 0L : previous.count(), parts[i]);
        memberships.put(id, new Membership(id, group.id(), household, next));
      }
    }
    requireConsistent(memberships, rows, social);
  }

  /**
   * ★★ <b>旧档重建</b>（{@code meta.rulesVersion = pre-modern-v1} 或首次加载旧档）：丢弃旧迁移器造的近似份额 / 合成 lot，按
   * social 的真实批次表重建。
   *
   * <pre>
   * 逐 (格, 居住类型)：Σ行人口 必须 == Σ该处社会的批次人数          // 不等 ⇒ 抛（拒绝静默把差额摊掉）
   * 逐 lot：把 group.count 在**同一 (格,居住类型) 的家户**之间按行人口权重切（最大余数法）
   * </pre>
   *
   * <p>★ 重建后逐 lot 相等、全球 Σ份额 == Σ社会人数；片区总量对不上时 fail-closed —— 那是旧档真的缺人口/多人口， 需要显式迁移判断，不是本方法能替它决定的。
   */
  public static void rebuildLegacy(
      Map<HouseholdId, ClassRow> rows,
      Map<MembershipId, Membership> memberships,
      SocialData social) {
    Objects.requireNonNull(rows, "rows");
    Objects.requireNonNull(memberships, "memberships");
    Objects.requireNonNull(social, "social");
    requireViewTotals(rows, social);
    memberships.clear();
    Map<ViewKey, List<HouseholdId>> householdsByView = householdsByView(rows);
    List<PopulationGroup> groups = new ArrayList<>(social.groups().values());
    groups.sort(Comparator.comparing(group -> group.id().value()));
    for (PopulationGroup group : groups) {
      ViewKey view = new ViewKey(group.residence(), ResidenceKind.ofLot(group.id()));
      List<HouseholdId> candidates = householdsByView.getOrDefault(view, List.of());
      if (group.count() == 0L) {
        continue;
      }
      if (candidates.isEmpty()) {
        throw new IllegalStateException(
            "旧档重建失败：批次 "
                + group.id()
                + " 有 "
                + group.count()
                + " 人，而 (格 "
                + group.residence()
                + ", 居住 "
                + ResidenceKind.ofLot(group.id())
                + ") 没有任何人口非 0 的家户");
      }
      long[] weights = new long[candidates.size()];
      long weightSum = 0L;
      for (int i = 0; i < candidates.size(); i++) {
        ClassRow row = rows.get(candidates.get(i));
        weights[i] = row == null ? 0L : row.population();
        weightSum = Math.addExact(weightSum, weights[i]);
      }
      if (weightSum <= 0L) {
        throw new IllegalStateException(
            "旧档重建失败：批次 " + group.id() + " 的候选家户行人口之和为 0，无法切分 " + group.count() + " 人");
      }
      long[] parts = ProportionalSplit.byDenominator(group.count(), weights, weightSum);
      for (int i = 0; i < candidates.size(); i++) {
        if (parts[i] <= 0L) {
          continue;
        }
        HouseholdId household = candidates.get(i);
        MembershipId id = Membership.idOf(group.id(), household);
        memberships.put(id, new Membership(id, group.id(), household, parts[i]));
      }
    }
    requireConsistent(memberships, rows, social);
  }

  /**
   * ★ 把某个 lot 的份额按**份额权重**削掉 {@code excess}（盈余再分配的落点；最大余数法、家户升序）。
   *
   * <p>★ 削到 0 的份额**保留该成员关系**（{@code count == 0} 是合法状态：身份还在、只是暂时没人）， 与 {@code Membership}
   * 的既定口径一致；不删键可以避免"下一轮出生又要重建身份"的漂移。
   */
  private static void reduceLot(
      Map<MembershipId, Membership> memberships, PeopleLotId lot, long excess) {
    List<Membership> members = membershipsFor(memberships, lot);
    if (members.isEmpty() || excess <= 0L) {
      throw new IllegalStateException(
          "批次 " + lot + " 的份额盈余无法再分配（没有份额或 excess ≤ 0）: excess=" + excess);
    }
    long sum = 0L;
    long[] weights = new long[members.size()];
    for (int i = 0; i < members.size(); i++) {
      weights[i] = members.get(i).count();
      sum = Math.addExact(sum, weights[i]);
    }
    if (sum < excess) {
      throw new IllegalStateException(
          "批次 " + lot + " 的份额合计 " + sum + " 小于要削掉的盈余 " + excess + "（状态不一致）");
    }
    long[] parts;
    if (sum == 0L) {
      throw new IllegalStateException("批次 " + lot + " 的份额合计为 0 却要削 " + excess);
    }
    parts = ProportionalSplit.byDenominator(excess, weights, sum);
    for (int i = 0; i < members.size(); i++) {
      Membership membership = members.get(i);
      long next = Math.max(0L, membership.count() - parts[i]);
      memberships.put(
          membership.id(),
          new Membership(membership.id(), membership.lot(), membership.household(), next));
    }
  }

  /** 逐 lot 的份额合计（键序按 lot 升序）。 */
  private static Map<PeopleLotId, Long> membershipSums(Map<MembershipId, Membership> memberships) {
    List<Membership> ordered = new ArrayList<>(memberships.values());
    ordered.sort(
        Comparator.comparing((Membership membership) -> membership.lot().value())
            .thenComparing(membership -> membership.household().value()));
    Map<PeopleLotId, Long> sums = new LinkedHashMap<>();
    for (Membership membership : ordered) {
      sums.merge(membership.lot(), membership.count(), Math::addExact);
    }
    return sums;
  }

  /** 某个 lot 的全部份额（按家户升序）。 */
  private static List<Membership> membershipsFor(
      Map<MembershipId, Membership> memberships, PeopleLotId lot) {
    List<Membership> result = new ArrayList<>();
    for (Membership membership : memberships.values()) {
      if (membership.lot().equals(lot)) {
        result.add(membership);
      }
    }
    result.sort(Comparator.comparing(membership -> membership.household().value()));
    return result;
  }

  /** (格, 居住类型) → 人口非 0 的家户（按 HouseholdId 升序）。 */
  private static Map<ViewKey, List<HouseholdId>> householdsByView(Map<HouseholdId, ClassRow> rows) {
    Map<ViewKey, Set<HouseholdId>> byView = new LinkedHashMap<>();
    for (Map.Entry<HouseholdId, ClassRow> entry : rows.entrySet()) {
      ClassRow row = entry.getValue();
      if (row.population() <= 0L) {
        continue; // 空壳家户收不下新生儿/迁移人口（H1 的"空账"与"能收人"是两件事）
      }
      ViewKey view = new ViewKey(row.view().hex(), row.view().residence());
      byView.computeIfAbsent(view, ignored -> new LinkedHashSet<>()).add(entry.getKey());
    }
    Map<ViewKey, List<HouseholdId>> result = new LinkedHashMap<>();
    for (Map.Entry<ViewKey, Set<HouseholdId>> entry : byView.entrySet()) {
      List<HouseholdId> sorted = new ArrayList<>(entry.getValue());
      sorted.sort(Comparator.comparing(HouseholdId::value));
      result.put(entry.getKey(), sorted);
    }
    return result;
  }

  /** 逐 (格, 居住类型) 的"行人口 == 社会人数"预检（旧档重建的唯一分量判据）。 */
  private static void requireViewTotals(Map<HouseholdId, ClassRow> rows, SocialData social) {
    Map<ViewKey, Long> rowTotals = new LinkedHashMap<>();
    for (ClassRow row : rows.values()) {
      ViewKey view = new ViewKey(row.view().hex(), row.view().residence());
      rowTotals.merge(view, row.population(), Math::addExact);
    }
    Map<ViewKey, Long> socialTotals = new LinkedHashMap<>();
    for (PopulationGroup group : social.groups().values()) {
      ViewKey view = new ViewKey(group.residence(), ResidenceKind.ofLot(group.id()));
      socialTotals.merge(view, group.count(), Math::addExact);
    }
    LinkedHashSet<ViewKey> views = new LinkedHashSet<>(rowTotals.keySet());
    views.addAll(socialTotals.keySet());
    for (ViewKey view : views) {
      long rowTotal = rowTotals.getOrDefault(view, 0L);
      long socialTotal = socialTotals.getOrDefault(view, 0L);
      if (rowTotal != socialTotal) {
        throw new IllegalStateException(
            "旧档重建失败：(格 "
                + view.hex()
                + ", 居住 "
                + view.residence()
                + ") 的经济行人口合计 "
                + rowTotal
                + " ≠ 社会批次人数合计 "
                + socialTotal
                + "（拒绝静默均摊差额；需要显式迁移判断）");
      }
    }
  }
}

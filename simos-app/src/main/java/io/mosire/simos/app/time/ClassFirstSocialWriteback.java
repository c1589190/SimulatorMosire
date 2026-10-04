package io.mosire.simos.app.time;

import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.HouseholdProductionAccountId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.economy.classfirst.ClassFirstSettlement;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.ClassPool;
import io.mosire.simos.economy.classfirst.HouseholdProductionAccount;
import io.mosire.simos.economy.classfirst.PilotConfig;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.population.PopulationDynamics;
import io.mosire.simos.social.population.PopulationGroup;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>R2b：把 classfirst 单日结算的"人口移动 + 当日生活资料压力"落到 social 批次</b>。
 *
 * <p>classfirst 引擎把人口按<b>阶层池</b>（全局 4 池）移动，而 social 批次是 {@code (格, 居住类型, 年龄, 性别)} 的视图 ——
 * 两侧维度不同，故本类的映射如实分两层写：
 *
 * <ol>
 *   <li><b>移动量逐值不丢</b>：用 classfirst {@code householdAccounts} 的<b>逐家户人口差分</b>（{@code before} →
 *       {@code result.state()}）作源；每个家户经 {@code economy.classes} 的 view 定位到 {@code (格,
 *       居住类型)}，把该差分按该处各 social 批次的当前人数比例（正增量）/ 瀑布（负增量）分过去 ⇒ social 总人口与 classfirst 总人口同步变化；
 *   <li><b>具名缺口</b>：家户在 {@code classes} 里缺席、或该 {@code (格, 居住类型)} 没有任何 social 批次 ⇒ 该笔差分记进 {@link
 *       AppliedSocial#gaps()}（调用方写日志），不静默丢。
 * </ol>
 *
 * <p>★★ <b>本轮的人口学边界（如实写在这里，报告同口径）</b>：
 *
 * <ul>
 *   <li><b>出生/死亡不在本类</b>：月度结算由参与者调 {@code PopulationDynamics.monthly} 后，经 {@link
 *       ClassFirstPopulationWriteback} 落到 classfirst 家户账户；本类只做<b>逐日</b>的阶层移动 +
 *       生理压力，避免同一条人口账被两处各写一遍；
 *   <li><b>逐日生理压力已接，但口径是聚合的</b>：用 classfirst 当日<b>口粮基准缺口</b>（{@code audit().baseRationGap()}）与当日布消费
 *       （{@code meta.totals()} 差分）/布需求算全体批次的满足率，再调 {@link
 *       PopulationDynamics#stressAfter}。引擎的消费是按池分的，而 social 批次没有阶层维 ⇒
 *       本轮只能把<b>全体</b>批次按同一满足率更新（近似，不冒充逐批次账）；
 *   <li><b>阶层维在 social 侧不存在</b>：家户差分摊到该 {@code (格, 居住类型)} 的全部批次（不分年龄/性别），故可能缓慢改变年龄/性别构成 ——
 *       这是映射分辨率的上限，不是"算错"，总人数守恒由逐值分摊保证。
 * </ul>
 */
final class ClassFirstSocialWriteback {

  private ClassFirstSocialWriteback() {}

  /** 落账结果：新的 social 状态 + 本批具名缺口（无法映射的差分/压力读数）。 */
  record AppliedSocial(SocialData data, Set<String> gaps) {

    AppliedSocial {
      Objects.requireNonNull(data, "data");
      gaps = Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(gaps)));
    }
  }

  static AppliedSocial apply(
      SocialData social,
      EconomyData economy,
      ClassFirstState before,
      ClassFirstSettlement.Result result) {
    Objects.requireNonNull(social, "social");
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(before, "before");
    Objects.requireNonNull(result, "result");

    Map<PeopleLotId, PopulationGroup> groups = new LinkedHashMap<>(social.groups());
    LinkedHashSet<String> gaps = new LinkedHashSet<>();
    applyPopulationMoves(social, groups, economy, before, result, gaps);
    applyDailyStress(groups, before, result, gaps);
    return new AppliedSocial(social.withGroups(groups), gaps);
  }

  // ── ① 人口移动：逐家户差分 → (格, 居住类型) 的 social 批次 ────────────────────────────

  /**
   * ★ <b>先对账再用</b>：{@link ClassFirstSettlement.Result#socialPopulationDeltas()} 是引擎口径的池级人口差分；本方法落
   * social 用的是 {@code householdAccounts} 的逐家户差分。两者之和必须逐值相等 —— 不等 ⇒ 当场抛（宁可红，也不让"引擎说动了多少人"与"落进 social
   * 的有多少人"各说各话）。
   */
  private static void applyPopulationMoves(
      SocialData social,
      Map<PeopleLotId, PopulationGroup> groups,
      EconomyData economy,
      ClassFirstState before,
      ClassFirstSettlement.Result result,
      Set<String> gaps) {
    ClassFirstState after = result.state();
    long declared = 0L;
    for (ClassFirstSettlement.PopulationDelta delta : result.socialPopulationDeltas()) {
      declared += delta.populationDelta();
    }
    long observed = 0L;
    LinkedHashSet<HouseholdProductionAccountId> ids =
        new LinkedHashSet<>(before.householdAccounts().keySet());
    ids.addAll(after.householdAccounts().keySet());
    for (HouseholdProductionAccountId id : ids) {
      HouseholdProductionAccount beforeAccount = before.householdAccounts().get(id);
      HouseholdProductionAccount afterAccount = after.householdAccounts().get(id);
      long oldCount = beforeAccount == null ? 0L : beforeAccount.population();
      long newCount = afterAccount == null ? 0L : afterAccount.population();
      long delta = newCount - oldCount;
      if (delta == 0L) {
        continue;
      }
      observed += delta;
      HouseholdProductionAccount sample = afterAccount != null ? afterAccount : beforeAccount;
      HouseholdId householdId = HouseholdId.parse(sample.householdId());
      ClassRow row = economy.classes().get(householdId);
      if (row == null) {
        gaps.add("家户人口差分无法定位 social 批次（classes 缺行）：household=" + householdId + " delta=" + delta);
        continue;
      }
      List<PeopleLotId> lots = lotsAt(social, groups, row.view().hex(), row.view().residence());
      if (lots.isEmpty()) {
        gaps.add(
            "家户人口差分无法定位 social 批次（该格/居住类型没有批次）：household="
                + householdId
                + " hex="
                + row.view().hex()
                + " residence="
                + row.view().residence()
                + " delta="
                + delta);
        continue;
      }
      distribute(groups, lots, delta);
    }
    if (observed != declared) {
      throw new IllegalStateException(
          "classfirst 人口差分对不上：逐家户差分之和="
              + observed
              + " socialPopulationDeltas 之和="
              + declared
              + "（引擎口径与家户账户口径不一致，拒绝把 social 落成第三个故事）");
    }
  }

  /** 某格某居住类型上的全部 social 批次（保持 {@code groups} 的迭代序 = 确定序；位置来自所属家户）。 */
  private static List<PeopleLotId> lotsAt(
      SocialData social, Map<PeopleLotId, PopulationGroup> groups, HexCoord hex, ResidenceKind residence) {
    List<PeopleLotId> lots = new ArrayList<>();
    for (PopulationGroup group : groups.values()) {
      if (social.hexOfLot(group.id()).filter(hex::equals).isPresent()
          && ResidenceKind.ofLot(group.id()) == residence) {
        lots.add(group.id());
      }
    }
    return lots;
  }

  /**
   * 把 {@code delta} 逐值分给 {@code lots}：正增量按各批次当前人数权重最大余数法；负增量按人数瀑布（不足 ⇒ 当场抛，不静默夹取 ——
   * 那说明两侧已经不同步，掩盖只会让账越来越假）。
   */
  private static void distribute(
      Map<PeopleLotId, PopulationGroup> groups, List<PeopleLotId> lots, long delta) {
    if (delta > 0L) {
      long[] weights = new long[lots.size()];
      for (int i = 0; i < lots.size(); i++) {
        weights[i] = groups.get(lots.get(i)).count();
      }
      long[] shares = ClassFirstDistribution.largestRemainder(delta, weights);
      for (int i = 0; i < lots.size(); i++) {
        addToGroup(groups, lots.get(i), shares[i]);
      }
      return;
    }

    long remaining = -delta;
    long[] counts = new long[lots.size()];
    for (int i = 0; i < lots.size(); i++) {
      counts[i] = groups.get(lots.get(i)).count();
    }
    for (int index : ClassFirstDistribution.orderByDescending(counts)) {
      if (remaining == 0L) {
        break;
      }
      long take = Math.min(remaining, counts[index]);
      if (take > 0L) {
        addToGroup(groups, lots.get(index), -take);
        remaining -= take;
      }
    }
    if (remaining > 0L) {
      throw new IllegalStateException(
          "social 批次人数不足以承接 classfirst 的负人口差分：剩余 "
              + remaining
              + " lots="
              + lots
              + "（两侧人口账已不同步，拒绝夹取掩盖）");
    }
  }

  private static void addToGroup(
      Map<PeopleLotId, PopulationGroup> groups, PeopleLotId id, long delta) {
    if (delta == 0L) {
      return;
    }
    PopulationGroup group = groups.get(id);
    long next = Math.addExact(group.count(), delta);
    if (next < 0L) {
      throw new IllegalStateException("social 批次人数不得为负: " + id + " -> " + next);
    }
    groups.put(id, group.withCountAndStress(next, group.physiologicalStress()));
  }

  // ── ② 逐日生理压力（聚合口径；出生/死亡见类注"暂未接"）────────────────────────────────

  private static void applyDailyStress(
      Map<PeopleLotId, PopulationGroup> groups,
      ClassFirstState before,
      ClassFirstSettlement.Result result,
      Set<String> gaps) {
    if (groups.isEmpty()) {
      return;
    }
    PilotConfig config = before.meta().config();
    if (config == null) {
      gaps.add("逐日生理压力未接：classFirst.meta.config 为空（无法读口粮/布需求参数）");
      return;
    }
    long grainSatisfaction = grainSatisfactionPerMille(before, result, config);
    long clothSatisfaction = clothSatisfactionPerMille(before, result, config);
    for (Map.Entry<PeopleLotId, PopulationGroup> entry : groups.entrySet()) {
      PopulationGroup group = entry.getValue();
      long stress =
          PopulationDynamics.stressAfter(
              group.physiologicalStress(), grainSatisfaction, clothSatisfaction);
      if (stress != group.physiologicalStress()) {
        entry.setValue(group.withPhysiologicalStress(stress));
      }
    }
  }

  /**
   * 当日口粮满足率（‰）：分母 = Σ 池人口 × {@code baseRationPerCapita}（与引擎 {@code consume()} 的基准口粮同式），分子 = 分母 −
   * 当日基准缺口（{@code audit().baseRationGap()}，逐 tick 量，不是累计）。
   */
  private static long grainSatisfactionPerMille(
      ClassFirstState before, ClassFirstSettlement.Result result, PilotConfig config) {
    long need = 0L;
    for (ClassPool pool : before.classPools().values()) {
      need =
          Math.addExact(need, Math.multiplyExact(pool.population(), config.baseRationPerCapita()));
    }
    if (need <= 0L) {
      return 1000L;
    }
    long gap = Math.max(0L, result.audit().baseRationGap());
    long shortfall = Math.min(1000L, (gap * 1000L + need - 1L) / need); // 缺口向上取整，不夸大满足
    return 1000L - shortfall;
  }

  /**
   * 当日布满足率（‰）：分母 = Σ 池（人口 × {@code nonEssentialNeedPerMille} / 1000，与引擎逐池同式），分子 = 当日布消费（{@code
   * ClassFirstMeta.Totals} 的差分；那是逐日累加量，累计表相减才是"这一天"）。
   */
  private static long clothSatisfactionPerMille(
      ClassFirstState before, ClassFirstSettlement.Result result, PilotConfig config) {
    long need = 0L;
    for (ClassPool pool : before.classPools().values()) {
      need = Math.addExact(need, pool.population() * config.nonEssentialNeedPerMille() / 1000L);
    }
    if (need <= 0L) {
      return 1000L;
    }
    ClassFirstState after = result.state();
    long consumed =
        after.meta().totals().clothConsumedTotal() - before.meta().totals().clothConsumedTotal();
    return Math.min(1000L, Math.max(0L, consumed) * 1000L / need);
  }
}

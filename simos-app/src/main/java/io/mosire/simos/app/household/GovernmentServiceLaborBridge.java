package io.mosire.simos.app.household;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.gov.GovAdministrationPlan;
import io.mosire.simos.gov.GovPostTier;
import io.mosire.simos.social.api.id.GovernmentHouseholds;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.GovernmentPostOfHousehold;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
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
 * ★★ <b>承诺→两维供给桥（Z3b，设计书 §3/§4.2/§10 C2；唯一供给权威）</b>：把某 GOV 的行政服务 unit 上全部 {@link
 * LaborCommitmentKind#GOV_SERVICE} 承诺，按岗位家户的档位权重拆到治安/公文两维。
 *
 * <pre>
 * GOV 的 service unit      = economy.units 里 operator == HOUSEHOLD:hh-gov-&lt;govUnitId&gt; 的 unit（Z1c 身份：
 *                            operator = 政府家户；activity 与 unit 的指向由 EconomyData 守卫判死）
 * 逐户承诺劳动 L_h          = 该 GOV 全部 service unit 上、household == h 的 GOV_SERVICE 承诺之和（毫小时/tick）
 *                            （只对有岗位的家户计；没有岗位的家户不进任何维，逐 GOV 一条具名 INFO）
 * 档位权重 (w_sec, w_pap)   = h 在 GovernmentFormation.governmentPostsOfHousehold 的 tierId → plan.postTiers 权重；
 *                            tierId 空（legacy/未指派）⇒ 按 role 的固有维度：YAMEN=(1000,0)，SCRIBE/POST=(0,1000)
 * 治安拆分 = ⌊L_h × w_sec ÷ (w_sec + w_pap)⌋；公文拆分 = L_h − 治安拆分（余数归公文，Σ 不丢）
 * </pre>
 *
 * <p>★★ <b>为什么用归一化权重（÷ w_sec+w_pap）而不是逐维 ÷1000</b>：冻结口径要求"把该户承诺劳动<b>拆</b>到两维、Σ 不丢" ——两维权重的合计允许
 * ≠1000（{@link GovPostTier} 只判非负），归一化拆分对任意合法权重都是<b>划分</b>（两维之和逐值等于 L_h）， 且缩放不变；余数固定落在公文维。`w_sec +
 * w_pap == 0` ⇒ 该档位无法定向，具名契约 ERROR fail-closed（不静默丢劳动）。
 *
 * <p>★★ <b>失败语义（设计书 §3/§7.6）</b>："有承诺但家户没有岗位" ⇒ 该户两维供给 0，逐 GOV 一条具名 INFO {@code
 * GOV_SERVICE_COMMITMENT_WITHOUT_POST}（承诺行不动，挂上档位后自然计入）；"档位 id 指到不存在的档位"或"档位两维权重合计 0" 是真契约故障 ⇒ 先
 * ERROR {@code GOV_SERVICE_SUPPLY_CONTRACT_VIOLATION} 再 {@link IllegalStateException}。
 *
 * <p>★ <b>只读 + 纯函数 + 进程内</b>：不改任何状态、不落库；返回的表是保序不可变快照（{@code LinkedHashMap} + {@code
 * Collections.unmodifiableMap}，<b>不</b>用 {@code Map.copyOf}）。
 */
public final class GovernmentServiceLaborBridge {

  /** 契约故障日志通道（与推进入口同一 logger；TICK origin）。 */
  private static final LogChannel BRIDGE = EventLog.channel(AppLog.time());

  private GovernmentServiceLaborBridge() {}

  /** 一次两维供给拆分（毫小时/tick；两维都 ≥ 0，合计 = <b>挂岗位家户</b>的 GOV_SERVICE 承诺之和；未挂岗位的按设计书 §3 记 INFO 并排除）。 */
  public record Supply(long securityLaborMilli, long paperworkLaborMilli) {

    public Supply {
      if (securityLaborMilli < 0L || paperworkLaborMilli < 0L) {
        throw new IllegalArgumentException(
            "GovernmentServiceLaborBridge.Supply 两维劳动都必须 ≥ 0: "
                + securityLaborMilli
                + "/"
                + paperworkLaborMilli);
      }
    }
  }

  /**
   * 该 GOV 全部 service unit 上、逐家户汇总的 {@code GOV_SERVICE} 承诺劳动（毫小时/tick；保序不可变）。
   *
   * <p>键序 = 按 {@link LaborAllocationId#value()} 升序遍历时首次出现的家户序（确定性）。只收 {@code kind == GOV_SERVICE}、
   * {@code laborMilli > 0}、且 {@code activity} 落在该 GOV service unit 集合里的行；行内 {@code actor} 必须等于该 GOV
   * 的 operator（{@code EconomyData} 已判死，这里防御性再判一次）。
   *
   * @param economy 经济切片；不得为 null
   * @param govUnitId GOV 单位 id；不得为 null
   * @param day 世界日（只进日志上下文；TICK origin 必带）
   */
  public static Map<HouseholdId, Long> committedLaborByHousehold(
      EconomyData economy, UnitId govUnitId, long day) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(govUnitId, "govUnitId");
    HouseholdId govHousehold = governmentHouseholdOf(govUnitId, day);
    ActorRef operator = HouseholdActors.of(govHousehold);
    Set<String> serviceUnitIds = serviceUnitIdsOf(economy, operator);
    Map<HouseholdId, Long> sums = new LinkedHashMap<>();
    List<LaborAllocationId> ordered = new ArrayList<>(economy.allocations().keySet());
    ordered.sort(Comparator.comparing(LaborAllocationId::value));
    try {
      for (LaborAllocationId allocationId : ordered) {
        HouseholdLaborCommitment commitment = economy.allocations().get(allocationId);
        if (commitment == null
            || commitment.kind() != LaborCommitmentKind.GOV_SERVICE
            || commitment.laborMilli() <= 0L) {
          continue;
        }
        if (!serviceUnitIds.contains(commitment.activity())) {
          continue;
        }
        if (!commitment.actor().equals(operator)) {
          throw contractFailure(
              "commitment-actor-mismatch",
              day,
              govUnitId,
              "allocation="
                  + allocationId.value()
                  + " activity="
                  + commitment.activity()
                  + " actor="
                  + commitment.actor());
        }
        sums.merge(commitment.household(), commitment.laborMilli(), Math::addExact);
      }
    } catch (ArithmeticException e) {
      throw contractFailure("arithmetic-overflow", day, govUnitId, e.getMessage());
    }
    return Collections.unmodifiableMap(sums); // ★ 冻在返回处（保序）
  }

  /**
   * 该 GOV 的两维供给（毫小时/tick）：按岗位家户的档位权重拆分 {@link #committedLaborByHousehold} 的逐户承诺劳动。
   *
   * @param economy 经济切片；不得为 null
   * @param govUnitId GOV 单位 id；不得为 null
   * @param formation 该 GOV 单位的编制（岗位目录 = {@code governmentPostsOfHousehold}）；不得为 null
   * @param plan 该 GOV 的编制计划（档位权重目录）；不得为 null
   * @param day 世界日（只进日志上下文）
   */
  public static Supply supply(
      EconomyData economy,
      UnitId govUnitId,
      GovernmentFormation formation,
      GovAdministrationPlan plan,
      long day) {
    Objects.requireNonNull(formation, "formation");
    Objects.requireNonNull(plan, "plan");
    Map<HouseholdId, Long> committed = committedLaborByHousehold(economy, govUnitId, day);
    if (committed.isEmpty()) {
      return new Supply(0L, 0L);
    }
    long security = 0L;
    long paperwork = 0L;
    long withoutPost = 0L;
    String firstWithoutPost = "-";
    try {
      for (Map.Entry<HouseholdId, Long> entry : committed.entrySet()) {
        HouseholdId household = entry.getKey();
        long laborMilli = entry.getValue();
        GovernmentPostOfHousehold post = formation.governmentPostsOfHousehold().get(household);
        if (post == null) {
          // ★ 设计书 §3：没有挂岗位的家户 ⇒ 该户承诺进不了任何维（供给 0）。这不是静默——逐 GOV 一条具名 INFO；
          //   承诺行本身一字不动（C7），等 assignPosts 把它挂到档位后下一 tick 自然计入。
          withoutPost++;
          if (withoutPost == 1L) {
            firstWithoutPost = household.value() + " laborMilli=" + laborMilli;
          }
          continue;
        }
        long[] weights = tierWeightsOf(post, plan, govUnitId, day);
        long weightSum = Math.addExact(weights[0], weights[1]);
        if (weightSum == 0L) {
          throw contractFailure(
              "tier-zero-weight",
              day,
              govUnitId,
              "household=" + household.value() + " tierId=" + post.tierId());
        }
        long securityShare = Math.floorDiv(Math.multiplyExact(laborMilli, weights[0]), weightSum);
        security = Math.addExact(security, securityShare);
        paperwork = Math.addExact(paperwork, laborMilli - securityShare);
      }
    } catch (ArithmeticException e) {
      throw contractFailure("arithmetic-overflow", day, govUnitId, e.getMessage());
    }
    if (withoutPost > 0L) {
      BRIDGE.info(
          LogEvent.of(
              "GOV_SERVICE_COMMITMENT_WITHOUT_POST",
              AppLogSource.DAILY_LOOP,
              "day",
              day,
              "unit",
              govUnitId.value(),
              "count",
              withoutPost,
              "first",
              firstWithoutPost,
              "reason",
              "no-posted-household-commitment-not-dimensioned"));
    }
    return new Supply(security, paperwork);
  }

  /**
   * ★ 官吏户的"全职人数当量"口径（读口/投影用）：{@code ⌊承诺劳动 ÷ 标准劳动定额⌋}（毫小时 ÷ 毫小时/人 = 人）。 全职官吏的承诺 ≥ 一个定额 ⇒ 至少 1；不足一个定额
   * ⇒ 0（尚未形成一份全职编制，不四舍五入虚构人头）。
   *
   * @param committedLaborMilli 该户对该 GOV 的 GOV_SERVICE 承诺（毫小时/tick；≥ 0）
   * @param standardLaborMilliHoursPerTick C8 唯一权威标准定额（必须 &gt; 0；非正 ⇒ 具名契约 ERROR）
   */
  public static long personEquivalents(
      long committedLaborMilli, long standardLaborMilliHoursPerTick, UnitId govUnitId, long day) {
    if (committedLaborMilli < 0L) {
      throw contractFailure(
          "negative-committed-labor", day, govUnitId, "committedLaborMilli=" + committedLaborMilli);
    }
    if (standardLaborMilliHoursPerTick <= 0L) {
      throw contractFailure(
          "standard-labor-non-positive",
          day,
          govUnitId,
          "standardLaborMilliHoursPerTick=" + standardLaborMilliHoursPerTick);
    }
    return Math.floorDiv(committedLaborMilli, standardLaborMilliHoursPerTick);
  }

  /** 该 GOV 的 service unit 集合（operator == {@code HOUSEHOLD:hh-gov-<govUnitId>} 的 unit id；确定性保序）。 */
  private static Set<String> serviceUnitIdsOf(EconomyData economy, ActorRef operator) {
    Set<String> ids = new LinkedHashSet<>();
    List<String> ordered = new ArrayList<>();
    for (var unit : economy.units().values()) {
      if (unit.operator().equals(operator)) {
        ordered.add(unit.id().value());
      }
    }
    ordered.sort(Comparator.naturalOrder());
    ids.addAll(ordered);
    return ids;
  }

  /** 档位权重：非空 tierId 必须命中计划目录；空 tierId（legacy/未指派）按 role 的固有维度回退。 */
  private static long[] tierWeightsOf(
      GovernmentPostOfHousehold post, GovAdministrationPlan plan, UnitId govUnitId, long day) {
    if (post.hasTier()) {
      for (GovPostTier tier : plan.postTiers()) {
        if (tier.tierId().equals(post.tierId())) {
          return new long[] {tier.securityWeightPerMille(), tier.paperworkWeightPerMille()};
        }
      }
      throw contractFailure(
          "tier-unknown",
          day,
          govUnitId,
          "household=" + post.householdId().value() + " tierId=" + post.tierId());
    }
    // ★ legacy（空 tierId）：沿用 GovEfficiency 旧桥的 role→维口径（YAMEN=治安；SCRIBE/POST=公文）。
    return switch (post.role()) {
      case YAMEN -> new long[] {GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE, 0L};
      case SCRIBE, POST -> new long[] {0L, GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE};
    };
  }

  /** GOV 单位 id → 政府家户身份（唯一拼写点）；坏 id = 契约故障（不猜）。 */
  private static HouseholdId governmentHouseholdOf(UnitId govUnitId, long day) {
    try {
      return GovernmentHouseholds.of(govUnitId.value());
    } catch (IllegalArgumentException e) {
      throw contractFailure("bad-gov-unit-id", day, govUnitId, e.getMessage());
    }
  }

  /** 契约故障：具名 ERROR（不降级）+ {@link IllegalStateException}（fail-closed）。 */
  private static IllegalStateException contractFailure(
      String reason, long day, UnitId govUnitId, String detail) {
    BRIDGE.error(
        LogEvent.of(
            "GOV_SERVICE_SUPPLY_CONTRACT_VIOLATION",
            AppLogSource.DAILY_LOOP,
            "reason",
            reason,
            "day",
            day,
            "unit",
            govUnitId.value(),
            "detail",
            detail == null ? "-" : detail));
    return new IllegalStateException(
        "gov 服务供给桥契约故障: " + reason + "（unit=" + govUnitId.value() + "，detail=" + detail + "）");
  }
}

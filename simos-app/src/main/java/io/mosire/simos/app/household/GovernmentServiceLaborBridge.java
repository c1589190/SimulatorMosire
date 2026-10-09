package io.mosire.simos.app.household;

import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.app.AppLog;
import io.mosire.simos.app.AppLogSource;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.HouseholdActors;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.model.HouseholdEconomy;
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
 * ★★ <b>承诺→三维供给桥（Z3b 两维；R2 补口岸 = 第三维，2026-10-09 口岸设计书 §4.2；唯一供给权威）</b>：把某 GOV 的行政服务 unit 上全部 {@link
 * LaborCommitmentKind#GOV_SERVICE} 承诺，按岗位家户的档位权重拆到治安/公文/<b>口岸</b>三维。
 *
 * <pre>
 * GOV 的 service unit      = economy.units 里 operator == HOUSEHOLD:hh-gov-&lt;govUnitId&gt; 的 unit（Z1c 身份：
 *                            operator = 政府家户；activity 与 unit 的指向由 EconomyData 守卫判死）
 * 逐户承诺劳动 L_h          = 该 GOV 全部 service unit 上、household == h 的 GOV_SERVICE 承诺之和（毫小时/tick）
 *                            （只对有岗位的家户计；没有岗位的家户不进任何维，逐 GOV 一条具名 INFO）
 * 档位权重 (w_sec,w_pap,w_port) = h 在 GovernmentFormation 的 governmentPostsOfHousehold（内部）或 externalPosts
 *                            （外部，Z3d）里的 tierId → plan.postTiers 权重；两表同权、互斥（同一户只能在一张表里）；
 *                            tierId 空（legacy/未指派）⇒ 按 role 的固有维度：YAMEN=(1000,0,0)，SCRIBE/POST=(0,1000,0)
 * W = w_sec + w_pap + w_port
 * 治安拆分 = ⌊L_h × w_sec ÷ W⌋；口岸拆分 = ⌊L_h × w_port ÷ W⌋；公文拆分 = L_h − 治安 − 口岸（余数归公文，Σ 不丢）
 * </pre>
 *
 * <p>★★ <b>R2 的拆分口径为什么把余数留给公文、而不是给口岸</b>：这是 I-P8（"无口岸政策/无接触面 ⇒ 旧世界逐值不变"）的<b>唯一 可行写法</b>——{@code
 * w_port = 0} 时 {@code W = w_sec + w_pap}、口岸拆分恒 0、公文拆分 = {@code L − ⌊L×w_sec÷W⌋}，与 Z3b
 * 的两维算式<b>逐值相同</b>。若把余数给口岸（{@code 公文 = ⌊L×w_pap÷W⌋}），默认档 3（500/500）下 L 为奇数时公文会少 1 毫小时 ⇒
 * 旧世界的行政供给当场变值，I-P8 不成立。
 *
 * <p>★★ <b>Z7d-1 有效供给（设计书 Z7 冲突 1=A）</b>：{@code supply(...)} 的逐户输入从"承诺劳动 L_h"改为 {@code min(L_h,
 * 该户当前实际劳动)}（实际劳动 = Economy 行 {@code laborMilli}，由 app 从 Social {@code householdLaborMilli}（基础劳动 ×
 * satietyPerMille ÷ 1000）注入）——**承诺行保留为职位**（C7 不缩/删），欠俸/断顿 只让有效供给与效率如实下降；{@code
 * committedLaborByHousehold} 仍返回原始承诺（工资/俸禄/人数当量读它）。
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

  /**
   * 一次两维供给拆分（毫小时/tick）。
   *
   * <p>★★ <b>Z7d-1 两个口径并存</b>：
   *
   * <ul>
   *   <li>{@link #securityLaborMilli()} / {@link #paperworkLaborMilli()} = <b>有效供给</b>：逐户 {@code
   *       min(该户 GOV_SERVICE 承诺, 该户当前实际劳动)} 后再按档位权重拆到两维；该户实际劳动 = Social 的 {@code
   *       householdLaborMilli}（已含饱食度折算）被经济行 {@code laborMilli} 投影出来的值。
   *   <li>{@link #committedSecurityLaborMilli()} / {@link #committedPaperworkLaborMilli()} =
   *       <b>职位承诺</b>： 完全不 cap 的承诺拆分（C7 的职位口径；工资/俸禄/人数当量读它）。
   *   <li>{@link #underfedHouseholds()} = 有效 &lt; 承诺（且已挂岗位）的家户数；只作具名读数，不自动缩承诺。
   * </ul>
   *
   * <p>★ 2 参便利构造器（旧形状）把两个口径取同值、underfed=0，只服务既有测试/夹具的编译与"承诺=实际"的合成输入。
   */
  public record Supply(
      long securityLaborMilli,
      long paperworkLaborMilli,
      long portLaborMilli,
      long committedSecurityLaborMilli,
      long committedPaperworkLaborMilli,
      long committedPortLaborMilli,
      long underfedHouseholds) {

    /** ★ R2 旧 5 参形状（Z3b 调用点/夹具）：口岸两维取 0（= 没有口岸编制 ⇒ 口岸效率 0 ⇒ 不限制）。 */
    public Supply(
        long securityLaborMilli,
        long paperworkLaborMilli,
        long committedSecurityLaborMilli,
        long committedPaperworkLaborMilli,
        long underfedHouseholds) {
      this(
          securityLaborMilli,
          paperworkLaborMilli,
          0L,
          committedSecurityLaborMilli,
          committedPaperworkLaborMilli,
          0L,
          underfedHouseholds);
    }

    /** 旧 2 参形状：有效 = 承诺（调用方已保证没有饥饿缺口），口岸维取 0。 */
    public Supply(long securityLaborMilli, long paperworkLaborMilli) {
      this(securityLaborMilli, paperworkLaborMilli, securityLaborMilli, paperworkLaborMilli, 0L);
    }

    public Supply {
      if (securityLaborMilli < 0L
          || paperworkLaborMilli < 0L
          || portLaborMilli < 0L
          || committedSecurityLaborMilli < 0L
          || committedPaperworkLaborMilli < 0L
          || committedPortLaborMilli < 0L
          || underfedHouseholds < 0L) {
        throw new IllegalArgumentException(
            "GovernmentServiceLaborBridge.Supply 各分量都必须 ≥ 0: "
                + securityLaborMilli
                + "/"
                + paperworkLaborMilli
                + "/"
                + portLaborMilli
                + "/"
                + committedSecurityLaborMilli
                + "/"
                + committedPaperworkLaborMilli
                + "/"
                + committedPortLaborMilli
                + "/"
                + underfedHouseholds);
      }
      if (securityLaborMilli > committedSecurityLaborMilli
          || paperworkLaborMilli > committedPaperworkLaborMilli
          || portLaborMilli > committedPortLaborMilli) {
        throw new IllegalArgumentException(
            "GovernmentServiceLaborBridge.Supply 有效供给不得超过承诺（min 口径）: effective="
                + securityLaborMilli
                + "/"
                + paperworkLaborMilli
                + "/"
                + portLaborMilli
                + " committed="
                + committedSecurityLaborMilli
                + "/"
                + committedPaperworkLaborMilli
                + "/"
                + committedPortLaborMilli);
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
   * <p>★ 旧签名（读口/旧夹具）：实际劳动取经济状态自身的 {@code classes[].laborMilli}。推进中的调用方应改用带 {@code
   * currentHouseholdRows} 的重载——那里传入会话工作副本，才拿得到**当 tick** 的 budget。
   *
   * @param economy 经济切片；不得为 null
   * @param govUnitId GOV 单位 id；不得为 null
   * @param formation 该 GOV 单位的编制（岗位目录 = 内部 {@code governmentPostsOfHousehold} + 外部 {@code
   *     externalPosts}，两张表同权）；不得为 null
   * @param plan 该 GOV 的编制计划（档位权重目录）；不得为 null
   * @param day 世界日（只进日志上下文）
   */
  public static Supply supply(
      EconomyData economy,
      UnitId govUnitId,
      GovernmentFormation formation,
      GovAdministrationPlan plan,
      long day) {
    Objects.requireNonNull(economy, "economy");
    return supply(economy, economy.classes(), govUnitId, formation, plan, day);
  }

  /**
   * ★★ <b>Z7d-1 有效供给</b>（设计书 Z7 冲突 1=A）：逐户 {@code 有效劳动 = min(GOV_SERVICE 承诺,
   * 该户当前实际劳动)}，再按档位权重拆两维；承诺行保留为职位（C7）。
   *
   * <p>★ <b>实际劳动来源</b>：{@code currentHouseholdRows.get(household).laborMilli()} —— 它由 app 组合根从
   * Social 的 {@code householdLaborMilli}（基础劳动 × satietyPerMille ÷ 1000）逐 tick 注入，是"饿过的劳动"在经济侧
   * 的唯一投影。家户行暂缺（旧档迁移期占位）⇒ 不 cap（保持旧行为）。本方法<b>不</b>改任何承诺行。
   *
   * <p>★ 已知边界（Z7e）：同一家户若对多个 GOV 同时有承诺，两个 GOV 各自的 cap 都拿该户完整实际劳动，跨 GOV 之和可能 重复计算同一份小时。当前世界一个官吏户只服务一个
   * GOV；跨 GOV 分摊属后续批次。
   *
   * @param currentHouseholdRows 推进中的家户行工作副本（{@code EconomyDayStepper.householdEconomies()}）；不得为
   *     null，键集应与 economy.classes() 同键
   */
  public static Supply supply(
      EconomyData economy,
      Map<HouseholdId, HouseholdEconomy> currentHouseholdRows,
      UnitId govUnitId,
      GovernmentFormation formation,
      GovAdministrationPlan plan,
      long day) {
    Objects.requireNonNull(economy, "economy");
    Objects.requireNonNull(currentHouseholdRows, "currentHouseholdRows");
    Objects.requireNonNull(formation, "formation");
    Objects.requireNonNull(plan, "plan");
    Map<HouseholdId, Long> committed = committedLaborByHousehold(economy, govUnitId, day);
    if (committed.isEmpty()) {
      return new Supply(0L, 0L, 0L, 0L, 0L, 0L, 0L);
    }
    long effectiveSecurity = 0L;
    long effectivePaperwork = 0L;
    long effectivePort = 0L;
    long committedSecurity = 0L;
    long committedPaperwork = 0L;
    long committedPort = 0L;
    long underfedHouseholds = 0L;
    long withoutPost = 0L;
    String firstWithoutPost = "-";
    // ★ Z3d：内部 householdPosts 与外部 externalPosts 同权（两张表互斥，postOf 给出唯一岗位）。
    Map<HouseholdId, GovernmentPostOfHousehold> posts = formation.allPosts();
    try {
      for (Map.Entry<HouseholdId, Long> entry : committed.entrySet()) {
        HouseholdId household = entry.getKey();
        long committedLaborMilli = entry.getValue();
        HouseholdEconomy row = currentHouseholdRows.get(household);
        // ★ Z7d-1：实际劳动 = 该户 session 工作副本的时间预算（已含 satiety 折算；缺行 ⇒ 不 cap，保持旧档行为）。
        long actualLaborMilli =
            row == null ? committedLaborMilli : Math.min(committedLaborMilli, row.laborMilli());
        GovernmentPostOfHousehold post = posts.get(household);
        if (post == null) {
          // ★ 设计书 §3：没有挂岗位的家户 ⇒ 该户承诺进不了任何维（供给 0）。这不是静默——逐 GOV 一条具名 INFO；
          //   承诺行本身一字不动（C7），等 assignPosts 把它挂到档位后下一 tick 自然计入。
          withoutPost++;
          if (withoutPost == 1L) {
            firstWithoutPost = household.value() + " laborMilli=" + committedLaborMilli;
          }
          continue;
        }
        if (actualLaborMilli < committedLaborMilli) {
          underfedHouseholds++;
        }
        long[] weights = tierWeightsOf(post, plan, govUnitId, day);
        long weightSum = Math.addExact(Math.addExact(weights[0], weights[1]), weights[2]);
        if (weightSum == 0L) {
          throw contractFailure(
              "tier-zero-weight",
              day,
              govUnitId,
              "household=" + household.value() + " tierId=" + post.tierId());
        }
        // ★ R2 三维拆分（余数归公文，w_port=0 ⇒ 逐值退化为旧两维算式；理由见类注）。
        long committedSecurityShare =
            Math.floorDiv(Math.multiplyExact(committedLaborMilli, weights[0]), weightSum);
        long committedPortShare =
            Math.floorDiv(Math.multiplyExact(committedLaborMilli, weights[2]), weightSum);
        committedSecurity = Math.addExact(committedSecurity, committedSecurityShare);
        committedPort = Math.addExact(committedPort, committedPortShare);
        committedPaperwork =
            Math.addExact(
                committedPaperwork,
                Math.subtractExact(
                    Math.subtractExact(committedLaborMilli, committedSecurityShare),
                    committedPortShare));
        long effectiveSecurityShare =
            Math.floorDiv(Math.multiplyExact(actualLaborMilli, weights[0]), weightSum);
        long effectivePortShare =
            Math.floorDiv(Math.multiplyExact(actualLaborMilli, weights[2]), weightSum);
        effectiveSecurity = Math.addExact(effectiveSecurity, effectiveSecurityShare);
        effectivePort = Math.addExact(effectivePort, effectivePortShare);
        effectivePaperwork =
            Math.addExact(
                effectivePaperwork,
                Math.subtractExact(
                    Math.subtractExact(actualLaborMilli, effectiveSecurityShare),
                    effectivePortShare));
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
    return new Supply(
        effectiveSecurity,
        effectivePaperwork,
        effectivePort,
        committedSecurity,
        committedPaperwork,
        committedPort,
        underfedHouseholds);
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

  /**
   * 档位权重（三维）：非空 tierId 必须命中计划目录；空 tierId（legacy/未指派）按 role 的固有维度回退。
   *
   * <p>★ R2：第三项 = 口岸维权重。legacy（未指派档位）的 role 回退<b>不给口岸权重</b>——旧世界的"挂了岗位但没有档位"家户一律 按旧口径进治安/公文，口岸维保持 0
   * ⇒ 逐值不变（I-P8）；要口岸编制就显式建一个 {@code portWeightPerMille > 0} 的档位并指派。
   */
  private static long[] tierWeightsOf(
      GovernmentPostOfHousehold post, GovAdministrationPlan plan, UnitId govUnitId, long day) {
    if (post.hasTier()) {
      for (GovPostTier tier : plan.postTiers()) {
        if (tier.tierId().equals(post.tierId())) {
          return new long[] {
            tier.securityWeightPerMille(), tier.paperworkWeightPerMille(), tier.portWeightPerMille()
          };
        }
      }
      throw contractFailure(
          "tier-unknown",
          day,
          govUnitId,
          "household=" + post.householdId().value() + " tierId=" + post.tierId());
    }
    // ★ legacy（空 tierId）：沿用 GovEfficiency 旧桥的 role→维口径（YAMEN=治安；SCRIBE/POST=公文；口岸=0）。
    return switch (post.role()) {
      case YAMEN -> new long[] {GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE, 0L, 0L};
      case SCRIBE, POST -> new long[] {0L, GovAdministrationPlan.NEUTRAL_MODIFIER_PER_MILLE, 0L};
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

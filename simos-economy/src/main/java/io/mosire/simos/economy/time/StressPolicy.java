package io.mosire.simos.economy.time;

import io.mosire.simos.economy.model.OperatorCondition;
import io.mosire.simos.economy.model.OperatorCondition.IndustryStatus;

/**
 * ★★ <b>S3.2 的经营压力阈值与缩产系数（唯一拼写点）</b>—— 状态机读本类，不在结算的 {@code switch} 里写死数字。
 *
 * <p>★★ <b>为什么单独一个类</b>：计划 §S3.2 明说"阈值放 {@code StressPolicy} 配置，不写死在结算里"。 V 阶段把 GM
 * 可调参数目录接上时只换本类的取值来源，状态转移的判据一个字不改。
 *
 * <p>★ <b>量纲</b>：周期数都是 {@code long} 计数；缩产系数是千分数（1000 = 满产）。
 */
public final class StressPolicy {

  /** 连续滞销多少个市场轮（关账周期）才进入 {@code OVERSUPPLIED}。 */
  public static final long UNSOLD_CYCLES_BEFORE_OVERSUPPLIED = 2L;

  /** 至少几家更便宜的卖方真的成交，才算 {@code OUTCOMPETED} 证据（0 家 ⇒ 不是竞争的锅）。 */
  public static final long OUTCOMPETED_MIN_ACTORS = 1L;

  /** 连续多少个关账周期投入不足，才进入 {@code CONTRACTING}（单周期天气式波动不触发）。 */
  public static final long INPUT_SHORTFALL_CYCLES_BEFORE_CONTRACTING = 2L;

  /**
   * ★★ <b>E1：连续投入不足多少个关账周期，{@code CONTRACTING} 就升级为 {@code SUSPENDED}</b>（前提是 {@code
   * !canSelfProvision}）。
   *
   * <p>取值 3 = 进入 {@code CONTRACTING}（2 个周期）后再观察一个周期，避免"单周期投入波动 = 破产"。自用可维生的经营者被 {@code
   * canSelfProvision} 挡在这条路之外，可长期停在 {@code CONTRACTING}。
   */
  public static final long INPUT_SHORTFALL_CYCLES_BEFORE_CANNOT_REPRODUCE = 3L;

  /**
   * ★★ <b>E1：连续滞销多少个关账周期，{@code CONTRACTING} 就升级为 {@code SUSPENDED}</b>（前提是 {@code
   * !canSelfProvision}）。
   *
   * <p>滞销计数只在"有市场轮、有挂单、零成交、且有 OUTCOMPETED 证据"时累加（见 {@code OperatorSettlement.advance}）——
   * 单轮没成交、买方没钱、买方本来就有库存都不算，本阈值因此不是"一轮卖不动就破产"。取 3 与投入不足同尺。
   */
  public static final long UNSOLD_CYCLES_BEFORE_CANNOT_REPRODUCE = 3L;

  /**
   * ★★ <b>E1：自用覆盖判据的守卫窗（天）</b>—— 用"库存可覆盖下一周期投入"代替"粮库存够一整个周期口粮"时，家户还必须至少有这一窗天数的
   * 基本口粮；否则"有种子、没饭吃"会被误判成"可自用维生"。
   *
   * <p>建议出厂值 30 天（一个月的安全垫），口径走 {@code HouseholdEconomy.expectedNeedMilli(GRAIN, 本常量)}
   * （逐户注入需求 × 30 天），不另写"每人每天多少"、也不按人口统一折算。
   */
  public static final long SELF_PROVISION_GUARD_DAYS = 30L;

  /** 连续多少个关账周期"现金+可自用产出 < 本息"，才进入 {@code INDEBTED}。 */
  public static final long DEBT_STRESS_CYCLES_BEFORE_INDEBTED = 2L;

  /** 连续多少个关账周期仍无法偿付，才从 {@code INDEBTED} 进入 {@code SUSPENDED}。 */
  public static final long DEBT_STRESS_CYCLES_BEFORE_SUSPENDED = 3L;

  /** 连续停业多少个关账周期才退出（{@code SUSPENDED -> EXITED}）。 */
  public static final long SUSPENDED_CYCLES_BEFORE_EXIT = 3L;

  /** 允许重开的最大次数（有限重开；超过后只能退出）。 */
  public static final long MAX_REOPENS = 1L;

  /** 缩产后的计划规模系数（千分数）：{@code CONTRACTING} 按一半规模生产。 */
  public static final long CONTRACTING_SCALE_PER_MILLE = 500L;

  /** 负债后的计划规模系数（千分数）：{@code INDEBTED} 再缩到四分之一。 */
  public static final long INDEBTED_SCALE_PER_MILLE = 250L;

  private StressPolicy() {}

  /**
   * 某状态下的计划规模系数（千分数；1000 = 按技术产能满产）。
   *
   * <p>★ <b>缩产不销毁 {@code Industry.capacity} / {@code OwnershipStake}</b>：本系数只乘进"本周期的计划规模"，
   * 产能与资产份额原样保留；状态回到 {@code ACTIVE} 即恢复满产。
   */
  public static long plannedScalePerMille(IndustryStatus status) {
    if (status == null) {
      return 1_000L; // 还没有条件组件（旧档）⇒ 与旧行为逐值相同：满产
    }
    return switch (status) {
      case TRIALING -> 1_000L; // 试产按当前计划跑（试产规模由 S2 的候选路径另给，不在这里压）
      case OVERSUPPLIED -> 1_000L; // 积压观察期仍按当前规模（下一周期才缩）
      case CONTRACTING -> CONTRACTING_SCALE_PER_MILLE;
      case INDEBTED -> INDEBTED_SCALE_PER_MILLE;
      case SUSPENDED, EXITING, EXITED, ABANDONED -> 0L; // 停业/退出：一料不投、一寸不种
      default -> 1_000L;
    };
  }

  /**
   * 状态是否已经"无法维持再生产"（市场原因归因用；只读判断，不做转移）。
   *
   * <p>★ 只有 {@code INDEBTED} 及以后才算——单轮卖不动是 {@code OUTCOMPETED}/自用，不是破产。
   */
  public static boolean reproductionFailed(OperatorCondition condition) {
    if (condition == null) {
      return false;
    }
    return switch (condition.status()) {
      case INDEBTED, SUSPENDED, EXITING, EXITED, ABANDONED -> true;
      default -> false;
    };
  }
}

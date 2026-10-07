package io.mosire.simos.social.household;

import java.util.Objects;

/**
 * ★★ <b>Z7d-2：单个官吏户的逃亡倾向状态</b>（Social 侧按户持久；与 {@code satietyPerMille} 同层）。
 *
 * <pre>
 * fleeRatePerMille  0..1000‰          // 当日结算后的逃亡速度（决定下一 tick 的逃亡量）
 * remainderMilli    ≥0 毫人          // 跨日结转的逃亡余数（不丢人；正常执行后 <1000，无处可去时可暂存整人）
 * lastFleeDay       ≥ 0；0 = 从未逃亡
 * lastFleeCount     ≥ 0（最近一次真走的人数）
 * lastFleeReason    最近一次真走的原因（underpaid/hunger/underpaid+hunger；空串 = 无）
 * lastDriverDay     ≥ 0；最近一次驱动评估日
 * lastDriverReason  最近一次驱动结论（satisfied/underpaid/hunger/underpaid+hunger；空串 = 未评估）
 * </pre>
 *
 * <p>★★ <b>为什么按户而不是按人</b>（用户原话"AAA，肯定按户啊"）：状态是"这个官吏户每天有多少比例的成员想走"； 真走人是按户内成员现算的确定性结果（{@code 成员数 ×
 * rate/1000 + 余数}，满 1 走 1）。
 *
 * <p>★★ <b>旧档/新世界缺键的语义</b>：{@link
 * io.mosire.simos.social.SocialData#fleeState(io.mosire.simos.social.api.id.HouseholdId)} 对缺键返回
 * {@link #none()}（rate=0、无余数、从未逃亡）——追加式字段，旧世界读回来必须逐值等于旧行为（不逃亡）。
 *
 * <p>★ <b>不变量（构造期判）</b>：rate ∈ [0,1000]；remainder ∈ [0,999]（余数永远是"不足 1 人"的部分）； day/count 非负；{@code
 * lastFleeCount > 0 ⇒ lastFleeDay > 0}；两个 reason 非 null（没有用空串）。 坏数据当场具名抛，不静默归一。
 *
 * @param fleeRatePerMille 逃亡速度（毫，0..1000）
 * @param remainderMilli 跨日逃亡余数（毫人，0..999）
 * @param lastFleeDay 最近一次真走人的世界日；0 = 从未
 * @param lastFleeCount 最近一次真走的人数（≥ 0）
 * @param lastFleeReason 最近一次真走的原因标签（非 null；无 = 空串）
 * @param lastDriverDay 最近一次驱动评估的世界日；0 = 未评估
 * @param lastDriverReason 最近一次驱动结论标签（非 null；未评估 = 空串）
 */
public record HouseholdFleeState(
    long fleeRatePerMille,
    long remainderMilli,
    long lastFleeDay,
    long lastFleeCount,
    String lastFleeReason,
    long lastDriverDay,
    String lastDriverReason) {

  /** 速度上限（毫）。 */
  public static final long RATE_MAX_PER_MILLE = 1_000L;

  /** 一人 = 1000 毫人（余数刻度）。 */
  public static final long MILLI_PER_PERSON = 1_000L;

  /** 默认/旧档缺键状态：不逃、无余数、从未逃亡、未评估。 */
  public static HouseholdFleeState none() {
    return new HouseholdFleeState(0L, 0L, 0L, 0L, "", 0L, "");
  }

  public HouseholdFleeState {
    if (fleeRatePerMille < 0L || fleeRatePerMille > RATE_MAX_PER_MILLE) {
      throw new IllegalArgumentException(
          "HouseholdFleeState.fleeRatePerMille 必须在 0..1000: " + fleeRatePerMille);
    }
    if (remainderMilli < 0L) {
      throw new IllegalArgumentException(
          "HouseholdFleeState.remainderMilli 不得为负（毫人）: " + remainderMilli);
    }
    if (lastFleeDay < 0L) {
      throw new IllegalArgumentException("HouseholdFleeState.lastFleeDay 不得为负: " + lastFleeDay);
    }
    if (lastFleeCount < 0L) {
      throw new IllegalArgumentException("HouseholdFleeState.lastFleeCount 不得为负: " + lastFleeCount);
    }
    if (lastFleeCount > 0L && lastFleeDay <= 0L) {
      throw new IllegalArgumentException(
          "HouseholdFleeState：真走人必须带正的世界日（未逃 = 计数 0）: day="
              + lastFleeDay
              + " count="
              + lastFleeCount);
    }
    if (lastFleeReason == null) {
      throw new IllegalArgumentException("HouseholdFleeState.lastFleeReason 不得为 null（无 = 空串）");
    }
    if (lastDriverDay < 0L) {
      throw new IllegalArgumentException("HouseholdFleeState.lastDriverDay 不得为负: " + lastDriverDay);
    }
    if (lastDriverReason == null) {
      throw new IllegalArgumentException("HouseholdFleeState.lastDriverReason 不得为 null（未评估 = 空串）");
    }
    if (lastFleeReason.isBlank() && lastFleeCount > 0L) {
      throw new IllegalArgumentException(
          "HouseholdFleeState：真走人必须有具名原因（欠俸/饥饿）: count=" + lastFleeCount);
    }
  }

  /** 只换速度与余数（其余原样）；用于每日驱动/执行推进。 */
  public HouseholdFleeState withRateAndRemainder(long newRatePerMille, long newRemainderMilli) {
    return new HouseholdFleeState(
        newRatePerMille,
        newRemainderMilli,
        lastFleeDay,
        lastFleeCount,
        lastFleeReason,
        lastDriverDay,
        lastDriverReason);
  }

  /** 只换最近一次驱动结论（其余原样）。 */
  public HouseholdFleeState withDriver(long day, String reason) {
    Objects.requireNonNull(reason, "reason");
    return new HouseholdFleeState(
        fleeRatePerMille, remainderMilli, lastFleeDay, lastFleeCount, lastFleeReason, day, reason);
  }

  /** 只换最近一次真走人读数（其余原样）。 */
  public HouseholdFleeState withLastFlight(long day, long count, String reason) {
    Objects.requireNonNull(reason, "reason");
    return new HouseholdFleeState(
        fleeRatePerMille, remainderMilli, day, count, reason, lastDriverDay, lastDriverReason);
  }
}

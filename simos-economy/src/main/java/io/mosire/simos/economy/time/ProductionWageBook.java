package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.ProductionWageQuote;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★★ <b>工资进位工作副本与 due 计算</b>（D-032 P4a/P4c3）。
 *
 * <p>★★ <b>本类不持有任何工资报价权威</b>：报价的唯一来源是 {@code ProductionWageAnchors}。调用方先从锚解析出
 * {@link ProductionWageQuote}，再交给 {@link #dueFor(ProductionWageQuote, ProductionUnitId, ProductionRoleId,
 * HouseholdId, long)}。本类只保存逐 {@code (unit, role, worker)} 的进位余数工作副本，并在 tick 结束时由调用方写回
 * {@code wageCarryRemainders}。
 *
 * <p>★ <b>U20 公式的唯一计算核心仍在 {@link #adjustPrice}</b>：{@link WageAnchorBook#applyObservations} 与本类都只调用这一份
 * momentum/minStep/延迟降价公式；本类不再实现旧工资表或第二套调价。
 *
 * <p>★ <b>缺价 = 调用方拒绝</b>：{@link #dueFor} 显式要求非 null 报价；本类不会用 0 或旧表兜底。显式 0 工资是合法报价。
 */
public final class ProductionWageBook {

  /** D3 §2.4：一次 due 的不可变结果；{@code carryRemainder ∈ 0..999}。 */
  public record Due(CurrencyId currency, long dueMilli, long carryRemainder) {
    public Due {
      Objects.requireNonNull(currency, "Due.currency 不得为 null");
      if (dueMilli < 0L) {
        throw new IllegalArgumentException("Due.dueMilli 不得为负: " + dueMilli);
      }
      if (carryRemainder < 0L || carryRemainder > 999L) {
        throw new IllegalArgumentException("Due.carryRemainder 必须在 0..999: " + carryRemainder);
      }
    }

    /** {@link #dueMilli()} 的短别名（D3 公式里的 {@code due}）。 */
    public long due() {
      return dueMilli;
    }

    /** {@link #carryRemainder()} 的短别名（D3 公式里的 {@code carry}）。 */
    public long carry() {
      return carryRemainder;
    }
  }

  /** D3 §3.6 的可配置工程默认；不是用户裁定，可被仿真标定覆盖。 */
  public record WagePricingPolicy(
      long increaseRatePerMille,
      long increaseMomentumCapTicks,
      long minStepMilliPerLabor,
      long maxStepMilliPerLabor,
      long decreaseDelayTicks,
      long decreaseInitialRatePerMille,
      long decreaseRateAccelPerMille,
      long decreaseRateCapPerMille) {

    public WagePricingPolicy {
      if (increaseRatePerMille < 0L
          || increaseMomentumCapTicks < 1L
          || minStepMilliPerLabor < 1L
          || maxStepMilliPerLabor < minStepMilliPerLabor
          || decreaseDelayTicks < 0L
          || decreaseInitialRatePerMille < 0L
          || decreaseRateAccelPerMille < 0L
          || decreaseRateCapPerMille < 1L) {
        throw new IllegalArgumentException("WagePricingPolicy 参数越域");
      }
    }

    /** D3 §3.6 建议值。 */
    public static WagePricingPolicy defaults() {
      return new WagePricingPolicy(100L, 8L, 1L, 200L, 3L, 10L, 5L, 100L);
    }
  }

  /**
   * ★★★ <b>U20 调价公式的唯一计算核心</b>：只做 target &gt; 0 的两分支。
   *
   * <p>本记录不携带 anchor/unit/role 身份，也不写任何状态；{@link WageAnchorBook#applyObservations} 调用本方法，避免出现第二套
   * momentum/minStep/延迟降价公式。
   */
  record PriceAdjustment(
      long wagePerLaborMilli,
      long shortageStreak,
      long satisfiedStreak,
      long lastAdjustedTick,
      long lastAdjustmentDelta,
      boolean changed) {}

  private final LinkedHashMap<WageCarryKey, Long> carries;

  /** 从当刻日视图构造一个 tick 的进位工作副本。 */
  public ProductionWageBook(EconomyDayView view) {
    Objects.requireNonNull(view, "view");
    this.carries = carryCopy(view.wageCarryRemainders());
  }

  /** 本 tick 结束时的 carry 工作副本（调用方须写回 EconomyData，且不得支付/改账户）。 */
  public Map<WageCarryKey, Long> carryRemainders() {
    return Collections.unmodifiableMap(new LinkedHashMap<>(carries));
  }

  /** 某 {@code (unit, role, worker)} 当前 carry（缺键 = 0）；只读。 */
  public long carryOf(ProductionUnitId unit, ProductionRoleId role, HouseholdId worker) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(worker, "worker");
    return carries.getOrDefault(new WageCarryKey(unit, role, worker), 0L);
  }

  /**
   * ★★★ D3 §2.4：精确计算一次逐户 due，并推进该 {@code (unit, role, worker)} 的 carry。
   *
   * <pre>
   * num   = quote.wagePerLaborMilli × tickLaborMilli + carry
   * due   = num / 1000
   * carry = num % 1000
   * </pre>
   *
   * <p>只写本对象内的 carry 工作副本，<b>不支付、不改账户、不改 EconomyData</b>。溢出 ⇒ {@code wage-quote-overflow}
   * fail-closed；{@code quote} 不得为 null（缺锚由调用方具名拒绝）。
   */
  public Due dueFor(
      ProductionWageQuote quote,
      ProductionUnitId unit,
      ProductionRoleId role,
      HouseholdId worker,
      long tickLaborMilli) {
    Objects.requireNonNull(quote, "quote（缺价不得传入 null）");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(worker, "worker");
    if (tickLaborMilli < 0L) {
      throw new IllegalArgumentException("tickLaborMilli 不得为负: " + tickLaborMilli);
    }
    WageCarryKey key = new WageCarryKey(unit, role, worker);
    long carry = carries.getOrDefault(key, 0L);
    BigInteger numerator =
        BigInteger.valueOf(quote.wagePerLaborMilli())
            .multiply(BigInteger.valueOf(tickLaborMilli))
            .add(BigInteger.valueOf(carry));
    BigInteger due = numerator.divide(BigInteger.valueOf(1000L));
    BigInteger remainder = numerator.remainder(BigInteger.valueOf(1000L));
    if (due.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
      throw new IllegalStateException(
          "wage-quote-overflow: due 超出 long 可表示范围: "
              + unit.value()
              + "|"
              + role.value()
              + "|"
              + worker.value());
    }
    long dueMilli = due.longValueExact();
    long carryRemainder = remainder.longValueExact();
    carries.put(key, carryRemainder);
    if (EconomyLog.trace().isTraceEnabled()) {
      EventLog.channel(EconomyLog.trace())
          .trace(
              LogEvent.of(
                  "PRODUCTION_WAGE_DUE_CARRY",
                  EconomyLogSource.ECONOMY_SETTLEMENT,
                  "unit",
                  unit.value(),
                  "role",
                  role.value(),
                  "worker",
                  worker.value(),
                  "tickLaborMilli",
                  tickLaborMilli,
                  "quote",
                  quote.wagePerLaborMilli(),
                  "carryBefore",
                  carry,
                  "dueMilli",
                  dueMilli,
                  "carryAfter",
                  carryRemainder));
    }
    return new Due(quote.currency(), dueMilli, carryRemainder);
  }

  /**
   * ★★★ <b>D3 §3.3/§3.4 的同一套调价公式</b>（anchor 观察调用；调用方保证 target &gt; 0）。
   *
   * <p>缺工：shortagePerMille → momentum → raw/base/step 封顶；招满：satisfiedStreak 超过 delay 后才按 initial+accel
   * 降价、每次至少 1（若 wage &gt; 0）、不穿 0。所有中间乘法用 BigInteger；改价才推进
   * {@code lastAdjustedTick}/{@code lastAdjustmentDelta}。
   */
  static PriceAdjustment adjustPrice(
      long currentWage,
      long shortageStreak,
      long satisfiedStreak,
      long lastAdjustedTick,
      long lastAdjustmentDelta,
      long targetLaborMilli,
      long matchedLaborMilli,
      WagePricingPolicy policy,
      long observedTick) {
    Objects.requireNonNull(policy, "policy");
    if (currentWage < 0L || shortageStreak < 0L || satisfiedStreak < 0L) {
      throw new IllegalArgumentException("adjustPrice 的工资/streak 不得为负");
    }
    if (targetLaborMilli <= 0L || matchedLaborMilli < 0L) {
      throw new IllegalArgumentException(
          "adjustPrice 只接受 target>0 且 matched>=0: target="
              + targetLaborMilli
              + " matched="
              + matchedLaborMilli);
    }
    if (lastAdjustedTick < -1L || observedTick < 0L) {
      throw new IllegalArgumentException("adjustPrice 的 tick 越域");
    }
    long nextWage = currentWage;
    long nextShortageStreak = shortageStreak;
    long nextSatisfiedStreak = satisfiedStreak;
    long nextLastAdjustedTick = lastAdjustedTick;
    long nextLastAdjustmentDelta = lastAdjustmentDelta;
    boolean changed = false;

    if (matchedLaborMilli < targetLaborMilli) {
      nextShortageStreak = Math.addExact(shortageStreak, 1L);
      nextSatisfiedStreak = 0L;
      long shortagePerMille = shortagePerMille(targetLaborMilli - matchedLaborMilli, targetLaborMilli);
      long momentum = Math.min(policy.increaseMomentumCapTicks(), nextShortageStreak);
      BigInteger raw =
          BigInteger.valueOf(currentWage)
              .multiply(BigInteger.valueOf(policy.increaseRatePerMille()))
              .multiply(BigInteger.valueOf(shortagePerMille))
              .divide(BigInteger.valueOf(1_000_000L));
      BigInteger rawCapped = raw.min(BigInteger.valueOf(policy.maxStepMilliPerLabor()));
      long base = Math.max(policy.minStepMilliPerLabor(), rawCapped.longValueExact());
      BigInteger step =
          BigInteger.valueOf(base)
              .multiply(BigInteger.valueOf(momentum))
              .min(BigInteger.valueOf(policy.maxStepMilliPerLabor()));
      long delta = Math.min(step.longValueExact(), Long.MAX_VALUE - currentWage);
      if (delta > 0L) {
        nextWage = Math.addExact(currentWage, delta);
        nextLastAdjustedTick = observedTick;
        nextLastAdjustmentDelta = delta;
        changed = true;
      }
    } else {
      nextShortageStreak = 0L;
      nextSatisfiedStreak = Math.addExact(satisfiedStreak, 1L);
      if (nextSatisfiedStreak > policy.decreaseDelayTicks() && currentWage > 0L) {
        BigInteger rate =
            BigInteger.valueOf(policy.decreaseInitialRatePerMille())
                .add(
                    BigInteger.valueOf(nextSatisfiedStreak - policy.decreaseDelayTicks())
                        .multiply(BigInteger.valueOf(policy.decreaseRateAccelPerMille())))
                .min(BigInteger.valueOf(policy.decreaseRateCapPerMille()));
        BigInteger raw =
            BigInteger.valueOf(currentWage)
                .multiply(rate)
                .divide(BigInteger.valueOf(1000L));
        long step = Math.max(1L, raw.longValueExact());
        long delta = Math.min(step, currentWage);
        if (delta > 0L) {
          nextWage = currentWage - delta;
          nextLastAdjustedTick = observedTick;
          nextLastAdjustmentDelta = -delta;
          changed = true;
        }
      }
    }
    return new PriceAdjustment(
        nextWage,
        nextShortageStreak,
        nextSatisfiedStreak,
        nextLastAdjustedTick,
        nextLastAdjustmentDelta,
        changed);
  }

  private static long shortagePerMille(long shortage, long effectiveTarget) {
    BigInteger value =
        BigInteger.valueOf(shortage)
            .multiply(BigInteger.valueOf(1000L))
            .divide(BigInteger.valueOf(effectiveTarget));
    return Math.max(1L, Math.min(1000L, value.longValueExact()));
  }

  private static LinkedHashMap<WageCarryKey, Long> carryCopy(Map<WageCarryKey, Long> source) {
    Objects.requireNonNull(source, "wageCarryRemainders");
    LinkedHashMap<WageCarryKey, Long> copy = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          Objects.requireNonNull(key, "wageCarryRemainders 不得含 null 键");
          Objects.requireNonNull(value, "wageCarryRemainders 不得含 null 值");
          if (value < 0L || value > 999L) {
            throw new IllegalArgumentException("wage-carry-out-of-range: " + key + "=" + value);
          }
          copy.put(key, value);
        });
    return copy;
  }
}

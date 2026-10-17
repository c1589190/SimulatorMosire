package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.ProductionWageDefault;
import io.mosire.simos.economy.model.ProductionWageDefaultLookup;
import io.mosire.simos.economy.model.ProductionWagePosting;
import io.mosire.simos.economy.model.ProductionWageQuote;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.economy.model.WageDefaultKey;
import io.mosire.simos.economy.model.WagePostingKey;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;

/**
 * ★★★ <b>W2a/W2b 的真实工资读口与逐 tick 工资计算</b>（D-032 D3 §2.2.1/§2.3/§2.4/§3）。
 *
 * <p>★★ <b>三类状态严格分开</b>：
 *
 * <ul>
 *   <li>已存在实例的执行期报价：只读 {@code productionWagePostings}（{@link ProductionWageQuoteLookup}）；
 *   <li>实例存在前的候选估价：只读 {@code productionWageDefaults}（{@link ProductionWageDefaultLookup}）；
 *   <li>逐 {@code (unit, role, worker)} 的进位余数：{@link #dueFor} 读/写本 tick 工作副本，最终由调用方写回 {@code
 *       wageCarryRemainders} 第 35 组件。
 * </ul>
 *
 * <p>★ <b>缺 = empty、0 = 合法</b>：本类不使用 {@code getOrDefault(..., 0)}；显式 0 报价/默认会原样返回。
 * 构造期按当刻视图冻结三张表；U20/命令改价只影响后续 tick。{@link #dueFor} <b>不支付、不改账户</b>。
 */
public final class ProductionWageBook
    implements ProductionWageQuoteLookup, ProductionWageDefaultLookup {

  private static final Logger LOG = EconomyLog.settlement();

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

  /** 一个 posting 的 tick 末观测值；U20 只用本 tick 实际值与两级 cap 后的有效目标。 */
  public record WageObservation(
      ProductionUnitId unit,
      ProductionRoleId role,
      long matchedLaborMilli,
      long maxAbsorbableNonLaborForRole) {
    public WageObservation {
      Objects.requireNonNull(unit, "WageObservation.unit 不得为 null");
      Objects.requireNonNull(role, "WageObservation.role 不得为 null");
      if (matchedLaborMilli < 0L || maxAbsorbableNonLaborForRole < 0L) {
        throw new IllegalArgumentException("WageObservation 的劳动量不得为负");
      }
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

  private final Map<WagePostingKey, ProductionWagePosting> postings;
  private final Map<WageDefaultKey, ProductionWageDefault> defaults;
  private final LinkedHashMap<WageCarryKey, Long> carries;

  /** 从当刻日视图构造一个 tick 的只读快照（carry 为工作副本）。 */
  public ProductionWageBook(EconomyDayView view) {
    Objects.requireNonNull(view, "view");
    this.postings = freeze(view.productionWagePostings(), "productionWagePostings");
    this.defaults = freeze(view.productionWageDefaults(), "productionWageDefaults");
    this.carries = carryCopy(view.wageCarryRemainders());
  }

  /** 显式以三张只读表构造（测试/装配用；表必须在别处冻结）。 */
  public ProductionWageBook(
      Map<WagePostingKey, ProductionWagePosting> postings,
      Map<WageDefaultKey, ProductionWageDefault> defaults,
      Map<WageCarryKey, Long> carries) {
    this.postings = freeze(postings, "postings");
    this.defaults = freeze(defaults, "defaults");
    this.carries = carryCopy(carries);
  }

  /** 兼容 W2a 的两表构造：carry 全 0。 */
  public ProductionWageBook(
      Map<WagePostingKey, ProductionWagePosting> postings,
      Map<WageDefaultKey, ProductionWageDefault> defaults) {
    this(postings, defaults, Map.of());
  }

  @Override
  public Optional<ProductionWageQuote> quoteFor(ProductionUnitId unit, ProductionRoleId role) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(role, "role");
    ProductionWagePosting posting = postings.get(new WagePostingKey(unit, role));
    return posting == null
        ? Optional.empty()
        : Optional.of(new ProductionWageQuote(posting.currency(), posting.wagePerLaborMilli()));
  }

  @Override
  public Optional<ProductionWageDefault> defaultFor(
      IndustryId plan, ProductionRoleId role, MarketZoneId zone) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(zone, "zone");
    return Optional.ofNullable(defaults.get(new WageDefaultKey(plan, role, zone)));
  }

  /** 本 tick 的 posting 快照（只读，按 {@code toString()} 稳定序）。 */
  public Map<WagePostingKey, ProductionWagePosting> postingSnapshot() {
    return postings;
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
   * fail-closed；缺报价 ⇒ {@code wage-quote-missing}。
   */
  public Due dueFor(
      ProductionUnitId unit, ProductionRoleId role, HouseholdId worker, long tickLaborMilli) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(worker, "worker");
    if (tickLaborMilli < 0L) {
      throw new IllegalArgumentException("tickLaborMilli 不得为负: " + tickLaborMilli);
    }
    ProductionWageQuote quote =
        quoteFor(unit, role)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "wage-quote-missing: " + unit.value() + "|" + role.value()));
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
   * ★★★ D3 §3 U20：本 tick 匹配完成后，用实际观测计算下一 tick 的 posting；本 tick 已发生的 due 不追溯。
   *
   * @return 新的完整 posting 表；调用方在 tick 末写回 {@code productionWagePostings}
   */
  public Map<WagePostingKey, ProductionWagePosting> adjustedPostings(
      List<WageObservation> observations, WagePricingPolicy policy, long observedTick) {
    Objects.requireNonNull(observations, "observations");
    Objects.requireNonNull(policy, "policy");
    if (observedTick < 0L) {
      throw new IllegalArgumentException("observedTick 不得为负: " + observedTick);
    }
    List<WageObservation> ordered = new ArrayList<>(observations);
    ordered.sort(
        Comparator.comparing((WageObservation value) -> value.unit().value())
            .thenComparing(value -> value.role().value()));
    LinkedHashMap<WagePostingKey, ProductionWagePosting> next = new LinkedHashMap<>(postings);
    for (WageObservation observation : ordered) {
      Objects.requireNonNull(observation, "observations 不得含 null");
      WagePostingKey key = new WagePostingKey(observation.unit(), observation.role());
      ProductionWagePosting current = next.get(key);
      if (current == null) {
        throw new IllegalStateException(
            "wage-posting-unit-unknown: "
                + observation.unit().value()
                + "|"
                + observation.role().value());
      }
      ProductionWagePosting adjusted = adjustOne(current, observation, policy, observedTick);
      next.put(key, adjusted);
      boolean quoteChanged = adjusted.wagePerLaborMilli() != current.wagePerLaborMilli();
      LogEvent event =
          LogEvent.of(
              quoteChanged ? "PRODUCTION_WAGE_ADJUSTED" : "PRODUCTION_WAGE_OBSERVED",
              EconomyLogSource.ECONOMY_SETTLEMENT,
              "unit",
              adjusted.unit().value(),
              "role",
              adjusted.role().value(),
              "oldWage",
              current.wagePerLaborMilli(),
              "newWage",
              adjusted.wagePerLaborMilli(),
              "matchedLaborMilli",
              observation.matchedLaborMilli(),
              "effectiveTarget",
              adjusted.lastObservedEffectiveTarget(),
              "maxAbsorbable",
              observation.maxAbsorbableNonLaborForRole(),
              "shortageStreak",
              adjusted.shortageStreak(),
              "satisfiedStreak",
              adjusted.satisfiedStreak());
      if (quoteChanged) {
        EventLog.channel(LOG).info(event);
      } else if (EconomyLog.settlement().isDebugEnabled()) {
        EventLog.channel(LOG).debug(event);
      }
    }
    return Collections.unmodifiableMap(next);
  }

  private static ProductionWagePosting adjustOne(
      ProductionWagePosting posting,
      WageObservation observation,
      WagePricingPolicy policy,
      long observedTick) {
    long effectiveTarget =
        Math.min(posting.targetLaborMilliPerDay(), observation.maxAbsorbableNonLaborForRole());
    long matched = Math.min(observation.matchedLaborMilli(), Long.MAX_VALUE);
    long nextWage = posting.wagePerLaborMilli();
    long shortageStreak = posting.shortageStreak();
    long satisfiedStreak = posting.satisfiedStreak();
    long lastAdjustedTick = posting.lastAdjustedTick();
    long lastAdjustmentDelta = posting.lastAdjustmentDelta();

    if (effectiveTarget == 0L) {
      shortageStreak = 0L;
      satisfiedStreak = 0L;
      lastAdjustmentDelta = 0L;
    } else if (matched < effectiveTarget) {
      shortageStreak = Math.addExact(shortageStreak, 1L);
      satisfiedStreak = 0L;
      long shortagePerMille = shortagePerMille(effectiveTarget - matched, effectiveTarget);
      long momentum = Math.min(policy.increaseMomentumCapTicks(), shortageStreak);
      BigInteger raw =
          BigInteger.valueOf(posting.wagePerLaborMilli())
              .multiply(BigInteger.valueOf(policy.increaseRatePerMille()))
              .multiply(BigInteger.valueOf(shortagePerMille))
              .divide(BigInteger.valueOf(1_000_000L));
      BigInteger rawCapped = raw.min(BigInteger.valueOf(policy.maxStepMilliPerLabor()));
      long base = Math.max(policy.minStepMilliPerLabor(), rawCapped.longValueExact());
      BigInteger step =
          BigInteger.valueOf(base)
              .multiply(BigInteger.valueOf(momentum))
              .min(BigInteger.valueOf(policy.maxStepMilliPerLabor()));
      long delta = Math.min(step.longValueExact(), Long.MAX_VALUE - nextWage);
      if (delta > 0L) {
        nextWage = Math.addExact(nextWage, delta);
        lastAdjustedTick = observedTick;
        lastAdjustmentDelta = delta;
      }
    } else {
      shortageStreak = 0L;
      satisfiedStreak = Math.addExact(satisfiedStreak, 1L);
      if (satisfiedStreak > policy.decreaseDelayTicks() && posting.wagePerLaborMilli() > 0L) {
        BigInteger rate =
            BigInteger.valueOf(policy.decreaseInitialRatePerMille())
                .add(
                    BigInteger.valueOf(satisfiedStreak - policy.decreaseDelayTicks())
                        .multiply(BigInteger.valueOf(policy.decreaseRateAccelPerMille())))
                .min(BigInteger.valueOf(policy.decreaseRateCapPerMille()));
        BigInteger raw =
            BigInteger.valueOf(posting.wagePerLaborMilli())
                .multiply(rate)
                .divide(BigInteger.valueOf(1000L));
        long step = Math.max(1L, raw.longValueExact());
        long delta = Math.min(step, posting.wagePerLaborMilli());
        if (delta > 0L) {
          nextWage = posting.wagePerLaborMilli() - delta;
          lastAdjustedTick = observedTick;
          lastAdjustmentDelta = -delta;
        }
      }
    }
    return new ProductionWagePosting(
        posting.unit(),
        posting.role(),
        posting.targetLaborMilliPerDay(),
        posting.currency(),
        nextWage,
        posting.quoteSource(),
        shortageStreak,
        satisfiedStreak,
        lastAdjustedTick,
        lastAdjustmentDelta,
        observation.matchedLaborMilli(),
        effectiveTarget,
        observation.maxAbsorbableNonLaborForRole());
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

  private static <K, V> Map<K, V> freeze(Map<K, V> source, String name) {
    Objects.requireNonNull(source, name);
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }
}

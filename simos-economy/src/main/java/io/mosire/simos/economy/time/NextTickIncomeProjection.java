package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.model.ProductionWageQuote;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * ★★★ <b>D-032 D3 §2.5.1.0 / D2 §8.2：下一 tick 收入投影（W2b 唯一 owner）</b>。
 *
 * <p>纯只读、无状态、无账户/市场写。D2 的周期 {@code Prospect}/{@code × cycleDays} 预计只能作为本方法的输入； 输出只包含下一 tick
 * 可交付量。产品份额只有 {@code cycleEndTick <= nextTick} 才计入，否则该 tick 为 0。
 */
public interface NextTickIncomeProjection {

  NextTickIncomeProjectionResult project(NextTickIncomeProjectionRequest request);

  /** 唯一无状态实现。 */
  static NextTickIncomeProjection standard() {
    return StandardNextTickIncomeProjection.INSTANCE;
  }
}

/** {@link NextTickIncomeProjection} 的唯一实现；缺 cycle/quote/target/劳动计划一律具名 fail-closed。 */
final class StandardNextTickIncomeProjection implements NextTickIncomeProjection {

  static final StandardNextTickIncomeProjection INSTANCE = new StandardNextTickIncomeProjection();

  private StandardNextTickIncomeProjection() {}

  @Override
  public NextTickIncomeProjectionResult project(NextTickIncomeProjectionRequest request) {
    Objects.requireNonNull(request, "NextTickIncomeProjectionRequest 不得为 null");
    if (request.nextTick() < 0L || request.cycleStartTick() < 0L || request.cycleEndTick() < 0L) {
      throw new SurvivalOutlookException(
          "survival-income-timing-unknown", "nextTick/cycleStartTick/cycleEndTick 必须 ≥ 0");
    }
    if (request.cycleEndTick() < request.cycleStartTick()
        || request.nextTick() < request.cycleStartTick()) {
      throw new SurvivalOutlookException(
          "survival-income-timing-unknown",
          "cycle 区间非法或 nextTick 早于 cycleStartTick: next="
              + request.nextTick()
              + " cycle=["
              + request.cycleStartTick()
              + ","
              + request.cycleEndTick()
              + "]");
    }
    if (request.expectedNextTickLaborMilli().isEmpty()) {
      throw new SurvivalOutlookException("survival-income-projection-missing", "缺下一 tick 劳动计划");
    }
    if (request.quote().isEmpty()) {
      throw new SurvivalOutlookException("survival-income-projection-missing", "缺 locked quote");
    }
    if (request.targetLaborMilliPerDay().isEmpty()) {
      throw new SurvivalOutlookException("survival-income-projection-missing", "缺 locked target");
    }
    ProductionWageQuote quote = request.quote().orElseThrow();
    OptionalLong target = request.targetLaborMilliPerDay();
    long totalLabor = 0L;
    for (long labor : request.expectedNextTickLaborMilli().values()) {
      try {
        totalLabor = Math.addExact(totalLabor, labor);
      } catch (ArithmeticException overflow) {
        throw new SurvivalOutlookException("survival-input-invalid", "下一 tick 劳动计划相加溢出");
      }
    }
    long wageLabor = Math.min(totalLabor, target.getAsLong());
    BigInteger wage =
        BigInteger.valueOf(quote.wagePerLaborMilli())
            .multiply(BigInteger.valueOf(wageLabor))
            .divide(BigInteger.valueOf(1000L));
    if (wage.compareTo(BigInteger.valueOf(Long.MAX_VALUE)) > 0) {
      throw new SurvivalOutlookException("wage-quote-overflow", "下一 tick 工资投影溢出");
    }
    Map<CurrencyId, Long> money = new LinkedHashMap<>();
    if (wage.signum() > 0) {
      money.put(quote.currency(), wage.longValueExact());
    }
    Map<CommodityId, Long> productShare = new LinkedHashMap<>();
    boolean productAvailable = request.cycleEndTick() <= request.nextTick();
    if (productAvailable) {
      productShare.putAll(request.cycleProductShareMilli());
    }
    long availableAtTick =
        money.isEmpty() && !productShare.isEmpty() ? request.cycleEndTick() : request.nextTick();
    return new NextTickIncomeProjectionResult(money, Map.of(), productShare, availableAtTick);
  }
}

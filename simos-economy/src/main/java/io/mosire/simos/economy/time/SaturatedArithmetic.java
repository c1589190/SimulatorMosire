package io.mosire.simos.economy.time;

import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.log.LogOrigin;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;

/**
 * ★★★ <b>W8（2026-10-11 用户裁定「修：溢出不再致命（世界能继续跑）」）：整数越界的<b>饱和</b>唯一拼写点</b>。
 *
 * <p>用户原话（第 690 天硬停的选项里选定）：
 *
 * <blockquote>
 *
 * 「<b>修：溢出不再致命（世界能继续跑）</b>」
 *
 * </blockquote>
 *
 * <p>★★ <b>改的是什么、没改什么</b>：
 *
 * <ul>
 *   <li><b>没改</b>：原实现"拒绝回退到 double"的精度纪律<b>原样保留</b> —— 本类全程 {@code long}/{@code Math.multiplyHigh}
 *       整数运算，<b>不出现任何浮点</b>，也不做近似；正常路径（中间量放得下）逐值不变。
 *   <li><b>改了</b>：中间量<b>确实</b>超界时的失败语义 —— 从"具名拒绝推进（世界硬停）"改成"夹到 {@link Long#MAX_VALUE} +
 *       <b>具名记录</b>"。
 * </ul>
 *
 * <p>★★ <b>两条纪律（用户裁定 + AGENTS §一.9）</b>：
 *
 * <ol>
 *   <li><b>饱和只用在"确实超界"的路径上</b>：先用 128 位比较判"结果是否真的越界"，只有真的越界才夹； 中间量放不下 {@code long} 但<b>结果</b>放得下（例如
 *       {@code q·n = 2^63} 而除数 {@code = 2}）⇒ 给出<b>逐值精确</b>的商，不夹。
 *   <li><b>不许静默算错</b>：每次饱和都发 {@code ARITHMETIC_SATURATED}，带 {@code site} / 操作数 / 除数 / 夹取前的量级（{@code
 *       productHigh}/{@code productLow}/{@code productBits}）；<b>每个 site 的第一次是 INFO</b> （默认 INFO
 *       档也看得见"这里发生过饱和"），其后 DEBUG 不刷屏。★ 日志只记录，<b>不进状态、不改算式</b>。
 * </ol>
 *
 * <p>★ <b>为什么要有这个类</b>：同一套 128 位"精确或饱和"的算术在债务估值（{@link DebtValuation}）与经营者债务本息合计 （{@link
 * OperatorSettlement}）两处都要用；各写一份必然漂成两把尺（本仓"唯一拼写点"纪律）。 ★ 与 {@link SettlementOrderBook} 里那对 private
 * {@code saturatedAdd/saturatedMul} 的关系如实记：那对是<b>先于本类存在</b>的
 * 静默饱和（只用于"已经是饱和值"的求和面，无具名记录），本批<b>不动</b>它们；本类是"必须具名"的新纪律的落点。
 */
final class SaturatedArithmetic {

  /**
   * 每个 site 的"第一条是否已经报过"闸门（键 = {@code site}）。
   *
   * <p>★ 只影响日志级别，**不进任何状态、不改任何算式**（AGENTS §一.9：日志失败不得影响结算）。
   */
  private static final ConcurrentHashMap<String, Boolean> FIRST_LOGGED = new ConcurrentHashMap<>();

  private SaturatedArithmetic() {}

  /**
   * ★ <b>饱和加法</b>：{@code left + right}，越界 ⇒ 同号侧的极值（{@link Long#MAX_VALUE} / {@link
   * Long#MIN_VALUE}）并具名记录。
   *
   * @param left 被加数
   * @param right 加数
   * @param logger 记录用的分类 logger（调用方自己的门面，不在这里拼 logger 名）
   * @param origin 事件来源（写进 {@code origin=}）
   * @param site 饱和点标识（唯一、稳定、便于 grep）
   * @param context 附加的具名字段（偶数个 key/value；可为空）
   * @return 精确和，或同号侧极值
   */
  static long add(
      long left, long right, Logger logger, LogOrigin origin, String site, Object... context) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException overflow) {
      long saturated = left > 0L ? Long.MAX_VALUE : Long.MIN_VALUE;
      LinkedHashMap<String, Object> magnitude = new LinkedHashMap<>();
      magnitude.put("left", left);
      magnitude.put("right", right);
      magnitude.put("leftBits", 64 - Long.numberOfLeadingZeros(Math.abs(left)));
      magnitude.put("rightBits", 64 - Long.numberOfLeadingZeros(Math.abs(right)));
      log(logger, origin, site, saturated, magnitude, context);
      return saturated;
    }
  }

  /**
   * ★★ <b>{@code ⌊quantity × numerator ÷ divisor⌋}</b>：乘积放得下 {@code long} ⇒ 与原来那一句 {@code
   * Math.multiplyExact(…)/divisor} <b>逐值相同</b>；放不下 ⇒ 用 128 位算<b>精确</b>商，只有商真的超过 {@link
   * Long#MAX_VALUE} 才夹取并具名记录。任何非正输入（或除数为 0）⇒ 0。
   *
   * <p>★ 本方法就是"债务估值整数溢出"那条具名失败的替代出口。
   *
   * @param quantity 被乘数（毫数量/本金）
   * @param numerator 乘数（单价口径/利率‰）
   * @param divisor 除数（量纲除数）
   * @param logger 记录用的分类 logger
   * @param origin 事件来源
   * @param site 饱和点标识
   * @param context 附加具名字段（偶数个 key/value）
   * @return {@code ⌊quantity × numerator ÷ divisor⌋}，越界时 {@link Long#MAX_VALUE}
   */
  static long floorMultiplyDivide(
      long quantity,
      long numerator,
      long divisor,
      Logger logger,
      LogOrigin origin,
      String site,
      Object... context) {
    if (quantity <= 0L || numerator <= 0L || divisor <= 0L) {
      return 0L;
    }
    try {
      return Math.multiplyExact(quantity, numerator) / divisor;
    } catch (ArithmeticException overflow) {
      return divide128Saturated(
          Math.multiplyHigh(quantity, numerator),
          quantity * numerator,
          divisor,
          logger,
          origin,
          site,
          quantity,
          numerator,
          context);
    }
  }

  /**
   * ★ <b>128 位无符号被除数 {@code (high:low) ÷ divisor} 的饱和商</b>：先与 {@code Long.MAX_VALUE × divisor} 的
   * 128 位值比大小，只有<b>商真的越界</b>才夹取；否则给出逐值精确的商。
   *
   * <p>★ 实现：{@link Math#multiplyHigh} 取高 64 位 + 64 步移位减法做 128÷64 —— 全程整数、零分配、零 double。
   *
   * @param high 被除数高 64 位（两个正操作数的乘积 ⇒ 恒 ≥ 0）
   * @param low 被除数低 64 位（按<b>无符号</b>读）
   * @param divisor 除数（必须 &gt; 0）
   * @param logger 记录用的分类 logger
   * @param origin 事件来源
   * @param site 饱和点标识
   * @param quantity 参与乘法的左操作数（只进日志）
   * @param numerator 参与乘法的右操作数（只进日志）
   * @param context 附加具名字段（偶数个 key/value）
   * @return {@code ⌊(high:low) ÷ divisor⌋}，越界时 {@link Long#MAX_VALUE}
   */
  static long divide128Saturated(
      long high,
      long low,
      long divisor,
      Logger logger,
      LogOrigin origin,
      String site,
      long quantity,
      long numerator,
      Object... context) {
    Objects.requireNonNull(logger, "logger");
    Objects.requireNonNull(origin, "origin");
    long limitHigh = Math.multiplyHigh(Long.MAX_VALUE, divisor);
    long limitLow = Long.MAX_VALUE * divisor;
    if (high > limitHigh || (high == limitHigh && Long.compareUnsigned(low, limitLow) > 0)) {
      LinkedHashMap<String, Object> magnitude = new LinkedHashMap<>();
      magnitude.put("quantity", quantity);
      magnitude.put("unitValue", numerator);
      magnitude.put("divisor", divisor);
      magnitude.put("productHigh", high);
      magnitude.put("productLow", low);
      magnitude.put(
          "productBits",
          high == 0L ? 64 - Long.numberOfLeadingZeros(low) : 128 - Long.numberOfLeadingZeros(high));
      log(logger, origin, site, Long.MAX_VALUE, magnitude, context);
      return Long.MAX_VALUE;
    }
    return divide128(high, low, divisor);
  }

  /**
   * ★ {@code ⌊(high:low) ÷ divisor⌋} 的 64 步移位减法（调用方已证明商 ≤ {@link Long#MAX_VALUE}）。
   *
   * <p>不变式：余数恒 {@code < divisor ≤ Long.MAX_VALUE} ⇒ {@code remainder << 1} 最多用满 64 位（无符号）， 故用
   * {@link Long#compareUnsigned} 比较、减法后余数又回到 {@code < divisor}。
   */
  private static long divide128(long high, long low, long divisor) {
    long quotient = 0L;
    long remainder = high;
    for (int bit = 63; bit >= 0; bit--) {
      remainder = (remainder << 1) | ((low >>> bit) & 1L);
      if (Long.compareUnsigned(remainder, divisor) >= 0) {
        remainder -= divisor;
        quotient |= (1L << bit);
      }
    }
    return quotient;
  }

  /** ★ 饱和的具名可观测（每个 site 第一次 INFO、其后 DEBUG；含操作数/除数/夹取前量级）。 */
  private static void log(
      Logger logger,
      LogOrigin origin,
      String site,
      long saturatedTo,
      LinkedHashMap<String, Object> magnitude,
      Object... context) {
    if (!logger.isDebugEnabled() && FIRST_LOGGED.containsKey(site)) {
      return; // INFO 已报过且 DEBUG 关着 ⇒ 不必构造事件（日志失败/开销不得影响结算）
    }
    LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
    fields.put("site", site);
    fields.putAll(magnitude);
    fields.put("saturatedTo", saturatedTo);
    if (context != null) {
      for (int index = 0; index + 1 < context.length; index += 2) {
        fields.put(String.valueOf(context[index]), context[index + 1]);
      }
    }
    fields.put(
        "why", "w8:integer-intermediate-exceeds-long:saturate-named-instead-of-refusing-progress");
    Object[] keyValues = new Object[fields.size() * 2];
    int cursor = 0;
    for (Map.Entry<String, Object> field : fields.entrySet()) {
      keyValues[cursor++] = field.getKey();
      keyValues[cursor++] = field.getValue();
    }
    LogEvent event = LogEvent.of("ARITHMETIC_SATURATED", origin, keyValues);
    if (FIRST_LOGGED.putIfAbsent(site, Boolean.TRUE) == null) {
      EventLog.channel(logger).info(event);
    } else if (logger.isDebugEnabled()) {
      EventLog.channel(logger).debug(event);
    }
  }
}

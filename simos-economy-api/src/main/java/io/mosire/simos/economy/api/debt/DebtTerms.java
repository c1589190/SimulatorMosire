package io.mosire.simos.economy.api.debt;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * ★★ <b>债务合同条款</b>（E4a；理想架构 §2.7）：一条连续欠账除了 (debtor, creditor, unit) 之外，决定 “能不能悄悄并成同一条”的全部合同维。
 *
 * <pre>
 * DebtTerms(
 *   interestRatePerMillePerCycle, // 每周期利率（千分数）；≥ 0
 *   interestTiming,               // 计息时点；默认 AFTER_REPAYMENT_ON_CLOSE（复现旧行为）
 *   repaymentRule,                // 偿还规则；默认 AVAILABLE_SURPLUS_SHARE（复现旧行为）
 *   monetaryConversion,           // 货币折偿条款；默认 NOT_ALLOWED（无有效价格不折）
 *   defaultRemedy,                // 违约救济；默认 MARK_DEFAULTED（复现旧 defaulted=true）
 *   dueCycle, dueDay)             // 合同约定期限（可空；空 = 没有写死到某一周期/某一天）
 * </pre>
 *
 * <p>★★ <b>默认 legacy terms 逐维复现旧行为</b>：利率取旧常量 20‰/周期，计息时点 = 关账日偿还后计息并入 本金，偿还规则 = 关账日按可用余粮比例还本，折偿 =
 * 不允许，违约救济 = 只标违约，期限 = 空（旧 {@code dueCycle = 当前周期 + 1} 是逐笔借入的到期日，不属于连续合同的条款维；它落在 {@code
 * DebtContract.dueCycle}，见那个类型的类注）。
 *
 * <p>★★ <b>身份维</b>：{@link #identityToken()} 覆盖上面**所有**字段，{@link #stableKey()} 是同一份内容的
 * 可读规范串（只含数字/枚举名，无自由文本，故无需转义）。{@code DebtContractId} 取前者做十六进制转义后再拼， 保证“不同 terms ⇒ 不同 id”，不允许静默合并。
 *
 * <p>★ {@code dueCycle}/{@code dueDay} 是**条款**：填了就是合同写死的期限维（参与身份）；空 = 期限由合同记录 的滚动字段表达（legacy
 * 行为）。两者都不得为负。
 *
 * @param interestRatePerMillePerCycle 每周期利率（千分数）；不得为负
 * @param interestTiming 计息时点；不得为 null
 * @param repaymentRule 偿还规则；不得为 null
 * @param monetaryConversion 货币折偿条款；不得为 null
 * @param defaultRemedy 违约救济；不得为 null
 * @param dueCycle 条款期限（周期号）；不得为 null（无期限用 {@code OptionalLong.empty()}）
 * @param dueDay 条款期限（日号）；不得为 null（无期限用 {@code OptionalLong.empty()}）
 */
public record DebtTerms(
    int interestRatePerMillePerCycle,
    InterestTiming interestTiming,
    RepaymentRule repaymentRule,
    MonetaryConversion monetaryConversion,
    DefaultRemedy defaultRemedy,
    OptionalLong dueCycle,
    OptionalLong dueDay) {

  /** ★ 旧生产路径的利率字面量（唯一拼写点；{@code 旧结算引擎（R3a 已删除）} 的公开常量引用它）。 */
  public static final int LEGACY_INTEREST_RATE_PER_MILLE_PER_CYCLE = 20;

  public DebtTerms {
    if (interestRatePerMillePerCycle < 0) {
      throw new IllegalArgumentException(
          "DebtTerms.interestRatePerMillePerCycle 不得为负: " + interestRatePerMillePerCycle);
    }
    Objects.requireNonNull(interestTiming, "DebtTerms.interestTiming 不得为 null");
    Objects.requireNonNull(repaymentRule, "DebtTerms.repaymentRule 不得为 null");
    Objects.requireNonNull(monetaryConversion, "DebtTerms.monetaryConversion 不得为 null");
    Objects.requireNonNull(defaultRemedy, "DebtTerms.defaultRemedy 不得为 null");
    // ★ 旧档/手写 JSON 缺键时 Jackson 可能把 OptionalLong 位置绑成 null ⇒ 统一收成 empty（与其余组件的缺键口径一致）。
    if (dueCycle == null) {
      dueCycle = OptionalLong.empty();
    }
    if (dueDay == null) {
      dueDay = OptionalLong.empty();
    }
    if (dueCycle.isPresent() && dueCycle.getAsLong() < 0L) {
      throw new IllegalArgumentException("DebtTerms.dueCycle 不得为负: " + dueCycle.getAsLong());
    }
    if (dueDay.isPresent() && dueDay.getAsLong() < 0L) {
      throw new IllegalArgumentException("DebtTerms.dueDay 不得为负: " + dueDay.getAsLong());
    }
  }

  /**
   * ★★ <b>默认 legacy terms</b>：逐维复现旧粮债行为（见类注）。利率由调用方给（旧档可能不是 20‰； 不同利率 ⇒ 不同 terms ⇒ 不同 id，不静默合并）。
   */
  public static DebtTerms legacyDefault(int interestRatePerMillePerCycle) {
    return new DebtTerms(
        interestRatePerMillePerCycle,
        InterestTiming.AFTER_REPAYMENT_ON_CLOSE,
        RepaymentRule.AVAILABLE_SURPLUS_SHARE,
        MonetaryConversion.NOT_ALLOWED,
        DefaultRemedy.MARK_DEFAULTED,
        OptionalLong.empty(),
        OptionalLong.empty());
  }

  /** ★ 旧生产路径的默认 terms（利率 = {@link #LEGACY_INTEREST_RATE_PER_MILLE_PER_CYCLE}）。 */
  public static DebtTerms legacyDefault() {
    return legacyDefault(LEGACY_INTEREST_RATE_PER_MILLE_PER_CYCLE);
  }

  /**
   * ★★ <b>可读规范串</b>：只含数字与枚举名，各段以 {@code "|"} 分隔且段内无 {@code "|"} ⇒ 同一 terms 恒同串、 不同 terms
   * 必不同串。它用于读口/审计，不直接进 id（{@link #identityToken()} 负责把字符集收窄成无点形式）。
   */
  public String stableKey() {
    return "debt-terms-v1"
        + "|rate="
        + interestRatePerMillePerCycle
        + "|timing="
        + interestTiming.name()
        + "|repay="
        + repaymentRule.name()
        + "|convert="
        + monetaryConversion.name()
        + "|remedy="
        + defaultRemedy.name()
        + "|dueCycle="
        + optionalLongToken(dueCycle)
        + "|dueDay="
        + optionalLongToken(dueDay);
  }

  /**
   * ★★ <b>身份 token</b>：{@link #stableKey()} 的十六进制转义。它是 {@code DebtContractId.idOf} 的唯一输入， 故“terms
   * 维不同 ⇒ 合同 id 不同”由编码的**单射性**结构性保证（十六进制串不含 {@code "."}/{@code "|"}）。
   */
  public String identityToken() {
    return HexFormat.of().formatHex(stableKey().getBytes(StandardCharsets.UTF_8));
  }

  private static String optionalLongToken(OptionalLong value) {
    return value.isPresent() ? Long.toString(value.getAsLong()) : "none";
  }
}

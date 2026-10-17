package io.mosire.simos.economy.model;

/**
 * ★★ <b>工资报价来源</b>（D-032 D3 §2.2）。
 *
 * <p>它只记录报价从哪条合法写面来，<b>不是</b>运行时兜底开关：
 *
 * <ul>
 *   <li>{@link #SEED}：{@code economy.Seed.wagePostings}；
 *   <li>{@link #EXPLICIT}：{@code economy.SetProductionWagePosting}；
 *   <li>{@link #GENERATED}：仅 C3 从旧 {@code FIXED_MONEY_WAGE} 显式换算；
 *   <li>{@link #DEFAULT_MATERIALIZED}：选中候选时由 {@link ProductionWageDefault} 物化。
 * </ul>
 *
 * <p>运行时不得静默使用 default 或裸 0 兜底。线格式使用小写下划线字面量；读侧兼容枚举名/大小写， 但写侧只输出 {@link #value()}。
 */
public enum WageQuoteSource {
  SEED("seed"),
  EXPLICIT("explicit"),
  GENERATED("generated"),
  DEFAULT_MATERIALIZED("default_materialized");

  private final String value;

  WageQuoteSource(String value) {
    this.value = value;
  }

  /** 稳定线格式/payload 字面量（小写下划线）。 */
  public String value() {
    return value;
  }

  /** 解析 payload 字面量；枚举名与规范字面量都接受，未知值 fail-closed。 */
  public static WageQuoteSource parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("WageQuoteSource 不得为空白: " + text);
    }
    for (WageQuoteSource source : values()) {
      if (source.value.equals(text) || source.name().equalsIgnoreCase(text)) {
        return source;
      }
    }
    throw new IllegalArgumentException("未知的 WageQuoteSource: " + text);
  }

  @Override
  public String toString() {
    return value;
  }
}

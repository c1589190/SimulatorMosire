package io.mosire.simos.economy.api.money;

/**
 * ★★ <b>货币工具的三档词表</b>（M1.1）：这张"钱"是<b>哪一种</b>东西 —— 而这决定了它的价值<b>对谁</b>成立。
 *
 * <pre>
 * SPECIE        金属币  价值来自金属本身（成色/重量）      ⇒ 没有发行人（issuer 必须为空）
 * STATE_NOTE    国币    价值来自国家的兑付承诺            ⇒ 必须有发行人（国库/政府）
 * BANK_DEPOSIT  银行存款 价值来自钱庄的兑付承诺            ⇒ 必须有发行人（钱庄/银行）
 * </pre>
 *
 * <p>★★ <b>"要不要发行人"是 {@link #requiresIssuer()} 一处拼写</b>（不是散在构造器里的 if）： 该方法是 {@code switch}
 * <b>表达式</b>且 <b>没有 {@code default}</b> ⇒ 将来新增一档（如 {@code TOKEN}）<b>编译就过不去</b>，必须先回答"它要不要发行人" ——
 * 这正是"加一档词表"该有的代价（本仓对"静默多一档"的处置：宁编译不过，不宁默认放行）。
 *
 * <p>★ <b>线格式</b>：枚举走 Jackson 默认的 {@code name()}（同 {@code ActorKind}）⇒ 落盘字面量就是上面的三个名字， 改名字 =
 * 改线格式（{@code MoneyIdentityTest#instrumentKindHasExactlyThreeKindsAndOneIssuerRuleEach} 钉住三档的
 * {@code name()} 字面量）。
 *
 * <p>★ <b>本批不做</b>：接受规则（谁收哪种工具）、兑现、成色、铸熔（M4+）。
 */
public enum InstrumentKind {

  /** 金属币：价值来自金属本身（成色/重量），**没有**发行人。 */
  SPECIE("金属币"),

  /** 国币：价值来自国家的兑付承诺，**必须有**发行人。 */
  STATE_NOTE("国币"),

  /** 银行存款：价值来自钱庄的兑付承诺，**必须有**发行人。 */
  BANK_DEPOSIT("银行存款");

  private final String label;

  InstrumentKind(String label) {
    this.label = label;
  }

  /** 中文标签（只服务读口与报错信息；**不是**线格式 —— 线格式是 {@link #name()}）。 */
  public String label() {
    return label;
  }

  /**
   * ★★ 这一档工具**必须有发行人**吗（{@code true} ⇒ {@link MoneyInstrument#issuer()} 不得为空；{@code false} ⇒ 必须为空）。
   *
   * <p>★ <b>语义</b>：发行人 = "这张工具的价值是<b>对谁</b>的索取"。金属币的价值来自金属本身 ⇒ 没有这样一个对象； 国币与存款的价值<b>就是</b>对发行人的索取 ⇒
   * 少了发行人，这张工具在账上读不出"谁欠我"。
   */
  public boolean requiresIssuer() {
    return switch (this) {
      case SPECIE -> false;
      case STATE_NOTE, BANK_DEPOSIT -> true;
    };
  }
}

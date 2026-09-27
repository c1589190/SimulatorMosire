package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>货币工具 ID</b>（M1.1）：一张**具体的钱**的稳定身份 —— "银币"、"某某国库发行的宝钞"、"某某钱庄的存款"各是一个工具。
 *
 * <p>★★ <b>它与 {@link CurrencyId} 是两个命名空间</b>（M1.1 的立身之本）：
 *
 * <ul>
 *   <li>{@link CurrencyId} = <b>币种</b>（计价单位："这是银"）—— 它是 {@code GoodsAccount.money} 的键、{@code
 *       Transfer.money} 的键、{@code Market.numeraire} 的类型，本批**一字不动**（裁定"旧的 {@code CurrencyId} 读口保留"）；
 *   <li>{@code InstrumentId} = <b>工具</b>（"这是<b>哪一种</b>银"：金属币 / 国币 / 银行存款）—— 本批**只加身份**。
 * </ul>
 *
 * ★ <b>为什么必须分开</b>：M1.1 之前，"1000 毫银"说不清是银币、银票还是一笔存款 —— 而三者的**发行人与兑付承诺完全不同**
 * （金属币没有发行人；国币与存款是"对某人的索取"）。★ 守恒也因此是两级：币种总量恒定**不等于**逐工具恒定（逐工具守恒是 M1.6 的事，本批不做）。
 *
 * <p>★ <b>它只是身份</b>：不带面额、不带成色、不带发行量、不带汇率 —— 铸熔、成色与兑现属 M4+。 ★ <b>形制与 {@link CurrencyId} / {@link
 * CommodityId} 逐字相同</b>（同族的稳定 ID：单参构造 + {@code toString()} 裸值 + {@code parse} 逆 + 空白即抛）——
 * 同一族的东西不许有两套写法。
 */
public record InstrumentId(String value) {

  public InstrumentId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("InstrumentId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static InstrumentId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("InstrumentId 不得为空白: " + text);
    }
    return new InstrumentId(text);
  }
}

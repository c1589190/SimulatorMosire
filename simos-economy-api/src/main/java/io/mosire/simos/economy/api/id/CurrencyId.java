package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>货币 ID</b>（H2；用户 2026-09-27 裁定「货币作为接口留好」）：钱也像商品一样是<b>有身份的东西</b> —— "1000
 * 毫钱"今天没说是什么钱，补上这一维之后才是"1000 毫<b>银</b>"。
 *
 * <p>★★ <b>本批只有类型、不产生任何货币数量</b>（I5.3「货币档只定义、不结算」不变）：
 *
 * <ul>
 *   <li>{@code CompensationRule.currency} 用它说清"货币档发的是哪种钱"（实物档恒空、货币档必须有值）；
 *   <li>{@code Transfer.money} 的键是它（<b>本批恒空 map</b>）—— 第 1 批转移原语的货币腿是"形状在位、数量为 0"。
 * </ul>
 *
 * <p>★ <b>形制与 {@link CommodityId} 逐字相同</b>（同族的稳定 ID：单参构造 + {@code toString()} 裸值 + {@code parse} 逆
 * + 空白即抛）—— 同一族的东西不许有两套写法。★ <b>数量用最小计量单位</b>（毫），本类型只是身份，不带计量单位、 不带价格、也不含任何汇率（汇率属市场/货币层，H4）。★
 * 与商品是<b>两个命名空间</b>：{@code silver} 是钱、{@code grain} 是货，"两种东西"不许在类型上塌成一个。
 */
public record CurrencyId(String value) {

  public CurrencyId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("CurrencyId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static CurrencyId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("CurrencyId 不得为空白: " + text);
    }
    return new CurrencyId(text);
  }
}

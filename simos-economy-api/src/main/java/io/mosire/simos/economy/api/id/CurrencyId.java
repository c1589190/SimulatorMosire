package io.mosire.simos.economy.api.id;

/**
 * ★★ <b>货币 ID</b>（H2；用户 2026-09-27 裁定「货币作为接口留好」）：钱也像商品一样是<b>有身份的东西</b> —— "1000
 * 毫钱"今天没说是什么钱，补上这一维之后才是"1000 毫<b>银</b>"。
 *
 * <p>★★ <b>H4 起货币真的有数量</b>（I5.3 的「只定义、不结算」到此结束）：
 *
 * <ul>
 *   <li>{@code CompensationRule.currency} 用它说清"货币档发的是哪种钱"（实物档恒空、货币档必须有值）；
 *   <li>{@code Transfer.money} 的键是它（<b>H4 起真的有钱</b>：货币工资/地租是一条**只带货币腿**的转移，市场成交是**一对** 转移 —— 见
 *       {@code Transfer} 的货币腿口径）；
 *   <li>{@code HouseholdInventory.money} 的键也是它（钱与货<b>同住一本账</b>，裁定 M2：两个独立身份、两张余额表）。
 * </ul>
 *
 * <p>★ <b>它仍然只是"身份"</b>（没有发行）：本批没有任何 {@code MoneyAuthority} 实现 ⇒ 世界上没有"铸币/回笼"这条路径， 任何账户的货币余额不得为负（透支
 * = 发行 ⇒ 当场抛）。
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

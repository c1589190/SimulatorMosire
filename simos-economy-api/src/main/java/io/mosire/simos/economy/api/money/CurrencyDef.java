package io.mosire.simos.economy.api.money;

import io.mosire.simos.economy.api.id.CurrencyId;

/**
 * ★★ <b>币种定义</b>（M1.1；A1 加显示名）：一个**计价单位**（{@link #id()}）、它的<b>最小单位精度</b>（{@link
 * #scale()}）与它的<b>显示名</b>（{@link #displayName()}）。
 *
 * <pre>
 * new CurrencyDef("silver", 3, "银")   // 银：最小单位 = 毫银 ⇒ 1 银 = 1000 毫银
 * new CurrencyDef("cash", 0, "现钱")   // 反例：最小单位就是 1 个币种单位（没有小数位）
 * new CurrencyDef("cash", 0)           // 旧形状（显示名 = id）：只给"此刻还说不出显示名"的旧调用点
 * </pre>
 *
 * <p>★★ <b>A1（2026-10-08，约束设计书 §3.1-1）：{@code id} 与 {@code displayName} 是两件事</b>——
 *
 * <ul>
 *   <li>{@link #id()} 是<b>不可变身份</b>：余额/流水/债务/市场/订单的键（{@code CurrencyId}）都由它派生 ⇒ <b>改 id =
 *       换身份</b>（违铁律 1）。改名（{@code gov.renameCurrency} / {@code economy.RenameCurrency}）<b>只改</b>
 *       {@link #displayName()}，不动任何账、不产生新身份（不变量 I16 / 判据 F1）；范式对齐 {@code Region.id} + {@code
 *       Region.name} + {@code map.RenameRegion}；
 *   <li>{@link #displayName()} 是<b>给人看的名字</b>：只守"非空白"一条，<b>不要求唯一</b>（两个币种同显名合法 —— 身份仍靠 id
 *       分得开）；它<b>不是</b>任何键、不进线格式的键位，改它不动任何引用。
 * </ul>
 *
 * <p>★ <b>为什么旧形状构造器（{@code (id, scale)}）还在</b>：既有调用点（旧夹具、旧档重建路径）此刻<b>说不出</b>显示名，
 * 让它们当场编译不过等于把"补一个显示名"变成全仓串改。旧形状的语义 = <b>显示名与 id 同字</b>（非空、可读）， 而真正的世界词表（{@code
 * EconomyData.currencies} 与 {@code MoneyVocabulary}）一律显式给名。
 *
 * <p>★★ <b>{@code scale} 的语义</b>：{@code 1 个币种单位 = 10^scale 个最小单位}（{@code scale} = 小数位数）。 全仓的货币余额一律是
 * <b>最小单位的定点整数</b>（"毫"；见 {@code HouseholdInventory.money} 的类注）⇒ 读口与算式都要知道"1 银是 1000 还是 100"， 这个数在
 * M1.1 之前是**隐含**在每个人脑子里的（本仓最反对的"没人写下来的一处真相"）。 ★ 本类**不给** {@code milliPerUnit()} 之类的折算函数：折算 = 10
 * 的幂，写出来就要处理 {@code scale} 过大时的溢出 —— 而 M1.1 没有任何调用方需要它 （真要折算时，落点是 V7 的参数化口径，不是这里多一个没人读的方法）。
 *
 * <p>★★ <b>为什么 {@code id} 是 {@code String} 而不是 {@link
 * io.mosire.simos.economy.api.id.CurrencyId}</b>： 本类型是<b>词表条目</b>（从世界状态/载荷读进来的数据），而 {@code
 * CurrencyId} 是<b>运行时身份</b>（余额表的键）。 两者的桥是 {@link #currencyId()} —— <b>唯一转换点</b>；词表里 {@code
 * CurrencyDef.id()} 与 {@code CurrencyId.value()} 逐字相同这件事由 {@code
 * MoneyIdentityTest#theOldCurrencyIdReadPathIsKept} 钉住（不是靠"两边都记得写一样"）。
 *
 * <p>★ <b>本批不做</b>：汇率（币种之间不许求和、不许折算，裁定 M1-A）、成色、铸熔、兑现（M4+）。
 *
 * @param id 币种的稳定 id（非空白；与 {@code CurrencyId.value()} 同一命名空间；A1 起禁止改）
 * @param scale 最小单位精度（小数位数；{@code ≥ 0}，{@code 0} = 没有小数位）
 * @param displayName 显示名（非空白；不要求唯一；改名只改它，不动 id 与任何账）
 */
public record CurrencyDef(String id, int scale, String displayName) {

  /**
   * 旧形状构造器（显示名 = id）：只给"此刻还说不出显示名"的既有调用点。
   *
   * <p>★ 它的存在是<b>兼容</b>，不是"显示名可有可无"：世界词表（{@code MoneyVocabulary} / {@code
   * EconomyData.currencies}）一律显式给名，读到"显示名 == id"的条目说明那一处还没补名。
   */
  public CurrencyDef(String id, int scale) {
    this(id, scale, id);
  }

  public CurrencyDef {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("CurrencyDef.id 不得为空白");
    }
    if (scale < 0) {
      throw new IllegalArgumentException(
          "CurrencyDef.scale 是最小单位精度、不得为负（0 = 没有小数位）: " + id + " scale=" + scale);
    }
    if (displayName == null || displayName.isBlank()) {
      throw new IllegalArgumentException(
          "CurrencyDef.displayName 不得为空白（身份是 id，名字是给人看的；说不出名字就给 id 同字）: " + id);
    }
  }

  /** 本币种的运行时身份（{@code String} 词表条目 → {@code CurrencyId} 的**唯一**转换点）。 */
  public CurrencyId currencyId() {
    return new CurrencyId(id);
  }

  /**
   * ★★ <b>改名</b>（A1）：只换显示名，{@link #id()} / {@link #scale()} 逐字不变 —— 这是"改名不动账"（I16）在类型上的
   * 唯一形态：调用方拿不到"顺手改 id"的口子。
   */
  public CurrencyDef withDisplayName(String newDisplayName) {
    return new CurrencyDef(id, scale, newDisplayName);
  }
}

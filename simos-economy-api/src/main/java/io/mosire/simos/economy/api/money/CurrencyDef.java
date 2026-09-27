package io.mosire.simos.economy.api.money;

import io.mosire.simos.economy.api.id.CurrencyId;

/**
 * ★★ <b>币种定义</b>（M1.1）：一个**计价单位**（{@link #id()}）与它的<b>最小单位精度</b>（{@link #scale()}）。
 *
 * <pre>
 * new CurrencyDef("silver", 3)   // 银：最小单位 = 毫银 ⇒ 1 银 = 1000 毫银
 * new CurrencyDef("cash", 0)     // 反例：最小单位就是 1 个币种单位（没有小数位）
 * </pre>
 *
 * <p>★★ <b>{@code scale} 的语义</b>：{@code 1 个币种单位 = 10^scale 个最小单位}（{@code scale} = 小数位数）。 全仓的货币余额一律是
 * <b>最小单位的定点整数</b>（"毫"；见 {@code GoodsAccount.money} 的类注）⇒ 读口与算式都要知道"1 银是 1000 还是 100"， 这个数在 M1.1
 * 之前是**隐含**在每个人脑子里的（本仓最反对的"没人写下来的一处真相"）。 ★ 本类**不给** {@code milliPerUnit()} 之类的折算函数：折算 = 10
 * 的幂，写出来就要处理 {@code scale} 过大时的溢出 —— 而 M1.1 没有任何调用方需要它 （真要折算时，落点是 V7 的参数化口径，不是这里多一个没人读的方法）。
 *
 * <p>★★ <b>为什么 {@code id} 是 {@code String} 而不是 {@link
 * io.mosire.simos.economy.api.id.CurrencyId}</b>： 本类型是<b>词表条目</b>（V7 起从 {@code worldParams}
 * 读进来的数据），而 {@code CurrencyId} 是<b>运行时身份</b>（余额表的键）。 两者的桥是 {@link #currencyId()} ——
 * <b>唯一转换点</b>；词表里 {@code CurrencyDef.id()} 与 {@code CurrencyId.value()} 逐字相同这件事由 {@code
 * MoneyIdentityTest#theOldCurrencyIdReadPathIsKept} 钉住（不是靠"两边都记得写一样"）。
 *
 * <p>★ <b>本批不做</b>：汇率（币种之间不许求和、不许折算，裁定 M1-A）、成色、铸熔、兑现（M4+）。
 *
 * @param id 币种的稳定 id（非空白；与 {@code CurrencyId.value()} 同一命名空间）
 * @param scale 最小单位精度（小数位数；{@code ≥ 0}，{@code 0} = 没有小数位）
 */
public record CurrencyDef(String id, int scale) {

  public CurrencyDef {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("CurrencyDef.id 不得为空白");
    }
    if (scale < 0) {
      throw new IllegalArgumentException(
          "CurrencyDef.scale 是最小单位精度、不得为负（0 = 没有小数位）: " + id + " scale=" + scale);
    }
  }

  /** 本币种的运行时身份（{@code String} 词表条目 → {@code CurrencyId} 的**唯一**转换点）。 */
  public CurrencyId currencyId() {
    return new CurrencyId(id);
  }
}

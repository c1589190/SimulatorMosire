package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Objects;

/**
 * ★★ <b>D2/D3 工资报价的最小只读值</b>（D-032 D2 §6.5）。
 *
 * <p>★ <b>C2 不持久化工资报价</b>：真实报价状态（{@code ProductionWageDefault} / {@code ProductionWagePosting}）归
 * D3/W2a。本类型只是让 C2 的执行/预计路径有一个稳定的读值可引用。
 *
 * <p>★★ <b>量纲</b>：{@code wagePerLaborMilli} = <b>毫货币 / 1000 毫小时（= 毫货币/小时）</b>，所以 {@code 工资 =
 * wagePerLaborMilli × laborMilli / 1000}。{@code 0} 是<b>合法报价</b>（U18：地主可报 0/很低）； "缺报价"用 {@link
 * java.util.Optional#empty()} 表达，<b>不得</b>用 0 冒充。
 *
 * @param currency 计价币种；不得为 null
 * @param wagePerLaborMilli 每 1000 毫小时劳动的毫货币；必须 ≥ 0
 */
public record ProductionWageQuote(CurrencyId currency, long wagePerLaborMilli) {

  public ProductionWageQuote {
    Objects.requireNonNull(currency, "ProductionWageQuote.currency 不得为 null");
    if (wagePerLaborMilli < 0L) {
      throw new IllegalArgumentException(
          "ProductionWageQuote.wagePerLaborMilli 不得为负（0 是合法报价）: " + wagePerLaborMilli);
    }
  }
}

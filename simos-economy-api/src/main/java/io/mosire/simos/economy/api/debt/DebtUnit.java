package io.mosire.simos.economy.api.debt;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Objects;

/**
 * ★★ <b>债务的计量标的（显式 unit）</b>（E4a；理想架构 §2.7）：一笔债要么是**实物债**（粮/其他商品， 按 {@link
 * CommodityId}），要么是**货币债**（按 {@link CurrencyId}）—— <b>恰有其一</b>，由 sealed 类型表达。
 *
 * <p>★★ <b>为什么不是 {@code Optional<CommodityId>}</b>：旧 {@code Debt.commodity} 用“空 Optional”暗中表示
 * “货币债”，于是“标的是钱”这条事实**没有一处被写下来**，读口只能靠一个 absent 值反推，将来出现第二种货币 或“既非粮也非银”的计量单位时无从表达。本类型把两个变体都做成
 * record：{@link Commodity} 与 {@link Money}， {@code switch} 必须写全两支，编译器替你把“漏了一支”变成编译错。
 *
 * <p>★ {@link #key()} 是**参与 {@code DebtContractId} 派生**的稳定串（前缀区分两类，避免粮 {@code grain} 与币 {@code
 * grain} 撞车）。它允许包含 {@code ":"} 等字符；{@code DebtContractId.idOf} 会先做十六进制 转义，故最终 id 不含 {@code "."}（地址
 * {@code economy:<mapId>:debt.<id>} 的接缝约定）。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = DebtUnit.Commodity.class, name = "commodity"),
  @JsonSubTypes.Type(value = DebtUnit.Money.class, name = "money"),
})
public sealed interface DebtUnit permits DebtUnit.Commodity, DebtUnit.Money {

  /** 参与合同身份派生的稳定键（唯一拼写点；两类前缀不同，故不会互相撞车）。 */
  String key();

  /** 实物债单位：粮食/其他商品。 */
  record Commodity(CommodityId commodity) implements DebtUnit {

    public Commodity {
      Objects.requireNonNull(commodity, "DebtUnit.Commodity.commodity 不得为 null");
    }

    @Override
    public String key() {
      return "commodity:" + commodity.value();
    }
  }

  /** 货币债单位：某个币种（最小币值）。 */
  record Money(CurrencyId currency) implements DebtUnit {

    public Money {
      Objects.requireNonNull(currency, "DebtUnit.Money.currency 不得为 null");
    }

    @Override
    public String key() {
      return "money:" + currency.value();
    }
  }

  /** 实物债工厂（阅读点比 new 更显式；构造期守卫仍由 record 承担）。 */
  static DebtUnit commodity(CommodityId commodity) {
    return new Commodity(commodity);
  }

  /** 货币债工厂。 */
  static DebtUnit money(CurrencyId currency) {
    return new Money(currency);
  }
}

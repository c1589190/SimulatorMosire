package io.mosire.simos.economy.api.debt;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mosire.simos.economy.api.id.CurrencyId;
import java.util.Objects;

/**
 * ★★ <b>债务本金的计量标的（显式 unit，P2c 起货币唯一）</b>：一笔债的本金只能是某个 {@link CurrencyId}
 * 的最小币值单位。商品库存不是债务标的，只是<b>还债介质</b>；商品欠款在形成时先按活动所在地市场区的价格折成本区计价货币，
 * 再落成普通货币债（见 {@code DebtSettlement.capitalizeArrears}）。
 *
 * <p>★ 本类型保留 sealed 形态而不是直接收成一个 record：合同身份、Codec、payload 与只读视图都以 {@link #key()}
 * 为稳定单位键；保留一层接口可以让“是否还有第二种计量标的”在类型系统里保持显式。P2c 的答案是没有——商品变体已删除。
 *
 * <p>★ {@link Money#key()} 形如 {@code money:silver}，是参与 {@code DebtContractId} 派生的稳定串。它允许包含
 * {@code ":"} 等字符；{@code DebtContractId.idOf} 会先做 UTF-8 十六进制转义，故最终 id 不含 {@code "."}。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({@JsonSubTypes.Type(value = DebtUnit.Money.class, name = "money")})
public sealed interface DebtUnit permits DebtUnit.Money {

  /** 参与合同身份派生的稳定键（唯一拼写点）。 */
  String key();

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

  /** 货币债工厂（构造期守卫仍由 record 承担）。 */
  static DebtUnit money(CurrencyId currency) {
    return new Money(currency);
  }
}

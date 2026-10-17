package io.mosire.simos.production.contract;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;

/**
 * ★★ <b>角色支付形态</b>（D-032 D1 §2.4）。
 *
 * <p>{@link ProductShare} 从可分配实物池按角色 {@code sharePerMille} 取得；{@link MoneyWage}
 * 是雇主对工资劳动的支付义务。工资报价/匹配/结算在 economy/D3，production 只定义形态与抵物折价策略。
 *
 * <p>★★ 交叉校验在 {@link ProductionRoleDefinition}：{@code PRODUCT_SHARE ⇒ sharePerMille > 0}， {@code
 * MONEY_WAGE ⇒ sharePerMille == 0}。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = RolePayment.ProductShare.class, name = "product_share"),
  @JsonSubTypes.Type(value = RolePayment.MoneyWage.class, name = "money_wage"),
})
public sealed interface RolePayment {

  /** 直接产品份额：从可分配实物池按角色份额取得。 */
  record ProductShare() implements RolePayment {}

  /**
   * 货币工资：币种固定为当地市场区货币（{@code LOCAL_MARKET_AREA}），由 economy/D3 在本地市场区 报价与结算；定义面只保存抵物折价策略。production
   * 不读市场/钱包。
   *
   * @param barterDiscount 抵物折价策略；不得为 null
   */
  record MoneyWage(BarterDiscountPolicy barterDiscount) implements RolePayment {

    public MoneyWage {
      Objects.requireNonNull(barterDiscount, "RolePayment.MoneyWage.barterDiscount 不得为 null");
    }
  }
}

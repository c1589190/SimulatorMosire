package io.mosire.simos.economy.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import java.util.Objects;

/**
 * ★★★ <b>候选级工资默认的稳定复合键</b>（D-032 D3 §2.2.1）。
 *
 * <p>键 = {@code (plan, role, marketZone)}。同一个键至多一条 {@link ProductionWageDefault}；
 * 它只服务实例存在前的候选估价与选中物化， <b>不是</b>执行期报价权威（执行期唯一权威仍是 {@link ProductionWagePosting}）。
 *
 * <p>★ 线格式采用长度前缀（{@code <plan 字符数>:<plan><role 字符数>:<role><marketZone>}），不用 {@code |} 裸拼：{@link
 * IndustryId} 与 {@link ProductionRoleId} 的合法值可以含 {@code |} / {@code :}，裸分隔符无法与 {@link
 * #parse(String)} 严格互逆。最后一段 marketZone 取剩余全部字符。
 *
 * @param plan 方案稳定身份；不得为 null
 * @param role 方案内工资角色稳定身份；不得为 null
 * @param marketZone 市场区稳定身份；不得为 null
 */
public record WageDefaultKey(IndustryId plan, ProductionRoleId role, MarketZoneId marketZone) {

  public WageDefaultKey {
    Objects.requireNonNull(plan, "WageDefaultKey.plan 不得为 null");
    Objects.requireNonNull(role, "WageDefaultKey.role 不得为 null");
    Objects.requireNonNull(marketZone, "WageDefaultKey.marketZone 不得为 null");
  }

  /** 规范串：{@code <plan.length()>:<plan><role.length()>:<role><marketZone>}（与 {@link #parse} 互为逆）。 */
  @Override
  @JsonValue
  public String toString() {
    return WageKeyWireFormat.encode(plan.value(), role.value(), marketZone.value());
  }

  /**
   * 解析 {@link #toString()} 的产物。
   *
   * @throws IllegalArgumentException 文本为空、长度前缀缺失/非整数/非正、长度越界或任一段为空
   */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static WageDefaultKey parse(String text) {
    String[] parts = WageKeyWireFormat.decode("WageDefaultKey", text, "plan", "role", "marketZone");
    return new WageDefaultKey(
        IndustryId.parse(parts[0]), ProductionRoleId.parse(parts[1]), MarketZoneId.parse(parts[2]));
  }
}

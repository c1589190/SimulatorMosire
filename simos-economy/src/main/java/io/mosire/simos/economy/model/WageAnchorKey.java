package io.mosire.simos.economy.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import java.util.Objects;

/**
 * ★★★ <b>U20 工资锚的稳定复合键</b>（D-032 P4 §6.1）：{@code (marketZone, industry, role)}。
 *
 * <p>同一个锚在同一个市场区内跨 unit / 雇主共享；不同市场区的同名产业/角色各有自己的锚。键是 {@link
 * io.mosire.simos.economy.EconomyData#productionWageAnchors()} 的唯一身份，值内不另存三元组 （P4 冻结形状）。
 *
 * <p>★ <b>线格式</b>：复用 {@link WageKeyWireFormat} 的长度前缀编码 （{@code <marketZone
 * 字符数>:<marketZone><industry 字符数>:<industry><role>}）。{@link MarketZoneId} / {@link IndustryId} /
 * {@link ProductionRoleId} 的合法值可以含 {@code ':'} / {@code '|'} 等分隔符， 裸拼无法与 {@link #parse(String)}
 * 严格互逆；长度前缀使三段边界只由长度决定。最后一段 role 吃掉剩余全部字符。
 *
 * @param marketZone 市场区稳定身份；不得为 null
 * @param industry 产业/方案稳定身份；不得为 null
 * @param role 方案内生产角色稳定身份；不得为 null
 */
public record WageAnchorKey(MarketZoneId marketZone, IndustryId industry, ProductionRoleId role) {

  public WageAnchorKey {
    Objects.requireNonNull(marketZone, "WageAnchorKey.marketZone 不得为 null");
    Objects.requireNonNull(industry, "WageAnchorKey.industry 不得为 null");
    Objects.requireNonNull(role, "WageAnchorKey.role 不得为 null");
  }

  /** 规范串（长度前缀；与 {@link #parse(String)} 互为逆）。 */
  @Override
  @JsonValue
  public String toString() {
    return WageKeyWireFormat.encode(marketZone.value(), industry.value(), role.value());
  }

  /**
   * 解析 {@link #toString()} 的产物。
   *
   * @throws IllegalArgumentException 文本为空、长度前缀缺失/非整数/非正/前导零、长度越界或任一段为空
   */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public static WageAnchorKey parse(String text) {
    String[] parts =
        WageKeyWireFormat.decode("WageAnchorKey", text, "marketZone", "industry", "role");
    return new WageAnchorKey(
        MarketZoneId.parse(parts[0]), IndustryId.parse(parts[1]), ProductionRoleId.parse(parts[2]));
  }
}

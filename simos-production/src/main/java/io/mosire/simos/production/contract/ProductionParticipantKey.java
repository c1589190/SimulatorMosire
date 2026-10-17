package io.mosire.simos.production.contract;

import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★ <b>「家户 × 生产角色」复合键</b>（D-032 D2 §6.1/§6.3）。
 *
 * <p>它用于本周期贡献台账、本 tick 劳动回执与分配结果。家户身份仍是 {@link HouseholdId}，角色身份仍是 {@link
 * ProductionRoleId}；本类型只是两者的有序组合，不替代任一稳定 ID。
 *
 * <p>★ 规范串采用<b>长度前缀</b>（{@code <household 字符数>:<household><role>}）而不是分隔符：家户与角色 ID
 * 的拼写约束没有共同禁用字符，长度前缀才能与 {@link #parse(String)} 严格互逆，不会把含分隔符的合法 ID 读错。
 *
 * @param household 家户稳定身份；不得为 null
 * @param role 角色稳定身份；不得为 null
 */
public record ProductionParticipantKey(HouseholdId household, ProductionRoleId role) {

  public ProductionParticipantKey {
    Objects.requireNonNull(household, "ProductionParticipantKey.household 不得为 null");
    Objects.requireNonNull(role, "ProductionParticipantKey.role 不得为 null");
  }

  /** 规范串：{@code <household.length()>:<household><role>}（与 {@link #parse} 互为逆）。 */
  @Override
  public String toString() {
    String householdValue = household.value();
    return householdValue.length() + ":" + householdValue + role.value();
  }

  /**
   * 解析 {@link #toString()} 的产物。
   *
   * @throws IllegalArgumentException 文本为空、前缀非法、长度越界或角色段为空
   */
  public static ProductionParticipantKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ProductionParticipantKey 的规范串不得为空白");
    }
    int colon = text.indexOf(':');
    if (colon <= 0 || colon == text.length() - 1) {
      throw new IllegalArgumentException(
          "ProductionParticipantKey 的规范串必须是 <household.length()>:<household><role>: " + text);
    }
    int householdLength;
    try {
      householdLength = Integer.parseInt(text.substring(0, colon));
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException("ProductionParticipantKey 的家户长度前缀不是整数: " + text, ex);
    }
    int start = colon + 1;
    int end = start + householdLength;
    if (householdLength <= 0 || end >= text.length()) {
      throw new IllegalArgumentException("ProductionParticipantKey 的家户长度前缀越界（必须有非空角色段）: " + text);
    }
    return new ProductionParticipantKey(
        HouseholdId.parse(text.substring(start, end)), ProductionRoleId.parse(text.substring(end)));
  }
}

package io.mosire.simos.culture.contract;

import io.mosire.simos.social.api.id.HouseholdId;

/**
 * ★★ <b>家户 → 文化标签的挂接</b>（D-031 spec §6.2；`POLITICAL_ECONOMY_DESIGN.md:387` 已定 {@code HouseholdId}
 * 是文化扩展点）。
 *
 * <p>它是本模块状态里"家户文化标签表"的值类型：键 = {@link #household()}（跨表不变量，见 {@code CultureData}）。★ 家户稳定身份来自 {@code
 * simos-social-api}；文化是<b>只读挂接</b>，culture 不写人口、不碰 Social 状态。
 *
 * <p>★ 本轮真实文化效果<b>未启用</b>：本记录存在不等于任何生产/流动数值会变（spec §7 修正 A）。
 *
 * @param household 家户稳定身份；不得为 null
 * @param tag 该家户的文化标签；不得为 null
 */
public record HouseholdCulture(HouseholdId household, CultureTag tag) {

  public HouseholdCulture {
    if (household == null) {
      throw new IllegalArgumentException("HouseholdCulture.household 不得为 null");
    }
    if (tag == null) {
      throw new IllegalArgumentException("HouseholdCulture.tag 不得为 null");
    }
  }
}

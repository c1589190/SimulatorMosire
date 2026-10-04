package io.mosire.simos.social.api.population;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个家户的率表（2026-10-09 家户/人口架构 §4.3）：{@link HouseholdVitalRate} 的冻结列表。
 *
 * <p>不变量（构造期判）：
 *
 * <ul>
 *   <li>{@code rates} <b>冻结不可变</b>：入参为 {@code null} 视为空表；{@link List#copyOf} 保序并拒绝 null 元素；
 *   <li>同一 {@code (bracketId, sex)} 不得出现两次（重复键会让结算方读哪一条变成"实现时再定"）。
 * </ul>
 *
 * <p>★ 本类型零 Jackson 注解：线格式由持有它的 codec 负责（架构 §3.1）。
 *
 * @param rates 冻结的率表；不得含重复 {@code (bracketId, sex)} 键、不得含 null 元素
 */
public record HouseholdVitalRates(List<HouseholdVitalRate> rates) {

  public HouseholdVitalRates {
    rates = freeze(rates);
    Set<BracketSex> seen = new HashSet<>();
    for (HouseholdVitalRate rate : rates) {
      if (!seen.add(new BracketSex(rate.bracketId(), rate.sex()))) {
        throw new IllegalArgumentException(
            "HouseholdVitalRates 不得含重复的 (bracketId, sex): " + rate.bracketId() + "/" + rate.sex());
      }
    }
  }

  /**
   * 按 {@code (bracketId, sex)} 查率；缺失 ⇒ {@link java.util.Optional#empty()}（"这个档没有率"是合法状态：
   * 结算方对缺失率按 0 处理，不臆造默认值）。
   */
  public java.util.Optional<HouseholdVitalRate> find(String bracketId, Sex sex) {
    for (HouseholdVitalRate rate : rates) {
      if (rate.bracketId().equals(bracketId) && rate.sex() == sex) {
        return java.util.Optional.of(rate);
      }
    }
    return java.util.Optional.empty();
  }

  /** 防御性拷贝 + null 元素具名拒绝 + 保序冻结。 */
  private static List<HouseholdVitalRate> freeze(List<HouseholdVitalRate> source) {
    if (source == null) {
      return List.of();
    }
    List<HouseholdVitalRate> copy = new ArrayList<>(source.size());
    for (HouseholdVitalRate rate : source) {
      if (rate == null) {
        throw new IllegalArgumentException("HouseholdVitalRates.rates 不得含 null 元素");
      }
      copy.add(rate);
    }
    return List.copyOf(copy);
  }

  /** 去重键：{@code (bracketId, sex)}。 */
  private record BracketSex(String bracketId, Sex sex) {}
}

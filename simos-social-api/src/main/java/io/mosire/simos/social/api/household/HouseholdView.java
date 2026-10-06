package io.mosire.simos.social.api.household;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.AgeBracketView;
import java.util.ArrayList;
import java.util.List;

/**
 * 家户只读视图（2026-10-09 家户/人口架构 §3.1 / §5）：身份 + 位置 + 画像 + 成员批次 + 年龄档。
 *
 * <p>不变量（构造期判）：
 *
 * <ul>
 *   <li>{@code id} / {@code location} / {@code profile} 非 null；
 *   <li>{@code memberLots} / {@code ageBrackets} <b>冻结不可变</b>：入参为 {@code null} 视为空表， {@link
 *       List#copyOf} 保序并拒绝 null 元素。
 * </ul>
 *
 * <p>★ 本类型零 Jackson 注解：线格式由持有它的 codec 负责（架构 §3.1）。
 *
 * @param id 家户稳定身份；不得为 null
 * @param location 家户位置（HEX / UNIT）；不得为 null
 * @param profile 家户画像；不得为 null
 * @param memberLots 成员批次身份；冻结、不得含 null 元素
 * @param ageBrackets 年龄档视图；冻结、不得含 null 元素
 */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "compact constructor 已做防御性拷贝并冻结；SpotBugs 不跨辅助方法识别")
public record HouseholdView(
    HouseholdId id,
    HouseholdLocation location,
    HouseholdProfile profile,
    List<PeopleLotId> memberLots,
    List<AgeBracketView> ageBrackets) {

  public HouseholdView {
    if (id == null) {
      throw new IllegalArgumentException("HouseholdView.id 不得为 null");
    }
    if (location == null) {
      throw new IllegalArgumentException("HouseholdView.location 不得为 null");
    }
    if (profile == null) {
      throw new IllegalArgumentException("HouseholdView.profile 不得为 null");
    }
    memberLots = freeze(memberLots, "memberLots");
    ageBrackets = freeze(ageBrackets, "ageBrackets");
  }

  /** 防御性拷贝 + null 元素具名拒绝 + 保序冻结。 */
  private static <T> List<T> freeze(List<T> source, String field) {
    if (source == null) {
      return List.of();
    }
    List<T> copy = new ArrayList<>(source.size());
    for (T element : source) {
      if (element == null) {
        throw new IllegalArgumentException("HouseholdView." + field + " 不得含 null 元素");
      }
      copy.add(element);
    }
    return List.copyOf(copy);
  }
}

package io.mosire.simos.culture.contract;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * ★★ <b>家户的文化标签</b>（D-031 spec §6.2；修正③（控制方）显式补 {@code CultureId} 关联）。
 *
 * <p>它是"这个家户是什么地方、认可什么宗教/文化的什么人"这一非经济身份的三元组：文化身份 + 宗教身份 + 习俗词条集合。★ <b>生产熟练度规则按 {@link #culture()}
 * 索引</b>（spec §6.2），故 {@code CultureId} 是显式组件、不是从宗教或习俗推出来的。
 *
 * <p>★ <b>保序不可变</b>：{@code customs} 在构造期<b>深拷贝</b>进 {@link LinkedHashSet} 并冻结（{@code
 * Collections.unmodifiableSet}），**绝不用 {@code Set.copyOf}**——迭代序不是内容的纯函数。重复词条按集合语义折叠为一条。
 *
 * <p>★ 本轮<b>不</b>实现文化传播/同化：本类型只是可编辑、可回放的 typed 标签（spec §2 非目标）。
 *
 * @param culture 文化稳定身份；不得为 null
 * @param religion 宗教稳定身份；不得为 null（"无宗教"若要表达，应改字段为 {@code Optional} 而非放开 null）
 * @param customs 习俗词条；不得为 null（无习俗用空 set）；逐项非 null、保序不可变
 */
public record CultureTag(CultureId culture, ReligionId religion, Set<CustomTag> customs) {

  public CultureTag {
    if (culture == null) {
      throw new IllegalArgumentException("CultureTag.culture 不得为 null");
    }
    if (religion == null) {
      throw new IllegalArgumentException("CultureTag.religion 不得为 null");
    }
    if (customs == null) {
      throw new IllegalArgumentException("CultureTag.customs 不得为 null（无习俗用空 set）");
    }
    Set<CustomTag> copy = new LinkedHashSet<>();
    for (CustomTag tag : customs) {
      if (tag == null) {
        throw new IllegalArgumentException("CultureTag.customs 不得含 null");
      }
      copy.add(tag);
    }
    customs = Collections.unmodifiableSet(copy);
  }
}

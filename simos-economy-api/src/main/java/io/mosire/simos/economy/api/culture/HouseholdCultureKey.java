package io.mosire.simos.economy.api.culture;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * ★★ <b>家户文化属性的不透明只读 key</b>（D-032 v4 §9；RC1 共享契约）。
 *
 * <p>它让 economy 不 import {@code simos-culture} 也能读到“同文化属性”的判据：{@code (cultureId, religionId,
 * customs, label)}。值仍只由 culture 模块维护；economy 只消费本只读快照。
 *
 * <p>★★ <b>确定性</b>：{@code customs} 在构造期复制、按字符串升序排序、去重并冻结；{@code cultureId} / {@code religionId}
 * 原样保留；{@code label} 原样保留（不 trim）。<b>禁止 HashMap 迭代序</b>。
 *
 * @param cultureId 文化稳定身份的规范串；不得为 null
 * @param religionId 宗教稳定身份的规范串；不得为 null
 * @param customs 习俗词条；不得为 null，逐项非 null；构造后为升序去重不可变列表
 * @param label 自然语言标签；不得为 null（缺失由 culture 写入时落空串）
 */
public record HouseholdCultureKey(
    String cultureId, String religionId, List<String> customs, String label) {

  /**
   * ★ RC4-A（D-032 v4 §9）：没有 culture 切片 / 缺标签时的<b>显式空 key</b>。
   *
   * <p>它是占位值，不是新文化：四个字段均非 null，customs 为空、label 为空串。economy 在 lookup 返回 {@link
   * java.util.Optional#empty()} 时必须使用本工厂，<b>不得</b>用 SocialClassId、生产方式或库存猜一个 cultureId。
   */
  public static HouseholdCultureKey missing() {
    return new HouseholdCultureKey("", "", List.of(), "");
  }

  public HouseholdCultureKey {
    if (cultureId == null) {
      throw new IllegalArgumentException("HouseholdCultureKey.cultureId 不得为 null");
    }
    if (religionId == null) {
      throw new IllegalArgumentException("HouseholdCultureKey.religionId 不得为 null");
    }
    if (customs == null) {
      throw new IllegalArgumentException("HouseholdCultureKey.customs 不得为 null（无习俗用空表）");
    }
    if (label == null) {
      throw new IllegalArgumentException("HouseholdCultureKey.label 不得为 null（缺失用空串）");
    }
    List<String> copy = new ArrayList<>(customs.size());
    for (String custom : customs) {
      if (custom == null) {
        throw new IllegalArgumentException("HouseholdCultureKey.customs 不得含 null");
      }
      copy.add(custom);
    }
    Collections.sort(copy);
    // ★ 升序去重：LinkedHashSet 保留排序后的顺序；绝不用 HashMap/Set.copyOf。
    List<String> deduped = new ArrayList<>(new LinkedHashSet<>(copy));
    customs = Collections.unmodifiableList(deduped);
  }
}

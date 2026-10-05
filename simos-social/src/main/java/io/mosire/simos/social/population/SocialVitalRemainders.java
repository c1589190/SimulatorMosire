package io.mosire.simos.social.population;

import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>每 tick 生死余数累加器的状态组件</b>（2026-10-09 Social 每 tick 计划 §3.4）：{@link SocialVitalRemainder}
 * 的<b>保序冻结列表</b>。
 *
 * <p>不变量（构造期判、坏数据当场抛）：
 *
 * <ul>
 *   <li>{@code entries} 不得为 null；列表与元素冻结不可变（保序拷贝，不用 {@code List.copyOf} 之外的集合直通）；
 *   <li>键 {@code (householdId, lotId, kind)} 不得重复；
 *   <li>所有元素自己保证 {@code numerator ∈ [0, 999_999]}。
 * </ul>
 *
 * <p>★ <b>0 不落键</b>：没有余数就不进本表（{@link #withNumerator} 在 0 时删除条目）。旧档缺本组件按"旧档作废"
 * 口径具名拒，不做缺省兜底；新世界由 {@code SocialData.empty()} / 便捷构造器给空表。</p>
 *
 * @param entries 余数条目（保序、冻结、键唯一）；不得为 null
 */
public record SocialVitalRemainders(List<SocialVitalRemainder> entries) {

  public SocialVitalRemainders {
    if (entries == null) {
      throw new IllegalArgumentException(
          "SocialVitalRemainders.entries 不得为 null（旧档缺此组件已作废，不做缺省兜底；空表用 List.of()）");
    }
    List<SocialVitalRemainder> copy = new ArrayList<>(entries.size());
    Set<RemainderKey> seen = new LinkedHashSet<>();
    for (int index = 0; index < entries.size(); index++) {
      SocialVitalRemainder entry = entries.get(index);
      if (entry == null) {
        throw new IllegalArgumentException("SocialVitalRemainders.entries 第 " + index + " 项不得为 null");
      }
      if (!seen.add(RemainderKey.of(entry))) {
        throw new IllegalArgumentException(
            "SocialVitalRemainders 出现重复键 (household, lot, kind): "
                + entry.householdId()
                + " / "
                + entry.lotId()
                + " / "
                + entry.kind());
      }
      copy.add(entry);
    }
    entries = Collections.unmodifiableList(copy); // ★ 冻在赋值处（与项目其余保序组件同制）
  }

  /** 查某个 {@code (household, lot, kind)} 的当前余数；没有条目 ⇒ {@link Optional#empty()}。 */
  public Optional<Long> numerator(HouseholdId householdId, PeopleLotId lotId, VitalKind kind) {
    SocialVitalRemainder entry = findEntry(householdId, lotId, kind);
    return entry == null ? Optional.empty() : Optional.of(entry.numerator());
  }

  /** 查某个 {@code (household, lot, kind)} 的当前余数；没有条目 ⇒ 0（结算公式的加数就是它）。 */
  public long numeratorOrZero(HouseholdId householdId, PeopleLotId lotId, VitalKind kind) {
    SocialVitalRemainder entry = findEntry(householdId, lotId, kind);
    return entry == null ? 0L : entry.numerator();
  }

  /**
   * 写入/覆盖某个键的余数：新键追加表尾、已有键原位替换；{@code numerator == 0} ⇒ 删除该条目（0 不落键）。
   *
   * <p>取值范围与键的非 null 由 {@link SocialVitalRemainder} 构造期判。</p>
   */
  public SocialVitalRemainders withNumerator(
      HouseholdId householdId, PeopleLotId lotId, VitalKind kind, long numerator) {
    return new SocialVitalRemainders(upsert(entries, new SocialVitalRemainder(householdId, lotId, kind, numerator)));
  }

  /** 某个键是否存在余数条目。 */
  public boolean hasNumerator(HouseholdId householdId, PeopleLotId lotId, VitalKind kind) {
    return findEntry(householdId, lotId, kind) != null;
  }

  private SocialVitalRemainder findEntry(HouseholdId householdId, PeopleLotId lotId, VitalKind kind) {
    if (householdId == null) {
      throw new IllegalArgumentException("SocialVitalRemainders 查余数：householdId 不得为 null");
    }
    if (lotId == null) {
      throw new IllegalArgumentException("SocialVitalRemainders 查余数：lotId 不得为 null");
    }
    if (kind == null) {
      throw new IllegalArgumentException("SocialVitalRemainders 查余数：kind 不得为 null");
    }
    RemainderKey wanted = new RemainderKey(householdId, lotId, kind);
    for (SocialVitalRemainder entry : entries) {
      if (RemainderKey.of(entry).equals(wanted)) {
        return entry;
      }
    }
    return null;
  }

  /** 表内 upsert：同键原位替换、新键追加表尾；0 ⇒ 删除。 */
  private static List<SocialVitalRemainder> upsert(
      List<SocialVitalRemainder> source, SocialVitalRemainder incoming) {
    List<SocialVitalRemainder> next = new ArrayList<>(source.size() + (incoming.numerator() == 0L ? 0 : 1));
    boolean replaced = false;
    for (SocialVitalRemainder existing : source) {
      if (RemainderKey.of(existing).equals(RemainderKey.of(incoming))) {
        if (incoming.numerator() != 0L) {
          next.add(incoming);
        }
        replaced = true;
      } else {
        next.add(existing);
      }
    }
    if (!replaced && incoming.numerator() != 0L) {
      next.add(incoming);
    }
    return next;
  }

  /** 余数键：{@code (householdId, lotId, kind)}。 */
  private record RemainderKey(HouseholdId householdId, PeopleLotId lotId, VitalKind kind) {
    static RemainderKey of(SocialVitalRemainder entry) {
      return new RemainderKey(entry.householdId(), entry.lotId(), entry.kind());
    }
  }
}

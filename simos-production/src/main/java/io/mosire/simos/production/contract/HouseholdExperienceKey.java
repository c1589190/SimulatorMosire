package io.mosire.simos.production.contract;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★ <b>「家户 × 经验条」复合键</b>（D-032 约束设计书 §4.1「家户经验账：{@code (HouseholdId, ExperienceTrackId) → 经验值}」）。
 *
 * <p>★★ <b>为什么账目方向是"家户 × 经验条"而不是"家户 × 方案"</b>：用户 U4 要求共用经验条 b 能被<b>多个方案</b> 共同增长与读取 ⇒
 * 账本必须挂在经验条上；方案只是"引用哪些经验条"（{@link ProductionExperienceUse}）。 若把账记成"家户 × 方案"，b 就会被复制成 N
 * 份、彼此漂开——那正是"两处真相"的老病。
 *
 * <p>★★ <b>它是账本的键、不是身份的替代</b>：铁律 1 说 ID 是身份；家户身份仍是 {@link HouseholdId}、 经验条身份仍是 {@link
 * ExperienceTrackId}，本类型只是两者的<b>有序对</b>（复合键的自然写法， 同 {@code HouseholdDebtReference} 的先例）。
 *
 * <p>★ <b>规范串</b>（{@link #toString()} / {@link #parse(String)} 互逆，{@code FieldDelta} 的键与 JSON key
 * 就靠这一对）：{@code <household>@<track>}。分隔符取 {@code '@'} 的理由同先例：家户 id 的实际拼写只含 {@code
 * [A-Za-z0-9_|.\-]}；经验条 id 由本模块自定，{@link ExperienceTrackId} 的构造期守卫<b>显式禁止</b> {@code '@'}（见该类）⇒
 * 两侧都不可能含分隔符。{@link #parse} 对"没有分隔符/多于一个分隔符"的串 <b>fail-closed 具名抛</b>（不猜、不截断）。
 *
 * @param household 家户稳定身份；不得为 null
 * @param track 经验条稳定身份；不得为 null
 */
public record HouseholdExperienceKey(HouseholdId household, ExperienceTrackId track) {

  /** 复合键的规范分隔符（见类注：两侧 id 的拼写都不含它）。 */
  public static final char SEPARATOR = '@';

  public HouseholdExperienceKey {
    Objects.requireNonNull(household, "HouseholdExperienceKey.household 不得为 null");
    Objects.requireNonNull(track, "HouseholdExperienceKey.track 不得为 null");
  }

  /** 规范串 = {@code <household>@<track>}（与 {@link #parse} 互为逆）。 */
  @Override
  public String toString() {
    return household.value() + SEPARATOR + track.value();
  }

  /**
   * 规范串 ⇒ 复合键（{@link #toString()} 的逆）。
   *
   * @throws IllegalArgumentException 文本为空、没有分隔符、或多于一个分隔符（不猜格式）
   */
  public static HouseholdExperienceKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("HouseholdExperienceKey 的规范串不得为空白");
    }
    int at = text.indexOf(SEPARATOR);
    if (at <= 0 || at != text.lastIndexOf(SEPARATOR) || at == text.length() - 1) {
      throw new IllegalArgumentException(
          "HouseholdExperienceKey 的规范串必须是 <household>@<track>（恰一个分隔符，两侧非空）: " + text);
    }
    return new HouseholdExperienceKey(
        HouseholdId.parse(text.substring(0, at)), ExperienceTrackId.parse(text.substring(at + 1)));
  }
}

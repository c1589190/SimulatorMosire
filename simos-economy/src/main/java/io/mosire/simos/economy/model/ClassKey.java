package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.SocialClassId;

/**
 * 阶层行的身份（新经济设计 §3.2 逐字）：{@code (IndustryId, SocialClassId)}。
 *
 * <p>★ **它同时是两张表的键**：{@code EconomyData.classes}（阶层行）与 {@code EconomyData.flows}（周期流水）都以 本类型为键
 * ——故它必须有"裸值 {@code toString()} + {@code static parse}"（{@code FieldDelta} 的 key 由 {@code
 * toString()} 产出、重建时用 {@code parse} 还原，见 {@link io.mosire.simos.util.state.FieldDelta}）。
 *
 * <p>★ **规范串用 {@code "|"} 分隔两段**（照 {@code EdgeRef} 的先例）：地址里的 {@code class.<industryId>.<slotId>}
 * 是给人读的另一套写法（点分），二者互不干扰——{@code "|"} 不可能出现在 {@code toString()} 自身的分隔位置上， 故{@link #parse}与{@link
 * #toString}互为逆（两段各自非空）。
 *
 * @param industry 所属产业
 * @param slot 该产业内的阶层槽位
 */
public record ClassKey(IndustryId industry, SocialClassId slot) {

  public ClassKey {
    if (industry == null) {
      throw new IllegalArgumentException("ClassKey.industry 不得为 null");
    }
    if (slot == null) {
      throw new IllegalArgumentException("ClassKey.slot 不得为 null");
    }
  }

  /** 规范字符串形式：{@code <industry>|<slot>}。既是变更集的 key，也是 JSON Map 的键。 */
  @Override
  public String toString() {
    return industry + "|" + slot;
  }

  /**
   * 解析 {@link #toString()} 的产物。
   *
   * <p>**段数不为 2 即抛**（宁抛不静默）：{@code null}、空串、分隔符在首尾、多一段少一段，一律 {@link
   * IllegalArgumentException}；两段各自交给 {@link IndustryId#parse}/{@link SocialClassId#parse}（空白即抛）。
   */
  public static ClassKey parse(String text) {
    if (text == null) {
      throw new IllegalArgumentException("非法阶层键: null");
    }
    int i = text.indexOf('|');
    if (i <= 0 || i == text.length() - 1 || text.indexOf('|', i + 1) >= 0) {
      throw new IllegalArgumentException("非法阶层键: " + text);
    }
    return new ClassKey(
        IndustryId.parse(text.substring(0, i)), SocialClassId.parse(text.substring(i + 1)));
  }
}

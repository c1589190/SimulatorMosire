package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.NationId;

/**
 * 外交关系边的键（D-003 / R6）：**有向边** {@code fromNationId → toNationId}——同一对国家 A→B 与 B→A 是两条不同的边。
 *
 * <p>★ **它是键、不是值**：关系内容（自然语言）住在 {@link DiplomaticRelation}，本类型只承载"哪两个 Nation、哪个方向"。
 *
 * <p>★★ <b>{@code toString()} / {@link #parse} 的长度前缀编码</b>：变更集（{@code FieldDelta}）的 key 约定是"各 key
 * 类型的裸值 {@code toString())"，而 {@code NationId} 的值允许任意非空白文本（世界里的 id 是中文名）。若用 {@code "|"} 或
 * {@code "->"} 之类裸分隔符拼串，一个含该分隔符的 Nation id 会**静默错切**（两个不同的边解析成同一对端点）⇒ 用<b>长度前缀</b>
 * {@code <from 的 UTF-16 长度>:<from><to>}：对任意 Nation id 都无歧义（{@code to} 至少一个字符，故长度前缀能精确切出两段）。
 *
 * <p>★ 自环（{@code from == to}）在构造期拒绝：D-003 的边是"每个 Nation 对其他 Nation"，自己对自己的关系没有语义。
 */
public record DiplomaticRelationKey(NationId from, NationId to) {

  public DiplomaticRelationKey {
    if (from == null) {
      throw new IllegalArgumentException("外交关系边的 from 不得为 null");
    }
    if (to == null) {
      throw new IllegalArgumentException("外交关系边的 to 不得为 null");
    }
    if (from.equals(to)) {
      throw new IllegalArgumentException("外交关系边不得自环（from=to=" + from.value() + "）");
    }
  }

  /**
   * 规范串：{@code <from 长度>:<from 裸值><to 裸值>}（长度按 {@link String#length()} 的 UTF-16 码元数，与 {@link
   * #parse} 的 {@link String#substring(int, int)} 同一把尺）。
   */
  @Override
  public String toString() {
    String fromValue = from.value();
    return fromValue.length() + ":" + fromValue + to.value();
  }

  /**
   * 解析 {@link #toString()} 的产物（变更集 key 还原的唯一入口）。
   *
   * <p>非法输入一律抛 {@link IllegalArgumentException}（宁抛不静默）：{@code null}、空串、无冒号、长度前缀非数、长度越界 （{@code to}
   * 会切成空串）都在这里挡下；两段交给 {@link NationId#parse} 完成空白的最终校验。
   */
  public static DiplomaticRelationKey parse(String text) {
    if (text == null || text.isEmpty()) {
      throw new IllegalArgumentException("非法外交边键: " + text);
    }
    int colon = text.indexOf(':');
    if (colon <= 0) {
      throw new IllegalArgumentException("非法外交边键（缺长度前缀）: " + text);
    }
    int fromLength;
    try {
      fromLength = Integer.parseInt(text.substring(0, colon));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("非法外交边键（长度前缀不是整数）: " + text, e);
    }
    if (fromLength <= 0) {
      throw new IllegalArgumentException("非法外交边键（from 长度必须 > 0）: " + text);
    }
    int fromStart = colon + 1;
    int fromEnd = fromStart + fromLength;
    // fromEnd == text.length() 时 to 为空串 ⇒ 拒绝（NationId 也要求非空白；此处先把"形状"判掉）。
    if (fromEnd >= text.length()) {
      throw new IllegalArgumentException("非法外交边键（长度越界或 to 为空）: " + text);
    }
    return new DiplomaticRelationKey(
        NationId.parse(text.substring(fromStart, fromEnd)),
        NationId.parse(text.substring(fromEnd)));
  }
}

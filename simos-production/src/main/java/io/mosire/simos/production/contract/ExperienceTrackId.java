package io.mosire.simos.production.contract;

/**
 * ★★ <b>经验条的稳定身份</b>（D-032 约束设计书 §4.1；用户 U2/U4"经验条"）。
 *
 * <p>★★ <b>为什么经验条要有自己的 ID（而不是"家户 × 方案"的复合键）</b>：用户 U4 原文要求
 * 「多个生产方式也可以有额外的<b>共用经验条</b>嘛？……不止当前生产方式也能累加的另一个熟练度参数为 b」 ⇒
 * 经验条必须能被<b>多个方案共同增长和读取</b>，因此它的身份必须独立于任何单个方案。这正是
 * `2026-10-17-d032-production-experience-choice-design.md` §2.4 那句"不能只在'家户 × 单一方式'上做一个
 * 封闭、不可复用的字段"的落地形状。
 *
 * <p>★ <b>铁律 1 三件套</b>：非空白守卫 + 裸值 {@code toString()} + {@code static parse}（供变更集 String key 与 JSON
 * key 还原）。
 *
 * @param value 非空白稳定键（如 {@code arable-farming}）
 */
public record ExperienceTrackId(String value) {

  public ExperienceTrackId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ExperienceTrackId 不得为空白");
    }
    // ★ 复合键（HouseholdExperienceKey）用 '@' 作分隔符 ⇒ 本 id 的拼写必须排除它，
    //   否则 "家户×经验条" 的规范串会二义（两个不同的对拼出同一个串 ⇒ 账本条会串行）。
    if (value.indexOf(HouseholdExperienceKey.SEPARATOR) >= 0) {
      throw new IllegalArgumentException(
          "ExperienceTrackId 不得含复合键分隔符 '" + HouseholdExperienceKey.SEPARATOR + "': " + value);
    }
  }

  /** 裸值（身份/地址形式，不是调试输出）。 */
  @Override
  public String toString() {
    return value;
  }

  /** 解析 {@link #toString()} 的产物；空白与 {@code null} 抛（与构造器同口径：宁抛不静默）。 */
  public static ExperienceTrackId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ExperienceTrackId 不得为空白: " + text);
    }
    return new ExperienceTrackId(text);
  }
}

package io.mosire.simos.app.skill;

/**
 * 一篇技能的**摘要**（目录项）：只有 id / 标题 / 版本，**不含正文**。
 *
 * <p>★ 分开是因为代价：目录会给模型整份看一遍，而正文常常只有一两篇真被读到——把全部正文塞进目录是最贵的做法。
 */
public record SkillSummary(String id, String title, int version) {

  public SkillSummary {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("技能 id 不得为空白");
    }
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("技能 title 不得为空白");
    }
  }
}

package io.mosire.simos.app.skill;

import java.nio.file.Path;

/**
 * 一篇技能（Skill 库的**完整**形态）。
 *
 * <p>★ {@code version} 与 {@code modifiedMillis} 不是装饰：技能**不在世界 revision 内**，改了它不会在世界里留下痕迹 ⇒
 * 这两个字段就是"当时读的是哪一版"的**唯一审计锚**（回放一段 AAR 时对得上的凭据）。见 {@link SkillLibrary} 的类注。
 *
 * @param id 技能 id（头里的 {@code id}，缺省取文件名）
 * @param title 标题（头里的 {@code title}，缺省取正文第一个一级标题，再缺省取 id）
 * @param version 版本号（头里的 {@code version}，缺省 1）
 * @param body Markdown 正文（**含**那个一级标题；不剥标题——模型读到的与文件里写的一致）
 * @param source 实际读到的那个文件（两处来源里**赢了的那一个**）
 * @param modifiedMillis 读取时的 mtime（审计锚）
 */
public record Skill(
    String id, String title, int version, String body, Path source, long modifiedMillis) {

  public Skill {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("技能 id 不得为空白");
    }
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("技能 title 不得为空白");
    }
    if (body == null) {
      throw new IllegalArgumentException("技能 body 不得为 null（空的正文用空串）");
    }
  }
}

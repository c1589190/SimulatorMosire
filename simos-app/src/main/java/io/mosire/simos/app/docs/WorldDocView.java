package io.mosire.simos.app.docs;

import java.util.List;

/**
 * 一份世界文档的读取结果：元数据 + 当前正文 + 版本清单。
 *
 * @param meta 元数据
 * @param content 当前正文（Markdown）
 * @param versions 版本清单（按版本升序；当前版本指向 {@code current.md}）
 */
public record WorldDocView(WorldDocMeta meta, String content, List<WorldDocVersion> versions) {

  public WorldDocView {
    if (meta == null) {
      throw new IllegalArgumentException("WorldDocView.meta 不得为 null");
    }
    content = content == null ? "" : content;
    versions = versions == null ? List.of() : List.copyOf(versions);
  }
}

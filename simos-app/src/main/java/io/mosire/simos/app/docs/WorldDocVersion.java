package io.mosire.simos.app.docs;

/**
 * 某个地址的一份历史版本描述。
 *
 * @param version 版本号（从 1 起）
 * @param file 相对文档目录的文件名（{@code current.md} 或 {@code history/vN.md}）
 */
public record WorldDocVersion(int version, String file) {

  public WorldDocVersion {
    if (version < 1) {
      throw new IllegalArgumentException("WorldDocVersion.version 必须 ≥ 1: " + version);
    }
    if (file == null || file.isBlank()) {
      throw new IllegalArgumentException("WorldDocVersion.file 不得为空白");
    }
  }
}

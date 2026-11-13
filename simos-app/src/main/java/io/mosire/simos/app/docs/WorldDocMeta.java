package io.mosire.simos.app.docs;

import java.util.List;
import java.util.Objects;

/**
 * 一份世界文档的元数据（存于 {@code meta.json}）。
 *
 * <p>D-036：一个地址可对应多个版本、多个名称；文档是独立持久数据，不随节点/revision 删除。
 *
 * @param address 程序化地址（节点地址或旧 namespace 地址）
 * @param title 显示标题
 * @param names 名称/别名（可查询；空表 = 只有 title/address 可查）
 * @param version 当前版本号（从 1 起；每次编辑 +1）
 * @param createdAt 创建时间（ISO-8601 字符串）
 * @param updatedAt 最近更新时间（ISO-8601 字符串）
 * @param deletedAt 删除时间（ISO-8601）；null = 未删除
 */
public record WorldDocMeta(
    String address,
    String title,
    List<String> names,
    int version,
    String createdAt,
    String updatedAt,
    String deletedAt) {

  public WorldDocMeta {
    if (address == null || address.isBlank()) {
      throw new IllegalArgumentException("WorldDocMeta.address 不得为空白");
    }
    if (title == null || title.isBlank()) {
      throw new IllegalArgumentException("WorldDocMeta.title 不得为空白");
    }
    names = names == null ? List.of() : List.copyOf(names);
    for (String name : names) {
      if (name == null || name.isBlank()) {
        throw new IllegalArgumentException("WorldDocMeta.names 的元素不得为空白");
      }
    }
    if (version < 1) {
      throw new IllegalArgumentException("WorldDocMeta.version 必须 ≥ 1: " + version);
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  /** 是否已删除（进入回收站）。 */
  public boolean deleted() {
    return deletedAt != null;
  }
}

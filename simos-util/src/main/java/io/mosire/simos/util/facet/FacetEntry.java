package io.mosire.simos.util.facet;

import java.util.Objects;

/**
 * 一条跨模块视图（spec §八）。
 *
 * @param namespace 贡献者的模块名（`unit` / `social`），用于分组与显示排序
 * @param label 显示标签
 * @param typeName 供 UI 着色/图标
 * @param value **结构化** `Object`（Jackson 可序列化），不是预先格式化的字符串——GUI / MCP / LLM
 *     三种消费者对同一个值有不同呈现需求，字符串化会把结构在 Core 层丢掉
 */
public record FacetEntry(String namespace, String label, String typeName, Object value) {

  public FacetEntry {
    if (namespace == null || namespace.isBlank()) {
      throw new IllegalArgumentException("FacetEntry.namespace 不得为空白");
    }
    if (label == null || label.isBlank()) {
      throw new IllegalArgumentException("FacetEntry.label 不得为空白");
    }
    if (typeName == null || typeName.isBlank()) {
      throw new IllegalArgumentException("FacetEntry.typeName 不得为空白");
    }
    Objects.requireNonNull(value, "value");
  }
}

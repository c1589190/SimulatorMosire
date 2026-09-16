package io.mosire.simos.util.info;

import io.mosire.simos.util.identity.SubjectId;
import io.mosire.simos.util.time.TimeRange;
import java.util.Objects;
import java.util.Optional;

/**
 * 一条外挂信息：`key` + 结构化值 + 有效期 + 来源（总纲 §4.6）。
 *
 * <p>**Info ≠ 领域字段**：凡影响领域计算的（`TerraType.height` 影响河流生成）都是领域字段， 不许走这条路；否则 Info 会退化成绕过领域模型的 JSON
 * 垃圾桶（总纲 §4.6）。
 *
 * <p>`value` 是结构化 `Object`，不是预格式化字符串。
 */
public record InfoEntry(
    String key, Object value, TimeRange valid, SubjectId source, Optional<String> note) {

  public InfoEntry {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("InfoEntry.key 不得为空白");
    }
    Objects.requireNonNull(value, "value");
    Objects.requireNonNull(valid, "valid");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(note, "note");
  }
}

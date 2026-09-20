package io.mosire.simos.sd.model;

import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.util.state.RevisionId;
import java.util.Optional;

/**
 * sd 侧 INFO 条目（spec §六，R14）：**自造**、**不复用**全局 {@code InfoEntry}——避免拖入 {@code TimeRange} / {@code
 * SubjectId} 的时态语义（spec §〇.3 / §六）。
 *
 * <p>★ **写的是感知层**（spec §六倾向）：供 UI / AAR 展示；ground truth 仍由 map/unit 等领域模块持有。★ 影响领域计算的字段**不许** 走
 * INFO（兑现 {@code InfoEntry} 的告诫）。
 *
 * <p>★ 诚实边界（spec §六 + M4 裁定 38 同口径）：{@code value} 是**裸 {@code Object}**——结构化值（Map/List）的 {@code
 * equals} 往返**不满足**，故往返判据只覆盖标量值（A5 明记，不假装覆盖）。
 */
public record SdInfoEntry(
    String key,
    Object value,
    Optional<String> note,
    RevisionId at,
    Optional<DirectiveId> sourceDirective) {

  public SdInfoEntry {
    if (key == null || key.isBlank()) {
      throw new IllegalArgumentException("key 不得为空白");
    }
    if (value == null) {
      throw new IllegalArgumentException("value 不得为 null");
    }
    if (note == null) {
      throw new IllegalArgumentException("note 不得为 null（无备注用 Optional.empty()）");
    }
    if (at == null) {
      throw new IllegalArgumentException("at 不得为 null");
    }
    if (sourceDirective == null) {
      throw new IllegalArgumentException("sourceDirective 不得为 null（无来源用 Optional.empty()）");
    }
  }
}

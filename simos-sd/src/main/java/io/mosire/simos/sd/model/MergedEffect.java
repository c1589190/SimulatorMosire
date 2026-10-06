package io.mosire.simos.sd.model;

import java.util.List;

/**
 * 合并效果集里的一条有序效果（D2 定义、D3 使用）：可以是原始 call 的复制，也可以是 GM 编辑后的命令。
 *
 * <p>★ {@code sourceCallRefs} 是来源 call 的稳定引用（形如 {@code pkt-…:0}），保序不可变——合并要能回答"这条效果 是谁的哪条提议变来的"。
 */
public record MergedEffect(String toolName, String argsJson, List<String> sourceCallRefs) {

  public MergedEffect {
    if (toolName == null || toolName.isBlank()) {
      throw new IllegalArgumentException("MergedEffect.toolName 不得为空白");
    }
    if (argsJson == null) {
      throw new IllegalArgumentException("MergedEffect.argsJson 不得为 null（无参数用 {}）");
    }
    if (sourceCallRefs == null) {
      throw new IllegalArgumentException("MergedEffect.sourceCallRefs 不得为 null（无来源用空表）");
    }
    for (String ref : sourceCallRefs) {
      if (ref == null || ref.isBlank()) {
        throw new IllegalArgumentException("MergedEffect.sourceCallRefs 不得含空白");
      }
    }
    sourceCallRefs = List.copyOf(sourceCallRefs); // ★ 冻在赋值处
  }
}

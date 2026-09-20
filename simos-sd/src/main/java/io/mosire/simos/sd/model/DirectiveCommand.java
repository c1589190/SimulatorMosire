package io.mosire.simos.sd.model;

/**
 * 决策携带的一条结构化命令（spec §三.5）：{@code type}（{@code <namespace>.<Command>}）+ 不透明载荷 JSON。
 *
 * <p>★ {@code type} 走**命令白名单**（§四）；★ **{@code sd} 自己的命令也不允许自指**（防无限递归）——v1 明确禁。校验在 {@code
 * sd.IssueDirective} 的命令期（D1）。
 */
public record DirectiveCommand(String type, String payloadJson) {

  public DirectiveCommand {
    if (type == null || type.isBlank()) {
      throw new IllegalArgumentException("type 不得为空白");
    }
    if (payloadJson == null) {
      throw new IllegalArgumentException("payloadJson 不得为 null（无载荷用 \"{}\"）");
    }
  }
}

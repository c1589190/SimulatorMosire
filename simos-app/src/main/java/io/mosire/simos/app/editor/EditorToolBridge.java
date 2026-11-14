package io.mosire.simos.app.editor;

import java.util.Map;

/**
 * 固定白名单的编辑器工具桥：网页端一次保存/读图只能调用这里点名的那几条领域工具，不能借 HTTP 执行任意 GM 工具。
 *
 * <p>实现由组合根提供（{@code RegistryEditorToolBridge}）：它从 GM 工具注册表按名字取同一条工具、用系统权限上下文执行， 并把工具结果（JSON 文本）与
 * PNG 工件（assetId + base64）折成 HTTP 可读的形状。
 */
public interface EditorToolBridge {

  /**
   * 执行一条白名单领域工具。
   *
   * @param toolName 固定工具名（实现必须拒绝白名单之外的任何名字）
   * @param arguments 工具参数（原样交领域工具）
   * @return {@code {ok, result?, images?, code?, message?}}
   */
  Map<String, Object> invoke(String toolName, Map<String, Object> arguments);
}

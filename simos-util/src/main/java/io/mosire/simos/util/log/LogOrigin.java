package io.mosire.simos.util.log;

/**
 * ★★ <b>日志来源的类型架构</b>（2026-10-23 用户裁定：util 只设类型字段架构，各模块自己独立维护来源表）。
 *
 * <p>实现形态由各模块自定（本仓一律是 {@code enum XxxLogSource implements LogOrigin}），表项必须带<b>中文说明</b>。 util
 * 不认识任何模块的 id、事件名或 logger 名。
 *
 * <pre>{@code
 * public enum SomeModuleSource implements LogOrigin {
 *   TOOL_CALL("tool-call", "工具调用（含被拒）", LogOriginKind.INTERACTION);
 *   ...
 * }
 * }</pre>
 *
 * <p>★ <b>id 稳定性</b>（2026-10-23 用户裁定）：id 一经发布<b>不再改名</b>——历史日志靠它 grep。要改 = 新增一项 + 旧项在表里标注废弃。
 */
public interface LogOrigin {

  /** 模块内稳定短 id（kebab-case，grep 用；发布后不改）。 */
  String id();

  /** 中文说明（写进模块来源表，供运维理解这一档"是什么"）。 */
  String description();

  /** 触发粗分类（tick / interaction / system）。 */
  LogOriginKind kind();
}

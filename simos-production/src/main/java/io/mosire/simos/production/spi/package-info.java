/**
 * production 的命令处理器（{@code production.RegisterTechnology} / {@code production.SetMastery}）与共用载荷助手。
 *
 * <p>★ 本包是 production 状态**唯一**的权威写入口径：{@code Command → ChangeSet → Revision}，领域无 public 绕过提交的写路径。
 */
package io.mosire.simos.production.spi;

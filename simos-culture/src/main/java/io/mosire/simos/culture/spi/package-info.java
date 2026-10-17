/**
 * culture 的命令处理器（{@code culture.SetHouseholdCulture} / {@code culture.UpsertAptitudeRule} / {@code
 * culture.RemoveAptitudeRule}）与共用载荷助手。
 *
 * <p>★ 本包是 culture 状态**唯一**的权威写入口径：{@code Command → ChangeSet → Revision}，领域无 public 绕过提交的写路径。
 */
package io.mosire.simos.culture.spi;

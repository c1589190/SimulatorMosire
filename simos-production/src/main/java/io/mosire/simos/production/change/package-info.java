/**
 * production 切片的变更集（{@code ProductionChangeSet}；铁律 5：从完整状态类型派生，往返不变式由 Z6 守卫）。
 *
 * <p>★ 组件与 {@code ProductionData} 的 record 组件一一对应；差异/重建语义委托 util 的 {@code FieldDelta}，不另写第二份。
 */
package io.mosire.simos.production.change;

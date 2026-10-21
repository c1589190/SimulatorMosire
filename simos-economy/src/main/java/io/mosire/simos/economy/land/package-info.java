/**
 * ★★★ <b>标量 LAND 的纯账本/校验实现</b>（D-032 RA1a，2026-10-19）：把 {@code io.mosire.simos.economy.api.land}
 * 的公共值形状守成“每 hex 总量守恒、任意数量同 hex 转移、确定性枚举”的不可变账本。
 *
 * <p>★ <b>本包不接线</b>：无 EconomyData/ChangeSet/Codec/StateBuilder/Invariant，也不改债务/市场/生产/Seeder；RA1b 才把
 * {@link LandBook} 接进持久化与运行时。
 *
 * <p>★ <b>无旧资产份额 id、无旧块编号</b>：土地是标量库存，账本键只有 {@code (hex, owner)}；数量降到 0 的 键被删除，不做“拆块/并块”。
 *
 * <p>★ <b>日志</b>：本单不是 tick 阶段、未接线，因此不新增日志锚点；RA1b/RA2 接线时按经济日志纪律补 INFO/DEBUG/TRACE。
 */
package io.mosire.simos.economy.land;

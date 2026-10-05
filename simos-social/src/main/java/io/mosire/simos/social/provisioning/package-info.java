/**
 * ★★ <b>Social 侧人口/劳动/需求权威旁表</b>（2026-10-09 家户结构修复计划 Batch 1，§3.2~§3.5）。
 *
 * <p>本包只放"人口属性的可调系数表"本身与它的查询语义：
 *
 * <ul>
 *   <li>{@link io.mosire.simos.social.provisioning.DemandPeriod}：需求的时间口径词表；
 *   <li>{@link io.mosire.simos.social.provisioning.DemandCoefficient}：{@code (年龄档, 性别, 商品)} 的
 *       每人需求量（毫单位 / 周期）；
 *   <li>{@link io.mosire.simos.social.provisioning.LaborCoefficient}：{@code (年龄档, 性别)} 的每人 每 tick
 *       毫小时预算；
 *   <li>{@link io.mosire.simos.social.provisioning.SocialProvisioning}：全局默认 + 逐户覆盖两张表， 以及"家户覆盖 &gt;
 *       全局默认"的具名查找。
 * </ul>
 *
 * <p>★ <b>边界</b>：本包不装库存/货币/价格/产业（那是经济切片），也不装"谁属于哪户"（那是 {@code Household.members}）。逐成员展开的纯函数落在
 * {@code SocialData} 上，本包只提供"查一行系数"的权威语义 ——避免出现两套展开算法。
 *
 * <p>★ 本包所有类型零 Jackson 注解：线格式由 {@code SocialCodec} 负责。
 */
package io.mosire.simos.social.provisioning;

/**
 * production 切片的 JSON 编解码（{@code ProductionCodec}，namespace {@code "production"}）。
 *
 * <p>★ 本包只做"状态形状 ↔ 字节"的转换，不含领域规则；键反序列化器（{@code IndustryId} / {@code ProductionModeId} / {@code
 * ProductionUnitId} / {@code ExperienceTrackId} / {@code HouseholdId} / {@code
 * HouseholdExperienceKey} / {@code CommodityId} / {@code CurrencyId}）在本包注册，是全模块唯一的 键读侧拼写点。★ D-032
 * 前的 {@code TechnologyId} / {@code MasteryScope} 两个键已随科技点面（D1）一起删除。
 */
package io.mosire.simos.production.codec;

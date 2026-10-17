/**
 * culture 切片的 {@code ModuleCodec}（{@link io.mosire.simos.culture.codec.CultureCodec}）。
 *
 * <p>★ 状态与变更集的 JSON 形态是本模块自己的事：本包只注册读侧键反序列化器（{@code HouseholdId}），领域类型保持零 Jackson 注解。★ D-032（D2
 * 直接删）：{@code CultureId} / {@code ProductionSkillKey} 两个键反序列化器已随 {@code aptitudeRules} 组件一起删除。
 */
package io.mosire.simos.culture.codec;

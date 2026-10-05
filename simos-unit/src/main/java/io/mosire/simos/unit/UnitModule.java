package io.mosire.simos.unit;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 单位侧**编制模块**（阶段 9，2026-09-30 用户裁定）：一个单位**至多一个**编制标签，互斥由 sealed 类型保证（裁定 3）。
 *
 * <p>★★ <b>职责边界（用户裁定 1/2）</b>：{@code Unit} 只放**编制成分/所处位置/如何移动**，<b>不算任何力量</b>——
 * 政府班子的行政力与一支军队的战斗力<b>分开算</b>，分别归 {@code simos-gov} / {@code simos-army}（本模块两个都不依赖）。
 * 因此本接口及其子类型里不许出现任何"行政效率/战力/加成"字段；它们只记"有哪些人、什么政策、认谁当主子"。
 *
 * <p>★ <b>两个实现</b>（一单位至多一个）：
 *
 * <ul>
 *   <li>{@link GovernmentFormation}：实际拥有行政职能的政府单位（裁定 5/7）——中央与地方都走它，层级由 {@link GovernmentLevel} 表达；
 *   <li>{@link ArmyFormation}：军事单位——一期只记"认哪个 GOV 当主子 + 职责短名"，战力计算不在本链表里（裁定 8）。
 * </ul>
 *
 * <p>★ <b>线格式</b>：Jackson 多态类型信息以注解钉在类型上（{@code Id.NAME} + 封闭子类集，与 {@code Affiliation}/{@code
 * Action} 同制）——不依赖某台 mapper 上的 mixin，也不另造手写格式。{@code Unit.module} 是 {@code
 * Optional<UnitModule>}，旧档缺 {@code "module"} 键 ⇒ {@code Optional.empty()}（同 {@code jurisdiction}
 * 的旧档兼容口径）。
 *
 * <p>★ <b>为什么是 sealed 接口而不是枚举 + 各存一份字段</b>：两种编制的字段形状不同（GOV 有 {@code staff}/{@code policy}/{@code
 * superiorGov}/{@code level}，Army 只有 {@code masterGov}/{@code role}）；枚举 +
 * 可空字段等于把"哪种字段在哪种编制里有效"变成运行期口头约定，sealed + record 让它在编译期成立。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "@class")
@JsonSubTypes({
  @JsonSubTypes.Type(value = GovernmentFormation.class, name = "gov"),
  @JsonSubTypes.Type(value = ArmyFormation.class, name = "army"),
})
public sealed interface UnitModule permits GovernmentFormation, ArmyFormation {}

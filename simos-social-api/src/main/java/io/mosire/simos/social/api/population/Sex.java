package io.mosire.simos.social.api.population;

/**
 * 人口的性别（第三阶段设计稿 §三 / §十.1「性别必须做」）：{@code MALE} / {@code FEMALE}。
 *
 * <p>★ 2026-10-09 家户/人口架构 §3.1：本词表从 {@code io.mosire.simos.social.population} 迁入 social 契约层
 * （公共契约层），旧包已删。人口状态（{@code PopulationGroup}）仍住 simos-social，只引用本类型。
 *
 * <p>★ **它为什么是一个类型而不是一个 boolean**：性别进了两处**会增殖**的语义——劳动折算（{@code EconomySeeder} 的年龄 ×
 * 性别系数表）与人口再生产（出生按育龄女性算，§七，属 R4）。boolean 会让"将来要加档"变成改签名， 而枚举加一档只改一处词表（与 {@code AssetKind} 同制）。
 *
 * <p>★ **它只表达"是哪个性别"**，不含任何系数、比率或权利——那些是**参数**（{@code EconomySeeder} 的系数表、世界生成期的性别比例
 * preset），不是词表的一部分。
 *
 * <p>★ **`name()` 就是它的稳定拼写**：{@code PopulationLots} 的 lot id 直接用 {@code name()}（大写、不加映射、不做大小写转换）
 * ——词表改名的代价是"存档里的 lot id 跟着变"，故本枚举的常量名视为**线格式的一部分**。
 */
public enum Sex {
  MALE,
  FEMALE
}

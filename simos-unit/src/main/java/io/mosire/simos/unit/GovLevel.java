package io.mosire.simos.unit;

/**
 * 政府编制层级（阶段 9，2026-09-30 裁定）：{@link #CENTRAL} | {@link #PROVINCE}。
 *
 * <p>★ 中央也是 GOV 单位（裁定 5）——不是"另一个类型的实体"；层级只决定它在编制链里的位置： {@link GovFormation#superiorGov()}
 * 为空的是层级根（中央），多数省直接指中央。★ <b>它不参与权限判定</b>： 决策人"能读/能写哪片"由 {@code simos-gov} 的范围函数按直辖算，本枚举只是编制事实。
 */
public enum GovLevel {

  /** 中央：层级根，{@code superiorGov} 应为空（是否为空由 GOV 侧校验，本词表不做跨字段约束）。 */
  CENTRAL,

  /** 省：地方行政单位，{@code superiorGov} 多数直接指中央。 */
  PROVINCE
}

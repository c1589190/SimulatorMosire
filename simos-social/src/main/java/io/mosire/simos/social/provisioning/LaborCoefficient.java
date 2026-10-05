package io.mosire.simos.social.provisioning;

import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.population.AgeBracket;

/**
 * ★★ <b>人口劳动系数</b>（2026-10-09 家户结构修复计划 §3.3）：按 {@code (年龄档, 性别)} 给出的 <b>每人每 tick 的毫小时预算</b>（{@code
 * 1 小时 = 1000 毫小时}，定点整数、无浮点）。
 *
 * <pre>
 * 家户劳动预算 = Σ 成员份额 × milliHoursPerTick   // 逐成员整数乘加，本身无时间分数
 * </pre>
 *
 * <p>★★ <b>它是 Social 侧的权威表</b>：经济侧 {@code HouseholdEconomy.laborMilli} 只是 app 注入的当日物化投影，
 * 不得拿它反推第二份劳动供给（计划 §2.2）。默认值表来自计划 §3.5 的"未成年 4h / 成年男 16h / 成年女 8h / 老年 0h"， 家户可按户覆盖；覆盖删除 ⇒
 * 回落全局默认（{@link SocialProvisioning#findLabor} 的查找顺序）。
 *
 * <p>★ <b>不变量</b>（构造期判、坏数据当场抛）：两个引用字段不得为 null；{@code milliHoursPerTick >= 0}。 0 是合法值（老年默认
 * 0；"这一档现在不配置劳动"与"未知档位"是两件事——后者由查不到时的具名拒绝表达）。
 *
 * <p>★ {@link #key()} 是表内唯一键（年龄档 × 性别），供 {@link SocialProvisioning} 拒绝重复行。
 *
 * <p>★ 本类型零 Jackson 注解：线格式由 {@code SocialCodec} 负责。
 *
 * @param ageBracket 年龄档；不得为 null
 * @param sex 性别；不得为 null
 * @param milliHoursPerTick 每人每 tick 的毫小时预算；不得为负
 */
public record LaborCoefficient(AgeBracket ageBracket, Sex sex, long milliHoursPerTick) {

  public LaborCoefficient {
    if (ageBracket == null) {
      throw ProvisioningReject.reject("LaborCoefficient.ageBracket 不得为 null");
    }
    if (sex == null) {
      throw ProvisioningReject.reject("LaborCoefficient.sex 不得为 null");
    }
    if (milliHoursPerTick < 0L) {
      throw ProvisioningReject.reject(
          "LaborCoefficient.milliHoursPerTick 不得为负: " + milliHoursPerTick);
    }
  }

  /** 表内唯一键：{@code (年龄档, 性别)}；同一张表内不得出现两行同键。 */
  public LaborKey key() {
    return new LaborKey(ageBracket, sex);
  }

  /**
   * 劳动系数的唯一键（{@code (年龄档, 性别)}）。
   *
   * <p>它只在内存里参与"表内是否重复"的判定：{@code record} 的 {@code equals}/{@code hashCode} 正好是 二维键的相等语义，{@code
   * LinkedHashSet} 因此可以在保序的同时当场抓到重复行。
   *
   * @param ageBracket 年龄档；不得为 null
   * @param sex 性别；不得为 null
   */
  public record LaborKey(AgeBracket ageBracket, Sex sex) {

    public LaborKey {
      if (ageBracket == null) {
        throw ProvisioningReject.reject("LaborCoefficient.LaborKey.ageBracket 不得为 null");
      }
      if (sex == null) {
        throw ProvisioningReject.reject("LaborCoefficient.LaborKey.sex 不得为 null");
      }
    }
  }
}

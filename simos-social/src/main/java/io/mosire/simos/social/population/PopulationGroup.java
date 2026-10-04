package io.mosire.simos.social.population;

import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.Sex;

/**
 * ★★ **人口的实体**（第三阶段设计稿 §三 + 2026-10-09 家户/人口架构 §4.2）：一批**属性完全相同的活人** —— 男的女的、几个人、多大了、身体状态如何。
 *
 * <p>★★ <b>2026-10-09 家户架构：{@code residence} 字段已删除</b>（架构 §4.2 / 用户 7.3 裁定）。
 * 批次的位置<b>只</b>能从所属 {@code Household} 的 {@code location} 得到：{@code SocialData.locationOfLot(...)} /
 * {@code householdOfLot(...)} 是唯一读口。于是 unit 家户与 hex 家户是同一个模型的两档，批次可以随家户从 hex 搬到 unit
 * （{@code id} 不变），而不需要往本类型加任何"位置/居住类型"字段。
 *
 * <p>★★ **它绝不是新版 {@code ClassRow}**（设计稿 §二 的明令）：本类型**不装** 贫农/中农/富农/地主、也不装 {@code farm}/{@code
 * craft}/{@code serf}、更不装库存货币债务。那些是**生产关系**（{@code Relation}/{@code LaborAllocation}，属
 * R2+）与**经济主体**（{@code EconomicActor}，属 economy 切片）的事。 只有把三者分开，"富裕依附农 / 贫穷自由农" 这种交叉才表达得出来 ——
 * 现在表达不了，因为阶层就是主键。
 *
 * <p>★★ **年龄是派生量，不每天改字段**（设计稿 §三 原文）：锚点时刻记 {@code ageAtAnchorDays} 与 {@code anchorTick}， 任意时刻的年龄由
 * {@link #ageDaysAt(long)} 现算 —— 逐日精度、不做五岁桶（用户旧设计的原口径：{@code
 * POLITICAL_ECONOMY_DESIGN.md:71}「0—4、5—9 等只是查询聚合」）。 于是"推进里变老"**不需要写任何状态**（T6 的全部内容）。
 *
 * <p>★ **身份是 {@link PeopleLotId}**（稳定 id 现住 {@code simos-social-api}，设计稿 §八.1 / 架构 §3.1）：迁移 = 换所属家户，
 * **id 不变**。id 由**调用方**给短名（本阶段 = {@link PopulationLots} 的两条命名：农村/城镇 + 性别）。
 *
 * <p>★ 量纲：{@code count} 是人（整数），{@code ageAtAnchorDays}/{@code anchorTick} 是**天**（日制裁定：{@code
 * SimosTimestamp.tick} 的单位就是天）。
 *
 * @param id 批次稳定身份；不得为 null
 * @param sex 性别；不得为 null
 * @param count 这批有几个人；**不得为负**（0 = 空批，合法：一批人整体迁走/死绝后仍可留着自己的身份）
 * @param ageAtAnchorDays **锚点时刻**的年龄（天）；不得为负
 * @param anchorTick 锚点（世界日）；不得为负
 * @param physiologicalStress ★★ **生理压力累积**（设计稿 §三 的字段，R4 落地）：**不得为负**。
 *     <p>★★ **它不是"饿了多少人"，而是"这批人身上积了多少亏空"**（spec §七）：缺粮/缺布的日子往上加，供给恢复后逐日消退，
 *     只有**长期严重不足**才把它堆到足以显著抬高死亡率的量级。于是"一次五天的供应中断"与"连续半年的严重营养不足"**不会产生同样的结果** —— 这正是 §九 R4
 *     行那条判据的落点。★ 它的**日常加减**在 {@code PopulationDynamics.stressAfter}（月度结算只读它算生死）。
 */
public record PopulationGroup(
    PeopleLotId id,
    Sex sex,
    long count,
    long ageAtAnchorDays,
    long anchorTick,
    long physiologicalStress) {

  /**
   * ★ **不带压力的 5 参构造**（= 压力 0）：创世播种、命令解析与既有夹具走的都是它。
   *
   * <p>★ 存在的理由：压力是**运行期才长出来的**量（创世那一刻人人没有亏空），把它塞进每一处 `new PopulationGroup(...)` 只会让调用点各写一个无意义的
   * {@code 0L}。**默认值只有一个拼写点**（这里），未来改口径也只需改这一行。
   */
  public PopulationGroup(
      PeopleLotId id, Sex sex, long count, long ageAtAnchorDays, long anchorTick) {
    this(id, sex, count, ageAtAnchorDays, anchorTick, 0L);
  }

  public PopulationGroup {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (sex == null) {
      throw new IllegalArgumentException("sex 不得为 null");
    }
    if (count < 0) {
      throw new IllegalArgumentException("count 必须 ≥ 0: " + count);
    }
    if (ageAtAnchorDays < 0) {
      throw new IllegalArgumentException("ageAtAnchorDays 必须 ≥ 0: " + ageAtAnchorDays);
    }
    if (anchorTick < 0) {
      throw new IllegalArgumentException("anchorTick 必须 ≥ 0: " + anchorTick);
    }
    if (physiologicalStress < 0) {
      throw new IllegalArgumentException("physiologicalStress 必须 ≥ 0: " + physiologicalStress);
    }
  }

  /**
   * 换一件事：人数 + 生理压力（**月度结算的唯一写点**：出生/死亡改 {@code count}，压力由逐日加减给出）。
   *
   * <p>★ 其余字段（身份、性别、年龄锚点）**一个都不动** —— 死亡减的是同一批人的数量，不是换一批人。
   */
  public PopulationGroup withCountAndStress(long newCount, long newStress) {
    return new PopulationGroup(id, sex, newCount, ageAtAnchorDays, anchorTick, newStress);
  }

  /** 换生理压力（其余字段原样带过）：逐日的"加一些/消退一些"。 */
  public PopulationGroup withPhysiologicalStress(long newStress) {
    return new PopulationGroup(id, sex, count, ageAtAnchorDays, anchorTick, newStress);
  }

  /**
   * 这批人在 {@code nowTick} 那一天的年龄（天）：{@code ageAtAnchorDays + (nowTick − anchorTick)}（设计稿 §三 的公式）。
   *
   * <p>★ **纯函数、不写状态**：年龄不是每天改的字段，而是"锚点 + 时间差"的现算值。★ 这就是 T6 里 social 成为时间参与者的全部内容——
   * 推进链条上有它，而它**不需要改任何字段**。
   *
   * <p>★ **查询早于锚点的时刻**按同一公式往回推（得到当时更小的年龄）：公式是线性的，不夹取、不抛 —— 往回推是重放/分支比较时的
   * 正常查询，把它变成异常只会让"看历史"变成出错。
   */
  public long ageDaysAt(long nowTick) {
    return ageAtAnchorDays + (nowTick - anchorTick);
  }
}

package io.mosire.simos.social.population;

import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★ **人口的实体**（第三阶段设计稿 §三）：一批**属性完全相同的活人** —— 住在哪、男的女的、几个人、多大了。
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
 * <p>★ **身份是 {@link PeopleLotId}**（复用 {@code simos-economy-api} 已有的稳定 id，设计稿 §八.1）：迁移 = 换 {@link
 * #residence}，**id 不变**。id 由**调用方**给短名（本阶段 = {@link PopulationLots} 的两条命名：农村/城镇 + 性别）。
 *
 * <p>★ 量纲：{@code count} 是人（整数），{@code ageAtAnchorDays}/{@code anchorTick} 是**天**（日制裁定：{@code
 * SimosTimestamp.tick} 的单位就是天）。
 *
 * @param id 批次稳定身份；不得为 null
 * @param residence 现居格；不得为 null（迁移 = 换它，id 不变）
 * @param sex 性别；不得为 null
 * @param count 这批有几个人；**不得为负**（0 = 空批，合法：一批人整体迁走/死绝后仍可留着自己的身份）
 * @param ageAtAnchorDays **锚点时刻**的年龄（天）；不得为负
 * @param anchorTick 锚点（世界日）；不得为负
 */
public record PopulationGroup(
    PeopleLotId id,
    HexCoord residence,
    Sex sex,
    long count,
    long ageAtAnchorDays,
    long anchorTick) {

  public PopulationGroup {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (residence == null) {
      throw new IllegalArgumentException("residence 不得为 null");
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
  }

  /**
   * 这批人在 {@code nowTick} 那一天的年龄（天）：{@code ageAtAnchorDays + (nowTick − anchorTick)}（设计稿 §三 的公式）。
   *
   * <p>★ **纯函数、不写状态**：年龄不是每天改的字段，而是"锚点 + 时间差"的现算值。★ 这就是 T6 里 social 成为时间参与者的全部内容——
   * 推进链条上有它，而它**不需要改任何字段**。
   *
   * <p>★ **查询早于锚点的时刻**按同一公式往回推（得到当时更小的年龄）：公式是线性的，不夹取、不抛 —— 往回推是重放/分支比较时的 正常查询，把它变成异常只会让"看历史"变成出错。
   */
  public long ageDaysAt(long nowTick) {
    return ageAtAnchorDays + (nowTick - anchorTick);
  }
}

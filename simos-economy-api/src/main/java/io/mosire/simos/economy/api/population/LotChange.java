package io.mosire.simos.economy.api.population;

import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★ **一个批次在一个结算月里的生死变动**（R4）：{@code births} 与 {@code deaths} 是**两条独立落账**的量。
 *
 * <p>★★ **它带着"住在哪一格"**（{@code at}）：经济侧把这份账摊回阶层行时，归属靠**该批次供给了哪些产业** （{@code LaborAllocation}）——
 * 而**没有劳动配额的批次**（典型：0-14 岁那一档，劳动系数为 0 ⇒ 创世不给它发配额） 就摊不出去。真实档里那正是**死亡最多**的那一档（未成年死亡率最高）⇒
 * 少了它，"孩子死了还在吃饭"。故这份账 **自己说清楚人住在哪**，经济侧据此**兜底**摊到该格的产业行上（见 {@code 旧结算引擎（R3a
 * 已删除）.applyPopulationChange}）。
 *
 * <p>★★ **为什么出生要有一个与死亡对称的项**：v1 的流水只有 {@code deaths}（"饿死的人口"），而 R4 起人口真的会两头动 —— 只记死亡会让"年末人口 − 创世人口
 * == 出生 − 死亡"这条恒等式**根本写不出来**（差出来的那一块只能靠反推）。故两端显式落账， 且**两侧都进经济侧的流水**（{@code FlowRow.deaths} / {@code
 * FlowRow.births}），守恒式因此是可核对的。
 *
 * <p>★★ **它住在 {@code economy-api} 而不是任何一侧的切片里**，理由与 {@link PeopleLotId} 同款（跨切片的稳定身份/桥）：这是**两侧都要看见的桥** ——
 * 出生/死亡的**判定**在 {@code social}（人住在那里：年龄、性别、生理压力都是批次的属性），而**落实**（阶层行的人口、劳动配额、 流水）在 {@code
 * economy}。{@code social → economy-api} 是设计稿 §八.1 明文允许的方向，反向则不行。
 *
 * <p>★ **量纲**：人（整数）。两者都**不得为负** —— "负数出生"不是一种状态，是坏数据。
 *
 * <p>★ **{@code group} 是身份**（{@link PeopleLotId}）：它让"这一笔是哪批人的"在两侧都指得同一个人，不需要第二份映射表。
 *
 * @param group 发生变动的批次；不得为 null
 * @param at 该批次**住在哪一格**（经济侧摊账的兜底归属，见类注）；不得为 null
 * @param births 本结算月的出生人数（人）；不得为负
 * @param deaths 本结算月的死亡人数（人）；不得为负
 */
public record LotChange(PeopleLotId group, HexCoord at, long births, long deaths) {

  public LotChange {
    if (group == null) {
      throw new IllegalArgumentException("LotChange.group 不得为 null");
    }
    if (at == null) {
      throw new IllegalArgumentException("LotChange.at 不得为 null（住在哪一格是这笔账的一部分，见类注）");
    }
    if (births < 0L) {
      throw new IllegalArgumentException("LotChange.births 不得为负: " + births);
    }
    if (deaths < 0L) {
      throw new IllegalArgumentException("LotChange.deaths 不得为负: " + deaths);
    }
  }

  /** 什么都没发生（两侧都为 0）：调用方据此**不落键**，保住"空表"的纯形态。 */
  public boolean isEmpty() {
    return births == 0L && deaths == 0L;
  }
}

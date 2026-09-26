package io.mosire.simos.economy.api.cohort;

import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★ <b>受方身份：某一格上的某个社会阶层</b>（spec §2.6 的 B′；S1 阶段 4+5 Task 1）。
 *
 * <p>★★ <b>它回答的问题只有一个：「这笔实物报酬是给谁的」</b>（{@code ProductionRelation} 的 {@code
 * CompensationRule.recipient} 的一种）。★ <b>它不是行键</b>（裁定 R9）：S1 阶段 4+5 <b>不合并</b> {@code ClassKey} →
 * {@code CohortKey}，行键的形状（含 {@code industry} 段）一字不动 ⇒ <b>V9 / I1.2 在本阶段开不了账</b>（如实记为未达成项，落点 =
 * {@code ClassRow} 收窄那一轮）。
 *
 * <p>★★ <b>为什么住 {@code simos-economy-api}</b>（裁定 E3）：它需要 {@link SocialClassId}（本模块）与 {@link
 * HexCoord} （{@code simos-map}），而 spec §三 把它列在 {@code simos-actor-api} —— 那个模块的<b>主依赖为零</b>（阶段 2 把
 * {@code simos-map} / {@code simos-util} 都删了）⇒ 硬放会成 {@code actor-api → economy-api → actor-api}
 * <b>循环</b>。 与 {@code LaborAllocation} 同待遇：<b>两侧切片都要看得见的东西，只能住契约层</b>。
 *
 * <p>★★ <b>本类型自带「裸 {@code toString()} + 单参 {@code parse}」这一对</b>（硬约束 R9，照 {@code
 * GoodsAccountKey#parse} / {@link io.mosire.simos.actor.api.actor.ActorRef#parseCanonical} 的先例；前者住在
 * {@code simos-actor}、本模块<b>不依赖</b>它，故只点名不链接）：{@code FieldDelta}（{@code simos-util}）把状态表的键压成 {@code
 * toString()} 的产物、重建时用 {@code parse} 还原 ⇒ 缺了这条配对，下游（阶段 6 的 receipt 表）就被迫自己写规范串的逆，于是
 * <b>同一个格式有了两处拼写点</b>。★ <b>格式的拼写只许在一个文件之内</b>：分隔符常量、{@code toString()} 与 {@code parse} 同住本文件。
 *
 * <p>★ <b>规范串的形状</b>：{@code <q>_<r>|<stratum>}，例如 {@code 3_-2|poor_peasant}。两段各自交给上游的逆（{@link
 * HexCoord#parse} / {@link SocialClassId#parse}）—— <b>本类不知道</b>下划线怎么切、阶层词表有哪几个值，只认「第一个 {@code |}
 * 是接缝」。
 *
 * <p>★★ <b>为什么按「第一个 {@code |}」切</b>（与 {@code GoodsAccountKey} / {@code AssetHoldingKey} 同款）：
 * <b>两个分量都不含接缝</b>（坐标是 {@code 数字_数字}、阶层是四词词表）⇒ 在<b>合法</b>串上「第一个」与「最后一个」<b>恒等</b>，
 * 而坏输入上「第一个」把<b>整段尾巴</b>交给阶层词表（报错点落在真正的坏段上）。★ 反面写法「按<b>最后一个</b>接缝切」不会静默 产出错的键（它照样抛），但它把坏输入的报错引到坐标轴
 * ⇒ 本条契约里「接缝在第一个 {@code |}」是<b>显式写下的</b>，不是巧合。
 *
 * <p>★ <b>身份键的粒度如实记</b>（裁定 R7）：{@code (hex, 阶层)} 在<b>城市格</b>上会把「农村贫农」与「城镇贫农」并成<b>一个</b>
 * cohort（它们今天靠 {@code ClassKey} 的产业段区分）—— 这<b>正是</b> R9 那条未合并的身份键的残留，也是 §2.6 目标模型的样子。
 *
 * <p>★ <b>补注（裁定 E24，2026-09-26）</b>：这一"并成一个 cohort"的残留只影响<b>身份键</b>，不影响<b>受方行</b>了 ——
 * 落到哪些行由<b>劳动侧</b>定池（{@code LaborAllocation.group} 的批次 → 它供给的产业，见 {@code
 * EconomySettlement.classRowsOfCohort}）：家庭纺织的 700‰ 只落<b>农业行</b>，作坊的 600‰ 只落<b>作坊行</b>。 ★
 * 本类型的<b>形状与规范串一字未改</b>（阶段 6 的 receipt 表仍按它键）。
 *
 * @param residence 居住格（{@code HexCoord} 是身份；「某人在哪一格」不影响它）
 * @param stratum 社会阶层（**产业无关**的人口身份，spec §2.6）
 */
public record CohortKey(HexCoord residence, SocialClassId stratum) {

  /**
   * 规范串的段分隔符 —— <b>只在 {@link #toString()} 与 {@link #parse(String)} 两处被读</b>（同处一个文件，故「分隔符长什么样」在本类型只有
   * 这一个拼写点）。
   */
  private static final String SEGMENT_SEPARATOR = "|";

  public CohortKey {
    if (residence == null) {
      throw new IllegalArgumentException("CohortKey.residence 不得为 null");
    }
    if (stratum == null) {
      throw new IllegalArgumentException("CohortKey.stratum 不得为 null");
    }
  }

  /** 规范串：{@code <q>_<r>|<stratum>}（既是状态表的键，也是阶段 6 receipt 表的键）。 */
  @Override
  public String toString() {
    return residence + SEGMENT_SEPARATOR + stratum;
  }

  /**
   * 解析 {@link #toString()} 的产物（见类注：按<b>第一个</b>接缝切）。
   *
   * <p>★ 两段各自交给上游的逆（{@link HexCoord#parse} / {@link SocialClassId#parse}）—— <b>本类不复述它们的格式</b>，
   * 故上游改了规范串，本类的往返当场跟着红。
   *
   * <p>★ <b>宁抛不静默</b>（照 {@code GoodsAccountKey#parse} 的口径）：{@code null} / 空白 / 没有接缝 / 接缝在首 /
   * 接缝在尾，一律 {@link IllegalArgumentException} —— 静默造一个半截的受方身份，比当场炸难查得多。
   */
  public static CohortKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("非法 cohort 键: " + text);
    }
    int seam = text.indexOf(SEGMENT_SEPARATOR);
    if (seam <= 0) {
      throw new IllegalArgumentException("非法 cohort 键（居住段缺失或在首）: " + text);
    }
    if (seam == text.length() - SEGMENT_SEPARATOR.length()) {
      throw new IllegalArgumentException("非法 cohort 键（阶层段缺失）: " + text);
    }
    return new CohortKey(
        HexCoord.parse(text.substring(0, seam)),
        SocialClassId.parse(text.substring(seam + SEGMENT_SEPARATOR.length())));
  }
}

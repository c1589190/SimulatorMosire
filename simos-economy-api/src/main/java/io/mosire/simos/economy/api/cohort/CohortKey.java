package io.mosire.simos.economy.api.cohort;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.mosire.simos.economy.api.id.SocialClassId;
import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★ <b>家户身份：某一格、某种居住类型上的某个社会阶层</b>（spec §2.6 的 B′；S1 阶段 4+5 Task 1 建； <b>2026-09-27 H0.1
 * 补上居住维</b>）。
 *
 * <p>★★ <b>它是"家户"的身份键</b>（2026-09-27 裁定 D3-C / R-N1-A）：<b>家户 = 持有商品与货币的经济主体</b>，
 * 而人口数、劳动、需求、压力、生死是<b>同一批人的视图</b>（{@code HouseholdEconomy}）。 一个家户给多个产业出劳动 ⇒ <b>仍然只有一个身份</b>（这就是 V9 / I1.2）。
 *
 * <p>★★ <b>为什么必须带"居住类型"那一维</b>（H0.1 的核心，风险 R-N1）：播种器对 {@code farm} / {@code weave} / {@code craft}
 * <b>三个产业都用同一套四阶层</b>与同一组份额， 而同一格的城镇人口是<b>独立批次</b>（{@code urban:} 前缀）。少了这一维，
 * <b>农村贫农与城镇贫农会并成同一个家户</b> ⇒ 农村余粮与城市缺口并到一本账上 ⇒ <b>城市不再饿死，但不是因为修好了通道，而是因为账合并了</b>—— 那正是 {@code
 * AGENT.md} §9.1 的"假绿"，会长在"城市缺口收敛"这条判据上。
 *
 * <p>★★ <b>本类型自带「裸 {@code toString()} + 单参 {@code parse}」这一对</b>（照 {@code HouseholdAccountKey#parse} /
 * {@code ActorRef#parseCanonical} 的先例）： {@code FieldDelta}（{@code simos-util}）把状态表的键压成 {@code
 * toString()} 的产物、 重建时用 {@code parse} 还原 ⇒ 缺了这条配对，下游就被迫自己写规范串的逆， 于是<b>同一个格式有了两处拼写点</b>。★
 * 格式的拼写只许在一个文件之内。
 *
 * <p>★ <b>规范串的形状</b>：{@code <q>_<r>|<residence>|<stratum>}，例如 {@code 3_-2|rural|poor_peasant}。
 * 三段各自交给上游的逆（{@link HexCoord#parse} / {@link ResidenceKind#parse} / {@link SocialClassId#parse}）——
 * <b>本类不知道</b>下划线怎么切、居住类型有几个值、阶层词表有哪几个值。
 *
 * <p>★★ <b>为什么按「第一个、第二个 {@code |}」切</b>：<b>三个分量都不含接缝</b> （坐标是 {@code 数字_数字}、居住类型与阶层都是封闭词表）⇒
 * 在<b>合法</b>串上这种切法与任何切法恒等， 而坏输入上它把<b>整段尾巴</b>交给词表（报错点落在真正的坏段上）。
 *
 * <p>★ <b>本类型是 {@code EconomyData.classes} 与 {@code EconomyData.flows} 的键</b>， 也是 {@code
 * ProductionRelation} 的 {@code CompensationRule.recipient} 的一种 （"这笔实物报酬 / 这笔钱是给哪个家户的"）。
 *
 * @param hex 居住格（{@code HexCoord} 是身份；"某人在哪一格"不影响它）
 * @param residence 居住类型（农村 / 城镇）★ H0.1 新增的那一维
 * @param stratum 社会阶层（**产业无关**的人口身份，spec §2.6）
 */
public record CohortKey(HexCoord hex, ResidenceKind residence, SocialClassId stratum) {

  /**
   * 规范串的段分隔符 —— <b>只在 {@link #toString()} 与 {@link #parse(String)}
   * 两处被读</b>（同处一个文件，故「分隔符长什么样」在本类型只有这 一个拼写点）。
   */
  private static final String SEGMENT_SEPARATOR = "|";

  public CohortKey {
    if (hex == null) {
      throw new IllegalArgumentException("CohortKey.hex 不得为 null");
    }
    if (residence == null) {
      throw new IllegalArgumentException(
          "CohortKey.residence 不得为 null（居住类型是身份的一维：少了它，农村与城镇的同阶层家户会并账）");
    }
    if (stratum == null) {
      throw new IllegalArgumentException("CohortKey.stratum 不得为 null");
    }
  }

  /** 规范串：{@code <q>_<r>|<residence>|<stratum>}（既是状态表的键，也是 receipt / 转移记录的键）。 */
  @Override
  @JsonValue
  public String toString() {
    return hex + SEGMENT_SEPARATOR + residence.value() + SEGMENT_SEPARATOR + stratum;
  }

  /**
   * 解析 {@link #toString()} 的产物（见类注：按<b>第一个与第二个</b>接缝切三段）。
   *
   * <p>★ 三段各自交给上游的逆 —— <b>本类不复述它们的格式</b>，故上游改了规范串，本类的往返当场跟着红。
   *
   * <p>★ <b>宁抛不静默</b>（照 {@code HouseholdAccountKey#parse} 的口径）：{@code null} / 空白 / 段数不足 / 接缝在首或在尾，一律
   * {@link IllegalArgumentException} —— 静默造一个半截的家户身份，比当场炸难查得多。
   */
  @JsonCreator
  public static CohortKey parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("非法 cohort 键: " + text);
    }
    int first = text.indexOf(SEGMENT_SEPARATOR);
    if (first <= 0) {
      throw new IllegalArgumentException("非法 cohort 键（格段缺失或在首）: " + text);
    }
    int second = text.indexOf(SEGMENT_SEPARATOR, first + SEGMENT_SEPARATOR.length());
    if (second < 0) {
      throw new IllegalArgumentException("非法 cohort 键（居住段缺失：需要 <格>|<居住>|<阶层>）: " + text);
    }
    if (second == first + SEGMENT_SEPARATOR.length()
        || second == text.length() - SEGMENT_SEPARATOR.length()) {
      throw new IllegalArgumentException("非法 cohort 键（居住段或阶层段为空）: " + text);
    }
    return new CohortKey(
        HexCoord.parse(text.substring(0, first)),
        ResidenceKind.parse(text.substring(first + SEGMENT_SEPARATOR.length(), second)),
        SocialClassId.parse(text.substring(second + SEGMENT_SEPARATOR.length())));
  }
}

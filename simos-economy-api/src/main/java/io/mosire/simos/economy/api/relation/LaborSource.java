package io.mosire.simos.economy.api.relation;

import java.util.Arrays;

/**
 * ★★ <b>劳动来源</b>（S1：{@code ProductionRelation} 的显式维度）：这份生产的劳动由谁提供、以什么制度身份提供。
 *
 * <p>★★ <b>为什么它是显式字段而不是从 {@code operator} 推</b>：同一个 {@code operator}（例如封建庄园）可以由 农奴、佃农、雇工或经营者自己出劳动
 * —— 补偿规则、剩余归属、乃至 S3 的阶层判定都不同。把它做成字段之后， "这条关系下的劳动是谁的"是<b>数据</b>（改一个枚举值），而不是埋在结算代码里的推导。
 *
 * <p>★ <b>各档语义</b>（S1 只钉词表与传递；结算对 SELF/FAMILY/TENANT/SERF/WAGE 的逐档差异属 S3）：
 *
 * <ul>
 *   <li>{@link #SELF} —— 经营者本人（家户/庄园主的自营劳动）；
 *   <li>{@link #FAMILY} —— 经营者家庭成员（同一家户内的亲属劳动）；
 *   <li>{@link #TENANT} —— 佃农（租佃关系下的劳动，S3 的地租规则读它）；
 *   <li>{@link #SERF} —— 农奴（人身依附关系下的劳动）；
 *   <li>{@link #WAGE} —— 雇工（工资劳动；S3 的 WageFirst 分配读它）。
 * </ul>
 *
 * <p>★ <b>旧档缺该键 ⇒ {@link #SELF}</b>（在 {@link ProductionRelation} 的构造期兜底）：旧口径没有这一维，
 * 把"没有说"读成"经营者自营"是该口径下最保守、且不改变旧结算结果的映射；不静默发明 FAMILY/SERF。
 */
public enum LaborSource {
  SELF,
  FAMILY,
  TENANT,
  SERF,
  WAGE;

  /** 按词表解析；词表外即抛并列出合法值（不默认、不归一）。 */
  public static LaborSource parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("LaborSource 不得为空白: " + text);
    }
    try {
      return valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未知 LaborSource: " + text + "；合法值: " + Arrays.toString(values()));
    }
  }
}

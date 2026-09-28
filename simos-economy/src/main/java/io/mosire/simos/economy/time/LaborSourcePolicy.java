package io.mosire.simos.economy.time;

import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.ProductionRelation;
import io.mosire.simos.economy.api.relation.RuleType;

/**
 * ★★ <b>S3：按 {@link LaborSource} 决定损失承担/付款次序的唯一策略点</b>（计划 §S3.2 "谁承担损失"表）。
 *
 * <p>★★ <b>它只改"付款次序"这一件事</b>（规则还是同一批、金额还是同一算式）：制度决定"先给谁"—— 这与 {@code ProductionSettlement} 的 {@code
 * priority} 数据叠加成两级键：先按 {@code priority}（数据）， 同优先级再按本策略的<b>制度档</b>（{@code SERF} 先给养后地租、{@code
 * TENANT} 先自留、{@code WAGE} 先工资）。 表内顺序仍是稳定排序的最终 tie-break（{@code inPaymentOrder} 的既有语义）。
 *
 * <p>★ <b>为什么不改 {@code CompensationRule} 的字段</b>：制度的差别是"同一批规则在不同制度下的次序"，
 * 写成规则数据会要求每个场景显式写两遍次序；本策略把四档制度语义收在一处，读的人一眼能对到计划表。
 */
final class LaborSourcePolicy {

  private LaborSourcePolicy() {}

  /**
   * 同一 {@code priority} 之内的制度次序档（越小越先付）。
   *
   * <ul>
   *   <li>{@code SERF}：给养（{@code FIXED_IN_KIND_PER_LABOR}）先于地租；其余按表序；
   *   <li>{@code TENANT}：自留（{@code SELF_RETENTION}）先于地租（佃农先承担投入 + 自留产出）；
   *   <li>{@code WAGE}：工资（{@code FIXED_MONEY_WAGE} / 实物劳动报酬）先于地租；经营者承担其余风险（残值归 operator）；
   *   <li>{@code SELF} / {@code FAMILY}：家户自己承担 ⇒ 不施加次序偏置（表序原样）。
   * </ul>
   */
  static int priorityTier(LaborSource source, CompensationRule rule) {
    if (source == null || rule == null) {
      return 1;
    }
    return switch (source) {
      case SERF -> {
        if (rule.type() == RuleType.FIXED_IN_KIND_PER_LABOR) {
          yield 0; // 先给养/口粮
        }
        if (isRent(rule.type())) {
          yield 2; // 再地租
        }
        yield 1;
      }
      case TENANT -> {
        if (rule.type() == RuleType.SELF_RETENTION) {
          yield 0; // 佃农先承担投入 + 自留产出
        }
        if (isRent(rule.type())) {
          yield 2;
        }
        yield 1;
      }
      case WAGE -> {
        if (rule.type() == RuleType.FIXED_MONEY_WAGE
            || rule.type() == RuleType.FIXED_IN_KIND_PER_LABOR) {
          yield 0; // 工资先付（付不出进 WageArrears 读数，不当 0）
        }
        if (isRent(rule.type())) {
          yield 2;
        }
        yield 1;
      }
      case SELF, FAMILY -> 1;
    };
  }

  /** 地租档（实物固定租 + 货币地租）。 */
  static boolean isRent(RuleType type) {
    return type == RuleType.FIXED_IN_KIND_RENT || type == RuleType.FIXED_MONEY_RENT;
  }

  /** 给养/工资档（"先给养、再地租"与"工资先付"读它；两者都是劳动报酬那一族）。 */
  static boolean isLaborPay(RuleType type) {
    return type == RuleType.FIXED_IN_KIND_PER_LABOR || type == RuleType.FIXED_MONEY_WAGE;
  }

  /** 关系里的制度档（缺关系 ⇒ 按 {@link LaborSource#SELF}，与 {@code ProductionRelation} 的缺省同值）。 */
  static LaborSource sourceOf(ProductionRelation relation) {
    return relation == null ? LaborSource.SELF : relation.laborSource();
  }
}

package io.mosire.simos.economy.api.relation;

import java.util.Arrays;

/**
 * ★★ <b>补偿规则的类型（六档词表）</b>：一次生产关账以后，产出按什么<b>方式</b>分给受方（spec §2.4 的「规则类型」表 + 裁定 E5）。
 *
 * <p>★ <b>与 {@link Pool} × {@link Weight} 正交</b>：类型说「怎么算这一档」（自留 / 分成 / 按劳动的固定实物 / 固定实物租 / 货币工资 /
 * 货币地租），池说「数量取自哪一层」（毛产 / 净产 / 经营剩余 / 固定额），权重说「池在受方之间怎么分」 （不分 / 按劳动量 / 平均）。★ <b>H2（裁定
 * D5-B）之前这三件事挤在一个 {@code basis} 里</b>——旧五档到新两个字段的 映射表在 {@link Basis}
 * 的类注里（那已是<b>只服务旧档读侧</b>的兼容词表）。三者<b>都不含公式</b>：公式表住 {@code simos-economy} 的 {@code
 * ProductionSettlement}（Task 3），本模块只有形状与守卫。
 *
 * <p>★★ <b>「货币档」这条事实的唯拼写点是 {@link #money()}</b>（裁定 I5.3「只定义、不结算」）：结算据它把规则分流进 {@code
 * deferredMoney}（不产生任何产权条目、cohort 也不入账），并明确报「待 S2」。★ <b>不许</b>在别处用字符串判断 （{@code
 * name().startsWith("FIXED_MONEY")} 之类）复述这条事实 —— 那就是同一个格式的第二处拼写点（本仓的硬规矩）。
 *
 * <p>★ <b>fail-closed 的词表</b>：{@link #parse(String)} 对词表外的输入即抛并列出全部合法值（照 {@code ActorKind.parse}
 * 的形制）。载荷边缘（{@code EconomyPayloads} 的 {@code industries[].relation}）读的就是它 ⇒ 写错一个档位必须<b>当场炸</b>，
 * 不许静默兜底。
 */
public enum RuleType {

  /**
   * 自留：这一档<b>不动</b>任何东西（余额归 {@code ProductionRelation.residualOwner}）。
   *
   * <p>★ 它是「自留」这件事的<b>显式</b>写法（读关系表的人不必去猜缺省）；把自留写成「没有这条规则」也等价（空表 ⇒ 全归 residualOwner）。
   */
  SELF_RETENTION(false),

  /** 产出分成：按 {@link Pool} 取一层数量、按 {@link Weight} 摊给受方，再乘 {@code ratePerMille}。 */
  OUTPUT_SHARE(false),

  /** 按劳动量的实物报酬（给养）：每 1000 劳动给 {@code fixedAmount}（单位由 {@code commodity} 定）。 */
  FIXED_IN_KIND_PER_LABOR(false),

  /** 固定实物租：每周期一笔 {@code fixedAmount}（无劳动、无资产比例）。 */
  FIXED_IN_KIND_RENT(false),

  /**
   * 货币工资：<b>只定义字段、不结算</b>（spec §2.4 / I5.3）—— S1 没有 ledger，不假装货币结算成功。
   *
   * <p>★ 它的 {@code commodity} 必须为<b>空</b>（{@link CompensationRule} 的构造期守卫判死）：这是「二选一是类型事实」的 载体，也是
   * I5.3 的判别力所在。
   */
  FIXED_MONEY_WAGE(true),

  /** 货币地租：同 {@link #FIXED_MONEY_WAGE}，只定义、不结算（待 S2）。 */
  FIXED_MONEY_RENT(true);

  /** 「这一档是否货币」。★ 唯一拼写点（见类注），故只是枚举的构造参数，不另设判断方法。 */
  private final boolean money;

  RuleType(boolean money) {
    this.money = money;
  }

  /**
   * 这一档是不是<b>货币</b>规则（{@code true} ⇒ 结算时进「待 S2」的清单，不产生任何实物条目）。
   *
   * <p>★ 与 {@link CompensationRule} 的守卫同源：{@code money()} 为 {@code true} ⟺ {@code commodity} 必须为空。
   */
  public boolean money() {
    return money;
  }

  /**
   * 按词表解析：<b>词表外的输入即抛，消息列出全部合法值</b>（fail-closed）。
   *
   * <p>★ 不做归一（{@code "share"} / {@code "OutputShare"} 都是未登记）—— 归一是「猜」，同 {@code RegimeOperators}
   * 的口径。
   */
  public static RuleType parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("RuleType 不得为空白: " + text);
    }
    try {
      return valueOf(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "未登记的规则类型: " + text + "；合法值: " + Arrays.toString(values()));
    }
  }
}

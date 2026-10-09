package io.mosire.simos.economy.api.market;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★ <b>市场挂单的"类型"维（2026-10-10 P-T1e：币种挂单过滤的过滤键之一）</b>—— 用户原话
 * 「货币交换的话，异种货币自然按手续费/规则来算啊，有一方不给过就不过，给这个挂单禁止进入市场，出市场……
 * 但是规则还是可以设置的，代表禁止本市场区货币被外国借贷（<b>借贷走的也是市场挂单</b>，恰好本国货币对于本国居民来说
 * 只能借贷不可能一块钱买一块钱，用货买钱倒是不知道可任意不可以）」（逐字见口岸设计书 §14.1）。
 *
 * <pre>
 * EXCHANGE   币 ↔ 币 的兑换（"一块钱买一块钱"那一路：外汇簿 FX 的价钱对）
 * COMMODITY  货 ↔ 钱 的买卖（"用货买钱"/"用钱买货"）
 * LENDING    借贷（借来的钱去买货 / 把钱借出去；用户明写"借贷走的也是市场挂单"）
 * </pre>
 *
 * <p>★★ <b>为什么过滤键必须带类型</b>（设计书 §14.3-4）：只有一个"币种 → 限制"的键<b>表达不出</b>用户给的例子 "禁止本市场区货币被外国借贷"——
 * 同一枚币上，"能不能拿它做兑换"与"能不能拿它做借贷"是两条独立的规则。 本枚举就是那个"类型"位的唯一词表；{@link PortRule} 本身（入口/出口限制 +
 * 入口/出口税）不区分类型，区分靠<b>表键</b> （政策侧 {@code GovPortPolicy.currencyRules} 是"币种 → 类型 → 规则"，注入侧 {@code
 * PortEnforcementInput} 同形）。
 *
 * <p>★★ <b>红线（本枚举的语义边界，不得越界实现）</b>（设计书 §14.3-3，用户原话"没有哪国会禁止本国公民在他国使用自己的货币吧？"）：
 * 这些类型只回答"<b>本市场区</b>允许／禁止哪一类挂单进来、出去"，<b>不是</b>"本币不得在境外使用／不得被外国人持有"。 ⇒
 * 判定读的是<b>参与交易的市场区</b>（钱的来源区/目的区）的规则，<b>不是</b>币种法定区的规则；一枚币在两个都与它无关的区之间 流动时，它的法定区规则<b>一个字都管不着</b>。
 *
 * <p>★★ <b>本批（P-T1e）落地面（别越界）</b>：
 *
 * <ul>
 *   <li>{@link #COMMODITY}：<b>已咬人</b>——商品市场里"买方支付币 × 货↔钱挂单"过 {@code MarketSettlement} 的挂单级闸；
 *   <li>{@link #LENDING}：<b>形状已通、执行面待接</b>——货币信用腿（借来的钱买货）走的是同一个判定点，但本仓的信用撮合是 <b>区内</b>的（D-027
 *       单区），而口岸闸只作用在<b>跨区</b>流动上（设计书 §10.3："区内禁售是市场管制、不是口岸"） ⇒
 *       当前世界里这条闸<b>不可达</b>（记账本"未完成/未验证"一节，不是静默丢弃）；
 *   <li>{@link #EXCHANGE}：<b>形状已通、执行面未接</b>——外汇簿（{@code FxSettlement}）是另一个结算器，本批的落点只在
 *       商品市场的挂单判定点（{@code MarketSettlement.acceptsCurrency}）⇒ 今天的币↔币挂牌<b>不受</b>这条闸约束（同上，具名记在账本）。
 * </ul>
 *
 * <p>★ <b>缺省语义中性</b>（I-C2）：<b>一条规则都不设 ⇒ 全类型全币接受</b> ⇒ 逐值退回改前行为；"显式 0 限制"与"未设"同义
 * （只读有效值，不做"设置过/没设置过"的区分）。
 *
 * <p>★ 字面量一律小写下划线（与 {@link PortDirection} / {@link PortTaxMode} 同制），{@link #parse} <b>不做归一</b>：
 * 写错一个档必须当场炸并看得见全部合法值。
 */
public enum MarketOrderKind {

  /** 币 ↔ 币 兑换（"一块钱买一块钱"；外汇簿那一路）。 */
  EXCHANGE("exchange"),

  /** 货 ↔ 钱 买卖（"用货买钱"／"用钱买货"；商品市场那一路）。 */
  COMMODITY("commodity"),

  /** 借贷（借来的钱买货／把钱借出去；用户"借贷走的也是市场挂单"）。 */
  LENDING("lending");

  private final String value;

  MarketOrderKind(String value) {
    this.value = value;
  }

  /** 规范字面量（小写下划线）：进载荷/读数/线格式的那一段的唯一拼写点。 */
  public String value() {
    return value;
  }

  /** 词表（保序 = <b>声明序</b>）：它是读数/日志/遍历的规范顺序（I7 确定性 —— 不许拿 {@code Map} 迭代序当序）。 */
  public static List<MarketOrderKind> all() {
    return List.of(values());
  }

  /**
   * 按规范字面量解析（<b>大小写敏感</b>，不归一：归一即猜）。
   *
   * @throws IllegalArgumentException 空白、或不在词表里（消息列出全部合法值）
   */
  public static MarketOrderKind parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("MarketOrderKind 不得为空白: " + text);
    }
    List<String> legal = new ArrayList<>(values().length);
    for (MarketOrderKind kind : values()) {
      if (kind.value.equals(text)) {
        return kind;
      }
      legal.add(kind.value);
    }
    throw new IllegalArgumentException("未登记的挂单类型: " + text + "；合法值: " + legal);
  }

  /**
   * ★ <b>线格式的字面量</b>（与 {@link #value()} 同字面）：本枚举会被当作 {@code Map} 的<b>键</b>写进 gov 状态 （{@code
   * GovPortPolicy.currencyRules} 是"币种 → 类型 → 规则"），而 Jackson 的键序列化器对枚举默认写 {@code name()} ⇒ 这里把
   * {@code toString()} 也钉到规范字面量上，配合 {@code GovCodec} 注册的键读写器， 让线格式里出现的是 {@code exchange}/{@code
   * commodity}/{@code lending} 而不是 {@code EXCHANGE}。
   */
  @Override
  public String toString() {
    return value;
  }
}

package io.mosire.simos.production.contract;

/**
 * ★★ <b>生产契约故障</b>（D-032 约束设计书 §4.4 / I15）：受限表达式求值错误、跨切片一致性故障一类的
 * <b>契约级</b>故障，一律以本异常出面，<b>不得</b>静默退化成中性加成。
 *
 * <p>★★ <b>为什么必须是异常而不是返回值</b>：设计书 §4.4 原文「求值错误 ⇒ <b>契约故障 ERROR、拒绝整个候选提交</b>， 不得静默退化成中性加成」。若求值失败返回
 * {@code 0} 或 {@code 1000}，世界会安静地长成"没有加成的另一天"—— 那正是本仓最贵的一类缺陷（错得无声）。故：
 *
 * <ul>
 *   <li>求值期任何越界（未知输入名、除零、{@code long} 溢出、版本未识别）⇒ 抛本异常；
 *   <li>调用方（economy / 组合根）<b>不捕获后降级</b>：让它冒泡到提案级，整个候选提交被拒；
 *   <li>日志侧同时记 ERROR（{@code PRODUCTION_CONTRACT_FAULT}），不降级。
 * </ul>
 *
 * <p>★ <b>它是 unchecked</b>：受限求值散布在试算与执行的纯计算里，强迫每个签名声明 checked 异常只会让调用方写出 {@code catch (Exception e)
 * { /* 忽略 *\/ }} —— 那比不抛更坏。
 *
 * <p>★ <b>它不承载状态</b>：只带 {@code reason}（稳定短 id）与 {@code detail}（人读细节），便于日志与测试断言。
 */
public final class ProductionContractFault extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String reason;

  public ProductionContractFault(String reason, String detail) {
    super(
        "production 契约故障["
            + (reason == null || reason.isBlank() ? "unspecified" : reason)
            + "]: "
            + detail);
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("ProductionContractFault.reason 不得为空白");
    }
    this.reason = reason;
  }

  /** 稳定短 id（日志/断言用；不是给人读的长句）。 */
  public String reason() {
    return reason;
  }
}

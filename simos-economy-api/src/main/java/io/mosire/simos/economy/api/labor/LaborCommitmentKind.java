package io.mosire.simos.economy.api.labor;

/**
 * ★★ <b>劳动承诺的种类</b>（Z1b，设计书 §4.2 / C7）：一张承诺表、一个不变量 {@code Σ commitments ≤
 * laborMilli}，用本枚举把"普通生产承诺"与"政府行政岗位承诺"分成两类。★ 闭枚举：新增种类必须同时更新 {@code EconomyCodec} 的旧档缺省与 {@code
 * LaborQueueSettlement} 的优先级；禁止用字符串 / 未知值兜底。
 *
 * <p>★★ <b>两类承诺的语义差</b>：
 *
 * <ul>
 *   <li>{@link #PRODUCTION}：普通生产承诺；进每 tick 的利润排队，可被按比例缩；旧档缺 {@code kind} 一律读成它。
 *   <li>{@link #GOV_SERVICE}：政府行政岗位承诺（官吏全职）；<b>不进队列、不被重算、不被按比例缩</b>，在 {@code preserveIntoBudget}
 *       里整额先占家户劳动预算，只有剩余的 {@code PRODUCTION} 才参与排队/缩。
 * </ul>
 *
 * <p>★ {@code GOV_SERVICE} 只允许政府工具写（Z3）；队列不得创建 / 覆盖它（{@code LaborQueueSettlement} 在写回前具名
 * fail-closed）。
 */
public enum LaborCommitmentKind {

  /** 生产承诺：家户把这段时间投给普通生产活动，参与劳动利润队列。 */
  PRODUCTION,

  /** 政府行政岗位承诺：官吏全职岗位，最高优先级、不可缩，只允许政府工具写（Z3）。 */
  GOV_SERVICE
}

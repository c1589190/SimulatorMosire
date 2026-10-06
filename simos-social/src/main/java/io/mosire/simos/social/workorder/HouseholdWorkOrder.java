package io.mosire.simos.social.workorder;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * 一张 <b>Social 工单</b>（2026-10-09 用户裁定：家户人口属性的变更一律向 Social 提交"更改理由 + 更改方案 + 更改对象"）： 目标家户 + 有序计划 + 原因
 * + 来源（+ 可选的幂等键）。
 *
 * <ul>
 *   <li>{@code orderId}：可选幂等键。给了 ⇒ 受理成功时在 {@code SocialData.populationEvents} 落一条 {@code
 *       WORK_ORDER} 标记事件（事件 id = {@code work-order:<orderId>}）；同 orderId 再次提交 ⇒ {@link
 *       HouseholdWorkOrderBook} <b>具名拒</b>（不静默重复改人口）。不给 ⇒ 不做幂等，调用方自担重放。
 *   <li>{@code target}：更改对象（家户稳定 id）；必须被 {@code plan} 点名，且计划执行后必须存在。
 *   <li>{@code reason}：非空白；进每一步的 {@code HouseholdBook} 日志/事件与工单汇总日志。
 *   <li>{@code source}：非空白的来源描述（由载荷的 {@code source:{module,commandId?,actorId?}} 规范化而来）；
 *       进工单汇总日志与标记事件。
 *   <li>{@code plan}：非空的有序操作清单，见 {@link HouseholdWorkOrderPlan}。
 * </ul>
 *
 * <p>★ <b>来源是调用方自报</b>：{@code CommandHandler} 结构性看不见发起者（{@code handle(state,payloadJson)} 没有调用者入参），
 * 本记录只能忠实携带载荷里的 {@code source} 供审计，不宣称它是可信身份。
 */
public record HouseholdWorkOrder(
    String orderId, HouseholdId target, String reason, String source, HouseholdWorkOrderPlan plan) {

  public HouseholdWorkOrder {
    if (orderId != null && orderId.isBlank()) {
      throw new IllegalArgumentException("工单 orderId 若给必须非空白（空白做不了幂等键）");
    }
    Objects.requireNonNull(target, "target");
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("工单 reason 不得为空白（日志/事件要能回答为什么）");
    }
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("工单 source 不得为空白（审计要能回答谁提的）");
    }
    Objects.requireNonNull(plan, "plan");
    if (!plan.references(target)) {
      throw new IllegalArgumentException("工单 target 必须被 plan 引用（作为 household/from/to）: " + target);
    }
  }

  /** 幂等键派生的标记事件 id；{@code orderId} 未给 ⇒ {@code null}（不做幂等、不落标记）。 */
  public String markerEventId() {
    return orderId == null ? null : "work-order:" + orderId;
  }
}

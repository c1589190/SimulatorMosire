package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import java.util.Objects;

/**
 * ★★ <b>模式变迁请求与其终态</b>（理想架构 §2.9/§4.4；E6a）：一条"某生产组织从 fromMode 切到 toMode、在 effectiveDay 生效、并保留原所属
 * {@code retainOriginalPerMille}‰"的持久记录。
 *
 * <pre>
 * ModeTransition(id, organizationId, fromModeId, toModeId,
 *                retainOriginalPerMille, requestedDay, effectiveDay,
 *                status: PENDING / APPLIED / FAILED, reason)
 * </pre>
 *
 * <p>★★ <b>命令只登记 PENDING，日结算才执行</b>：{@code economy.SwitchMode} 成功时只新增一条 {@link Status#PENDING}
 * 的记录；真正迁移生产组织/单元/阶层份额由日结算在**自动组织之前**完成（见 {@code EconomyModeTransitionSettlement}）。终态 {@link
 * Status#APPLIED} 或 {@link Status#FAILED} 由日结算写回；{@code FAILED} 必须带具名原因（"没有位置匹配"这类 可读原因，不是静默回滚）。
 *
 * <p>★★ <b>身份唯一性</b>：{@code id} 由 {@link ModeTransitionId#idOf(ProductionOrganizationId,
 * ProductionModeId, long)} 确定性派生（同一组织 + 同一目标 mode + 同一生效日 = 同一条记录）⇒ 同一请求重复提交不会产生第二条；{@code
 * EconomyData} 另判"同一组织至多一条 PENDING"。
 *
 * <p>★ <b>不可变</b>：record 组件全是不可变值；终态切换用 {@link #withStatus(Status, String)} 返回新记录，不原地改。
 *
 * @param id 稳定身份；不得为 null（键 == 值内 id 由 {@code EconomyData} 判）
 * @param organizationId 变迁的生产组织；不得为 null
 * @param fromModeId 变迁发生时的当前生产方式；不得为 null（命令期从组织读出并落盘，之后不再随组织改写而漂）
 * @param toModeId 目标生产方式；不得为 null
 * @param retainOriginalPerMille 保留原所属的千分比；∈ [0, 1000]
 * @param requestedDay 请求提交的世界日；不得为负
 * @param effectiveDay 生效世界日；不得早于 {@code requestedDay}
 * @param status 变迁状态；不得为 null
 * @param reason 原因/失败原因；不得为 null（没有就给空串）；{@link Status#FAILED} 必须非空白
 */
public record ModeTransition(
    ModeTransitionId id,
    ProductionOrganizationId organizationId,
    ProductionModeId fromModeId,
    ProductionModeId toModeId,
    int retainOriginalPerMille,
    long requestedDay,
    long effectiveDay,
    Status status,
    String reason) {

  /** 变迁状态：待执行 / 已应用 / 已失败（FAILED 带具名原因，不静默回滚）。 */
  public enum Status {
    /** 已登记、等待生效日到达；命令成功后的唯一初态。 */
    PENDING,
    /** 日结算已完成组织/单元/阶层份额迁移。 */
    APPLIED,
    /** 日结算规划失败（具名原因），状态不改动；本记录保留供审计，不自动重试。 */
    FAILED
  }

  public ModeTransition {
    Objects.requireNonNull(id, "ModeTransition.id 不得为 null");
    Objects.requireNonNull(organizationId, "ModeTransition.organizationId 不得为 null");
    Objects.requireNonNull(fromModeId, "ModeTransition.fromModeId 不得为 null");
    Objects.requireNonNull(toModeId, "ModeTransition.toModeId 不得为 null");
    Objects.requireNonNull(status, "ModeTransition.status 不得为 null");
    Objects.requireNonNull(reason, "ModeTransition.reason 不得为 null（没有就给空串）");
    if (retainOriginalPerMille < 0 || retainOriginalPerMille > 1000) {
      throw new IllegalArgumentException(
          "ModeTransition.retainOriginalPerMille 必须 ∈ [0, 1000]: " + retainOriginalPerMille);
    }
    if (requestedDay < 0L) {
      throw new IllegalArgumentException("ModeTransition.requestedDay 不得为负: " + requestedDay);
    }
    if (effectiveDay < requestedDay) {
      throw new IllegalArgumentException(
          "ModeTransition.effectiveDay 不得早于 requestedDay: requested="
              + requestedDay
              + "，effective="
              + effectiveDay);
    }
    if (status == Status.FAILED && reason.isBlank()) {
      throw new IllegalArgumentException("ModeTransition.FAILED 必须带具名原因（不许静默失败）");
    }
  }

  /** 终态写回（APPLIED/FAILED + 原因）；PENDING 原样保留请求字段。 */
  public ModeTransition withStatus(Status nextStatus, String nextReason) {
    Objects.requireNonNull(nextStatus, "ModeTransition.withStatus 的 nextStatus 不得为 null");
    Objects.requireNonNull(nextReason, "ModeTransition.withStatus 的 nextReason 不得为 null");
    return new ModeTransition(
        id,
        organizationId,
        fromModeId,
        toModeId,
        retainOriginalPerMille,
        requestedDay,
        effectiveDay,
        nextStatus,
        nextReason);
  }

  /** 同一次请求（身份相同且请求字段逐值相同）⇒ 命令期可安全幂等返回 no-op。 */
  public boolean sameRequest(
      ProductionOrganizationId requestedOrganization,
      ProductionModeId requestedToMode,
      int requestedRetainPerMille,
      long requestedEffectiveDay,
      String requestedReason) {
    return organizationId.equals(requestedOrganization)
        && toModeId.equals(requestedToMode)
        && retainOriginalPerMille == requestedRetainPerMille
        && effectiveDay == requestedEffectiveDay
        && reason.equals(requestedReason);
  }
}

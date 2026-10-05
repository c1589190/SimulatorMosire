package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code economy.SwitchMode}（E6a）：登记一次"某生产组织切换到另一生产方式"的请求。
 *
 * <pre>{@code
 * {"organizationId":"org-…","toModeId":"…","retainOriginalPerMille":400,
 *  "effectiveDay":121?,"reason":"…"?}
 * }</pre>
 *
 * <p>★★ <b>本命令只登记 PENDING，不迁移任何状态</b>：生产组织/生产单元/阶层份额/质押模式的真正迁移由日结算在生效日 （{@code effectiveDay <=
 * day}）**自动组织之前**执行（见 {@code EconomyModeTransitionSettlement}）。因此命令成功 = 恰好新增一条 {@link
 * ModeTransition.Status#PENDING}；一次 revision 原子。
 *
 * <p>★★ <b>校验（命令期，逐条 fail-closed）</b>：enterprise 存在；toMode 存在且 ≠ 组织当前 mode；同一组织没有 PENDING
 * 变迁；{@code retainOriginalPerMille ∈ [0,1000]}；{@code effectiveDay >= 当前日}（缺省 = 当前日）。★
 * <b>同一请求重复提交幂等</b>： id 由 {@link ModeTransitionId#idOf(ProductionOrganizationId, ProductionModeId,
 * long)} 确定性派生，同 id 且请求字段逐值相同 ⇒ 返回空变更集（不再落第二条）。
 *
 * <p>★★ <b>GM-only</b>：实现 {@link GmOnlyCommand} —— 注册面（Core / {@code commandTargets}）照常可见、GM 的
 * {@code simos.command.submit} 可提交；组合根在构造 {@code DirectiveWhitelist} 时排除它，普通 GOV Agent 无法把它写进令里执行。
 * ★ <b>不实现任何给决策人的窄工具</b>（见 {@code SimosToolSource} 的工具面）。
 *
 * <p>★ <b>CommandTargets 的诚实边界</b>：{@code targetPaths(mapId, payloadJson)} 的签名**拿不到状态**，因此无法按 "org
 * 的 unit/industry 或 assetSources 推格键"；这里走 {@link ProductionOrganizationId#hexKey()} —— 它读的是 {@link
 * ProductionOrganizationId#idOf} 已经写进稳定身份末尾的同一个格键（唯一拼写点，不内联切串）。旧档/手写 org id 末尾不是 {@code q_r} ⇒
 * 返回空目标（裁决侧 fail-closed 拒绝"无目标声明"），不伪造坐标。
 *
 */
public final class EconomySwitchModeHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  private static final String COMMAND = "economy.SwitchMode";


  @Override
  public String type() {
    return COMMAND;
  }

  /**
   * ★ 目标声明 = 组织稳定身份末尾的格键（唯一可寻址维度）。
   *
   * <p>★ 该签名没有 {@code SimulationState}，拿不到 org 的 unit/industry；故本实现只解码组织 id 里由 {@code
   * ProductionOrganizationId.idOf} 写下的格键。解不出 ⇒ 空列表（调用方 fail-closed 拒绝，不静默放行）。
   */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload = EconomyCommandPayloads.parseObject(COMMAND, payloadJson);
    ProductionOrganizationId organizationId =
        ProductionOrganizationId.parse(
            EconomyCommandPayloads.requireText(COMMAND, payload, "organizationId"));
    return organizationId.hexKey().map(List::of).orElseGet(List::of);
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(COMMAND, payloadJson);
      ProductionOrganizationId organizationId =
          ProductionOrganizationId.parse(
              EconomyCommandPayloads.requireText(COMMAND, payload, "organizationId"));
      ProductionModeId toModeId =
          ProductionModeId.parse(EconomyCommandPayloads.requireText(COMMAND, payload, "toModeId"));
      int retainOriginalPerMille =
          EconomyCommandPayloads.optionalInt(
              COMMAND, payload, "retainOriginalPerMille", Integer.MIN_VALUE);
      if (retainOriginalPerMille == Integer.MIN_VALUE) {
        throw new IllegalArgumentException(COMMAND + " 缺少整数字段: retainOriginalPerMille");
      }
      long now = state.meta().timestamp().tick();
      long requestedDay = now;
      long effectiveDay =
          EconomyCommandPayloads.optionalLong(COMMAND, payload, "effectiveDay", now);
      String reason = EconomyCommandPayloads.optionalText(COMMAND, payload, "reason", "");

      ProductionEnterprise enterprise = base.productionOrganizations().get(organizationId);
      if (enterprise == null) {
        return new HandlerOutcome.Rejected("生产组织不存在: " + organizationId.value());
      }
      ProductionModeId fromModeId = enterprise.modeId();
      if (!base.modes().containsKey(toModeId)) {
        return new HandlerOutcome.Rejected("目标生产方式不存在: " + toModeId.value());
      }
      if (toModeId.equals(fromModeId)) {
        return new HandlerOutcome.Rejected("目标生产方式与组织当前 mode 相同，不构成变迁: " + toModeId.value());
      }
      if (enterprise.status() == ProductionEnterprise.Status.EXITING) {
        return new HandlerOutcome.Rejected("生产组织正在退出中，拒绝再次变迁: " + organizationId.value());
      }
      if (retainOriginalPerMille < 0 || retainOriginalPerMille > 1000) {
        return new HandlerOutcome.Rejected(
            "retainOriginalPerMille 必须 ∈ [0, 1000]: " + retainOriginalPerMille);
      }
      if (effectiveDay < now) {
        return new HandlerOutcome.Rejected(
            "effectiveDay 不得早于当前日: effective=" + effectiveDay + "，当前=" + now);
      }

      ModeTransitionId transitionId = ModeTransitionId.idOf(organizationId, toModeId, effectiveDay);
      ModeTransition existing = base.modeTransitions().get(transitionId);
      if (existing != null) {
        if (existing.sameRequest(
            organizationId, toModeId, retainOriginalPerMille, effectiveDay, reason)) {
          // ★ 同一请求重复提交：id 相同、字段逐值相同 ⇒ 幂等返回空变更集（不落第二条、不报冲突）。
          return new HandlerOutcome.Applied(EconomyChangeSet.between(base, base));
        }
        return new HandlerOutcome.Rejected(
            "同 (组织, 目标 mode, 生效日) 已存在不同的模式变迁请求: " + transitionId.value());
      }
      for (ModeTransition transition : base.modeTransitions().values()) {
        if (transition.status() == ModeTransition.Status.PENDING
            && transition.organizationId().equals(organizationId)) {
          return new HandlerOutcome.Rejected(
              "该生产组织已有待执行的模式变迁（同一组织至多一条 PENDING）: " + transition.id().value());
        }
      }

      ModeTransition pending =
          new ModeTransition(
              transitionId,
              organizationId,
              fromModeId,
              toModeId,
              retainOriginalPerMille,
              requestedDay,
              effectiveDay,
              ModeTransition.Status.PENDING,
              reason);
      Map<ModeTransitionId, ModeTransition> transitions =
          new LinkedHashMap<>(base.modeTransitions());
      transitions.put(transitionId, pending);
      return new HandlerOutcome.Applied(
          EconomyChangeSet.between(base, base.withModeTransitions(transitions)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

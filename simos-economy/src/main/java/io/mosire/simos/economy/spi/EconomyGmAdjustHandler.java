package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * ★★ <b>{@code economy.GmAdjust}（E6b）：GM 经济调整的窄命令</b>。载荷：
 *
 * <pre>{@code
 * {"adjustment":"forgiveDebt"|"setLiquidationPolicy",
 *  "parameters":{...},
 *  "reason":"..."}
 * }</pre>
 *
 * <p>★★ <b>只认两个 adjustment（源状态白名单）</b>：
 *
 * <ul>
 *   <li>{@code forgiveDebt}：{@code debtContractId} + 可选 {@code amount}（缺省 = 全额本金）。调用 {@code
 *       DebtContractBook.forgive} 减/清本金，<b>不碰粮/钱库存</b>；{@code amount > 本金} ⇒ 具名 {@link
 *       HandlerOutcome.Rejected}；
 *   <li>{@code setLiquidationPolicy}：{@code assetRuleId} + {@code maxLiquidatePerMille ∈ [0,1000]}
 *       + {@code protectedReserve ≥ 0} + {@code priceSource(MARKET|AGREED|POLICY)} + {@code
 *       policyValuePerUnitMilli ≥ 0（非 POLICY 必须 0）} + {@code
 *       recipientRule(CREDITOR_FIRST|MARKET_FIRST)}； upsert 到 {@code liquidationPolicies}；引用的
 *       {@code AssetRule} 不存在 ⇒ 具名拒绝。
 * </ul>
 *
 * <p>★★ <b>派生读数不可直写</b>：{@code flows} / {@code demandBook} / {@code crisisSignals} / {@code
 * classStandings.consecutiveDebtStressCycles} / {@code debtCapacity} 等派生读数没有写口；白名单外的 {@code
 * adjustment} 一律以 {@link EconomyGmAdjustments#DERIVED_REJECTION} 具名拒绝。
 *
 * <p>★★ <b>handler 与 GM 窄写工具共用同一份纯函数</b>：{@link EconomyGmAdjustments#project} 是唯一语义落点 —— handler
 * 只做"装配状态 → project → {@code Applied(changeSet)}"，工具只做"读状态 → 同一个 project → 预览/提交"。 同一 payload
 * 重放确定性由该纯函数保证（保序复制、稳定 ID、无时钟/随机数）。
 *
 * <p>★★ <b>GM-only</b>：实现 {@link GmOnlyCommand} —— 仍注册到 Core、仍进 {@code commandTargets}、GM 的 {@code
 * simos.command.submit} 可提交；但组合根构造 {@code DirectiveWhitelist} / {@code RegisterEffect} 白名单 /
 * 决策人工具目录时 排除它，普通 GOV Agent 无法把它写进令里执行（E6a 的 {@code directiveCommandTypes} 过滤口径不变）。
 *
 * <p>★ <b>{@link CommandTargets} 的诚实边界</b>：{@code targetPaths(mapId, payloadJson)}
 * 的签名拿不到状态；本命令的语义对象 （债务合同 / 生产资料规则）不是本仓资源命名空间里的可寻址路径（economy 资源围栏以格为粒度），且本命令 GM-only、不进入决策人令 ⇒
 * 有意返回空列表（"没有可声明的目标"）。空列表在裁决路径上是 fail-closed 的语义，而本命令根本到不了那条路径。
 */
public final class EconomyGmAdjustHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点：工具、catalog 提示与 Shell 注册都从这里取/对齐）。 */
  public static final String TYPE = "economy.GmAdjust";

  @Override
  public String type() {
    return TYPE;
  }

  /**
   * ★ 本命令没有可声明的资源目标（见类注）：债务合同 / 生产资料规则不是 {@code economy} 命名空间里的格键路径，且本命令 GM-only、
   * 不进入决策人令。只做载荷形状校验（坏载荷仍抛具名 {@link IllegalArgumentException}），合法载荷返回空列表。
   */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
    String adjustment = EconomyCommandPayloads.requireText(TYPE, payload, "adjustment");
    JsonNode parameters = payload.get("parameters");
    if (parameters == null || !parameters.isObject()) {
      throw new IllegalArgumentException(TYPE + " 的字段 parameters 必须是 JSON 对象: " + parameters);
    }
    switch (adjustment) {
      case EconomyGmAdjustments.FORGIVE_DEBT ->
          EconomyCommandPayloads.requireText(TYPE + "." + adjustment, parameters, "debtContractId");
      case EconomyGmAdjustments.SET_LIQUIDATION_POLICY ->
          EconomyCommandPayloads.requireText(TYPE + "." + adjustment, parameters, "assetRuleId");
      default ->
          throw new IllegalArgumentException(
              EconomyGmAdjustments.DERIVED_REJECTION
                  + ": "
                  + adjustment
                  + "（"
                  + TYPE
                  + " 只允许 "
                  + EconomyGmAdjustments.FORGIVE_DEBT
                  + " | "
                  + EconomyGmAdjustments.SET_LIQUIDATION_POLICY
                  + "）");
    }
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(TYPE, payloadJson);
      String adjustment = EconomyCommandPayloads.requireText(TYPE, payload, "adjustment");
      String reason = EconomyCommandPayloads.requireText(TYPE, payload, "reason");
      JsonNode parameters = payload.get("parameters");
      if (parameters == null || !parameters.isObject()) {
        throw new IllegalArgumentException(TYPE + " 的字段 parameters 必须是 JSON 对象: " + parameters);
      }
      long day = state.meta().timestamp().tick();
      EconomyChangeSet changeSet =
          EconomyGmAdjustments.project(base, adjustment, parameters, reason, day).changeSet();
      return new HandlerOutcome.Applied(changeSet);
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

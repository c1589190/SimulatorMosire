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
 * ★★ <b>{@code economy.GmAdjust}：GM 经济调整的窄命令</b>。载荷：
 *
 * <pre>{@code
 * {"adjustment":"forgiveDebt"|"setLiquidationPolicy"
 *              |"upsertProductionMode"|"deactivateProductionMode"
 *              |"upsertClassStructure"|"upsertClassPosition"
 *              |"upsertProductionRelation"|"upsertAssetRule"
 *              |"upsertProductionOrganization"|"upsertCandidate",
 *  "parameters":{...},
 *  "reason":"..."}
 * }</pre>
 *
 * <p>★★ <b>十条 adjustment（源状态白名单，唯一语义落点在 {@link EconomyGmAdjustments#project}）</b>：
 *
 * <ul>
 *   <li><b>旧表两</b>：
 *       <ul>
 *         <li>{@code forgiveDebt}：{@code debtContractId} + 可选 {@code amount}（缺省 = 全额本金）。调用 {@code
 *             DebtContractBook.forgive} 减/清本金，<b>不碰粮/钱库存</b>；{@code amount > 本金} ⇒ 具名 {@link
 *             HandlerOutcome.Rejected}；
 *         <li>{@code setLiquidationPolicy}：{@code assetRuleId} + {@code maxLiquidatePerMille ∈
 *             [0,1000]} + {@code protectedReserve ≥ 0} + {@code priceSource(MARKET|AGREED|POLICY)}
 *             + {@code policyValuePerUnitMilli ≥ 0（非 POLICY 必须 0）} + {@code
 *             recipientRule(CREDITOR_FIRST|MARKET_FIRST)}； upsert 到 {@code liquidationPolicies}；引用的
 *             {@code AssetRule} 不存在 ⇒ 具名拒绝。
 *       </ul>
 *   <li><b>P7 生产方式编辑（八）</b>： {@code upsertProductionMode}（{@code
 *       id,name,version?,classStructureId}；version 必须推进，classStructureId 必须已存在）、{@code
 *       deactivateProductionMode}（被结构/位置/组织/资产规则/变迁/质押引用 ⇒ 具名拒绝）、{@code
 *       upsertClassStructure}（{@code id,modeId,positions?,defaultSharesPerMille?}； 位置 upsert
 *       并同步全局表与所有结构副本）、{@code upsertClassPosition}（{@code id,modeId,字段?,classStructureId?}）、{@code
 *       upsertProductionRelation}（{@code activity,operator?,
 *       inputSupplier?,rules?,residualOwner?,laborSource?}；operator 必须与 unit.operator 一致）、 {@code
 *       upsertAssetRule}（{@code modeId,assetKind,...}；id 由 {@code AssetRuleId.idOf} 派生）、 {@code
 *       upsertProductionOrganization}（引用与四档状态守卫）、{@code upsertCandidate}（按 ProductionCandidate
 *       现有字段；见 {@link EconomyGmAdjustments#project}）。八个 kind 只做形状校验， 引用存在性与幂等由 {@code project} 统一判。
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
 * 的签名拿不到状态；本命令的语义对象 （债务合同 / 生产资料规则 / 生产方式制度表）不是本仓资源命名空间里的可寻址路径（economy 资源围栏以格为粒度），且本命令
 * GM-only、不进入决策人令 ⇒ 有意返回空列表（"没有可声明的目标"）。空列表在裁决路径上是 fail-closed 的语义，而本命令根本到不了那条路径。
 */
public final class EconomyGmAdjustHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点：工具、catalog 提示与 Shell 注册都从这里取/对齐）。 */
  public static final String TYPE = "economy.GmAdjust";

  @Override
  public String type() {
    return TYPE;
  }

  /**
   * ★ 本命令没有可声明的资源目标（见类注）：债务合同 / 生产资料规则 / 生产方式制度表都不是 {@code economy} 命名空间里的格键路径，且本命令
   * GM-only、不进入决策人令。只做载荷形状校验（必填/类型/至少一项；坏载荷仍抛具名 {@link
   * IllegalArgumentException}），合法载荷返回空列表；引用存在性（policy/lender/account/assetRule/meta.config）在 {@code
   * handle} 走 {@link EconomyGmAdjustments#project} 时判、由 catch 折成 {@link HandlerOutcome.Rejected}。
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
    String label = TYPE + "." + adjustment;
    switch (adjustment) {
      case EconomyGmAdjustments.FORGIVE_DEBT ->
          EconomyCommandPayloads.requireText(label, parameters, "debtContractId");
      case EconomyGmAdjustments.SET_LIQUIDATION_POLICY ->
          EconomyCommandPayloads.requireText(label, parameters, "assetRuleId");
      case EconomyGmAdjustments.UPSERT_PRODUCTION_MODE ->
          requireUpsertProductionModeShape(label, parameters);
      case EconomyGmAdjustments.DEACTIVATE_PRODUCTION_MODE ->
          EconomyCommandPayloads.requireText(label, parameters, "id");
      case EconomyGmAdjustments.UPSERT_CLASS_STRUCTURE ->
          requireUpsertClassStructureShape(label, parameters);
      case EconomyGmAdjustments.UPSERT_CLASS_POSITION ->
          requireUpsertProductionRoleShape(label, parameters);
      case EconomyGmAdjustments.UPSERT_PRODUCTION_RELATION ->
          EconomyCommandPayloads.requireText(label, parameters, "activity");
      case EconomyGmAdjustments.UPSERT_ASSET_RULE -> requireUpsertAssetRuleShape(label, parameters);
      case EconomyGmAdjustments.UPSERT_PRODUCTION_ORGANIZATION ->
          requireUpsertProductionEnterpriseShape(label, parameters);
      case EconomyGmAdjustments.UPSERT_CANDIDATE -> requireUpsertCandidateShape(label, parameters);
      default ->
          throw new IllegalArgumentException(
              EconomyGmAdjustments.DERIVED_REJECTION
                  + ": "
                  + adjustment
                  + "（"
                  + TYPE
                  + " 只允许 "
                  + String.join(" | ", EconomyGmAdjustments.ADJUSTMENTS)
                  + "）");
    }
    return List.of();
  }

  /** {@code upsertProductionMode} 的形状：id/name/classStructureId 必填非空；version 可选但必须是整数。 */
  private static void requireUpsertProductionModeShape(String label, JsonNode parameters) {
    EconomyCommandPayloads.requireText(label, parameters, "id");
    EconomyCommandPayloads.requireText(label, parameters, "name");
    EconomyCommandPayloads.requireText(label, parameters, "classStructureId");
    if (hasValue(parameters, "version")) {
      EconomyCommandPayloads.requireLong(label, parameters, "version");
    }
  }

  /** {@code upsertClassStructure} 的形状：id/modeId 必填；positions 可选但必须是对象数组；份额表可选但必须是对象。 */
  private static void requireUpsertClassStructureShape(String label, JsonNode parameters) {
    EconomyCommandPayloads.requireText(label, parameters, "id");
    EconomyCommandPayloads.requireText(label, parameters, "modeId");
    if (parameters.has("positions") && !parameters.get("positions").isNull()) {
      JsonNode positions = parameters.get("positions");
      if (!positions.isArray()) {
        throw new IllegalArgumentException(label + " 的 positions 必须是数组: " + positions);
      }
      for (JsonNode position : positions) {
        if (!position.isObject()) {
          throw new IllegalArgumentException(label + " 的 positions 每项必须是对象: " + position);
        }
      }
    }
    if (parameters.has("defaultSharesPerMille")
        && !parameters.get("defaultSharesPerMille").isNull()
        && !parameters.get("defaultSharesPerMille").isObject()) {
      throw new IllegalArgumentException(
          label + " 的 defaultSharesPerMille 必须是对象: " + parameters.get("defaultSharesPerMille"));
    }
  }

  /** {@code upsertClassPosition} 的形状：id/modeId 必填非空（三个结构维词表与 classStructureId 由 project 统一判）。 */
  private static void requireUpsertProductionRoleShape(String label, JsonNode parameters) {
    EconomyCommandPayloads.requireText(label, parameters, "id");
    EconomyCommandPayloads.requireText(label, parameters, "modeId");
    if (hasValue(parameters, "classStructureId")) {
      EconomyCommandPayloads.requireText(label, parameters, "classStructureId");
    }
  }

  /** {@code upsertAssetRule} 的形状：modeId/assetKind 必填非空；transferRule 可选但必须是对象。 */
  private static void requireUpsertAssetRuleShape(String label, JsonNode parameters) {
    EconomyCommandPayloads.requireText(label, parameters, "modeId");
    EconomyCommandPayloads.requireText(label, parameters, "assetKind");
    if (parameters.has("transferRule") && !parameters.get("transferRule").isNull()) {
      if (!parameters.get("transferRule").isObject()) {
        throw new IllegalArgumentException(
            label + " 的 transferRule 必须是对象: " + parameters.get("transferRule"));
      }
    }
    if (parameters.has("rentRule") && !parameters.get("rentRule").isNull()) {
      if (!parameters.get("rentRule").isObject()) {
        throw new IllegalArgumentException(
            label + " 的 rentRule 必须是对象: " + parameters.get("rentRule"));
      }
    }
  }

  /**
   * {@code upsertProductionOrganization} 的形状：modeId/classPositionId/organizer 必填（更新分支的
   * outputOwnership/ status 等可省略并沿用既有值，故这里不把它们当必填）；id/unitId 给了必须非空，outputOwnership/status
   * 给了必须是正确类型。
   */
  private static void requireUpsertProductionEnterpriseShape(String label, JsonNode parameters) {
    if (hasValue(parameters, "id")) {
      EconomyCommandPayloads.requireText(label, parameters, "id");
    }
    EconomyCommandPayloads.requireText(label, parameters, "modeId");
    EconomyCommandPayloads.requireText(label, parameters, "classPositionId");
    if (hasValue(parameters, "unitId")) {
      EconomyCommandPayloads.requireText(label, parameters, "unitId");
    }
    EconomyCommandPayloads.requireActor(label, parameters, "organizer");
    if (hasValue(parameters, "outputOwnership") && !parameters.get("outputOwnership").isObject()) {
      throw new IllegalArgumentException(
          label
              + " 的 outputOwnership 必须是对象（actor/household/cohort 恰给其一）: "
              + parameters.get("outputOwnership"));
    }
    if (hasValue(parameters, "status")) {
      EconomyCommandPayloads.requireText(label, parameters, "status");
    }
  }

  /** {@code upsertCandidate} 的形状：id 必填；version 可选整数；output/cycleDays/regime 在有给时做最小类型判。 */
  private static void requireUpsertCandidateShape(String label, JsonNode parameters) {
    EconomyCommandPayloads.requireText(label, parameters, "id");
    if (hasValue(parameters, "version")) {
      EconomyCommandPayloads.requireLong(label, parameters, "version");
    }
    if (hasValue(parameters, "output")) {
      EconomyCommandPayloads.requireText(label, parameters, "output");
    }
    if (hasValue(parameters, "cycleDays")) {
      EconomyCommandPayloads.requireLong(label, parameters, "cycleDays");
    }
    if (hasValue(parameters, "regime")) {
      EconomyCommandPayloads.requireText(label, parameters, "regime");
    }
  }

  private static boolean hasValue(JsonNode parameters, String field) {
    JsonNode node = parameters.get(field);
    return node != null && !node.isNull();
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

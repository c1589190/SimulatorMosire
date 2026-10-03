package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * ★★ <b>{@code economy.GmAdjust}（E6b / class-first 阶段 1–2）：GM 经济调整的窄命令</b>。载荷：
 *
 * <pre>{@code
 * {"adjustment":"setMobilityPolicy"|"setClassFirstLender"|"forgiveClassFirstDebt"
 *              |"setCollectionPolicy"|"setProductionParameters"|"levyStock"
 *              |"forgiveDebt"|"setLiquidationPolicy"
 *              |"upsertProductionMode"|"deactivateProductionMode"
 *              |"upsertClassStructure"|"upsertClassPosition"
 *              |"upsertProductionRelation"|"upsertAssetRule"
 *              |"upsertProductionOrganization"|"upsertCandidate",
 *  "parameters":{...},
 *  "reason":"..."}
 * }</pre>
 *
 * <p>★★ <b>十六条 adjustment（源状态白名单，唯一语义落点在 {@link EconomyGmAdjustments#project}）</b>：
 *
 * <ul>
 *   <li><b>class-first 原生六</b>：
 *       <ul>
 *         <li>{@code setMobilityPolicy}：{@code modeId?（缺省 = classFirst.meta.config.mode.id()）} +
 *             至少一个 {@code MobilityPolicy} 标量字段 / {@code
 *             absorptionPolicy(PROPORTIONAL|ALL_OR_NOTHING)}；只改既有 {@code mobilityPolicies}
 *             行，未给字段保持原值；{@code schema}/{@code bounds}/{@code absorptionCapByEdgePerMille}/{@code
 *             bundleTemplates} 给到即具名拒绝；policy 不存在 ⇒ 具名拒绝；
 *         <li>{@code setClassFirstLender}：{@code lenderId} + {@code interestRatePerMille}/{@code
 *             nextDueTick} 至少一项；{@code collectionPower} 给到即具名拒绝（引擎无消费点、禁止"改了不生效"）；只改既有 lender
 *             的制度参数（不动 money/goods）；lender 不存在 ⇒ 具名拒绝；
 *         <li>{@code forgiveClassFirstDebt}：{@code ownerId} + {@code counterpartyId} + {@code
 *             unit?（缺省 grain）} + {@code amount?（缺省=全额债务）}；只对称清减 {@code owner→counterparty} 与 {@code
 *             counterparty→owner} 两条镜像账户的 {@code cumulativeNet}，归零 ⇒ {@code SETTLED}；不动 {@code
 *             interestAccrued}/库存账户；找不到债务侧或镜像账户 ⇒ 具名拒绝；
 *         <li>{@code setCollectionPolicy}：{@code collectionThreshold?（≥0）}/{@code
 *             collectionTriggerRatioPerMille?（≥0）}/{@code collectionRatioPerMille?（0..1000）}/{@code
 *             landPricePerUnit?（≥1）} 至少一项，按给定字段改既有 {@code CollectionPolicy}，未给字段保持原值；{@code
 *             seizurePriority（枚举只有一个取值）}/{@code collectorClassPositionId（本阶段固定 LANDLORD）} 给到即具名拒绝；
 *             未播种/无 {@code meta.config} ⇒ 具名拒绝；
 *         <li>{@code setProductionParameters}：15 个生产/技术标量（{@code yieldPerLand(>0)}/{@code
 *             seedPerLand(≥0)}/{@code laborPerLand(>0)}/{@code toolCapacityPerTool(>0)}/{@code
 *             rentPerLand(≥0)}/{@code wagePerLabor(≥0)}/{@code baseRationPerCapita(≥0)}/{@code
 *             laborRationPerLabor(≥0)}/{@code nonEssentialNeedPerMille(≥0)}/{@code
 *             nonEssentialEfficiencyPenaltyPerMille(≥0)}/{@code
 *             loanInterestRatePerMille(≥0)}/{@code moneyPerGrain(>0)}/{@code
 *             toolPricePerUnit(≥0)}/{@code reserveTicks(≥0)}/{@code collectionIntervalTicks(≥1)}）
 *             至少一项，只改 {@code classFirst.meta.config}，未给字段保持原值；不碰
 *             mode/lender/collectionPolicy/mobilityPolicy； 未播种/无 {@code meta.config} ⇒ 具名拒绝；
 *         <li>{@code levyStock}：{@code fromClassPositionId} + {@code lenderId} + {@code
 *             unit(grain|money)} + {@code amount(整数 ≥ 1)} 四字段全必填；从既有 {@code classFirst.classPools}
 *             源池抽取粮/钱到既有 {@code classFirst.lenders} 目标账户。grain 上限 = {@code stock(GRAIN) −
 *             config.protectedGrainReserve(population, labor)}，money 上限 = {@code stock(MONEY)}；超上限
 *             ⇒ 具名拒绝并报 {@code available}（不截断）；源池按 {@code classPositionId} 找不到 / lender 找不到 / unit
 *             不认 ⇒ 具名拒绝；只改源池与目标 lender（守恒不破），其余组件逐值不变；
 *       </ul>
 *   <li><b>旧表两（仅非空 class-first 为空的世界）</b>：
 *       <ul>
 *         <li>{@code forgiveDebt}：{@code debtContractId} + 可选 {@code amount}（缺省 = 全额本金）。调用 {@code
 *             DebtContractBook.forgive} 减/清本金，<b>不碰粮/钱库存</b>；{@code amount > 本金} ⇒ 具名 {@link
 *             HandlerOutcome.Rejected}；
 *         <li>{@code setLiquidationPolicy}：{@code assetRuleId} + {@code maxLiquidatePerMille ∈
 *             [0,1000]} + {@code protectedReserve ≥ 0} + {@code priceSource(MARKET|AGREED|POLICY)}
 *             + {@code policyValuePerUnitMilli ≥ 0（非 POLICY 必须 0）} + {@code
 *             recipientRule(CREDITOR_FIRST|MARKET_FIRST)}； upsert 到 {@code liquidationPolicies}；引用的
 *             {@code AssetRule} 不存在 ⇒ 具名拒绝。★ {@code classFirst} 非空 ⇒ 二者由 {@link
 *             EconomyGmAdjustments#project} 统一具名拒绝并指路六个 class-first 原生 kind（handler 不重复这道门）。
 *       </ul>
 *   <li><b>P7 生产方式编辑（八，class-first 世界也可用；classFirst 优先分支不受影响）</b>：
 *       {@code upsertProductionMode}（{@code id,name,version?,classStructureId}；version 必须推进，classStructureId
 *       必须已存在，class-first 首建空表例外）、{@code deactivateProductionMode}（被结构/位置/组织/资产规则/变迁/质押引用
 *       ⇒ 具名拒绝）、{@code upsertClassStructure}（{@code id,modeId,positions?,defaultSharesPerMille?}；
 *       位置 upsert 并同步全局表与所有结构副本）、{@code upsertClassPosition}（{@code
 *       id,modeId,字段?,classStructureId?}）、{@code upsertProductionRelation}（{@code activity,operator?,
 *       inputSupplier?,rules?,residualOwner?,laborSource?}；operator 必须与 unit.operator 一致）、
 *       {@code upsertAssetRule}（{@code modeId,assetKind,...}；id 由 {@code AssetRuleId.idOf} 派生）、
 *       {@code upsertProductionOrganization}（引用与四档状态守卫）、{@code upsertCandidate}（按
 *       ProductionCandidate 现有字段；见 {@link EconomyGmAdjustments#project}）。八个 kind 只做形状校验，
 *       引用存在性与幂等由 {@code project} 统一判。
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
 * 的签名拿不到状态；本命令的语义对象 （债务合同 / 生产资料规则 / class-first 制度参数）不是本仓资源命名空间里的可寻址路径（economy 资源围栏以格为粒度），且本命令
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
   * ★ 本命令没有可声明的资源目标（见类注）：债务合同 / 生产资料规则 / class-first 政策、放贷方与双边账户都不是 {@code economy} 命名空间里的格键路径，且本命令
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
      case EconomyGmAdjustments.SET_MOBILITY_POLICY ->
          requireMobilityPolicyShape(label, parameters);
      case EconomyGmAdjustments.SET_CLASS_FIRST_LENDER -> requireLenderShape(label, parameters);
      case EconomyGmAdjustments.FORGIVE_CLASS_FIRST_DEBT ->
          requireForgiveClassFirstDebtShape(label, parameters);
      case EconomyGmAdjustments.SET_COLLECTION_POLICY ->
          requireCollectionPolicyShape(label, parameters);
      case EconomyGmAdjustments.SET_PRODUCTION_PARAMETERS ->
          requireProductionParametersShape(label, parameters);
      case EconomyGmAdjustments.LEVY_STOCK -> requireLevyStockShape(label, parameters);
      case EconomyGmAdjustments.UPSERT_PRODUCTION_MODE ->
          requireUpsertProductionModeShape(label, parameters);
      case EconomyGmAdjustments.DEACTIVATE_PRODUCTION_MODE ->
          EconomyCommandPayloads.requireText(label, parameters, "id");
      case EconomyGmAdjustments.UPSERT_CLASS_STRUCTURE ->
          requireUpsertClassStructureShape(label, parameters);
      case EconomyGmAdjustments.UPSERT_CLASS_POSITION ->
          requireUpsertClassPositionShape(label, parameters);
      case EconomyGmAdjustments.UPSERT_PRODUCTION_RELATION ->
          EconomyCommandPayloads.requireText(label, parameters, "activity");
      case EconomyGmAdjustments.UPSERT_ASSET_RULE ->
          requireUpsertAssetRuleShape(label, parameters);
      case EconomyGmAdjustments.UPSERT_PRODUCTION_ORGANIZATION ->
          requireUpsertProductionOrganizationShape(label, parameters);
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

  /** {@code setMobilityPolicy} 的形状：modeId 可选非空文本；至少一个标量/枚举字段；给了的字段类型必须对。 */
  private static void requireMobilityPolicyShape(String label, JsonNode parameters) {
    EconomyCommandPayloads.optionalText(label, parameters, "modeId", null);
    int given = 0;
    for (String field : EconomyGmAdjustments.MOBILITY_POLICY_LONG_FIELDS) {
      if (hasValue(parameters, field)) {
        EconomyCommandPayloads.requireLong(label, parameters, field);
        given++;
      }
    }
    if (hasValue(parameters, EconomyGmAdjustments.MOBILITY_POLICY_ENUM_FIELD)) {
      EconomyCommandPayloads.requireText(
          label, parameters, EconomyGmAdjustments.MOBILITY_POLICY_ENUM_FIELD);
      given++;
    }
    if (given == 0) {
      throw new IllegalArgumentException(label + " 至少需要给出一个可调整字段（modeId 只是定位键）");
    }
  }

  /**
   * {@code setClassFirstLender} 的形状：lenderId 必填；两个制度参数至少一项；给了的字段必须是整数；collectionPower 由 project
   * 统一拒绝。
   */
  private static void requireLenderShape(String label, JsonNode parameters) {
    EconomyCommandPayloads.requireText(label, parameters, "lenderId");
    int given = 0;
    for (String field : EconomyGmAdjustments.LENDER_FIELDS) {
      if (hasValue(parameters, field)) {
        EconomyCommandPayloads.requireLong(label, parameters, field);
        given++;
      }
    }
    if (given == 0) {
      throw new IllegalArgumentException(
          label + " 至少需要给出一个可调整字段: " + String.join(" | ", EconomyGmAdjustments.LENDER_FIELDS));
    }
  }

  /** {@code forgiveClassFirstDebt} 的形状：ownerId/counterpartyId 必填；unit/amount 可选但类型与范围要对。 */
  private static void requireForgiveClassFirstDebtShape(String label, JsonNode parameters) {
    EconomyCommandPayloads.requireText(label, parameters, "ownerId");
    EconomyCommandPayloads.requireText(label, parameters, "counterpartyId");
    EconomyCommandPayloads.optionalText(label, parameters, "unit", PilotModel.GRAIN);
    long amount = EconomyCommandPayloads.optionalLong(label, parameters, "amount", 1L);
    if (amount <= 0L) {
      throw new IllegalArgumentException(label + " 的 amount 必须 > 0: " + amount);
    }
  }

  /**
   * {@code setCollectionPolicy} 的形状：四个可调标量至少一项、给了的必须是整数；{@code seizurePriority}/{@code
   * collectorClassPositionId} 的存在性由 {@link EconomyGmAdjustments#project} 统一具名拒绝 （顺序同 {@code
   * setMobilityPolicy} 的 schema/bounds：不给"看起来接受了"）。
   */
  private static void requireCollectionPolicyShape(String label, JsonNode parameters) {
    int given = 0;
    for (String field : EconomyGmAdjustments.COLLECTION_POLICY_FIELDS) {
      if (hasValue(parameters, field)) {
        EconomyCommandPayloads.requireLong(label, parameters, field);
        given++;
      }
    }
    if (given == 0) {
      throw new IllegalArgumentException(
          label
              + " 至少需要给出一个可调整字段: "
              + String.join(" | ", EconomyGmAdjustments.COLLECTION_POLICY_FIELDS));
    }
  }

  /**
   * {@code setProductionParameters} 的形状：15 个标量至少一项、给了的必须是整数（范围由 {@link
   * EconomyGmAdjustments#project} 统一判，保证与 GM 窄写工具同一份语义）。
   */
  private static void requireProductionParametersShape(String label, JsonNode parameters) {
    int given = 0;
    for (String field : EconomyGmAdjustments.PRODUCTION_TUNING_FIELDS) {
      if (hasValue(parameters, field)) {
        EconomyCommandPayloads.requireLong(label, parameters, field);
        given++;
      }
    }
    if (given == 0) {
      throw new IllegalArgumentException(
          label
              + " 至少需要给出一个可调整字段: "
              + String.join(" | ", EconomyGmAdjustments.PRODUCTION_TUNING_FIELDS));
    }
  }

  /**
   * {@code levyStock} 的形状：四个字段全必填；文本字段非空、{@code amount} 是整数且 ≥ 1。unit 的取值白名单与 源池/lender 上限等语义由
   * {@link EconomyGmAdjustments#project} 统一判（保证与 GM 窄写工具同一份语义）。
   */
  private static void requireLevyStockShape(String label, JsonNode parameters) {
    EconomyCommandPayloads.requireText(label, parameters, "fromClassPositionId");
    EconomyCommandPayloads.requireText(label, parameters, "lenderId");
    EconomyCommandPayloads.requireText(label, parameters, "unit");
    long amount = EconomyCommandPayloads.requireLong(label, parameters, "amount");
    if (amount < 1L) {
      throw new IllegalArgumentException(label + " 的 amount 必须 >= 1: " + amount);
    }
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
  private static void requireUpsertClassPositionShape(String label, JsonNode parameters) {
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
   * {@code upsertProductionOrganization} 的形状：modeId/classPositionId/organizer 必填（更新分支的 outputOwnership/
   * status 等可省略并沿用既有值，故这里不把它们当必填）；id/unitId 给了必须非空，outputOwnership/status 给了必须是正确类型。
   */
  private static void requireUpsertProductionOrganizationShape(String label, JsonNode parameters) {
    if (hasValue(parameters, "id")) {
      EconomyCommandPayloads.requireText(label, parameters, "id");
    }
    EconomyCommandPayloads.requireText(label, parameters, "modeId");
    EconomyCommandPayloads.requireText(label, parameters, "classPositionId");
    if (hasValue(parameters, "unitId")) {
      EconomyCommandPayloads.requireText(label, parameters, "unitId");
    }
    EconomyCommandPayloads.requireActor(label, parameters, "organizer");
    if (hasValue(parameters, "outputOwnership")
        && !parameters.get("outputOwnership").isObject()) {
      throw new IllegalArgumentException(
          label + " 的 outputOwnership 必须是对象（actor/household/cohort 恰给其一）: "
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

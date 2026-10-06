package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CandidateId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.model.IndustryHexKeys;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.ModeTransition;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.Pledge;
import io.mosire.simos.economy.model.ProductionCandidate;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionMode;
import io.mosire.simos.economy.model.ProductionProcess;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RentRule;
import io.mosire.simos.economy.model.TransferRule;
import io.mosire.simos.economy.time.DebtContractBook;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>{@code economy.GmAdjust} 的纯函数项目</b>（E6b + P7）：handler（{@code simos-economy}）与 GM 窄写工具
 * {@code simos.economy.adjust}（{@code simos-app}）<b>共用同一份</b>调整语义 —— 载荷解析、白名单拒绝、前后差异与 {@link
 * EconomyChangeSet} 都在这里算一次，两处只做各自的边界折叠（handler → {@code Rejected}/`Applied`；工具 → {@code
 * BAD_REQUEST} /预览视图）。
 *
 * <p>★★ <b>十二条源状态白名单</b>（{@link #ADJUSTMENTS}）：
 *
 * <ul>
 *   <li><b>旧表（两）</b>：{@link #FORGIVE_DEBT}（{@link DebtContractBook#forgive} + {@link
 *       EconomyData#withDebtContracts}）与 {@link #SET_LIQUIDATION_POLICY}（{@link
 *       EconomyData#withLiquidationPolicies}）；
 *   <li><b>P7 生产方式编辑（八）</b>：{@link #UPSERT_PRODUCTION_MODE}/{@link
 *       #DEACTIVATE_PRODUCTION_MODE}/{@link #UPSERT_CLASS_STRUCTURE}/{@link
 *       #UPSERT_CLASS_POSITION}/ {@link #UPSERT_PRODUCTION_RELATION}/{@link
 *       #UPSERT_ASSET_RULE}/{@link #UPSERT_PRODUCTION_ORGANIZATION}/{@link #UPSERT_CANDIDATE} ——
 *       只编辑 {@code
 *       modes/classStructures/classPositions/relations/assetRules/productionOrganizations/candidates}
 *       七张 源状态表（class 两表同批落值走 {@link EconomyData#withClassStructuresAndPositions} 成对写口）。
 *   <li><b>Z1 产品产出数量覆盖（两）</b>：{@link #SET_OUTPUT_QUANTITY} / {@link #CLEAR_OUTPUT_QUANTITY} —— 只编辑
 *       {@code outputQuantityOverrides}（键 = industryId，内层键 = commodityId，值 = 商品数量/单位规模）； 本批保持
 *       GmOnly，不加 {@code CommandTargets}、不进决策令桶（§4）。
 * </ul>
 *
 * <p>★★ <b>只改源状态、只走既有写口</b>：本类全部 kind 经既有 {@code with*} 写口落在对应组件上，不搬粮/钱/商品，不新增/删除任何其它表。
 *
 * <p>★★ <b>派生读数不可直写</b>：{@code flows} / {@code demandBook} / {@code crisisSignals} / {@code
 * classStandings.consecutiveDebtStressCycles} / {@code debtCapacity} 这类派生读数一律以 {@link
 * #DERIVED_REJECTION} 具名拒绝 —— 派生读数只能由结算从源状态现算，不能从这里写进去。
 *
 * <p>★ <b>确定性</b>：同一 {@code (base, adjustment, parameters, reason, day)} ⇒ 逐字段相同的 {@link
 * Projection}。复制既有表用 {@link LinkedHashMap}（保序），id 用各稳定 ID 的 {@code parse}/{@code
 * of}（唯一拼写点），不做任何与迭代序 / 时钟 / 随机数有关的事。
 *
 * <p>★ <b>reason 的落点</b>：本类把 {@code reason} 原样带进 {@link Projection} 与旧的 {@code forgive} 写口（E4c 的
 * {@code Forgiveness} 返回里含原因）；命令载荷本身也带 {@code reason}（见 handler 与工具）。本阶段不新增持久审计组件。
 */
public final class EconomyGmAdjustments {

  /** {@code adjustment} 白名单项：减免旧表债务合同（部分/全额）。 */
  public static final String FORGIVE_DEBT = "forgiveDebt";

  /** {@code adjustment} 白名单项：按 {@code assetRuleId} upsert 旧表清算政策。 */
  public static final String SET_LIQUIDATION_POLICY = "setLiquidationPolicy";

  /** {@code adjustment} 白名单项：按 id upsert 生产方式（version 必须严格递增；classStructureId 引用完整性见 project）。 */
  public static final String UPSERT_PRODUCTION_MODE = "upsertProductionMode";

  /** {@code adjustment} 白名单项：停用一个没有被组织/候选/变迁/资产规则/质押/结构/位置引用的生产方式。 */
  public static final String DEACTIVATE_PRODUCTION_MODE = "deactivateProductionMode";

  /** {@code adjustment} 白名单项：按 id upsert 阶层结构与其内嵌位置（与全局 classPositions 逐值一致）。 */
  public static final String UPSERT_CLASS_STRUCTURE = "upsertClassStructure";

  /** {@code adjustment} 白名单项：按 id upsert 一个阶层位置（同步它在所有结构内的副本与全局表）。 */
  public static final String UPSERT_CLASS_POSITION = "upsertClassPosition";

  /** {@code adjustment} 白名单项：按 activity(=unit id) upsert 一条生产关系（operator 必须与 unit.operator 一致）。 */
  public static final String UPSERT_PRODUCTION_RELATION = "upsertProductionRelation";

  /** {@code adjustment} 白名单项：按 {@code AssetRuleId.idOf(modeId, assetKind)} upsert 一条资产规则。 */
  public static final String UPSERT_ASSET_RULE = "upsertAssetRule";

  /** {@code adjustment} 白名单项：按 id upsert 一条生产组织（引用与状态四档由构造期守卫判死）。 */
  public static final String UPSERT_PRODUCTION_ORGANIZATION = "upsertProductionOrganization";

  /** {@code adjustment} 白名单项：按 id upsert 一条候选生产方式（修订必须推进 version）。 */
  public static final String UPSERT_CANDIDATE = "upsertCandidate";

  /** ★★ Z1：{@code adjustment} 白名单项：upsert 一条产品产出数量覆盖（键 = industryId + "/" + commodityId）。 */
  public static final String SET_OUTPUT_QUANTITY = "setOutputQuantity";

  /** ★★ Z1：{@code adjustment} 白名单项：删除一条产品产出数量覆盖（回落到配方默认）。 */
  public static final String CLEAR_OUTPUT_QUANTITY = "clearOutputQuantity";

  /** 白名单外调整的统一拒绝短语（handler 折 {@code Rejected}、工具折 {@code BAD_REQUEST} 都用它）。 */
  public static final String DERIVED_REJECTION = "派生读数不可由 GM 调整工具直写";

  private static final String COMMAND = EconomyGmAdjustHandler.TYPE;

  /** ★★ Z1：{@code outputQuantityOverrides} 的组件名（投影 Change.component 的固定拼写，§4）。 */
  static final String OUTPUT_QUANTITY_OVERRIDES_COMPONENT = "outputQuantityOverrides";

  /** 十二条源状态白名单（拒绝消息与 handler 兜底共用同一顺序；唯一拼写点在各自常量）。 */
  static final List<String> ADJUSTMENTS =
      List.of(
          FORGIVE_DEBT,
          SET_LIQUIDATION_POLICY,
          UPSERT_PRODUCTION_MODE,
          DEACTIVATE_PRODUCTION_MODE,
          UPSERT_CLASS_STRUCTURE,
          UPSERT_CLASS_POSITION,
          UPSERT_PRODUCTION_RELATION,
          UPSERT_ASSET_RULE,
          UPSERT_PRODUCTION_ORGANIZATION,
          UPSERT_CANDIDATE,
          SET_OUTPUT_QUANTITY,
          CLEAR_OUTPUT_QUANTITY);

  private EconomyGmAdjustments() {}

  /**
   * 计算一次 GM 调整的项目（<b>纯函数，不写任何状态</b>）。
   *
   * @param base 当前 {@link EconomyData}（只读；不得为 null）
   * @param adjustment 调整名；白名单外一律 {@link IllegalArgumentException}（具名 {@link #DERIVED_REJECTION}）
   * @param parameters 调整参数对象（形状见十二条白名单常量）
   * @param reason 调整原因；必填非空白
   * @param day 世界当前日（只进审计摘要；不改状态）
   * @return 投影后的 {@link EconomyData}、{@link EconomyChangeSet} 与前后差异清单
   * @throws IllegalArgumentException 参数缺失/类型不对/引用不存在/白名单外（消息可直接进 {@code Rejected} / {@code
   *     BAD_REQUEST}）
   */
  public static Projection project(
      EconomyData base, String adjustment, JsonNode parameters, String reason, long day) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(parameters, "parameters");
    if (adjustment == null || adjustment.isBlank()) {
      throw new IllegalArgumentException(COMMAND + " 缺少非空文本字段: adjustment");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException(COMMAND + " 的 reason 必填且非空白");
    }
    if (!parameters.isObject()) {
      throw new IllegalArgumentException(COMMAND + " 的字段 parameters 必须是 JSON 对象: " + parameters);
    }
    if (day < 0L) {
      throw new IllegalArgumentException(COMMAND + " 的 day 不得为负: " + day);
    }
    return switch (adjustment) {
      case FORGIVE_DEBT -> forgive(base, parameters, reason, day);
      case SET_LIQUIDATION_POLICY -> setLiquidationPolicy(base, parameters, reason, day);
      case UPSERT_PRODUCTION_MODE -> upsertProductionMode(base, parameters, reason, day);
      case DEACTIVATE_PRODUCTION_MODE -> deactivateProductionMode(base, parameters, reason, day);
      case UPSERT_CLASS_STRUCTURE -> upsertClassStructure(base, parameters, reason, day);
      case UPSERT_CLASS_POSITION -> upsertClassPosition(base, parameters, reason, day);
      case UPSERT_PRODUCTION_RELATION -> upsertProductionRelation(base, parameters, reason, day);
      case UPSERT_ASSET_RULE -> upsertAssetRule(base, parameters, reason, day);
      case UPSERT_PRODUCTION_ORGANIZATION ->
          upsertProductionOrganization(base, parameters, reason, day);
      case UPSERT_CANDIDATE -> upsertCandidate(base, parameters, reason, day);
      case SET_OUTPUT_QUANTITY -> setOutputQuantity(base, parameters, reason, day);
      case CLEAR_OUTPUT_QUANTITY -> clearOutputQuantity(base, parameters, reason, day);
      default -> throw derivedRejection(adjustment);
    };
  }

  /** {@code forgiveDebt}：{@code debtContractId} + 可选 {@code amount}（缺省 = 全额本金）。 */
  private static Projection forgive(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + FORGIVE_DEBT;
    DebtContractId id =
        parseDebtId(label, EconomyCommandPayloads.requireText(label, parameters, "debtContractId"));
    DebtContract existing = base.debtContracts().get(id);
    if (existing == null) {
      throw new IllegalArgumentException(label + " 债务合同不存在: " + id.value());
    }
    if (existing.principal() <= 0L) {
      throw new IllegalArgumentException(label + " 合同本金已为 0，无可减免: " + id.value());
    }
    long amount = existing.principal();
    JsonNode amountNode = parameters.get("amount");
    if (amountNode != null && !amountNode.isNull()) {
      amount = EconomyCommandPayloads.requireLong(label, parameters, "amount");
    }
    if (amount <= 0L) {
      throw new IllegalArgumentException(label + " 的 amount 必须 > 0: " + amount);
    }
    if (amount > existing.principal()) {
      throw new IllegalArgumentException(
          label + " 的 amount 超过本金（不可下溢）：amount=" + amount + "，本金=" + existing.principal());
    }
    Map<DebtContractId, DebtContract> contracts = new LinkedHashMap<>(base.debtContracts());
    // ★ 唯一写口：只减/清本金（部分减免保留原状态、全额 ⇒ FORGIVEN），不碰粮/钱库存。
    DebtContractBook.forgive(contracts, id, amount, reason);
    EconomyData projected = base.withDebtContracts(contracts);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        FORGIVE_DEBT,
        reason,
        day,
        projected,
        changeSet,
        List.of(
            new Change("debtContracts", id.value(), existing, projected.debtContracts().get(id))));
  }

  /** {@code setLiquidationPolicy}：按 {@code assetRuleId} upsert 一条清算政策（六个字段全必填）。 */
  private static Projection setLiquidationPolicy(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + SET_LIQUIDATION_POLICY;
    AssetRuleId ruleId =
        parseAssetRuleId(
            label, EconomyCommandPayloads.requireText(label, parameters, "assetRuleId"));
    if (!base.assetRules().containsKey(ruleId)) {
      throw new IllegalArgumentException(label + " 引用的 AssetRule 不存在: " + ruleId.value());
    }
    int maxLiquidatePerMille =
        intInRange(
            label,
            "maxLiquidatePerMille",
            EconomyCommandPayloads.requireLong(label, parameters, "maxLiquidatePerMille"),
            0,
            1000);
    long protectedReserve =
        nonNegative(
            label,
            "protectedReserve",
            EconomyCommandPayloads.requireLong(label, parameters, "protectedReserve"));
    LiquidationPolicy.PriceSource priceSource =
        enumValue(
            label,
            "priceSource",
            EconomyCommandPayloads.requireText(label, parameters, "priceSource"),
            LiquidationPolicy.PriceSource.class);
    long policyValuePerUnitMilli =
        nonNegative(
            label,
            "policyValuePerUnitMilli",
            EconomyCommandPayloads.requireLong(label, parameters, "policyValuePerUnitMilli"));
    if (priceSource != LiquidationPolicy.PriceSource.POLICY && policyValuePerUnitMilli != 0L) {
      throw new IllegalArgumentException(
          label
              + " 的 policyValuePerUnitMilli 只在 POLICY 档可为非 0：priceSource="
              + priceSource
              + "，值="
              + policyValuePerUnitMilli);
    }
    LiquidationPolicy.PayeeRule recipientRule =
        enumValue(
            label,
            "recipientRule",
            EconomyCommandPayloads.requireText(label, parameters, "recipientRule"),
            LiquidationPolicy.PayeeRule.class);

    LiquidationPolicy policy =
        new LiquidationPolicy(
            ruleId,
            maxLiquidatePerMille,
            protectedReserve,
            priceSource,
            policyValuePerUnitMilli,
            recipientRule);
    Map<AssetRuleId, LiquidationPolicy> policies = new LinkedHashMap<>(base.liquidationPolicies());
    LiquidationPolicy before = policies.put(ruleId, policy);
    EconomyData projected = base.withLiquidationPolicies(policies);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    List<Change> changes =
        policy.equals(before)
            ? List.of() // 逐值相同的 upsert = 幂等 no-op（changeSet 也是 Unchanged），不报"变了"
            : List.of(new Change("liquidationPolicies", ruleId.value(), before, policy));
    return new Projection(SET_LIQUIDATION_POLICY, reason, day, projected, changeSet, changes);
  }

  // ── P7：生产方式 / 阶层结构 / 关系 / 资产规则 / 组织 / 候选的 GM 编辑 ────────────────────────────────
  //
  // ★★ 全部只改源状态、只走既有 with* 写口（class 结构+位置两表需要同批落值时走
  //   EconomyData.withClassStructuresAndPositions 这个成对写口），最终都过 EconomyChangeSet.between；
  //   派生读数（flows/crisisSignals/classStandings.* 等）仍没有写口，白名单外一律 DERIVED_REJECTION。

  /** 缺省 version 的哨兵：合法 version ≥ 1，-1 只用于"键缺省 / JSON null"。 */
  private static final int VERSION_ABSENT = -1;

  /**
   * {@code upsertProductionMode}：{@code {id,name,version?,classStructureId}}。
   *
   * <p>version 规则：新 id 缺省 = 1；既有 id 缺省 = 沿用现有 version（仅当其余字段逐值相同 ⇒ 幂等 no-op，否则要求显式推进）； 显式给时必须 ≥ 现有
   * version+1（同 version 的逐值重放仍幂等 no-op，同 version 的改值 ⇒ 具名拒绝）。 {@code classStructureId} 必须已存在（请先用
   * {@code upsertClassStructure} 创建）。
   */
  private static Projection upsertProductionMode(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + UPSERT_PRODUCTION_MODE;
    ProductionModeId id =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "id"));
    String name = EconomyCommandPayloads.requireText(label, parameters, "name");
    ClassStructureId classStructureId =
        classStructureId(
            label, EconomyCommandPayloads.requireText(label, parameters, "classStructureId"));
    ProductionMode before = base.modes().get(id);
    if (!base.classStructures().containsKey(classStructureId)) {
      throw new IllegalArgumentException(
          label
              + " 的 classStructureId 必须已存在: "
              + classStructureId.value()
              + "（请先用 "
              + UPSERT_CLASS_STRUCTURE
              + " 创建它）");
    }
    int version =
        hasValue(parameters, "version")
            ? optionalVersion(label, parameters)
            : (before == null ? 1 : before.version());
    if (version < 1) {
      throw new IllegalArgumentException(label + " 的 version 必须 ≥ 1: " + version);
    }
    ProductionMode after = new ProductionMode(id, name, version, classStructureId);
    if (after.equals(before)) {
      return noOp(UPSERT_PRODUCTION_MODE, reason, day, base);
    }
    if (before != null) {
      if (version < before.version()) {
        throw new IllegalArgumentException(
            label + " 的 version 不得倒退：当前 " + before.version() + "，收到 " + version);
      }
      if (!hasValue(parameters, "version")) {
        throw new IllegalArgumentException(
            label
                + " 修改既有 mode 必须显式给 version ≥ 现有 version+1（当前 "
                + before.version()
                + "，未给 version）；逐值相同的重放可省略 version");
      }
      if (version == before.version()) {
        throw new IllegalArgumentException(
            label
                + " 修订必须推进 version：当前 "
                + before.version()
                + "，收到同值 "
                + version
                + "（同 version 只允许逐值相同的幂等重放）");
      }
    }
    Map<ProductionModeId, ProductionMode> modes = new LinkedHashMap<>(base.modes());
    modes.put(id, after);
    EconomyData projected = base.withModes(modes);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        UPSERT_PRODUCTION_MODE,
        reason,
        day,
        projected,
        changeSet,
        List.of(new Change("modes", id.value(), before, after)));
  }

  /**
   * {@code deactivateProductionMode}：{@code {id}}。被任一跨表引用 ⇒ 具名拒绝并列出引用者： {@code
   * classStructures}/{@code classPositions}（modeId）、{@code productionOrganizations}（modeId）、 {@code
   * assetRules}（modeId）、{@code modeTransitions}（from/to）、{@code pledges}（modeId）。
   *
   * <p>★ {@code ProductionCandidate} 现有字段里<b>没有</b> modeId（见 {@link
   * ProductionCandidate}），候选与本表没有持久引用， 故删除守卫不检查它；候选的"模式关联"是 {@code regime}（由 {@code
   * upsertCandidate} 按 {@code RegimeOperators.registered()} 判）。
   */
  private static Projection deactivateProductionMode(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + DEACTIVATE_PRODUCTION_MODE;
    ProductionModeId id =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "id"));
    ProductionMode before = base.modes().get(id);
    if (before == null) {
      throw new IllegalArgumentException(label + " 的生产方式不存在: " + id.value());
    }
    List<String> structures = new ArrayList<>();
    for (ClassStructure structure : base.classStructures().values()) {
      if (structure.modeId().equals(id)) {
        structures.add(structure.id().value());
      }
    }
    List<String> positions = new ArrayList<>();
    for (ProductionRole position : base.classPositions().values()) {
      if (position.modeId().equals(id)) {
        positions.add(position.id().value());
      }
    }
    List<String> enterprises = new ArrayList<>();
    for (ProductionEnterprise enterprise : base.productionOrganizations().values()) {
      if (enterprise.modeId().equals(id)) {
        enterprises.add(enterprise.id().value());
      }
    }
    List<String> assetRules = new ArrayList<>();
    for (AssetRule rule : base.assetRules().values()) {
      if (rule.modeId().equals(id)) {
        assetRules.add(rule.id().value());
      }
    }
    List<String> transitions = new ArrayList<>();
    for (ModeTransition transition : base.modeTransitions().values()) {
      if (transition.fromModeId().equals(id)) {
        transitions.add(transition.id().value() + "(from)");
      }
      if (transition.toModeId().equals(id)) {
        transitions.add(transition.id().value() + "(to)");
      }
    }
    List<String> pledges = new ArrayList<>();
    for (Pledge pledge : base.pledges().values()) {
      if (pledge.modeId().equals(id)) {
        pledges.add(pledge.id().value());
      }
    }
    List<String> references = new ArrayList<>();
    if (!structures.isEmpty()) {
      references.add("classStructures=" + structures);
    }
    if (!positions.isEmpty()) {
      references.add("classPositions=" + positions);
    }
    if (!enterprises.isEmpty()) {
      references.add("productionOrganizations=" + enterprises);
    }
    if (!assetRules.isEmpty()) {
      references.add("assetRules=" + assetRules);
    }
    if (!transitions.isEmpty()) {
      references.add("modeTransitions=" + transitions);
    }
    if (!pledges.isEmpty()) {
      references.add("pledges=" + pledges);
    }
    if (!references.isEmpty()) {
      throw new IllegalArgumentException(
          label + " 的生产方式仍被引用，不能停用: " + id.value() + "；" + String.join("，", references));
    }
    Map<ProductionModeId, ProductionMode> modes = new LinkedHashMap<>(base.modes());
    modes.remove(id);
    EconomyData projected = base.withModes(modes);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        DEACTIVATE_PRODUCTION_MODE,
        reason,
        day,
        projected,
        changeSet,
        List.of(new Change("modes", id.value(), before, null)));
  }

  /**
   * {@code upsertClassStructure}：{@code {id,modeId,positions?,defaultSharesPerMille?}}（至少给一项）。
   *
   * <p>语义：{@code positions} 逐项 upsert（同一位置 id 的全局表与<b>所有</b>含它的结构副本同值更新；不删除未列出的位置 —— 删除会牵动
   * classStandings/classShares/enterprises 的引用，本阶段不做）；{@code defaultSharesPerMille} 给到即整体替换（给
   * {@code {}} 可清空），未给保持原值。modeId 必须存在；既有结构的 modeId 不可改（换绑要删除重建）。新结构必须至少给一个 position。
   */
  private static Projection upsertClassStructure(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + UPSERT_CLASS_STRUCTURE;
    ClassStructureId id =
        classStructureId(label, EconomyCommandPayloads.requireText(label, parameters, "id"));
    ProductionModeId modeId =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "modeId"));
    if (!base.modes().containsKey(modeId)) {
      throw new IllegalArgumentException(
          label
              + " 的 modeId 必须已存在: "
              + modeId.value()
              + "；请先 "
              + UPSERT_PRODUCTION_MODE
              + "（其 classStructureId 必须已存在）");
    }
    ClassStructure before = base.classStructures().get(id);
    if (before != null && !before.modeId().equals(modeId)) {
      throw new IllegalArgumentException(
          label
              + " 不允许修改结构的 modeId：结构="
              + id.value()
              + "，现有 modeId="
              + before.modeId()
              + "，收到="
              + modeId);
    }
    boolean positionsGiven = hasValue(parameters, "positions");
    boolean sharesGiven =
        parameters.has("defaultSharesPerMille")
            && !parameters.get("defaultSharesPerMille").isNull();
    if (sharesGiven && !parameters.get("defaultSharesPerMille").isObject()) {
      throw new IllegalArgumentException(
          label + " 的 defaultSharesPerMille 必须是对象: " + parameters.get("defaultSharesPerMille"));
    }
    if (before == null && !positionsGiven) {
      throw new IllegalArgumentException(label + " 新建 classStructure 必须给非空 positions");
    }
    if (!positionsGiven && !sharesGiven) {
      throw new IllegalArgumentException(
          label + " 至少需要给 positions 或 defaultSharesPerMille（两者都给 = 结构+份额同批更新）");
    }
    Map<ClassStructureId, ClassStructure> structures = new LinkedHashMap<>(base.classStructures());
    Map<ClassPositionId, ProductionRole> positions = new LinkedHashMap<>(base.classPositions());
    Map<ClassPositionId, ProductionRole> targetPositions =
        before == null ? new LinkedHashMap<>() : new LinkedHashMap<>(before.positions());
    if (positionsGiven) {
      JsonNode positionNodes = parameters.get("positions");
      if (!positionNodes.isArray()) {
        throw new IllegalArgumentException(label + " 的 positions 必须是数组: " + positionNodes);
      }
      if (before == null && positionNodes.isEmpty()) {
        throw new IllegalArgumentException(label + " 新建 classStructure 必须至少给一个 position");
      }
      Set<ClassPositionId> seen = new LinkedHashSet<>();
      for (JsonNode positionNode : positionNodes) {
        if (!positionNode.isObject()) {
          throw new IllegalArgumentException(label + " 的 positions 每项必须是对象: " + positionNode);
        }
        ProductionRole position = EconomyPayloads.productionRole(positionNode, modeId.value());
        if (!seen.add(position.id())) {
          throw new IllegalArgumentException(
              label + " 的 positions 里位置 id 重复: " + position.id().value());
        }
        if (!position.modeId().equals(modeId)) {
          throw new IllegalArgumentException(
              label
                  + " 的位置 modeId 必须与结构 modeId 一致：位置="
                  + position.id().value()
                  + "，位置 modeId="
                  + position.modeId()
                  + "，结构 modeId="
                  + modeId);
        }
        ProductionRole globalBefore = positions.get(position.id());
        if (globalBefore != null && !globalBefore.modeId().equals(modeId)) {
          throw new IllegalArgumentException(
              label
                  + " 不允许把既有位置改属另一个 mode：位置="
                  + position.id().value()
                  + "，现有 modeId="
                  + globalBefore.modeId()
                  + "，收到="
                  + modeId);
        }
        targetPositions.put(position.id(), position);
        // ★ 同一位置 id 在全局表只有一份值：把含它的每个结构副本都同步到新值（含本结构）。
        List<ClassStructureId> structureIds = new ArrayList<>(structures.keySet());
        for (ClassStructureId structureId : structureIds) {
          ClassStructure structure = structures.get(structureId);
          ProductionRole copy = structure.positions().get(position.id());
          if (copy == null || copy.equals(position)) {
            continue;
          }
          Map<ClassPositionId, ProductionRole> updated = new LinkedHashMap<>(structure.positions());
          updated.put(position.id(), position);
          structures.put(
              structureId,
              new ClassStructure(
                  structure.id(), structure.modeId(), updated, structure.defaultSharesPerMille()));
        }
        if (globalBefore == null || !globalBefore.equals(position)) {
          positions.put(position.id(), position);
        }
      }
    }
    Map<ClassPositionId, Long> shares;
    if (sharesGiven) {
      shares =
          EconomyPayloads.productionRoleShareMap(
              parameters.get("defaultSharesPerMille"), label + ".defaultSharesPerMille");
      for (Map.Entry<ClassPositionId, Long> entry : shares.entrySet()) {
        if (!targetPositions.containsKey(entry.getKey())) {
          throw new IllegalArgumentException(
              label
                  + " 的 defaultSharesPerMille 键必须是 resulting positions 里已有的位置: "
                  + entry.getKey().value());
        }
        if (entry.getValue() < 0L) {
          throw new IllegalArgumentException(
              label
                  + " 的 defaultSharesPerMille 不得为负："
                  + entry.getKey().value()
                  + " = "
                  + entry.getValue());
        }
      }
    } else {
      shares =
          before == null
              ? new LinkedHashMap<>()
              : new LinkedHashMap<>(before.defaultSharesPerMille());
    }
    ClassStructure after = new ClassStructure(id, modeId, targetPositions, shares);
    structures.put(id, after);
    EconomyData projected = base.withClassStructuresAndPositions(structures, positions);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    List<Change> changes = new ArrayList<>();
    for (Map.Entry<ClassPositionId, ProductionRole> entry : positions.entrySet()) {
      ProductionRole old = base.classPositions().get(entry.getKey());
      if (!Objects.equals(old, entry.getValue())) {
        changes.add(new Change("classPositions", entry.getKey().value(), old, entry.getValue()));
      }
    }
    for (Map.Entry<ClassStructureId, ClassStructure> entry : structures.entrySet()) {
      ClassStructure old = base.classStructures().get(entry.getKey());
      if (!Objects.equals(old, entry.getValue())) {
        changes.add(new Change("classStructures", entry.getKey().value(), old, entry.getValue()));
      }
    }
    if (changes.isEmpty()) {
      return noOp(UPSERT_CLASS_STRUCTURE, reason, day, base);
    }
    return new Projection(UPSERT_CLASS_STRUCTURE, reason, day, projected, changeSet, changes);
  }

  /**
   * {@code upsertClassPosition}：{@code {id,modeId,name?,relationToMeans?,laborRole?,surplusRole?,
   * ruleExtensions?,classStructureId?}}。
   *
   * <p>位置已属于某结构 ⇒ 同步更新全局表与<b>所有</b>结构内副本；位置未属于任何结构（或给了新的 classStructureId）⇒ 必须给 classStructureId
   * 以挂进该结构。modeId 必须存在，既有位置的 modeId 不可改。 未给的字段沿用既有值（新建时三个结构维与 name 必填，ruleExtensions 缺省空表）。
   */
  private static Projection upsertClassPosition(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + UPSERT_CLASS_POSITION;
    ClassPositionId id =
        classPositionId(label, EconomyCommandPayloads.requireText(label, parameters, "id"));
    ProductionModeId modeId =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "modeId"));
    if (!base.modes().containsKey(modeId)) {
      throw new IllegalArgumentException(label + " 的 modeId 必须已存在: " + modeId.value());
    }
    ProductionRole before = base.classPositions().get(id);
    if (before != null && !before.modeId().equals(modeId)) {
      throw new IllegalArgumentException(
          label
              + " 不允许修改既有位置的 modeId：位置="
              + id.value()
              + "，现有 modeId="
              + before.modeId()
              + "，收到="
              + modeId);
    }
    String name =
        hasValue(parameters, "name")
            ? EconomyCommandPayloads.requireText(label, parameters, "name")
            : requireExistingField(label, "name", before == null ? null : before.name());
    ProductionRole.RelationToMeans relationToMeans =
        optionalEnumOrExisting(
            label,
            parameters,
            "relationToMeans",
            before == null ? null : before.relationToMeans(),
            ProductionRole.RelationToMeans.class);
    ProductionRole.LaborRole laborRole =
        optionalEnumOrExisting(
            label,
            parameters,
            "laborRole",
            before == null ? null : before.laborRole(),
            ProductionRole.LaborRole.class);
    ProductionRole.SurplusRole surplusRole =
        optionalEnumOrExisting(
            label,
            parameters,
            "surplusRole",
            before == null ? null : before.surplusRole(),
            ProductionRole.SurplusRole.class);
    Map<String, String> ruleExtensions;
    if (parameters.has("ruleExtensions")) {
      JsonNode node = parameters.get("ruleExtensions");
      if (node == null || node.isNull()) {
        ruleExtensions = Map.of();
      } else {
        if (!node.isObject()) {
          throw new IllegalArgumentException(label + " 的 ruleExtensions 必须是对象: " + node);
        }
        ruleExtensions = EconomyPayloads.stringMap(node, label + ".ruleExtensions");
      }
    } else {
      ruleExtensions = before == null ? Map.of() : before.ruleExtensions();
    }
    ProductionRole after =
        new ProductionRole(
            id, modeId, name, relationToMeans, laborRole, surplusRole, ruleExtensions);

    ClassStructureId targetStructureId = null;
    if (hasValue(parameters, "classStructureId")) {
      targetStructureId =
          classStructureId(
              label, EconomyCommandPayloads.requireText(label, parameters, "classStructureId"));
    }
    Map<ClassStructureId, ClassStructure> structures = new LinkedHashMap<>(base.classStructures());
    List<ClassStructureId> containing = new ArrayList<>();
    for (ClassStructure structure : structures.values()) {
      if (structure.positions().containsKey(id)) {
        containing.add(structure.id());
      }
    }
    if (targetStructureId != null) {
      ClassStructure target = structures.get(targetStructureId);
      if (target == null) {
        throw new IllegalArgumentException(
            label + " 的 classStructureId 不存在: " + targetStructureId.value());
      }
      if (!target.modeId().equals(modeId)) {
        throw new IllegalArgumentException(
            label
                + " 的目标结构 modeId 与位置 modeId 不一致：结构="
                + targetStructureId.value()
                + "，结构 modeId="
                + target.modeId()
                + "，位置 modeId="
                + modeId);
      }
      if (!containing.contains(targetStructureId)) {
        containing.add(targetStructureId);
      }
    }
    if (containing.isEmpty()) {
      throw new IllegalArgumentException(
          label + " 的位置不属于任何 ClassStructure，必须给 classStructureId 指明挂进哪个结构: " + id.value());
    }
    for (ClassStructureId structureId : containing) {
      ClassStructure structure = structures.get(structureId);
      Map<ClassPositionId, ProductionRole> updated = new LinkedHashMap<>(structure.positions());
      updated.put(id, after);
      structures.put(
          structureId,
          new ClassStructure(
              structure.id(), structure.modeId(), updated, structure.defaultSharesPerMille()));
    }
    Map<ClassPositionId, ProductionRole> positions = new LinkedHashMap<>(base.classPositions());
    positions.put(id, after);
    EconomyData projected = base.withClassStructuresAndPositions(structures, positions);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    List<Change> changes = new ArrayList<>();
    if (!Objects.equals(before, after)) {
      changes.add(new Change("classPositions", id.value(), before, after));
    }
    for (ClassStructureId structureId : containing) {
      ClassStructure old = base.classStructures().get(structureId);
      ClassStructure next = structures.get(structureId);
      if (!Objects.equals(old, next)) {
        changes.add(new Change("classStructures", structureId.value(), old, next));
      }
    }
    if (changes.isEmpty()) {
      return noOp(UPSERT_CLASS_POSITION, reason, day, base);
    }
    return new Projection(UPSERT_CLASS_POSITION, reason, day, projected, changeSet, changes);
  }

  /**
   * {@code upsertProductionRelation}：{@code
   * {activity,operator?,inputSupplier?,rules?,residualOwner?, laborSource?}}。activity 必须对应既有
   * unit；operator（给了必须与 {@code unit.operator} 逐值相等，未给取 unit.operator ——
   * 那个值由守卫判死，是唯一合法值）。未给的可选字段沿用既有 relation。
   */
  private static Projection upsertProductionRelation(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + UPSERT_PRODUCTION_RELATION;
    ProductionUnitId activity =
        productionProcessId(
            label, EconomyCommandPayloads.requireText(label, parameters, "activity"));
    ProductionProcess unit = base.units().get(activity);
    if (unit == null) {
      throw new IllegalArgumentException(label + " 的 activity 必须对应已存在的 unit: " + activity.value());
    }
    ProductionRules before = base.relations().get(activity);
    ActorRef operator =
        hasValue(parameters, "operator")
            ? EconomyCommandPayloads.requireActor(label, parameters, "operator")
            : (before == null ? unit.operator() : before.operator());
    if (!operator.equals(unit.operator())) {
      throw new IllegalArgumentException(
          label
              + " 的 operator 必须与 unit.operator 一致：关系="
              + operator
              + "，unit="
              + unit.operator()
              + "（unit="
              + activity.value()
              + "）");
    }
    Payee inputSupplier;
    if (parameters.has("inputSupplier")) {
      JsonNode node = parameters.get("inputSupplier");
      if (node == null || node.isNull()) {
        inputSupplier = null; // ★ null/缺省语义交给 ProductionRules 构造期缺省（= operator）
      } else {
        if (!node.isObject()) {
          throw new IllegalArgumentException(label + " 的 inputSupplier 必须是对象: " + node);
        }
        inputSupplier = EconomyPayloads.recipient(node, "inputSupplier");
      }
    } else {
      inputSupplier = before == null ? null : before.inputSupplier();
    }
    List<CompensationRule> rules = new ArrayList<>();
    if (parameters.has("rules")) {
      JsonNode rulesNode = parameters.get("rules");
      if (rulesNode == null || !rulesNode.isArray()) {
        throw new IllegalArgumentException(label + " 的 rules 必须是数组: " + rulesNode);
      }
      for (JsonNode ruleNode : rulesNode) {
        if (!ruleNode.isObject()) {
          throw new IllegalArgumentException(label + " 的 rules 每项必须是对象: " + ruleNode);
        }
        rules.add(EconomyPayloads.compensationRule(ruleNode));
      }
    } else if (before != null) {
      rules.addAll(before.rules());
    }
    ActorRef residualOwner =
        hasValue(parameters, "residualOwner")
            ? EconomyCommandPayloads.requireActor(label, parameters, "residualOwner")
            : requireExistingField(
                label, "residualOwner", before == null ? null : before.residualOwner());
    LaborSource laborSource;
    if (parameters.has("laborSource")) {
      // 显式 null ⇒ null ⇒ ProductionRules 构造期缺省 SELF（清回"经营者自营"）。
      laborSource =
          EconomyCommandPayloads.optionalLaborSource(label, parameters, "laborSource", null);
    } else {
      laborSource = before == null ? null : before.laborSource();
    }
    ProductionRules after =
        new ProductionRules(activity, operator, inputSupplier, rules, residualOwner, laborSource);
    Map<ProductionUnitId, ProductionRules> relations = new LinkedHashMap<>(base.relations());
    relations.put(activity, after);
    EconomyData projected = base.withRelations(relations);
    // ★ EconomyData 会把旧档 ToCohort 受方按一一对应归一到 ToHousehold；幂等与审计读数必须取**投影里真正落下的**
    //   那一份，不能用归一前的 after（否则同一 payload 会被误报成变了）。
    ProductionRules stored = projected.relations().get(activity);
    if (stored.equals(before)) {
      return noOp(UPSERT_PRODUCTION_RELATION, reason, day, base);
    }
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        UPSERT_PRODUCTION_RELATION,
        reason,
        day,
        projected,
        changeSet,
        List.of(new Change("relations", activity.value(), before, stored)));
  }

  /**
   * {@code upsertAssetRule}：{@code {modeId,assetKind,id?,isCoreMeans?,pledgeable?,
   * liquidationPriority?,rentRule?,transferRule?}}。id 由 {@code AssetRuleId.idOf(modeId, assetKind)}
   * 确定性派生（显式给必须与派生值一致）。mode 必须存在；新建四字段（isCoreMeans/pledgeable/
   * liquidationPriority/transferRule）必填，rentRule 缺省空；既有则未给字段沿用原值（rentRule 显式 null 清空）。
   */
  private static Projection upsertAssetRule(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + UPSERT_ASSET_RULE;
    ProductionModeId modeId =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "modeId"));
    if (!base.modes().containsKey(modeId)) {
      throw new IllegalArgumentException(label + " 的 modeId 必须已存在: " + modeId.value());
    }
    io.mosire.simos.actor.api.asset.AssetKind assetKind =
        enumValue(
            label,
            "assetKind",
            EconomyCommandPayloads.requireText(label, parameters, "assetKind"),
            io.mosire.simos.actor.api.asset.AssetKind.class);
    AssetRuleId id = AssetRuleId.idOf(modeId, assetKind);
    if (parameters.hasNonNull("id")) {
      AssetRuleId declared =
          parseAssetRuleId(label, EconomyCommandPayloads.requireText(label, parameters, "id"));
      if (!declared.equals(id)) {
        throw new IllegalArgumentException(
            label
                + " 的 id 必须与 (modeId, assetKind) 派生值一致（不许手写第二份身份）：声明="
                + declared.value()
                + " 派生="
                + id.value());
      }
    }
    AssetRule before = base.assetRules().get(id);
    boolean isCoreMeans =
        booleanField(
            label, parameters, "isCoreMeans", before == null ? null : before.isCoreMeans());
    boolean pledgeable =
        booleanField(label, parameters, "pledgeable", before == null ? null : before.pledgeable());
    int liquidationPriority =
        intField(
            label,
            parameters,
            "liquidationPriority",
            before == null ? null : before.liquidationPriority());
    if (liquidationPriority < 0) {
      throw new IllegalArgumentException(
          label + " 的 liquidationPriority 不得为负: " + liquidationPriority);
    }
    Optional<RentRule> rentRule;
    if (parameters.has("rentRule")) {
      JsonNode node = parameters.get("rentRule");
      if (node == null || node.isNull()) {
        rentRule = Optional.empty();
      } else {
        if (!node.isObject()) {
          throw new IllegalArgumentException(label + " 的 rentRule 必须是对象: " + node);
        }
        rentRule = Optional.of(EconomyPayloads.rentRule(node));
      }
    } else {
      rentRule = before == null ? Optional.empty() : before.rentRule();
    }
    TransferRule transferRule;
    if (parameters.has("transferRule")) {
      JsonNode node = parameters.get("transferRule");
      if (node == null || !node.isObject()) {
        throw new IllegalArgumentException(label + " 的 transferRule 必须是对象: " + node);
      }
      transferRule = EconomyPayloads.transferRule(node);
    } else {
      transferRule =
          requireExistingField(
              label, "transferRule", before == null ? null : before.transferRule());
    }
    AssetRule after =
        new AssetRule(
            id,
            modeId,
            assetKind,
            isCoreMeans,
            pledgeable,
            liquidationPriority,
            rentRule,
            transferRule);
    if (after.equals(before)) {
      return noOp(UPSERT_ASSET_RULE, reason, day, base);
    }
    Map<AssetRuleId, AssetRule> rules = new LinkedHashMap<>(base.assetRules());
    rules.put(id, after);
    EconomyData projected = base.withAssetRules(rules);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        UPSERT_ASSET_RULE,
        reason,
        day,
        projected,
        changeSet,
        List.of(new Change("assetRules", id.value(), before, after)));
  }

  /**
   * {@code upsertProductionOrganization}：{@code {id?,modeId,classPositionId,unitId?,organizer,
   * laborSources?,assetSources?,inputSources?,outputOwnership,relationTemplateRef?,status,
   * statusReason?}}。
   *
   * <p>id 缺省 ⇒ 走 {@link ProductionOrganizationId#idOf} 的既有拼写点，要求 organizer 是 HOUSEHOLD 且有 unitId
   * （格键从 unit 的 industry id 解出）；否则必须显式给 id。mode/position/unit 引用必须存在；资产份额与家户引用在对应表非空时
   * fail-closed；ACTIVE/EXITING 必须有 unitId，SHORTAGE 必须具名 reason。
   */
  private static Projection upsertProductionOrganization(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + UPSERT_PRODUCTION_ORGANIZATION;
    ProductionModeId modeId =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "modeId"));
    if (!base.modes().containsKey(modeId)) {
      throw new IllegalArgumentException(label + " 的 modeId 必须已存在: " + modeId.value());
    }
    ClassPositionId classPositionId =
        classPositionId(
            label, EconomyCommandPayloads.requireText(label, parameters, "classPositionId"));
    ProductionRole position = base.classPositions().get(classPositionId);
    if (position == null) {
      throw new IllegalArgumentException(
          label + " 的 classPositionId 必须已存在: " + classPositionId.value());
    }
    if (!position.modeId().equals(modeId)) {
      throw new IllegalArgumentException(
          label
              + " 的 classPositionId 必须属于 modeId：位置="
              + classPositionId.value()
              + "，位置 modeId="
              + position.modeId()
              + "，组织 modeId="
              + modeId);
    }
    ActorRef organizer = EconomyCommandPayloads.requireActor(label, parameters, "organizer");
    Optional<ProductionUnitId> unitId =
        hasValue(parameters, "unitId")
            ? Optional.of(
                productionProcessId(
                    label, EconomyCommandPayloads.requireText(label, parameters, "unitId")))
            : Optional.empty();
    ProductionOrganizationId id;
    if (hasValue(parameters, "id")) {
      id =
          productionEnterpriseId(
              label, EconomyCommandPayloads.requireText(label, parameters, "id"));
    } else {
      if (organizer.kind() != io.mosire.simos.actor.api.actor.ActorKind.HOUSEHOLD) {
        throw new IllegalArgumentException(
            label
                + " 的 id 缺省派生要求 organizer.kind=HOUSEHOLD；"
                + "当前 kind="
                + organizer.kind()
                + "，请显式给 id");
      }
      if (unitId.isEmpty()) {
        throw new IllegalArgumentException(
            label + " 的 id 缺省派生要求给 unitId（格键从 unit 的 industry id 解出），请显式给 id");
      }
      ProductionProcess unitForHex = base.units().get(unitId.get());
      if (unitForHex == null) {
        throw new IllegalArgumentException(label + " 的 unitId 必须已存在: " + unitId.get().value());
      }
      String hexKey =
          IndustryHexKeys.hexKeyOf(unitForHex.industry())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          label
                              + " 的 unit.industry id 里解不出格键，无法派生 id: "
                              + unitForHex.industry().value()));
      id =
          ProductionOrganizationId.idOf(
              modeId, classPositionId, HouseholdId.parse(organizer.id()), hexKey);
    }
    ProductionEnterprise before = base.productionOrganizations().get(id);
    if (before != null && !hasValue(parameters, "unitId")) {
      unitId = before.unitId();
    }
    if (unitId.isPresent()) {
      ProductionProcess unit = base.units().get(unitId.get());
      if (unit == null) {
        throw new IllegalArgumentException(label + " 的 unitId 必须已存在: " + unitId.get().value());
      }
      if (!unit.operator().equals(organizer)) {
        throw new IllegalArgumentException(
            label
                + " 的 organizer 必须与它指名 unit 的 operator 一致：organizer="
                + organizer
                + "，unit.operator="
                + unit.operator());
      }
    }
    List<HouseholdId> laborSources =
        hasValue(parameters, "laborSources")
            ? parseHouseholdList(base, label, parameters, "laborSources")
            : (before == null ? List.of() : before.laborSources());
    List<AssetShareId> assetSources =
        hasValue(parameters, "assetSources")
            ? parseOwnershipStakeList(base, label, parameters, "assetSources", organizer)
            : (before == null ? List.of() : before.assetSources());
    List<Payee> inputSources =
        hasValue(parameters, "inputSources")
            ? parsePayeeList(label, parameters, "inputSources")
            : (before == null ? List.of() : before.inputSources());
    Payee outputOwnership =
        hasValue(parameters, "outputOwnership")
            ? EconomyPayloads.recipient(
                requireObjectNode(label, parameters, "outputOwnership"), "outputOwnership")
            : requireExistingField(
                label, "outputOwnership", before == null ? null : before.outputOwnership());
    Optional<String> relationTemplateRef;
    if (parameters.has("relationTemplateRef")) {
      JsonNode node = parameters.get("relationTemplateRef");
      relationTemplateRef =
          node == null || node.isNull()
              ? Optional.empty()
              : Optional.of(
                  EconomyCommandPayloads.requireText(label, parameters, "relationTemplateRef"));
    } else {
      relationTemplateRef = before == null ? Optional.empty() : before.relationTemplateRef();
    }
    ProductionEnterprise.Status status =
        hasValue(parameters, "status")
            ? enumValue(
                label,
                "status",
                EconomyCommandPayloads.requireText(label, parameters, "status"),
                ProductionEnterprise.Status.class)
            : requireExistingField(label, "status", before == null ? null : before.status());
    String statusReason;
    if (parameters.has("statusReason")) {
      JsonNode node = parameters.get("statusReason");
      if (node == null || node.isNull()) {
        statusReason = "";
      } else {
        if (!node.isTextual()) {
          throw new IllegalArgumentException(label + " 的 statusReason 必须是文本: " + node);
        }
        statusReason = node.asText();
      }
    } else {
      statusReason = before == null ? "" : before.statusReason();
    }
    if ((status == ProductionEnterprise.Status.ACTIVE
            || status == ProductionEnterprise.Status.EXITING)
        && unitId.isEmpty()) {
      throw new IllegalArgumentException(label + " 的 " + status + " 必须有 unitId（没有 unit 的在产/退出说不通）");
    }
    if (status == ProductionEnterprise.Status.SHORTAGE && statusReason.isBlank()) {
      throw new IllegalArgumentException(label + " 的 SHORTAGE 必须带具名 statusReason");
    }
    if (status == ProductionEnterprise.Status.ACTIVE && !statusReason.isBlank()) {
      throw new IllegalArgumentException(
          label + " 的 ACTIVE 不携带缺口原因（要写原因请用 SHORTAGE）: " + statusReason);
    }
    ProductionEnterprise after =
        new ProductionEnterprise(
            id,
            modeId,
            classPositionId,
            unitId,
            organizer,
            laborSources,
            assetSources,
            inputSources,
            outputOwnership,
            relationTemplateRef,
            status,
            statusReason);
    if (after.equals(before)) {
      return noOp(UPSERT_PRODUCTION_ORGANIZATION, reason, day, base);
    }
    Map<ProductionOrganizationId, ProductionEnterprise> enterprises =
        new LinkedHashMap<>(base.productionOrganizations());
    enterprises.put(id, after);
    EconomyData projected = base.withProductionEnterprises(enterprises);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        UPSERT_PRODUCTION_ORGANIZATION,
        reason,
        day,
        projected,
        changeSet,
        List.of(new Change("productionOrganizations", id.value(), before, after)));
  }

  /**
   * {@code upsertCandidate}：按 {@link ProductionCandidate} 现有字段 upsert（{@code id,version?,output?,
   * outputPerUnit?,inputPerUnit?,requiredAssets?,laborPerUnit?,buildDays?,cycleDays?,regime?,
   * laborSource?,acceptedRightKinds?,assetSource?,name?}）；未给字段沿用既有值，新建时 output/outputPerUnit/
   * cycleDays/regime 必填。
   *
   * <p>★★ <b>与任务书的一处如实偏离</b>：{@link ProductionCandidate} 的现有字段里<b>没有</b> modeId，候选与本表的 {@code
   * modes} 没有持久引用；它真正关联的已登记制度是 {@code regime}（{@code EconomyData} 守卫按 {@code
   * RegimeOperators.registered()} 判）。故这里按模型实现并拒绝显式 {@code modeId}（避免"看起来记了"）， 详见交付报告。
   */
  private static Projection upsertCandidate(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + UPSERT_CANDIDATE;
    CandidateId id =
        candidateId(label, EconomyCommandPayloads.requireText(label, parameters, "id"));
    if (parameters.has("modeId")) {
      throw new IllegalArgumentException(
          label
              + " 不接受 modeId：ProductionCandidate 的现有字段不含 modeId，候选与 modes 表没有持久引用；"
              + "请改给 regime（必须是 RegimeOperators 已登记的制度）");
    }
    ProductionCandidate before = base.candidates().get(id);
    boolean versionGiven = hasValue(parameters, "version");
    int version =
        versionGiven ? optionalVersion(label, parameters) : (before == null ? 1 : before.version());
    if (version < 1) {
      throw new IllegalArgumentException(label + " 的 version 必须 ≥ 1: " + version);
    }
    CommodityId output =
        hasValue(parameters, "output")
            ? CommodityId.parse(EconomyCommandPayloads.requireText(label, parameters, "output"))
            : requireExistingField(label, "output", before == null ? null : before.output());
    Map<CommodityId, Long> outputPerUnit;
    if (parameters.has("outputPerUnit")) {
      outputPerUnit =
          EconomyCommandPayloads.optionalCommodityMap(label, parameters, "outputPerUnit", true);
    } else {
      outputPerUnit = before == null ? Map.of() : before.outputPerUnit();
    }
    if (outputPerUnit.isEmpty()) {
      throw new IllegalArgumentException(label + " 的 outputPerUnit 不得为空");
    }
    if (!outputPerUnit.containsKey(output)) {
      throw new IllegalArgumentException(
          label + " 的 output 必须出现在 outputPerUnit 的键里: " + output.value() + "（主产出不许另写一份）");
    }
    Map<CommodityId, Long> inputPerUnit =
        parameters.has("inputPerUnit")
            ? EconomyCommandPayloads.optionalCommodityMap(label, parameters, "inputPerUnit", false)
            : (before == null ? Map.of() : before.inputPerUnit());
    Map<io.mosire.simos.actor.api.asset.AssetKind, Long> requiredAssets =
        parameters.has("requiredAssets")
            ? EconomyCommandPayloads.optionalAssetMap(label, parameters, "requiredAssets")
            : (before == null ? Map.of() : before.requiredAssets());
    long laborPerUnit =
        EconomyCommandPayloads.optionalLong(
            label, parameters, "laborPerUnit", before == null ? 0L : before.laborPerUnit());
    if (laborPerUnit < 0L) {
      throw new IllegalArgumentException(label + " 的 laborPerUnit 不得为负: " + laborPerUnit);
    }
    long buildDays =
        EconomyCommandPayloads.optionalLong(
            label, parameters, "buildDays", before == null ? 0L : before.buildDays());
    if (buildDays < 0L) {
      throw new IllegalArgumentException(label + " 的 buildDays 不得为负: " + buildDays);
    }
    if (before == null && !hasValue(parameters, "cycleDays")) {
      throw new IllegalArgumentException(label + " 新建候选缺少必填字段: cycleDays");
    }
    long cycleDays =
        EconomyCommandPayloads.optionalLong(
            label, parameters, "cycleDays", before == null ? 1L : before.cycleDays());
    if (cycleDays < 1L) {
      throw new IllegalArgumentException(label + " 的 cycleDays 必须 ≥ 1: " + cycleDays);
    }
    RegimeId regime =
        hasValue(parameters, "regime")
            ? RegimeId.parse(EconomyCommandPayloads.requireText(label, parameters, "regime"))
            : requireExistingField(label, "regime", before == null ? null : before.regime());
    if (!RegimeOperators.registered().containsKey(regime.value())) {
      throw new IllegalArgumentException(
          label
              + " 的 regime 未登记（候选进入采用算法时无法推导默认经营主体/关系）: "
              + regime.value()
              + "；已登记: "
              + RegimeOperators.registered().keySet());
    }
    LaborSource laborSource =
        EconomyCommandPayloads.optionalLaborSource(
            label,
            parameters,
            "laborSource",
            before == null ? LaborSource.SELF : before.laborSource());
    Set<OwnershipStake.RightKind> acceptedRightKinds =
        parameters.has("acceptedRightKinds")
            ? EconomyCommandPayloads.optionalRightKinds(label, parameters, "acceptedRightKinds")
            : (before == null ? Set.of() : before.acceptedRightKinds());
    Optional<ActorRef> assetSource;
    if (parameters.has("assetSource")) {
      assetSource = EconomyCommandPayloads.optionalActor(label, parameters, "assetSource");
    } else {
      assetSource = before == null ? Optional.empty() : before.assetSource();
    }
    String name =
        hasValue(parameters, "name")
            ? EconomyCommandPayloads.requireText(label, parameters, "name")
            : (before == null ? id.value() : before.name());
    ProductionCandidate after =
        new ProductionCandidate(
            id,
            version,
            output,
            outputPerUnit,
            inputPerUnit,
            requiredAssets,
            laborPerUnit,
            buildDays,
            cycleDays,
            regime,
            laborSource,
            acceptedRightKinds,
            assetSource,
            name);
    if (after.equals(before)) {
      return noOp(UPSERT_CANDIDATE, reason, day, base);
    }
    if (before != null) {
      if (version < before.version()) {
        throw new IllegalArgumentException(
            label + " 的 version 不得倒退：当前 " + before.version() + "，收到 " + version);
      }
      if (!versionGiven) {
        throw new IllegalArgumentException(
            label
                + " 修改既有候选必须显式给 version ≥ 现有 version+1（当前 "
                + before.version()
                + "，未给 version）；逐值相同的重放可省略 version");
      }
      if (version == before.version()) {
        throw new IllegalArgumentException(
            label
                + " 修订必须推进 version：当前 "
                + before.version()
                + "，收到同值 "
                + version
                + "（同 version 只允许逐值相同的幂等重放）");
      }
    }
    Map<CandidateId, ProductionCandidate> candidates = new LinkedHashMap<>(base.candidates());
    candidates.put(id, after);
    EconomyData projected = base.withCandidates(candidates);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        UPSERT_CANDIDATE,
        reason,
        day,
        projected,
        changeSet,
        List.of(new Change("candidates", id.value(), before, after)));
  }

  // ── Z1：产品产出数量覆盖（GM 唯一可改项；§3.1/§4）────────────────────────────────────────

  /**
   * ★★ {@code setOutputQuantity}：{@code {industryId,commodityId,quantity}} —— upsert 一条覆盖（值 =
   * 商品数量/单位规模）。
   *
   * <p>具名拒绝：{@code INDUSTRY_NOT_FOUND} / {@code COMMODITY_NOT_IN_RECIPE} / {@code
   * QUANTITY_OUT_OF_RANGE}（含缺失、非整数）。 投影组件固定 {@code outputQuantityOverrides}、{@code keyId =
   * industryId + "/" + commodityId}（§4）。
   */
  private static Projection setOutputQuantity(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + SET_OUTPUT_QUANTITY;
    IndustryId industryId = outputIndustryId(label, parameters);
    Industry industry = requireOutputIndustry(base, label, industryId);
    CommodityId commodityId = outputCommodityId(label, parameters, industry);
    long quantity = outputQuantity(label, parameters);
    Long before = overrideQuantity(base.outputQuantityOverrides(), industryId, commodityId);
    Map<IndustryId, Map<CommodityId, Long>> overrides =
        copyOutputQuantityOverrides(base.outputQuantityOverrides());
    overrides
        .computeIfAbsent(industryId, ignored -> new LinkedHashMap<>())
        .put(commodityId, quantity);
    EconomyData projected = base.withOutputQuantityOverrides(overrides);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        SET_OUTPUT_QUANTITY,
        reason,
        day,
        projected,
        changeSet,
        List.of(
            new Change(
                OUTPUT_QUANTITY_OVERRIDES_COMPONENT,
                outputQuantityKeyId(industryId, commodityId),
                before,
                quantity)));
  }

  /**
   * ★★ {@code clearOutputQuantity}：{@code {industryId,commodityId}} —— 删除一条覆盖（回配方默认）。
   *
   * <p>具名拒绝：{@code INDUSTRY_NOT_FOUND} / {@code COMMODITY_NOT_IN_RECIPE} / {@code
   * NO_OVERRIDE_TO_CLEAR}（不做静默幂等，防错字）。
   */
  private static Projection clearOutputQuantity(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + CLEAR_OUTPUT_QUANTITY;
    IndustryId industryId = outputIndustryId(label, parameters);
    Industry industry = requireOutputIndustry(base, label, industryId);
    CommodityId commodityId = outputCommodityId(label, parameters, industry);
    Long before = overrideQuantity(base.outputQuantityOverrides(), industryId, commodityId);
    if (before == null) {
      throw new IllegalArgumentException(
          label
              + " NO_OVERRIDE_TO_CLEAR: 该产业/商品没有既有覆盖: "
              + industryId.value()
              + "/"
              + commodityId.value());
    }
    Map<IndustryId, Map<CommodityId, Long>> overrides =
        copyOutputQuantityOverrides(base.outputQuantityOverrides());
    Map<CommodityId, Long> line = overrides.get(industryId);
    line.remove(commodityId);
    if (line.isEmpty()) {
      overrides.remove(industryId); // ★ 空内层不留残余：覆盖表"只在 GM 显式改过时存在"。
    }
    EconomyData projected = base.withOutputQuantityOverrides(overrides);
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        CLEAR_OUTPUT_QUANTITY,
        reason,
        day,
        projected,
        changeSet,
        List.of(
            new Change(
                OUTPUT_QUANTITY_OVERRIDES_COMPONENT,
                outputQuantityKeyId(industryId, commodityId),
                before,
                null)));
  }

  /** {@code industryId} 必填非空文本 ⇒ 稳定 id（畸形 ⇒ 具名拒绝）。 */
  private static IndustryId outputIndustryId(String label, JsonNode parameters) {
    String text = EconomyCommandPayloads.requireText(label, parameters, "industryId");
    try {
      return IndustryId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 industryId 非法: " + e.getMessage());
    }
  }

  /** {@code industryId} 必须存在于 {@code industries} ⇒ {@code INDUSTRY_NOT_FOUND}。 */
  private static Industry requireOutputIndustry(
      EconomyData base, String label, IndustryId industryId) {
    Industry industry = base.industries().get(industryId);
    if (industry == null) {
      throw new IllegalArgumentException(
          label + " INDUSTRY_NOT_FOUND: 产业不存在: " + industryId.value());
    }
    return industry;
  }

  /**
   * {@code commodityId} 必须是该产业 {@code recipe().outputPerUnit()} 的键 ⇒ {@code
   * COMMODITY_NOT_IN_RECIPE}。
   */
  private static CommodityId outputCommodityId(
      String label, JsonNode parameters, Industry industry) {
    String text = EconomyCommandPayloads.requireText(label, parameters, "commodityId");
    CommodityId commodityId;
    try {
      commodityId = CommodityId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 commodityId 非法: " + e.getMessage());
    }
    if (!industry.recipe().outputPerUnit().containsKey(commodityId)) {
      throw new IllegalArgumentException(
          label
              + " COMMODITY_NOT_IN_RECIPE: "
              + commodityId.value()
              + " 不在产业 "
              + industry.id().value()
              + " 的配方产出键里");
    }
    return commodityId;
  }

  /**
   * {@code quantity} 缺失/非整数/&lt;0/&gt;{@code MAX_OUTPUT_QUANTITY} ⇒ 一律 {@code
   * QUANTITY_OUT_OF_RANGE}（§4）。
   */
  private static long outputQuantity(String label, JsonNode parameters) {
    JsonNode node = parameters.get("quantity");
    if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new IllegalArgumentException(
          label
              + " QUANTITY_OUT_OF_RANGE: quantity 必须是 [0, "
              + EconomyData.MAX_OUTPUT_QUANTITY
              + "] 的整数: "
              + node);
    }
    long quantity = node.asLong();
    if (quantity < 0L || quantity > EconomyData.MAX_OUTPUT_QUANTITY) {
      throw new IllegalArgumentException(
          label
              + " QUANTITY_OUT_OF_RANGE: quantity 必须 ∈ [0, "
              + EconomyData.MAX_OUTPUT_QUANTITY
              + "]: "
              + quantity);
    }
    return quantity;
  }

  /** 投影 Change 的固定 keyId 拼写点（§4：{@code industryId + "/" + commodityId}）。 */
  private static String outputQuantityKeyId(IndustryId industryId, CommodityId commodityId) {
    return industryId.value() + "/" + commodityId.value();
  }

  /** 读现有覆盖（缺产业/缺商品 ⇒ null）。 */
  private static Long overrideQuantity(
      Map<IndustryId, Map<CommodityId, Long>> overrides,
      IndustryId industryId,
      CommodityId commodityId) {
    Map<CommodityId, Long> line = overrides.get(industryId);
    return line == null ? null : line.get(commodityId);
  }

  /** 覆盖表的可变深拷贝（外层 + 内层都保序；构造器会再冻一次）。 */
  private static Map<IndustryId, Map<CommodityId, Long>> copyOutputQuantityOverrides(
      Map<IndustryId, Map<CommodityId, Long>> source) {
    Map<IndustryId, Map<CommodityId, Long>> copy = new LinkedHashMap<>();
    for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry : source.entrySet()) {
      copy.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
    }
    return copy;
  }

  // ── P7 形状/解析助手 ──────────────────────────────────────────────────────────────────────

  /** 幂等 no-op 的 Projection：投影就是 base、changeSet Unchanged、不报任何 Change。 */
  private static Projection noOp(String adjustment, String reason, long day, EconomyData base) {
    return new Projection(
        adjustment, reason, day, base, EconomyChangeSet.between(base, base), List.of());
  }

  /** 必填 version（JSON 整数）；仅由调用方在 hasValue 后调用。 */
  private static int optionalVersion(String label, JsonNode parameters) {
    int value = EconomyCommandPayloads.optionalInt(label, parameters, "version", VERSION_ABSENT);
    if (value == VERSION_ABSENT) {
      throw new IllegalArgumentException(label + " 的 version 必须是整数: " + parameters);
    }
    return value;
  }

  /** 缺失即具名拒绝的既有字段取值（更新分支的 fallback）。 */
  private static <T> T requireExistingField(String label, String field, T fallback) {
    if (fallback == null) {
      throw new IllegalArgumentException(label + " 新建对象缺少必填字段: " + field + "（既有对象更新时可省略）");
    }
    return fallback;
  }

  /** 可选枚举：给了 ⇒ 词表解析；没给 ⇒ fallback（null 则具名拒绝）。 */
  private static <E extends Enum<E>> E optionalEnumOrExisting(
      String label, JsonNode parameters, String field, E fallback, Class<E> type) {
    if (hasValue(parameters, field)) {
      return enumValue(
          label, field, EconomyCommandPayloads.requireText(label, parameters, field), type);
    }
    return requireExistingField(label, field, fallback);
  }

  /** 可选布尔：给了 ⇒ 必须是 JSON 布尔；没给 ⇒ fallback（null 则具名拒绝）。 */
  private static boolean booleanField(
      String label, JsonNode parameters, String field, Boolean fallback) {
    if (hasValue(parameters, field)) {
      JsonNode node = parameters.get(field);
      if (!node.isBoolean()) {
        throw new IllegalArgumentException(label + " 的 " + field + " 必须是布尔值: " + node);
      }
      return node.asBoolean();
    }
    if (fallback == null) {
      throw new IllegalArgumentException(label + " 新建对象缺少必填字段: " + field);
    }
    return fallback;
  }

  /** 可选 int：给了 ⇒ 整数且 ∈ int；没给 ⇒ fallback（null 则具名拒绝）。 */
  private static int intField(String label, JsonNode parameters, String field, Integer fallback) {
    if (hasValue(parameters, field)) {
      long value = EconomyCommandPayloads.requireLong(label, parameters, field);
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        throw new IllegalArgumentException(label + " 的 " + field + " 超出 int 范围: " + value);
      }
      return (int) value;
    }
    if (fallback == null) {
      throw new IllegalArgumentException(label + " 新建对象缺少必填字段: " + field);
    }
    return fallback;
  }

  private static JsonNode requireObjectNode(String label, JsonNode parameters, String field) {
    JsonNode node = parameters.get(field);
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException(label + " 的 " + field + " 必须是对象: " + node);
    }
    return node;
  }

  private static List<HouseholdId> parseHouseholdList(
      EconomyData base, String label, JsonNode parameters, String field) {
    JsonNode node = requireArrayNode(label, parameters, field);
    List<HouseholdId> out = new ArrayList<>();
    Set<HouseholdId> seen = new LinkedHashSet<>();
    for (JsonNode element : node) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException(label + " 的 " + field + " 每项必须是非空文本: " + element);
      }
      HouseholdId household = householdId(label, element.asText());
      if (!seen.add(household)) {
        throw new IllegalArgumentException(label + " 的 " + field + " 含重复家户: " + household.value());
      }
      if (!base.classes().isEmpty() && !base.classes().containsKey(household)) {
        throw new IllegalArgumentException(
            label + " 的 " + field + " 指名的家户不存在: " + household.value());
      }
      out.add(household);
    }
    return List.copyOf(out);
  }

  private static List<AssetShareId> parseOwnershipStakeList(
      EconomyData base, String label, JsonNode parameters, String field, ActorRef organizer) {
    JsonNode node = requireArrayNode(label, parameters, field);
    List<AssetShareId> out = new ArrayList<>();
    Set<AssetShareId> seen = new LinkedHashSet<>();
    for (JsonNode element : node) {
      if (!element.isTextual() || element.asText().isBlank()) {
        throw new IllegalArgumentException(label + " 的 " + field + " 每项必须是非空文本: " + element);
      }
      AssetShareId shareId = assetShareId(label, element.asText());
      if (!seen.add(shareId)) {
        throw new IllegalArgumentException(label + " 的 " + field + " 含重复份额: " + shareId.value());
      }
      if (!base.assetShares().isEmpty()) {
        OwnershipStake share = base.assetShares().get(shareId);
        if (share == null) {
          throw new IllegalArgumentException(
              label + " 的 " + field + " 指名的资产份额不存在: " + shareId.value());
        }
        if (!share.operator().equals(organizer)) {
          throw new IllegalArgumentException(
              label
                  + " 的 "
                  + field
                  + " 必须由 organizer 经营：份额="
                  + shareId.value()
                  + "，份额 operator="
                  + share.operator()
                  + "，organizer="
                  + organizer);
        }
      }
      out.add(shareId);
    }
    return List.copyOf(out);
  }

  private static List<Payee> parsePayeeList(String label, JsonNode parameters, String field) {
    JsonNode node = requireArrayNode(label, parameters, field);
    List<Payee> out = new ArrayList<>();
    for (JsonNode element : node) {
      if (!element.isObject()) {
        throw new IllegalArgumentException(label + " 的 " + field + " 每项必须是对象: " + element);
      }
      out.add(EconomyPayloads.recipient(element, field));
    }
    return List.copyOf(out);
  }

  private static JsonNode requireArrayNode(String label, JsonNode parameters, String field) {
    JsonNode node = parameters.get(field);
    if (node == null || !node.isArray()) {
      throw new IllegalArgumentException(label + " 的 " + field + " 必须是数组: " + node);
    }
    return node;
  }

  private static ProductionModeId productionModeId(String label, String text) {
    try {
      return ProductionModeId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 modeId 非法: " + e.getMessage());
    }
  }

  private static ClassStructureId classStructureId(String label, String text) {
    try {
      return ClassStructureId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 classStructureId 非法: " + e.getMessage());
    }
  }

  private static ClassPositionId classPositionId(String label, String text) {
    try {
      return ClassPositionId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 classPositionId 非法: " + e.getMessage());
    }
  }

  private static ProductionOrganizationId productionEnterpriseId(String label, String text) {
    try {
      return ProductionOrganizationId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 organizationId 非法: " + e.getMessage());
    }
  }

  private static ProductionUnitId productionProcessId(String label, String text) {
    try {
      return ProductionUnitId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 activity/unitId 非法: " + e.getMessage());
    }
  }

  private static HouseholdId householdId(String label, String text) {
    try {
      return HouseholdId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 householdId 非法: " + e.getMessage());
    }
  }

  private static AssetShareId assetShareId(String label, String text) {
    try {
      return AssetShareId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 assetShareId 非法: " + e.getMessage());
    }
  }

  private static CandidateId candidateId(String label, String text) {
    try {
      return CandidateId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 candidateId 非法: " + e.getMessage());
    }
  }

  private static boolean hasValue(JsonNode parameters, String field) {
    JsonNode node = parameters.get(field);
    return node != null && !node.isNull();
  }

  private static IllegalArgumentException derivedRejection(String adjustment) {
    return new IllegalArgumentException(
        DERIVED_REJECTION
            + ": "
            + adjustment
            + "（"
            + COMMAND
            + " 只允许源状态调整："
            + String.join(" | ", ADJUSTMENTS)
            + "）");
  }

  private static DebtContractId parseDebtId(String label, String text) {
    try {
      return DebtContractId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 debtContractId 非法: " + e.getMessage());
    }
  }

  private static AssetRuleId parseAssetRuleId(String label, String text) {
    try {
      return AssetRuleId.parse(text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 assetRuleId 非法: " + e.getMessage());
    }
  }

  private static int intInRange(String label, String field, long value, int min, int max) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          label + " 的 " + field + " 必须 ∈ [" + min + ", " + max + "]: " + value);
    }
    return (int) value;
  }

  private static long nonNegative(String label, String field, long value) {
    if (value < 0L) {
      throw new IllegalArgumentException(label + " 的 " + field + " 不得为负: " + value);
    }
    return value;
  }

  private static <E extends Enum<E>> E enumValue(
      String label, String field, String text, Class<E> type) {
    try {
      return Enum.valueOf(type, text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          label
              + " 的 "
              + field
              + " 未知: "
              + text
              + "；合法值: "
              + Arrays.toString(type.getEnumConstants()));
    }
  }

  /**
   * 一次调整的项目：**投影后的状态 + 完整变更集 + 前后差异**。
   *
   * <p>★ {@code projected} 只服务预览/差异（工具<b>不得</b>把它写回任何地方）；落盘一律由 handler 把 {@link #changeSet} 交给
   * Core，走 {@code Command → ChangeSet → Revision}。
   *
   * @param adjustment 调整名（白名单项）
   * @param reason 调整原因（原样，必填非空白）
   * @param day 世界当前日
   * @param projected 投影后的完整 {@link EconomyData}
   * @param changeSet {@code between(base, projected)}（handler 的落盘载荷）
   * @param changes 逐键前后差异（工具预览渲染用；本次调整实际碰过的源状态键）
   */
  public record Projection(
      String adjustment,
      String reason,
      long day,
      EconomyData projected,
      EconomyChangeSet changeSet,
      List<Change> changes) {

    public Projection {
      Objects.requireNonNull(adjustment, "adjustment");
      Objects.requireNonNull(reason, "reason");
      Objects.requireNonNull(projected, "projected");
      Objects.requireNonNull(changeSet, "changeSet");
      changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }
  }

  /**
   * 一个稳定键的前后差异。
   *
   * @param component 源状态组件名（如 {@code debtContracts} / {@code liquidationPolicies} / {@code
   *     classStructures}）
   * @param keyId 该组件内的稳定键（规范串）
   * @param before 调整前的值；新增时为 {@code null}
   * @param after 调整后的值
   */
  public record Change(String component, String keyId, Object before, Object after) {

    public Change {
      Objects.requireNonNull(component, "component");
      Objects.requireNonNull(keyId, "keyId");
    }
  }
}

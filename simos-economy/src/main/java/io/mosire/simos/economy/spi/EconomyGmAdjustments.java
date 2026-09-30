package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.ClassFirstAccountId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.ExternalLenderId;
import io.mosire.simos.economy.api.id.MobilityPolicyId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.classfirst.ClassFirstAccount;
import io.mosire.simos.economy.classfirst.ClassFirstMeta;
import io.mosire.simos.economy.classfirst.ClassFirstState;
import io.mosire.simos.economy.classfirst.MobilityPolicy;
import io.mosire.simos.economy.classfirst.PilotConfig;
import io.mosire.simos.economy.classfirst.PilotModel;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.time.DebtContractBook;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>{@code economy.GmAdjust} 的纯函数项目</b>（E6b / class-first 阶段 1–2）：handler（{@code
 * simos-economy}）与 GM 窄写工具 {@code simos.economy.adjust}（{@code simos-app}）<b>共用同一份</b>调整语义 ——
 * 载荷解析、白名单拒绝、前后差异与 {@link EconomyChangeSet} 都在这里算一次，两处只做各自的边界折叠（handler → {@code
 * Rejected}/`Applied`；工具 → {@code BAD_REQUEST} /预览视图）。
 *
 * <p>★★ <b>七条源状态白名单</b>（{@link #ADJUSTMENTS}）：
 *
 * <ul>
 *   <li><b>class-first 原生（五）</b>：{@link #SET_MOBILITY_POLICY} 按给定字段 upsert 既有 {@code
 *       mobilityPolicies} 行（只改 {@link MobilityPolicy} 的标量字段 + {@code absorptionPolicy}；{@code
 *       schema}/{@code bounds} 两个 record 组件与 {@code absorptionCapByEdgePerMille}/{@code
 *       bundleTemplates} 两张嵌套表给到即具名拒绝）； {@link #SET_CLASS_FIRST_LENDER} 只改既有 {@link
 *       PilotModel.Lender} 的三个制度参数（不动 money/goods）；{@link #FORGIVE_CLASS_FIRST_DEBT} 对称清减既有 {@code
 *       owner→counterparty} / {@code counterparty→owner} 两条镜像账户 的 {@code cumulativeNet}；{@link
 *       #SET_COLLECTION_POLICY} 按给定标量改既有 {@link PilotModel.CollectionPolicy} 的四个可调字段（{@code
 *       seizurePriority}/{@code collectorClassPositionId} 给到即具名拒绝）；{@link
 *       #SET_PRODUCTION_PARAMETERS} 从 {@link PilotConfig#currentTuning()} 起步、按给定字段改 {@code
 *       meta.config} 的 15 个生产/技术标量；
 *   <li><b>旧表（两，仅非 class-first 世界）</b>：{@link #FORGIVE_DEBT}（{@link DebtContractBook#forgive} +
 *       {@link EconomyData#withDebtContracts}）与 {@link #SET_LIQUIDATION_POLICY}（{@link
 *       EconomyData#withLiquidationPolicies}）。{@link EconomyData#classFirst()} 非空 ⇒ 二者具名拒绝并指路
 *       class-first 原生 kind —— class-first 世界不读 {@code debtContracts}/{@code
 *       liquidationPolicies}，改旧表没有结算路径；{@code classFirst} 为空（旧档 / 尚未播种）⇒ 现有行为逐字不变。
 * </ul>
 *
 * <p>★★ <b>只改源状态、只走既有写口</b>：class-first 五个 kind 只经 {@link ClassFirstState} 的 {@code
 * withMobilityPolicies}/{@code withLenders}/{@code withAccounts}/{@code withMeta} 纯 copy-with 与
 * {@link EconomyData#withClassFirst} 落值。其中 {@link #SET_COLLECTION_POLICY}/{@link
 * #SET_PRODUCTION_PARAMETERS} 只改 {@code meta.config}（引擎 {@code restore} 直接读的源参数）：前者只改 {@code
 * collectionPolicy}，后者只改 15 个标量；两者都不碰 {@code mode}（无调整口）与 {@code lender}/{@code
 * mobilityPolicy}（各有专属 kind），也不碰其余状态表。 旧两 kind 仍只调用原有写口；任何调整都不碰
 * totals/conservation/pools/classFlowEvents 以及 {@code meta} 的累计读数/托管/初始基数等派生量，也不搬粮/钱/库存。
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

  /** {@code adjustment} 白名单项：减免旧表债务合同（部分/全额；仅非 class-first 世界）。 */
  public static final String FORGIVE_DEBT = "forgiveDebt";

  /** {@code adjustment} 白名单项：按 {@code assetRuleId} upsert 旧表清算政策（仅非 class-first 世界）。 */
  public static final String SET_LIQUIDATION_POLICY = "setLiquidationPolicy";

  /** {@code adjustment} 白名单项：按给定字段 upsert 既有 mode 的 {@code MobilityPolicy}（class-first 原生）。 */
  public static final String SET_MOBILITY_POLICY = "setMobilityPolicy";

  /** {@code adjustment} 白名单项：修改既有外部放贷主体的三个制度参数（class-first 原生）。 */
  public static final String SET_CLASS_FIRST_LENDER = "setClassFirstLender";

  /** {@code adjustment} 白名单项：对称清减既有双边账户的债务/债权净额（class-first 原生）。 */
  public static final String FORGIVE_CLASS_FIRST_DEBT = "forgiveClassFirstDebt";

  /** {@code adjustment} 白名单项：按给定标量改既有催收政策（class-first 原生）。 */
  public static final String SET_COLLECTION_POLICY = "setCollectionPolicy";

  /** {@code adjustment} 白名单项：按给定标量改 {@code meta.config} 的 15 个生产/技术参数（class-first 原生）。 */
  public static final String SET_PRODUCTION_PARAMETERS = "setProductionParameters";

  /** 白名单外调整的统一拒绝短语（handler 折 {@code Rejected}、工具折 {@code BAD_REQUEST} 都用它）。 */
  public static final String DERIVED_REJECTION = "派生读数不可由 GM 调整工具直写";

  private static final String COMMAND = EconomyGmAdjustHandler.TYPE;

  /** 七条源状态白名单（拒绝消息与 handler 兜底共用同一顺序；唯一拼写点在各自常量）。 */
  static final List<String> ADJUSTMENTS =
      List.of(
          FORGIVE_DEBT,
          SET_LIQUIDATION_POLICY,
          SET_MOBILITY_POLICY,
          SET_CLASS_FIRST_LENDER,
          FORGIVE_CLASS_FIRST_DEBT,
          SET_COLLECTION_POLICY,
          SET_PRODUCTION_PARAMETERS);

  /** {@code setMobilityPolicy} 可调整的 17 个 long 标量字段（与 {@link MobilityPolicy} 逐项对齐）。 */
  static final List<String> MOBILITY_POLICY_LONG_FIELDS =
      List.of(
          "gamma",
          "upMinPerMillePerYear",
          "upMaxPerMillePerYear",
          "downMinPerMillePerYear",
          "downMaxPerMillePerYear",
          "upCapPerMillePerTick",
          "downCapPerMillePerTick",
          "leaseAvailabilityPerMille",
          "initialLandForSale",
          "ticksPerYear",
          "leasePerCapitaMilli",
          "landPurchasePerCapitaMilli",
          "absorptionCapTenantPerMille",
          "absorptionCapMiddlePerMille",
          "absorptionCapLandlordPerMille",
          "absorptionCapLaborerPerMille",
          "extractionTaxPerMille");

  /** {@code setMobilityPolicy} 可调整的枚举字段（与 17 个 long 字段合起来是"至少给一个"的全集）。 */
  static final String MOBILITY_POLICY_ENUM_FIELD = "absorptionPolicy";

  /** {@code setMobilityPolicy} 本阶段只读、给到即具名拒绝的字段（两个 record 组件 + 两张嵌套表）。 */
  private static final List<String> MOBILITY_POLICY_UNSUPPORTED_FIELDS =
      List.of("schema", "bounds", "absorptionCapByEdgePerMille", "bundleTemplates");

  /**
   * {@code setClassFirstLender} 两个可调整制度参数（至少给一个）。
   *
   * <p>★ 2026-09-30 裁定：`collectionPower` **本阶段不在白名单** —— 它在全引擎唯一出现是 {@code
   * LenderState.snapshot()}，没有任何消费点；接受一个"改了不生效"的参数是本仓禁的"看起来在记"。
   */
  static final List<String> LENDER_FIELDS = List.of("interestRatePerMille", "nextDueTick");

  /** {@code setClassFirstLender} 给到即具名拒绝的字段（当前引擎无消费点）。 */
  private static final String LENDER_UNSUPPORTED_FIELD = "collectionPower";

  /** {@code setCollectionPolicy} 四个可调整的 long 标量（至少给一个；两个固定组件不在内）。 */
  static final List<String> COLLECTION_POLICY_FIELDS =
      List.of(
          "collectionThreshold",
          "collectionTriggerRatioPerMille",
          "collectionRatioPerMille",
          "landPricePerUnit");

  /** {@code setCollectionPolicy} 给到即具名拒绝：{@link PilotModel.SeizurePriority} 只有一个取值（假旋钮）。 */
  private static final String COLLECTION_POLICY_SEIZURE_FIELD = "seizurePriority";

  /** {@code setCollectionPolicy} 给到即具名拒绝：催收方本阶段固定 {@link PilotModel#LANDLORD_ID}（制度重建）。 */
  private static final String COLLECTION_POLICY_COLLECTOR_FIELD = "collectorClassPositionId";

  /**
   * {@code setProductionParameters} 15 个可调整的 long 标量（至少给一个；顺序与 {@link PilotConfig.Tuning} 逐项一致）。
   */
  static final List<String> PRODUCTION_TUNING_FIELDS =
      List.of(
          "yieldPerLand",
          "seedPerLand",
          "laborPerLand",
          "toolCapacityPerTool",
          "rentPerLand",
          "wagePerLabor",
          "baseRationPerCapita",
          "laborRationPerLabor",
          "nonEssentialNeedPerMille",
          "nonEssentialEfficiencyPenaltyPerMille",
          "loanInterestRatePerMille",
          "moneyPerGrain",
          "toolPricePerUnit",
          "reserveTicks",
          "collectionIntervalTicks");

  private EconomyGmAdjustments() {}

  /**
   * 计算一次 GM 调整的项目（<b>纯函数，不写任何状态</b>）。
   *
   * @param base 当前 {@link EconomyData}（只读；不得为 null）
   * @param adjustment 调整名；白名单外一律 {@link IllegalArgumentException}（具名 {@link #DERIVED_REJECTION}）
   * @param parameters 调整参数对象（形状见七条白名单常量）
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
    // ★ 旧表两 kind 的 class-first 门统一在这里：非空 classFirst ⇒ 旧表没有结算路径，具名拒绝并指路 class-first 原生 kind。
    if (!base.classFirst().isEmpty()
        && (FORGIVE_DEBT.equals(adjustment) || SET_LIQUIDATION_POLICY.equals(adjustment))) {
      throw legacyClassFirstRejection(adjustment);
    }
    return switch (adjustment) {
      case FORGIVE_DEBT -> forgive(base, parameters, reason, day);
      case SET_LIQUIDATION_POLICY -> setLiquidationPolicy(base, parameters, reason, day);
      case SET_MOBILITY_POLICY -> setMobilityPolicy(base, parameters, reason, day);
      case SET_CLASS_FIRST_LENDER -> setClassFirstLender(base, parameters, reason, day);
      case FORGIVE_CLASS_FIRST_DEBT -> forgiveClassFirstDebt(base, parameters, reason, day);
      case SET_COLLECTION_POLICY -> setCollectionPolicy(base, parameters, reason, day);
      case SET_PRODUCTION_PARAMETERS -> setProductionParameters(base, parameters, reason, day);
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
    LiquidationPolicy.RecipientRule recipientRule =
        enumValue(
            label,
            "recipientRule",
            EconomyCommandPayloads.requireText(label, parameters, "recipientRule"),
            LiquidationPolicy.RecipientRule.class);

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

  /**
   * {@code setMobilityPolicy}：按给到的字段 upsert 既有 {@link MobilityPolicy}（至少一个字段；未给字段保持原值）。
   *
   * <p>★ key = {@code MobilityPolicyId.of(modeId)}；{@code modeId} 缺省 = {@code
   * base.classFirst().meta().config().mode().id()}；policy 不存在 ⇒ 具名拒绝。{@code schema}/{@code bounds}
   * 两个 record 组件与 {@code absorptionCapByEdgePerMille}/{@code bundleTemplates} 两张嵌套表本阶段只读 ——
   * 给到即具名拒绝，不静默忽略。
   */
  private static Projection setMobilityPolicy(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + SET_MOBILITY_POLICY;
    rejectUnsupportedMobilityPolicyFields(label, parameters);
    ClassFirstState state = base.classFirst();
    String modeId = resolveModeId(label, state.meta(), parameters);
    MobilityPolicyId key = mobilityPolicyId(label, modeId);
    MobilityPolicy before = state.mobilityPolicies().get(key);
    if (before == null) {
      throw new IllegalArgumentException(label + " 指名的 MobilityPolicy 不存在: " + key.value());
    }
    if (!hasAnyMobilityPolicyField(parameters)) {
      throw new IllegalArgumentException(label + " 至少需要给出一个可调整字段（modeId 只是定位键）: " + parameters);
    }

    long gamma = optionalAtLeast(label, parameters, "gamma", before.gamma(), 1L);
    long upMin =
        optionalNonNegative(
            label, parameters, "upMinPerMillePerYear", before.upMinPerMillePerYear());
    long upMax =
        optionalNonNegative(
            label, parameters, "upMaxPerMillePerYear", before.upMaxPerMillePerYear());
    long downMin =
        optionalNonNegative(
            label, parameters, "downMinPerMillePerYear", before.downMinPerMillePerYear());
    long downMax =
        optionalNonNegative(
            label, parameters, "downMaxPerMillePerYear", before.downMaxPerMillePerYear());
    long upCap =
        optionalNonNegative(
            label, parameters, "upCapPerMillePerTick", before.upCapPerMillePerTick());
    long downCap =
        optionalNonNegative(
            label, parameters, "downCapPerMillePerTick", before.downCapPerMillePerTick());
    long leaseAvailability =
        optionalInRange(
            label,
            parameters,
            "leaseAvailabilityPerMille",
            before.leaseAvailabilityPerMille(),
            0L,
            1000L);
    long initialLandForSale =
        optionalNonNegative(label, parameters, "initialLandForSale", before.initialLandForSale());
    long ticksPerYear =
        optionalAtLeast(label, parameters, "ticksPerYear", before.ticksPerYear(), 1L);
    long leasePerCapita =
        optionalNonNegative(label, parameters, "leasePerCapitaMilli", before.leasePerCapitaMilli());
    long landPurchasePerCapita =
        optionalNonNegative(
            label, parameters, "landPurchasePerCapitaMilli", before.landPurchasePerCapitaMilli());
    long capTenant =
        optionalNonNegative(
            label, parameters, "absorptionCapTenantPerMille", before.absorptionCapTenantPerMille());
    long capMiddle =
        optionalNonNegative(
            label, parameters, "absorptionCapMiddlePerMille", before.absorptionCapMiddlePerMille());
    long capLandlord =
        optionalNonNegative(
            label,
            parameters,
            "absorptionCapLandlordPerMille",
            before.absorptionCapLandlordPerMille());
    long capLaborer =
        optionalNonNegative(
            label,
            parameters,
            "absorptionCapLaborerPerMille",
            before.absorptionCapLaborerPerMille());
    long extractionTax =
        optionalNonNegative(
            label, parameters, "extractionTaxPerMille", before.extractionTaxPerMille());
    MobilityPolicy.AbsorptionPolicy absorptionPolicy = before.absorptionPolicy();
    if (hasValue(parameters, MOBILITY_POLICY_ENUM_FIELD)) {
      absorptionPolicy =
          enumValue(
              label,
              MOBILITY_POLICY_ENUM_FIELD,
              EconomyCommandPayloads.requireText(label, parameters, MOBILITY_POLICY_ENUM_FIELD),
              MobilityPolicy.AbsorptionPolicy.class);
    }
    if (upMax < upMin) {
      throw new IllegalArgumentException(
          label
              + " 的 upMaxPerMillePerYear 必须 >= upMinPerMillePerYear：upMin="
              + upMin
              + "，upMax="
              + upMax);
    }
    if (downMax < downMin) {
      throw new IllegalArgumentException(
          label
              + " 的 downMaxPerMillePerYear 必须 >= downMinPerMillePerYear：downMin="
              + downMin
              + "，downMax="
              + downMax);
    }

    MobilityPolicy after =
        new MobilityPolicy(
            before.schema(),
            before.bounds(),
            gamma,
            upMin,
            upMax,
            downMin,
            downMax,
            upCap,
            downCap,
            leaseAvailability,
            initialLandForSale,
            ticksPerYear,
            leasePerCapita,
            landPurchasePerCapita,
            capTenant,
            capMiddle,
            capLandlord,
            capLaborer,
            before.absorptionCapByEdgePerMille(),
            absorptionPolicy,
            before.bundleTemplates(),
            extractionTax);
    EconomyData projected = base.withClassFirst(state.withMobilityPolicies(Map.of(key, after)));
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    List<Change> changes =
        after.equals(before)
            ? List.of() // 逐值相同的 upsert = 幂等 no-op（同 setLiquidationPolicy 口径）
            : List.of(new Change("classFirst.mobilityPolicies", key.value(), before, after));
    return new Projection(SET_MOBILITY_POLICY, reason, day, projected, changeSet, changes);
  }

  /**
   * {@code setClassFirstLender}：只改既有放贷主体的 {@code interestRatePerMille}/{@code nextDueTick}（至少一项），
   * 不动 money/goods；{@code collectionPower} 当前引擎无消费点 ⇒ 给到即具名拒绝。
   *
   * <p>lender 不存在 ⇒ 具名拒绝；两个参数都必须 &ge; 0（负值会让下一 tick 的账户校验炸掉，故在这里 fail-closed）。 ★ 接线说明：{@code
   * ClassFirstPilotEngine.restore} 会在构造引擎前把 state 里同 id 的 lender 写回 {@code config}， 外部货币借款路径读的就是
   * {@code config.lender()} ⇒ 这里改的利率/到期对下一 tick 新建债生效。
   */
  private static Projection setClassFirstLender(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + SET_CLASS_FIRST_LENDER;
    String lenderId = EconomyCommandPayloads.requireText(label, parameters, "lenderId");
    ExternalLenderId key = externalLenderId(label, lenderId);
    PilotModel.Lender before = base.classFirst().lenders().get(key);
    if (before == null) {
      throw new IllegalArgumentException(label + " 指名的放贷方不存在: " + key.value());
    }
    if (parameters.has(LENDER_UNSUPPORTED_FIELD)) {
      throw new IllegalArgumentException(
          label
              + " 本阶段拒绝 "
              + LENDER_UNSUPPORTED_FIELD
              + "：当前引擎没有消费点（改了不生效），等催收逻辑接线后再开；可调整字段: "
              + String.join(" | ", LENDER_FIELDS));
    }
    if (!hasAnyLenderField(parameters)) {
      throw new IllegalArgumentException(
          label + " 至少需要给出一个可调整字段: " + String.join(" | ", LENDER_FIELDS));
    }
    long interestRate =
        optionalNonNegative(
            label, parameters, "interestRatePerMille", before.interestRatePerMille());
    long nextDueTick = optionalNonNegative(label, parameters, "nextDueTick", before.nextDueTick());

    PilotModel.Lender after =
        new PilotModel.Lender(
            before.id(),
            before.money(),
            before.goods(),
            interestRate,
            nextDueTick,
            before.collectionPower());
    EconomyData projected = base.withClassFirst(base.classFirst().withLenders(Map.of(key, after)));
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    List<Change> changes =
        after.equals(before)
            ? List.of()
            : List.of(new Change("classFirst.lenders", key.value(), before, after));
    return new Projection(SET_CLASS_FIRST_LENDER, reason, day, projected, changeSet, changes);
  }

  /**
   * {@code forgiveClassFirstDebt}：对称免去既有双边账户的债务/债权净额（只改 {@code cumulativeNet}/{@code status}）。
   *
   * <p>账户按 {@code ownerId→counterpartyId} 方向找债务人侧（{@code cumulativeNet < 0}），{@code unit} 缺省 {@link
   * PilotModel#GRAIN}；减免额 = {@code min(amount（缺省=全额债务）, -cumulativeNet)}。两条镜像账户的 {@code
   * cumulativeNet} 同步一增一减，保持 {@code debt==claim} 与 {@code Σ账户净额=0}；归零的一侧（全额免债时是两侧） {@code
   * status=SETTLED}。★ <b>本阶段不动 {@code interestAccrued}</b> —— 免债不冲销已计利息，累计利息只由结算滚动追加；也不改 {@code
   * terms}/{@code interestRatePerMille}/{@code nextDueTick}，不搬库存/商品/货币，不新增/删除账户。
   */
  private static Projection forgiveClassFirstDebt(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + FORGIVE_CLASS_FIRST_DEBT;
    String ownerId = EconomyCommandPayloads.requireText(label, parameters, "ownerId");
    String counterpartyId = EconomyCommandPayloads.requireText(label, parameters, "counterpartyId");
    if (ownerId.equals(counterpartyId)) {
      throw new IllegalArgumentException(label + " 的 ownerId 与 counterpartyId 不得相同: " + ownerId);
    }
    String unit = EconomyCommandPayloads.optionalText(label, parameters, "unit", PilotModel.GRAIN);
    ClassFirstState state = base.classFirst();
    ClassFirstAccountId debtorId = accountId(label, ownerId, counterpartyId, unit);
    ClassFirstAccountId mirrorId = accountId(label, counterpartyId, ownerId, unit);
    ClassFirstAccount debtor = state.accounts().get(debtorId);
    if (debtor == null) {
      throw new IllegalArgumentException(
          label
              + " 找不到 owner→counterparty 账户: "
              + debtorId.value()
              + "（owner="
              + ownerId
              + "，counterparty="
              + counterpartyId
              + "，unit="
              + unit
              + "）");
    }
    if (debtor.cumulativeNet() >= 0L) {
      throw new IllegalArgumentException(
          label
              + " 指名的账户不是债务人侧（cumulativeNet 必须 < 0）: "
              + debtorId.value()
              + "，net="
              + debtor.cumulativeNet());
    }
    long debt = -debtor.cumulativeNet();
    ClassFirstAccount mirror = state.accounts().get(mirrorId);
    if (mirror == null) {
      throw new IllegalArgumentException(
          label + " 缺少镜像账户 " + mirrorId.value() + "，无法对称免债（不新增/删除账户）");
    }
    if (mirror.cumulativeNet() != debt) {
      throw new IllegalArgumentException(
          label
              + " 的镜像账户 "
              + mirrorId.value()
              + " 与债务侧不构成 debt==claim（debt="
              + debt
              + "，claim="
              + mirror.cumulativeNet()
              + "），拒绝免债以免破坏双边守恒");
    }
    long amount = debt;
    if (hasValue(parameters, "amount")) {
      amount = EconomyCommandPayloads.requireLong(label, parameters, "amount");
    }
    if (amount <= 0L) {
      throw new IllegalArgumentException(label + " 的 amount 必须 > 0: " + amount);
    }
    // ★ 按计划取 min(amount, 全额债务)：给出超过债务的 amount 视为封顶到全额，不报错。
    long forgiven = Math.min(amount, debt);
    long debtorAfterNet = debtor.cumulativeNet() + forgiven; // 向 0 靠近，恒 ≤ 0
    long mirrorAfterNet = mirror.cumulativeNet() - forgiven; // claim 侧对称减少，恒 ≥ 0
    PilotModel.AccountStatus debtorStatus =
        debtorAfterNet == 0L ? PilotModel.AccountStatus.SETTLED : debtor.status();
    PilotModel.AccountStatus mirrorStatus =
        mirrorAfterNet == 0L ? PilotModel.AccountStatus.SETTLED : mirror.status();

    ClassFirstAccount nextDebtor =
        new ClassFirstAccount(
            debtorId,
            ownerId,
            counterpartyId,
            unit,
            debtor.terms(),
            debtor.interestRatePerMille(),
            debtor.nextDueTick(),
            debtorAfterNet,
            debtor.interestAccrued(), // ★ 本阶段不动：免债不冲销已计利息
            debtorStatus);
    ClassFirstAccount nextMirror =
        new ClassFirstAccount(
            mirrorId,
            counterpartyId,
            ownerId,
            unit,
            mirror.terms(),
            mirror.interestRatePerMille(),
            mirror.nextDueTick(),
            mirrorAfterNet,
            mirror.interestAccrued(), // ★ 同上
            mirrorStatus);
    EconomyData projected =
        base.withClassFirst(state.withAccounts(Map.of(debtorId, nextDebtor, mirrorId, nextMirror)));
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    return new Projection(
        FORGIVE_CLASS_FIRST_DEBT,
        reason,
        day,
        projected,
        changeSet,
        List.of(
            new Change("classFirst.accounts", debtorId.value(), debtor, nextDebtor),
            new Change("classFirst.accounts", mirrorId.value(), mirror, nextMirror)));
  }

  /**
   * {@code setCollectionPolicy}：只改既有 {@link PilotModel.CollectionPolicy} 的四个标量（至少一项；未给保持原值）。
   *
   * <p>★ {@code collectorClassPositionId} 与 {@code seizurePriority} 本阶段是 record 的固定组件：给到即具名拒绝
   * （催收方固定 {@link PilotModel#LANDLORD_ID}；{@link PilotModel.SeizurePriority} 只有一个取值）。逐值相同 ⇒ 幂等
   * no-op。落点只有 {@code classFirst.meta.config.collectionPolicy} —— 不碰 mode/lender/mobilityPolicy
   * 与一切派生读数。
   */
  private static Projection setCollectionPolicy(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + SET_COLLECTION_POLICY;
    rejectUnsupportedCollectionPolicyFields(label, parameters);
    ClassFirstState state = base.classFirst();
    PilotConfig config = requireConfig(label, state.meta());
    if (!hasAnyCollectionPolicyField(parameters)) {
      throw new IllegalArgumentException(
          label + " 至少需要给出一个可调整字段: " + String.join(" | ", COLLECTION_POLICY_FIELDS));
    }
    PilotModel.CollectionPolicy before = config.collectionPolicy();
    long collectionThreshold =
        optionalNonNegative(label, parameters, "collectionThreshold", before.collectionThreshold());
    long collectionTriggerRatioPerMille =
        optionalNonNegative(
            label,
            parameters,
            "collectionTriggerRatioPerMille",
            before.collectionTriggerRatioPerMille());
    long collectionRatioPerMille =
        optionalInRange(
            label,
            parameters,
            "collectionRatioPerMille",
            before.collectionRatioPerMille(),
            0L,
            1000L);
    long landPricePerUnit =
        optionalAtLeast(label, parameters, "landPricePerUnit", before.landPricePerUnit(), 1L);
    PilotModel.CollectionPolicy after =
        new PilotModel.CollectionPolicy(
            before.collectorClassPositionId(),
            collectionThreshold,
            collectionTriggerRatioPerMille,
            collectionRatioPerMille,
            landPricePerUnit,
            before.seizurePriority());
    PilotConfig nextConfig = config.withCollectionPolicy(after);
    EconomyData projected =
        base.withClassFirst(state.withMeta(state.meta().withConfig(nextConfig)));
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    List<Change> changes =
        after.equals(before)
            ? List.of() // 逐值相同的 upsert = 幂等 no-op（同 setLiquidationPolicy 口径）
            : List.of(
                new Change(
                    "classFirst.meta.config.collectionPolicy", "collectionPolicy", before, after));
    return new Projection(SET_COLLECTION_POLICY, reason, day, projected, changeSet, changes);
  }

  /**
   * {@code setProductionParameters}：只改 {@code classFirst.meta.config} 的 15 个生产/技术标量（至少一项；未给保持原值）。
   *
   * <p>★ 从 {@link PilotConfig#currentTuning()} 起步、只覆盖给到的字段，再走 {@link PilotConfig#withTuning}
   * 这个唯一重建点 —— 不手抄 19 个组件。mode/lender/collectionPolicy/mobilityPolicy 各有权威面（后者三者各有专属 kind）， 本 kind
   * 一律不碰；逐值相同 ⇒ 幂等 no-op。参数边界按 {@link PilotConfig} 构造器与用量：生产三率与 {@code moneyPerGrain} 必须 &gt;
   * 0，其余标量 &ge; 0，{@code collectionIntervalTicks} 必须 &ge; 1。
   */
  private static Projection setProductionParameters(
      EconomyData base, JsonNode parameters, String reason, long day) {
    String label = COMMAND + "." + SET_PRODUCTION_PARAMETERS;
    ClassFirstState state = base.classFirst();
    PilotConfig config = requireConfig(label, state.meta());
    if (!hasAnyProductionTuningField(parameters)) {
      throw new IllegalArgumentException(
          label + " 至少需要给出一个可调整字段: " + String.join(" | ", PRODUCTION_TUNING_FIELDS));
    }
    PilotConfig.Tuning before = config.currentTuning();
    long yieldPerLand =
        optionalAtLeast(label, parameters, "yieldPerLand", before.yieldPerLand(), 1L);
    long seedPerLand = optionalNonNegative(label, parameters, "seedPerLand", before.seedPerLand());
    long laborPerLand =
        optionalAtLeast(label, parameters, "laborPerLand", before.laborPerLand(), 1L);
    long toolCapacityPerTool =
        optionalAtLeast(label, parameters, "toolCapacityPerTool", before.toolCapacityPerTool(), 1L);
    long rentPerLand = optionalNonNegative(label, parameters, "rentPerLand", before.rentPerLand());
    long wagePerLabor =
        optionalNonNegative(label, parameters, "wagePerLabor", before.wagePerLabor());
    long baseRationPerCapita =
        optionalNonNegative(label, parameters, "baseRationPerCapita", before.baseRationPerCapita());
    long laborRationPerLabor =
        optionalNonNegative(label, parameters, "laborRationPerLabor", before.laborRationPerLabor());
    long nonEssentialNeedPerMille =
        optionalNonNegative(
            label, parameters, "nonEssentialNeedPerMille", before.nonEssentialNeedPerMille());
    long nonEssentialEfficiencyPenaltyPerMille =
        optionalNonNegative(
            label,
            parameters,
            "nonEssentialEfficiencyPenaltyPerMille",
            before.nonEssentialEfficiencyPenaltyPerMille());
    long loanInterestRatePerMille =
        optionalNonNegative(
            label, parameters, "loanInterestRatePerMille", before.loanInterestRatePerMille());
    long moneyPerGrain =
        optionalAtLeast(label, parameters, "moneyPerGrain", before.moneyPerGrain(), 1L);
    long toolPricePerUnit =
        optionalNonNegative(label, parameters, "toolPricePerUnit", before.toolPricePerUnit());
    long reserveTicks =
        optionalNonNegative(label, parameters, "reserveTicks", before.reserveTicks());
    long collectionIntervalTicks =
        optionalAtLeast(
            label, parameters, "collectionIntervalTicks", before.collectionIntervalTicks(), 1L);
    PilotConfig.Tuning after =
        new PilotConfig.Tuning(
            yieldPerLand,
            seedPerLand,
            laborPerLand,
            toolCapacityPerTool,
            rentPerLand,
            wagePerLabor,
            baseRationPerCapita,
            laborRationPerLabor,
            nonEssentialNeedPerMille,
            nonEssentialEfficiencyPenaltyPerMille,
            loanInterestRatePerMille,
            moneyPerGrain,
            toolPricePerUnit,
            reserveTicks,
            collectionIntervalTicks);
    PilotConfig nextConfig = config.withTuning(after);
    EconomyData projected =
        base.withClassFirst(state.withMeta(state.meta().withConfig(nextConfig)));
    EconomyChangeSet changeSet = EconomyChangeSet.between(base, projected);
    List<Change> changes =
        after.equals(before)
            ? List.of() // 逐值相同 = 幂等 no-op（changeSet 也是 Unchanged），不报"变了"
            : List.of(new Change("classFirst.meta.config", "tuning", before, after));
    return new Projection(SET_PRODUCTION_PARAMETERS, reason, day, projected, changeSet, changes);
  }

  /** {@code meta.config} 是两个新 kind 的权威面；空态/旧档没有 config ⇒ 具名拒绝（不静默 no-op）。 */
  private static PilotConfig requireConfig(String label, ClassFirstMeta meta) {
    if (meta == null || meta.config() == null) {
      throw new IllegalArgumentException(
          label + " 需要 classFirst.meta.config（当前 classFirst 未播种/无 config）；请先播种 class-first 世界");
    }
    return meta.config();
  }

  /** 缺省 modeId 取 {@code classFirst.meta.config.mode.id()}；classFirst 未播种/无 config ⇒ 具名拒绝。 */
  private static String resolveModeId(String label, ClassFirstMeta meta, JsonNode parameters) {
    String modeId = EconomyCommandPayloads.optionalText(label, parameters, "modeId", null);
    if (modeId != null) {
      return modeId;
    }
    if (meta == null || meta.config() == null || meta.config().mode() == null) {
      throw new IllegalArgumentException(
          label
              + " 的 modeId 缺省需要 classFirst.meta.config.mode（当前 classFirst 未播种/无 config）；请显式给出 modeId");
    }
    return meta.config().mode().id();
  }

  /** 四个只读字段给了就拒（含显式 null，避免"看起来接受了"）。 */
  private static void rejectUnsupportedMobilityPolicyFields(String label, JsonNode parameters) {
    for (String field : MOBILITY_POLICY_UNSUPPORTED_FIELDS) {
      if (parameters.has(field)) {
        throw new IllegalArgumentException(
            label
                + " 本阶段不支持修改 "
                + field
                + "（schema/bounds 是 record 组件、absorptionCapByEdgePerMille/bundleTemplates"
                + " 是嵌套表；给到即具名拒绝，不静默忽略）");
      }
    }
  }

  private static boolean hasAnyMobilityPolicyField(JsonNode parameters) {
    for (String field : MOBILITY_POLICY_LONG_FIELDS) {
      if (hasValue(parameters, field)) {
        return true;
      }
    }
    return hasValue(parameters, MOBILITY_POLICY_ENUM_FIELD);
  }

  private static boolean hasAnyLenderField(JsonNode parameters) {
    for (String field : LENDER_FIELDS) {
      if (hasValue(parameters, field)) {
        return true;
      }
    }
    return false;
  }

  /** 两个固定组件给了就拒（含显式 null，避免"看起来接受了"）：枚举只剩一个取值 / 本阶段催收方固定。 */
  private static void rejectUnsupportedCollectionPolicyFields(String label, JsonNode parameters) {
    if (parameters.has(COLLECTION_POLICY_COLLECTOR_FIELD)) {
      throw new IllegalArgumentException(
          label
              + " 本阶段拒绝 "
              + COLLECTION_POLICY_COLLECTOR_FIELD
              + "：催收方固定为 "
              + PilotModel.LANDLORD_ID
              + "，改它属于制度重建；可调整字段: "
              + String.join(" | ", COLLECTION_POLICY_FIELDS));
    }
    if (parameters.has(COLLECTION_POLICY_SEIZURE_FIELD)) {
      throw new IllegalArgumentException(
          label
              + " 本阶段拒绝 "
              + COLLECTION_POLICY_SEIZURE_FIELD
              + "："
              + PilotModel.SeizurePriority.class.getSimpleName()
              + " 当前只有 "
              + PilotModel.SeizurePriority.LIQUID_THEN_LAND
              + " 一个取值，不是可调参数；可调整字段: "
              + String.join(" | ", COLLECTION_POLICY_FIELDS));
    }
  }

  private static boolean hasAnyCollectionPolicyField(JsonNode parameters) {
    for (String field : COLLECTION_POLICY_FIELDS) {
      if (hasValue(parameters, field)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasAnyProductionTuningField(JsonNode parameters) {
    for (String field : PRODUCTION_TUNING_FIELDS) {
      if (hasValue(parameters, field)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasValue(JsonNode parameters, String field) {
    JsonNode node = parameters.get(field);
    return node != null && !node.isNull();
  }

  /** 可选 long：缺键 / JSON null ⇒ fallback；给了 ⇒ 必须为非负整数。 */
  private static long optionalNonNegative(
      String label, JsonNode parameters, String field, long fallback) {
    if (!hasValue(parameters, field)) {
      return fallback;
    }
    return nonNegative(label, field, EconomyCommandPayloads.requireLong(label, parameters, field));
  }

  /** 可选 long：缺键 / JSON null ⇒ fallback；给了 ⇒ 必须 &ge; min。 */
  private static long optionalAtLeast(
      String label, JsonNode parameters, String field, long fallback, long min) {
    if (!hasValue(parameters, field)) {
      return fallback;
    }
    long value = EconomyCommandPayloads.requireLong(label, parameters, field);
    if (value < min) {
      throw new IllegalArgumentException(label + " 的 " + field + " 必须 >= " + min + ": " + value);
    }
    return value;
  }

  /** 可选 long：缺键 / JSON null ⇒ fallback；给了 ⇒ 必须 ∈ [min, max]。 */
  private static long optionalInRange(
      String label, JsonNode parameters, String field, long fallback, long min, long max) {
    if (!hasValue(parameters, field)) {
      return fallback;
    }
    long value = EconomyCommandPayloads.requireLong(label, parameters, field);
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          label + " 的 " + field + " 必须 ∈ [" + min + ", " + max + "]: " + value);
    }
    return value;
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

  /** 旧两 kind 在非空 class-first 世界的具名拒绝：旧表没有结算路径，指路五个 class-first 原生 kind。 */
  private static IllegalArgumentException legacyClassFirstRejection(String adjustment) {
    return new IllegalArgumentException(
        COMMAND
            + "."
            + adjustment
            + " 在 class-first 世界不可用：class-first 不读 debtContracts/liquidationPolicies，旧表没有结算路径；"
            + "请改用 "
            + SET_MOBILITY_POLICY
            + " | "
            + SET_CLASS_FIRST_LENDER
            + " | "
            + FORGIVE_CLASS_FIRST_DEBT
            + " | "
            + SET_COLLECTION_POLICY
            + " | "
            + SET_PRODUCTION_PARAMETERS);
  }

  private static MobilityPolicyId mobilityPolicyId(String label, String modeId) {
    try {
      return MobilityPolicyId.of(modeId);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 modeId 非法: " + e.getMessage());
    }
  }

  private static ExternalLenderId externalLenderId(String label, String lenderId) {
    try {
      return ExternalLenderId.of(lenderId);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的 lenderId 非法: " + e.getMessage());
    }
  }

  private static ClassFirstAccountId accountId(
      String label, String ownerId, String counterpartyId, String unit) {
    try {
      return ClassFirstAccountId.idOf(ownerId, counterpartyId, unit);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(label + " 的账户身份非法: " + e.getMessage());
    }
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
   *     classFirst.mobilityPolicies}）
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

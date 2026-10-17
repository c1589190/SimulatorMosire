package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.api.relation.Payee;
import io.mosire.simos.economy.api.relation.ProductionRules;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AssetRule;
import io.mosire.simos.economy.model.ClassStructure;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.LiquidationPolicy;
import io.mosire.simos.economy.model.OwnershipStake;
import io.mosire.simos.economy.model.ProductionEnterprise;
import io.mosire.simos.economy.model.ProductionRole;
import io.mosire.simos.economy.model.RentRule;
import io.mosire.simos.economy.model.TransferRule;
import io.mosire.simos.economy.time.DebtContractBook;
import io.mosire.simos.production.contract.ProductionProcess;
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
 * <p>★★ <b>十条源状态白名单</b>（{@link #ADJUSTMENTS}）：
 *
 * <ul>
 *   <li><b>旧表（两）</b>：{@link #FORGIVE_DEBT}（{@link DebtContractBook#forgive} + {@link
 *       EconomyData#withDebtContracts}）与 {@link #SET_LIQUIDATION_POLICY}（{@link
 *       EconomyData#withLiquidationPolicies}）；
 *   <li><b>P7 阶层/关系/规则/组织编辑（七）</b>：{@link #UPSERT_CLASS_STRUCTURE}/{@link #UPSERT_CLASS_POSITION}/
 *       {@link #UPSERT_PRODUCTION_RELATION}/{@link #UPSERT_ASSET_RULE}/{@link
 *       #UPSERT_PRODUCTION_ORGANIZATION}（{@link #UPSERT_CANDIDATE} 已退役）—— 只编辑 {@code
 *       classStructures/classPositions/relations/assetRules/productionOrganizations} 五张 源状态表（class
 *       两表同批落值走 {@link EconomyData#withClassStructuresAndPositions} 成对写口）。 ★★ <b>B5（§13.15.2
 *       裁定十八）：{@link #UPSERT_PRODUCTION_MODE} / {@link #DEACTIVATE_PRODUCTION_MODE} 两个 kind 已删除</b>
 *       —— {@code EconomyData.modes} 组件已删， 生产方式定义的唯一写面在 production（{@code
 *       production.UpsertProductionMode} / {@code production.DeactivateProductionMode}）；命中这两个 kind
 *       ⇒ 具名拒 （{@link #PRODUCTION_MODE_MOVED_REASON}）。
 *   <li><b>Z1 产品产出数量覆盖（两）</b>：{@link #SET_OUTPUT_QUANTITY} / {@link #CLEAR_OUTPUT_QUANTITY} —— 只编辑
 *       {@code outputQuantityOverrides}（键 = industryId，内层键 = commodityId，值 = 商品数量/单位规模）； 本批保持 仅
 *       GM（不打 {@code DmUsableCommand}），不加 {@code CommandTargets}、不进决策令桶（§4）。
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

  /**
   * ★★★ <b>B5（§13.15.2 裁定十八）：{@code upsertProductionMode} 已从本命令删除</b> —— 与 {@link
   * #SET_OUTPUT_QUANTITY} 同一形态（具名拒，不是"白名单外"那句与事实不符的通用拒绝）。
   *
   * <p>★ <b>能力的真实落点（不是退役）</b>：{@code production.UpsertProductionMode}（"生产方式定义"的唯一语义落点 {@code
   * ProductionModeUpserts}，装配点 {@code Shell.java:925}）；economy 侧不再有 {@code modes} 组件可写。
   */
  public static final String UPSERT_PRODUCTION_MODE = "upsertProductionMode";

  /**
   * 见 {@link #UPSERT_PRODUCTION_MODE}；落点是 {@code production.DeactivateProductionMode}（装配点 {@code
   * Shell.java:926}）。
   */
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

  /**
   * ★★ Z1 的产出数量覆盖两 kind —— <b>B2b 起不再是白名单项</b>（{@code outputQuantityOverrides} 组件已从 economy
   * 删除，唯一权威在 production）。
   *
   * <p>★ 常量保留的唯一用途：把"这个 kind 已迁出"做成**具名拒绝**（{@link #OUTPUT_QUANTITY_MOVED_REASON}），
   * 而不是让它掉进"白名单外"这句与事实不符的通用拒绝里。
   */
  public static final String SET_OUTPUT_QUANTITY = "setOutputQuantity";

  /** 见 {@link #SET_OUTPUT_QUANTITY}。 */
  public static final String CLEAR_OUTPUT_QUANTITY = "clearOutputQuantity";

  /**
   * ★★ <b>B2c（裁定八）：{@code upsertCandidate} kind 已随候选面整体退役</b> —— 与 {@link #SET_OUTPUT_QUANTITY}
   * 同一形态：它<b>不再改任何 economy 状态</b>，命中时给<b>具名拒绝</b> （{@link
   * #PLAN_DEFINITION_MOVED_REASON}），而不是掉进"白名单外"那句与事实不符的通用拒绝里。
   *
   * <p>★ 能力的真实落点：方案定义的写面是 production 的 {@code production.UpsertIndustry} （GM 工具 {@code
   * simos.economy.upsertIndustry}），"按需登记"走 {@code ProductionRuntime#registerPlan}；economy 侧的 {@code
   * assetSource} 声明落点是 {@code EconomyMeta.planAssetSources}（写面属 U10，见账本 B2c 节）。
   */
  public static final String UPSERT_CANDIDATE = "upsertCandidate";

  /**
   * ★★ B2b：产出数量覆盖迁出的稳定拒绝理由（同 B2 的铸币键口径：**具名拒**，不是"保留键、无人读"）。
   *
   * <p>★ 能力的真实落点：{@code production.UpsertIndustry} 的 {@code outputPerUnit} （被引用的产业须发新版本
   * id）。"运行中产业原地改产出数量"的便捷入口属 **U10 后续未做**—— 本批**不**声称已交付（见账本 B2b 节）。
   */
  public static final String OUTPUT_QUANTITY_MOVED_REASON =
      "output-quantity-moved-to-production-definition";

  /**
   * ★★ <b>B2c（裁定八）：{@code upsertCandidate} 迁出的稳定拒绝理由</b>（与上一条同制）。
   *
   * <p>★ 逐字段落点：{@code outputPerUnit}/{@code cycleDays}/{@code regime} 等本来就在方案定义里； {@code buildDays}
   * 与 {@code acceptedRightKinds} 已搬进 {@code IndustryDefinitionView}（B2c 新增组件）； {@code assetSource}
   * 留 economy（{@code EconomyMeta.planAssetSources}）；{@code version} 与"主产出"概念删除。
   */
  public static final String PLAN_DEFINITION_MOVED_REASON =
      "plan-definition-moved-to-production-definition";

  /**
   * ★★★ <b>B5（§13.15.2 裁定十八）：两个生产方式定义 kind 迁出的稳定拒绝理由</b>（与上两条同制：<b>具名拒</b>， 不是"保留键、无人读"）。
   *
   * <p>★ 去核结论（B5 账本 §B5-§2）：economy 侧写腿**只有** `economy.GmAdjust` 的这两个 kind， 而它们在 main/test/工具
   * schema/catalog 里**全是 0 个调用点**；世界播种走的是 `production.Seed` 的 `modes[]`（`ProductionSeedHandler`）。⇒
   * 按裁定十八第 ② 条：**连命令 kind 一起删**， 能力（改生产方式定义）由 production 既有命令面承接，economy 侧不再有该写口。
   */
  public static final String PRODUCTION_MODE_MOVED_REASON =
      "production-mode-definition-moved-to-production";

  /** 白名单外调整的统一拒绝短语（handler 折 {@code Rejected}、工具折 {@code BAD_REQUEST} 都用它）。 */
  public static final String DERIVED_REJECTION = "派生读数不可由 GM 调整工具直写";

  private static final String COMMAND = EconomyGmAdjustHandler.TYPE;

  /**
   * ★★ <b>七条源状态白名单</b>（拒绝消息与 handler 兜底共用同一顺序；唯一拼写点在各自常量）。
   *
   * <p>★ B2b/B2c：{@code upsertCandidate} / {@code setOutputQuantity} / {@code clearOutputQuantity}
   * 三项已**退出白名单**（前者随候选面退役、后两者随 {@code outputQuantityOverrides} 组件迁出 economy）—— 它们不再改任何 economy
   * 状态，命中时给具名拒绝（见 {@link #OUTPUT_QUANTITY_MOVED_REASON} / {@link #PLAN_DEFINITION_MOVED_REASON}）。
   *
   * <p>★★ <b>B5（§13.15.2 裁定十八）：总数 12 → 9 → 7 的第二次收缩</b>：原 P7 八 kind 里的 {@code
   * upsertProductionMode}/{@code deactivateProductionMode} 已随 {@code EconomyData.modes} 组件删除而退出
   * 白名单（见 {@link #PRODUCTION_MODE_MOVED_REASON}）⇒ 现存 <b>七</b> 项（{@link #ADJUSTMENTS} 逐项即真值）。
   */
  static final List<String> ADJUSTMENTS =
      List.of(
          FORGIVE_DEBT,
          SET_LIQUIDATION_POLICY,
          UPSERT_CLASS_STRUCTURE,
          UPSERT_CLASS_POSITION,
          UPSERT_PRODUCTION_RELATION,
          UPSERT_ASSET_RULE,
          UPSERT_PRODUCTION_ORGANIZATION);

  /**
   * ★ B2b/B2c：已迁出、但仍给**具名拒**的 kind（不进 {@link #ADJUSTMENTS}）。
   *
   * <p>★ 五个 kind 各有一个**具名**理由（{@link #OUTPUT_QUANTITY_MOVED_REASON} ×2、 {@link
   * #PLAN_DEFINITION_MOVED_REASON}、{@link #PRODUCTION_MODE_MOVED_REASON} ×2），不是"白名单外"的通用拒绝。
   */
  static final List<String> MOVED_ADJUSTMENTS =
      List.of(
          SET_OUTPUT_QUANTITY,
          CLEAR_OUTPUT_QUANTITY,
          UPSERT_CANDIDATE,
          UPSERT_PRODUCTION_MODE,
          DEACTIVATE_PRODUCTION_MODE);

  private EconomyGmAdjustments() {}

  /**
   * 计算一次 GM 调整的项目（<b>纯函数，不写任何状态</b>）。
   *
   * @param base 当前 {@link EconomyData}（只读；不得为 null）
   * @param definitions production 当刻定义只读来源（★★ B5 / §13.15.2 裁定十八：{@code EconomyData.modes} 副本已删 ⇒
   *     "某阶层结构指名的生产方式是否存在"这类判据必须读 production 当刻权威；不得为 null）
   * @param units 生产实例的只读投影（{@code ProductionStateLookup#instances()}）
   * @param adjustment 调整名；白名单外一律 {@link IllegalArgumentException}（具名 {@link #DERIVED_REJECTION}）
   * @param parameters 调整参数对象（形状见七条白名单常量）
   * @param reason 调整原因；必填非空白
   * @param day 世界当前日（只进审计摘要；不改状态）
   * @return 投影后的 {@link EconomyData}、{@link EconomyChangeSet} 与前后差异清单
   * @throws IllegalArgumentException 参数缺失/类型不对/引用不存在/白名单外（消息可直接进 {@code Rejected} / {@code
   *     BAD_REQUEST}）
   */
  public static Projection project(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      Map<ProductionUnitId, ProductionProcess> units,
      String adjustment,
      JsonNode parameters,
      String reason,
      long day) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(definitions, "definitions（B5：生产方式定义的唯一权威在 production，必须显式注入）");
    Objects.requireNonNull(units, "units（production 实例的只读投影，B2b 起必填）");
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
      // ★★★ B5（§13.15.2 裁定十八）：两个生产方式定义 kind 已删除（组件 `modes` 已删）—— 给**具名拒**，
      //   能力的真实落点是 production 既有命令面。
      case UPSERT_PRODUCTION_MODE, DEACTIVATE_PRODUCTION_MODE ->
          throw new IllegalArgumentException(
              COMMAND
                  + "."
                  + adjustment
                  + " "
                  + PRODUCTION_MODE_MOVED_REASON
                  + ": 生产方式定义（id/name/version/classStructureId）的唯一权威与唯一写面在 production —— 请用"
                  + " production.UpsertProductionMode / production.DeactivateProductionMode"
                  + "（定义唯一语义落点 ProductionModeUpserts）；economy 侧不再持有生产方式定义，"
                  + "也没有它的写口");
      case UPSERT_CLASS_STRUCTURE ->
          upsertClassStructure(base, definitions, parameters, reason, day);
      case UPSERT_CLASS_POSITION -> upsertClassPosition(base, definitions, parameters, reason, day);
      case UPSERT_PRODUCTION_RELATION ->
          upsertProductionRelation(base, units, parameters, reason, day);
      case UPSERT_ASSET_RULE -> upsertAssetRule(base, definitions, parameters, reason, day);
      case UPSERT_PRODUCTION_ORGANIZATION ->
          upsertProductionOrganization(base, definitions, units, parameters, reason, day);
      // ★★ B2c（裁定八）：候选 kind 与两个产出覆盖 kind 一样给**具名拒**（不是"白名单外"那句与事实不符的
      //   通用拒绝）；方案定义的写面在 production（见 UPSERT_CANDIDATE 的类注）。
      case UPSERT_CANDIDATE ->
          throw new IllegalArgumentException(
              COMMAND
                  + "."
                  + adjustment
                  + " "
                  + PLAN_DEFINITION_MOVED_REASON
                  + ": 方案定义（含 buildDays / acceptedRightKinds）的唯一权威在 production —— 请用"
                  + " production.UpsertIndustry（GM 工具 simos.economy.upsertIndustry）；economy 侧的"
                  + " assetSource 声明落点见 EconomyMeta.planAssetSources（写面属 U10 未做）");
      // ★★ B2b：两个已迁出的 kind 给**具名拒**（不是"白名单外"那句与事实不符的通用拒绝）。
      case SET_OUTPUT_QUANTITY, CLEAR_OUTPUT_QUANTITY ->
          throw new IllegalArgumentException(
              COMMAND
                  + "."
                  + adjustment
                  + " "
                  + OUTPUT_QUANTITY_MOVED_REASON
                  + ": GM 产出数量覆盖的唯一权威在 production —— 请用 production.UpsertIndustry 的"
                  + " outputPerUnit（被引用的产业发新版本 id）；运行中产业原地改产出数量的便捷入口属 U10 未做");
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

  // ── P7：阶层结构 / 关系 / 资产规则 / 组织 的 GM 编辑（生产方式定义两个 kind 见下面的 B5 说明）──────────
  //
  // ★★ 全部只改源状态、只走既有 with* 写口（class 结构+位置两表需要同批落值时走
  //   EconomyData.withClassStructuresAndPositions 这个成对写口），最终都过 EconomyChangeSet.between；
  //   派生读数（flows/crisisSignals/classStandings.* 等）仍没有写口，白名单外一律 DERIVED_REJECTION。
  //   ★★★ B5（§13.15.2 裁定十八）：本段四个 kind 的"modeId 必须已存在"判据**读 production 当刻定义权威**
  //     （{@code definitions.mode(modeId)}），不再读已删的 economy `modes` 副本。

  // ★★★ B5（§13.15.2 裁定十八）：原 `upsertProductionMode(...)` 与 `deactivateProductionMode(...)`
  //   两个语义落点**整段删除**（连同它们读的 `EconomyData.modes` 副本、`copyModeReferences` 六类引用者
  //   遍历、以及 `VERSION_ABSENT` / 全部 mode 版本推进判据）。
  //   ★ B12（DEF-T6-4 #2）：`VERSION_ABSENT` 唯一的残留读者 `optionalVersion(...)` 也零调用点，
  //     同批删除 ⇒ 上面这句"连同 VERSION_ABSENT"在本批之后逐字成立（本批删的就是最后两个残留）。
  //   ★ 去核结论（账本 §B5-§2）：economy 的 mode 写腿**只有** `economy.GmAdjust` 这两个 kind，
  //     两者在 main/test/工具 schema/catalog **全是 0 调用点**；世界播种走 `production.Seed` 的
  //     `modes[]`。⇒ 按裁定十八第 ② 条连命令 kind 一起删；能力由 production 既有命令面承接
  //     （`production.UpsertProductionMode` / `production.DeactivateProductionMode`，
  //     唯一语义落点 `ProductionModeUpserts`，装配点 `Shell.java:925/:926`）。
  //   ★ 六类引用者清单没有丢：它是 production 停用命令的判据（`ProductionConsumerPort.modeReferences`
  //     → `AppProductionConsumerPort:123`，B4 已逐类平移）。

  /**
   * {@code upsertClassStructure}：{@code {id,modeId,positions?,defaultSharesPerMille?}}（至少给一项）。
   *
   * <p>语义：{@code positions} 逐项 upsert（同一位置 id 的全局表与<b>所有</b>含它的结构副本同值更新；不删除未列出的位置 —— 删除会牵动
   * classStandings/classShares/enterprises 的引用，本阶段不做）；{@code defaultSharesPerMille} 给到即整体替换（给
   * {@code {}} 可清空），未给保持原值。modeId 必须存在；既有结构的 modeId 不可改（换绑要删除重建）。新结构必须至少给一个 position。
   */
  private static Projection upsertClassStructure(
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      JsonNode parameters,
      String reason,
      long day) {
    String label = COMMAND + "." + UPSERT_CLASS_STRUCTURE;
    ClassStructureId id =
        classStructureId(label, EconomyCommandPayloads.requireText(label, parameters, "id"));
    ProductionModeId modeId =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "modeId"));
    // ★★★ B5（裁定十八）：读 production 当刻定义权威（economy 侧不再有 modes 副本）。
    if (definitions.mode(modeId).isEmpty()) {
      throw new IllegalArgumentException(
          label
              + " 的 modeId 必须已存在（production 当刻定义权威）: "
              + modeId.value()
              + "；请先 production.UpsertProductionMode（其 classStructureId 必须已存在）");
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
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      JsonNode parameters,
      String reason,
      long day) {
    String label = COMMAND + "." + UPSERT_CLASS_POSITION;
    ClassPositionId id =
        classPositionId(label, EconomyCommandPayloads.requireText(label, parameters, "id"));
    ProductionModeId modeId =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "modeId"));
    // ★★★ B5（裁定十八）：读 production 当刻定义权威（economy 侧不再有 modes 副本）。
    if (definitions.mode(modeId).isEmpty()) {
      throw new IllegalArgumentException(
          label + " 的 modeId 必须已存在（production 当刻定义权威）: " + modeId.value());
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
      EconomyData base,
      Map<ProductionUnitId, ProductionProcess> units,
      JsonNode parameters,
      String reason,
      long day) {
    String label = COMMAND + "." + UPSERT_PRODUCTION_RELATION;
    ProductionUnitId activity =
        productionProcessId(
            label, EconomyCommandPayloads.requireText(label, parameters, "activity"));
    ProductionProcess unit = units.get(activity);
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
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      JsonNode parameters,
      String reason,
      long day) {
    String label = COMMAND + "." + UPSERT_ASSET_RULE;
    ProductionModeId modeId =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "modeId"));
    // ★★★ B5（裁定十八）：读 production 当刻定义权威（economy 侧不再有 modes 副本）。
    if (definitions.mode(modeId).isEmpty()) {
      throw new IllegalArgumentException(
          label + " 的 modeId 必须已存在（production 当刻定义权威）: " + modeId.value());
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
      EconomyData base,
      io.mosire.simos.production.contract.ProductionDefinitionLookup definitions,
      Map<ProductionUnitId, ProductionProcess> units,
      JsonNode parameters,
      String reason,
      long day) {
    String label = COMMAND + "." + UPSERT_PRODUCTION_ORGANIZATION;
    ProductionModeId modeId =
        productionModeId(label, EconomyCommandPayloads.requireText(label, parameters, "modeId"));
    // ★★★ B5（裁定十八）：读 production 当刻定义权威（economy 侧不再有 modes 副本）。
    if (definitions.mode(modeId).isEmpty()) {
      throw new IllegalArgumentException(
          label + " 的 modeId 必须已存在（production 当刻定义权威）: " + modeId.value());
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
      ProductionProcess unitForHex = units.get(unitId.get());
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
      ProductionProcess unit = units.get(unitId.get());
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

  // ★★ B2b：`setOutputQuantity` / `clearOutputQuantity` 两个投影方法已**整体删除** ——
  //   它们写的 `outputQuantityOverrides` 组件已从 EconomyData 删除。命中这两个 kind 时 `project(...)`
  //   在 switch 里直接给具名拒（见 OUTPUT_QUANTITY_MOVED_REASON）。
  //   ★ B2c（裁定八）：`upsertCandidate` 投影方法**同批删除**（候选面退役）—— 命中该 kind 时 `project(...)`
  //     在 switch 里直接给具名拒（见 PLAN_DEFINITION_MOVED_REASON）。

  // ── Z1：产品产出数量覆盖 ─────────────────────────────────────────────────────────────
  //   ★★ B2b：整段（setOutputQuantity / clearOutputQuantity 的投影 + 它们的解析/拷贝助手）已删除 ——
  //   `outputQuantityOverrides` 组件已从 economy 移除，唯一权威在 production（见 OUTPUT_QUANTITY_MOVED_REASON）。

  // ── P7 形状/解析助手 ──────────────────────────────────────────────────────────────────────

  /** 幂等 no-op 的 Projection：投影就是 base、changeSet Unchanged、不报任何 Change。 */
  private static Projection noOp(String adjustment, String reason, long day, EconomyData base) {
    return new Projection(
        adjustment, reason, day, base, EconomyChangeSet.between(base, base), List.of());
  }

  // ★ B12 / DEF-T6-4 #2（UPM_UNCALLED_PRIVATE_METHOD）：`optionalVersion(...)` 已删 —— 全仓零调用点
  //   （B5 删掉两个 mode 版本推进 kind 之后它就是纯死代码）；`VERSION_ABSENT` 哨兵随之删除，
  //   下面的 B5 记录（"连同 `VERSION_ABSENT` / 全部 mode 版本推进判据"）至此逐字成立。
  //   删除不改任何入参校验：它只是把 `EconomyCommandPayloads.optionalInt` 再包一层，没有调用方就没有语义。

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

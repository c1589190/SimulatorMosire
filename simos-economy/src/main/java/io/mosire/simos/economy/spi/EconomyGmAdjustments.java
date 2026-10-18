package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.production.contract.ProductionProcess;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>{@code economy.GmAdjust} 的纯函数项目</b>（E6b + P7）：handler（{@code simos-economy}）与 GM 窄写工具
 * {@code simos.economy.adjust}（{@code simos-app}）<b>共用同一份</b>调整语义 —— 载荷解析、白名单拒绝、前后差异与 {@link
 * EconomyChangeSet} 都在这里算一次，两处只做各自的边界折叠（handler → {@code Rejected}/`Applied`；工具 → {@code
 * BAD_REQUEST} /预览视图）。
 *
 * <p>★★ <b>十条源状态白名单</b>（{@link #ADJUSTMENTS}）：
 *
 * <ul>
 *   <li><b>旧表（一）</b>：{@link #FORGIVE_DEBT}（{@link DebtContractBook#forgive} + {@link
 *       EconomyData#withDebtContracts}）；
 *   <li><b>P7 阶层/关系/规则/组织编辑（六）</b>：{@link #UPSERT_CLASS_STRUCTURE}/{@link #UPSERT_CLASS_POSITION}/
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

  static final String COMMAND = EconomyGmAdjustHandler.TYPE;

  /**
   * ★★ <b>六条源状态白名单</b>（拒绝消息与 handler 兜底共用同一顺序；唯一拼写点在各自常量）。
   *
   * <p>★ B2b/B2c：{@code upsertCandidate} / {@code setOutputQuantity} / {@code clearOutputQuantity}
   * 三项已**退出白名单**（前者随候选面退役、后两者随 {@code outputQuantityOverrides} 组件迁出 economy）—— 它们不再改任何 economy
   * 状态，命中时给具名拒绝（见 {@link #OUTPUT_QUANTITY_MOVED_REASON} / {@link #PLAN_DEFINITION_MOVED_REASON}）。
   *
   * <p>★★ <b>B5（§13.15.2 裁定十八）：总数 12 → 9 → 7 的第二次收缩</b>：原 P7 八 kind 里的 {@code
   * upsertProductionMode}/{@code deactivateProductionMode} 已随 {@code EconomyData.modes} 组件删除而退出
   * 白名单（见 {@link #PRODUCTION_MODE_MOVED_REASON}）⇒ 现存 <b>六</b> 项（{@link #ADJUSTMENTS} 逐项即真值）。
   */
  static final List<String> ADJUSTMENTS =
      List.of(
          FORGIVE_DEBT,
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
      case FORGIVE_DEBT -> GmHouseholdAdjustments.forgive(base, parameters, reason, day);
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
          GmProductionAdjustments.upsertClassStructure(base, definitions, parameters, reason, day);
      case UPSERT_CLASS_POSITION ->
          GmProductionAdjustments.upsertClassPosition(base, definitions, parameters, reason, day);
      case UPSERT_PRODUCTION_RELATION ->
          GmProductionAdjustments.upsertProductionRelation(base, units, parameters, reason, day);
      case UPSERT_ASSET_RULE ->
          GmMarketAdjustments.upsertAssetRule(base, definitions, parameters, reason, day);
      case UPSERT_PRODUCTION_ORGANIZATION ->
          GmProductionAdjustments.upsertProductionOrganization(
              base, definitions, units, parameters, reason, day);
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
      default -> throw GmAdjustmentSupport.derivedRejection(adjustment);
    };
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
   * @param component 源状态组件名（如 {@code debtContracts} / {@code classStructures}）
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

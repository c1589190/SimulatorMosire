package io.mosire.simos.production.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.contract.ClassSlot;
import io.mosire.simos.production.contract.ExperienceGrowth;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionAvailabilityCondition;
import io.mosire.simos.production.contract.ProductionConsumerPort;
import io.mosire.simos.production.contract.ProductionRoleDefinition;
import io.mosire.simos.production.contract.RolePayment;
import io.mosire.simos.production.spi.IndustryUpserts;
import io.mosire.simos.production.spi.UpsertIndustryHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ <b>{@code simos.economy.upsertIndustry}（Z1a）：GM 产业模板创建/修改窄工具</b> —— {@code
 * production.UpsertIndustry} 命令的预览 / 提交入口（照 {@code simos.economy.adjust} 的 GM 经济写工具先例）。
 *
 * <p>★★ <b>载荷逐字透传</b>：{@code payloadJson} 与 生产定义种子的 {@code industries[]} 节点同形； 工具不做第二套字段翻译，直接原样交给
 * {@link IndustryUpserts#project(ProductionData, ProductionConsumerPort, String)} 预览、 原样装进 {@link
 * CommandEnvelope} 提交（ADR-1 R11：Core 逐字节转交）。
 *
 * <ul>
 *   <li>{@code preview=true}（缺省）：读当前状态跑同一个纯函数，返回引用计数 + 前后模板 + changeSetEmpty； <b>不提交、不写状态</b>；
 *   <li>{@code preview=false}：同一条载荷走 {@code ToolWritePort.submitWithReceipt}（一条命令 = 一条
 *       revision）；逐值相同的幂等重放直接 返回 {@code noop}（不落空 revision）。
 * </ul>
 *
 * <p>★★ <b>业务规则与命令层同源</b>：id 不存在 ⇒ 创建（格必须有已激活经济状态；版本不得倒退/撞号）；id 已存在 ⇒ 仅当无 {@code units}/{@code
 * assetShares}/{@code relations} 引用时原地全量替换，被引用 ⇒ {@code REJECTED} 并指路新版本 id。 新版本 = 新的 kind 后缀
 * id（{@code office@0_0 → office_v2@0_0}）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有本工具，{@code 决策人白名单} 也不含它；命令本身不打
 * {@code DmUsableCommand} ⇒ 令 / {@code RegisterEffect} / 决策人 catalog 三条路径同样排除。★ 工具名不是命令类型 ⇒ 不进
 * {@code CatalogTool} 的 PAYLOAD_HINTS（命令类型 {@code production.UpsertIndustry} 本体由 catalog 单独列出）。
 */
public final class EconomyUpsertIndustryTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.economy.upsertIndustry";

  /** 本工具只写 production 命名空间（定义权威）。 */
  private static final ResourceManifest ECONOMY_WRITE =
      ResourceManifest.of(Map.of(ToolResources.PRODUCTION_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolResources.PRODUCTION_NAMESPACE, "*"));

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final ProductionConsumerPort consumers;

  /**
   * @param writes 唯一写入口（preview=true 时一个字节都不写）
   * @param reads 只读入口（读 branch/revision 的当前状态；预览与提交取同一个坐标）
   * @param consumers ★★ D-032 B4（§13.14.5 第 1 条）：跨切片使用情况端口，<b>实现在组合根</b>（app 装配期注入）；引用计数的 {@code
   *     units} 维从此读 production 的实例只读投影（B2b 后 economy 侧已无 {@code units} 组件）。
   */
  public EconomyUpsertIndustryTool(
      ToolWritePort writes, ToolReadPort reads, ProductionConsumerPort consumers) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.consumers =
        Objects.requireNonNull(consumers, "consumers（组合根必须注入 ProductionConsumerPort 实现）");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 创建/修改产业模板（production.UpsertIndustry 的窄封装；一条命令 = 一条 revision）。"
        + "payloadJson 与生产定义种子 industries[] 节点同形："
        + "{id(<kind>@<q>_<r>，新版本写 kind：office_v2@0_0), name, regime, cycleDays(≥1),"
        + " capacityPerUnit(非空且逐值>0), dailyInputPerUnit?, dailyLaborPerUnit?, laborPerUnit?,"
        + " outputPerUnit?(空 map = 无商品产出), moneyOutputPerUnit?(★ W5：{币种:毫/规模·周期}，空 map = 本产业"
        + " 不铸币；铸币产出走账户余额，组织者 = unit.operator，另受 Government.mintScalePerCycle"
        + " 上限约束（★ W5-fix5：决策人预期即权威，实铸 = min(产出请求, 该旋钮)，没有第二条闸门）),"
        + " cycleInputPerUnit?,"
        + " slots[{id,name,laborParticipationPerMille}], allocation({@class:split,两权重和=1000}),"
        + " roles?[{id,name,socialClass?,sharePerMille,intraRoleSplit,standardLaborMilliPerCycle,"
        + " experienceGrowth?{track,basis,coefficientPerMille,maxPerTickValue?,countsManagement?},"
        + " conditions?,payment(product_share|{kind:money_wage,currency?:LOCAL_MARKET_AREA})}],"
        + " roleShareRemainderRule?(unallocated|to_last_role), upgradesTo?[planId], mode?}。"
        + "roles 缺省 = 单 operator 角色（share=1000/product_share）；份额合计≤1000、id 不重复；"
        + "PRODUCT_SHARE ⇒ share>0，MONEY_WAGE ⇒ share=0。"
        + "id 不存在 ⇒ 创建（格必须已有已激活经济状态；同格同 kind 版本不得倒退/撞号）；"
        + "id 已存在 ⇒ 仅当无 units/assetShares/relations 引用时原地全量替换，被引用 ⇒ 具名拒并指路新版本 id；"
        + "逐值相同的重放 = 幂等 noop（不落空 revision）。preview=true（缺省）只算前后差异、不写；"
        + "preview=false 提交同一条 payloadJson。★ 只在 GM 桶，决策人不可调用。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "payloadJson",
        ToolSchema.prop(
            "string",
            "产业模板 JSON 文本（与生产定义种子的 industries[] 节点同形，逐字透传给命令）："
                + "{id,name,regime,cycleDays,capacityPerUnit,dailyInputPerUnit?,dailyLaborPerUnit?,"
                + "laborPerUnit?,outputPerUnit?,moneyOutputPerUnit?,cycleInputPerUnit?,slots,allocation,"
                + "roles?,roleShareRemainderRule?,upgradesTo?,mode?}；roles 缺省 = operator 单角色；"
                + "money_wage 只接受 kind/currency（currency 仅 LOCAL_MARKET_AREA）；"
                + "id=<kind>@<q>_<r>，新版本 = 新 kind 后缀 id（office_v2@0_0）"));
    props.put(
        "preview",
        ToolSchema.prop(
            "boolean", "true（缺省）= 只算引用计数/前后差异、不写；false = 提交 production.UpsertIndustry"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("payloadJson"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return ECONOMY_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        name()
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String payloadJson = ToolSchema.requiredText(args, "payloadJson");
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        throw new IllegalArgumentException("expectedRevision 不得为负: " + expectedRevisionArg);
      }
      if (!preview && expectedRevisionArg == null) {
        throw new IllegalArgumentException(
            "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      // ★ preview / apply 的共同输入：同一个坐标上的 base 状态 + 同一条 payloadJson。
      SimulationState state =
          reads.stateAt(branch, expectedRevision < 0L ? null : new RevisionId(expectedRevision));
      ProductionData productionBase = productionDataOf(state);
      IndustryUpserts.Projection projection =
          IndustryUpserts.project(productionBase, state, consumers, payloadJson);
      Map<String, Object> view = diffView(projection, payloadJson);
      if (preview) {
        view.put("preview", true);
        view.put("submitted", false);
        return ToolResults.ok(view);
      }
      if (projection.changeSet().isEmpty()) {
        // ★ 逐值相同的幂等重放：不提交空变更（一个 revision 都不落）。
        view.put("preview", false);
        view.put("submitted", false);
        view.put("noop", true);
        view.put("noopReason", "逐值相同的幂等重放（changeSet 为空）");
        return ToolResults.ok(view);
      }
      return commit(view, payloadJson, branch, expectedRevision);
    } catch (IndustryUpserts.Rejection e) {
      // ★ 业务拒绝（被引用原地改 / 版本倒退 / 空白格 / 经济未激活）：具名 REJECTED，零 revision。
      return ToolResult.error("REJECTED", e.getMessage());
    } catch (IllegalArgumentException e) {
      // ★ 载荷形状/字段值/坏 id 等：BAD_REQUEST，不提交半笔。
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与 EconomyAdjustTool / AbstractNarrowWriteTool 同一条：资源拒因原样逃到 ToolCallAuthorizer 边界。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR",
          "产业模板 upsert 工具失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 提交阶段：同一条 payloadJson 原样装信封、走唯一写入口，把三结局与预览差异拼在一份结果里。 */
  private ToolResult commit(
      Map<String, Object> view, String payloadJson, BranchId branch, long expectedRevision) {
    ToolWritePort.Receipt receipt =
        writes.submitWithReceipt(
            branch,
            () -> new RevisionId(expectedRevision),
            UpsertIndustryHandler.TYPE,
            payloadJson);
    String commandId = receipt.commandId();
    view.put("preview", false);
    view.put("submitted", true);
    view.put("commandId", commandId);
    return switch (receipt.outcome()) {
      case ToolWritePort.Outcome.Committed committed -> {
        view.put("submission", ToolResults.committedView(committed.ref(), commandId, commandId));
        yield ToolResults.ok(view);
      }
      case ToolWritePort.Outcome.Conflict conflict -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "conflict");
        submission.put("current", ToolResults.stateRef(conflict.current()));
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolResults.json(view));
      }
      case ToolWritePort.Outcome.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", rejected.reason());
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolResults.json(view));
      }
    };
  }

  /** 预览/结果共同部分：引用计数、前后模板、变更集是否为空、待提交命令（type + payloadJson 原样）。 */
  private static Map<String, Object> diffView(
      IndustryUpserts.Projection projection, String payloadJson) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", projection.industry().id().value());
    view.put("created", projection.created());
    view.put("noop", projection.changeSet().isEmpty());
    view.put("references", referenceView(projection.references()));
    view.put(
        "industryBefore",
        projection.previous() == null ? null : industryView(projection.previous()));
    view.put("industryAfter", industryView(projection.industry()));
    view.put("changeSetEmpty", projection.changeSet().isEmpty());
    Map<String, Object> command = new LinkedHashMap<>();
    command.put("type", UpsertIndustryHandler.TYPE);
    command.put("payloadJson", payloadJson);
    view.put("commandsPreview", List.of(command));
    return view;
  }

  private static Map<String, Object> referenceView(
      ProductionConsumerPort.IndustryReferenceCounts references) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("units", references.units());
    view.put("assetShares", references.assetShares());
    view.put("relations", references.relations());
    view.put("total", references.total());
    return view;
  }

  /** 模板的只读视图（字段与载荷同形；表键转字符串，避免视图层依赖键类型的 Jackson 键序列化）。 */
  private static Map<String, Object> industryView(IndustryDefinitionView industry) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", industry.id().value());
    view.put("name", industry.name());
    view.put("regime", industry.regime().value());
    view.put("cycleDays", industry.cycleDays());
    view.put("capacityPerUnit", assetAmounts(industry.capacityPerUnit()));
    view.put("dailyInputPerUnit", assetCommodityAmounts(industry.dailyInputPerUnit()));
    view.put("dailyLaborPerUnit", industry.dailyLaborPerUnit());
    view.put("laborPerUnit", industry.laborPerUnit());
    view.put("outputPerUnit", commodityAmounts(industry.outputPerUnit()));
    // ★★ W5：铸币产出率（空表 = 本产业不铸币）—— 读口与商品产出并排，读者不必去猜"这个产业铸不铸"。
    Map<String, Object> moneyOutput = new LinkedHashMap<>();
    for (Map.Entry<CurrencyId, Long> minted : industry.moneyOutputPerUnit().perUnit().entrySet()) {
      moneyOutput.put(minted.getKey().value(), minted.getValue());
    }
    view.put("moneyOutputPerUnit", moneyOutput);
    view.put("cycleInputPerUnit", assetCommodityAmounts(industry.cycleInputPerUnit()));
    List<Map<String, Object>> slots = new ArrayList<>(industry.slots().size());
    for (ClassSlot slot : industry.slots()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", slot.id().value());
      row.put("name", slot.name());
      row.put("laborParticipationPerMille", slot.laborParticipationPerMille());
      slots.add(row);
    }
    view.put("slots", List.copyOf(slots));
    view.put("allocation", industry.allocation());
    List<Map<String, Object>> roles = new ArrayList<>(industry.roles().size());
    for (ProductionRoleDefinition role : industry.roles()) {
      roles.add(roleView(role));
    }
    view.put("roles", List.copyOf(roles));
    view.put("roleShareRemainderRule", industry.roleShareRemainderRule().key());
    List<String> upgradesTo = new ArrayList<>(industry.upgradesTo().size());
    for (io.mosire.simos.economy.api.id.IndustryId upgrade : industry.upgradesTo()) {
      upgradesTo.add(upgrade.value());
    }
    view.put("upgradesTo", List.copyOf(upgradesTo));
    view.put(
        "mode",
        industry.mode().map(io.mosire.simos.economy.api.id.ProductionModeId::value).orElse(null));
    return view;
  }

  /** 角色的只读视图（与载荷同形；支付策略只读展示，不在这里解释/结算）。 */
  private static Map<String, Object> roleView(ProductionRoleDefinition role) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", role.id().value());
    row.put("name", role.name());
    row.put("socialClass", role.socialClass().map(sc -> sc.value()).orElse(null));
    row.put("sharePerMille", role.sharePerMille());
    row.put("intraRoleSplit", role.intraRoleSplit().key());
    row.put("standardLaborMilliPerCycle", role.standardLaborMilliPerCycle());
    row.put(
        "experienceGrowth",
        role.experienceGrowth().map(EconomyUpsertIndustryTool::experienceGrowthView).orElse(null));
    List<Map<String, Object>> conditions = new ArrayList<>(role.conditions().size());
    for (ProductionAvailabilityCondition condition : role.conditions()) {
      Map<String, Object> conditionView = new LinkedHashMap<>();
      conditionView.put("type", condition.getClass().getSimpleName());
      conditionView.put("description", condition.describe());
      conditions.add(conditionView);
    }
    row.put("conditions", List.copyOf(conditions));
    row.put("payment", paymentView(role.payment()));
    return row;
  }

  private static Map<String, Object> experienceGrowthView(ExperienceGrowth growth) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("track", growth.track().value());
    view.put("basis", growth.basis().key());
    view.put("coefficientPerMille", growth.coefficientPerMille());
    view.put("maxPerTickValue", growth.maxPerTickValue());
    view.put("countsManagement", growth.countsManagement());
    return view;
  }

  private static Object paymentView(RolePayment payment) {
    if (payment instanceof RolePayment.ProductShare) {
      return "product_share";
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("kind", "money_wage");
    view.put("currency", "LOCAL_MARKET_AREA");
    return view;
  }

  private static Map<String, Object> assetAmounts(Map<AssetKind, Long> amounts) {
    Map<String, Object> view = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Long> entry : amounts.entrySet()) {
      view.put(entry.getKey().name(), entry.getValue());
    }
    return view;
  }

  private static Map<String, Object> commodityAmounts(Map<CommodityId, Long> amounts) {
    Map<String, Object> view = new LinkedHashMap<>();
    for (Map.Entry<CommodityId, Long> entry : amounts.entrySet()) {
      view.put(entry.getKey().value(), entry.getValue());
    }
    return view;
  }

  private static Map<String, Object> assetCommodityAmounts(
      Map<AssetKind, Map<CommodityId, Long>> amounts) {
    Map<String, Object> view = new LinkedHashMap<>();
    for (Map.Entry<AssetKind, Map<CommodityId, Long>> entry : amounts.entrySet()) {
      view.put(entry.getKey().name(), commodityAmounts(entry.getValue()));
    }
    return view;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }

  /** production 切片状态（装配故障当场炸）。 */
  private static ProductionData productionDataOf(SimulationState state) {
    io.mosire.simos.util.state.Snapshot snapshot =
        state
            .module("production")
            .orElseThrow(() -> new IllegalStateException("state 里没有 production 切片（装配故障）"));
    if (!(snapshot instanceof ProductionSnapshot productionSnapshot)) {
      throw new IllegalStateException(
          "state 的 production 切片不是 ProductionSnapshot: " + snapshot.getClass().getName());
    }
    return productionSnapshot.state();
  }
}

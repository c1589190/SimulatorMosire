package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.model.ClassSlot;
import io.mosire.simos.economy.model.Industry;
import io.mosire.simos.economy.spi.EconomyIndustryUpserts;
import io.mosire.simos.economy.spi.EconomyUpsertIndustryHandler;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * ★★ <b>{@code simos.economy.upsertIndustry}（Z1a）：GM 产业模板创建/修改窄工具</b> —— {@code
 * economy.UpsertIndustry} 命令的预览 / 提交入口（照 {@code simos.economy.adjust} 的 GM 经济写工具先例）。
 *
 * <p>★★ <b>载荷逐字透传</b>：{@code payloadJson} 与 {@code economy.Seed} 的 {@code industries[]} 节点同形；
 * 工具不做第二套字段翻译，直接原样交给 {@link EconomyIndustryUpserts#project(EconomyData, String)} 预览、 原样装进 {@link
 * CommandEnvelope} 提交（ADR-1 R11：Core 逐字节转交）。
 *
 * <ul>
 *   <li>{@code preview=true}（缺省）：读当前状态跑同一个纯函数，返回引用计数 + 前后模板 + changeSetEmpty； <b>不提交、不写状态</b>；
 *   <li>{@code preview=false}：同一条载荷走 {@link CoreSimos#submit}（一条命令 = 一条 revision）；逐值相同的幂等重放直接 返回
 *       {@code noop}（不落空 revision）。
 * </ul>
 *
 * <p>★★ <b>业务规则与命令层同源</b>：id 不存在 ⇒ 创建（格必须有已激活经济状态；版本不得倒退/撞号）；id 已存在 ⇒ 仅当无 {@code units}/{@code
 * assetShares}/{@code relations} 引用时原地全量替换，被引用 ⇒ {@code REJECTED} 并指路新版本 id。 新版本 = 新的 kind 后缀
 * id（{@code office@0_0 → office_v2@0_0}）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有本工具，{@code
 * DecisionCallerFactory.WHITELIST} 也不含它；命令本身标了 {@code GmOnlyCommand} ⇒ 令 / {@code RegisterEffect} /
 * 决策人 catalog 三条路径同样排除。★ 工具名不是命令类型 ⇒ 不进 {@code CatalogTool} 的 PAYLOAD_HINTS（命令类型 {@code
 * economy.UpsertIndustry} 本体由 catalog 单独列出）。
 */
public final class EconomyUpsertIndustryTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.economy.upsertIndustry";

  /** 本工具只写 economy 命名空间（GM 侧 economy 未受限 ⇒ 逐条判通过）。 */
  private static final ResourceManifest ECONOMY_WRITE =
      ResourceManifest.of(Map.of(ToolSupport.ECONOMY_NAMESPACE, ResourcePolicy.UNRESTRICTED));

  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolSupport.ECONOMY_NAMESPACE, "*"));

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;

  /**
   * @param core 唯一写入口（preview=true 时一个字节都不写）
   * @param query 只读入口（读 branch/revision 的当前状态；预览与提交取同一个坐标）
   * @param initiator 落盘时的发起者
   */
  public EconomyUpsertIndustryTool(CoreSimos core, QueryService query, String initiator) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 创建/修改产业模板（economy.UpsertIndustry 的窄封装；一条命令 = 一条 revision）。"
        + "payloadJson 与 economy.Seed 的 industries[] 节点同形："
        + "{id(<kind>@<q>_<r>，新版本写 kind：office_v2@0_0), name, regime, cycleDays(≥1),"
        + " capacityPerUnit(非空且逐值>0), dailyInputPerUnit?, dailyLaborPerUnit?, laborPerUnit?,"
        + " outputPerUnit?(空 map = 无商品产出), cycleInputPerUnit?,"
        + " slots[{id,name,laborParticipationPerMille}], allocation({@class:split,两权重和=1000})}。"
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
        ToolSupport.prop(
            "string",
            "产业模板 JSON 文本（与 economy.Seed 的 industries[] 节点同形，逐字透传给命令）："
                + "{id,name,regime,cycleDays,capacityPerUnit,dailyInputPerUnit?,dailyLaborPerUnit?,"
                + "laborPerUnit?,outputPerUnit?,cycleInputPerUnit?,slots,allocation}；"
                + "id=<kind>@<q>_<r>，新版本 = 新 kind 后缀 id（office_v2@0_0）"));
    props.put(
        "preview",
        ToolSupport.prop("boolean", "true（缺省）= 只算引用计数/前后差异、不写；false = 提交 economy.UpsertIndustry"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSupport.schema(props, List.of("payloadJson"));
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
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolSupport.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String payloadJson = ToolSupport.requiredText(args, "payloadJson");
      boolean preview = ToolSupport.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
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
          query.stateAt(
              expectedRevision < 0L
                  ? QueryTarget.head(branch)
                  : QueryTarget.at(branch, new RevisionId(expectedRevision)));
      EconomyData base = ToolSupport.economyData(state);
      EconomyIndustryUpserts.Projection projection =
          EconomyIndustryUpserts.project(base, payloadJson);
      Map<String, Object> view = diffView(projection, payloadJson);
      if (preview) {
        view.put("preview", true);
        view.put("submitted", false);
        return ToolSupport.ok(view);
      }
      if (projection.changeSet().isEmpty()) {
        // ★ 逐值相同的幂等重放：不提交空变更（一个 revision 都不落）。
        view.put("preview", false);
        view.put("submitted", false);
        view.put("noop", true);
        view.put("noopReason", "逐值相同的幂等重放（changeSet 为空）");
        return ToolSupport.ok(view);
      }
      return commit(view, payloadJson, branch, expectedRevision);
    } catch (EconomyIndustryUpserts.Rejection e) {
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
    String commandId = UUID.randomUUID().toString();
    CommandEnvelope envelope =
        new CommandEnvelope(
            commandId,
            commandId,
            initiator,
            branch,
            new RevisionId(expectedRevision),
            EconomyUpsertIndustryHandler.TYPE,
            payloadJson);
    CommandResult result = core.submit(envelope);
    view.put("preview", false);
    view.put("submitted", true);
    view.put("commandId", commandId);
    return switch (result) {
      case CommandResult.Committed committed -> {
        view.put("submission", ToolSupport.committedView(committed.ref(), commandId, commandId));
        yield ToolSupport.ok(view);
      }
      case CommandResult.Conflict conflict -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "conflict");
        submission.put("current", ToolSupport.stateRef(conflict.current()));
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolSupport.json(view));
      }
      case CommandResult.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", rejected.reason());
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolSupport.json(view));
      }
    };
  }

  /** 预览/结果共同部分：引用计数、前后模板、变更集是否为空、待提交命令（type + payloadJson 原样）。 */
  private static Map<String, Object> diffView(
      EconomyIndustryUpserts.Projection projection, String payloadJson) {
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
    command.put("type", EconomyUpsertIndustryHandler.TYPE);
    command.put("payloadJson", payloadJson);
    view.put("commandsPreview", List.of(command));
    return view;
  }

  private static Map<String, Object> referenceView(
      EconomyIndustryUpserts.ReferenceCounts references) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("units", references.units());
    view.put("assetShares", references.assetShares());
    view.put("relations", references.relations());
    view.put("total", references.total());
    return view;
  }

  /** 模板的只读视图（字段与载荷同形；表键转字符串，避免视图层依赖键类型的 Jackson 键序列化）。 */
  private static Map<String, Object> industryView(Industry industry) {
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
}

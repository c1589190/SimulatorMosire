package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ResourceScope;
import io.mosire.agentlib.permission.ResourceScopeMap;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.DecisionScopeFunctions;
import io.mosire.simos.app.decision.ProposalCatalog;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DecisionPacketId;
import io.mosire.simos.sd.model.CallStatus;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.DecisionPacket;
import io.mosire.simos.sd.model.FormattedCall;
import io.mosire.simos.sd.model.PacketStatus;
import io.mosire.simos.sd.spi.UpsertDecisionPacketHandler;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * {@code simos.sd.propose}（D2 决策包计划 §3.1）：决策人把一个**真实工具名 + JSON 参数**提议进本 tick 的 DRAFT 决策包。
 *
 * <p>★★ **流程**（一条 revision 一次；任何一步失败 = 零写入）：
 *
 * <ol>
 *   <li>身份从 {@code context.identity()} 派生（载荷里的 proposer 一律不采信）；
 *   <li>工具必须在本批 {@link ProposalCatalog} 初始清单、且被该决策人的 {@code allowedTools} 允许（空 = 全清单）；
 *   <li>取 base 状态（branch/expectedRevision 可选，缺省 head）⇒ tick = 世界当前 tick；包 id = {@code
 *       pkt-<proposerId>-<tick>}；已存在且非 DRAFT ⇒ 具名拒；
 *   <li>用系统预览上下文跑目标工具的真 {@code preview=true}（不落盘）；
 *   <li>从 args + preview 提取跨命名空间目标，逐条按调用者现算 scope 校验（越界 ⇒ FORBIDDEN、不写包）；
 *   <li>追加一条 {@link FormattedCall}，经 {@code sd.UpsertDecisionPacket} 落一条 revision。
 * </ol>
 *
 * <p>★ 敏感写（{@link ToolGate.Ask}）：决策人链路走待批，与既有窄写同制。
 */
public final class ProposeCallTool implements AgentTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.sd.propose";

  /** 本工具只写 sd 命名空间（自己的 packet 前缀由身份派生）。 */
  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final CoreSimos core;
  private final QueryService query;
  private final String initiator;
  private final String mapId;
  private final ProposalCatalog catalog;

  public ProposeCallTool(
      CoreSimos core, QueryService query, String initiator, String mapId, ProposalCatalog catalog) {
    this.core = Objects.requireNonNull(core, "core");
    this.query = Objects.requireNonNull(query, "query");
    this.initiator = Objects.requireNonNull(initiator, "initiator");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "决策人提议：把工具名 + JSON 参数格式化成一个 FormattedCall 追加进本 tick 的 DRAFT 决策包。"
        + "参数 {tool(必填；必须是 ProposalCatalog 初始清单里的 simos.* 工具), args?(目标工具参数对象，缺省空), "
        + "intent?(可选 NL 意图，写包级 intent), branch?(缺省 "
        + ToolSupport.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。"
        + "流程：catalog 校验 → 真 preview(preview=true，不落盘) → 逐目标 scope 校验（越界 = FORBIDDEN）→ "
        + "一条 sd.UpsertDecisionPacket。同 proposer 同 tick 一个包；已提交/已裁决 ⇒ BAD_REQUEST。"
        + "返回 {packetId, status, callIndex, tool, targets, preview, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("tool", ToolSupport.prop("string", "要提议的真实工具名（须在 ProposalCatalog 初始清单内）"));
    props.put("args", ToolSupport.prop("object", "目标工具的 JSON 参数对象（缺省 {}）"));
    props.put("intent", ToolSupport.prop("string", "可选：这条提议的自然语言意图（写进包的 intent）"));
    props.put("branch", ToolSupport.prop("string", "分支名（缺省 " + ToolSupport.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSupport.prop("integer", "读取/提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSupport.schema(props, List.of("tool"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "决策提议 tool="
            + args.get("tool")
            + " intent="
            + args.getOrDefault("intent", "")
            + " branch="
            + args.getOrDefault("branch", ToolSupport.DEFAULT_BRANCH),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      Optional<String> decisionMakerId =
          DecisionCallerFactory.decisionMakerIdOf(context.identity());
      if (decisionMakerId.isEmpty()) {
        return ToolResult.error(
            "FORBIDDEN", "simos.sd.propose 只能由决策人身份调用（身份里没有 decision-maker: 前缀）");
      }
      DecisionMakerId proposerId = DecisionMakerId.parse(decisionMakerId.get());
      String toolName = ToolSupport.requiredText(args, "tool");
      Map<String, Object> innerArgs = optionalMap(args, "args");
      Optional<String> intent = optionalNonBlankText(args, "intent");
      BranchId branch =
          new BranchId(ToolSupport.optionalText(args, "branch", ToolSupport.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSupport.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      SimulationState state =
          expectedRevisionArg == null
              ? query.stateAt(QueryService.QueryTarget.head(branch))
              : query.stateAt(
                  QueryService.QueryTarget.at(branch, new RevisionId(expectedRevisionArg)));
      SdState sd = ToolSupport.sdState(state);
      DecisionMaker dm = sd.decisionMakers().get(proposerId);
      if (dm == null) {
        return ToolResult.error("FORBIDDEN", "决策人不存在: " + proposerId.value());
      }
      if (!catalog.contains(toolName)) {
        return ToolResult.error("BAD_REQUEST", "工具不在 ProposalCatalog 初始清单，不可 propose: " + toolName);
      }
      if (!dm.allowedTools().isEmpty() && !dm.allowedTools().contains(toolName)) {
        return ToolResult.error("FORBIDDEN", "工具不在该决策人的 allowedTools 白名单: " + toolName);
      }
      // ★ 自指资源围栏：只写自己的 packet 前缀（与 DecisionCallerFactory.selfDecisionScope 的第二条前缀同源）。
      ToolSupport.requireAll(
          context,
          Operation.WRITE,
          List.of(ToolSupport.resourceSd("decision-packet", proposerId.value())));

      long tick = state.meta().timestamp().tick();
      DecisionPacketId packetId = DecisionPacketId.parse(packetIdOf(proposerId, tick));
      DecisionPacket existing = sd.decisionPackets().get(packetId);
      if (existing != null && existing.status() != PacketStatus.DRAFT) {
        return ToolResult.error(
            "BAD_REQUEST",
            "本 tick 决策包已提交/已裁决，不能再 propose: " + packetId.value() + " status=" + existing.status());
      }
      // ★ 真预览（系统上下文、preview=true、不落盘）+ 跨命名空间目标提取。
      Map<String, Object> preview = catalog.preview(toolName, state, innerArgs);
      List<CommandTarget> targets = catalog.targets(toolName, state, innerArgs, preview);
      if (targets.isEmpty()) {
        return ToolResult.error("BAD_REQUEST", "未能从参数/预览提取到任何可寻址目标，拒绝 propose: " + toolName);
      }
      ResourceScopeMap scopes =
          DecisionCallerFactory.resourceScopesFor(
              DecisionScopeFunctions.defaults(), dm, state, mapId);
      Optional<String> violation = firstScopeViolation(state, scopes, targets);
      if (violation.isPresent()) {
        return ToolResult.error("FORBIDDEN", violation.get());
      }

      int callIndex = nextCallIndex(existing);
      FormattedCall call =
          new FormattedCall(
              callIndex,
              toolName,
              ToolSupport.json(innerArgs),
              targets,
              ToolSupport.json(preview),
              List.of("scope-ok", "preview-ok"),
              CallStatus.PENDING,
              Optional.empty());
      DecisionPacket packet =
          newPacket(
              existing,
              packetId,
              branch,
              tick,
              proposerId,
              intent,
              call,
              state.meta().ref().revision().value());
      String commandId = UUID.randomUUID().toString();
      CommandEnvelope envelope =
          new CommandEnvelope(
              commandId,
              commandId,
              initiator,
              branch,
              new RevisionId(state.meta().ref().revision().value()),
              UpsertDecisionPacketHandler.TYPE,
              DecisionPacketPayloads.upsert(packet));
      CommandResult result = core.submit(envelope);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("packetId", packetId.value());
      view.put("status", PacketStatus.DRAFT.name());
      view.put("callIndex", callIndex);
      view.put("tool", toolName);
      view.put("targets", targetsView(targets));
      view.put("preview", preview);
      return DecisionPacketPayloads.fold(result, view, commandId);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e; // 资源拒因原样逃到 ToolCallAuthorizer 边界，折成 RESOURCE_DENIED
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "propose 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── 包构造 ─────────────────────────────────────────────────────────────────────────

  private static String packetIdOf(DecisionMakerId proposerId, long tick) {
    return "pkt-" + proposerId.value() + "-" + tick;
  }

  private static int nextCallIndex(DecisionPacket existing) {
    if (existing == null || existing.calls().isEmpty()) {
      return 0;
    }
    int max = -1;
    for (FormattedCall call : existing.calls()) {
      max = Math.max(max, call.callIndex());
    }
    return max + 1;
  }

  private static DecisionPacket newPacket(
      DecisionPacket existing,
      DecisionPacketId packetId,
      BranchId branch,
      long tick,
      DecisionMakerId proposerId,
      Optional<String> intent,
      FormattedCall call,
      long baseRevision) {
    List<FormattedCall> calls = new ArrayList<>();
    String finalIntent = "";
    long createdAtRevision = baseRevision;
    Optional<String> decidedBy = Optional.empty();
    OptionalLong decidedAtRevision = OptionalLong.empty();
    Optional<String> reasonInfoId = Optional.empty();
    Optional<String> decisionNote = Optional.empty();
    if (existing != null) {
      calls.addAll(existing.calls());
      finalIntent = existing.intent();
      createdAtRevision = existing.createdAtRevision();
      decidedBy = existing.decidedBy();
      decidedAtRevision = existing.decidedAtRevision();
      reasonInfoId = existing.reasonInfoId();
      decisionNote = existing.decisionNote();
    }
    if (intent.isPresent()) {
      finalIntent = intent.get();
    }
    calls.add(call);
    return new DecisionPacket(
        packetId,
        branch.value(),
        tick,
        proposerId,
        PacketStatus.DRAFT,
        finalIntent,
        calls,
        createdAtRevision,
        decidedBy,
        decidedAtRevision,
        reasonInfoId,
        decisionNote);
  }

  // ── scope 校验 ─────────────────────────────────────────────────────────────────────

  /** 逐条目标按调用者现算 scope 判越界；返回第一条越界的具名拒因。 */
  private static Optional<String> firstScopeViolation(
      SimulationState state, ResourceScopeMap scopes, List<CommandTarget> targets) {
    GameMap gameMap = null;
    for (CommandTarget target : targets) {
      ResourceScope scope = scopes.declaredScope(target.namespace());
      String problem;
      if (ToolSupport.MAP_NAMESPACE.equals(target.namespace())) {
        if (gameMap == null) {
          gameMap = ToolSupport.gameMap(state);
        }
        problem = mapViolation(gameMap, scope, target);
      } else {
        problem = simpleViolation(scope, target);
      }
      if (problem != null) {
        return Optional.of(problem);
      }
    }
    return Optional.empty();
  }

  /** map 目标：hex 走"hex 资源 ∪ 所属 region 资源"两条通道（与 {@code ToolSupport.hexVisible} 同款）。 */
  private static String mapViolation(GameMap gameMap, ResourceScope scope, CommandTarget target) {
    String hexPrefix = mapIdOf(target) + "/hex/";
    if (target.path().contains("/hex/")) {
      if (scope == null) {
        return "目标命名空间 map 调用者未表态，fail-closed 拒: " + target;
      }
      if (scope.allows(target.path())) {
        return null;
      }
      int slash = target.path().indexOf(hexPrefix);
      if (slash < 0) {
        return "地图 hex 目标路径非法: " + target;
      }
      HexCoord coord = parseHex(target.path().substring(slash + hexPrefix.length()));
      if (coord == null) {
        return "地图 hex 目标坐标解析失败: " + target;
      }
      for (RegionId owner : gameMap.regionIndex().regionOf(coord)) {
        if (scope.allows(ResourcePaths.region(mapIdOf(target), owner.value()))) {
          return null;
        }
      }
      return "地图 hex 目标超出可见范围: " + target;
    }
    if (scope == null || !scope.allows(target.path())) {
      return "地图目标超出可见范围: " + target;
    }
    return null;
  }

  private static String simpleViolation(ResourceScope scope, CommandTarget target) {
    if (scope == null) {
      return "目标命名空间 " + target.namespace() + " 调用者未表态，fail-closed 拒: " + target;
    }
    return scope.allows(target.path()) ? null : "目标超出可见范围: " + target;
  }

  /** 目标路径的首段就是 mapId（{@code <mapId>/hex/<q>_<r>}）；从路径取，避免成员变量与参数两处真相。 */
  private static String mapIdOf(CommandTarget target) {
    int slash = target.path().indexOf('/');
    return slash < 0 ? target.path() : target.path().substring(0, slash);
  }

  /** {@code q_r}（负号原样带）；解析失败 ⇒ null。 */
  private static HexCoord parseHex(String text) {
    int underscore = text.lastIndexOf('_');
    if (underscore <= 0 || underscore == text.length() - 1) {
      return null;
    }
    try {
      return new HexCoord(
          Integer.parseInt(text.substring(0, underscore)),
          Integer.parseInt(text.substring(underscore + 1)));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // ── 小件 ───────────────────────────────────────────────────────────────────────────

  private static Map<String, Object> optionalMap(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 " + name + " 若给出必须是对象");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank()) {
        throw new IllegalArgumentException("参数 " + name + " 的键必须是非空文本");
      }
      out.put(key, entry.getValue());
    }
    return out;
  }

  private static Optional<String> optionalNonBlankText(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return Optional.empty();
    }
    if (!(raw instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("参数 " + name + " 若给出必须是非空文本");
    }
    return Optional.of(text);
  }

  private static List<Map<String, Object>> targetsView(List<CommandTarget> targets) {
    List<Map<String, Object>> out = new ArrayList<>(targets.size());
    for (CommandTarget target : targets) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("namespace", target.namespace());
      item.put("path", target.path());
      out.add(item);
    }
    return out;
  }
}

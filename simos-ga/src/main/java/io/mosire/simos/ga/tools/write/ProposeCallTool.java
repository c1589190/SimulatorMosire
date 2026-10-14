package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.CallStatus;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.model.FormattedCall;
import io.mosire.simos.ga.model.PacketStatus;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.spi.UpsertDecisionPacketHandler;
import io.mosire.simos.ga.tools.GaProposalHost;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.DecisionMakerId;
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
import java.util.Set;

/**
 * ★★ {@code simos.ga.packet.propose}（DM 面；D2 决策包计划 §3.1 + 2026-10-14 GM⇄DM 设计书 §3.2 / 缺口 G9）：决策人把一个
 * **真实工具名 + JSON 参数**提议进本 tick 的 DRAFT 决策包——<b>一次调用可以提多条</b>。
 *
 * <p>★★ <b>两种载荷形态都收</b>（单条形态**逐字保留**，向后兼容）：
 *
 * <ul>
 *   <li><b>单条</b>：{@code {tool(必填), args?, intent?, branch?, expectedRevision?}} ⇒ 追加 **1** 条 call；
 *   <li><b>多条</b>：{@code {calls:[{tool(必填), args?, intent?}, …], intent?, branch?,
 *       expectedRevision?}} ⇒ 按数组顺序追加 **N** 条 call（{@code calls} 不得为空数组）。
 * </ul>
 *
 * <p>★★ <b>整次调用要么全成、要么全不成</b>：每条 call 都先跑完下面这段（一次都不落盘），**全部**通过之后才落**一次** {@code
 * ga.UpsertDecisionPacket}（**一条 revision**，不是 N 条）。任一条越界 / 校验失败 ⇒ 具名拒 + 世界零变化 + 包不变。
 *
 * <p>★★ <b>逐条走的那一段（与 {@link ReviseCallTool} 共用同一份实现，不是复制）</b>：
 *
 * <ol>
 *   <li>身份从 {@code context.identity()} 派生（载荷里的 proposer 一律不采信）；
 *   <li>工具必须在本批 {@code ProposalCatalog} 初始清单、且被该决策人的 {@code allowedTools} 允许（空 = 全清单）；
 *   <li>取 base 状态（branch/expectedRevision 可选，缺省 head）⇒ tick = 世界当前 tick；包 id = {@code
 *       pkt-<proposerId>-<tick>}；已存在且非 DRAFT ⇒ 具名拒；
 *   <li>用系统预览上下文跑目标工具的真 {@code preview=true}（不落盘）；
 *   <li>从 args + preview 提取跨命名空间目标，逐条按调用者现算 scope 校验（越界 ⇒ FORBIDDEN、包不变）。
 * </ol>
 *
 * <p>★ 两个调用点：{@link #validateCall}（本类，逐条循环里调）与 {@link ReviseCallTool}（改一条时调）。
 *
 * <p>★★ <b>{@code intent} 的合成规则</b>（多条形态才用得上；单条形态**逐字不变**）：
 *
 * <ol>
 *   <li>逐条 {@code calls[i].intent} 存在时，按数组顺序拼成包级 intent，每段带标号 {@code 【意图 #<callIndex>】} ⇒ 包级 intent
 *       里看得出**每条各自说了什么**；
 *   <li>顶层 {@code intent} 存在 ⇒ **整体覆盖**第 1 步的拼接结果（顶层是"这次提议的总纲"，语义上更强）；
 *   <li>两者都没有 ⇒ 不动包级 intent（沿用包里已有的）。
 * </ol>
 *
 * ★ 返回视图里 {@code calls[]} 逐条给出 {@code callIndex/tool/targets/preview/intent?} ⇒ 即使顶层覆盖了拼接结果，
 * 每条各自说了什么仍然看得见（本规则的可见性保证）。
 *
 * <p>★★ <b>权限 = 类3 {@code DM_FREE}</b>（设计书 §5；用户 2026-10-14 原话「这些命令允许 DM 进行基本的决策-提交流程， 很显然属于 DM
 * 无条件执行」）：决策表达**零世界效果**（不变量 I1，只写 {@code sd} 命名空间的包）⇒ {@code gate() = ALLOW}、{@code roles() =
 * {DM}}。
 *
 * <p>★ 旧名 {@code simos.sd.propose} **不做兼容**（Alpha 不兼容旧档，{@code docs/agents/02-一-并发.md} §一.11）。 ★
 * 命令类型仍是 {@code ga.UpsertDecisionPacket}（工具名 ≠ 命令类型）。
 */
public final class ProposeCallTool implements SimosTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.ga.packet.propose";

  /** 本工具只写 sd 命名空间（自己的 packet 前缀由身份派生）。 */
  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolResources.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  /** 逐条意图写进包级 intent 时的标号前缀（见类注的合成规则第 1 条）。 */
  static final String INTENT_MARKER_PREFIX = "【意图 #";

  /** 标号后缀。 */
  static final String INTENT_MARKER_SUFFIX = "】";

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final String mapId;
  private final GaProposalHost catalog;
  private final GaToolIdentity identity;

  /**
   * @param writes 窄写口（命令 id / 发起者 / 信封由装配面的适配器装配，见 {@link ToolWritePort} 类注的适配规则）
   * @param reads 窄读口
   * @param mapId 地图 id（scope 判据的入参，构造期注入）
   * @param catalog 提议面（真预览 + 目标提取的唯一实现）
   * @param identity 身份口（决策人 id / scope 判据的唯一实现）
   */
  public ProposeCallTool(
      ToolWritePort writes,
      ToolReadPort reads,
      String mapId,
      GaProposalHost catalog,
      GaToolIdentity identity) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.identity = Objects.requireNonNull(identity, "identity");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "决策人提议：把一个（或一次多个）真实工具名 + JSON 参数格式化成 FormattedCall 追加进本 tick 的 DRAFT 决策包。"
        + "参数 {tool?(单条形态必填；必须是 ProposalCatalog 初始清单里的 simos.* 工具), args?(目标工具参数对象，缺省空), "
        + "calls?(多条形态；数组 [{tool(必填), args?, intent?}, …]，不得为空数组), "
        + "intent?(可选 NL 意图，写包级 intent；多条形态下**覆盖**逐条拼接的结果), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。tool 与 calls 二选一、不得同时给。"
        + "逐条流程：catalog 校验 → 真 preview(preview=true，不落盘) → 逐目标 scope 校验（越界 = FORBIDDEN）→ "
        + "**全部通过后**一次 ga.UpsertDecisionPacket 落一条 revision（整次调用要么全成、要么全不成、世界零变化）。"
        + "多条形态下每条的 intent 按顺序拼成包级 intent（带【意图 #<callIndex>】标号），顶层 intent 有则整体覆盖它。"
        + "同 proposer 同 tick 一个包；已提交/已裁决 ⇒ BAD_REQUEST（先 "
        + ReopenPacketTool.NAME
        + " 撤回）。返回 {packetId, status, callCount, callIndex, tool, targets, preview, calls:[{callIndex, tool, "
        + "targets, preview, intent?}], intent?, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("tool", ToolSchema.prop("string", "要提议的真实工具名（单条形态必填；须在 ProposalCatalog 初始清单内）"));
    props.put("args", ToolSchema.prop("object", "目标工具的 JSON 参数对象（缺省 {}）"));
    props.put(
        "calls",
        ToolSchema.prop("array", "多条形态：待提议的调用数组，元素 {tool(必填), args?(对象), intent?(文本)}，不得为空数组"));
    props.put("intent", ToolSchema.prop("string", "可选：这次提议的自然语言意图（写进包的 intent；多条形态下覆盖逐条拼接）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "读取/提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  /** ★ 类3 {@code DM_FREE}：决策表达零世界效果（不变量 I1；设计书 §5 权限表）。 */
  @Override
  public ToolGate gate(ToolContext context) {
    return ToolGate.ALLOW;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      Optional<String> decisionMakerId = identity.decisionMakerIdOf(context.identity());
      if (decisionMakerId.isEmpty()) {
        return ToolResult.error("FORBIDDEN", NAME + " 只能由决策人身份调用（身份里没有 decision-maker: 前缀）");
      }

      DecisionMakerId proposerId = DecisionMakerId.parse(decisionMakerId.get());
      Optional<String> topLevelIntent = optionalNonBlankText(args, "intent");
      BranchId branch = GaToolReads.branch(args);
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      // ★ 两种形态在**读状态之前**就解析并互斥判定：形态本身不合法 ⇒ 立刻 BAD_REQUEST，不碰世界、不碰包。
      List<Proposal> proposals;
      try {
        proposals = proposalsOf(args);
      } catch (IllegalArgumentException e) {
        return ToolResult.error("BAD_REQUEST", e.getMessage());
      }
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      DecisionMaker dm = ga.decisionMakers().get(proposerId);
      if (dm == null) {
        return ToolResult.error("FORBIDDEN", "决策人不存在: " + proposerId.value());
      }
      // ★ 自指资源围栏：只写自己的 packet 前缀（与 DecisionCallerFactory.selfDecisionScope 的第二条前缀同源）。
      ToolResources.requireAll(
          context,
          Operation.WRITE,
          List.of(ToolResources.resourceSd(identity.decisionPacketKind(), proposerId.value())));

      long tick = state.meta().timestamp().tick();
      DecisionPacketId packetId = PacketDraftSupport.packetIdValueOf(proposerId, tick);
      DecisionPacket existing = ga.decisionPackets().get(packetId);
      Optional<ToolResult> notDraft = PacketDraftSupport.rejectIfNotDraft(existing);
      if (notDraft.isPresent()) {
        return notDraft.get();
      }

      // ★★ 逐条先全部校验（真预览 + 目标 scope），一条不过就整次返回 ⇒ 世界零变化、包不变（见类注）。
      int firstIndex = PacketDraftSupport.nextCallIndex(existing);
      List<ValidatedCall> validated = new ArrayList<>(proposals.size());
      for (int i = 0; i < proposals.size(); i++) {
        ValidatedCall check =
            validateCall(
                dm, state, proposals.get(i).tool(), proposals.get(i).args(), firstIndex + i);
        if (check.error() != null) {
          return check.error();
        }
        validated.add(check);
      }

      List<FormattedCall> calls = new ArrayList<>();
      for (ValidatedCall check : validated) {
        calls.add(check.call());
      }
      // ★ 包级 intent：顶层覆盖逐条拼接；两者都没有 ⇒ finalIntentText = null = "不动包里已有的 intent"。
      String composed = composedIntent(proposals, validated);
      String finalIntentText = topLevelIntent.orElseGet(() -> composed.isBlank() ? null : composed);
      DecisionPacket packet =
          newPacket(
              existing,
              packetId,
              branch,
              tick,
              proposerId,
              finalIntentText,
              calls,
              state.meta().ref().revision().value());
      // ★ 命令 id 由窄写口的装配面生成并**回报**（见 ToolWritePort.Receipt）。
      ToolWritePort.Receipt receipt =
          writes.submitWithReceipt(
              branch,
              () -> new RevisionId(state.meta().ref().revision().value()),
              UpsertDecisionPacketHandler.TYPE,
              DecisionPacketPayloads.upsert(packet));
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("packetId", packetId.value());
      view.put("status", PacketStatus.DRAFT.name());
      view.put("callCount", calls.size());
      view.put("callIndex", validated.get(0).call().callIndex());
      view.put("tool", validated.get(0).call().toolName());
      view.put("targets", targetsView(validated.get(0).call().targets()));
      view.put("preview", validated.get(0).preview());
      view.put("calls", callsView(proposals, validated));
      if (!finalIntentText.isBlank()) {
        view.put("intent", finalIntentText);
      }
      return DecisionPacketPayloads.fold(receipt.outcome(), view, receipt.commandId());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e; // 资源拒因原样逃到 ToolCallAuthorizer 边界，折成 RESOURCE_DENIED
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "propose 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ── 共用校验段（★ propose 与 revise 的唯一一份；两个调用点：本方法 + ReviseCallTool）────────────

  /**
   * ★★ <b>"catalog 校验 → 真 preview → 目标 scope 校验 → 造 {@link FormattedCall}" 的唯一实现</b>： {@link
   * #execute}（逐条循环）与 {@link ReviseCallTool}（改一条）都调这里，第二份实现不许存在。
   *
   * <p>★ 返回 {@code error() != null} ⇒ 调用方**原样**返回它（具名拒的码与文案都由这里定，两处一致）。
   *
   * @param dm 调用者（allowedTools 白名单判据）
   * @param state base 状态（预览与 scope 都读它）
   * @param toolName 目标工具名（未经校验）
   * @param innerArgs 目标工具参数
   * @param callIndex 这条 call 在包里的序号（propose = 追加位；revise = 被替换的那一位）
   */
  ValidatedCall validateCall(
      DecisionMaker dm,
      SimulationState state,
      String toolName,
      Map<String, Object> innerArgs,
      int callIndex) {
    Objects.requireNonNull(dm, "dm");
    Objects.requireNonNull(state, "state");
    if (!catalog.contains(toolName)) {
      return ValidatedCall.failed(
          ToolResult.error("BAD_REQUEST", "工具不在 ProposalCatalog 初始清单，不可 propose: " + toolName));
    }
    if (!dm.allowedTools().isEmpty() && !dm.allowedTools().contains(toolName)) {
      return ValidatedCall.failed(
          ToolResult.error("FORBIDDEN", "工具不在该决策人的 allowedTools 白名单: " + toolName));
    }
    // ★ 真预览（系统上下文、preview=true、不落盘）+ 跨命名空间目标提取。
    Map<String, Object> preview = catalog.preview(toolName, state, innerArgs);
    List<CommandTarget> targets = catalog.targets(toolName, state, innerArgs, preview);
    if (targets.isEmpty()) {
      return ValidatedCall.failed(
          ToolResult.error("BAD_REQUEST", "未能从参数/预览提取到任何可寻址目标，拒绝 propose: " + toolName));
    }
    Optional<String> violation = identity.scopeViolation(dm.id(), state, targets, mapId);
    if (violation.isPresent()) {
      return ValidatedCall.failed(ToolResult.error("FORBIDDEN", violation.get()));
    }
    FormattedCall call =
        new FormattedCall(
            callIndex,
            toolName,
            ToolResults.json(innerArgs),
            targets,
            ToolResults.json(preview),
            List.of("scope-ok", "preview-ok"),
            CallStatus.PENDING,
            Optional.empty());
    return ValidatedCall.ok(call, preview, targets);
  }

  /** 一条通过校验的 call：{@code error != null} ⇒ 校验失败（调用方原样返回），否则其余三个字段可用。 */
  record ValidatedCall(
      ToolResult error,
      FormattedCall call,
      Map<String, Object> preview,
      List<CommandTarget> targets) {

    static ValidatedCall failed(ToolResult error) {
      return new ValidatedCall(Objects.requireNonNull(error, "error"), null, Map.of(), List.of());
    }

    static ValidatedCall ok(
        FormattedCall call, Map<String, Object> preview, List<CommandTarget> targets) {
      return new ValidatedCall(null, Objects.requireNonNull(call, "call"), preview, targets);
    }
  }

  /** 一次提议请求里的一条（形态解析的产物；{@code intent} 只参与包级 intent 的**拼接**，不进 call 本身）。 */
  record Proposal(String tool, Map<String, Object> args, Optional<String> intent) {}

  // ── 载荷形态解析 ───────────────────────────────────────────────────────────────────

  /**
   * 载荷 → 1..N 条提议。{@code tool} 与 {@code calls} **二选一**（都给 / 都不给 / {@code calls} 为空数组 ⇒ {@code
   * BAD_REQUEST}）。
   */
  private static List<Proposal> proposalsOf(Map<String, Object> args) {
    boolean hasTool = args.get("tool") != null;
    boolean hasCalls = args.get("calls") != null;
    if (hasTool && hasCalls) {
      throw new IllegalArgumentException("tool 与 calls 只能给一个（单条形态用 tool，多条形态用 calls）");
    }
    if (hasCalls) {
      return callsOf(args.get("calls"));
    }
    if (hasTool) {
      return List.of(
          new Proposal(
              ToolSchema.requiredText(args, "tool"),
              optionalMap(args, "args"),
              optionalNonBlankText(args, "intent")));
    }
    throw new IllegalArgumentException("缺少必填参数：单条形态给 tool，多条形态给 calls（数组，不得为空）");
  }

  /** {@code calls} 数组 → 提议列表（逐元素校验形状；空数组当场拒）。 */
  private static List<Proposal> callsOf(Object raw) {
    if (!(raw instanceof List<?> list)) {
      throw new IllegalArgumentException("参数 calls 必须是数组");
    }
    if (list.isEmpty()) {
      throw new IllegalArgumentException("参数 calls 不得为空数组");
    }
    List<Proposal> out = new ArrayList<>(list.size());
    for (int i = 0; i < list.size(); i++) {
      Object element = list.get(i);
      if (!(element instanceof Map<?, ?> map)) {
        throw new IllegalArgumentException("参数 calls[" + i + "] 必须是对象 {tool, args?, intent?}");
      }
      Map<String, Object> item = stringKeyedMap(map, "calls[" + i + "]");
      out.add(
          new Proposal(
              ToolSchema.requiredText(item, "tool"),
              optionalMap(item, "args"),
              optionalNonBlankText(item, "intent")));
    }
    return out;
  }

  /**
   * 包级 intent 的**拼接**（类注合成规则第 1 条）：逐条 intent 按数组顺序、各带 {@code 【意图 #<callIndex>】} 标号。 ★ 一条显式 intent
   * 都没有 ⇒ 空串（调用方按"不动包级 intent"处置）。
   */
  private static String composedIntent(List<Proposal> proposals, List<ValidatedCall> validated) {
    StringBuilder text = new StringBuilder();
    for (int i = 0; i < proposals.size(); i++) {
      Optional<String> intent = proposals.get(i).intent();
      if (intent.isEmpty()) {
        continue;
      }
      if (text.length() > 0) {
        text.append("\n");
      }
      text.append(INTENT_MARKER_PREFIX)
          .append(validated.get(i).call().callIndex())
          .append(INTENT_MARKER_SUFFIX)
          .append(intent.get());
    }
    return text.toString();
  }

  /** 逐条 call 的视图（含各自说了什么 intent ⇒ 顶层覆盖拼接结果之后仍看得见每条的表达）。 */
  private static List<Map<String, Object>> callsView(
      List<Proposal> proposals, List<ValidatedCall> validated) {
    List<Map<String, Object>> out = new ArrayList<>(validated.size());
    for (int i = 0; i < validated.size(); i++) {
      FormattedCall call = validated.get(i).call();
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("callIndex", call.callIndex());
      item.put("tool", call.toolName());
      item.put("targets", targetsView(call.targets()));
      item.put("preview", validated.get(i).preview());
      proposals.get(i).intent().ifPresent(value -> item.put("intent", value));
      out.add(item);
    }
    return out;
  }

  // ── 包构造 ─────────────────────────────────────────────────────────────────────────

  /**
   * 追加 N 条 call 之后的整包（{@code intent} 为 {@code null} ⇒ **不动**包里已有的 intent；非 null ⇒ 覆盖成它）。
   *
   * <p>★ 其余字段（{@code createdAtRevision} / {@code decidedBy} / …）一律沿用已有包，缺包时取缺省——与冻结回合 "包能跨 revision
   * 累积"（不变量 I6）同一条口径。
   */
  private static DecisionPacket newPacket(
      DecisionPacket existing,
      DecisionPacketId packetId,
      BranchId branch,
      long tick,
      DecisionMakerId proposerId,
      String finalIntent,
      List<FormattedCall> appended,
      long baseRevision) {
    List<FormattedCall> calls = new ArrayList<>();
    String intent = finalIntent == null ? "" : finalIntent;
    long createdAtRevision = baseRevision;
    Optional<String> decidedBy = Optional.empty();
    OptionalLong decidedAtRevision = OptionalLong.empty();
    Optional<String> reasonInfoId = Optional.empty();
    Optional<String> decisionNote = Optional.empty();
    if (existing != null) {
      calls.addAll(existing.calls());
      if (finalIntent == null) {
        intent = existing.intent();
      }
      createdAtRevision = existing.createdAtRevision();
      decidedBy = existing.decidedBy();
      decidedAtRevision = existing.decidedAtRevision();
      reasonInfoId = existing.reasonInfoId();
      decisionNote = existing.decisionNote();
    }
    calls.addAll(appended);
    return new DecisionPacket(
        packetId,
        branch.value(),
        tick,
        proposerId,
        PacketStatus.DRAFT,
        intent,
        calls,
        createdAtRevision,
        decidedBy,
        decidedAtRevision,
        reasonInfoId,
        decisionNote);
  }

  // ── 小件 ───────────────────────────────────────────────────────────────────────────

  /** 键必须是非空文本的 Map 拷贝（{@code args} 与 {@code calls[i]} 共用）。 */
  static Map<String, Object> stringKeyedMap(Map<?, ?> map, String name) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key) || key.isBlank()) {
        throw new IllegalArgumentException("参数 " + name + " 的键必须是非空文本");
      }
      out.put(key, entry.getValue());
    }
    return out;
  }

  static Map<String, Object> optionalMap(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return Map.of();
    }
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 " + name + " 若给出必须是对象");
    }
    return stringKeyedMap(map, name);
  }

  static Optional<String> optionalNonBlankText(Map<String, Object> args, String name) {
    Object raw = args.get(name);
    if (raw == null) {
      return Optional.empty();
    }
    if (!(raw instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("参数 " + name + " 若给出必须是非空文本");
    }
    return Optional.of(text);
  }

  static List<Map<String, Object>> targetsView(List<CommandTarget> targets) {
    List<Map<String, Object>> out = new ArrayList<>(targets.size());
    for (CommandTarget target : targets) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("namespace", target.namespace());
      item.put("path", target.path());
      out.add(item);
    }
    return out;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM);
  }
}

package io.mosire.simos.economy.tools.write;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>经济侧"行为参数" GM 窄写的共同基类</b>（B3 第三域，2026-10-12）：<b>preview / apply / planOnly、reason、资源围栏、
 * 结局折叠</b>四件事一条骨架。
 *
 * <p>★ <b>它从哪来</b>：app 侧的 {@code AbstractHouseholdGmTool} 是 S3a（2026-10-09）家户/人口 GM 窄写的共同基类，服务 11 条
 * 家户/人口工具（本批全部下放到 social/economy）。其中<b>经济的两条</b>（{@code simos.gm.periodicAdjustment} / {@code
 * simos.gm.govMarketMandate}）用到的只是那条骨架里"preview / apply / planOnly / reason / 资源围栏 / 结局折叠"一段，
 * 而它们的载荷形态（列表/授权表）与家户工具不同 ⇒ 随迁时**按用到的面**立一条经济分册，而不是让经济工具去继承 social 的工具骨架 （那会把 social 的家户语义塞进
 * economy 的工具层）。逐条口径与原文案（含 {@code planOnly} 内部参数、错误折叠文案）一字未改， 对宿主的直连换成构造期注入的 {@link ToolWritePort}
 * / {@link ToolReadPort}。
 *
 * <p>★ <b>为什么两条经济 GM 工具共用一条骨架而不是各自内联</b>：{@code ArmyCodec} 式的"为一个子类搬整类"才是假接缝；这里是
 * **两个**子类共用的同一段（preview/planOnly/结局折叠），内联会造出第二份结局折叠（拒绝码与字段名两处各说各话 ⇒ I-G1 要防的漂移）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有这些工具；工具名不是命令类型 ⇒ 不进 catalog /
 * {@code PAYLOAD_HINTS}。
 *
 * <p>★ <b>失败具名</b>：参数缺失/类型错/{@code preview=false} 缺 {@code expectedRevision}/域校验不过 ⇒ {@code
 * BAD_REQUEST}（零 revision）；命令被拒 ⇒ {@code REJECTED} 带真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head； 资源不匹配 ⇒
 * 原样抛 {@link ResourceDeniedException}（由唯一入口折资源拒因）。
 */
abstract class AbstractEconomyGmTool implements AgentTool {

  protected final ToolWritePort writes;
  protected final ToolReadPort reads;

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "本类是包内可见的 abstract 类（不可实例化、外部无法子类化），构造器只做 Objects.requireNonNull ⇒ finalizer 攻击面不存在，属 SpotBugs 对 abstract 的启发式误报")
  protected AbstractEconomyGmTool(ToolWritePort writes, ToolReadPort reads) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
  }

  /** 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。 */
  @Override
  public final ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public final ResourceManifest resources() {
    return resourceManifest();
  }

  @Override
  public final ToolGate gate(ToolContext context) {
    return new ToolGate.Ask(name(), summary(context.arguments()), AskKind.SENSITIVE);
  }

  /**
   * 是否强制工具级 {@code reason}（缺省 true）。★ 参数工具（周期规则 / 政府市场授权）里 {@code reason} 是**各自载荷的一部分**
   * （有的必填、有的可选），不是所有动作都有统一的工具级审计理由 ⇒ 那些工具覆写本方法返回 false，由各自的 {@code run} 按动作解析载荷。
   */
  protected boolean requiresReason() {
    return true;
  }

  /** 审批摘要：子类可覆写加自己的关键参数；缺省给 preview/branch/reason 三件套（与 app 侧骨架同口径）。 */
  protected String summary(Map<String, Object> args) {
    return name()
        + " preview="
        + args.getOrDefault("preview", true)
        + " branch="
        + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH)
        + " reason="
        + args.get("reason");
  }

  @Override
  public final ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(context, Operation.WRITE, writeResources());
      Map<String, Object> args = context.arguments();
      String reason =
          requiresReason()
              ? ToolSchema.requiredText(args, "reason")
              : ToolSchema.optionalText(args, "reason", null);
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      // ★ D3 内部参数：不进 jsonSchema / 工具描述，只被系统执行器注入。
      boolean planOnly = !preview && ToolSchema.optionalBoolean(args, "planOnly").orElse(false);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedArg != null && expectedArg < 0L) {
        throw new IllegalArgumentException("expectedRevision 不得为负: " + expectedArg);
      }
      if (!preview && expectedArg == null) {
        throw new IllegalArgumentException(
            "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      long expectedRevision = expectedArg == null ? -1L : expectedArg;
      SimulationState state =
          reads.stateAt(branch, expectedRevision < 0L ? null : new RevisionId(expectedRevision));
      return run(new Request(state, branch, expectedRevision, preview, planOnly, reason, args));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 资源拒因原样逃到 ToolCallAuthorizer 边界（与其余写工具同一条）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", name() + " 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 一次工具调用的共同输入（preview 与 apply 共用同一份 base 状态与坐标）。 */
  protected record Request(
      SimulationState state,
      BranchId branch,
      long expectedRevision,
      boolean preview,
      boolean planOnly,
      String reason,
      Map<String, Object> args) {

    public Request {
      // ★ 参数树来自工具上下文，构造期做只读快照：run() 只读它（全仓 grep 无 args 写点），
      //   快照既不改值也不改迭代序（LinkedHashMap 保序），只是不再把外部可变引用透传出去。
      args = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(args, "args")));
    }
  }

  /** 子类实现：读 base、跑纯推导、preview 出视图或提交命令。 */
  protected abstract ToolResult run(Request request);

  protected abstract ResourceManifest resourceManifest();

  protected abstract List<ResourceId> writeResources();

  // ── 提交与折叠 ───────────────────────────────────────────────────────────────────────

  /**
   * 单条命令提交与三结局折叠（preview/apply 的结果视图由调用方先搭好）：落盘的 {@code command_type} 就是命令类型本身。 preview 一律不调本方法。
   *
   * <p>★ 命令 id 由写口适配器生成并回报（{@link ToolWritePort.Receipt}）⇒ 视图里的 {@code commandId} 与落盘信封里的那一个
   * 逐字相同（不再是"工具另生成一个"）。
   */
  protected final ToolResult submitCommand(
      Request request, String type, Map<String, Object> payload, Map<String, Object> view) {
    ToolWritePort.Receipt receipt =
        writes.submitWithReceipt(
            request.branch(),
            () -> new RevisionId(request.expectedRevision()),
            type,
            ToolResults.json(payload));
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

  /** preview 结果（一个字节都没写）。 */
  protected static ToolResult preview(Map<String, Object> view) {
    view.put("preview", true);
    view.put("submitted", false);
    return ToolResults.ok(view);
  }

  /**
   * D3 内部执行器专用：把这条工具本来要提交的命令组批返回，**一个字节都不写**（不调写口）。
   *
   * <p>★ 只在 {@code !preview && planOnly} 时被调用；{@code planOnly} 不进 jsonSchema / 工具描述。
   */
  protected static ToolResult planOnly(String type, Map<String, Object> payload) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", false);
    view.put("submitted", false);
    view.put("planOnly", true);
    // ★ 逐条只给 {type, payloadJson}——与 app 侧 ToolSupport.plannedCommandsView 的字段集逐字相同。
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("type", type);
    row.put("payloadJson", ToolResults.json(payload));
    view.put("plannedCommands", List.of(row));
    return ToolResults.ok(view);
  }

  /** 一条命令预览（type + 裸载荷 + payloadJson；与写口组包同源）。 */
  protected static Map<String, Object> commandPreview(String type, Map<String, Object> payload) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("type", type);
    row.put("payload", payload);
    row.put("payloadJson", ToolResults.json(payload));
    return row;
  }

  /** 家户必须存在（经济域读 social 切片是允许面内的事；social 域自己的 handler 也会再判一遍）。 */
  protected static void requireHousehold(SocialData base, HouseholdId id) {
    if (!base.households().containsKey(id)) {
      throw new IllegalArgumentException("家户不存在: " + id);
    }
  }
}

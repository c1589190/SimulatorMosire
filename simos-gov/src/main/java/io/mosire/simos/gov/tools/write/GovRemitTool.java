package io.mosire.simos.gov.tools.write;

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
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.gov.tools.GovToolReads;
import io.mosire.simos.gov.tools.GovToolSupport;
import io.mosire.simos.gov.tools.GovToolViews;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code simos.gov.remit}（R3a，行政区划修复计划 §1.3/§R4）：<b>GM-only 显式 GOV 国库上缴 / 转移窄工具</b> —— 把用户选定的两个
 * GOV 单位当刻有效位置折成一条 {@code actor.RemitGovTreasury} 命令：源国库扣、目标国库加， 一条命令、一条 revision、整条原子。
 *
 * <p>★★ <b>preview / apply 共用同一份解析与视图</b>：preview 只读 {@link QueryService} 给出的 base state，找两个单位、
 * 要求都带 {@link GovernmentFormation} 且当刻有效位置非空，返回源/目标位置、请求量与命令预览（含源国库可支配量），
 * <b>一个字节都不写</b>；preview=false 把同一份解析结果组一条 {@link CommandEnvelope} 走 {@link CoreSimos#submit}。
 * 金额语义（源账必须存在、可支配量足、目标缺账正增量新建）由域层 handler 判，本工具不重复实现。
 *
 * <p>★★ <b>GM 特权</b>：允许任意两个 GOV 单位之间转移，<b>不</b>要求 {@code to} 是 {@code from} 的 {@code superiorGov}
 * —— 该约束属于省份决策人的指令 scope / 校验（R3b），GM 走这里时不受限。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：决策人桶没有它；工具名不是命令类型 ⇒ 不进 catalog / {@code
 * PAYLOAD_HINTS}（它提交的 {@code actor.RemitGovTreasury} 已由 handler 注册进 catalog）。
 *
 * <p>★ <b>资源声明</b>：只写 {@code actor} 命名空间（{@link ResourcePolicy#UNRESTRICTED}，GM 侧 {@code actor} 是
 * unlimited）；与 {@code ActorAdjustAccountsTool} 同一条 P0 对齐口径。
 *
 * <p>★ <b>失败具名</b>：参数缺失 / 类型错 / 金额为负 / 三项全 0 / GOV 单位不存在或不是 GOV / 无当刻有效位置 / {@code preview=false} 缺
 * expectedRevision ⇒ {@code BAD_REQUEST}（零 revision，不提交半笔）； 域层拒绝（源账不存在、可支配量不足、同账自转等）⇒ {@code
 * REJECTED} 带真拒因；提交冲突 ⇒ {@code CONFLICT} 带真实 head； 资源不匹配 ⇒ 原样抛 {@link
 * ResourceDeniedException}（由唯一入口折资源拒因）。
 */
public final class GovRemitTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.remit";

  /** 本工具提交的唯一命令类型（与 handler 的 {@code TYPE} 同一个拼写点）。 */
  private final String commandType;

  /** 本工具只写 actor 命名空间（GM 侧 actor 未受限 ⇒ 逐条判通过）。 */
  private static final ResourceManifest ACTOR_WRITE =
      ResourceManifest.of(ToolResources.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  /** 与 {@link #ACTOR_WRITE} 同源的逐命名空间粗断言（{@code actor:*}；GM 侧 unlimited）。 */
  private static final List<ResourceId> WRITE_RESOURCES =
      List.of(ResourceId.of(ToolResources.ACTOR_NAMESPACE, "*"));

  private final GovToolSupport support;

  /**
   * @param support 政府工具接缝束（写口/读口/视图口/视角口/经济口/命令类型口/人力口；见 {@link GovToolSupport}）
   */
  public GovRemitTool(GovToolSupport support) {
    this.support = Objects.requireNonNull(support, "support");
    this.commandType = support.commandTypes().remitGovTreasury();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 显式 GOV 国库上缴 / 转移（actor.RemitGovTreasury 窄封装）：从源 GOV 当刻有效位置的国库账扣"
        + " grain/cloth/money，加到目标 GOV 当刻有效位置的国库账；一条命令 = 一条 revision，整条原子。"
        + "参数 {fromGovUnitId(必填), toGovUnitId(必填), grain?/cloth?/money?(缺省 0；负数拒、三项全 0 拒), "
        + "reason(必填非空白), preview?(缺省 true=只读预览), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填)}。"
        + "两个单位都必须存在、带 GovernmentFormation 且有当刻有效位置；preview 返回源国库可支配量（账不存在 = null，不填 0）"
        + "与 commandsPreview；apply 由域层判源账存在 / 可支配量足（不足带资源、请求、可用），目标账缺 ⇒ 五参新建。"
        + "★ GM 允许任意两个 GOV 之间转移（不要求 to 是 from.superiorGov）；决策人路径的 superior 校验留 R3b。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "fromGovUnitId",
        ToolSchema.prop("string", "源 GOV 单位 id（必填；必须存在且带 GovernmentFormation、有当刻有效位置）"));
    props.put(
        "toGovUnitId",
        ToolSchema.prop("string", "目标 GOV 单位 id（必填；必须存在且带 GovernmentFormation、有当刻有效位置）"));
    props.put(
        "grain", ToolSchema.prop("integer", "上缴粮（最小计量单位；可选，缺省 0；不得为负；grain/cloth/money 至少一个 > 0）"));
    props.put("cloth", ToolSchema.prop("integer", "上缴布（最小计量单位；可选，缺省 0；不得为负；至少一个 > 0）"));
    props.put("money", ToolSchema.prop("integer", "上缴银（毫银；可选，缺省 0；不得为负；至少一个 > 0）"));
    props.put("reason", ToolSchema.prop("string", "上缴原因（必填非空白；进命令载荷与工具结果）"));
    props.put(
        "preview", ToolSchema.prop("boolean", "true（缺省）= 只读预览；false = 提交 actor.RemitGovTreasury"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("fromGovUnitId", "toGovUnitId", "reason"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：GM 侧走 GmAutoApproveGate，与其余窄写同制。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return ACTOR_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(),
        "GOV 国库上缴 from="
            + args.get("fromGovUnitId")
            + " to="
            + args.get("toGovUnitId")
            + " grain="
            + args.getOrDefault("grain", 0)
            + " cloth="
            + args.getOrDefault("cloth", 0)
            + " money="
            + args.getOrDefault("money", 0)
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH)
            + " reason="
            + args.get("reason"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(context, Operation.WRITE, WRITE_RESOURCES);
      Map<String, Object> args = context.arguments();
      String fromGovUnitId = ToolSchema.requiredText(args, "fromGovUnitId");
      String toGovUnitId = ToolSchema.requiredText(args, "toGovUnitId");
      String reason = ToolSchema.requiredText(args, "reason");
      long grain = optionalAmount(args, "grain");
      long cloth = optionalAmount(args, "cloth");
      long money = optionalAmount(args, "money");
      String amountViolation = amountViolation(grain, cloth, money);
      if (amountViolation != null) {
        return ToolResult.error("BAD_REQUEST", amountViolation);
      }
      boolean preview = ToolSchema.optionalBoolean(args, "preview").orElse(true);
      BranchId branch =
          new BranchId(ToolSchema.optionalText(args, "branch", ToolResources.DEFAULT_BRANCH));
      Long expectedRevisionArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedRevisionArg);
      }
      if (!preview && expectedRevisionArg == null) {
        return ToolResult.error(
            "BAD_REQUEST", "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
      }
      long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
      // ★ preview / apply 的共同输入：同一个坐标上的 base 状态 + 同一份单位解析。
      SimulationState state =
          support
              .reads()
              .stateAt(branch, expectedRevision < 0L ? null : new RevisionId(expectedRevision));
      TreasuryLocation from = resolveGov(state, fromGovUnitId, "fromGovUnitId");
      TreasuryLocation to = resolveGov(state, toGovUnitId, "toGovUnitId");
      Map<String, Object> payload = commandPayload(from, to, grain, cloth, money, reason);
      if (preview) {
        return ToolResults.ok(
            view(state, from, to, grain, cloth, money, reason, payload, true, false, null));
      }
      return submit(
          state, from, to, grain, cloth, money, reason, payload, branch, expectedRevision);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与其余写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界（折成 TOOL_ERROR 会让调用方
      //   看到"参数问题"而看不到"换个资源就行"）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "GOV 国库上缴失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 可选数量的参数解析（缺省 0；类型错误由 {@link ToolSupport#optionalLong} 抛 ⇒ BAD_REQUEST）。 */
  private static long optionalAmount(Map<String, Object> args, String name) {
    Long value = ToolSchema.optionalLong(args, name);
    return value == null ? 0L : value;
  }

  /** 三个金额的边界（负数 / 三项全 0）：违例理由；合法 ⇒ null。 */
  private static String amountViolation(long grain, long cloth, long money) {
    if (grain < 0L || cloth < 0L || money < 0L) {
      return "grain/cloth/money 都不得为负: grain=" + grain + ", cloth=" + cloth + ", money=" + money;
    }
    if (grain == 0L && cloth == 0L && money == 0L) {
      return "grain/cloth/money 至少一个必须 > 0（至少上缴一种资源）";
    }
    return null;
  }

  /**
   * 解析一个 GOV 单位的国库落点：必须存在、带 {@link GovernmentFormation}、有<b>当刻有效位置</b> （与 GUI / scope / facet 同口径走
   * {@link UnitState#effectivePosition}）—— 任一不满足 ⇒ {@link IllegalArgumentException}（由 {@link
   * #execute} 折成 BAD_REQUEST，零 revision）。
   */
  private TreasuryLocation resolveGov(SimulationState state, String rawUnitId, String field) {
    UnitState units = GovToolReads.unitState(state);
    UnitId unitId = new UnitId(rawUnitId);
    Unit unit = units.units().get(unitId);
    if (unit == null) {
      throw new IllegalArgumentException("参数 " + field + " 指定的单位不存在: " + rawUnitId);
    }
    if (!(unit.module().orElse(null) instanceof GovernmentFormation)) {
      throw new IllegalArgumentException(
          "参数 " + field + " 指定的单位没有 GovernmentFormation，不能作为 GOV: " + rawUnitId);
    }
    HexCoord at =
        units
            .effectivePosition(unitId, state.meta().timestamp())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "参数 " + field + " 指定的 GOV 单位没有当刻有效位置（国库落点未知）: " + rawUnitId));
    return new TreasuryLocation(
        rawUnitId, at, support.perspective().governmentHousehold(unit, rawUnitId).value());
  }

  /** 一条 {@code actor.RemitGovTreasury} 的载荷（字段名与 handler 的解析契约一致；三个金额显式写出）。 */
  private static Map<String, Object> commandPayload(
      TreasuryLocation from,
      TreasuryLocation to,
      long grain,
      long cloth,
      long money,
      String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("fromHousehold", from.householdId());
    payload.put("toHousehold", to.householdId());
    payload.put("grain", grain);
    payload.put("cloth", cloth);
    payload.put("money", money);
    payload.put("reason", reason);
    return payload;
  }

  /** 提交阶段：一条信封走唯一写入口，把三结局折进同一份视图。 */
  private ToolResult submit(
      SimulationState state,
      TreasuryLocation from,
      TreasuryLocation to,
      long grain,
      long cloth,
      long money,
      String reason,
      Map<String, Object> payload,
      BranchId branch,
      long expectedRevision) {
    // ★ 视图在提交前算好（available 取提交前基态）：提交成功后不再读 state/actor，避免"已落盘但工具报错"。
    Map<String, Object> view =
        view(state, from, to, grain, cloth, money, reason, payload, false, true, null);
    // ★ 与迁入前**逐字同形**的折叠（本工具的结果视图**没有**顶层 commandId 键，冲突/拒节点带 commandId + correlationId）：
    //   经写口取回装配面生成的 commandId（报出去的 id == 落盘信封的 id）。
    ToolWritePort.Receipt receipt =
        support
            .writes()
            .submitWithReceipt(
                branch,
                () -> new RevisionId(expectedRevision),
                commandType,
                ToolResults.json(payload));
    String commandId = receipt.commandId();
    return switch (receipt.outcome()) {
      case ToolWritePort.Outcome.Committed committed -> {
        view.put("submission", ToolResults.committedView(committed.ref(), commandId, commandId));
        yield ToolResults.ok(view);
      }
      case ToolWritePort.Outcome.Conflict conflict -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "conflict");
        submission.put("current", ToolResults.stateRef(conflict.current()));
        submission.put("commandId", commandId);
        submission.put("correlationId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolResults.json(view));
      }
      case ToolWritePort.Outcome.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", rejected.reason());
        submission.put("commandId", commandId);
        submission.put("correlationId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolResults.json(view));
      }
    };
  }

  /** preview / apply 共用的结果视图（{@code submission} 为 null 时不落该键）。 */
  private Map<String, Object> view(
      SimulationState state,
      TreasuryLocation from,
      TreasuryLocation to,
      long grain,
      long cloth,
      long money,
      String reason,
      Map<String, Object> payload,
      boolean preview,
      boolean submitted,
      Map<String, Object> submission) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("preview", preview);
    view.put("submitted", submitted);
    view.put("from", fromView(state, from));
    view.put("to", toView(to));
    Map<String, Object> requested = new LinkedHashMap<>();
    requested.put("grain", grain);
    requested.put("cloth", cloth);
    requested.put("money", money);
    view.put("requested", requested);
    view.put("commandsPreview", commandsPreview(payload));
    view.put("reason", reason);
    if (submission != null) {
      view.put("submission", submission);
    }
    return view;
  }

  /** 源 GOV 视图：{@code {unitId,q,r,available}}；国库账不存在 ⇒ {@code available:null}（不填 0）。 */
  private Map<String, Object> fromView(SimulationState state, TreasuryLocation from) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("unitId", from.unitId());
    view.put("q", from.at().q());
    view.put("r", from.at().r());
    view.put("available", availableOf(state, from));
    return view;
  }

  /** 目标 GOV 视图：{@code {unitId,q,r}}（按本工具契约不承诺目标可用量）。 */
  private static Map<String, Object> toView(TreasuryLocation to) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("unitId", to.unitId());
    view.put("q", to.at().q());
    view.put("r", to.at().r());
    return view;
  }

  /**
   * 源国库账的可支配量（{@code {grain,cloth,money}}）：账不存在 ⇒ null。
   *
   * <p>★ 走 {@code AvailableStock}（全仓唯一的"余额 − 冻结"算法；实现仍在 app，经视图口转调），不自己写减法；账存在但某资源缺键 ⇒ 该维度 0（事实就是
   * 0），与"账不存在"的 null 是两件事。
   */
  private Map<String, Object> availableOf(SimulationState state, TreasuryLocation from) {
    GovToolViews.TreasuryAvailable available =
        support.views().treasuryAvailable(state, from.householdId());
    if (available == null) {
      return null;
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("grain", available.grain());
    view.put("cloth", available.cloth());
    view.put("money", available.money());
    return view;
  }

  /** 一条命令的预览：type + 裸载荷 + payloadJson（与 {@link #submit} 的组包同源）。 */
  private List<Map<String, Object>> commandsPreview(Map<String, Object> payload) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("type", commandType);
    row.put("payload", payload);
    row.put("payloadJson", ToolResults.json(payload));
    return List.of(row);
  }

  /** 国库落点：GOV 单位 id + 当刻有效位置。 */
  private record TreasuryLocation(String unitId, HexCoord at, String householdId) {}
}

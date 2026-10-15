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
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.gov.tools.GovToolPerspective;
import io.mosire.simos.gov.tools.GovToolReads;
import io.mosire.simos.gov.tools.GovToolSupport;
import io.mosire.simos.gov.tools.GovToolViews;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.gov.pay}（D5 / D-004 / R5）：<b>决策人版"给 XXX 政府钱"</b> —— 从<b>调用者所属 GOV</b>的国库账， 把
 * grain/cloth/money 转给参数点名的 GOV 国库账；底层复用 {@code actor.RemitGovTreasury}（三资源、整条原子）。
 *
 * <p>★★ <b>付款人 = 调用者所属 GOV</b>（R5）：身份从 {@link ToolContext#identity()} 取 （{@code
 * decision-maker:<id>}）→ sd 决策人 → {@link Affiliation.Gov}；<b>载荷不允许指定付款人</b>（schema 里没有 from
 * 字段，模型给不出）⇒ 结构性防"代别人付款"。非决策人身份 / 非 GOV 归属 / 所属 GOV 单位不存在或无当刻有效位置 ⇒ 具名拒。
 *
 * <p>★★ <b>收款人任意 GOV</b>：不同于决策令里嵌 {@code actor.RemitGovTreasury} 的"只能上缴给 superiorGov"（那是 {@code
 * AdjudicateTickTool} 的命令专属校验），本工具按 R5 语义只解析收款 GOV 的<b>当刻有效位置</b>；<b>是否放行由既有审批链
 * 决定</b>——本工具是敏感闸（{@link ToolGate.Ask}），决策人<b>直写</b>链路 = {@code AutoApproveGate → ConfirmGate →
 * 待批}， <b>需 GM 在审批面点头</b>。★ <b>决策人只有直写这一条路径</b>：本工具<b>不在</b> {@code ProposalCatalog}（{@code
 * simos.ga.packet.propose} 的可提议清单）里 ⇒ 没有"提议零审批"那条路（2026-10-15 回代码核实；别的 9 条 {@code simos.gov.*} 类2
 * 工具在清单里，别按它们类推）。金额 / 余额 / 冻结 / 原子性的唯一口径在域层 {@link RemitGovTreasuryHandler}，工具不重写一遍。
 *
 * <p>★ <b>preview / apply 同一份解析</b>：{@code preview?} 缺省 {@code true}（只读：返回双方落点、请求量与源账可支配量，
 * 一个字节都不写）；{@code preview=false} 必须给 {@code expectedRevision}，提交一条 {@code actor.RemitGovTreasury}。
 *
 * <p>★ <b>资源声明</b>：只声明 {@code actor} 命名空间（handler 只产 {@code ActorChangeSet}）；工具真正写之前断言
 * <b>付款人自己国库格</b>的 actor 写权限（路径由身份派生的 GOV 当刻位置算出，不取载荷）——GovernmentScope 的 actor 可达面 天然含"自己 +
 * superiorGov"，故付款侧恒可断言通过。收款侧不做工具层资源断言：收款人由决策人点名、由 GM 审批把关（见上）。
 */
public final class GovPayTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gov.pay";

  /** 本工具提交的唯一命令类型（与 handler 的 {@code TYPE} 同一个拼写点）。 */
  private final String commandType;

  /** 本工具只写 actor 命名空间（政府决策人的 actor 可达面 = 自己 + superiorGov 的国库格）。 */
  private static final ResourceManifest ACTOR_WRITE =
      ResourceManifest.of(ToolResources.ACTOR_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final GovToolSupport support;

  /**
   * @param support 政府工具接缝束（写口/读口/视图口/视角口/经济口/命令类型口/人力口；见 {@link GovToolSupport}）
   */
  public GovPayTool(GovToolSupport support) {
    this.support = Objects.requireNonNull(support, "support");
    this.commandType = support.commandTypes().remitGovTreasury();
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "决策人给指定 GOV 政府钱（R5）：付款人 = 调用者所属 GOV（身份派生，载荷不得指定）；"
        + "从付款国库账转 grain/cloth/money 到 toGovId 的国库账，底层 actor.RemitGovTreasury、整条原子。"
        + "参数 {toGovId(必填), grain?/cloth?/money?(缺省 0；负数拒、三项全 0 拒), reason?, preview?(缺省 true=只读预览), "
        + "branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(preview=false 必填)}。"
        + "调用者必须是隶属某个 GOV 的决策人（Army/Nation 归属不收）；付款/收款 GOV 都必须存在、带 GovernmentFormation 且有当刻有效位置。"
        + "★ 需 GM 在审批面点头：本工具是敏感工具，决策人【直写】会停在待批（超时 APPROVAL_DENIED）。"
        + "★ 决策人只有直写这一条路径（本工具不在 simos.ga.packet.propose 的可提议清单里 ⇒ 没有零审批的提议路径）；"
        + "★ 金额/余额/冻结/原子由域层判。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "toGovId",
        ToolSchema.prop("string", "收款 GOV 单位 id（必填；必须存在且带 GovernmentFormation、有当刻有效位置）"));
    props.put("grain", ToolSchema.prop("integer", "支付粮（可选，缺省 0；不得为负；三项至少一个 > 0）"));
    props.put("cloth", ToolSchema.prop("integer", "支付布（可选，缺省 0；不得为负；三项至少一个 > 0）"));
    props.put("money", ToolSchema.prop("integer", "支付银（毫银；可选，缺省 0；不得为负；三项至少一个 > 0）"));
    props.put("reason", ToolSchema.prop("string", "支付原因（可选；给出则必须非空白；进命令载荷与结果）"));
    props.put(
        "preview", ToolSchema.prop("boolean", "true（缺省）= 只读预览；false = 提交 actor.RemitGovTreasury"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop(
            "integer", "preview=false 必填：提交的乐观并发 base revision；preview 的读数也取它（缺省=该分支 head）"));
    return ToolSchema.schema(props, List.of("toGovId"));
  }

  @Override
  public ToolSpec spec() {
    // 敏感写：决策人【直写】走 AutoApproveGate → ConfirmGate → 待批（需 GM 点头）；本工具无提议路径（不在可提议清单里）。
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
        "决策人支付（付款人=调用者所属 GOV，身份派生） to="
            + args.get("toGovId")
            + " grain="
            + args.getOrDefault("grain", 0)
            + " cloth="
            + args.getOrDefault("cloth", 0)
            + " money="
            + args.getOrDefault("money", 0)
            + " preview="
            + args.getOrDefault("preview", true)
            + " branch="
            + args.getOrDefault("branch", ToolResources.DEFAULT_BRANCH),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      String toGovId = ToolSchema.requiredText(args, "toGovId");
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
      SimulationState state =
          support
              .reads()
              .stateAt(branch, expectedRevision < 0L ? null : new RevisionId(expectedRevision));
      Payer payer = resolvePayer(context, state);
      // ★ 付款人由身份派生 ⇒ 资源断言也从身份派生的当刻位置算（不取载荷）：付款侧 fail-closed。
      ToolResources.requireAll(
          context,
          Operation.WRITE,
          List.of(
              ResourceId.of(
                  ToolResources.ACTOR_NAMESPACE,
                  ResourcePaths.actor(payer.at().q(), payer.at().r()))));
      TreasuryLocation to = resolveGov(state, toGovId, "toGovId");
      String reason = ToolSchema.optionalText(args, "reason", null);
      Map<String, Object> payload = commandPayload(payer, to, grain, cloth, money, reason);
      if (preview) {
        return ToolResults.ok(
            view(state, payer, to, grain, cloth, money, reason, payload, true, false, null));
      }
      return submit(
          state, payer, to, grain, cloth, money, reason, payload, branch, expectedRevision);
    } catch (PaymentRejected e) {
      return ToolResult.error("REJECTED", e.getMessage());
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 与其余写工具同一条：资源拒因原样逃到 ToolCallAuthorizer 的边界（不折成 TOOL_ERROR）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "决策人支付失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * 解析付款人：{@code identity → 决策人 → GOV 归属 → GOV 单位 → 当刻有效位置}。任一步落空 ⇒ {@link PaymentRejected}
   * （身份/归属问题不是改参数能修的，与参数形状错分开报）。
   */
  private Payer resolvePayer(ToolContext context, SimulationState state) {
    Optional<String> decisionMakerId = support.perspective().decisionMakerIdOf(context.identity());
    if (decisionMakerId.isEmpty()) {
      throw new PaymentRejected("调用者不是决策人（身份 " + context.identity().instanceId() + "）：支付工具只暴露给决策人");
    }
    GovToolPerspective.GovDecisionMaker maker;
    try {
      maker = support.perspective().decisionMaker(state, decisionMakerId.get());
    } catch (IllegalArgumentException e) {
      throw new PaymentRejected("调用者身份里的决策人 id 非法: " + decisionMakerId.get());
    }
    if (!maker.known()) {
      throw new PaymentRejected("调用者身份不是本世界已知的决策人: " + decisionMakerId.get());
    }
    if (!maker.gov()) {
      throw new PaymentRejected(
          "只有 GOV 归属的决策人可付款（调用者 "
              + decisionMakerId.get()
              + " 归属: "
              + maker.affiliationText()
              + "）");
    }
    UnitId govUnitId = new UnitId(maker.govUnitId());
    UnitState units = GovToolReads.unitState(state);
    Unit unit = units.units().get(govUnitId);
    if (unit == null || !(unit.module().orElse(null) instanceof GovernmentFormation)) {
      throw new PaymentRejected("调用者所属 GOV 单位不存在或不是 GOV: " + govUnitId.value());
    }
    HexCoord at =
        units
            .effectivePosition(unit.id(), state.meta().timestamp())
            .orElseThrow(
                () -> new PaymentRejected("调用者所属 GOV 没有当刻有效位置（付款国库落点未知）: " + govUnitId.value()));
    return new Payer(
        govUnitId.value(),
        at,
        support.perspective().governmentHousehold(unit, govUnitId.value()).value());
  }

  /**
   * 解析一个收款 GOV 的国库落点：必须存在、带 {@link GovernmentFormation}、有当刻有效位置（与 GUI / scope / GM 的 {@code
   * simos.gov.remit} 同口径）——任一不满足 ⇒ {@link IllegalArgumentException}（由 {@link #execute} 折成
   * BAD_REQUEST，零 revision）。
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

  /** 可选金额（缺省 0；类型错由 {@link ToolSupport#optionalLong} 抛 ⇒ BAD_REQUEST）。 */
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
      return "grain/cloth/money 至少一个必须 > 0（至少支付一种资源）";
    }
    return null;
  }

  /** 一条 {@code actor.RemitGovTreasury} 的载荷（字段名与 handler 的解析契约一致）。 */
  private static Map<String, Object> commandPayload(
      Payer payer, TreasuryLocation to, long grain, long cloth, long money, String reason) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("fromHousehold", payer.householdId());
    payload.put("toHousehold", to.householdId());
    payload.put("grain", grain);
    payload.put("cloth", cloth);
    payload.put("money", money);
    if (reason != null) {
      payload.put("reason", reason);
    }
    return payload;
  }

  /** 提交阶段：一条信封走唯一写入口，把三结局折进同一份视图（与 GM 版同制）。 */
  private ToolResult submit(
      SimulationState state,
      Payer payer,
      TreasuryLocation to,
      long grain,
      long cloth,
      long money,
      String reason,
      Map<String, Object> payload,
      BranchId branch,
      long expectedRevision) {
    // ★ 视图在提交前算好（available 取提交前基态）：提交成功后不再读 state，避免"已落盘但工具报错"。
    Map<String, Object> view =
        view(state, payer, to, grain, cloth, money, reason, payload, false, true, null);
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
      Payer payer,
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
    view.put("from", payerView(state, payer));
    view.put("to", toView(to));
    Map<String, Object> requested = new LinkedHashMap<>();
    requested.put("grain", grain);
    requested.put("cloth", cloth);
    requested.put("money", money);
    view.put("requested", requested);
    view.put("commandsPreview", commandsPreview(payload));
    view.put("reason", reason);
    view.put("payerDerivedFromIdentity", true);
    if (submission != null) {
      view.put("submission", submission);
    }
    return view;
  }

  /** 付款 GOV 视图：{@code {unitId,q,r,available}}；国库账不存在 ⇒ {@code available:null}（不填 0）。 */
  private Map<String, Object> payerView(SimulationState state, Payer payer) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("unitId", payer.unitId());
    view.put("q", payer.at().q());
    view.put("r", payer.at().r());
    view.put("available", availableOf(state, payer));
    return view;
  }

  /** 收款 GOV 视图：{@code {unitId,q,r}}（按本工具契约不承诺收款方可用量）。 */
  private static Map<String, Object> toView(TreasuryLocation to) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("unitId", to.unitId());
    view.put("q", to.at().q());
    view.put("r", to.at().r());
    return view;
  }

  /**
   * 付款国库账的可支配量（{@code {grain,cloth,money}}）：账不存在 ⇒ null。
   *
   * <p>★ 走 {@link AvailableStock}（全仓唯一的"余额 − 冻结"算法），不自己写减法；账存在但某资源缺键 ⇒ 该维度 0。
   */
  private Map<String, Object> availableOf(SimulationState state, Payer payer) {
    GovToolViews.TreasuryAvailable available =
        support.views().treasuryAvailable(state, payer.householdId());
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

  /** 付款人（身份派生）：GOV 单位 id + 当刻有效位置。 */
  private record Payer(String unitId, HexCoord at, String householdId) {}

  /** 身份 / 归属层面的拒（不是改参数能修的）：由 {@link #execute} 折成 {@code REJECTED}。 */
  private static final class PaymentRejected extends RuntimeException {

    PaymentRejected(String message) {
      super(message);
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM);
  }
}

package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.household.GovernmentHouseholdResolver;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.core.command.BatchResult;
import io.mosire.simos.core.command.CommandEnvelope;
import io.mosire.simos.core.command.CommandResult;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.GovState;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ★★ <b>Z3c-2 政府工具共用件</b>：GM / 决策人双面工具共享的身份派生、GOV 单位守卫、资源断言与批量提交折叠。
 *
 * <p>★★ <b>身份派生（决策人）</b>：身份只从 {@link ToolContext#identity()} 取（{@code decision-maker:<id>}）→ sd 决策人
 * → {@link Affiliation.Gov}。决策人工具<b>不接受载荷指定操作对象</b>：载荷若显式带了 {@code govUnitId}，必须逐字等于自己的 GOV， 否则具名
 * {@code REJECTED}（越权 GOV 拒）。
 *
 * <p>★★ <b>资源断言锚点 = 自己那个 GOV 单位</b>（{@code unit:<govUnitId>}）：{@code GovScope} 对 GOV 决策人恒授自己单位的
 * unit 前缀，因此"自己 GOV"必过、别的 GOV 必拒；GM 桶的 unit 面是 {@code unlimited}。工具内部对 household/role/档位的校验仍按
 * 命令契约逐条做，资源断言只回答"这个调用者够不够得着这个 GOV 的调度面"。
 *
 * <p>★ <b>不写任何状态</b>：本类只读状态、组信封、折叠结局；所有写入口仍是 {@link CoreSimos#submit} / {@link
 * CoreSimos#submitBatch}。
 */
final class GovToolSupport {

  private GovToolSupport() {}

  /**
   * 一次解析出的 GOV 操作目标：GOV 单位、编制、国库家户、当刻有效位置；{@code decisionMaker} 标记身份来源。
   *
   * @param decisionMakerId 决策人 id（GM 调用 = null）
   */
  record GovTarget(
      UnitId govId,
      Unit unit,
      GovernmentFormation formation,
      HouseholdId governmentHousehold,
      Optional<HexCoord> at,
      boolean decisionMaker,
      String decisionMakerId) {

    GovTarget {
      Objects.requireNonNull(govId, "govId");
      Objects.requireNonNull(unit, "unit");
      Objects.requireNonNull(formation, "formation");
      Objects.requireNonNull(governmentHousehold, "governmentHousehold");
      Objects.requireNonNull(at, "at");
    }
  }

  /**
   * 身份/归属层面的拒（越权 GOV、调用者不是 GOV 决策人、自己 GOV 不存在等）：调用方折 {@code REJECTED}， 与"改参数能修的" BAD_REQUEST 分开。
   */
  static final class GovRejectedException extends RuntimeException {

    GovRejectedException(String message) {
      super(message);
    }
  }

  /** 载荷/参数层面的 GOV 解析（GM 面）：显式 {@code govUnitId} 必填、单位存在且带编制。 */
  static GovTarget resolveGov(
      ToolContext context, SimulationState state, String explicitGovUnitId) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(state, "state");
    Optional<String> decisionMakerId = DecisionCallerFactory.decisionMakerIdOf(context.identity());
    if (decisionMakerId.isPresent()) {
      return resolveDecisionMakerGov(state, decisionMakerId.get(), explicitGovUnitId);
    }
    if (explicitGovUnitId == null || explicitGovUnitId.isBlank()) {
      throw new IllegalArgumentException("govUnitId 必填（GM 调用必须显式点名要操作的 GOV 单位）");
    }
    return target(state, UnitId.parse(explicitGovUnitId), false, null, null);
  }

  /** 决策人身份派生：只能操作自己所属 GOV；显式载荷指定别的 GOV ⇒ 具名越权拒。 */
  private static GovTarget resolveDecisionMakerGov(
      SimulationState state, String decisionMakerId, String explicitGovUnitId) {
    DecisionMaker maker;
    try {
      maker = ToolSupport.sdState(state).decisionMakers().get(new DecisionMakerId(decisionMakerId));
    } catch (IllegalArgumentException e) {
      throw new GovRejectedException("调用者身份里的决策人 id 非法: " + decisionMakerId);
    }
    if (maker == null) {
      throw new GovRejectedException("调用者身份不是本世界已知的决策人: " + decisionMakerId);
    }
    if (!(maker.affiliation() instanceof Affiliation.Gov gov)) {
      throw new GovRejectedException(
          "只有 GOV 归属的决策人可调用政府配置工具（调用者 " + decisionMakerId + " 归属: " + maker.affiliation() + "）");
    }
    if (explicitGovUnitId != null
        && !explicitGovUnitId.isBlank()
        && !explicitGovUnitId.equals(gov.govUnit().value())) {
      throw new GovRejectedException(
          "越权 GOV：载荷指定 "
              + explicitGovUnitId
              + "，但调用者只能操作自己所属 GOV "
              + gov.govUnit().value()
              + "（身份派生，不接受载荷改付款人/改主体）");
    }
    return target(state, gov.govUnit(), true, decisionMakerId, null);
  }

  /**
   * GOV 单位解析（单位存在 + {@link GovernmentFormation} + 国库家户 + 当刻有效位置）。
   *
   * @param decisionMaker true = 决策人路径：单位缺失/不是 GOV 折 {@link GovRejectedException}
   */
  private static GovTarget target(
      SimulationState state,
      UnitId govId,
      boolean decisionMaker,
      String decisionMakerId,
      String fieldLabel) {
    UnitState units = ToolSupport.unitState(state);
    Unit unit = units.units().get(govId);
    if (unit == null) {
      String message = "GOV 单位不存在: " + govId.value();
      if (decisionMaker) {
        throw new GovRejectedException("调用者所属 " + message);
      }
      throw new IllegalArgumentException(
          (fieldLabel == null ? "" : "参数 " + fieldLabel + " ") + message);
    }
    if (!(unit.module().orElse(null) instanceof GovernmentFormation formation)) {
      String message = "单位 " + govId.value() + " 没有 GovernmentFormation，不是 GOV 编制单位";
      if (decisionMaker) {
        throw new GovRejectedException("调用者所属 " + message);
      }
      throw new IllegalArgumentException(
          (fieldLabel == null ? "" : "参数 " + fieldLabel + " ") + message);
    }
    HouseholdId governmentHousehold =
        GovernmentHouseholdResolver.requireGovernmentHousehold(unit, govId.value());
    Optional<HexCoord> at = units.effectivePosition(govId, state.meta().timestamp());
    return new GovTarget(
        govId, unit, formation, governmentHousehold, at, decisionMaker, decisionMakerId);
  }

  /** 资源断言：只锚定自己/点名 GOV 的 unit 路径（GovScope 对决策人只授自己单位；GM unit 面 unlimited）。 */
  static void requireGovWrite(ToolContext context, GovTarget target) {
    ToolSupport.requireAll(
        context, Operation.WRITE, List.of(ToolSupport.resourceUnit(target.govId().value())));
  }

  /** gov 切片提取：缺切片/类型不符 = 装配故障（ERROR 不降级）。 */
  static GovState govState(SimulationState state) {
    Snapshot snapshot =
        state.module("gov").orElseThrow(() -> new IllegalStateException("state 里没有 gov 切片（装配故障）"));
    if (snapshot instanceof GovSnapshot govSnapshot) {
      return govSnapshot.state();
    }
    throw new IllegalStateException(
        "state 的 gov 切片不是 GovSnapshot: " + snapshot.getClass().getName());
  }

  /** 当刻有效位置（无位置 = 空，不伪造坐标）。 */
  static Optional<HexCoord> effectivePosition(SimulationState state, UnitId govId) {
    return ToolSupport.unitState(state).effectivePosition(govId, state.meta().timestamp());
  }

  /** 读 base 状态：preview 与 apply 必须取同一坐标（expectedRevision 缺省 = head）。 */
  static SimulationState stateAt(
      QueryService query, boolean preview, Long expectedRevisionArg, BranchId branch) {
    Objects.requireNonNull(query, "query");
    if (!preview && expectedRevisionArg == null) {
      throw new IllegalArgumentException(
          "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
    }
    long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
    if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
      throw new IllegalArgumentException("expectedRevision 不得为负: " + expectedRevisionArg);
    }
    return query.stateAt(
        expectedRevision < 0L
            ? QueryTarget.head(branch)
            : QueryTarget.at(branch, new RevisionId(expectedRevision)));
  }

  /** 批内一条命令（命令 id 独立、correlationId = batchId）。 */
  static CommandEnvelope envelope(
      String initiator,
      String batchId,
      BranchId branch,
      RevisionId expectedRevision,
      String type,
      String payloadJson) {
    return new CommandEnvelope(
        UUID.randomUUID().toString(),
        batchId,
        initiator,
        branch,
        expectedRevision,
        type,
        payloadJson);
  }

  /** 逐条命令预览（type + payloadJson 原样；与提交组包同源）。 */
  static List<Map<String, Object>> commandsPreview(List<CommandEnvelope> batch) {
    List<Map<String, Object>> rows = new ArrayList<>(batch.size());
    for (CommandEnvelope envelope : batch) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", envelope.type());
      row.put("payloadJson", envelope.payloadJson());
      rows.add(row);
    }
    return List.copyOf(rows);
  }

  /** 提交一条命令并把三结局折进同一份视图（与既有窄工具同制）。 */
  static ToolResult submitOne(
      CoreSimos core,
      Map<String, Object> view,
      String initiator,
      String commandId,
      String type,
      String payloadJson,
      BranchId branch,
      long expectedRevision) {
    CommandEnvelope envelope =
        new CommandEnvelope(
            commandId,
            commandId,
            initiator,
            branch,
            new RevisionId(expectedRevision),
            type,
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
        submission.put("commandId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolSupport.json(view));
      }
      case CommandResult.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", rejected.reason());
        submission.put("commandId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolSupport.json(view));
      }
    };
  }

  /**
   * 原子批提交（一批 = 一条 revision）：把三结局折进同一份视图；整批拒时逐条列真拒因（不吞成一句"提交失败"）。
   *
   * @param view preview 视图（本方法在其上补 {@code submitted/submission}；不得为 null）
   */
  static ToolResult submitBatch(
      CoreSimos core, Map<String, Object> view, String batchId, List<CommandEnvelope> batch) {
    BatchResult result = core.submitBatch(batch);
    view.put("preview", false);
    view.put("submitted", true);
    if (result instanceof BatchResult.Committed committed) {
      view.put("submission", ToolSupport.committedView(committed.ref(), batchId, batchId));
      return ToolSupport.ok(view);
    }
    if (result instanceof BatchResult.Conflict conflict) {
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("result", "conflict");
      submission.put("current", ToolSupport.stateRef(conflict.current()));
      submission.put("commandId", batchId);
      submission.put("correlationId", batchId);
      view.put("submission", submission);
      return ToolResult.error("CONFLICT", ToolSupport.json(view));
    }
    BatchResult.Rejected rejected = (BatchResult.Rejected) result;
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "rejected");
    submission.put("commandId", batchId);
    submission.put("correlationId", batchId);
    List<Map<String, Object>> rows = new ArrayList<>();
    List<CommandResult> outcomes = rejected.outcomes().stream().map(o -> o.result()).toList();
    for (int i = 0; i < outcomes.size(); i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", batch.get(i).type());
      CommandResult outcome = outcomes.get(i);
      row.put(
          "reason",
          outcome instanceof CommandResult.Rejected rejection
              ? rejection.reason()
              : outcome.toString());
      rows.add(row);
    }
    submission.put("commands", List.copyOf(rows));
    view.put("submission", submission);
    return ToolResult.error("REJECTED", ToolSupport.json(view));
  }
}

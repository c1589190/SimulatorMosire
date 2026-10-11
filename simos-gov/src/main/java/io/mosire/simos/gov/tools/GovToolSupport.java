package io.mosire.simos.gov.tools;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.GovernmentFormation;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * ★★ <b>政府工具共用件（Z3c-2；2026-10-12 B3 第六域下放）</b>：身份派生、GOV 单位守卫、资源断言与提交折叠。
 *
 * <p>★★ <b>身份派生（决策人）</b>：身份只从 {@link ToolContext#identity()} 取（{@code decision-maker:<id>}）→ sd 决策人
 * → {@code Affiliation.Gov}（经 {@link GovToolPerspective}，sd 是 gov 够不着的域）。决策人工具<b>不接受载荷指定操作对象</b>：
 * 载荷若显式带了 {@code govUnitId}，必须逐字等于自己的 GOV，否则具名 {@code REJECTED}（越权 GOV 拒）。
 *
 * <p>★★ <b>资源断言锚点 = 自己那个 GOV 单位</b>（{@code unit:<govUnitId>}）：{@code GovScope} 对 GOV 决策人恒授自己单位的
 * unit 前缀，因此"自己 GOV"必过、别的 GOV 必拒；GM 桶的 unit 面是 {@code unlimited}。工具内部对 household/role/档位的校验仍按
 * 命令契约逐条做，资源断言只回答"这个调用者够不够得着这个 GOV 的调度面"。
 *
 * <p>★★ <b>本批（B3 第六域）的形态变化：静态助手 → 实例化的接缝束</b>。迁入前它是 app 的包内静态助手，直接吃 {@code CoreSimos} / {@code
 * QueryService} / {@code ToolSupport}。下放后 gov 的工具面一共要五条接缝（写口 / 读口 / 视图口 / 视角口 / 经济口 + 命令类型口），逐条塞进
 * 36 个工具的构造器只会让每个构造器变成一长串位置参数（错一个位置就静默接错线）。因此本类收成 **一个由组合根构造的实例**：端口仍是 {@code ga-spi} 与 gov
 * 自有的那几条（没有新增跨模块契约），只是把"它们是谁"钉在一处。
 *
 * <p>★ <b>不写任何状态</b>：本类只读状态、组命令请求、折叠结局；所有写入口仍是 {@link ToolWritePort}（app 侧实现转调 {@code CoreSimos}）。
 */
public final class GovToolSupport {

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final GovToolViews views;
  private final GovToolPerspective perspective;
  private final GovToolEconomy economy;
  private final GovToolCommandTypes commandTypes;
  private final GovToolManpower manpower;

  public GovToolSupport(
      ToolWritePort writes,
      ToolReadPort reads,
      GovToolViews views,
      GovToolPerspective perspective,
      GovToolEconomy economy,
      GovToolCommandTypes commandTypes,
      GovToolManpower manpower) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.views = Objects.requireNonNull(views, "views");
    this.perspective = Objects.requireNonNull(perspective, "perspective");
    this.economy = Objects.requireNonNull(economy, "economy");
    this.commandTypes = Objects.requireNonNull(commandTypes, "commandTypes");
    this.manpower = Objects.requireNonNull(manpower, "manpower");
  }

  /** 窄写口（谁在调、命令 id / 发起者由 app 适配器负责）。 */
  public ToolWritePort writes() {
    return writes;
  }

  /** 窄读口（{@code stateAt} 的值参坐标）。 */
  public ToolReadPort reads() {
    return reads;
  }

  /** 视图口（跨切片投影 + 需要组合根装配件的读数）。 */
  public GovToolViews views() {
    return views;
  }

  /** 视角口（身份派生、围栏、sd 事实）。 */
  public GovToolPerspective perspective() {
    return perspective;
  }

  /** 经济口（economy 切片事实 + 政府政策解析/合并）。 */
  public GovToolEconomy economy() {
    return economy;
  }

  /**
   * 跨域命令类型口（economy/actor/sd 的 handler 拼写点）。
   *
   * <p>★ {@code @SuppressFBWarnings} 是**精确位置**的误报豁免：本类是被装配面构造一次、只读转发的接缝束，端口实现是 **无状态适配器**（{@code
   * AppGovToolCommandTypes} 只返回各域 handler 的常量串）⇒ 不存在"暴露内部可变表示"。 SpotBugs 对
   * "返回接口类型"无法证明不可变，故只在这一处按仓内既有形制（{@code @SuppressFBWarnings} + 理由）豁免，不改任何行为。
   */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "返回的是构造期注入的无状态端口适配器（只回常量串），不是本类的内部可变状态 ⇒ 误报")
  public GovToolCommandTypes commandTypes() {
    return commandTypes;
  }

  /** 人力口（选人瀑布 + 家户劳动量；时钟住在 app 适配器里）。 */
  public GovToolManpower manpower() {
    return manpower;
  }

  /**
   * 一次解析出的 GOV 操作目标：GOV 单位、编制、国库家户、当刻有效位置；{@code decisionMaker} 标记身份来源。
   *
   * @param decisionMakerId 决策人 id（GM 调用 = null）
   */
  public record GovTarget(
      UnitId govId,
      Unit unit,
      GovernmentFormation formation,
      HouseholdId governmentHousehold,
      Optional<HexCoord> at,
      boolean decisionMaker,
      String decisionMakerId) {

    public GovTarget {
      Objects.requireNonNull(govId, "govId");
      Objects.requireNonNull(unit, "unit");
      Objects.requireNonNull(formation, "formation");
      Objects.requireNonNull(governmentHousehold, "governmentHousehold");
      Objects.requireNonNull(at, "at");
    }
  }

  /** 身份/归属层面的拒（越权 GOV、调用者不是 GOV 决策人、自己 GOV 不存在等）：调用方折 {@code REJECTED}，与"改参数能修的" BAD_REQUEST 分开。 */
  public static final class GovRejectedException extends RuntimeException {

    public GovRejectedException(String message) {
      super(message);
    }
  }

  /** 载荷/参数层面的 GOV 解析（GM 面）：显式 {@code govUnitId} 必填、单位存在且带编制。 */
  public GovTarget resolveGov(
      ToolContext context, SimulationState state, String explicitGovUnitId) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(state, "state");
    Optional<String> decisionMakerId = perspective.decisionMakerIdOf(context.identity());
    if (decisionMakerId.isPresent()) {
      return resolveDecisionMakerGov(state, decisionMakerId.get(), explicitGovUnitId);
    }
    if (explicitGovUnitId == null || explicitGovUnitId.isBlank()) {
      throw new IllegalArgumentException("govUnitId 必填（GM 调用必须显式点名要操作的 GOV 单位）");
    }
    return target(state, UnitId.parse(explicitGovUnitId), false, null, null);
  }

  /** 决策人身份派生：只能操作自己所属 GOV；显式载荷指定别的 GOV ⇒ 具名越权拒。 */
  private GovTarget resolveDecisionMakerGov(
      SimulationState state, String decisionMakerId, String explicitGovUnitId) {
    GovToolPerspective.GovDecisionMaker maker;
    try {
      maker = perspective.decisionMaker(state, decisionMakerId);
    } catch (IllegalArgumentException e) {
      throw new GovRejectedException("调用者身份里的决策人 id 非法: " + decisionMakerId);
    }
    if (!maker.known()) {
      throw new GovRejectedException("调用者身份不是本世界已知的决策人: " + decisionMakerId);
    }
    if (!maker.gov()) {
      throw new GovRejectedException(
          "只有 GOV 归属的决策人可调用政府配置工具（调用者 "
              + decisionMakerId
              + " 归属: "
              + maker.affiliationText()
              + "）");
    }
    if (explicitGovUnitId != null
        && !explicitGovUnitId.isBlank()
        && !explicitGovUnitId.equals(maker.govUnitId())) {
      throw new GovRejectedException(
          "越权 GOV：载荷指定 "
              + explicitGovUnitId
              + "，但调用者只能操作自己所属 GOV "
              + maker.govUnitId()
              + "（身份派生，不接受载荷改付款人/改主体）");
    }
    // ★ 用 new UnitId(value) 而不是 UnitId.parse(...)：后者会剥掉 "unit:" 前缀，而迁入前这里拿到的就是
    //   sd 的 Affiliation.Gov.govUnit()（一个原样的 UnitId 值对象）⇒ 逐值等价。
    return target(state, new UnitId(maker.govUnitId()), true, decisionMakerId, null);
  }

  /**
   * GOV <b>单位</b>解析（单位存在 + {@link GovernmentFormation} + 国库家户 + 当刻有效位置）。
   *
   * <p>★ <b>参数名是 {@code govUnitId}（GOV 单位的 {@link UnitId}），不是 {@code GovernmentId}</b>：两者是不同的稳定身份
   * ——政府记录的身份由 {@code GovernmentIds.ofUnit(govUnitId)} 派生（{@code gov-unit-<govUnitId>}），要在 {@code
   * EconomyData.governments()} 里查政府必须**先派生**。这里收的是**单位** id，查的是单位表与单位编制，全方法没有一处按政府 id 查表。
   *
   * @param decisionMaker true = 决策人路径：单位缺失/不是 GOV 折 {@link GovRejectedException}
   */
  private GovTarget target(
      SimulationState state,
      UnitId govUnitId,
      boolean decisionMaker,
      String decisionMakerId,
      String fieldLabel) {
    UnitState units = GovToolReads.unitState(state);
    Unit unit = units.units().get(govUnitId);
    if (unit == null) {
      String message = "GOV 单位不存在: " + govUnitId.value();
      if (decisionMaker) {
        throw new GovRejectedException("调用者所属 " + message);
      }
      throw new IllegalArgumentException(
          (fieldLabel == null ? "" : "参数 " + fieldLabel + " ") + message);
    }
    if (!(unit.module().orElse(null) instanceof GovernmentFormation formation)) {
      String message = "单位 " + govUnitId.value() + " 没有 GovernmentFormation，不是 GOV 编制单位";
      if (decisionMaker) {
        throw new GovRejectedException("调用者所属 " + message);
      }
      throw new IllegalArgumentException(
          (fieldLabel == null ? "" : "参数 " + fieldLabel + " ") + message);
    }
    HouseholdId governmentHousehold = perspective.governmentHousehold(unit, govUnitId.value());
    Optional<HexCoord> at = units.effectivePosition(govUnitId, state.meta().timestamp());
    return new GovTarget(
        govUnitId, unit, formation, governmentHousehold, at, decisionMaker, decisionMakerId);
  }

  /** 资源断言：只锚定自己/点名 GOV 的 unit 路径（GovScope 对决策人只授自己单位；GM unit 面 unlimited）。 */
  public void requireGovWrite(ToolContext context, GovTarget target) {
    ToolResources.requireAll(
        context, Operation.WRITE, List.of(ToolResources.resourceUnit(target.govId().value())));
  }

  /** 当刻有效位置（无位置 = 空，不伪造坐标）。 */
  public Optional<HexCoord> effectivePosition(SimulationState state, UnitId govId) {
    return GovToolReads.unitState(state).effectivePosition(govId, state.meta().timestamp());
  }

  /** 读 base 状态：preview 与 apply 必须取同一坐标（expectedRevision 缺省 = head）。 */
  public SimulationState stateAt(boolean preview, Long expectedRevisionArg, BranchId branch) {
    Objects.requireNonNull(branch, "branch");
    if (!preview && expectedRevisionArg == null) {
      throw new IllegalArgumentException(
          "preview=false 时必须给 expectedRevision（提交的乐观并发 base revision）");
    }
    long expectedRevision = expectedRevisionArg == null ? -1L : expectedRevisionArg;
    if (expectedRevisionArg != null && expectedRevisionArg < 0L) {
      throw new IllegalArgumentException("expectedRevision 不得为负: " + expectedRevisionArg);
    }
    return reads.stateAt(branch, expectedRevision < 0L ? null : new RevisionId(expectedRevision));
  }

  /** 批内一条命令请求（{@code type} + {@code payloadJson}；命令 id / 调用链 id 由写口适配器给）。 */
  public static ToolWritePort.Request request(String type, String payloadJson) {
    return new ToolWritePort.Request(type, payloadJson);
  }

  /** 逐条命令预览（type + payloadJson 原样；与提交组包同源）。 */
  public static List<Map<String, Object>> commandsPreview(List<ToolWritePort.Request> batch) {
    List<Map<String, Object>> rows = new ArrayList<>(batch.size());
    for (ToolWritePort.Request request : batch) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", request.type());
      row.put("payloadJson", request.payloadJson());
      rows.add(row);
    }
    // ★ 保序冻结在赋值处（本仓口径：不用 List.copyOf —— 迭代序不是内容的纯函数，且 SpotBugs 认不出它的不可变性）。
    return Collections.unmodifiableList(rows);
  }

  /**
   * 提交一条命令并把三结局折进同一份视图（与既有窄工具同制）。
   *
   * <p>★ {@code commandId} 不再由工具预生成：它由写口适配器写进信封并**回报**，工具用回报值填视图 ⇒ "报出去的 id == 落盘信封 里的
   * id"（迁入前工具自己生成、适配器照用，效果相同；区别只在谁生成，见 {@link ToolWritePort.Receipt}）。
   */
  public ToolResult submitOne(
      Map<String, Object> view,
      String type,
      String payloadJson,
      BranchId branch,
      long expectedRevision) {
    ToolWritePort.Receipt receipt =
        writes.submitWithReceipt(branch, () -> new RevisionId(expectedRevision), type, payloadJson);
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
        submission.put("commandId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("CONFLICT", ToolResults.json(view));
      }
      case ToolWritePort.Outcome.Rejected rejected -> {
        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("result", "rejected");
        submission.put("reason", rejected.reason());
        submission.put("commandId", commandId);
        view.put("submission", submission);
        yield ToolResult.error("REJECTED", ToolResults.json(view));
      }
    };
  }

  /**
   * 原子批提交（一批 = 一条 revision）：把三结局折进同一份视图；整批拒时逐条列真拒因（不吞成一句"提交失败"）。
   *
   * @param view preview 视图（本方法在其上补 {@code submitted/submission}；不得为 null）
   * @param batchId 本批的调用链 id（工具生成：载荷里的确定性幂等键要用它，见 {@link ToolWritePort#submitBatchWithReceipt})
   */
  public ToolResult submitBatch(
      Map<String, Object> view,
      String batchId,
      BranchId branch,
      long expectedRevision,
      List<ToolWritePort.Request> batch) {
    ToolWritePort.BatchReceipt receipt =
        writes.submitBatchWithReceipt(branch, new RevisionId(expectedRevision), batch, batchId);
    String correlationId = receipt.correlationId();
    view.put("preview", false);
    view.put("submitted", true);
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Committed committed) {
      view.put(
          "submission", ToolResults.committedView(committed.ref(), correlationId, correlationId));
      return ToolResults.ok(view);
    }
    if (receipt.outcome() instanceof ToolWritePort.BatchOutcome.Conflict conflict) {
      Map<String, Object> submission = new LinkedHashMap<>();
      submission.put("result", "conflict");
      submission.put("current", ToolResults.stateRef(conflict.current()));
      submission.put("commandId", correlationId);
      submission.put("correlationId", correlationId);
      view.put("submission", submission);
      return ToolResult.error("CONFLICT", ToolResults.json(view));
    }
    ToolWritePort.BatchOutcome.Rejected rejected =
        (ToolWritePort.BatchOutcome.Rejected) receipt.outcome();
    Map<String, Object> submission = new LinkedHashMap<>();
    submission.put("result", "rejected");
    submission.put("commandId", correlationId);
    submission.put("correlationId", correlationId);
    List<Map<String, Object>> rows = new ArrayList<>();
    List<ToolWritePort.Outcome> outcomes = rejected.outcomes();
    for (int i = 0; i < outcomes.size(); i++) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("type", batch.get(i).type());
      ToolWritePort.Outcome outcome = outcomes.get(i);
      row.put(
          "reason",
          outcome instanceof ToolWritePort.Outcome.Rejected rejection
              ? rejection.reason()
              : outcome.toString());
      rows.add(row);
    }
    submission.put("commands", Collections.unmodifiableList(rows));
    view.put("submission", submission);
    return ToolResult.error("REJECTED", ToolResults.json(view));
  }

  /** 一次批提交的调用链 id（工具在组批**之前**生成：载荷里的幂等键要用它）。 */
  public static String newBatchId() {
    return UUID.randomUUID().toString();
  }
}

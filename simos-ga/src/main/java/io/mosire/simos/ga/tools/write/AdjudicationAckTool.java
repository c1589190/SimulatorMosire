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
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.SdInfoEntry;
import io.mosire.simos.ga.spi.PutInfoHandler;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.DecisionMakerId;
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
 * ★★ {@code simos.ga.adjudication.ack}（DM 面，2026-10-14 GM⇄DM 设计书
 * §3.5）：<b>决策人确认"这次推演我认了"</b>——一轮问答的收尾动作。
 *
 * <pre>{@code
 * {"tick":12,"note":"可选","branch":"可选","expectedRevision":"可选"}
 * }</pre>
 *
 * <p>★★ <b>它属于类3 {@code DM_FREE}</b>（设计书 §5 权限表）：这是 DM 的**一次表态**，<b>零世界效果</b>——产物只是一条 INFO， 要 **经
 * GM 才生效**（GM 用 {@code simos.gm.round} 看谁确认了推演）。故 {@code gate() = ALLOW}、{@code roles() = {DM}}， 与
 * DM 侧其余"写自己的包 / 自己的信 / 自己的确认"同制。
 *
 * <p>★★ <b>天然幂等一次，不用自建限流器</b>：条目 id 是**显式**的 {@code ack-<tick>-<dm>}，而 {@code PutInfoHandler} 对显式
 * id **命令期查重**（{@code containsInfoId} 命中即拒、不留 revision）⇒ 同一个 {@code (tick, dm)} 的**第二条**
 * 当场具名拒（{@code REJECTED}，理由原文回给调用方）。这条既有的机制就是本工具的幂等边界，不另造状态。
 *
 * <p>★ <b>身份不采信载荷</b>：DM 从 {@link GaToolIdentity#decisionMakerIdOf} 派生（解不出 ⇒ {@code FORBIDDEN}），
 * 地址段与 {@code tags} 都用派生值 ⇒ 谁也不能替别人确认推演（与 {@code SubmitPacketTool} 同形）。
 *
 * <p>★ <b>tick 不得记在未来</b>：与 {@code ga.PutInfo} 同口径（过去合法——"我刚才那轮没来得及确认"是真实场景）。 为负 ⇒ 直接 {@code
 * BAD_REQUEST}（{@code SdInfoEntry} 的构造期判据是 {@code tick ≥ 0}，但那会以 handler 的具名拒返回， 不如在工具侧说清）。
 *
 * <p>★ <b>资源断言照抄 {@code SubmitPacketTool}</b>：{@code sd:decision-packet/<自己>}（自指域，决策人基础可达面里已有它）
 * ——因为本工具落的是"我自己的决策流程"里的一条 INFO，不是别人的数据。
 */
public final class AdjudicationAckTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}（它提交的是既有的 ga.PutInfo）。 */
  public static final String NAME = "simos.ga.adjudication.ack";

  /** 条目 {@code key}：确认条目的唯一拼写点（{@code simos.gm.round} 也引用它，不抄字面量）。 */
  public static final String KEY_ACK = "ack";

  /** 地址族名（{@code ga:adjudicationAck.<tick>.<dm>} 的第二段）。 */
  public static final String ADDRESS_KIND = "adjudicationAck";

  /** 工具面事件的发射通道（分类 = {@link GaLog#tool()}，来源 = {@link GaLogSource#GA_TOOL_CALL}）。 */
  private static final LogChannel TOOL = EventLog.channel(GaLog.tool());

  /**
   * 写 {@code sd} 域（与 {@code SubmitPacketTool} 同款：本工具钉死的 {@code ga.PutInfo} 只产 {@code GaChangeSet}）。
   */
  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolResources.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final GaToolIdentity identity;

  public AdjudicationAckTool(ToolWritePort writes, ToolReadPort reads, GaToolIdentity identity) {
    this.writes = Objects.requireNonNull(writes, "writes");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.identity = Objects.requireNonNull(identity, "identity");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "确认**GM 刚做完的那次推演**（本轮问答的收尾动作）：载荷 {tick, note?}。tick = 你确认的那次推演所属的 tick"
        + "（必填整数、不得为负、**不得记在未来**——与 ga.PutInfo 同口径；过去合法）。note 可选。"
        + "★ 本工具**零世界效果**：只落一条 INFO，要经 GM 才生效（GM 用 simos.gm.round 看谁确认了推演）。"
        + "同一个 tick **只能确认一次**（同 tick 第二条 ⇒ 命令期具名拒）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("tick", ToolSchema.prop("integer", "你确认的那次推演所属的 tick（必填、≥ 0、不得为未来）"));
    props.put("note", ToolSchema.prop("string", "可选备注（例如「哪几条令我看过了」）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of("tick"));
  }

  /**
   * ★ 写感知层 ⇒ {@code destructive=true}（它确实落一条 revision）、**{@code sensitive=false}**：闸门是"这是 DM 的
   * 一次表态、产物要经 GM 才生效"（设计书 §5 类3），标 sensitive 会让"要不要停下来问人"出现第二处真相。
   */
  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, false, true);
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  /** ★ 类3 {@code DM_FREE}：DM 的一次表态，零世界效果（设计书 §5 权限表）。 */
  @Override
  public ToolGate gate(ToolContext context) {
    return ToolGate.ALLOW;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      // ★ 身份只从 context 派生（不采信载荷）：照 SubmitPacketTool 的同形制；解不出 ⇒ FORBIDDEN。
      Optional<String> decisionMakerRaw = identity.decisionMakerIdOf(context.identity());
      if (decisionMakerRaw.isEmpty()) {
        return reject("FORBIDDEN", NAME + " 只能由决策人身份调用（身份里没有 decision-maker: 前缀）");
      }
      DecisionMakerId decisionMaker = DecisionMakerId.parse(decisionMakerRaw.get());
      long tick = ToolSchema.requiredLong(args, "tick");
      if (tick < 0L) {
        return reject("BAD_REQUEST", "tick 不得为负: " + tick);
      }
      String note = ToolSchema.optionalText(args, "note", null);

      BranchId branch = GaToolReads.branch(args);
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      long worldTick = state.meta().timestamp().tick();
      if (tick > worldTick) {
        return reject("BAD_REQUEST", "确认不得记在未来：载荷 tick " + tick + " > 世界 tick " + worldTick);
      }

      // ★ 资源断言照抄 SubmitPacketTool 那一行（自指域；决策人基础可达面里有它）——在 submit 之前 ⇒ 被拒时世界零变化。
      ToolResources.requireAll(
          context,
          Operation.WRITE,
          List.of(ToolResources.resourceSd(identity.decisionPacketKind(), decisionMaker.value())));

      String infoId = ackId(tick, decisionMaker.value());
      Address address = ackAddress(tick, decisionMaker.value());
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("dm", decisionMaker.value());
      value.put("tick", tick);
      if (note != null && !note.isBlank()) {
        value.put("note", note);
      }
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("address", address.canonical());
      payload.put("key", KEY_ACK);
      payload.put("value", ToolResults.json(value));
      // ★ 显式 id：同 tick 第二条被 PutInfoHandler **命令期具名拒**（天然幂等一次，见类注）。
      payload.put("id", infoId);
      payload.put("tags", List.of(decisionMaker.value()));
      payload.put("tick", tick);

      ToolWritePort.Receipt receipt =
          writes.submitWithReceipt(
              branch,
              () -> new RevisionId(state.meta().ref().revision().value()),
              PutInfoHandler.TYPE,
              ToolResults.json(payload));

      Map<String, Object> view = new LinkedHashMap<>();
      view.put("address", address.canonical());
      view.put("id", infoId);
      view.put("dm", decisionMaker.value());
      view.put("tick", tick);
      if (note != null && !note.isBlank()) {
        view.put("note", note);
      }
      view.put("acknowledged", true);
      view.put("commandId", receipt.commandId());
      return switch (receipt.outcome()) {
        case ToolWritePort.Outcome.Committed committed -> {
          TOOL.debug(
              LogEvent.of(
                  "ADJUDICATION_ACK_APPLIED",
                  GaLogSource.GA_TOOL_CALL,
                  "tool",
                  NAME,
                  "id",
                  infoId,
                  "dm",
                  decisionMaker.value(),
                  "tick",
                  tick));
          view.put(
              "submission",
              ToolResults.committedView(committed.ref(), receipt.commandId(), receipt.commandId()));
          yield ToolResults.ok(view);
        }
        case ToolWritePort.Outcome.Conflict conflict -> {
          Map<String, Object> submission = new LinkedHashMap<>();
          submission.put("result", "conflict");
          submission.put("current", ToolResults.stateRef(conflict.current()));
          view.put("submission", submission);
          yield ToolResult.error("CONFLICT", ToolResults.json(view));
        }
        case ToolWritePort.Outcome.Rejected rejectedOutcome -> {
          // ★ 本 tick 已经确认过 ⇒ 走到这里（PutInfoHandler 对显式 id 命令期查重），理由原文回给调用方。
          Map<String, Object> submission = new LinkedHashMap<>();
          submission.put("result", "rejected");
          submission.put("reason", rejectedOutcome.reason());
          view.put("acknowledged", false);
          view.put("submission", submission);
          yield ToolResult.error("REJECTED", ToolResults.json(view));
        }
      };
    } catch (IllegalArgumentException e) {
      return reject("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 不许把资源拒因折成 TOOL_ERROR（照 AbstractNarrowWriteTool 的同款处置）：边界上有一条
      //   catch (ResourceDeniedException) → RESOURCE_DENIED 只在异常真的逃出去时才有机会跑。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "确认失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 具名拒绝的唯一发射点（业务拒绝 = INFO，用户 2026-10-23 裁定）；拒因原文同时在返回值里给调用方。 */
  private static ToolResult reject(String code, String reason) {
    TOOL.info(
        LogEvent.of(
            "ADJUDICATION_ACK_REJECTED",
            GaLogSource.GA_TOOL_CALL,
            "tool",
            NAME,
            "code",
            code,
            "reason",
            reason));
    return ToolResult.error(code, reason);
  }

  // ── 确认条目的形状（唯一拼写点：simos.gm.round 从这里取，不各写一份）────────────────────────────

  /** 确认条目 id：{@code ack-<tick>-<dm>}（显式给 ⇒ 命令期查重 ⇒ 同 tick 一次）。 */
  public static String ackId(long tick, String decisionMakerId) {
    return "ack-" + tick + "-" + decisionMakerId;
  }

  /** 确认条目地址：{@code ga:adjudicationAck.<tick>.<dm>}。 */
  public static Address ackAddress(long tick, String decisionMakerId) {
    return new Address(
        List.of(new Namespace("ga"), Entity.of(ADDRESS_KIND, tick + "." + decisionMakerId)));
  }

  /**
   * 该 {@code (tick, dm)} 确认过没有（**纯派生读口，零新状态**）：查 {@link #ackAddress} 那一段地址下有没有 {@code key ==
   * }{@link #KEY_ACK} 的条目。
   *
   * <p>★ 判据取**地址 + key**（不是前缀扫描）：地址由 {@link #ackAddress} 现拼，与写侧同一段代码 ⇒ 不存在"两处拼串漂移"。 值里的 {@code
   * dm}/{@code tick} 不参与判定（它们只是给人看的回执）。
   */
  public static boolean isAcked(GaState ga, long tick, String decisionMakerId) {
    List<SdInfoEntry> entries = ga.info().get(ackAddress(tick, decisionMakerId).canonical());
    if (entries == null) {
      return false;
    }
    for (SdInfoEntry entry : entries) {
      if (KEY_ACK.equals(entry.key())) {
        return true;
      }
    }
    return false;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点：**决策人面**（GM 的表态面是 {@code simos.gm.round}，它只读）。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM);
  }
}

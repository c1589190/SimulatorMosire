package io.mosire.simos.ga.tools.write;

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
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.tools.DecisionPacketViews;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.ga.packet.my}（DM 面；D2 决策包计划 §3.2）：决策人读**自己**的本 tick（或指定 tick）决策包——状态、 intent、逐
 * call 的参数/目标/预览/拟稿检查。
 *
 * <p>★ <b>只读口</b>：它**没有** {@code gate()} 覆写（读口照旧走缺省的直放；本批"权限转类3"针对的是六个写口）。身份从 {@code
 * context.identity()} 派生、包 id 由身份 + tick 派生 ⇒ **读到的一定是自己的包**（载荷里没有 proposer 字段可自报）。
 *
 * <p>★ <b>它是"改/删/撤回"的复查口</b>：{@link ReviseCallTool} / {@link DropCallTool} 要的 {@code callIndex}、以及
 * {@link ReopenPacketTool} 要判断的 {@code status}/{@code decidedBy}，都能在这里先看清楚（返回视图里就有它们）。
 *
 * <p>★ 视图与 GM 的 {@code simos.gm.packet} 共用 {@link DecisionPacketViews}（同字段口径，两处一起变）。★ 旧名 {@code
 * simos.sd.packet.my} 不做兼容（Alpha 不兼容旧档）。
 */
public final class MyPacketTool implements SimosTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.ga.packet.my";

  private static final ResourceManifest SD_READ =
      ResourceManifest.of(ToolResources.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final ToolReadPort reads;
  private final GaToolIdentity identity;

  public MyPacketTool(ToolReadPort reads, GaToolIdentity identity) {
    this.reads = Objects.requireNonNull(reads, "reads");
    this.identity = Objects.requireNonNull(identity, "identity");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "决策人读自己的决策包（只读）：参数 {tick?(缺省 = 当前世界 tick), branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。返回 {packetId, branch, tick, proposerId, status, intent, "
        + "createdAtRevision, decidedBy?, decidedAtRevision?, reasonInfoId?, decisionNote?, calls:[…]}（call 含 "
        + "callIndex/toolName/args/preview 解析后的 Map 与原始 JSON）。"
        + "★ 改/删/撤回前先读它：revise/drop 要的 callIndex 与 reopen 要看的 status/decidedBy 都在这里。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("tick", ToolSchema.prop("integer", "要读的 tick（缺省 = 当前世界 tick）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put("expectedRevision", ToolSchema.prop("integer", "读取的 revision（缺省 = 该分支 head）"));
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.GUEST);
  }

  @Override
  public ResourceManifest resources() {
    return SD_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Optional<String> decisionMakerId = identity.decisionMakerIdOf(context.identity());
      if (decisionMakerId.isEmpty()) {
        return ToolResult.error("FORBIDDEN", NAME + " 只能由决策人身份调用（身份里没有 decision-maker: 前缀）");
      }

      DecisionMakerId proposerId = DecisionMakerId.parse(decisionMakerId.get());
      Map<String, Object> args = context.arguments();
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      Long tickArg = ToolSchema.optionalLong(args, "tick");
      if (tickArg != null && tickArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "tick 不得为负: " + tickArg);
      }
      long tick = tickArg == null ? state.meta().timestamp().tick() : tickArg;
      ToolResources.requireAll(
          context,
          Operation.READ,
          List.of(ToolResources.resourceSd(identity.decisionPacketKind(), proposerId.value())));
      DecisionPacketId packetId = PacketDraftSupport.packetIdValueOf(proposerId, tick);
      DecisionPacket packet = ga.decisionPackets().get(packetId);
      if (packet == null) {
        return ToolResult.error("NOT_FOUND", "本 tick 没有决策包: " + packetId.value());
      }
      if (!packet.proposerId().equals(proposerId)) {
        // 身份派生下不可能发生；留一条响亮的兜底，好过静默返回别人的包。
        return ToolResult.error("FORBIDDEN", "决策包不属于调用者: " + packetId.value());
      }
      return ToolResults.ok(DecisionPacketViews.packetView(packet, state));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e; // 资源拒因原样逃到 ToolCallAuthorizer 边界，折成 RESOURCE_DENIED
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "读取决策包失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM);
  }
}

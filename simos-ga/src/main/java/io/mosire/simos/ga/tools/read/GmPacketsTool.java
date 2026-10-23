package io.mosire.simos.ga.tools.read;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.ga.spi.GmOnlyRead;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code simos.gm.packets}（D2 决策包计划 §4）：GM 的决策包**摘要列表**（只读、GM 桶）。
 *
 * <p>★ 参与筛选：tick / status / proposerId / tool（含该工具名的一条 call）；只发
 * id/tick/proposer/status/callCount/toolNames。
 *
 * <p>★ 只在 GM 桶（{@link GmOnlyRead}），资源声明 sd 只读；GM 的 sd 命名空间是 unlimited。
 */
public final class GmPacketsTool implements SimosTool, GmOnlyRead {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / PAYLOAD_HINTS。 */
  public static final String NAME = "simos.gm.packets";

  private static final ResourceManifest GA_READ =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final ToolReadPort reads;

  public GmPacketsTool(ToolReadPort reads) {
    this.reads = Objects.requireNonNull(reads, "reads");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 查看决策包摘要列表（只读）：参数 {tick?, status?, proposerId?, tool?, branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省 head)}。返回 {branch, revision, count, packets:[{id, tick, proposerId, "
        + "status, packageState, callCount, toolNames}]}。"
        + "★ **PENDING = 该决策人当前的提交**；同一 tick 内它可以提交**很多次**，GM 应**以最后一次为准**"
        + "（读到的就是最后一次的内容）；已裁决（decidedBy 非空或终态 APPROVED/REJECTED/MERGED/PARTIALLY_APPROVED）"
        + "之后不能再改。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSchema.targetProps();
    props.put("tick", ToolSchema.prop("integer", "只看该 tick"));
    props.put(
        "status",
        ToolSchema.prop(
            "string", "只看该状态（DRAFT|PENDING|APPROVED|REJECTED|MERGED|PARTIALLY_APPROVED）"));
    props.put("proposerId", ToolSchema.prop("string", "只看该决策人提交的包"));
    props.put("tool", ToolSchema.prop("string", "只看含该工具名 call 的包"));
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return GA_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(
          context, Operation.READ, List.of(ResourceId.of(ToolResources.GA_NAMESPACE, "*")));
      Map<String, Object> args = context.arguments();
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      Long tick = ToolSchema.optionalLong(args, "tick");
      String status = ToolSchema.optionalText(args, "status", null);
      String proposerId = ToolSchema.optionalText(args, "proposerId", null);
      String tool = ToolSchema.optionalText(args, "tool", null);
      List<Map<String, Object>> packets = new ArrayList<>();
      for (DecisionRecord record : ga.decisionPackets().values()) {
        if (tick != null && record.tick() != tick) {
          continue;
        }
        if (status != null && !status.equals(record.view().status().name())) {
          continue;
        }
        if (proposerId != null && !proposerId.equals(record.proposerId().value())) {
          continue;
        }
        if (tool != null && !containsTool(record, tool)) {
          continue;
        }
        packets.add(summary(record));
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("branch", state.meta().ref().branch().value());
      view.put("revision", state.meta().ref().revision().value());
      view.put("count", packets.size());
      view.put("packets", packets);
      return ToolResults.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "读取决策包列表失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  private static boolean containsTool(DecisionRecord record, String tool) {
    return record.packageState().pkg().items().stream()
        .anyMatch(item -> tool.equals(item.toolName()));
  }

  private static Map<String, Object> summary(DecisionRecord record) {
    DecisionPacket view = record.view();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", view.id().value());
    row.put("tick", view.tick());
    row.put("proposerId", view.proposerId().value());
    row.put("status", view.status().name());
    row.put("packageState", record.packageState().pkg().state().name());
    row.put("callCount", record.packageState().pkg().items().size());
    List<String> toolNames =
        record.packageState().pkg().items().stream().map(item -> item.toolName()).toList();
    row.put("toolNames", toolNames);
    return row;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}

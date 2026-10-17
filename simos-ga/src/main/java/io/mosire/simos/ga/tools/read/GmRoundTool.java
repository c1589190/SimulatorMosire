package io.mosire.simos.ga.tools.read;

import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.model.DecisionMaker;
import io.mosire.simos.ga.model.DecisionPacket;
import io.mosire.simos.ga.model.PacketStatus;
import io.mosire.simos.ga.spi.GmOnlyRead;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.ga.tools.write.AdjudicationAckTool;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code simos.gm.round}（只读，GM 桶，2026-10-14 GM⇄DM 设计书 §3.5）：<b>GM 的轮次看板</b>——一眼看清"谁还没确认决策 /
 * 谁有未答问题 / 谁还没确认推演"。
 *
 * <pre>{@code
 * {"tick":"可选（缺省 = 世界当前 tick）","branch":"可选","expectedRevision":"可选"}
 * }</pre>
 *
 * <p>★★ <b>纯派生读口，零新状态</b>——三件事全部从既有状态现算，不新开任何存储：
 *
 * <table border="1">
 *   <caption>逐 DM 的一行怎么来</caption>
 *   <tr><th>字段</th><th>来源</th></tr>
 *   <tr><td>{@code packetStatus} / {@code callCount}</td><td>{@code GaState.decisionPackets()} 的 {@code pkt-<dm>-<tick>}（没有包 ⇒ {@code null} / {@code 0}）</td></tr>
 *   <tr><td>{@code openQuestions}</td><td>该 DM 发给 GM 且**还没有回信**（没有任何信件的 {@code replyTo} 指向它）的信件条数（{@link GmInboxTool#letters} / {@link GmInboxTool#repliedIds}——不新写一份判据）</td></tr>
 *   <tr><td>{@code adjudicationAcked}</td><td>{@code ga:adjudicationAck.<tick>.<dm>} 下有没有 {@code key == "ack"} 的条目（{@link AdjudicationAckTool#isAcked}）</td></tr>
 * </table>
 *
 * <p>★ <b>遍历对象 = 存在过的决策人</b>（{@code GaState.decisionMakers()}），**不是**历史包：只看包会把"这一 tick 没有包"的人整个漏掉
 * —— 而"谁还没交"恰恰是本看板的第一个问题。★ 输出按决策人 id 升序（稳定、可 diff）。★ GM 自己（{@code "gm"}）**不在**决策人表里 ⇒
 * 不会出现在任何一行（它不是决策人）。
 *
 * <p>★★ <b>纪律（不是系统拦截）</b>：{@link #description()} 里写明——三个 {@code pending*} 清单有任何一个非空时**不要推演**。 这是设计书
 * §9-D 的裁定（用户："不做强制机制"）：GM 是自主行动方，工具只**报告**谁还没确认，不替它踩刹车。
 *
 * <p>★ <b>{@code openQuestions} 不按 tick 过滤</b>（"三个 tick 前问的那件事还没答"仍然算未答）——它是"欠着几件事"，不是"这一页有几件事"。
 *
 * <p>★ <b>资源声明与断言照同族 GM 只读口</b>（{@code simos.gm.packets}）：声明 {@code sd} 只读 + 断言 {@code
 * sd:*}；**不声明** {@code peer}（GM 组的可达面没有 {@code peer} 那一维，理由见 {@link ToolResources#PEER_NAMESPACE} 第
 * 3 条）。
 *
 * <p>★ 日志：**空候选只记 DEBUG、不逐次记**（照 {@code GmApprovalsTool} 的同款口径）。
 */
public final class GmRoundTool implements SimosTool, GmOnlyRead {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gm.round";

  private static final ResourceManifest GA_READ =
      ResourceManifest.of(ToolResources.GA_NAMESPACE, ResourcePolicy.READ_ONLY);

  /** 工具面事件的发射通道（分类 = {@link GaLog#tool()}，来源 = {@link GaLogSource#GA_TOOL_CALL}）。 */
  private static final LogChannel TOOL = EventLog.channel(GaLog.tool());

  private final ToolReadPort reads;

  public GmRoundTool(ToolReadPort reads) {
    this.reads = Objects.requireNonNull(reads, "reads");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 的**轮次看板**（只读）：一次看清每个决策人这一 tick 的状态。载荷 {tick?（缺省 = 世界当前 tick）, branch?, expectedRevision?}。"
        + "返回 {branch, revision, tick, decisionMakers:[{id, packetStatus, callCount, openQuestions, adjudicationAcked}], "
        + "pendingConfirmations, pendingQuestions, pendingAcks}。"
        + "packetStatus = 该 tick 的决策包状态（DRAFT|PENDING|…；**没有包 ⇒ null**）；callCount = 包里的命令条数（没有包 ⇒ 0）；"
        + "openQuestions = 该决策人发给 GM 且**还没被回信**的信件条数（不按 tick 过滤）；adjudicationAcked = 该 tick 的推演确认到了没有。"
        + "pendingConfirmations = 决策包还没到 PENDING 的决策人 id；pendingQuestions = 有未答问题的决策人 id；pendingAcks = 这个 tick 还没确认推演的决策人 id。"
        + "★ **PENDING = 该决策人当前的提交**；同一 tick 内它可以提交**很多次**（GM 应**以最后一次为准**，"
        + "且 DM 改包时包会自动退回 DRAFT ⇒ 该 DM 会**重新出现**在这个清单里）；"
        + "已裁决（decidedBy 非空或终态 APPROVED/REJECTED/MERGED/PARTIALLY_APPROVED）之后不能再改。"
        + "★★ **纪律：这三个清单有任何一个非空时，不要推演（simos.advance / 任何世界写）** —— 等他们确认/答完再推。"
        + "这是**纪律，不是系统拦截**（工具只报告谁还没确认，不替 GM 踩刹车）；纯粹只读，零世界效果。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("tick", ToolSchema.prop("integer", "看哪个 tick（缺省 = 世界当前 tick；不得记在未来）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put("expectedRevision", ToolSchema.prop("integer", "读取的版本（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of());
  }

  /** ★ 声明 {@code sd} 只读（与 {@code simos.gm.packets} 同款；**不声明** {@code peer}，理由见类注）。 */
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
      long worldTick = state.meta().timestamp().tick();
      Long tickArg = ToolSchema.optionalLong(args, "tick");
      long tick = tickArg == null ? worldTick : tickArg;
      if (tick < 0L) {
        return ToolResult.error("BAD_REQUEST", "tick 不得为负: " + tick);
      }
      if (tick > worldTick) {
        // ★ 未来 tick 上"人人都没确认"是**没有信息量的假读数**（那个 tick 还没开始）⇒ 具名拒，不静默给一张会误导的板。
        return ToolResult.error(
            "BAD_REQUEST", "tick 不得记在未来（世界当前 tick = " + worldTick + "）: " + tick);
      }

      Set<String> replied = GmInboxTool.repliedIds(ga);
      List<DecisionMaker> decisionMakers = new ArrayList<>(ga.decisionMakers().values());
      decisionMakers.sort(Comparator.comparing(maker -> maker.id().value()));
      List<Map<String, Object>> rows = new ArrayList<>(decisionMakers.size());
      List<String> pendingConfirmations = new ArrayList<>();
      List<String> pendingQuestions = new ArrayList<>();
      List<String> pendingAcks = new ArrayList<>();
      for (DecisionMaker maker : decisionMakers) {
        rows.add(
            row(ga, maker, tick, replied, pendingConfirmations, pendingQuestions, pendingAcks));
      }
      if (rows.isEmpty()) {
        // ★ 读口只在空候选时记 DEBUG，不逐次记。
        TOOL.debug(
            LogEvent.of(
                "GM_ROUND_EMPTY",
                GaLogSource.GA_TOOL_CALL,
                "tool",
                NAME,
                "tick",
                tick,
                "reason",
                "no-decision-makers"));
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("branch", state.meta().ref().branch().value());
      view.put("revision", state.meta().ref().revision().value());
      view.put("tick", tick);
      view.put("decisionMakers", rows);
      view.put("pendingConfirmations", pendingConfirmations);
      view.put("pendingQuestions", pendingQuestions);
      view.put("pendingAcks", pendingAcks);
      return ToolResults.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "读取轮次状态失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 逐 DM 一行（同时把三个 pending 清单按同一批判据填好——三处判据只在这里写一遍）。 */
  private static Map<String, Object> row(
      GaState ga,
      DecisionMaker maker,
      long tick,
      Set<String> replied,
      List<String> pendingConfirmations,
      List<String> pendingQuestions,
      List<String> pendingAcks) {
    String id = maker.id().value();
    DecisionPacket packet = ga.decisionPackets().get(DecisionPacketId.parse(packetIdOf(id, tick)));
    boolean confirmed = packet != null && packet.status() == PacketStatus.PENDING;
    int openQuestions = openQuestionsOf(ga, replied, maker.id());
    boolean acked = AdjudicationAckTool.isAcked(ga, tick, id);
    if (!confirmed) {
      pendingConfirmations.add(id);
    }
    if (openQuestions > 0) {
      pendingQuestions.add(id);
    }
    if (!acked) {
      pendingAcks.add(id);
    }
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", id);
    row.put("packetStatus", packet == null ? null : packet.status().name());
    row.put("callCount", packet == null ? 0 : packet.calls().size());
    row.put("openQuestions", openQuestions);
    row.put("adjudicationAcked", acked);
    return row;
  }

  /**
   * 决策包 id 的形状（{@code pkt-<dm>-<tick>}）——与 {@code ConfirmPacketTool} / {@code
   * DecisionTurnFinalizer} 同源口径。
   */
  private static String packetIdOf(String decisionMakerId, long tick) {
    return "pkt-" + decisionMakerId + "-" + tick;
  }

  /** 该 DM 发给 GM 且**还没有回信**的信件条数（不按 tick 过滤，见类注）。 */
  private static int openQuestionsOf(GaState ga, Set<String> replied, DecisionMakerId maker) {
    int open = 0;
    for (GmInboxTool.Letter letter : GmInboxTool.letters(ga)) {
      if (!maker.value().equals(letter.from())) {
        continue;
      }
      if (!ToolResources.GM_ID.equals(letter.to())) {
        continue;
      }
      if (!replied.contains(letter.id())) {
        open++;
      }
    }
    return open;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点：**GM 面**（决策人自己看包用 {@code simos.ga.packet.my}）。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}

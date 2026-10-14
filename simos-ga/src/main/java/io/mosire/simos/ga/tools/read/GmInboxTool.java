package io.mosire.simos.ga.tools.read;

import com.fasterxml.jackson.databind.JsonNode;
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
import io.mosire.simos.ga.model.SdInfoEntry;
import io.mosire.simos.ga.spi.GmOnlyRead;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.ga.tools.write.GaMessageSendTool;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.gm.inbox}（只读，GM 桶，2026-10-14 GM⇄DM 设计书 §3.1 / 缺口 G2）：<b>GM 读决策人寄来的信</b>。
 *
 * <pre>{@code
 * {"sinceTick":"可选","limit":"可选（缺省 20，上限 200）","branch":"可选","expectedRevision":"可选"}
 * }</pre>
 *
 * <p>★★ <b>判据（三道，全部 fail-closed）</b>：在 {@code GaState.info} 覆盖层里取 ①{@code key == } {@link
 * GaMessageSendTool#KEY_MESSAGE}、②值能解析成对象、③值的 {@code to == "}{@link ToolResources#GM_ID}{@code "} ⇒
 * **只返回寄给 GM 的信**（决策人之间的信不会出现在这里；GM 自己发出去的信 {@code to != gm} 也不在这里—— 要看自己发的，是另一件事）。
 *
 * <p>★★ <b>它直接读全量，不引入任何可见性谓词</b>：GM 读全部 INFO 是**既有的另一件事**（{@code
 * RedactingQueryService.infoVisibleToDecisionMaker} 的类注原文：「**GM 不走本方法**：解不出决策人身份时读全部」）。 ★
 * 因此本工具**不得**复用/复制 {@code RedactingQueryService} 的归属判据（{@code DecisionResultsVisibilityGuardTest}
 * 用源码扫描钉着"归属判据全仓 src/main 恰一份"）——这里筛的是**收件人**（地址事实），不是"谁有权看"。
 *
 * <p>★ <b>只给 GM 桶</b>（{@link GmOnlyRead}）：决策人读自己的收件箱走 {@code simos.ga.message.inbox}（它按可见性三轴筛， 且只回
 * {@code to == 自己} 的信）——两条读口各读各的那一半，**没有**共享的"全量读"捷径。
 *
 * <p>★ <b>{@code answered} 是纯派生</b>（零新状态）：存在另一条信件的 {@code replyTo} == 本条 {@code id} ⇒ 已答。 ★
 * 它认的是"**有人回了**"，不认回信人是谁：DM 侧的发信工具**载荷里没有 {@code replyTo} 键**（只有 GM 侧的 {@code
 * simos.gm.message.send} 有）⇒ 今天只有 GM 的回信能把一条信标成已答。
 *
 * <p>★ <b>{@code unanswered} 是全局计数</b>：**全部**寄给 GM 的信里未答的条数，**不受 {@code sinceTick}/{@code limit}
 * 影响**（GM 要的是"还有几件事没答"，不是"这一页里有几件"）。★ 用 {@code sinceTick} 分页时，这个数**不会**跟着变小—— 它就是这么设计的（要按 tick
 * 切分的视图走 {@code simos.gm.round} 的 {@code openQuestions}）。
 *
 * <p>★ <b>{@code limit} 截取的是**最近**的 N 条</b>（返回列表内部仍按 {@code (tick, sequence, id)} 升序，见 {@link
 * #letters}）：GM 的用途是"答还没答的问题"，截取最旧的一页会让新问题永远看不见——而"GM 的答复必须到达 DM"是这批的红线之一。
 * 超上限**具名拒**（不静默截断；静默截断会让调用方以为拿到了全部）。
 *
 * <p>★★ <b>条数上限：每 tick 每方向最多 {@value GaMessageSendTool#MAX_MESSAGES_PER_TICK_PER_DIRECTION} 条</b>
 * （用户 2026-10-14 裁定「至少改成128」；**命令期**判，第 N+1 条具名拒 + 世界零变化）。★ 本工具 <b>不做截断</b>：{@code limit}
 * 只管"这一次返回几条"，不改变"信里写了多少"——正文一律原样给出 （截断 = 静默丢掉 GM 的答复 = 问答断链）。
 *
 * <p>★ <b>资源声明与断言照同族 GM 只读口</b>（{@code simos.gm.packets}）：声明 {@code sd} 只读 + 断言 {@code sd:*}（GM 的
 * {@code sd} 是 unlimited）。 ★ 为什么**不**声明 {@code peer}：GM 组的可达面**没有** {@code peer} 那一维表态 ⇒ 声明它并断言
 * {@code peer:*} 会在 {@code ResourceAuthorizer.denial()} 的执行前闸上被整调拒掉（{@link
 * ToolResources#PEER_NAMESPACE} javadoc 第 3 条记的坑）；而本工具读的是"寄给 GM 的信"这条**地址事实**，不是"我能碰哪些 peer 资源"。
 *
 * <p>★ 日志：**空候选只记 DEBUG、不逐次记**（照 {@code GmApprovalsTool} 的同款口径）。
 */
public final class GmInboxTool implements SimosTool, GmOnlyRead {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.gm.inbox";

  /** {@code limit} 缺省。 */
  public static final int DEFAULT_LIMIT = 20;

  /** {@code limit} 硬上限：超限**具名拒**（静默截断会让调用方以为拿到了全部）。 */
  public static final int MAX_LIMIT = 200;

  private static final ResourceManifest SD_READ =
      ResourceManifest.of(ToolResources.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  /** 工具面事件的发射通道（分类 = {@link GaLog#tool()}，来源 = {@link GaLogSource#GA_TOOL_CALL}）。 */
  private static final LogChannel TOOL = EventLog.channel(GaLog.tool());

  private final ToolReadPort reads;

  public GmInboxTool(ToolReadPort reads) {
    this.reads = Objects.requireNonNull(reads, "reads");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "读**决策人寄给 GM 的信**（只读，GM 专用）：载荷 {sinceTick?, limit?, branch?, expectedRevision?}。"
        + "sinceTick = 只看该 tick 及以后的（闭区间下界）；limit 缺省 "
        + DEFAULT_LIMIT
        + "、上限 "
        + MAX_LIMIT
        + "（超出 ⇒ 具名拒；截取的是**最近**的 N 条）。"
        + "返回 {branch, revision, count, messages:[{id, from, to, tick, subject?, body, replyTo?, answered}], "
        + "unanswered}。"
        + "answered=true 表示你已经回过这一条（存在另一条信件的 replyTo == 它的 id）；unanswered = 全部寄给 GM 的信里"
        + "**还没答**的条数（不受 sinceTick/limit 影响）。"
        + "★ 决策人之间的信不会出现在这里（本工具只回 to=\""
        + ToolResources.GM_ID
        + "\" 的信）。回信请用 simos.gm.message.send{to, body, replyTo}。"
        + "★ 条数上限：每 tick 每方向最多 "
        + GaMessageSendTool.MAX_MESSAGES_PER_TICK_PER_DIRECTION
        + " 条（命令期判，超出的第 N+1 条具名拒、世界零变化）；正文不截断，limit 只影响这一次返回几条。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("sinceTick", ToolSchema.prop("integer", "只看该 tick 及以后的信（闭区间下界，可选）"));
    props.put(
        "limit",
        ToolSchema.prop(
            "integer", "最多返回多少条（缺省 " + DEFAULT_LIMIT + "，上限 " + MAX_LIMIT + "，超限拒；取最近的 N 条）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put("expectedRevision", ToolSchema.prop("integer", "读取的版本（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of());
  }

  /** ★ 声明 {@code sd} 只读（与 {@code simos.gm.packets} 同款；**不声明** {@code peer}，理由见类注）。 */
  @Override
  public ResourceManifest resources() {
    return SD_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      ToolResources.requireAll(
          context, Operation.READ, List.of(ResourceId.of(ToolResources.SD_NAMESPACE, "*")));
      Map<String, Object> args = context.arguments();
      Long sinceTick = ToolSchema.optionalLong(args, "sinceTick");
      if (sinceTick != null && sinceTick < 0L) {
        return ToolResult.error("BAD_REQUEST", "sinceTick 必须 ≥ 0: " + sinceTick);
      }
      Long limitArg = ToolSchema.optionalLong(args, "limit");
      int limit;
      if (limitArg == null) {
        limit = DEFAULT_LIMIT;
      } else if (limitArg > MAX_LIMIT) {
        return ToolResult.error("BAD_REQUEST", "limit 上限为 " + MAX_LIMIT + ": " + limitArg);
      } else if (limitArg < 1L) {
        return ToolResult.error("BAD_REQUEST", "limit 必须 ≥ 1: " + limitArg);
      } else {
        limit = limitArg.intValue();
      }

      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      GaState ga = GaToolReads.ga(state);
      // ★ answered 的判据是**全局**的（任何一条信件的 replyTo 指向它）⇒ 先把"被回复过的 id"整轮算一次。
      Set<String> replied = repliedIds(ga);
      List<Letter> matched = new ArrayList<>();
      int unanswered = 0;
      for (Letter letter : letters(ga)) {
        if (!ToolResources.GM_ID.equals(letter.value().path("to").asText(""))) {
          continue;
        }
        boolean answered = replied.contains(letter.id());
        if (!answered) {
          // ★ 全局计数（不受 sinceTick/limit 影响）——GM 要的是"还有几件事没答"。
          unanswered++;
        }
        if (sinceTick != null && letter.tick() < sinceTick) {
          continue;
        }
        matched.add(letter);
      }
      // ★ 截取**最近**的 limit 条（列表内部仍按 (tick,id) 升序）：截最旧的一页会让新问题永远看不见。
      List<Letter> window =
          matched.size() <= limit
              ? matched
              : new ArrayList<>(matched.subList(matched.size() - limit, matched.size()));
      List<Map<String, Object>> messages = new ArrayList<>(window.size());
      for (Letter letter : window) {
        messages.add(messageView(letter, replied.contains(letter.id())));
      }
      if (messages.isEmpty()) {
        // ★ 读口只在空候选时记 DEBUG，不逐次记。
        TOOL.debug(
            LogEvent.of(
                "GM_INBOX_EMPTY",
                GaLogSource.GA_TOOL_CALL,
                "tool",
                NAME,
                "sinceTick",
                sinceTick == null ? "-" : sinceTick,
                "toGmInWindow",
                matched.size(),
                "unanswered",
                unanswered));
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("branch", state.meta().ref().branch().value());
      view.put("revision", state.meta().ref().revision().value());
      view.put("count", messages.size());
      view.put("messages", messages);
      view.put("unanswered", unanswered);
      return ToolResults.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "读取收件箱失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 单条信的视图：只回显信里的事实 + 派生的 {@code answered}，不带 tags/affiliations 这类内部轴。 */
  private static Map<String, Object> messageView(Letter letter, boolean answered) {
    JsonNode message = letter.value();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", letter.id());
    view.put("from", message.path("from").asText(""));
    view.put("to", message.path("to").asText(""));
    view.put("tick", letter.tick());
    if (message.hasNonNull("subject")) {
      view.put("subject", message.path("subject").asText(""));
    }
    view.put("body", message.path("body").asText(""));
    if (message.hasNonNull("replyTo")) {
      view.put("replyTo", message.path("replyTo").asText(""));
    }
    view.put("answered", answered);
    return view;
  }

  // ── 读侧共享件（唯一实现：GmRoundTool 也走这里，不复制第二份）──────────────────────────────────

  /**
   * 一条**能解析出结构**的信件条目：条目 + 解析后的值 + **数值化的信序号**。{@code id} 取条目自带的 id（{@code msg-...}）。
   *
   * <p>★ 解析不出结构（不是对象 / 非 JSON 文本）的条目**不进列表**——"看不懂的信"不猜、不显示。
   *
   * <p>★★ {@code sequence} 是 **2026-10-14 批 B 修的一个真缺陷**（设计书 §3.4.1，见 {@link #letters}）： 信件 id
   * 末段是**十进制**序号，而 {@code "<tick>,<id>"} 的**字典序**会把 {@code 10} 排到 {@code 2} 前面 ⇒ 注入游标（"最后一条 id +
   * 1"）会**静默跳过**同一 tick 的第 10..N 条。故这里把序号**解析成数值**参与排序。
   */
  public record Letter(SdInfoEntry entry, JsonNode value, int sequence) {

    /** 条目 id（信件 id，回信时填进 {@code replyTo} 的就是它）。 */
    public String id() {
      return entry.id().value();
    }

    /** 信件所属 tick（条目 tick；{@code ga.PutInfo} 的 tick 字段）。 */
    public long tick() {
      return entry.tick();
    }

    /** 发件人（{@code value.from}；缺/空白 ⇒ 空串）。 */
    public String from() {
      return value.path("from").asText("");
    }

    /** 收件人（{@code value.to}；缺/空白 ⇒ 空串）。 */
    public String to() {
      return value.path("to").asText("");
    }
  }

  /**
   * id 末段解析不出十进制序号时的**哨兵值**（★ 明确的约定，不是 {@code 0}）：这些条目在**同一 tick 内排在最前**， 彼此之间退化成按 id 字典序（稳定、不猜）。★
   * 与合法序号（{@code 0..}{@value GaMessageSendTool#MAX_MESSAGES_PER_TICK_PER_DIRECTION}{@code
   * -1}）**永不相等** ⇒ 排序可全序，不会撞。
   */
  static final int SEQUENCE_SENTINEL = Integer.MIN_VALUE;

  /**
   * 从信件 id 末段取**数值**序号（{@code msg-<from>-<to>-<tick>-<n>} 的 {@code <n>}）；解析不出 ⇒ {@link
   * #SEQUENCE_SENTINEL}。
   *
   * <p>★ 拼写点只此一处：只认**最后一段**、只认整段都是十进制数字（{@code +1} / {@code 01} / {@code 1a} 都不算——
   * 前两个不是发信工具的产物，第三个根本不是序号）。
   */
  static int sequenceOf(String id) {
    if (id == null) {
      return SEQUENCE_SENTINEL;
    }
    int dash = id.lastIndexOf('-');
    if (dash < 0 || dash == id.length() - 1) {
      return SEQUENCE_SENTINEL;
    }
    String tail = id.substring(dash + 1);
    for (int i = 0; i < tail.length(); i++) {
      if (tail.charAt(i) < '0' || tail.charAt(i) > '9') {
        return SEQUENCE_SENTINEL;
      }
    }
    try {
      return Integer.parseInt(tail);
    } catch (NumberFormatException e) {
      return SEQUENCE_SENTINEL; // 位数超 int：明确约定成哨兵，不抛、不截断
    }
  }

  /**
   * **全部**信件条目（{@code key == }{@link GaMessageSendTool#KEY_MESSAGE} 且值能解析成对象），按 {@code (tick,
   * sequence, id)} 升序。
   *
   * <p>★★ <b>为什么不是 {@code (tick, id)}</b>（2026-10-14 批 B 修的真缺陷，设计书 §3.4.1）：信件 id 的末段是**十进制**序号，
   * 字典序在同一 tick ≥10 条时会变成 {@code 0,1,10,11,2,…,9}。后果不是"看起来乱"—— {@code
   * DecisionAgentRunner.newLettersMessage} 的注入游标是"最后一条信的 id + 1（下标）"， 而**新到的第 12 条（{@code
   * -12}）排在游标前面** ⇒ {@code start = index(cursor)+1} **跳过它** ⇒ **GM 的答复被静默丢掉**（正是本设计最不能出的错）；{@code
   * simos.gm.inbox} 的"截最近 N 条"也会截错。 ⇒ 排序改成先比**数值**序号、再拿 id 兜底（同序号不可能来自同一发信工具，但不同来源的条目可能撞 ⇒
   * 全序必须有最后一键）。
   *
   * <p>★ 解析不出序号的条目取 {@link #SEQUENCE_SENTINEL}（同一 tick 内排最前，保持稳定序）——**不丢、不猜**。
   *
   * <p>★ <b>不按收件人/可见性筛</b>：那是调用方的事（GM 读全量是本工具的口径；DM 侧的收件箱走它自己的可见性端口）。 ★ {@code repliedIds}
   * 的既有语义**不受本次改动影响**（它只收集 {@code replyTo} 的集合，与顺序无关）。
   */
  public static List<Letter> letters(GaState ga) {
    List<Letter> all = new ArrayList<>();
    for (List<SdInfoEntry> entries : ga.info().values()) {
      for (SdInfoEntry entry : entries) {
        if (!GaMessageSendTool.KEY_MESSAGE.equals(entry.key())) {
          continue;
        }
        Optional<JsonNode> value = GaMessageSendTool.parseMessageValue(entry.value());
        value.ifPresent(node -> all.add(new Letter(entry, node, sequenceOf(entry.id().value()))));
      }
    }
    all.sort(
        Comparator.comparingLong(Letter::tick)
            .thenComparingInt(Letter::sequence)
            .thenComparing(Letter::id));
    return all;
  }

  /**
   * **被回复过的信件 id 集合**（{@code answered} 判据的唯一实现）：扫全部信件，收集它们的 {@code value.replyTo}。
   *
   * <p>★ 纯派生、零新状态；{@code replyTo} 缺 / 非文本 / 空白 ⇒ 不参与（不猜）。★ 只认"有人回了"，不认回信人是谁 ——DM 侧发信载荷里没有 {@code
   * replyTo} 键（只有 GM 侧的 {@code simos.gm.message.send} 有）。
   */
  public static Set<String> repliedIds(GaState ga) {
    Set<String> replied = new LinkedHashSet<>();
    for (Letter letter : letters(ga)) {
      JsonNode replyTo = letter.value().get("replyTo");
      if (replyTo == null || !replyTo.isTextual()) {
        continue;
      }
      String target = replyTo.asText();
      if (!target.isBlank()) {
        replied.add(target);
      }
    }
    return replied;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点：**GM 面**（决策人读自己的收件箱走 {@code simos.ga.message.inbox}）。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}

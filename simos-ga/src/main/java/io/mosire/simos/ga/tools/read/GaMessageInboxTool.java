package io.mosire.simos.ga.tools.read;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.ga.model.DecisionInfoEntry;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.tools.GaToolIdentity;
import io.mosire.simos.ga.tools.GaToolQueries;
import io.mosire.simos.ga.tools.GaToolReads;
import io.mosire.simos.ga.tools.write.GaMessageSendTool;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.ga.message.inbox}（DM 面，2026-10-14 决策人互致消息，设计书 §4.3）：读**寄给自己的**消息。
 *
 * <pre>{@code
 * {"sinceTick":"可选","limit":"可选（缺省 50，上限 200）","branch":"可选","expectedRevision":"可选"}
 * }</pre>
 *
 * <p>★ <b>判据（三道，全部 fail-closed）</b>：在 {@code GaState.info} 覆盖层里取 ①{@code key == "message"}、
 * ②<b>这条我能不能看见</b> ⇒ 交给既有的可见性端口 {@link GaToolQueries#infoVisibleToDecisionMaker}（三轴并集 = {@code
 * tags} / 归属 / 发送人 = 自己；前两轴的唯一实现在 app 的 {@code RedactingQueryService.visible}——本工具<b>不自己写</b>
 * 这条判据）、③消息体的 {@code to} == 自己 ⇒ **只返回寄给自己的信**，不含自己发出去的（②里"发送人 = 自己"那一支会让 自己的信同样"可见" ⇒
 * 靠③按**收件人**把它筛掉；那要看自己发的，是另一件事，本批不做）。
 *
 * <p>★ <b>它只读自己的信 ⇒ 不需要 peer 授权</b>：资源断言取自指域 {@code peer:<自己>}（基础可达面， 见 {@code
 * DecisionCallerFactory}）——零授权的决策人也能用本工具（拿到空列表，不是拒），这正是"基础可达面里确实有 {@code peer:<自己>}"的判据。
 *
 * <p>★ <b>不泄漏名册</b>（I4）：返回里只有**信里出现过的**对方 id（那是收发双方自己写入的事实）， 从不列出"还有哪些决策人存在"。★ 排序 = tick
 * **升序**（对话按时间读），同 tick 按条目 id 升序； {@code limit} 超上限**具名拒**（不静默截断）。
 *
 * <p>★ <b>旧档不解释</b>（I10）：只认 {@code key="message"} 与 {@code ga:message.*} 地址；旧 {@code
 * sd:doc.report-*} 条目**不显示、不迁移**。
 */
public final class GaMessageInboxTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.ga.message.inbox";

  /** {@code limit} 缺省。 */
  public static final int DEFAULT_LIMIT = 50;

  /** {@code limit} 硬上限：超限**具名拒**（静默截断会让调用方以为拿到了全部）。 */
  public static final int MAX_LIMIT = 200;

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final GaToolQueries queries;
  private final ToolReadPort reads;
  private final GaToolIdentity identity;

  public GaMessageInboxTool(GaToolQueries queries, ToolReadPort reads, GaToolIdentity identity) {
    this.queries = Objects.requireNonNull(queries, "queries");
    this.reads = Objects.requireNonNull(reads, "reads");
    this.identity = Objects.requireNonNull(identity, "identity");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "读**寄给自己的**消息（被 GM 授权与你互通的决策人发来的；每 tick 每方向最多 "
        + GaMessageSendTool.MAX_MESSAGES_PER_TICK_PER_DIRECTION
        + " 条）。"
        + "载荷 {sinceTick?, limit?}：sinceTick = 只看该 tick 及以后的（闭区间下界）；limit 缺省 "
        + DEFAULT_LIMIT
        + "、上限 "
        + MAX_LIMIT
        + "。返回 {messages:[{tick, from, subject?, body}], count}，按 tick 升序。"
        + "★ 本工具不提供决策人名册（只出现给你发过信的那些 id）。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("sinceTick", ToolSchema.prop("integer", "只看该 tick 及以后的消息（闭区间下界，可选）"));
    props.put(
        "limit",
        ToolSchema.prop("integer", "最多返回多少条（缺省 " + DEFAULT_LIMIT + "，上限 " + MAX_LIMIT + "，超限拒）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put("expectedRevision", ToolSchema.prop("integer", "读取的版本（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of());
  }

  /** 只读、不敏感：读自己的信不需要审批（也没有可被"改坏"的东西）。 */
  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, false, false);
  }

  /** ★ 声明 {@code peer} 域（DENY 策略）：没表态就不许——自指域由基础可达面给，故"自己的信永远读得到"。 */
  @Override
  public ResourceManifest resources() {
    return ToolResources.PEER_WRITE;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      Optional<String> selfRaw = identity.decisionMakerIdOf(context.identity());
      if (selfRaw.isEmpty()) {
        return ToolResult.error("FORBIDDEN", NAME + " 只能由决策人身份调用（身份里没有 decision-maker: 前缀）");
      }
      DecisionMakerId self = DecisionMakerId.parse(selfRaw.get());

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

      // ★ 资源断言（自指域）：零授权的决策人也有 peer:<自己> ⇒ 本工具可用（P7b）。
      ToolResources.requireAll(
          context, Operation.READ, List.of(ToolResources.resourcePeer(self.value())));

      SimulationState state = GaToolReads.state(reads, args);
      GaState ga = GaToolReads.ga(state);
      // ★ 归属轴：受众标签集由端口现拼（ga 侧不认识任何归属种类），整轮只算一次。
      Set<String> affiliationLabels = queries.affiliationLabelsOf(self, state);
      List<MatchedMessage> matched = new ArrayList<>();
      for (Map.Entry<String, List<DecisionInfoEntry>> at : ga.info().entrySet()) {
        for (DecisionInfoEntry entry : at.getValue()) {
          if (!GaMessageSendTool.KEY_MESSAGE.equals(entry.key())) {
            continue;
          }
          JsonNode message = parseMessage(entry.value());
          if (message == null) {
            continue;
          }
          // ★ I5：可见性走**唯一实现**（tags / 归属 / 发送人 = 自己 三轴的并集，落点在 app 的
          //   RedactingQueryService.visible）——本工具**不得**自己再筛一道 tags/归属：
          //   DecisionResultsVisibilityGuardTest 用源码扫描钉着"归属判据全仓 src/main 恰一份"。
          if (!queries.infoVisibleToDecisionMaker(
              entry, self, affiliationLabels, senderOf(message))) {
            continue;
          }
          // ★ 上面那一支**含**"发送人 = 自己"⇒ 自己发出去的信在它眼里同样"可见"；本工具只返回**寄给自己的**
          //   ⇒ 这里按**收件人**再筛一道（这是地址判据，不是可见性判据的第二份）。
          if (!self.value().equals(message.path("to").asText(""))) {
            continue;
          }
          if (sinceTick != null && entry.tick() < sinceTick) {
            continue;
          }
          matched.add(new MatchedMessage(entry, message));
        }
      }
      matched.sort(
          Comparator.comparingLong((MatchedMessage row) -> row.entry().tick())
              .thenComparing(row -> row.entry().id().value()));
      List<Map<String, Object>> messages = new ArrayList<>();
      for (MatchedMessage row : matched) {
        if (messages.size() >= limit) {
          break;
        }
        messages.add(messageView(row));
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("messages", messages);
      view.put("count", messages.size());
      if (messages.isEmpty()) {
        view.put("note", "没有寄给你的消息（要么还没被授权互通，要么对方这 tick 没发）");
      }
      return ToolResults.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "读取消息失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 单条消息视图：**只回显信里的事实**（tick/from/subject/body），不带 tags/affiliations 这类内部轴。 */
  private static Map<String, Object> messageView(MatchedMessage row) {
    JsonNode message = row.message();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("tick", row.entry().tick());
    view.put("from", message.path("from").asText(""));
    if (message.hasNonNull("subject")) {
      view.put("subject", message.path("subject").asText(""));
    }
    view.put("body", message.path("body").asText(""));
    return view;
  }

  /**
   * {@code value} 按约定是 JSON 文本（{@code ga.PutInfo} 的载荷契约）⇒ 解析成对象；不是文本 / 解析失败 / 不是对象 ⇒ {@code
   * null}（该条**不**进结果——"看不懂的信"不猜）。
   */
  private static JsonNode parseMessage(Object value) {
    if (value instanceof JsonNode node) {
      return node.isObject() ? node : null;
    }
    if (!(value instanceof String text)) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(text);
      return node != null && node.isObject() ? node : null;
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  /**
   * 条目的 {@code value.from}（发件人）：缺 / 非文本 / 空白 ⇒ {@code null}——端口把 {@code null} 当"发送人这一轴不参与"
   * （不猜、不回落成空串）。
   */
  private static String senderOf(JsonNode message) {
    JsonNode from = message.get("from");
    if (from == null || !from.isTextual()) {
      return null;
    }
    String text = from.asText();
    return text.isBlank() ? null : text;
  }

  /** 命中的一条：条目与解析后的结构化值成对保留。 */
  private record MatchedMessage(DecisionInfoEntry entry, JsonNode message) {}

  /** ★ 「这个工具能被谁用」的唯一拼写点：**决策人面**（GM 不是决策人，它读 info 全量、不需要这个收件箱）。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.DM);
  }
}

package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.Operation;
import io.mosire.agentlib.permission.ResourceDeniedException;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.GaState;
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
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.sd.report}（D4，2026-10-22）：<b>决策人向 GOV / NATION / GM 跨区上报</b>。
 *
 * <p>★ <b>载荷</b>：{@code {to:{kind:GOV|NATION|GM, id?}, subject, body, evidence?, tick?}}。发送人身份从
 * {@link ToolContext#identity()} 派生（{@code decision-maker:<自己>}），<b>不由载荷自报</b>；{@code tick} 缺省 =
 * 当前世界 tick，不得记未来。
 *
 * <p>★ <b>落点</b>：一条 {@code ga.PutInfo} 命令，地址 {@code sd:doc.report-<sender>-<tick>-<seq>}、{@code
 * key="report"}、 {@code value} 为 JSON 字符串 {@code {from,to,subject,body,evidence?,tick}}：
 *
 * <ul>
 *   <li>可见性两轴：{@code to.kind=GOV} ⇒ {@code affiliations=["gov:<unitId>"]}；{@code NATION} ⇒ {@code
 *       affiliations=["nation:<nationId>"]}；{@code GM} ⇒ 两者都空（只有 GM 的 {@code simos.sd.reports}
 *       能看到）。{@code tags} 恒空——归属匹配由 affiliations 承担；★ 2026-10-12 乙收尾：{@code affiliations}
 *       是<b>不透明受众标签串</b>（写入方自报），本工具只按"谁收"拼那两串，不解释任何归属种类；
 *   <li>一条命令 = 一条 revision；发送人只能写自己的报告（{@code requireAll} 用 {@code sd:decision-maker/<自己>}
 *       自指域，不采信载荷里的 id）。
 * </ul>
 *
 * <p>★★ <b>不因上报给决策人任何跨区原始数据读权</b>：本工具只写 ga 侧的 INFO 感知层，报告内容由发送方负责；读侧过滤见 {@code SimosSdReportsTool}。
 *
 * <p>★ <b>只在决策人桶</b>（{@code SimosToolSource.addDecisionAgentWrites}）——决策人白名单即由那个桶派生（{@code roles()}
 * 是唯一声明），不另立清单。
 */
public final class SimosSdReportTool implements SimosTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.sd.report";

  /**
   * 资源声明 = sd 域（不是某一个具体 INFO 地址——{@code sd:doc.*} 不在决策人可达面）。
   *
   * <p>★ 写操作取 {@link ResourcePolicy#UNRESTRICTED}：真正要写的是 {@code sd:doc.report-…}，它不在任何决策人的 sd 前缀里 ⇒
   * 由 {@code execute} 里那条**自指域** {@code requireAll(... sd:decision-maker/<自己>)} 承担"只能以自己
   * 名义发报"。{@code READ_ONLY} 会把决策人自己那条合法断言也判否。
   */
  private static final ResourceManifest SD_WRITE =
      ResourceManifest.of(ToolResources.SD_NAMESPACE, ResourcePolicy.UNRESTRICTED);

  private final ToolWritePort writes;
  private final ToolReadPort reads;
  private final GaToolIdentity identity;

  /**
   * @param writes 窄写口（命令 id / 发起者 / 信封由装配面的适配器装配，见 {@link ToolWritePort} 类注的适配规则）
   * @param reads 窄读口
   * @param identity 身份口（发送人从调用者身份派生，不采信载荷）
   */
  public SimosSdReportTool(ToolWritePort writes, ToolReadPort reads, GaToolIdentity identity) {
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
    return "决策人向 GOV/NATION/GM 上报（写 sd INFO 覆盖层，一条命令=一条 revision；发送人=调用者身份，不由载荷自报）："
        + "参数 {to:{kind:GOV|NATION|GM, id?}, subject(必填), body(必填), evidence?, tick?(缺省=当前 tick，不得记未来),"
        + " branch?(缺省 "
        + ToolResources.DEFAULT_BRANCH
        + "), expectedRevision?(缺省=head)}。"
        + "to.kind=GOV/NATION 时 id 必填（分别写 gov/nation 归属）；GM 无归属、只有 GM 的 simos.sd.reports 能看到。"
        + "返回 {address, from, to, subject, tick, seq, submitted, submission?}。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put(
        "to",
        ToolSchema.prop(
            "object", "{kind:GOV|NATION|GM, id?}（GOV/NATION 必须给 id；GM 的 id 只进正文、不产生归属）"));
    props.put("subject", ToolSchema.prop("string", "报告主题（必填非空白）"));
    props.put("body", ToolSchema.prop("string", "报告正文（必填非空白）"));
    props.put("evidence", ToolSchema.prop("string", "可选证据说明（非空白文本）"));
    props.put("tick", ToolSchema.prop("integer", "条目所属 tick（缺省=当前世界 tick；不得记未来）"));
    props.put("branch", ToolSchema.prop("string", "分支名（缺省 " + ToolResources.DEFAULT_BRANCH + "）"));
    props.put(
        "expectedRevision",
        ToolSchema.prop("integer", "读取/提交的乐观并发 base revision（缺省 = 该分支 head；给出必须 ≥ 0）"));
    return ToolSchema.schema(props, List.of("to", "subject", "body"));
  }

  @Override
  public ToolSpec spec() {
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    return SD_WRITE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        name(), "跨区上报 to=" + args.get("to") + " subject=" + args.get("subject"), AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      Optional<String> senderId = identity.decisionMakerIdOf(context.identity());
      if (senderId.isEmpty()) {
        return ToolResult.error(
            "FORBIDDEN", "simos.sd.report 只能由决策人身份调用（身份里没有 decision-maker: 前缀）");
      }

      DecisionMakerId sender = DecisionMakerId.parse(senderId.get());
      To to = parseTo(args);
      String subject = ToolSchema.requiredText(args, "subject");
      String body = ToolSchema.requiredText(args, "body");
      String evidence = ToolSchema.optionalText(args, "evidence", null);
      BranchId branch = GaToolReads.branch(args);
      Long expectedArg = ToolSchema.optionalLong(args, "expectedRevision");
      if (expectedArg != null && expectedArg < 0L) {
        return ToolResult.error("BAD_REQUEST", "expectedRevision 不得为负: " + expectedArg);
      }
      SimulationState state = GaToolReads.stateOfExpectedRevision(reads, args);
      long worldTick = state.meta().timestamp().tick();
      Long tickArg = ToolSchema.optionalLong(args, "tick");
      long tick = tickArg == null ? worldTick : tickArg;
      if (tick < 0L) {
        return ToolResult.error("BAD_REQUEST", "tick 不得为负: " + tick);
      }
      if (tick > worldTick) {
        return ToolResult.error(
            "BAD_REQUEST", "上报不得记在未来：载荷 tick " + tick + " > 世界 tick " + worldTick);
      }
      // ★ 自指域断言：只能以自己名义上报；不采信载荷里的任何 id。
      ToolResources.requireAll(
          context,
          Operation.WRITE,
          List.of(ToolResources.resourceSd(identity.decisionMakerKind(), sender.value())));
      GaState ga = GaToolReads.ga(state);
      int seq = nextSeq(ga, sender.value(), tick);
      Address address =
          new Address(
              List.of(
                  new Namespace(ToolResources.SD_NAMESPACE),
                  Entity.of("doc", "report-" + sender.value() + "-" + tick + "-" + seq)));
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("from", sender.value());
      report.put("to", to.payload());
      report.put("subject", subject);
      report.put("body", body);
      if (evidence != null) {
        report.put("evidence", evidence);
      }
      report.put("tick", tick);
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("address", address.canonical());
      payload.put("key", "report");
      payload.put("value", ToolResults.json(report));
      to.affiliations().ifPresent(affiliations -> payload.put("affiliations", affiliations));
      String payloadJson = ToolResults.json(payload);
      // ★ 命令 id 由窄写口的装配面生成并**回报**（见 ToolWritePort.Receipt）——视图里报的 id 与落盘信封的是同一个。
      ToolWritePort.Receipt receipt =
          writes.submitWithReceipt(
              branch,
              () -> new RevisionId(state.meta().ref().revision().value()),
              PutInfoHandler.TYPE,
              payloadJson);
      String commandId = receipt.commandId();
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("address", address.canonical());
      view.put("key", "report");
      view.put("from", sender.value());
      view.put("to", to.payload());
      view.put("subject", subject);
      view.put("tick", tick);
      view.put("seq", seq);
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
          view.put("submission", submission);
          yield ToolResult.error("CONFLICT", ToolResults.json(view));
        }
        case ToolWritePort.Outcome.Rejected rejected -> {
          Map<String, Object> submission = new LinkedHashMap<>();
          submission.put("result", "rejected");
          submission.put("reason", rejected.reason());
          view.put("submission", submission);
          yield ToolResult.error("REJECTED", ToolResults.json(view));
        }
      };
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (ResourceDeniedException e) {
      // ★ 资源拒因原样逃到 ToolCallAuthorizer 边界（与其余写工具同一条）。
      throw e;
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "上报失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * 下一个地址序号：扫描 ga INFO 覆盖层里本发送人、本 tick 的既有报告地址 {@code sd:doc.report-<sender>-<tick>-<n>}，取 max+1；
   * 没有 ⇒ 1。地址是身份的一部分 ⇒ 同一发送人同 tick 多次上报不覆盖（★ 地址族串一字未改，只是表自 3b-2 起住 ga）。
   */
  private static int nextSeq(GaState ga, String sender, long tick) {
    String prefix = "report-" + sender + "-" + tick + "-";
    int max = 0;
    for (String key : ga.info().keySet()) {
      try {
        Address address = Address.parse(key);
        if (!ToolResources.SD_NAMESPACE.equals(address.namespace())
            || address.segments().size() < 2) {
          continue;
        }
        if (!(address.segments().get(1) instanceof Entity entity)) {
          continue;
        }
        if (!"doc".equals(entity.kind().orElse("")) || !entity.name().startsWith(prefix)) {
          continue;
        }
        int seq = Integer.parseInt(entity.name().substring(prefix.length()));
        if (seq > max) {
          max = seq;
        }
      } catch (IllegalArgumentException ignored) {
        // 别的 INFO 地址/别的报告发送人：不是本工具的命名空间，不参与序号推导。
      }
    }
    return max + 1;
  }

  /** 解析 {@code to}；{@code id} 的必填性由 kind 决定。 */
  private static To parseTo(Map<String, Object> args) {
    Object raw = args.get("to");
    if (!(raw instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("参数 to 必填且为 {kind:GOV|NATION|GM, id?} 对象");
    }
    Object kindRaw = map.get("kind");
    if (!(kindRaw instanceof String kindText) || kindText.isBlank()) {
      throw new IllegalArgumentException("参数 to.kind 必填且为非空文本（GOV|NATION|GM）");
    }
    String kind = kindText.trim().toUpperCase(Locale.ROOT);
    String id = optionalString(map, "id");
    return switch (kind) {
      case "GOV" -> new To(kind, requireId(id, "GOV"), false);
      case "NATION" -> new To(kind, requireId(id, "NATION"), true);
      case "GM" -> new To(kind, id, null);
      default -> throw new IllegalArgumentException("参数 to.kind 只认 GOV | NATION | GM: " + kindText);
    };
  }

  private static String requireId(String id, String kind) {
    if (id == null) {
      throw new IllegalArgumentException("to.kind=" + kind + " 时参数 to.id 必填");
    }
    return id;
  }

  private static String optionalString(Map<?, ?> map, String key) {
    Object value = map.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("参数 to." + key + " 若给出必须是非空文本");
    }
    return text;
  }

  /**
   * 已解析的收件人。
   *
   * @param kind 规范化后的 GOV|NATION|GM
   * @param id GOV/NATION 必填的对方稳定 id；GM 可空（只进正文）
   * @param nation true=写 nation 归属；false=写 gov 归属；null=不写归属（GM）
   */
  private record To(String kind, String id, Boolean nation) {

    Map<String, Object> payload() {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("kind", kind);
      if (id != null) {
        out.put("id", id);
      }
      return out;
    }

    /**
     * ★ 受众标签（{@code SdInfoEntry.affiliations} 的**不透明串**形状）：{@code "nation:<id>"} / {@code
     * "gov:<id>"}。
     *
     * <p>★★ 2026-10-12 乙收尾：从 {@code [{kind,id}]} 改成<b>串</b>——旧形状要求 sd/ga 侧有一个"归属种类的
     * switch"（新加一个模块就得改 ga），新形状把种类交给写入方自报、解释交给 app。
     */
    Optional<List<String>> affiliations() {
      if (nation == null) {
        return Optional.empty();
      }
      // ★ 标签的拼法（kind:ref）与各模块自报的种类名同源：这里用到的两串由本工具的 to.kind 决定。
      return Optional.of(List.of((nation ? "nation" : "gov") + ":" + id));
    }
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.DM);
  }
}

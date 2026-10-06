package io.mosire.simos.app.tools.read;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.query.RedactingQueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.model.Affiliation;
import io.mosire.simos.sd.model.DecisionMaker;
import io.mosire.simos.sd.model.SdInfoEntry;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ {@code simos.sd.reports}（D4，2026-10-22）：<b>跨区上报的共享读口</b>（决策人桶 + GM 桶）。
 *
 * <p>★ <b>可见性</b>（只读 {@code key="report"} 的 sd INFO 条目）：
 *
 * <ul>
 *   <li><b>决策人</b>：条目 {@code tags} 含自己 <b>或</b> {@code affiliations} 含自己的归属 <b>或</b>报告 {@code
 *       value.from} = 自己（自己发的自己看得到）。三支都不命中 ⇒ 不可见（fail-closed）；
 *   <li><b>GM</b>（{@link DecisionCallerFactory#decisionMakerIdOf} 为空）：读全部——{@code to.kind=GM} 的报告
 *       没有归属，只有 GM 看得到。
 * </ul>
 *
 * <p>★ <b>参数</b>：{@code from?, to?, subjectContains?, fromTick?, toTick?, limit?}。{@code from} =
 * 发送人 id 精确匹配； {@code to} = 收件人 kind（GOV/NATION/GM，大小写不敏感）或 id 精确匹配；{@code fromTick/toTick} 闭区间；
 * {@code limit} 缺省 {@value #DEFAULT_LIMIT}、上限 {@value #MAX_LIMIT}，<b>超限具名拒</b>（不是静默截断）。 排序 = tick
 * 降序、同 tick 按 id 升序，先排后截。
 *
 * <p>★ <b>资源声明 = sd 只读</b>：INFO 条目地址（{@code sd:doc.*}）不在任何决策人的可达面，可见性完全由上一条判据承担；
 * 本工具不因读报告给出任何跨区原始数据读权（报告内容由发送方负责）。★ 工具名不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。
 */
public final class SimosSdReportsTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.sd.reports";

  /** {@code limit} 缺省：最近 20 条（与决策结果/Docs 的读口同口径，直接引用同一个常量，不另写一组）。 */
  public static final int DEFAULT_LIMIT = DecisionResultsTool.DEFAULT_LIMIT;

  /** {@code limit} 硬上限：直接引用同一个常量。 */
  public static final int MAX_LIMIT = DecisionResultsTool.MAX_LIMIT;

  /** 本工具的资源声明：sd 只读（见类注的 fail-closed 理由）。 */
  private static final ResourceManifest SD_READ =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  /** 本工具唯一的一台 mapper：只解析报告 {@code value} 那串 JSON 文本，不认识任何领域类型。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  private final QueryService query;

  public SimosSdReportsTool(QueryService query) {
    this.query = java.util.Objects.requireNonNull(query, "query");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "读取跨区上报（simos.sd.report 写下的 sd INFO，key=report；决策人与 GM 共用）："
        + "决策人只看到 tags 含自己 / affiliations 含自己归属 / 自己发的报告；GM 读全部。"
        + "参数 {from?(发送人 id), to?(收件人 kind GOV|NATION|GM 或 id), subjectContains?, fromTick?, toTick?,"
        + " branch?, revision?, limit?(缺省 "
        + DEFAULT_LIMIT
        + "，上限 "
        + MAX_LIMIT
        + "，超限具名拒)}。返回 {reports:[{id, tick, tags, affiliations, key, value, report?, at}], count, note?}，"
        + "按 tick 降序（同 tick 按 id 升序）；value 是原样的 JSON 文本，report 是能解析时的结构化副本。"
        + "读报告不授予任何跨区原始数据读权（内容由发送方负责）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("from", ToolSupport.prop("string", "只看该发送人 id（value.from 精确匹配）"));
    props.put("to", ToolSupport.prop("string", "只看该收件人：kind（GOV|NATION|GM，大小写不敏感）或 id 精确匹配"));
    props.put("subjectContains", ToolSupport.prop("string", "只看 subject 含该子串的报告"));
    props.put("fromTick", ToolSupport.prop("integer", "起始 tick（闭区间下界）"));
    props.put("toTick", ToolSupport.prop("integer", "结束 tick（闭区间上界）"));
    props.put(
        "limit",
        ToolSupport.prop("integer", "最多返回多少条（缺省 " + DEFAULT_LIMIT + "，上限 " + MAX_LIMIT + "，超限拒）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return SD_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      QueryTarget target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      RedactingQueryService.DecisionResultWindow window = windowOf(args);
      String fromFilter = ToolSupport.optionalText(args, "from", null);
      String toFilter = ToolSupport.optionalText(args, "to", null);
      String subjectContains = ToolSupport.optionalText(args, "subjectContains", null);
      SimulationState state = query.stateAt(target);
      SdState sd = ToolSupport.sdState(state);
      DecisionMakerId actor =
          DecisionCallerFactory.decisionMakerIdOf(context.identity())
              .map(DecisionMakerId::parse)
              .orElse(null);
      Affiliation affiliation =
          actor == null
              ? null
              : Optional.ofNullable(sd.decisionMakers().get(actor))
                  .map(DecisionMaker::affiliation)
                  .orElse(null);
      List<MatchedReport> matched = new ArrayList<>();
      for (Map.Entry<String, List<SdInfoEntry>> at : sd.info().entrySet()) {
        for (SdInfoEntry entry : at.getValue()) {
          if (!"report".equals(entry.key())) {
            continue;
          }
          JsonNode report = parseReport(entry.value());
          if (actor != null && !visibleToDecisionMaker(entry, actor, affiliation, report)) {
            continue;
          }
          if (!inWindow(entry.tick(), window)) {
            continue;
          }
          if (fromFilter != null && !fromFilter.equals(textOf(report, "from"))) {
            continue;
          }
          if (toFilter != null && !toMatches(report, toFilter)) {
            continue;
          }
          if (subjectContains != null && !subjectOf(report).contains(subjectContains)) {
            continue;
          }
          matched.add(new MatchedReport(entry, report));
        }
      }
      matched.sort(
          Comparator.comparingLong((MatchedReport row) -> row.entry().tick())
              .reversed()
              .thenComparing((MatchedReport row) -> row.entry().id().value()));
      List<Map<String, Object>> reports = new ArrayList<>();
      for (MatchedReport row : matched) {
        if (reports.size() >= window.limit()) {
          break;
        }
        reports.add(reportView(row, target));
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("reports", reports);
      view.put("count", reports.size());
      if (reports.isEmpty()) {
        view.put("note", "没有可查看的上报（当前查询范围与你的可见范围内没有命中）");
      }
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "读取上报失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /** 载荷 → 窗口；limit 上限在本层先判（其余边界复用 {@link RedactingQueryService.DecisionResultWindow} 的口径）。 */
  private static RedactingQueryService.DecisionResultWindow windowOf(Map<String, Object> args) {
    Long fromTick = ToolSupport.optionalLong(args, "fromTick");
    Long toTick = ToolSupport.optionalLong(args, "toTick");
    requireNonNegative("fromTick", fromTick);
    requireNonNegative("toTick", toTick);
    Long limitArg = ToolSupport.optionalLong(args, "limit");
    int limit;
    if (limitArg == null) {
      limit = DEFAULT_LIMIT;
    } else if (limitArg > MAX_LIMIT) {
      // ★ 超限**明确拒**（不是静默截断——静默会让调用方以为拿到了全部）。
      throw new IllegalArgumentException("limit 上限为 " + MAX_LIMIT + ": " + limitArg);
    } else {
      limit = limitArg.intValue();
    }
    return new RedactingQueryService.DecisionResultWindow(null, fromTick, toTick, limit);
  }

  private static void requireNonNegative(String name, Long value) {
    if (value != null && value < 0) {
      throw new IllegalArgumentException("参数 " + name + " 必须 ≥ 0: " + value);
    }
  }

  /**
   * 决策人可见性三支：tags / 归属两轴复用 {@link RedactingQueryService#reportVisibleToDecisionMaker}，第三轴 = 自己发的。
   */
  private static boolean visibleToDecisionMaker(
      SdInfoEntry entry, DecisionMakerId actor, Affiliation affiliation, JsonNode report) {
    return RedactingQueryService.reportVisibleToDecisionMaker(
        entry, actor, affiliation, textOf(report, "from"));
  }

  private static boolean inWindow(long tick, RedactingQueryService.DecisionResultWindow window) {
    if (window.fromTick() != null && tick < window.fromTick()) {
      return false;
    }
    return window.toTick() == null || tick <= window.toTick();
  }

  /** 参数 {@code to}：收件人 kind（大小写不敏感）或 id 精确匹配；报告 {@code to} 非对象时按文本比。 */
  private static boolean toMatches(JsonNode report, String filter) {
    if (report == null) {
      return false;
    }
    JsonNode to = report.path("to");
    if (to.isTextual()) {
      return filter.equals(to.asText());
    }
    if (!to.isObject()) {
      return false;
    }
    return filter.equalsIgnoreCase(to.path("kind").asText(""))
        || filter.equals(to.path("id").asText(""));
  }

  private static String subjectOf(JsonNode report) {
    return textOf(report, "subject");
  }

  private static String textOf(JsonNode report, String field) {
    if (report == null || !report.isObject()) {
      return "";
    }
    return report.path(field).asText("");
  }

  /**
   * {@code value} 按约定是 JSON 文本 ⇒ 解析成对象副本；不是文本 / 解析失败 / 不是对象 ⇒ {@code null} （可见性仍可由
   * tags/affiliations 承担，原始 value 照原样返回，不因解析失败丢报告）。
   */
  private static JsonNode parseReport(Object value) {
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

  /** 命中的一条报告：条目与解析后的结构化值成对保留（解析失败时第二项为 null）。 */
  private record MatchedReport(SdInfoEntry entry, JsonNode report) {}

  private static Map<String, Object> reportView(MatchedReport row, QueryTarget target) {
    SdInfoEntry entry = row.entry();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("id", entry.id().value());
    view.put("tick", entry.tick());
    view.put("key", entry.key());
    view.put("tags", entry.tags().stream().map(DecisionMakerId::value).sorted().toList());
    view.put("affiliations", affiliationViews(entry.affiliations()));
    view.put("value", entry.value());
    if (row.report() != null) {
      view.put("report", row.report());
    }
    Map<String, Object> at = new LinkedHashMap<>();
    at.put("branch", target.branch().value());
    at.put("revision", entry.at().value());
    view.put("at", at);
    return view;
  }

  /** 归属视图：{@code {kind, id}}，按 {@code kind:id} 升序（响应字节可复现）。 */
  private static List<Map<String, Object>> affiliationViews(Set<Affiliation> affiliations) {
    List<Map<String, Object>> out = new ArrayList<>(affiliations.size());
    for (Affiliation affiliation : affiliations) {
      Map<String, Object> view = new LinkedHashMap<>();
      switch (affiliation) {
        case Affiliation.Nation nation -> {
          view.put("kind", "nation");
          view.put("id", nation.nationId().value());
        }
        case Affiliation.Army army -> {
          view.put("kind", "army");
          view.put("id", army.armyId().value());
        }
        case Affiliation.Gov gov -> {
          view.put("kind", "gov");
          view.put("id", gov.govUnit().value());
        }
      }
      out.add(view);
    }
    out.sort(
        Comparator.comparing((Map<String, Object> row) -> row.get("kind") + ":" + row.get("id")));
    return List.copyOf(out);
  }
}

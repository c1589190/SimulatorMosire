package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ResourcePolicy;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.access.DecisionCallerFactory;
import io.mosire.simos.app.access.DecisionScopeFunctions;
import io.mosire.simos.app.docs.DecisionDoc;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.query.RedactingQueryService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.sd.id.DecisionMakerId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code sd.DecisionDocs}（Docs 系统，2026-09-23）：**决策人查看发给自己的设定文档**。
 *
 * <p>★ **用户的诉求**：「Docs 系统则是限定范围到单个决策人的文档信息查看系统……为具体的决策人补全用于做决策的具体设定」。
 *
 * <p>★★ **可见性 = 喂给 {@link RedactingQueryService#docs}**（不是本类自己筛）：判据是「条目 {@code tags} 含调用者自己 **或**
 * 条目 {@code affiliations} 含调用者的归属」（用户裁定：「显式指派 + 归属自动，取并集」）。规则**只此一处**——GUI 的文档子页复用
 * 同一个方法（照 {@code sd.DecisionResults} 与决策结果子页的关系）。
 *
 * <p>★ **调用者身份取自宿主已知的东西**（{@code context.identity()}）——**不由载荷自报**（载荷是模型写的，让它自报等于让它自己发文档给自己）。
 * 解不出决策人 ⇒ 明确失败，不给数据。
 *
 * <p>★ **取不到与不可见返回同一个回答**：{@code docId} 命中不了时**不区分**"不存在"与"存在但你无权看"——否则读出的是
 * **存在性**（一个不该漏的信息）。两种情况都回"没有可查看的文档"。
 *
 * <p>★ **资源声明 = sd 只读**（与 {@link DecisionResultsTool} 同口径）：文档条目的地址（{@code sd:doc.<docId>}）不在任何决策人的可达面，
 * 拉资源断言会把全部文档判成不可见；归属由上面那条判据承担。{@code READ_ONLY} 的失效方向是 fail-closed。
 *
 * <p>★ **只在决策人桶**（{@code Role.DECISION_AGENT}）：GM 侧有 GUI 的文档子页（可按任意决策人的视角预览实际可见集合）。本工具与 {@link
 * DecisionCallerFactory#WHITELIST} **两处同步**（否则 {@code DecisionToolDefs.requireAll} 当场炸）。
 *
 * <p>★ **正文约定**（{@link DecisionDoc} 的类注）：{@code value} 是**原样的 {@code Object}**——本工具**不解析**它（与 {@code
 * sd.DecisionResults} 同口径：{@code SdInfoEntry.value} 的契约是裸 {@code Object}）。按约定它是 JSON 文本，形如 {@code
 * {"title":…,"body":…,"subject":{…},"effectiveTick":…,"author":…}}；读的一方自行解读，工具不假装能解析任意值。
 */
public final class DecisionDocsTool implements AgentTool {

  /** 工具名（全局唯一）。★ 它不是一条命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "sd.DecisionDocs";

  /**
   * {@code limit} 的缺省/上限：**直接引用 {@link DecisionResultsTool} 的同名常量**。
   *
   * <p>★ 不另写一组 20/200：那是"两份都对、只会漂移"的老形态（那个常量的 javadoc 已经交代过同一件事）。
   */
  public static final int DEFAULT_LIMIT = DecisionResultsTool.DEFAULT_LIMIT;

  /** 见 {@link #DEFAULT_LIMIT}。 */
  public static final int MAX_LIMIT = DecisionResultsTool.MAX_LIMIT;

  /** 本工具的资源声明：sd 只读（见类注的 fail-closed 理由）。 */
  private static final ResourceManifest SD_READ =
      ResourceManifest.of(ToolSupport.SD_NAMESPACE, ResourcePolicy.READ_ONLY);

  private final RedactingQueryService redacting;

  public DecisionDocsTool(QueryService query, String mapId) {
    this.redacting = new RedactingQueryService(query, DecisionScopeFunctions.defaults(), mapId);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "查看**发给本人的**设定文档（Docs，决策人只读）：返回 sd INFO 里"
        + " tags 含调用者自己、**或** affiliations 含调用者归属的文档条目（两轴取并集）。"
        + "载荷 {docId?, branch?, revision?, tick?, fromTick?, toTick?, limit?}——不给 docId 返回清单（按 tick 降序），"
        + "给了则精确取那一篇（此时 limit 不参与，取不到与不可见返回同一个回答）。"
        + "返回 {docs:[{docId, id, tick, tags, affiliations, key, value, note?, at{branch, revision}}], count, note?}；"
        + "★ value 是原样的裸值（按约定是 JSON 文本，形如 {title, body, subject, effectiveTick, author}），本工具不解析它。"
        + "两轴都不命中的文档对决策人不可见（无主 ≠ 大家都能看）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("docId", ToolSupport.prop("string", "只看这一篇（精确取；取不到与不可见返回同一回答）"));
    props.put("tick", ToolSupport.prop("integer", "只看该 tick（与 fromTick/toTick 互斥）"));
    props.put("fromTick", ToolSupport.prop("integer", "起始 tick（闭区间下界）"));
    props.put("toTick", ToolSupport.prop("integer", "结束 tick（闭区间上界）"));
    props.put(
        "limit",
        ToolSupport.prop("integer", "最多返回多少条（缺省 " + DEFAULT_LIMIT + "，上限 " + MAX_LIMIT + "）"));
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
      // ★ 身份取自宿主已知的 AgentIdentity（不由载荷自报）；解不出决策人 ⇒ 明确失败，不给任何数据。
      DecisionMakerId actor =
          DecisionCallerFactory.decisionMakerIdOf(context.identity())
              .map(DecisionMakerId::new)
              .orElse(null);
      if (actor == null) {
        return ToolResult.error("BAD_REQUEST", "本工具只对决策人可用（调用者身份不是决策人）");
      }
      // 给了就必须是非空文本（空串 ⇒ BAD_REQUEST，不静默当成"没给"）。
      String docId = ToolSupport.optionalText(args, "docId", null);
      // ★ 按 id 取单篇时**不受 limit 截断**：否则"第 21 篇"会以"查无此文档"的形式出现（分不清"没有"与"没取到"）。
      RedactingQueryService.DecisionResultWindow window = windowOf(args, docId != null);
      List<Map<String, Object>> docs = redacting.docs(actor, target, window);
      if (docId != null) {
        docs = onlyDocId(docs, docId);
      }
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("docs", docs);
      view.put("count", docs.size());
      if (docs.isEmpty()) {
        view.put(
            "note",
            docId == null
                ? "没有可查看的文档（该决策人在这次查询范围里没有命中任何文档）"
                : "没有可查看的文档（这个 docId 不存在，或它对你不可见——两者返回同一个回答）");
      }
      return ToolSupport.ok(view);
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** 精确取一篇（{@code docId} 是 canonical 地址里那一段，见 {@link DecisionDoc}）。 */
  private static List<Map<String, Object>> onlyDocId(List<Map<String, Object>> docs, String docId) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> doc : docs) {
      if (docId.equals(doc.get("docId"))) {
        out.add(doc);
      }
    }
    return List.copyOf(out);
  }

  /** 载荷 → 可见窗口；所有边界在此校成 {@code BAD_REQUEST}（不把含糊输入交给下层猜）。 */
  private static RedactingQueryService.DecisionResultWindow windowOf(
      Map<String, Object> args, boolean exactDocId) {
    Long tick = ToolSupport.optionalLong(args, "tick");
    Long fromTick = ToolSupport.optionalLong(args, "fromTick");
    Long toTick = ToolSupport.optionalLong(args, "toTick");
    Long limitArg = exactDocId ? null : ToolSupport.optionalLong(args, "limit");
    requireNonNegative("tick", tick);
    requireNonNegative("fromTick", fromTick);
    requireNonNegative("toTick", toTick);
    int limit;
    if (exactDocId) {
      limit = MAX_LIMIT;
    } else if (limitArg == null) {
      limit = DEFAULT_LIMIT;
    } else if (limitArg > MAX_LIMIT) {
      // ★ 超限**明确拒**（不是静默截断——静默会让调用方以为拿到了全部）。
      throw new IllegalArgumentException("limit 上限为 " + MAX_LIMIT + ": " + limitArg);
    } else {
      limit = limitArg.intValue();
    }
    // 互斥 / 方向 / limit 为正 的判定住在 DecisionResultWindow 的构造期（同一处口径）。
    return new RedactingQueryService.DecisionResultWindow(tick, fromTick, toTick, limit);
  }

  /** 给了就必须 ≥ 0（{@code tick} 的既有口径；缺省不算违规）。 */
  private static void requireNonNegative(String name, Long value) {
    if (value != null && value < 0) {
      throw new IllegalArgumentException("参数 " + name + " 必须 ≥ 0: " + value);
    }
  }
}

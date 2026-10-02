package io.mosire.simos.app.tools.write;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.approval.AskKind;
import io.mosire.agentlib.approval.ToolGate;
import io.mosire.agentlib.permission.AccessToken;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.permission.ToolSpec;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.time.CalendarConfig;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarDate;
import io.mosire.simos.calendar.ClimatePhase;
import io.mosire.simos.calendar.JulianCalendar;
import io.mosire.simos.calendar.SeasonBoundary;
import io.mosire.simos.calendar.SeasonState;
import io.mosire.simos.calendar.TropicalModel;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code simos.calendar.configure}（C5b，设计稿 §七；D-018 补裁）：**GM 专属**的历法/气候配置写口（部分合并 + dryRun）。
 *
 * <p>★★ <b>不落 revision</b>：它写的是库级元数据 {@code store_meta.calendar}（与 {@code time_base} 同类）——设计稿 §七原文
 * 「不落 revision（改锚点不改变世界状态；若落 revision，重放时反要问"当时配置是什么"，反而制造第二真相）」。因此本类 <b>不继承</b> {@code
 * AbstractNarrowWriteTool}、<b>不调</b> {@code core.submit}、也不碰 {@code Command → ChangeSet → Revision}
 * 那条路；持久化只经 {@link CalendarService#apply}（先校验 → 再 {@code writeStoreMeta} → 后原子换快照，写失败不换内存）。
 *
 * <p>★★ <b>只在 GM 桶</b>：注册点 = {@code SimosToolSource.addGmWrites(...)}（GM 专属写注册处），<b>不进</b>四桶共享的普通读
 * 列表、也不进决策人桶。它是"世界级配置"（改锚点会让所有历史日期整体平移、改分带会改全世界的季节判定），不该由决策人 自选。GM 面的审批链由 {@code GmAutoApproveGate}
 * 无脑放行，每次实际执行另有 {@code GmToolUsage} 留痕（工具名 + 结果码 + 时刻）——即 <b>GM 桶 + 审批留痕</b>。
 *
 * <p>★ <b>敏感写</b>：{@code spec().sensitive()=true} + {@link ToolGate.Ask}（classKey = 工具名，summary 只回显
 * 字段、不解析参数——{@code gate()} 在 {@code execute()} 校验之前调用，解析失败不能让审批链先炸，口径同 {@code
 * GmApproveTool}）。{@code destructive=false}：它是覆盖一条配置值，不删任何世界数据。
 *
 * <p>★ <b>资源面取 {@link ResourceManifest#NONE}</b>：历法配置是 {@code store_meta} 键，**没有**对应的世界资源命名空间
 * （map/social/unit/sd/economy/actor/army 都不对）。NONE 表示"不声明任何资源面"——声明一个不存在的命名空间会与 {@code
 * Shell#gmPermissionSet} 的逐命名空间表态对不上，且本工具<b>不自调</b> {@code require}，故 NONE 是唯一诚实的形状 （同 {@code
 * GmApproveTool} / {@code LlmProvidersTool} 的控制面写法）。
 *
 * <p>★ <b>部分合并</b>：只给要改的字段，缺省字段沿用当前 {@link CalendarService#config()}；非法值一律 fail-closed（具名 {@code
 * BAD_REQUEST}，不静默截断/回落）。{@code dryRun=true} ⇒ 只预览：不写 store、不换内存快照，返回 {@code applied=false} 与
 * warnings。
 */
public final class CalendarConfigureTool implements AgentTool {

  /** 工具名（全局唯一）。★ 不是命令类型 ⇒ 不进 catalog / {@code PAYLOAD_HINTS}。 */
  public static final String NAME = "simos.calendar.configure";

  /** {@code epoch} 文本形状：{@code y-m-d}，年可负（月/日位数 1~2；月内合法性交给 CalendarDate/JulianCalendar 当场抛）。 */
  private static final Pattern EPOCH_PATTERN = Pattern.compile("^(-?\\d+)-(\\d{1,2})-(\\d{1,2})$");

  /**
   * 本工具**实际声明/读取**的参数名全集（C7a）：配置字段（含新增的 {@code calendar}）+ {@code dryRun} + {@link
   * ToolSupport#targetProps()} 的 {@code branch/revision} + 可选预览坐标 {@code q/r}。
   *
   * <p>★ <b>未知参数 fail-closed</b>：schema 之外的 key 一律具名 {@code BAD_REQUEST}，不得静默 merge 忽略。框架注入的键走
   * {@link ToolContext#config()}（宿主通道），不混进 {@code arguments()}，故这份集合就是本工具参数面的全集；它必须与 {@code
   * jsonSchema().get("properties")} 逐键同源。
   */
  private static final Set<String> ACCEPTED_ARGS =
      Set.of(
          "calendar",
          "epoch",
          "seasonBoundary",
          "tropicalModel",
          "tropicalRainyStartLongitude",
          "tropicalRainyEndLongitude",
          "northIsNegative",
          "northMax",
          "southMin",
          "dryRun",
          "branch",
          "revision",
          "q",
          "r");

  private final QueryService query;
  private final CalendarService calendarService;

  // ★ CalendarService 是**共享协作者**（本类只调它，不拥有、不把它暴露出去；构造期注入是设计本身）：与
  //   LevyRegionTool 的 CalendarService 同口径豁免 EI_EXPOSE_REP2。
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "CalendarService 是共享协作者（唯一写口 apply），只调用不拥有、不外泄引用")
  public CalendarConfigureTool(QueryService query, CalendarService calendarService) {
    this.query = query;
    this.calendarService = calendarService;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "配置历法/气候（GM 专用，库级元数据，**不落世界 revision**）：字段全部可选、部分合并（缺省沿用当前值）："
        + "calendar(历法 id；本批**只认 julian**，缺省=沿用当前配置) / epoch(y-m-d) /"
        + " seasonBoundary(SOLAR_TERM|ASTRONOMICAL) / tropicalModel(RAINY_DRY|TEMPERATE_LIKE) /"
        + " tropicalRainyStartLongitude / tropicalRainyEndLongitude(0 ≤ start < end ≤ 360) /"
        + " northIsNegative / northMax / southMin(必须成对、northMax < southMin，缺省 null=未配置) /"
        + " dryRun(缺省 false) / 预览坐标 q,r(必须成对，缺省 (0,0))。"
        + "只接受以上字段 + branch/revision(目标 state 选择)；schema 之外的未知参数 ⇒ 具名 BAD_REQUEST，不静默忽略。"
        + "正式 apply 落 store_meta.calendar 并换内存快照（sources=store）；"
        + "dryRun=true 只预览（applied=false，不落盘不换内存）。非法值 ⇒ BAD_REQUEST。"
        + "锚点变更会让所有历史显示日期整体平移（warnings 里给出）；建议在创世期配置";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    Map<String, Object> calendarProp =
        ToolSupport.prop("string", "历法 id：本批只认 \"julian\"；缺省 = 沿用当前配置");
    calendarProp.put("enum", List.of(JulianCalendar.ID));
    props.put("calendar", calendarProp);
    props.put("epoch", ToolSupport.prop("string", "tick 0 的儒略历锚点，y-m-d（如 1445-01-01；年可负）"));
    props.put(
        "seasonBoundary", ToolSupport.prop("string", "季界族：SOLAR_TERM（24 节气）| ASTRONOMICAL（二分二至）"));
    props.put(
        "tropicalModel",
        ToolSupport.prop("string", "热带季节模型：RAINY_DRY（雨/旱季）| TEMPERATE_LIKE（类温带四季）"));
    props.put(
        "tropicalRainyStartLongitude",
        ToolSupport.prop("number", "雨季窗口起点太阳黄经（度，含；0 ≤ start < end ≤ 360）"));
    props.put("tropicalRainyEndLongitude", ToolSupport.prop("number", "雨季窗口终点太阳黄经（度，不含）"));
    props.put("northIsNegative", ToolSupport.prop("boolean", "原始坐标轴向：true = 负 r 为北（缺省 true）"));
    props.put("northMax", ToolSupport.prop("integer", "北温带与热带的分界 r（含，按北负约定；与 southMin 成对）"));
    props.put("southMin", ToolSupport.prop("integer", "热带与南温带的分界 r（含；与 northMax 成对）"));
    props.put(
        "dryRun", ToolSupport.prop("boolean", "true = 只预览（applied=false，不写 store、不换内存），缺省 false"));
    props.put("q", ToolSupport.prop("integer", "预览坐标 q（可选，与 r 成对；季节用的格子，缺省 0）"));
    props.put("r", ToolSupport.prop("integer", "预览坐标 r（可选，与 q 成对；季节用的格子，缺省 0）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ToolSpec spec() {
    // 敏感写（走审批门链）、可外发（noExport=false）——与其余 GM 敏感写同制；GM 面无脑过。
    return ToolSpec.level(AccessToken.DEFAULT, true, false);
  }

  @Override
  public ResourceManifest resources() {
    // 历法配置是库级 store_meta 键（与 time_base 同层），没有对应的世界资源命名空间 ⇒ NONE（见类注）。
    return ResourceManifest.NONE;
  }

  @Override
  public ToolGate gate(ToolContext context) {
    // ★ 摘要只做字段回显、不解析参数：gate() 在 execute() 的校验之前调用，解析失败不能让审批链先炸（同 GmApproveTool）。
    Map<String, Object> args = context.arguments();
    return new ToolGate.Ask(
        NAME,
        "配置历法/气候（库级 store_meta.calendar，不落 revision）：epoch="
            + args.get("epoch")
            + " seasonBoundary="
            + args.get("seasonBoundary")
            + " tropicalModel="
            + args.get("tropicalModel")
            + " rainyWindow="
            + args.get("tropicalRainyStartLongitude")
            + ".."
            + args.get("tropicalRainyEndLongitude")
            + " northIsNegative="
            + args.get("northIsNegative")
            + " northMax="
            + args.get("northMax")
            + " southMin="
            + args.get("southMin")
            + " dryRun="
            + args.get("dryRun")
            + " at="
            + args.get("q")
            + "_"
            + args.get("r"),
        AskKind.SENSITIVE);
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      // ★ C7a：未知参数 fail-closed 拒在前（先于任何解析/落盘）；只接受本工具实际声明/读取的 key 全集。
      rejectUnknownArgs(args);
      // ★ q/r 必须成对：只给一个 ⇒ 具名 BAD_REQUEST（两处工具同一口径，不静默按 (0,0) 办）。
      Long qArg = ToolSupport.optionalLong(args, "q");
      Long rArg = ToolSupport.optionalLong(args, "r");
      if ((qArg == null) != (rArg == null)) {
        return ToolResult.error("BAD_REQUEST", "q/r 必须成对给出（只给一个无法定位格子）：q=" + qArg + "，r=" + rArg);
      }
      boolean dryRun = ToolSupport.optionalBoolean(args, "dryRun").orElse(false);
      CalendarConfig current = calendarService.config();
      CalendarConfig candidate = merge(args, current);
      // ★ atTick = 目标 state 的当前 tick（缺省 head、可用 branch/revision 覆盖）；at = q/r 或 (0,0)。
      SimulationState state = query.stateAt(ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH));
      long atTick = state.meta().timestamp().tick();
      HexCoord at =
          qArg == null
              ? new HexCoord(0, 0)
              : new HexCoord(Math.toIntExact(qArg), Math.toIntExact(rArg));
      CalendarService.ApplyResult applied = calendarService.apply(candidate, dryRun, atTick, at);

      Map<String, Object> view = new LinkedHashMap<>();
      view.put("applied", applied.applied());
      view.put("dryRun", dryRun);
      view.put("date", dateView(applied.date()));
      if (qArg != null) {
        view.put("season", seasonView(calendarService, applied.season(), at));
      }
      view.put("warnings", applied.warnings());
      // ★ sources/config 取**apply 之后**的服务读侧：正式 apply 成功 ⇒ calendar/season=store（分带未配置则 fallback）；
      //   dryRun ⇒ 仍是改前真相（不落盘、不换内存快照）。
      view.put("sources", sourcesView(calendarService));
      view.put("config", configView(calendarService.config()));
      view.put("notes", notes(dryRun, qArg == null));
      return ToolResult.ok(ToolSupport.json(view));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    } catch (RuntimeException e) {
      return ToolResult.error(
          "TOOL_ERROR", "历法配置写入失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  /**
   * C7a 未知参数 fail-closed：只接受 {@link #ACCEPTED_ARGS}（= {@code jsonSchema()} 的声明面 + 本类实际读取面）。
   *
   * <p>★ 模型给 schema 之外的 key 时不得静默 merge 忽略；拒因只报未知 key 名（排序后输出，回显值不必要）。
   */
  private static void rejectUnknownArgs(Map<String, Object> args) {
    List<String> unknown = new ArrayList<>();
    for (String name : args.keySet()) {
      if (!ACCEPTED_ARGS.contains(name)) {
        unknown.add(name);
      }
    }
    if (unknown.isEmpty()) {
      return;
    }
    List<String> accepted = new ArrayList<>(ACCEPTED_ARGS);
    accepted.sort(null);
    unknown.sort(null);
    throw new IllegalArgumentException(
        "不支持的参数：" + String.join(", ", unknown) + "；本工具只接受 " + String.join(" / ", accepted));
  }

  /**
   * 部分合并：只覆盖**显式给出**的字段（缺省沿用 {@code current}）；所有解析/校验在构造 {@link CalendarConfig} 与 {@link
   * CalendarService#apply} 里收口，非法值 fail-closed。
   */
  private static CalendarConfig merge(Map<String, Object> args, CalendarConfig current) {
    // ★ C7a：calendar 是显式可选入参（schema 已声明）。键缺席 ⇒ 沿用当前配置；只要给出（含 JSON null）
    //   就必须逐字等于 julian，否则具名 BAD_REQUEST，绝不静默忽略。
    String calendar = current.calendar();
    if (args.containsKey("calendar")) {
      Object raw = args.get("calendar");
      if (!(raw instanceof String text) || !JulianCalendar.ID.equals(text)) {
        throw new IllegalArgumentException(
            "calendar 必须是非空历法 id 且只认 \"" + JulianCalendar.ID + "\"：" + raw);
      }
      calendar = text;
    }
    CalendarDate epoch =
        ToolSupport.has(args, "epoch")
            ? parseEpoch(ToolSupport.requiredText(args, "epoch"))
            : current.epoch();
    SeasonBoundary seasonBoundary =
        ToolSupport.has(args, "seasonBoundary")
            ? enumOf(
                SeasonBoundary.class,
                "seasonBoundary",
                ToolSupport.requiredText(args, "seasonBoundary"))
            : current.seasonBoundary();
    TropicalModel tropicalModel =
        ToolSupport.has(args, "tropicalModel")
            ? enumOf(
                TropicalModel.class,
                "tropicalModel",
                ToolSupport.requiredText(args, "tropicalModel"))
            : current.tropicalModel();
    double rainyStart =
        ToolSupport.has(args, "tropicalRainyStartLongitude")
            ? optionalDouble(args, "tropicalRainyStartLongitude")
            : current.tropicalRainyStartLongitude();
    double rainyEnd =
        ToolSupport.has(args, "tropicalRainyEndLongitude")
            ? optionalDouble(args, "tropicalRainyEndLongitude")
            : current.tropicalRainyEndLongitude();
    boolean northIsNegative =
        ToolSupport.optionalBoolean(args, "northIsNegative").orElse(current.northIsNegative());
    Long northMax =
        ToolSupport.has(args, "northMax")
            ? ToolSupport.optionalLong(args, "northMax")
            : current.northMax();
    Long southMin =
        ToolSupport.has(args, "southMin")
            ? ToolSupport.optionalLong(args, "southMin")
            : current.southMin();
    // ★ 来源标记必须与候选值自洽（CalendarConfig 构造期强制）：分带已配置 ⇔ zoneSource=store，未配置 ⇔ fallback。
    //   分带**没有缺省值**（D-018 补裁：只给接口、GM 配置）——两个都非 null 只可能来自 store，故由值反推来源。
    CalendarConfig.Source zoneSource =
        northMax == null ? CalendarConfig.Source.FALLBACK : CalendarConfig.Source.STORE;
    return new CalendarConfig(
        CalendarConfig.VERSION_1,
        calendar,
        epoch,
        seasonBoundary,
        tropicalModel,
        rainyStart,
        rainyEnd,
        northIsNegative,
        northMax,
        southMin,
        current.calendarSource(),
        current.seasonSource(),
        zoneSource);
  }

  /** {@code epoch} 文本 → {@link CalendarDate}；月内合法性（2/30 等）由 {@code CalendarConfig} 构造期正算触发。 */
  private static CalendarDate parseEpoch(String text) {
    Matcher matcher = EPOCH_PATTERN.matcher(text);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("epoch 必须匹配 y-m-d（允许负年；如 1445-01-01）：" + text);
    }
    try {
      return new CalendarDate(
          Long.parseLong(matcher.group(1)),
          Integer.parseInt(matcher.group(2)),
          Integer.parseInt(matcher.group(3)));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("epoch 的数字部分越界（年/月/日）：" + text, e);
    }
  }

  /** 枚举参数：只认 {@code Enum.name()} 全大写（口径同 {@code store_meta.calendar} 的 JSON）。 */
  private static <E extends Enum<E>> E enumOf(Class<E> type, String name, String text) {
    try {
      return Enum.valueOf(type, text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          name + " 只接受 " + java.util.Arrays.toString(type.getEnumConstants()) + "：" + text);
    }
  }

  /** 可选 double：接受 JSON number 或数字文本；NaN/Infinity/非数字 ⇒ BAD_REQUEST（不静默截断）。 */
  private static double optionalDouble(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (value instanceof Number number) {
      double result = number.doubleValue();
      if (!Double.isFinite(result)) {
        throw new IllegalArgumentException("参数 " + name + " 必须是有限数：" + value);
      }
      return result;
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        double result = Double.parseDouble(text.trim());
        if (!Double.isFinite(result)) {
          throw new IllegalArgumentException("参数 " + name + " 必须是有限数：" + text);
        }
        return result;
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("参数 " + name + " 必须是数字：" + text);
      }
    }
    throw new IllegalArgumentException("参数 " + name + " 必须是数字");
  }

  /** 日期四件套（与 {@code info} 同形；{@code dayOfYear} 用儒略历现算）。 */
  private static Map<String, Object> dateView(CalendarDate date) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("year", date.year());
    view.put("month", date.month());
    view.put("day", date.day());
    view.put("dayOfYear", JulianCalendar.INSTANCE.dayOfYear(date));
    return view;
  }

  /** 季节视图（与 {@code info} 同形；仅 q/r 成对给出时出现）。 */
  private static Map<String, Object> seasonView(
      CalendarService service, SeasonState season, HexCoord at) {
    ClimatePhase phase = season.phase();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("phase", phase.getClass().getSimpleName());
    view.put("key", phase.key());
    view.put("name", phase.chineseName());
    view.put("dayOfSeason", season.dayOfSeason());
    view.put("daysInSeason", season.daysInSeason());
    view.put("progressPerMille", (int) Math.round(season.progress() * 1000.0));
    view.put("zone", service.bands().zoneOf(at).name());
    view.put("zoneSource", service.zoneSource().key());
    return view;
  }

  /** 三个来源标记（{@code store|default|fallback}；正式 apply 后 = store / store / store|fallback）。 */
  private static Map<String, Object> sourcesView(CalendarService service) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("calendarSource", service.calendarSource().key());
    view.put("seasonSource", service.seasonSource().key());
    view.put("zoneSource", service.zoneSource().key());
    return view;
  }

  /** 配置全文（与 {@code info} 同形）。 */
  private static Map<String, Object> configView(CalendarConfig config) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("version", config.version());
    view.put("calendar", config.calendar());
    view.put("epoch", config.epochText());
    view.put("seasonBoundary", config.seasonBoundary().name());
    view.put("tropicalModel", config.tropicalModel().name());
    view.put("tropicalRainyStartLongitude", config.tropicalRainyStartLongitude());
    view.put("tropicalRainyEndLongitude", config.tropicalRainyEndLongitude());
    view.put("northIsNegative", config.northIsNegative());
    view.put("northMax", config.northMax());
    view.put("southMin", config.southMin());
    return view;
  }

  /** 明确口径的备注：dryRun 只预览（不落盘不换内存）；未给 q/r ⇒ 季节不适用。 */
  private static List<String> notes(boolean dryRun, boolean noCoordinates) {
    List<String> notes = new ArrayList<>(2);
    if (dryRun) {
      notes.add("dryRun=true：只预览，未写 store_meta.calendar、未换内存快照（sources 仍是改前真相）。");
    }
    if (noCoordinates) {
      notes.add("未给坐标（q/r 都缺省），季节不适用：本响应省略 season；给成对的 q/r 再查。");
    }
    return notes;
  }
}

package io.mosire.simos.app.tools.read;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.time.CalendarConfig;
import io.mosire.simos.app.time.CalendarService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.calendar.CalendarDate;
import io.mosire.simos.calendar.ClimatePhase;
import io.mosire.simos.calendar.JulianCalendar;
import io.mosire.simos.calendar.SeasonState;
import io.mosire.simos.calendar.SolarTerm;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.calendar.info}（C5b 读工具，设计稿 §七/§八）：当前历法/气候配置 + 指定 tick 的日期/节气（可选季节）。
 *
 * <p>★ <b>四桶共享</b>：不标 {@code GmOnlyRead}——历法只是"今天几号、什么节气"，不含任何可见性侧信道（配置本身是 {@code store_meta}
 * 库级元数据，不落 revision、也不含保密值）。故本类直接进 {@code SimosToolSource.readTools(...)} 的共享列表。
 *
 * <p>★ <b>只读</b>：不写任何状态、不落 revision——{@link CalendarService} 只被读侧调用（{@code
 * config()/clock()/solarTermAt/ seasonAt}），本类**不持有** core、也不调 {@code apply}。
 *
 * <p>★ <b>资源面取 {@link ToolSupport#ALL_READ}</b>（map+social+unit READ_ONLY + actor DENY）：历法**没有自己的资源
 * 命名空间**（配置是 {@code store_meta} 键，与 {@code time_base} 同层，不属于任何领域模块的资源路径）。本类其他读工具 （{@code
 * unit.list}/{@code social.population}/{@code economy.hex}…）一律声明 ALL_READ，这里随同族取它——"读 config"若
 * 单开一个不存在的命名空间反而会与 Shell 的逐命名空间表态对不上（新命名空间不进 {@code gmPermissionSet} 就会回落成未表态）。 声明 ALL_READ
 * 只表示"本工具与那些世界读面同制"，不表示本工具会读它们。
 *
 * <p>★ <b>形状与 C6 读口同源</b>：{@code date}/{@code solarTerm}/{@code season} 的字段名照设计稿 §八（C6 的 {@code
 * ApiViews.timestamp} 必须与本工具一致），来源标记照 C5a 的三个 getter（{@code store|default|fallback}）。
 */
public final class CalendarInfoTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.calendar.info";

  private final QueryService query;
  private final CalendarService calendarService;

  // ★ CalendarService 是**共享只读协作者**（本类只调读侧 getter，不调 apply、不外泄引用）：与
  //   TimelineRevisionsTool 的 CoreSimos 同口径豁免 EI_EXPOSE_REP2。
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "CalendarService 是共享只读协作者（只调 config/clock/solarTermAt/seasonAt），非内部表示外泄")
  public CalendarInfoTool(QueryService query, CalendarService calendarService) {
    this.query = query;
    this.calendarService = calendarService;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "查历法/气候配置与某 tick 的日期：{branch?, revision?, tick?, q?, r?}。"
        + "tick 缺省 = 目标 state 当前 tick；q/r 必须成对——只给一个 ⇒ BAD_REQUEST。"
        + "回 {config（含 version）, sources{calendarSource,seasonSource,zoneSource}(store|default|fallback),"
        + " date{year,month,day,dayOfYear}, solarTerm{key,name,longitude}，成对给 q/r 时才带"
        + " season{phase,key,name,dayOfSeason,daysInSeason,progressPerMille,zone,zoneSource}，"
        + " notes[]}。只读：不写状态、不落 revision";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("tick", ToolSupport.prop("integer", "要查的 tick（缺省 = 目标 state 的当前 tick）"));
    props.put("q", ToolSupport.prop("integer", "六角列坐标 q（必须与 r 成对；只给一个 ⇒ 拒）"));
    props.put("r", ToolSupport.prop("integer", "六角行坐标 r（必须与 q 成对；只给一个 ⇒ 拒）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolSupport.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      Long tickArg = ToolSupport.optionalLong(args, "tick");
      Long qArg = ToolSupport.optionalLong(args, "q");
      Long rArg = ToolSupport.optionalLong(args, "r");
      if ((qArg == null) != (rArg == null)) {
        return ToolResult.error("BAD_REQUEST", "q/r 必须成对给出（只给一个无法定位格子）：q=" + qArg + "，r=" + rArg);
      }
      SimulationState state = query.stateAt(ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH));
      long tick = tickArg == null ? state.meta().timestamp().tick() : tickArg;
      CalendarClock clock = calendarService.clock();
      CalendarDate date = clock.dateOfTick(tick);

      Map<String, Object> view = new LinkedHashMap<>();
      view.put("tick", tick);
      view.put("config", configView(calendarService.config()));
      view.put("sources", sourcesView(calendarService));
      view.put("date", dateView(date));
      view.put("solarTerm", solarTermView(calendarService.solarTermAt(tick)));
      List<String> notes = new ArrayList<>(1);
      if (qArg == null) {
        // ★ 不给坐标 ⇒ season 字段**省略**，理由写进 notes（设计稿 §七的"季节不适用"是显式事实，不静默省略）。
        notes.add("未给坐标（q/r），季节不适用：本响应省略 season；给成对的 q/r 再查。");
      } else {
        HexCoord coord = new HexCoord(Math.toIntExact(qArg), Math.toIntExact(rArg));
        view.put(
            "season", seasonView(calendarService, calendarService.seasonAt(tick, coord), coord));
      }
      view.put("notes", notes);
      return ToolResult.ok(ToolSupport.json(view));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /** {@code CalendarConfig} 的可读字段 + {@code version}（形状与设计稿 §七的 JSON 键同名）。 */
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

  /** 三个来源标记（C5a 的 getter；{@code store|default|fallback}）。 */
  private static Map<String, Object> sourcesView(CalendarService service) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("calendarSource", service.calendarSource().key());
    view.put("seasonSource", service.seasonSource().key());
    view.put("zoneSource", service.zoneSource().key());
    return view;
  }

  /** 日期四件套（{@code dayOfYear} 用 {@link JulianCalendar#dayOfYear}）。 */
  private static Map<String, Object> dateView(CalendarDate date) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("year", date.year());
    view.put("month", date.month());
    view.put("day", date.day());
    view.put("dayOfYear", JulianCalendar.INSTANCE.dayOfYear(date));
    return view;
  }

  /** 当天节气（{@link SolarTerm} 的 key/中文名/黄经）。 */
  private static Map<String, Object> solarTermView(SolarTerm term) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("key", term.key());
    view.put("name", term.chineseName());
    view.put("longitude", term.longitude());
    return view;
  }

  /** 季节状态 + 该格所在纬度带 + 分带来源（设计稿 §八的字段口径）。 */
  private static Map<String, Object> seasonView(
      CalendarService service, SeasonState season, HexCoord at) {
    ClimatePhase phase = season.phase();
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("phase", phase.getClass().getSimpleName());
    view.put("key", phase.key());
    view.put("name", phase.chineseName());
    view.put("dayOfSeason", season.dayOfSeason());
    view.put("daysInSeason", season.daysInSeason());
    view.put("progressPerMille", progressPerMille(season.progress()));
    // ★ 分带与来源必须成对：分带未配置 ⇒ zoneSource=fallback 且 zone 恒 NORTH_TEMPERATE（不假装已分带）。
    view.put("zone", service.bands().zoneOf(at).name());
    view.put("zoneSource", service.zoneSource().key());
    return view;
  }

  /** 进度千分位（四舍五入；{@code progress ∈ [0,1)} ⇒ 值域 0..999，读口只出可比的整数）。 */
  private static int progressPerMille(double progress) {
    return (int) Math.round(progress * 1000.0);
  }
}

package io.mosire.simos.app.time;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.calendar.CalendarClock;
import io.mosire.simos.calendar.CalendarDate;
import io.mosire.simos.calendar.CalendarDefaults;
import io.mosire.simos.calendar.JulianCalendar;
import io.mosire.simos.calendar.LatitudeBands;
import io.mosire.simos.calendar.SeasonBoundary;
import io.mosire.simos.calendar.SeasonSettings;
import io.mosire.simos.calendar.SeasonState;
import io.mosire.simos.calendar.SeasonSystem;
import io.mosire.simos.calendar.SolarTerm;
import io.mosire.simos.calendar.SolarTerms;
import io.mosire.simos.calendar.TropicalModel;
import io.mosire.simos.calendar.ZonedSeasonSystem;
import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.time.YearFraction;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 世界历法/气候配置服务（设计稿 §七/§九；D-018 补裁 / D-019）：不可变快照 + volatile 原子换。
 *
 * <p><b>装配</b>：{@link #load(CoreSimos)} 读 {@code store_meta.calendar}——空 ⇒ 用 {@link
 * CalendarConfig#defaults()} 且<b>不写盘</b>；有值 ⇒ JSON 解析 + 校验，任何失败<b>当场抛</b>（fail-closed，不静默回落缺省）。
 * {@link #defaults()} 不绑 core（不读也不写 store），只能 {@code dryRun}，正式 apply 会当场抛。
 *
 * <p><b>并发口径</b>：读侧一次读取 {@code volatile} 快照字段、不加锁——{@link #config()} / {@link #clock()} / {@link
 * #seasonAt(long, HexCoord)} 等全部基于同一份不可变 {@link Snapshot}；写侧 {@link #apply(CalendarConfig, boolean,
 * long, HexCoord)} 是 {@code synchronized}，<b>先落盘、后换快照</b>（volatile 引用赋值即原子换），故两个并发 apply 不会出现
 * “内存与落盘次序颠倒”；落盘失败不换内存。
 *
 * <p><b>core 不认识历法</b>：持久化只走 {@link CoreSimos#readStoreMeta}/{@link CoreSimos#writeStoreMeta}
 * 的不透明字符串口；单条元数据写不落 revision（设计稿 §七）。
 */
public final class CalendarService {

  /** {@code store_meta} 的历法配置键（设计稿 §七；core 只当不透明字符串存取）。 */
  public static final String STORE_META_KEY = "calendar";

  /** 全项目唯一的 ObjectMapper 装配点（app 层 Jackson；calendar 模块不 import Jackson）。 */
  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  /** {@link #apply(CalendarConfig, boolean)} 缺省的预览坐标（只用于算预览日期/季节，不落盘）。 */
  private static final HexCoord DEFAULT_AT = new HexCoord(0, 0);

  /** JSON 只认的固定字段集；多一个字段 ⇒ IAE（防拼错静默回落）。 */
  private static final Set<String> JSON_FIELDS =
      Set.of(
          "version",
          "calendar",
          "epoch",
          "seasonBoundary",
          "tropicalModel",
          "tropicalRainyStartLongitude",
          "tropicalRainyEndLongitude",
          "northIsNegative",
          "northMax",
          "southMin");

  /** 决定 {@code seasonSource} 是否标 store 的字段集（设计稿 §七的来源标记口径）。 */
  private static final Set<String> SEASON_FIELDS =
      Set.of(
          "seasonBoundary",
          "tropicalModel",
          "tropicalRainyStartLongitude",
          "tropicalRainyEndLongitude");

  /** epoch 文本形状：{@code y-m-d}，年可负（月/日位数 1~2；月内合法性交给 CalendarConfig 构造器）。 */
  private static final Pattern EPOCH_PATTERN = Pattern.compile("^(-?\\d+)-(\\d{1,2})-(\\d{1,2})$");

  /** null = {@link #defaults()} 造的不绑 store 实例；非 null = load 造的生产实例。 */
  private final CoreSimos core;

  /** 不可变快照，volatile 引用赋值即原子换；读侧一次读取后不加锁。 */
  private volatile Snapshot snapshot;

  private CalendarService(CoreSimos core, Snapshot snapshot) {
    this.core = core;
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
  }

  /** 不可变快照：配置 + 由它派生的时钟/季节设置/分带/季节系统，四者同生同换（不会读到半新半旧）。 */
  private record Snapshot(
      CalendarConfig config,
      CalendarClock clock,
      SeasonSettings seasons,
      LatitudeBands bands,
      SeasonSystem seasonSystem) {}

  /** 把一份已校验的配置装配成快照：{@link CalendarClock#of} 与 {@link ZonedSeasonSystem} 当场构造一遍。 */
  private static Snapshot snapshotOf(CalendarConfig config) {
    Objects.requireNonNull(config, "config");
    CalendarClock clock = CalendarClock.of(JulianCalendar.INSTANCE, config.epoch());
    SeasonSettings seasons = config.seasonSettings();
    LatitudeBands bands = config.bands();
    return new Snapshot(config, clock, seasons, bands, new ZonedSeasonSystem(bands, seasons));
  }

  /** 缺省配置 + 不绑 core：不读 store，也不允许正式 apply（只可 dryRun 预览）。 */
  public static CalendarService defaults() {
    return new CalendarService(null, snapshotOf(CalendarConfig.defaults()));
  }

  /**
   * 启动装载（fail-closed）：{@code store_meta.calendar} 缺省 ⇒ {@link CalendarConfig#defaults()}
   * 且<b>不写盘</b>； 有值 ⇒ Jackson 解析 + 校验 + 构造，任何失败当场抛 {@link IllegalStateException}，绝不静默回落缺省配置。
   *
   * @param core 生产实例的 core（正式 apply 要经它落盘）；非空
   * @throws IllegalStateException {@code store_meta.calendar} 解析/校验失败
   */
  public static CalendarService load(CoreSimos core) {
    Objects.requireNonNull(core, "core");
    Optional<String> stored = core.readStoreMeta(STORE_META_KEY);
    if (stored.isEmpty()) {
      // 缺省配置**不静默写盘**：落盘只发生在 GM 显式 apply 之后（设计稿 §七）。
      return new CalendarService(core, snapshotOf(CalendarConfig.defaults()));
    }
    try {
      return new CalendarService(core, snapshotOf(parse(stored.get())));
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "store_meta.calendar 解析/校验失败（fail-closed，不回落默认配置）：" + e.getMessage(), e);
    }
  }

  // ── 读侧：一次读 volatile 快照，不加锁 ────────────────────────────────────────────────

  /** 当前配置（来源标记含 store/default/fallback 的最近一次落盘后真相）。 */
  public CalendarConfig config() {
    return snapshot.config();
  }

  /** 当前世界时钟（历法 + 锚点）；一次推进/一次查询请取一次、整段复用。 */
  public CalendarClock clock() {
    return snapshot.clock();
  }

  /** 当前季节设置（季界族 + 热带模型 + 雨季窗口）。 */
  public SeasonSettings seasonSettings() {
    return snapshot.seasons();
  }

  /** 当前纬度分带（未配置时是 {@link LatitudeBands#unconfigured()} 的 fallback）。 */
  public LatitudeBands bands() {
    return snapshot.bands();
  }

  /** 该 tick 的历法日期（tick 0 = 配置锚点）。 */
  public CalendarDate dateOfTick(long tick) {
    return clock().dateOfTick(tick);
  }

  /** 该 tick 当天的节气（24 节气之一；由日号决定，与是否分带无关）。 */
  public SolarTerm solarTermAt(long tick) {
    return SolarTerms.termOf(clock().dayNumberOfTick(tick));
  }

  /** 该 tick 在某格的季节（按格子 {@code r} 分带；分带未配置 ⇒ 全球北半球四季）。 */
  public SeasonState seasonAt(long tick, HexCoord at) {
    Objects.requireNonNull(at, "at");
    // ★ 只读一次 volatile 快照：时钟与季节系统必须来自同一代，不能在两次读之间被 apply 换掉一半。
    Snapshot current = snapshot;
    return current.seasonSystem().seasonOf(current.clock().dayNumberOfTick(tick), at);
  }

  /** 该 tick 所属历法年的天数（平年 365 / 闰年 366）。 */
  public int daysInYearAtTick(long tick) {
    return clock().daysInYearAtTick(tick);
  }

  /** 半开区间 {@code [fromTick, toTick)} 的历法年分数（精确有理数，见 {@link CalendarClock#yearFraction}）。 */
  public YearFraction yearFraction(long fromTick, long toTick) {
    return clock().yearFraction(fromTick, toTick);
  }

  /** 历法部分的来源标记（store / default）。 */
  public CalendarConfig.Source calendarSource() {
    return config().calendarSource();
  }

  /** 季节部分的来源标记（store / default）。 */
  public CalendarConfig.Source seasonSource() {
    return config().seasonSource();
  }

  /** 分带部分的来源标记（store / fallback）。 */
  public CalendarConfig.Source zoneSource() {
    return config().zoneSource();
  }

  // ── 写侧：synchronized；先落盘、后换快照 ─────────────────────────────────────────────

  /** {@code apply(candidate, dryRun)} 的缺省坐标重载（atTick=0、at={@link #DEFAULT_AT}）。 */
  public ApplyResult apply(CalendarConfig candidate, boolean dryRun) {
    return apply(candidate, dryRun, 0L, DEFAULT_AT);
  }

  /**
   * 应用一份新配置：先建新快照（触发时钟/季界构造与校验），再按“落盘后的来源”归一化；{@code dryRun=true} 只预览，不写 store、不换内存；正式路径先 {@link
   * CoreSimos#writeStoreMeta} 成功、再换 volatile 快照。
   *
   * <p>★ 换取入内存的快照按归一化后的 {@code effective} 建：来源标记必须与落盘后的真相一致（否则 apply 后读侧仍报
   * default）。时钟/季界只由数值字段决定，来源归一不影响它们的构造与校验。
   *
   * @param candidate 已由 {@link CalendarConfig} compact 构造器校验收口的新配置
   * @param dryRun true = 只预览：不写 store、不动 snapshot
   * @param atTick 预览/返回日期所用的 tick
   * @param at 预览/返回季节所用的格子坐标
   * @throws IllegalStateException {@code core == null} 时的正式 apply（{@link #defaults()} 造的服务）
   */
  public synchronized ApplyResult apply(
      CalendarConfig candidate, boolean dryRun, long atTick, HexCoord at) {
    Objects.requireNonNull(candidate, "candidate");
    Objects.requireNonNull(at, "at");
    // 正式/预览统一归一成“落盘后的来源”：配置全量写盘 ⇒ 历法/季节 = store；分带未配置 = fallback。
    CalendarConfig effective =
        candidate.withSources(
            CalendarConfig.Source.STORE,
            CalendarConfig.Source.STORE,
            candidate.bandsConfigured()
                ? CalendarConfig.Source.STORE
                : CalendarConfig.Source.FALLBACK);
    Snapshot next = snapshotOf(effective);
    CalendarDate date = next.clock().dateOfTick(atTick);
    SeasonState season = next.seasonSystem().seasonOf(next.clock().dayNumberOfTick(atTick), at);
    List<String> warnings = warnings(candidate, dryRun);
    if (dryRun) {
      return new ApplyResult(false, effective, date, season, warnings);
    }
    if (core == null) {
      throw new IllegalStateException("CalendarService.defaults() 未绑定 CoreSimos，不能正式 apply");
    }
    // ★ 先落盘：写失败（core 抛）会在这里逃出，内存快照保持旧值；成功后才换引用。
    core.writeStoreMeta(STORE_META_KEY, serialize(effective));
    snapshot = next;
    return new ApplyResult(true, effective, date, season, warnings);
  }

  /** 来源：{@code apply} 返回的结果；{@code warnings} 已用 {@link List#copyOf} 防外泄可变表。 */
  public record ApplyResult(
      boolean applied,
      CalendarConfig config,
      CalendarDate date,
      SeasonState season,
      List<String> warnings) {

    public ApplyResult {
      Objects.requireNonNull(config, "config");
      Objects.requireNonNull(date, "date");
      Objects.requireNonNull(season, "season");
      warnings = List.copyOf(warnings);
    }
  }

  /** 锚点变更警告（必含）+ 分带未配置警告（按需）；{@code candidate} 与 effective 的数值字段相同，用它判分带是否配置。 */
  private List<String> warnings(CalendarConfig candidate, boolean dryRun) {
    List<String> warnings = new ArrayList<>(2);
    String oldText = config().epochText();
    String newText = candidate.epochText();
    if (Objects.equals(config().epoch(), candidate.epoch())) {
      warnings.add(
          "锚点变更会让所有历史显示日期整体平移：epoch "
              + newText
              + (dryRun ? " 未变更；dryRun 仅预览，不落盘、不换内存快照" : "（未变更）"));
    } else {
      warnings.add(
          "锚点变更会让所有历史显示日期整体平移：epoch "
              + oldText
              + " → "
              + newText
              + (dryRun ? "（dryRun 仅预览，不落盘、不换内存快照）" : ""));
    }
    if (!candidate.bandsConfigured()) {
      warnings.add("分带未配置（northMax/southMin 都是 null）：全球按北半球四季处理，zoneSource=fallback。");
    }
    return warnings;
  }

  /** 手工构造设计稿 §七的 JSON 形状；{@code epoch} 用 y-m-d 文本，枚举用 {@code name()}，分带 null 写 {@code null}。 */
  private static String serialize(CalendarConfig config) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("version", config.version());
    node.put("calendar", config.calendar());
    node.put("epoch", config.epochText());
    node.put("seasonBoundary", config.seasonBoundary().name());
    node.put("tropicalModel", config.tropicalModel().name());
    node.put("tropicalRainyStartLongitude", config.tropicalRainyStartLongitude());
    node.put("tropicalRainyEndLongitude", config.tropicalRainyEndLongitude());
    node.put("northIsNegative", config.northIsNegative());
    if (config.northMax() == null) {
      node.putNull("northMax");
    } else {
      node.put("northMax", config.northMax().longValue());
    }
    if (config.southMin() == null) {
      node.putNull("southMin");
    } else {
      node.put("southMin", config.southMin().longValue());
    }
    try {
      return MAPPER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("序列化 store_meta.calendar 失败", e);
    }
  }

  /**
   * 解析并校验 {@code store_meta.calendar}：只认固定字段集，出现未知字段 ⇒ IAE；缺字段用缺省；字段存在即“显式”——
   * null/类型不对/blank/枚举值不认一律 IAE（消息含字段名与允许值）。
   */
  private static CalendarConfig parse(String json) {
    JsonNode root;
    try {
      root = MAPPER.readTree(json);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException(
          "store_meta.calendar 不是合法 JSON：" + e.getOriginalMessage(), e);
    }
    if (root == null || !root.isObject()) {
      throw new IllegalArgumentException("store_meta.calendar 必须是 JSON 对象");
    }
    for (Iterator<String> fields = root.fieldNames(); fields.hasNext(); ) {
      String field = fields.next();
      if (!JSON_FIELDS.contains(field)) {
        throw new IllegalArgumentException(
            "store_meta.calendar 含未知字段（防拼错静默回落）：" + field + "；允许字段=" + JSON_FIELDS);
      }
    }

    int version =
        root.has("version")
            ? requireInt(root.get("version"), "version", "只认 " + CalendarConfig.VERSION_1)
            : CalendarConfig.VERSION_1;
    String calendar =
        root.has("calendar")
            ? requireText(root.get("calendar"), "calendar", JulianCalendar.ID)
            : CalendarDefaults.CALENDAR_ID;
    CalendarDate epoch =
        root.has("epoch") ? parseEpoch(root.get("epoch")) : CalendarDefaults.EPOCH_DATE;
    SeasonBoundary seasonBoundary =
        root.has("seasonBoundary")
            ? requireEnum(root.get("seasonBoundary"), "seasonBoundary", SeasonBoundary.class)
            : CalendarDefaults.SEASON_BOUNDARY;
    TropicalModel tropicalModel =
        root.has("tropicalModel")
            ? requireEnum(root.get("tropicalModel"), "tropicalModel", TropicalModel.class)
            : CalendarDefaults.TROPICAL_MODEL;
    double rainyStart =
        root.has("tropicalRainyStartLongitude")
            ? requireDouble(root.get("tropicalRainyStartLongitude"), "tropicalRainyStartLongitude")
            : CalendarDefaults.TROPICAL_RAINY_START_LONGITUDE;
    double rainyEnd =
        root.has("tropicalRainyEndLongitude")
            ? requireDouble(root.get("tropicalRainyEndLongitude"), "tropicalRainyEndLongitude")
            : CalendarDefaults.TROPICAL_RAINY_END_LONGITUDE;
    boolean northIsNegative =
        root.has("northIsNegative")
            ? requireBoolean(root.get("northIsNegative"), "northIsNegative")
            : CalendarDefaults.NORTH_IS_NEGATIVE;
    Long northMax =
        root.has("northMax") ? requireNullableLong(root.get("northMax"), "northMax") : null;
    Long southMin =
        root.has("southMin") ? requireNullableLong(root.get("southMin"), "southMin") : null;

    CalendarConfig.Source calendarSource =
        (root.has("calendar") || root.has("epoch"))
            ? CalendarConfig.Source.STORE
            : CalendarConfig.Source.DEFAULT;
    CalendarConfig.Source seasonSource =
        SEASON_FIELDS.stream().anyMatch(root::has)
            ? CalendarConfig.Source.STORE
            : CalendarConfig.Source.DEFAULT;
    CalendarConfig.Source zoneSource =
        northMax != null ? CalendarConfig.Source.STORE : CalendarConfig.Source.FALLBACK;
    return new CalendarConfig(
        version,
        calendar,
        epoch,
        seasonBoundary,
        tropicalModel,
        rainyStart,
        rainyEnd,
        northIsNegative,
        northMax,
        southMin,
        calendarSource,
        seasonSource,
        zoneSource);
  }

  /** epoch：只认 {@code y-m-d} 文本（覆盖全串，防 "1445-1-1-x" 半匹配）；月内合法性由 CalendarConfig 构造器查。 */
  private static CalendarDate parseEpoch(JsonNode node) {
    String text = requireText(node, "epoch", "y-m-d 文本，如 1445-01-01");
    Matcher matcher = EPOCH_PATTERN.matcher(text);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("epoch 必须匹配 y-m-d（允许负年；如 1445-01-01）：" + text);
    }
    try {
      return new CalendarDate(
          Long.parseLong(matcher.group(1)),
          Integer.parseInt(matcher.group(2)),
          Integer.parseInt(matcher.group(3)));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "epoch 非法（必须是 y-m-d，如 1445-01-01）：" + text + "（" + e.getMessage() + "）", e);
    }
  }

  /** 整型字段：必须是 integral number 且能装进 int；否则 IAE（消息含字段名与允许值）。 */
  private static int requireInt(JsonNode node, String field, String allowed) {
    if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
      throw new IllegalArgumentException(field + " 必须是整型数字（" + allowed + "）：" + node);
    }
    return node.intValue();
  }

  /** 文本字段：类型必须是 textual 且非 blank；否则 IAE（消息含字段名与允许值）。 */
  private static String requireText(JsonNode node, String field, String allowed) {
    if (node == null || !node.isTextual() || node.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " 必须是非空文本（允许：" + allowed + "）：" + node);
    }
    return node.textValue();
  }

  /** 数字字段：类型必须是 number（显式 null 也拒）；范围/次序由 CalendarConfig 构造器统一查。 */
  private static double requireDouble(JsonNode node, String field) {
    if (node == null || !node.isNumber()) {
      throw new IllegalArgumentException(field + " 必须是数字：");
    }
    return node.doubleValue();
  }

  /** 布尔字段：类型必须是 boolean（显式 null 也拒）。 */
  private static boolean requireBoolean(JsonNode node, String field) {
    if (node == null || !node.isBoolean()) {
      throw new IllegalArgumentException(field + " 必须是布尔值：");
    }
    return node.booleanValue();
  }

  /** 枚举字段：必须是 textual 且枚举值可认；IAE 消息带字段名与全部允许值。 */
  private static <E extends Enum<E>> E requireEnum(JsonNode node, String field, Class<E> type) {
    String allowed = Arrays.toString(type.getEnumConstants());
    String text = requireText(node, field, allowed);
    try {
      return Enum.valueOf(type, text);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(field + " 不是合法枚举值（允许：" + allowed + "）：" + text, e);
    }
  }

  /** 可空分带界限：null 合法（明确“未配置”）；数字必须是 integral 且能装进 long（1.5 之类 IAE）。 */
  private static Long requireNullableLong(JsonNode node, String field) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (!node.isIntegralNumber() || !node.canConvertToLong()) {
      throw new IllegalArgumentException(field + " 必须是整型 long 或 null（字段显式 null = 分带未配置）：" + node);
    }
    return node.longValue();
  }
}

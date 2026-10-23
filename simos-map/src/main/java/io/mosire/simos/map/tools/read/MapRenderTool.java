package io.mosire.simos.map.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.map.tools.MapRenderPort;
import io.mosire.simos.map.tools.MapToolViews;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code simos.map.render}：把世界<b>渲染成图</b>——图片随工具结果一并出站（MCP 的 {@code ImageContent} / 决策人的图片分片），
 * 文本摘要永远在。
 *
 * <p>语义对齐 GSimulator 的字符查看器：中心 + 半径（1..10）；本工具在此之上给出 {@code format}：
 *
 * <ul>
 *   <li>{@code image}（缺省 {@code auto} = 有视觉能力就发图）：出 PNG 工件；
 *   <li>{@code text}：字符图（每格一字符 + 图例）——无视觉能力模型的回落形态，也给"只想看一眼"的调用方。
 * </ul>
 *
 * <p>★ 视野只能按<b>中心</b>或<b>区域</b>给：不给就 {@code BAD_REQUEST}——静默给个"世界中心"会让模型以为看到的是它问的那块。
 *
 * <p>★ <b>2026-10-12 B3 下放</b>：本类自 {@code app/tools/read} 迁入 {@code simos-map}，对 app/core
 * 的直连换成构造期注入 （读口 + 视图口 + 渲染口 {@link MapRenderPort}——渲染服务/工件库/token 预算都在 app，地图模块不做存储）；
 * 参数缺省、图层校验文案、载荷字段逐字不变。
 */
public final class MapRenderTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.map.render";

  /**
   * **宿主编排配置的键：调用者的模型有没有视觉能力**（P4，2026-09-24）——它决定 {@code format=auto} 的落点。
   *
   * <p>★★ **为什么这个事实走 {@code ToolContext.config} 而不是写进别处**：它是**部署事实**（这条路由的模型有没有眼睛）， 既不是世界状态（不能落
   * revision），也不是永久不变量（不能钉进决策人的开场消息里——改配置就成了一句谎，而且没有任何症状）。 AgentLib 的 {@code ToolContext.config}
   * 正是给"宿主此刻怎么部署的"准备的通道，由决策人运行流按**该决策人绑的路由**逐次注入。
   *
   * <p>★ **键缺席 = 出图**（历史行为）：其它调用点（GUI 读、用例、MCP 面）不注这个键，故它们看到的形态逐字不变。
   */
  public static final String VISION_CONFIG_KEY = "vision";

  /** 缺省半径（4 ⇒ 61 格：看图足够、token 友好）。 */
  private static final int DEFAULT_RADIUS = 4;

  /**
   * 缺省图层名（与今天的 {@code EnumSet.of(TERRAIN, REGIONS, CITIES, UNITS)} 同集合）：交给渲染口按既有词表解析
   * （解析只有一处，未知名照样响亮拒绝）。
   */
  private static final List<String> DEFAULT_LAYER_NAMES =
      List.of("terrain", "regions", "cities", "units");

  private final ToolReadPort reads;
  private final MapToolViews views;
  private final MapRenderPort render;

  public MapRenderTool(ToolReadPort reads, MapToolViews views, MapRenderPort render) {
    this.reads = Objects.requireNonNull(reads, "reads");
    this.views = Objects.requireNonNull(views, "views");
    this.render = Objects.requireNonNull(render, "render");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "把世界渲染成图：中心(q,r) 或 regionId + radius(1-10)，可选图层 terrain/regions/cities/units/population；"
        + "format=auto|image|text（auto 缺省：你的模型能看图就出图，不能就给你一张字符图；"
        + "text 恒为字符图）。图片随结果一并提供，文本摘要永远在。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSchema.targetProps();
    props.put("q", ToolSchema.prop("integer", "中心六角列坐标 q（与 r 同时给；也可改用 regionId）"));
    props.put("r", ToolSchema.prop("integer", "中心六角行坐标 r"));
    props.put("regionId", ToolSchema.prop("string", "按区域取景（取该区域最北偏西的一格作中心）"));
    props.put("radius", ToolSchema.prop("integer", "半径 1..10（轴向距离；缺省 " + DEFAULT_RADIUS + "）"));
    props.put(
        "layers",
        ToolSchema.prop("array", "图层，可多选：terrain / regions / cities / units / population（缺省前四者）"));
    props.put("width", ToolSchema.prop("integer", "画布宽 64..1024（缺省 768）"));
    props.put("height", ToolSchema.prop("integer", "画布高 64..1024（缺省 768）"));
    props.put(
        "format", ToolSchema.prop("string", "auto（缺省：按你的模型有没有视觉能力选图/字符图）| image | text（字符图）"));
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    // 一次取景同时读 map（地形/区域）、social（城市/人口）、unit（单位位置）——按"全读"声明最诚实
    return ToolResources.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      SimulationState state = MapToolReads.state(reads, args);
      GameMap map = MapToolReads.gameMap(state);

      HexCoord center = resolveCenter(args, map);
      int radius = resolveRadius(args);
      String format = resolveFormat(args, context.config());

      if ("text".equals(format)) {
        MapRenderPort.Rendered rendered =
            render.renderText(
                MapToolReads.branch(args), MapToolReads.revision(args), center, radius);
        Map<String, Object> view = view(center, radius, "text", rendered);
        return ToolResults.ok(view);
      }

      MapRenderPort.Rendered rendered =
          render.renderImage(
              MapToolReads.branch(args),
              MapToolReads.revision(args),
              center,
              radius,
              resolveLayers(args),
              ToolSchema.optionalLong(args, "width"),
              ToolSchema.optionalLong(args, "height"));
      Map<String, Object> view = view(center, radius, "image", rendered);
      // ★ 文本摘要 + 图片资产（assetId）：MCP 面把它出成 ImageContent，决策人面把它附成图片分片
      return ToolResult.ok(ToolResults.json(view), List.of(rendered.assetId().orElseThrow()));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  // ---------- 参数 ----------

  private HexCoord resolveCenter(Map<String, Object> args, GameMap map) {
    Long q = ToolSchema.optionalLong(args, "q");
    Long r = ToolSchema.optionalLong(args, "r");
    String regionId = ToolSchema.optionalText(args, "regionId", null);
    boolean byCoord = q != null || r != null;
    if (byCoord && regionId != null) {
      throw new IllegalArgumentException("q/r 与 regionId 只能给一个（同时给无法判断以谁为准）");
    }
    if (byCoord) {
      if (q == null || r == null) {
        throw new IllegalArgumentException("q 与 r 必须同时给");
      }
      HexCoord coord = new HexCoord((int) (long) q, (int) (long) r);
      if (!map.hexes().containsKey(coord)) {
        // ★ 坐标串取 HexCoord.toString()（L6：坐标串只有一份实现），文案逐字不变。
        throw new IllegalArgumentException("中心格不存在: " + coord);
      }
      return coord;
    }
    if (regionId != null) {
      Region region = map.regions().get(new RegionId(regionId));
      if (region == null) {
        throw new IllegalArgumentException("区域不存在: " + regionId);
      }
      return labelHex(region);
    }
    throw new IllegalArgumentException("必须给中心：q + r，或 regionId");
  }

  /**
   * 区域的"取景中心"：最北（r 最小）偏西（q 最小）的一格——确定性、可预期，且落在区域里。
   *
   * <p>★ **公开给"开场快照"复用**（P4）：决策人首轮那张"我的国土"图按 {@code homeRegion} 取景，必须与按 {@code regionId}
   * 调本工具**落在同一个中心格**上——否则人（与模型）看到的图与它自己再调一次得到的图会是两个取景。
   */
  public static HexCoord labelHex(Region region) {
    return region.hexes().stream()
        .min(Comparator.comparingInt(HexCoord::r).thenComparingInt(HexCoord::q))
        .orElseThrow(() -> new IllegalArgumentException("区域没有格: " + region.id().value()));
  }

  private static int resolveRadius(Map<String, Object> args) {
    Long radius = ToolSchema.optionalLong(args, "radius");
    return radius == null ? DEFAULT_RADIUS : (int) (long) radius;
  }

  /** 图层名（{@code layers} 的列表形态与 {@code "terrain,units"} 字符串形态都容忍；解析/拒绝在渲染口）。 */
  private static List<String> resolveLayers(Map<String, Object> args) {
    Object raw = args.get("layers");
    if (raw == null) {
      return DEFAULT_LAYER_NAMES;
    }
    List<String> names = new ArrayList<>();
    if (raw instanceof List<?> list) {
      for (Object item : list) {
        names.add(String.valueOf(item));
      }
    } else {
      // 也容忍 "terrain,units" 这种字符串形态（LLM 常这么写）
      for (String piece : String.valueOf(raw).split(",")) {
        names.add(piece);
      }
    }
    return names;
  }

  /**
   * 形态选择：{@code auto} = **宿主编排说这个模型有视觉能力就出图、否则回落字符图**（见 {@link #VISION_CONFIG_KEY}）。
   *
   * <p>★★ **为什么 {@code auto} 必须真的看能力**（P4，2026-09-24）：它原先无条件出图 ⇒ 绑在无视觉能力模型上的决策人调一次 {@code
   * auto}，拿到的是一句"PNG 已生成（assetId=…）"——**几乎没有信息量**，而它本可以拿到一张字符图。这正是"回落形态"
   * 存在的意义：不看能力位就等于把一个只会看文本的调用者引到空手而归。
   *
   * <p>★ **显式 {@code image}/{@code text} 一律照办**（能力位不覆盖调用者的明确要求）：调用方要什么给什么，选错是它的事。
   */
  private static String resolveFormat(Map<String, Object> args, Map<String, Object> config) {
    String format = ToolSchema.optionalText(args, "format", "auto").trim().toLowerCase(Locale.ROOT);
    return switch (format) {
      case "auto" -> visionEnabled(config) ? "image" : "text";
      case "image" -> "image";
      case "text" -> "text";
      default -> throw new IllegalArgumentException("format 只能是 auto / image / text: " + format);
    };
  }

  /** 宿主编排注入的视觉能力；**键缺席 ⇒ true**（其它调用点的历史行为是出图，见 {@link #VISION_CONFIG_KEY}）。 */
  private static boolean visionEnabled(Map<String, Object> config) {
    Object vision = config.get(VISION_CONFIG_KEY);
    if (vision instanceof Boolean enabled) {
      return enabled;
    }
    return true;
  }

  private Map<String, Object> view(
      HexCoord center, int radius, String format, MapRenderPort.Rendered rendered) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("center", views.hexCoord(center));
    view.put("radius", radius);
    view.put("format", format);
    view.put("summary", rendered.text());
    if (rendered.assetId().isPresent()) {
      view.put("assetId", rendered.assetId().get());
      view.put("byteSize", rendered.byteSize());
      view.put("width", rendered.width());
      view.put("height", rendered.height());
    }
    return view;
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.DM, ToolRole.GM);
  }
}

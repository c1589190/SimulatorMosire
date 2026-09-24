package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.render.RenderLayer;
import io.mosire.simos.app.render.RenderRequest;
import io.mosire.simos.app.render.RenderService;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.Region;
import io.mosire.simos.map.region.RegionId;
import io.mosire.simos.util.state.SimulationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
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
 */
public final class MapRenderTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.map.render";

  /** 缺省半径（4 ⇒ 61 格：看图足够、token 友好）。 */
  private static final int DEFAULT_RADIUS = 4;

  private static final Set<RenderLayer> DEFAULT_LAYERS =
      EnumSet.of(RenderLayer.TERRAIN, RenderLayer.REGIONS, RenderLayer.CITIES, RenderLayer.UNITS);

  private final QueryService query;
  private final RenderService render;
  private final String mapId;

  public MapRenderTool(QueryService query, RenderService render, String mapId) {
    this.query = Objects.requireNonNull(query, "query");
    this.render = Objects.requireNonNull(render, "render");
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "把世界渲染成图：中心(q,r) 或 regionId + radius(1-10)，可选图层 terrain/regions/cities/units/population；"
        + "format=auto|image|text（text = 字符图回落）。图片随结果一并提供，文本摘要永远在。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSupport.targetProps();
    props.put("q", ToolSupport.prop("integer", "中心六角列坐标 q（与 r 同时给；也可改用 regionId）"));
    props.put("r", ToolSupport.prop("integer", "中心六角行坐标 r"));
    props.put("regionId", ToolSupport.prop("string", "按区域取景（取该区域最北偏西的一格作中心）"));
    props.put("radius", ToolSupport.prop("integer", "半径 1..10（轴向距离；缺省 " + DEFAULT_RADIUS + "）"));
    props.put(
        "layers",
        ToolSupport.prop("array", "图层，可多选：terrain / regions / cities / units / population（缺省前四者）"));
    props.put("width", ToolSupport.prop("integer", "画布宽 64..1024（缺省 768）"));
    props.put("height", ToolSupport.prop("integer", "画布高 64..1024（缺省 768）"));
    props.put("format", ToolSupport.prop("string", "auto（缺省，出图）| image | text（字符图）"));
    return ToolSupport.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    // 一次取景同时读 map（地形/区域）、social（城市/人口）、unit（单位位置）——按"全读"声明最诚实
    return ToolSupport.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      QueryTarget target = ToolSupport.target(args, ToolSupport.DEFAULT_BRANCH);
      SimulationState state = query.stateAt(target);
      GameMap map = ToolSupport.gameMap(state);

      HexCoord center = resolveCenter(args, map);
      int radius = resolveRadius(args);
      String format = resolveFormat(args);

      if ("text".equals(format)) {
        RenderService.Rendered rendered = render.renderText(target, center, radius);
        Map<String, Object> view = view(center, radius, "text", rendered);
        return ToolSupport.ok(view);
      }

      RenderRequest request =
          new RenderRequest(
              center,
              radius,
              resolveLayers(args),
              resolveSide(args, "width"),
              resolveSide(args, "height"));
      RenderService.Rendered rendered = render.renderImage(target, request);
      Map<String, Object> view = view(center, radius, "image", rendered);
      // ★ 文本摘要 + 图片资产（assetId）：MCP 面把它出成 ImageContent，决策人面把它附成图片分片
      return ToolResult.ok(ToolSupport.json(view), List.of(rendered.assetId().orElseThrow()));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  // ---------- 参数 ----------

  private HexCoord resolveCenter(Map<String, Object> args, GameMap map) {
    Long q = ToolSupport.optionalLong(args, "q");
    Long r = ToolSupport.optionalLong(args, "r");
    String regionId = ToolSupport.optionalText(args, "regionId", null);
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
        throw new IllegalArgumentException("中心格不存在: " + coord.q() + "_" + coord.r());
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

  /** 区域的"取景中心"：最北（r 最小）偏西（q 最小）的一格——确定性、可预期，且落在区域里。 */
  static HexCoord labelHex(Region region) {
    return region.hexes().stream()
        .min(Comparator.comparingInt(HexCoord::r).thenComparingInt(HexCoord::q))
        .orElseThrow(() -> new IllegalArgumentException("区域没有格: " + region.id().value()));
  }

  private static int resolveRadius(Map<String, Object> args) {
    Long radius = ToolSupport.optionalLong(args, "radius");
    return radius == null ? DEFAULT_RADIUS : (int) (long) radius;
  }

  private static int resolveSide(Map<String, Object> args, String name) {
    Long side = ToolSupport.optionalLong(args, name);
    return side == null ? RenderRequest.DEFAULT_SIDE : (int) (long) side;
  }

  private static Set<RenderLayer> resolveLayers(Map<String, Object> args) {
    Object raw = args.get("layers");
    if (raw == null) {
      return DEFAULT_LAYERS;
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
    return RenderLayer.parseAll(names);
  }

  private static String resolveFormat(Map<String, Object> args) {
    String format =
        ToolSupport.optionalText(args, "format", "auto").trim().toLowerCase(Locale.ROOT);
    return switch (format) {
      case "auto", "image" -> "image";
      case "text" -> "text";
      default -> throw new IllegalArgumentException("format 只能是 auto / image / text: " + format);
    };
  }

  private static Map<String, Object> view(
      HexCoord center, int radius, String format, RenderService.Rendered rendered) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("center", ToolSupport.hexCoord(center));
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
}

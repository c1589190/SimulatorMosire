package io.mosire.simos.map.tools.read;

import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.ga.spi.GmOnlyRead;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolReadPort;
import io.mosire.simos.ga.spi.ToolResources;
import io.mosire.simos.ga.spi.ToolResults;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.reference.MapReferenceService;
import io.mosire.simos.map.reference.MapReferenceService.Summary;
import io.mosire.simos.map.reference.MapReferenceViews;
import io.mosire.simos.util.state.SimulationState;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code simos.map.reference}：GM 读取已共享的参考底图（概览或带坐标的局部原图）。
 *
 * <p>★ <b>GM-only 读口</b>：参考图是宿主输入工件（原图 + 格网），不进入 DM 视野；实现 {@link GmOnlyRead} 且 {@link #toolRoles()}
 * 只声明 GM，两道声明各自都足以把它挡在决策人桶外。
 *
 * <p>★ <b>图片经既有工件链出站</b>：PNG 由 {@link MapReferenceService} 发布成内容寻址 assetId，工具结果把 assetId 放进 {@code
 * assetIds}（MCP {@code ImageContent} / 决策人图片分片），文本里**不放 base64**。
 *
 * <p>★ <b>文本可定位</b>：返回真实 {@code branch}/{@code revision}、参考图尺寸/格网范围，以及局部的逐格
 * q/r、是否已建格、当前地形与高度（概览不塞几千格文本）。
 */
public final class MapReferenceTool implements SimosTool, GmOnlyRead {

  public static final String NAME = "simos.map.reference";

  private final ToolReadPort reads;
  private final MapReferenceService references;

  public MapReferenceTool(ToolReadPort reads, MapReferenceService references) {
    this.reads = Objects.requireNonNull(reads, "reads");
    this.references = Objects.requireNonNull(references, "references");
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "GM 查看已共享的参考底图：缺 referenceId 列出全部参考工件；给 referenceId 看整图概览；"
        + "再给中心 q/r + radius(1..8) 看局部原图，图上叠加六角边界与 q/r。"
        + "terrainOverlay=true 叠当前地形。图片随结果出站；文本含实际 branch/revision 与局部格状态。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = ToolSchema.targetProps();
    props.put("referenceId", ToolSchema.prop("string", "参考图 id（sha256）；缺省 = 列出参考工件"));
    props.put("q", ToolSchema.prop("integer", "局部中心 q（与 r 同时给；缺省 = 概览）"));
    props.put("r", ToolSchema.prop("integer", "局部中心 r"));
    props.put(
        "radius",
        ToolSchema.prop(
            "integer",
            "局部半径 "
                + MapReferenceService.MIN_RADIUS
                + ".."
                + MapReferenceService.MAX_RADIUS
                + "（缺省 "
                + MapReferenceService.DEFAULT_RADIUS
                + "）"));
    props.put("terrainOverlay", ToolSchema.prop("boolean", "是否叠加当前地形（缺省 false）"));
    return ToolSchema.schema(props, List.of());
  }

  @Override
  public ResourceManifest resources() {
    return ToolResources.ALL_READ;
  }

  @Override
  public ToolResult execute(ToolContext context) {
    try {
      Map<String, Object> args = context.arguments();
      SimulationState state = MapToolReads.state(reads, args);
      String branch = state.meta().ref().branch().value();
      long revision = state.meta().ref().revision().value();
      String referenceId = ToolSchema.optionalText(args, "referenceId", null);
      if (referenceId == null) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("branch", branch);
        view.put("revision", revision);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Summary summary : references.list()) {
          entries.add(MapReferenceViews.summary(summary));
        }
        view.put("references", entries);
        return ToolResults.ok(view);
      }
      Integer q = optionalInt(args, "q");
      Integer r = optionalInt(args, "r");
      Integer radius = optionalInt(args, "radius");
      boolean overlay = ToolSchema.optionalBoolean(args, "terrainOverlay").orElse(false);
      GameMap map = MapToolReads.gameMap(state);
      MapReferenceService.View result = references.view(referenceId, q, r, radius, overlay, map);
      Map<String, Object> view = new LinkedHashMap<>();
      view.put("branch", branch);
      view.put("revision", revision);
      view.putAll(MapReferenceViews.view(result, overlay));
      return ToolResult.ok(ToolResults.json(view), List.of(result.assetId()));
    } catch (IllegalArgumentException e) {
      return ToolResult.error("BAD_REQUEST", e.getMessage());
    }
  }

  /**
   * 可选整数参数：只接受**精确整数**（JSON number 不得带小数、字符串走 Integer.parseInt），且必须在 int 范围内。
   *
   * <p>★ 不复用 {@link ToolSchema#optionalLong}：它对 Number 走 {@code longValue()}，会把 1.5 截成 1；新工具不能悄悄收下
   * 小数坐标。也不改 ToolSchema——那会影响旧工具的参数语义。
   */
  private static Integer optionalInt(Map<String, Object> args, String name) {
    Object value = args.get(name);
    if (value == null) {
      return null;
    }
    if (value instanceof Integer integer) {
      return integer;
    }
    if (value instanceof Long longValue) {
      if (longValue < Integer.MIN_VALUE || longValue > Integer.MAX_VALUE) {
        throw new IllegalArgumentException("参数 " + name + " 超出 int 范围: " + longValue);
      }
      return (int) (long) longValue;
    }
    if (value instanceof Number number) {
      // 用 BigDecimal 的精确 int 转换：不做 round/截断，小数、非有限与 int 越界都抛。
      try {
        return new BigDecimal(number.toString()).intValueExact();
      } catch (NumberFormatException | ArithmeticException e) {
        throw new IllegalArgumentException("参数 " + name + " 必须是 int 范围内的精确整数: " + number, e);
      }
    }
    if (value instanceof String text && !text.isBlank()) {
      try {
        return Integer.parseInt(text.trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("参数 " + name + " 必须是整数: " + text);
      }
    }
    throw new IllegalArgumentException("参数 " + name + " 必须是整数");
  }

  /** 只在 GM 桶；装配期与 {@link GmOnlyRead} 交叉校验。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}

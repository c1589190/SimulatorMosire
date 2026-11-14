package io.mosire.simos.map.tools.write;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolSchema;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code map.PaintTerrain} 的结构化窄工具：GM 直接、原子地把一批格改成可用地形。
 *
 * <p>★ <b>顶层结构化参数</b>：{@code cells:[{q,r,terrain,height?}]}（{@code branch}/{@code expectedRevision}
 * 仍走信封）。 这是“工具调用自由、直接生效”的入口——没有提案/不确定/复核步骤，也不需要先识别底图。
 *
 * <p>★ <b>校验全在域层</b>：1..12000、已存在格、唯一坐标、词表内地形、height 0..1/缺省取地形中值，由 {@code PaintTerrainHandler} +
 * {@code RasterImportOperations} 整批校验后一次提交；工具层不复制第二套判据。
 */
public final class MapPaintTerrainTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "map.PaintTerrain";

  public MapPaintTerrainTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "直接刷地形 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 直接改一批已存在格的地形（原子，一次一条命令）：cells=[{q,r,terrain,height?}]，1..12000 格；"
        + "height 缺省取该地形标准编辑高度中值；未知地形/重复/图外/非法高度整批拒绝。保留土地树与其他组件。";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    Map<String, Object> props = new LinkedHashMap<>();
    props.put("branch", ToolSchema.prop("string", "分支名"));
    props.put("expectedRevision", ToolSchema.prop("integer", "期望的 base revision（乐观并发）"));
    Map<String, Object> item = ToolSchema.prop("object", "一格目标地形");
    Map<String, Object> itemProps = new LinkedHashMap<>();
    itemProps.put("q", ToolSchema.prop("integer", "六角列坐标 q"));
    itemProps.put("r", ToolSchema.prop("integer", "六角行坐标 r"));
    itemProps.put("terrain", ToolSchema.prop("string", "地形 key（当前地图标准词表）"));
    itemProps.put("height", ToolSchema.prop("number", "可选高度 0..1；缺省取该地形标准编辑高度中值"));
    item.put("properties", itemProps);
    item.put("required", List.of("q", "r", "terrain"));
    Map<String, Object> cells = ToolSchema.prop("array", "要直接刷写的格，1..12000");
    cells.put("items", item);
    props.put("cells", cells);
    return ToolSchema.schema(props, List.of("branch", "expectedRevision", "cells"));
  }

  @Override
  protected String payloadJson(ToolContext context) {
    Object cells = context.arguments().get("cells");
    if (!(cells instanceof List<?>)) {
      throw new IllegalArgumentException("参数 cells 必填且为 [{q,r,terrain,height?}] 数组");
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("cells", cells);
    return payloadJsonOf(payload);
  }

  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}

package io.mosire.simos.map.reference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 参考图视图的**唯一形状**（MCP 工具与 GUI HTTP 共用，避免两处各拼一份同形 JSON）。
 *
 * <p>本类只做“值 → 字段名/顺序”，不含 base64、不含 branch/revision 游标；游标由调用方补（工具取真实 {@code
 * SimulationState.meta().ref()}，GUI 取同一查询坐标）。工具的文本因此与 GUI 摘要是同一份事实。
 */
public final class MapReferenceViews {

  private MapReferenceViews() {}

  /**
   * 参考工件摘要：{@code
   * referenceId/fileName/mediaType/imageWidth/imageHeight/byteSize/cellCount/gridRange}。
   */
  public static Map<String, Object> summary(MapReferenceService.Summary summary) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("referenceId", summary.referenceId());
    view.put("fileName", summary.fileName());
    view.put("mediaType", summary.mediaType());
    view.put("imageWidth", summary.width());
    view.put("imageHeight", summary.height());
    view.put("byteSize", summary.byteSize());
    view.put("cellCount", summary.cellCount());
    Map<String, Object> range = new LinkedHashMap<>();
    range.put("minQ", summary.minQ());
    range.put("maxQ", summary.maxQ());
    range.put("minR", summary.minR());
    range.put("maxR", summary.maxR());
    view.put("gridRange", range);
    return view;
  }

  /** 一格状态：q/r 是身份，image* 是原图像素几何，built/terrain/height 是当前地图事实。 */
  public static Map<String, Object> cell(MapReferenceService.CellStatus cell) {
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("q", cell.q());
    view.put("r", cell.r());
    view.put("imageX", cell.imageX());
    view.put("imageY", cell.imageY());
    view.put("imageRadius", cell.imageRadius());
    view.put("built", cell.built());
    view.put("terrain", cell.terrain());
    view.put("height", cell.height());
    return view;
  }

  /**
   * 一次查看的完整摘要（不含 base64/游标）：概览/半径、PNG 工件、原图裁切矩形、局部格状态。
   *
   * @param view 服务返回值
   * @param terrainOverlay 调用方请求是否叠了地形（回显请求语义）
   */
  public static Map<String, Object> view(MapReferenceService.View view, boolean terrainOverlay) {
    Map<String, Object> body = summary(view.summary());
    body.put("overview", view.overview());
    body.put("radius", view.overview() ? null : view.radius());
    body.put("terrainOverlay", terrainOverlay);
    Map<String, Object> image = new LinkedHashMap<>();
    image.put("assetId", view.assetId());
    image.put("width", view.width());
    image.put("height", view.height());
    image.put("byteSize", view.png().length);
    body.put("image", image);
    Map<String, Object> crop = new LinkedHashMap<>();
    crop.put("x", view.sourceX());
    crop.put("y", view.sourceY());
    crop.put("width", view.sourceWidth());
    crop.put("height", view.sourceHeight());
    body.put("sourceCrop", crop);
    List<Map<String, Object>> cells = new ArrayList<>(view.cells().size());
    for (MapReferenceService.CellStatus cell : view.cells()) {
      cells.add(cell(cell));
    }
    body.put("cells", cells);
    return body;
  }
}

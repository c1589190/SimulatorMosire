package io.mosire.simos.app.tools.map;

import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.app.render.RenderLayer;
import io.mosire.simos.app.render.RenderRequest;
import io.mosire.simos.app.render.RenderService;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.tools.MapRenderPort;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.List;
import java.util.Objects;

/**
 * {@link MapRenderPort} 的宿主实现（组合根）：把端口入参还原成渲染服务的 {@link RenderRequest} 并转调 {@link
 * RenderService}（渲染缓存、PNG 预算降采样、工件库、文本摘要文案全留在那一处）。
 *
 * <p>★ <b>校验留在原处</b>：图层名的解析与响亮拒绝由 {@link RenderLayer#parseAll} 产出，边长的缺省与范围由 {@link RenderRequest}
 * 的构造期产出——文案因此与搬迁前逐字相同（本类只做"值参 → 请求对象"的转写）。
 */
public final class AppMapRenderPort implements MapRenderPort {

  private final RenderService render;

  public AppMapRenderPort(RenderService render) {
    this.render = Objects.requireNonNull(render, "render");
  }

  @Override
  public Rendered renderText(BranchId branch, RevisionId revision, HexCoord center, int radius) {
    return adapt(render.renderText(targetOf(branch, revision), center, radius));
  }

  @Override
  public Rendered renderImage(
      BranchId branch,
      RevisionId revision,
      HexCoord center,
      int radius,
      List<String> layerNames,
      Long width,
      Long height) {
    RenderRequest request =
        new RenderRequest(
            center,
            radius,
            RenderLayer.parseAll(layerNames),
            width == null ? RenderRequest.DEFAULT_SIDE : (int) (long) width,
            height == null ? RenderRequest.DEFAULT_SIDE : (int) (long) height);
    return adapt(render.renderImage(targetOf(branch, revision), request));
  }

  /** 与工具载荷同义：{@code revision == null} ⇒ 该分支 head。 */
  private static QueryTarget targetOf(BranchId branch, RevisionId revision) {
    Objects.requireNonNull(branch, "branch");
    return revision == null ? QueryTarget.head(branch) : QueryTarget.at(branch, revision);
  }

  private static Rendered adapt(RenderService.Rendered rendered) {
    return new Rendered(
        rendered.text(),
        rendered.assetId(),
        rendered.byteSize(),
        rendered.width(),
        rendered.height());
  }
}

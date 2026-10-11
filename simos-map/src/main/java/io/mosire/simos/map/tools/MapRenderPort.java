package io.mosire.simos.map.tools;

import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.state.BranchId;
import io.mosire.simos.util.state.RevisionId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 地图工具面的**渲染口**（{@code simos.map.render} 专用）：把某坐标的某块视野渲染成字符图 / PNG 工件。
 *
 * <p>★★ <b>为什么它必须注入</b>：渲染要读 map + social + unit 三个切片、要把 PNG 落工件库、要按 token 预算降采样 ——那是 app 的 {@code
 * RenderService}（"同一份字节只渲染一次"的缓存也在它里面）。地图模块**不做存储、不认识 social/unit** （铁律 3 + enforcer）⇒ 由 app
 * 注入实现，且实现逐条转调同一个 {@code RenderService}（缓存/预算/文案因此逐字不变）。
 *
 * <p>★ <b>参数按"工具解析到哪一步"切</b>：工具负责从载荷取值与缺省（{@code radius} 缺省、图层名的列表/逗号两种形态）， 端口负责**校验与构造**（{@code
 * RenderLayer.parseAll} 的响亮拒绝、边长缺省与范围、{@code RenderRequest} 的构造） ——拒绝文案因此仍由原实现那几行产出（I-G1）。
 */
public interface MapRenderPort {

  /**
   * 一次渲染的产物（镜像 app 的 {@code RenderService.Rendered}，字段与含义逐条相同）。
   *
   * @param text 给模型的文本摘要（图是附加的，文本永远在）
   * @param assetId 图片工件 id（字符图/纯文本形态为空）
   * @param byteSize 实际 PNG 字节数（字符图为 0）
   * @param width 实际画布宽（字符图为 0）
   * @param height 实际画布高（字符图为 0）
   */
  record Rendered(String text, Optional<String> assetId, int byteSize, int width, int height) {

    public Rendered {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(assetId, "assetId");
    }
  }

  /** 渲染字符图（无视觉能力模型的回落形态；不产工件）。 */
  Rendered renderText(BranchId branch, RevisionId revision, HexCoord center, int radius);

  /**
   * 渲染图片（自动降采样到预算内、落工件库）。
   *
   * @param branch 目标分支
   * @param revision 版本号；{@code null} = 该分支 head
   * @param center 取景中心
   * @param radius 半径（范围校验在实现侧，与今天同一条）
   * @param layerNames 图层名（已按载荷形态拆好；空表 / 未知名 ⇒ 实现按既有文案响亮拒绝）
   * @param width 画布宽；{@code null} = 既有缺省（{@code RenderRequest.DEFAULT_SIDE}）
   * @param height 画布高；{@code null} = 既有缺省
   */
  Rendered renderImage(
      BranchId branch,
      RevisionId revision,
      HexCoord center,
      int radius,
      List<String> layerNames,
      Long width,
      Long height);
}

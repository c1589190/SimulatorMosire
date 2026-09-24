package io.mosire.simos.app.render;

import io.mosire.simos.app.gui.ApiViews;
import io.mosire.simos.app.query.QueryService;
import io.mosire.simos.app.query.QueryService.QueryTarget;
import io.mosire.simos.map.GameMap;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.util.state.SimulationState;
import java.util.Objects;
import java.util.Optional;

/**
 * 渲染服务：把"某个 revision 的某块视野"变成<b>可发给 LLM 的字节</b>，并把字节放进工件库。
 *
 * <p>三个消费面（决策人 / MCP / GUI）都走这一个入口——"同一份字节只渲染一次"就靠它：键 = revision + 参数指纹， 命中 {@link RenderCache}
 * 就复用（同一 revision 同参数 ⇒ 同一张图 ⇒ 同一个 assetId）。
 *
 * <p>★ <b>预算硬顶</b>（{@link #MAX_PNG_BYTES}）：超限时把边长折半重渲，直到进入预算或触到最小边长。图太大不是"好看点的问题"， 是把一次决策的 token
 * 预算吃光的问题（实测 64×64 小图也要 ~230 prompt tokens）。
 */
public final class RenderService {

  /** 单张 PNG 的字节上限（≈ token 预算的间接闸门；超限自动降采样）。 */
  public static final int MAX_PNG_BYTES = 512 * 1024;

  private final QueryService query;
  private final RenderCache cache;
  private final ArtifactStore artifacts;
  private final int maxPngBytes;

  public RenderService(QueryService query, ArtifactStore artifacts) {
    this(query, new RenderCache(64), artifacts, MAX_PNG_BYTES);
  }

  public RenderService(QueryService query, RenderCache cache, ArtifactStore artifacts) {
    this(query, cache, artifacts, MAX_PNG_BYTES);
  }

  /**
   * 全参组装：{@code maxPngBytes} 可注入——生产用 {@link #MAX_PNG_BYTES}，测试用一个小值就能把"降采样重试"这条路径逼出来
   * （否则那条路径只能靠"真造一张 512KB 的图"来覆盖，脆且慢）。
   */
  public RenderService(
      QueryService query, RenderCache cache, ArtifactStore artifacts, int maxPngBytes) {
    this.query = Objects.requireNonNull(query, "query");
    this.cache = Objects.requireNonNull(cache, "cache");
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    if (maxPngBytes < 1) {
      throw new IllegalArgumentException("maxPngBytes 必须 ≥ 1: " + maxPngBytes);
    }
    this.maxPngBytes = maxPngBytes;
  }

  /**
   * 一次渲染的产物。
   *
   * @param text 给模型的文本摘要（图是附加的，文本永远在）
   * @param assetId 图片工件 id（字符图/纯文本形态为空）
   * @param byteSize 实际 PNG 字节数（字符图为 0）
   * @param width 实际画布宽（字符图为 0）
   * @param height 实际画布高（字符图为 0）
   */
  public record Rendered(
      String text, Optional<String> assetId, int byteSize, int width, int height) {

    public Rendered {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(assetId, "assetId");
    }
  }

  /** 渲染图片视图（自动降采样到预算内），落工件库，返回摘要 + assetId。 */
  public Rendered renderImage(QueryTarget target, RenderRequest request) {
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(request, "request");
    SimulationState state = query.stateAt(target);
    String key = "v1:r" + state.meta().ref().revision().value() + ":" + request.fingerprint();

    Attempt attempt = cacheWithSize(key, state, request);
    String assetId = artifacts.putPng(attempt.png());
    String text =
        "世界视图（PNG "
            + attempt.width()
            + "×"
            + attempt.height()
            + "，"
            + attempt.png().length
            + " 字节）已生成：中心 ("
            + request.center().q()
            + ","
            + request.center().r()
            + ") 半径 "
            + request.radius()
            + "，图层 "
            + request.layers()
            + "（图片随本次结果一并提供；assetId="
            + assetId
            + "）";
    return new Rendered(
        text, Optional.of(assetId), attempt.png().length, attempt.width(), attempt.height());
  }

  /** 渲染字符图（无视觉能力模型的回落形态；不产工件）。 */
  public Rendered renderText(QueryTarget target, HexCoord center, int radius) {
    Objects.requireNonNull(target, "target");
    SimulationState state = query.stateAt(target);
    GameMap map = ApiViews.gameMap(state);
    SocialData social = ApiViews.socialData(state);
    UnitState units = ApiViews.unitState(state);
    String grid =
        MapTextRenderer.render(map, social, units, state.meta().timestamp(), center, radius);
    return new Rendered(grid, Optional.empty(), 0, 0, 0);
  }

  // ---------- 内部 ----------

  private record Attempt(byte[] png, int width, int height) {}

  /**
   * 缓存与尺寸重试的接线：{@link RenderCache} 的键是"revision + 参数指纹"，而降采样会**改变参数**（边长），
   * 所以预算循环必须在缓存<b>外侧**：先按请求的尺寸试一次（命中就省一次渲染），超限才逐级降（每一级各自有自己的键）。
   */
  private Attempt cacheWithSize(String key, SimulationState state, RenderRequest request) {
    Attempt first = cacheAttempt(key, state, request);
    if (first.png().length <= maxPngBytes) {
      return first;
    }
    RenderRequest current = request;
    Attempt attempt = first;
    while (attempt.png().length > maxPngBytes
        && (current.width() > RenderRequest.MIN_SIDE
            || current.height() > RenderRequest.MIN_SIDE)) {
      current =
          new RenderRequest(
              current.center(),
              current.radius(),
              current.layers(),
              Math.max(RenderRequest.MIN_SIDE, current.width() / 2),
              Math.max(RenderRequest.MIN_SIDE, current.height() / 2));
      attempt =
          cacheAttempt(
              "v1:r" + state.meta().ref().revision().value() + ":" + current.fingerprint(),
              state,
              current);
    }
    return attempt;
  }

  private Attempt cacheAttempt(String key, SimulationState state, RenderRequest request) {
    // 缓存里存的是"字节"；尺寸由请求本身决定（同一个键必然同一尺寸），故重算一次尺寸回填即可
    byte[] png = cache.get(key, () -> renderNow(state, request));
    return new Attempt(png, request.width(), request.height());
  }

  private byte[] renderNow(SimulationState state, RenderRequest request) {
    GameMap map = ApiViews.gameMap(state);
    SocialData social = ApiViews.socialData(state);
    UnitState units = ApiViews.unitState(state);
    RenderModel model =
        RenderModelBuilder.build(map, social, units, state.meta().timestamp(), request);
    return MapImageRenderer.renderPng(model);
  }
}

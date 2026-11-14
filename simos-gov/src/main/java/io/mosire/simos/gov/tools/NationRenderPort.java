package io.mosire.simos.gov.tools;

import io.mosire.simos.gov.tools.GovNationViews.NationView;
import java.util.Objects;
import java.util.Optional;

/**
 * 国家读视图的 PNG 渲染口（{@code simos.gov.nations} 专用）。
 *
 * <p>★★ <b>绘制归 gov</b>：国家领土/颜色/名称的绘制实现住 {@code io.mosire.simos.gov.render}，不放在 app 的
 * RenderService；app 只注入内容寻址的 PNG 发布器（与 {@code MapReferenceService.PngPublisher} 同一模式）。工具把同一次 {@code
 * state} 派生出的 {@link NationView} 原样交给它，渲染不再二次查询 head ⇒ 图文同一 snapshot。
 */
public interface NationRenderPort {

  /** 把一张视图 PNG 发布成内容寻址 assetId（生产 = ArtifactStore::putPng）。 */
  @FunctionalInterface
  interface PngPublisher {
    String publish(byte[] png);
  }

  /** 一次渲染的产物（字段与 app 的 RenderService.Rendered 同义）。 */
  record Rendered(String text, Optional<String> assetId, int byteSize, int width, int height) {
    public Rendered {
      Objects.requireNonNull(text, "text");
      Objects.requireNonNull(assetId, "assetId");
    }
  }

  /** 渲染一个国家视图（领土并集、颜色、名称都已在该 view 中）；不重新读状态。 */
  Rendered render(NationView view);
}

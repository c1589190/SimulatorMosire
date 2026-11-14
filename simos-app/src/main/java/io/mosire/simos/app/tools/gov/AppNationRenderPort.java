package io.mosire.simos.app.tools.gov;

import io.mosire.simos.gov.render.NationImageRenderer;
import io.mosire.simos.gov.tools.GovNationViews.NationView;
import io.mosire.simos.gov.tools.NationRenderPort;
import java.util.Objects;
import java.util.function.Function;

/**
 * {@link NationRenderPort} 的宿主实现：绘制在 gov 的 {@link NationImageRenderer}，这里只注入内容寻址的 PNG 写函数。
 * 不重新读状态、不另算国家领土。
 */
public final class AppNationRenderPort implements NationRenderPort {

  private final NationImageRenderer renderer;

  public AppNationRenderPort(Function<byte[], String> putPng) {
    this.renderer = new NationImageRenderer(Objects.requireNonNull(putPng, "putPng")::apply);
  }

  @Override
  public Rendered render(NationView view) {
    return renderer.render(view);
  }
}

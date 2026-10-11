package io.mosire.simos.app.tools.social;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.tools.SocialToolPerspective;

/**
 * {@link SocialToolPerspective} 的宿主实现（组合根）：**可见性判定逐条转调** {@link ToolSupport}——围栏逻辑只有那一份 （{@code
 * social:<q>_<r>} 与 {@code unit:<unitId>} 两条资源路径的唯一拼写点也在那里）。
 *
 * <p>★ 本类对下放工具的行为是**恒等**的：同一个 {@link ToolContext} 与同一个坐标进来，同一个布尔出去。
 */
public final class AppSocialToolPerspective implements SocialToolPerspective {

  @Override
  public boolean populationVisible(ToolContext context, HexCoord coord) {
    return ToolSupport.populationVisible(context, coord);
  }

  @Override
  public boolean unitVisible(ToolContext context, String unitId) {
    return ToolSupport.unitVisible(context, new io.mosire.simos.unit.UnitId(unitId));
  }
}

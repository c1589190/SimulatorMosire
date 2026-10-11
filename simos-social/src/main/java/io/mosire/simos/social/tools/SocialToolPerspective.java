package io.mosire.simos.social.tools;

import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.map.hex.HexCoord;

/**
 * social 工具的**宿主视角口**：可见性围栏（人口格 / 单位）——**唯一实现在 app 的 {@code ToolSupport}**，域侧只声明端口、宿主转调。
 *
 * <p>★★ <b>为什么是注入而不是在域内写第二份</b>：权限围栏**不许抄第二份**（B3 各批的硬纪律）——可见性谓词是"该看见的看不见 /
 * 不该看见的看得见"都不抛异常的那类代码，两处各写一份就必然漂移；且它们的判据跨命名空间（{@code map:<mapId>/hex/<q>_<r>} 与区域级前缀取并集、 {@code
 * social:<q>_<r>}、{@code unit:<unitId>}），只有宿主能把三条路径拼全。
 */
public interface SocialToolPerspective {

  /** 某格的人口是否可见：{@code social:<q>_<r>}（宿主转调 {@code ToolSupport.populationVisible}）。 */
  boolean populationVisible(ToolContext context, HexCoord coord);

  /** 某单位是否可见：{@code unit:<unitId>}（宿主转调 {@code ToolSupport.unitVisible}）。 */
  boolean unitVisible(ToolContext context, String unitId);
}

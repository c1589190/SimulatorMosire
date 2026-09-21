package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code map.UpdateRegion} 窄工具（M1，spec §八.3）：**GM 改区域**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（故 {@code EXTERNAL_WITH_GM} 复合口也含它）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★★ **{@code meta} 是整体替换**：给了 {@code meta} 就把 {@code color}/{@code tag}/{@code description}/{@code
 * annexedBy} 四键**给全**——只给一个键会静默清掉其余三个（域层 {@code RegionMeta} 不是逐键合并）。这条已写进
 * {@link #description()}，让模型在调用前就看得见。
 *
 * <p>★ {@code hexes} 与 {@code meta} **至少给一个**：都不给 ⇒ 域层拒绝（"什么都没改"是一条坏载荷，不是一次无副作用的写）。
 */
public final class MapUpdateRegionTool extends AbstractNarrowWriteTool {

  public static final String NAME = "map.UpdateRegion";

  public MapUpdateRegionTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "改区域 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 改区域：固定 map.UpdateRegion，载荷 {regionId, hexes?, meta?}（至少给一个；★ meta 是整体替换——给了就把"
        + " color/tag/description/annexedBy 四键给全，只给一个键会静默清掉其余三个）";
  }
}

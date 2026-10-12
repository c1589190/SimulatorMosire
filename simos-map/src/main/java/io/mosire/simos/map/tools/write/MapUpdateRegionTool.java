package io.mosire.simos.map.tools.write;

import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.Map;
import java.util.Set;

/**
 * {@code map.UpdateRegion} 窄工具（M1，spec §八.3）：**GM 改区域**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（故运行时 MCP 口（= GM 组）也含它）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★★ **{@code meta} 是整体替换**：给了 {@code meta} 就把 {@code color}/{@code tag}/{@code
 * description}/{@code annexedBy} 四键**给全**——只给一个键会静默清掉其余三个（域层 {@code RegionMeta} 不是逐键合并）。这条已写进
 * {@link #description()}，让模型在调用前就看得见。
 *
 * <p>★ {@code hexes} 与 {@code meta} **至少给一个**：都不给 ⇒ 域层拒绝（"什么都没改"是一条坏载荷，不是一次无副作用的写）。
 *
 * <p>★ <b>2026-10-12 B3 下放</b>：本类自 {@code app/tools/write} 迁入 {@code simos-map}，对 {@code CoreSimos}
 * 的直连换成构造期注入的 {@link ToolWritePort}（命令类型、载荷、拒绝码、资源声明、审批门逐字不变）。
 */
public final class MapUpdateRegionTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "map.UpdateRegion";

  public MapUpdateRegionTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
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

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}

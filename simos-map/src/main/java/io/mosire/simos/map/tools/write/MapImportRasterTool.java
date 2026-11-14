package io.mosire.simos.map.tools.write;

import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.Map;
import java.util.Set;

/** 底图确认结果的 GM 窄写入口，图像不进入工具载荷。 */
public final class MapImportRasterTool extends AbstractNarrowWriteTool implements SimosTool {
  public static final String NAME = "map.ImportRaster";

  public MapImportRasterTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "确认导入底图 branch=" + args.get("branch");
  }

  @Override
  public String description() {
    return "GM 原子导入底图确认格：payloadJson={mode:patch|merge,cells:[{q,r,terrain,height}]}，"
        + "1..12000 格；patch 只改现格，merge 新增及覆盖；保留图外数据。height 是 0..1 归一化值。";
  }

  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}

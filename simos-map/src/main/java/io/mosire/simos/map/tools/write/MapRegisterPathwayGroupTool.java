package io.mosire.simos.map.tools.write;

import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.Map;

/**
 * {@code map.RegisterPathwayGroup} 窄工具（M1，spec §八.3）：**GM 注册通路组**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（故运行时 MCP 口（= GM 组）也含它）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **它改的是组定义、不是边**：注册后 {@code map.SetEdge{kind:…}} 才认这个词；未注册的 {@code kind} 仍被域层 fail-closed
 * 拒绝。{@code color} 必须是 {@code #RRGGBB} 形式（非此形态 ⇒ 域层拒绝，理由原文到达调用方）。
 *
 * <p>★ <b>2026-10-12 B3 下放</b>：本类自 {@code app/tools/write} 迁入 {@code simos-map}，对 {@code CoreSimos}
 * 的直连换成构造期注入的 {@link ToolWritePort}（命令类型、载荷、拒绝码、资源声明、审批门逐字不变）。
 */
public final class MapRegisterPathwayGroupTool extends AbstractNarrowWriteTool {

  public static final String NAME = "map.RegisterPathwayGroup";

  public MapRegisterPathwayGroupTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "注册通路组 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 注册通路组：固定 map.RegisterPathwayGroup，载荷 {id, name, color（#RRGGBB）, description?, visible?（缺省"
        + " true）, properties?}";
  }
}

package io.mosire.simos.unit.tools.write;

import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.Map;
import java.util.Set;

/**
 * {@code unit.PlaceAt} 窄工具（M2，spec §八.3）：**瞬移单位**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★★ **{@code hex} 缺省或为 null = 撤销位置**，**不是**"不动"：这条已写进 {@link #description()}。正常编辑手段是 {@code
 * unit.PlanRoute}，瞬移交由本工具（工作台左键瞬移已于 M7c 取消，本工具是 MCP/agent 面）。
 */
public final class UnitPlaceAtTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "unit.PlaceAt";

  public UnitPlaceAtTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "瞬移单位 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "瞬移单位：固定 unit.PlaceAt，载荷 {id, hex?}（★ hex 缺省或为 null = 撤销位置，不是“不动”）";
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}

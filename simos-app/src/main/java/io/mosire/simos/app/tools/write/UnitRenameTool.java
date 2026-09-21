package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.RenameUnit} 窄工具（M2，spec §八.3）：**改单位名**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷；"选什么命令"不再是可自由发挥的面。标 sensitive ⇒ 经 MCP 调用时走**审批门链**。
 *
 * <p>★ **前置即错**：单位存不存在由域层判（`单位不存在: <id>`），经 {@code ToolSupport.fold} 变成可读的 {@code
 * REJECTED}；工具层不重复校验（那份校验能被 {@code simos.command.submit} 绕过 ⇒ 是装饰）。
 *
 * <p>★ **它是 20 条里唯一的形状异类**：本命令的载荷不走共享解析器，故其载荷层文案自成一族；重名**不拒**（域层没有名字唯一性检查）。
 */
public final class UnitRenameTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.RenameUnit";

  public UnitRenameTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "改单位名 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "单位改名：固定 unit.RenameUnit，载荷 {id, name}";
  }
}

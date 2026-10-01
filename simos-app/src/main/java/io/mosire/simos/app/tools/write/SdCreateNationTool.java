package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.core.CoreSimos;
import java.util.List;
import java.util.Map;

/**
 * {@code sd.CreateNation} 窄工具（M3，spec §八.3）：**建国家**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**（故运行时 MCP 口（= GM 组）也含它）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **{@code homeRegionId} 指向的区域必须带 {@code nation:} 前缀的
 * tag**（R13）——这是建国家的前置，缺它即被域层拒绝，理由原文到达调用方（工具层不重复校验：那份校验能被 {@code simos.command.submit} 绕过 ⇒ 是装饰）。
 */
public final class SdCreateNationTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.CreateNation";

  public SdCreateNationTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "建国家 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "建国家：固定 sd.CreateNation，载荷 {nationId, name, homeRegionId, adminBudgetPerTick}（四者全必填；★ homeRegionId 指向的区域必须**已带 `nation:` 前缀的 tag**，否则被拒——R13）";
  }

  @Override
  public ResourceManifest resources() {
    return SD_NAMESPACE_WRITE;
  }

  /**
   * ★ P0 资源对齐：本工具钉死的 {@code sd.*} 命令只产 {@code SdChangeSet}（实际只写 sd 命名空间），故写断言取 {@code sd:*}（GM 侧
   * unlimited），不再沿用基类的三命名空间粗断言。
   */
  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return sdNamespaceWriteResources();
  }
}

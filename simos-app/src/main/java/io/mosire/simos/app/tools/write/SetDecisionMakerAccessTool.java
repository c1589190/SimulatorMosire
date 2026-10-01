package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.core.CoreSimos;
import java.util.List;
import java.util.Map;

/**
 * {@code sd.SetDecisionMakerAccess} 窄工具（spec §4.2，N9/N11）：**GM 专用**配权写面（标 sensitive，走审批）。
 *
 * <p>★ **取代 {@code sd.SetViewScope}**（用户 2026-09-22 裁定③）：旧工具配的是 GM **绝对指定**的可见集合；本工具配的是 **额外限制**——与
 * app 层范围函数现算的结果做交集（{@code narrowTo}）⇒ GM 只能额外收紧。
 *
 * <p>★ **{@code NAME} 必须是字面量**（与 sd 侧 handler 的 {@code type()} 一样）：两侧各有一条**派生式同源判据**按源码字面量扫描 ——
 * 窄写工具集合 == GM 桶的窄写工具集合（{@code SimosToolsTest}）、catalog 的 type 集合 == 全部 handler 的 {@code
 * type()}。它们才是把三处名字钉在一起的东西（写成常量引用会让扫描器当场判"抽不到"）。
 */
public final class SetDecisionMakerAccessTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.SetDecisionMakerAccess";

  public SetDecisionMakerAccessTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "GM 配权 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 配权：固定 sd.SetDecisionMakerAccess，载荷 {decisionMakerId, allowedTools?, "
        + "accessLimit{命名空间:[前缀…]}?, redactedFields?, adjudicationDisclosure?}";
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

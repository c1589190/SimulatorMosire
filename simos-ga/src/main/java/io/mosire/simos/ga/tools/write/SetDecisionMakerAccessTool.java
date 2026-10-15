package io.mosire.simos.ga.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import io.mosire.simos.ga.tools.GaToolIdentity;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code ga.SetDecisionMakerAccess} 窄工具（spec §4.2，N9/N11）：**GM 专用**配权写面（标 sensitive，走审批）。
 *
 * <p>★ **取代 {@code sd.SetViewScope}**（用户 2026-09-22 裁定③）：旧工具配的是 GM **绝对指定**的可见集合；本工具配的是 **额外限制**——与
 * app 层范围函数现算的结果做交集（{@code narrowTo}）⇒ GM 只能额外收紧。
 *
 * <p>★ **{@code NAME} 必须是字面量**（与 ga 侧 handler 的 {@code type()} 一样）：两侧各有一条**派生式同源判据**按源码字面量扫描 ——
 * 窄写工具集合 == GM 桶的窄写工具集合（{@code SimosToolsTest}）、catalog 的 type 集合 == 全部 handler 的 {@code
 * type()}。它们才是把三处名字钉在一起的东西（写成常量引用会让扫描器当场判"抽不到"）。
 */
public final class SetDecisionMakerAccessTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "ga.SetDecisionMakerAccess";

  public SetDecisionMakerAccessTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
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
    return "GM 配权：固定 ga.SetDecisionMakerAccess，载荷 {decisionMakerId, allowedTools?, "
        + "accessLimit{命名空间:[前缀…]}?, redactedFields?, adjudicationDisclosure?}";
  }

  @Override
  public ResourceManifest resources() {
    return GA_NAMESPACE_WRITE;
  }

  /**
   * ★ P0 资源对齐（2026-10-15 批 N3 回代码核，原文写 {@code sd.*} / {@code SdChangeSet} / {@code sd:*} 三处均与事实不符）：
   * 本工具钉死的 {@code ga.SetDecisionMakerAccess} 命令产 {@code GaChangeSet}（{@code
   * SetDecisionMakerAccessHandler}，写 {@code decisionMakers} 组件），实际只写 ga 命名空间（{@code
   * ToolResources.GA_NAMESPACE = "ga"}，2026-10-14 批次 F 由 {@code sd} 改名）⇒ 写断言取 {@code ga:*}（{@link
   * #gaNamespaceWriteResources()}，GM 侧 ga 是 unlimited），不再沿用基类的三命名空间粗断言。
   */
  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return gaNamespaceWriteResources();
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}

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
 * ★★ {@code ga.SetDecisionMakerPeer} 窄工具（2026-10-14 决策人互致消息，设计书 §4.1）：**GM 专用**的"授权两人互通" 写面（标
 * sensitive，走审批——配权是 GM 的活，与 {@code ga.SetDecisionMakerAccess} 同待遇）。
 *
 * <p>★ <b>一次授权 = 双向可通</b>：命令侧一次写两条 DM 记录、一条 revision（见 {@code SetDecisionMakerPeerHandler} 的类注）⇒
 * 不存在"半开"态。本工具只是那条命令的薄封装（载荷原样透传）。
 *
 * <p>★ <b>{@code NAME} 必须是字面量</b>（与 sd 侧 handler 的 {@code type()} 一样）：两侧各有一条**派生式同源判据**按源码字面量扫描
 * ——窄写工具集合 == GM 桶的窄写工具集合（{@code SimosToolsTest}）、catalog 的 type 集合 == 全部 handler 的 {@code
 * type()}。写成常量引用会让扫描器当场判"抽不到"。
 *
 * <p>★ <b>它进的是 GM 桶</b>（{@code roles()={GM}}）：决策人不得给自己开闸。
 */
public final class SetDecisionMakerPeerTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "ga.SetDecisionMakerPeer";

  public SetDecisionMakerPeerTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "GM 授权决策人互致消息 branch="
        + args.get("branch")
        + " expected="
        + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 授权两个决策人互致消息：固定 ga.SetDecisionMakerPeer，载荷 {dmA, dmB, allowed}（三者全必填；"
        + "allowed 必须是布尔）。★ 一次调用**同时写两条**决策人记录、落**一条 revision** ⇒ **双向**授权（不存在"
        + "\"A 能发 B、B 回不了\"的半开态）；allowed=false 关闸（两侧各删去对方）。"
        + "授权后两人的可达面各出现 peer:<对方> ⇒ 可用 simos.ga.message.send 发、simos.ga.message.inbox 收（每 tick 每方向最多 "
        + GaMessageSendTool.MAX_MESSAGES_PER_TICK_PER_DIRECTION
        + " 条）。★ 本命令只动 accessLimit 的 peer 前缀表，不新建存储、不新建切片。";
  }

  @Override
  public ResourceManifest resources() {
    return GA_NAMESPACE_WRITE;
  }

  /**
   * ★ 本工具钉死的 {@code ga.SetDecisionMakerPeer} 只产 {@code GaChangeSet}（实际写 sd 命名空间的决策人表）⇒ 写断言取 {@code
   * sd:*}（GM 侧 unlimited），与 {@code ga.SetDecisionMakerAccess} 逐字同款。
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

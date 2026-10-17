package io.mosire.simos.army.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.army.spi.SetArmyContactWatchHandler;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★★ {@code simos.army.contactWatch}（2026-10-13 急停协议，口径 2 = (a) 的最小形态）：<b>GM 注册"这两军相遇时报警"</b>的窄写工具。
 *
 * <p>用户原话：「目前我能想到的其实是 Unit 模块，例如 Unit 报两个单位接触，<b>且之前 GM 要求在两个单位接触的时候报警</b>，那么就停下，
 * <b>这些条件之类都是可自定义、具体条件交给对应模块具体维护的</b>」＋「监控职能甚至应该包给 Army 模块」⇒ 注册位住 {@code Army.contactWatch}（世界状态，随
 * revision 落盘），设置走本工具的唯一命令 {@code army.SetArmyContactWatch}。
 *
 * <p>★ <b>命令类型固定</b>（{@link SetArmyContactWatchHandler#TYPE}），模型的输入只有 {@code payloadJson} + {@code
 * branch} + {@code expectedRevision}（{@code {"armyId":"a1","contactWatch":true}}）。工具名不是命令类型 ⇒ 不进
 * catalog / {@code PAYLOAD_HINTS}。
 *
 * <p>★ <b>判据不在本工具里</b>：它只设位；"相遇"怎么算、什么时候停由 {@code ArmyContacts} / {@code ArmyTimeParticipant} 与
 * core 的急停协议负责（"触发器本身只管是否被触发，不管怎么触发怎么监控的"）。
 *
 * <p>★ <b>只在 GM 桶</b>（不迁入 {@code DmUsableCommand}）：监控注册是 GM 的活。★ <b>写面只声明 {@code army} 命名空间</b>
 * （{@link #ARMY_NAMESPACE_WRITE}），GM 侧 {@code army} 在 {@code Shell#gmPermissionSet} 显式 {@code
 * unlimited()}。
 */
public final class SetArmyContactWatchTool extends AbstractNarrowWriteTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.army.contactWatch";

  public SetArmyContactWatchTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String commandType() {
    return SetArmyContactWatchHandler.TYPE;
  }

  @Override
  public ResourceManifest resources() {
    return ARMY_NAMESPACE_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return armyNamespaceWriteResources();
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "两军相遇报警注册 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 注册/取消\"该军与他军相遇时报警\"（固定命令 army.SetArmyContactWatch）：入参 payloadJson 载荷 "
        + "{\"armyId\":\"a1\",\"contactWatch\":true|false}（外加 branch / expectedRevision）。"
        + "armyId 必须已存在（不存在 ⇒ 具名拒）；contactWatch 必须显式给布尔值（没有缺省：缺键/非布尔 ⇒ 具名拒）。"
        + "设为 true 后，只要该军与另一军在同一格或相邻格（**逐日**判定，同一天内成立即算），推进就会报突发事件"
        + " army.contact 并**停在那一天**；未启用的军对相遇不报（P4）。"
        + "★ 重复设同一个值 = 一条不变 revision（仍返回 committed）。★ 该位是**世界状态**（随 revision 落盘）——"
        + "重启后仍然有效；要核对当前值可读 store 里的 army 切片（GUI /api/state 的 army 模块）。";
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}

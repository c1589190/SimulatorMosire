package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.core.CoreSimos;
import java.util.List;
import java.util.Map;

/**
 * {@code sd.PutInfo} 窄工具（M3，spec §六/§八.3）：**写 sd 侧 INFO** 的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 写的是**感知层**（供 UI / AAR 展示），ground truth 仍由领域模块持有；地址非法 / {@code key} 空白 / {@code value}
 * 缺失都在命令期被拒、理由原文到达调用方。
 */
public final class SdPutInfoTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.PutInfo";

  public SdPutInfoTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "写 Info branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "写 Info：固定 sd.PutInfo，载荷 {address, key, value, note?, id?, tags[决策人 id…]?,"
        + " affiliations[{\"kind\":\"nation\"|\"army\",\"id\":…}…]?, tick?}"
        + "（address/key/value 必填；★ value 是裸值，只保证标量往返，结构化值读回不保证逐字段相等"
        + "（要存结构化内容请自行序列化成 JSON 字符串）；"
        + "★ tags 与 affiliations 是决策文档可见性的两轴，**取并集**（命中其一即可见、都空 = 谁都不给看）；"
        + "★ tick 缺省 = 世界当前 tick，记在未来会被拒）";
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

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
 * {@code ga.PutInfo} 窄工具（M3，spec §六/§八.3）：**写 ga 侧 INFO** 的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 写的是**感知层**（供 UI / AAR 展示），ground truth 仍由领域模块持有；地址非法 / {@code key} 空白 / {@code value}
 * 缺失都在命令期被拒、理由原文到达调用方。
 */
public final class PutInfoTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "ga.PutInfo";

  public PutInfoTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
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
    return "写 Info：固定 ga.PutInfo，载荷 {address, key, value, note?, id?, tags[决策人 id…]?,"
        + " affiliations[{\"kind\":\"nation\"|\"army\",\"id\":…}…]?, tick?}"
        + "（address/key/value 必填；★ value 是裸值，只保证标量往返，结构化值读回不保证逐字段相等"
        + "（要存结构化内容请自行序列化成 JSON 字符串）；"
        + "★ tags 与 affiliations 是决策文档可见性的两轴，**取并集**（命中其一即可见、都空 = 谁都不给看）；"
        + "★ tick 缺省 = 世界当前 tick，记在未来会被拒）";
  }

  @Override
  public ResourceManifest resources() {
    return GA_NAMESPACE_WRITE;
  }

  /**
   * ★ P0 资源对齐（2026-10-15 批 N3 回代码核，原文写 {@code sd.*} / {@code SdChangeSet} / {@code sd:*} 三处均与事实不符）：
   * 本工具钉死的 {@code ga.PutInfo} 命令产 {@code GaChangeSet}（{@code PutInfoHandler}，写 {@code info}
   * 组件），实际只写 ga 命名空间（{@code ToolResources.GA_NAMESPACE = "ga"}，2026-10-14 批次 F 由 {@code sd} 改名）⇒
   * 写断言取 {@code ga:*}（{@link #gaNamespaceWriteResources()}，GM 侧 ga 是 unlimited），不再沿用基类的三命名空间粗断言。
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

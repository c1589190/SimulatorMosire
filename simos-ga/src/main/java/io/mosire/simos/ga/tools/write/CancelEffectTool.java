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
 * {@code ga.CancelEffect} 窄工具（M3，spec §八.3）：**取消效果**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 只有**可取消状态**（{@code PLANNED} / {@code COMMITTED}）的效果才允许；已触发 / 已取消 / 已过期的被域层拒。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：工具名与命令类型 {@code sd.CancelEffect → ga.CancelEffect}</b>（同 {@link
 * RegisterEffectTool} 的类注：用户裁定「sd 整个弃用！」＋"命令必须与切片同名"）。★ 类名保持 {@code CancelEffectTool}（留待批次 3e 随 sd
 * 空壳一起正名）。
 *
 * <p>★★ <b>已知债已还（2026-10-14 批次 F）</b>：本工具的资源声明原写 {@code sd} 命名空间而实际写 ga（与 3c §E.1 / 3d-1 §D.1 同族）——
 * 批次 F 把资源命名空间 {@code sd} → {@code ga} 正名（{@link #resources()} = {@code GA_NAMESPACE_WRITE} /
 * {@link #writeResources} = {@code gaNamespaceWriteResources()}，两处同源、一起改）⇒ 该债不再存在；理由与影响见 {@link
 * RegisterEffectTool} 的类注。
 */
public final class CancelEffectTool extends AbstractNarrowWriteTool implements SimosTool {

  /** ★ 本批起 = 命令类型（{@code ga.CancelEffect}）。 */
  public static final String NAME = "ga.CancelEffect";

  public CancelEffectTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "取消效果 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "取消效果：固定 ga.CancelEffect，载荷 {effectId}（★ 只有可取消状态的效果才允许）";
  }

  @Override
  public ResourceManifest resources() {
    return GA_NAMESPACE_WRITE;
  }

  /**
   * ★ P0 资源对齐（**已知债已还：2026-10-14 批次 F**，见类注；2026-10-15 批 N3 回代码核，原文反向谎称「写断言仍取 {@code sd:*}」）：
   * 本工具钉死的 {@code ga.CancelEffect} 命令产 {@code GaChangeSet}（{@code CancelEffectHandler}，写 {@code
   * effects} 组件）；写断言取 {@code ga:*}（{@link #gaNamespaceWriteResources()}，GM 侧 ga 是 unlimited）。
   */
  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return gaNamespaceWriteResources();
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}

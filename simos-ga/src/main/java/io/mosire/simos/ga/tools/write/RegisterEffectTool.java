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
 * {@code ga.RegisterEffect} 窄工具（M3，spec §八.3）：**登记效果（ECA 规则）**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ {@code trigger} / {@code action} 引用的实体**在命令期解析**，解析不到即拒（含 {@code action} 引用的命令 不在白名单）；{@code
 * createdTick} 缺省 = **当前 tick**，回执区分不出「我给的」与「引擎填的」。
 *
 * <p>★★ <b>2026-10-12 批次 3b-4：工具名与命令类型 {@code sd.RegisterEffect → ga.RegisterEffect}</b>（用户裁定「sd
 * 整个弃用！」；{@code effects} 表搬进 {@code GaState}，命令必须与切片同名——见 {@code GaCodec} 类注）。★ 类名保持 {@code
 * RegisterEffectTool}（工具名不是命令类型，改名它要连带动 app 的装配面与用例；留待批次 3e 随 sd 空壳一起正名）。
 *
 * <p>★★ <b>已知债已还（2026-10-14 批次 F）</b>：本工具的资源声明原写 {@code sd} 命名空间（与 3c §E.1 / 3d-1 §D.1 同族）， 而这条命令的
 * handler 自 3b-4 起只产 {@code GaChangeSet}（**实际写 ga 命名空间**）—— 批次 F 把资源命名空间 {@code sd} → {@code ga}
 * 正名（{@link #resources()} = {@code GA_NAMESPACE_WRITE} / {@link #writeResources} = {@code
 * gaNamespaceWriteResources()}，两处同源、一起改），声明面与断言面**同时**落到 {@code ga}， 该债不再存在（3d-1 §D.1
 * 记过：两者不同步时失效方向是"该拒的放行"）。
 */
public final class RegisterEffectTool extends AbstractNarrowWriteTool implements SimosTool {

  /** ★ 本批起 = 命令类型（{@code ga.RegisterEffect}）：类型前缀决定 {@code CommandBus} 把变更集喂给哪个 codec。 */
  public static final String NAME = "ga.RegisterEffect";

  public RegisterEffectTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
    super(writes, identity, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "登记效果 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "登记效果：固定 ga.RegisterEffect，载荷 {effectId, kind, trigger, action, createdTick?}（前四者必填；★ createdTick 缺省 = 当前 tick）";
  }

  @Override
  public ResourceManifest resources() {
    return GA_NAMESPACE_WRITE;
  }

  /** ★ P0 资源对齐（**2026-10-14 批次 F 已还债**，见类注）：写断言取 {@code ga:*}；与声明面同源，本命令实际写 ga 命名空间。 */
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

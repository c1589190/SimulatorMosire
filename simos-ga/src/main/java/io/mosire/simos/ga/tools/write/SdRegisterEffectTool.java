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
 * SdRegisterEffectTool}（工具名不是命令类型，改名它要连带动 app 的装配面与用例；留待批次 3e 随 sd 空壳一起正名）。
 *
 * <p>★★ <b>已知债（如实记，与 3c §E.1 / 3d-1 §D.1 同族）</b>：本工具的资源声明仍写 {@code sd} 命名空间 （{@link #resources()} =
 * {@code SD_NAMESPACE_WRITE}、{@link #writeResources} = {@code sdNamespaceWriteResources()}）， 而这条命令的
 * handler 自本批起只产 {@code GaChangeSet}（**实际写 ga 命名空间**）。★ 运行时影响为零：本工具只在 GM 桶， GM 侧 {@code sd} 与
 * {@code ga} 的写表态**相同**（{@code Shell#gmPermissionSet} 对逐命名空间 unlimited）， 且断言只决定"放不放行"、不决定命令落哪个切片；DM
 * 侧两条命令本就不可用。★ 不本批正名的理由：新增 {@code GA_NAMESPACE} 要动 {@code simos-ga-spi/ToolResources}（本批禁碰），且"资源声明面
 * + 断言面"要一起改（3d-1 §D.1 记过： 两者不同步时失效方向是"该拒的放行"）⇒ **留给批次 5（gov 命令面）一并收口**。
 */
public final class SdRegisterEffectTool extends AbstractNarrowWriteTool implements SimosTool {

  /** ★ 本批起 = 命令类型（{@code ga.RegisterEffect}）：类型前缀决定 {@code CommandBus} 把变更集喂给哪个 codec。 */
  public static final String NAME = "ga.RegisterEffect";

  public SdRegisterEffectTool(ToolWritePort writes, GaToolIdentity identity, String mapId) {
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
    return SD_NAMESPACE_WRITE;
  }

  /** ★ P0 资源对齐（**本批起为已知债**，见类注）：写断言仍取 {@code sd:*}；本命令实际写 ga 命名空间（GM 侧 unlimited ⇒ 放行结果不变）。 */
  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return sdNamespaceWriteResources();
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> roles() {
    return Set.of(ToolRole.GM);
  }
}

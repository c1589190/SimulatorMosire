package io.mosire.simos.unit.tools.write;

import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.Map;
import java.util.Set;

/**
 * {@code unit.UpdateCommandChain} 窄工具（M2，spec §八.3）：**改命令链**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★★ **三个字段全缺是合法的**（载荷层面不拒），但**仍会落一条 revision**——"没改任何东西"不等于"什么都没 发生"，时间线上会多一格。这条写进 {@link
 * #description()}，免得调用方把空改当成 cheap 的读操作。
 *
 * <p>★ 链不存在 ⇒ 域层拒；改了 `members` 后 `commander` 必须仍在 `members` 内（否则拒，理由带**可执行的下一步**）。
 */
public final class UnitUpdateCommandChainTool extends AbstractNarrowWriteTool implements SimosTool {

  public static final String NAME = "unit.UpdateCommandChain";

  public UnitUpdateCommandChainTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "改命令链 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "改命令链：固定 unit.UpdateCommandChain，载荷 {chainId, name?, commander?, members?}（★ 三者全缺是合法的，但会落一条 revision）";
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}

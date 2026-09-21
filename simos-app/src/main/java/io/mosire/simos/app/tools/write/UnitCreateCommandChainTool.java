package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.CreateCommandChain} 窄工具（M2，spec §八.3）：**建命令链**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 链**没有时刻**（全标量，命令表里也没有 `at`）：成立即可用，不参与时间推进。`chainId` 已存在 ⇒ 域层拒； `commander` 必须是 `members`
 * 之一、且 `members` 非空、不含 null、每个成员都解析得到 ⇒ 否则逐条可读拒绝。
 */
public final class UnitCreateCommandChainTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.CreateCommandChain";

  public UnitCreateCommandChainTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "建命令链 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "建命令链：固定 unit.CreateCommandChain，载荷 {chainId, name, commander, members[]}";
  }
}

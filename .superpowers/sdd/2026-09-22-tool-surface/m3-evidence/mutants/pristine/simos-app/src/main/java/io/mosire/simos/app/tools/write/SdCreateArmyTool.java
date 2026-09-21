package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code sd.CreateArmy} 窄工具（M3，spec §八.3）：**建军**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 三条前置（id 已存在 / {@code nationId} 不存在 / {@code rootUnitId} 不存在）都在**域层**判、逐条有可读文案——工具层不重复校验（同上）。
 */
public final class SdCreateArmyTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.CreateArmy";

  public SdCreateArmyTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "建军 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "建军：固定 sd.CreateArmy，载荷 {armyId, nationId, rootUnitId, name}（四者全必填；★ nationId 与 rootUnitId 指向不存在者即被拒）";
  }
}

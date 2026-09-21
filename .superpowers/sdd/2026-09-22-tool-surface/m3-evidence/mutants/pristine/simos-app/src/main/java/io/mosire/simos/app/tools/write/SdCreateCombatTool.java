package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code sd.CreateCombat} 窄工具（M3，spec §八.3）：**建交战**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 只建交战本身（阶段表为空）：首个 {@code sd.AddCombatStage} 才建对应的战斗状态。 {@code participants} 缺省即空集 ⇒
 * **可建出零参与者交战**。
 */
public final class SdCreateCombatTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.CreateCombat";

  public SdCreateCombatTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "建交战 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "建交战：固定 sd.CreateCombat，载荷 {combatId, name, participants?}（前两者必填；★ participants 缺省即空集，可建出零参与者交战）";
  }
}

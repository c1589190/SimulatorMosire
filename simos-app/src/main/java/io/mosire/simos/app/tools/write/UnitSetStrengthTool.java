package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.SetStrength} 窄工具（M2，spec §八.3）：**设定单位兵力**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **{@code member} 与 {@code equipment} 都是整份替换**（与 {@code unit.ApplyCasualties}
 * 的"增量"是两种语义，别混用）； 两者越界（{@code member} 为负、装备值为负）由域层构造期拒。
 */
public final class UnitSetStrengthTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.SetStrength";

  public UnitSetStrengthTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设定单位兵力 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "设定单位兵力：固定 unit.SetStrength，载荷 {id, member, equipment}";
  }
}

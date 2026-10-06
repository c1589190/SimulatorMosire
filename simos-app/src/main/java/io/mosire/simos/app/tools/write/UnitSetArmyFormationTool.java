package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.SetArmyFormation} 窄工具（阶段 10a）：**立 Army 编制**的唯一窄写面。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 经 MCP
 * 调用时走审批门链。
 *
 * <p>★ <b>载荷</b>：{@code {unitId, masterGov?, role}}；{@code role} 必填非空白，{@code masterGov} 可缺省（未认主子），
 * 给了就必须存在且带 {@code GovernmentFormation}。既有 GovernmentFormation ⇒ 具名拒（一单位一标签，不静默替换）。拒绝理由由域层给、经
 * {@code ToolSupport.fold} 变成可读的 {@code REJECTED}。
 */
public final class UnitSetArmyFormationTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.SetArmyFormation";

  public UnitSetArmyFormationTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "立 Army 编制 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "立/改 Army 编制：固定 unit.SetArmyFormation，载荷 {unitId, masterGov?, role,"
        + " householdDuties?[{household,kind(SOLDIER|NCO|OFFICER|COMMANDER),appointment,commandOf?}]}"
        + "（★ S3b：householdDuties 是以 HouseholdId 为键的军官/军职家户具名配置（家户必须在本单位 households 里）；"
        + "缺省 = 保持既有配置（不是清空）；role 必填非空白；masterGov 缺省 = 未认主子，给了必须存在且带 GovernmentFormation；"
        + "既有 GovernmentFormation ⇒ 具名拒，一单位至多一个编制标签、不静默替换；"
        + "同类型重复设置 = 整体替换（role/masterGov 一起换））";
  }
}

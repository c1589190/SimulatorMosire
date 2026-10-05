package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.SetGovFormation} 窄工具（阶段 10a）：**立 GOV 编制**的唯一窄写面。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 经 MCP
 * 调用时走审批门链。
 *
 * <p>★ <b>载荷</b>：{@code {unitId, level(CENTRAL|PROVINCE), superiorGov?, staff?, policy?}}；{@code
 * staff} 缺省空表、 {@code policy} 缺省 {@code OfficePolicy.defaults()}（也可给部分字段，缺省取 defaults）。既有
 * ArmyFormation ⇒ 具名拒 （一单位一标签，不静默替换）；{@code superiorGov} 必须存在、是 GOV、且不得指向自身。拒绝理由由域层给、经 {@code
 * ToolSupport.fold} 变成可读的 {@code REJECTED}。
 */
public final class UnitSetGovFormationTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.SetGovFormation";

  public UnitSetGovFormationTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "立 GOV 编制 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "立/改 GOV 编制：固定 unit.SetGovFormation，载荷 {unitId, level(CENTRAL|PROVINCE),"
        + " superiorGov?, householdPosts?[{household,role,level,head?}],"
        + " staff?{SCRIBE|YAMEN|POST:整数}, policy?{grainPerStaffPerTick?,"
        + " clothPerStaffPerCycle?, moneyPerStaffPerTick?, retirementPerStaff?, staffCap?{角色:整数}}}"
        + "（★ 2026-10-09 唯一列表裁定：载荷没有 households 键——域层立编制时把政府家户 hh-gov-<unitId> 编入"
        + " Unit.households，其余家户先走 unit.SetUnitHouseholds（GOV 单位须保留该政府家户）；"
        + "★ S3b：householdPosts 是以 HouseholdId 为键的领导层家户具名配置（家户必须在本单位 households 里）；"
        + "householdPosts 缺省 = 保持既有（不是清空）；staff 缺省空表、"
        + "policy 缺省 OfficePolicy.defaults() 且可给部分字段；householdPosts 非空时 staff 只是家户人口投影的兼容字段；"
        + "既有 ArmyFormation ⇒ 具名拒，一单位至多一个编制标签、不静默替换；"
        + "superiorGov 必须存在且带 GovFormation、不得指向自身；同类型重复设置 = 整体替换）";
  }
}

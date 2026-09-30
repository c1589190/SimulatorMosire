package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.SetGovPolicy} 窄工具（阶段 10b-i，2026-10-01）：<b>改 GOV 编制政策</b>的唯一 GM 窄写面。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 经 MCP
 * 调用时走审批门链。
 *
 * <p>★ <b>部分覆盖</b>：四个数值与 {@code staffCap} 未给 ⇒ 保持原值；{@code staffCap} 给 {@code {}} ⇒ 清空上限。四个数值与上限值 ≥
 * 0 由 {@code OfficePolicy} 构造期拒；单位必须是 GOV（否则 {@code unit.SetGovFormation} 指路）。
 *
 * <p>★ 工具层不做前置校验（那份校验能被 {@code simos.command.submit} 绕过 ⇒ 是装饰），拒绝理由由域层给、经 {@code ToolSupport.fold}
 * 变成可读的 {@code REJECTED}。
 */
public final class UnitSetGovPolicyTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.SetGovPolicy";

  public UnitSetGovPolicyTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设 GOV 政策 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "设/改 GOV 编制政策：固定 unit.SetGovPolicy，载荷 {unitId, grainPerStaffPerTick?,"
        + " clothPerStaffPerCycle?, moneyPerStaffPerTick?, retirementPerStaff?,"
        + " staffCap?{SCRIBE|YAMEN|POST:整数}}"
        + "（★ 部分覆盖：未给字段保持原值；staffCap 给 {} = 清空上限、缺省保持原表；"
        + "四个数值与上限值必须 ≥0，否则 OfficePolicy 构造期具名拒；单位必须是 GOV）";
  }
}

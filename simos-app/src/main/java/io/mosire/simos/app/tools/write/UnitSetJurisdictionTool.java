package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.SetJurisdiction} 窄工具（辖区阶段 5 / 计划 §2.2）：**设/改单位管辖**的唯一窄写面。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 经 MCP
 * 调用时走审批门链。
 *
 * <p>★ <b>regions 必填、可空数组 = 撤销全部管辖</b>；每个 regionId 必须在当前地图里存在（域层具名拒，不静默丢）；未给的可选字段保持原值 （单位原本无管辖 ⇒ 用
 * 0）。
 *
 * <p>★ 工具层不做前置校验（那份校验能被 {@code simos.command.submit} 绕过 ⇒ 是装饰），拒绝理由由域层给、经 {@code ToolSupport.fold}
 * 变成可读的 {@code REJECTED}。
 */
public final class UnitSetJurisdictionTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.SetJurisdiction";

  public UnitSetJurisdictionTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设管辖区域 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "设/改单位管辖：固定 unit.SetJurisdiction，载荷 {unitId, regions[regionId...]"
        + "（必填；空数组 = 撤销全部管辖）, levyGrainCapPerCommand?, levyMoneyCapPerCommand?,"
        + " levyManpowerCapPerCommand?, administrationPerMille?(0..1000)}"
        + "（★ 每个 regionId 必须在当前地图里存在，否则具名拒；未给的可选字段保持原值；"
        + "三个 levy*CapPerCommand 的上限 = **一条**抽取命令的上限，0 = 该类无额度、拒，本批不建周期累计账本）";
  }
}

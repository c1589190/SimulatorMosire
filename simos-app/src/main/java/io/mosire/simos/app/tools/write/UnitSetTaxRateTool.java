package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.SetTaxRate} 窄工具（辖区阶段 5 / 计划 §2.2）：**改某管辖区域长期税率**的唯一窄写面。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 经 MCP
 * 调用时走审批门链。
 *
 * <p>★ <b>regionId 必须已经在该单位的管辖里</b>（否则具名拒、指路先 {@code unit.SetJurisdiction}）；{@code ratePerMille}
 * 必须在 {@code [0,1000]}（否则具名拒，不钳制）。
 *
 * <p>★ 工具层不做前置校验（那份校验能被 {@code simos.command.submit} 绕过 ⇒ 是装饰），拒绝理由由域层给、经 {@code ToolSupport.fold}
 * 变成可读的 {@code REJECTED}。
 */
public final class UnitSetTaxRateTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.SetTaxRate";

  public UnitSetTaxRateTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设管辖税率 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "改管辖税率：固定 unit.SetTaxRate，载荷 {unitId, regionId, ratePerMille(0..1000)}"
        + "（★ regionId 必须已在该单位的管辖里，否则具名拒并指路先 unit.SetJurisdiction）";
  }
}

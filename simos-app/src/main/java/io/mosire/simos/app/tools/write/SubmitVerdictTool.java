package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/** {@code sd.SubmitVerdict} 窄工具（spec §八.3，N9）：裁决者冻结判决的**唯一**写面。 */
public final class SubmitVerdictTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.SubmitVerdict";

  public SubmitVerdictTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "提交判决 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "裁决者冻结判决：固定 sd.SubmitVerdict，载荷 {verdictId, breakpoint, subject, payload, meta}";
  }
}

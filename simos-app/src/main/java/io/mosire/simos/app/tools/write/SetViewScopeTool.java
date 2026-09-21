package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/** {@code sd.SetViewScope} 窄工具（spec §八.3，N9/N11）：**GM 专用**配权写面（标 sensitive，走审批）。 */
public final class SetViewScopeTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.SetViewScope";

  public SetViewScopeTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "GM 配权 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "GM 配权：固定 sd.SetViewScope，载荷 {decisionMakerId, viewScope}";
  }
}

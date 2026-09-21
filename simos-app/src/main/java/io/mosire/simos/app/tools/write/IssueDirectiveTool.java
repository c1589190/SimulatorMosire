package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/** {@code sd.IssueDirective} 窄工具（spec §八.3，N9）：决策人出令的**唯一**写面。 */
public final class IssueDirectiveTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.IssueDirective";

  public IssueDirectiveTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "决策出令 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "决策人出令：固定 sd.IssueDirective，载荷 {directiveId, decisionMakerId, tick, target?, intentInfo, commands[], effects[]?}";
  }
}

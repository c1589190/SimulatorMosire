package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.CreateUnit} 窄工具（M2，spec §八.3）：**新建单位**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ **{@code status?} 有缺省**（缺 ⇒ {@code MOVING}）：它与"给对 {@code MOVING}"在 revision 上无法区分。同 id 已存在 ⇒
 * 域层拒（`单位 id 已存在: <id>`）；**重名不拒**。
 */
public final class UnitCreateTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.CreateUnit";

  public UnitCreateTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "新建单位 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "新建单位：固定 unit.CreateUnit，载荷 {id, name, position{q,r}?, member, equipment, speed,"
        + " mobilityPerMille, parent?, status?（缺省 MOVING）}（★ 省略 position ⇒ 无自身位置、跟随父，此时须给 parent）";
  }
}

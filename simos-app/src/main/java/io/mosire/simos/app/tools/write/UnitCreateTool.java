package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.unit.spi.CreateUnitHandler;
import java.util.Map;

/**
 * {@code unit.CreateUnit} 窄工具（M2，spec §八.3）：**新建单位**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ <b>D3a 表形状</b>：{@code manpower}/{@code equipment} 都是 `[{type,amount}]` 有序条目数组（空数组合法）；
 * 一个单位可拥有多种人力/装备。旧 {@code member:int}/{@code equipment:Map} 载荷按 D-011 删除，不留兼容。
 *
 * <p>★ **{@code status?} 有缺省**（缺 ⇒ {@code MOVING}）：它与"给对 {@code MOVING}"在 revision 上无法区分。同 id 已存在 ⇒
 * 域层拒（`单位 id 已存在: <id>`）；**重名不拒**。
 */
public final class UnitCreateTool extends AbstractNarrowWriteTool {

  public static final String NAME = CreateUnitHandler.TYPE;

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
    return "新建单位：固定 unit.CreateUnit，载荷 {id, name, position{q,r}?, households?[家户id],"
        + " equipment[{type,amount}], speed, mobilityPerMille, parent?, status?（缺省 MOVING）}"
        + "（★ S3b：manpower 已退役——人员由 Social 家户承载，非空 manpower 具名拒；equipment 必填、空数组合法；"
        + "households 给的 Social 家户必须存在，位置不一致时由下一轮推进的 Unit↔Social 自动同步对齐到 UNIT(本单位)；"
        + "省略 position ⇒ 无自身位置、跟随父，此时须给 parent）";
  }
}

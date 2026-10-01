package io.mosire.simos.app.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.army.spi.RecordCombatHandler;
import io.mosire.simos.core.CoreSimos;
import java.util.List;
import java.util.Map;

/**
 * {@code simos.army.recordCombat} 窄工具（阶段 D1 / 用户设计 D-012，2026-10-02）：**写一条单 tick 单场交战记录**的唯一 GM
 * 窄写面。
 *
 * <p>★ <b>命令类型固定</b>（{@code army.RecordCombat}），模型的输入只有 {@code payloadJson} + {@code branch} +
 * {@code expectedRevision}。★ <b>工具名不是命令类型</b>：与 {@code simos.unit.set-state-description} 同款（覆写
 * {@link #toolName()}）；它**不进** catalog / {@code PAYLOAD_HINTS}（那两处认的是命令类型 {@code
 * army.RecordCombat}）。
 *
 * <p>★ <b>校验交域层</b>：tick 不得记在未来、同 id 不覆盖、参与单位至少一个且不重复、损失量 ≥ 0 等判据全在 handler / {@code
 * CombatRecord}；本工具只做信封组装。
 *
 * <p>★ <b>只在 GM 桶</b>：{@code army.RecordCombat} 标了 {@code GmOnlyCommand}（裁定战果是 GM 的活，决策人不得凭空写战果），
 * 故本工具也只在 {@code SimosToolSource.addGmWrites} 注册。★ <b>写面只声明 {@code army} 命名空间</b>（{@link
 * #ARMY_NAMESPACE_WRITE}），GM 侧 {@code army} 在 {@code Shell#gmPermissionSet} 显式 {@code unlimited()}。
 */
public final class ArmyRecordCombatTool extends AbstractNarrowWriteTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.army.recordCombat";

  public ArmyRecordCombatTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String commandType() {
    return RecordCombatHandler.TYPE;
  }

  @Override
  public ResourceManifest resources() {
    return ARMY_NAMESPACE_WRITE;
  }

  @Override
  protected List<ResourceId> writeResources(ToolContext context) {
    return armyNamespaceWriteResources();
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "记录交战 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "写一条单 tick 单场交战记录：固定 army.RecordCombat，载荷 {id, tick?, hex{q,r}, participants[unitId...],"
        + " text, losses?{自然语义键:非负整数}}——tick 缺省 = 当前 tick、不得记在未来；同 id 已存在 ⇒ 具名拒"
        + "（交战记录是不可变历史）；participants 至少一个且不重复；本阶段只写 army 切片，不替代 sd 战斗命令。";
  }
}

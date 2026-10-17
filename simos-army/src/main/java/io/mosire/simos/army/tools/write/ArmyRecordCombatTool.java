package io.mosire.simos.army.tools.write;

import io.mosire.agentlib.permission.ResourceId;
import io.mosire.agentlib.permission.ResourceManifest;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.simos.army.spi.RecordCombatHandler;
import io.mosire.simos.ga.spi.SimosTool;
import io.mosire.simos.ga.spi.ToolRole;
import io.mosire.simos.ga.spi.ToolWritePort;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>★ <b>只在 GM 桶</b>：{@code army.RecordCombat} 不打 {@code DmUsableCommand}（裁定战果是 GM 的活，决策人不得凭空写战果），
 * 故本工具也只在 {@code SimosToolSource.addGmWrites} 注册。★ <b>写面只声明 {@code army} 命名空间</b>（{@link
 * #ARMY_NAMESPACE_WRITE}），GM 侧 {@code army} 在 {@code Shell#gmPermissionSet} 显式 {@code unlimited()}。
 *
 * <p>★ <b>2026-10-12 B3 第二域下放</b>：本类自 {@code app/tools/write} 迁入 {@code simos-army}，对 {@code
 * CoreSimos} 的直连换成构造期注入的 {@link ToolWritePort}（适配器在组合根）；工具名、载荷、拒绝码、资源声明逐字不变。
 */
public final class ArmyRecordCombatTool extends AbstractNarrowWriteTool implements SimosTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.army.recordCombat";

  public ArmyRecordCombatTool(ToolWritePort writes, String mapId) {
    super(writes, mapId);
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
    return "写一条单 tick 单场交战记录：固定 army.RecordCombat，载荷 {id, kind(自定义交战状态，如野战/轰城), tick?,"
        + " hex{q,r}, participants[unitId...], text, initialStage?{id,name,participants?,text,outcomes?[{id,label,"
        + "weight,losses?[{unit,manpower?[{type,amount(有符号)}],equipment?[]}]}]}}——tick 缺省 = 当前 tick、不得记在未来；"
        + "同 id 已存在 ⇒ 具名拒（记录 id 一次性；阶段追加走 army.AppendCombatStage、判定走 army.ResolveCombatStage）；"
        + "participants 至少一个且不重复；weight 必须 > 0、阶段/结局 id 不得重复；旧 D1 的 losses{键:值} 字段已删除（D-011）。";
  }

  /** ★ 「这个工具能被谁用」的唯一拼写点（设计书 §4.4 / 不变量 I-G3）；装配期与两桶逐条比对，不一致当场抛。 */
  @Override
  public Set<ToolRole> toolRoles() {
    return Set.of(ToolRole.GM);
  }
}

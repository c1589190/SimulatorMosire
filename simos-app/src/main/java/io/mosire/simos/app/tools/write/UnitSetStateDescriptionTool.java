package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.unit.spi.SetStateDescriptionHandler;
import java.util.Map;

/**
 * {@code simos.unit.set-state-description} 窄工具（阶段 D1 / 用户设计 D-012，2026-10-02）：**设定/清除单位当前回合状态对应的
 * 状态描述地址**的唯一 GM 窄写面。
 *
 * <p>★ <b>命令类型固定</b>（{@code unit.SetStateDescription}），模型的输入只有 {@code payloadJson} + {@code branch}
 * + {@code expectedRevision}——"选什么命令"不是模型可自由发挥的面（与既有窄工具同制）。★ <b>工具名不是命令类型</b>：用户给定名为 {@code
 * simos.unit.set-state-description}，故覆写 {@link #toolName()}；它**不进** catalog / {@code PAYLOAD_HINTS}
 * （那两处认的是命令类型 {@code unit.SetStateDescription}）。
 *
 * <p>★ <b>校验交域层</b>（铁律 2 的同一条纪律）：本工具只把载荷折成信封，{@code id}/{@code state} 非空白、地址 canonical 形态等判据全在
 * {@code unit.SetStateDescription} 的 handler / {@code Unit} 构造期——工具层复写一份就是**装饰**（{@code
 * simos.command.submit} 那条路绕得过去）。
 *
 * <p>★ <b>只在 GM 桶</b>（{@code SimosToolSource.addGmWrites}）：命令本身非 GmOnly（D-012
 * 的机制是通用的、政府模块后续要用），决策人仍可 经 {@code sd.IssueDirective} 审批链写；本窄工具是 GM 的直通口。
 *
 * <p>★ 标 sensitive ⇒ 走审批门链（GM 侧 {@code GmAutoApproveGate} 无脑过，与其余窄写同制）。
 */
public final class UnitSetStateDescriptionTool extends AbstractNarrowWriteTool {

  /** 工具名（全局唯一；用户给定）。 */
  public static final String NAME = "simos.unit.set-state-description";

  public UnitSetStateDescriptionTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String toolName() {
    return NAME;
  }

  @Override
  protected String commandType() {
    return SetStateDescriptionHandler.TYPE;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设定单位状态描述地址 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "设定单位当前回合状态对应的状态描述地址：固定 unit.SetStateDescription，载荷 {id, state, address}——"
        + "id/state 必填非空白；address 缺省/null/空串 = 清除该状态链接，非空 = canonical 地址文本。"
        + "地址目标域对本命令不透明（unit 只记链接）。";
  }
}

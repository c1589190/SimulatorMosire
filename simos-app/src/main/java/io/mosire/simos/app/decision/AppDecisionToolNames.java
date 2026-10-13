package io.mosire.simos.app.decision;

import io.mosire.simos.app.tools.read.BranchListTool;
import io.mosire.simos.app.tools.read.CatalogTool;
import io.mosire.simos.app.tools.read.TimelineRevisionsTool;
import io.mosire.simos.ga.decision.DecisionToolNames;
import io.mosire.simos.ga.tools.write.IssueDirectiveTool;
import io.mosire.simos.ga.tools.write.MyPacketTool;
import io.mosire.simos.ga.tools.write.PacketIntentTool;
import io.mosire.simos.ga.tools.write.ProposeCallTool;
import io.mosire.simos.ga.tools.write.SubmitPacketTool;
import io.mosire.simos.ga.tools.write.SubmitVerdictTool;
import io.mosire.simos.map.tools.read.MapRenderTool;

/**
 * **app 侧交给运行流的工具名声明**（{@link DecisionToolNames} 的实现）：这是"app 只负责组装"的接线点之一。
 *
 * <p>★★ <b>每个名字都取自工具类自己的 {@code NAME} 常量</b>（不在这里抄字面量）：工具改名时这里跟着走， 运行流的冻结分类、提交拦截与开场消息不会**静默**错位。
 *
 * <p>★★ <b>为什么是 app 给</b>（设计书 §4.5 I-G2）：这些工具现在住在 {@code simos-app}（B3 才按域下放）， 而运行流住在 {@code
 * simos-ga}——{@code ga} 依赖 {@code app} 会成环，故由本类实现契约、{@code ga} 按契约取名字。
 *
 * <p>★ {@link #INSTANCE} 无状态、可共享；测试与生产用同一份（同一组名字 ⇒ 同一套回合语义）。
 */
public final class AppDecisionToolNames implements DecisionToolNames {

  /** 唯一实例（无状态）。 */
  public static final AppDecisionToolNames INSTANCE = new AppDecisionToolNames();

  private AppDecisionToolNames() {}

  @Override
  public String catalog() {
    return CatalogTool.NAME;
  }

  @Override
  public String branches() {
    return BranchListTool.NAME;
  }

  @Override
  public String timelineRevisions() {
    return TimelineRevisionsTool.NAME;
  }

  @Override
  public String propose() {
    return ProposeCallTool.NAME;
  }

  @Override
  public String packetIntent() {
    return PacketIntentTool.NAME;
  }

  @Override
  public String packetSubmit() {
    return SubmitPacketTool.NAME;
  }

  @Override
  public String myPacket() {
    return MyPacketTool.NAME;
  }

  @Override
  public String issueDirective() {
    return IssueDirectiveTool.NAME;
  }

  @Override
  public String submitVerdict() {
    return SubmitVerdictTool.NAME;
  }

  @Override
  public String visionConfigKey() {
    return MapRenderTool.VISION_CONFIG_KEY;
  }
}

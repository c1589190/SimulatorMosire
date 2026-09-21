package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.SetRejoinTarget} 窄工具（M2，spec §八.3）：**设回归目标**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 持久事实**只是这条引用**（`rejoinTarget`）：每个 tick 现算回归行程，不落"已归位"这类派生状态。 目标**不存在**或**是自身** ⇒ 域层拒。**状态不是
 * {@code MOVING} 时**本引用被忽略、不回归。
 *
 * <p>★★ **{@code target} 缺省或为 null = 清除回归意图**，**不是**"不动"：这条已写进 {@link #description()}， 让模型在调用前就看得见。
 */
public final class UnitSetRejoinTargetTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.SetRejoinTarget";

  public UnitSetRejoinTargetTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "设回归目标 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "设回归目标：固定 unit.SetRejoinTarget，载荷 {id, target?}（★ target 缺省或为 null = 清除回归意图）";
  }
}

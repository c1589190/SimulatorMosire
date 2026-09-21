package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code sd.CancelEffect} 窄工具（M3，spec §八.3）：**取消效果**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 只有**可取消状态**（{@code PLANNED} / {@code COMMITTED}）的效果才允许；已触发 / 已取消 / 已过期的被域层拒。
 */
public final class SdCancelEffectTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.CancelEffect";

  public SdCancelEffectTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "取消效果 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "取消效果：固定 sd.CancelEffect，载荷 {effectId}（★ 只有可取消状态的效果才允许）";
  }
}

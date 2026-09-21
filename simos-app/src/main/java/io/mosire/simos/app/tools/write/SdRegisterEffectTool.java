package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code sd.RegisterEffect} 窄工具（M3，spec §八.3）：**登记效果（ECA 规则）**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ {@code trigger} / {@code action} 引用的实体**在命令期解析**，解析不到即拒（含 {@code action} 引用的命令 不在白名单）；{@code
 * createdTick} 缺省 = **当前 tick**，回执区分不出「我给的」与「引擎填的」。
 */
public final class SdRegisterEffectTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.RegisterEffect";

  public SdRegisterEffectTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "登记效果 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "登记效果：固定 sd.RegisterEffect，载荷 {effectId, kind, trigger, action, createdTick?}（前四者必填；★ createdTick 缺省 = 当前 tick）";
  }
}

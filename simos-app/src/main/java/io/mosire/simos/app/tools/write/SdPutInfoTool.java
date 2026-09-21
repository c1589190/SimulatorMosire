package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code sd.PutInfo} 窄工具（M3，spec §六/§八.3）：**写 sd 侧 INFO** 的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 写的是**感知层**（供 UI / AAR 展示），ground truth 仍由领域模块持有；地址非法 / {@code key} 空白 / {@code value}
 * 缺失都在命令期被拒、理由原文到达调用方。
 */
public final class SdPutInfoTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.PutInfo";

  public SdPutInfoTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "写 Info branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "写 Info：固定 sd.PutInfo，载荷 {address, key, value, note?}（前三者必填；★ value 是裸值，只保证标量往返，结构化值读回不保证逐字段相等）";
  }
}

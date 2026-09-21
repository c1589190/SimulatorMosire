package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code sd.RecordCasualties} 窄工具（M3，spec §八.3）：**记战损**的唯一窄写面。
 *
 * <p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 上界由代码判（N3，绝不交给 AI）：{@code |Δ| <= 当前值}，越界**命令期拒绝**；**未知装备键被拒、不视作
 * 0**（拼错键不会被静默忽略成「没这条装备」）。{@code deltas[].personnel} / {@code equipment} 缺省分别取 0 / 空表 ⇒
 * **可写出零损失记录**。
 */
public final class SdRecordCasualtiesTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.RecordCasualties";

  public SdRecordCasualtiesTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "记战损 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "记战损：固定 sd.RecordCasualties，载荷 {combatId, stageId, deltas[{unit, lossClass, personnel?, equipment?}]}（deltas 必须非空；★ personnel 缺省 0、equipment 缺省空表 ⇒ 可写出零损失记录；★ 未知装备键会被拒，不视作 0）";
  }
}

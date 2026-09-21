package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * {@code unit.ApplyCasualties} 窄工具（M2，spec §八.3）：**施加战损**的唯一窄写面。
 *
 * <p>★ **进 GM 桶与决策人桶**（用户裁定 D-1）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 与 {@code unit.SetStrength}（整体重置兵力）是**两条不同的命令**：这里是**增量**，且必须是**负增量** （战损只减员，正增量拒）。绝对值落
 * revision ⇒ **时间线上可回退**。
 *
 * <p>★★ **{@code equipment} 必填**：只报人员战损也要显式给 {@code {}}（缺字段拒，不按 0 兜底）； **未知装备键会被拒**，**不视作
 * 0**（拼错键不会被静默忽略成"没这条装备"）。上界双向：`|Δ|` 不得超过当前值。 这些写进 {@link #description()}，让模型在调用前就看得见。
 */
public final class UnitApplyCasualtiesTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.ApplyCasualties";

  public UnitApplyCasualtiesTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "施加战损 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "施加战损：固定 unit.ApplyCasualties，载荷 {id, personnel（负增量）, equipment{键:负增量}}（★ equipment 必填——只报人员战损也要显式给 {}；未知装备键会被拒，不视作 0）";
  }
}

package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import io.mosire.simos.unit.spi.ApplyCasualtiesHandler;
import java.util.Map;

/**
 * {@code unit.ApplyCasualties} 窄工具（M2，spec §八.3；D3a 改为双轨 delta 有序条目列表）：**施加战损**的唯一窄写面。
 *
 * <p>★ **进 GM 桶**（与其余 unit 窄写同制）：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。
 *
 * <p>★ 与 {@code unit.SetComposition}（整表复写）是**两条不同的命令**：这里是**增量**，且必须是**负增量**（战损只减员， 正增量拒）。绝对值落
 * revision ⇒ **时间线上可回退**。
 *
 * <p>★★ **{@code manpower}/{@code equipment} 都必填**：只报人力战损也要显式给空装备数组 {@code []}（缺字段拒，不按 0 兜底）；**未知
 * type 会被拒**，**不视作 0**（拼错 type 不会被静默忽略成"没这项"）。上界双向：`|Δ|` 不得超过当前值。 这些写进 {@link
 * #description()}，让模型在调用前就看得见。
 */
public final class UnitApplyCasualtiesTool extends AbstractNarrowWriteTool {

  public static final String NAME = ApplyCasualtiesHandler.TYPE;

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
    return "施加装备战损：固定 unit.ApplyCasualties，载荷 {id, equipment[{type,amount≤0}]}"
        + "（★ S3b：manpower 已退役，非空 manpower 具名拒；人员战损要落 Social 家户命令）——只扣提及的 type、"
        + "未提及的保持不变；提及不存在的 type ⇒ 具名拒（不视作 0）；|Δ| ≤ 当前值。";
  }
}

package io.mosire.simos.economy.classfirst;

import io.mosire.simos.economy.api.id.ClassPoolId;
import io.mosire.simos.economy.api.id.ModeParticipationId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import java.util.Objects;

/**
 * 一个阶层池在某个生产方式里的参与记录。
 *
 * <p>身份 = {@code (modeId, classPositionId)} 的纯函数（{@link ModeParticipationId#idOf}）。{@code
 * participationSharePerMille} 是该池对本 mode 的参与份额（当前单 mode 单池恒 1000‰；保留维度给多 mode 并行的后续增量， 不改变单 mode
 * 语义）。
 *
 * @param id 稳定身份；必须等于 {@code ModeParticipationId.idOf(modeId.value(), classPositionId)}
 * @param modeId 生产方式 id
 * @param poolId 该位置对应的阶层池
 * @param classPositionId 阶层位置 id（{@link PilotModel} 的稳定字面量）
 * @param participationSharePerMille 参与份额（千分）
 */
public record ModeParticipation(
    ModeParticipationId id,
    ProductionModeId modeId,
    ClassPoolId poolId,
    String classPositionId,
    long participationSharePerMille) {

  public ModeParticipation {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(modeId, "modeId");
    Objects.requireNonNull(poolId, "poolId");
    if (classPositionId == null || classPositionId.isBlank()) {
      throw new IllegalArgumentException("ModeParticipation.classPositionId 不得为空白");
    }
    if (participationSharePerMille < 0L) {
      throw new IllegalArgumentException(
          "ModeParticipation.participationSharePerMille 必须 >= 0: " + participationSharePerMille);
    }
    ModeParticipationId derived = ModeParticipationId.idOf(modeId.value(), classPositionId);
    if (!id.equals(derived)) {
      throw new IllegalArgumentException(
          "ModeParticipation.id 必须由 (modeId, classPositionId) 确定性派生：id=" + id + "，派生=" + derived);
    }
  }
}

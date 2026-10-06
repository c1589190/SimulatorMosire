package io.mosire.simos.economy.model;

import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★ <b>一次模式变迁里一个家户的阶层保留份额</b>（理想架构 §2.9/§7.2；E6a）：{@code sharePerMille}‰ 表达 "该家户仍保留在 {@link
 * #classPositionId} 这个位置"。
 *
 * <p>★★ <b>同一 {@code (transitionId, householdId)} 的 Σ sharePerMille 必须 = 1000</b>（在 {@code
 * EconomyData} 构造期逐组判）。一次变迁把一个家户拆成"保留原所属 X‰ + 迁入新位置 (1000−X)‰"两条记录（0‰ 那一侧可省略）； 这与 {@link
 * HouseholdClassMembership#retainedShares()} 同源：standing 是"以家户为键的当前视图"，本表是"以变迁为键的历史明细"，
 * 两份数据的比例逐值相等（结算在同一处写出）。
 *
 * <p>★ <b>不复制实物</b>：本记录只表达阶层权利的保留比例，不拥有资产数量；资产份额仍按 industry 存在于 {@code OwnershipStake}，债务仍是家户间债权 ——
 * 模式变迁不按本比例拆分实物或债务。
 *
 * <p>★ <b>不可变</b>：record 组件全是不可变值（ID 与整数），无需额外冻结。
 *
 * @param id 稳定身份；由 {@link ClassShareId#idOf(ModeTransitionId, HouseholdId, ClassPositionId)} 派生
 * @param transitionId 所属模式变迁；不得为 null（键 == 值内 id 由 {@code EconomyData} 判）
 * @param householdId 家户稳定身份；不得为 null
 * @param classPositionId 该份额对应的阶层位置；不得为 null
 * @param sharePerMille 保留千分比；不得为负（同一对侧组的和必须是 1000）
 */
public record ClassShare(
    ClassShareId id,
    ModeTransitionId transitionId,
    HouseholdId householdId,
    ClassPositionId classPositionId,
    long sharePerMille) {

  public ClassShare {
    Objects.requireNonNull(id, "ClassShare.id 不得为 null");
    Objects.requireNonNull(transitionId, "ClassShare.transitionId 不得为 null");
    Objects.requireNonNull(householdId, "ClassShare.householdId 不得为 null");
    Objects.requireNonNull(classPositionId, "ClassShare.classPositionId 不得为 null");
    if (sharePerMille < 0L) {
      throw new IllegalArgumentException("ClassShare.sharePerMille 不得为负: " + sharePerMille);
    }
  }
}

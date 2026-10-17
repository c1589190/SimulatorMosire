package io.mosire.simos.production.contract;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Objects;

/**
 * ★★★ <b>一次"人口迁移/合户的经验携带与合并"的回执</b>（D-032 B3；I10 / §13.13.7 第 6 条）。
 *
 * <p>★ <b>为什么要有回执</b>：{@link ProductionRuntime#mergeExperience} 是<b>写</b>口（改 production 的 {@code
 * householdExperience} / {@code unlocks} 两个状态组件），调用方需要能对账"到底搬了什么" —— 条数、并入了
 * 几条解锁、这次调用是否真的产生了变化（{@code changed == false} ⇒ 本域变更集里不会有这一行的增量）。
 *
 * <p>★ <b>它是不可变事实，不是句柄</b>（§13.2 第 1 条③）：只带计数与参与方身份，不带任何可写内部对象。
 *
 * @param from 迁出户；不得为 null
 * @param to 迁入户；不得为 null
 * @param movedPeople 随迁人口（> 0）
 * @param targetPeople 迁入户原有口数（≥ 0）
 * @param tracksMerged 真正并入（结果非全零）的经验条数（≥ 0）
 * @param unlocksAdded 本次并入的**新**解锁条数（≥ 0；并集中原属迁入户的不计）
 * @param changed 本次调用是否改变了本域状态（false = 幂等空操作）
 */
public record ProductionExperienceMoveOutcome(
    HouseholdId from,
    long movedPeople,
    HouseholdId to,
    long targetPeople,
    int tracksMerged,
    int unlocksAdded,
    boolean changed) {

  public ProductionExperienceMoveOutcome {
    Objects.requireNonNull(from, "ProductionExperienceMoveOutcome.from 不得为 null");
    Objects.requireNonNull(to, "ProductionExperienceMoveOutcome.to 不得为 null");
    if (movedPeople <= 0L) {
      throw new IllegalArgumentException(
          "ProductionExperienceMoveOutcome.movedPeople 必须 > 0: " + movedPeople);
    }
    if (targetPeople < 0L) {
      throw new IllegalArgumentException(
          "ProductionExperienceMoveOutcome.targetPeople 不得为负: " + targetPeople);
    }
    if (tracksMerged < 0 || unlocksAdded < 0) {
      throw new IllegalArgumentException(
          "ProductionExperienceMoveOutcome 的计数不得为负: tracks="
              + tracksMerged
              + " unlocks="
              + unlocksAdded);
    }
  }
}

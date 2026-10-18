package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;

/** P0.5-E1 机械拆分自 {@code ModeMigrationSettlement}：迁移经验条目的 production 契约调用与日志。 */
final class MigrationExperienceMove {

  private MigrationExperienceMove() {}

  /**
   * 人口迁移完整携带各经验条（主值 + 余数、不乘人口比例），到迁入户后按人口加权平均合并。
   *
   * <p>本方法逐字搬自 {@code ModeMigrationSettlement.applySource} 的原内联块：唯一权威在 production（{@code
   * mergeExperience} 契约口；economy 只给人口事实，不碰经验账）。
   */
  static void mergeExperience(
      EconomySession session,
      HouseholdId source,
      long popTake,
      ModeMigrationPolicy.MigrationMove move,
      long targetPlannedPopulationBox,
      long day) {
    io.mosire.simos.production.contract.ProductionExperienceMoveOutcome experienceMove =
        session
            .productionRuntime()
            .mergeExperience(source, popTake, move.target(), targetPlannedPopulationBox);
    if (experienceMove.changed() && EconomyLog.migration().isDebugEnabled()) {
      EventLog.channel(EconomyLog.migration())
          .debug(
              LogEvent.of(
                  "MIGRATION_EXPERIENCE_MERGED",
                  EconomyLogSource.ECONOMY_MIGRATION,
                  "source",
                  source.value(),
                  "target",
                  move.target().value(),
                  "movedPeople",
                  popTake,
                  "targetPeople",
                  targetPlannedPopulationBox,
                  "tracksMerged",
                  experienceMove.tracksMerged(),
                  "unlocksAdded",
                  experienceMove.unlocksAdded(),
                  "day",
                  day,
                  "note",
                  "迁移完整携带（含余数、不乘比例）+ 合户按人口加权平均；解锁并集（I7）"));
    }
  }
}

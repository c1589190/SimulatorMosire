package io.mosire.simos.sd.spi;

import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatStageId;
import io.mosire.simos.sd.model.Combat;
import io.mosire.simos.sd.model.CombatStage;
import io.mosire.simos.sd.model.CombatState;
import io.mosire.simos.sd.state.SdState;

final class SdCombats {

  private SdCombats() {}

  static CombatStage stageOrNull(Combat combat, CombatStageId stageId) {
    for (CombatStage stage : combat.stages()) {
      if (stage.id().equals(stageId)) {
        return stage;
      }
    }
    return null;
  }

  static CombatState stateOrNull(SdState state, CombatId combatId) {
    for (CombatState combatState : state.combatStates().values()) {
      if (combatState.combatId().equals(combatId)) {
        return combatState;
      }
    }
    return null;
  }
}

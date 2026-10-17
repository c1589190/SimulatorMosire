package io.mosire.simos.economy.time;

import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Optional;

/**
 * ★★ <b>C2 的 fail-closed 属性桥适配器</b>（组合根未装配属性来源时）—— 永远返回 {@link Optional#empty()}。
 *
 * <p>★ 调用方不得把它读成 {@link ExternalAttributeFacts#EMPTY}；需要属性的角色条件因此 fail-closed。
 */
public final class UnavailableParticipantAttributeLookup implements ParticipantAttributeLookup {

  @Override
  public Optional<ExternalAttributeFacts> factsOf(HouseholdId household) {
    return Optional.empty();
  }
}

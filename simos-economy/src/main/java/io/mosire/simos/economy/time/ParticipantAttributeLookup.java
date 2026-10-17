package io.mosire.simos.economy.time;

import io.mosire.simos.production.contract.ExternalAttributeFacts;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Optional;

/**
 * ★★ <b>参与户外部属性事实的唯一读口</b>（D-032 D2 §6.2/§7.3）。
 *
 * <p>★★ <b>不可用与空的区分</b>：{@link Optional#empty()} 表示"属性桥未装配 / 该户没有事实"，调用方不得把它读成 {@link
 * ExternalAttributeFacts#EMPTY} 并让条件静默通过；角色条件需要属性而读不到 ⇒ 具名 {@code
 * role-qualification:<role>:<household>:attribute-missing}。返回的空 facts 才表示"该户确有权威事实且为空表"。
 *
 * <p>★ <b>只读</b>：本接口不写状态、不产生变更。
 */
public interface ParticipantAttributeLookup {

  /**
   * 读某参与户的外部属性事实。
   *
   * @param household 参与家户
   * @return 权威事实；未装配 / 缺该户 ⇒ empty
   */
  Optional<ExternalAttributeFacts> factsOf(HouseholdId household);
}

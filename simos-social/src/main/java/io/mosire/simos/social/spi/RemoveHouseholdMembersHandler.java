package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code social.RemoveHouseholdMembers} 命令的处理器（S3a，2026-10-09 / 架构 §4.1）：从指名批次扣人；扣到 0 删批次。
 *
 * <pre>{@code
 * {"householdId":"hh-1","lotId":"hh-1-m","count":12,"reason":"征兵"}
 * }</pre>
 *
 * <p>★ <b>语义</b>：只调 {@link HouseholdBook#removeMembers}（走 {@code GM_ADJUST} 负事件，可回放）；家户不存在、批次不属于该家户、
 * 扣减超量、{@code count ≤ 0} 都由域层具名拒。
 */
public final class RemoveHouseholdMembersHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点：Shell 注册、组合工具与 catalog 都从这里取/对齐）。 */
  public static final String TYPE = "social.RemoveHouseholdMembers";

  /** 2026-10-20：目标 = 家户 SocialData 当前位置（HEX ⇒ social / UNIT ⇒ unit）；家户查无 ⇒ 具名拒。 */
  @Override
  public List<CommandTarget> targetResources(
      String commandType, SimulationState state, String mapId, String payloadJson) {
    JsonNode payload = SocialPayloads.parse(payloadJson);
    HouseholdId id = SocialPayloads.requireHouseholdId(payload, "householdId");
    return List.of(HouseholdCommandTargets.currentTarget(state, id));
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    SocialPayloads.parse(payloadJson); // 坏载荷照样在判目标时抛（fail-closed），只是没有目标可给
    return List.of();
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = SocialPayloads.parse(payloadJson);
      HouseholdId id = SocialPayloads.requireHouseholdId(payload, "householdId");
      PeopleLotId lotId = PeopleLotId.parse(SocialPayloads.requireText(payload, "lotId"));
      long count = SocialPayloads.requireLong(payload, "count");
      String reason = SocialPayloads.requireReason(payload);
      SocialData next = HouseholdBook.removeMembers(base, id, lotId, count, reason);
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

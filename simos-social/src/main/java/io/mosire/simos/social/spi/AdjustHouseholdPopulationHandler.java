package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
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
 * {@code social.AdjustHouseholdPopulation} 命令的处理器（S3a，2026-10-09 / 架构 §4.1）：GM 直调某个家户某个
 * {@code (性别, 年龄档)} 的人数。
 *
 * <pre>{@code
 * {"householdId":"hh-1","sex":"MALE","ageBracketId":"15-59","delta":30,"reason":"补录"}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code delta} 可正可负（负不得使批次人数 &lt; 0）；{@code delta == 0} 明令拒（没有可调整的人数，
 * 落一条空 revision 不是"成功"）；未知年龄档 id 由 {@link HouseholdBook#adjustPopulation} 经事件落账路径具名拒。
 *
 * <p>★ <b>语义</b>：只调 {@link HouseholdBook#adjustPopulation}——走 {@code GM_ADJUST} 事件（可正可负、可回放），守恒检查在域层收口。
 */
public final class AdjustHouseholdPopulationHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点：Shell 注册、组合工具与 catalog 都从这里取/对齐）。 */
  public static final String TYPE = "social.AdjustHouseholdPopulation";

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
      Sex sex = SocialPayloads.requireSex(payload, "sex");
      String ageBracketId = SocialPayloads.requireText(payload, "ageBracketId");
      long delta = SocialPayloads.requireLong(payload, "delta");
      String reason = SocialPayloads.requireReason(payload);
      if (delta == 0L) {
        throw new IllegalArgumentException("delta 不得为 0（没有可调整的人数；空改动不落 revision）");
      }
      SocialData next = HouseholdBook.adjustPopulation(base, id, sex, ageBracketId, delta, reason);
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

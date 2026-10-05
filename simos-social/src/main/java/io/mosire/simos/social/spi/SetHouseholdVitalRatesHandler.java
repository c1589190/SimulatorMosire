package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code social.SetHouseholdVitalRates} 命令的处理器（S3a，2026-10-09 / 架构 §4.1）：整体替换一个家户的出生/死亡率<b>覆盖表</b>。
 *
 * <pre>{@code
 * {"householdId":"hh-1",
 *  "rates":[{"bracketId":"15-59","sex":"FEMALE","birthRatePerMillionPerTick":18,"deathRatePerMillionPerTick":4},
 *           {"bracketId":"0-14","sex":"MALE","birthRatePerMillionPerTick":0,"deathRatePerMillionPerTick":12}],
 *  "reason":"设定率"}
 * }</pre>
 *
 * <p>★ <b>载荷语义</b>：{@code rates} 缺失 / JSON {@code null} ⇒ 空表（= 清空覆盖表，全部键回落全局默认）；两个率缺省 0
 * （0 是"这一档确实按 0 率结算"，不是"没有这一行"）；负数、重复 {@code (bracketId, sex)} 由
 * {@link io.mosire.simos.social.api.population.HouseholdVitalRate} / {@link HouseholdVitalRates} 构造期具名拒。
 * ★ 单位是 <b>ppm/tick</b>。
 *
 * <p>★ <b>语义</b>：只调 {@link HouseholdBook#setVitalRates}——覆盖表本体进状态的 {@code Household.vitalRates}，另落一条
 * {@code RATE_SET} 审计事件；查找时家户覆盖优先、全局默认兜底，见
 * {@link io.mosire.simos.social.SocialData#findVitalRate(io.mosire.simos.social.api.id.HouseholdId,
 * io.mosire.simos.social.population.AgeBracket, io.mosire.simos.social.api.population.Sex)}。
 */
public final class SetHouseholdVitalRatesHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点：Shell 注册、组合工具与 catalog 都从这里取/对齐）。 */
  public static final String TYPE = "social.SetHouseholdVitalRates";

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
      HouseholdVitalRates rates =
          new HouseholdVitalRates(SocialPayloads.requireVitalRates(payload, "rates"));
      String reason = SocialPayloads.requireReason(payload);
      SocialData next = HouseholdBook.setVitalRates(base, id, rates, reason);
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

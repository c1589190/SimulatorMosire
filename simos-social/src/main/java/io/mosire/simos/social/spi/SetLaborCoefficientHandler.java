package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialLog;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.population.Sex;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.AgeBracket;
import io.mosire.simos.social.provisioning.LaborCoefficient;
import io.mosire.simos.social.provisioning.SocialProvisioningEdits;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * ★★ {@code social.SetLaborCoefficient} 命令的处理器（2026-10-09 家户结构修复计划 Batch 4）：改/清 social
 * 劳动系数表（全局默认或单家户覆盖），键为 {@code (年龄档, 性别)}，值为 {@code milliHoursPerTick}。
 *
 * <pre>{@code
 * {"householdId"?:"hh-1", "ageBracket":"0-14|15-59|60+", "sex":"MALE|FEMALE",
 *  "milliHoursPerTick"?:整数, "reason":"..."}
 * }</pre>
 *
 * <p>★★ <b>语义</b>（与 {@link SocialProvisioningEdits} 同源）：
 *
 * <ul>
 *   <li>{@code householdId} 缺席 = 改<b>全局默认</b>（必须给 {@code milliHoursPerTick}）；给了 =
 *       改该<b>家户覆盖</b>（家户必须存在）；
 *   <li>{@code milliHoursPerTick} 给了 = upsert 该 {@code (年龄档, 性别)}；缺席 = 删除该家户覆盖键并回落全局 （只允许
 *       householdId 在场；全局默认不允许删键 ⇒ 具名拒）；
 *   <li>结果只经 {@link SocialData#withProvisioning} 写回，产出 {@link SocialChangeSet}；改/清各 INFO 一条带
 *       scope/household/年龄档/性别/milliHoursPerTick 的事件，失败一律 {@link HandlerOutcome.Rejected} 带中文原因。
 * </ul>
 *
 * <p>★★ <b>GM-only</b>（实现 {@link GmOnlyCommand}）：仍注册到 Core、仍进 {@code commandTargets}、GM 直接提交与窄工具
 * 照常可用；但不得嵌入决策人令，也不进决策人目录——劳动系数是全世界/全户的调参面。
 *
 * <p>★ <b>旧档作废、不迁移</b>（用户 2026-10-09 裁定"一切从新"）：本命令只认带第 6 组件 {@code provisioning} 的新档； 旧档缺该组件在
 * {@link SocialData} / {@link SocialChangeSet} 构造期即具名拒，本命令不补默认值、不做双读。
 */
public final class SetLaborCoefficientHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点：Shell 注册、窄工具与 catalog 都从这里取/对齐）。 */
  public static final String TYPE = "social.SetLaborCoefficient";

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
      HouseholdId householdId = SocialPayloads.optionalHouseholdId(payload, "householdId");
      AgeBracket ageBracket = SocialPayloads.requireAgeBracket(payload, "ageBracket");
      Sex sex = SocialPayloads.requireSex(payload, "sex");
      Long milliHoursPerTick = SocialPayloads.optionalLong(payload, "milliHoursPerTick");
      String reason = SocialPayloads.requireReason(payload);
      if (milliHoursPerTick == null) {
        SocialData next = SocialProvisioningEdits.clearLabor(base, householdId, ageBracket, sex);
        LaborCoefficient removed =
            base.provisioning().householdLaborOverride(householdId, ageBracket, sex).orElse(null);
        SocialLog.provisioning()
            .info(
                "event=SOCIAL_LABOR_COEFFICIENT_CLEARED "
                    + SocialLog.kv(
                        "scope",
                        "household",
                        "household",
                        householdId,
                        "ageBracket",
                        ageBracket.key(),
                        "sex",
                        sex,
                        "milliHoursPerTick",
                        removed == null ? "none" : removed.milliHoursPerTick(),
                        "reason",
                        reason));
        return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
      }
      SocialData next =
          SocialProvisioningEdits.setLabor(base, householdId, ageBracket, sex, milliHoursPerTick);
      LaborCoefficient effective =
          householdId == null
              ? next.provisioning().globalLabor(ageBracket, sex).orElse(null)
              : next.provisioning()
                  .householdLaborOverride(householdId, ageBracket, sex)
                  .orElse(null);
      SocialLog.provisioning()
          .info(
              "event=SOCIAL_LABOR_COEFFICIENT_SET "
                  + SocialLog.kv(
                      "scope",
                      householdId == null ? "global" : "household",
                      "household",
                      householdId == null ? "-" : householdId,
                      "ageBracket",
                      ageBracket.key(),
                      "sex",
                      sex,
                      "milliHoursPerTick",
                      effective == null ? milliHoursPerTick : effective.milliHoursPerTick(),
                      "reason",
                      reason));
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      SocialLog.provisioning()
          .warn("event=SOCIAL_LABOR_COEFFICIENT_REJECTED reason={}", e.getMessage());
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

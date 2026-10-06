package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.population.SocialVitalRates;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code social.SetGlobalVitalRates} 命令的处理器（D4，2026-10-22）：整体替换 Social 的<b>全局默认出生/死亡率表</b>。
 *
 * <pre>{@code
 * {"rates":[{"bracketId":"15-59","sex":"FEMALE","birthRatePerMillionPerTick":667,
 *            "deathRatePerMillionPerTick":33}],
 *  "reason":"GM 调率"}
 * }</pre>
 *
 * <p>★★ <b>载荷语义与 {@code social.SetHouseholdVitalRates} 逐字同制</b>：{@code rates} 缺失 / JSON {@code
 * null} ⇒ 空表；两个率缺省 0（0 = 这一档确实按 0 率结算，不是"没有这一行"）；负数、重复 {@code (bracketId, sex)} 由 {@link
 * io.mosire.simos.social.api.population.HouseholdVitalRate} / {@link HouseholdVitalRates} 构造期具名拒。
 * 单位是 <b>ppm/tick</b>。
 *
 * <p>★ <b>语义</b>：只调 {@link SocialData#withVitalRates}——整体替换第七组件；家户覆盖不动（查找仍是家户覆盖优先、全局兜底）。
 *
 * <p>★★ <b>GM-only</b>（实现 {@link GmOnlyCommand}）：本命令仍注册 Core、仍进 {@code commandTargets}，GM 的窄工具
 * {@code simos.gm.vitalRates} 与 {@code simos.command.submit} 照常可用；但排除出令白名单 / RegisterEffect / 决策人目录
 * ——全局参数是 GM 的活（与 {@code social.SetDemandCoefficient} / {@code social.SetLaborCoefficient} 同族）。
 */
public final class SetGlobalVitalRatesHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点：Shell 注册、窄工具与 catalog 都从这里取/对齐）。 */
  public static final String TYPE = "social.SetGlobalVitalRates";

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    SocialPayloads.parse(payloadJson); // 坏载荷照样在判目标时抛（fail-closed），只是没有可声明的目标
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
      HouseholdVitalRates rates =
          new HouseholdVitalRates(SocialPayloads.requireVitalRates(payload, "rates"));
      // reason 形状必填（命令契约统一），语义只进审计；本 handler 与 SetHouseholdVitalRatesHandler 同制不落日志。
      SocialPayloads.requireReason(payload);
      SocialData next = base.withVitalRates(new SocialVitalRates(rates));
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

package io.mosire.simos.actor.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorLog;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.GoodsAccount;
import io.mosire.simos.actor.model.GoodsAccountKey;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ {@code actor.EnsureHouseholdAccount}（P2-C §13.7）：<b>给一个家户补一本零余额账户（幂等）</b> —— 让"新登记的政府家户"
 * 也能像普通家户一样持有账户 / 下单 / 被市场匹配。
 *
 * <pre>{@code
 * {"household":"hh-gov-u-central","reason":"gm:…"}
 * }</pre>
 *
 * <p>★★ <b>为什么需要它</b>：账户归 actor 切片，而政府家户可能在社会/经济侧先被创建（{@code social.CreateHousehold} + {@code
 * economy.RegisterGovernment}）。既有 {@code actor.AdjustAccounts} 只在<b>纯正增量</b>时新建账户（且那是"改余额"的语义，
 * 会去动发行审计）；而一个还没有国库余额的新政府家户需要的只是"存在一本空账"—— 否则推进装载账本时会 fail-closed（缺账不得当余额 0）。 本命令只创建一个五张表全空 {@link
 * GoodsAccount}，<b>不碰余额、不造钱、不写 actors/meta</b>。
 *
 * <p>★ <b>幂等</b>：账已存在 ⇒ 逐值不变（返回 {@code Applied(Unchanged)}），不覆盖任何余额/冻结。
 *
 * <p>★ <b>GM-only</b>：实现 {@link GmOnlyCommand} —— 它是给组合工具（{@code simos.gov.createOffice} / {@code
 * simos.map.province.apply} 的政府家户开户）用的裸原语；仍注册到 Core、GM 的 {@code simos.command.submit} 可调，
 * 但排除出决策令白名单。
 *
 * <p>★ <b>目标声明</b>：家户账户键不含格（P2-A §13.3），本命令没有 {@link io.mosire.simos.util.spi.ResourcePaths}
 * 可声明的格目标，故返回空列表（GM-only、不进决策令）。
 */
public final class EnsureHouseholdAccountHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "actor.EnsureHouseholdAccount";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    Objects.requireNonNull(payloadJson, "payloadJson");
    // 形状校验（缺 household / reason ⇒ 抛具名载荷错）；没有格资源目标可声明。
    JsonNode payload = ActorPayloads.parse(payloadJson);
    AccountPayloads.household(payload, "household");
    optionalReason(payload);
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ActorData base = ActorSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = ActorPayloads.parse(payloadJson);
      HouseholdId household = AccountPayloads.household(payload, "household");
      String reason = optionalReason(payload);
      GoodsAccountKey key = new GoodsAccountKey(household);
      ActorData projected = base;
      boolean created = false;
      if (!base.accounts().containsKey(key)) {
        Map<GoodsAccountKey, GoodsAccount> accounts = new LinkedHashMap<>(base.accounts());
        accounts.put(key, new GoodsAccount(key, Map.of(), Map.of(), Map.of(), Map.of()));
        projected = base.withAccounts(accounts);
        created = true;
      }
      if (created) {
        ActorLog.account()
            .info(
                "event=HOUSEHOLD_ACCOUNT_ENSURED household={} created={} reason={}",
                household.value(),
                true,
                reason);
      }
      return new HandlerOutcome.Applied(ActorChangeSet.between(base, projected));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 可选 {@code reason}：缺失/null ⇒ 默认审计文本；给了必须是非空白文本。 */
  private static String optionalReason(JsonNode payload) {
    JsonNode node = payload.get("reason");
    if (node == null || node.isNull()) {
      return "gm:" + TYPE;
    }
    if (!node.isTextual() || node.asText().isBlank()) {
      throw new IllegalArgumentException(TYPE + " 的 reason 必须是非空白文本: " + node);
    }
    return node.asText();
  }
}

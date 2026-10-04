package io.mosire.simos.actor.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.ops.AccountOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * ★★ {@code actor.TransferAccounts} 命令的处理器（P1.2 后端行政）：<b>任意两个 actor 账户之间的商品/货币原子转移</b>。
 *
 * <pre>{@code
 * {"from":{"owner":{"kind":"UNIT","id":"u-a"},"q":0,"r":0},
 *  "to":{"owner":{"kind":"UNIT","id":"u-b"},"q":1,"r":1},
 *  "goods":{"grain":100},"money":{"silver":10},
 *  "reason":"行政调拨"}
 * }</pre>
 *
 * <p>★ <b>源/目标语义</b>：{@code from} 与 {@code to} 各是一个 {@code (owner, 格)} 账键；来源账必须存在， 目标账缺失 ⇒
 * 以净转入量新建。转移量是<b>正数</b>（0 请删键）；两本账键相同 ⇒ 拒。
 *
 * <p>★★ <b>冻结语义</b>：源扣减只动可支配（余额 − 冻结，走 {@link io.mosire.simos.actor.model.AvailableStock}
 * 的唯一算法），两张冻结表原样保留；目标相加只加余额，冻结不动。于是 {@code 0 ≤ 冻结 ≤ 余额} 这条类型不变量不会被转移破坏。
 *
 * <p>★ <b>GM-only</b>：这是裸账目原语；受管辖/额度约束的路径应走组合工具或专属命令，不能让决策令随意动用任意账户。
 */
public final class TransferAccountsHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "actor.TransferAccounts";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = ActorPayloads.parse(payloadJson);
    AccountPayloads.AccountRef from = AccountPayloads.accountRef(payload.get("from"), "from");
    AccountPayloads.AccountRef to = AccountPayloads.accountRef(payload.get("to"), "to");
    return List.of(
        ResourcePaths.actor(from.at().q(), from.at().r()),
        ResourcePaths.actor(to.at().q(), to.at().r()));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ActorData base = ActorSnapshots.of(state).data();
    try {
      JsonNode payload = ActorPayloads.parse(payloadJson);
      AccountPayloads.AccountRef from = AccountPayloads.accountRef(payload.get("from"), "from");
      AccountPayloads.AccountRef to = AccountPayloads.accountRef(payload.get("to"), "to");
      var goods = AccountPayloads.positiveCommodities(payload, "goods");
      var money = AccountPayloads.positiveMoney(payload, "money");
      if (goods.isEmpty() && money.isEmpty()) {
        return new HandlerOutcome.Rejected(
            "actor.TransferAccounts 至少要转移一种商品或货币（goods/money 不得同时为空）");
      }
      ActorData next =
          AccountOperations.transfer(
              base, from.owner(), from.at(), to.owner(), to.at(), goods, money);
      return new HandlerOutcome.Applied(ActorChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

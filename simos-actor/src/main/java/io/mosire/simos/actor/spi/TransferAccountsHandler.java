package io.mosire.simos.actor.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.ops.AccountOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * ★★ {@code actor.TransferAccounts} 命令的处理器（P1.2 后端行政；P2-A §13.3 起账户主体只有家户）：
 * <b>两个家户账户之间的商品/货币原子转移</b>。
 *
 * <pre>{@code
 * {"from":{"household":"hh-0_0-rural-poor_peasant"},
 *  "to":{"household":"hh-1_0-urban-landlord"},
 *  "goods":{"grain":100},"money":{"silver":10},
 *  "reason":"行政调拨"}
 * }</pre>
 *
 * <p>★ <b>源/目标语义</b>：{@code from} 与 {@code to} 各是一个家户账户；来源账必须存在，目标账缺失 ⇒ 以净转入量新建。转移量是<b>正数</b>（0
 * 请删键）；两本账相同 ⇒ 拒。
 *
 * <p>★ <b>位置</b>：账户键不再带格（P2-A）—— 位置从 {@code Household.location} 派生，家户搬家账自动跟走。
 *
 * <p>★★ <b>冻结语义</b>：源扣减只动可支配（余额 − 冻结，走 {@link io.mosire.simos.actor.model.AvailableStock}
 * 的唯一算法），两张冻结表原样保留；目标相加只加余额，冻结不动。于是 {@code 0 ≤ 冻结 ≤ 余额} 这条类型不变量不会被转移破坏。
 *
 * <p>★ <b>GM-only</b>：这是裸账目原语；受管辖/额度约束的路径应走组合工具或专属命令，不能让决策令随意动用任意账户。
 *
 * <p>★★ <b>目标声明</b>：家户账户没有格 ⇒ 本命令返回空目标清单（{@link CommandTargets} 的"没有可寻址目标"档）。 它是 GM-only
 * 命令，不走决策令的资源围栏；这是 P2-A 的具名缺口（政府家户的采购/上缴路径在 P2-C 重建）。
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
    ActorPayloads.parse(payloadJson); // 载荷必须可解析；家户账户无格 ⇒ 无 hex 目标
    return List.of();
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
          AccountOperations.transfer(base, from.household(), to.household(), goods, money);
      return new HandlerOutcome.Applied(ActorChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}

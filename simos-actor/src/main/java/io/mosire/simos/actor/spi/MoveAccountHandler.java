package io.mosire.simos.actor.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.ops.AccountOperations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.spi.ResourcePaths;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * ★★ {@code actor.MoveAccount} 命令的处理器（P1.2 后端行政）：<b>按 owner 把整本账从一格搬到另一格</b>。
 *
 * <pre>{@code
 * {"owner":{"kind":"UNIT","id":"u-central"},
 *  "from":{"q":33,"r":-55},"to":{"q":35,"r":-60},
 *  "reason":"迁都：国库随中央政府走"}
 * }</pre>
 *
 * <p>★★ <b>源/目标位置语义（这是本命令最该说清的一条）</b>：
 *
 * <ul>
 *   <li>{@code owner} <b>不变</b>——搬的是同一本账（同一主人、换一个位置）。要换主人请用 {@code
 *       actor.TransferAccounts}；要让某主体在新格有一本账而不是搬空此格，也走转移。
 *   <li>{@code from} 指向的账必须存在；{@code to} 指向的账可以不存在——不存在 ⇒ 四张表（商品余额、货币余额、 商品冻结、货币冻结）<b>整本随账键搬过去</b>。
 *   <li>{@code to} 已存在 ⇒ 目标账保留其键与其余键，四张表<b>逐键精确相加</b>；任一处 {@code long} 溢出 ⇒ 整条拒绝，
 *       不做截断。源账随后删除；两处位置相同 ⇒ 拒（无操作，不产生空 revision）。
 * </ul>
 *
 * <p>★ <b>为什么冻结额随行</b>：冻结是"这本账上已被明确占用的部分"，位置移动不解除任何承诺；留在旧格会立刻变成无主冻结，
 * 而新格又凭空多出可支配量——两边都是错的。故整本随行、目标已有则相加。
 *
 * <p>★ <b>GM-only</b>：任意账户搬整本账是行政/迁移原语，不开放给决策令。
 */
public final class MoveAccountHandler implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "actor.MoveAccount";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = ActorPayloads.parse(payloadJson);
    HexCoord from = hex(payload, "from");
    HexCoord to = hex(payload, "to");
    return List.of(ResourcePaths.actor(from.q(), from.r()), ResourcePaths.actor(to.q(), to.r()));
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ActorData base = ActorSnapshots.of(state).data();
    try {
      JsonNode payload = ActorPayloads.parse(payloadJson);
      ActorRef owner = AccountPayloads.owner(payload, "owner");
      HexCoord from = hex(payload, "from");
      HexCoord to = hex(payload, "to");
      ActorData next = AccountOperations.move(base, owner, from, to);
      return new HandlerOutcome.Applied(ActorChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** from/to 是 {q,r} 对象。 */
  private static HexCoord hex(JsonNode payload, String field) {
    JsonNode node = payload.get(field);
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 {q,r} 对象: " + payload);
    }
    JsonNode q = node.get("q");
    JsonNode r = node.get("r");
    if (q == null
        || !q.isIntegralNumber()
        || !q.canConvertToInt()
        || r == null
        || !r.isIntegralNumber()
        || !r.canConvertToInt()) {
      throw new IllegalArgumentException("字段 " + field + " 必须有整数 q 与 r: " + node);
    }
    return new HexCoord(q.asInt(), r.asInt());
  }
}

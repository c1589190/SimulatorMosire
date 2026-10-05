package io.mosire.simos.actor.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorLog;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.ops.StockDeductionOperations;
import io.mosire.simos.economy.api.stock.HouseholdStockDeduction;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.GmOnlyCommand;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * ★★ {@code actor.DeductHouseholdStock}（P1.2，2026-10-09 用户裁定）：<b>通用家户库存扣除</b> —— 传入「家户 + 扣除的库存 +
 * 扣除理由」即可扣除；税收、行政俸禄、军队俸禄都只给政策与 reason，不再各写一套扣账机制。
 *
 * <pre>{@code
 * {"entries":[
 *   {"household":"hh-1","goods":{"grain":120},"money":{"silver":30},
 *    "reason":"jurisdiction_tax","detail":"unit=u-1 region=r1","toHousehold":"hh-gov-u-1"},
 *   {"household":"hh-2","money":{"silver":5},"reason":"admin_upkeep"}
 * ]}
 * }</pre>
 *
 * <p>★★ <b>载荷语义</b>：
 *
 * <ul>
 *   <li>{@code entries} 必填、非空数组；每项 {@code household} 与 {@code reason} 必填（reason = {@link
 *       io.mosire.simos.economy.api.stock.DeductionReason} 封闭词表的小写字面量）；
 *   <li>{@code goods} / {@code money} <b>至少一个非空</b>、键值都必须 <b>&gt; 0</b>（扣除量是正数；0 请删键）；
 *   <li>{@code detail} 可选（只进审计与日志，不参与判定）；
 *   <li>{@code toHousehold} 可选：<b>缺席 = 明确 sink</b>（付出即消失的支出，如行政俸禄）；给出 = 扣减与收款在同一批里
 *       <b>原子转移</b>（如税：家户 → 政府家户）。
 * </ul>
 *
 * <p>★★ <b>失败语义（整条具名拒、不允许部分生效）</b>：家户不存在 / 账户不存在 / 余额不足 / 侵占冻结 / reason 空白或不在词表 / goods+money 皆空 /
 * 请求量 ≤ 0 / 自转 / 收款相加溢出 —— 任一违例 ⇒ 整条 {@link HandlerOutcome.Rejected}，一个字节都不落。 ★
 * 条目<b>按载荷序</b>顺序应用（前一条的收款，后一条的扣减看得见）。
 *
 * <p>★★ <b>可用量走唯一算法</b>：{@code 可支配 = 余额 − 冻结}（{@code AvailableStock}）；扣减不侵占冻结、冻结表原样带过； 余额扣到 0
 * <b>保留</b>（0 是"现在手里是 0"，不是"没有这一条"）。
 *
 * <p>★ <b>为什么账户状态在 actor 切片</b>（P2-A §13.3）：{@code GoodsAccount} 是家户商品/货币的唯一真源；本命令只写 {@code
 * accounts} 一张表（{@code meta} / {@code actors} 一字不动），返回 {@link ActorChangeSet} —— 满足 Core 的单
 * namespace 约束。
 *
 * <p>★ <b>GM-only</b>：它是裸账目原语（与 {@code actor.AdjustAccounts} / {@code actor.TransferAccounts}
 * 同待遇）—— GM 的 {@code simos.command.submit} 与窄工具可调，但不进决策令白名单 / {@code sd.RegisterEffect}；受政策约束的税 /
 * 俸禄路径应由 app 侧结算调共享扣除服务（{@code StockDeductionService}），而不是让决策令直接动任意家户账。
 *
 * <p>★ <b>目标声明</b>：家户账户键不含格（P2-A）⇒ 本命令返回空目标清单（与 {@code actor.TransferAccounts} 同款）。
 */
public final class DeductHouseholdStockHandler
    implements CommandHandler, CommandTargets, GmOnlyCommand {

  /** 命令类型（唯一拼写点）。 */
  public static final String TYPE = "actor.DeductHouseholdStock";

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    ActorPayloads.deductions(ActorPayloads.parse(payloadJson)); // 载荷必须可解析；家户账户无格
    return List.of();
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ActorData base = ActorSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = ActorPayloads.parse(payloadJson);
      List<HouseholdStockDeduction> deductions = ActorPayloads.deductions(payload);
      ActorData next = StockDeductionOperations.deductAll(base, deductions);
      return new HandlerOutcome.Applied(ActorChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      ActorLog.account()
          .debug(
              "event=HOUSEHOLD_STOCK_DEDUCTION_REJECTED type={} reason={}", TYPE, e.getMessage());
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
